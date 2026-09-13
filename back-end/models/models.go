package models

import (
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// User represents a user in the system
type User struct {
	ID            uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	Email         string    `json:"email" gorm:"uniqueIndex;not null"`
	Username      string    `json:"username" gorm:"uniqueIndex;not null"`
	PasswordHash  string    `json:"-" gorm:"not null"`
	DisplayName   string    `json:"display_name"`
	AvatarURL     string    `json:"avatar_url"`
	PhoneNumber   string    `json:"phone_number"`
	PublicKey     string    `json:"public_key" gorm:"size:512"`
	IsOnline      bool      `json:"is_online" gorm:"default:false"`
	LastSeen      time.Time `json:"last_seen"`
	FirebaseToken string    `json:"firebase_token" gorm:"size:512"`
	// TotpSecret is RFC 6238 base32 (never in JSON). TotpEnabled is the login gate.
	TotpSecret       string    `json:"-" gorm:"size:64"`
	TotpEnabled      bool      `json:"totp_enabled" gorm:"default:false"`
	TotpBackupHashes string    `json:"-" gorm:"type:text"`
	CreatedAt        time.Time `json:"created_at"`
	UpdatedAt        time.Time `json:"updated_at"`
}

// Message represents a chat message
type Message struct {
	ID               uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	SenderID         uuid.UUID `json:"sender_id" gorm:"type:uuid;index"`
	ChatID           string    `json:"chat_id" gorm:"index"`
	ChatType         string    `json:"chat_type" gorm:"index"` // "direct" or "group"
	EncryptedContent string    `json:"encrypted_content" gorm:"type:text"`
	ContentType      string    `json:"content_type" gorm:"default:'text'"`
	FileName         string    `json:"file_name" gorm:"size:255"`
	FileURL          string    `json:"file_url" gorm:"size:512"`
	FileSize         int64     `json:"file_size"`
	// DurationMs is the length of an audio or round-video message, so clients
	// can show it before downloading and decrypting the (opaque) payload.
	DurationMs int64 `json:"duration_ms"`
	// ThumbnailURL is a round video's poster frame. The bubble is drawn from
	// this while the video itself is still downloading, so a chat never shows
	// an empty circle waiting on a multi-megabyte fetch.
	ThumbnailURL string `json:"thumbnail_url" gorm:"size:512"`
	// SenderDeviceID is the installing device that sealed a direct v3 fan-out
	// (FN1). Empty on historical rows; clients fall back to a shared session.
	SenderDeviceID string `json:"sender_device_id" gorm:"size:128"`
	// KeyVersion identifies which of the sender's group Sender Key versions
	// (see Chat.KeyEpoch) encrypted this message - meaningless/0 outside a
	// group chat, where the pairwise crypto_box scheme needs no versioning.
	KeyVersion        int         `json:"key_version"`
	IsEncrypted       bool        `json:"is_encrypted" gorm:"default:true"`
	EncryptionVersion int         `json:"encryption_version" gorm:"default:1"`
	DeliveredTo       []uuid.UUID `json:"delivered_to" gorm:"type:uuid[]"`
	ReadBy            []uuid.UUID `json:"read_by" gorm:"type:uuid[]"`
	DeletedFor        []uuid.UUID `json:"deleted_for" gorm:"type:uuid[]"`
	ReplyToID         *uuid.UUID  `json:"reply_to_id" gorm:"type:uuid"`
	MentionIDs        []uuid.UUID `json:"mention_ids" gorm:"type:uuid[]"`
	// Forward metadata is display-only. The payload is always a fresh
	// E2EE ciphertext for the target chat; the server never copies source
	// ciphertext between chats.
	IsForwarded            bool       `json:"is_forwarded" gorm:"default:false"`
	ForwardedFromName      string     `json:"forwarded_from_name" gorm:"size:255"`
	ForwardedFromMessageID *uuid.UUID `json:"forwarded_from_message_id" gorm:"type:uuid"`
	DeliveredAt            *time.Time `json:"delivered_at"`
	ReadAt                 *time.Time `json:"read_at"`
	CreatedAt              time.Time  `json:"created_at"`
	UpdatedAt              time.Time  `json:"updated_at"`
	DeletedAt              *time.Time `json:"deleted_at" gorm:"index"`
	// ClientMessageID is the sender's own logical id for this message, minted
	// on the device before the first transmission and reused by every retry.
	// (sender_id, client_message_id) is unique, so a retry after a lost ACK
	// resolves to this row instead of creating a second one. Nil for senders
	// that predate Phase 71. See migratePhase71 for the index.
	ClientMessageID *string `json:"client_message_id,omitempty" gorm:"size:64"`
	// Seq is this message's position in its chat: assigned by the database on
	// INSERT, gap-free per chat, in commit order, and write-once. It is the
	// sync cursor - random ids and client-chosen timestamps cannot be. Never
	// written by the application; see migratePhase71.
	Seq int64 `json:"seq" gorm:"->;-:migration"`
}

// UserBlock is a one-way block: BlockerID will not receive messages from
// BlockedID (and messaging is rejected either direction). Distinct from
// DeleteChat, which only hides a chat until the next message arrives.
type UserBlock struct {
	ID        uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	BlockerID uuid.UUID `json:"blocker_id" gorm:"type:uuid;not null;uniqueIndex:idx_user_block_pair"`
	BlockedID uuid.UUID `json:"blocked_id" gorm:"type:uuid;not null;uniqueIndex:idx_user_block_pair;index"`
	CreatedAt time.Time `json:"created_at"`
}

// ChatParticipant represents a participant in a chat
type ChatParticipant struct {
	ID         uuid.UUID  `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	ChatID     string     `json:"chat_id" gorm:"type:uuid;index"`
	UserID     uuid.UUID  `json:"user_id" gorm:"type:uuid;index"`
	Role       string     `json:"role" gorm:"size:20;default:'member'"`
	LastReadAt *time.Time `json:"last_read_at"`
	JoinedAt   time.Time  `json:"joined_at"`
	LeftAt     *time.Time `json:"left_at"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
}

// Chat represents a conversation (direct or group)
type Chat struct {
	ID          string    `json:"id" gorm:"primaryKey"`
	Type        string    `json:"type" gorm:"index"`
	Name        string    `json:"name" gorm:"size:255"`
	AvatarURL   string    `json:"avatar_url" gorm:"size:512"`
	Description string    `json:"description"`
	OwnerID     uuid.UUID `json:"owner_id" gorm:"type:uuid"`
	// KeyEpoch increments every time a group's membership changes (add or
	// remove a member). It's the group E2EE "Sender Key" rotation signal:
	// a client compares this to the epoch its own currently-distributed
	// sender key was issued for, and if this is higher, generates a new
	// sender key and redistributes it to the current membership only -
	// this is what keeps a removed member from reading messages sent after
	// they left, the same guarantee WhatsApp/Signal's Sender Keys give.
	// Meaningless for a direct chat (always 0).
	KeyEpoch int `json:"key_epoch" gorm:"default:0"`
	// LastMessage is never loaded or written - it only ever serialized as null.
	// gorm:"-" keeps it out of the schema: left visible, GORM infers a has-one
	// relation from it and emits fk_chats_last_message (messages.chat_id ->
	// chats.id, ON DELETE NO ACTION), which makes every group that has messages
	// impossible to delete. See MigrateDB, which drops the legacy constraint.
	LastMessage   *Message   `json:"last_message" gorm:"-"`
	LastMessageAt *time.Time `json:"last_message_at"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
}

// MessageDeletion records that one user removed one message from their own
// view ("delete for me"). A join table rather than an array column on
// messages: Postgres arrays of uuid do not round-trip through GORM's
// []uuid.UUID mapping, and one populated row breaks reads of the whole table.
// A row here is also trivially indexable and removable.
type MessageDeletion struct {
	MessageID uuid.UUID `json:"message_id" gorm:"type:uuid;primaryKey"`
	UserID    uuid.UUID `json:"user_id" gorm:"type:uuid;primaryKey"`
	CreatedAt time.Time `json:"created_at"`
}

// GroupSenderKey stores one member's encrypted copy of another member's
// current group "Sender Key" - see the WhatsApp/Signal-style scheme
// described on Chat.KeyEpoch. EncryptedKey is hex(nonce||crypto_box(...)),
// encrypted by SenderID for RecipientID specifically (the same pairwise
// crypto_box direct chats already use) - the server only ever stores and
// relays these opaque blobs, never the key itself.
type GroupSenderKey struct {
	ID           uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	ChatID       string    `json:"chat_id" gorm:"index:idx_gsk_lookup"`
	SenderID     uuid.UUID `json:"sender_id" gorm:"type:uuid"`
	RecipientID  uuid.UUID `json:"recipient_id" gorm:"type:uuid;index:idx_gsk_lookup"`
	KeyVersion   int       `json:"key_version"`
	EncryptedKey string    `json:"encrypted_key" gorm:"type:text"`
	CreatedAt    time.Time `json:"created_at"`
}

// GroupMember represents a member of a group chat (legacy, use ChatParticipant)
type GroupMember struct {
	ID        uuid.UUID  `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	GroupID   string     `json:"group_id" gorm:"type:uuid;index"`
	UserID    uuid.UUID  `json:"user_id" gorm:"type:uuid;index"`
	Role      string     `json:"role" gorm:"size:20"`
	JoinedAt  time.Time  `json:"joined_at"`
	LeftAt    *time.Time `json:"left_at"`
	CreatedAt time.Time  `json:"created_at"`
}

// CallLog records a 1:1 call's lifecycle for call history (WhatsApp-style
// "Missed call"/"Answered" entries). The server never sees call media or its
// keys - libdatachannel negotiates DTLS-SRTP directly between the two peers
// via ICE, and this table only tracks who called whom, when, and how it ended.
// Only SDP offer/answer and ICE candidates are relayed through the server
// (see websocket/calls.go), the same "opaque blob" principle as E2EE messages.
type CallLog struct {
	ID       uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	ChatID   string    `json:"chat_id" gorm:"index"`
	CallerID uuid.UUID `json:"caller_id" gorm:"type:uuid;index"`
	CalleeID uuid.UUID `json:"callee_id" gorm:"type:uuid;index"`
	// "ringing" | "answered" | "missed" | "rejected" | "ended" | "failed"
	Status string `json:"status" gorm:"size:20;default:'ringing'"`
	// True when the caller invited with video (the media itself is still
	// opaque to the server - this only labels the history entry).
	IsVideo     bool       `json:"is_video" gorm:"default:false"`
	StartedAt   time.Time  `json:"started_at"`
	ConnectedAt *time.Time `json:"connected_at"`
	EndedAt     *time.Time `json:"ended_at"`
	DurationSec int        `json:"duration_sec"`
	CreatedAt   time.Time  `json:"created_at"`
}

// Presence represents user presence status
type Presence struct {
	UserID    uuid.UUID `json:"user_id" gorm:"type:uuid;primaryKey"`
	IsOnline  bool      `json:"is_online"`
	LastSeen  time.Time `json:"last_seen"`
	DeviceID  string    `json:"device_id"`
	UpdatedAt time.Time `json:"updated_at"`
}

// TypingIndicator represents a user typing status
type TypingIndicator struct {
	UserID    uuid.UUID `json:"user_id" gorm:"type:uuid"`
	ChatID    string    `json:"chat_id"`
	IsTyping  bool      `json:"is_typing"`
	UpdatedAt time.Time `json:"updated_at"`
}

// WebSocketMessage represents a message sent over WebSocket
type WebSocketMessage struct {
	Type      string      `json:"type"`
	Data      interface{} `json:"data"`
	Timestamp time.Time   `json:"timestamp"`
}

// SendMessageRequest represents the request to send a message
type SendMessageRequest struct {
	ChatID      string      `json:"chat_id" binding:"required"`
	ChatType    string      `json:"chat_type" binding:"required"`
	Content     string      `json:"content" binding:"required"`
	ContentType string      `json:"content_type"`
	ReplyToID   *uuid.UUID  `json:"reply_to_id"`
	MentionIDs  []uuid.UUID `json:"mention_ids"`
}

// MessageCreateRequest represents the request to create a message
type MessageCreateRequest struct {
	ChatID             string `json:"chat_id" binding:"required"`
	ChatType           string `json:"chat_type" binding:"required"`
	Content            string `json:"content" binding:"required"`
	ContentType        string `json:"content_type"`
	RecipientPublicKey string `json:"recipient_public_key"`
	// Encrypted is true when Content is client-side E2EE ciphertext
	// (hex of nonce||crypto_box) rather than plaintext. The server treats
	// Content as an opaque blob either way and never decrypts it.
	Encrypted bool   `json:"encrypted"`
	FileURL   string `json:"file_url"`
	FileType  string `json:"file_type"`
	// FileName/FileSize describe an attachment (image or generic file) for
	// display before the client fetches and decrypts the actual bytes -
	// same reasoning as DurationMs for voice notes.
	FileName string `json:"file_name"`
	FileSize int64  `json:"file_size"`
	// DurationMs is the length of a voice note or round video in milliseconds.
	DurationMs int64 `json:"duration_ms"`
	// ThumbnailURL points at a round video's already-uploaded poster frame.
	ThumbnailURL string `json:"thumbnail_url"`
	// KeyVersion: see Message.KeyVersion. Only meaningful (and only ever
	// non-zero) for a group message.
	KeyVersion int `json:"key_version"`
	// EncryptionVersion: 1 = static pairwise crypto_box (legacy);
	// 2 = ephemeral crypto_box (direct FS). Groups stay at 1.
	EncryptionVersion int         `json:"encryption_version"`
	ReplyToID         *uuid.UUID  `json:"reply_to_id"`
	MentionIDs        []uuid.UUID `json:"mention_ids"`
	// Forward attribution (UI only; content is always re-encrypted for the target).
	IsForwarded            bool       `json:"is_forwarded"`
	ForwardedFromName      string     `json:"forwarded_from_name"`
	ForwardedFromMessageID *uuid.UUID `json:"forwarded_from_message_id"`
	// ClientMessageID makes the send idempotent: a UUID the sending device
	// generated once for this logical message and repeats on every retry.
	// Optional, so older clients keep working (without idempotency).
	ClientMessageID string `json:"client_message_id"`
}

// CreateGroupRequest represents the request to create a group
type CreateGroupRequest struct {
	Name        string      `json:"name" binding:"required"`
	Description string      `json:"description"`
	MemberIDs   []uuid.UUID `json:"member_ids" binding:"required"`
	AvatarURL   string      `json:"avatar_url"`
}

// AddMemberRequest represents the request to add members to a group
type AddMemberRequest struct {
	MemberIDs []uuid.UUID `json:"member_ids" binding:"required"`
}

// UpdateGroupRequest represents the request to update a group
type UpdateGroupRequest struct {
	Name        *string `json:"name"`
	Description *string `json:"description"`
	AvatarURL   *string `json:"avatar_url"`
}

// RemoveMemberRequest represents the request to remove members from a group
type RemoveMemberRequest struct {
	MemberIDs []uuid.UUID `json:"member_ids" binding:"required"`
}

// AuthResponse represents the authentication response
type AuthResponse struct {
	User         User      `json:"user"`
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	ExpiresAt    time.Time `json:"expires_at"`
}

// LoginRequest represents the login request
type LoginRequest struct {
	Email    string `json:"email" binding:"required,email"`
	Password string `json:"password" binding:"required"`
}

// RegisterRequest represents the registration request
type RegisterRequest struct {
	Email       string `json:"email" binding:"required,email"`
	Username    string `json:"username" binding:"required"`
	Password    string `json:"password" binding:"required,min=8"`
	DisplayName string `json:"display_name"`
	PhoneNumber string `json:"phone_number"`
	PublicKey   string `json:"public_key" binding:"required"`
}

// PaginationRequest represents pagination parameters
type PaginationRequest struct {
	Limit  int `form:"limit"`
	Offset int `form:"offset"`
}

// PaginationResponse represents the pagination response
type PaginationResponse struct {
	Data    interface{} `json:"data"`
	Total   int64       `json:"total"`
	Limit   int         `json:"limit"`
	Offset  int         `json:"offset"`
	HasMore bool        `json:"has_more"`
}

// ErrorResponse represents an error response
type ErrorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message"`
	Code    int    `json:"code"`
}

