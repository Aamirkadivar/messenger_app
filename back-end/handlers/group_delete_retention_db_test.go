package handlers

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"messenger-app/models"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// PHASE 72I - DeleteGroup keeps a deleted group's messages as rows with deleted_at set and
// hard-deletes the chat row (group.go DeleteGroup; TestArchive_ChatDeletion_RemovesArchivesDespite-
// SoftDeletedMessages). An accidental foreign key, fk_chats_last_message - messages.chat_id ->
// chats.id, ON DELETE NO ACTION, which GORM inferred from the dead Chat.LastMessage field - made that
// impossible for any group with messages. Removing it makes those kept rows reachable, so GetMessages
// must never serve them: they are not history.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

// ============================================================ migration

// p72iUnrelatedFKs is every other foreign key MigrateDB produced before Phase 72I, captured from the
// catalog: name -> table|definition|on-delete. The fix must leave each exactly as it was.
var p72iUnrelatedFKs = map[string]string{
	"fk_consumed_refresh_session": "consumed_refresh|FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE RESTRICT|r",
	"fk_history_keyring_user":     "history_keyring_recoveries|FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE|c",
	"fk_message_archives_chat":    "message_archives|FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE|c",
	"fk_message_archives_message": "message_archives|FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE|c",
	"fk_message_archives_user":    "message_archives|FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE|c",
	"fk_sessions_device":          "sessions|FOREIGN KEY (device_id) REFERENCES e2_ee_devices(id) ON DELETE SET NULL|n",
	"fk_sessions_user":            "sessions|FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE|c",
}

const p72iLegacyFK = `ALTER TABLE messages ADD CONSTRAINT fk_chats_last_message FOREIGN KEY (chat_id) REFERENCES chats(id)`

// fkSnapshot reads every foreign key in the public schema: name -> table|definition|on-delete.
func fkSnapshot(t *testing.T, db *gorm.DB) map[string]string {
	t.Helper()
	var rows []struct{ Name, Spec string }
	if err := db.Raw(`SELECT conname::text AS name,
			conrelid::regclass::text || '|' || pg_get_constraintdef(oid) || '|' || confdeltype::text AS spec
		FROM pg_constraint WHERE contype = 'f' AND connamespace = 'public'::regnamespace`).Scan(&rows).Error; err != nil {
		t.Fatalf("fk snapshot: %v", err)
	}
	out := make(map[string]string, len(rows))
	for _, r := range rows {
		out[r.Name] = r.Spec
	}
	return out
}

// messagesToChats counts any foreign key from messages to chats, whatever its name.
func messagesToChats(t *testing.T, db *gorm.DB) int64 {
	t.Helper()
	var n int64
	db.Raw(`SELECT count(*) FROM pg_constraint WHERE contype = 'f'
		AND conrelid = 'messages'::regclass AND confrelid = 'chats'::regclass`).Scan(&n)
	return n
}

func migrate(t *testing.T, db *gorm.DB, label string) {
	t.Helper()
	if err := models.MigrateDB(db); err != nil {
		t.Fatalf("MigrateDB (%s): %v", label, err)
	}
}

// messageRows is a stable fingerprint of every message row, for proving a migration touches none.
func messageRows(t *testing.T, db *gorm.DB) string {
	t.Helper()
	var s string
	db.Raw(`SELECT coalesce(string_agg(id::text || '/' || chat_id || '/' || coalesce(deleted_at::text, '-') || '/' || seq::text, ',' ORDER BY id), '')
		FROM messages`).Scan(&s)
	return s
}

