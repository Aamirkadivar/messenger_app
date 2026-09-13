package middleware

import (
	"net/http"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"messenger-app/database"
	"messenger-app/models"
)

// DeviceRevocationGuard rejects authenticated API calls from a revoked device.
// Clients send X-Device-Id (stable per-install id). If the row is revoked,
// all further access is denied until that device is deleted and re-linked.
//
// An unknown id is passed through UNTOUCHED. It is deliberately not registered
// here, and Phase 43 is why: this guard runs before RequireDeviceIdentity in the
// same chain, so a row created here satisfied the strict check a moment later.
// An attacker holding only an account token could therefore mint a device
// identity and immediately read history with it - and, by inventing a fresh id,
// walk straight past a revocation of their own device.
//
// Registration now happens only through POST /e2ee/devices, which demands proof
// of possession of the device key. That is also what keeps the FN1 fan-out
// correct: a device that never registers receives no sealed copies, which is the
// right outcome for an identity nobody has proven, and both clients already call
// the registration endpoint on connect.
func DeviceRevocationGuard() fiber.Handler {
	return func(c *fiber.Ctx) error {
		deviceID := c.Get("X-Device-Id")
		if deviceID == "" {
			return c.Next()
		}
		userID := GetCurrentUserID(c)
		if userID == uuid.Nil {
			return c.Next()
		}
		d, ok, err := models.LookupE2EEDevice(database.DB, userID, deviceID)
		if err != nil {
			return c.Next()
		}
		now := time.Now()
		if !ok {
			// Unknown: nothing is created and nothing is asserted. Device-gated
			// routes deny this below in RequireDeviceIdentity; ordinary messaging
			// is unaffected, exactly as when no header is sent at all.
			return c.Next()
		}
		if d.RevokedAt != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device_revoked",
				"message": "This device was revoked. Sign in again from an authorized device.",
			})
		}
		_ = database.DB.Model(&d).Update("last_seen_at", now)
		return c.Next()
	}
}

// WebSocketDeviceGuard rejects WS upgrades from a revoked device_id query param.
func WebSocketDeviceGuard() fiber.Handler {
	return func(c *fiber.Ctx) error {
		deviceID := c.Query("device_id")
		if deviceID == "" {
			return c.Next()
		}
		userID := GetCurrentUserID(c)
		d, ok, err := models.LookupE2EEDevice(database.DB, userID, deviceID)
		if err != nil || !ok {
			return c.Next()
		}
		if d.RevokedAt != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error": "device_revoked",
			})
		}
		return c.Next()
	}
}
