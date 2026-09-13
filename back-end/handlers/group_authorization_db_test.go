package handlers

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/google/uuid"
)

// PHASE 72B - group authorization and left_at.
//
// Leaving a group, or being removed from one, never deletes the chat_participants row: the
// departure is recorded in left_at. A row existing is therefore not membership. These tests drive
// the REAL group and message handlers behind the REAL middleware chain (p71Setup), the REAL hub
// and real sockets, and assert what a departed member, a departed admin and an outsider can still
// do - and that active members and admins keep doing what they could.
//
// Direct chats are the exception the code already makes (see canJoinRoom): there left_at only
// means "cleared from my list" (DeleteChat) and a new message brings the chat back.
//
// Message bodies are random hex standing in for client ciphertext. Everything runs against an
// ISOLATED database named by TEST_DATABASE_URL.

type p72bUser struct {
	id  uuid.UUID
	tok string
}

type p72bEnv struct {
	*p71Env
}

// p72bSetup is p71Setup (real middleware, real hub, real /ws) plus the group routes and
// DeleteChat, mounted the way main.go mounts them.
func p72bSetup(t *testing.T) *p72bEnv {
	t.Helper()
	e := p71Setup(t)
	groups := NewGroupService(e.hub)
	msgs := NewMessageService(e.hub)
	authMW := middleware.AuthMiddleware(e.cfg)
	guard := middleware.DeviceRevocationGuard()
	e.app.Post("/api/groups", authMW, guard, groups.CreateGroup)
	e.app.Get("/api/groups/:chat_id", authMW, guard, groups.GetGroupInfo)
	e.app.Put("/api/groups/:chat_id", authMW, guard, groups.UpdateGroup)
	e.app.Delete("/api/groups/:chat_id", authMW, guard, groups.DeleteGroup)
	e.app.Post("/api/groups/:chat_id/members", authMW, guard, groups.AddMembers)
	e.app.Delete("/api/groups/:chat_id/members/:member_id", authMW, guard, groups.RemoveMember)
	e.app.Put("/api/groups/:chat_id/members/:member_id/role", authMW, guard, groups.UpdateMemberRole)
	e.app.Post("/api/groups/:chat_id/leave", authMW, guard, groups.LeaveGroup)
	e.app.Delete("/api/chats/:chat_id", authMW, guard, msgs.DeleteChat)
	return &p72bEnv{p71Env: e}
}

func (e *p72bEnv) account(t *testing.T) p72bUser {
	t.Helper()
	id, tok := e.user(t)
	return p72bUser{id: id, tok: tok}
}

func p72bJSON(v any) string {
	raw, _ := json.Marshal(v)
	return string(raw)
}

// p72bTick separates timeline steps so every message and every departure has its own timestamp.
func p72bTick() { time.Sleep(30 * time.Millisecond) }

// createGroup creates a group through the real handler: owner is its admin, members are members.
func (e *p72bEnv) createGroup(t *testing.T, owner p72bUser, members ...p72bUser) string {
	t.Helper()
	ids := make([]string, 0, len(members))
	for _, m := range members {
		ids = append(ids, m.id.String())
	}
	code, out := e.req(t, http.MethodPost, "/api/groups",
		p72bJSON(map[string]any{"name": "p72b", "member_ids": ids}), owner.tok, "")
	if code != http.StatusCreated {
		t.Fatalf("create group: %d %v", code, out)
	}
	return fmt.Sprint(dataOf(out)["id"])
}

func (e *p72bEnv) promote(t *testing.T, by p72bUser, chat string, m p72bUser) {
	t.Helper()
	code, out := e.req(t, http.MethodPut, "/api/groups/"+chat+"/members/"+m.id.String()+"/role",
		`{"role":"admin"}`, by.tok, "")
	if code != http.StatusOK {
		t.Fatalf("promote: %d %v", code, out)
	}
}

func (e *p72bEnv) leave(t *testing.T, m p72bUser, chat string) {
	t.Helper()
	code, out := e.req(t, http.MethodPost, "/api/groups/"+chat+"/leave", "", m.tok, "")
	if code != http.StatusOK {
		t.Fatalf("leave: %d %v", code, out)
	}
}

func (e *p72bEnv) addMembers(t *testing.T, by p72bUser, chat string, ms ...p72bUser) (int, map[string]any) {
	t.Helper()
	ids := make([]string, 0, len(ms))
	for _, m := range ms {
		ids = append(ids, m.id.String())
	}
	return e.req(t, http.MethodPost, "/api/groups/"+chat+"/members",
		p72bJSON(map[string]any{"member_ids": ids}), by.tok, "")
}

