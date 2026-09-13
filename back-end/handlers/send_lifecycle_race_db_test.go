package handlers

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

// PHASE 72L - SendMessage racing group lifecycle operations.
//
// Phase 72I dropped fk_chats_last_message. That FK was never designed, but it did one real job: the
// INSERT of a message took a KEY SHARE lock on its chat row, so a send that had already passed its
// checks could not land in a chat that DeleteGroup removed in the meantime - the insert failed. With
// the FK gone, SendMessage checks the chat, DeleteGroup soft-deletes the group's messages and
// deletes the chat, and only then does the send's INSERT run: a LIVE message in a chat that no
// longer exists, served to every former member.
//
// Both orders are forced deterministically, never by timing:
//   - send first: the send passes every check and is then held INSIDE its own INSERT (a test-only
//     trigger waits on an advisory lock this test holds) while the lifecycle operation runs;
//   - operation first: the operation takes its group lock and is held at the participant rows (the
//     Phase 72E barrier) while the send is fired.
// Each side is released only once the other is seen either finished or waiting on a lock.
//
// The trigger, its function and its table exist only for the duration of a test. Everything runs
// against an ISOLATED database named by TEST_DATABASE_URL; message bodies are random hex.

const (
	p72lHoldKey = 7272012
	p72lRounds  = 12 // per order and per operation: 24 iterations of every race
)

type p72lEnv struct {
	*p72iEnv
}

func p72lSetup(t *testing.T) *p72lEnv {
	t.Helper()
	return &p72lEnv{p72iEnv: p72iSetup(t)}
}

// installHold adds the test-only trigger: an INSERT into a chat listed in p72l_hold_chats waits for
// the advisory lock p72lHoldKey. BEFORE triggers fire in name order, so it runs ahead of Phase 71's
// seq trigger and the held insert owns no counter row.
func (e *p72lEnv) installHold(t *testing.T) {
	t.Helper()
	for _, stmt := range []string{
		`CREATE TABLE IF NOT EXISTS p72l_hold_chats (chat_id text PRIMARY KEY)`,
		fmt.Sprintf(`CREATE OR REPLACE FUNCTION p72l_hold_insert() RETURNS trigger AS $$
		BEGIN
			IF EXISTS (SELECT 1 FROM p72l_hold_chats WHERE chat_id = NEW.chat_id) THEN
				PERFORM pg_advisory_xact_lock(%d);
			END IF;
			RETURN NEW;
		END $$ LANGUAGE plpgsql`, p72lHoldKey),
		`DROP TRIGGER IF EXISTS p72l_hold ON messages`,
		`CREATE TRIGGER p72l_hold BEFORE INSERT ON messages FOR EACH ROW EXECUTE FUNCTION p72l_hold_insert()`,
	} {
		if err := e.db.Exec(stmt).Error; err != nil {
			t.Fatalf("install hold: %v", err)
		}
	}
	t.Cleanup(func() {
		e.db.Exec(`DROP TRIGGER IF EXISTS p72l_hold ON messages`)
		e.db.Exec(`DROP FUNCTION IF EXISTS p72l_hold_insert()`)
		e.db.Exec(`DROP TABLE IF EXISTS p72l_hold_chats`)
	})
}

// hold makes every INSERT into chat's messages wait until the returned release runs.
func (e *p72lEnv) hold(t *testing.T, chat string) func() {
	t.Helper()
	ctx := context.Background()
	sqlDB, err := e.db.DB()
	if err != nil {
		t.Fatalf("hold: %v", err)
	}
	conn, err := sqlDB.Conn(ctx)
	if err != nil {
		t.Fatalf("hold: %v", err)
	}
	if _, err := conn.ExecContext(ctx, `SELECT pg_advisory_lock($1)`, p72lHoldKey); err != nil {
		conn.Close()
		t.Fatalf("hold: %v", err)
	}
	if err := e.db.Exec(`INSERT INTO p72l_hold_chats (chat_id) VALUES (?)`, chat).Error; err != nil {
		conn.Close()
		t.Fatalf("hold: %v", err)
	}
	var once sync.Once
	return func() {
		once.Do(func() {
			_, _ = conn.ExecContext(ctx, `SELECT pg_advisory_unlock($1)`, p72lHoldKey)
			_ = conn.Close()
		})
	}
}

