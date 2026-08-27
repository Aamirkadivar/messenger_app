package models

import (
	"time"

	"github.com/google/uuid"
)

// HistoryKeyringRecovery is one user's opaque, MK-sealed history keyring.
//
// It exists so history can survive device replacement and a fresh install: the
// keyring itself has always been device-local, so losing the device lost the
// roots and made every server-stored archive permanently undecryptable.
//
// WHAT THE SERVER KNOWS. Only that a user owns one versioned blob. The payload
// is XChaCha20-Poly1305 sealed on the device under the vault master key with a
// dedicated recovery AAD domain, so the server cannot decrypt it, cannot tell
// one keyring from another, and cannot learn how many roots or chats it covers
// beyond what the ciphertext length implies. MK never reaches the server; it is
// recoverable only from the user's password or recovery key, exactly as the
// existing vault already requires.
//
// Deliberately absent: roots, root versions, chat ids, message ids, device ids,
// MK, passwords, recovery keys. Adding any of them would defeat the point.
//
// LIFETIME. Owned by the user and removed with them:
//
//	user_id REFERENCES users(id) ON DELETE CASCADE
//
// There is no chat, message, or device foreign key - the keyring spans all of a
// user's chats and is not tied to any single device.
//
// MK STABILITY. A password change preserves MK (the change flow unlocks with the
// old password and re-wraps the SAME master key), so the blob stays valid across
// password changes. A deliberate E2EE reset mints a fresh MK, which makes the
// stored blob cryptographically unopenable - the invalidation is a property of
// the crypto, not of any cleanup code. See the note on the reset path in the
// Gate 5 report.
type HistoryKeyringRecovery struct {
	// UserID is both owner and primary key: exactly one recovery object per user.
	UserID uuid.UUID `json:"user_id" gorm:"type:uuid;primaryKey"`
	// Ciphertext is nonce||ciphertext||tag, opaque to the server. json:"-" so it
	// can never be echoed back by an accidental struct serialisation.
	Ciphertext []byte `json:"-" gorm:"type:bytea;not null"`
	// Version supports optimistic concurrency. Unlike a message archive - which
	// is immutable - a keyring legitimately grows, so replacement must be allowed
	// but never blind: a writer must present the version it believes is current.
	Version   int       `json:"version" gorm:"not null;default:1"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (HistoryKeyringRecovery) TableName() string { return "history_keyring_recoveries" }
