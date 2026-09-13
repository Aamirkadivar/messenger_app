package handlers

import (
	"net/http"
	"strings"
	"testing"

	"messenger-app/models"

	"github.com/google/uuid"
)

// PHASE 72D - group lifecycle: active admins, the last-active-admin rule, and every path a
// member can take out of a group.
//
// The contract these tests hold the server to comes from the code as it stands:
//
//   - the owner is created as an admin and their role is immutable ("the one account guaranteed to
//     be able to administer the group" - UpdateMemberRole); nobody else can remove them;
//   - an admin may leave while another admin remains; the only admin may not ("Group owner cannot
//     leave while still being the only admin" - LeaveGroup, and the Android client's doc comment);
//   - since Phase 72B a departed admin or owner holds no authority at all.
//
// So "another admin" must mean another ACTIVE admin, and every way out of a group - LeaveGroup,
// removing yourself through RemoveMember, clearing the group with DeleteChat, handing back the admin
// role - is the same departure and gets the same rule. Phase 72F settled OWNERSHIP: the owner may not
// leave at all while they are the owner (L5). States with a departed owner come from groups created
// under the earlier contract and are seeded (seedLegacyOwnerDeparture).
//
// Built on the Phase 72B harness (real middleware and handlers). Everything runs against an
// ISOLATED database named by TEST_DATABASE_URL.

type p72dEnv struct {
	*p72bEnv
}

func p72dSetup(t *testing.T) *p72dEnv {
	t.Helper()
	return &p72dEnv{p72bEnv: p72bSetup(t)}
}

func (e *p72dEnv) leaveCode(t *testing.T, u p72bUser, chat string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodPost, "/api/groups/"+chat+"/leave", "", u.tok, "")
}

// removeAs calls RemoveMember with the path segments exactly as given, so a test can spell the
// ids differently from how the server stores them.
func (e *p72dEnv) removeAs(t *testing.T, by p72bUser, chatPath, memberPath string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodDelete, "/api/groups/"+chatPath+"/members/"+memberPath, "", by.tok, "")
}

func (e *p72dEnv) setRole(t *testing.T, by p72bUser, chatPath, memberPath, role string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodPut, "/api/groups/"+chatPath+"/members/"+memberPath+"/role",
		p72bJSON(map[string]string{"role": role}), by.tok, "")
}

func (e *p72dEnv) deleteChat(t *testing.T, u p72bUser, chat string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodDelete, "/api/chats/"+chat, "", u.tok, "")
}

// activeAdmins counts the group's admins who have not left - the quantity the lifecycle rule is
// about.
func (e *p72dEnv) activeAdmins(t *testing.T, chat string) int64 {
	t.Helper()
	var n int64
	if err := e.db.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND role = ? AND left_at IS NULL", chat, "admin").Count(&n).Error; err != nil {
		t.Fatalf("active admins: %v", err)
	}
	return n
}

func (e *p72dEnv) ownerOf(t *testing.T, chat string) (uuid.UUID, bool) {
	t.Helper()
	var c models.Chat
	if err := e.db.First(&c, "id = ?", chat).Error; err != nil {
		return uuid.Nil, false
	}
	return c.OwnerID, true
}

// ============================================================ leaving

// L1. The only active admin cannot leave, and the group stays administrable.
func TestP72D_L1_LastActiveAdminCannotLeave(t *testing.T) {
	e := p72dSetup(t)
	owner, member, newbie := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)

	code, out := e.leaveCode(t, owner, chat)
	m := e.membership(t, chat, owner)
	admins := e.activeAdmins(t, chat)
	addCode, _ := e.addMembers(t, owner, chat, newbie)
	t.Logf("sole admin leave -> %d; still active=%v; active admins=%d; can still add=%d", code, m.leftAt == nil, admins, addCode)
	if code != http.StatusForbidden || m.leftAt != nil || admins != 1 {
		t.Errorf("the only active admin left the group: %d %v (active admins now %d)", code, out, admins)
	}
	if addCode != http.StatusOK {
		t.Errorf("the group is no longer administrable: add -> %d", addCode)
	}
}

