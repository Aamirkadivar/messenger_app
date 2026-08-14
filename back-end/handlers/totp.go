package handlers

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base32"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

const totpPeriod = 30

type pendingTotpSetup struct {
	Secret    string
	ExpiresAt time.Time
}

var (
	totpSetupMu sync.Mutex
	totpSetups  = map[uuid.UUID]pendingTotpSetup{}
)

func generateTotpSecret() (string, error) {
	raw := make([]byte, 20)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw), nil
}

func totpCodeAt(secret string, unix int64) (string, error) {
	sec := strings.ToUpper(strings.TrimSpace(secret))
	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(sec)
	if err != nil {
		return "", err
	}
	counter := uint64(unix / totpPeriod)
	var buf [8]byte
	binary.BigEndian.PutUint64(buf[:], counter)
	mac := hmac.New(sha1.New, key)
	mac.Write(buf[:])
	sum := mac.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	bin := binary.BigEndian.Uint32(sum[off:off+4]) & 0x7fffffff
	return fmt.Sprintf("%06d", bin%1000000), nil
}

func validateTotp(secret, code string) bool {
	code = strings.TrimSpace(code)
	if len(code) != 6 || secret == "" {
		return false
	}
	now := time.Now().Unix()
	for _, skew := range []int64{-1, 0, 1} {
		want, err := totpCodeAt(secret, now+skew*totpPeriod)
		if err == nil && hmac.Equal([]byte(want), []byte(code)) {
			return true
		}
	}
	return false
}

func putPendingTotp(userID uuid.UUID, secret string) {
	totpSetupMu.Lock()
	defer totpSetupMu.Unlock()
	totpSetups[userID] = pendingTotpSetup{Secret: secret, ExpiresAt: time.Now().Add(10 * time.Minute)}
}

func peekPendingTotp(userID uuid.UUID) (string, bool) {
	totpSetupMu.Lock()
	defer totpSetupMu.Unlock()
	p, ok := totpSetups[userID]
	if !ok || time.Now().After(p.ExpiresAt) {
		delete(totpSetups, userID)
		return "", false
	}
	return p.Secret, true
}

func clearPendingTotp(userID uuid.UUID) {
	totpSetupMu.Lock()
	delete(totpSetups, userID)
	totpSetupMu.Unlock()
}

func otpauthURL(email, secret string) string {
	return fmt.Sprintf("otpauth://totp/Messenger:%s?secret=%s&issuer=Messenger&digits=6&period=30",
		url.QueryEscape(email), secret)
}

func (h *AuthService) TotpStatus(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var user models.User
	if err := database.DB.Select("totp_enabled").First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}
	return c.JSON(fiber.Map{"totp_enabled": user.TotpEnabled})
}

func (h *AuthService) TotpSetup(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}
	if user.TotpEnabled {
		return c.Status(http.StatusConflict).JSON(fiber.Map{"error": "authenticator already enabled"})
	}
	secret, err := generateTotpSecret()
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to generate secret"})
	}
	putPendingTotp(userID, secret)
	return c.JSON(fiber.Map{
		"secret":      secret,
		"otpauth_url": otpauthURL(user.Email, secret),
	})
}

func (h *AuthService) TotpConfirm(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body struct {
		Code string `json:"code"`
	}
	if err := c.BodyParser(&body); err != nil || strings.TrimSpace(body.Code) == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "code required"})
	}
	secret, ok := peekPendingTotp(userID)
	if !ok {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "setup expired; start again"})
	}
	if !validateTotp(secret, body.Code) {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "invalid code"})
	}
	clearPendingTotp(userID)
	plain, hashes, err := generateBackupCodes(8)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to generate backup codes"})
	}
	hashJSON, _ := json.Marshal(hashes)
	if err := database.DB.Model(&models.User{}).Where("id = ?", userID).Updates(map[string]interface{}{
		"totp_secret":        secret,
		"totp_enabled":       true,
		"totp_backup_hashes": string(hashJSON),
	}).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to enable 2FA"})
	}
	return c.JSON(fiber.Map{"totp_enabled": true, "backup_codes": plain})
}

func (h *AuthService) TotpDisable(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body struct {
		Password string `json:"password"`
		Code     string `json:"code"`
	}
	if err := c.BodyParser(&body); err != nil || body.Password == "" || body.Code == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "password and code required"})
	}
	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(body.Password)); err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "invalid password"})
	}
	if !user.TotpEnabled || (!validateTotp(user.TotpSecret, body.Code) && !consumeBackupCode(&user, body.Code)) {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "invalid code"})
	}
	if err := database.DB.Model(&user).Updates(map[string]interface{}{
		"totp_secret":        "",
		"totp_enabled":       false,
		"totp_backup_hashes": "",
	}).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to disable 2FA"})
	}
	return c.JSON(fiber.Map{"totp_enabled": false})
}

func (h *AuthService) TotpRegenerateBackupCodes(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body struct {
		Password string `json:"password"`
		Code     string `json:"code"`
	}
	if err := c.BodyParser(&body); err != nil || body.Password == "" || body.Code == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "password and code required"})
	}
	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}
	if !user.TotpEnabled {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "authenticator is not enabled"})
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(body.Password)); err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "invalid password"})
	}
	if !validateTotp(user.TotpSecret, body.Code) && !consumeBackupCode(&user, body.Code) {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "invalid code"})
	}
	plain, hashes, err := generateBackupCodes(8)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to generate backup codes"})
	}
	hashJSON, _ := json.Marshal(hashes)
	if err := database.DB.Model(&user).Update("totp_backup_hashes", string(hashJSON)).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to save backup codes"})
	}
	return c.JSON(fiber.Map{"totp_enabled": true, "backup_codes": plain})
}

const backupAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

func normalizeBackupCode(s string) string {
	s = strings.ToUpper(strings.TrimSpace(s))
	s = strings.ReplaceAll(s, "-", "")
	s = strings.ReplaceAll(s, " ", "")
	return s
}

func formatBackupCode(raw string) string {
	if len(raw) == 8 {
		return raw[:4] + "-" + raw[4:]
	}
	return raw
}

func generateBackupCodes(n int) (plain []string, hashes []string, err error) {
	plain = make([]string, 0, n)
	hashes = make([]string, 0, n)
	for i := 0; i < n; i++ {
		buf := make([]byte, 8)
		if _, err = rand.Read(buf); err != nil {
			return nil, nil, err
		}
		raw := make([]byte, 8)
		for j := 0; j < 8; j++ {
			raw[j] = backupAlphabet[int(buf[j])%len(backupAlphabet)]
		}
		code := string(raw)
		hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.DefaultCost)
		if err != nil {
			return nil, nil, err
		}
		plain = append(plain, formatBackupCode(code))
		hashes = append(hashes, string(hash))
	}
	return plain, hashes, nil
}

func consumeBackupCode(user *models.User, code string) bool {
	if user == nil {
		return false
	}
	norm := normalizeBackupCode(code)
	if len(norm) != 8 {
		return false
	}
	var hashes []string
	if user.TotpBackupHashes != "" {
		_ = json.Unmarshal([]byte(user.TotpBackupHashes), &hashes)
	}
	for i, h := range hashes {
		if bcrypt.CompareHashAndPassword([]byte(h), []byte(norm)) != nil {
			continue
		}
		hashes = append(hashes[:i], hashes[i+1:]...)
		b, _ := json.Marshal(hashes)
		user.TotpBackupHashes = string(b)
		if database.DB != nil {
			database.DB.Model(user).Update("totp_backup_hashes", user.TotpBackupHashes)
		}
		return true
	}
	return false
}


