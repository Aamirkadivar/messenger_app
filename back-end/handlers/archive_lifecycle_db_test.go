package handlers

import (
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	"messenger-app/models"
)

// GATE 4 PHASE 0 - archive lifetime, against real Postgres.
//
// These prove the rule the design depends on:
//
//	an archive exists only while its message, its chat, and its owning user
//	exist, AND its owner is still an active participant of the chat.
//
// The first three are foreign keys, so they are the database's guarantee rather
// than a handler's; the fourth cannot be (left_at is an UPDATE) and is proven
// through the purge helper the departure handlers call.
//
// Requires a scratch database. NEVER point this at the live `messenger` DB:
// setup drops and recreates the tables it uses.
//
//	TEST_DATABASE_URL="host=localhost port=5432 user=postgres password=... dbname=messenger_e2ee_scratch sslmode=disable"

func archiveTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		// FAIL, do not skip. These are the only tests that exercise the real
		// schema, the real cascades, and the real optimistic-concurrency
		// statements; a skip made a run without a database look identical to a
		// run that proved all of it, which is how a green CI came to mean
		// nothing for this suite.
		//
		// Opting out has to be deliberate and visible in the invocation:
		//
		//	ALLOW_SKIP_DB_TESTS=1 go test ./handlers/
		if os.Getenv("ALLOW_SKIP_DB_TESTS") == "1" {
			t.Skip("TEST_DATABASE_URL not set and ALLOW_SKIP_DB_TESTS=1; " +
				"database-backed coverage was NOT executed")
		}
		t.Fatal("TEST_DATABASE_URL is not set. The database-backed suite cannot run, " +
			"and skipping it would report untested code as passing. Set it to the " +
			"scratch database, or pass ALLOW_SKIP_DB_TESTS=1 to opt out explicitly.")
	}
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		t.Fatalf("connect scratch db: %v", err)
	}
	// One never-closed pool per test exhausts Postgres under -count=N; see
	// closeWhenDone.
	closeWhenDone(t, db)
	return db
}

type archiveFixture struct {
	chatID    string
	userA     uuid.UUID
	userB     uuid.UUID
	messageID uuid.UUID
}

// seedArchive builds the minimal slice of schema the archive touches, with the
// same column types and cascades MigrateDB produces, then installs one chat with
// two participants and one message from A.
func seedArchive(t *testing.T, db *gorm.DB) archiveFixture {
	t.Helper()

	stmts := []string{
		`DROP TABLE IF EXISTS message_archives, message_deletions, messages, chat_participants, chats, users CASCADE`,
		`CREATE TABLE users (id UUID PRIMARY KEY)`,
		`CREATE TABLE chats (id TEXT PRIMARY KEY, type TEXT)`,
		// id is part of the real ChatParticipant model and GORM's First() orders
		// by the primary key, so the column has to exist here too.
		`CREATE TABLE chat_participants (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			chat_id UUID NOT NULL, user_id UUID NOT NULL, left_at TIMESTAMPTZ)`,
		`CREATE TABLE messages (
			id UUID PRIMARY KEY,
			sender_id UUID NOT NULL,
			chat_id TEXT NOT NULL,
			deleted_at TIMESTAMPTZ)`,
		`CREATE TABLE message_archives (
			message_id UUID NOT NULL,
			user_id UUID NOT NULL,
			chat_id TEXT NOT NULL,
			root_version INT NOT NULL,
			protocol_version INT NOT NULL DEFAULT 1,
			ciphertext BYTEA NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			PRIMARY KEY (message_id, user_id),
			CONSTRAINT fk_message_archives_message
				FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
			CONSTRAINT fk_message_archives_chat
				FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
			CONSTRAINT fk_message_archives_user
				FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)`,
	}
	for _, s := range stmts {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("seed: %v\n%s", err, s)
		}
	}

	f := archiveFixture{
		chatID:    uuid.New().String(),
		userA:     uuid.New(),
		userB:     uuid.New(),
		messageID: uuid.New(),
	}
	db.Exec(`INSERT INTO users (id) VALUES (?), (?)`, f.userA, f.userB)
	db.Exec(`INSERT INTO chats (id, type) VALUES (?, 'group')`, f.chatID)
	db.Exec(`INSERT INTO chat_participants (chat_id, user_id, left_at) VALUES (?, ?, NULL), (?, ?, NULL)`,
		f.chatID, f.userA, f.chatID, f.userB)
	db.Exec(`INSERT INTO messages (id, sender_id, chat_id) VALUES (?, ?, ?)`,
		f.messageID, f.userA, f.chatID)
	return f
}

