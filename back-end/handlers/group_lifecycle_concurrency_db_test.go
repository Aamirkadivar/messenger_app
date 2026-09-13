package handlers

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// PHASE 72E - concurrent departures of a group's last active admins.
//
// Phase 72D closed every sequential way for the last active admin to leave, but each check ran
// before the write it guarded, outside any shared lock: two admins acting at the same instant
// could each see the other still in charge, and both go. The runtime probe stranded 16 of 20
// groups that way.
//
// These tests force that interleaving deterministically rather than hoping for it. A test
// transaction row-locks every chat_participants row of the group. Plain reads are never blocked by
// a row lock, so without serialization both requests make their decision and then stop at their
// first write; the test waits until Postgres shows both waiting, then releases them together. With
// the group lock in place, the second request waits BEFORE its decision instead, and decides on
// what the first one committed.
//
// Built on the 72B/72D harness. Everything runs against an ISOLATED database named by
// TEST_DATABASE_URL.

type p72eEnv struct {
	*p72dEnv
}

func p72eSetup(t *testing.T) *p72eEnv {
	t.Helper()
	return &p72eEnv{p72dEnv: p72dSetup(t)}
}

// do is a request for use off the test goroutine: it returns the status (0 on transport error)
// and never calls t.Fatal.
func (e *p72eEnv) do(method, path, body, tok string) int {
	req := httptest.NewRequest(method, path, bytes.NewReader([]byte(body)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := e.app.Test(req, 20000)
	if err != nil {
		return 0
	}
	return resp.StatusCode
}

func (e *p72eEnv) leaveOp(u p72bUser, chat string) func() int {
	return func() int { return e.do(http.MethodPost, "/api/groups/"+chat+"/leave", "", u.tok) }
}

func (e *p72eEnv) deleteChatOp(u p72bUser, chat string) func() int {
	return func() int { return e.do(http.MethodDelete, "/api/chats/"+chat, "", u.tok) }
}

func (e *p72eEnv) demoteOp(by, target p72bUser, chat string) func() int {
	return func() int {
		return e.do(http.MethodPut, "/api/groups/"+chat+"/members/"+target.id.String()+"/role", `{"role":"member"}`, by.tok)
	}
}

func (e *p72eEnv) removeOp(by, target p72bUser, chat string) func() int {
	return func() int {
		return e.do(http.MethodDelete, "/api/groups/"+chat+"/members/"+target.id.String(), "", by.tok)
	}
}

// twoAdminGroup returns a group whose only active admins are a and b, neither of them the owner:
// the owner promoted both and then departed. m is an ordinary member. Since Phase 72F an owner
// cannot leave, so this is a group from the earlier contract and its departure is seeded.
func (e *p72eEnv) twoAdminGroup(t *testing.T) (string, p72bUser, p72bUser) {
	t.Helper()
	owner, a, b, m := e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, a, b, m)
	e.promote(t, owner, chat, a)
	e.promote(t, owner, chat, b)
	e.seedLegacyOwnerDeparture(t, chat, owner)
	if n := e.activeAdmins(t, chat); n != 2 {
		t.Fatalf("setup: want exactly 2 active admins, got %d", n)
	}
	return chat, a, b
}

func (e *p72eEnv) lockWaiters() int64 {
	var n int64
	e.db.Raw(`SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'`).Scan(&n)
	return n
}

// race runs op1 and op2 at once, holds both at a lock until both are in flight, then releases
// them together. held reports whether both were seen waiting before the release.
func (e *p72eEnv) race(t *testing.T, chat string, op1, op2 func() int) (int, int, bool) {
	t.Helper()
	tx := e.db.Begin()
	if err := tx.Exec(`SELECT 1 FROM chat_participants WHERE chat_id = ? FOR UPDATE`, chat).Error; err != nil {
		tx.Rollback()
		t.Fatalf("hold participant rows: %v", err)
	}
	var c1, c2 int
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); c1 = op1() }()
	go func() { defer wg.Done(); c2 = op2() }()
	held := false
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		if e.lockWaiters() >= 2 {
			held = true
			break
		}
	}
	if err := tx.Commit().Error; err != nil {
		t.Fatalf("release participant rows: %v", err)
	}
	wg.Wait()
	return c1, c2, held
}

