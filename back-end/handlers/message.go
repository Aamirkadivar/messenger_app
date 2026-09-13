package handlers

import (
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"
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

	// Idempotency. A client that sends client_message_id repeats it on every
	// retry of the same logical message, so the one question that matters for a
	// retry is "did this sender already get it accepted?".
	//
	// That is answered before every other check on purpose: a retry that lands
	// after the sender left the group, or after a block, must still learn that
	// its message WAS accepted - reporting a failure for a message the server
	// holds is how a client ends up re-sending it as a new one. The lookup is
	// scoped to the caller's own rows, so it discloses nothing about anyone else.
	var clientMessageID *string
	if raw := strings.TrimSpace(req.ClientMessageID); raw != "" {
		parsed, err := uuid.Parse(raw)
		if err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error":   "invalid_client_message_id",
				"message": "client_message_id must be a UUID",
			})
		}
		canonical := parsed.String()
		clientMessageID = &canonical
		existing, found, err := findSentByClientID(userID, canonical)
		if err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Failed to check message",
			})
		}
		if found {
			return replayAccepted(c, existing, chatIDParsed)
		}
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
	// A group member who left, or was removed, keeps their row - left_at records
	// the departure - so the row alone is not membership. Direct chats are the
	// exception: there left_at only means the chat was cleared from the list, and
	// sending brings it back (below).
	if chat.Type != "direct" && participant.LeftAt != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a participant of this chat",
		})
	}

	// Block check for direct chats — either direction rejects the send so a
	// blocked contact cannot resurrect a hidden chat via SendMessage.
	if chat.Type == "direct" {
		var other models.ChatParticipant
		if err := database.DB.Where("chat_id = ? AND user_id != ?", chatIDParsed, userID).
			First(&other).Error; err == nil {
			if IsEitherBlocked(userID, other.UserID) {
				return c.Status(http.StatusForbidden).JSON(fiber.Map{
					"error":   "blocked",
					"message": "Cannot message this user",
				})
			}
		}
	}

	if !req.Encrypted {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "encryption_required",
			"message": "Messages must be end-to-end encrypted",
		})
	}
	content := req.Content

	// Reply is just an id pointer - clients resolve the quote from local
	// history so plaintext never rides along. Parent must live in this chat.
	if req.ReplyToID != nil {
		var parent models.Message
		if err := database.DB.Select("id", "chat_id").First(&parent, *req.ReplyToID).Error; err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error":   "invalid reply_to_id",
				"message": "Reply target message not found",
			})
		}
		if parent.ChatID != chatIDParsed.String() {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error":   "invalid reply_to_id",
				"message": "Reply target is not in this chat",
			})
		}
	}

	// Generate message ID
	messageID := uuid.New()

	// Create message record
	message := models.Message{
		ID:                     messageID,
		ChatID:                 chatIDParsed.String(),
		ChatType:               chat.Type,
		SenderID:               userID,
		EncryptedContent:       content,
		IsEncrypted:            req.Encrypted,
		KeyVersion:             req.KeyVersion,
		EncryptionVersion:      req.EncryptionVersion,
		ReplyToID:              req.ReplyToID,
		IsForwarded:            req.IsForwarded,
		ForwardedFromName:      req.ForwardedFromName,
		ForwardedFromMessageID: req.ForwardedFromMessageID,
		SenderDeviceID:         c.Get("X-Device-Id"),
		ClientMessageID:        clientMessageID,
	}

	if message.EncryptionVersion == 0 {
		message.EncryptionVersion = 1
	}

	if req.FileType != "" {
		message.ContentType = req.FileType
	} else if req.ContentType != "" {
		message.ContentType = req.ContentType
	}
	// New clients keep file_url / names / duration / thumbnail in EM1.
	// Legacy sends that still include file_url are stored as-is.
	if req.FileURL != "" {
		message.FileURL = req.FileURL
		message.DurationMs = req.DurationMs
		message.FileName = req.FileName
		message.FileSize = req.FileSize
		message.ThumbnailURL = req.ThumbnailURL
	}

	// The checks above ran without a lock, and a group can change under them:
	// every lifecycle operation - DeleteGroup included, which soft-deletes the
	// group's messages and then deletes the chat - first takes the chat row FOR
	// UPDATE (see lockGroup). So the insert runs in a transaction that holds the
	// chat row in KEY SHARE, and the checks a lifecycle operation can invalidate
	// are taken again under it. A send either commits before a concurrent
	// DeleteGroup, which then soft-deletes it with the rest, or waits and finds
	// the chat gone: it can never leave a live message in a deleted chat.
	//
	// KEY SHARE is exactly the lock the old fk_chats_last_message check took on
	// every INSERT (see MigrateDB). It does not block other sends, nor plain
	// updates of the chat row. Everything in the transaction goes through tx:
	// the connection pool is bounded, and a second connection taken while this
	// one holds the lock could exhaust it.
	failedToSave := func() error {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to save message",
		})
	}
	tx := database.DB.Begin()
	if tx.Error != nil {
		return failedToSave()
	}
	committed := false
	defer func() {
		if !committed {
			tx.Rollback()
		}
	}()
	var held models.Chat
	lock := tx.Clauses(clause.Locking{Strength: "KEY SHARE"}).Select("id").
		Where("id = ?", chatIDParsed.String()).Limit(1).Find(&held)
	if lock.Error != nil {
		return failedToSave()
	}
	if lock.RowsAffected == 0 {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "chat not found",
			"message": "Chat not found",
		})
	}
	if chat.Type != "direct" {
		var active int64
		if err := tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatIDParsed, userID).
			Count(&active).Error; err != nil {
			return failedToSave()
		}
		if active == 0 {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "forbidden",
				"message": "You are not a participant of this chat",
			})
		}
	}

	// A new message brings a direct chat back for anyone who had cleared it
	// off their list (see DeleteChat) - otherwise the chat stays hidden and
	// their messages silently disappear. Deliberately direct-only: in a group,
	// left_at means someone actually left, and a message must not drag them
	// back in.
	if chat.Type == "direct" {
		tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND left_at IS NOT NULL", chatID).
			Update("left_at", nil)
	}

	// Durable persistence, and the only point acceptance may follow. The INSERT
	// commits before anything is reported, and a failure is an HTTP failure.
	//
	// ON CONFLICT covers the race the lookup above cannot: two retries of one
	// logical message arriving together. Exactly one inserts; the other gets no
	// row back and resolves to the winner. RETURNING reads the seq the database
	// assigned inside the INSERT (see migratePhase71).
	res := tx.Clauses(
		clause.OnConflict{
			Columns:     []clause.Column{{Name: "sender_id"}, {Name: "client_message_id"}},
			TargetWhere: clause.Where{Exprs: []clause.Expression{clause.Expr{SQL: "client_message_id IS NOT NULL"}}},
			DoNothing:   true,
		},
		clause.Returning{Columns: []clause.Column{{Name: "seq"}}},
	).Create(&message)
	if res.Error != nil {
		return failedToSave()
	}
	if err := tx.Commit().Error; err != nil {
		return failedToSave()
	}
	committed = true
	if res.RowsAffected == 0 && clientMessageID != nil {
		existing, found, err := findSentByClientID(userID, *clientMessageID)
		if err != nil || !found {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Failed to save message",
			})
		}
		return replayAccepted(c, existing, chatIDParsed)
	}
	now := time.Now()
	database.DB.Model(&models.Chat{}).Where("id = ?", chatID).Updates(map[string]interface{}{
		"last_message_at": now,
		"updated_at":      now,
	})

	var forwardedFromMsgID interface{}
	if message.ForwardedFromMessageID != nil {
		forwardedFromMsgID = message.ForwardedFromMessageID.String()
	}
	var replyToID interface{}
	if message.ReplyToID != nil {
		replyToID = message.ReplyToID.String()
	}

	// Create WebSocket message for real-time delivery
	wsMsg := models.WebSocketMessage{
		Type: "message",
		Data: map[string]interface{}{
			// Canonical form: rooms are joined under the canonical chat id.
			"chat_id":                   chatIDParsed.String(),
			"chat_type":                 chat.Type,
			"message_id":                messageID.String(),
			"sender_id":                 userID.String(),
			"content":                   content,
			"encrypted":                 req.Encrypted,
			"file_url":                  message.FileURL,
			"file_type":                 message.ContentType,
			"file_name":                 message.FileName,
			"file_size":                 message.FileSize,
			"duration_ms":               message.DurationMs,
			"thumbnail_url":             message.ThumbnailURL,
			"key_version":               message.KeyVersion,
			"encryption_version":        message.EncryptionVersion,
			"sender_device_id":          message.SenderDeviceID,
			"reply_to_id":               replyToID,
			"is_forwarded":              message.IsForwarded,
			"forwarded_from_name":       message.ForwardedFromName,
			"forwarded_from_message_id": forwardedFromMsgID,
			"timestamp":                 message.CreatedAt,
			"seq":                       message.Seq,
		},
		Timestamp: time.Now(),
	}

	wsData, _ := json.Marshal(wsMsg)

	// Realtime push of the PERSISTED message, after the commit. It is a
	// best-effort shortcut, never the delivery guarantee: a recipient that is
	// offline, disconnected mid-push or too slow to drain its buffer gets this
	// message from GET /messages ?after= when it next syncs.
	if s.hub != nil {
		s.hub.Broadcast <- wsData
	}

	return c.JSON(acceptedResponse(message, false))
}

