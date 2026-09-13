package handlers

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
	"messenger-app/database"
	"messenger-app/e2ee"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"
)

// Single-use state on a pairing session, and the revoked/active state of a
// device, are security preconditions. Checking them in application memory and
// then writing unconditionally lets two callers both pass the check: proven in
// Gate 11.6 as a double-consumed master-key payload and as a registration that
// reset revoked_at to NULL over a committed revocation.
//
// The remedy is the idiom this repository already uses in SubmitCommit,
// ClaimQRLogin and ApproveQRLogin: open a transaction, take the row lock with
// SELECT ... FOR UPDATE, validate while holding it, then write. A caller that
// loses the race blocks on the lock, re-reads the winner's committed state, and
// is rejected deterministically.
//
// These handlers return distinct status codes per rejection reason, which a
// bare error out of the transaction would flatten, so the reason is carried out
// in an outcome value and mapped after the transaction closes.
type pairingOutcome int

const (
	pairingOK pairingOutcome = iota
	pairingNotFound
	pairingConsumed
	pairingExpired
	pairingAlreadyCompleted
	pairingNoPayload
	pairingWriteFailed
)

// errPairingAbort rolls the transaction back and releases the row lock when the
// state check fails. The reason travels in the outcome, not in the error.
var errPairingAbort = errors.New("pairing precondition failed")

// errDeviceRevoked aborts a registration or deletion that found a revoked row
// under lock. Revocation is terminal: no ordinary device operation may move a
// device out of it, and none may erase the row that records it.
var errDeviceRevoked = errors.New("device revoked")

// errDeviceNotFound distinguishes a missing row from a refused one, so a
// deletion attempt on a tombstone is not reported as if nothing was there.
var errDeviceNotFound = errors.New("device not found")

// E2EEHandler serves opaque vault / device APIs. It never decrypts vault bytes.
type E2EEHandler struct {
	hub *websocket.Hub
}

func NewE2EEHandler(hub *websocket.Hub) *E2EEHandler { return &E2EEHandler{hub: hub} }

type vaultPutBody struct {
	VaultVersion       int    `json:"vault_version"`
	ProtocolVersion    int    `json:"protocol_version"`
	Suite              string `json:"suite"`
	VaultCiphertextB64 string `json:"vault_ciphertext_b64"`
	PwKDF              string `json:"pw_kdf"`
	PwSaltB64          string `json:"pw_salt_b64"`
	PwParamsJSON       string `json:"pw_params"`
	PwWrappedMasterB64 string `json:"pw_wrapped_master_b64"`
	RkKDF              string `json:"rk_kdf"`
	RkSaltB64          string `json:"rk_salt_b64"`
	RkWrappedMasterB64 string `json:"rk_wrapped_master_b64"`
	// ExpectedVersion is the version the client last read (optimistic lock).
	// For create, omit or send 0.
	ExpectedVersion int `json:"expected_version"`
}

func b64(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(data)
}

func unb64(s string) ([]byte, error) {
	if s == "" {
		return nil, nil
	}
	return base64.StdEncoding.DecodeString(s)
}

func vaultToJSON(v models.E2EEVault) fiber.Map {
	return fiber.Map{
		"user_id":               v.UserID,
		"vault_version":         v.VaultVersion,
		"protocol_version":      v.ProtocolVersion,
		"suite":                 v.Suite,
		"vault_ciphertext_b64":  b64(v.VaultCiphertext),
		"pw_kdf":                v.PwKDF,
		"pw_salt_b64":           b64(v.PwSalt),
		"pw_params":             v.PwParamsJSON,
		"pw_wrapped_master_b64": b64(v.PwWrappedMaster),
		"rk_kdf":                v.RkKDF,
		"rk_salt_b64":           b64(v.RkSalt),
		"rk_wrapped_master_b64": b64(v.RkWrappedMaster),
		"updated_at":            v.UpdatedAt,
	}
}

// GetVault GET /e2ee/vault
func (h *E2EEHandler) GetVault(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var v models.E2EEVault
	if err := database.DB.Where("user_id = ?", userID).First(&v).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "vault not found"})
	}
	return c.JSON(vaultToJSON(v))
}