func seedLegacyMessages(t *testing.T, db *gorm.DB) (string, []uuid.UUID) {
	t.Helper()
	chat := uuid.New().String()
	sender := uuid.New()
	if err := db.Exec(`INSERT INTO chats (id, type, name, created_at, updated_at) VALUES (?, 'group', 'legacy', now(), now())`, chat).Error; err != nil {
		t.Fatalf("seed chat: %v", err)
	}
	ids := []uuid.UUID{uuid.New(), uuid.New(), uuid.New()}
	for _, id := range ids {
		if err := db.Exec(`INSERT INTO messages (id, chat_id, chat_type, sender_id, encrypted_content, is_encrypted, created_at, updated_at)
			VALUES (?, ?, 'group', ?, 'opaque', true, now(), now())`, id, chat, sender).Error; err != nil {
			t.Fatalf("seed message: %v", err)
		}
	}
	return chat, ids
}

// MIG1. A fresh database migrated by MigrateDB has no fk_chats_last_message - and no foreign key from
// messages to chats under any name.
func TestP72I_MIG1_FreshSchemaHasNoAccidentalFK(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	migrate(t, db, "fresh")
	fks := fkSnapshot(t, db)
	_, has := fks["fk_chats_last_message"]
	t.Logf("fresh schema: %d foreign keys; fk_chats_last_message present=%v; messages->chats FKs=%d", len(fks), has, messagesToChats(t, db))
	if has || messagesToChats(t, db) != 0 {
		t.Fatalf("a fresh schema still carries the accidental messages -> chats foreign key")
	}
}

// MIG2. A legacy database - the constraint in place and message rows present, one soft-deleted -
// loses the constraint on the next migration, and not one message row changes. While the constraint
// stood, DeleteGroup's own statements could not commit, which is why the operator pre-check
// (SELECT count(*) FROM messages WHERE deleted_at IS NOT NULL) should read 0 on a real deployment.
func TestP72I_MIG2_LegacyFKIsRemovedWithoutTouchingRows(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	migrate(t, db, "fresh")
	chat, ids := seedLegacyMessages(t, db)
	if err := db.Exec(`UPDATE messages SET deleted_at = now() WHERE id = ?`, ids[1]).Error; err != nil {
		t.Fatalf("soft-delete seed: %v", err)
	}
	if _, has := fkSnapshot(t, db)["fk_chats_last_message"]; !has {
		if err := db.Exec(p72iLegacyFK).Error; err != nil {
			t.Fatalf("recreate the legacy constraint: %v", err)
		}
	}
	legacyErr := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec(`UPDATE messages SET deleted_at = now() WHERE chat_id = ? AND deleted_at IS NULL`, chat).Error; err != nil {
			return err
		}
		return tx.Exec(`DELETE FROM chats WHERE id = ?`, chat).Error
	})
	before := messageRows(t, db)

	migrate(t, db, "legacy")
	_, has := fkSnapshot(t, db)["fk_chats_last_message"]
	after := messageRows(t, db)
	t.Logf("under the legacy constraint DeleteGroup's statements -> %v; after migration: fk present=%v, message rows unchanged=%v",
		legacyErr, has, before == after)
	if legacyErr == nil || !strings.Contains(legacyErr.Error(), "fk_chats_last_message") {
		t.Errorf("expected the legacy constraint to refuse the chat delete, got %v", legacyErr)
	}
	if has || messagesToChats(t, db) != 0 {
		t.Errorf("migration left the legacy constraint in place")
	}
	if before != after {
		t.Errorf("the migration modified message rows")
	}
}

// MIG3. The migration is idempotent: run again and again, no error, no change.
func TestP72I_MIG3_MigrationIsIdempotent(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	migrate(t, db, "first")
	if _, has := fkSnapshot(t, db)["fk_chats_last_message"]; !has {
		if err := db.Exec(p72iLegacyFK).Error; err != nil {
			t.Fatalf("recreate the legacy constraint: %v", err)
		}
	}
	migrate(t, db, "second")
	first := fkSnapshot(t, db)
	migrate(t, db, "third")
	second := fkSnapshot(t, db)
	_, has := second["fk_chats_last_message"]
	t.Logf("second and third runs succeeded; fk present=%v; snapshots equal=%v", has, fmt.Sprint(first) == fmt.Sprint(second))
	if has || fmt.Sprint(first) != fmt.Sprint(second) {
		t.Fatalf("re-running the migration was not a no-op")
	}
}

