package models

import (
	"time"

	"github.com/google/uuid"
)

// QRLoginSession backs WhatsApp-style "scan to sign in": a signed-out client
// shows a QR, an already-authenticated device scans it, and the first client
// receives tokens without a password.
//
// The threat this schema is shaped around is account takeover. Two independent
// secrets are required, and neither travels the same path:
//
//   - VerifierHash is SHA-256 of a secret the DISPLAYING client generated and
//     never transmits. Only that client can later collect the tokens, so
//     someone who learns a session id (from logs, a shoulder-surfed QR, or
//     guessing) still cannot claim the session.
//   - ScanSecretHash is SHA-256 of a secret carried ONLY inside the QR image.
//     Approving requires it, which proves the approver physically scanned the
//     code rather than merely knowing a session id.
//
// Both are stored hashed so a database dump cannot be replayed to complete a
// pending login.
type QRLoginSession struct {
	ID        uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	SessionID string    `json:"session_id" gorm:"size:64;uniqueIndex;not null"`

	VerifierHash   string `json:"-" gorm:"size:64;not null"`
	ScanSecretHash string `json:"-" gorm:"size:64;not null"`

	// Shown to the approving user so they can see what they are authorising.
	ClientName     string `json:"client_name" gorm:"size:128"`
	ClientPlatform string `json:"client_platform" gorm:"size:32"`
	// Best-effort origin of the request, for the approval prompt.
	RequestIP string `json:"request_ip" gorm:"size:64"`

	// Set once a signed-in device approves. Until then the session grants
	// nothing.
	ApprovedUserID *uuid.UUID `json:"-" gorm:"type:uuid"`
	ApprovedAt     *time.Time `json:"approved_at"`

	// Single-use: stamped when the displaying client collects its tokens, so a
	// replayed collect returns nothing.
	ConsumedAt *time.Time `json:"consumed_at"`

	// Short lifetime bounds how long an unattended QR stays dangerous.
	ExpiresAt time.Time `json:"expires_at" gorm:"index;not null"`
	CreatedAt time.Time `json:"created_at"`
}
