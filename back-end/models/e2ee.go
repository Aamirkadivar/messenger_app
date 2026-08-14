package models

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// E2EEVault stores opaque encrypted vault + key-wrap metadata per user.
// The server never decrypts Ciphertext or Wrapped* fields.
type E2EEVault struct {
	ID              uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID          uuid.UUID `json:"user_id" gorm:"type:uuid;uniqueIndex;not null"`
	VaultVersion    int       `json:"vault_version" gorm:"not null;default:1"`
	ProtocolVersion int       `json:"protocol_version" gorm:"not null;default:1"`
	Suite           string    `json:"suite" gorm:"size:128;not null"`
	// VaultCiphertext is nonce||ciphertext (XChaCha20-Poly1305), base64 in JSON APIs.
	VaultCiphertext []byte `json:"-" gorm:"type:bytea;not null"`
	// Password wrap of E2EE Master Key (opaque).
	PwKDF           string `json:"pw_kdf" gorm:"size:64"`
	PwSalt          []byte `json:"-" gorm:"type:bytea"`
	PwParamsJSON    string `json:"pw_params" gorm:"type:text"`
	PwWrappedMaster []byte `json:"-" gorm:"type:bytea"`
	// Recovery wrap of E2EE Master Key (optional until user confirms recovery key).
	RkKDF           string `json:"rk_kdf" gorm:"size:64"`
	RkSalt          []byte `json:"-" gorm:"type:bytea"`
	RkWrappedMaster []byte `json:"-" gorm:"type:bytea"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

// E2EEDevice is a registered client device (public metadata only).
type E2EEDevice struct {
	ID           uuid.UUID  `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID       uuid.UUID  `json:"user_id" gorm:"type:uuid;uniqueIndex:idx_e2ee_user_device;not null"`
	DeviceID     string     `json:"device_id" gorm:"size:128;not null;uniqueIndex:idx_e2ee_user_device"`
	Name         string     `json:"name" gorm:"size:128"`
	Platform     string     `json:"platform" gorm:"size:32"`
	PublicKey    string     `json:"public_key" gorm:"size:512"`
	RevokedAt    *time.Time `json:"revoked_at"`
	LastSeenAt   *time.Time `json:"last_seen_at"`
	CreatedAt    time.Time  `json:"created_at"`
	UpdatedAt    time.Time  `json:"updated_at"`
}

// LookupE2EEDevice finds a device without treating a miss as a GORM error
// (First() logs "record not found" on every pre-registration API call).
func LookupE2EEDevice(db *gorm.DB, userID uuid.UUID, deviceID string) (E2EEDevice, bool, error) {
	var d E2EEDevice
	tx := db.Where("user_id = ? AND device_id = ?", userID, deviceID).Limit(1).Find(&d)
	if tx.Error != nil {
		return d, false, tx.Error
	}
	return d, tx.RowsAffected > 0, nil
}

// E2EEPairingSession is a short-lived, single-use device-link channel.
// The server stores only opaque ciphertext — never the MK plaintext.
type E2EEPairingSession struct {
	ID              uuid.UUID  `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID          uuid.UUID  `json:"user_id" gorm:"type:uuid;index;not null"`
	SessionID       string     `json:"session_id" gorm:"size:64;uniqueIndex;not null"`
	EphemeralPubHex string     `json:"ephemeral_pub_hex" gorm:"size:128;not null"`
	NewDeviceID     string     `json:"new_device_id" gorm:"size:128"`
	Payload         []byte     `json:"-" gorm:"type:bytea"`
	SenderPubHex    string     `json:"sender_pub_hex" gorm:"size:128"`
	ConsumedAt      *time.Time `json:"consumed_at"`
	ExpiresAt       time.Time  `json:"expires_at" gorm:"index;not null"`
	CreatedAt       time.Time  `json:"created_at"`
}