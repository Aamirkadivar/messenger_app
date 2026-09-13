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
	RkKDF           string    `json:"rk_kdf" gorm:"size:64"`
	RkSalt          []byte    `json:"-" gorm:"type:bytea"`
	RkWrappedMaster []byte    `json:"-" gorm:"type:bytea"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

// E2EEDevice is a registered client device (public metadata only).
type E2EEDevice struct {
	ID        uuid.UUID  `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID    uuid.UUID  `json:"user_id" gorm:"type:uuid;uniqueIndex:idx_e2ee_user_device;not null"`
	DeviceID  string     `json:"device_id" gorm:"size:128;not null;uniqueIndex:idx_e2ee_user_device"`
	Name      string     `json:"name" gorm:"size:128"`
	Platform  string     `json:"platform" gorm:"size:32"`
	PublicKey string     `json:"public_key" gorm:"size:512"`
	RevokedAt *time.Time `json:"revoked_at"`

	// VerifiedAt is when this device last PROVED possession of the private key
	// matching PublicKey, via the device-challenge flow.
	//
	// NULL means unproven, and unproven is not authorized: a device row alone
	// says only that somebody with an account token named this id. Phase 43
	// showed that is worth nothing - an unknown id was auto-registered and
	// immediately read history with it. Device-gated routes therefore require a
	// non-NULL value here, reached only by decrypting a server challenge.
	VerifiedAt *time.Time `json:"verified_at"`

	// AuthorityKind records WHICH authority minted this row, because nothing
	// about the row itself reveals it. Phase 63 established that a legacy
	// account-wide key and a per-device key produce structurally identical rows -
	// same shape, same 32 bytes - so provenance has to be written down at the
	// moment of enrolment or it is gone.
	//
	// It is set from what the enrolment PRESENTED, never inferred afterwards from
	// key equality, device_id, platform or timestamps:
	//
	//	AuthorityDevice - the enrolment spent an account-recovery marker, so the
	//	                  key was minted for this device by a client that had just
	//	                  proved password + second factor.
	//	AuthorityLegacy - everything else, i.e. the account-wide K_account path
	//	                  that predates this phase.
	//
	// Empty is read as AuthorityLegacy so rows written before this column existed
	// keep their real meaning instead of silently becoming trusted.
	AuthorityKind string `json:"authority_kind" gorm:"size:16;not null;default:legacy"`

	LastSeenAt *time.Time `json:"last_seen_at"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
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

// E2EEDeviceChallenge is a one-shot proof-of-possession challenge.
//
// The server seals random bytes TO a candidate device public key and keeps the
// expected plaintext. Only a holder of the matching private key can return it,
// which is what turns a self-asserted device id into a credential.
//
// Every field exists to close a specific substitution:
//   - UserID     binds the challenge to one account, so B cannot spend A's.
//   - DeviceID   binds it to one device identity.
//   - PublicKey  binds it to the exact key it was sealed to, so a proof cannot
//     be redeemed to register a DIFFERENT key.
//   - Expected   is never returned to the client; it is the answer.
//   - ExpiresAt  bounds the window.
//   - ConsumedAt makes it single-use; set under a row lock, never re-set.
type E2EEDeviceChallenge struct {
	ID        uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID    uuid.UUID `json:"user_id" gorm:"type:uuid;index;not null"`
	DeviceID  string    `json:"device_id" gorm:"size:128;not null"`
	PublicKey string    `json:"public_key" gorm:"size:512;not null"`
	// Expected is the plaintext the client must return. Never serialized.
	Expected   []byte     `json:"-" gorm:"type:bytea;not null"`
	ExpiresAt  time.Time  `json:"expires_at" gorm:"not null"`
	ConsumedAt *time.Time `json:"consumed_at"`
	CreatedAt  time.Time  `json:"created_at"`
}

// Device enrolment authority kinds. See E2EEDevice.AuthorityKind.
const (
	AuthorityLegacy = "legacy"
	AuthorityDevice = "device"
)

// E2EEAccountAuthority is the account-level lifecycle of the LEGACY enrolment
// authority.
//
// A missing row means LEGACY_OPEN, which is what every existing account is: the
// account-wide K_account is still accepted for device enrolment, so clients that
// have not shipped per-device keys keep working. Setting LegacyRetiredAt moves
// the account to LEGACY_RETIRED permanently.
//
// It is a separate table rather than a column on users deliberately. users is
// the authentication record; this is E2EE authority state, read under a row lock
// by device registration, and keeping it apart means registration never locks a
// row that login also writes.
//
// This phase implements the STATE and its enforcement. It deliberately ships no
// route that sets it: retiring an account is a rollout decision with real
// lock-out consequences (an account with no second factor cannot earn recovery
// authority, so it cannot enrol after retirement), and Phase 67 was scoped to
// establish the mechanism, not to roll it out.
type E2EEAccountAuthority struct {
	UserID uuid.UUID `json:"user_id" gorm:"type:uuid;primaryKey"`

	// LegacyRetiredAt is write-once in practice: nothing un-retires an account.
	LegacyRetiredAt *time.Time `json:"legacy_retired_at"`

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (E2EEAccountAuthority) TableName() string { return "e2ee_account_authorities" }

// E2EERetiredDeviceKey is the durable, account-scoped record that a particular
// legacy public key (a K_account) has been retired and must never again be
// accepted for device enrolment on this account.
//
// Phase 69 proved why this has to exist separately from e2ee_devices. Retirement
// used to reject a key by counting legacy device ROWS that still held it, so
// deleting the last such row - or leaving the key in a device-provenance row -
// let the same K_account re-enrol and regain full device authority. This table
// is the deletion-independent evidence: it is written once, at retirement, from
// every legacy key the account then holds, and device deletion never touches it.
//
// Public material only. The stored key is the same X25519 public key already
// published to /crypto/public-key; no private key, wrap, or secret is kept. It is
// normalised to lowercase hex on insert so the unique index and the enrolment
// check agree without a functional index.
type E2EERetiredDeviceKey struct {
	ID     uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID uuid.UUID `json:"user_id" gorm:"type:uuid;not null;uniqueIndex:idx_e2ee_retired_key,priority:1"`

	// PublicKey is lowercase hex. The (user_id, public_key) unique index makes a
	// second retirement of the same account idempotent and stops duplicates.
	PublicKey string `json:"public_key" gorm:"size:512;not null;uniqueIndex:idx_e2ee_retired_key,priority:2"`

	// RetiredAt is when the account's legacy authority was retired - the same
	// instant written to E2EEAccountAuthority.LegacyRetiredAt in the same
	// transaction.
	RetiredAt time.Time `json:"retired_at" gorm:"not null"`
	CreatedAt time.Time `json:"created_at"`
}

func (E2EERetiredDeviceKey) TableName() string { return "e2ee_retired_device_keys" }