// L2. Removing yourself through RemoveMember is leaving, and gets the same rule: the only active
// admin cannot do it - whether they are the owner, a non-owner admin left in charge after the
// owner went, or spell their own id in a different case.
func TestP72D_L2_LastActiveAdminCannotSelfRemove(t *testing.T) {
	e := p72dSetup(t)

	// (a) the owner, as the only admin
	owner, member := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)
	codeA, _ := e.removeAs(t, owner, chat, owner.id.String())
	activeA := e.membership(t, chat, owner).leftAt == nil
	adminsA := e.activeAdmins(t, chat)

	// (b) a non-owner admin who is the last active admin after the owner left
	owner2, admin2, member2 := e.account(t), e.account(t), e.account(t)
	chat2 := e.createGroup(t, owner2, admin2, member2)
	e.promote(t, owner2, chat2, admin2)
	e.seedLegacyOwnerDeparture(t, chat2, owner2)
	codeB, _ := e.removeAs(t, admin2, chat2, admin2.id.String())
	activeB := e.membership(t, chat2, admin2).leftAt == nil
	adminsB := e.activeAdmins(t, chat2)

	// (c) the same, with the caller's own id upper-cased
	owner3, admin3, member3 := e.account(t), e.account(t), e.account(t)
	chat3 := e.createGroup(t, owner3, admin3, member3)
	e.promote(t, owner3, chat3, admin3)
	e.seedLegacyOwnerDeparture(t, chat3, owner3)
	codeC, _ := e.removeAs(t, admin3, chat3, strings.ToUpper(admin3.id.String()))
	activeC := e.membership(t, chat3, admin3).leftAt == nil
	adminsC := e.activeAdmins(t, chat3)

	t.Logf("self-remove as last active admin: owner -> %d (active=%v, admins=%d); admin after owner left -> %d (active=%v, admins=%d); upper-cased own id -> %d (active=%v, admins=%d)",
		codeA, activeA, adminsA, codeB, activeB, adminsB, codeC, activeC, adminsC)
	for _, r := range []struct {
		name   string
		code   int
		active bool
		admins int64
	}{{"owner as only admin", codeA, activeA, adminsA}, {"admin left in charge", codeB, activeB, adminsB},
		{"upper-cased own id", codeC, activeC, adminsC}} {
		if r.code != http.StatusForbidden || !r.active || r.admins != 1 {
			t.Errorf("%s: self-removal -> %d, still active=%v, active admins=%d - want 403, active, 1", r.name, r.code, r.active, r.admins)
		}
	}
}

// L2b. Clearing the group off your list with DeleteChat is also leaving it (it records left_at),
// so the only active admin cannot use it to walk out either.
func TestP72D_L2b_LastActiveAdminCannotDepartViaDeleteChat(t *testing.T) {
	e := p72dSetup(t)
	owner, member := e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)

	code, out := e.deleteChat(t, owner, chat)
	active := e.membership(t, chat, owner).leftAt == nil
	admins := e.activeAdmins(t, chat)
	t.Logf("sole admin DeleteChat on the group -> %d; still active=%v; active admins=%d", code, active, admins)
	if code != http.StatusForbidden || !active || admins != 1 {
		t.Errorf("the only active admin left through DeleteChat: %d %v (active admins now %d)", code, out, admins)
	}
}

// L3. Departed admins do not count. With the owner and a second admin gone, the one admin left is
// the last active admin and cannot leave - the historical admin rows must not stand in for them.
// They do not block anything either: once another member is promoted, leaving works.
func TestP72D_L3_DepartedAdminsDoNotCount(t *testing.T) {
	e := p72dSetup(t)
	owner, a, b, c := e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, a, b, c)
	e.promote(t, owner, chat, a)
	e.promote(t, owner, chat, b)
	e.seedLegacyOwnerDeparture(t, chat, owner) // a and b remain
	e.leave(t, b, chat)                        // a remains; owner and b are departed admins

	code, out := e.leaveCode(t, a, chat)
	active := e.membership(t, chat, a).leftAt == nil
	admins := e.activeAdmins(t, chat)
	t.Logf("last active admin (two departed admin rows present) leave -> %d; still active=%v; active admins=%d", code, active, admins)
	if code != http.StatusForbidden || !active || admins != 1 {
		t.Fatalf("departed admins were counted: the last active admin left (%d %v), active admins now %d", code, out, admins)
	}

	e.promote(t, a, chat, c)
	code2, _ := e.leaveCode(t, a, chat)
	t.Logf("after promoting another member: leave -> %d; active admins=%d", code2, e.activeAdmins(t, chat))
	if code2 != http.StatusOK || e.activeAdmins(t, chat) != 1 {
		t.Fatalf("with another active admin present, leaving must work: %d", code2)
	}
}