// heldInserts counts sessions parked inside the test trigger.
func (e *p72lEnv) heldInserts() int64 {
	var n int64
	e.db.Raw(`SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = ? AND NOT granted`, p72lHoldKey).Scan(&n)
	return n
}

// rowWaiters counts sessions waiting on another transaction's row lock - as opposed to on the insert
// hold, which is an advisory lock.
func (e *p72lEnv) rowWaiters() int64 {
	var n int64
	e.db.Raw(`SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
		AND wait_event_type = 'Lock' AND wait_event <> 'advisory'`).Scan(&n)
	return n
}

// liveOrphans counts messages that are not soft-deleted but whose chat no longer exists - the state
// this phase exists to rule out. It spans the whole database, not one chat.
func (e *p72lEnv) liveOrphans() int64 {
	var n int64
	e.db.Raw(`SELECT count(*) FROM messages m WHERE m.deleted_at IS NULL
		AND NOT EXISTS (SELECT 1 FROM chats c WHERE c.id = m.chat_id)`).Scan(&n)
	return n
}

func (e *p72lEnv) deadlocks() int64 {
	var n int64
	e.db.Raw(`SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()`).Scan(&n)
	return n
}

func p72lWait(d time.Duration, cond func() bool) bool {
	for deadline := time.Now().Add(d); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if cond() {
			return true
		}
	}
	return cond()
}

func (e *p72lEnv) sendOp(u p72bUser, chat string) func() int {
	return func() int {
		code, _, _ := e.sendNoFatal(u.tok, chat, opaqueCipher(), uuid.New().String())
		return code
	}
}

// sendFirst: the send passes every check and is held inside its INSERT; op then runs, and the send
// is released once op has either finished or is seen waiting on a lock. Returns both statuses and
// whether op committed while the send was still held - that is, before the message did.
func (e *p72lEnv) sendFirst(t *testing.T, chat string, send, op func() int) (int, int, bool) {
	t.Helper()
	release := e.hold(t, chat)
	defer release()
	var sendCode, opCode int
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); sendCode = send() }()
	if !p72lWait(5*time.Second, func() bool { return e.heldInserts() >= 1 }) {
		release()
		wg.Wait()
		t.Fatalf("the send never reached its INSERT (status %d)", sendCode)
	}
	opDone := make(chan struct{})
	wg.Add(1)
	go func() { defer wg.Done(); opCode = op(); close(opDone) }()
	opFinished := false
	p72lWait(5*time.Second, func() bool {
		select {
		case <-opDone:
			opFinished = true
			return true
		default:
		}
		return e.rowWaiters() >= 1
	})
	release()
	wg.Wait()
	e.db.Exec(`DELETE FROM p72l_hold_chats WHERE chat_id = ?`, chat)
	return sendCode, opCode, opFinished
}

func (e *p72lEnv) messageCount(chat string) int64 {
	var n int64
	e.db.Raw(`SELECT count(*) FROM messages WHERE chat_id = ?`, chat).Scan(&n)
	return n
}

// opFirst: op takes its group lock and is held at the participant rows; the send is fired and the
// hold lifted once the send has either finished or is seen waiting on a lock. Returns both statuses
// and whether the send's message had committed while op was still held - that is, before op did.
// (Where the send waits is not evidence of order: the send's own post-commit UPDATE of
// chats.last_message_at also waits for op's group lock.)
func (e *p72lEnv) opFirst(t *testing.T, chat string, send, op func() int) (int, int, bool) {
	t.Helper()
	tx := e.db.Begin()
	if err := tx.Exec(`SELECT 1 FROM chat_participants WHERE chat_id = ? FOR UPDATE`, chat).Error; err != nil {
		tx.Rollback()
		t.Fatalf("hold participant rows: %v", err)
	}
	var sendCode, opCode int
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); opCode = op() }()
	if !p72lWait(5*time.Second, func() bool { return e.rowWaiters() >= 1 }) {
		tx.Rollback()
		wg.Wait()
		t.Fatalf("the operation never reached the participant rows (status %d)", opCode)
	}
	before := e.messageCount(chat)
	sendDone := make(chan struct{})
	wg.Add(1)
	go func() { defer wg.Done(); sendCode = send(); close(sendDone) }()
	p72lWait(5*time.Second, func() bool {
		select {
		case <-sendDone:
			return true
		default:
		}
		return e.rowWaiters() >= 2
	})
	landed := e.messageCount(chat) > before
	if err := tx.Commit().Error; err != nil {
		t.Fatalf("release participant rows: %v", err)
	}
	wg.Wait()
	return sendCode, opCode, landed
}