func (e *p72bEnv) removeMember(t *testing.T, by p72bUser, chat string, m p72bUser) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodDelete, "/api/groups/"+chat+"/members/"+m.id.String(), "", by.tok, "")
}

func (e *p72bEnv) groupInfo(t *testing.T, u p72bUser, chat string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodGet, "/api/groups/"+chat, "", u.tok, "")
}

// p72bMembership is what the database holds for one (chat, user).
type p72bMembership struct {
	rows   int
	leftAt *time.Time
	role   string
}

func (e *p72bEnv) membership(t *testing.T, chat string, u p72bUser) p72bMembership {
	t.Helper()
	var rows []models.ChatParticipant
	if err := e.db.Where("chat_id = ? AND user_id = ?", chat, u.id).Find(&rows).Error; err != nil {
		t.Fatalf("membership: %v", err)
	}
	m := p72bMembership{rows: len(rows)}
	if len(rows) > 0 {
		m.leftAt, m.role = rows[0].LeftAt, rows[0].Role
	}
	return m
}

func (e *p72bEnv) sendOK(t *testing.T, u p72bUser, chat string) string {
	t.Helper()
	code, out := e.send(t, u.tok, chat, opaqueCipher(), uuid.New().String())
	if code != http.StatusOK {
		t.Fatalf("send by an active member: %d %v", code, out)
	}
	return fmt.Sprint(dataOf(out)["id"])
}

// history reads one GET /messages page: status, ids in page order, seq per id, total.
func (e *p72bEnv) history(t *testing.T, u p72bUser, chat, query string) (int, []string, map[string]int64, int64) {
	t.Helper()
	code, out := e.page(t, u.tok, chat, query)
	ids := []string{}
	seqs := map[string]int64{}
	for _, m := range rowsOf(out) {
		id := fmt.Sprint(m["id"])
		ids = append(ids, id)
		if s, ok := m["seq"].(float64); ok {
			seqs[id] = int64(s)
		}
	}
	total := int64(-1)
	if v, ok := out["total"].(float64); ok {
		total = int64(v)
	}
	return code, ids, seqs, total
}

func p72bSet(ids ...string) map[string]bool {
	s := make(map[string]bool, len(ids))
	for _, id := range ids {
		s[id] = true
	}
	return s
}

// p72bLeaked returns the ids in got that are in forbidden.
func p72bLeaked(got []string, forbidden map[string]bool) []string {
	var out []string
	for _, id := range got {
		if forbidden[id] {
			out = append(out, id)
		}
	}
	return out
}

func p72bHas(ids []string, id string) bool {
	for _, x := range ids {
		if x == id {
			return true
		}
	}
	return false
}

// ============================================================ G1-G3: HTTP send

// G1. A member who left, and a member who was removed, cannot post into the group.
func TestP72B_G1_DepartedMemberCannotSend(t *testing.T) {
	e := p72bSetup(t)
	owner, left, removed := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, left, removed)
	e.sendOK(t, left, chat) // control: both could send while members
	e.sendOK(t, removed, chat)

	e.leave(t, left, chat)
	if code, out := e.removeMember(t, owner, chat, removed); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}

	before := e.countChat(t, chat)
	leftCode, leftOut := e.send(t, left.tok, chat, opaqueCipher(), uuid.New().String())
	remCode, remOut := e.send(t, removed.tok, chat, opaqueCipher(), uuid.New().String())
	after := e.countChat(t, chat)
	t.Logf("left member send -> %d, removed member send -> %d; message rows %d -> %d",
		leftCode, remCode, before, after)
	if leftCode != http.StatusForbidden {
		t.Errorf("a member who left posted into the group: %d %v", leftCode, leftOut)
	}
	if remCode != http.StatusForbidden {
		t.Errorf("a removed member posted into the group: %d %v", remCode, remOut)
	}
	if after != before {
		t.Errorf("departed members' sends were persisted: message rows %d -> %d", before, after)
	}
}

// G2. An active member's send is accepted and stored exactly once.
func TestP72B_G2_ActiveMemberCanSend(t *testing.T) {
	e := p72bSetup(t)
	owner, member := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)

	before := e.countChat(t, chat)
	code, out := e.send(t, member.tok, chat, opaqueCipher(), uuid.New().String())
	after := e.countChat(t, chat)
	t.Logf("active member send -> %d; message rows %d -> %d", code, before, after)
	if code != http.StatusOK {
		t.Fatalf("an active member could not send: %d %v", code, out)
	}
	if after != before+1 {
		t.Fatalf("want exactly one stored message, rows %d -> %d", before, after)
	}
	var row models.Message
	if err := e.db.First(&row, "id = ?", fmt.Sprint(dataOf(out)["id"])).Error; err != nil {
		t.Fatalf("stored message: %v", err)
	}
	if row.SenderID != member.id || row.ChatID != chat || !row.IsEncrypted {
		t.Fatalf("stored row does not match the send: sender=%s chat=%s encrypted=%v", row.SenderID, row.ChatID, row.IsEncrypted)
	}
}

