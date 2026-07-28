package handlers

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

// GroupService handles group chat operations
type GroupService struct {
	hub *websocket.Hub
}

// NewGroupService creates a new GroupService
func NewGroupService(hub *websocket.Hub) *GroupService {
	return &GroupService{
		hub: hub,
	}
}

// notify publishes a group event over the WebSocket hub.
//
// NOTE: this goes out on the hub's global Broadcast channel, so every connected
// client receives it and filters on chat_id. That is how message delivery in
// this codebase already works (see handlers/message.go) rather than something
// specific to groups - but it does mean group membership changes are visible to
// clients outside the group. Fixing it properly means adding room-scoped
// delivery to the hub and moving all senders over at once.
func (s *GroupService) notify(eventType string, data fiber.Map) {
	if s.hub == nil {
		return
	}
	payload, err := json.Marshal(models.WebSocketMessage{
		Type:      eventType,
		Data:      data,
		Timestamp: time.Now(),
	})
	if err != nil {
		return
	}
	s.hub.Broadcast <- payload
}

// CreateGroup handles creating a new group chat
func (s *GroupService) CreateGroup(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	var req models.CreateGroupRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request body",
			"message": "Failed to parse request body",
		})
	}

	name := strings.TrimSpace(req.Name)
	if name == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "validation error",
			"message": "Group name is required",
		})
	}
	if len(name) > 255 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "validation error",
			"message": "Group name must be 255 characters or fewer",
		})
	}

	// De-duplicate the requested members and drop the creator, who is added
	// separately as admin - otherwise passing yourself in member_ids would
	// create two participant rows for the same user and demote you to member.
	uniqueMemberIDs := make([]uuid.UUID, 0, len(req.MemberIDs))
	seen := map[uuid.UUID]bool{userID: true}
	for _, id := range req.MemberIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		uniqueMemberIDs = append(uniqueMemberIDs, id)
	}

	// Every member must exist. Resolved up front so we fail before writing
	// anything, and so the response can carry real names instead of blanks.
	var users []models.User
	if len(uniqueMemberIDs) > 0 {
		if err := database.DB.Where("id IN ?", uniqueMemberIDs).Find(&users).Error; err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Failed to look up members",
			})
		}
		if len(users) != len(uniqueMemberIDs) {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error":   "validation error",
				"message": "One or more member_ids do not exist",
			})
		}
	}

	var creator models.User
	if err := database.DB.Where("id = ?", userID).First(&creator).Error; err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error":   "unauthorized",
			"message": "Current user not found",
		})
	}

	groupID := uuid.New().String()
	now := time.Now()

	chat := models.Chat{
		ID:          groupID,
		Type:        "group",
		Name:        name,
		AvatarURL:   req.AvatarURL,
		Description: req.Description,
		OwnerID:     userID,
	}

	// One transaction: a group with no participants (or a partial member list)
	// is not a valid group, so a failure halfway through must roll back rather
	// than leave an orphaned chat row behind.
	err := database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&chat).Error; err != nil {
			return err
		}

		participants := make([]models.ChatParticipant, 0, len(uniqueMemberIDs)+1)
		participants = append(participants, models.ChatParticipant{
			ChatID:   groupID,
			UserID:   userID,
			Role:     "admin",
			JoinedAt: now,
		})
		for _, memberID := range uniqueMemberIDs {
			participants = append(participants, models.ChatParticipant{
				ChatID:   groupID,
				UserID:   memberID,
				Role:     "member",
				JoinedAt: now,
			})
		}
		return tx.Create(&participants).Error
	})
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to create group",
		})
	}

	members := make([]fiber.Map, 0, len(users)+1)
	members = append(members, fiber.Map{
		"id":           creator.ID,
		"email":        creator.Email,
		"username":     creator.Username,
		"display_name": creator.DisplayName,
		"avatar_url":   creator.AvatarURL,
		"role":         "admin",
	})
	for _, u := range users {
		members = append(members, fiber.Map{
			"id":           u.ID,
			"email":        u.Email,
			"username":     u.Username,
			"display_name": u.DisplayName,
			"avatar_url":   u.AvatarURL,
			"role":         "member",
		})
	}

	// Tell the new members a group appeared, so their chat list updates without
	// waiting for a manual refresh.
	s.notify("group_created", fiber.Map{
		"chat_id":      groupID,
		"name":         name,
		"type":         "group",
		"owner_id":     userID,
		"member_count": len(members),
	})

	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"message": "Group created successfully",
		"data": fiber.Map{
			"id":           groupID,
			"name":         name,
			"type":         "group",
			"avatar_url":   req.AvatarURL,
			"description":  req.Description,
			"owner_id":     userID,
			"members":      members,
			"member_count": len(members),
			"created_at":   chat.CreatedAt,
		},
	})
}

