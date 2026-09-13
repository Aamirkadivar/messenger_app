package models

import (
	"time"

	"github.com/google/uuid"
)

// Session is the server-side half of an authenticated login.
//
// Before Gate 17 there was no such thing: access and refresh credentials were
// stateless JWTs, so nothing on the server could withdraw one. Every property
// Gate 17 needs - single-use refresh, replay detection, logout, revocation that
// actually reaches the credential - requires a durable row, and this is it.
//
// The refresh hash lives HERE, on the session row, rather than in a separate
// refresh_tokens table. That is a security decision, not a modelling
// convenience. The rotation CAS is a conditional UPDATE whose predicate reads
// both the hash and revoked_at; under READ COMMITTED, PostgreSQL re-evaluates
// that predicate against the updated tuple (EvalPlanQual) when a concurrent
// writer commits first. A joined table gets no such re-check - the join side is
// read from the original statement snapshot - so a revocation committing
// mid-flight would be invisible to a rotation that merely joined sessions. Two
// tables would reintroduce exactly the write skew Gate 17 exists to remove.
type Session struct {
	ID     uuid.UUID `json:"id" gorm:"type:uuid;primaryKey"`
	UserID uuid.UUID `json:"user_id" gorm:"type:uuid;not null;index"`

	// DeviceID is an ASSOCIATION, never an identity. It is nullable because no
	// authentication path knows a device: login, both 2FA branches and QR claim
	// all mint credentials without ever reading X-Device-Id. Making it NOT NULL
	// would force login to manufacture a device row from a self-asserted header,
	// which is precisely the trust Gate 11 proved absent. Sessions with a NULL
	// device are outside device-scoped revocation, by design and on the record.
	DeviceID *uuid.UUID `json:"device_id" gorm:"type:uuid;index"`

	// RefreshHash is HMAC-SHA256(pepper, refresh token). The plaintext is never
	// stored and never reaches SQL. NULL means the session holds no usable
	// refresh credential - either it was revoked, or its token was consumed and
	// the successor has not been written yet (never observable outside a
	// transaction).
	RefreshHash    []byte `json:"-" gorm:"type:bytea;uniqueIndex"`
	HashKeyVersion int16  `json:"-" gorm:"not null;default:1"`

	// Generation is the monotonic rotation counter. It only ever increases, and
	// only inside the CAS, so it doubles as evidence of how many times this
	// session's credential has legitimately turned over.
	Generation int `json:"generation" gorm:"not null;default:0"`

	RefreshExpiresAt time.Time `json:"refresh_expires_at" gorm:"not null"`

	// AbsoluteExpiresAt is never advanced by rotation. Without it a stolen
	// refresh token that keeps rotating ahead of the legitimate client owns the
	// account forever; with it, the session dies on schedule no matter how
	// diligently it is refreshed.
	AbsoluteExpiresAt time.Time `json:"absolute_expires_at" gorm:"not null"`

	// RevokedAt is write-once. A revoked session is terminal: no code path may
	// clear it, and a database trigger enforces that rather than trusting every
	// future handler to remember.
	RevokedAt    *time.Time `json:"revoked_at"`
	RevokeReason *string    `json:"revoke_reason" gorm:"size:32"`

	// RecoveryAuthorityExpiresAt is the ACCOUNT-RECOVERY marker: non-NULL and in
	// the future means this session was authenticated with password AND a
	// verified second factor, and may therefore bootstrap a new device identity.
	//
	// It lives here rather than in the JWT for three reasons. A claim cannot be
	// withdrawn before its own expiry, which is the property Gate 17 removed
	// refresh JWTs to obtain. A claim cannot be consumed. And a claim travels
	// with whoever holds the token, whereas this marker is pinned to one session
	// row owned by one account, so it cannot be transferred.
	//
	// NULL is the ordinary state. Password-only login, dev-OTP login and QR claim
	// all leave it NULL: they are authentication, not recovery authority.
	RecoveryAuthorityExpiresAt *time.Time `json:"-"`

	// RecoveryAuthorityConsumedAt makes the marker SINGLE-USE. It is set by the
	// same conditional UPDATE that reads it, inside device registration's
	// transaction, so two concurrent enrolments cannot both spend one marker and
	// a rolled-back enrolment does not spend it at all.
	RecoveryAuthorityConsumedAt *time.Time `json:"-"`

	CreatedAt  time.Time `json:"created_at" gorm:"not null"`
	UpdatedAt  time.Time `json:"updated_at" gorm:"not null"`
	LastUsedAt time.Time `json:"last_used_at" gorm:"not null"`
}

func (Session) TableName() string { return "sessions" }

// ConsumedRefresh is the durable record that a refresh token was spent.
//
// It is not bookkeeping. It carries NextRefreshHash - the successor's identity -
// and that is the only way to tell a lost-response retry from a fork. A client
// that never received its response replays the same token while the successor
// is still unconsumed; an attacker replays a token whose successor has already
// been spent. Without the successor identity both look identical, and the
// system would have to choose between punishing honest retries or ignoring real
// compromise.
//
// The primary key is consumed_hash alone. A composite key would permit several
// consumption rows for one token, which is the exact representation Gate 17
// forbids. Note the CAS - not this key - is what makes double-consumption
// impossible; the key is a structural backstop, and a collision on it means the
// CAS and the ledger have disagreed, which is an invariant failure rather than
// an attack.
type ConsumedRefresh struct {
	ConsumedHash []byte    `json:"-" gorm:"type:bytea;primaryKey"`
	SessionID    uuid.UUID `json:"session_id" gorm:"type:uuid;not null;index"`

	// RequestID is client-generated and is meaningful ONLY relative to
	// consumed_hash. It carries no uniqueness at any scope: the same value may
	// legitimately appear across different users, sessions and tokens. It is
	// compared after lookup, never used to find the row.
	RequestID uuid.UUID `json:"request_id" gorm:"type:uuid;not null"`

	NextRefreshHash []byte `json:"-" gorm:"type:bytea;not null"`

	// The response cache holds ONLY the successor refresh plaintext, sealed with
	// AES-256-GCM. No access token is ever stored: replay re-mints one instead,
	// so the database never holds a credential that is directly usable against
	// the API. AAD binds consumed_hash and session_id, so a row moved elsewhere
	// by a database-write attacker fails to open.
	ResponseCiphertext []byte `json:"-" gorm:"type:bytea"`
	ResponseNonce      []byte `json:"-" gorm:"type:bytea"`

	// Two independent clocks. ResponseExpiresAt is the lost-response window and
	// is deliberately tiny - the network event it covers lasts seconds, whereas
	// a session lives for days. Gating the cache on session liveness instead
	// would let a token consumed days ago still hand back live credentials and
	// suppress the reuse alarm at the same time.
	ResponseExpiresAt time.Time `json:"-" gorm:"not null;index"`
	// ReuseWindowExpiresAt outlives any token that could still be presented, so
	// fork detection never loses the evidence it needs while a consumed token
	// remains within its own validity horizon.
	ReuseWindowExpiresAt time.Time `json:"-" gorm:"not null;index"`

	ConsumedAt time.Time `json:"consumed_at" gorm:"not null"`
}

func (ConsumedRefresh) TableName() string { return "consumed_refresh" }
