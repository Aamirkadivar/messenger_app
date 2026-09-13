package handlers

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"sort"
	"sync"
	"testing"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"

	fws "github.com/fasthttp/websocket"
	"github.com/google/uuid"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// PHASE 71 - durable E2EE message delivery, driven through the REAL handlers, the REAL
// middleware chain main.go wires (AuthMiddleware -> DeviceRevocationGuard, WebSocketAuth ->
// WebSocketDeviceGuard), the REAL hub and a real socket.
//
// The invariant under test:
//
//	encrypted payload -> durable server persistence -> acceptance -> recipient offline or online
//	-> reconnect -> ordered catch-up of EVERYTHING -> exactly one logical message per retry.
//
// Message bodies are random hex standing in for client ciphertext. The server never decrypts, so
// no real content or key material is involved anywhere in this file.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

type p71Env struct {
	*p66Env
	hub    *websocket.Hub
	wsBase string

	mu    sync.Mutex
	users []uuid.UUID
}

func p71Setup(t *testing.T) *p71Env {
	t.Helper()
	base := p66Setup(t)
	hub := websocket.NewHub()
	go hub.Run()
	svc := NewMessageService(hub)

	authMW := middleware.AuthMiddleware(base.cfg)
	guard := middleware.DeviceRevocationGuard()
	base.app.Post("/api/messages", authMW, guard, svc.SendMessage)
	base.app.Get("/api/messages/:chat_id", authMW, guard, svc.GetMessages)
	base.app.Get("/ws", middleware.WebSocketAuth(base.cfg), middleware.WebSocketDeviceGuard(),
		websocket.WSHandler(hub))

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = base.app.Listener(ln) }()

	e := &p71Env{p66Env: base, hub: hub, wsBase: "ws://" + ln.Addr().String()}
	// Runs AFTER every socket's own cleanup (LIFO) and BEFORE p66Setup restores database.DB: the
	// hub writes presence through database.DB when the last socket of a user goes away, so every
	// user has to be observed offline while the test database is still the live handle.
	t.Cleanup(func() {
		e.mu.Lock()
		users := append([]uuid.UUID(nil), e.users...)
		e.mu.Unlock()
		deadline := time.Now().Add(3 * time.Second)
		for _, u := range users {
			for hub.IsUserOnline(u) && time.Now().Before(deadline) {
				time.Sleep(20 * time.Millisecond)
			}
		}
		time.Sleep(250 * time.Millisecond) // leavePresence runs on its own goroutine
		_ = base.app.Shutdown()
	})
	return e
}

func (e *p71Env) user(t *testing.T) (uuid.UUID, string) {
	t.Helper()
	a := e.newAccount(t, false)
	return a.id, e.fullLogin(t, a)
}

func (e *p71Env) chat(t *testing.T, typ string, members ...uuid.UUID) string {
	t.Helper()
	id := uuid.New().String()
	if err := e.db.Create(&models.Chat{ID: id, Type: typ, Name: "p71", OwnerID: members[0]}).Error; err != nil {
		t.Fatalf("chat: %v", err)
	}
	for _, m := range members {
		if err := e.db.Create(&models.ChatParticipant{ChatID: id, UserID: m, JoinedAt: time.Now()}).Error; err != nil {
			t.Fatalf("participant: %v", err)
		}
	}
	return id
}

func opaqueCipher() string {
	b := make([]byte, 48)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func sendBody(chatID, content, cmid string) string {
	body := map[string]any{"chat_id": chatID, "chat_type": "direct", "content": content,
		"encrypted": true, "encryption_version": 4}
	if cmid != "" {
		body["client_message_id"] = cmid
	}
	raw, _ := json.Marshal(body)
	return string(raw)
}

func (e *p71Env) send(t *testing.T, tok, chatID, content, cmid string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodPost, "/api/messages", sendBody(chatID, content, cmid), tok, "")
}