// MessageResponse represents a message in API responses
type MessageResponse struct {
	ID                     uuid.UUID   `json:"id"`
	SenderID               uuid.UUID   `json:"sender_id"`
	Sender                 UserInfo    `json:"sender"`
	ChatID                 string      `json:"chat_id"`
	ChatType               string      `json:"chat_type"`
	DecryptedContent       string      `json:"decrypted_content,omitempty"`
	EncryptedContent       string      `json:"encrypted_content"`
	ContentType            string      `json:"content_type"`
	FileName               string      `json:"file_name"`
	FileURL                string      `json:"file_url"`
	FileSize               int64       `json:"file_size"`
	IsEncrypted            bool        `json:"is_encrypted"`
	EncryptionVersion      int         `json:"encryption_version"`
	DeliveredTo            []uuid.UUID `json:"delivered_to"`
	ReadBy                 []uuid.UUID `json:"read_by"`
	ReplyToID              *uuid.UUID  `json:"reply_to_id"`
	MentionIDs             []uuid.UUID `json:"mention_ids"`
	IsForwarded            bool        `json:"is_forwarded"`
	ForwardedFromName      string      `json:"forwarded_from_name"`
	ForwardedFromMessageID *uuid.UUID  `json:"forwarded_from_message_id"`
	DeliveredAt            *time.Time  `json:"delivered_at"`
	ReadAt                 *time.Time  `json:"read_at"`
	CreatedAt              time.Time   `json:"created_at"`
	UpdatedAt              time.Time   `json:"updated_at"`
}

