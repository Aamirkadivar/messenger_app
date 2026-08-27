package handlers

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// ===================================== GATE 15: session lifecycle audit
//
// READ-ONLY. These tests do not change production behaviour; they establish,
// against the real middleware and a real database, exactly how long a credential
// stays useful and what (if anything) can withdraw it.

// gate15Env builds a real user plus a real AuthMiddleware-protected app, so the
// credential checks under test are the production ones.
type gate15Env struct {
	app   *fiber.App
	auth  *AuthService
	cfg   *config.Config
	user  models.User
	token string
}

func gate15Setup(t *testing.T) *gate15Env {
	t.Helper()
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	if err := models.MigrateDB(db); err != nil {
		t.Fatalf("MigrateDB: %v", err)
	}
	prev := database.DB
	database.DB = db
	t.Cleanup(func() {
		database.DB = prev
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
	})

	cfg := config.LoadConfig()
	u := models.User{
		ID: uuid.New(), Username: "g15" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "G15",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := db.Create(&u).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}

	app := fiber.New()
	app.Post("/auth/refresh", NewAuthService().RefreshToken)
	protected := app.Group("")
	protected.Use(middleware.AuthMiddleware(cfg))
	protected.Get("/whoami", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"user_id": middleware.GetCurrentUserID(c)})
	})

	access, err := middleware.GenerateToken(u.ID, u.Email, u.DisplayName, cfg)
	if err != nil {
		t.Fatalf("generate access token: %v", err)
	}
	return &gate15Env{app: app, auth: NewAuthService(), cfg: cfg, user: u, token: access}
}

// bearer calls a protected route with the supplied token.
func (e *gate15Env) bearer(t *testing.T, token string) int {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/whoami", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

// refresh posts a token to the refresh endpoint and returns status plus the
// freshly minted pair, if any.
func (e *gate15Env) refresh(t *testing.T, token string) (int, string, string) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"refresh_token": token})
	req := httptest.NewRequest(http.MethodPost, "/auth/refresh", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	raw, _ := io.ReadAll(resp.Body)
	var parsed struct {
		Tokens struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		} `json:"tokens"`
	}
	_ = json.Unmarshal(raw, &parsed)
	return resp.StatusCode, parsed.Tokens.AccessToken, parsed.Tokens.RefreshToken
}

// ---------------------------------------------------------------- token confusion
//
// GenerateToken and GenerateRefreshToken emit the SAME JWTClaims type, signed
// with the SAME secret and algorithm, and nothing in the claims marks which is
// which. AuthMiddleware verifies only the signature and expiry.
func TestGate15_RefreshTokenIsAcceptedAsAnAccessToken(t *testing.T) {
	e := gate15Setup(t)
	refreshTok, err := middleware.GenerateRefreshToken(e.user.ID, e.cfg)
	if err != nil {
		t.Fatalf("generate refresh token: %v", err)
	}

	code := e.bearer(t, refreshTok)
	if code == http.StatusUnauthorized {
		t.Logf("SAFE: a refresh token is rejected as a bearer credential")
		return
	}
	t.Errorf("PROVEN BROKEN: a REFRESH token authenticated as an ACCESS token (status %d). "+
		"Access TTL is %dh but refresh TTL is %dh, so this hands the holder a %dx longer "+
		"credential on every protected route.",
		code, e.cfg.JWTExpiration, e.cfg.RefreshTokenExpiration,
		e.cfg.RefreshTokenExpiration/e.cfg.JWTExpiration)
}

func TestGate15_AccessTokenIsAcceptedAsARefreshToken(t *testing.T) {
	e := gate15Setup(t)
	code, newAccess, newRefresh := e.refresh(t, e.token)
	if code != http.StatusOK {
		t.Logf("SAFE: an access token is rejected by the refresh endpoint (%d)", code)
		return
	}
	if newAccess == "" || newRefresh == "" {
		t.Fatalf("refresh returned %d but no tokens", code)
	}
	t.Errorf("PROVEN BROKEN: an ACCESS token was accepted by /auth/refresh (status %d) and "+
		"minted a fresh access+refresh pair. The two credential types are interchangeable "+
		"in both directions.", code)
}