// G3. Someone who was never in the group cannot post into it.
func TestP72B_G3_OutsiderCannotSend(t *testing.T) {
	e := p72bSetup(t)
	owner, member, outsider := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)

	before := e.countChat(t, chat)
	code, out := e.send(t, outsider.tok, chat, opaqueCipher(), uuid.New().String())
	after := e.countChat(t, chat)
	t.Logf("outsider send -> %d; message rows %d -> %d", code, before, after)
	if code != http.StatusForbidden {
		t.Errorf("an outsider posted into the group: %d %v", code, out)
	}
	if after != before {
		t.Errorf("outsider send persisted: rows %d -> %d", before, after)
	}
}

// ============================================================ G4-G7: history

// G4. What was sent while the member was in the group stays readable after they leave: GetMessages
// has always served a participant's history, and nothing in the code or the clients withdraws it.
func TestP72B_G4_PreDepartureHistoryStaysReadable(t *testing.T) {
	e := p72bSetup(t)
	owner, member := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)
	a := e.sendOK(t, owner, chat)
	own := e.sendOK(t, member, chat)
	p72bTick()
	e.leave(t, member, chat)

	code, ids, _, total := e.history(t, member, chat, "?limit=100")
	t.Logf("departed member history -> %d ids=%d total=%d (A=%v own=%v)", code, len(ids), total,
		p72bHas(ids, a), p72bHas(ids, own))
	if code != http.StatusOK {
		t.Fatalf("pre-departure history must stay readable: %d", code)
	}
	if !p72bHas(ids, a) || !p72bHas(ids, own) {
		t.Fatalf("pre-departure messages missing from the departed member's history: %v", ids)
	}
	if total != 2 {
		t.Fatalf("total=%d, want 2", total)
	}
}

// G5. Nothing sent after the departure is disclosed - to a member who left or one who was removed -
// and the total does not count it either.
func TestP72B_G5_PostDepartureHistoryIsNotDisclosed(t *testing.T) {
	e := p72bSetup(t)
	owner, left, removed := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, left, removed)
	a := e.sendOK(t, owner, chat)
	p72bTick()
	e.leave(t, left, chat)
	if code, out := e.removeMember(t, owner, chat, removed); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	p72bTick()
	b := e.sendOK(t, owner, chat)

	for _, who := range []struct {
		name string
		u    p72bUser
	}{{"left", left}, {"removed", removed}} {
		code, ids, _, total := e.history(t, who.u, chat, "?limit=100")
		t.Logf("%s member history -> %d ids=%d total=%d (A=%v B=%v)", who.name, code, len(ids), total,
			p72bHas(ids, a), p72bHas(ids, b))
		if code != http.StatusOK {
			t.Errorf("%s: history -> %d, want 200 (pre-departure history stays readable)", who.name, code)
			continue
		}
		if p72bHas(ids, b) {
			t.Errorf("%s member received message B, sent after their departure", who.name)
		}
		if !p72bHas(ids, a) {
			t.Errorf("%s member lost message A, sent before their departure", who.name)
		}
		if total != 1 {
			t.Errorf("%s: total=%d counts messages sent after the departure, want 1", who.name, total)
		}
	}

	// Control: the active owner sees both.
	code, ids, _, total := e.history(t, owner, chat, "?limit=100")
	if code != http.StatusOK || !p72bHas(ids, a) || !p72bHas(ids, b) || total != 2 {
		t.Fatalf("control: active owner history -> %d ids=%v total=%d", code, ids, total)
	}
}

// G6. A never-member reads nothing.
func TestP72B_G6_OutsiderCannotReadHistory(t *testing.T) {
	e := p72bSetup(t)
	owner, member, outsider := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)
	e.sendOK(t, owner, chat)

	code, ids, _, _ := e.history(t, outsider, chat, "?limit=100")
	t.Logf("outsider history -> %d, %d messages", code, len(ids))
	if code != http.StatusForbidden || len(ids) != 0 {
		t.Fatalf("outsider history -> %d with %d messages, want 403 and none", code, len(ids))
	}
}