// MIG4. GORM does not put it back: AutoMigrate on the two models that produced it, then the whole
// startup migration again.
func TestP72I_MIG4_AutoMigrateDoesNotRecreateFK(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	migrate(t, db, "first")
	if err := db.Exec(`ALTER TABLE messages DROP CONSTRAINT IF EXISTS fk_chats_last_message`).Error; err != nil {
		t.Fatalf("drop: %v", err)
	}
	if err := db.AutoMigrate(&models.Chat{}, &models.Message{}); err != nil {
		t.Fatalf("AutoMigrate: %v", err)
	}
	_, afterAuto := fkSnapshot(t, db)["fk_chats_last_message"]
	migrate(t, db, "startup again")
	_, afterStartup := fkSnapshot(t, db)["fk_chats_last_message"]
	t.Logf("after AutoMigrate(Chat, Message): fk present=%v; after MigrateDB: fk present=%v", afterAuto, afterStartup)
	if afterAuto || afterStartup {
		t.Fatalf("GORM recreated the accidental constraint")
	}
}

// MIG5. Every other foreign key is exactly what it was before Phase 72I - names, tables,
// definitions and delete rules - on a fresh schema and on a migrated legacy one.
func TestP72I_MIG5_UnrelatedForeignKeysUnchanged(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	migrate(t, db, "fresh")
	fresh := fkSnapshot(t, db)
	if _, has := fresh["fk_chats_last_message"]; !has {
		if err := db.Exec(p72iLegacyFK).Error; err != nil {
			t.Fatalf("recreate the legacy constraint: %v", err)
		}
	}
	migrate(t, db, "legacy")
	legacy := fkSnapshot(t, db)
	for label, got := range map[string]map[string]string{"fresh": fresh, "legacy-migrated": legacy} {
		if len(got) != len(p72iUnrelatedFKs) {
			t.Errorf("%s: %d foreign keys, want exactly the %d unrelated ones: %v", label, len(got), len(p72iUnrelatedFKs), got)
		}
		for name, want := range p72iUnrelatedFKs {
			if got[name] != want {
				t.Errorf("%s: %s = %q, want %q", label, name, got[name], want)
			}
		}
	}
	t.Logf("fresh and legacy-migrated schemas each hold exactly the %d unrelated foreign keys, unchanged", len(p72iUnrelatedFKs))
}

// The tag change is invisible to clients: models.Chat still serializes last_message, as null.
func TestP72I_ChatJSONStillCarriesLastMessage(t *testing.T) {
	raw, _ := json.Marshal(models.Chat{ID: "x"})
	t.Logf("models.Chat JSON: %s", raw)
	if !strings.Contains(string(raw), `"last_message":null`) {
		t.Fatalf("models.Chat JSON lost last_message: %s", raw)
	}
}

// ============================================================ DeleteGroup

type p72iEnv struct {
	*p72fEnv
}

func p72iSetup(t *testing.T) *p72iEnv {
	t.Helper()
	return &p72iEnv{p72fEnv: p72fSetup(t)}
}

func (e *p72iEnv) deleteGroup(t *testing.T, u p72bUser, chatPath string) int {
	t.Helper()
	code, _ := e.req(t, http.MethodDelete, "/api/groups/"+chatPath, "", u.tok, "")
	return code
}

type p72iState struct {
	Chat, Participants, Active, Messages, SoftDeleted, Archives int64
}

