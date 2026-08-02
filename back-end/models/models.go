package models

import (
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
	PrivateKey    string    `json:"-" gorm:"size:512"`
	IsOnline      bool      `json:"is_online" gorm:"default:false"`
	LastSeen      time.Time `json:"last_seen"`
	FirebaseToken string    `json:"firebase_token" gorm:"size:512"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
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
	DeliveredAt       *time.Time  `json:"delivered_at"`
	ReadAt            *time.Time  `json:"read_at"`
	CreatedAt         time.Time   `json:"created_at"`
	UpdatedAt         time.Time   `json:"updated_at"`
	DeletedAt         *time.Time  `json:"deleted_at" gorm:"index"`
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
	KeyEpoch      int        `json:"key_epoch" gorm:"default:0"`
	LastMessage   *Message   `json:"last_message"`
	LastMessageAt *time.Time `json:"last_message_at"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
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

// CallLog records a 1:1 audio call's lifecycle for call history (WhatsApp-style
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
	Status      string     `json:"status" gorm:"size:20;default:'ringing'"`
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
	KeyVersion int         `json:"key_version"`
	ReplyToID  *uuid.UUID  `json:"reply_to_id"`
	MentionIDs []uuid.UUID `json:"mention_ids"`
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
	ID                uuid.UUID   `json:"id"`
	SenderID          uuid.UUID   `json:"sender_id"`
	Sender            UserInfo    `json:"sender"`
	ChatID            string      `json:"chat_id"`
	ChatType          string      `json:"chat_type"`
	DecryptedContent  string      `json:"decrypted_content,omitempty"`
	EncryptedContent  string      `json:"encrypted_content"`
	ContentType       string      `json:"content_type"`
	FileName          string      `json:"file_name"`
	FileURL           string      `json:"file_url"`
	FileSize          int64       `json:"file_size"`
	IsEncrypted       bool        `json:"is_encrypted"`
	EncryptionVersion int         `json:"encryption_version"`
	DeliveredTo       []uuid.UUID `json:"delivered_to"`
	ReadBy            []uuid.UUID `json:"read_by"`
	ReplyToID         *uuid.UUID  `json:"reply_to_id"`
	MentionIDs        []uuid.UUID `json:"mention_ids"`
	DeliveredAt       *time.Time  `json:"delivered_at"`
	ReadAt            *time.Time  `json:"read_at"`
	CreatedAt         time.Time   `json:"created_at"`
	UpdatedAt         time.Time   `json:"updated_at"`
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
	return db.AutoMigrate(
		&User{},
		&Chat{},
		&Message{},
		&ChatParticipant{},
		&GroupMember{},
		&Presence{},
		&TypingIndicator{},
		&GroupSenderKey{},
		&CallLog{},
	)
}
