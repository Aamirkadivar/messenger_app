package handlers

import (
	"crypto/rand"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/models"
)

// Temporary DEV-only 2FA challenge store. Not for production HA.
type otpChallenge struct {
	UserID    uuid.UUID
	Email     string
	Username  string
	CodeHash  string
	ExpiresAt time.Time
	Purpose   string // "2fa", "totp", or "password_reset"
	Attempts  int
}

var (
	otpMu         sync.Mutex
	otpChallenges = map[string]otpChallenge{} // challengeID -> challenge
)

func purgeExpiredOTPLocked() {
	now := time.Now()
	for id, ch := range otpChallenges {
		if now.After(ch.ExpiresAt) {
			delete(otpChallenges, id)
		}
	}
}

const max2FAAttempts = 8

func failOTPChallenge(id string) {
	otpMu.Lock()
	defer otpMu.Unlock()
	ch, ok := otpChallenges[id]
	if !ok {
		return
	}
	ch.Attempts++
	if ch.Attempts >= max2FAAttempts {
		delete(otpChallenges, id)
		return
	}
	otpChallenges[id] = ch
}

func generateOTPCode() (string, error) {
	var b [4]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	n := int(b[0])<<16 | int(b[1])<<8 | int(b[2])
	n = n % 1000000
	return fmt.Sprintf("%06d", n), nil
}

// Dev2FAEnabled is true only in development when explicitly turned on.
func Dev2FAEnabled(cfg *config.Config) bool {
	return cfg.Env == "development" && cfg.Dev2FAEnabled
}

// issueDev2FAChallenge creates a challenge and relays the code to the configured username.
func issueDev2FAChallenge(user models.User, cfg *config.Config) (challengeID string, err error) {
	code, err := generateOTPCode()
	if err != nil {
		return "", err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.MinCost)
	if err != nil {
		return "", err
	}
	id := uuid.New().String()

	otpMu.Lock()
	purgeExpiredOTPLocked()
	otpChallenges[id] = otpChallenge{
		UserID:    user.ID,
		Email:     user.Email,
		Username:  user.Username,
		CodeHash:  string(hash),
		ExpiresAt: time.Now().Add(5 * time.Minute),
		Purpose:   "2fa",
	}
	otpMu.Unlock()

	relayDevOTPCode(cfg, user, code, id, "2FA")
	return id, nil
}

func issuePasswordResetChallenge(user models.User, cfg *config.Config) (challengeID string, err error) {
	code, err := generateOTPCode()
	if err != nil {
		return "", err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.MinCost)
	if err != nil {
		return "", err
	}
	id := uuid.New().String()

	otpMu.Lock()
	purgeExpiredOTPLocked()
	otpChallenges[id] = otpChallenge{
		UserID:    user.ID,
		Email:     user.Email,
		Username:  user.Username,
		CodeHash:  string(hash),
		ExpiresAt: time.Now().Add(5 * time.Minute),
		Purpose:   "password_reset",
	}
	otpMu.Unlock()

	relayDevOTPCode(cfg, user, code, id, "password reset")
	return id, nil
}

func relayDevOTPCode(cfg *config.Config, forUser models.User, code, challengeID, kind string) {
	relayUser := cfg.Dev2FARelayUsername
	if relayUser == "" {
		relayUser = "koueosh"
	}

	// Always log in development when 2FA is on — tester convenience.
	log.Printf("[dev_2fa_relay] kind=%s challenge=%s account=%s (@%s) code=%s relay_to=@%s",
		kind, challengeID, forUser.Email, forUser.Username, code, relayUser)

	var dest models.User
	if err := database.DB.Where("username = ?", relayUser).First(&dest).Error; err != nil {
		log.Printf("[dev_2fa_relay] relay user @%s not found — code only in server log", relayUser)
		return
	}

	bot := ensureDev2FABot()
	if bot == nil {
		return
	}

	chatID := ensureDirectChat(bot.ID, dest.ID)
	if chatID == "" {
		return
	}

	body := fmt.Sprintf(
		"DEV %s code for %s (@%s):\n%s\n\nChallenge: %s\nExpires in 5 minutes.\n(This bot is development-only.)",
		kind, forUser.Email, forUser.Username, code, challengeID,
	)
	msg := models.Message{
		ID:               uuid.New(),
		ChatID:           chatID,
		SenderID:         bot.ID,
		EncryptedContent: body,
		IsEncrypted:      false,
		ContentType:      "text",
		ChatType:         "direct",
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}
	// GORM skips bool false (zero value) and the column default is true —
	// Select forces plaintext so clients don't try to crypto_box-decrypt it.
	if err := database.DB.Select(
		"ID", "ChatID", "SenderID", "EncryptedContent", "IsEncrypted",
		"ContentType", "ChatType", "CreatedAt", "UpdatedAt",
	).Create(&msg).Error; err != nil {
		log.Printf("[dev_2fa_relay] failed to insert bot message: %v", err)
		return
	}
	_ = database.DB.Model(&msg).UpdateColumn("is_encrypted", false).Error
	now := time.Now()
	database.DB.Model(&models.Chat{}).Where("id = ?", chatID).Updates(map[string]interface{}{
		"last_message_at": now,
		"updated_at":      now,
	})
}

