package handlers

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"messenger-app/database"
	"messenger-app/middleware"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// Database-backed tests for RecreateGroup.
//
// The decision logic is pinned in mls_recreate_test.go, but that mirrors the
// handler rather than exercising it - and it happily passed while the handler
// was emitting an INSERT that violated UNIQUE(chat_id) and reporting the failure
// as 404. These tests drive the real handler against a real Postgres so the SQL
// itself is under test.
//
// Requires a scratch database. NEVER point this at the live `messenger` DB: the
// setup truncates the tables it uses.
//
//	TEST_DATABASE_URL="host=localhost port=5432 user=postgres password=... dbname=messenger_e2ee_scratch sslmode=disable"

// closeWhenDone releases the connection pool the test just opened.
//
// Every DB test here calls gorm.Open for itself, and gorm.Open builds a fresh
// *sql.DB - a pool that keeps its connections idle and open forever. One pool
// per test is invisible in a single run and fatal in a repeated one: under
// `go test -count=20` the pools accumulate until Postgres answers
// "FATAL: sorry, too many clients already" and every remaining test fails on
// connect. That is a resource leak in the harness, not shared state between
// iterations, and it is entirely separate from anything the handlers do.
//
// Registered by the opener so no caller can forget it. Cleanups run LIFO, so a
// caller that afterwards swaps database.DB and registers its own restore still
// restores before this closes.
func closeWhenDone(t *testing.T, db *gorm.DB) {
	t.Helper()
	t.Cleanup(func() {
		sqlDB, err := db.DB()
		if err != nil {
			return
		}
		_ = sqlDB.Close()
	})
}

func recreateTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set; skipping database-backed RecreateGroup tests")
	}
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		t.Fatalf("connect scratch db: %v", err)
	}
	closeWhenDone(t, db)
	return db
}