// findSentByClientID returns the caller's own message carrying clientMessageID.
func findSentByClientID(senderID uuid.UUID, clientMessageID string) (models.Message, bool, error) {
	var m models.Message
	tx := database.DB.Where("sender_id = ? AND client_message_id = ?", senderID, clientMessageID).
		Limit(1).Find(&m)
	if tx.Error != nil {
		return m, false, tx.Error
	}
	return m, tx.RowsAffected > 0, nil
}

// replayAccepted answers a retry of a message that is already stored. Nothing
// is inserted and nothing is re-broadcast: the original acceptance did both.
// A client_message_id reused for a DIFFERENT chat is not a retry but a client
// bug, and resolving it to the other chat's message would be wrong either way.
func replayAccepted(c *fiber.Ctx, existing models.Message, chatID uuid.UUID) error {
	if existing.ChatID != chatID.String() {
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error":   "client_message_id_conflict",
			"message": "client_message_id already identifies a message in another chat",
		})
	}
	return c.JSON(acceptedResponse(existing, true))
}

// acceptedResponse reports server acceptance: the ciphertext is durably stored
// and has a position in its chat. It deliberately carries no delivered_at -
// acceptance says nothing about whether any recipient has the message yet, and
// this server does not track delivery at all.
func acceptedResponse(m models.Message, replay bool) fiber.Map {
	var forwardedFromMsgID interface{}
	if m.ForwardedFromMessageID != nil {
		forwardedFromMsgID = m.ForwardedFromMessageID.String()
	}
	var replyToID interface{}
	if m.ReplyToID != nil {
		replyToID = m.ReplyToID.String()
	}
	var clientMessageID interface{}
	if m.ClientMessageID != nil {
		clientMessageID = *m.ClientMessageID
	}
	return fiber.Map{
		"message": "Message accepted",
		"data": fiber.Map{
			"id":                        m.ID,
			"chat_id":                   m.ChatID,
			"sender_id":                 m.SenderID,
			"encrypted":                 m.IsEncrypted,
			"content":                   m.EncryptedContent,
			"file_url":                  m.FileURL,
			"file_type":                 m.ContentType,
			"file_name":                 m.FileName,
			"file_size":                 m.FileSize,
			"duration_ms":               m.DurationMs,
			"thumbnail_url":             m.ThumbnailURL,
			"key_version":               m.KeyVersion,
			"encryption_version":        m.EncryptionVersion,
			"sender_device_id":          m.SenderDeviceID,
			"reply_to_id":               replyToID,
			"is_forwarded":              m.IsForwarded,
			"forwarded_from_name":       m.ForwardedFromName,
			"forwarded_from_message_id": forwardedFromMsgID,
			"type":                      m.ChatType,
			"status":                    "accepted",
			"accepted_at":               m.CreatedAt,
			"seq":                       m.Seq,
			"client_message_id":         clientMessageID,
			"idempotent_replay":         replay,
			"created_at":                m.CreatedAt,
			"updated_at":                m.UpdatedAt,
		},
	}
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

	if IsEitherBlocked(userID, contactIDParsed) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "blocked",
			"message": "Cannot message this user",
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
//
// Pagination contract (Phase 71) - one model, keyed on the per-chat seq:
//
//	(no cursor)   the newest `limit` messages, newest first         order=desc
//	?before=N     messages with seq < N, newest first (scroll back)  order=desc
//	?after=N      messages with seq > N, oldest first (catch-up)     order=asc
//
// limit is 1..100 (default 50; larger values are clamped to 100). has_more is
// exact: another page exists in the direction being read. cursor.after is the
// highest seq on the page (the next ?after=) and cursor.before the lowest (the
// next ?before=); on an empty page cursor.after echoes the ?after= it was given
// so a client's sync point never moves backwards. A client is synchronised when
// ?after=<its sync point> returns has_more=false.
//
// seq is gap-free and commit-ordered per chat (see models.migratePhase71), so
// paging ?after= from any point returns every later message exactly once, even
// for rows that share a timestamp. before+after together, offset, and malformed
// or negative cursors are 400: nothing here is silently ignored.
func (s *MessageService) GetMessages(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")

	badPage := func(code, msg string) error {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": code, "message": msg})
	}
	limit := 50
	if raw := c.Query("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 {
			return badPage("invalid_limit", "limit must be a positive integer")
		}
		if n > 100 {
			n = 100
		}
		limit = n
	}
	if c.Query("offset") != "" {
		return badPage("offset_not_supported", "offset pagination is not supported; page with before/after")
	}
	beforeRaw, afterRaw := c.Query("before"), c.Query("after")
	if beforeRaw != "" && afterRaw != "" {
		return badPage("ambiguous_cursor", "use either before or after, not both")
	}
	parseSeq := func(raw string) (int64, bool) {
		n, err := strconv.ParseInt(raw, 10, 64)
		return n, err == nil && n >= 0
	}
	var before, after int64
	if beforeRaw != "" {
		var ok bool
		if before, ok = parseSeq(beforeRaw); !ok {
			return badPage("invalid_cursor", "before must be a non-negative message seq")
		}
	}
	if afterRaw != "" {
		var ok bool
		if after, ok = parseSeq(afterRaw); !ok {
			return badPage("invalid_cursor", "after must be a non-negative message seq")
		}
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

	// A group member who left keeps what was sent while they were in the group
	// and nothing after it: the bound below applies to every page, every cursor
	// and the total alike. Direct chats are exempt - there left_at only means the
	// chat was cleared from the list, and a new message brings it back.
	var departedAt *time.Time
	if participant.LeftAt != nil {
		var chat models.Chat
		if err := database.DB.Select("type").First(&chat, "id = ?", chatIDParsed.String()).Error; err != nil || chat.Type != "direct" {
			departedAt = participant.LeftAt
		}
	}

	var messages []models.Message
	// Soft-deleted messages are not history. DeleteGroup keeps a deleted
	// group's messages as rows with deleted_at set; every other reader already
	// leaves them out (the chat list, archive authorization), and so must this
	// one - the page, every cursor and the total alike.
	//
	// Hide anything this user deleted for themselves. A NOT EXISTS against the
	// join table, rather than an array-contains on messages.deleted_for: that
	// column cannot be read back at all once written (see MessageDeletion).
	query := database.DB.Where("chat_id = ?", chatIDParsed.String()).
		Where("messages.deleted_at IS NULL").
		Where("NOT EXISTS (SELECT 1 FROM message_deletions md WHERE md.message_id = messages.id AND md.user_id = ?)", userID)
	if departedAt != nil {
		query = query.Where("created_at < ?", *departedAt)
	}
	order := "desc"
	switch {
	case afterRaw != "":
		query = query.Where("seq > ?", after).Order("seq ASC")
		order = "asc"
	case beforeRaw != "":
		query = query.Where("seq < ?", before).Order("seq DESC")
	default:
		query = query.Order("seq DESC")
	}

	// One extra row decides has_more exactly, without a second query.
	if err := query.Limit(limit + 1).Find(&messages).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to fetch messages",
		})
	}
	hasMore := len(messages) > limit
	if hasMore {
		messages = messages[:limit]
	}

	// Get total count
	var total int64
	totalQuery := database.DB.Model(&models.Message{}).
		Where("chat_id = ?", chatIDParsed.String()).
		Where("messages.deleted_at IS NULL").
		Where("NOT EXISTS (SELECT 1 FROM message_deletions md WHERE md.message_id = messages.id AND md.user_id = ?)", userID)
	if departedAt != nil {
		totalQuery = totalQuery.Where("created_at < ?", *departedAt)
	}
	totalQuery.Count(&total)

	// Decrypt messages for response
	type DecryptedMessage struct {
		ID                     string     `json:"id"`
		ChatID                 string     `json:"chat_id"`
		SenderID               string     `json:"sender_id"`
		Sender                 fiber.Map  `json:"sender"`
		Content                string     `json:"content"`
		Encrypted              bool       `json:"encrypted"`
		FileURL                *string    `json:"file_url,omitempty"`
		FileType               *string    `json:"file_type,omitempty"`
		FileName               *string    `json:"file_name,omitempty"`
		FileSize               int64      `json:"file_size"`
		DurationMs             int64      `json:"duration_ms"`
		ThumbnailURL           string     `json:"thumbnail_url"`
		KeyVersion             int        `json:"key_version"`
		EncryptionVersion      int        `json:"encryption_version"`
		SenderDeviceID         string     `json:"sender_device_id"`
		ReplyToID              *string    `json:"reply_to_id,omitempty"`
		IsForwarded            bool       `json:"is_forwarded"`
		ForwardedFromName      string     `json:"forwarded_from_name"`
		ForwardedFromMessageID *string    `json:"forwarded_from_message_id,omitempty"`
		Type                   string     `json:"type"`
		DeliveredAt            *time.Time `json:"delivered_at"`
		ReadAt                 *time.Time `json:"read_at"`
		CreatedAt              time.Time  `json:"created_at"`
		UpdatedAt              time.Time  `json:"updated_at"`
		Seq                    int64      `json:"seq"`
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
		// Both of these must be per-iteration copies. go.mod declares go 1.21,
		// where the range variable is reused between iterations - taking
		// &m.ContentType directly gave every message the same pointer, so the
		// whole list reported the *last* message's content type and voice notes
		// came back as "text".
		fileURL := m.FileURL
		contentType := m.ContentType
		fileName := m.FileName
		var forwardedFromMsgID *string
		if m.ForwardedFromMessageID != nil {
			s := m.ForwardedFromMessageID.String()
			forwardedFromMsgID = &s
		}
		var replyToID *string
		if m.ReplyToID != nil {
			s := m.ReplyToID.String()
			replyToID = &s
		}
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
			Content:                m.EncryptedContent,
			Encrypted:              m.IsEncrypted,
			FileURL:                &fileURL,
			FileType:               &contentType,
			FileName:               &fileName,
			FileSize:               m.FileSize,
			DurationMs:             m.DurationMs,
			ThumbnailURL:           m.ThumbnailURL,
			KeyVersion:             m.KeyVersion,
			EncryptionVersion:      m.EncryptionVersion,
			SenderDeviceID:         m.SenderDeviceID,
			ReplyToID:              replyToID,
			IsForwarded:            m.IsForwarded,
			ForwardedFromName:      m.ForwardedFromName,
			ForwardedFromMessageID: forwardedFromMsgID,
			Type:                   m.ChatType,
			DeliveredAt:            m.DeliveredAt,
			ReadAt:                 m.ReadAt,
			CreatedAt:              m.CreatedAt,
			UpdatedAt:              m.UpdatedAt,
			Seq:                    m.Seq,
		}
	}

	// cursor.after = highest seq on the page, cursor.before = lowest. An empty
	// page keeps the caller's ?after= so its sync point never moves backwards.
	var beforeCursor, afterCursor interface{}
	if afterRaw != "" {
		afterCursor = after
	}
	for _, m := range messages {
		if hi, ok := afterCursor.(int64); !ok || m.Seq > hi {
			afterCursor = m.Seq
		}
		if lo, ok := beforeCursor.(int64); !ok || m.Seq < lo {
			beforeCursor = m.Seq
		}
	}

	return c.JSON(fiber.Map{
		"data":     decryptedMessages,
		"total":    total,
		"limit":    limit,
		"order":    order,
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

	// "for_everyone" retracts the message for both sides and is the sender's
	// privilege only. Anything else removes it from the caller's own view,
	// which any participant may do to their own copy.
	forEveryone := c.Query("for_everyone") == "true"

	if forEveryone {
		if message.SenderID != userID {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "forbidden",
				"message": "You can only delete your own messages for everyone",
			})
		}
		// Atomic: the message row and EVERY user's encrypted history archive of
		// it go together or not at all. The archives are removed by the
		// message_id ON DELETE CASCADE, so the guarantee is the database's
		// rather than this handler's - but the delete must still be wrapped, or
		// a crash mid-statement could leave the two out of step.
		//
		// The WebSocket event is published AFTER commit, never inside the
		// transaction: hub.Broadcast is an UNBUFFERED channel, so sending on it
		// blocks until the hub's run loop receives, and doing that with an open
		// transaction would hold database locks for the duration of a blocking
		// send. See the publish block below.
		if err := database.DB.Transaction(func(tx *gorm.DB) error {
			return tx.Delete(&message, messageIDParsed).Error
		}); err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Could not delete message",
			})
		}
	} else {
		// Record the deletion against this user rather than removing the row,
		// so the other participant keeps their copy - the same "mine only"
		// semantics DeleteChat uses. ON CONFLICT makes a repeat click a no-op.
		if err := database.DB.Exec(
			"INSERT INTO message_deletions (message_id, user_id, created_at) VALUES (?, ?, ?) "+
				"ON CONFLICT (message_id, user_id) DO NOTHING",
			messageIDParsed, userID, time.Now(),
		).Error; err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Could not delete message",
			})
		}
	}

	// Tell the other clients, or the message sits on their screen until they
	// happen to refetch - which is exactly how the delete-chat flow felt
	// broken before it pushed an event too.
	if s.hub != nil && forEveryone {
		wsMsg := models.WebSocketMessage{
			Type: "message:deleted",
			Data: map[string]interface{}{
				"chat_id":    message.ChatID,
				"message_id": messageIDParsed.String(),
				"deleted_by": userID.String(),
			},
			Timestamp: time.Now(),
		}
		if out, err := json.Marshal(wsMsg); err == nil {
			s.hub.Broadcast <- out
		}
	}

	return c.JSON(fiber.Map{
		"message":      "Message deleted successfully",
		"for_everyone": forEveryone,
		"message_id":   messageIDParsed.String(),
	})
}

