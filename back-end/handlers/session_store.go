package handlers

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"gorm.io/gorm"

	"messenger-app/config"
	"messenger-app/models"
)

// Gate 17 session store.
//
// PROTOCOL DEPENDENCY: READ COMMITTED.
//
// The rotation CAS relies on PostgreSQL's READ COMMITTED behaviour: when a
// concurrent writer commits first, a blocked UPDATE re-evaluates its WHERE
// clause against the updated tuple (EvalPlanQual) and matches nothing. That is
// what makes exactly one rotation possible per token. Under REPEATABLE READ or
// SERIALIZABLE the loser would instead raise a serialization failure (40001),
// which this code does not expect and does not retry. Do not change the
// isolation level without rewriting the classifier.
//
// The re-check only covers the row the statement UPDATEs. A joined table is
// read from the original snapshot and is NOT re-checked - which is precisely
// why refresh_hash and revoked_at live on the same row. A design that kept
// tokens in a separate table and merely joined sessions would let a revocation
// committing mid-flight go unseen.

const (
	// responseCacheTTL is the lost-response window: how long after a successful
	// rotation the same request may be retried and receive the same successor.
	//
	// Deliberately seconds, not minutes, and never tied to session liveness.
	// The event it covers is a dropped HTTP response, which resolves in seconds;
	// a session lives for days. Binding it to liveness would let a token
	// consumed days ago still hand back a live credential AND suppress the reuse
	// alarm at the same time - the alarm would be serving the attacker.
	responseCacheTTL = 60 * time.Second

	// reuseWindowSlack keeps fork evidence alive strictly longer than any token
	// that could still be presented. If the ledger row vanished first, a
	// consumed token would look merely unknown and its replay would go
	// undetected.
	reuseWindowSlack = 24 * time.Hour
)

// errNoSessionRow is returned when the CAS matches nothing. It carries no
// detail on purpose: the caller must not be able to tell an unknown token from
// an expired, consumed or revoked one.
var errNoSessionRow = errors.New("gate17: refresh CAS matched no row")

// refreshOutcome is what the classifier decided after a failed CAS.
type refreshOutcome int

const (
	// refreshReject is the uniform failure. Externally always a bare 401.
	refreshReject refreshOutcome = iota
	// refreshReplay is a legitimate lost-response retry: the successor is
	// returned again and no new generation is created.
	refreshReplay
	// refreshFork is evidence of a real fork - the successor was itself already
	// consumed - so the session is revoked.
	refreshFork
	// refreshLedgerFault is an impossible state: the CAS and the ledger
	// disagree. It is an invariant failure, not an attack, and must never be
	// treated as reuse.
	refreshLedgerFault
)

// createSession inserts a new session and returns its id plus the refresh
// plaintext, which the caller returns to the client and then forgets.
//
// deviceID is nullable and is an association only. No authentication path knows
// a trusted device (Gate 11 is unresolved), so most sessions carry NULL here.
// recoveryAuthorityTTL is how long an account-recovery marker stays usable.
//
// Short on purpose. It only has to cover "I just finished a second factor" ->
// "my client generated a key and answered a challenge", which is two round trips,
// not a working session. Anything longer turns a momentary proof into a standing
// permission to mint device identities.
const recoveryAuthorityTTL = 10 * time.Minute

// consumeRecoveryAuthority spends this session's account-recovery marker, and
// reports whether there was one to spend.
//
// The decision is ONE conditional UPDATE, for the same reason rotateRefresh is:
// reading the marker and then deciding in Go is a read-modify-write that two
// concurrent enrolments can both win. Every precondition - right session, right
// account, session still live, marker present, unexpired, unspent - is a
// predicate on the write, so RowsAffected is the answer.
//
// Callers MUST run this inside the transaction that performs the enrolment. That
// is what makes a failed enrolment leave the marker unspent: the rollback takes
// the consumption with it.
func consumeRecoveryAuthority(tx *gorm.DB, sessionID, userID uuid.UUID, now time.Time) (bool, error) {
	res := tx.Model(&models.Session{}).
		Where(`id = ? AND user_id = ? AND revoked_at IS NULL
		       AND recovery_authority_expires_at IS NOT NULL
		       AND recovery_authority_expires_at > ?
		       AND recovery_authority_consumed_at IS NULL`,
			sessionID, userID, now).
		Updates(map[string]interface{}{
			"recovery_authority_consumed_at": now,
			"updated_at":                     now,
		})
	if res.Error != nil {
		return false, res.Error
	}
	return res.RowsAffected == 1, nil
}

