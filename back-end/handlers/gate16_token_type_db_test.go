package handlers

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// ============================ GATE 16 Priority 1: token-type separation
//
// Access and refresh tokens were the same credential with two lifetimes: same
// claims type, same secret, same algorithm, nothing recording intent. A refresh
// token authorized every protected route (24h -> 168h) and an access token
// renewed itself at /auth/refresh.
//
// Every test here drives the REAL middleware and REAL handlers. None of them
// decodes a token and inspects claims: a claim that nothing enforces is not a
// security control, so the assertions are always about what the verifier did.

// assertScratchDB fails closed unless the configured database is the scratch
// one. Several tests below drop and recreate the schema.
func assertScratchDB(t *testing.T) {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Fatal("TEST_DATABASE_URL is not set; refusing to run against an unknown database")
	}
	if !strings.Contains(url, "messenger_e2ee_scratch") {
		t.Fatalf("refusing to run: TEST_DATABASE_URL does not target messenger_e2ee_scratch")
	}
	if strings.Contains(url, "/messenger?") || strings.HasSuffix(url, "/messenger") {
		t.Fatal("refusing to run against the live messenger database")
	}
}

type gate16Env struct {
	app  *fiber.App
	cfg  *config.Config
	user models.User
}

func gate16Setup(t *testing.T) *gate16Env {
	t.Helper()
	assertScratchDB(t)

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
		ID: uuid.New(), Username: "g16" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "G16",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := db.Create(&u).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}

	// Mirrors main.go's structure: the protected group lives under /api/v1, and
	// /ws is mounted on the root app with only WebSocketAuth in front of it. A
	// flat app.Group("") here would mount AuthMiddleware at "/" and wrongly
	// capture /ws too, which is a harness artifact rather than real behaviour.
	auth := NewAuthService()
	app := fiber.New()
	api := app.Group("/api/v1")
	api.Post("/auth/refresh", auth.RefreshToken)
	api.Post("/auth/qr/start", auth.StartQRLogin)
	api.Post("/auth/qr/claim", auth.ClaimQRLogin)
	api.Post("/auth/2fa/verify", auth.Verify2FA)

	// The real access-token surface.
	protected := api.Group("")
	protected.Use(middleware.AuthMiddleware(cfg))
	protected.Get("/protected", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"user_id": middleware.GetCurrentUserID(c)})
	})
	// QR approval is authenticated, so it sits behind the access-token middleware.
	protected.Post("/auth/qr/approve", auth.ApproveQRLogin)

	// The real WebSocket surface: separate verification path, token from a query
	// parameter, mounted outside the protected group exactly as in main.go.
	app.Get("/ws", middleware.WebSocketAuth(cfg), func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"user_id": middleware.GetCurrentUserID(c)})
	})

	return &gate16Env{app: app, cfg: cfg, user: u}
}

func (e *gate16Env) do(t *testing.T, method, path, body, bearer string) (int, string) {
	t.Helper()
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, path, nil)
	} else {
		req = httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(raw)
}

func (e *gate16Env) protectedWith(t *testing.T, token string) int {
	code, _ := e.do(t, http.MethodGet, "/api/v1/protected", "", token)
	return code
}

func (e *gate16Env) wsWith(t *testing.T, token string) int {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/ws?token="+token, nil)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func (e *gate16Env) refreshWith(t *testing.T, token string) (int, string, string) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"refresh_token": token})
	code, raw := e.do(t, http.MethodPost, "/api/v1/auth/refresh", string(body), "")
	var parsed struct {
		Tokens struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		} `json:"tokens"`
	}
	_ = json.Unmarshal([]byte(raw), &parsed)
	return code, parsed.Tokens.AccessToken, parsed.Tokens.RefreshToken
}