// UserInfo represents minimal user info for API responses
type UserInfo struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	Username    string    `json:"username"`
	DisplayName string    `json:"display_name"`
	AvatarURL   string    `json:"avatar_url"`
	IsOnline    bool      `json:"is_online"`
}

// ChatResponse represents a chat in API responses
type ChatResponse struct {
	ID            string           `json:"id"`
	Type          string           `json:"type"`
	Name          string           `json:"name"`
	AvatarURL     string           `json:"avatar_url"`
	Members       []UserInfo       `json:"members,omitempty"`
	LastMessage   *MessageResponse `json:"last_message"`
	LastMessageAt *time.Time       `json:"last_message_at"`
	CreatedAt     time.Time        `json:"created_at"`
	UpdatedAt     time.Time        `json:"updated_at"`
}

// MigrateDB runs database migrations
func MigrateDB(db *gorm.DB) error {
	if err := db.AutoMigrate(
		&User{},
		&Chat{},
		&Message{},
		&ChatParticipant{},
		&GroupMember{},
		&Presence{},
		&TypingIndicator{},
		&GroupSenderKey{},
		&CallLog{},
		&MessageDeletion{},
		&MessageArchive{},
		&HistoryKeyringRecovery{},
		&UserBlock{},
		&E2EEVault{},
		&E2EEDevice{},
		&E2EEDeviceChallenge{},
		&E2EEAccountAuthority{},
		&E2EERetiredDeviceKey{},
		&E2EEPairingSession{},
		&MLSKeyPackage{},
		&MLSGroup{},
		&MLSHandshake{},
		&MLSWelcome{},
		&QRLoginSession{},
		&Session{},
		&ConsumedRefresh{},
	); err != nil {
		return err
	}

	// Legacy: Register used to store an unused server-side private key.
	// Drop the column so a DB dump cannot expose leftover E2EE material.
	if err := db.Exec("ALTER TABLE users DROP COLUMN IF EXISTS private_key").Error; err != nil {
		return fmt.Errorf("drop legacy users.private_key: %w", err)
	}

	// Legacy: fk_chats_last_message (messages.chat_id -> chats.id, ON DELETE NO
	// ACTION) was never designed. GORM inferred it from Chat.LastMessage, a field
	// nothing loads or writes - now tagged gorm:"-", so AutoMigrate above no
	// longer creates it. DeleteGroup keeps a deleted group's messages as rows
	// with deleted_at set and hard-deletes the chat row; this constraint refused
	// that, so no group with messages could ever be deleted. Dropping it changes
	// no row. On a fresh database, and on every run after the first, it is a
	// no-op.
	//
	// Operator pre-check before this first reaches an existing deployment: while
	// the constraint stood, no DeleteGroup on a group with messages could commit,
	// so `SELECT count(*) FROM messages WHERE deleted_at IS NOT NULL` should read
	// 0. Anything else means soft-deleted rows came from a path this change did
	// not account for - stop and find it first. (GetMessages never serves such
	// rows; see its visibility predicate.)
	if err := db.Exec("ALTER TABLE messages DROP CONSTRAINT IF EXISTS fk_chats_last_message").Error; err != nil {
		return fmt.Errorf("drop accidental fk_chats_last_message: %w", err)
	}

	// messages.deleted_for is abandoned in favour of MessageDeletion, and any
	// value left in it has to go. []uuid.UUID is only scannable while the
	// column is NULL: the driver returns "{uuid,...}" as a string, which GORM
	// cannot map into the slice, so a single populated row makes EVERY read of
	// the messages table fail. Clearing it is safe and idempotent.
	if err := db.Exec("UPDATE messages SET deleted_for = NULL WHERE deleted_for IS NOT NULL").Error; err != nil {
		return fmt.Errorf("clear legacy messages.deleted_for: %w", err)
	}

	// Encrypted history archives are decryptable by any device holding the
	// chat's history root, so unlike a message row - whose ciphertext is inert
	// once its MLS/ratchet key is consumed - an orphaned archive is a live
	// privacy liability. Its lifetime is therefore enforced by the database
	// rather than by remembering to clean up in every deletion handler.
	//
	// AutoMigrate does not emit these: the model uses plain scalar columns with
	// no GORM association, by design (an association would invite the ORM to
	// load or cascade archive rows implicitly). Declaring them here follows the
	// same post-migrate fixup convention as the statements above.
	//
	//   message_id -> delete-for-everyone hard-deletes the message row
	//   chat_id    -> DeleteGroup hard-deletes the chat row
	//   user_id    -> gives archives an explicit owner, so if an account is ever
	//                 deleted its archives go with it. See the account-deletion
	//                 gap documented on PurgeArchivesForParticipant.
	//
	// Idempotent: Postgres has no ADD CONSTRAINT IF NOT EXISTS.
	for _, fk := range []struct{ name, spec string }{
		{"fk_message_archives_message", "FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE"},
		{"fk_message_archives_chat", "FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE"},
		{"fk_message_archives_user", "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"},
	} {
		if err := db.Exec(`DO $$ BEGIN
			IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '` + fk.name + `') THEN
				ALTER TABLE message_archives ADD CONSTRAINT ` + fk.name + ` ` + fk.spec + `;
			END IF;
		END $$;`).Error; err != nil {
			return fmt.Errorf("archive FK %s: %w", fk.name, err)
		}
	}

	// The history keyring recovery blob is owned by its user and dies with them.
	// Same reasoning as the archive cascades above: an orphaned blob is a live
	// liability rather than dead weight, so its lifetime is enforced by the
	// database rather than by remembering to clean up in a handler.
	if err := db.Exec(`DO $$ BEGIN
		IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_history_keyring_user') THEN
			ALTER TABLE history_keyring_recoveries ADD CONSTRAINT fk_history_keyring_user
				FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
		END IF;
	END $$;`).Error; err != nil {
		return fmt.Errorf("history keyring FK: %w", err)
	}

	// Phase 70 back-fill. Accounts that are ALREADY LEGACY_RETIRED when this
	// remediation deploys have no durable retired-key evidence yet, so their
	// K_account would still resurrect through the Phase 69 deleted-row gap.
	// Reconstruct the deny-list for them from whatever legacy keys they still
	// hold: every authority_kind='legacy' key, plus any device row whose key
	// equals the account's published identity key (a K_account that landed in a
	// 'device' row). Idempotent: ON CONFLICT DO NOTHING, and the retired_at is
	// carried from the account's own legacy_retired_at.
	//
	// LIMITATION, reported not hidden: if an already-retired account had its last
	// legacy row deleted BEFORE this deploy and the key never sat in a device row
	// or users.public_key, the server has no record of that K_account and cannot
	// reconstruct it. Such a key remains resurrectable. Nothing here invents
	// cryptographic history; the gap is closed for every account retired from this
	// deploy onward and for every already-retired account whose key still exists
	// somewhere in its rows.
	// (a) the account's own published identity key (K_account), which survives on
	//     users.public_key even when every device row is gone;
	if err := db.Exec(`
		INSERT INTO e2ee_retired_device_keys (id, user_id, public_key, retired_at, created_at)
		SELECT gen_random_uuid(), aa.user_id, lower(u.public_key), aa.legacy_retired_at, now()
		FROM e2ee_account_authorities aa
		JOIN users u ON u.id = aa.user_id
		WHERE aa.legacy_retired_at IS NOT NULL AND COALESCE(u.public_key, '') <> ''
		ON CONFLICT (user_id, public_key) DO NOTHING`).Error; err != nil {
		return fmt.Errorf("backfill retired account keys: %w", err)
	}
	// (b) every legacy device-row key still present (and any device-provenance row
	//     holding the account key).
	if err := db.Exec(`
		INSERT INTO e2ee_retired_device_keys (id, user_id, public_key, retired_at, created_at)
		SELECT gen_random_uuid(), d.user_id, lower(d.public_key), aa.legacy_retired_at, now()
		FROM e2_ee_devices d
		JOIN e2ee_account_authorities aa ON aa.user_id = d.user_id
		LEFT JOIN users u ON u.id = d.user_id
		WHERE aa.legacy_retired_at IS NOT NULL
		  AND d.public_key <> ''
		  AND (d.authority_kind = 'legacy'
		       OR (COALESCE(u.public_key, '') <> '' AND lower(d.public_key) = lower(u.public_key)))
		ON CONFLICT (user_id, public_key) DO NOTHING`).Error; err != nil {
		return fmt.Errorf("backfill retired legacy keys: %w", err)
	}

	if err := migratePhase71(db); err != nil {
		return err
	}

	return migrateGate17(db)
}