// sendNoFatal is send() for use off the test goroutine, where t.Fatal is not allowed.
func (e *p71Env) sendNoFatal(tok, chatID, content, cmid string) (int, map[string]any, error) {
	req := httptest.NewRequest(http.MethodPost, "/api/messages", bytes.NewReader([]byte(sendBody(chatID, content, cmid))))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := e.app.Test(req, 10000)
	if err != nil {
		return 0, nil, err
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out, nil
}

func dataOf(out map[string]any) map[string]any {
	d, _ := out["data"].(map[string]any)
	return d
}

func rowsOf(out map[string]any) []map[string]any {
	raw, _ := out["data"].([]any)
	rows := make([]map[string]any, 0, len(raw))
	for _, r := range raw {
		if m, ok := r.(map[string]any); ok {
			rows = append(rows, m)
		}
	}
	return rows
}

func (e *p71Env) page(t *testing.T, tok, chatID, query string) (int, map[string]any) {
	t.Helper()
	return e.req(t, http.MethodGet, "/api/messages/"+chatID+query, "", tok, "")
}

// syncForward is what a reconnecting client does: start from its sync point and page ?after=
// until the server says there is nothing more. Bounded so a broken cursor cannot loop forever.
func (e *p71Env) syncForward(t *testing.T, tok, chatID string, limit, maxPages int) ([]string, int) {
	t.Helper()
	var ids []string
	cursor := "0"
	pages := 0
	for pages < maxPages {
		code, out := e.page(t, tok, chatID, fmt.Sprintf("?after=%s&limit=%d", cursor, limit))
		if code != http.StatusOK {
			t.Fatalf("forward page %d -> %d %v", pages, code, out)
		}
		pages++
		batch := rowsOf(out)
		for _, m := range batch {
			ids = append(ids, fmt.Sprint(m["id"]))
		}
		more, _ := out["has_more"].(bool)
		cur, _ := out["cursor"].(map[string]any)
		next := fmt.Sprint(cur["after"])
		if !more || len(batch) == 0 || next == "" || next == "<nil>" {
			break
		}
		cursor = next
	}
	return ids, pages
}

// syncBackward pages history newest-first with ?before=, the way a client scrolls up.
func (e *p71Env) syncBackward(t *testing.T, tok, chatID string, limit, maxPages int) []string {
	t.Helper()
	var ids []string
	query := fmt.Sprintf("?limit=%d", limit)
	for pages := 0; pages < maxPages; pages++ {
		code, out := e.page(t, tok, chatID, query)
		if code != http.StatusOK {
			t.Fatalf("backward page %d -> %d %v", pages, code, out)
		}
		batch := rowsOf(out)
		for _, m := range batch {
			ids = append(ids, fmt.Sprint(m["id"]))
		}
		more, _ := out["has_more"].(bool)
		cur, _ := out["cursor"].(map[string]any)
		next := fmt.Sprint(cur["before"])
		if !more || len(batch) == 0 || next == "" || next == "<nil>" {
			break
		}
		query = fmt.Sprintf("?before=%s&limit=%d", next, limit)
	}
	return ids
}

func (e *p71Env) countChat(t *testing.T, chatID string) int64 {
	t.Helper()
	var n int64
	if err := e.db.Raw(`SELECT count(*) FROM messages WHERE chat_id = ?`, chatID).Scan(&n).Error; err != nil {
		t.Fatalf("count: %v", err)
	}
	return n
}

func (e *p71Env) sendMany(t *testing.T, tok, chatID string, n int) []string {
	t.Helper()
	ids := make([]string, 0, n)
	for i := 0; i < n; i++ {
		code, out := e.send(t, tok, chatID, opaqueCipher(), uuid.New().String())
		if code != http.StatusOK {
			t.Fatalf("send %d -> %d %v", i, code, out)
		}
		ids = append(ids, fmt.Sprint(dataOf(out)["id"]))
	}
	return ids
}

func sameSequence(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func dupes(ids []string) int {
	seen := map[string]bool{}
	d := 0
	for _, id := range ids {
		if seen[id] {
			d++
		}
		seen[id] = true
	}
	return d
}

func reversed(in []string) []string {
	out := make([]string, len(in))
	for i := range in {
		out[len(in)-1-i] = in[i]
	}
	return out
}

// ------------------------------------------------------------------ websocket client

type p71Socket struct {
	conn   *fws.Conn
	frames chan map[string]any
	wmu    sync.Mutex
}

func (e *p71Env) dial(t *testing.T, tok string, user uuid.UUID) *p71Socket {
	t.Helper()
	conn, resp, err := fws.DefaultDialer.Dial(e.wsBase+"/ws?token="+tok, nil)
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		t.Fatalf("ws dial: %v (http %d)", err, code)
	}
	s := &p71Socket{conn: conn, frames: make(chan map[string]any, 4096)}
	go func() {
		defer close(s.frames)
		for {
			_, raw, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var m map[string]any
			if json.Unmarshal(raw, &m) == nil {
				s.frames <- m
			}
		}
	}()
	e.mu.Lock()
	e.users = append(e.users, user)
	e.mu.Unlock()
	t.Cleanup(func() { _ = conn.Close() })
	return s
}

func (s *p71Socket) write(t *testing.T, v any) {
	t.Helper()
	s.wmu.Lock()
	defer s.wmu.Unlock()
	if err := s.conn.WriteJSON(v); err != nil {
		t.Fatalf("ws write: %v", err)
	}
}

func (s *p71Socket) join(t *testing.T, chatID string) {
	t.Helper()
	s.write(t, map[string]any{"type": "join", "data": map[string]any{"chat_id": chatID}})
}

// next returns the first frame of type typ within d, skipping everything else (presence, etc.).
func (s *p71Socket) next(typ string, d time.Duration) (map[string]any, bool) {
	deadline := time.After(d)
	for {
		select {
		case m, ok := <-s.frames:
			if !ok {
				return nil, false
			}
			if m["type"] == typ {
				return m, true
			}
		case <-deadline:
			return nil, false
		}
	}
}

// settle gives the hub time to apply joins written on a socket before the test acts on them.
func settle() { time.Sleep(400 * time.Millisecond) }

// ============================================================ 1-2, 12-15: idempotency

// 1. POST, POST same client_message_id, POST same client_message_id -> one stored message.
func TestP71_S01_DuplicateClientMessageIDIsOneRow(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)

	cmid := uuid.New().String()
	payload := opaqueCipher()
	var ids []string
	var last map[string]any
	for i := 0; i < 3; i++ {
		code, out := e.send(t, ta, chat, payload, cmid)
		if code != http.StatusOK {
			t.Fatalf("attempt %d -> %d %v", i+1, code, out)
		}
		ids = append(ids, fmt.Sprint(dataOf(out)["id"]))
		last = out
	}
	n := e.countChat(t, chat)
	t.Logf("3 POSTs, one client_message_id -> rows=%d ids=%v", n, ids)
	if n != 1 {
		t.Fatalf("same sender + same client_message_id stored %d rows, want exactly 1", n)
	}
	if ids[0] != ids[1] || ids[1] != ids[2] {
		t.Fatalf("every retry must resolve to the one existing server message: %v", ids)
	}
	if got := fmt.Sprint(dataOf(last)["client_message_id"]); got != cmid {
		t.Fatalf("response must echo client_message_id %s, got %s", cmid, got)
	}
}