// L4. With several active admins, one of them leaving is normal and the rest carry on.
func TestP72D_L4_MultipleActiveAdminsLeave(t *testing.T) {
	e := p72dSetup(t)
	owner, a, b, c, newbie := e.account(t), e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, a, b, c)
	e.promote(t, owner, chat, a)
	e.promote(t, owner, chat, b)

	code, out := e.leaveCode(t, a, chat)
	aLeft := e.membership(t, chat, a).leftAt != nil
	bAdmin := e.membership(t, chat, b)
	addCode, _ := e.addMembers(t, b, chat, newbie)
	t.Logf("admin leave with other admins present -> %d; departed=%v; b active admin=%v; active admins=%d; b can add=%d",
		code, aLeft, bAdmin.leftAt == nil && bAdmin.role == "admin", e.activeAdmins(t, chat), addCode)
	if code != http.StatusOK || !aLeft || bAdmin.leftAt != nil || bAdmin.role != "admin" || e.activeAdmins(t, chat) != 2 || addCode != http.StatusOK {
		t.Fatalf("multi-admin leave broke: %d %v", code, out)
	}
}

// L5. Owner departure, under the Phase 72F contract: the owner may not leave while they are the
// owner, even with another active admin present. Ownership - and with it the owner-only DeleteGroup -
// stays with an owner who is still in the group, and the other admin keeps administering. (Under the
// earlier contract this leave succeeded and left owner_id naming a departed owner.)
func TestP72D_L5_OwnerDepartureEstablishedBehaviour(t *testing.T) {
	e := p72dSetup(t)
	owner, admin, member, newbie := e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)

	code, out := e.leaveCode(t, owner, chat)
	active := e.membership(t, chat, owner).leftAt == nil
	ownerID, _ := e.ownerOf(t, chat)
	addCode, _ := e.addMembers(t, admin, chat, newbie)
	t.Logf("owner leave with another active admin -> %d; owner still active=%v; owner_id kept=%v; other admin can add -> %d; active admins=%d",
		code, active, ownerID == owner.id, addCode, e.activeAdmins(t, chat))
	if code != http.StatusForbidden || !active || ownerID != owner.id || addCode != http.StatusOK || e.activeAdmins(t, chat) != 2 {
		t.Fatalf("owner departure contract broke: %d %v", code, out)
	}
}

// L6. A departed owner has no authority left: every administrative endpoint refuses and nothing
// changes.
func TestP72D_L6_DepartedOwnerCannotRegainAuthority(t *testing.T) {
	e := p72dSetup(t)
	owner, admin, member, newbie := e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)
	e.seedLegacyOwnerDeparture(t, chat, owner)

	add, _ := e.addMembers(t, owner, chat, newbie)
	rem, _ := e.removeMember(t, owner, chat, member)
	role, _ := e.setRole(t, owner, chat, member.id.String(), "admin")
	upd, _ := e.req(t, http.MethodPut, "/api/groups/"+chat, `{"name":"renamed by departed owner"}`, owner.tok, "")
	del, _ := e.req(t, http.MethodDelete, "/api/groups/"+chat, "", owner.tok, "")
	m := e.membership(t, chat, member)
	_, exists := e.ownerOf(t, chat)
	t.Logf("departed owner: add -> %d, remove -> %d, role -> %d, update -> %d, delete -> %d; member active=%v role=%s; newbie rows=%d; chat exists=%v",
		add, rem, role, upd, del, m.leftAt == nil, m.role, e.membership(t, chat, newbie).rows, exists)
	for _, r := range []struct {
		op   string
		code int
	}{{"add", add}, {"remove", rem}, {"role", role}, {"update", upd}, {"delete", del}} {
		if r.code != http.StatusForbidden {
			t.Errorf("departed owner %s -> %d, want 403", r.op, r.code)
		}
	}
	if m.leftAt != nil || m.role != "member" || e.membership(t, chat, newbie).rows != 0 || !exists {
		t.Errorf("a departed owner's request changed the group")
	}
}