// PutVault PUT /e2ee/vault — create or update with optimistic concurrency.
func (h *E2EEHandler) PutVault(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body vaultPutBody
	if err := c.BodyParser(&body); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid body"})
	}
	if body.Suite == "" {
		body.Suite = e2ee.SuiteVaultAEAD
	}
	if body.ProtocolVersion == 0 {
		body.ProtocolVersion = e2ee.ProtocolVersionV1
	}
	if body.VaultVersion < 1 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "vault_version must be >= 1"})
	}
	ct, err := unb64(body.VaultCiphertextB64)
	if err != nil || len(ct) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid vault_ciphertext_b64"})
	}
	pwSalt, err := unb64(body.PwSaltB64)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid pw_salt_b64"})
	}
	pwWrap, err := unb64(body.PwWrappedMasterB64)
	if err != nil || len(pwWrap) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid pw_wrapped_master_b64"})
	}
	rkSalt, _ := unb64(body.RkSaltB64)
	rkWrap, _ := unb64(body.RkWrappedMasterB64)

	var existing models.E2EEVault
	err = database.DB.Where("user_id = ?", userID).First(&existing).Error
	now := time.Now()

	if err != nil {
		// Create
		if body.ExpectedVersion != 0 {
			return c.Status(http.StatusConflict).JSON(fiber.Map{
				"error":            "vault already exists elsewhere",
				"server_version":   0,
				"expected_version": body.ExpectedVersion,
			})
		}
		v := models.E2EEVault{
			ID:              uuid.New(),
			UserID:          userID,
			VaultVersion:    body.VaultVersion,
			ProtocolVersion: body.ProtocolVersion,
			Suite:           body.Suite,
			VaultCiphertext: ct,
			PwKDF:           body.PwKDF,
			PwSalt:          pwSalt,
			PwParamsJSON:    body.PwParamsJSON,
			PwWrappedMaster: pwWrap,
			RkKDF:           body.RkKDF,
			RkSalt:          rkSalt,
			RkWrappedMaster: rkWrap,
			CreatedAt:       now,
			UpdatedAt:       now,
		}
		if v.PwKDF == "" {
			v.PwKDF = e2ee.KDFArgon2id
		}
		if err := database.DB.Create(&v).Error; err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to store vault"})
		}
		return c.Status(http.StatusCreated).JSON(vaultToJSON(v))
	}

	// Update — reject stale
	//
	// MASTER-KEY INVARIANT. This branch replaces vault_ciphertext,
	// pw_wrapped_master, pw_salt and optionally the recovery wrap. The server
	// never sees MK and therefore CANNOT tell a same-MK rewrap (password change,
	// vault re-seal) from a wholesale MK replacement. Every caller that reaches
	// this branch today re-seals under the SAME MK it just unlocked with, which
	// is what keeps the history-keyring recovery blob openable.
	//
	// Any future flow that mints a NEW MK here - an "E2EE reset" is the obvious
	// one - MUST delete the recovery blob before the new MK becomes active, or
	// that account's recovery is stranded forever: the blob cannot be opened
	// under the new MK, and PUT /e2ee/history-keyring with expected_version=0 is
	// create-only, so every later publish conflicts. See DeleteHistoryKeyring.
	// That flow is deliberately NOT implemented here; see the Gate 5 report.
	if body.ExpectedVersion != existing.VaultVersion {
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error":            "stale vault version",
			"server_version":   existing.VaultVersion,
			"expected_version": body.ExpectedVersion,
		})
	}
	if body.VaultVersion <= existing.VaultVersion {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":          "vault_version must increase",
			"server_version": existing.VaultVersion,
		})
	}

	existing.VaultVersion = body.VaultVersion
	existing.ProtocolVersion = body.ProtocolVersion
	existing.Suite = body.Suite
	existing.VaultCiphertext = ct
	existing.PwKDF = body.PwKDF
	existing.PwSalt = pwSalt
	existing.PwParamsJSON = body.PwParamsJSON
	existing.PwWrappedMaster = pwWrap
	if body.RkKDF != "" {
		existing.RkKDF = body.RkKDF
		existing.RkSalt = rkSalt
		existing.RkWrappedMaster = rkWrap
	}
	existing.UpdatedAt = now

	// ATOMIC COMPARE-AND-SET. The version check above is advisory on its own: it
	// reads the row, decides, and then writes in a separate statement, so two
	// writers that both observed the same version both pass it and the second
	// silently overwrites the first. For this row that means a stale device can
	// destroy a newer master-key wrap - the account's real key - while both
	// clients are told they succeeded.
	//
	// Re-stating the version in the WHERE clause makes the decision and the write
	// one statement, so the database arbitrates instead of the handler. This is
	// the same shape PutHistoryKeyring already uses. A row count of zero means
	// somebody else moved the vault in between, which is exactly the conflict the
	// caller is already prepared to handle.
	res := database.DB.Model(&models.E2EEVault{}).
		Where("user_id = ? AND vault_version = ?", userID, body.ExpectedVersion).
		Updates(map[string]interface{}{
			"vault_version":     existing.VaultVersion,
			"protocol_version":  existing.ProtocolVersion,
			"suite":             existing.Suite,
			"vault_ciphertext":  existing.VaultCiphertext,
			"pw_kdf":            existing.PwKDF,
			"pw_salt":           existing.PwSalt,
			"pw_params_json":    existing.PwParamsJSON,
			"pw_wrapped_master": existing.PwWrappedMaster,
			"rk_kdf":            existing.RkKDF,
			"rk_salt":           existing.RkSalt,
			"rk_wrapped_master": existing.RkWrappedMaster,
			"updated_at":        existing.UpdatedAt,
		})
	if res.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to update vault"})
	}
	if res.RowsAffected == 0 {
		var current models.E2EEVault
		serverVersion := 0
		if err := database.DB.Where("user_id = ?", userID).First(&current).Error; err == nil {
			serverVersion = current.VaultVersion
		}
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error":            "stale vault version",
			"server_version":   serverVersion,
			"expected_version": body.ExpectedVersion,
		})
	}
	return c.JSON(vaultToJSON(existing))
}

