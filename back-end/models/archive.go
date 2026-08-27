package models

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// MessageArchive is one user's encrypted history archive of one message.
//
// The server stores opaque sealed bytes and nothing else. Ciphertext is produced
// on the device by XChaCha20-Poly1305 under a per-chat history root that never
// leaves that device, and is authenticated against a context binding
// (protocol version, user, chat, message, root version). The server holds no
// history root, no keyring, no master key, and no MLS material, and therefore
// cannot decrypt any row in this table.
//
// Identity is the composite primary key (MessageID, UserID):
//
//   - one archive per user per message, so an archive can never be REPLACED,
//     only inserted or deleted. Uploads use ON CONFLICT DO NOTHING, which makes
//     a retry idempotent and makes replay of an older ciphertext a no-op rather
//     than an overwrite;
//   - archives are per-user, so one participant deleting a chat cannot affect
//     the other participant's copy.
//
// LIFETIME. An archive exists only while all of the following hold:
//
//	1. its message row exists          -> FK message_id ON DELETE CASCADE
//	2. its chat row exists             -> FK chat_id    ON DELETE CASCADE
//	3. its owning user row exists      -> FK user_id    ON DELETE CASCADE
//	4. the owning user is still an active participant of the chat
//
// (1)-(3) are enforced by the database, so no application path can forget them.
// (4) cannot be: leaving a chat sets chat_participants.left_at, an UPDATE that
// no foreign key can observe, so the participant-departure handlers delete the
// departing user's archives explicitly. See PurgeArchivesForParticipant.
//
// ChatID is stored despite being derivable from the message row. It is not
// secret - the server already stores messages.chat_id - and it is what lets
// rule (2) be a database constraint and rule (4) a single indexed DELETE rather
// than a join against every message in the chat.
type MessageArchive struct {
	MessageID uuid.UUID `json:"message_id" gorm:"type:uuid;primaryKey"`
	UserID    uuid.UUID `json:"user_id" gorm:"type:uuid;primaryKey;index:idx_message_archives_user_chat,priority:1"`
	// ChatID matches chats.id, which is a text primary key in this schema.
	ChatID string `json:"chat_id" gorm:"not null;index:idx_message_archives_user_chat,priority:2"`
	// RootVersion is which per-chat history root sealed Ciphertext. Bound into
	// the client's key derivation and AAD; without it the archive cannot be
	// reopened after a rotation. Opaque to the server.
	RootVersion int `json:"root_version" gorm:"not null"`
	// ProtocolVersion mirrors the E2EEVault convention so the archive format can
	// evolve without ambiguity.
	ProtocolVersion int `json:"protocol_version" gorm:"not null;default:1"`
	// Ciphertext is nonce||ciphertext||tag (XChaCha20-Poly1305), base64 only at
	// the API edge. json:"-" so it can never be echoed back by accident.
	Ciphertext []byte    `json:"-" gorm:"type:bytea;not null"`
	CreatedAt  time.Time `json:"created_at" gorm:"index:idx_message_archives_user_chat,priority:3"`
}

func (MessageArchive) TableName() string { return "message_archives" }

// PurgeArchivesForParticipant removes one user's archives for one chat.
//
// This is lifetime rule (4), and it is the only rule that cannot be a foreign
// key. Leaving or deleting a chat sets chat_participants.left_at - an UPDATE,
// not a row deletion - so no cascade can observe it. Every path where a user
// stops participating must call this inside the same transaction that records
// the departure, or the user keeps a decryptable copy of a chat they deleted.
//
// Per-user by construction: the other participants' archives are untouched.
//
// ACCOUNT-DELETION GAP (external product gap, deliberately not invented here):
// this backend has no account-deletion endpoint under any name. Archives are
// therefore given an explicit owner at the schema level - user_id REFERENCES
// users(id) ON DELETE CASCADE - so that whenever account deletion is built, or
// an operator removes a user row directly, that user's archives are removed by
// the database without needing to know this table exists. That converts
// "archives are retained forever because nothing deletes them" into "archives
// are owned, and die with their owner", which is the invariant that keeps
// indefinite retention from being silently accepted.
func PurgeArchivesForParticipant(tx *gorm.DB, chatID string, userID uuid.UUID) error {
	return tx.Where("chat_id = ? AND user_id = ?", chatID, userID).
		Delete(&MessageArchive{}).Error
}

// PurgeArchivesForChat removes every user's archives for a chat.
//
// Only needed where a chat is emptied WITHOUT its chats row being deleted; when
// the row goes, the chat_id cascade already did this.
func PurgeArchivesForChat(tx *gorm.DB, chatID string) error {
	return tx.Where("chat_id = ?", chatID).Delete(&MessageArchive{}).Error
}