// MarkAsRead handles marking messages as read
// DeleteChat removes a chat from the caller's list, for the caller only.
//
// Implemented by stamping left_at on their own chat_participants row rather
// than deleting anything: GetChatsByUserID already filters on
// "left_at IS NULL", so the chat vanishes for them while the other
// participant's view and the message history are untouched. Deleting the chat
// row outright would destroy both sides' conversation, which is not what
// "delete this chat" means anywhere else.
//
// A later message in a direct chat resurrects it - see SendMessage - so this
// is "clear it off my list", not "block this person".
func (s *MessageService) DeleteChat(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	chatIDParsed, err := uuid.Parse(chatID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid chat ID",
			"message": "Invalid chat ID format",
		})
	}

	// Deleting a chat is a per-user departure: it records left_at rather than
	// removing anything shared. This user's encrypted history archives for the
	// chat must go with it, in the SAME transaction - otherwise "I deleted this
	// chat" leaves a decryptable copy behind. The other participant's archives
	// are untouched, which is what per-user archive identity is for.
	//
	// Clearing a group off your list is leaving it, so LeaveGroup's rule holds
	// here too, decided under the same group lock (see lockGroup): the only
	// active admin cannot walk out this way. Direct chats have no admins, so the
	// rule never applies to them.
	var result *gorm.DB
	lastAdmin, ownerLeaving := false, false
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		chat, found, err := lockGroup(tx, chatIDParsed.String())
		if err != nil {
			return err
		}
		if lastActiveAdmin(tx, chatIDParsed.String(), userID) {
			lastAdmin = true
			return errLifecycleRefused
		}
		// ...nor may a group's owner leave it this way (see msgOwnerCannotLeave).
		// A direct chat has an owner_id too - whoever opened it - but clearing one
		// is not leaving anything, so this is for groups only; and only for an
		// owner still in the group, since for one who is not there is nothing to
		// leave (the not-a-participant answer below stands).
		if found && chat.Type != "direct" && chat.OwnerID == userID {
			var active int64
			if err := tx.Model(&models.ChatParticipant{}).
				Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatIDParsed.String(), userID).
				Count(&active).Error; err != nil {
				return err
			}
			if active > 0 {
				ownerLeaving = true
				return errLifecycleRefused
			}
		}
		result = tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ? AND left_at IS NULL",
				chatIDParsed.String(), userID).
			Update("left_at", time.Now())
		if result.Error != nil {
			return result.Error
		}
		if result.RowsAffected == 0 {
			return nil
		}
		return models.PurgeArchivesForParticipant(tx, chatIDParsed.String(), userID)
	})
	if lastAdmin {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Group owner cannot leave while still being the only admin. Transfer ownership first.",
		})
	}
	if ownerLeaving {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": msgOwnerCannotLeave,
		})
	}
	if txErr != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to delete chat",
		})
	}

	if result.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to delete chat",
		})
	}
	if result.RowsAffected == 0 {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "not found",
			"message": "You are not a participant of this chat",
		})
	}

	// Clearing a group this way is leaving it, so the live subscription ends now
	// rather than at the next reconnect. Not for direct chats: there left_at only
	// hides the chat, and a new message brings it back.
	var chat models.Chat
	if database.DB.Select("type").First(&chat, "id = ?", chatIDParsed.String()).Error == nil && chat.Type != "direct" {
		evictFromChatRoom(s.hub, chatIDParsed.String(), userID)
	}

	return c.JSON(fiber.Map{"message": "Chat deleted"})
}