// GetVaultVersions GET /e2ee/vault/versions — currently single current version.
func (h *E2EEHandler) GetVaultVersions(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var v models.E2EEVault
	if err := database.DB.Where("user_id = ?", userID).First(&v).Error; err != nil {
		return c.JSON(fiber.Map{"versions": []int{}})
	}
	return c.JSON(fiber.Map{
		"current_version": v.VaultVersion,
		"versions":        []int{v.VaultVersion},
		"updated_at":      v.UpdatedAt,
	})
}

// ListDevices GET /e2ee/devices
func (h *E2EEHandler) ListDevices(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var devices []models.E2EEDevice
	database.DB.Where("user_id = ?", userID).Order("created_at asc").Find(&devices)
	out := make([]fiber.Map, 0, len(devices))
	for _, d := range devices {
		out = append(out, fiber.Map{
			"id":           d.ID,
			"device_id":    d.DeviceID,
			"name":         d.Name,
			"platform":     d.Platform,
			"public_key":   d.PublicKey,
			"revoked_at":   d.RevokedAt,
			"last_seen_at": d.LastSeenAt,
			"created_at":   d.CreatedAt,
		})
	}
	return c.JSON(fiber.Map{"devices": out})
}

// ListChatDevices GET /e2ee/chats/:chat_id/devices — live (non-revoked)
// devices for every participant. Direct v3 fan-out encrypts a copy per id.
func (h *E2EEHandler) ListChatDevices(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	var part models.ChatParticipant
	if err := database.DB.Where("chat_id = ? AND user_id = ?", chatID, userID).First(&part).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	var parts []models.ChatParticipant
	database.DB.Where("chat_id = ? AND left_at IS NULL", chatID).Find(&parts)
	ids := make([]uuid.UUID, 0, len(parts))
	for _, p := range parts {
		ids = append(ids, p.UserID)
	}
	if len(ids) == 0 {
		return c.JSON(fiber.Map{"devices": []fiber.Map{}})
	}
	var devices []models.E2EEDevice
	database.DB.Where("user_id IN ? AND revoked_at IS NULL", ids).Find(&devices)
	out := make([]fiber.Map, 0, len(devices))
	for _, d := range devices {
		out = append(out, fiber.Map{
			"user_id":   d.UserID,
			"device_id": d.DeviceID,
			"platform":  d.Platform,
		})
	}
	return c.JSON(fiber.Map{"devices": out})
}