// migrateGate17 installs the session layer's database-enforced invariants.
//
// Every statement here is checked, and every resulting object is then verified
// against the PostgreSQL catalogs. That is not belt-and-braces: AutoMigrate
// cannot emit foreign keys (the models declare no associations, deliberately),
// partial indexes, CHECK constraints or triggers, so ALL of Gate 17's
// database-level guarantees ride these raw statements. The historical pattern
// in this file discarded their errors, which meant a server could boot with
// none of them present and say nothing. A missing constraint here is a missing
// security control, so it stops startup.
func migrateGate17(db *gorm.DB) error {
	// Constraints. Postgres has no ADD CONSTRAINT IF NOT EXISTS, hence the
	// catalog guard; the same idempotent shape already used above.
	constraints := []struct{ table, name, spec string }{
		{"sessions", "fk_sessions_user",
			"FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"},
		// SET NULL, never CASCADE: Gate 14 made device revocation terminal, and a
		// cascade here would let deleting a device silently erase the sessions
		// that recorded its activity - along with the fork evidence hanging off
		// them.
		//
		// SET NULL is safe here only because of the guard trigger below, which
		// permits device_id -> NULL exclusively on an ALREADY-REVOKED session.
		// The FK action is an ordinary UPDATE and is therefore subject to that
		// trigger, so a device with even one LIVE session cannot be deleted: the
		// trigger raises and the whole deletion rolls back. Detaching a live
		// session is impossible by construction rather than by convention.
		{"sessions", "fk_sessions_device",
			"FOREIGN KEY (device_id) REFERENCES e2_ee_devices(id) ON DELETE SET NULL"},
		{"sessions", "ck_sessions_refresh_hash_len",
			"CHECK (refresh_hash IS NULL OR octet_length(refresh_hash) = 32)"},
		// The pairing of terminal revocation with an unusable credential. Without
		// it, a revoked row could still carry a hash that some future CAS matches.
		{"sessions", "ck_sessions_revoked_implies_no_hash",
			"CHECK (revoked_at IS NULL OR refresh_hash IS NULL)"},
		{"sessions", "ck_sessions_absolute_after_created",
			"CHECK (absolute_expires_at > created_at)"},
		{"consumed_refresh", "fk_consumed_refresh_session",
			"FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE RESTRICT"},
		{"consumed_refresh", "ck_consumed_refresh_hash_len",
			"CHECK (octet_length(consumed_hash) = 32)"},
		{"consumed_refresh", "ck_consumed_refresh_next_hash_len",
			"CHECK (octet_length(next_refresh_hash) = 32)"},
		// Fork evidence must outlive the token it describes, or a consumed token
		// could still be presented after its ledger row was purged.
		{"consumed_refresh", "ck_consumed_refresh_window_order",
			"CHECK (reuse_window_expires_at >= response_expires_at)"},
	}
	// Gate 18: fk_sessions_device changed action from RESTRICT to SET NULL. The
	// guarded ADD below keys on the constraint NAME, so a database created
	// before this change would keep the old action forever. Drop it whenever the
	// catalog reports an action other than SET NULL ('n') and let the loop
	// rebuild it. A no-op once converged.
	if err := db.Exec(`DO $$ BEGIN
		IF EXISTS (SELECT 1 FROM pg_constraint
		            WHERE conname = 'fk_sessions_device' AND confdeltype <> 'n') THEN
			ALTER TABLE sessions DROP CONSTRAINT fk_sessions_device;
		END IF;
	END $$;`).Error; err != nil {
		return fmt.Errorf("gate18 fk_sessions_device action upgrade: %w", err)
	}

	for _, c := range constraints {
		stmt := `DO $$ BEGIN
			IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '` + c.name + `') THEN
				ALTER TABLE ` + c.table + ` ADD CONSTRAINT ` + c.name + ` ` + c.spec + `;
			END IF;
		END $$;`
		if err := db.Exec(stmt).Error; err != nil {
			return fmt.Errorf("gate17 constraint %s: %w", c.name, err)
		}
	}

	// Partial indexes. CREATE INDEX IF NOT EXISTS is natively idempotent, so
	// these need no catalog guard - and they live in pg_indexes, not
	// pg_constraint, which is why they are not folded into the loop above.
	//
	// Deliberately absent: UNIQUE (user_id, device_id) WHERE revoked_at IS NULL.
	// NULLs compare distinct in PostgreSQL, so it would silently fail to
	// constrain exactly the device_id IS NULL sessions this design produces,
	// while reading as though one-live-session-per-device were enforced.
	indexes := []struct{ name, spec string }{
		{"ix_sessions_user_live",
			"ON sessions (user_id) WHERE revoked_at IS NULL"},
		{"ix_sessions_absolute_live",
			"ON sessions (absolute_expires_at) WHERE revoked_at IS NULL"},
		{"ix_sessions_device_live",
			"ON sessions (device_id) WHERE revoked_at IS NULL AND device_id IS NOT NULL"},
		{"ix_consumed_refresh_session",
			"ON consumed_refresh (session_id, consumed_at DESC)"},
		{"ix_consumed_refresh_reuse_window",
			"ON consumed_refresh (reuse_window_expires_at)"},
		{"ix_consumed_refresh_response_live",
			"ON consumed_refresh (response_expires_at) WHERE response_ciphertext IS NOT NULL"},
	}
	for _, ix := range indexes {
		if err := db.Exec("CREATE INDEX IF NOT EXISTS " + ix.name + " " + ix.spec).Error; err != nil {
			return fmt.Errorf("gate17 index %s: %w", ix.name, err)
		}
	}

	// Resurrection prevention, and immutability of the columns that decide whose
	// session this is. Application code could enforce both, but application code
	// is exactly what a future handler forgets; a revoked session becoming live
	// again, or a session quietly changing owner, must be unrepresentable rather
	// than merely unwritten.
	if err := db.Exec(`
		CREATE OR REPLACE FUNCTION gate17_sessions_guard() RETURNS trigger AS $$
		BEGIN
			IF OLD.revoked_at IS NOT NULL AND
			   (NEW.revoked_at IS NULL OR NEW.revoked_at <> OLD.revoked_at) THEN
				RAISE EXCEPTION 'gate17: revoked_at is write-once (session %)', OLD.id
					USING ERRCODE = 'check_violation';
			END IF;
			IF NEW.user_id <> OLD.user_id THEN
				RAISE EXCEPTION 'gate17: session user_id is immutable (session %)', OLD.id
					USING ERRCODE = 'check_violation';
			END IF;
			-- device_id is WRITE-ONCE, not immutable-from-birth.
			--
			-- Exactly two transitions are permitted.
			--
			-- (1) Gate 18: detaching a session that is ALREADY revoked, and only
			-- to NULL. That is what fk_sessions_device's ON DELETE SET NULL
			-- performs after DeleteDevice has revoked the device's sessions in the
			-- same transaction, and it is what lets a revoked session survive as a
			-- historical record (with its fork evidence) once its device row is
			-- gone.
			--
			-- (2) Phase 44: the FIRST attachment of a live, still-unbound session,
			-- NULL -> a device. No authentication path knows a device - login,
			-- both 2FA branches and QR claim all mint credentials before any
			-- device has proven anything - so a session that could only ever be
			-- bound at INSERT could never be bound at all, and device
			-- authorization would stay decorative. RegisterDevice performs this
			-- write in the same transaction that verifies proof of possession.
			--
			-- Everything else still raises, and the property Gate 18 actually
			-- protects is untouched: no session, live or revoked, can ever be
			-- re-bound from one device to a DIFFERENT one, and a live session can
			-- never be detached. Since the FK action is an ordinary UPDATE, a
			-- device with even one LIVE session still cannot be deleted; the
			-- deletion fails closed instead of orphaning a credential.
			IF NEW.device_id IS DISTINCT FROM OLD.device_id THEN
				IF NOT (
					(OLD.revoked_at IS NOT NULL AND NEW.device_id IS NULL) OR
					(OLD.revoked_at IS NULL AND OLD.device_id IS NULL AND NEW.device_id IS NOT NULL)
				) THEN
					RAISE EXCEPTION 'gate18: session device_id is immutable while live (session %)', OLD.id
						USING ERRCODE = 'check_violation';
				END IF;
			END IF;
			RETURN NEW;
		END;
		$$ LANGUAGE plpgsql;`).Error; err != nil {
		return fmt.Errorf("gate17 guard function: %w", err)
	}
	if err := db.Exec(`DROP TRIGGER IF EXISTS trg_gate17_sessions_guard ON sessions`).Error; err != nil {
		return fmt.Errorf("gate17 guard trigger drop: %w", err)
	}
	if err := db.Exec(`CREATE TRIGGER trg_gate17_sessions_guard
		BEFORE UPDATE ON sessions
		FOR EACH ROW EXECUTE FUNCTION gate17_sessions_guard()`).Error; err != nil {
		return fmt.Errorf("gate17 guard trigger: %w", err)
	}

	// Password replacement revokes every session for that account, enforced at
	// the row that changes rather than in the handler that usually changes it.
	//
	// F4 requires the revocation to share a transaction with the event causing
	// it, and a trigger is the strongest form of that: it holds for the HTTP
	// handlers AND for a reset script, an admin console or a manual UPDATE. A
	// handler-only implementation would leave "password changed by any other
	// means" as a silent hole, which is exactly the shape of bug this gate
	// exists to remove.
	if err := db.Exec(`
		CREATE OR REPLACE FUNCTION gate17_revoke_on_password_change() RETURNS trigger AS $$
		BEGIN
			IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
				UPDATE sessions
				   SET revoked_at    = COALESCE(revoked_at, clock_timestamp()),
				       revoke_reason = COALESCE(revoke_reason, 'password_change'),
				       refresh_hash  = NULL,
				       updated_at    = clock_timestamp()
				 WHERE user_id = NEW.id AND revoked_at IS NULL;
				UPDATE consumed_refresh
				   SET response_ciphertext = NULL, response_nonce = NULL
				 WHERE response_ciphertext IS NOT NULL
				   AND session_id IN (SELECT id FROM sessions WHERE user_id = NEW.id);
			END IF;
			RETURN NEW;
		END;
		$$ LANGUAGE plpgsql;`).Error; err != nil {
		return fmt.Errorf("gate17 password-change function: %w", err)
	}
	if err := db.Exec(`DROP TRIGGER IF EXISTS trg_gate17_password_change ON users`).Error; err != nil {
		return fmt.Errorf("gate17 password trigger drop: %w", err)
	}
	if err := db.Exec(`CREATE TRIGGER trg_gate17_password_change
		AFTER UPDATE OF password_hash ON users
		FOR EACH ROW EXECUTE FUNCTION gate17_revoke_on_password_change()`).Error; err != nil {
		return fmt.Errorf("gate17 password trigger: %w", err)
	}

	return verifyGate17Schema(db)
}

