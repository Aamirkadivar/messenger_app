package websocket

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"messenger-app/database"
	"messenger-app/firebase"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/websocket/v2"
	"github.com/google/uuid"
)

// Keepalive: without this, idle connections get silently dropped by NAT/
// routers/mobile networks after a few minutes (observed as abnormal closure
// code 1006), causing missed real-time messages until the client reconnects.
const (
	pongWait   = 60 * time.Second
	pingPeriod = (pongWait * 9) / 10
)

// Client represents a connected WebSocket client
type Client struct {
	ID       string
	UserID   uuid.UUID
	DeviceID string
	Conn     *websocket.Conn
	Send     chan []byte
	Rooms    map[string]bool
	mu       sync.RWMutex
	// writeMu serializes ALL writes to Conn. A websocket connection permits
	// only one concurrent writer, and there are two here: writePump's
	// goroutine, and the read goroutine's control-frame replies (the pong
	// this sends in answer to a client ping). Without this they interleave
	// and produce corrupted frames, which clients report as an abnormal
	// closure (code 1006, "unexpected EOF") seconds into a healthy
	// connection - and every reconnect drops the user offline long enough
	// to make an incoming call ring against nobody.
	writeMu sync.Mutex
}

// writeMessage is the single serialized path for writing to a client.
func (c *Client) writeMessage(messageType int, data []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.Conn.WriteMessage(messageType, data)
}

// Hub manages all connected WebSocket clients.
// Multiple live connections per user are allowed (one phone + one desktop).
// UserClients maps userID -> connectionID -> *Client.
type Hub struct {
	UserClients map[uuid.UUID]map[string]*Client
	Rooms       map[string]map[uuid.UUID]bool
	Register    chan *Client
	Unregister  chan *Client
	Broadcast   chan []byte
	mu          sync.RWMutex
}

// NewHub creates a new Hub instance
func NewHub() *Hub {
	return &Hub{
		UserClients: make(map[uuid.UUID]map[string]*Client),
		Rooms:       make(map[string]map[uuid.UUID]bool),
		Register:    make(chan *Client),
		Unregister:  make(chan *Client),
		Broadcast:   make(chan []byte),
	}
}

// roomScopedTypes are broadcast to whoever has joined the chat's room. Adding
// a new event type means adding it here - the hub logs and drops anything it
// has no rule for, rather than failing silently.
var roomScopedTypes = map[string]bool{
	"message":         true,
	"typing":          true,
	"read":            true,
	"message:deleted": true,
}

// forEachClient runs fn for every live connection (caller must hold lock).
func (h *Hub) forEachClient(fn func(*Client)) {
	for _, conns := range h.UserClients {
		for _, client := range conns {
			fn(client)
		}
	}
}