// 2. Same payload under two different client ids is two logical messages (no content-dedup).
func TestP71_S02_SamePayloadDifferentClientIDsIsTwoMessages(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)
	payload := opaqueCipher()
	_, o1 := e.send(t, ta, chat, payload, uuid.New().String())
	_, o2 := e.send(t, ta, chat, payload, uuid.New().String())
	n := e.countChat(t, chat)
	t.Logf("same payload, two client ids -> rows=%d", n)
	if n != 2 || dataOf(o1)["id"] == dataOf(o2)["id"] {
		t.Fatalf("two logical sends must be two messages: rows=%d ids=%v/%v", n, dataOf(o1)["id"], dataOf(o2)["id"])
	}
}

// 12. Concurrent retries of one client_message_id (lost ACK + eager retry) -> still one row.
func TestP71_S12_ConcurrentRetriesOfOneClientMessageID(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)
	cmid := uuid.New().String()
	payload := opaqueCipher()

	const n = 12
	var wg sync.WaitGroup
	codes := make([]int, n)
	ids := make([]string, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			code, out, err := e.sendNoFatal(ta, chat, payload, cmid)
			codes[i], errs[i] = code, err
			ids[i] = fmt.Sprint(dataOf(out)["id"])
		}(i)
	}
	wg.Wait()
	rows := e.countChat(t, chat)
	t.Logf("%d concurrent POSTs of one client_message_id -> codes=%v rows=%d", n, codes, rows)
	for i := range codes {
		if errs[i] != nil || codes[i] != http.StatusOK {
			t.Fatalf("attempt %d -> %d err=%v", i, codes[i], errs[i])
		}
		if ids[i] != ids[0] {
			t.Fatalf("attempt %d resolved to %s, others to %s", i, ids[i], ids[0])
		}
	}
	if rows != 1 {
		t.Fatalf("concurrent retries stored %d rows, want 1", rows)
	}
}

