package handlers

import (
	"encoding/json"
	"errors"
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
	"gorm.io/gorm/clause"
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
// bumpKeyEpoch signals to clients that this group's Sender Keys need to be
// rotated (see models.Chat.KeyEpoch) - called after any membership change.
// Errors are swallowed: a missed epoch bump just means members redistribute
// their sender key on the next send instead of the very next one, not a
// correctness issue worth failing the request over.
func (s *GroupService) bumpKeyEpoch(chatID string) {
	database.DB.Model(&models.Chat{}).Where("id = ?", chatID).
		UpdateColumn("key_epoch", gorm.Expr("key_epoch + 1"))
}

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

// evictFromChatRoom ends userID's live subscription to chatID's room - see
// Hub.EvictFromRoom - under the canonical chat id rooms are joined with.
// Called by every path that ends a group membership.
func evictFromChatRoom(hub *websocket.Hub, chatID string, userID uuid.UUID) {
	id, err := uuid.Parse(chatID)
	if err != nil {
		return
	}
	hub.EvictFromRoom(userID, id.String())
}

// errLifecycleRefused ends a membership transaction whose checks refused the
// change. The handler maps the reason it recorded to a response once the
// transaction has rolled back (the pattern the pairing handlers use).
var errLifecycleRefused = errors.New("group membership change refused")

// msgOwnerCannotLeave refuses the group owner's attempt to leave, by any route.
// Nothing can hand ownership on - no endpoint changes owner_id after
// CreateGroup - so an owner who left would take the owner-only operations,
// deleting the group above all, away for good. The owner can still delete it.
const msgOwnerCannotLeave = "The group owner cannot leave the group"

// lockGroup takes the row lock on chatID's chats row for the rest of tx and
// returns the row (found is false when there is none, and then nothing is
// locked: there is no live group to protect).
//
// Every transaction that changes who is in a group, or who administers it,
// takes this lock FIRST - before any check. The only-admin decision is a read
// followed by a write; without the lock two admins could each read that the
// other is still in charge and both go, leaving the group with nobody able to
// administer it. With it, the second waits here and then reads what the first
// committed (read committed: each statement sees data committed before it
// began). A check made before the lock would still race.
func lockGroup(tx *gorm.DB, chatID string) (models.Chat, bool, error) {
	var chat models.Chat
	res := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ?", chatID).Limit(1).Find(&chat)
	return chat, res.RowsAffected > 0, res.Error
}

// lastActiveAdmin reports whether userID is the only admin of chatID who has not
// left - the one account whose departure, or stepping down, would leave the
// group with nobody able to administer it. Departed admins hold no authority, so
// they cannot stand in for one who does. A failed lookup answers true: the
// callers refuse, rather than risk stranding the group.
//
// db is the caller's transaction, already holding lockGroup: the answer is only
// good while nothing else can change the group's admins.
func lastActiveAdmin(db *gorm.DB, chatID string, userID uuid.UUID) bool {
	var self, others int64
	if err := db.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").
		Count(&self).Error; err != nil {
		return true
	}
	if self == 0 {
		return false
	}
	if err := db.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id <> ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").
		Count(&others).Error; err != nil {
		return true
	}
	return others == 0
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

	// Verify user is a participant. Someone who left keeps their row (left_at
	// records the departure), so the row alone is not membership.
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).First(&participant).Error; err != nil {
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
			"public_key":   user.PublicKey,
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
			"key_epoch":    chat.KeyEpoch,
			"created_at":   chat.CreatedAt,
			"updated_at":   chat.UpdatedAt,
		},
	})
}

// SenderKeyRecipient is one recipient's encrypted copy of the caller's
// current group Sender Key.
type SenderKeyRecipient struct {
	UserID       uuid.UUID `json:"user_id" binding:"required"`
	EncryptedKey string    `json:"encrypted_key" binding:"required"`
}

// PublishSenderKeyRequest is the body for distributing a Sender Key.
type PublishSenderKeyRequest struct {
	KeyVersion int                  `json:"key_version"`
	Recipients []SenderKeyRecipient `json:"recipients" binding:"required"`
}