// RevokeDevice POST /e2ee/devices/:device_id/revoke
func (h *E2EEHandler) RevokeDevice(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	deviceID := c.Params("device_id")
	// One conditional statement rather than read-then-whole-struct-Save. The
	// previous form could write a pre-read copy of every column back over a
	// concurrent update; scoping the write to the two fields revocation owns
	// removes that whole class. Absence of a revoked_at IS NULL predicate is
	// deliberate: re-revoking an existing device stays a 200 as before.
	now := time.Now()
	var (
		affected   int64
		revokedIDs []uuid.UUID
	)
	// The device flag and the sessions it authorizes are written in ONE
	// transaction. Revocation only reaches a credential if the sessions row
	// itself changes: the refresh CAS re-reads that row under lock, so a
	// concurrent refresh either precedes this commit or observes it. Flipping
	// only devices.revoked_at and hoping the refresh path reads it would be
	// write skew.
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		res := tx.Model(&models.E2EEDevice{}).
			Where("user_id = ? AND device_id = ?", userID, deviceID).
			Updates(map[string]interface{}{"revoked_at": now, "updated_at": now})
		if res.Error != nil {
			return res.Error
		}
		affected = res.RowsAffected
		if affected == 0 {
			return nil
		}
		var dev models.E2EEDevice
		if err := tx.Where("user_id = ? AND device_id = ?", userID, deviceID).
			First(&dev).Error; err != nil {
			return err
		}
		if err := tx.Raw(`SELECT id FROM sessions
		                   WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL`,
			userID, dev.ID).Scan(&revokedIDs).Error; err != nil {
			return err
		}
		// Scoped strictly to this device. Sessions with a NULL device belong to
		// logins that never had an association and must not be swept: revoking
		// one device may not sign a user out everywhere.
		_, err := revokeSessionsForDevice(tx, userID, dev.ID, "device_revoke")
		return err
	})
	if txErr != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "lookup failed"})
	}
	if affected == 0 {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "device not found"})
	}
	kickSessions(revokedIDs)
	if h.hub != nil {
		h.hub.KickDevice(userID, deviceID)
	}
	return c.JSON(fiber.Map{"message": "device revoked", "device_id": deviceID})
}

// DeleteDevice DELETE /e2ee/devices/:device_id
func (h *E2EEHandler) DeleteDevice(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	deviceID := c.Params("device_id")
	// Deleting a device row is how the row disappears; deleting a REVOKED row is
	// how the evidence of revocation disappears. Those are different acts, and
	// only the first is a device-list operation. Gate 11.5 proved the second is
	// an attack primitive: erase the tombstone and the same device_id becomes
	// merely unknown again, which registration (and the revocation guard) will
	// happily treat as a fresh, trusted device.
	//
	// The revoked_at IS NULL predicate rides in the DELETE itself, so the
	// precondition and the deletion are one atomic statement rather than a
	// decision made before it. This is the only DELETE against this table in the
	// codebase: there is no cascade, cleanup job, or account-teardown path, so
	// refusing here makes the tombstone permanent.
	var found, revoked bool
	var kicked []uuid.UUID
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		var d models.E2EEDevice
		err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("user_id = ? AND device_id = ?", userID, deviceID).First(&d).Error
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return errDeviceNotFound
		}
		if err != nil {
			return err
		}
		found = true
		if d.RevokedAt != nil {
			revoked = true
			return errDeviceRevoked
		}
		// Sessions first, delete second, both inside this transaction. The FK is
		// ON DELETE SET NULL, and the guard trigger permits device_id -> NULL
		// only on an ALREADY-REVOKED session.
		//
		// The ordering is load-bearing in both directions. Revoking first is what
		// destroys refresh_hash and purges the response cache, so neither a
		// credential nor a cached lost-response successor can outlive the device.
		// And because the FK action is an ordinary UPDATE subject to that
		// trigger, the DELETE below is REFUSED outright if any session is still
		// live - the device cannot be removed out from under a working
		// credential, it fails closed instead. Detachment is a consequence of
		// deletion, never a means of preserving a live session.
		//
		// The sessions themselves are deliberately retained as revoked history:
		// consumed_refresh references them with RESTRICT and is Gate 17's fork
		// evidence, which must outlive the device that produced it.
		if err := tx.Raw(`SELECT id FROM sessions
		                   WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL`,
			userID, d.ID).Scan(&kicked).Error; err != nil {
			return err
		}
		if _, err := revokeSessionsForDevice(tx, userID, d.ID, "device_delete"); err != nil {
			return err
		}

		res := tx.Where("id = ? AND revoked_at IS NULL", d.ID).Delete(&models.E2EEDevice{})
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected == 0 {
			// The row stopped being active despite the lock; fail closed rather
			// than reporting a deletion that did not happen.
			revoked = true
			return errDeviceRevoked
		}
		return nil
	})

	if revoked {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "device revoked",
			"message": "A revoked device cannot be deleted; its revocation is permanent.",
		})
	}
	if !found {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "device not found"})
	}
	if txErr != nil {
		// A RESTRICT violation means something still references this device.
		// Report it as a conflict rather than leaking a generic 500: the caller
		// can act on "still in use", but not on "internal error".
		if name, isConstraint := constraintViolation(txErr); isConstraint {
			log.Printf("[e2ee] device delete blocked by %s", name)
			return c.Status(http.StatusConflict).JSON(fiber.Map{
				"error":   "device in use",
				"message": "This device still has references that must be removed first.",
			})
		}
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to delete device"})
	}
	kickSessions(kicked)
	return c.JSON(fiber.Map{"message": "device deleted"})
}