// 13. Uniqueness is scoped to the SENDER: two senders may pick the same client id.
func TestP71_S13_ClientMessageIDIsScopedPerSender(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)
	cmid := uuid.New().String()
	c1, _ := e.send(t, ta, chat, opaqueCipher(), cmid)
	c2, _ := e.send(t, tb, chat, opaqueCipher(), cmid)
	n := e.countChat(t, chat)
	t.Logf("A and B reuse one client id -> %d/%d rows=%d", c1, c2, n)
	if c1 != 200 || c2 != 200 || n != 2 {
		t.Fatalf("client_message_id must be unique per sender only: codes %d/%d rows=%d", c1, c2, n)
	}
}

// 14. Reusing a client id for a DIFFERENT chat is a client bug, not a retry: refuse it.
func TestP71_S14_ClientMessageIDReusedAcrossChatsIsRejected(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	c, _ := e.user(t)
	chat1 := e.chat(t, "direct", a, b)
	chat2 := e.chat(t, "direct", a, c)
	cmid := uuid.New().String()
	if code, out := e.send(t, ta, chat1, opaqueCipher(), cmid); code != 200 {
		t.Fatalf("first send -> %d %v", code, out)
	}
	code, out := e.send(t, ta, chat2, opaqueCipher(), cmid)
	t.Logf("same client id, other chat -> %d %v ; chat2 rows=%d", code, out["error"], e.countChat(t, chat2))
	if code != http.StatusConflict || e.countChat(t, chat2) != 0 {
		t.Fatalf("cross-chat reuse must be 409 with nothing stored, got %d rows=%d", code, e.countChat(t, chat2))
	}
}

// 15. The client id is a UUID; anything else is refused rather than silently ignored.
func TestP71_S15_MalformedClientMessageIDRejected(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)
	code, _ := e.send(t, ta, chat, opaqueCipher(), "not-a-uuid")
	t.Logf("malformed client_message_id -> %d rows=%d", code, e.countChat(t, chat))
	if code != http.StatusBadRequest || e.countChat(t, chat) != 0 {
		t.Fatalf("malformed client_message_id must be 400 with nothing stored, got %d", code)
	}
}

// ============================================================ 3-5, 16-17: ordered sync

// 3. Forward (catch-up) pagination returns every message exactly once, in send order.
func TestP71_S03_ForwardPaginationReturnsEverythingInOrder(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)
	sent := e.sendMany(t, ta, chat, 137)
	got, pages := e.syncForward(t, tb, chat, 25, 40)
	t.Logf("forward sync: sent=%d got=%d pages=%d dupes=%d", len(sent), len(got), pages, dupes(got))
	if !sameSequence(got, sent) {
		t.Fatalf("forward pagination is not the exact send sequence (got %d, want %d, dupes %d)",
			len(got), len(sent), dupes(got))
	}
	if pages != 6 {
		t.Fatalf("137 rows at limit 25 should take exactly 6 pages, took %d", pages)
	}
}

// 4. Backward cursor continuation returns the complete set, newest first.
func TestP71_S04_BackwardCursorReturnsCompleteSet(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)
	sent := e.sendMany(t, ta, chat, 83)
	got := e.syncBackward(t, tb, chat, 20, 40)
	t.Logf("backward sync: sent=%d got=%d dupes=%d", len(sent), len(got), dupes(got))
	if !sameSequence(got, reversed(sent)) {
		t.Fatalf("backward pagination is not the exact reverse send sequence (got %d, want %d, dupes %d)",
			len(got), len(sent), dupes(got))
	}
}

// 5. Rows sharing ONE timestamp - inserted in descending-uuid order, so neither created_at nor id
// can be what orders them - still page out complete, unique, and in insertion order.
func TestP71_S05_EqualTimestampsStayOrderedAndComplete(t *testing.T) {
	e := p71Setup(t)
	a, _ := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)

	ts := time.Now().UTC().Truncate(time.Second)
	ids := make([]string, 40)
	for i := range ids {
		ids[i] = uuid.New().String()
	}
	sort.Sort(sort.Reverse(sort.StringSlice(ids)))
	for _, id := range ids {
		m := models.Message{ID: uuid.MustParse(id), SenderID: a, ChatID: chat, ChatType: "direct",
			EncryptedContent: opaqueCipher(), IsEncrypted: true, EncryptionVersion: 4, CreatedAt: ts, UpdatedAt: ts}
		if err := e.db.Create(&m).Error; err != nil {
			t.Fatalf("insert: %v", err)
		}
	}
	got, pages := e.syncForward(t, tb, chat, 7, 30)
	t.Logf("40 rows, one timestamp: got=%d pages=%d dupes=%d", len(got), pages, dupes(got))
	if !sameSequence(got, ids) {
		t.Fatalf("equal timestamps broke pagination: got %d unique=%d want 40 in insertion order",
			len(got), len(got)-dupes(got))
	}
}