func putArchive(t *testing.T, db *gorm.DB, f archiveFixture, user uuid.UUID, rootVersion int) {
	t.Helper()
	err := db.Exec(
		`INSERT INTO message_archives (message_id, user_id, chat_id, root_version, ciphertext)
		 VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
		f.messageID, user, f.chatID, rootVersion, []byte("sealed-opaque-bytes"),
	).Error
	if err != nil {
		t.Fatalf("insert archive: %v", err)
	}
}

func countArchives(t *testing.T, db *gorm.DB) int64 {
	t.Helper()
	var n int64
	if err := db.Raw(`SELECT COUNT(*) FROM message_archives`).Scan(&n).Error; err != nil {
		t.Fatalf("count: %v", err)
	}
	return n
}

// ---------------------------------------------------------------- cascades

// delete-for-everyone hard-deletes the message row; every user's archive of it
// must go with it, by cascade, atomically.
func TestArchive_DeleteForEveryone_RemovesEveryUsersArchive(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)
	putArchive(t, db, f, f.userA, 1)
	putArchive(t, db, f, f.userB, 1)
	if got := countArchives(t, db); got != 2 {
		t.Fatalf("setup: want 2 archives, got %d", got)
	}

	if err := db.Transaction(func(tx *gorm.DB) error {
		return tx.Exec(`DELETE FROM messages WHERE id = ?`, f.messageID).Error
	}); err != nil {
		t.Fatalf("delete message: %v", err)
	}

	if got := countArchives(t, db); got != 0 {
		t.Fatalf("archives survived delete-for-everyone: %d remain", got)
	}
}

// Group deletion hard-deletes the chats row. Message rows are only soft-deleted,
// so the message_id cascade cannot help - the chat_id cascade is what makes the
// archives unrecoverable.
func TestArchive_ChatDeletion_RemovesArchivesDespiteSoftDeletedMessages(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)
	putArchive(t, db, f, f.userA, 1)
	putArchive(t, db, f, f.userB, 1)

	// Exactly what DeleteGroup does: soft-delete messages, hard-delete the chat.
	if err := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec(`UPDATE messages SET deleted_at = ? WHERE chat_id = ?`,
			time.Now(), f.chatID).Error; err != nil {
			return err
		}
		return tx.Exec(`DELETE FROM chats WHERE id = ?`, f.chatID).Error
	}); err != nil {
		t.Fatalf("delete chat: %v", err)
	}

	var msgs int64
	db.Raw(`SELECT COUNT(*) FROM messages WHERE id = ?`, f.messageID).Scan(&msgs)
	if msgs != 1 {
		t.Fatalf("message semantics changed: soft-deleted row should remain, got %d", msgs)
	}
	if got := countArchives(t, db); got != 0 {
		t.Fatalf("archives survived chat deletion: %d remain", got)
	}
}

// The account-deletion lifecycle hook: archives are owned, and die with the
// owner, even though no account-deletion endpoint exists yet.
func TestArchive_UserDeletion_RemovesOnlyThatUsersArchives(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)
	putArchive(t, db, f, f.userA, 1)
	putArchive(t, db, f, f.userB, 1)

	if err := db.Exec(`DELETE FROM users WHERE id = ?`, f.userA).Error; err != nil {
		t.Fatalf("delete user: %v", err)
	}

	var remaining string
	if err := db.Raw(`SELECT user_id::text FROM message_archives`).Scan(&remaining).Error; err != nil {
		t.Fatalf("scan: %v", err)
	}
	if got := countArchives(t, db); got != 1 || remaining != f.userB.String() {
		t.Fatalf("want only userB's archive to survive, got %d rows (owner %v)", got, remaining)
	}
}

// ---------------------------------------------------------------- departure

// Leaving/deleting a chat is an UPDATE, so no cascade fires. The purge helper is
// what enforces lifetime rule (4), and it must be per-user.
func TestArchive_ParticipantDeparture_PurgesOnlyThatUser(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)
	putArchive(t, db, f, f.userA, 1)
	putArchive(t, db, f, f.userB, 1)

	if err := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec(`UPDATE chat_participants SET left_at = ? WHERE chat_id = ? AND user_id = ?`,
			time.Now(), f.chatID, f.userA).Error; err != nil {
			return err
		}
		return models.PurgeArchivesForParticipant(tx, f.chatID, f.userA)
	}); err != nil {
		t.Fatalf("departure tx: %v", err)
	}

	var owner string
	db.Raw(`SELECT user_id::text FROM message_archives`).Scan(&owner)
	if got := countArchives(t, db); got != 1 || owner != f.userB.String() {
		t.Fatalf("departure must purge only the leaver: %d rows, owner %v", got, owner)
	}
}

// delete-for-me records a per-user deletion without removing the message. Policy:
// the archive is NOT touched by the row insert itself - the client retracts its
// own local copy, and the server-side archive is removed only when the user
// actually leaves the chat or the message/chat/user is deleted.
func TestArchive_DeleteForMe_LeavesArchiveUntouched(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)
	putArchive(t, db, f, f.userA, 1)

	if err := db.Exec(`CREATE TABLE IF NOT EXISTS message_deletions (
		message_id UUID NOT NULL, user_id UUID NOT NULL, created_at TIMESTAMPTZ,
		PRIMARY KEY (message_id, user_id))`).Error; err != nil {
		t.Fatalf("create message_deletions: %v", err)
	}
	db.Exec(`INSERT INTO message_deletions (message_id, user_id, created_at)
		VALUES (?, ?, ?) ON CONFLICT DO NOTHING`, f.messageID, f.userA, time.Now())

	if got := countArchives(t, db); got != 1 {
		t.Fatalf("delete-for-me must not remove the archive: got %d", got)
	}
}

// ---------------------------------------------------------------- immutability

// Composite PK + ON CONFLICT DO NOTHING: an archive can be inserted or deleted,
// never replaced. This is what makes replay of an older ciphertext a no-op.
func TestArchive_DuplicateInsert_DoesNotReplace(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)
	putArchive(t, db, f, f.userA, 1)

	// A second upload for the same (message, user) with different content.
	if err := db.Exec(
		`INSERT INTO message_archives (message_id, user_id, chat_id, root_version, ciphertext)
		 VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
		f.messageID, f.userA, f.chatID, 99, []byte("attacker-supplied"),
	).Error; err != nil {
		t.Fatalf("second insert should be a silent no-op: %v", err)
	}

	var rootVersion int
	var ciphertext []byte
	db.Raw(`SELECT root_version, ciphertext FROM message_archives WHERE message_id = ? AND user_id = ?`,
		f.messageID, f.userA).Row().Scan(&rootVersion, &ciphertext)

	if got := countArchives(t, db); got != 1 {
		t.Fatalf("want exactly 1 row, got %d", got)
	}
	if rootVersion != 1 {
		t.Fatalf("original root_version was replaced: got %d", rootVersion)
	}
	if string(ciphertext) != "sealed-opaque-bytes" {
		t.Fatalf("original ciphertext was replaced: %q", ciphertext)
	}
}