// mintRaw signs a token with the production secret but arbitrary claims, so the
// signature is genuine and only the token_use value is under test.
func (e *gate16Env) mintRaw(t *testing.T, use interface{}, exp time.Time, secret string) string {
	t.Helper()
	claims := jwt.MapClaims{
		"user_id": e.user.ID.String(),
		"email":   e.user.Email,
		"exp":     exp.Unix(),
		"iat":     time.Now().Unix(),
		"nbf":     time.Now().Add(-time.Minute).Unix(),
	}
	if use != nil {
		claims["token_use"] = use
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := tok.SignedString([]byte(secret))
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return signed
}

// ------------------------------------------------------------ 1-4, 11-14
func TestGate16_TokenTypeBoundary(t *testing.T) {
	e := gate16Setup(t)
	access, err := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.cfg)
	if err != nil {
		t.Fatalf("access: %v", err)
	}
	refresh, err := middleware.GenerateRefreshToken(e.user.ID, e.cfg)
	if err != nil {
		t.Fatalf("refresh: %v", err)
	}

	t.Run("1 access to protected route is allowed", func(t *testing.T) {
		if code := e.protectedWith(t, access); code != http.StatusOK {
			t.Errorf("expected 200, got %d", code)
		}
	})

	t.Run("2 refresh to protected route is denied", func(t *testing.T) {
		if code := e.protectedWith(t, refresh); code != http.StatusUnauthorized {
			t.Errorf("GATE15 EXPLOIT STILL OPEN: refresh token accepted on a protected "+
				"route (%d); expected 401", code)
		}
	})

	t.Run("3 refresh to /auth/refresh is allowed", func(t *testing.T) {
		code, newAccess, newRefresh := e.refreshWith(t, refresh)
		if code != http.StatusOK {
			t.Fatalf("expected 200, got %d", code)
		}
		if newAccess == "" || newRefresh == "" {
			t.Fatal("refresh returned 200 but no tokens")
		}
		// The minted pair must itself obey the boundary.
		if c := e.protectedWith(t, newAccess); c != http.StatusOK {
			t.Errorf("minted access token rejected on protected route (%d)", c)
		}
		if c := e.protectedWith(t, newRefresh); c != http.StatusUnauthorized {
			t.Errorf("minted refresh token accepted on protected route (%d)", c)
		}
	})

	t.Run("4 access to /auth/refresh is denied", func(t *testing.T) {
		code, a, r := e.refreshWith(t, access)
		if code != http.StatusUnauthorized {
			t.Errorf("GATE15 EXPLOIT STILL OPEN: access token accepted by /auth/refresh "+
				"(%d) minting access=%v refresh=%v", code, a != "", r != "")
		}
	})

	t.Run("11 websocket accepts an access token", func(t *testing.T) {
		if code := e.wsWith(t, access); code != http.StatusOK {
			t.Errorf("expected 200, got %d", code)
		}
	})

	t.Run("12 websocket denies a refresh token", func(t *testing.T) {
		if code := e.wsWith(t, refresh); code != http.StatusUnauthorized {
			t.Errorf("refresh token accepted by WebSocketAuth (%d); expected 401", code)
		}
	})

	t.Run("13 expired access token is denied", func(t *testing.T) {
		expired := e.mintRaw(t, "access", time.Now().Add(-time.Hour), e.cfg.JWTSecret)
		if code := e.protectedWith(t, expired); code != http.StatusUnauthorized {
			t.Errorf("expired token accepted (%d)", code)
		}
		if code := e.wsWith(t, expired); code != http.StatusUnauthorized {
			t.Errorf("expired token accepted by WebSocketAuth (%d)", code)
		}
	})

	t.Run("14 wrong signature is denied", func(t *testing.T) {
		forged := e.mintRaw(t, "access", time.Now().Add(time.Hour), "not-the-real-secret")
		if code := e.protectedWith(t, forged); code != http.StatusUnauthorized {
			t.Errorf("wrong-secret token accepted (%d)", code)
		}
		fr, _, _ := e.refreshWith(t, e.mintRaw(t, "refresh", time.Now().Add(time.Hour), "not-the-real-secret"))
		if fr != http.StatusUnauthorized {
			t.Errorf("wrong-secret refresh token accepted (%d)", fr)
		}
	})
}