// ============================================================ removing

// L7. Nobody can remove the group's last line of administration: members cannot remove admins,
// admins cannot remove the owner, and an admin removal always leaves the remover in place.
func TestP72D_L7_RemovingAdminsCannotStrandTheGroup(t *testing.T) {
	e := p72dSetup(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)

	mOwner, _ := e.removeMember(t, member, chat, owner)
	mAdmin, _ := e.removeMember(t, member, chat, admin)
	aOwner, _ := e.removeMember(t, admin, chat, owner)
	t.Logf("member removes owner -> %d, member removes admin -> %d, admin removes owner -> %d; active admins=%d",
		mOwner, mAdmin, aOwner, e.activeAdmins(t, chat))
	if mOwner != http.StatusForbidden || mAdmin != http.StatusForbidden || aOwner != http.StatusForbidden {
		t.Fatalf("an admin was removed by someone without the authority: %d %d %d", mOwner, mAdmin, aOwner)
	}
	if e.activeAdmins(t, chat) != 2 {
		t.Fatalf("active admins changed: %d", e.activeAdmins(t, chat))
	}
}

// L7b. The owner's protections cannot be sidestepped by spelling an id differently. Postgres
// accepts any spelling of a uuid, while chats.id is text and the owner checks compare strings.
func TestP72D_L7b_OwnerProtectionIgnoresIDSpelling(t *testing.T) {
	e := p72dSetup(t)
	// One fresh group per spelling, so one bypass cannot mask another.
	group := func() (string, p72bUser, p72bUser) {
		owner, admin, member := e.account(t), e.account(t), e.account(t)
		chat := e.createGroup(t, owner, admin, member)
		e.promote(t, owner, chat, admin)
		return chat, owner, admin
	}

	chat1, owner1, admin1 := group()
	upOwner, _ := e.removeAs(t, admin1, chat1, strings.ToUpper(owner1.id.String()))
	afterOwnerID := e.membership(t, chat1, owner1).leftAt == nil

	chat2, owner2, admin2 := group()
	upChat, _ := e.removeAs(t, admin2, strings.ToUpper(chat2), owner2.id.String())
	afterChatID := e.membership(t, chat2, owner2).leftAt == nil

	chat3, owner3, admin3 := group()
	demote, _ := e.setRole(t, admin3, chat3, strings.ToUpper(owner3.id.String()), "member")
	o := e.membership(t, chat3, owner3)
	t.Logf("admin removes owner via upper-case owner id -> %d (owner active=%v); via upper-case chat id -> %d (owner active=%v); demotes owner via upper-case id -> %d (owner role=%s active=%v)",
		upOwner, afterOwnerID, upChat, afterChatID, demote, o.role, o.leftAt == nil)
	if upOwner != http.StatusForbidden || !afterOwnerID {
		t.Errorf("an admin removed the owner by upper-casing the owner's id: %d", upOwner)
	}
	if upChat != http.StatusForbidden || !afterChatID {
		t.Errorf("an admin removed the owner by upper-casing the chat id: %d", upChat)
	}
	if demote != http.StatusForbidden || o.role != "admin" || o.leftAt != nil {
		t.Errorf("an admin demoted the owner by upper-casing the owner's id: %d (role %s)", demote, o.role)
	}
}

// L8. Ordinary administration is unaffected: an admin removes a member.
func TestP72D_L8_RemovingANonAdminStaysValid(t *testing.T) {
	e := p72dSetup(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)

	code, out := e.removeMember(t, admin, chat, member)
	t.Logf("admin removes member -> %d; member departed=%v; active admins=%d", code, e.membership(t, chat, member).leftAt != nil, e.activeAdmins(t, chat))
	if code != http.StatusOK || e.membership(t, chat, member).leftAt == nil || e.activeAdmins(t, chat) != 2 {
		t.Fatalf("normal removal broke: %d %v", code, out)
	}
}