func (e *p72iEnv) state(t *testing.T, chat string) p72iState {
	t.Helper()
	var s p72iState
	e.db.Raw(`SELECT
		(SELECT count(*) FROM chats WHERE id = ?) AS chat,
		(SELECT count(*) FROM chat_participants WHERE chat_id = ?) AS participants,
		(SELECT count(*) FROM chat_participants WHERE chat_id = ? AND left_at IS NULL) AS active,
		(SELECT count(*) FROM messages WHERE chat_id = ?) AS messages,
		(SELECT count(*) FROM messages WHERE chat_id = ? AND deleted_at IS NOT NULL) AS soft_deleted,
		(SELECT count(*) FROM message_archives WHERE chat_id = ?) AS archives`,
		chat, chat, chat, chat, chat, chat).Scan(&s)
	return s
}

func (e *p72iEnv) archive(t *testing.T, chat string, u p72bUser, msg string) {
	t.Helper()
	if err := e.db.Exec(`INSERT INTO message_archives (message_id, user_id, chat_id, root_version, protocol_version, ciphertext, created_at)
		VALUES (?, ?, ?, 1, 1, ?, ?)`, msg, u.id, chat, []byte("opaque"), time.Now()).Error; err != nil {
		t.Fatalf("seed archive: %v", err)
	}
}

func (e *p72iEnv) softDelete(t *testing.T, ids ...string) {
	t.Helper()
	if err := e.db.Exec(`UPDATE messages SET deleted_at = now() WHERE id IN ?`, ids).Error; err != nil {
		t.Fatalf("soft-delete: %v", err)
	}
}

// deletedGroup is a group with n messages (and an archive row for the member) that its owner then
// deletes; returns the chat, its owner, admin and member, and the message ids.
func (e *p72iEnv) groupWithMessages(t *testing.T, n int) (string, p72bUser, p72bUser, p72bUser, []string) {
	t.Helper()
	chat, owner, admin, member := e.ownerGroup(t)
	ids := make([]string, 0, n)
	for i := 0; i < n; i++ {
		sender := owner
		if i%2 == 1 {
			sender = member
		}
		ids = append(ids, e.sendOK(t, sender, chat))
	}
	return chat, owner, admin, member, ids
}

// assertRetained checks the state DeleteGroup must leave: no chat row; every participant row kept and
// marked left; every message row kept, soft-deleted, still naming the deleted chat.
func (e *p72iEnv) assertRetained(t *testing.T, chat string, n int64, participants int64) {
	t.Helper()
	s := e.state(t, chat)
	t.Logf("after DeleteGroup: %+v", s)
	if s.Chat != 0 {
		t.Errorf("the chat row is still there")
	}
	if s.Participants != participants || s.Active != 0 {
		t.Errorf("participants: %d rows / %d active, want %d rows all marked left", s.Participants, s.Active, participants)
	}
	if s.Messages != n || s.SoftDeleted != n {
		t.Errorf("messages: %d rows / %d soft-deleted, want %d rows all soft-deleted and still naming the chat", s.Messages, s.SoftDeleted, n)
	}
}

// G1. An empty group - unchanged.
func TestP72I_G1_DeleteEmptyGroup(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, _ := e.ownerGroup(t)
	code := e.deleteGroup(t, owner, chat)
	t.Logf("owner deletes an empty group -> %d", code)
	if code != http.StatusOK {
		t.Fatalf("empty group delete -> %d", code)
	}
	e.assertRetained(t, chat, 0, 3)
}

// G2. A group with one message - the Phase 72G blocker.
func TestP72I_G2_DeleteGroupWithOneMessage(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, _, _ := e.groupWithMessages(t, 1)
	code := e.deleteGroup(t, owner, chat)
	t.Logf("owner deletes a group with 1 message -> %d", code)
	if code != http.StatusOK {
		t.Fatalf("delete -> %d, want 200", code)
	}
	e.assertRetained(t, chat, 1, 3)
}

