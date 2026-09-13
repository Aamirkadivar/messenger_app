package handlers

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
	"time"

	"github.com/google/uuid"

	"messenger-app/database"
	"messenger-app/models"
)

// Gate 17 follow-up coverage.
//
// Two implemented paths had no end-to-end assertion. Both are exercised here
// against the real handlers over HTTP, with no production code changed to make
// either reachable.

// ---------------------------------------------------------------- reset flow

// startDevReset issues a real reset challenge through the production endpoint
// and returns (challengeID, code).
//
// Phase 72A: reset has a delivery mechanism ONLY in the explicit development
// configuration (ENV=development AND DEV_2FA_ENABLED=true), where the DEV relay
// posts the code to the relay account. This switches to that configuration for
// the test, points the relay at a synthetic account created here, and reads the
// code from the relay message in the scratch database.
//
// These tests used to scrape the code from a plaintext "code=" server-log line.
// That log line was the vulnerability Phase 72A removes, so the code is now
// taken from the relay's own channel instead. Only how the code is obtained
// changed; every assertion about what the reset does is untouched.
func (e *g17Env) startDevReset(t *testing.T) (string, string) {
	t.Helper()
	relay := "g17_relay_" + uuid.New().String()[:8]
	t.Setenv("ENV", "development")
	t.Setenv("DEV_2FA_ENABLED", "true")
	t.Setenv("DEV_2FA_RELAY_USERNAME", relay)
	now := time.Now()
	if err := database.DB.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, 'x', ?, ?)`, uuid.New(), relay, relay+"@t.local", now, now).Error; err != nil {
		t.Fatalf("seed relay account: %v", err)
	}

	code, body := e.postJSON(t, "/auth/password-reset/start",
		map[string]string{"email": e.user.Email})
	if code != http.StatusOK {
		t.Fatalf("reset start: got %d, want 200 (%s)", code, body)
	}
	var started struct {
		ChallengeID string `json:"challenge_id"`
	}
	if err := json.Unmarshal(body, &started); err != nil || started.ChallengeID == "" {
		t.Fatalf("reset start returned no challenge_id: %s", body)
	}
	var bodies []string
	database.DB.Raw(`SELECT encrypted_content FROM messages WHERE encrypted_content LIKE ?`,
		"%Challenge: "+started.ChallengeID+"%").Scan(&bodies)
	re := regexp.MustCompile(`\n(\d{6})\n\nChallenge: ` + regexp.QuoteMeta(started.ChallengeID))
	for _, b := range bodies {
		if m := re.FindStringSubmatch(b); m != nil {
			return started.ChallengeID, m[1]
		}
	}
	t.Fatalf("the DEV relay delivered no code for challenge %s", started.ChallengeID)
	return "", ""
}

// mountPasswordReset registers the production reset handlers on the harness
// app. NewAuthService is stateless (handlers/auth.go:24), so a second instance
// shares nothing with the one gate17Setup built.
//
// Mounted at the ROOT, deliberately. gate17Setup builds its protected group as
// api.Group(""), which mounts AuthMiddleware across the whole /api/v1 prefix,
// so anything registered under it afterwards inherits authentication. Password
// reset is an UNAUTHENTICATED endpoint (main.go:119-120 mounts it outside the
// protected group), and putting it under that prefix here would test a route
// that does not exist in production. Same harness artifact the Gate 16 setup
// documents for /ws.
func (e *g17Env) mountPasswordReset() {
	auth := NewAuthService()
	e.app.Post("/auth/password-reset/start", auth.StartPasswordReset)
	e.app.Post("/auth/password-reset/complete", auth.CompletePasswordReset)
}

func (e *g17Env) postJSON(t *testing.T, path string, body map[string]string) (int, []byte) {
	t.Helper()
	raw, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request %s: %v", path, err)
	}
	out, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, out
}

// TestGate17_PasswordResetRevokesSessionsOverHTTP drives the whole reset through
// the HTTP surface rather than writing password_hash directly.
//
// The direct-SQL Gate 15 proof covers the database trigger. This covers what the
// trigger cannot: that the ENDPOINT reaches it at all, and that every credential
// minted before the reset is dead afterwards - including a cached lost-response
// replay, the one path that could otherwise hand back a working token after a
// password reset.
func TestGate17_PasswordResetRevokesSessionsOverHTTP(t *testing.T) {
	e := gate17Setup(t)
	e.mountPasswordReset()

	// Complete a rotation first, so a live response-cache entry exists to try to
	// resurrect the session with later.
	reqX := uuid.New()
	rotated := e.refreshWith(t, e.refresh, reqX)
	if rotated.code != http.StatusOK {
		t.Fatalf("seed rotation: got %d, want 200", rotated.code)
	}
	if code := e.bearer(t, rotated.access); code != http.StatusOK {
		t.Fatalf("access token before reset: got %d, want 200", code)
	}

	// Issue the challenge through the production endpoint (see startDevReset).
	challengeID, otp := e.startDevReset(t)

	// Complete over HTTP. Nothing here writes password_hash directly.
	code, body := e.postJSON(t, "/auth/password-reset/complete", map[string]string{
		"challenge_id": challengeID,
		"code":         otp,
		"new_password": "a-new-password-that-is-long-enough",
	})
	if code != http.StatusOK {
		t.Fatalf("reset complete: got %d, want 200 (%s)", code, body)
	}

	// The password really changed.
	var u models.User
	if err := database.DB.First(&u, "id = ?", e.user.ID).Error; err != nil {
		t.Fatalf("reload user: %v", err)
	}
	if u.PasswordHash == e.user.PasswordHash {
		t.Errorf("password_hash unchanged after a successful reset")
	}

	// The session is revoked and its credential destroyed.
	s := e.sessionRow(t)
	if s.RevokedAt == nil {
		t.Errorf("PROVEN BROKEN: password reset returned 200 but left the session live")
	}
	if s.RefreshHash != nil {
		t.Errorf("revoked session still carries a refresh hash")
	}
	// The trigger fires on the users UPDATE inside the handler transaction and
	// wins the COALESCE, so the recorded reason is the trigger's. Either value
	// means revoked; neither may be absent.
	reason := ""
	if s.RevokeReason != nil {
		reason = *s.RevokeReason
	}
	if reason != "password_change" && reason != "password_reset" {
		t.Errorf("revoke_reason = %q, want password_change or password_reset", reason)
	}

	// Every credential that existed before the reset is now dead.
	if code := e.bearer(t, rotated.access); code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: an access token minted before the reset still authenticates (%d)",
			code)
	}
	if r := e.refreshWith(t, rotated.refresh, uuid.New()); r.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: the current refresh credential still rotates after reset (%d)",
			r.code)
	}
	// The lost-response replay path must not resurrect the session either:
	// revocation dominates idempotency.
	replay := e.refreshWith(t, e.refresh, reqX)
	if replay.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: a cached replay resurrected a password-reset session (%d)",
			replay.code)
	}
	if replay.access != "" || replay.refresh != "" {
		t.Errorf("cached replay returned credentials after a password reset")
	}

	// The cache material is gone rather than merely gated.
	var live int64
	database.DB.Model(&models.ConsumedRefresh{}).
		Where("session_id = ? AND response_ciphertext IS NOT NULL", e.session).Count(&live)
	if live != 0 {
		t.Errorf("%d cached response(s) survived the reset", live)
	}
}

// ------------------------------------------------------- ledger PK collision

// TestGate17_LedgerCollisionIsInvariantFaultNotReuse forces the "impossible"
// case: a hash simultaneously live on the session AND already in the ledger.
// That is exactly the CAS/ledger divergence ledgerFault describes.
//
// It is reachable from the HTTP surface without touching production code -
// seeding the divergent row is enough, because the CAS then matches on the
// session row while the ledger insert collides on the primary key.
//
// What must NOT happen is the dangerous reading: 23505 classified as refresh
// reuse would let internal drift mass-revoke innocent sessions.
func TestGate17_LedgerCollisionIsInvariantFaultNotReuse(t *testing.T) {
	e := gate17Setup(t)

	presented, ok := hashRefreshTransport(e.cfg, e.refresh)
	if !ok {
		t.Fatalf("could not hash the seeded refresh token")
	}

	// A ledger row whose consumed_hash is the session's CURRENT credential.
	next := make([]byte, 32)
	if _, err := rand.Read(next); err != nil {
		t.Fatalf("rand: %v", err)
	}
	// Inserted as a typed row: GORM expands a []byte bind in raw Exec into one
	// placeholder per byte, which a hand-written INSERT cannot survive.
	now := time.Now()
	if err := database.DB.Create(&models.ConsumedRefresh{
		ConsumedHash:         presented,
		SessionID:            e.session,
		RequestID:            uuid.New(),
		NextRefreshHash:      next,
		ResponseExpiresAt:    now.Add(60 * time.Second),
		ReuseWindowExpiresAt: now.Add(time.Hour),
		ConsumedAt:           now,
	}).Error; err != nil {
		t.Fatalf("seed divergent ledger row: %v", err)
	}

	before := e.sessionRow(t)
	ledgerBefore := e.ledgerCount(t)

	res := e.refreshWith(t, e.refresh, uuid.New())

	if res.code != http.StatusInternalServerError {
		t.Fatalf("PROVEN BROKEN: ledger collision produced %d, want 500. A CAS/ledger "+
			"divergence must surface as an invariant fault, not as a credential decision",
			res.code)
	}
	if res.access != "" || res.refresh != "" {
		t.Errorf("invariant fault returned credentials")
	}

	after := e.sessionRow(t)
	if after.RevokedAt != nil {
		faultReason := ""
		if after.RevokeReason != nil {
			faultReason = *after.RevokeReason
		}
		t.Errorf("PROVEN BROKEN: an invariant fault revoked the session (reason %q). "+
			"Internal drift must never be read as attacker reuse", faultReason)
	}
	if after.Generation != before.Generation {
		t.Errorf("rotation was not rolled back: generation %d -> %d",
			before.Generation, after.Generation)
	}
	if !bytes.Equal(after.RefreshHash, before.RefreshHash) {
		t.Errorf("rotation was not rolled back: refresh_hash changed")
	}
	if n := e.ledgerCount(t); n != ledgerBefore {
		t.Errorf("ledger rows changed on a rolled-back rotation: %d -> %d", ledgerBefore, n)
	}

	// The holder is not locked out by the fault: once the divergence is cleared
	// the same credential still rotates.
	if err := database.DB.Exec(`DELETE FROM consumed_refresh`).Error; err != nil {
		t.Fatalf("clear divergence: %v", err)
	}
	if r := e.refreshWith(t, e.refresh, uuid.New()); r.code != http.StatusOK {
		t.Errorf("after clearing the divergence the credential should rotate, got %d", r.code)
	}
}
