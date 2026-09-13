package handlers

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"messenger-app/models"
)

// PHASE 72F - the owner lifecycle contract: A GROUP OWNER MAY NOT LEAVE THE GROUP WHILE THEY ARE ITS
// OWNER.
//
// Nothing can hand ownership on - no endpoint changes owner_id after CreateGroup - so an owner who
// left took the owner-only operations, deleting the group above all, away for good. The product
// decision for this phase closes that without inventing a transfer: an active owner cannot leave by
// any route (LeaveGroup, removing themselves through RemoveMember, clearing the group with
// DeleteChat), cannot be removed by anyone else, and can still delete the group.
//
// Groups whose owner left under the earlier contract still exist. seedLegacyOwnerDeparture
// recreates that state, so the rules for a departed owner, and for the admins left in charge of
// such a group, stay tested.
//
// Built on the 72B/72D/72E harness. Everything runs against an ISOLATED database named by
// TEST_DATABASE_URL.

// seedLegacyOwnerDeparture marks chat's owner as departed directly in the database. Since Phase 72F
// no request can produce this state; before it, an owner could leave while another admin remained,
// and those groups are still out there.
func (e *p72bEnv) seedLegacyOwnerDeparture(t *testing.T, chat string, owner p72bUser) {
	t.Helper()
	res := e.db.Exec(`UPDATE chat_participants SET left_at = ? WHERE chat_id = ? AND user_id = ? AND left_at IS NULL`,
		time.Now(), chat, owner.id)
	if res.Error != nil || res.RowsAffected != 1 {
		t.Fatalf("seed legacy owner departure: %v (rows %d)", res.Error, res.RowsAffected)
	}
}

type p72fEnv struct {
	*p72eEnv
}

func p72fSetup(t *testing.T) *p72fEnv {
	t.Helper()
	return &p72fEnv{p72eEnv: p72eSetup(t)}
}

func (e *p72fEnv) keyEpoch(t *testing.T, chat string) int {
	t.Helper()
	var c models.Chat
	if err := e.db.First(&c, "id = ?", chat).Error; err != nil {
		t.Fatalf("key epoch: %v", err)
	}
	return c.KeyEpoch
}

func (e *p72fEnv) archiveRows(t *testing.T, chat string, u p72bUser) int64 {
	t.Helper()
	var n int64
	e.db.Model(&models.MessageArchive{}).Where("chat_id = ? AND user_id = ?", chat, u.id).Count(&n)
	return n
}

// ownerGroup is the standard state: owner A, admin B, member C, all active.
func (e *p72fEnv) ownerGroup(t *testing.T) (string, p72bUser, p72bUser, p72bUser) {
	t.Helper()
	a, b, c := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, a, b, c)
	e.promote(t, a, chat, b)
	return chat, a, b, c
}

// O1. The owner cannot leave while another active admin is there to carry on - and a refused leave
// has none of a departure's effects: no membership change, no archive purge, no key-epoch bump, no
// eviction of the owner's live socket.
func TestP72F_O1_OwnerWithAnotherActiveAdminCannotLeave(t *testing.T) {
	e := p72fSetup(t)
	chat, a, b, c := e.ownerGroup(t)
	msg := e.sendOK(t, c, chat)
	if err := e.db.Exec(`INSERT INTO message_archives (message_id, user_id, chat_id, root_version, protocol_version, ciphertext, created_at)
		VALUES (?, ?, ?, 1, 1, ?, ?)`, msg, a.id, chat, []byte("opaque-archive-ciphertext"), time.Now()).Error; err != nil {
		t.Fatalf("seed archive: %v", err)
	}
	watch := e.dial(t, a.tok, a.id)
	watch.join(t, chat)
	settle()
	epoch := e.keyEpoch(t, chat)

	code, out := e.leaveCode(t, a, chat)
	aState, bState := e.membership(t, chat, a), e.membership(t, chat, b)
	owner, _ := e.ownerOf(t, chat)
	after := e.sendOK(t, b, chat)
	stillSubscribed := p72bReceived(watch, after, 3*time.Second)
	t.Logf("owner leave with another active admin -> %d %v; owner active=%v admin active=%v owner_id kept=%v archive rows=%d key_epoch %d -> %d; owner socket still receives=%v",
		code, out["message"], aState.leftAt == nil, bState.leftAt == nil, owner == a.id, e.archiveRows(t, chat, a), epoch, e.keyEpoch(t, chat), stillSubscribed)
	if code != http.StatusForbidden {
		t.Fatalf("the owner left the group: %d %v", code, out)
	}
	if aState.leftAt != nil || bState.leftAt != nil || owner != a.id || e.activeAdmins(t, chat) != 2 {
		t.Errorf("a refused owner leave changed membership")
	}
	if e.archiveRows(t, chat, a) != 1 {
		t.Errorf("a refused owner leave purged the owner's archives")
	}
	if e.keyEpoch(t, chat) != epoch {
		t.Errorf("a refused owner leave bumped key_epoch %d -> %d", epoch, e.keyEpoch(t, chat))
	}
	if !stillSubscribed {
		t.Errorf("a refused owner leave evicted the owner's live socket")
	}
}

