package handlers

import (
	"errors"
	"net/http"

	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// KeyringRecoveryHandler serves the per-user MK-sealed history keyring.
//
// It never decrypts anything and never sees MK. Ownership comes exclusively from
// the authenticated session - no caller-supplied user id participates in any
// query - and both routes additionally require a known, non-revoked device.
type KeyringRecoveryHandler struct{}

func NewKeyringRecoveryHandler() *KeyringRecoveryHandler { return &KeyringRecoveryHandler{} }

// maxKeyringCiphertext bounds one blob. A keyring entry is a chat id, a version,
// and a 32-byte root; even thousands of chats with many rotations stay far below
// this, while it keeps a single request well under Fiber's global body limit.
const maxKeyringCiphertext = 1 << 20 // 1 MiB

type keyringPutBody struct {
	// ExpectedVersion is the version the client believes is current. 0 means "I
	// believe no object exists yet" and only succeeds as a create.
	ExpectedVersion int    `json:"expected_version"`
	CiphertextB64   string `json:"ciphertext_b64"`
}

// GetHistoryKeyring GET /e2ee/history-keyring
//
// Returns the caller's own blob. A user with no recovery object gets the
// project's conventional 404 rather than a synthetic empty keyring - inventing
// one would let a client mistake "nothing stored" for "an empty keyring is
// authoritative", which is exactly the mint-over-live-archives mistake the
// client's fail-closed rules exist to prevent.
func (h *KeyringRecoveryHandler) GetHistoryKeyring(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	var row models.HistoryKeyringRecovery
	if err := database.DB.Where("user_id = ?", userID).First(&row).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return c.Status(http.StatusNotFound).JSON(fiber.Map{
				"error":   "not found",
				"message": "No history keyring stored for this account",
			})
		}
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not load history keyring",
		})
	}

	return c.JSON(fiber.Map{
		"version":        row.Version,
		"ciphertext_b64": b64(row.Ciphertext),
	})
}

// PutHistoryKeyring PUT /e2ee/history-keyring
//
// Optimistic concurrency, never a blind overwrite. A keyring grows over time, so
// unlike an immutable message archive this must permit replacement - but only by
// a writer who can name the version it is replacing.
//
//	expected_version == 0                 -> create; fails if an object exists
//	expected_version == current version    -> replace; version increments
//	anything else                          -> 409 with the server's version
//
// Both branches are single conditional statements, so two concurrent writers
// cannot both succeed: the loser's row count is zero and it receives a conflict.
// A replayed successful update also conflicts, because the version it names is
// no longer current - which is what stops a retry from clobbering a newer keyring.
func (h *KeyringRecoveryHandler) PutHistoryKeyring(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	var body keyringPutBody
	if err := c.BodyParser(&body); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid body"})
	}
	if body.ExpectedVersion < 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "expected_version must be >= 0",
		})
	}
	ct, err := unb64(body.CiphertextB64)
	if err != nil || len(ct) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid ciphertext_b64"})
	}
	if len(ct) > maxKeyringCiphertext {
		return c.Status(http.StatusRequestEntityTooLarge).JSON(fiber.Map{
			"error": "ciphertext too large",
		})
	}

	if body.ExpectedVersion == 0 {
		// Create only. ON CONFLICT DO NOTHING means an existing row is left
		// completely untouched and the writer is told it is stale.
		res := database.DB.Exec(
			`INSERT INTO history_keyring_recoveries (user_id, ciphertext, version, created_at, updated_at)
			 VALUES (?, ?, 1, now(), now())
			 ON CONFLICT (user_id) DO NOTHING`,
			userID, ct,
		)
		if res.Error != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error":   "internal error",
				"message": "Could not store history keyring",
			})
		}
		if res.RowsAffected == 0 {
			return h.conflict(c, userID)
		}
		return c.JSON(fiber.Map{"version": 1, "created": true})
	}

	// Replace only when the caller named the current version.
	res := database.DB.Exec(
		`UPDATE history_keyring_recoveries
		    SET ciphertext = ?, version = version + 1, updated_at = now()
		  WHERE user_id = ? AND version = ?`,
		ct, userID, body.ExpectedVersion,
	)
	if res.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store history keyring",
		})
	}
	if res.RowsAffected == 0 {
		return h.conflict(c, userID)
	}
	return c.JSON(fiber.Map{"version": body.ExpectedVersion + 1, "created": false})
}

// DeleteHistoryKeyring DELETE /e2ee/history-keyring
//
// Removes the caller's own recovery blob. This exists for exactly one reason:
// when a fresh master key is minted, the stored blob becomes permanently
// unopenable, and because PUT with expected_version=0 is create-only it would
// then conflict forever - stranding recovery for that account with no client
// reachable remedy. Deletion is the only way out of that state.
//
// Idempotent: deleting when nothing is stored succeeds. Ownership comes from the
// session alone; no caller-supplied user id participates. Returns no ciphertext.
func (h *KeyringRecoveryHandler) DeleteHistoryKeyring(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	res := database.DB.Exec(`DELETE FROM history_keyring_recoveries WHERE user_id = ?`, userID)
	if res.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not delete history keyring",
		})
	}
	return c.JSON(fiber.Map{"deleted": res.RowsAffected > 0})
}

// conflict reports the server's current version so the client can fetch, merge,
// and retry. It never returns ciphertext - the loser must re-read it explicitly.
func (h *KeyringRecoveryHandler) conflict(c *fiber.Ctx, userID interface{}) error {
	var row models.HistoryKeyringRecovery
	current := 0
	if err := database.DB.Where("user_id = ?", userID).First(&row).Error; err == nil {
		current = row.Version
	}
	return c.Status(http.StatusConflict).JSON(fiber.Map{
		"error":          "version conflict",
		"message":        "The stored history keyring has moved on; fetch, merge, and retry",
		"server_version": current,
	})
}