type pairingCreateBody struct {
	EphemeralPubHex string `json:"ephemeral_pub_hex"`
	DeviceID        string `json:"device_id"`
}

type pairingCompleteBody struct {
	PayloadB64   string `json:"payload_b64"`
	SenderPubHex string `json:"sender_pub_hex"`
}

func pairingStatus(s models.E2EEPairingSession) string {
	now := time.Now()
	if s.ConsumedAt != nil {
		return "consumed"
	}
	if now.After(s.ExpiresAt) {
		return "expired"
	}
	if len(s.Payload) > 0 {
		return "ready"
	}
	return "waiting"
}

// CreatePairing POST /e2ee/pairing — new device starts a link session.
func (h *E2EEHandler) CreatePairing(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body pairingCreateBody
	if err := c.BodyParser(&body); err != nil || body.EphemeralPubHex == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "ephemeral_pub_hex required"})
	}
	pub, err := hex.DecodeString(body.EphemeralPubHex)
	if err != nil || len(pub) != 32 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid ephemeral_pub_hex"})
	}
	sessionID := uuid.New().String()
	now := time.Now()
	s := models.E2EEPairingSession{
		ID:              uuid.New(),
		UserID:          userID,
		SessionID:       sessionID,
		EphemeralPubHex: body.EphemeralPubHex,
		NewDeviceID:     body.DeviceID,
		ExpiresAt:       now.Add(time.Duration(e2ee.PairingTTLSeconds) * time.Second),
		CreatedAt:       now,
	}
	if err := database.DB.Create(&s).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to create pairing"})
	}
	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"session_id":        sessionID,
		"expires_at":        s.ExpiresAt,
		"ephemeral_pub_hex": s.EphemeralPubHex,
		"pairing_string":    e2ee.FormatPairingString(sessionID, pub),
		"status":            "waiting",
	})
}

// GetPairing GET /e2ee/pairing/:session_id
func (h *E2EEHandler) GetPairing(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	sessionID := c.Params("session_id")
	var s models.E2EEPairingSession
	if err := database.DB.Where("user_id = ? AND session_id = ?", userID, sessionID).First(&s).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "pairing not found"})
	}
	return c.JSON(fiber.Map{
		"session_id":        s.SessionID,
		"status":            pairingStatus(s),
		"ephemeral_pub_hex": s.EphemeralPubHex,
		"expires_at":        s.ExpiresAt,
		"has_payload":       len(s.Payload) > 0 && s.ConsumedAt == nil,
	})
}

