package handlers

import (
	"errors"

	"github.com/google/uuid"
	"gorm.io/gorm"
	"messenger-app/models"
)

// Archive authorization errors, mapped to HTTP status by the caller.
var (
	// ErrArchiveNoSuchMessage - the message id does not resolve, or resolves to
	// a message that has been deleted. Deliberately indistinguishable from
	// "exists but you may not touch it" at the API edge, so the endpoint is not
	// an existence oracle for other people's message ids.
	ErrArchiveNoSuchMessage = errors.New("archive: message not found")
	// ErrArchiveNotMember - authenticated, but not an active participant of the
	// chat the message belongs to.
	ErrArchiveNotMember = errors.New("archive: not a chat member")
)

// ArchiveScope is the verified answer to "may this user archive this message?".
//
// It carries the chat id resolved FROM THE MESSAGE ROW rather than from the
// request, which is what stops a caller from binding an archive to a chat it
// does not belong to.
type ArchiveScope struct {
	MessageID uuid.UUID
	UserID    uuid.UUID
	ChatID    string
}

// AuthorizeArchiveAccess is the single authorization gate for archive
// operations. It answers three distinct questions in order, and all three must
// pass:
//
//	(a) authenticated user  - the caller supplies a non-nil userID, established
//	    by AuthMiddleware. Device identity is enforced separately, and earlier,
//	    by middleware.RequireDeviceIdentity.
//	(b) membership          - the user is an ACTIVE participant (left_at IS NULL)
//	    of the chat that owns the message. A user who left keeps no archive
//	    rights, which matches the lifetime rule that deletes their archives on
//	    departure.
//	(c) message authorization - the message exists, is not deleted, and its chat
//	    is the chat the membership was checked against.
//
// The existence of a message id is NEVER sufficient on its own. Message ids are
// server-generated UUIDs that appear in every participant's message list, so
// treating possession of one as authorization would let any authenticated user
// write an archive row under another user's message, or probe which ids exist.
//
// Returns the verified scope; the caller must use scope.ChatID rather than any
// chat id supplied by the client.
func AuthorizeArchiveAccess(db *gorm.DB, userID uuid.UUID, messageID uuid.UUID) (ArchiveScope, error) {
	if userID == uuid.Nil {
		return ArchiveScope{}, ErrArchiveNotMember
	}

	// (c) resolve the message first, so the chat is derived from server state.
	var message models.Message
	if err := db.Where("id = ? AND deleted_at IS NULL", messageID).
		First(&message).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return ArchiveScope{}, ErrArchiveNoSuchMessage
		}
		return ArchiveScope{}, err
	}

	// (b) membership in THAT chat, active only.
	var participant models.ChatParticipant
	if err := db.Where("chat_id = ? AND user_id = ? AND left_at IS NULL",
		message.ChatID, userID).First(&participant).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return ArchiveScope{}, ErrArchiveNotMember
		}
		return ArchiveScope{}, err
	}

	return ArchiveScope{
		MessageID: message.ID,
		UserID:    userID,
		ChatID:    message.ChatID,
	}, nil
}
