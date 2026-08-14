package handlers

import (
	"log"
	"net/http"
	"strings"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

func publicKeysEqual(a, b string) bool {
	return strings.EqualFold(strings.TrimSpace(a), strings.TrimSpace(b))
}

// identityChangeBlocked is true when a client tries to replace an already
// published identity and the account already has a vault or a live device.
func identityChangeBlocked(existing, incoming string, hasVault, hasLiveDevice bool) bool {
	if existing == "" || publicKeysEqual(existing, incoming) {
		return false
	}
	return hasVault || hasLiveDevice
}

// CryptoHandler handles cryptographic operations
type CryptoHandler struct{}

// NewCryptoHandler creates a new CryptoHandler instance
func NewCryptoHandler() *CryptoHandler {
	return &CryptoHandler{}
}

// SavePublicKeyInput represents the input for saving a public key
type SavePublicKeyInput struct {
	PublicKey string `json:"public_key" validate:"required"`
}

// SavePublicKey handles saving a user's public key
func (h *CryptoHandler) SavePublicKey(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not authenticated",
		})
	}

	var input SavePublicKeyInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}

	if input.PublicKey == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Public key is required",
		})
	}

	var user models.User
	if err := database.DB.Select("id", "public_key").First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "User not found"})
	}

	if user.PublicKey != "" && !publicKeysEqual(user.PublicKey, input.PublicKey) {
		var vaultCount, deviceCount int64
		database.DB.Model(&models.E2EEVault{}).Where("user_id = ?", userID).Count(&vaultCount)
		database.DB.Model(&models.E2EEDevice{}).Where("user_id = ? AND revoked_at IS NULL", userID).Count(&deviceCount)
		if identityChangeBlocked(user.PublicKey, input.PublicKey, vaultCount > 0, deviceCount > 0) {
			return c.Status(http.StatusConflict).JSON(fiber.Map{
				"error":   "identity_locked",
				"message": "This account already has an E2EE identity. Unlock the vault on this device instead of publishing a new key.",
			})
		}
	}

	if err := database.DB.Model(&models.User{}).Where("id = ?", userID).
		Select("public_key"). // Only update public_key, ignore other fields
		Update("public_key", input.PublicKey).Error; err != nil {
		// Try UpdateColumns as fallback
		if err2 := database.DB.Model(&models.User{}).Where("id = ?", userID).
			UpdateColumn("public_key", input.PublicKey).Error; err2 != nil {
			log.Printf("Failed to save public key: %v", err2)
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error": "Failed to save public key",
			})
		}
	}

	return c.JSON(fiber.Map{
		"message":    "Public key saved successfully",
		"public_key": input.PublicKey,
	})
}

// GetPublicKey handles getting the current user's public key
func (h *CryptoHandler) GetPublicKey(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not authenticated",
		})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	return c.JSON(fiber.Map{
		"user_id":    user.ID,
		"public_key": user.PublicKey,
	})
}

// GetPublicKeyByUserId handles getting a specific user's public key
func (h *CryptoHandler) GetPublicKeyByUserId(c *fiber.Ctx) error {
	userIDStr := c.Params("user_id")
	userID, err := uuid.Parse(userIDStr)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid user ID",
		})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	return c.JSON(fiber.Map{
		"user_id":    user.ID,
		"public_key": user.PublicKey,
	})
}

// NOTE: server-side DecryptMessage and GenerateKeyPairHandler were removed on
// purpose. With real end-to-end encryption the server must never see a private
// key or plaintext: keypairs are generated on the client, only the *public*
// key is uploaded (SavePublicKey), and decryption happens client-side.