// createSession mints a session row and its refresh credential.
//
// recoveryUntil carries ACCOUNT-RECOVERY AUTHORITY and is nil on every path
// except a verified second factor. It is a parameter rather than something the
// caller patches in afterwards so the marker and the session are one write: there
// is no committed instant in which a recovery session exists without its marker,
// or a marker exists on a session that failed to be created.
func createSession(tx *gorm.DB, cfg *config.Config, userID uuid.UUID, deviceID *uuid.UUID, recoveryUntil *time.Time) (uuid.UUID, string, error) {
	plaintext, hash, err := mintRefreshToken(cfg)
	if err != nil {
		return uuid.Nil, "", err
	}
	sessionID := uuid.New()
	now := time.Now()
	s := models.Session{
		ID:                sessionID,
		UserID:            userID,
		DeviceID:          deviceID,
		RefreshHash:       hash,
		HashKeyVersion:    int16(cfg.RefreshHashKeyVersion),
		Generation:        0,
		RefreshExpiresAt:  now.Add(time.Duration(cfg.RefreshTokenExpiration) * time.Hour),
		AbsoluteExpiresAt: now.Add(time.Duration(cfg.RefreshTokenExpiration) * time.Hour * 4),
		CreatedAt:         now,
		UpdatedAt:         now,
		LastUsedAt:        now,

		RecoveryAuthorityExpiresAt: recoveryUntil,
	}
	if err := tx.Create(&s).Error; err != nil {
		return uuid.Nil, "", err
	}
	return sessionID, plaintext, nil
}

// rotateRefresh performs the whole rotation decision in ONE statement.
//
// The compare-and-swap and the ledger insert are a single data-modifying CTE,
// so there is no committed state in which a session rotated but its consumption
// went unrecorded, or vice versa. Success is the presence of a RETURNING row -
// never RowsAffected, which Row() reports as -1 and which would make any ==0
// guard dead code.
//
// Ordering is deliberate: CAS first, ledger second. The ledger insert consumes
// the CAS's output, so if the CAS matches nothing the insert has no input and
// silently writes nothing, which is exactly the desired no-op.
func rotateRefresh(tx *gorm.DB, cfg *config.Config, sessionID uuid.UUID, oldHash, newHash []byte,
	requestID uuid.UUID, cacheCipher, cacheNonce []byte) (generation int, err error) {

	const stmt = `
WITH rotated AS (
    UPDATE sessions
       SET refresh_hash       = @new_hash,
           hash_key_version   = @key_version,
           generation         = generation + 1,
           refresh_expires_at = now() + make_interval(secs => @refresh_ttl),
           last_used_at       = clock_timestamp(),
           updated_at         = clock_timestamp()
     WHERE id                  = @session_id
       AND refresh_hash        = @old_hash
       AND revoked_at         IS NULL
       AND refresh_expires_at  > now()
       AND absolute_expires_at > now()
    RETURNING id, generation
)
INSERT INTO consumed_refresh (
    consumed_hash, session_id, request_id, next_refresh_hash,
    response_ciphertext, response_nonce,
    response_expires_at, reuse_window_expires_at, consumed_at)
SELECT @old_hash, r.id, @request_id, @new_hash,
       @cache_cipher, @cache_nonce,
       clock_timestamp() + make_interval(secs => @cache_ttl),
       clock_timestamp() + make_interval(secs => @reuse_ttl),
       clock_timestamp()
  FROM rotated r
RETURNING (SELECT generation FROM rotated)`

	err = tx.Raw(stmt,
		sql.Named("new_hash", newHash),
		sql.Named("key_version", cfg.RefreshHashKeyVersion),
		sql.Named("refresh_ttl", float64(cfg.RefreshTokenExpiration)*3600),
		sql.Named("session_id", sessionID),
		sql.Named("old_hash", oldHash),
		sql.Named("request_id", requestID),
		sql.Named("cache_cipher", cacheCipher),
		sql.Named("cache_nonce", cacheNonce),
		sql.Named("cache_ttl", responseCacheTTL.Seconds()),
		sql.Named("reuse_ttl", (time.Duration(cfg.RefreshTokenExpiration)*time.Hour+reuseWindowSlack).Seconds()),
	).Row().Scan(&generation)

	if errors.Is(err, sql.ErrNoRows) {
		return 0, errNoSessionRow
	}
	if err != nil {
		return 0, err
	}
	return generation, nil
}

