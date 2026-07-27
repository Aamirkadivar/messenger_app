package handlers

import (
	"encoding/json"
	"net/http"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

// MessageService handles message-related operations
type MessageService struct {
	hub *websocket.Hub
}

// NewMessageService creates a new MessageService
func NewMessageService(hub *websocket.Hub) *MessageService {
	return &MessageService{
		hub: hub,
	}
}

// SendMessage handles sending a message
func (s *MessageService) SendMessage(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	var req models.MessageCreateRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request body",
			"message": "Failed to parse request body",
		})
	}

	chatID := req.ChatID
	chatIDParsed, err := uuid.Parse(chatID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid chat ID",
			"message": "Invalid chat ID format",
		})
	}

	// Verify chat exists and user is a participant
	var chat models.Chat
	if err := database.DB.First(&chat, chatIDParsed).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "chat not found",
			"message": "Chat not found",
		})
	}

	// Check if user is a participant
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ?", chatIDParsed, userID).First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a participant of this chat",
		})
	}

	// Content is an opaque blob to the server: for E2EE direct messages it's
	// client-produced ciphertext (hex of nonce||crypto_box); for plaintext
	// fallback it's the raw text. Either way the server never decrypts it -
	// end-to-end means only the two clients hold the keys.
	content := req.Content

	// Generate message ID
	messageID := uuid.New()

	// Create message record
	message := models.Message{
		ID:               messageID,
		ChatID:           chatIDParsed.String(),
		ChatType:         chat.Type,
		SenderID:         userID,
		EncryptedContent: content,
		IsEncrypted:      req.Encrypted,
	}

	if req.FileURL != "" {
		message.FileURL = req.FileURL
		message.ContentType = req.FileType
	}

	if err := database.DB.Create(&message).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to save message",
		})
	}

	// Create WebSocket message for real-time delivery
	wsMsg := models.WebSocketMessage{
		Type: "message",
		Data: map[string]interface{}{
			"chat_id":    chatID,
			"chat_type":  chat.Type,
			"message_id": messageID.String(),
			"sender_id":  userID.String(),
			"content":    content,
			"encrypted":  req.Encrypted,
			"timestamp":  message.CreatedAt,
		},
		Timestamp: time.Now(),
	}

	wsData, _ := json.Marshal(wsMsg)

	// Broadcast via WebSocket hub
	if s.hub != nil {
		s.hub.Broadcast <- wsData
	}

	return c.JSON(fiber.Map{
		"message": "Message sent successfully",
		"data": fiber.Map{
			"id":           messageID,
			"chat_id":      chatID,
			"sender_id":    userID,
			"encrypted":    req.Encrypted,
			"content":      content,
			"file_url":     message.FileURL,
			"file_type":    message.ContentType,
			"type":         chat.Type,
			"delivered_at": message.CreatedAt,
			"created_at":   message.CreatedAt,
			"updated_at":   message.CreatedAt,
		},
	})
}

// GetDirectChat handles getting or creating a direct chat
func (s *MessageService) GetDirectChat(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	contactID := c.Params("contact_id")

	contactIDParsed, err := uuid.Parse(contactID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid contact ID",
			"message": "Invalid contact ID format",
		})
	}

	// Check if a direct chat already exists between these two users, via the
	// chat_participants join table (chats itself has no user_1_id/user_2_id columns).
	var chat models.Chat
	err = database.DB.
		Joins("JOIN chat_participants cp1 ON cp1.chat_id = chats.id::uuid AND cp1.user_id = ?", userID).
		Joins("JOIN chat_participants cp2 ON cp2.chat_id = chats.id::uuid AND cp2.user_id = ?", contactIDParsed).
		Where("chats.type = ?", "direct").
		Order("chats.created_at ASC").
		First(&chat).Error

	if err != nil {
		// Create new direct chat
		chatID := uuid.New()
		chat = models.Chat{
			ID:      chatID.String(),
			Type:    "direct",
			Name:    "Direct Chat",
			OwnerID: userID,
		}
		database.DB.Create(&chat)

		// Add participants
		database.DB.Create(&models.ChatParticipant{
			ChatID: chat.ID,
			UserID: userID,
		})
		database.DB.Create(&models.ChatParticipant{
			ChatID: chat.ID,
			UserID: contactIDParsed,
		})
	}

	// Get chat info with participant details
	type ChatWithParticipants struct {
		models.Chat
		Participants []fiber.Map `json:"participants"`
	}

	var result ChatWithParticipants
	result.Chat = chat

	var participants []models.ChatParticipant
	database.DB.Where("chat_id = ?", chat.ID).Find(&participants)

	result.Participants = make([]fiber.Map, len(participants))
	for i, p := range participants {
		var user models.User
		database.DB.First(&user, p.UserID)
		result.Participants[i] = fiber.Map{
			"id":           user.ID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
		}
	}

	return c.JSON(fiber.Map{
		"data": result,
	})
}