// L9. An admin may remove another (non-owner) admin; the remover stays, so this can never leave
// the group without an active admin.
func TestP72D_L9_AdminCanRemoveAnotherAdmin(t *testing.T) {
	e := p72dSetup(t)
	owner, a, b, member := e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, a, b, member)
	e.promote(t, owner, chat, a)
	e.promote(t, owner, chat, b)

	code, out := e.removeMember(t, a, chat, b)
	aState := e.membership(t, chat, a)
	t.Logf("admin removes admin -> %d; removed departed=%v; remover active admin=%v; active admins=%d",
		code, e.membership(t, chat, b).leftAt != nil, aState.leftAt == nil && aState.role == "admin", e.activeAdmins(t, chat))
	if code != http.StatusOK || e.membership(t, chat, b).leftAt == nil || aState.leftAt != nil || e.activeAdmins(t, chat) != 2 {
		t.Fatalf("admin-on-admin removal broke: %d %v", code, out)
	}
}

// L10. Removing someone who already left changes nothing: not left_at, not the row count, not the
// role, not the owner (Phase 72B's no-op).
func TestP72D_L10_RepeatedRemovalIsANoOp(t *testing.T) {
	e := p72dSetup(t)
	owner, a, b := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, a, b)
	e.promote(t, owner, chat, a)
	e.promote(t, owner, chat, b)
	if code, out := e.removeMember(t, a, chat, b); code != http.StatusOK {
		t.Fatalf("first removal: %d %v", code, out)
	}
	before := e.membership(t, chat, b)
	admins := e.activeAdmins(t, chat)
	p72bTick()

	code, _ := e.removeMember(t, a, chat, b)
	after := e.membership(t, chat, b)
	ownerID, _ := e.ownerOf(t, chat)
	t.Logf("repeat removal -> %d; left_at unchanged=%v rows=%d role=%s owner unchanged=%v active admins %d -> %d",
		code, after.leftAt != nil && after.leftAt.Equal(*before.leftAt), after.rows, after.role, ownerID == owner.id, admins, e.activeAdmins(t, chat))
	if after.leftAt == nil || !after.leftAt.Equal(*before.leftAt) || after.rows != 1 || after.role != before.role ||
		ownerID != owner.id || e.activeAdmins(t, chat) != admins {
		t.Fatalf("a repeated removal changed lifecycle state")
	}
}

// ============================================================ handing back the admin role

// L11. The last active admin cannot hand the admin role back either - that would leave the group
// with no one able to administer it. Another admin still can, while someone remains.
func TestP72D_L11_LastActiveAdminCannotSelfDemote(t *testing.T) {
	e := p72dSetup(t)
	owner, admin, member := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, member)
	e.promote(t, owner, chat, admin)
	e.seedLegacyOwnerDeparture(t, chat, owner) // admin is now the only active admin

	code, out := e.setRole(t, admin, chat, admin.id.String(), "member")
	st := e.membership(t, chat, admin)
	t.Logf("last active admin self-demote -> %d; role now %s; active admins=%d", code, st.role, e.activeAdmins(t, chat))
	if code != http.StatusForbidden || st.role != "admin" || e.activeAdmins(t, chat) != 1 {
		t.Errorf("the last active admin handed back the role: %d %v (active admins now %d)", code, out, e.activeAdmins(t, chat))
	}

	// Control: with a second active admin, stepping down is fine.
	e.promote(t, admin, chat, member)
	code2, _ := e.setRole(t, admin, chat, admin.id.String(), "member")
	t.Logf("self-demote with another active admin -> %d; active admins=%d", code2, e.activeAdmins(t, chat))
	if code2 != http.StatusOK || e.activeAdmins(t, chat) != 1 {
		t.Errorf("stepping down with another active admin present -> %d", code2)
	}
}

// ============================================================ negative matrix

