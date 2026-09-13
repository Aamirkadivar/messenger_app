package websocket

import (
	"encoding/json"
	"testing"
	"time"

	"messenger-app/models"

	"github.com/google/uuid"
)

// PHASE 71 - a recipient that cannot keep up must be disconnected, never crash the hub.
//
// Before this phase the hub answered a full Send buffer with close(client.Send) while the client
// was still registered. The next frame for that client was then a send on a closed channel, and
// its eventual Unregister closed the channel a second time - either one is a panic on the hub
// goroutine, which takes down the whole server and every other user's connection with it.
//
// A second connection for the same user keeps Unregister from reaching leavePresence, which needs
// a database this unit test deliberately does not have.
func TestP71_SlowClientDoesNotCrashHub(t *testing.T) {
	h := NewHub()
	go h.Run()

	user := uuid.New()
	slow := &Client{ID: "slow", UserID: user, Send: make(chan []byte, 1), Rooms: map[string]bool{"room-1": true}}
	healthy := &Client{ID: "healthy", UserID: user, Send: make(chan []byte, 64), Rooms: map[string]bool{"room-1": true}}
	h.Register <- slow
	h.Register <- healthy

	frame, _ := json.Marshal(models.WebSocketMessage{Type: "message",
		Data: map[string]interface{}{"chat_id": "room-1"}, Timestamp: time.Now()})
	presence, _ := json.Marshal(models.WebSocketMessage{Type: "presence",
		Data: map[string]interface{}{"user_id": user.String()}, Timestamp: time.Now()})

	// Five room frames and five presence frames into a buffer of one.
	for i := 0; i < 5; i++ {
		h.Broadcast <- frame
		h.Broadcast <- presence
	}

	// The hub must still be serving: unregister the slow client (its channel closes exactly once)...
	done := make(chan struct{})
	go func() { h.Unregister <- slow; close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("hub stopped serving after a slow client overflowed")
	}

	// ...and keep delivering to everyone else.
	for len(healthy.Send) > 0 {
		<-healthy.Send
	}
	h.Broadcast <- frame
	select {
	case <-healthy.Send:
	case <-time.After(2 * time.Second):
		t.Fatal("hub stopped delivering to healthy clients after a slow one overflowed")
	}
	if !h.IsUserOnline(user) {
		t.Fatal("the healthy connection was dropped along with the slow one")
	}
}