// Run starts the Hub event loop
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.Register:
			h.mu.Lock()
			var stale []*Client
			if client.DeviceID != "" {
				for _, c := range h.UserClients[client.UserID] {
					if c.DeviceID == client.DeviceID {
						stale = append(stale, c)
					}
				}
			}
			if h.UserClients[client.UserID] == nil {
				h.UserClients[client.UserID] = make(map[string]*Client)
			}
			h.UserClients[client.UserID][client.ID] = client
			h.mu.Unlock()
			log.Printf("Client %s (user %s device %s) connected", client.ID, client.UserID, client.DeviceID)
			for _, old := range stale {
				_ = old.writeMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, "replaced"))
				_ = old.Conn.Close()
			}

		case client := <-h.Unregister:
			h.mu.Lock()
			if conns, ok := h.UserClients[client.UserID]; ok {
				if current, ok := conns[client.ID]; ok && current == client {
					delete(conns, client.ID)
				}
				if len(conns) == 0 {
					delete(h.UserClients, client.UserID)
					for room := range client.Rooms {
						if members, ok := h.Rooms[room]; ok {
							delete(members, client.UserID)
						}
					}
				} else {
					// Drop rooms this connection alone held; keep user in a
					// room if another of their devices is still joined.
					for room := range client.Rooms {
						still := false
						for _, other := range conns {
							if other.Rooms[room] {
								still = true
								break
							}
						}
						if !still {
							if members, ok := h.Rooms[room]; ok {
								delete(members, client.UserID)
							}
						}
					}
				}
			}
			close(client.Send)
			wasLast := len(h.UserClients[client.UserID]) == 0
			h.mu.Unlock()
			log.Printf("Client %s disconnected", client.ID)
			if wasLast {
				// Mark offline only when no device remains connected.
				go leavePresence(h, client.UserID)
			}

		case message := <-h.Broadcast:
			var wsMsg models.WebSocketMessage
			if err := json.Unmarshal(message, &wsMsg); err != nil {
				log.Printf("Error parsing broadcast message: %v", err)
				continue
			}

			h.mu.RLock()
			if wsMsg.Type == "presence" {
				h.forEachClient(func(client *Client) {
					select {
					case client.Send <- message:
					default:
						close(client.Send)
					}
				})
			} else if roomScopedTypes[wsMsg.Type] {
				if wsMsg.Data != nil {
					if data, ok := wsMsg.Data.(map[string]interface{}); ok {
						chatID, _ := data["chat_id"].(string)
						h.forEachClient(func(client *Client) {
							if !client.Rooms[chatID] {
								return
							}
							select {
							case client.Send <- message:
							default:
								close(client.Send)
							}
						})
					}
				}
			} else {
				log.Printf("hub: no routing rule for broadcast type %q - dropped", wsMsg.Type)
			}
			h.mu.RUnlock()
		}
	}
}

// JoinRoom marks this connection as subscribed to a chat room.
func (h *Hub) JoinRoom(client *Client, room string) {
	if client == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()

	if client.Rooms == nil {
		client.Rooms = make(map[string]bool)
	}
	client.Rooms[room] = true
	if h.Rooms[room] == nil {
		h.Rooms[room] = make(map[uuid.UUID]bool)
	}
	h.Rooms[room][client.UserID] = true
}

// LeaveRoom unsubscribes this connection from a chat room.
func (h *Hub) LeaveRoom(client *Client, room string) {
	if client == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()

	delete(client.Rooms, room)
	still := false
	if conns, ok := h.UserClients[client.UserID]; ok {
		for _, other := range conns {
			if other.Rooms[room] {
				still = true
				break
			}
		}
	}
	if !still {
		if roomMembers, ok := h.Rooms[room]; ok {
			delete(roomMembers, client.UserID)
		}
	}
}

// IsUserOnline checks if a user has any live connection.
func (h *Hub) IsUserOnline(userID uuid.UUID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.UserClients[userID]) > 0
}

// GetOnlineUsers returns a list of online user IDs
func (h *Hub) GetOnlineUsers() []uuid.UUID {
	h.mu.RLock()
	defer h.mu.RUnlock()

	online := make([]uuid.UUID, 0, len(h.UserClients))
	for userID, conns := range h.UserClients {
		if len(conns) > 0 {
			online = append(online, userID)
		}
	}
	return online
}

// KickDevice closes every live WebSocket for userID+deviceID (used on revoke).
func (h *Hub) KickDevice(userID uuid.UUID, deviceID string) {
	if deviceID == "" {
		return
	}
	h.mu.RLock()
	var targets []*Client
	for _, c := range h.UserClients[userID] {
		if c.DeviceID == deviceID {
			targets = append(targets, c)
		}
	}
	h.mu.RUnlock()
	for _, c := range targets {
		_ = c.writeMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, "device revoked"))
		_ = c.Conn.Close()
	}
}

