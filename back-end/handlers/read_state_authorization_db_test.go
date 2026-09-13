package handlers

import (
	"fmt"
	"net/http"
	"testing"
	"time"

	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/google/uuid"
)

// PHASE 72C - read/unread authorization.
//
// MarkAsRead writes messages.read_at - ONE column per message, shared by every recipient - and
// broadcasts a "read" event naming the caller into the chat's room. GetUnreadCount counts rows
// whose read_at is still NULL. So whoever may call them can rewrite what every member of the chat
// sees as unread/seen, or learn how much unread traffic a chat has. These tests assert that only a
// participant may do either: an active member of a group, or a participant of a direct chat - where
// left_at only means "cleared from my list" and changes nothing (see canJoinRoom, GetMessages).
//
// Built on the Phase 72B harness (real middleware, real hub, real sockets). Message bodies are
// random hex standing in for ciphertext. Everything runs against an ISOLATED database named by
// TEST_DATABASE_URL.

type p72cEnv struct {
	*p72bEnv
}

func p72cSetup(t *testing.T) *p72cEnv {
	t.Helper()
	e := p72bSetup(t)
	msgs := NewMessageService(e.hub)
	authMW := middleware.AuthMiddleware(e.cfg)
	guard := middleware.DeviceRevocationGuard()
	e.app.Post("/api/chats/:chat_id/read", authMW, guard, msgs.MarkAsRead)
	e.app.Post("/api/messages/:chat_id/unread", authMW, guard, msgs.GetUnreadCount)
	return &p72cEnv{p72bEnv: e}
}

func (e *p72cEnv) markRead(t *testing.T, u p72bUser, chat string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodPost, "/api/chats/"+chat+"/read", "", u.tok, "")
}

// unread returns the status and, when present, the unread_count the endpoint disclosed.
func (e *p72cEnv) unread(t *testing.T, u p72bUser, chat string) (int, int64, bool) {
	t.Helper()
	code, out := e.req(t, http.MethodPost, "/api/messages/"+chat+"/unread", "", u.tok, "")
	v, ok := dataOf(out)["unread_count"].(float64)
	return code, int64(v), ok
}

// unreadRows is the shared read state straight from the database: messages in chat whose read_at
// is still NULL.
func (e *p72cEnv) unreadRows(t *testing.T, chat string) int64 {
	t.Helper()
	var n int64
	if err := e.db.Model(&models.Message{}).Where("chat_id = ? AND read_at IS NULL", chat).Count(&n).Error; err != nil {
		t.Fatalf("unread rows: %v", err)
	}
	return n
}

// ============================================================ outsiders

// C1. Someone who was never in the group cannot mark its messages read: the shared read state is
// untouched and no "read" event naming them reaches the members.
func TestP72C_C1_OutsiderCannotMarkGroupRead(t *testing.T) {
	e := p72cSetup(t)
	owner, member, outsider := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member)
	watch := e.dial(t, owner.tok, owner.id)
	watch.join(t, chat)
	settle()
	e.sendOK(t, member, chat)
	e.sendOK(t, member, chat)

	before := e.unreadRows(t, chat)
	code, out := e.markRead(t, outsider, chat)
	after := e.unreadRows(t, chat)
	ev, gotEvent := watch.next("read", 1500*time.Millisecond)
	t.Logf("outsider mark-read on group -> %d; unread rows %d -> %d; read event to members=%v", code, before, after, gotEvent)
	if code != http.StatusForbidden {
		t.Errorf("an outsider marked a group read: %d %v", code, out)
	}
	if after != before {
		t.Errorf("an outsider changed the group's shared read state: unread rows %d -> %d", before, after)
	}
	if gotEvent {
		t.Errorf("an outsider's mark-read broadcast a read event into the group: %v", ev)
	}
}

// C2. Nor can an outsider mark a direct chat between two other people read.
func TestP72C_C2_OutsiderCannotMarkDirectChatRead(t *testing.T) {
	e := p72cSetup(t)
	a, b, outsider := e.account(t), e.account(t), e.account(t)
	chat := e.chat(t, "direct", a.id, b.id)
	e.sendOK(t, a, chat)

	before := e.unreadRows(t, chat)
	code, out := e.markRead(t, outsider, chat)
	after := e.unreadRows(t, chat)
	t.Logf("outsider mark-read on direct chat -> %d; unread rows %d -> %d", code, before, after)
	if code != http.StatusForbidden {
		t.Errorf("an outsider marked a direct chat read: %d %v", code, out)
	}
	if after != before {
		t.Errorf("an outsider flipped the recipient's messages to read: unread rows %d -> %d", before, after)
	}
}

