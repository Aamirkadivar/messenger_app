package handlers

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"messenger-app/database"
	"messenger-app/e2ee"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"
)

// E2EEHandler serves opaque vault / device APIs. It never decrypts vault bytes.
type E2EEHandler struct {
	hub *websocket.Hub
}

func NewE2EEHandler(hub *websocket.Hub) *E2EEHandler { return &E2EEHandler{hub: hub} }

type vaultPutBody struct {
	VaultVersion      int    `json:"vault_version"`
	ProtocolVersion   int    `json:"protocol_version"`
	Suite             string `json:"suite"`
	VaultCiphertextB64 string `json:"vault_ciphertext_b64"`
	PwKDF             string `json:"pw_kdf"`
	PwSaltB64         string `json:"pw_salt_b64"`
	PwParamsJSON      string `json:"pw_params"`
	PwWrappedMasterB64 string `json:"pw_wrapped_master_b64"`
	RkKDF             string `json:"rk_kdf"`
	RkSaltB64         string `json:"rk_salt_b64"`
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
		"user_id":                 v.UserID,
		"vault_version":           v.VaultVersion,
		"protocol_version":        v.ProtocolVersion,
		"suite":                   v.Suite,
		"vault_ciphertext_b64":    b64(v.VaultCiphertext),
		"pw_kdf":                  v.PwKDF,
		"pw_salt_b64":             b64(v.PwSalt),
		"pw_params":               v.PwParamsJSON,
		"pw_wrapped_master_b64":   b64(v.PwWrappedMaster),
		"rk_kdf":                  v.RkKDF,
		"rk_salt_b64":             b64(v.RkSalt),
		"rk_wrapped_master_b64":   b64(v.RkWrappedMaster),
		"updated_at":              v.UpdatedAt,
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
				"error":             "vault already exists elsewhere",
				"server_version":    0,
				"expected_version":  body.ExpectedVersion,
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
	if err := database.DB.Save(&existing).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to update vault"})
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

type deviceBody struct {
	DeviceID  string `json:"device_id"`
	Name      string `json:"name"`
	Platform  string `json:"platform"`
	PublicKey string `json:"public_key"`
}

// ListDevices GET /e2ee/devices
func (h *E2EEHandler) ListDevices(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var devices []models.E2EEDevice
	database.DB.Where("user_id = ?", userID).Order("created_at asc").Find(&devices)
	out := make([]fiber.Map, 0, len(devices))
	for _, d := range devices {
		out = append(out, fiber.Map{
			"id":          d.ID,
			"device_id":   d.DeviceID,
			"name":        d.Name,
			"platform":    d.Platform,
			"public_key":  d.PublicKey,
			"revoked_at":  d.RevokedAt,
			"last_seen_at": d.LastSeenAt,
			"created_at":  d.CreatedAt,
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

// RegisterDevice POST /e2ee/devices
func (h *E2EEHandler) RegisterDevice(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body deviceBody
	if err := c.BodyParser(&body); err != nil || body.DeviceID == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "device_id required"})
	}
	existing, found, err := models.LookupE2EEDevice(database.DB, userID, body.DeviceID)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to register device"})
	}
	now := time.Now()
	if found {
		if existing.RevokedAt != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "device revoked"})
		}
		existing.Name = body.Name
		existing.Platform = body.Platform
		if body.PublicKey != "" {
			existing.PublicKey = body.PublicKey
		}
		existing.LastSeenAt = &now
		existing.UpdatedAt = now
		database.DB.Save(&existing)
		return c.JSON(fiber.Map{"device": existing})
	}
	d := models.E2EEDevice{
		ID:         uuid.New(),
		UserID:     userID,
		DeviceID:   body.DeviceID,
		Name:       body.Name,
		Platform:   body.Platform,
		PublicKey:  body.PublicKey,
		LastSeenAt: &now,
		CreatedAt:  now,
		UpdatedAt:  now,
	}
	if err := database.DB.Create(&d).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to register device"})
	}
	return c.Status(http.StatusCreated).JSON(fiber.Map{"device": d})
}

// RevokeDevice POST /e2ee/devices/:device_id/revoke
func (h *E2EEHandler) RevokeDevice(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	deviceID := c.Params("device_id")
	d, found, err := models.LookupE2EEDevice(database.DB, userID, deviceID)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "lookup failed"})
	}
	if !found {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "device not found"})
	}
	now := time.Now()
	d.RevokedAt = &now
	d.UpdatedAt = now
	database.DB.Save(&d)
	if h.hub != nil {
		h.hub.KickDevice(userID, deviceID)
	}
	return c.JSON(fiber.Map{"message": "device revoked", "device_id": deviceID})
}

// DeleteDevice DELETE /e2ee/devices/:device_id
func (h *E2EEHandler) DeleteDevice(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	deviceID := c.Params("device_id")
	res := database.DB.Where("user_id = ? AND device_id = ?", userID, deviceID).Delete(&models.E2EEDevice{})
	if res.RowsAffected == 0 {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "device not found"})
	}
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
		"session_id":       sessionID,
		"expires_at":       s.ExpiresAt,
		"ephemeral_pub_hex": s.EphemeralPubHex,
		"pairing_string":   e2ee.FormatPairingString(sessionID, pub),
		"status":           "waiting",
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

	var s models.E2EEPairingSession
	if err := database.DB.Where("user_id = ? AND session_id = ?", userID, sessionID).First(&s).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "pairing not found"})
	}
	if s.ConsumedAt != nil {
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "pairing already consumed"})
	}
	if time.Now().After(s.ExpiresAt) {
		return c.Status(http.StatusGone).JSON(fiber.Map{"error": "pairing expired"})
	}
	if len(s.Payload) > 0 {
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "pairing already completed"})
	}
	s.Payload = payload
	s.SenderPubHex = body.SenderPubHex
	if err := database.DB.Save(&s).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to store payload"})
	}
	return c.JSON(fiber.Map{"message": "pairing payload stored", "status": "ready"})
}

// TakePairingPayload GET /e2ee/pairing/:session_id/payload — new device fetches once.
func (h *E2EEHandler) TakePairingPayload(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	sessionID := c.Params("session_id")
	var s models.E2EEPairingSession
	if err := database.DB.Where("user_id = ? AND session_id = ?", userID, sessionID).First(&s).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "pairing not found"})
	}
	if s.ConsumedAt != nil {
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "pairing already consumed"})
	}
	if time.Now().After(s.ExpiresAt) {
		return c.Status(http.StatusGone).JSON(fiber.Map{"error": "pairing expired"})
	}
	if len(s.Payload) == 0 {
		return c.Status(http.StatusNoContent).Send(nil)
	}
	now := time.Now()
	s.ConsumedAt = &now
	payload := s.Payload
	sender := s.SenderPubHex
	s.Payload = nil // scrub after handoff
	if err := database.DB.Save(&s).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to consume pairing"})
	}
	return c.JSON(fiber.Map{
		"session_id":      s.SessionID,
		"payload_b64":     b64(payload),
		"sender_pub_hex":  sender,
	})
}

// DefaultPwParamsJSON helper for clients (documentation endpoint style).
func DefaultPwParamsJSON() string {
	p := e2ee.DefaultArgon2idParams()
	b, _ := json.Marshal(p)
	return string(b)
}