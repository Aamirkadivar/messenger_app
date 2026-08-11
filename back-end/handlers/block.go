package handlers

import (
	"net/http"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm/clause"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// IsEitherBlocked reports whether either user has blocked the other.
func IsEitherBlocked(a, b uuid.UUID) bool {
	if a == b {
		return false
	}
	var n int64
	database.DB.Model(&models.UserBlock{}).
		Where("(blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)",
			a, b, b, a).
		Count(&n)
	return n > 0
}

// BlockUser POST /users/:user_id/block
//
// Creates a one-way block. Messaging either way is rejected until
// UnblockUser. The direct chat stays on the caller's list so they can
// unblock from it; clients that want it gone remove the row themselves.
// Idempotent: repeating a block is success.
func (h *UserHandler) BlockUser(c *fiber.Ctx) error {
	blockerID := middleware.GetCurrentUserID(c)
	blockedID, err := uuid.Parse(c.Params("user_id"))
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "Invalid user ID"})
	}
	if blockedID == blockerID {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "Cannot block yourself"})
	}

	var target models.User
	if err := database.DB.First(&target, "id = ?", blockedID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "User not found"})
	}

	block := models.UserBlock{
		ID:        uuid.New(),
		BlockerID: blockerID,
		BlockedID: blockedID,
		CreatedAt: time.Now(),
	}
	// DoNothing on the pair unique index so a retry does not 500.
	if err := database.DB.Clauses(clause.OnConflict{
		Columns:   []clause.Column{{Name: "blocker_id"}, {Name: "blocked_id"}},
		DoNothing: true,
	}).Create(&block).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to block user"})
	}

	return c.JSON(fiber.Map{
		"message":    "User blocked",
		"blocked_id": blockedID,
	})
}

// UnblockUser DELETE /users/:user_id/block
func (h *UserHandler) UnblockUser(c *fiber.Ctx) error {
	blockerID := middleware.GetCurrentUserID(c)
	blockedID, err := uuid.Parse(c.Params("user_id"))
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "Invalid user ID"})
	}

	result := database.DB.Where("blocker_id = ? AND blocked_id = ?", blockerID, blockedID).
		Delete(&models.UserBlock{})
	if result.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to unblock user"})
	}
	if result.RowsAffected == 0 {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "Block not found"})
	}

	// Older builds stamped left_at on block — clear that so the chat can
	// reappear after unblock if it was hidden.
	restoreDirectChatForUser(blockerID, blockedID)

	return c.JSON(fiber.Map{
		"message":      "User unblocked",
		"unblocked_id": blockedID,
	})
}

// ListBlockedUsers GET /users/me/blocks
func (h *UserHandler) ListBlockedUsers(c *fiber.Ctx) error {
	blockerID := middleware.GetCurrentUserID(c)

	var blocks []models.UserBlock
	if err := database.DB.Where("blocker_id = ?", blockerID).
		Order("created_at DESC").Find(&blocks).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to list blocks"})
	}

	ids := make([]uuid.UUID, 0, len(blocks))
	for _, b := range blocks {
		ids = append(ids, b.BlockedID)
	}

	type userResponse struct {
		ID          uuid.UUID `json:"id"`
		Username    string    `json:"username"`
		DisplayName string    `json:"display_name"`
		AvatarURL   string    `json:"avatar_url"`
		BlockedAt   time.Time `json:"blocked_at"`
	}

	out := make([]userResponse, 0, len(blocks))
	if len(ids) == 0 {
		return c.JSON(fiber.Map{"users": out})
	}

	var users []models.User
	database.DB.Where("id IN ?", ids).Find(&users)
	byID := make(map[uuid.UUID]models.User, len(users))
	for _, u := range users {
		byID[u.ID] = u
	}
	for _, b := range blocks {
		u, ok := byID[b.BlockedID]
		if !ok {
			continue
		}
		out = append(out, userResponse{
			ID:          u.ID,
			Username:    u.Username,
			DisplayName: u.DisplayName,
			AvatarURL:   u.AvatarURL,
			BlockedAt:   b.CreatedAt,
		})
	}

	return c.JSON(fiber.Map{"users": out})
}

// restoreDirectChatForUser clears left_at on the caller's direct-chat row with
// otherUser (undoes a prior block-time hide from older builds).
func restoreDirectChatForUser(callerID, otherUserID uuid.UUID) {
	var chatIDs []string
	database.DB.Raw(`
		SELECT p1.chat_id::text
		FROM chat_participants p1
		INNER JOIN chat_participants p2 ON p1.chat_id = p2.chat_id
		INNER JOIN chats c ON c.id::uuid = p1.chat_id AND c.type = 'direct'
		WHERE p1.user_id = ? AND p2.user_id = ? AND p1.left_at IS NOT NULL
	`, callerID, otherUserID).Scan(&chatIDs)
	if len(chatIDs) == 0 {
		return
	}
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id IN ? AND user_id = ?", chatIDs, callerID).
		Update("left_at", nil)
}