// ------------------------------------------------------------ 5-6 fail closed
func TestGate16_MissingOrUnknownTokenUseFailsClosed(t *testing.T) {
	e := gate16Setup(t)
	future := time.Now().Add(time.Hour)

	cases := []struct {
		name string
		use  interface{}
	}{
		{"5 missing token_use (a legacy token)", nil},
		{"6a empty token_use", ""},
		{"6b unknown token_use", "superuser"},
		{"6c wrong-typed token_use", 12345},
		{"6d case-mismatched token_use", "Access"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			tok := e.mintRaw(t, tc.use, future, e.cfg.JWTSecret)
			if code := e.protectedWith(t, tok); code != http.StatusUnauthorized {
				t.Errorf("protected route accepted %v token_use (%d)", tc.use, code)
			}
			if code := e.wsWith(t, tok); code != http.StatusUnauthorized {
				t.Errorf("websocket accepted %v token_use (%d)", tc.use, code)
			}
			if code, _, _ := e.refreshWith(t, tok); code != http.StatusUnauthorized {
				t.Errorf("/auth/refresh accepted %v token_use (%d)", tc.use, code)
			}
		})
	}
}

// ------------------------------------------------------------ 7 cross-account
func TestGate16_CrossAccountIsolationStillSafe(t *testing.T) {
	e := gate16Setup(t)
	other := models.User{
		ID: uuid.New(), Username: "g16b" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "Other",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := database.DB.Create(&other).Error; err != nil {
		t.Fatalf("seed other: %v", err)
	}
	access, _ := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.cfg)

	code, raw := e.do(t, http.MethodGet, "/api/v1/protected", "", access)
	if code != http.StatusOK {
		t.Fatalf("expected 200, got %d", code)
	}
	var got struct {
		UserID uuid.UUID `json:"user_id"`
	}
	_ = json.Unmarshal([]byte(raw), &got)
	if got.UserID != e.user.ID {
		t.Fatalf("CROSS-ACCOUNT CONFUSION: token for %s resolved to %s", e.user.ID, got.UserID)
	}
	if got.UserID == other.ID {
		t.Fatal("CROSS-ACCOUNT CONFUSION: resolved to the other account")
	}
	t.Log("PROVEN SAFE: token resolves to its own account under the new claim")
}