// PublishSenderKey stores the caller's encrypted-per-recipient copies of
// their current group Sender Key (see models.Chat.KeyEpoch / GroupSenderKey).
// Each recipient's copy was already encrypted client-side via crypto_box
// (the same pairwise scheme direct chats use) before it ever reaches here -
// the server only stores and later relays these opaque blobs.
func (s *GroupService) PublishSenderKey(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).
		First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a member of this group",
		})
	}

	var req PublishSenderKeyRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request body",
			"message": "Failed to parse request body",
		})
	}
	if len(req.Recipients) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "validation error",
			"message": "At least one recipient is required",
		})
	}

	for _, r := range req.Recipients {
		key := models.GroupSenderKey{
			ChatID:       chatID,
			SenderID:     userID,
			RecipientID:  r.UserID,
			KeyVersion:   req.KeyVersion,
			EncryptedKey: r.EncryptedKey,
		}
		// One row per (chat, sender, recipient, version) - re-publishing the
		// same version (e.g. a new member's initial catch-up distribution
		// naturally reuses the sender's current version) just overwrites.
		database.DB.Where(models.GroupSenderKey{
			ChatID: chatID, SenderID: userID, RecipientID: r.UserID, KeyVersion: req.KeyVersion,
		}).Assign(models.GroupSenderKey{EncryptedKey: r.EncryptedKey}).FirstOrCreate(&key)
	}

	return c.JSON(fiber.Map{
		"message": "Sender key published",
	})
}