// HandleWebSocket handles WebSocket connections
func HandleWebSocket(hub *Hub) fiber.Handler {
	return websocket.New(func(c *websocket.Conn) {
		// Get user from context
		user := c.Locals("user")
		if user == nil {
			_ = c.WriteJSON(models.WebSocketMessage{
				Type: "error",
				Data: map[string]interface{}{
					"error": "Unauthorized: no user in context",
				},
				Timestamp: time.Now(),
			})
			c.Close()
			return
		}

		claims, ok := user.(*middleware.JWTClaims)
		if !ok {
			_ = c.WriteJSON(models.WebSocketMessage{
				Type: "error",
				Data: map[string]interface{}{
					"error": "Unauthorized: invalid user context type",
				},
				Timestamp: time.Now(),
			})
			c.Close()
			return
		}

		userID := claims.UserID
		deviceID := c.Query("device_id")

		clientID := fmt.Sprintf("%s-%d", userID.String(), time.Now().UnixNano())
		client := &Client{
			ID:       clientID,
			UserID:   userID,
			DeviceID: deviceID,
			Conn:     c,
			Send:     make(chan []byte, 256),
			Rooms:    make(map[string]bool),
		}

		// Register client
		hub.Register <- client
		hub.JoinRoom(client, fmt.Sprintf("user:%s", userID.String()))

		// Update presence
		updatePresence(hub, userID, true)

		// Keepalive: reset the read deadline on every pong so the connection
		// stays open as long as the client keeps responding to pings.
		c.Conn.SetReadDeadline(time.Now().Add(pongWait))
		c.Conn.SetPongHandler(func(string) error {
			c.Conn.SetReadDeadline(time.Now().Add(pongWait))
			return nil
		})
		// Clients ping us too (the Windows app every ~12s, to detect a dead
		// link the OS hasn't noticed). Answer through the same serialized
		// writer writePump uses - the default handler writes the pong
		// straight from this read goroutine, racing writePump.
		// A client ping is also proof the link is alive, so refresh the
		// read deadline on it rather than waiting for our own ping cycle.
		c.Conn.SetPingHandler(func(appData string) error {
			c.Conn.SetReadDeadline(time.Now().Add(pongWait))
			return client.writeMessage(websocket.PongMessage, []byte(appData))
		})

		// Handle connection
		go client.writePump(hub)

		// Read messages
		for {
			_, message, err := c.ReadMessage()
			if err != nil {
				if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
					log.Printf("WebSocket error: %v", err)
				}
				break
			}
			handleClientMessage(hub, client, message)
		}

		// Unregister and cleanup (presence offline is handled in Unregister
		// when this was the user's last live connection).
		hub.Unregister <- client
	})
}

// handleClientMessage processes messages received from clients
func handleClientMessage(hub *Hub, client *Client, message []byte) {
	var wsMsg models.WebSocketMessage
	if err := json.Unmarshal(message, &wsMsg); err != nil {
		log.Printf("Error parsing message: %v", err)
		return
	}

	wsMsg.Timestamp = time.Now()

	switch wsMsg.Type {
	case "join":
		if data, ok := wsMsg.Data.(map[string]interface{}); ok {
			if chatID, ok := data["chat_id"].(string); ok {
				hub.JoinRoom(client, chatID)
			}
		}
	case "leave":
		if data, ok := wsMsg.Data.(map[string]interface{}); ok {
			if chatID, ok := data["chat_id"].(string); ok {
				hub.LeaveRoom(client, chatID)
			}
		}
	case "message", "typing", "presence":
		broadcastMsg, _ := json.Marshal(wsMsg)
		hub.Broadcast <- broadcastMsg
	default:
		if CallSignalTypes[wsMsg.Type] {
			handleCallSignal(hub, client, wsMsg)
			return
		}
		log.Printf("Unknown message type: %s", wsMsg.Type)
	}
}

// updatePresence updates user presence in database and tells every connected
// client right away - without this, a contact's online dot only ever
// reflected reality after the next full chat-list refetch.
func updatePresence(hub *Hub, userID uuid.UUID, isOnline bool) {
	database.DB.Model(&models.User{}).Where("id = ?", userID).Updates(map[string]interface{}{
		"is_online": isOnline,
		"last_seen": time.Now(),
	})
	broadcastPresence(hub, userID, isOnline)
}