// O2. The owner as the only admin: refused exactly as before, with the existing message.
func TestP72F_O2_OwnerAsSoleAdminCannotLeave(t *testing.T) {
	e := p72fSetup(t)
	a, b := e.account(t), e.account(t)
	chat := e.createGroup(t, a, b)

	code, out := e.leaveCode(t, a, chat)
	t.Logf("sole-admin owner leave -> %d %v", code, out["message"])
	if code != http.StatusForbidden || out["message"] != "Group owner cannot leave while still being the only admin. Transfer ownership first." {
		t.Fatalf("sole-admin owner leave changed: %d %v", code, out)
	}
	if e.membership(t, chat, a).leftAt != nil {
		t.Fatalf("the owner left")
	}
}

// O3. Nobody removes the owner - not another admin, not a member, not the owner themselves (which is
// leaving) - under any spelling of the ids.
func TestP72F_O3_OwnerCannotBeRemoved(t *testing.T) {
	e := p72fSetup(t)
	chat, a, b, c := e.ownerGroup(t)
	up := strings.ToUpper

	type row struct {
		name string
		code int
	}
	var rows []row
	c1, _ := e.removeAs(t, b, chat, a.id.String())
	rows = append(rows, row{"admin removes owner", c1})
	c2, _ := e.removeAs(t, b, chat, up(a.id.String()))
	rows = append(rows, row{"admin removes OWNER (upper-case id)", c2})
	c3, _ := e.removeAs(t, b, up(chat), a.id.String())
	rows = append(rows, row{"admin removes owner (UPPER-CASE chat)", c3})
	c4, _ := e.removeAs(t, c, chat, a.id.String())
	rows = append(rows, row{"member removes owner", c4})
	c5, _ := e.removeAs(t, a, chat, a.id.String())
	rows = append(rows, row{"owner removes self", c5})
	c6, _ := e.removeAs(t, a, chat, up(a.id.String()))
	rows = append(rows, row{"owner removes SELF (upper-case id)", c6})
	owner, _ := e.ownerOf(t, chat)
	for _, r := range rows {
		t.Logf("%-40s -> %d", r.name, r.code)
		if r.code != http.StatusForbidden {
			t.Errorf("%s -> %d, want 403", r.name, r.code)
		}
	}
	if e.membership(t, chat, a).leftAt != nil || owner != a.id {
		t.Errorf("the owner was removed (active=%v, owner_id kept=%v)", e.membership(t, chat, a).leftAt == nil, owner == a.id)
	}
}

// O4. The owner can still delete the group while active (a group with no messages - the state in
// which DeleteGroup already succeeds; see the known fk_chats_last_message finding).
func TestP72F_O4_OwnerCanDeleteGroup(t *testing.T) {
	e := p72fSetup(t)
	chat, a, _, c := e.ownerGroup(t)

	code, out := e.req(t, http.MethodDelete, "/api/groups/"+chat, "", a.tok, "")
	_, exists := e.ownerOf(t, chat)
	t.Logf("active owner delete -> %d; chat exists=%v; member departed=%v", code, exists, e.membership(t, chat, c).leftAt != nil)
	if code != http.StatusOK || exists || e.membership(t, chat, c).leftAt == nil {
		t.Fatalf("owner delete changed: %d %v", code, out)
	}
}

