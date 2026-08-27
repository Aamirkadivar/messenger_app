package middleware

import (
	"net/http"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"messenger-app/database"
	"messenger-app/models"
)

// ContextKeyDeviceID holds the verified X-Device-Id for the current request.
const ContextKeyDeviceID = "verified_device_id"

// RequireDeviceIdentity enforces a known, non-revoked device on archive routes.
//
// This is deliberately STRICTER than the global DeviceRevocationGuard, which it
// does not replace or weaken. That guard is permissive by design: it lets a
// request through when X-Device-Id is absent, and auto-registers an unknown id
// so a fresh install cannot get stuck receiving messages it can never decrypt.
// Both behaviours are right for ordinary messaging and wrong for archives:
//
//   - a missing header would let any client opt out of device accountability
//     simply by not sending one;
//   - auto-registration would let an attacker with a stolen token mint a brand
//     new device identity and immediately read history with it.
//
// So here a missing header is a 400, an unknown device is a 403, and a revoked
// device is a 403. Nothing is created. Run this AFTER AuthMiddleware.
func RequireDeviceIdentity() fiber.Handler {
	return func(c *fiber.Ctx) error {
		userID := GetCurrentUserID(c)
		if userID == uuid.Nil {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error":   "unauthorized",
				"message": "Authentication required",
			})
		}

		deviceID := c.Get("X-Device-Id")
		if deviceID == "" || len(deviceID) > 128 {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error":   "device required",
				"message": "X-Device-Id is required for archive operations",
			})
		}

		d, ok, err := models.LookupE2EEDevice(database.DB, userID, deviceID)
		if err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Could not verify device",
			})
		}
		// Unknown is refused, never registered: archives must not be reachable
		// from a device identity the user has never seen in their device list.
		if !ok {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "unknown device",
				"message": "This device is not registered",
			})
		}
		if d.RevokedAt != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device revoked",
				"message": "This device has been revoked",
			})
		}

		c.Locals(ContextKeyDeviceID, deviceID)
		return c.Next()
	}
}