// G3/G4/G5. Many messages, last_message_at populated by the sends; the rows are kept soft-deleted.
func TestP72I_G3_DeleteGroupWithManyMessages(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, _, _ := e.groupWithMessages(t, 12)
	var lastAt *time.Time
	e.db.Raw(`SELECT last_message_at FROM chats WHERE id = ?`, chat).Scan(&lastAt)
	code := e.deleteGroup(t, owner, chat)
	t.Logf("last_message_at populated=%v; owner deletes a group with 12 messages -> %d", lastAt != nil, code)
	if lastAt == nil {
		t.Fatalf("setup: last_message_at was not populated by the sends")
	}
	if code != http.StatusOK {
		t.Fatalf("delete -> %d, want 200", code)
	}
	e.assertRetained(t, chat, 12, 3)
}

// G6. Archive cleanup is the existing chat_id cascade, unchanged; another group - its chat, messages
// and archives - is untouched.
func TestP72I_G6_ArchiveCleanupUnchangedAndOtherGroupsUntouched(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, member, ids := e.groupWithMessages(t, 3)
	e.archive(t, chat, member, ids[0])
	other, _, _, otherMember, otherIDs := e.groupWithMessages(t, 2)
	e.archive(t, other, otherMember, otherIDs[0])
	otherBefore := e.state(t, other)

	code := e.deleteGroup(t, owner, chat)
	after, otherAfter := e.state(t, chat), e.state(t, other)
	t.Logf("delete -> %d; deleted group archives=%d; other group before %+v after %+v", code, after.Archives, otherBefore, otherAfter)
	if code != http.StatusOK || after.Archives != 0 || after.Messages != 3 {
		t.Errorf("deleted group: code %d, archives %d (want 0), messages %d (want 3 retained)", code, after.Archives, after.Messages)
	}
	if otherAfter != otherBefore {
		t.Errorf("an unrelated group changed: %+v -> %+v", otherBefore, otherAfter)
	}
}

// ============================================================ visibility

func (e *p72iEnv) seqs(t *testing.T, chat string) map[string]int64 {
	t.Helper()
	var rows []struct {
		ID  string
		Seq int64
	}
	e.db.Raw(`SELECT id::text AS id, seq FROM messages WHERE chat_id = ?`, chat).Scan(&rows)
	out := map[string]int64{}
	for _, r := range rows {
		out[r.ID] = r.Seq
	}
	return out
}

// V1. After DeleteGroup, the former members - and the former owner - read back nothing: no rows, a
// zero total, nothing on any cursor.
func TestP72I_V1_DeletedGroupHistoryIsEmpty(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, admin, member, _ := e.groupWithMessages(t, 6)
	if code := e.deleteGroup(t, owner, chat); code != http.StatusOK {
		t.Fatalf("delete -> %d", code)
	}
	for _, who := range []struct {
		name string
		u    p72bUser
	}{{"member", member}, {"admin", admin}, {"owner", owner}} {
		code, ids, _, total := e.history(t, who.u, chat, "?limit=100")
		fwd, _ := e.syncForward(t, who.u.tok, chat, 2, 10)
		bwd := e.syncBackward(t, who.u.tok, chat, 2, 10)
		t.Logf("former %s: history -> %d rows=%d total=%d; forward walk %d; backward walk %d", who.name, code, len(ids), total, len(fwd), len(bwd))
		if code != http.StatusOK || len(ids) != 0 || total != 0 || len(fwd) != 0 || len(bwd) != 0 {
			t.Errorf("former %s read soft-deleted messages of a deleted group", who.name)
		}
	}
}

// V2. A member who left before - their departure bound still applies, and soft-deleted rows inside
// that bound are hidden too.
func TestP72I_V2_DepartedMemberBoundAndDeletionBothApply(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, member := e.ownerGroup(t)
	live := e.sendOK(t, owner, chat)
	gone := e.sendOK(t, owner, chat)
	p72bTick()
	e.leave(t, member, chat)
	p72bTick()
	after := e.sendOK(t, owner, chat)
	e.softDelete(t, gone)

	code, ids, _, total := e.history(t, member, chat, "?limit=100")
	t.Logf("departed member: history -> %d ids=%d total=%d (pre-departure live=%v, pre-departure deleted=%v, post-departure=%v)",
		code, len(ids), total, p72bHas(ids, live), p72bHas(ids, gone), p72bHas(ids, after))
	if code != http.StatusOK || !p72bHas(ids, live) || p72bHas(ids, gone) || p72bHas(ids, after) || total != 1 {
		t.Fatalf("departed member visibility wrong: code %d ids %v total %d", code, ids, total)
	}
}