// O5. The restriction is the owner's, not every admin's: a non-owner admin still leaves.
func TestP72F_O5_NonOwnerAdminCanStillLeave(t *testing.T) {
	e := p72fSetup(t)
	chat, a, b, _ := e.ownerGroup(t)

	code, out := e.leaveCode(t, b, chat)
	owner, _ := e.ownerOf(t, chat)
	t.Logf("non-owner admin leave -> %d; owner active=%v admin departed=%v owner_id kept=%v", code,
		e.membership(t, chat, a).leftAt == nil, e.membership(t, chat, b).leftAt != nil, owner == a.id)
	if code != http.StatusOK || e.membership(t, chat, a).leftAt != nil || e.membership(t, chat, b).leftAt == nil || owner != a.id {
		t.Fatalf("non-owner admin leave broke: %d %v", code, out)
	}
}

// O6. An owner who left under the earlier contract has no authority left.
func TestP72F_O6_DepartedOwnerRemainsPowerless(t *testing.T) {
	e := p72fSetup(t)
	chat, a, _, c := e.ownerGroup(t)
	newbie := e.account(t)
	e.seedLegacyOwnerDeparture(t, chat, a)

	ops := map[string]int{}
	ops["AddMembers"], _ = e.addMembers(t, a, chat, newbie)
	ops["RemoveMember"], _ = e.removeMember(t, a, chat, c)
	ops["UpdateMemberRole"], _ = e.setRole(t, a, chat, c.id.String(), "admin")
	ops["UpdateGroup"], _ = e.req(t, http.MethodPut, "/api/groups/"+chat, `{"name":"renamed"}`, a.tok, "")
	ops["DeleteGroup"], _ = e.req(t, http.MethodDelete, "/api/groups/"+chat, "", a.tok, "")
	_, exists := e.ownerOf(t, chat)
	m := e.membership(t, chat, c)
	t.Logf("departed owner: %v; member active=%v role=%s; newbie rows=%d; chat exists=%v", ops, m.leftAt == nil, m.role, e.membership(t, chat, newbie).rows, exists)
	for op, code := range ops {
		if code != http.StatusForbidden {
			t.Errorf("departed owner %s -> %d, want 403", op, code)
		}
	}
	if m.leftAt != nil || m.role != "member" || e.membership(t, chat, newbie).rows != 0 || !exists {
		t.Errorf("a departed owner's request changed the group")
	}
}

// O7. DeleteChat on a group records a departure, so it is a way out - and the owner may not take
// it. As the only admin the existing refusal still applies. A direct chat's owner_id is only who
// opened it, and clearing a direct chat is unchanged.
func TestP72F_O7_OwnerCannotLeaveViaDeleteChat(t *testing.T) {
	e := p72fSetup(t)
	chat, a, _, _ := e.ownerGroup(t)
	code, out := e.deleteChat(t, a, chat)
	active := e.membership(t, chat, a).leftAt == nil

	solo, member := e.account(t), e.account(t)
	soloChat := e.createGroup(t, solo, member)
	soloCode, soloOut := e.deleteChat(t, solo, soloChat)

	x, y := e.account(t), e.account(t)
	direct := e.chat(t, "direct", x.id, y.id) // owner_id = x
	dCode, _ := e.deleteChat(t, x, direct)
	t.Logf("owner DeleteChat (another admin present) -> %d %v (still active=%v); sole-admin owner -> %d %v; direct chat creator clears it -> %d (left_at set=%v)",
		code, out["message"], active, soloCode, soloOut["message"], dCode, e.membership(t, direct, x).leftAt != nil)
	if code != http.StatusForbidden || !active {
		t.Errorf("the owner left the group through DeleteChat: %d %v", code, out)
	}
	if soloCode != http.StatusForbidden || e.membership(t, soloChat, solo).leftAt != nil {
		t.Errorf("sole-admin owner DeleteChat -> %d, want 403", soloCode)
	}
	if dCode != http.StatusOK || e.membership(t, direct, x).leftAt == nil {
		t.Errorf("clearing a direct chat as its creator changed: %d", dCode)
	}
}

