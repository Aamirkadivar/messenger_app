package websocket

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"messenger-app/crypto"
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
	ID     string
	UserID uuid.UUID
	Conn   *websocket.Conn
	Send   chan []byte
	Rooms  map[string]bool
	mu     sync.RWMutex
}

// Hub manages all connected WebSocket clients
type Hub struct {
	Clients    map[uuid.UUID]*Client
	Rooms      map[string]map[uuid.UUID]bool
	Register   chan *Client
	Unregister chan *Client
	Broadcast  chan []byte
	mu         sync.RWMutex
}

// NewHub creates a new Hub instance
func NewHub() *Hub {
	return &Hub{
		Clients:    make(map[uuid.UUID]*Client),
		Rooms:      make(map[string]map[uuid.UUID]bool),
		Register:   make(chan *Client),
		Unregister: make(chan *Client),
		Broadcast:  make(chan []byte),
	}
}

// Run starts the Hub event loop
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.Register:
			h.mu.Lock()
			h.Clients[client.UserID] = client
			h.mu.Unlock()
			log.Printf("Client %s connected", client.ID)

		case client := <-h.Unregister:
			h.mu.Lock()
			if _, ok := h.Clients[client.UserID]; ok {
				delete(h.Clients, client.UserID)
				close(client.Send)
				for room := range client.Rooms {
					if _, ok := h.Rooms[room]; ok {
						delete(h.Rooms[room], client.UserID)
					}
				}
			}
			h.mu.Unlock()
			log.Printf("Client %s disconnected", client.ID)

		case message := <-h.Broadcast:
			var wsMsg models.WebSocketMessage
			if err := json.Unmarshal(message, &wsMsg); err != nil {
				log.Printf("Error parsing broadcast message: %v", err)
				continue
			}

			h.mu.RLock()
			for _, client := range h.Clients {
				if wsMsg.Type == "message" || wsMsg.Type == "typing" {
					if wsMsg.Data != nil {
						data := wsMsg.Data.(map[string]interface{})
						chatID, _ := data["chat_id"].(string)
						chatType, _ := data["chat_type"].(string)

						if chatType == "direct" {
							if client.Rooms[chatID] {
								select {
								case client.Send <- message:
								default:
									close(client.Send)
								}
							}
						} else if chatType == "group" {
							if client.Rooms[chatID] {
								select {
								case client.Send <- message:
								default:
									close(client.Send)
								}
							}
						}
					}
				} else if wsMsg.Type == "presence" || wsMsg.Type == "typing" {
					if wsMsg.Data != nil {
						data := wsMsg.Data.(map[string]interface{})
						chatID, _ := data["chat_id"].(string)
						if chatID != "" && client.Rooms[chatID] {
							select {
							case client.Send <- message:
							default:
								close(client.Send)
							}
						}
					}
				}
			}
			h.mu.RUnlock()
		}
	}
}

// JoinRoom allows a client to join a room (chat)
func (h *Hub) JoinRoom(userID uuid.UUID, room string) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if client, ok := h.Clients[userID]; ok {
		if client.Rooms == nil {
			client.Rooms = make(map[string]bool)
		}
		client.Rooms[room] = true
		if h.Rooms[room] == nil {
			h.Rooms[room] = make(map[uuid.UUID]bool)
		}
		h.Rooms[room][userID] = true
	}
}

// LeaveRoom allows a client to leave a room
func (h *Hub) LeaveRoom(userID uuid.UUID, room string) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if client, ok := h.Clients[userID]; ok {
		delete(client.Rooms, room)
	}
	if roomMembers, ok := h.Rooms[room]; ok {
		delete(roomMembers, userID)
	}
}

// IsUserOnline checks if a user is currently connected
func (h *Hub) IsUserOnline(userID uuid.UUID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.Clients[userID]
	return ok
}

// GetOnlineUsers returns a list of online user IDs
func (h *Hub) GetOnlineUsers() []uuid.UUID {
	h.mu.RLock()
	defer h.mu.RUnlock()

	online := make([]uuid.UUID, 0, len(h.Clients))
	for userID := range h.Clients {
		online = append(online, userID)
	}
	return online
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

		clientID := fmt.Sprintf("%s-%d", userID.String(), time.Now().UnixNano())
		client := &Client{
			ID:     clientID,
			UserID: userID,
			Conn:   c,
			Send:   make(chan []byte, 256),
			Rooms:  make(map[string]bool),
		}

		// Register client
		hub.Register <- client
		hub.JoinRoom(userID, fmt.Sprintf("user:%s", userID.String()))

		// Update presence
		updatePresence(userID, true)

		// Keepalive: reset the read deadline on every pong so the connection
		// stays open as long as the client keeps responding to pings.
		c.Conn.SetReadDeadline(time.Now().Add(pongWait))
		c.Conn.SetPongHandler(func(string) error {
			c.Conn.SetReadDeadline(time.Now().Add(pongWait))
			return nil
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
			handleClientMessage(hub, userID, message)
		}

		// Unregister and cleanup
		hub.Unregister <- client
		leavePresence(userID)
	})
}

// handleClientMessage processes messages received from clients
func handleClientMessage(hub *Hub, userID uuid.UUID, message []byte) {
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
				hub.JoinRoom(userID, chatID)
			}
		}
	case "leave":
		if data, ok := wsMsg.Data.(map[string]interface{}); ok {
			if chatID, ok := data["chat_id"].(string); ok {
				hub.LeaveRoom(userID, chatID)
			}
		}
	case "message", "typing", "presence":
		broadcastMsg, _ := json.Marshal(wsMsg)
		hub.Broadcast <- broadcastMsg
	default:
		log.Printf("Unknown message type: %s", wsMsg.Type)
	}
}

// updatePresence updates user presence in database
func updatePresence(userID uuid.UUID, isOnline bool) {
	database.DB.Model(&models.User{}).Where("id = ?", userID).Updates(map[string]interface{}{
		"is_online": isOnline,
		"last_seen": time.Now(),
	})
}

// leavePresence handles user disconnect
func leavePresence(userID uuid.UUID) {
	database.DB.Model(&models.User{}).Where("id = ?", userID).Update("is_online", false)

	// Update or create presence record
	var presence models.Presence
	result := database.DB.Where("user_id = ?", userID).First(&presence)
	if result.Error != nil {
		presence = models.Presence{
			UserID:   userID,
			IsOnline: false,
			LastSeen: time.Now(),
		}
		database.DB.Create(&presence)
	} else {
		database.DB.Model(&presence).Updates(map[string]interface{}{
			"is_online": false,
			"last_seen": time.Now(),
		})
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
				_ = c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.Conn.WriteMessage(websocket.TextMessage, message); err != nil {
				return
			}
		case <-ticker.C:
			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// WSHandler returns the WebSocket handler for Fiber route
func WSHandler(hub *Hub) fiber.Handler {
	return HandleWebSocket(hub)
}

// BroadcastToUser sends a message to a specific user's WebSocket connection
func (h *Hub) BroadcastToUser(userID uuid.UUID, message []byte) error {
	h.mu.RLock()
	client, ok := h.Clients[userID]
	h.mu.RUnlock()

	if !ok {
		return fmt.Errorf("user %s is not connected", userID.String())
	}

	select {
	case client.Send <- message:
		return nil
	default:
		return fmt.Errorf("send buffer full for user %s", userID.String())
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

// DecryptMessageForUser decrypts a message for the given user
func DecryptMessageForUser(encryptedContent string, recipientPrivateKey string, nonce string) (string, error) {
	return crypto.DecryptMessage(encryptedContent, recipientPrivateKey, nonce)
}