// V3/V4/V5. A live group whose seq 2 and 4 are soft-deleted: history is 1, 3, 5; the total is 3; and
// every limit, cursor and direction returns only those.
func TestP72I_V3_MixedLiveAndDeletedAcrossEveryPage(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, member := e.ownerGroup(t)
	var ids []string
	for i := 0; i < 5; i++ {
		ids = append(ids, e.sendOK(t, owner, chat))
	}
	e.softDelete(t, ids[1], ids[3])
	seq := e.seqs(t, chat)
	deleted := p72bSet(ids[1], ids[3])
	live := []string{ids[0], ids[2], ids[4]}

	code, got, _, total := e.history(t, member, chat, "?limit=100")
	t.Logf("seqs %d..%d, deleted seq %d and %d; history -> %d %d rows total=%d", seq[ids[0]], seq[ids[4]], seq[ids[1]], seq[ids[3]], code, len(got), total)
	if code != http.StatusOK || !sameSequence(got, reversed(live)) || total != 3 {
		t.Fatalf("history = %v total %d, want the three live messages newest first and total 3", got, total)
	}

	var leaks []string
	check := func(q string, want []string) {
		c, g, _, tot := e.history(t, member, chat, q)
		leaks = append(leaks, p72bLeaked(g, deleted)...)
		if c != http.StatusOK || !sameSequence(g, want) || tot != 3 {
			t.Errorf("%s -> %d %v (total %d), want %v (total 3)", q, c, g, tot, want)
		}
	}
	check("?limit=1", []string{ids[4]})
	check("?limit=2", []string{ids[4], ids[2]})
	check(fmt.Sprintf("?after=%d", seq[ids[0]]), []string{ids[2], ids[4]})
	check(fmt.Sprintf("?after=%d", seq[ids[1]]), []string{ids[2], ids[4]})
	check(fmt.Sprintf("?after=%d&limit=1", seq[ids[1]]), []string{ids[2]})
	check(fmt.Sprintf("?after=%d", seq[ids[3]]), []string{ids[4]})
	check(fmt.Sprintf("?after=%d", seq[ids[4]]), []string{})
	check(fmt.Sprintf("?before=%d", seq[ids[4]]), []string{ids[2], ids[0]})
	check(fmt.Sprintf("?before=%d", seq[ids[3]]), []string{ids[2], ids[0]})
	check(fmt.Sprintf("?before=%d&limit=1", seq[ids[3]]), []string{ids[2]})
	check(fmt.Sprintf("?before=%d", seq[ids[1]]), []string{ids[0]})
	for _, limit := range []int{1, 2} {
		fwd, _ := e.syncForward(t, member.tok, chat, limit, 10)
		bwd := e.syncBackward(t, member.tok, chat, limit, 10)
		leaks = append(leaks, p72bLeaked(fwd, deleted)...)
		leaks = append(leaks, p72bLeaked(bwd, deleted)...)
		if !sameSequence(fwd, live) || !sameSequence(bwd, reversed(live)) {
			t.Errorf("limit %d walks: forward %v backward %v, want %v and its reverse", limit, fwd, bwd, live)
		}
	}
	t.Logf("11 cursor probes + 4 full walks: soft-deleted rows served=%d", len(leaks))
	if len(leaks) > 0 {
		t.Errorf("a soft-deleted message appeared on a page")
	}
}