// 16. One pagination model: ambiguous or ignored parameters are refused, not silently dropped.
func TestP71_S16_PaginationContractRejectsAmbiguity(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)
	e.sendMany(t, ta, chat, 3)
	for _, q := range []string{"?before=5&after=3", "?offset=10", "?after=abc", "?after=-1", "?before=0x10"} {
		code, _ := e.page(t, ta, chat, q)
		t.Logf("GET %s -> %d", q, code)
		if code != http.StatusBadRequest {
			t.Fatalf("GET %s must be 400, got %d", q, code)
		}
	}
	if code, _ := e.page(t, ta, chat, ""); code != http.StatusOK {
		t.Fatalf("the parameterless newest page must keep working, got %d", code)
	}
}

// 17. Concurrent senders into ONE chat: seqs are unique, gap-free and commit-ordered, and a
// catch-up sees every message.
func TestP71_S17_ConcurrentSendersGetContiguousSeqs(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)

	const workers, each = 8, 15
	var wg sync.WaitGroup
	var fails int
	var fmu sync.Mutex
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(tok string) {
			defer wg.Done()
			for i := 0; i < each; i++ {
				code, _, err := e.sendNoFatal(tok, chat, opaqueCipher(), uuid.New().String())
				if err != nil || code != 200 {
					fmu.Lock()
					fails++
					fmu.Unlock()
				}
			}
		}(map[bool]string{true: ta, false: tb}[w%2 == 0])
	}
	wg.Wait()
	if fails > 0 {
		t.Fatalf("%d concurrent sends failed", fails)
	}
	var seqs []int64
	if err := e.db.Raw(`SELECT seq FROM messages WHERE chat_id = ? ORDER BY seq`, chat).Scan(&seqs).Error; err != nil {
		t.Fatalf("read seqs: %v", err)
	}
	for i, s := range seqs {
		if s != int64(i+1) {
			t.Fatalf("seqs are not the contiguous 1..%d: position %d holds %d", workers*each, i, s)
		}
	}
	got, _ := e.syncForward(t, tb, chat, 30, 20)
	t.Logf("concurrent: rows=%d seqs 1..%d contiguous; catch-up got %d dupes=%d", len(seqs), len(seqs), len(got), dupes(got))
	if len(seqs) != workers*each || len(got) != workers*each || dupes(got) != 0 {
		t.Fatalf("catch-up after concurrent sends: rows=%d got=%d dupes=%d", len(seqs), len(got), dupes(got))
	}
}

// ============================================================ 6-8: offline, restart, delivery

// 6. A recipient that never connected retrieves every accepted message, byte-identical.
func TestP71_S06_OfflineRecipientRetrievesEverything(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t) // B never opens a socket: offline for the whole test
	chat := e.chat(t, "direct", a, b)

	sentContent := map[string]string{}
	var order []string
	for i := 0; i < 120; i++ {
		c := opaqueCipher()
		code, out := e.send(t, ta, chat, c, uuid.New().String())
		if code != 200 {
			t.Fatalf("send %d -> %d", i, code)
		}
		id := fmt.Sprint(dataOf(out)["id"])
		sentContent[id] = c
		order = append(order, id)
	}
	got, pages := e.syncForward(t, tb, chat, 50, 20)
	var mismatched int
	var cursor = "0"
	for p := 0; p < pages; p++ {
		_, out := e.page(t, tb, chat, fmt.Sprintf("?after=%s&limit=50", cursor))
		for _, m := range rowsOf(out) {
			if sentContent[fmt.Sprint(m["id"])] != fmt.Sprint(m["content"]) {
				mismatched++
			}
		}
		cur, _ := out["cursor"].(map[string]any)
		cursor = fmt.Sprint(cur["after"])
	}
	t.Logf("offline recipient: sent=120 retrieved=%d pages=%d content-mismatch=%d", len(got), pages, mismatched)
	if !sameSequence(got, order) || mismatched != 0 {
		t.Fatalf("offline recipient lost or altered messages: got %d/120 mismatched=%d", len(got), mismatched)
	}
}