// GetMessages handles getting messages for a chat with pagination
func (s *MessageService) GetMessages(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	limit := 50
	offset := 0

	if l := c.QueryInt("limit", 50); l > 0 {
		limit = l
	}
	if l := c.QueryInt("limit", 50); l > 100 {
		limit = 100
	}
	if o := c.QueryInt("offset", 0); o >= 0 {
		offset = o
	}

	// Get before/after cursor for cursor-based pagination
	var before, after string
	if b := c.Query("before"); b != "" {
		before = b
	}
	if a := c.Query("after"); a != "" {
		after = a
	}

	chatIDParsed, err := uuid.Parse(chatID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid chat ID",
			"message": "Invalid chat ID format",
		})
	}

	// Verify user is a participant
	userID := middleware.GetCurrentUserID(c)
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ?", chatIDParsed.String(), userID).First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a participant of this chat",
		})
	}

	var messages []models.Message
	query := database.DB.Where("chat_id = ?", chatIDParsed.String()).
		Order("created_at DESC")

	if before != "" {
		beforeUUID, err := uuid.Parse(before)
		if err == nil {
			query = query.Where("id < ?", beforeUUID)
		}
	}
	if after != "" {
		afterUUID, err := uuid.Parse(after)
		if err == nil {
			query = query.Where("id > ?", afterUUID)
		}
	}

	if err := query.Limit(limit).Find(&messages).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to fetch messages",
		})
	}

	// Get total count
	var total int64
	database.DB.Model(&models.Message{}).Where("chat_id = ?", chatIDParsed.String()).Count(&total)

	// Decrypt messages for response
	type DecryptedMessage struct {
		ID          string     `json:"id"`
		ChatID      string     `json:"chat_id"`
		SenderID    string     `json:"sender_id"`
		Sender      fiber.Map  `json:"sender"`
		Content     string     `json:"content"`
		Encrypted   bool       `json:"encrypted"`
		FileURL     *string    `json:"file_url,omitempty"`
		FileType    *string    `json:"file_type,omitempty"`
		Type        string     `json:"type"`
		DeliveredAt *time.Time `json:"delivered_at"`
		ReadAt      *time.Time `json:"read_at"`
		CreatedAt   time.Time  `json:"created_at"`
		UpdatedAt   time.Time  `json:"updated_at"`
	}

	decryptedMessages := make([]DecryptedMessage, len(messages))
	senderIDs := make(map[string]bool)
	for _, m := range messages {
		senderIDs[m.SenderID.String()] = true
	}

	var senders []models.User
	if len(senderIDs) > 0 {
		ids := make([]string, 0, len(senderIDs))
		for id := range senderIDs {
			ids = append(ids, id)
		}
		database.DB.Find(&senders, ids)
	}

	senderMap := make(map[string]models.User)
	for _, s := range senders {
		senderMap[s.ID.String()] = s
	}

	for i, m := range messages {
		sender := senderMap[m.SenderID.String()]
		fileURL := m.FileURL
		decryptedMessages[i] = DecryptedMessage{
			ID:       m.ID.String(),
			ChatID:   m.ChatID,
			SenderID: m.SenderID.String(),
			Sender: fiber.Map{
				"id":           sender.ID,
				"email":        sender.Email,
				"username":     sender.Username,
				"display_name": sender.DisplayName,
			},
			Content:     m.EncryptedContent,
			Encrypted:   m.IsEncrypted,
			FileURL:     &fileURL,
			FileType:    &m.ContentType,
			Type:        m.ChatType,
			DeliveredAt: m.DeliveredAt,
			ReadAt:      m.ReadAt,
			CreatedAt:   m.CreatedAt,
			UpdatedAt:   m.UpdatedAt,
		}
	}

	hasMore := int64(offset+limit) < total

	var beforeCursor, afterCursor string
	if len(messages) > 0 {
		beforeCursor = messages[0].ID.String()
		afterCursor = messages[len(messages)-1].ID.String()
	}

	return c.JSON(fiber.Map{
		"data":     decryptedMessages,
		"total":    total,
		"limit":    limit,
		"offset":   offset,
		"has_more": hasMore,
		"cursor": fiber.Map{
			"before": beforeCursor,
			"after":  afterCursor,
		},
	})
}

