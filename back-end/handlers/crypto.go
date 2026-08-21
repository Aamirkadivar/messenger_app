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

// identityChangeBlocked is true when a client tries to silently replace an
// already-published identity.
//
// Once an account has published an identity public key, a *different* key
// orphans every message ever sealed to the old one (static v1 boxes, v3 ratchet
// setup, sender-key wraps). The only safe response to a new device that lacks
// the private half is to restore the existing key from the E2EE vault — never to
// overwrite. So any change of an established key is blocked here.
//
// The earlier version gated this on `hasVault || hasLiveDevice`; with both
// tables empty (the system was never activated) the guard never engaged and
// every fresh login silently replaced the identity. See docs/e2ee-architecture.md
// §1.5. The precondition is now simply "an identity already exists".
//
// Deliberate rotation (user explicitly resets E2EE, accepting history loss) is
// the only legitimate way to change an established key and must come through an
// acknowledged reset (reset=true), never as a side effect of login.
func identityChangeBlocked(existing, incoming string, reset bool) bool {
	if existing == "" || publicKeysEqual(existing, incoming) {
		return false
	}
	return !reset
}

// CryptoHandler handles cryptographic operations
type CryptoHandler struct{}

// NewCryptoHandler creates a new CryptoHandler instance
func NewCryptoHandler() *CryptoHandler {
	return &CryptoHandler{}
}

// SavePublicKeyInput represents the input for saving a public key.
//
// Reset must be sent ONLY from a deliberate, user-acknowledged "reset my E2EE
// identity" action in the client UI. It authorizes replacing an already-published
// identity key, which permanently orphans all history sealed to the old key.
// Normal login/key-restore paths must never set it — a new device restores the
// existing key from the vault instead.
type SavePublicKeyInput struct {
	PublicKey string `json:"public_key" validate:"required"`
	Reset     bool   `json:"reset"`
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
		if identityChangeBlocked(user.PublicKey, input.PublicKey, input.Reset) {
			return c.Status(http.StatusConflict).JSON(fiber.Map{
				"error":   "identity_locked",
				"message": "This account already has an E2EE identity. Restore it from the vault on this device (or pair from an existing device) instead of publishing a new key. To start over and lose history, use the explicit E2EE reset.",
			})
		}
		// Reached only with reset=true: a deliberate, acknowledged identity
		// rotation that orphans prior history. Audit the fact (never the key
		// bytes) so the event is traceable.
		log.Printf("[e2ee_identity_reset] user=%s replaced identity key (deliberate reset; prior history orphaned)", userID)
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
