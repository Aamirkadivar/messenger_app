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
func (h *AuthService) StartPasswordReset(c *fiber.Ctx) error {
	cfg := config.LoadConfig()
	generic := fiber.Map{
		"message": "If that account exists, a reset code was issued. Check the DEV 2FA bot / server log.",
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
		"relay_hint":    "DEV: code sent to @" + cfg.Dev2FARelayUsername + " and server log [dev_2fa_relay]",
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

	otpMu.Lock()
	delete(otpChallenges, input.ChallengeID)
	otpMu.Unlock()

	hashed, err := bcrypt.GenerateFromPassword([]byte(input.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to hash password"})
	}
	if err := database.DB.Model(&models.User{}).Where("id = ?", ch.UserID).
		Update("password_hash", string(hashed)).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to update password"})
	}

	return c.JSON(fiber.Map{
		"message": "Password updated. Sign in with the new password. Unlock history with your recovery key; without it, old messages stay undecryptable.",
	})
}