// DeleteMessage handles deleting a message
func (s *MessageService) DeleteMessage(c *fiber.Ctx) error {
	messageID := c.Params("message_id")
	userID := middleware.GetCurrentUserID(c)

	messageIDParsed, err := uuid.Parse(messageID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid message ID",
			"message": "Invalid message ID format",
		})
	}

	var message models.Message
	if err := database.DB.First(&message, messageIDParsed).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "message not found",
			"message": "Message not found",
		})
	}

	// Verify user is the sender
	if message.SenderID != userID {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You can only delete your own messages",
		})
	}

	database.DB.Delete(&message, messageIDParsed)

	return c.JSON(fiber.Map{
		"message": "Message deleted successfully",
	})
}

// MarkAsRead handles marking messages as read
func (s *MessageService) MarkAsRead(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	chatIDParsed, err := uuid.Parse(chatID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid chat ID",
			"message": "Invalid chat ID format",
		})
	}

	// Update read_at for unread messages. (delivered_at is never populated
	// anywhere in this codebase - delivery tracking isn't implemented - so
	// requiring it here as a earlier version of this handler did meant this
	// UPDATE could never match any row.)
	readAt := time.Now()
	result := database.DB.Model(&models.Message{}).
		Where("chat_id = ? AND sender_id != ? AND read_at IS NULL",
			chatIDParsed.String(), userID).
		Update("read_at", readAt)

	if result.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to mark messages as read",
		})
	}

	// Update last read timestamp for the chat-participant relationship
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id = ?", chatIDParsed.String(), userID).
		Update("last_read_at", readAt)

	// Notify whoever's messages just got read, in real time, so their "seen"
	// indicator updates without waiting for them to reopen the chat.
	if result.RowsAffected > 0 && s.hub != nil {
		var chat models.Chat
		database.DB.First(&chat, chatIDParsed)

		wsMsg := models.WebSocketMessage{
			Type: "read",
			Data: map[string]interface{}{
				"chat_id":   chatIDParsed.String(),
				"chat_type": chat.Type,
				"reader_id": userID.String(),
				"read_at":   readAt,
			},
			Timestamp: time.Now(),
		}
		if wsData, err := json.Marshal(wsMsg); err == nil {
			s.hub.Broadcast <- wsData
		}
	}

	return c.JSON(fiber.Map{
		"message": "Messages marked as read",
	})
}

// GetUnreadCount handles getting unread message count for a chat
func (s *MessageService) GetUnreadCount(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	chatIDParsed, err := uuid.Parse(chatID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid chat ID",
			"message": "Invalid chat ID format",
		})
	}

	var count int64
	database.DB.Model(&models.Message{}).
		Where("chat_id = ? AND sender_id != ? AND read_at IS NULL", chatIDParsed.String(), userID).
		Count(&count)

	return c.JSON(fiber.Map{
		"data": fiber.Map{
			"chat_id":      chatID,
			"unread_count": count,
		},
	})
}

// ChatListItem represents a single chat entry in the user's chat list
type ChatListItem struct {
	ID             string       `json:"id"`
	Type           string       `json:"type"`
	Name           string       `json:"name"`
	AvatarURL      string       `json:"avatar_url"`
	OtherUserID    *uuid.UUID   `json:"other_user_id,omitempty"`
	OtherUser      *models.User `json:"other_user,omitempty"`
	LastMessage    *MessageResp `json:"last_message"`
	LastMessageAt  *time.Time   `json:"last_message_at"`
	UnreadCount    int64        `json:"unread_count"`
	LastReadAt     *time.Time   `json:"last_read_at"`
	IsOnline       bool         `json:"is_online"`
	UpdatedAt      time.Time    `json:"updated_at"`
}