// An archive can never be written for a message that does not exist - the FK
// refuses it, so this holds even if a handler forgets to check.
func TestArchive_ForNonexistentMessage_Rejected(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)

	err := db.Exec(
		`INSERT INTO message_archives (message_id, user_id, chat_id, root_version, ciphertext)
		 VALUES (?, ?, ?, ?, ?)`,
		uuid.New(), f.userA, f.chatID, 1, []byte("x"),
	).Error
	if err == nil {
		t.Fatal("insert for a nonexistent message must be rejected by the FK")
	}
	if got := countArchives(t, db); got != 0 {
		t.Fatalf("nothing should have been written, got %d", got)
	}
}

// ---------------------------------------------------------------- authorization

func TestArchive_Authorization(t *testing.T) {
	db := archiveTestDB(t)
	f := seedArchive(t, db)

	t.Run("member is authorized and the chat is derived from the message", func(t *testing.T) {
		scope, err := AuthorizeArchiveAccess(db, f.userA, f.messageID)
		if err != nil {
			t.Fatalf("member must be authorized: %v", err)
		}
		if scope.ChatID != f.chatID || scope.MessageID != f.messageID {
			t.Fatalf("scope must come from the message row: %+v", scope)
		}
	})

	t.Run("non-member is rejected even with a valid message id", func(t *testing.T) {
		outsider := uuid.New()
		db.Exec(`INSERT INTO users (id) VALUES (?)`, outsider)
		if _, err := AuthorizeArchiveAccess(db, outsider, f.messageID); err != ErrArchiveNotMember {
			t.Fatalf("want ErrArchiveNotMember, got %v", err)
		}
	})

	t.Run("departed member is rejected", func(t *testing.T) {
		db.Exec(`UPDATE chat_participants SET left_at = ? WHERE chat_id = ? AND user_id = ?`,
			time.Now(), f.chatID, f.userB)
		if _, err := AuthorizeArchiveAccess(db, f.userB, f.messageID); err != ErrArchiveNotMember {
			t.Fatalf("a user who left keeps no archive rights, got %v", err)
		}
	})

	t.Run("unauthenticated is rejected", func(t *testing.T) {
		if _, err := AuthorizeArchiveAccess(db, uuid.Nil, f.messageID); err != ErrArchiveNotMember {
			t.Fatalf("want rejection for nil user, got %v", err)
		}
	})

	t.Run("nonexistent message is rejected", func(t *testing.T) {
		if _, err := AuthorizeArchiveAccess(db, f.userA, uuid.New()); err != ErrArchiveNoSuchMessage {
			t.Fatalf("want ErrArchiveNoSuchMessage, got %v", err)
		}
	})

	t.Run("soft-deleted message is rejected", func(t *testing.T) {
		gone := uuid.New()
		db.Exec(`INSERT INTO messages (id, sender_id, chat_id, deleted_at) VALUES (?, ?, ?, ?)`,
			gone, f.userA, f.chatID, time.Now())
		if _, err := AuthorizeArchiveAccess(db, f.userA, gone); err != ErrArchiveNoSuchMessage {
			t.Fatalf("a deleted message must not be archivable, got %v", err)
		}
	})
}
