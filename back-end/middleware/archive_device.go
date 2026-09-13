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

// RequireDeviceIdentity enforces a PROVEN device on device-gated routes.
//
// WHAT CHANGED AND WHY. This check used to trust X-Device-Id: it looked the
// header up and admitted any row it found. Phase 43 showed that authorized
// nothing. The permissive DeviceRevocationGuard ran first in the same chain and
// created a row for any unknown id, so an attacker holding only an account token
// read the MK-sealed history keyring from a device identity invented seconds
// earlier - and evaded a revocation of their own device simply by asserting a
// different string. Removing that auto-registration alone would not have helped,
// because POST /e2ee/devices would still mint a row on demand.
//
// The fix is to stop asking the caller who they are. Authorization now resolves
// through the SESSION, which is server-side state the caller cannot edit:
//
//	access token -> sid -> sessions.device_id -> e2ee_devices row
//
// A session acquires that binding in exactly one place: completing the
// proof-of-possession flow in POST /e2ee/devices. So a token alone can no longer
// reach these routes, and revoking a device revokes the sessions bound to it -
// which the existing sessionIsLive check then rejects up in AuthMiddleware,
// before any of this runs.
//
// X-Device-Id survives only as a cross-check. It states which device the caller
// BELIEVES it is; if that disagrees with the session's binding the request is
// refused rather than quietly answered as the bound device. It never grants
// access by itself.
//
// Run this AFTER AuthMiddleware.
func RequireDeviceIdentity() fiber.Handler {
	return func(c *fiber.Ctx) error {
		userID := GetCurrentUserID(c)
		sessionID := GetCurrentSessionID(c)
		if userID == uuid.Nil || sessionID == uuid.Nil {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error":   "unauthorized",
				"message": "Authentication required",
			})
		}

		// Still required, and still a 400 when absent: a client that declines to
		// say which device it is cannot be held to a device identity at all.
		headerID := c.Get("X-Device-Id")
		if headerID == "" || len(headerID) > 128 {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error":   "device required",
				"message": "X-Device-Id is required for archive operations",
			})
		}

		// The authority. Conjunctive on (id, user_id) for the same reason
		// liveSession is: resolving by sid alone would let a token naming another
		// account's session borrow that account's device binding.
		var session models.Session
		if err := database.DB.
			Where("id = ? AND user_id = ? AND revoked_at IS NULL", sessionID, userID).
			First(&session).Error; err != nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device not verified",
				"message": "This session is not bound to a verified device",
			})
		}
		if session.DeviceID == nil {
			// Authenticated, but never proved a device. This is the ordinary
			// state right after login, and it is the reason a token is not enough.
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device not verified",
				"message": "Complete device verification before using this endpoint",
			})
		}

		var d models.E2EEDevice
		if err := database.DB.
			Where("id = ? AND user_id = ?", *session.DeviceID, userID).
			First(&d).Error; err != nil {
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
		// Belt and braces. A binding is only ever written after a successful
		// proof, so this should be unreachable - which is exactly why it is
		// checked rather than assumed.
		if d.VerifiedAt == nil {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device not verified",
				"message": "This device has not proven possession of its key",
			})
		}
		if d.DeviceID != headerID {
			return c.Status(http.StatusForbidden).JSON(fiber.Map{
				"error":   "device mismatch",
				"message": "X-Device-Id does not match the device bound to this session",
			})
		}

		c.Locals(ContextKeyDeviceID, d.DeviceID)
		return c.Next()
	}
}
