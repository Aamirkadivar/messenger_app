package handlers

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"log"
	"net/http"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// WhatsApp-style QR sign-in.
//
//	displaying client            server              signed-in phone
//	  StartQRLogin  ───────────▶ session (pending)
//	  show QR  ◀──────────────── session_id + scan_secret
//	                                         ◀───────  ApproveQRLogin (scan)
//	  PollQRLogin (verifier) ──▶ tokens (single use)
//
// Security rests on two separate secrets (see models.QRLoginSession): the
// verifier proves you are the client that started the session, the scan secret
// proves you physically scanned the code. Knowing a session id alone is useless.
const (
	qrLoginTTL     = 2 * time.Minute
	qrScanSecretLn = 32
	qrVerifierLn   = 32
)

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func randB64(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

type qrStartBody struct {
	// Hash of a secret the client keeps. Never send the secret itself.
	VerifierHash   string `json:"verifier_hash"`
	ClientName     string `json:"client_name"`
	ClientPlatform string `json:"client_platform"`
}

// StartQRLogin POST /auth/qr/start — unauthenticated; creates a pending session.
func (h *AuthService) StartQRLogin(c *fiber.Ctx) error {
	var body qrStartBody
	if err := c.BodyParser(&body); err != nil || len(body.VerifierHash) != 64 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "verifier_hash (sha256 hex) required",
		})
	}
	scanSecret, err := randB64(qrScanSecretLn)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to start"})
	}
	sessionID := uuid.New().String()
	now := time.Now()
	s := models.QRLoginSession{
		ID:             uuid.New(),
		SessionID:      sessionID,
		VerifierHash:   body.VerifierHash,
		ScanSecretHash: sha256Hex([]byte(scanSecret)),
		ClientName:     body.ClientName,
		ClientPlatform: body.ClientPlatform,
		RequestIP:      c.IP(),
		ExpiresAt:      now.Add(qrLoginTTL),
		CreatedAt:      now,
	}
	if err := database.DB.Create(&s).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to start"})
	}
	// The scan secret is returned exactly once, to be drawn into the QR. It is
	// never stored in the clear and never appears in any later response.
	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"session_id":  sessionID,
		"scan_secret": scanSecret,
		"expires_at":  s.ExpiresAt,
		"qr_payload":  "qr1." + sessionID + "." + scanSecret,
	})
}

type qrApproveBody struct {
	SessionID  string `json:"session_id"`
	ScanSecret string `json:"scan_secret"`
}

// ApproveQRLogin POST /auth/qr/approve — authenticated; the phone approves.
func (h *AuthService) ApproveQRLogin(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "not authenticated"})
	}
	var body qrApproveBody
	if err := c.BodyParser(&body); err != nil || body.SessionID == "" || body.ScanSecret == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "session_id and scan_secret required",
		})
	}

	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		var s models.QRLoginSession
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("session_id = ?", body.SessionID).First(&s).Error; err != nil {
			return err
		}
		if s.ConsumedAt != nil || time.Now().After(s.ExpiresAt) {
			return gorm.ErrInvalidValue
		}
		// Proves the approver actually scanned the code rather than guessing a
		// session id. Compared as hashes so the stored row is not replayable.
		if sha256Hex([]byte(body.ScanSecret)) != s.ScanSecretHash {
			return gorm.ErrInvalidValue
		}
		if s.ApprovedUserID != nil {
			return nil // idempotent re-approval by the same flow
		}
		now := time.Now()
		s.ApprovedUserID = &userID
		s.ApprovedAt = &now
		return tx.Save(&s).Error
	})
	if txErr != nil {
		// Deliberately uniform: do not reveal whether the session existed,
		// expired, or had the wrong secret.
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid or expired sign-in code"})
	}
	log.Printf("[qr_login] session approved by user %s", userID)
	return c.JSON(fiber.Map{"message": "approved"})
}

// GetQRLoginStatus GET /auth/qr/:session_id — unauthenticated status for the
// displaying client's UI. Reveals only pending/approved/expired, never who.
func (h *AuthService) GetQRLoginStatus(c *fiber.Ctx) error {
	var s models.QRLoginSession
	if err := database.DB.Where("session_id = ?", c.Params("session_id")).First(&s).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "unknown session"})
	}
	status := "pending"
	switch {
	case s.ConsumedAt != nil:
		status = "consumed"
	case time.Now().After(s.ExpiresAt):
		status = "expired"
	case s.ApprovedUserID != nil:
		status = "approved"
	}
	return c.JSON(fiber.Map{"status": status, "expires_at": s.ExpiresAt})
}

type qrClaimBody struct {
	SessionID string `json:"session_id"`
	// The plaintext secret whose hash was registered at start.
	Verifier string `json:"verifier"`
}

// ClaimQRLogin POST /auth/qr/claim — unauthenticated; the displaying client
// exchanges its verifier for tokens. Single-use.
func (h *AuthService) ClaimQRLogin(c *fiber.Ctx) error {
	cfg := config.LoadConfig()
	var body qrClaimBody
	if err := c.BodyParser(&body); err != nil || body.SessionID == "" || body.Verifier == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "session_id and verifier required",
		})
	}

	var approvedUser uuid.UUID
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		var s models.QRLoginSession
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("session_id = ?", body.SessionID).First(&s).Error; err != nil {
			return err
		}
		if s.ConsumedAt != nil || time.Now().After(s.ExpiresAt) || s.ApprovedUserID == nil {
			return gorm.ErrInvalidValue
		}
		// Only the client that started this session knows the verifier.
		if sha256Hex([]byte(body.Verifier)) != s.VerifierHash {
			return gorm.ErrInvalidValue
		}
		now := time.Now()
		s.ConsumedAt = &now
		approvedUser = *s.ApprovedUserID
		return tx.Save(&s).Error
	})
	if txErr != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "not approved"})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", approvedUser).Error; err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "not approved"})
	}
	// Same tokens a password login would issue. The E2EE vault still has to be
	// unlocked separately on this device — signing in never grants message
	// access on its own.
	return issueLoginTokens(c, user, cfg)
}