// canUseReadState reports whether userID may read or change chatID's read
// state: a participant of a direct chat - where left_at only means the chat was
// cleared from their list - or a member of any other chat who has not left it.
// read_at is one column per message, shared by every recipient, so anyone else
// could rewrite what the members see as unread and seen, or learn how much
// unread traffic the chat carries.
func canUseReadState(chatID string, userID uuid.UUID) bool {
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ?", chatID, userID).First(&participant).Error; err != nil {
		return false
	}
	if participant.LeftAt == nil {
		return true
	}
	var chat models.Chat
	return database.DB.Select("type").First(&chat, "id = ?", chatID).Error == nil && chat.Type == "direct"
}

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
	if !canUseReadState(chatIDParsed.String(), userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a participant of this chat",
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
	if !canUseReadState(chatIDParsed.String(), userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a participant of this chat",
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
	ID            string       `json:"id"`
	Type          string       `json:"type"`
	Name          string       `json:"name"`
	AvatarURL     string       `json:"avatar_url"`
	KeyEpoch      int          `json:"key_epoch"`
	OtherUserID   *uuid.UUID   `json:"other_user_id,omitempty"`
	OtherUser     *models.User `json:"other_user,omitempty"`
	LastMessage   *MessageResp `json:"last_message"`
	LastMessageAt *time.Time   `json:"last_message_at"`
	UnreadCount   int64        `json:"unread_count"`
	LastReadAt    *time.Time   `json:"last_read_at"`
	IsOnline      bool         `json:"is_online"`
	UpdatedAt     time.Time    `json:"updated_at"`
}

type MessageResp struct {
	ID          string    `json:"id"`
	SenderID    string    `json:"sender_id"`
	Content     string    `json:"content"`
	ContentType string    `json:"content_type"`
	FileName    string    `json:"file_name,omitempty"`
	Encrypted   bool      `json:"encrypted"`
	KeyVersion  int       `json:"key_version"`
	EncryptionVersion int `json:"encryption_version"`
	// Which of the sender's devices sealed this row. The chat-list preview
	// decrypts last_message, and for MLS (v6) a client must not hand its OWN
	// ciphertext to OpenMLS - the sending leaf's key is dropped at encrypt
	// time for forward secrecy. Without this field the preview could not tell
	// its own row from a peer's and asked OpenMLS to open it on every refresh.
	SenderDeviceID string `json:"sender_device_id"`
	CreatedAt   time.Time `json:"created_at"`
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

	// Fetch last messages for each chat. Same message_deletions filter as
	// GetMessages - without it, a "delete for me" (or clearing the whole
	// thread that way) left the old ciphertext as last_message forever,
	// because the row is still in messages and only hidden via the join
	// table.
	var messages []models.Message
	database.DB.Where("chat_id IN ? AND deleted_at IS NULL", chatIDs).
		Where("NOT EXISTS (SELECT 1 FROM message_deletions md WHERE md.message_id = messages.id AND md.user_id = ?)", userID).
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
		Where("chat_id IN ? AND sender_id != ? AND read_at IS NULL AND deleted_at IS NULL", chatIDs, userID).
		Where("NOT EXISTS (SELECT 1 FROM message_deletions md WHERE md.message_id = messages.id AND md.user_id = ?)", userID).
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
			ID:            ch.ID,
			Type:          ch.Type,
			Name:          ch.Name,
			AvatarURL:     ch.AvatarURL,
			KeyEpoch:      ch.KeyEpoch,
			LastMessageAt: ch.LastMessageAt,
			LastReadAt:    p.LastReadAt,
			UpdatedAt:     ch.UpdatedAt,
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
				FileName:    msg.FileName,
				Encrypted:   msg.IsEncrypted,
				KeyVersion:  msg.KeyVersion,
				EncryptionVersion: msg.EncryptionVersion,
				SenderDeviceID:    msg.SenderDeviceID,
				CreatedAt:   msg.CreatedAt,
			}
		}

		// Get unread count
		item.UnreadCount = unreadMap[ch.ID]

		chatsList = append(chatsList, item)
	}

	sort.SliceStable(chatsList, func(i, j int) bool {
		return chatActivityTime(chatsList[i]).After(chatActivityTime(chatsList[j]))
	})
	return c.JSON(fiber.Map{
		"data": chatsList,
	})
}

func chatActivityTime(item ChatListItem) time.Time {
	if item.LastMessage != nil && !item.LastMessage.CreatedAt.IsZero() {
		return item.LastMessage.CreatedAt
	}
	if item.LastMessageAt != nil && !item.LastMessageAt.IsZero() {
		return *item.LastMessageAt
	}
	return item.UpdatedAt
}
