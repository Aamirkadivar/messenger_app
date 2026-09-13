package handlers

import (
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"messenger-app/database"
	"messenger-app/e2ee"
	"messenger-app/middleware"
	"messenger-app/models"
)

// errDeviceProof is any failure to prove possession. It is deliberately ONE
// error for every cause - unknown challenge, wrong account, expired, already
// spent, wrong key, wrong answer - so the response cannot be used to probe which
// part was wrong.
var errDeviceProof = errors.New("device proof failed")

// errSessionBound is a session trying to adopt a SECOND device identity. One
// session authenticates one device; allowing a swap would let a caller move an
// authorization it already holds onto a freshly minted identity, which is the
// revocation evasion this phase exists to close.
var errSessionBound = errors.New("session already bound to another device")

// errLegacyRetired is an enrolment refused because the account has retired the
// legacy account-wide authority and this attempt does not carry per-device
// authority earned through account recovery.
var errLegacyRetired = errors.New("legacy enrolment authority retired")

// deviceChallengeBody requests a proof-of-possession challenge.
type deviceChallengeBody struct {
	DeviceID  string `json:"device_id"`
	PublicKey string `json:"public_key"`
}

// deviceRegisterBody completes registration by answering a challenge.
//
// Registration REQUIRES the two proof fields; a body without them is not a
// registration attempt at all, which is why they are part of the type rather
// than optional extras on the old shape.
type deviceRegisterBody struct {
	// AuthorityKind is what the CLIENT says it is presenting: "device" for a
	// keypair minted for this device alone, anything else (including absent) for
	// the legacy account-wide key. It is a declaration, not a proof, and it is
	// never trusted on its own - a row is recorded as per-device authority only
	// when the declaration AND a spent account-recovery marker agree. Its job is
	// to stop an unchanged legacy client from being mislabelled as modern merely
	// because its user happened to log in with a second factor.
	AuthorityKind string `json:"authority_kind"`

	DeviceID    string `json:"device_id"`
	Name        string `json:"name"`
	Platform    string `json:"platform"`
	PublicKey   string `json:"public_key"`
	ChallengeID string `json:"challenge_id"`
	ProofB64    string `json:"proof_b64"`
}

// CreateDeviceChallenge POST /e2ee/devices/challenge
//
// Step one of device registration: the server seals random bytes TO the public
// key the caller claims to hold, and remembers the answer. Only a holder of the
// matching private key can complete step two.
//
// The challenge is bound to (account, device_id, public_key), and all three
// matter. Without the account binding, B could spend a challenge issued to A.
// Without the key binding, a proof obtained for a key the caller controls could
// be redeemed to register a DIFFERENT key under the same device identity.
//
// Issuing a challenge grants nothing and creates no device row, so probing this
// endpoint with a thousand invented ids leaves the device registry untouched.
func (h *E2EEHandler) CreateDeviceChallenge(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "unauthorized"})
	}

	var body deviceChallengeBody
	if err := c.BodyParser(&body); err != nil || body.DeviceID == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "device_id required"})
	}
	if len(body.DeviceID) > 128 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "device_id too long"})
	}
	pub, err := hex.DecodeString(strings.TrimSpace(body.PublicKey))
	if err != nil || len(pub) != 32 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "public_key must be 32 bytes of hex",
		})
	}
	normalizedPub := hex.EncodeToString(pub)

	// An existing identity may not be re-keyed. Whoever holds the registered key
	// keeps the identity; a caller wanting a different key must use a different
	// device_id. Without this, an attacker holding only a token could request a
	// challenge for their OWN key under the victim device's id and then
	// legitimately answer it.
	existing, found, lookupErr := models.LookupE2EEDevice(database.DB, userID, body.DeviceID)
	if lookupErr != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "internal error"})
	}
	if found {
		if existing.RevokedAt != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "device revoked"})
		}
		if existing.PublicKey != "" && !strings.EqualFold(existing.PublicKey, normalizedPub) {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device key mismatch",
				"message": "This device identity is already bound to a different public key",
			})
		}
	}

	challenge, err := e2ee.NewDeviceChallenge()
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "internal error"})
	}
	senderPub, sealed, err := e2ee.SealDeviceChallenge(pub, challenge)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "internal error"})
	}

	now := time.Now()
	row := models.E2EEDeviceChallenge{
		ID:        uuid.New(),
		UserID:    userID,
		DeviceID:  body.DeviceID,
		PublicKey: normalizedPub,
		Expected:  challenge,
		ExpiresAt: now.Add(time.Duration(e2ee.DeviceChallengeTTLSeconds) * time.Second),
		CreatedAt: now,
	}
	if err := database.DB.Create(&row).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "internal error"})
	}

	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"challenge_id":   row.ID,
		"sender_pub_hex": hex.EncodeToString(senderPub),
		"sealed_b64":     b64(sealed),
		"expires_at":     row.ExpiresAt,
	})
}