// G7. The departure boundary holds on every page and for every cursor: forward and backward
// sync, before/after pointing past the boundary, any limit, newest-first and oldest-first.
func TestP72B_G7_PaginationCannotCrossTheDeparture(t *testing.T) {
	e := p72bSetup(t)
	owner, member := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)
	var pre, post []string
	for i := 0; i < 5; i++ {
		pre = append(pre, e.sendOK(t, owner, chat))
	}
	p72bTick()
	e.leave(t, member, chat)
	p72bTick()
	for i := 0; i < 5; i++ {
		post = append(post, e.sendOK(t, owner, chat))
	}
	forbidden := p72bSet(post...)

	_, all, seq, _ := e.history(t, owner, chat, "?limit=100")
	if len(all) != 10 {
		t.Fatalf("control: owner sees %d messages, want 10", len(all))
	}
	lastPre, firstPost, lastPost := seq[pre[4]], seq[post[0]], seq[post[4]]

	queries := []string{
		"?limit=100",
		"?limit=1",
		"?after=0&limit=100",
		fmt.Sprintf("?after=%d", lastPre),
		fmt.Sprintf("?after=%d", firstPost-1),
		fmt.Sprintf("?after=%d", firstPost),
		fmt.Sprintf("?before=%d", lastPost+1),
		fmt.Sprintf("?before=%d&limit=2", lastPost+1),
		"?before=999999999",
	}
	var leaks []string
	for _, q := range queries {
		code, ids, _, total := e.history(t, member, chat, q)
		l := p72bLeaked(ids, forbidden)
		t.Logf("departed %-24s -> %d ids=%d total=%d post-departure=%d", q, code, len(ids), total, len(l))
		if code != http.StatusOK {
			t.Errorf("%s -> %d, want 200", q, code)
		}
		if total != 5 {
			t.Errorf("%s: total=%d, want 5 (the post-departure messages must not be counted)", q, total)
		}
		leaks = append(leaks, l...)
	}
	fwd, _ := e.syncForward(t, member.tok, chat, 2, 20)
	bwd := e.syncBackward(t, member.tok, chat, 2, 20)
	t.Logf("departed forward sync: %d ids (post-departure %d); backward sync: %d ids (post-departure %d)",
		len(fwd), len(p72bLeaked(fwd, forbidden)), len(bwd), len(p72bLeaked(bwd, forbidden)))
	leaks = append(leaks, p72bLeaked(fwd, forbidden)...)
	leaks = append(leaks, p72bLeaked(bwd, forbidden)...)
	if len(leaks) > 0 {
		t.Errorf("pagination disclosed %d post-departure message(s) to the departed member", len(leaks))
	}
	// The pre-departure history stays complete in both directions.
	if !sameSequence(fwd, pre) {
		t.Errorf("forward sync of the pre-departure history: got %d ids, want the 5 pre-departure in order", len(fwd))
	}
	if !sameSequence(bwd, reversed(pre)) {
		t.Errorf("backward sync of the pre-departure history: got %d ids, want the 5 pre-departure newest first", len(bwd))
	}
}

// ============================================================ G8-G12: administration

// G8. An active admin (not the owner) adds a member, who can then post.
func TestP72B_G8_ActiveAdminCanAdd(t *testing.T) {
	e := p72bSetup(t)
	owner, admin, newMember := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin)
	e.promote(t, owner, chat, admin)
	pre := e.sendOK(t, owner, chat)

	code, out := e.addMembers(t, admin, chat, newMember)
	m := e.membership(t, chat, newMember)
	t.Logf("active admin add -> %d; new member rows=%d left_at=%v", code, m.rows, m.leftAt)
	if code != http.StatusOK || m.rows != 1 || m.leftAt != nil {
		t.Fatalf("active admin could not add: %d %v (rows=%d)", code, out, m.rows)
	}
	e.sendOK(t, newMember, chat)
	_, ids, _, _ := e.history(t, newMember, chat, "?limit=100")
	t.Logf("observation (unchanged by 72B): a newly added member's history includes messages sent before they joined: %v",
		p72bHas(ids, pre))
}

// G9. An admin who left cannot add members.
func TestP72B_G9_DepartedAdminCannotAdd(t *testing.T) {
	e := p72bSetup(t)
	owner, admin, newMember := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin)
	e.promote(t, owner, chat, admin)
	e.leave(t, admin, chat)

	code, out := e.addMembers(t, admin, chat, newMember)
	m := e.membership(t, chat, newMember)
	t.Logf("departed admin add -> %d; new member rows=%d", code, m.rows)
	if code != http.StatusForbidden {
		t.Errorf("a departed admin added a member: %d %v", code, out)
	}
	if m.rows != 0 {
		t.Errorf("membership changed: the added user has %d participant row(s)", m.rows)
	}
}

