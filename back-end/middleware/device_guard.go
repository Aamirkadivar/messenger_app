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
// Clients send X-Device-Id (stable per-install id). If the id is unknown the
// request is allowed (pre-registration / migration); if the row is revoked,
// all further access is denied until that device is deleted and re-linked.
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
		if err != nil || !ok {
			return c.Next()
		}
		if d.RevokedAt != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device_revoked",
				"message": "This device was revoked. Sign in again from an authorized device.",
			})
		}
		now := time.Now()
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