// ---------------------------------------------------------------- replay matrix
func TestGate15_RefreshReplayMatrix(t *testing.T) {
	e := gate15Setup(t)
	seed, err := middleware.GenerateRefreshToken(e.user.ID, e.cfg)
	if err != nil {
		t.Fatalf("generate: %v", err)
	}

	t.Run("same refresh token twice sequentially", func(t *testing.T) {
		c1, _, _ := e.refresh(t, seed)
		c2, _, _ := e.refresh(t, seed)
		t.Logf("refresh #1=%d  refresh #2=%d", c1, c2)
		if c1 == http.StatusOK && c2 == http.StatusOK {
			t.Errorf("PROVEN BROKEN: a refresh token is replayable; it is not rotated or consumed")
		}
	})

	t.Run("old refresh token still valid after rotation", func(t *testing.T) {
		c1, _, rotated := e.refresh(t, seed)
		if c1 != http.StatusOK || rotated == "" {
			t.Skip("refresh did not succeed; covered elsewhere")
		}
		cOld, _, _ := e.refresh(t, seed)
		cNew, _, _ := e.refresh(t, rotated)
		t.Logf("after rotation: old=%d new=%d", cOld, cNew)
		if cOld == http.StatusOK {
			t.Errorf("PROVEN BROKEN: the superseded refresh token remains valid after rotation")
		}
	})

	t.Run("refresh after password change", func(t *testing.T) {
		if err := database.DB.Model(&models.User{}).Where("id = ?", e.user.ID).
			Update("password_hash", "COMPLETELY-NEW-HASH").Error; err != nil {
			t.Fatalf("password change: %v", err)
		}
		code, _, _ := e.refresh(t, seed)
		bearerCode := e.bearer(t, e.token)
		t.Logf("after password change: refresh=%d  existing access token=%d", code, bearerCode)
		if code == http.StatusOK {
			t.Errorf("PROVEN BROKEN: a stolen refresh token still mints credentials after " +
				"the password was replaced")
		}
		if bearerCode == http.StatusOK {
			t.Errorf("PROVEN BROKEN: an existing access token still authenticates after " +
				"the password was replaced")
		}
	})

	t.Run("refresh after every device is revoked", func(t *testing.T) {
		now := time.Now()
		database.DB.Create(&models.E2EEDevice{
			ID: uuid.New(), UserID: e.user.ID, DeviceID: "g15-dev",
			RevokedAt: &now, CreatedAt: now, UpdatedAt: now,
		})
		code, _, _ := e.refresh(t, seed)
		t.Logf("after device revocation: refresh=%d", code)
		if code == http.StatusOK {
			t.Errorf("PROVEN BROKEN: refresh succeeds after device revocation; refresh " +
				"consults no device state")
		}
	})
}

// A revoked credential minting an unbroken chain of fresh credentials is the
// concrete form of "indefinitely useful". Ten generations, no state consulted.
func TestGate15_RevokedCredentialMintsAnUnboundedChain(t *testing.T) {
	e := gate15Setup(t)
	now := time.Now()
	database.DB.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.user.ID, DeviceID: "g15-revoked",
		RevokedAt: &now, CreatedAt: now, UpdatedAt: now,
	})
	database.DB.Model(&models.User{}).Where("id = ?", e.user.ID).
		Update("password_hash", "ROTATED-AFTER-COMPROMISE")

	current, err := middleware.GenerateRefreshToken(e.user.ID, e.cfg)
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	generations := 0
	for i := 0; i < 10; i++ {
		code, access, next := e.refresh(t, current)
		if code != http.StatusOK || next == "" {
			break
		}
		if e.bearer(t, access) != http.StatusOK {
			break
		}
		generations++
		current = next
	}
	if generations == 0 {
		t.Logf("SAFE: the chain could not be extended")
		return
	}
	t.Errorf("PROVEN BROKEN: a credential belonging to a revoked device, after a password "+
		"change, minted %d consecutive working generations. Nothing in the system can "+
		"withdraw it before its %dh expiry.", generations, e.cfg.RefreshTokenExpiration)
}

// ---------------------------------------------------------------- cross-account
// The one boundary proven safe in earlier gates must remain safe.
func TestGate15_CrossAccountCredentialIsolation(t *testing.T) {
	e := gate15Setup(t)
	other := models.User{
		ID: uuid.New(), Username: "g15b" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "Other",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := database.DB.Create(&other).Error; err != nil {
		t.Fatalf("seed other: %v", err)
	}

	// A token minted for account A must resolve to A, never to B.
	req := httptest.NewRequest(http.MethodGet, "/whoami", nil)
	req.Header.Set("Authorization", "Bearer "+e.token)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	raw, _ := io.ReadAll(resp.Body)
	var got struct {
		UserID uuid.UUID `json:"user_id"`
	}
	_ = json.Unmarshal(raw, &got)
	if got.UserID != e.user.ID {
		t.Fatalf("CROSS-ACCOUNT CONFUSION: token for %s resolved to %s", e.user.ID, got.UserID)
	}

	// A token signed with a different secret must not authenticate at all.
	forged, err := middleware.GenerateToken(other.ID, other.Email, other.DisplayName,
		&config.Config{JWTSecret: "a-different-signing-secret", JWTExpiration: 24})
	if err != nil {
		t.Fatalf("generate forged: %v", err)
	}
	if code := e.bearer(t, forged); code != http.StatusUnauthorized {
		t.Fatalf("a token signed with the wrong secret authenticated (%d)", code)
	}
	t.Log("PROVEN SAFE: credentials resolve to their own account; wrong-secret tokens refused")
}