// RegisterDevice POST /e2ee/devices
//
// Step two, and the ONLY way an E2EEDevice is created or a session becomes
// device-bound.
//
// Before Phase 44 this endpoint trusted the caller completely: a valid account
// token plus an arbitrary device_id and public_key produced a trusted device.
// That is the bypass Phase 43 demonstrated, and it was independent of the
// auto-registration in DeviceRevocationGuard - so closing one without the other
// would have changed nothing.
//
// Now the caller must return the plaintext of a challenge sealed to the key it
// is registering. The comparison is constant-time, the challenge is consumed
// under a row lock so it cannot be replayed, and every binding on it is
// re-checked here rather than trusted from step one.
//
// On success the CURRENT session is bound to the device, in the same
// transaction. That binding is what finally makes revocation bite: revoking a
// device revokes its sessions, and the existing session liveness check then
// rejects the access token itself.
func (h *E2EEHandler) RegisterDevice(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	sessionID := middleware.GetCurrentSessionID(c)
	if userID == uuid.Nil || sessionID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "unauthorized"})
	}

	var body deviceRegisterBody
	if err := c.BodyParser(&body); err != nil || body.DeviceID == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "device_id required"})
	}
	challengeID, err := uuid.Parse(strings.TrimSpace(body.ChallengeID))
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "challenge required",
			"message": "Obtain a challenge from POST /e2ee/devices/challenge first",
		})
	}
	proof, err := unb64(body.ProofB64)
	if err != nil || len(proof) != e2ee.DeviceChallengeBytes {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid proof_b64"})
	}

	now := time.Now()
	var (
		device   models.E2EEDevice
		created  bool
		revoked  bool
		badProof bool
		bound    bool
		retired  bool
	)
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		// Consume the challenge FIRST, under a lock. A concurrent redeemer waits
		// here and then observes consumed_at already set, so one challenge yields
		// at most one registration.
		var ch models.E2EEDeviceChallenge
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("id = ? AND user_id = ?", challengeID, userID).
			First(&ch).Error; err != nil {
			badProof = true
			return errDeviceProof
		}
		// Every binding re-verified at redemption rather than assumed from
		// issuance: same device identity, same key, unspent, unexpired.
		if ch.ConsumedAt != nil || now.After(ch.ExpiresAt) ||
			ch.DeviceID != body.DeviceID ||
			!strings.EqualFold(ch.PublicKey, strings.TrimSpace(body.PublicKey)) {
			badProof = true
			return errDeviceProof
		}
		if subtle.ConstantTimeCompare(ch.Expected, proof) != 1 {
			badProof = true
			return errDeviceProof
		}
		// Single-use enforced by the WRITE, not by the read above.
		spent := tx.Model(&models.E2EEDeviceChallenge{}).
			Where("id = ? AND consumed_at IS NULL", ch.ID).
			Update("consumed_at", now)
		if spent.Error != nil {
			return spent.Error
		}
		if spent.RowsAffected == 0 {
			badProof = true
			return errDeviceProof
		}

		// The session is read HERE rather than after the device write, so a dead
		// session fails the enrolment before any device row exists. It is also
		// what tells a genuine new enrolment apart from the idempotent
		// re-registration both clients perform on every connect.
		var sess models.Session
		if err := tx.Where("id = ? AND user_id = ? AND revoked_at IS NULL", sessionID, userID).
			First(&sess).Error; err != nil {
			badProof = true
			return errDeviceProof
		}

		var existing models.E2EEDevice
		lookup := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("user_id = ? AND device_id = ?", userID, body.DeviceID).
			First(&existing).Error

		// A refresh is this session re-proving the device it is ALREADY bound to.
		// It grants no new authority, so it must not spend a recovery marker and
		// must not be refused by retirement - otherwise every reconnect of an
		// already-authorized device would fail.
		isRefresh := lookup == nil && sess.DeviceID != nil && *sess.DeviceID == existing.ID

		authorityKind := models.AuthorityLegacy
		if !isRefresh {
			// Account lifecycle under the same lock discipline as everything else
			// here: a retirement that committed before this lock was granted is
			// visible, and one that commits after waits behind this transaction.
			var acct models.E2EEAccountAuthority
			accErr := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
				Where("user_id = ?", userID).First(&acct).Error
			if accErr != nil && !errors.Is(accErr, gorm.ErrRecordNotFound) {
				return accErr
			}
			isRetired := accErr == nil && acct.LegacyRetiredAt != nil

			// Spend the marker inside THIS transaction. If anything below fails,
			// the rollback un-spends it, which is what makes a failed enrolment
			// non-consuming.
			hasRecovery, err := consumeRecoveryAuthority(tx, sessionID, userID, now)
			if err != nil {
				return err
			}
			if hasRecovery && strings.EqualFold(strings.TrimSpace(body.AuthorityKind), models.AuthorityDevice) {
				authorityKind = models.AuthorityDevice
			}

			if isRetired {
				// After retirement the legacy authority buys nothing: only a
				// freshly recovered per-device identity may enrol.
				if authorityKind != models.AuthorityDevice {
					retired = true
					return errLegacyRetired
				}
				// Phase 70: the DURABLE deny-list is the authority. A retired
				// K_account is refused because it is recorded in
				// e2ee_retired_device_keys, which is written at retirement and
				// which device deletion never touches - so this holds even when
				// every historical device row for the key is gone (Phase 69's
				// deleted-row gap). It is account-scoped and applied whatever the
				// caller declared, so "device" over a retired K_account cannot
				// evade it.
				var deniedDurable int64
				if err := tx.Model(&models.E2EERetiredDeviceKey{}).
					Where("user_id = ? AND lower(public_key) = lower(?)",
						userID, ch.PublicKey).
					Count(&deniedDurable).Error; err != nil {
					return err
				}
				if deniedDurable > 0 {
					retired = true
					return errLegacyRetired
				}
				// Belt and braces: the original row-presence check. It is
				// subsumed by the durable list for any account retired through
				// retireLegacyAuthority, but it costs nothing and keeps the guard
				// correct for any legacy row that predates a durable snapshot.
				var reused int64
				if err := tx.Model(&models.E2EEDevice{}).
					Where("user_id = ? AND authority_kind = ? AND lower(public_key) = lower(?)",
						userID, models.AuthorityLegacy, ch.PublicKey).
					Count(&reused).Error; err != nil {
					return err
				}
				if reused > 0 {
					retired = true
					return errLegacyRetired
				}
			}
		}

		switch {
		case lookup == nil:
			// Authoritative state, re-read while holding the row lock, so a
			// revocation that committed before the lock was granted is visible.
			if existing.RevokedAt != nil {
				revoked = true
				return errDeviceRevoked
			}
			// The key that was proved is the key that stays. Allowing a swap here
			// would let one proof install a different key under the same identity.
			if existing.PublicKey != "" && !strings.EqualFold(existing.PublicKey, ch.PublicKey) {
				badProof = true
				return errDeviceProof
			}
			// Column-scoped update, never a whole-struct Save: this writes only
			// the fields registration owns, so revoked_at cannot be carried back
			// to NULL from an in-memory copy. The redundant revoked_at IS NULL
			// predicate keeps the precondition in the same statement as the write.
			res := tx.Model(&models.E2EEDevice{}).
				Where("id = ? AND revoked_at IS NULL", existing.ID).
				Updates(map[string]interface{}{
					"name":         body.Name,
					"platform":     body.Platform,
					"public_key":   ch.PublicKey,
					"verified_at":  now,
					"last_seen_at": now,
					"updated_at":   now,
				})
			if res.Error != nil {
				return res.Error
			}
			if res.RowsAffected == 0 {
				// Only reachable if the row stopped being active despite the
				// lock; treat exactly as revoked rather than guessing.
				revoked = true
				return errDeviceRevoked
			}
			existing.Name = body.Name
			existing.Platform = body.Platform
			existing.PublicKey = ch.PublicKey
			existing.VerifiedAt = &now
			existing.LastSeenAt = &now
			existing.UpdatedAt = now
			device = existing

		case errors.Is(lookup, gorm.ErrRecordNotFound):
			d := models.E2EEDevice{
				ID:         uuid.New(),
				UserID:     userID,
				DeviceID:   body.DeviceID,
				Name:       body.Name,
				Platform:   body.Platform,
				PublicKey:  ch.PublicKey,
				VerifiedAt: &now,
				LastSeenAt: &now,
				CreatedAt:  now,
				UpdatedAt:  now,

				AuthorityKind: authorityKind,
			}
			if err := tx.Create(&d).Error; err != nil {
				return err
			}
			device = d
			created = true

		default:
			return lookup
		}

		// Bind THIS session to the proven device, inside the transaction that
		// proved it. Scoped to a live session owned by this account: a token
		// naming someone else's session must not move that session's binding.
		if sess.DeviceID != nil {
			if *sess.DeviceID != device.ID {
				// One session, one device. Refused explicitly rather than left to
				// the gate17 trigger, so the caller gets a 403 that says why
				// instead of a 500 from a constraint violation.
				bound = true
				return errSessionBound
			}
			// Already bound to this same device: re-registration is idempotent.
			// Both clients call registration on every connect, and the binding is
			// write-once, so this path must not be an error.
			return nil
		}
		bind := tx.Model(&models.Session{}).
			Where("id = ? AND user_id = ? AND revoked_at IS NULL AND device_id IS NULL",
				sessionID, userID).
			Updates(map[string]interface{}{"device_id": device.ID, "updated_at": now})
		if bind.Error != nil {
			return bind.Error
		}
		if bind.RowsAffected == 0 {
			badProof = true
			return errDeviceProof
		}
		return nil
	})

	if badProof {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "device proof failed",
			"message": "The challenge was invalid, expired, already used, or answered incorrectly",
		})
	}
	if bound {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "session already bound",
			"message": "This session is already bound to a different device; sign in again to enrol another",
		})
	}
	if retired {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "legacy authority retired",
			"message": "This account no longer accepts the account-wide key. Sign in with your password and authenticator, then enrol a device-specific key.",
		})
	}
	if revoked {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "device revoked"})
	}
	if txErr != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to register device"})
	}
	if created {
		return c.Status(http.StatusCreated).JSON(fiber.Map{"device": device})
	}
	return c.JSON(fiber.Map{"device": device})
}