// GetGroupInfo handles getting group chat information
func (s *GroupService) GetGroupInfo(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	// Verify user is a participant
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ?", chatID, userID).First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a member of this group",
		})
	}

	// Get chat info
	var chat models.Chat
	if err := database.DB.Where("id = ?", chatID).First(&chat).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "group not found",
			"message": "Group not found",
		})
	}

	// Get members
	var participants []models.ChatParticipant
	database.DB.Where("chat_id = ? AND left_at IS NULL", chatID).Find(&participants)

	members := make([]fiber.Map, len(participants))
	for i, p := range participants {
		var user models.User
		database.DB.Where("id = ?", p.UserID).First(&user)
		members[i] = fiber.Map{
			"id":           user.ID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
			"avatar_url":   user.AvatarURL,
			"role":         p.Role,
			"joined_at":    p.JoinedAt,
		}
	}

	// Get unread count
	var unreadCount int64
	database.DB.Model(&models.Message{}).
		Where("chat_id = ? AND sender_id != ? AND read_at IS NULL", chatID, userID).
		Count(&unreadCount)

	return c.JSON(fiber.Map{
		"data": fiber.Map{
			"id":           chat.ID,
			"name":         chat.Name,
			"type":         chat.Type,
			"avatar_url":   chat.AvatarURL,
			"description":  chat.Description,
			"owner_id":     chat.OwnerID,
			"members":      members,
			"member_count": len(members),
			"unread_count": unreadCount,
			"created_at":   chat.CreatedAt,
			"updated_at":   chat.UpdatedAt,
		},
	})
}

// GetGroups handles getting all groups for a user
func (s *GroupService) GetGroups(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	// Get groups user is a member of
	var participants []models.ChatParticipant
	database.DB.Where("user_id = ? AND left_at IS NULL", userID).Find(&participants)

	type ChatWithInfo struct {
		models.Chat
		MemberCount int       `json:"member_count"`
		LastMessage fiber.Map `json:"last_message"`
		UnreadCount int64     `json:"unread_count"`
		UserRole    string    `json:"user_role"`
	}

	groups := make([]ChatWithInfo, 0)
	for _, p := range participants {
		var chat models.Chat
		if err := database.DB.Where("id = ?", p.ChatID).First(&chat).Error; err != nil {
			continue
		}

		if chat.Type != "group" {
			continue
		}

		// Count members
		var memberCount int64
		database.DB.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND left_at IS NULL", p.ChatID).
			Count(&memberCount)

		// Get unread count
		var unreadCount int64
		database.DB.Model(&models.Message{}).
			Where("chat_id = ? AND sender_id != ? AND read_at IS NULL", p.ChatID, userID).
			Count(&unreadCount)

		// Get last message
		var lastMessage models.Message
		database.DB.Where("chat_id = ?", p.ChatID).Order("created_at DESC").First(&lastMessage)

		lastMsgInfo := fiber.Map{}
		if lastMessage.ID != uuid.Nil {
			var sender models.User
			database.DB.Where("id = ?", lastMessage.SenderID).First(&sender)
			lastMsgInfo = fiber.Map{
				"id":        lastMessage.ID,
				"sender_id": sender.ID,
				"content":   "[Encrypted]",
				"timestamp": lastMessage.CreatedAt,
			}
		}

		groups = append(groups, ChatWithInfo{
			Chat:        chat,
			MemberCount: int(memberCount),
			LastMessage: lastMsgInfo,
			UnreadCount: unreadCount,
			UserRole:    p.Role,
		})
	}

	return c.JSON(fiber.Map{
		"data": groups,
	})
}