// ledgerVerdict is the classifier's read of a consumed hash.
type ledgerVerdict struct {
	Found             bool
	SessionID         uuid.UUID
	UserID            uuid.UUID
	StoredRequestID   uuid.UUID
	SessionLive       bool
	SuccessorConsumed bool
	CacheLive         bool
	Ciphertext        []byte
	Nonce             []byte
}

// classifyFailedCAS decides what a failed rotation actually was.
//
// Semantic order matters and is enforced here: ledger lookup, then session
// liveness, then request identity, then the cache. Revocation dominates
// idempotency - a revoked session never receives cached credentials, however
// well-formed the retry.
//
// Fork evidence is the successor having been consumed, NOT the mere
// re-presentation of a consumed token. Clients legitimately retry: the Windows
// client deliberately keeps its refresh token on a network error and tries
// again, and a commit can succeed server-side while the response is lost. Bare
// re-presentation is therefore ordinary, and treating it as an attack would
// revoke honest users.
func classifyFailedCAS(tx *gorm.DB, presentedHash []byte) (ledgerVerdict, error) {
	var v ledgerVerdict
	const q = `
SELECT cr.session_id,
       s.user_id,
       cr.request_id,
       (s.revoked_at IS NULL
          AND s.refresh_expires_at  > now()
          AND s.absolute_expires_at > now())                    AS session_live,
       EXISTS (SELECT 1 FROM consumed_refresh f
                WHERE f.consumed_hash = cr.next_refresh_hash)   AS successor_consumed,
       (cr.response_expires_at > now())                         AS cache_live,
       cr.response_ciphertext,
       cr.response_nonce
  FROM consumed_refresh cr
  JOIN sessions s ON s.id = cr.session_id
 WHERE cr.consumed_hash = ?`

	row := tx.Raw(q, presentedHash).Row()
	err := row.Scan(&v.SessionID, &v.UserID, &v.StoredRequestID, &v.SessionLive,
		&v.SuccessorConsumed, &v.CacheLive, &v.Ciphertext, &v.Nonce)
	if errors.Is(err, sql.ErrNoRows) {
		// Unattributable hash. Return 401 and revoke NOTHING: turning arbitrary
		// invalid input into a session-kill primitive would hand any attacker a
		// remote logout for free.
		return ledgerVerdict{Found: false}, nil
	}
	if err != nil {
		return v, err
	}
	v.Found = true
	return v, nil
}

// revokeSessions is the single transactional revocation primitive.
//
// Every revocation-bearing event routes through here, in the same transaction
// as the event that caused it. That is what makes the CAS see it: refresh and
// revocation contend for the same session row, so PostgreSQL serialises them
// and the loser re-reads the winner's committed state. Reading
// users.password_changed_at or devices.revoked_at from the refresh path instead
// would be write skew - two transactions each reading state the other is about
// to change.
//
// The response cache is purged in the same statement sequence. Merely testing
// liveness at read time would leave decryptable successor material sitting in
// the ledger after a logout.
func revokeSessions(tx *gorm.DB, reason string, where string, args ...interface{}) (int64, error) {
	// COALESCE preserves the first revocation's timestamp and reason; the
	// database trigger rejects any attempt to move them anyway, and the
	// revoked_at IS NULL guard means this only ever touches live rows.
	upd := `UPDATE sessions
	           SET revoked_at    = COALESCE(revoked_at, clock_timestamp()),
	               revoke_reason = COALESCE(revoke_reason, ?),
	               refresh_hash  = NULL,
	               updated_at    = clock_timestamp()
	         WHERE ` + where + ` AND revoked_at IS NULL`
	res := tx.Exec(upd, append([]interface{}{reason}, args...)...)
	if res.Error != nil {
		return 0, res.Error
	}

	purge := `UPDATE consumed_refresh
	             SET response_ciphertext = NULL, response_nonce = NULL
	           WHERE response_ciphertext IS NOT NULL
	             AND session_id IN (SELECT id FROM sessions WHERE ` + where + `)`
	if err := tx.Exec(purge, args...).Error; err != nil {
		return 0, err
	}
	return res.RowsAffected, nil
}