// legacyKeysToRetire collects every public key that represents this account's
// LEGACY authority and must be denied after retirement. Phase 69 proved that
// "authority_kind = 'legacy'" alone is not enough, so this is the union of:
//
//  1. every distinct key on an authority_kind='legacy' device row - K_account by
//     construction; and
//  2. any device row (whatever its authority_kind) whose key equals the account's
//     published identity key users.public_key - i.e. a K_account that landed in a
//     'device' row through pre-retirement mis-declaration or the Phase 69 Race 1
//     window.
//
// It deliberately does NOT include genuine K_device keys: a locally generated
// device key never equals users.public_key and is never recorded as 'legacy', so
// neither clause selects it. Keys are returned lowercased to match the enrolment
// check and the unique index.
func legacyKeysToRetire(tx *gorm.DB, userID uuid.UUID) ([]string, error) {
	set := map[string]struct{}{}

	// The account's published identity key IS K_account, and it lives in
	// users.public_key durably - independent of any device row. Capturing it
	// directly is what makes retirement robust even when every device row has
	// already been deleted (Phase 70 §13 delete-before-retire): the key is still
	// on record on the user. A genuine K_device is generated locally and never
	// equals users.public_key, so this never over-captures a device key.
	var accountKey string
	// COALESCE because the column is nullable and Scan cannot map NULL to string.
	if err := tx.Model(&models.User{}).Select("COALESCE(public_key, '')").
		Where("id = ?", userID).Scan(&accountKey).Error; err != nil {
		return nil, err
	}
	if k := strings.ToLower(strings.TrimSpace(accountKey)); k != "" {
		set[k] = struct{}{}
	}

	// Plus every distinct key still sitting in a legacy device row (K_account by
	// construction) and any device-provenance row that happens to hold the
	// account key (Phase 69 §12b). authority_kind='legacy' OR key==accountKey.
	var rowKeys []string
	q := tx.Model(&models.E2EEDevice{}).
		Distinct("lower(public_key)").
		Where("user_id = ? AND public_key <> ''", userID)
	if strings.TrimSpace(accountKey) != "" {
		q = q.Where("authority_kind = ? OR lower(public_key) = lower(?)",
			models.AuthorityLegacy, accountKey)
	} else {
		q = q.Where("authority_kind = ?", models.AuthorityLegacy)
	}
	if err := q.Pluck("lower(public_key)", &rowKeys).Error; err != nil {
		return nil, err
	}
	for _, k := range rowKeys {
		set[strings.ToLower(k)] = struct{}{}
	}

	keys := make([]string, 0, len(set))
	for k := range set {
		keys = append(keys, k)
	}
	return keys, nil
}