// AddMembers handles adding members to a group
func (s *GroupService) AddMembers(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	var req models.AddMemberRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request body",
			"message": "Failed to parse request body",
		})
	}

	// Check if user is admin
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND role = ?", chatID, userID, "admin").First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only group admins can add members",
		})
	}

	// Add members
	addedMembers := make([]fiber.Map, 0, len(req.MemberIDs))
	for _, memberID := range req.MemberIDs {
		// Check if already a member
		var existing models.ChatParticipant
		if err := database.DB.Where("chat_id = ? AND user_id = ?", chatID, memberID).First(&existing).Error; err == nil {
			continue // Already a member
		}

		database.DB.Create(&models.ChatParticipant{
			ChatID:   chatID,
			UserID:   memberID,
			Role:     "member",
			JoinedAt: time.Now(),
		})

		var user models.User
		database.DB.Where("id = ?", memberID).First(&user)
		addedMembers = append(addedMembers, fiber.Map{
			"id":           memberID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
		})

		s.notify("member_added", fiber.Map{
			"chat_id": chatID,
			"member":  addedMembers[len(addedMembers)-1],
		})
	}

	return c.JSON(fiber.Map{
		"message": "Members added successfully",
		"data":    addedMembers,
	})
}

// RemoveMember handles removing a member from a group
func (s *GroupService) RemoveMember(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)
	memberID := c.Params("member_id")

	// Check if user is admin
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND role = ?", chatID, userID, "admin").First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only group admins can remove members",
		})
	}

	// The owner cannot be removed by another admin - otherwise any admin could
	// evict the group's creator and take it over.
	var chat models.Chat
	if err := database.DB.Where("id = ?", chatID).First(&chat).Error; err == nil {
		if chat.OwnerID.String() == memberID && userID.String() != memberID {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "forbidden",
				"message": "The group owner cannot be removed",
			})
		}
	}

	// Can't remove yourself unless you're transferring ownership
	if userID.String() == memberID {
		// Left the group
		database.DB.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ?", chatID, userID).
			Update("left_at", time.Now())
	} else {
		// Remove the member
		database.DB.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ?", chatID, memberID).
			Update("left_at", time.Now())
	}

	s.notify("member_removed", fiber.Map{
		"chat_id":   chatID,
		"member_id": memberID,
	})

	return c.JSON(fiber.Map{
		"message": "Member removed successfully",
	})
}

// UpdateMemberRoleRequest is the body for promoting/demoting a member.
type UpdateMemberRoleRequest struct {
	Role string `json:"role"`
}

// UpdateMemberRole promotes a member to admin, or demotes an admin to member.
//
// Only admins may change roles, and the group owner's role is immutable: the
// owner is the one account guaranteed to be able to administer the group, so
// allowing another admin to demote them would let a group be taken over.
func (s *GroupService) UpdateMemberRole(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	memberID := c.Params("member_id")
	userID := middleware.GetCurrentUserID(c)

	var req UpdateMemberRoleRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request body",
			"message": "Failed to parse request body",
		})
	}

	role := strings.ToLower(strings.TrimSpace(req.Role))
	if role != "admin" && role != "member" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "validation error",
			"message": "Role must be either 'admin' or 'member'",
		})
	}

	// Caller must be an active admin of this group.
	var caller models.ChatParticipant
	if err := database.DB.
		Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").
		First(&caller).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only group admins can change member roles",
		})
	}

	var chat models.Chat
	if err := database.DB.Where("id = ?", chatID).First(&chat).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "group not found",
			"message": "Group not found",
		})
	}
	if chat.OwnerID.String() == memberID {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "The group owner's role cannot be changed",
		})
	}

	// Target must actually still be in the group.
	var target models.ChatParticipant
	if err := database.DB.
		Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, memberID).
		First(&target).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "not found",
			"message": "That person is not a member of this group",
		})
	}

	if err := database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id = ?", chatID, memberID).
		Update("role", role).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to update member role",
		})
	}

	s.notify("member_role_changed", fiber.Map{
		"chat_id":   chatID,
		"member_id": memberID,
		"role":      role,
	})

	return c.JSON(fiber.Map{
		"message": "Member role updated successfully",
		"data": fiber.Map{
			"member_id": memberID,
			"role":      role,
		},
	})
}

// DeleteGroup deletes a group for everyone.
//
// Restricted to the owner rather than any admin: this is irreversible and
// affects every member, so it stays with the single account that created the
// group. Participants are marked as left and messages soft-deleted in the same
// transaction as the chat removal, so a partial failure can't leave members
// pointing at a chat that no longer exists.
func (s *GroupService) DeleteGroup(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	var chat models.Chat
	if err := database.DB.Where("id = ?", chatID).First(&chat).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "group not found",
			"message": "Group not found",
		})
	}
	if chat.Type != "group" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "validation error",
			"message": "Only group chats can be deleted",
		})
	}
	if chat.OwnerID != userID {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only the group owner can delete this group",
		})
	}

	now := time.Now()
	err := database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND left_at IS NULL", chatID).
			Update("left_at", now).Error; err != nil {
			return err
		}
		if err := tx.Model(&models.Message{}).
			Where("chat_id = ? AND deleted_at IS NULL", chatID).
			Update("deleted_at", now).Error; err != nil {
			return err
		}
		return tx.Where("id = ?", chatID).Delete(&models.Chat{}).Error
	})
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to delete group",
		})
	}

	s.notify("group_deleted", fiber.Map{"chat_id": chatID})

	return c.JSON(fiber.Map{
		"message": "Group deleted successfully",
	})
}