// GetSenderKeys returns every Sender Key distributed to the caller across
// this group's members (i.e. rows where the caller is the recipient) -
// each client decrypts them locally (crypto_box, using that sender's public
// key) and caches the result keyed by (sender, key_version).
func (s *GroupService) GetSenderKeys(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).
		First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "You are not a member of this group",
		})
	}

	var keys []models.GroupSenderKey
	database.DB.Where("chat_id = ? AND recipient_id = ?", chatID, userID).Find(&keys)

	// The recipient needs each sender's public key to open the crypto_box
	// the key was encrypted with - look them all up in one query rather
	// than one per row.
	senderIDs := make(map[string]bool)
	for _, k := range keys {
		senderIDs[k.SenderID.String()] = true
	}
	ids := make([]string, 0, len(senderIDs))
	for id := range senderIDs {
		ids = append(ids, id)
	}
	var senders []models.User
	if len(ids) > 0 {
		database.DB.Find(&senders, ids)
	}
	senderPubKeys := make(map[string]string, len(senders))
	for _, u := range senders {
		senderPubKeys[u.ID.String()] = u.PublicKey
	}

	result := make([]fiber.Map, len(keys))
	for i, k := range keys {
		result[i] = fiber.Map{
			"sender_id":         k.SenderID,
			"sender_public_key": senderPubKeys[k.SenderID.String()],
			"key_version":       k.KeyVersion,
			"encrypted_key":     k.EncryptedKey,
		}
	}

	return c.JSON(fiber.Map{
		"data": result,
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

	// Check if user is an admin who is still in the group
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").First(&participant).Error; err != nil {
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

	if len(addedMembers) > 0 {
		s.bumpKeyEpoch(chatID)
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

	// Compare and match ids in canonical form. Postgres reads any spelling of a
	// uuid (upper case, braces, no hyphens), while chats.id is text and the owner
	// and self checks below compare strings - another spelling would skip those
	// checks yet still match the rows the removal writes.
	if id, err := uuid.Parse(chatID); err == nil {
		chatID = id.String()
	}
	if id, err := uuid.Parse(memberID); err == nil {
		memberID = id.String()
	}

	// Removing yourself is leaving the group, so it is LeaveGroup - the same
	// last-admin rule, archive cleanup and events - and this endpoint cannot be
	// used to leave in a way LeaveGroup would refuse. It stays an admin's
	// endpoint: anyone else gets the refusal they always got, and uses LeaveGroup.
	if userID.String() == memberID {
		var self models.ChatParticipant
		if err := database.DB.Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").First(&self).Error; err != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "forbidden",
				"message": "Only group admins can remove members",
			})
		}
		return s.LeaveGroup(c)
	}

	// Decided under the group lock (see lockGroup): an admin removed, or gone, a
	// moment ago must not remove anyone - two admins removing each other at once
	// would otherwise both succeed and leave nobody in charge.
	var status int
	var refusal fiber.Map
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		chat, found, err := lockGroup(tx, chatID)
		if err != nil {
			return err
		}

		// Check if user is an admin who is still in the group
		var participant models.ChatParticipant
		if err := tx.Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").First(&participant).Error; err != nil {
			status, refusal = http.StatusForbidden, fiber.Map{
				"error":   "forbidden",
				"message": "Only group admins can remove members",
			}
			return errLifecycleRefused
		}

		// The owner cannot be removed by another admin - otherwise any admin could
		// evict the group's creator and take it over.
		if found && chat.OwnerID.String() == memberID {
			status, refusal = http.StatusForbidden, fiber.Map{
				"error":   "forbidden",
				"message": "The group owner cannot be removed",
			}
			return errLifecycleRefused
		}

		// Remove the member, dropping their archives for this chat in the same
		// transaction - see the note in LeaveGroup.
		//
		// Only an active row is marked: left_at is the member's departure, and the
		// boundary of the history they may still read (GetMessages), so removing
		// someone who already left must not move it.
		if err := tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, memberID).
			Update("left_at", time.Now()).Error; err != nil {
			return err
		}
		// memberID arrives as a path param; a malformed one simply purges
		// nothing rather than failing the removal.
		if parsed, err := uuid.Parse(memberID); err == nil {
			return models.PurgeArchivesForParticipant(tx, chatID, parsed)
		}
		return nil
	}); err != nil {
		if errors.Is(err, errLifecycleRefused) {
			return c.Status(status).JSON(refusal)
		}
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to remove member",
		})
	}

	if parsed, err := uuid.Parse(memberID); err == nil {
		evictFromChatRoom(s.hub, chatID, parsed)
	}

	s.bumpKeyEpoch(chatID)

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

	// Canonical form, so the owner check below compares like with like - see
	// RemoveMember.
	if id, err := uuid.Parse(memberID); err == nil {
		memberID = id.String()
	}

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

	// Every check and the write happen under the group lock (see lockGroup): two
	// admins demoting each other at once would otherwise both succeed and leave
	// nobody in charge, and a caller demoted a moment ago must not act.
	var status int
	var refusal fiber.Map
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		chat, found, err := lockGroup(tx, chatID)
		if err != nil {
			return err
		}

		// Caller must be an active admin of this group.
		var caller models.ChatParticipant
		if err := tx.
			Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").
			First(&caller).Error; err != nil {
			status, refusal = http.StatusForbidden, fiber.Map{
				"error":   "forbidden",
				"message": "Only group admins can change member roles",
			}
			return errLifecycleRefused
		}

		if !found {
			status, refusal = http.StatusNotFound, fiber.Map{
				"error":   "group not found",
				"message": "Group not found",
			}
			return errLifecycleRefused
		}
		if chat.OwnerID.String() == memberID {
			status, refusal = http.StatusForbidden, fiber.Map{
				"error":   "forbidden",
				"message": "The group owner's role cannot be changed",
			}
			return errLifecycleRefused
		}

		// Target must actually still be in the group.
		var target models.ChatParticipant
		if err := tx.
			Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, memberID).
			First(&target).Error; err != nil {
			status, refusal = http.StatusNotFound, fiber.Map{
				"error":   "not found",
				"message": "That person is not a member of this group",
			}
			return errLifecycleRefused
		}

		// The last active admin cannot step down: that would leave nobody able to
		// administer the group - the rule LeaveGroup applies to leaving. Only the
		// caller can be that admin; demoting anyone else leaves the caller in place.
		if role == "member" && lastActiveAdmin(tx, chatID, target.UserID) {
			status, refusal = http.StatusForbidden, fiber.Map{
				"error":   "forbidden",
				"message": "A group must keep at least one active admin",
			}
			return errLifecycleRefused
		}

		return tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ?", chatID, memberID).
			Update("role", role).Error
	}); err != nil {
		if errors.Is(err, errLifecycleRefused) {
			return c.Status(status).JSON(refusal)
		}
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
		// The group lock first, as every membership change takes it (see
		// lockGroup) - one lock order, so this cannot deadlock against a leave.
		if _, _, err := lockGroup(tx, chatID); err != nil {
			return err
		}
		// ...and the owner must still be in the group, checked under that lock.
		// Leaving does not change owner_id, so the match above alone would let an
		// owner who left - even a moment ago - delete the group for everyone.
		var participant models.ChatParticipant
		if err := tx.Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).First(&participant).Error; err != nil {
			return errLifecycleRefused
		}
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
	if errors.Is(err, errLifecycleRefused) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only the group owner can delete this group",
		})
	}
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

	// Canonical form: the group lock below is taken on chats.id, which is text,
	// so another spelling of the id would find no row to lock.
	if id, err := uuid.Parse(chatID); err == nil {
		chatID = id.String()
	}

	// The membership check, the only-admin rule and the departure are one
	// transaction opened by the group lock (see lockGroup): the last two admins
	// leaving at once must not both see the other still in charge and both go.
	var status int
	var refusal fiber.Map
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		chat, found, err := lockGroup(tx, chatID)
		if err != nil {
			return err
		}

		// Check if user is a member
		var participant models.ChatParticipant
		if err := tx.Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).First(&participant).Error; err != nil {
			status, refusal = http.StatusNotFound, fiber.Map{
				"error":   "not found",
				"message": "You are not a member of this group",
			}
			return errLifecycleRefused
		}

		// Check if user is the owner
		if participant.Role == "admin" {
			// Find another admin to transfer ownership - one who is still in the
			// group: a departed admin holds no authority and cannot stand in.
			var owner models.ChatParticipant
			if err := tx.Where("chat_id = ? AND role = ? AND user_id != ? AND left_at IS NULL", chatID, "admin", userID).First(&owner).Error; err != nil {
				status, refusal = http.StatusForbidden, fiber.Map{
					"error":   "forbidden",
					"message": "Group owner cannot leave while still being the only admin. Transfer ownership first.",
				}
				return errLifecycleRefused
			}
			// Transfer ownership
			if err := tx.Model(&models.ChatParticipant{}).
				Where("chat_id = ? AND user_id = ?", chatID, owner.UserID).
				Update("role", "admin").Error; err != nil {
				return err
			}
		}

		// The owner may not leave the group while they are its owner (see
		// msgOwnerCannotLeave). Checked after the only-admin rule, so an owner who
		// is also the only admin keeps getting that existing, more specific answer.
		if found && chat.OwnerID == userID {
			status, refusal = http.StatusForbidden, fiber.Map{
				"error":   "forbidden",
				"message": msgOwnerCannotLeave,
			}
			return errLifecycleRefused
		}

		// Leave the group. The departing member's encrypted history archives for
		// this chat go in the same transaction: left_at is an UPDATE, so no foreign
		// key can observe the departure, and an archive left behind would keep a
		// decryptable copy of a chat the user is no longer in.
		if err := tx.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ?", chatID, userID).
			Update("left_at", time.Now()).Error; err != nil {
			return err
		}
		return models.PurgeArchivesForParticipant(tx, chatID, userID)
	}); err != nil {
		if errors.Is(err, errLifecycleRefused) {
			return c.Status(status).JSON(refusal)
		}
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to leave group",
		})
	}

	evictFromChatRoom(s.hub, chatID, userID)

	s.bumpKeyEpoch(chatID)

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

	// Check if user is an admin who is still in the group
	var participant models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").First(&participant).Error; err != nil {
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

// Group message encryption lives on the clients (Sender Keys): each member
// generates a crypto_secretbox key, wraps a copy for every other member with
// pairwise crypto_box, and publishes those blobs via PublishSenderKey. The
// server stores and relays ciphertext and key wrappers only - it never sees
// plaintext. Chat.KeyEpoch (bumped on membership change) is the rotation
// signal; Message.KeyVersion records which Sender Key sealed a message.
// See clients' ChatRepository / ChatService and models.GroupSenderKey.