// oneWins asserts the lifecycle outcome of a race: exactly one request succeeded, the other was
// refused with 403, and the group still has an active admin.
func (e *p72eEnv) oneWins(t *testing.T, name, chat string, c1, c2 int, held bool) {
	t.Helper()
	admins := e.activeAdmins(t, chat)
	t.Logf("%s: %d / %d; both held before any write=%v; active admins after=%d", name, c1, c2, held, admins)
	if !((c1 == http.StatusOK && c2 == http.StatusForbidden) || (c1 == http.StatusForbidden && c2 == http.StatusOK)) {
		t.Errorf("%s: want one 200 and one 403, got %d and %d", name, c1, c2)
	}
	if admins < 1 {
		t.Errorf("%s: the group was left with no active admin", name)
	}
}

const p72eRounds = 3

// C1. The last two active admins both leave at once.
func TestP72E_C1_ConcurrentLeaveLeave(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.leaveOp(a, chat), e.leaveOp(b, chat))
		e.oneWins(t, "leave + leave", chat, c1, c2, held)
	}
}

// C2. One leaves while the other clears the group with DeleteChat.
func TestP72E_C2_ConcurrentLeaveDeleteChat(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.leaveOp(a, chat), e.deleteChatOp(b, chat))
		e.oneWins(t, "leave + DeleteChat", chat, c1, c2, held)
	}
}

// C3. One leaves while the other hands back the admin role.
func TestP72E_C3_ConcurrentLeaveSelfDemotion(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.leaveOp(a, chat), e.demoteOp(b, b, chat))
		e.oneWins(t, "leave + self-demotion", chat, c1, c2, held)
	}
}

// C4. One clears the group with DeleteChat while the other hands back the admin role.
func TestP72E_C4_ConcurrentDeleteChatSelfDemotion(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.deleteChatOp(a, chat), e.demoteOp(b, b, chat))
		e.oneWins(t, "DeleteChat + self-demotion", chat, c1, c2, held)
	}
}

// C5. One admin removes the other while also leaving (two requests from the same admin).
func TestP72E_C5_ConcurrentRemoveAdminAndLeave(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.removeOp(b, a, chat), e.leaveOp(b, chat))
		e.oneWins(t, "remove admin + leave", chat, c1, c2, held)
	}
}

// C6. The two admins remove each other.
func TestP72E_C6_ConcurrentMutualRemoval(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.removeOp(a, b, chat), e.removeOp(b, a, chat))
		e.oneWins(t, "remove + remove", chat, c1, c2, held)
	}
}

// C7. The two admins demote each other.
func TestP72E_C7_ConcurrentMutualDemotion(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		chat, a, b := e.twoAdminGroup(t)
		c1, c2, held := e.race(t, chat, e.demoteOp(a, b, chat), e.demoteOp(b, a, chat))
		e.oneWins(t, "demote + demote", chat, c1, c2, held)
	}
}

// C8. The exact Phase 72D reproduction: the owner and the only other admin leave at once. Exactly
// one of them may go - since Phase 72F it is always the admin, as the owner may not leave at all.
func TestP72E_C8_ConcurrentOwnerAndAdminLeave(t *testing.T) {
	e := p72eSetup(t)
	for i := 0; i < p72eRounds; i++ {
		owner, admin := e.account(t), e.account(t)
		chat := e.createGroup(t, owner, admin)
		e.promote(t, owner, chat, admin)
		c1, c2, held := e.race(t, chat, e.leaveOp(owner, chat), e.leaveOp(admin, chat))
		e.oneWins(t, "owner leave + admin leave", chat, c1, c2, held)
	}
}