// G10. An active admin (not the owner) removes a member.
func TestP72B_G10_ActiveAdminCanRemove(t *testing.T) {
	e := p72bSetup(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)

	code, out := e.removeMember(t, admin, chat, member)
	m := e.membership(t, chat, member)
	t.Logf("active admin remove -> %d; member left_at set=%v", code, m.leftAt != nil)
	if code != http.StatusOK || m.leftAt == nil {
		t.Fatalf("active admin could not remove: %d %v", code, out)
	}
}

// G11. An admin who left cannot remove members.
func TestP72B_G11_DepartedAdminCannotRemove(t *testing.T) {
	e := p72bSetup(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)
	e.leave(t, admin, chat)

	code, out := e.removeMember(t, admin, chat, member)
	m := e.membership(t, chat, member)
	t.Logf("departed admin remove -> %d; target left_at set=%v", code, m.leftAt != nil)
	if code != http.StatusForbidden {
		t.Errorf("a departed admin removed a member: %d %v", code, out)
	}
	if m.leftAt != nil {
		t.Errorf("membership changed: the target was marked as left")
	}
}

// G12. An active member who is not an admin cannot administer - membership is not a substitute for
// the role.
func TestP72B_G12_ActiveNonAdminCannotAdminister(t *testing.T) {
	e := p72bSetup(t)
	owner, member, other, newMember := e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member, other)

	addCode, _ := e.addMembers(t, member, chat, newMember)
	remCode, _ := e.removeMember(t, member, chat, other)
	updCode, _ := e.req(t, http.MethodPut, "/api/groups/"+chat, `{"name":"renamed by member"}`, member.tok, "")
	t.Logf("non-admin add -> %d, remove -> %d, update -> %d", addCode, remCode, updCode)
	if addCode != http.StatusForbidden || e.membership(t, chat, newMember).rows != 0 {
		t.Errorf("a non-admin member added a member (%d)", addCode)
	}
	if remCode != http.StatusForbidden || e.membership(t, chat, other).leftAt != nil {
		t.Errorf("a non-admin member removed a member (%d)", remCode)
	}
	if updCode != http.StatusForbidden {
		t.Errorf("a non-admin member updated the group (%d)", updCode)
	}
}

// ============================================================ G13: re-add

// G13. What re-adding a departed member actually does, and that it grants nothing: the existing
// row is found by the "already a member" check, so no row is written and the departure stands.
// The member - who was an admin - stays departed for sending, history, group info and
// administration, with no duplicate row and an unchanged left_at.
func TestP72B_G13_ReAddOfDepartedMemberIsDeterministic(t *testing.T) {
	e := p72bSetup(t)
	owner, readded, newMember := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, readded)
	e.promote(t, owner, chat, readded)
	a := e.sendOK(t, owner, chat)
	p72bTick()
	e.leave(t, readded, chat)
	departed := e.membership(t, chat, readded)
	p72bTick()

	code, out := e.addMembers(t, owner, chat, readded)
	after := e.membership(t, chat, readded)
	var active int64
	e.db.Model(&models.ChatParticipant{}).Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chat, readded.id).Count(&active)
	t.Logf("re-add -> %d data=%v; rows=%d active rows=%d left_at unchanged=%v role=%s",
		code, out["data"], after.rows, active,
		after.leftAt != nil && departed.leftAt != nil && after.leftAt.Equal(*departed.leftAt), after.role)
	if after.rows != 1 || active != 0 {
		t.Fatalf("re-add left rows=%d active=%d, want one departed row", after.rows, active)
	}
	if after.leftAt == nil || !after.leftAt.Equal(*departed.leftAt) {
		t.Fatalf("re-add changed left_at: %v -> %v", departed.leftAt, after.leftAt)
	}

	p72bTick()
	b := e.sendOK(t, owner, chat)
	sendCode, _ := e.send(t, readded.tok, chat, opaqueCipher(), uuid.New().String())
	hCode, ids, _, _ := e.history(t, readded, chat, "?limit=100")
	infoCode, _ := e.groupInfo(t, readded, chat)
	adminCode, _ := e.addMembers(t, readded, chat, newMember)
	t.Logf("after re-add: send -> %d, history -> %d (A=%v B=%v), group info -> %d, add as former admin -> %d",
		sendCode, hCode, p72bHas(ids, a), p72bHas(ids, b), infoCode, adminCode)
	if sendCode != http.StatusForbidden {
		t.Errorf("still-departed member could send after re-add: %d", sendCode)
	}
	if p72bHas(ids, b) || !p72bHas(ids, a) {
		t.Errorf("history boundary after re-add: A=%v (want true) B=%v (want false)", p72bHas(ids, a), p72bHas(ids, b))
	}
	if infoCode != http.StatusForbidden {
		t.Errorf("still-departed member read group info after re-add: %d", infoCode)
	}
	if adminCode != http.StatusForbidden || e.membership(t, chat, newMember).rows != 0 {
		t.Errorf("still-departed former admin administered the group: %d", adminCode)
	}
}