// 7. Closing every connection and re-running MigrateDB (what a backend restart does) keeps every
// accepted message, its order, and its sequence; the next send continues the sequence.
func TestP71_S07_RestartPreservesMessagesAndSequence(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)
	sent := e.sendMany(t, ta, chat, 15)

	type row struct {
		ID  string
		Seq int64
	}
	var before []row
	if err := e.db.Raw(`SELECT id::text AS id, seq FROM messages WHERE chat_id = ? ORDER BY seq`, chat).Scan(&before).Error; err != nil {
		t.Fatalf("read before: %v", err)
	}

	// "Restart": drop the pool, open a fresh one, migrate again.
	if sqlDB, err := e.db.DB(); err == nil {
		_ = sqlDB.Close()
	}
	fresh, err := gorm.Open(postgres.Open(os.Getenv("TEST_DATABASE_URL")), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	closeWhenDone(t, fresh)
	if err := models.MigrateDB(fresh); err != nil {
		t.Fatalf("re-migrate: %v", err)
	}
	e.db = fresh
	database.DB = fresh

	var after []row
	fresh.Raw(`SELECT id::text AS id, seq FROM messages WHERE chat_id = ? ORDER BY seq`, chat).Scan(&after)
	got, _ := e.syncForward(t, tb, chat, 50, 5)
	code, out := e.send(t, ta, chat, opaqueCipher(), uuid.New().String())
	next := fmt.Sprint(dataOf(out)["seq"])
	t.Logf("restart: before=%d after=%d catch-up=%d next-send %d seq=%s", len(before), len(after), len(got), code, next)
	if len(before) != 15 || len(after) != 15 {
		t.Fatalf("rows changed across restart: %d -> %d", len(before), len(after))
	}
	for i := range before {
		if before[i] != after[i] {
			t.Fatalf("row %d changed across restart: %+v -> %+v", i, before[i], after[i])
		}
	}
	if !sameSequence(got, sent) {
		t.Fatalf("post-restart catch-up is not the send sequence")
	}
	if code != 200 || next != fmt.Sprint(before[len(before)-1].Seq+1) {
		t.Fatalf("sequence must continue after restart: got seq %s want %d", next, before[len(before)-1].Seq+1)
	}
}

// 8. Acceptance is not delivery: the send response reports persistence only.
func TestP71_S08_AcceptedIsNotDelivered(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)
	code, out := e.send(t, ta, chat, opaqueCipher(), uuid.New().String())
	d := dataOf(out)
	delivered, has := d["delivered_at"]
	t.Logf("send -> %d status=%v delivered_at present=%v value=%v", code, d["status"], has, delivered)
	if code != 200 {
		t.Fatalf("send -> %d", code)
	}
	if has && delivered != nil {
		t.Fatalf("send response claims delivered_at=%v although no recipient received anything", delivered)
	}
	if d["status"] != "accepted" {
		t.Fatalf("send response must report status=accepted, got %v", d["status"])
	}
	var dbDelivered int64
	e.db.Raw(`SELECT count(*) FROM messages WHERE chat_id = ? AND delivered_at IS NOT NULL`, chat).Scan(&dbDelivered)
	_, pg := e.page(t, tb, chat, "?after=0")
	for _, m := range rowsOf(pg) {
		if m["delivered_at"] != nil {
			t.Fatalf("history claims delivery: %v", m["delivered_at"])
		}
	}
	if dbDelivered != 0 {
		t.Fatalf("%d rows carry delivered_at without a delivery event", dbDelivered)
	}
}

// ============================================================ 9-11: websocket security