// retireLegacyAuthority moves an account from LEGACY_OPEN to LEGACY_RETIRED and,
// in the SAME transaction, snapshots its legacy keys into the durable
// deny-list. Phase 70: setting legacy_retired_at and recording the retired keys
// must succeed or fail together, so there is no committed state in which an
// account is retired but its keys are unrecorded.
//
// Idempotent: the account-authority upsert and the ON CONFLICT DO NOTHING key
// inserts both tolerate a repeat, so two concurrent retirements agree. It takes a
// tx because a rollout will want to retire and record whatever prompted it
// atomically; the whole body runs inside one transaction regardless of whether
// the caller passed a live tx or a plain handle.
//
// There is still NO production route that calls this. Retiring an account is a
// rollout decision with real lock-out consequences, and wiring it up belongs to
// the rollout phase.
func retireLegacyAuthority(tx *gorm.DB, userID uuid.UUID, now time.Time) error {
	return tx.Transaction(func(tx *gorm.DB) error {
		keys, err := legacyKeysToRetire(tx, userID)
		if err != nil {
			return err
		}
		for _, k := range keys {
			if err := tx.Clauses(clause.OnConflict{DoNothing: true}).
				Create(&models.E2EERetiredDeviceKey{
					UserID: userID, PublicKey: k, RetiredAt: now, CreatedAt: now,
				}).Error; err != nil {
				return err
			}
		}
		return tx.Clauses(clause.OnConflict{
			Columns: []clause.Column{{Name: "user_id"}},
			DoUpdates: clause.Assignments(map[string]interface{}{
				"legacy_retired_at": gorm.Expr("COALESCE(e2ee_account_authorities.legacy_retired_at, ?)", now),
				"updated_at":        now,
			}),
		}).Create(&models.E2EEAccountAuthority{
			UserID:          userID,
			LegacyRetiredAt: &now,
			CreatedAt:       now,
			UpdatedAt:       now,
		}).Error
	})
}