// G13b. Removing someone who already left is not a second departure: left_at - the member's history
// boundary - must not move.
func TestP72B_G13b_RemovingADepartedMemberDoesNotMoveTheBoundary(t *testing.T) {
	e := p72bSetup(t)
	owner, member := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)
	e.leave(t, member, chat)
	departed := e.membership(t, chat, member)
	p72bTick()
	b := e.sendOK(t, owner, chat)
	p72bTick()

	code, _ := e.removeMember(t, owner, chat, member)
	after := e.membership(t, chat, member)
	_, ids, _, _ := e.history(t, member, chat, "?limit=100")
	moved := after.leftAt == nil || !after.leftAt.Equal(*departed.leftAt)
	t.Logf("remove of an already-departed member -> %d; left_at moved=%v; post-departure B visible=%v",
		code, moved, p72bHas(ids, b))
	if moved {
		t.Errorf("removing an already-departed member moved left_at %v -> %v", departed.leftAt, after.leftAt)
	}
	if p72bHas(ids, b) {
		t.Errorf("the departed member can read a message sent after their departure")
	}
}

// ============================================================ G14-G16: other group endpoints

// G14. An admin who left cannot rename the group.
func TestP72B_G14_DepartedAdminCannotUpdateGroup(t *testing.T) {
	e := p72bSetup(t)
	owner, admin := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin)
	e.promote(t, owner, chat, admin)
	e.leave(t, admin, chat)

	code, out := e.req(t, http.MethodPut, "/api/groups/"+chat, `{"name":"renamed by departed admin"}`, admin.tok, "")
	var c models.Chat
	e.db.First(&c, "id = ?", chat)
	t.Logf("departed admin update -> %d; name now %q", code, c.Name)
	if code != http.StatusForbidden || c.Name != "p72b" {
		t.Errorf("a departed admin updated the group: %d %v (name %q)", code, out, c.Name)
	}

	// Control: an active admin (the owner) still can.
	if code, out := e.req(t, http.MethodPut, "/api/groups/"+chat, `{"name":"renamed by owner"}`, owner.tok, ""); code != http.StatusOK {
		t.Fatalf("control: active admin update -> %d %v", code, out)
	}
}

// G15. An owner who left the group cannot delete it for everyone; an owner who is still in it can.
func TestP72B_G15_DepartedOwnerCannotDeleteGroup(t *testing.T) {
	e := p72bSetup(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)
	// Since Phase 72F an owner cannot leave; a departed owner exists only in groups from the earlier
	// contract, so that state is seeded.
	e.seedLegacyOwnerDeparture(t, chat, owner)

	code, out := e.req(t, http.MethodDelete, "/api/groups/"+chat, "", owner.tok, "")
	var n int64
	e.db.Model(&models.Chat{}).Where("id = ?", chat).Count(&n)
	t.Logf("departed owner delete -> %d; chat rows=%d; member still active=%v", code, n,
		e.membership(t, chat, member).leftAt == nil)
	if code != http.StatusForbidden || n != 1 || e.membership(t, chat, member).leftAt != nil {
		t.Errorf("a departed owner deleted the group: %d %v", code, out)
	}

	// Control: an owner who is still a member deletes their group.
	other := e.createGroup(t, admin, member)
	if code, out := e.req(t, http.MethodDelete, "/api/groups/"+other, "", admin.tok, ""); code != http.StatusOK {
		t.Fatalf("control: active owner delete -> %d %v", code, out)
	}
}

// G16. A member who left cannot read the group's current member list.
func TestP72B_G16_DepartedMemberCannotReadGroupInfo(t *testing.T) {
	e := p72bSetup(t)
	owner, member, stays := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member, stays)
	e.leave(t, member, chat)

	code, out := e.groupInfo(t, member, chat)
	t.Logf("departed member group info -> %d", code)
	if code != http.StatusForbidden {
		t.Errorf("a departed member read the group's member list: %d (members=%v)", code, dataOf(out)["member_count"])
	}
	if code, out := e.groupInfo(t, stays, chat); code != http.StatusOK {
		t.Fatalf("control: active member group info -> %d %v", code, out)
	}
}