// verifyGate17Schema refuses to start unless every declared object exists.
//
// The statements above are idempotent and guarded, which means a silent no-op
// looks identical to success. Reading the catalogs back is the only way to know
// the difference between "already present" and "never created".
func verifyGate17Schema(db *gorm.DB) error {
	var missing []string

	for _, name := range []string{
		"fk_sessions_user", "fk_sessions_device",
		"ck_sessions_refresh_hash_len", "ck_sessions_revoked_implies_no_hash",
		"ck_sessions_absolute_after_created",
		"fk_consumed_refresh_session", "ck_consumed_refresh_hash_len",
		"ck_consumed_refresh_next_hash_len", "ck_consumed_refresh_window_order",
	} {
		var n int64
		if err := db.Raw(`SELECT count(*) FROM pg_constraint WHERE conname = ?`, name).
			Scan(&n).Error; err != nil {
			return fmt.Errorf("gate17 verify constraint %s: %w", name, err)
		}
		if n == 0 {
			missing = append(missing, "constraint "+name)
		}
	}

	for _, name := range []string{
		"ix_sessions_user_live", "ix_sessions_absolute_live", "ix_sessions_device_live",
		"ix_consumed_refresh_session", "ix_consumed_refresh_reuse_window",
		"ix_consumed_refresh_response_live",
	} {
		var n int64
		if err := db.Raw(`SELECT count(*) FROM pg_indexes WHERE indexname = ?`, name).
			Scan(&n).Error; err != nil {
			return fmt.Errorf("gate17 verify index %s: %w", name, err)
		}
		if n == 0 {
			missing = append(missing, "index "+name)
		}
	}

	// Gate 18: the ACTION matters, not merely the constraint's existence. Model C
	// depends on the FK nulling a revoked session's device pointer rather than
	// refusing the delete, and the trigger depends on that action being an
	// ordinary UPDATE it can veto. Read the action back from the catalog: a
	// database still carrying RESTRICT would make DeleteDevice fail on any device
	// that ever held a session.
	var fkAction string
	if err := db.Raw(`SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_sessions_device'`).
		Scan(&fkAction).Error; err != nil {
		return fmt.Errorf("gate18 verify fk_sessions_device action: %w", err)
	}
	if fkAction != "n" {
		missing = append(missing,
			fmt.Sprintf("fk_sessions_device ON DELETE action is %q, want \"n\" (SET NULL)", fkAction))
	}

	for _, tg := range []string{"trg_gate17_sessions_guard", "trg_gate17_password_change"} {
		var trg int64
		if err := db.Raw(`SELECT count(*) FROM pg_trigger WHERE tgname = ?`, tg).
			Scan(&trg).Error; err != nil {
			return fmt.Errorf("gate17 verify trigger %s: %w", tg, err)
		}
		if trg == 0 {
			missing = append(missing, "trigger "+tg)
		}
	}

	if len(missing) > 0 {
		return fmt.Errorf("gate17 schema incomplete, refusing to start: %s",
			strings.Join(missing, ", "))
	}
	return nil
}