// ------------------------------------------------------------ 8-9 QR ceremony
func TestGate16_QRIssuedTokensObeyTheBoundary(t *testing.T) {
	e := gate16Setup(t)

	verifier := "verifier-" + uuid.New().String()
	sum := sha256.Sum256([]byte(verifier))
	startBody, _ := json.Marshal(map[string]string{
		"verifier_hash": hex.EncodeToString(sum[:]),
		"client_name":   "g16", "client_platform": "test",
	})
	code, raw := e.do(t, http.MethodPost, "/api/v1/auth/qr/start", string(startBody), "")
	if code != http.StatusCreated {
		t.Fatalf("qr start: expected 201, got %d (%s)", code, raw)
	}
	var start struct {
		SessionID  string `json:"session_id"`
		ScanSecret string `json:"scan_secret"`
	}
	if err := json.Unmarshal([]byte(raw), &start); err != nil {
		t.Fatalf("qr start decode: %v", err)
	}

	// Approval is authenticated: it needs a real ACCESS token, which itself
	// exercises the new boundary on the approving side.
	approver, _ := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.cfg)
	appBody, _ := json.Marshal(map[string]string{
		"session_id": start.SessionID, "scan_secret": start.ScanSecret,
	})
	if code, raw = e.do(t, http.MethodPost, "/api/v1/auth/qr/approve", string(appBody), approver); code != http.StatusOK {
		t.Fatalf("qr approve: expected 200, got %d (%s)", code, raw)
	}

	claimBody, _ := json.Marshal(map[string]string{
		"session_id": start.SessionID, "verifier": verifier,
	})
	code, raw = e.do(t, http.MethodPost, "/api/v1/auth/qr/claim", string(claimBody), "")
	if code != http.StatusOK {
		t.Fatalf("qr claim: expected 200, got %d (%s)", code, raw)
	}
	var issued struct {
		Tokens struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		} `json:"tokens"`
	}
	if err := json.Unmarshal([]byte(raw), &issued); err != nil {
		t.Fatalf("qr claim decode: %v", err)
	}

	// 8: the QR-issued access token behaves as an access token, and only that.
	if c := e.protectedWith(t, issued.Tokens.AccessToken); c != http.StatusOK {
		t.Errorf("QR access token rejected on protected route (%d)", c)
	}
	if c, _, _ := e.refreshWith(t, issued.Tokens.AccessToken); c != http.StatusUnauthorized {
		t.Errorf("QR access token accepted by /auth/refresh (%d)", c)
	}
	// 9: the QR-issued refresh token behaves as a refresh token, and only that.
	if c := e.protectedWith(t, issued.Tokens.RefreshToken); c != http.StatusUnauthorized {
		t.Errorf("QR refresh token accepted on protected route (%d)", c)
	}
	if c, _, _ := e.refreshWith(t, issued.Tokens.RefreshToken); c != http.StatusOK {
		t.Errorf("QR refresh token rejected by /auth/refresh (%d)", c)
	}

	// Preserved from the existing ceremony: the session is single-use.
	if c, _ := e.do(t, http.MethodPost, "/api/v1/auth/qr/claim", string(claimBody), ""); c == http.StatusOK {
		t.Error("a consumed QR session was claimable twice")
	}
}

// ------------------------------------------------------------ 10 2FA ceremony
func TestGate16_TwoFactorIssuedTokensObeyTheBoundary(t *testing.T) {
	e := gate16Setup(t)

	secret, err := generateTotpSecret()
	if err != nil {
		t.Fatalf("totp secret: %v", err)
	}
	if err := database.DB.Model(&models.User{}).Where("id = ?", e.user.ID).
		Updates(map[string]interface{}{"totp_enabled": true, "totp_secret": secret}).Error; err != nil {
		t.Fatalf("enable totp: %v", err)
	}
	var user models.User
	if err := database.DB.First(&user, "id = ?", e.user.ID).Error; err != nil {
		t.Fatalf("reload user: %v", err)
	}

	challengeID := issueTotpLoginChallenge(user)
	code6, err := totpCodeAt(secret, time.Now().Unix())
	if err != nil {
		t.Fatalf("totp code: %v", err)
	}
	body, _ := json.Marshal(map[string]string{"challenge_id": challengeID, "code": code6})
	code, raw := e.do(t, http.MethodPost, "/api/v1/auth/2fa/verify", string(body), "")
	if code != http.StatusOK {
		t.Fatalf("2fa verify: expected 200, got %d (%s)", code, raw)
	}
	var issued struct {
		Tokens struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		} `json:"tokens"`
	}
	if err := json.Unmarshal([]byte(raw), &issued); err != nil {
		t.Fatalf("2fa decode: %v", err)
	}

	if c := e.protectedWith(t, issued.Tokens.AccessToken); c != http.StatusOK {
		t.Errorf("2FA access token rejected on protected route (%d)", c)
	}
	if c, _, _ := e.refreshWith(t, issued.Tokens.AccessToken); c != http.StatusUnauthorized {
		t.Errorf("2FA access token accepted by /auth/refresh (%d)", c)
	}
	if c := e.protectedWith(t, issued.Tokens.RefreshToken); c != http.StatusUnauthorized {
		t.Errorf("2FA refresh token accepted on protected route (%d)", c)
	}
	if c, _, _ := e.refreshWith(t, issued.Tokens.RefreshToken); c != http.StatusOK {
		t.Errorf("2FA refresh token rejected by /auth/refresh (%d)", c)
	}
}