// G17. Direct chats keep their own meaning of left_at: clearing a chat from the list keeps its
// history readable, and a new message - from either side - brings it back.
func TestP72B_G17_DirectChatSemanticsUnchanged(t *testing.T) {
	e := p72bSetup(t)
	a, b := e.account(t), e.account(t)
	chat := e.chat(t, "direct", a.id, b.id)
	m1 := e.sendOK(t, a, chat)

	if code, out := e.req(t, http.MethodDelete, "/api/chats/"+chat, "", b.tok, ""); code != http.StatusOK {
		t.Fatalf("delete chat: %d %v", code, out)
	}
	if e.membership(t, chat, b).leftAt == nil {
		t.Fatalf("DeleteChat did not record left_at")
	}
	code, ids, _, _ := e.history(t, b, chat, "?limit=100")
	if code != http.StatusOK || !p72bHas(ids, m1) {
		t.Fatalf("cleared direct chat history -> %d (m1 present=%v)", code, p72bHas(ids, m1))
	}
	m2 := e.sendOK(t, b, chat) // the side that cleared it may still write, which restores it
	if e.membership(t, chat, b).leftAt != nil {
		t.Fatalf("a new message did not bring the direct chat back")
	}
	_, ids, _, _ = e.history(t, a, chat, "?limit=100")
	if !p72bHas(ids, m2) {
		t.Fatalf("the other side did not get the message sent after the chat was cleared")
	}
	t.Logf("direct chat: cleared-side history readable, cleared side can send, chat restored")
}

// ============================================================ W1-W4: WebSocket

// p72bRoom is a group whose watcher (an active member) and victim (on two live sockets - phone and
// desktop) have joined the room and are confirmed to be receiving.
type p72bRoom struct {
	chat                   string
	owner, watcher, victim p72bUser
	watch                  *p71Socket
	victimSockets          []*p71Socket
}

func (e *p72bEnv) liveRoom(t *testing.T) p72bRoom {
	t.Helper()
	owner, watcher, victim := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, watcher, victim)
	r := p72bRoom{chat: chat, owner: owner, watcher: watcher, victim: victim}
	r.watch = e.dial(t, watcher.tok, watcher.id)
	r.victimSockets = []*p71Socket{e.dial(t, victim.tok, victim.id), e.dial(t, victim.tok, victim.id)}
	r.watch.join(t, chat)
	for _, s := range r.victimSockets {
		s.join(t, chat)
	}
	settle()
	id := e.sendOK(t, owner, chat)
	for i, s := range append([]*p71Socket{r.watch}, r.victimSockets...) {
		if !p72bReceived(s, id, 3*time.Second) {
			t.Fatalf("control: socket %d did not receive while its user was a member", i)
		}
	}
	return r
}

// p72bReceived reports whether s gets the "message" frame for messageID within d.
func p72bReceived(s *p71Socket, messageID string, d time.Duration) bool {
	deadline := time.Now().Add(d)
	for {
		left := time.Until(deadline)
		if left <= 0 {
			return false
		}
		m, ok := s.next("message", left)
		if !ok {
			return false
		}
		if data, _ := m["data"].(map[string]any); data != nil && fmt.Sprint(data["message_id"]) == messageID {
			return true
		}
	}
}

// assertCutOff sends a new group message: the active watcher must get it, no victim socket may.
func (e *p72bEnv) assertCutOff(t *testing.T, r p72bRoom, how string) {
	t.Helper()
	settle()
	id := e.sendOK(t, r.owner, r.chat)
	if !p72bReceived(r.watch, id, 3*time.Second) {
		t.Fatalf("control: the active member did not receive the new message")
	}
	got := 0
	for _, s := range r.victimSockets {
		if p72bReceived(s, id, 1500*time.Millisecond) {
			got++
		}
	}
	t.Logf("%s: departed member's live sockets that received the new group message: %d of %d", how, got, len(r.victimSockets))
	if got > 0 {
		t.Errorf("%s: the departed member kept receiving the group on %d live socket(s)", how, got)
	}
}

// W1a. Removed by an admin while connected.
func TestP72B_W1a_RemovedMemberStopsReceiving(t *testing.T) {
	e := p72bSetup(t)
	r := e.liveRoom(t)
	if code, out := e.removeMember(t, r.owner, r.chat, r.victim); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	e.assertCutOff(t, r, "removed")
}

