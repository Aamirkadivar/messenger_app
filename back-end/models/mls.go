package models

import (
	"time"

	"github.com/google/uuid"
)

// MLS (RFC 9420) Delivery Service storage.
//
// The server is an UNTRUSTED Delivery Service. Every field holding protocol
// material is opaque bytes: the backend never parses a KeyPackage, never runs
// TreeKEM, never derives a group secret, and cannot read application messages.
// Its only jobs are (a) hand out one-time KeyPackages, (b) relay handshake and
// welcome blobs, and (c) enforce a single total order of commits per group so
// members cannot silently diverge.

// MLSKeyPackage is one publishable, single-use KeyPackage for a device.
//
// Single-use matters: reusing a KeyPackage across two Adds would reuse its init
// key and undermine the forward secrecy of the joins. ClaimedAt/ClaimedBy make
// consumption observable and idempotent.
type MLSKeyPackage struct {
	ID       uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID   uuid.UUID `json:"user_id" gorm:"type:uuid;index;not null"`
	DeviceID string    `json:"device_id" gorm:"size:128;index;not null"`
	// CipherSuite is the RFC 9420 suite id; stored so a claimer can request a
	// matching package without the server interpreting the contents.
	CipherSuite int `json:"cipher_suite" gorm:"not null"`
	// KeyPackageData is the opaque serialized KeyPackage. Public material only.
	KeyPackageData []byte `json:"-" gorm:"type:bytea;not null"`
	// RefHash is a client-computed hash used for dedupe; unique per user.
	RefHash string `json:"ref_hash" gorm:"size:128;uniqueIndex;not null"`
	// StoreID scopes a package to ONE incarnation of the publisher's local MLS
	// store. It is an opaque client-supplied tag: the server compares it for
	// equality and never parses or derives it.
	//
	// A device that replaces its MLS store (a recovery reset, a reinstall, or a
	// switch of client implementation) keeps its device_id but loses the private
	// init keys behind everything it published before. Those rows stay on the
	// server and, without this column, are still handed out - producing a Welcome
	// the recipient cannot open. Scoping claims and counts to the current
	// incarnation makes the abandoned rows inert instead of harmful.
	//
	// Nullable on purpose: rows published before this column existed have no
	// incarnation the server can attribute them to, and guessing one would be
	// worse than leaving them unattributed. NULL means "legacy, unattributed".
	StoreID   *string    `json:"store_id" gorm:"size:128;index"`
	ClaimedAt *time.Time `json:"claimed_at" gorm:"index"`
	ClaimedBy *uuid.UUID `json:"claimed_by" gorm:"type:uuid"`
	CreatedAt time.Time  `json:"created_at"`
}

// MLSGroup is public metadata binding an MLS group to an existing chat.
//
// Epoch is the concurrency fence. A commit is only accepted when it advances
// from the epoch the sender last observed, which is what stops two members
// committing against the same epoch and forking the group.
type MLSGroup struct {
	ID     uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	ChatID string    `json:"chat_id" gorm:"size:64;uniqueIndex;not null"`
	// GroupIDData is the opaque MLS group identifier chosen by the creator.
	GroupIDData []byte    `json:"-" gorm:"type:bytea;not null"`
	CipherSuite int       `json:"cipher_suite" gorm:"not null"`
	Epoch       uint64    `json:"epoch" gorm:"not null;default:0"`
	CreatedBy   uuid.UUID `json:"created_by" gorm:"type:uuid;not null"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`

	// GroupInfoData is the latest published GroupInfo (opaque, signed by a
	// member, carrying the ratchet tree inline). It is what lets a member who
	// lost local state rejoin by external commit — the only recovery path for a
	// group we created ourselves, since no Welcome is ever addressed to the
	// creator and neither BouncyCastle nor mlspp can serialize group state.
	//
	// GroupInfoEpoch is the epoch it describes: joining against a stale
	// GroupInfo produces a commit the server will reject, so clients must check
	// it matches Epoch before using it.
	GroupInfoData  []byte `json:"-" gorm:"type:bytea"`
	GroupInfoEpoch uint64 `json:"group_info_epoch" gorm:"not null;default:0"`
}

// MLSHandshake is a relayed commit/proposal, ordered by epoch per group.
// Members fetch everything after the epoch they have already applied.
type MLSHandshake struct {
	ID     uuid.UUID `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	ChatID string    `json:"chat_id" gorm:"size:64;index:idx_mls_hs_chat_epoch;not null"`
	// Epoch this message moves the group INTO (commit) or targets (proposal).
	Epoch uint64 `json:"epoch" gorm:"index:idx_mls_hs_chat_epoch;not null"`
	// Kind is "commit" or "proposal" — routing only, never parsed as crypto.
	Kind           string    `json:"kind" gorm:"size:16;not null"`
	SenderUserID   uuid.UUID `json:"sender_user_id" gorm:"type:uuid;not null"`
	SenderDeviceID string    `json:"sender_device_id" gorm:"size:128"`
	// Payload is the opaque serialized MLSMessage.
	Payload   []byte    `json:"-" gorm:"type:bytea;not null"`
	CreatedAt time.Time `json:"created_at" gorm:"index"`
}

// MLSWelcome is a Welcome blob addressed to one joining device. Single-use:
// once fetched it is marked consumed so a replayed fetch cannot re-deliver it.
type MLSWelcome struct {
	ID                uuid.UUID  `json:"id" gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	ChatID            string     `json:"chat_id" gorm:"size:64;index;not null"`
	RecipientUserID   uuid.UUID  `json:"recipient_user_id" gorm:"type:uuid;index;not null"`
	RecipientDeviceID string     `json:"recipient_device_id" gorm:"size:128;index"`
	SenderUserID      uuid.UUID  `json:"sender_user_id" gorm:"type:uuid;not null"`
	Epoch             uint64     `json:"epoch" gorm:"not null"`
	Payload           []byte     `json:"-" gorm:"type:bytea;not null"`
	ConsumedAt        *time.Time `json:"consumed_at"`
	CreatedAt         time.Time  `json:"created_at"`
}
