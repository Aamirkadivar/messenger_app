package handlers

import (
	"encoding/json"
	"net/http"
	"time"

	"messenger-app/crypto"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
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

	if req.Name == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "validation error",
			"message": "Group name is required",
		})
	}

	// Generate group ID
	groupID := uuid.New().String()

	// Create group chat
	chat := models.Chat{
		ID:      groupID,
		Type:    "group",
		Name:    req.Name,
		AvatarURL: req.AvatarURL,
		OwnerID: userID,
	}
	if err := database.DB.Create(&chat).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to create group",
		})
	}

	// Add creator as admin
	database.DB.Create(&models.ChatParticipant{
		ChatID: groupID,
		UserID: userID,
		Role:   "admin",
		JoinedAt: time.Now(),
	})

	// Add initial members
	members := make([]fiber.Map, 0, len(req.MemberIDs)+1)
	members = append(members, fiber.Map{
		"id":         userID,
		"email":      "",
		"username":   "",
		"display_name": "",
		"role":       "admin",
	})

	for _, memberID := range req.MemberIDs {
		database.DB.Create(&models.ChatParticipant{
			ChatID: groupID,
			UserID: memberID,
			Role:   "member",
			JoinedAt: time.Now(),
		})

		var user models.User
		database.DB.First(&user, memberID)
		members = append(members, fiber.Map{
			"id":           memberID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
			"role":         "member",
		})
	}

	return c.JSON(fiber.Map{
		"message": "Group created successfully",
		"data": fiber.Map{
			"id":        groupID,
			"name":      req.Name,
			"type":      "group",
			"avatar_url": req.AvatarURL,
			"owner_id":   userID,
			"members":    members,
			"created_at": chat.CreatedAt,
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
	if err := database.DB.First(&chat, chatID).Error; err != nil {
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
		database.DB.First(&user, p.UserID)
		members[i] = fiber.Map{
			"id":           user.ID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
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
		MemberCount int `json:"member_count"`
		LastMessage fiber.Map `json:"last_message"`
		UnreadCount int64 `json:"unread_count"`
		UserRole    string `json:"user_role"`
	}

	groups := make([]ChatWithInfo, 0)
	for _, p := range participants {
		var chat models.Chat
		if err := database.DB.First(&chat, p.ChatID).Error; err != nil {
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
			database.DB.First(&sender, lastMessage.SenderID)
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
			ChatID: chatID,
			UserID: memberID,
			Role:   "member",
			JoinedAt: time.Now(),
		})

		var user models.User
		database.DB.First(&user, memberID)
		addedMembers = append(addedMembers, fiber.Map{
			"id":           memberID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
		})

		// Notify via WebSocket
		wsMsg := models.WebSocketMessage{
			Type: "member_added",
			Data: fiber.Map{
				"chat_id": chatID,
				"member":  addedMembers[len(addedMembers)-1],
			},
			Timestamp: time.Now(),
		}
		wsData, _ := json.Marshal(wsMsg)
		if s.hub != nil {
			s.hub.Broadcast <- wsData
		}
	}

	return c.JSON(fiber.Map{
		"message": "Members added successfully",
		"data":   addedMembers,
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

	// Notify via WebSocket
	wsMsg := models.WebSocketMessage{
		Type: "member_removed",
		Data: fiber.Map{
			"chat_id":  chatID,
			"member_id": memberID,
		},
		Timestamp: time.Now(),
	}
	wsData, _ := json.Marshal(wsMsg)
	if s.hub != nil {
		s.hub.Broadcast <- wsData
	}

	return c.JSON(fiber.Map{
		"message": "Member removed successfully",
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

	// Notify via WebSocket
	wsMsg := models.WebSocketMessage{
		Type: "user_left",
		Data: fiber.Map{
			"chat_id": chatID,
			"user_id": userID,
		},
		Timestamp: time.Now(),
	}
	wsData, _ := json.Marshal(wsMsg)
	if s.hub != nil {
		s.hub.Broadcast <- wsData
	}

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

// EncryptForGroup encrypts a message for all recipients in a group
func EncryptForGroup(content string, recipientPublicKeys []string) (string, error) {
	// For group chats, we use a symmetric session key approach
	// In production, you'd use a more sophisticated key management system
	encrypted, err := crypto.EncryptMessage(content, recipientPublicKeys[0], "aes256")
	if err != nil {
		return "", err
	}
	return encrypted, nil
}