// revokeSessionsForUser invalidates every live session an account has.
func revokeSessionsForUser(tx *gorm.DB, userID uuid.UUID, reason string) (int64, error) {
	return revokeSessions(tx, reason, "user_id = ?", userID)
}

// revokeSessionsForDevice invalidates the sessions associated with one device.
//
// Scoped strictly to device_id = $1. It deliberately does NOT sweep
// device_id IS NULL sessions: those belong to logins that never had a device
// association, and revoking one device must not sign out unrelated sessions.
// The inverse - an authorization predicate reading "device_id = $1 OR device_id
// IS NULL" - would be a bypass, and appears nowhere.
func revokeSessionsForDevice(tx *gorm.DB, userID, deviceID uuid.UUID, reason string) (int64, error) {
	return revokeSessions(tx, reason, "user_id = ? AND device_id = ?", userID, deviceID)
}

// revokeOneSession invalidates a single session, checked against its owner.
func revokeOneSession(tx *gorm.DB, userID, sessionID uuid.UUID, reason string) (int64, error) {
	return revokeSessions(tx, reason, "id = ? AND user_id = ?", sessionID, userID)
}

// liveSession resolves an access token's sid.
//
// The lookup is CONJUNCTIVE on (id, user_id). Resolving by sid alone would let
// a token naming another account's session authenticate as that account - a
// silent account switch.
func liveSession(db *gorm.DB, sessionID, userID uuid.UUID) (bool, error) {
	var n int64
	err := db.Raw(`SELECT count(*) FROM sessions
	                WHERE id = ? AND user_id = ?
	                  AND revoked_at IS NULL
	                  AND absolute_expires_at > now()`, sessionID, userID).Scan(&n).Error
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// purgeExpiredResponseCache drops successor material once its window closes,
// while leaving the ledger row itself in place: the row is fork evidence and
// must outlive the cache it carried.
func purgeExpiredResponseCache(db *gorm.DB, limit int) (int64, error) {
	res := db.Exec(`UPDATE consumed_refresh
	                   SET response_ciphertext = NULL, response_nonce = NULL
	                 WHERE consumed_hash IN (
	                       SELECT consumed_hash FROM consumed_refresh
	                        WHERE response_ciphertext IS NOT NULL
	                          AND response_expires_at < now()
	                        LIMIT ?)`, limit)
	return res.RowsAffected, res.Error
}

// purgeExpiredLedger removes ledger rows only once no token they describe could
// still be presented.
func purgeExpiredLedger(db *gorm.DB, limit int) (int64, error) {
	res := db.Exec(`DELETE FROM consumed_refresh
	                 WHERE consumed_hash IN (
	                       SELECT consumed_hash FROM consumed_refresh
	                        WHERE reuse_window_expires_at < now()
	                        LIMIT ?)`, limit)
	return res.RowsAffected, res.Error
}

// constraintViolation returns the violated constraint's name, if err is a
// PostgreSQL integrity violation.
//
// Classification is by ConstraintName off the typed driver error, never by
// matching message text - message wording is not an API. This depends on GORM's
// TranslateError staying OFF: the postgres driver's translator returns an
// unwrapped sentinel, which would erase ConstraintName and collapse distinct
// constraints (say, the ledger primary key and the session refresh_hash unique
// index) into a single indistinguishable error.
func constraintViolation(err error) (string, bool) {
	if err == nil {
		return "", false
	}
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		return "", false
	}
	return pgErr.ConstraintName, pgErr.ConstraintName != ""
}

// ledgerFault wraps the impossible case: the CAS succeeded but the ledger
// insert collided.
//
// In correct operation this is unreachable - a consumed hash fails the CAS
// first, so the ledger insert receives an empty input set and writes nothing.
// If it ever fires, the CAS and the ledger have diverged. That is an invariant
// failure to be alarmed on and surfaced as a 500; it must NEVER be read as
// attacker reuse, because doing so would let internal drift trigger mass
// revocation of innocent sessions.
func ledgerFault(err error) error {
	return fmt.Errorf("gate17 invariant failure: CAS succeeded but ledger insert collided: %w", err)
}