// C3. An outsider learns nothing from the unread endpoint - not a group's count, not a direct
// chat's count, and not even whether the chat exists: every one of those answers is the same 403.
func TestP72C_C3_OutsiderCannotReadUnreadState(t *testing.T) {
	e := p72cSetup(t)
	owner, member, outsider := e.account(t), e.account(t), e.account(t)
	group := e.createGroup(t, owner, member)
	direct := e.chat(t, "direct", owner.id, member.id)
	e.sendOK(t, member, group)
	e.sendOK(t, member, direct)

	gCode, gCount, gDisclosed := e.unread(t, outsider, group)
	dCode, dCount, dDisclosed := e.unread(t, outsider, direct)
	nCode, nCount, nDisclosed := e.unread(t, outsider, uuid.New().String())
	t.Logf("outsider unread: group -> %d (count %d disclosed=%v), direct -> %d (count %d disclosed=%v), nonexistent -> %d (count %d disclosed=%v)",
		gCode, gCount, gDisclosed, dCode, dCount, dDisclosed, nCode, nCount, nDisclosed)
	for _, r := range []struct {
		name      string
		code      int
		disclosed bool
	}{{"group", gCode, gDisclosed}, {"direct", dCode, dDisclosed}, {"nonexistent", nCode, nDisclosed}} {
		if r.code != http.StatusForbidden || r.disclosed {
			t.Errorf("outsider unread on %s chat -> %d (count disclosed=%v), want 403 and nothing", r.name, r.code, r.disclosed)
		}
	}
}

// ============================================================ departed group members

// C4. A member who left - or was removed - cannot mark the group read: in particular they cannot
// flip messages sent after their departure, and no read event goes out in their name.
func TestP72C_C4_DepartedMemberCannotMarkGroupRead(t *testing.T) {
	e := p72cSetup(t)
	owner, left, removed := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, left, removed)
	watch := e.dial(t, owner.tok, owner.id)
	watch.join(t, chat)
	settle()
	e.leave(t, left, chat)
	if code, out := e.removeMember(t, owner, chat, removed); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	p72bTick()
	// Sent after both departures, by the owner, so unread for everyone but the owner.
	e.sendOK(t, owner, chat)
	e.sendOK(t, owner, chat)

	for _, who := range []struct {
		name string
		u    p72bUser
	}{{"left", left}, {"removed", removed}} {
		before := e.unreadRows(t, chat)
		code, out := e.markRead(t, who.u, chat)
		after := e.unreadRows(t, chat)
		ev, gotEvent := watch.next("read", 1200*time.Millisecond)
		t.Logf("%s member mark-read -> %d; unread rows %d -> %d; read event=%v", who.name, code, before, after, gotEvent)
		if code != http.StatusForbidden {
			t.Errorf("a %s member marked the group read: %d %v", who.name, code, out)
		}
		if after != before {
			t.Errorf("a %s member changed post-departure read state: unread rows %d -> %d", who.name, before, after)
		}
		if gotEvent {
			t.Errorf("a %s member's mark-read broadcast into the group: %v", who.name, ev)
		}
	}
}

// C5. A member who left - or was removed - cannot read the group's unread count, which after their
// departure counts messages they are not entitled to know about.
func TestP72C_C5_DepartedMemberCannotReadUnreadState(t *testing.T) {
	e := p72cSetup(t)
	owner, left, removed := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, left, removed)
	e.leave(t, left, chat)
	if code, out := e.removeMember(t, owner, chat, removed); code != http.StatusOK {
		t.Fatalf("remove: %d %v", code, out)
	}
	p72bTick()
	e.sendOK(t, owner, chat)
	e.sendOK(t, owner, chat)
	e.sendOK(t, owner, chat)

	for _, who := range []struct {
		name string
		u    p72bUser
	}{{"left", left}, {"removed", removed}} {
		code, count, disclosed := e.unread(t, who.u, chat)
		t.Logf("%s member unread -> %d (count %d disclosed=%v)", who.name, code, count, disclosed)
		if code != http.StatusForbidden || disclosed {
			t.Errorf("a %s member read the group's unread state: %d (count %d)", who.name, code, count)
		}
	}
}

// ============================================================ participants keep working