// V6. Outsiders are still refused, deleted rows or not.
func TestP72I_V6_OutsiderStillRefused(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, _ := e.ownerGroup(t)
	gone := e.sendOK(t, owner, chat)
	e.sendOK(t, owner, chat)
	e.softDelete(t, gone)
	outsider := e.account(t)
	code, ids, _, _ := e.history(t, outsider, chat, "?limit=100")
	t.Logf("outsider history -> %d (%d rows)", code, len(ids))
	if code != http.StatusForbidden || len(ids) != 0 {
		t.Fatalf("outsider -> %d with %d rows, want 403 and none", code, len(ids))
	}
}

// Direct chats follow the same rule: nothing soft-deletes a direct message today, but a row carrying
// deleted_at is still not history. (DeleteChat/restore semantics: 72B G17, 72C C7.)
func TestP72I_DirectChatFollowsTheSameRule(t *testing.T) {
	e := p72iSetup(t)
	a, b := e.account(t), e.account(t)
	chat := e.chat(t, "direct", a.id, b.id)
	m1 := e.sendOK(t, a, chat)
	m2 := e.sendOK(t, b, chat)
	m3 := e.sendOK(t, a, chat)
	e.softDelete(t, m2)
	for _, who := range []struct {
		name string
		u    p72bUser
	}{{"a", a}, {"b", b}} {
		code, ids, _, total := e.history(t, who.u, chat, "?limit=100")
		t.Logf("direct chat, participant %s: -> %d ids=%d total=%d (deleted served=%v)", who.name, code, len(ids), total, p72bHas(ids, m2))
		if code != http.StatusOK || !sameSequence(ids, []string{m3, m1}) || total != 2 {
			t.Errorf("direct chat participant %s: %v total %d, want [m3 m1] total 2", who.name, ids, total)
		}
	}
}

// ============================================================ rollback

// DeleteGroup stays one transaction: a failure injected at its last step (a test-only trigger on this
// one chat row) leaves the chat, participants, messages, soft-delete flags and archives exactly as they
// were. Without the trigger the same delete then succeeds.
func TestP72I_DeleteGroupRollbackIsAtomic(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, _, member, ids := e.groupWithMessages(t, 4)
	e.archive(t, chat, member, ids[0])
	if err := e.db.Exec(`CREATE OR REPLACE FUNCTION p72i_injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN RAISE EXCEPTION 'p72i injected failure'; END $$`).Error; err != nil {
		t.Fatalf("create failure function: %v", err)
	}
	if err := e.db.Exec(fmt.Sprintf(`CREATE TRIGGER p72i_fail_chat_delete BEFORE DELETE ON chats FOR EACH ROW
		WHEN (OLD.id = '%s') EXECUTE FUNCTION p72i_injected_failure()`, chat)).Error; err != nil {
		t.Fatalf("create failure trigger: %v", err)
	}
	before := e.state(t, chat)
	code := e.deleteGroup(t, owner, chat)
	after := e.state(t, chat)
	t.Logf("delete with a failure injected at the chat delete -> %d; before %+v after %+v", code, before, after)
	if code != http.StatusInternalServerError || after != before {
		t.Fatalf("a failed delete was not rolled back completely: %d, %+v -> %+v", code, before, after)
	}

	e.db.Exec(`DROP TRIGGER IF EXISTS p72i_fail_chat_delete ON chats`)
	code = e.deleteGroup(t, owner, chat)
	t.Logf("control, trigger removed: delete -> %d", code)
	if code != http.StatusOK {
		t.Fatalf("control delete -> %d, want 200", code)
	}
	e.assertRetained(t, chat, 4, 3)
}

// ============================================================ lifecycle regression

