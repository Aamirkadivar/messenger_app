package handlers

import (
	"net/http"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/models"
)

type passwordResetStartInput struct {
	Email string `json:"email"`
}

type passwordResetCompleteInput struct {
	ChallengeID string `json:"challenge_id"`
	Code        string `json:"code"`
	TotpCode    string `json:"totp_code"`
	NewPassword string `json:"new_password"`
}

// StartPasswordReset POST /auth/password-reset/start
// Issues a short-lived OTP. Always returns 200 with a generic message so
// emails cannot be enumerated. The vault is never touched here — after the
// account password changes, the client logs in and rewraps MK with the
// recovery key (or loses history if they have neither old password nor key).
//
// The only way this server can deliver a reset code is the DEV relay. Outside
// the explicit development configuration there is therefore no delivery
// mechanism, and reset fails closed: before the body is read or any account is
// looked up, so every caller - known email, unknown email, malformed request -
// gets the identical answer in the same time, and no challenge, code, message
// or log line is ever produced.
func (h *AuthService) StartPasswordReset(c *fiber.Ctx) error {
	cfg := config.LoadConfig()
	if !Dev2FAEnabled(cfg) {
		return c.Status(http.StatusServiceUnavailable).JSON(fiber.Map{
			"error":   "password_reset_unavailable",
			"message": "Password reset is not available on this server.",
		})
	}
	generic := fiber.Map{
		"message": "If that account exists, a reset code was issued. Check the DEV 2FA bot.",
	}

	var input passwordResetStartInput
	if err := c.BodyParser(&input); err != nil {
		return c.JSON(generic)
	}
	email := strings.ToLower(strings.TrimSpace(input.Email))
	if email == "" {
		return c.JSON(generic)
	}

	var user models.User
	if err := database.DB.Where("email = ?", email).First(&user).Error; err != nil {
		if err != gorm.ErrRecordNotFound {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to start reset"})
		}
		return c.JSON(generic)
	}

	challengeID, err := issuePasswordResetChallenge(user, cfg)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to issue reset code"})
	}
	return c.JSON(fiber.Map{
		"message":       generic["message"],
		"challenge_id":  challengeID,
		"totp_required": user.TotpEnabled,
		"relay_hint":    "DEV: code sent to @" + cfg.Dev2FARelayUsername,
	})
}

// CompletePasswordReset POST /auth/password-reset/complete
// Sets a new login password. Does not unwrap or rewrap the E2EE vault.
func (h *AuthService) CompletePasswordReset(c *fiber.Ctx) error {
	var input passwordResetCompleteInput
	if err := c.BodyParser(&input); err != nil || input.ChallengeID == "" || input.Code == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "challenge_id, code, and new_password required"})
	}
	input.NewPassword = strings.TrimSpace(input.NewPassword)
	if len(input.NewPassword) < 8 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "New password must be at least 8 characters"})
	}

	otpMu.Lock()
	purgeExpiredOTPLocked()
	ch, ok := otpChallenges[input.ChallengeID]
	otpMu.Unlock()

	if !ok || time.Now().After(ch.ExpiresAt) || ch.Purpose != "password_reset" {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}
	if err := bcrypt.CompareHashAndPassword([]byte(ch.CodeHash), []byte(input.Code)); err != nil {
		failOTPChallenge(input.ChallengeID)
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", ch.UserID).Error; err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}
	if user.TotpEnabled {
		if strings.TrimSpace(input.TotpCode) == "" {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "authenticator or backup code required"})
		}
		if !validateTotp(user.TotpSecret, input.TotpCode) && !consumeBackupCode(&user, input.TotpCode) {
			failOTPChallenge(input.ChallengeID)
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
		}
	}

	// Claim the challenge. It was read above in a separate critical section, so
	// concurrent completions could all pass those checks; only the one that still
	// finds it here - the same challenge, not a replacement - may reset. This is
	// what keeps a challenge single-use under concurrency.
	otpMu.Lock()
	cur, still := otpChallenges[input.ChallengeID]
	claimed := still && cur.CodeHash == ch.CodeHash
	if claimed {
		delete(otpChallenges, input.ChallengeID)
	}
	otpMu.Unlock()
	if !claimed {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}

	hashed, err := bcrypt.GenerateFromPassword([]byte(input.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to hash password"})
	}
	// Same reasoning as ChangePassword: a reset that leaves old refresh
	// credentials alive defeats its own purpose. One transaction, sessions as the
	// serialization point.
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&models.User{}).Where("id = ?", user.ID).
			Update("password_hash", string(hashed)).Error; err != nil {
			return err
		}
		_, err := revokeSessionsForUser(tx, user.ID, "password_reset")
		return err
	}); err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to update password"})
	}
	kickSessionsOfUser(user.ID)

	return c.JSON(fiber.Map{
		"message": "Password updated. Sign in with the new password. Unlock history with your recovery key; without it, old messages stay undecryptable.",
	})
}