// 9. The socket is not a second message path: forged frames - plaintext, spoofed sender - from an
// outsider AND from a member never reach anyone and are never stored.
func TestP71_S09_WebSocketCannotInjectMessages(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	c, tc := e.user(t)
	chat := e.chat(t, "direct", a, b)

	wa := e.dial(t, ta, a)
	wb := e.dial(t, tb, b)
	wc := e.dial(t, tc, c)
	wa.join(t, chat)
	wb.join(t, chat)
	settle()

	forged := map[string]any{"type": "message", "data": map[string]any{
		"chat_id": chat, "message_id": uuid.New().String(), "sender_id": a.String(),
		"content": "PLAINTEXT-FORGERY", "encrypted": false}}
	wc.write(t, forged) // outsider
	wb.write(t, forged) // member: still must go through HTTP

	if m, ok := wa.next("message", 1500*time.Millisecond); ok {
		t.Fatalf("a forged websocket message reached a member: %v", m)
	}
	if n := e.countChat(t, chat); n != 0 {
		t.Fatalf("forged frames produced %d stored rows", n)
	}

	// Positive control: the socket is alive and the real path still delivers.
	if code, _ := e.send(t, tb, chat, opaqueCipher(), uuid.New().String()); code != 200 {
		t.Fatalf("control send -> %d", code)
	}
	m, ok := wa.next("message", 3*time.Second)
	t.Logf("forged: none delivered, none stored; control delivered=%v", ok)
	if !ok {
		t.Fatalf("control: a real HTTP message did not reach the joined member")
	}
	if data, _ := m["data"].(map[string]any); data["encrypted"] != true {
		t.Fatalf("control frame is not the encrypted, persisted message: %v", m)
	}
}

// 10. Joining a room requires being a participant of that chat.
func TestP71_S10_UnauthorizedJoinRejected(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	c, tc := e.user(t)
	chat := e.chat(t, "direct", a, b)

	wb := e.dial(t, tb, b)
	wc := e.dial(t, tc, c)
	wb.join(t, chat)
	wc.join(t, chat) // C is not a participant
	settle()

	if code, _ := e.send(t, ta, chat, opaqueCipher(), uuid.New().String()); code != 200 {
		t.Fatalf("send -> %d", code)
	}
	_, gotB := wb.next("message", 3*time.Second)
	m, gotC := wc.next("message", 1500*time.Millisecond)
	t.Logf("member received=%v outsider received=%v", gotB, gotC)
	if !gotB {
		t.Fatalf("control: the participant did not receive the message")
	}
	if gotC {
		t.Fatalf("a non-participant joined the room and received %v", m)
	}
}

// 11. Typing rides the same rooms: an outsider cannot inject it and a member cannot spoof who is
// typing.
func TestP71_S11_TypingIsBoundToMembershipAndIdentity(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, tb := e.user(t)
	c, tc := e.user(t)
	chat := e.chat(t, "direct", a, b)
	wa := e.dial(t, ta, a)
	wb := e.dial(t, tb, b)
	wc := e.dial(t, tc, c)
	wa.join(t, chat)
	wb.join(t, chat)
	settle()

	wc.write(t, map[string]any{"type": "typing", "data": map[string]any{"chat_id": chat, "user_id": a.String(), "typing": true}})
	if m, ok := wb.next("typing", 1200*time.Millisecond); ok {
		t.Fatalf("an outsider's typing frame reached a member: %v", m)
	}
	wb.write(t, map[string]any{"type": "typing", "data": map[string]any{"chat_id": chat, "user_id": a.String(), "typing": true}})
	m, ok := wa.next("typing", 3*time.Second)
	if !ok {
		t.Fatalf("control: a member's typing frame was not relayed")
	}
	data, _ := m["data"].(map[string]any)
	t.Logf("member typing relayed with user_id=%v (sender is %s)", data["user_id"], b)
	if data["user_id"] != b.String() {
		t.Fatalf("typing identity must be the authenticated sender %s, got %v", b, data["user_id"])
	}
}

// ============================================================ migration + E2EE guards