// R0. The exact interleaving Phase 72K reproduced: the send passes its checks, DeleteGroup runs to
// completion, and only then does the send's INSERT execute. No live message may be left in the
// deleted chat, and the deleted group's history must stay empty.
func TestP72L_R0_SendCannotLandInAGroupDeletedUnderIt(t *testing.T) {
	e := p72lSetup(t)
	e.installHold(t)
	chat, owner, _, member, ids := e.groupWithMessages(t, 2)
	send, del, delFirst := e.sendFirst(t, chat, e.sendOp(member, chat),
		func() int { return e.do(http.MethodDelete, "/api/groups/"+chat, "", owner.tok) })
	s := e.state(t, chat)
	live := s.Messages - s.SoftDeleted
	code, rows, _, total := e.history(t, member, chat, "?limit=100")
	t.Logf("send -> %d, DeleteGroup -> %d, DeleteGroup committed while the send was held=%v; after %+v; live rows in the deleted chat=%d; "+
		"former member history -> %d rows=%d total=%d", send, del, delFirst, s, live, code, len(rows), total)
	if del != http.StatusOK {
		t.Errorf("DeleteGroup -> %d, want 200", del)
	}
	if send != http.StatusOK && send != http.StatusNotFound {
		t.Errorf("send -> %d, want 200 (committed before the delete) or 404 (chat gone)", send)
	}
	if live != 0 {
		t.Errorf("%d live message(s) reference the deleted chat", live)
	}
	if s.Chat != 0 || s.Active != 0 {
		t.Errorf("group not fully deleted: %+v", s)
	}
	if send == http.StatusOK && s.Messages != int64(len(ids))+1 {
		t.Errorf("an accepted send must be retained soft-deleted with the rest: %d rows, want %d", s.Messages, len(ids)+1)
	}
	if code != http.StatusOK || len(rows) != 0 || total != 0 {
		t.Errorf("deleted group history -> %d, %d rows, total %d; want 200, 0, 0", code, len(rows), total)
	}
}