// C6. An active group member marks the group read: the messages others sent become read, their own
// last_read_at is stamped, and the members' sockets get the read event.
func TestP72C_C6_ActiveGroupMemberCanMarkRead(t *testing.T) {
	e := p72cSetup(t)
	owner, member, other := e.account(t), e.account(t), e.account(t)
	chat := e.createGroup(t, owner, member, other)
	watch := e.dial(t, owner.tok, owner.id)
	watch.join(t, chat)
	settle()
	e.sendOK(t, owner, chat)
	e.sendOK(t, owner, chat)
	e.sendOK(t, member, chat) // the member's own message is not theirs to mark

	uCode, uCount, _ := e.unread(t, member, chat)
	code, out := e.markRead(t, member, chat)
	var fromOthersUnread, ownUnread int64
	e.db.Model(&models.Message{}).Where("chat_id = ? AND sender_id <> ? AND read_at IS NULL", chat, member.id).Count(&fromOthersUnread)
	e.db.Model(&models.Message{}).Where("chat_id = ? AND sender_id = ? AND read_at IS NULL", chat, member.id).Count(&ownUnread)
	var p models.ChatParticipant
	e.db.Where("chat_id = ? AND user_id = ?", chat, member.id).First(&p)
	ev, gotEvent := watch.next("read", 3*time.Second)
	t.Logf("active member unread before -> %d (count %d); mark-read -> %d; others' unread rows now %d, own unread rows %d, last_read_at set=%v, read event=%v",
		uCode, uCount, code, fromOthersUnread, ownUnread, p.LastReadAt != nil, gotEvent)
	if uCode != http.StatusOK || uCount != 2 {
		t.Fatalf("active member unread count -> %d (count %d), want 200 and 2", uCode, uCount)
	}
	if code != http.StatusOK || fromOthersUnread != 0 || ownUnread != 1 || p.LastReadAt == nil {
		t.Fatalf("active member mark-read -> %d %v", code, out)
	}
	if !gotEvent {
		t.Fatalf("members did not get the read event")
	}
	data, _ := ev["data"].(map[string]any)
	if fmt.Sprint(data["reader_id"]) != member.id.String() {
		t.Fatalf("read event names %v, want the reader %s", data["reader_id"], member.id)
	}

	// Observation only - the shared read_at model is unchanged by 72C: one member's read marks the
	// message read for every recipient.
	_, otherCount, _ := e.unread(t, other, chat)
	t.Logf("observation (shared read_at, unchanged): after one member's mark-read, another member's unread count is %d", otherCount)
}

// C7. Direct chats keep working for both participants - including the one who cleared the chat off
// their list, for whom left_at changes nothing.
func TestP72C_C7_DirectChatParticipantsUnchanged(t *testing.T) {
	e := p72cSetup(t)
	a, b := e.account(t), e.account(t)
	chat := e.chat(t, "direct", a.id, b.id)
	e.sendOK(t, a, chat)
	e.sendOK(t, a, chat)

	code, count, _ := e.unread(t, b, chat)
	if code != http.StatusOK || count != 2 {
		t.Fatalf("direct participant unread -> %d (count %d), want 200 and 2", code, count)
	}
	if code, out := e.req(t, http.MethodDelete, "/api/chats/"+chat, "", b.tok, ""); code != http.StatusOK {
		t.Fatalf("delete chat: %d %v", code, out)
	}
	if e.membership(t, chat, b).leftAt == nil {
		t.Fatalf("DeleteChat did not record left_at")
	}
	clearedCode, clearedCount, _ := e.unread(t, b, chat)
	markCode, out := e.markRead(t, b, chat)
	after := e.unreadRows(t, chat)
	t.Logf("cleared direct chat: unread -> %d (count %d), mark-read -> %d, unread rows now %d", clearedCode, clearedCount, markCode, after)
	if clearedCode != http.StatusOK || clearedCount != 2 {
		t.Fatalf("participant who cleared the direct chat: unread -> %d (count %d), want 200 and 2", clearedCode, clearedCount)
	}
	if markCode != http.StatusOK || after != 0 {
		t.Fatalf("participant who cleared the direct chat could not mark it read: %d %v (unread rows %d)", markCode, out, after)
	}
}

// C8. The request shape is unchanged: a malformed chat id is still a 400 for both endpoints.
func TestP72C_C8_MalformedChatIDStillRejected(t *testing.T) {
	e := p72cSetup(t)
	u := e.account(t)
	markCode, _ := e.markRead(t, u, "not-a-uuid")
	unreadCode, _, _ := e.unread(t, u, "not-a-uuid")
	t.Logf("malformed chat id: mark-read -> %d, unread -> %d", markCode, unreadCode)
	if markCode != http.StatusBadRequest || unreadCode != http.StatusBadRequest {
		t.Fatalf("malformed chat id: mark-read -> %d, unread -> %d, want 400 and 400", markCode, unreadCode)
	}
}
