package handlers

import (
	"log"
	"net/http"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"messenger-app/crypto"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

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

	// Update user's public key
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

// DecryptMessage handles decrypting a message
func (h *CryptoHandler) DecryptMessage(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not authenticated",
		})
	}

	var input struct {
		Ciphertext   string `json:"ciphertext" validate:"required"`
		SenderID     string `json:"sender_id" validate:"required"`
		ReceiverID   string `json:"receiver_id" validate:"required"`
	}

	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}

	// Get sender's public key
	var sender models.User
	if err := database.DB.First(&sender, "id = ?", input.SenderID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "Sender not found",
		})
	}

	// Get receiver's private key
	var receiver models.User
	if err := database.DB.First(&receiver, "id = ?", input.ReceiverID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "Receiver not found",
		})
	}

	// Decrypt the message
	decrypted, err := crypto.DecryptMessage(input.Ciphertext, sender.PublicKey, receiver.PrivateKey)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Failed to decrypt message: " + err.Error(),
		})
	}

	return c.JSON(fiber.Map{
		"decrypted_message": decrypted,
	})
}

// GenerateKeyPairHandler handles generating a new key pair for the current user
func (h *CryptoHandler) GenerateKeyPairHandler(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not authenticated",
		})
	}

	keyPair, err := crypto.GenerateKeyPair()
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate key pair",
		})
	}

	// Update user's keys
	if err := database.DB.Model(&models.User{}).Where("id = ?", userID).
		Select("public_key, private_key").
		Update("public_key", keyPair.PublicKey).Update("private_key", keyPair.PrivateKey).Error; err != nil {
		// Fallback to UpdateColumn
		if err2 := database.DB.Model(&models.User{}).Where("id = ?", userID).
			UpdateColumn("public_key", keyPair.PublicKey).UpdateColumn("private_key", keyPair.PrivateKey).Error; err2 != nil {
			log.Printf("Failed to save key pair: %v", err2)
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error": "Failed to save key pair",
			})
		}
	}

	return c.JSON(fiber.Map{
		"user_id":    userID,
		"public_key": keyPair.PublicKey,
	})
}