// R1-R5. SendMessage against every group lifecycle operation, both orders, p72lRounds each.
// Invariants on every iteration: no transport failure and no 5xx, no live message whose chat is
// gone, and a deleted group left fully deleted with every row retained soft-deleted. Deadlocks are
// read from pg_stat_database across the whole matrix.
func TestP72L_RaceMatrix(t *testing.T) {
	e := p72lSetup(t)
	e.installHold(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	races := []struct {
		name    string
		deletes bool
		sender  p72bUser
		op      func(chat string) func() int
	}{
		{"R1 SendMessage vs DeleteGroup(owner)", true, member, func(chat string) func() int {
			return func() int { return e.do(http.MethodDelete, "/api/groups/"+chat, "", owner.tok) }
		}},
		{"R2 SendMessage vs DeleteChat(group, by the sender)", false, member, func(chat string) func() int {
			return e.deleteChatOp(member, chat)
		}},
		{"R3 SendMessage vs LeaveGroup(the sender, an admin)", false, admin, func(chat string) func() int {
			return e.leaveOp(admin, chat)
		}},
		{"R4 SendMessage vs RemoveMember(admin removes the sender)", false, member, func(chat string) func() int {
			return e.removeOp(admin, member, chat)
		}},
		{"R5 SendMessage vs UpdateMemberRole(admin promotes the sender)", false, member, func(chat string) func() int {
			return func() int {
				return e.do(http.MethodPut, "/api/groups/"+chat+"/members/"+member.id.String()+"/role", `{"role":"admin"}`, admin.tok)
			}
		}},
	}
	dl0 := e.deadlocks()
	for _, r := range races {
		t.Run(r.name, func(t *testing.T) {
			outcomes := map[string]int{}
			var accepted, refused, failures, orphans int
			for i := 0; i < 2*p72lRounds; i++ {
				chat := e.createGroup(t, owner, admin, member)
				e.promote(t, owner, chat, admin)
				e.sendOK(t, owner, chat)
				e.sendOK(t, member, chat)
				before := e.liveOrphans()
				var label string
				var send, op int
				if i%2 == 0 {
					var opCommitted bool
					send, op, opCommitted = e.sendFirst(t, chat, e.sendOp(r.sender, chat), r.op(chat))
					label = fmt.Sprintf("send first: send %d / op %d / op committed while the send was held=%v", send, op, opCommitted)
				} else {
					var landed bool
					send, op, landed = e.opFirst(t, chat, e.sendOp(r.sender, chat), r.op(chat))
					label = fmt.Sprintf("operation first: send %d / op %d / message committed while the op was held=%v", send, op, landed)
				}
				order := label[:strings.Index(label, ":")]
				outcomes[label]++
				s := e.state(t, chat)
				grown := e.liveOrphans() - before
				bad := false
				if send == 0 || send >= 500 || op == 0 || op >= 500 {
					t.Errorf("%s #%d: send -> %d, operation -> %d", order, i, send, op)
					bad = true
				}
				if send == http.StatusOK {
					accepted++
				} else {
					refused++
				}
				if grown != 0 {
					t.Errorf("%s #%d: %d new live message(s) whose chat is gone", order, i, grown)
					orphans += int(grown)
					bad = true
				}
				if r.deletes && (s.Chat != 0 || s.Active != 0 || s.Messages != s.SoftDeleted) {
					t.Errorf("%s #%d: group not fully deleted: %+v", order, i, s)
					bad = true
				}
				if !r.deletes && s.Chat != 1 {
					t.Errorf("%s #%d: the group disappeared: %+v", order, i, s)
					bad = true
				}
				if bad {
					failures++
				}
			}
			t.Logf("%d iterations: sends accepted=%d refused=%d, invariant failures=%d, live orphans=%d; outcomes %v",
				2*p72lRounds, accepted, refused, failures, orphans, outcomes)
		})
	}
	time.Sleep(1500 * time.Millisecond) // pg_stat_database is flushed by idle backends
	if d := e.deadlocks() - dl0; d != 0 {
		t.Errorf("deadlocks during the matrix: %d", d)
	} else {
		t.Logf("deadlocks during the matrix: 0")
	}
}

// Direct chats keep their own contract: DeleteChat only clears the chat from the caller's list, and
// a later message brings it back. Whatever order the two commit in, the clearer must end up seeing
// the chat again exactly when the message committed after the clear.
func TestP72L_DirectChatSendVersusClear(t *testing.T) {
	e := p72lSetup(t)
	e.installHold(t)
	a, b := e.account(t), e.account(t)
	outcomes := map[string]int{}
	for i := 0; i < 2*p72lRounds; i++ {
		chat := e.chat(t, "direct", a.id, b.id)
		e.sendOK(t, b, chat)
		clear := e.deleteChatOp(b, chat)
		order := "send first"
		var send, op int
		var clearFirst bool
		if i%2 == 0 {
			send, op, clearFirst = e.sendFirst(t, chat, e.sendOp(a, chat), clear)
		} else {
			order = "clear first"
			var landed bool
			send, op, landed = e.opFirst(t, chat, e.sendOp(a, chat), clear)
			clearFirst = !landed
		}
		visible := e.membership(t, chat, b).leftAt == nil
		outcomes[fmt.Sprintf("%s: send %d / clear %d / clear committed first=%v / visible to the clearer=%v", order, send, op, clearFirst, visible)]++
		if send != http.StatusOK || op != http.StatusOK {
			t.Errorf("%s #%d: send -> %d, clear -> %d; both must succeed on a direct chat", order, i, send, op)
		}
		if visible != clearFirst {
			t.Errorf("%s #%d: clear committed first=%v but the chat is visible to the clearer=%v", order, i, clearFirst, visible)
		}
		if n := e.liveOrphans(); n != 0 {
			t.Errorf("%s #%d: %d live orphan message(s)", order, i, n)
		}
	}
	t.Logf("%d iterations; outcomes %v", 2*p72lRounds, outcomes)
}