// 18. Rows that predate the sequence are numbered per chat in (created_at, id) order, the counter
// continues from there, and running the migration again changes nothing.
func TestP71_S18_MigrationBackfillsExistingRowsChronologically(t *testing.T) {
	e := p71Setup(t)
	a, _ := e.user(t)
	b, tb := e.user(t)
	chat := e.chat(t, "direct", a, b)

	// Put the table back in its pre-Phase-71 shape.
	for _, s := range []string{
		`DROP TRIGGER IF EXISTS trg_phase71_message_seq ON messages`,
		`DROP TRIGGER IF EXISTS trg_phase71_message_seq_guard ON messages`,
		`DROP INDEX IF EXISTS ux_messages_chat_seq`,
		`ALTER TABLE messages DROP COLUMN IF EXISTS seq`,
		`DROP TABLE IF EXISTS message_chat_seqs`,
	} {
		if err := e.db.Exec(s).Error; err != nil {
			t.Fatalf("revert: %v", err)
		}
	}
	// Legacy rows written out of chronological order.
	base := time.Now().UTC().Add(-time.Hour).Truncate(time.Millisecond)
	offsets := []int{5, 1, 4, 2, 3} // minutes
	for _, off := range offsets {
		ts := base.Add(time.Duration(off) * time.Minute)
		if err := e.db.Exec(`INSERT INTO messages (id, sender_id, chat_id, chat_type, encrypted_content, is_encrypted,
			encryption_version, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, 'direct', ?, true, 4, ?, ?)`,
			a, chat, opaqueCipher(), ts, ts).Error; err != nil {
			t.Fatalf("legacy insert: %v", err)
		}
	}
	for i := 0; i < 2; i++ {
		if err := models.MigrateDB(e.db); err != nil {
			t.Fatalf("MigrateDB run %d: %v", i+1, err)
		}
	}
	type row struct {
		Seq       int64
		CreatedAt time.Time
	}
	var rows []row
	if err := e.db.Raw(`SELECT seq, created_at FROM messages WHERE chat_id = ? ORDER BY seq`, chat).Scan(&rows).Error; err != nil {
		t.Fatalf("read: %v", err)
	}
	for i := range rows {
		if rows[i].Seq != int64(i+1) || (i > 0 && !rows[i].CreatedAt.After(rows[i-1].CreatedAt)) {
			t.Fatalf("backfill is not chronological 1..5: %+v", rows)
		}
	}
	got, _ := e.syncForward(t, tb, chat, 2, 10)
	t.Logf("backfilled %d legacy rows as 1..%d; catch-up got %d", len(rows), len(rows), len(got))
	if len(rows) != 5 || len(got) != 5 {
		t.Fatalf("backfill lost rows: stored=%d synced=%d", len(rows), len(got))
	}
	var next int64
	if err := e.db.Raw(`INSERT INTO messages (id, sender_id, chat_id, chat_type, encrypted_content, is_encrypted,
		encryption_version, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, 'direct', ?, true, 4, now(), now())
		RETURNING seq`, a, chat, opaqueCipher()).Scan(&next).Error; err != nil {
		t.Fatalf("post-backfill insert: %v", err)
	}
	if next != 6 {
		t.Fatalf("counter must continue after the backfill: got %d want 6", next)
	}
}

// 19. The sequence is server-owned and write-once.
func TestP71_S19_SeqIsServerOwnedAndImmutable(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)
	raw, _ := json.Marshal(map[string]any{"chat_id": chat, "chat_type": "direct", "content": opaqueCipher(),
		"encrypted": true, "encryption_version": 4, "client_message_id": uuid.New().String(), "seq": 999999})
	code, out := e.req(t, http.MethodPost, "/api/messages", string(raw), ta, "")
	if code != 200 {
		t.Fatalf("send -> %d %v", code, out)
	}
	if got := fmt.Sprint(dataOf(out)["seq"]); got != "1" {
		t.Fatalf("a client-supplied seq must be ignored; first message got seq %s", got)
	}
	err := e.db.Exec(`UPDATE messages SET seq = seq + 100 WHERE chat_id = ?`, chat).Error
	t.Logf("rewrite seq -> err=%v", err)
	if err == nil {
		t.Fatalf("seq must be write-once, but an UPDATE rewrote it")
	}
}

// 20. The E2EE gate is unchanged: plaintext is refused and never stored.
func TestP71_S20_PlaintextSendStillRejected(t *testing.T) {
	e := p71Setup(t)
	a, ta := e.user(t)
	b, _ := e.user(t)
	chat := e.chat(t, "direct", a, b)
	raw, _ := json.Marshal(map[string]any{"chat_id": chat, "chat_type": "direct", "content": "hello",
		"encrypted": false, "client_message_id": uuid.New().String()})
	code, _ := e.req(t, http.MethodPost, "/api/messages", string(raw), ta, "")
	t.Logf("plaintext send -> %d rows=%d", code, e.countChat(t, chat))
	if code != http.StatusBadRequest || e.countChat(t, chat) != 0 {
		t.Fatalf("plaintext must be refused and not stored: %d rows=%d", code, e.countChat(t, chat))
	}
}