// LeaveGroup handles leaving a group
func (s *GroupService) LeaveGroup(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	// Check if user is a member
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).First(&participant).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error":   "not found",
			"message": "You are not a member of this group",
		})
	}

	// Check if user is the owner
	if participant.Role == "admin" {
		// Find another admin to transfer ownership
		var owner models.ChatParticipant
		if err := database.DB.Where("chat_id = ? AND role = ? AND user_id != ?", chatID, "admin", userID).First(&owner).Error; err != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "forbidden",
				"message": "Group owner cannot leave while still being the only admin. Transfer ownership first.",
			})
		}
		// Transfer ownership
		database.DB.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ?", chatID, owner.UserID).
			Update("role", "admin")
	}

	// Leave the group
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id = ?", chatID, userID).
		Update("left_at", time.Now())

	s.notify("user_left", fiber.Map{
		"chat_id": chatID,
		"user_id": userID,
	})

	return c.JSON(fiber.Map{
		"message": "Left group successfully",
	})
}

// UpdateGroup handles updating group information
func (s *GroupService) UpdateGroup(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	var req models.UpdateGroupRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request body",
			"message": "Failed to parse request body",
		})
	}

	// Check if user is admin
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND role = ?", chatID, userID, "admin").First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only group admins can update group info",
		})
	}

	updates := make(fiber.Map)
	if req.Name != nil {
		updates["name"] = *req.Name
	}
	if req.Description != nil {
		updates["description"] = *req.Description
	}
	if req.AvatarURL != nil {
		updates["avatar_url"] = *req.AvatarURL
	}

	if len(updates) > 0 {
		database.DB.Model(&models.Chat{}).Where("id = ?", chatID).Updates(updates)
	}

	return c.JSON(fiber.Map{
		"message": "Group updated successfully",
	})
}

// SearchUsers handles searching for users to add to groups
func (s *GroupService) SearchUsers(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	query := c.Query("q", "")

	var users []models.User
	if query != "" {
		database.DB.Where("id != ? AND (username ILIKE ? OR display_name ILIKE ? OR email ILIKE ?)",
			userID, "%"+query+"%", "%"+query+"%", "%"+query+"%").
			Limit(20).
			Find(&users)
	} else {
		database.DB.Where("id != ?", userID).
			Limit(20).
			Find(&users)
	}

	result := make([]fiber.Map, len(users))
	for i, u := range users {
		result[i] = fiber.Map{
			"id":           u.ID,
			"email":        u.Email,
			"username":     u.Username,
			"display_name": u.DisplayName,
			"avatar_url":   u.AvatarURL,
			"is_online":    u.IsOnline,
		}
	}

	return c.JSON(fiber.Map{
		"data": result,
	})
}

// Group message encryption is deliberately absent.
//
// An EncryptForGroup helper used to live here, but it encrypted only for
// recipientPublicKeys[0] and panicked on an empty slice - despite its name
// promising otherwise. It was unreferenced, so it has been removed rather than
// left as a trap for the first caller.
//
// Direct messages are end-to-end encrypted with pairwise X25519 crypto_box
// (see handlers/crypto.go and the clients' E2ECrypto). That does not extend to
// N participants without a real decision, and the server must never see
// plaintext, so the choice has to be made client-side:
//
//   - Per-recipient fanout: the sender encrypts once per member and uploads N-1
//     ciphertexts. No new primitives, but message size grows with the group and
//     the Message model needs somewhere to put per-recipient payloads (today it
//     has a single Content column).
//
//   - Sender keys (Signal-style): each sender derives a symmetric chain key,
//     distributes it over the existing pairwise channel, then encrypts each
//     message once. Efficient, but requires rekeying whenever membership
//     changes so removed members lose forward access.
//
// Until one is implemented, group chats can be created and managed but group
// messages have no encryption path - do not route them through the direct
// message crypto, which assumes exactly two parties.