func ensureDev2FABot() *models.User {
	const botUsername = "messenger_2fa_bot"
	var bot models.User
	if err := database.DB.Where("username = ?", botUsername).First(&bot).Error; err == nil {
		return &bot
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(uuid.New().String()), bcrypt.DefaultCost)
	if err != nil {
		return nil
	}
	bot = models.User{
		ID:           uuid.New(),
		Email:        "2fa-bot@localhost.invalid",
		Username:     botUsername,
		DisplayName:  "2FA Bot (DEV)",
		PasswordHash: string(hash),
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
	}
	if err := database.DB.Create(&bot).Error; err != nil {
		log.Printf("[dev_2fa_relay] failed to create bot user: %v", err)
		return nil
	}
	return &bot
}

func ensureDirectChat(a, b uuid.UUID) string {
	var chat models.Chat
	err := database.DB.
		Joins("JOIN chat_participants cp1 ON cp1.chat_id = chats.id::uuid AND cp1.user_id = ?", a).
		Joins("JOIN chat_participants cp2 ON cp2.chat_id = chats.id::uuid AND cp2.user_id = ?", b).
		Where("chats.type = ?", "direct").
		Order("chats.created_at ASC").
		First(&chat).Error
	if err == nil {
		database.DB.Model(&models.ChatParticipant{}).
			Where("chat_id = ? AND user_id = ?", chat.ID, b).
			Update("left_at", nil)
		return chat.ID
	}

	chatID := uuid.New().String()
	chat = models.Chat{
		ID:        chatID,
		Type:      "direct",
		Name:      "Direct Chat",
		OwnerID:   a,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
	if err := database.DB.Create(&chat).Error; err != nil {
		return ""
	}
	database.DB.Create(&models.ChatParticipant{ChatID: chatID, UserID: a, JoinedAt: time.Now()})
	database.DB.Create(&models.ChatParticipant{ChatID: chatID, UserID: b, JoinedAt: time.Now()})
	return chatID
}

func issueTotpLoginChallenge(user models.User) string {
	id := uuid.New().String()
	otpMu.Lock()
	purgeExpiredOTPLocked()
	otpChallenges[id] = otpChallenge{
		UserID:    user.ID,
		Email:     user.Email,
		Username:  user.Username,
		ExpiresAt: time.Now().Add(5 * time.Minute),
		Purpose:   "totp",
	}
	otpMu.Unlock()
	return id
}

type Verify2FAInput struct {
	ChallengeID string `json:"challenge_id"`
	Code        string `json:"code"`
}

// Verify2FA completes TOTP or DEV OTP and issues tokens.
func (h *AuthService) Verify2FA(c *fiber.Ctx) error {
	cfg := config.LoadConfig()

	var input Verify2FAInput
	if err := c.BodyParser(&input); err != nil || input.ChallengeID == "" || input.Code == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "challenge_id and code required"})
	}

	otpMu.Lock()
	purgeExpiredOTPLocked()
	ch, ok := otpChallenges[input.ChallengeID]
	otpMu.Unlock()
	if !ok || time.Now().After(ch.ExpiresAt) {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}

	if ch.Purpose == "totp" {
		var user models.User
		if err := database.DB.First(&user, "id = ?", ch.UserID).Error; err != nil {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
		}
		if !user.TotpEnabled || (!validateTotp(user.TotpSecret, input.Code) && !consumeBackupCode(&user, input.Code)) {
			failOTPChallenge(input.ChallengeID)
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
		}
		otpMu.Lock()
		delete(otpChallenges, input.ChallengeID)
		otpMu.Unlock()
		return issueLoginTokens(c, user, cfg)
	}

	if ch.Purpose != "2fa" {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}
	if !Dev2FAEnabled(cfg) {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "2FA not enabled"})
	}
	if err := bcrypt.CompareHashAndPassword([]byte(ch.CodeHash), []byte(input.Code)); err != nil {
		failOTPChallenge(input.ChallengeID)
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}
	otpMu.Lock()
	delete(otpChallenges, input.ChallengeID)
	otpMu.Unlock()

	var user models.User
	if err := database.DB.First(&user, "id = ?", ch.UserID).Error; err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid or expired code"})
	}
	return issueLoginTokens(c, user, cfg)
}