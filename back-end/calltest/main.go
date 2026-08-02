// Command calltest is a throwaway diagnostic: it connects two synthetic
// WebSocket clients (the two real test accounts) and exercises the exact
// pattern that was breaking real calls -
//
//  1. STABILITY: the Windows app pings the server every ~12s. The server used
//     to answer that ping from its read goroutine while writePump wrote from
//     another, and two concurrent writers on one websocket corrupt frames -
//     surfacing to clients as "close 1006 (abnormal closure): unexpected EOF"
//     seconds into a healthy connection. Here both clients ping aggressively
//     (every 1s) to force that race far faster than a real client would.
//
//  2. CALL RELAY: A sends call:invite to B and we assert B actually receives
//     it, which is what "I didn't receive calls on either device" was about.
//
// Run with the backend already listening on :3000.
package main

import (
	"fmt"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fasthttp/websocket"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const (
	jwtSecret = "your-super-secret-jwt-key-change-in-production"
	wsURL     = "ws://localhost:3000/ws?token="
	userA     = "07a6f67a-470a-4bbc-85b5-2ee35dcf1ac2" // gmail account
	userB     = "a3864020-90db-4dcd-88e8-40731ce5142c" // icloud account
	chatID    = "08ca93d3-0d29-4366-83a6-a7405a741309" // their existing direct chat
	runFor    = 40 * time.Second
)

type claims struct {
	UserID   uuid.UUID `json:"user_id"`
	Email    string    `json:"email"`
	FullName string    `json:"full_name"`
	jwt.RegisteredClaims
}

func mintToken(userID string) (string, error) {
	id, err := uuid.Parse(userID)
	if err != nil {
		return "", err
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, claims{
		UserID: id,
		Email:  "calltest@example.com",
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	})
	return tok.SignedString([]byte(jwtSecret))
}

type client struct {
	name      string
	conn      *websocket.Conn
	gotInvite atomic.Bool
	readErr   atomic.Value // string
	pongCount atomic.Int64
	msgCount  atomic.Int64
	// Same one-writer-at-a-time rule the server side needs: this harness
	// pings from its own goroutine while main writes the invite.
	writeMu sync.Mutex
}

func (c *client) writeMessage(messageType int, data []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return c.conn.WriteMessage(messageType, data)
}

func (c *client) writeJSON(v interface{}) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return c.conn.WriteJSON(v)
}

func dial(name, userID string) (*client, error) {
	token, err := mintToken(userID)
	if err != nil {
		return nil, err
	}
	conn, resp, err := websocket.DefaultDialer.Dial(wsURL+token, http.Header{})
	if err != nil {
		if resp != nil {
			return nil, fmt.Errorf("dial: %w (http %d)", err, resp.StatusCode)
		}
		return nil, fmt.Errorf("dial: %w", err)
	}
	c := &client{name: name, conn: conn}
	conn.SetPongHandler(func(string) error {
		c.pongCount.Add(1)
		return nil
	})
	return c, nil
}

// readLoop drains messages, recording the first read error (a corrupted-frame
// disconnect shows up here as an abnormal closure).
func (c *client) readLoop(done chan<- struct{}) {
	defer close(done)
	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			c.readErr.Store(err.Error())
			return
		}
		c.msgCount.Add(1)
		if len(data) > 0 && containsInvite(data) {
			c.gotInvite.Store(true)
		}
	}
}

func containsInvite(data []byte) bool {
	const needle = `"call:invite"`
	s := string(data)
	for i := 0; i+len(needle) <= len(s); i++ {
		if s[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}

// pingLoop hammers the server with client-originated pings - the trigger for
// the concurrent-write bug.
func (c *client) pingLoop(stop <-chan struct{}) {
	t := time.NewTicker(1 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			if err := c.writeMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func main() {
	a, err := dial("A", userA)
	if err != nil {
		fmt.Println("FAIL: client A", err)
		os.Exit(1)
	}
	defer a.conn.Close()

	b, err := dial("B", userB)
	if err != nil {
		fmt.Println("FAIL: client B", err)
		os.Exit(1)
	}
	defer b.conn.Close()
	fmt.Println("both clients connected")

	aDone, bDone := make(chan struct{}), make(chan struct{})
	go a.readLoop(aDone)
	go b.readLoop(bDone)

	stop := make(chan struct{})
	go a.pingLoop(stop)
	go b.pingLoop(stop)

	// Give the hub a moment to register both, then have A call B.
	time.Sleep(2 * time.Second)
	callID := uuid.NewString()
	invite := map[string]interface{}{
		"type": "call:invite",
		"data": map[string]interface{}{
			"to_user_id":   userB,
			"from_user_id": userA,
			"chat_id":      chatID,
			"call_id":      callID,
			"sdp":          "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
		},
	}
	if err := a.writeJSON(invite); err != nil {
		fmt.Println("FAIL: A could not send call:invite:", err)
		os.Exit(1)
	}
	fmt.Println("A sent call:invite ->", callID)

	deadline := time.After(runFor)
	select {
	case <-deadline:
	case <-aDone:
	case <-bDone:
	}
	close(stop)

	fmt.Println()
	fmt.Println("=== RESULTS ===")
	for _, c := range []*client{a, b} {
		errStr := "none"
		if v := c.readErr.Load(); v != nil {
			errStr = v.(string)
		}
		fmt.Printf("client %s: pongs=%d msgs=%d readErr=%s\n",
			c.name, c.pongCount.Load(), c.msgCount.Load(), errStr)
	}
	fmt.Printf("B received call:invite: %v\n", b.gotInvite.Load())

	stable := a.readErr.Load() == nil && b.readErr.Load() == nil
	fmt.Println()
	if stable && b.gotInvite.Load() {
		fmt.Println("PASS: connections stayed up under ping load AND the invite was relayed")
		return
	}
	if !stable {
		fmt.Println("FAIL: a connection dropped (the 1006 corrupted-frame bug)")
	}
	if !b.gotInvite.Load() {
		fmt.Println("FAIL: call:invite never reached B")
	}
	os.Exit(1)
}