type MessageResp struct {
	ID        string    `json:"id"`
	SenderID  string    `json:"sender_id"`
	Content   string    `json:"content"`
	ContentType string  `json:"content_type"`
	Encrypted bool      `json:"encrypted"`
	CreatedAt time.Time `json:"created_at"`
}

// GetChatsByUserID handles getting all chats for the current user
func (s *MessageService) GetChatsByUserID(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	// Get all ChatParticipant records for this user
	var participants []models.ChatParticipant
	if err := database.DB.Where("user_id = ? AND left_at IS NULL", userID).
		Order("last_read_at DESC").
		Find(&participants).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to fetch chats",
		})
	}

	if len(participants) == 0 {
		return c.JSON(fiber.Map{
			"data": []ChatListItem{},
		})
	}

	// Collect unique chat IDs
	chatIDs := make([]string, len(participants))
	for i, p := range participants {
		chatIDs[i] = p.ChatID
	}

	// Fetch all chats
	var chats []models.Chat
	database.DB.Find(&chats, chatIDs)
	chatMap := make(map[string]models.Chat)
	for _, ch := range chats {
		chatMap[ch.ID] = ch
	}

	// Fetch all participants for these chats (to find other users)
	var allParticipants []models.ChatParticipant
	database.DB.Where("chat_id IN ? AND left_at IS NULL", chatIDs).Find(&allParticipants)

	// Group participants by chat_id
	participantsByChat := make(map[string][]models.ChatParticipant)
	for _, p := range allParticipants {
		participantsByChat[p.ChatID] = append(participantsByChat[p.ChatID], p)
	}

	// Fetch last messages for each chat
	var messages []models.Message
	database.DB.Where("chat_id IN ? AND deleted_at IS NULL", chatIDs).
		Order("chat_id, created_at DESC").
		Find(&messages)

	// Get latest message per chat
	latestMessages := make(map[string]models.Message)
	for _, m := range messages {
		if _, exists := latestMessages[m.ChatID]; !exists {
			latestMessages[m.ChatID] = m
		}
	}

	// Fetch unread counts per chat
	type chatUnread struct {
		ChatID string `gorm:"column:chat_id"`
		Count  int64  `gorm:"column:count"`
	}
	var unreadResults []chatUnread
	database.DB.Model(&models.Message{}).
		Select("chat_id, COUNT(*) as count").
		Where("chat_id IN ? AND sender_id != ? AND read_at IS NULL", chatIDs, userID).
		Group("chat_id").
		Scan(&unreadResults)
	unreadMap := make(map[string]int64)
	for _, u := range unreadResults {
		unreadMap[u.ChatID] = u.Count
	}

	// Build response
	chatsList := make([]ChatListItem, 0, len(participants))
	for _, p := range participants {
		ch, exists := chatMap[p.ChatID]
		if !exists {
			continue
		}

		item := ChatListItem{
			ID:          ch.ID,
			Type:        ch.Type,
			Name:        ch.Name,
			AvatarURL:   ch.AvatarURL,
			LastMessageAt: ch.LastMessageAt,
			LastReadAt:  p.LastReadAt,
			UpdatedAt:   ch.UpdatedAt,
		}

		// Find other user(s)
		chatParticipants := participantsByChat[ch.ID]
		for _, cp := range chatParticipants {
			if cp.UserID == userID {
				continue
			}
			uid := cp.UserID
			item.OtherUserID = &uid
			var user models.User
			if err := database.DB.First(&user, uid).Error; err == nil {
				item.OtherUser = &user
				item.IsOnline = user.IsOnline
				if ch.Type == "direct" {
					break
				}
			}
		}

		// Get latest message
		if msg, ok := latestMessages[ch.ID]; ok {
			item.LastMessage = &MessageResp{
				ID:          msg.ID.String(),
				SenderID:    msg.SenderID.String(),
				Content:     msg.EncryptedContent,
				ContentType: msg.ContentType,
				Encrypted:   msg.IsEncrypted,
				CreatedAt:   msg.CreatedAt,
			}
		}

		// Get unread count
		item.UnreadCount = unreadMap[ch.ID]

		chatsList = append(chatsList, item)
	}

	// Sort by last_message_at descending (or last_read_at if no message)
	// Already sorted by last_read_at via query, but let's ensure proper ordering
	return c.JSON(fiber.Map{
		"data": chatsList,
	})
}