// M1. The lifecycle authorization matrix, each row against a fresh expectation and a check that
// nothing changed.
func TestP72D_M1_LifecycleAuthorizationMatrix(t *testing.T) {
	e := p72dSetup(t)
	owner, admin, depAdmin, depMember, member, outsider, newbie := e.account(t), e.account(t), e.account(t), e.account(t), e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, admin, depAdmin, depMember, member)
	e.promote(t, owner, chat, admin)
	e.promote(t, owner, chat, depAdmin)
	e.leave(t, depAdmin, chat)
	e.leave(t, depMember, chat)

	// A group whose owner left, for the departed-owner row.
	dOwner, dAdmin := e.account(t), e.account(t)
	dChat := e.createGroup(t, dOwner, dAdmin)
	e.promote(t, dOwner, dChat, dAdmin)
	e.seedLegacyOwnerDeparture(t, dChat, dOwner)

	// A group with a single admin, for the last-admin rows.
	solo, soloMember := e.account(t), e.account(t)
	soloChat := e.createGroup(t, solo, soloMember)

	type row struct {
		actor, op string
		got, want int
	}
	var rows []row
	add := func(actor, op string, got, want int) { rows = append(rows, row{actor, op, got, want}) }

	c, _ := e.leaveCode(t, outsider, chat)
	add("outsider", "LeaveGroup", c, http.StatusNotFound)
	c, _ = e.removeMember(t, outsider, chat, member)
	add("outsider", "RemoveMember", c, http.StatusForbidden)
	c, _ = e.leaveCode(t, depMember, chat)
	add("departed member", "LeaveGroup", c, http.StatusNotFound)
	c, _ = e.addMembers(t, depAdmin, chat, newbie)
	add("departed admin", "AddMembers", c, http.StatusForbidden)
	c, _ = e.removeMember(t, depAdmin, chat, member)
	add("departed admin", "RemoveMember", c, http.StatusForbidden)
	c, _ = e.setRole(t, depAdmin, chat, member.id.String(), "admin")
	add("departed admin", "UpdateMemberRole", c, http.StatusForbidden)
	c, _ = e.req(t, http.MethodPut, "/api/groups/"+chat, `{"name":"x"}`, depAdmin.tok, "")
	add("departed admin", "UpdateGroup", c, http.StatusForbidden)
	c, _ = e.req(t, http.MethodDelete, "/api/groups/"+dChat, "", dOwner.tok, "")
	add("departed owner", "DeleteGroup", c, http.StatusForbidden)
	c, _ = e.addMembers(t, member, chat, newbie)
	add("active member", "AddMembers", c, http.StatusForbidden)
	c, _ = e.setRole(t, member, chat, member.id.String(), "admin")
	add("active member", "UpdateMemberRole", c, http.StatusForbidden)
	c, _ = e.leaveCode(t, solo, soloChat)
	add("last active admin", "LeaveGroup", c, http.StatusForbidden)
	c, _ = e.removeAs(t, solo, soloChat, solo.id.String())
	add("last active admin", "RemoveMember(self)", c, http.StatusForbidden)
	c, _ = e.deleteChat(t, solo, soloChat)
	add("last active admin", "DeleteChat", c, http.StatusForbidden)
	c, _ = e.setRole(t, solo, soloChat, solo.id.String(), "member")
	// The owner's role is immutable, so this is refused for that reason too.
	add("last active admin", "UpdateMemberRole(self demote)", c, http.StatusForbidden)
	c, _ = e.addMembers(t, admin, chat, newbie)
	add("active admin", "AddMembers", c, http.StatusOK)

	pass := 0
	for _, r := range rows {
		ok := r.got == r.want
		if ok {
			pass++
		}
		t.Logf("%-18s %-30s -> %d (want %d) %s", r.actor, r.op, r.got, r.want, map[bool]string{true: "ok", false: "MISMATCH"}[ok])
		if !ok {
			t.Errorf("%s %s -> %d, want %d", r.actor, r.op, r.got, r.want)
		}
	}
	t.Logf("matrix: %d/%d rows as expected", pass, len(rows))

	// Nothing the refused rows attempted may have changed the groups.
	if m := e.membership(t, chat, member); m.leftAt != nil || m.role != "member" {
		t.Errorf("a refused request changed the member (left=%v role=%s)", m.leftAt != nil, m.role)
	}
	if _, exists := e.ownerOf(t, dChat); !exists {
		t.Errorf("the departed owner's delete removed the group")
	}
	if e.membership(t, soloChat, solo).leftAt != nil || e.activeAdmins(t, soloChat) != 1 {
		t.Errorf("the solo group lost its only active admin")
	}
}