// Phase 72F on a group that has messages: the owner still cannot leave or clear it, nobody else can
// delete it, spelling the id differently gets nobody in - and the owner can delete it.
func TestP72I_OwnerLifecycleUnchangedOnGroupsWithMessages(t *testing.T) {
	e := p72iSetup(t)
	chat, owner, admin, member, _ := e.groupWithMessages(t, 3)
	outsider := e.account(t)
	legacyChat, legacyOwner, _, _, _ := e.groupWithMessages(t, 2)
	e.seedLegacyOwnerDeparture(t, legacyChat, legacyOwner)
	before, legacyBefore := e.state(t, chat), e.state(t, legacyChat)

	leave, _ := e.leaveCode(t, owner, chat)
	clear, _ := e.deleteChat(t, owner, chat)
	rows := map[string]int{
		"owner LeaveGroup":            leave,
		"owner DeleteChat(group)":     clear,
		"non-owner admin DeleteGroup": e.deleteGroup(t, admin, chat),
		"member DeleteGroup":          e.deleteGroup(t, member, chat),
		"outsider DeleteGroup":        e.deleteGroup(t, outsider, chat),
		"departed owner DeleteGroup":  e.deleteGroup(t, legacyOwner, legacyChat),
	}
	upper := e.deleteGroup(t, owner, strings.ToUpper(chat))
	t.Logf("%v; owner via UPPER-CASE chat id -> %d", rows, upper)
	for op, code := range rows {
		if code != http.StatusForbidden {
			t.Errorf("%s -> %d, want 403", op, code)
		}
	}
	if upper != http.StatusNotFound {
		t.Errorf("owner delete via a re-spelled chat id -> %d, want 404 (chats.id is text)", upper)
	}
	if e.state(t, chat) != before || e.state(t, legacyChat) != legacyBefore {
		t.Errorf("a refused request changed a group")
	}
	if code := e.deleteGroup(t, owner, chat); code != http.StatusOK {
		t.Errorf("active owner DeleteGroup -> %d, want 200", code)
	}
}

// ============================================================ concurrency

// DeleteGroup racing each lifecycle operation, now on groups that have messages. Both requests are
// held at a lock until both are in flight (the Phase 72E harness). The delete always succeeds; the
// other request either committed first (200) or finds the group gone (403/404); nothing 5xx; the
// messages end up retained and soft-deleted.
func TestP72I_DeleteGroupRacesOnGroupsWithMessages(t *testing.T) {
	e := p72iSetup(t)
	type op struct {
		name string
		run  func(chat string, admin, member p72bUser) func() int
	}
	ops := []op{
		{"LeaveGroup(admin)", func(chat string, admin, _ p72bUser) func() int { return e.leaveOp(admin, chat) }},
		{"DeleteChat(member)", func(chat string, _, member p72bUser) func() int { return e.deleteChatOp(member, chat) }},
		{"RemoveMember(admin removes member)", func(chat string, admin, member p72bUser) func() int { return e.removeOp(admin, member, chat) }},
		{"UpdateMemberRole(admin promotes member)", func(chat string, admin, member p72bUser) func() int {
			return func() int {
				return e.do(http.MethodPut, "/api/groups/"+chat+"/members/"+member.id.String()+"/role", `{"role":"admin"}`, admin.tok)
			}
		}},
	}
	for _, o := range ops {
		for i := 0; i < p72eRounds; i++ {
			chat, owner, admin, member, ids := e.groupWithMessages(t, 3)
			del := func() int { return e.do(http.MethodDelete, "/api/groups/"+chat, "", owner.tok) }
			c1, c2, held := e.race(t, chat, del, o.run(chat, admin, member))
			s := e.state(t, chat)
			t.Logf("DeleteGroup vs %s: %d / %d; both held=%v; after %+v", o.name, c1, c2, held, s)
			if c1 != http.StatusOK {
				t.Errorf("DeleteGroup vs %s: delete -> %d, want 200", o.name, c1)
			}
			if c2 != http.StatusOK && c2 != http.StatusForbidden && c2 != http.StatusNotFound {
				t.Errorf("DeleteGroup vs %s: other request -> %d", o.name, c2)
			}
			if s.Chat != 0 || s.Messages != int64(len(ids)) || s.SoftDeleted != int64(len(ids)) || s.Active != 0 {
				t.Errorf("DeleteGroup vs %s: end state %+v", o.name, s)
			}
		}
	}
}