// W1b. Left the group while connected.
func TestP72B_W1b_LeftMemberStopsReceiving(t *testing.T) {
	e := p72bSetup(t)
	r := e.liveRoom(t)
	e.leave(t, r.victim, r.chat)
	e.assertCutOff(t, r, "left")
}

// W1c. Cleared the group off their list (DeleteChat records left_at for groups too) while connected.
func TestP72B_W1c_GroupClearedViaDeleteChatStopsReceiving(t *testing.T) {
	e := p72bSetup(t)
	r := e.liveRoom(t)
	if code, out := e.req(t, http.MethodDelete, "/api/chats/"+r.chat, "", r.victim.tok, ""); code != http.StatusOK {
		t.Fatalf("delete chat: %d %v", code, out)
	}
	if e.membership(t, r.chat, r.victim).leftAt == nil {
		t.Fatalf("DeleteChat did not record the departure")
	}
	e.assertCutOff(t, r, "cleared via DeleteChat")
}

// W2. A removed member's still-open socket cannot put anything into the group: message frames are
// refused outright (Phase 71) and nothing is stored; typing is no longer relayed to the members.
func TestP72B_W2_RemovedMemberCannotSendOverWebSocket(t *testing.T) {
	e := p72bSetup(t)
	r := e.liveRoom(t)
	if code, out := e.removeMember(t, r.owner, r.chat, r.victim); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	settle()
	before := e.countChat(t, r.chat)
	r.victimSockets[0].write(t, map[string]any{"type": "message", "data": map[string]any{
		"chat_id": r.chat, "content": opaqueCipher(), "encrypted": true, "sender_id": r.victim.id.String()}})
	m, gotMsg := r.watch.next("message", 1200*time.Millisecond)
	after := e.countChat(t, r.chat)
	r.victimSockets[0].write(t, map[string]any{"type": "typing", "data": map[string]any{"chat_id": r.chat, "typing": true}})
	tm, gotTyping := r.watch.next("typing", 1500*time.Millisecond)
	t.Logf("removed member WS message frame: relayed=%v, rows %d -> %d; WS typing relayed=%v", gotMsg, before, after, gotTyping)
	if gotMsg {
		t.Errorf("a removed member's WS message frame reached a member: %v", m)
	}
	if after != before {
		t.Errorf("a removed member's WS frame was persisted: rows %d -> %d", before, after)
	}
	if gotTyping {
		t.Errorf("a removed member's typing frame was relayed to the group: %v", tm)
	}
}

// W3. The removed member's open socket cannot get back in by leaving and re-joining the room.
func TestP72B_W3_RejoinAfterRemovalIsRefused(t *testing.T) {
	e := p72bSetup(t)
	r := e.liveRoom(t)
	if code, out := e.removeMember(t, r.owner, r.chat, r.victim); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	s := r.victimSockets[0]
	s.write(t, map[string]any{"type": "leave", "data": map[string]any{"chat_id": r.chat}})
	s.join(t, r.chat)
	settle()
	id := e.sendOK(t, r.owner, r.chat)
	if !p72bReceived(r.watch, id, 3*time.Second) {
		t.Fatalf("control: the active member did not receive")
	}
	got := p72bReceived(s, id, 1500*time.Millisecond)
	t.Logf("re-join after removal: received=%v", got)
	if got {
		t.Errorf("a removed member re-joined the room and received a new message")
	}
}

// W4. Reconnecting does not restore anything: a fresh socket's join is refused and it gets nothing.
func TestP72B_W4_ReconnectAfterRemovalRestoresNothing(t *testing.T) {
	e := p72bSetup(t)
	r := e.liveRoom(t)
	if code, out := e.removeMember(t, r.owner, r.chat, r.victim); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	for _, s := range r.victimSockets {
		_ = s.conn.Close()
	}
	fresh := e.dial(t, r.victim.tok, r.victim.id)
	fresh.join(t, r.chat)
	settle()
	id := e.sendOK(t, r.owner, r.chat)
	if !p72bReceived(r.watch, id, 3*time.Second) {
		t.Fatalf("control: the active member did not receive")
	}
	got := p72bReceived(fresh, id, 1500*time.Millisecond)
	fresh.write(t, map[string]any{"type": "typing", "data": map[string]any{"chat_id": r.chat, "typing": true}})
	_, typing := r.watch.next("typing", 1200*time.Millisecond)
	t.Logf("reconnect after removal: received=%v, typing relayed=%v", got, typing)
	if got || typing {
		t.Errorf("a reconnected removed member regained the room: received=%v typing=%v", got, typing)
	}
}