// O8. No spelling of an id gets the owner out: upper-cased chat ids on LeaveGroup and DeleteChat,
// upper-cased own and chat ids on self-removal.
func TestP72F_O8_OwnerRuleIgnoresIDSpelling(t *testing.T) {
	e := p72fSetup(t)
	chat, a, _, _ := e.ownerGroup(t)
	up := strings.ToUpper

	leaveUp, _ := e.leaveCode(t, a, up(chat))
	delUp, _ := e.deleteChat(t, a, up(chat))
	selfUp, _ := e.removeAs(t, a, up(chat), up(a.id.String()))
	owner, _ := e.ownerOf(t, chat)
	t.Logf("owner leave (UPPER chat) -> %d; DeleteChat (UPPER chat) -> %d; self-remove (UPPER chat + id) -> %d; owner active=%v owner_id kept=%v",
		leaveUp, delUp, selfUp, e.membership(t, chat, a).leftAt == nil, owner == a.id)
	for name, code := range map[string]int{"leave": leaveUp, "DeleteChat": delUp, "self-remove": selfUp} {
		if code != http.StatusForbidden {
			t.Errorf("owner %s with a re-spelled id -> %d, want 403", name, code)
		}
	}
	if e.membership(t, chat, a).leftAt != nil || owner != a.id {
		t.Errorf("the owner got out")
	}
}

// M2. The Phase 72F security matrix.
func TestP72F_M2_OwnerSecurityMatrix(t *testing.T) {
	e := p72fSetup(t)
	chat, owner, admin, _ := e.ownerGroup(t)
	emptyChat, emptyOwner, _, _ := e.ownerGroup(t)
	legacyChat, legacyOwner, lastAdmin, legacyMember := e.ownerGroup(t)
	newbie := e.account(t)
	e.seedLegacyOwnerDeparture(t, legacyChat, legacyOwner) // lastAdmin is now the only active admin

	type row struct {
		actor, op string
		got, want int
	}
	var rows []row
	add := func(actor, op string, got, want int) { rows = append(rows, row{actor, op, got, want}) }

	c, _ := e.leaveCode(t, owner, chat)
	add("owner", "LeaveGroup", c, http.StatusForbidden)
	c, _ = e.removeAs(t, owner, chat, owner.id.String())
	add("owner", "RemoveMember(self)", c, http.StatusForbidden)
	c, _ = e.removeMember(t, admin, chat, owner)
	add("another admin", "RemoveMember(owner)", c, http.StatusForbidden)
	c, _ = e.leaveCode(t, admin, chat)
	add("another admin", "LeaveGroup (owner remains)", c, http.StatusOK)
	c, _ = e.req(t, http.MethodDelete, "/api/groups/"+emptyChat, "", emptyOwner.tok, "")
	add("owner", "DeleteGroup", c, http.StatusOK)
	c, _ = e.leaveCode(t, lastAdmin, legacyChat)
	add("last admin", "LeaveGroup", c, http.StatusForbidden)
	c, _ = e.addMembers(t, legacyOwner, legacyChat, newbie)
	add("departed owner", "AddMembers", c, http.StatusForbidden)
	c, _ = e.removeMember(t, legacyOwner, legacyChat, legacyMember)
	add("departed owner", "RemoveMember", c, http.StatusForbidden)
	c, _ = e.setRole(t, legacyOwner, legacyChat, legacyMember.id.String(), "admin")
	add("departed owner", "UpdateMemberRole", c, http.StatusForbidden)
	c, _ = e.req(t, http.MethodPut, "/api/groups/"+legacyChat, `{"name":"x"}`, legacyOwner.tok, "")
	add("departed owner", "UpdateGroup", c, http.StatusForbidden)
	c, _ = e.req(t, http.MethodDelete, "/api/groups/"+legacyChat, "", legacyOwner.tok, "")
	add("departed owner", "DeleteGroup", c, http.StatusForbidden)

	pass := 0
	for _, r := range rows {
		ok := r.got == r.want
		if ok {
			pass++
		}
		t.Logf("%-15s %-28s -> %d (want %d) %s", r.actor, r.op, r.got, r.want, map[bool]string{true: "ok", false: "MISMATCH"}[ok])
		if !ok {
			t.Errorf("%s %s -> %d, want %d", r.actor, r.op, r.got, r.want)
		}
	}
	t.Logf("matrix: %d/%d rows as expected", pass, len(rows))
	if e.membership(t, chat, owner).leftAt != nil || e.membership(t, legacyChat, lastAdmin).leftAt != nil {
		t.Errorf("an owner or the last admin got out")
	}
}