// CompletePairing POST /e2ee/pairing/:session_id/complete — old device uploads sealed MK.
func (h *E2EEHandler) CompletePairing(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	sessionID := c.Params("session_id")
	var body pairingCompleteBody
	if err := c.BodyParser(&body); err != nil || body.PayloadB64 == "" || body.SenderPubHex == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "payload_b64 and sender_pub_hex required"})
	}
	senderPub, err := hex.DecodeString(body.SenderPubHex)
	if err != nil || len(senderPub) != 32 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid sender_pub_hex"})
	}
	payload, err := unb64(body.PayloadB64)
	if err != nil || len(payload) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid payload_b64"})
	}

	outcome := pairingNotFound
	_ = database.DB.Transaction(func(tx *gorm.DB) error {
		var s models.E2EEPairingSession
		// waiting -> ready must happen at most once. Without the lock both
		// callers read an empty payload, both pass the check, and the later
		// write silently replaces the earlier device's sealed master key.
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("user_id = ? AND session_id = ?", userID, sessionID).First(&s).Error; err != nil {
			outcome = pairingNotFound
			return err
		}
		if s.ConsumedAt != nil {
			outcome = pairingConsumed
			return errPairingAbort
		}
		if time.Now().After(s.ExpiresAt) {
			outcome = pairingExpired
			return errPairingAbort
		}
		if len(s.Payload) > 0 {
			outcome = pairingAlreadyCompleted
			return errPairingAbort
		}
		s.Payload = payload
		s.SenderPubHex = body.SenderPubHex
		if err := tx.Save(&s).Error; err != nil {
			outcome = pairingWriteFailed
			return err
		}
		outcome = pairingOK
		return nil
	})

	switch outcome {
	case pairingConsumed:
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "pairing already consumed"})
	case pairingExpired:
		return c.Status(http.StatusGone).JSON(fiber.Map{"error": "pairing expired"})
	case pairingAlreadyCompleted:
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "pairing already completed"})
	case pairingWriteFailed:
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to store payload"})
	case pairingOK:
		return c.JSON(fiber.Map{"message": "pairing payload stored", "status": "ready"})
	}
	return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "pairing not found"})
}

// TakePairingPayload GET /e2ee/pairing/:session_id/payload — new device fetches once.
func (h *E2EEHandler) TakePairingPayload(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	sessionID := c.Params("session_id")
	outcome := pairingNotFound
	var (
		claimedSession string
		payload        []byte
		sender         string
	)
	_ = database.DB.Transaction(func(tx *gorm.DB) error {
		var s models.E2EEPairingSession
		// The lock is what makes single-use real: a concurrent consumer waits
		// here and then observes ConsumedAt already set by the winner.
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("user_id = ? AND session_id = ?", userID, sessionID).First(&s).Error; err != nil {
			outcome = pairingNotFound
			return err
		}
		if s.ConsumedAt != nil {
			outcome = pairingConsumed
			return errPairingAbort
		}
		if time.Now().After(s.ExpiresAt) {
			outcome = pairingExpired
			return errPairingAbort
		}
		if len(s.Payload) == 0 {
			outcome = pairingNoPayload
			return errPairingAbort
		}
		now := time.Now()
		s.ConsumedAt = &now
		claimedSession = s.SessionID
		payload = s.Payload
		sender = s.SenderPubHex
		s.Payload = nil // scrub after handoff
		if err := tx.Save(&s).Error; err != nil {
			outcome = pairingWriteFailed
			return err
		}
		outcome = pairingOK
		return nil
	})

	switch outcome {
	case pairingConsumed:
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "pairing already consumed"})
	case pairingExpired:
		return c.Status(http.StatusGone).JSON(fiber.Map{"error": "pairing expired"})
	case pairingNoPayload:
		return c.Status(http.StatusNoContent).Send(nil)
	case pairingWriteFailed:
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to consume pairing"})
	case pairingOK:
		return c.JSON(fiber.Map{
			"session_id":     claimedSession,
			"payload_b64":    b64(payload),
			"sender_pub_hex": sender,
		})
	}
	return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "pairing not found"})
}

// DefaultPwParamsJSON helper for clients (documentation endpoint style).
func DefaultPwParamsJSON() string {
	p := e2ee.DefaultArgon2idParams()
	b, _ := json.Marshal(p)
	return string(b)
}