// leavePresence handles user disconnect
func leavePresence(hub *Hub, userID uuid.UUID) {
	database.DB.Model(&models.User{}).Where("id = ?", userID).Update("is_online", false)
	broadcastPresence(hub, userID, false)
}

func broadcastPresence(hub *Hub, userID uuid.UUID, isOnline bool) {
	if hub == nil {
		return
	}
	wsMsg := models.WebSocketMessage{
		Type: "presence",
		Data: map[string]interface{}{
			"user_id":   userID.String(),
			"is_online": isOnline,
			"last_seen": time.Now(),
		},
		Timestamp: time.Now(),
	}
	if data, err := json.Marshal(wsMsg); err == nil {
		hub.Broadcast <- data
	}
}

// Client write pump
func (c *Client) writePump(hub *Hub) {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.Send:
			if !ok {
				_ = c.writeMessage(websocket.CloseMessage, []byte{})
				return
			}

			if err := c.writeMessage(websocket.TextMessage, message); err != nil {
				return
			}
		case <-ticker.C:
			if err := c.writeMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// WSHandler returns the WebSocket handler for Fiber route
func WSHandler(hub *Hub) fiber.Handler {
	return HandleWebSocket(hub)
}

// BroadcastToUser sends a message to every live connection for a user.
func (h *Hub) BroadcastToUser(userID uuid.UUID, message []byte) error {
	h.mu.RLock()
	conns := h.UserClients[userID]
	clients := make([]*Client, 0, len(conns))
	for _, c := range conns {
		clients = append(clients, c)
	}
	h.mu.RUnlock()

	if len(clients) == 0 {
		return fmt.Errorf("user %s is not connected", userID.String())
	}

	var lastErr error
	sent := 0
	for _, client := range clients {
		select {
		case client.Send <- message:
			sent++
		default:
			lastErr = fmt.Errorf("send buffer full for user %s", userID.String())
		}
	}
	if sent == 0 {
		return lastErr
	}
	return nil
}

// BroadcastToUserExcept is BroadcastToUser skipping one connection (the device
// that originated a signal, so siblings can still be told "answered here").
func (h *Hub) BroadcastToUserExcept(userID uuid.UUID, exceptClientID string, message []byte) {
	h.mu.RLock()
	conns := h.UserClients[userID]
	clients := make([]*Client, 0, len(conns))
	for _, c := range conns {
		if c.ID == exceptClientID {
			continue
		}
		clients = append(clients, c)
	}
	h.mu.RUnlock()
	for _, client := range clients {
		select {
		case client.Send <- message:
		default:
		}
	}
}

// GenerateRandomID generates a random string ID
func GenerateRandomID(length int) string {
	const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	result := make([]byte, length)
	for i := range result {
		b := make([]byte, 1)
		_, _ = rand.Read(b)
		result[i] = chars[int(b[0])%len(chars)]
	}
	return string(result)
}

// FormatPresenceResponse formats presence data for API response
func FormatPresenceResponse(userID uuid.UUID, isOnline bool, lastSeen time.Time) map[string]interface{} {
	return map[string]interface{}{
		"user_id":   userID.String(),
		"is_online": isOnline,
		"last_seen": lastSeen.Format(time.RFC3339),
	}
}

// NotifyPushNotification sends a push notification via Firebase
func NotifyPushNotification(recipientID uuid.UUID, senderName, messageContent string) error {
	// Get Firebase token from database
	var user models.User
	if err := database.DB.Where("id = ?", recipientID).First(&user).Error; err != nil {
		return fmt.Errorf("failed to get user: %w", err)
	}

	if user.FirebaseToken == "" {
		return nil // No token, skip push notification
	}

	fcm := firebase.GetInstance()
	if fcm == nil {
		return nil // Firebase not configured
	}

	return fcm.SendPushNotification(user.FirebaseToken, senderName, messageContent, senderName, map[string]string{"type": "message"})
}