// seedGroup builds the minimal schema the handler touches and installs one chat
// with one MLS group, one handshake and two Welcomes (one consumed).
func seedGroup(t *testing.T, db *gorm.DB) (chatID string, member uuid.UUID, instance uuid.UUID) {
	t.Helper()

	stmts := []string{
		`DROP TABLE IF EXISTS mls_welcomes, mls_handshakes, mls_groups, chat_participants`,
		`CREATE TABLE chat_participants (
			chat_id UUID NOT NULL, user_id UUID NOT NULL, left_at TIMESTAMPTZ)`,
		`CREATE TABLE mls_groups (
			id UUID PRIMARY KEY,
			chat_id VARCHAR(64) NOT NULL,
			group_id_data BYTEA NOT NULL,
			cipher_suite INT NOT NULL,
			epoch BIGINT NOT NULL DEFAULT 0,
			created_by UUID NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			group_info_data BYTEA,
			group_info_epoch BIGINT NOT NULL DEFAULT 0)`,
		// The constraint the original bug tripped over.
		`CREATE UNIQUE INDEX idx_mls_groups_chat_id ON mls_groups (chat_id)`,
		`CREATE TABLE mls_handshakes (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			chat_id VARCHAR(64) NOT NULL, epoch BIGINT NOT NULL, kind VARCHAR(16) NOT NULL,
			sender_user_id UUID NOT NULL, sender_device_id VARCHAR(128),
			payload BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
		`CREATE TABLE mls_welcomes (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			chat_id VARCHAR(64) NOT NULL,
			recipient_user_id UUID NOT NULL, recipient_device_id VARCHAR(128),
			sender_user_id UUID NOT NULL, epoch BIGINT NOT NULL,
			payload BYTEA NOT NULL, consumed_at TIMESTAMPTZ,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	}
	for _, s := range stmts {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("seed schema: %v", err)
		}
	}

	chatUUID := uuid.New()
	chatID = chatUUID.String()
	member = uuid.New()
	instance = uuid.New()

	if err := db.Exec(
		`INSERT INTO chat_participants (chat_id, user_id, left_at) VALUES (?, ?, NULL)`,
		chatUUID, member).Error; err != nil {
		t.Fatalf("seed participant: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO mls_groups (id, chat_id, group_id_data, cipher_suite, epoch, created_by, group_info_data, group_info_epoch)
		 VALUES (?, ?, ?, 1, 7, ?, ?, 7)`,
		instance, chatID, []byte(chatID), member, []byte("stale-group-info")).Error; err != nil {
		t.Fatalf("seed group: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO mls_handshakes (chat_id, epoch, kind, sender_user_id, payload)
		 VALUES (?, 7, 'commit', ?, ?)`, chatID, member, []byte("old-commit")).Error; err != nil {
		t.Fatalf("seed handshake: %v", err)
	}
	// One pending (must be cleared) and one consumed (must survive).
	if err := db.Exec(
		`INSERT INTO mls_welcomes (chat_id, recipient_user_id, sender_user_id, epoch, payload, consumed_at)
		 VALUES (?, ?, ?, 7, ?, NULL)`, chatID, member, member, []byte("pending")).Error; err != nil {
		t.Fatalf("seed pending welcome: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO mls_welcomes (chat_id, recipient_user_id, sender_user_id, epoch, payload, consumed_at)
		 VALUES (?, ?, ?, 7, ?, now())`, chatID, member, member, []byte("consumed")).Error; err != nil {
		t.Fatalf("seed consumed welcome: %v", err)
	}
	return chatID, member, instance
}

// recreateApp mounts the handler with an injected identity, mirroring how the
// real route sits behind AuthMiddleware.
func recreateApp(as uuid.UUID) *fiber.App {
	app := fiber.New()
	app.Post("/e2ee/mls/groups/:chat_id/recreate", func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: as})
		return (&MLSHandler{}).RecreateGroup(c)
	})
	return app
}

func postRecreate(t *testing.T, app *fiber.App, chatID string, body map[string]any) (int, map[string]any) {
	t.Helper()
	raw, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/e2ee/mls/groups/"+chatID+"/recreate", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, 10000)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(payload, &out)
	return resp.StatusCode, out
}

func newGid() string {
	g := uuid.New()
	return base64.StdEncoding.EncodeToString([]byte("gid-" + g.String()))
}

func TestRecreateGroupReplacesRowInPlace(t *testing.T) {
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	chatID, member, oldInstance := seedGroup(t, db)
	app := recreateApp(member)

	status, body := postRecreate(t, app, chatID, map[string]any{
		"group_id_b64":         newGid(),
		"cipher_suite":         1,
		"expected_instance_id": oldInstance.String(),
	})

	if status != http.StatusCreated {
		t.Fatalf("expected 201, got %d (%v)", status, body)
	}

	// Exactly one row for the chat - the original bug tried to insert a second.
	var rows int64
	db.Raw(`SELECT count(*) FROM mls_groups WHERE chat_id = ?`, chatID).Scan(&rows)
	if rows != 1 {
		t.Fatalf("expected exactly 1 group row for the chat, got %d", rows)
	}

	var got struct {
		ID             uuid.UUID
		GroupIDData    []byte
		Epoch          int64
		GroupInfoEpoch int64
		GroupInfoData  []byte
	}
	if err := db.Raw(
		`SELECT id, group_id_data, epoch, group_info_epoch, group_info_data FROM mls_groups WHERE chat_id = ?`,
		chatID).Scan(&got).Error; err != nil {
		t.Fatalf("read back: %v", err)
	}

	if got.ID == oldInstance {
		t.Error("instance_id must change - it is what identifies the incarnation")
	}
	if string(got.GroupIDData) == chatID {
		t.Error("group_id must change; it still equals the old chat-derived value")
	}
	if got.Epoch != 0 {
		t.Errorf("a new incarnation starts at epoch 0, got %d", got.Epoch)
	}
	if got.GroupInfoEpoch != 0 || len(got.GroupInfoData) != 0 {
		t.Error("stale GroupInfo must be cleared with the tree it described")
	}

	// Cleanup scope.
	var handshakes, pending, consumed int64
	db.Raw(`SELECT count(*) FROM mls_handshakes WHERE chat_id = ?`, chatID).Scan(&handshakes)
	db.Raw(`SELECT count(*) FROM mls_welcomes WHERE chat_id = ? AND consumed_at IS NULL`, chatID).Scan(&pending)
	db.Raw(`SELECT count(*) FROM mls_welcomes WHERE chat_id = ? AND consumed_at IS NOT NULL`, chatID).Scan(&consumed)
	if handshakes != 0 {
		t.Errorf("handshakes for the abandoned tree must be cleared, %d remain", handshakes)
	}
	if pending != 0 {
		t.Errorf("unconsumed Welcomes admit a device into a dead tree, %d remain", pending)
	}
	if consumed != 1 {
		t.Errorf("consumed Welcomes are an audit trail and must survive, got %d", consumed)
	}
}

func TestRecreateGroupCASLoserCreatesNoSecondIncarnation(t *testing.T) {
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	chatID, member, oldInstance := seedGroup(t, db)
	app := recreateApp(member)

	// Winner.
	status, body := postRecreate(t, app, chatID, map[string]any{
		"group_id_b64":         newGid(),
		"cipher_suite":         1,
		"expected_instance_id": oldInstance.String(),
	})
	if status != http.StatusCreated {
		t.Fatalf("winner expected 201, got %d (%v)", status, body)
	}
	winner, _ := body["instance_id"].(string)

	// Loser, still holding the pre-recreation instance.
	status2, body2 := postRecreate(t, app, chatID, map[string]any{
		"group_id_b64":         newGid(),
		"cipher_suite":         1,
		"expected_instance_id": oldInstance.String(),
	})
	if status2 != http.StatusConflict {
		t.Fatalf("stale caller must get 409, got %d (%v)", status2, body2)
	}
	if got, _ := body2["instance_id"].(string); got != winner {
		t.Errorf("409 must hand back the winning incarnation %q, got %q", winner, got)
	}

	var rows int64
	db.Raw(`SELECT count(*) FROM mls_groups WHERE chat_id = ?`, chatID).Scan(&rows)
	if rows != 1 {
		t.Fatalf("exactly one incarnation must exist, got %d", rows)
	}
}

func TestRecreateGroupRejectsReusedGroupID(t *testing.T) {
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	chatID, member, oldInstance := seedGroup(t, db)
	app := recreateApp(member)

	status, _ := postRecreate(t, app, chatID, map[string]any{
		"group_id_b64":         base64.StdEncoding.EncodeToString([]byte(chatID)),
		"cipher_suite":         1,
		"expected_instance_id": oldInstance.String(),
	})
	if status != http.StatusBadRequest {
		t.Fatalf("reusing the current group_id must be 400, got %d", status)
	}

	var id uuid.UUID
	var epoch int64
	if err := db.Raw(`SELECT id, epoch FROM mls_groups WHERE chat_id = ?`, chatID).
		Row().Scan(&id, &epoch); err != nil {
		t.Fatalf("read back: %v", err)
	}
	if id != oldInstance {
		t.Errorf("a rejected request must not mutate the group: instance %s -> %s", oldInstance, id)
	}
	if epoch != 7 {
		t.Errorf("a rejected request must not reset the epoch, got %d", epoch)
	}
}

func TestRecreateGroupMissingGroupIs404(t *testing.T) {
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	_, member, _ := seedGroup(t, db)

	// A chat this user participates in, but with no MLS group row.
	orphan := uuid.New()
	if err := db.Exec(`INSERT INTO chat_participants (chat_id, user_id, left_at) VALUES (?, ?, NULL)`,
		orphan, member).Error; err != nil {
		t.Fatalf("seed orphan participant: %v", err)
	}

	app := recreateApp(member)
	status, _ := postRecreate(t, app, orphan.String(), map[string]any{
		"group_id_b64": newGid(), "cipher_suite": 1,
	})
	if status != http.StatusNotFound {
		t.Fatalf("a genuinely absent group must be 404, got %d", status)
	}
}

// The regression that matters most: a database fault must not masquerade as
// "not found". Dropping the handshakes table makes the cleanup step fail after
// the group row has already been located, so the transaction errors for a reason
// that has nothing to do with the group being missing.
func TestRecreateGroupDatabaseErrorIsNot404(t *testing.T) {
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	chatID, member, oldInstance := seedGroup(t, db)
	if err := db.Exec(`DROP TABLE mls_handshakes`).Error; err != nil {
		t.Fatalf("drop handshakes: %v", err)
	}

	app := recreateApp(member)
	status, body := postRecreate(t, app, chatID, map[string]any{
		"group_id_b64":         newGid(),
		"cipher_suite":         1,
		"expected_instance_id": oldInstance.String(),
	})

	if status == http.StatusNotFound {
		t.Fatal("a database failure was reported as 404 - this is the bug that " +
			"sent a whole investigation looking for a row that was present")
	}
	if status < 500 {
		t.Fatalf("a database failure must be 5xx, got %d (%v)", status, body)
	}
	if msg, _ := body["error"].(string); msg == "" {
		t.Error("the client should still get an error message")
	} else if containsSQLDetail(msg) {
		t.Errorf("raw SQL detail must not reach the client: %q", msg)
	}

	// The failed transaction must not have partially mutated the group.
	var id uuid.UUID
	var epoch int64
	db.Raw(`SELECT id, epoch FROM mls_groups WHERE chat_id = ?`, chatID).Row().Scan(&id, &epoch)
	if id != oldInstance {
		t.Error("a rolled-back recreation must leave the original instance in place")
	}
	if epoch != 7 {
		t.Errorf("epoch must be untouched by a rolled-back recreation, got %d", epoch)
	}
}

func containsSQLDetail(msg string) bool {
	for _, needle := range []string{"SQLSTATE", "INSERT INTO", "UPDATE ", "duplicate key", "pq:"} {
		if len(msg) >= len(needle) && contains(msg, needle) {
			return true
		}
	}
	return false
}

func contains(haystack, needle string) bool {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}
