package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"messenger-app/models"

	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

// PHASE 72A - the password-reset / dev-OTP-relay security boundary.
//
// The vulnerability: POST /auth/password-reset/start is public, and it called the DEV 2FA relay
// unconditionally - whatever ENV and DEV_2FA_ENABLED said. Every call wrote the reset code and the
// target's email to the server log and, when the configured relay username existed, stored the code
// as a PLAINTEXT row in `messages` for that account. The start response returns the challenge id,
// and for an account without TOTP the id plus the code is the whole reset.
//
// The policy these tests pin:
//   - the relay runs ONLY when ENV=development AND DEV_2FA_ENABLED=true (Dev2FAEnabled);
//   - with no relay there is no delivery mechanism, so reset START fails closed for every account,
//     identically for known and unknown emails (no challenge, no code, nothing stored or logged);
//   - no OTP ever reaches a log line, in any configuration;
//   - the challenge stays expiry-bound and single-use, including under concurrent completion;
//   - TOTP accounts still need their second factor to complete.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL. No real account and no
// real relay username is involved: every relay target here is a synthetic user created per test.

type p72aEnv struct {
	*p66Env
	relayUsername string
}

// p72aSetup builds the Phase 66 harness (real Login/Verify2FA/TOTP) and mounts the PRODUCTION reset
// handlers at the root, exactly as main.go does (they are unauthenticated). env/devFlag decide the
// configuration the handlers read per request. A synthetic relay target always exists, so a relay
// that runs when it must not is observed, not merely skipped.
func p72aSetup(t *testing.T, env, devFlag string) *p72aEnv {
	t.Helper()
	base := p66Setup(t)
	t.Setenv("ENV", env)
	t.Setenv("DEV_2FA_ENABLED", devFlag)
	relay := "p72a_relay_" + strings.ReplaceAll(uuid.New().String()[:8], "-", "")
	t.Setenv("DEV_2FA_RELAY_USERNAME", relay)
	now := time.Now()
	hash, _ := bcrypt.GenerateFromPassword([]byte(uuid.New().String()), bcrypt.MinCost)
	if err := base.db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?)`, uuid.New(), relay, relay+"@t.local", string(hash), now, now).Error; err != nil {
		t.Fatalf("seed relay user: %v", err)
	}
	auth := &AuthService{}
	base.app.Post("/auth/password-reset/start", auth.StartPasswordReset)
	base.app.Post("/auth/password-reset/complete", auth.CompletePasswordReset)
	return &p72aEnv{p66Env: base, relayUsername: relay}
}

// captureLog runs fn with the standard logger redirected, and returns everything it wrote.
func captureLog(fn func()) string {
	var buf bytes.Buffer
	prev := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(prev)
	fn()
	return buf.String()
}

func (e *p72aEnv) post(t *testing.T, path string, body map[string]string) (int, map[string]any, string) {
	t.Helper()
	raw, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.app.Test(req, 10000)
	if err != nil {
		t.Fatalf("request %s: %v", path, err)
	}
	var rawBody bytes.Buffer
	_, _ = rawBody.ReadFrom(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(rawBody.Bytes(), &out)
	return resp.StatusCode, out, rawBody.String()
}

func (e *p72aEnv) start(t *testing.T, email string) (int, map[string]any, string) {
	return e.post(t, "/auth/password-reset/start", map[string]string{"email": email})
}

func (e *p72aEnv) complete(t *testing.T, challengeID, code, totp, newPassword string) int {
	t.Helper()
	body := map[string]string{"challenge_id": challengeID, "code": code, "new_password": newPassword}
	if totp != "" {
		body["totp_code"] = totp
	}
	status, _, _ := e.post(t, "/auth/password-reset/complete", body)
	return status
}

// relayedCode reads the code the DEV relay delivered for challengeID, from the relay message in the
// isolated database - the only legitimate channel for it, and only in the development configuration.
func (e *p72aEnv) relayedCode(t *testing.T, challengeID string) string {
	t.Helper()
	var bodies []string
	e.db.Raw(`SELECT encrypted_content FROM messages WHERE encrypted_content LIKE ?`,
		"%Challenge: "+challengeID+"%").Scan(&bodies)
	re := regexp.MustCompile(`\n(\d{6})\n\nChallenge: ` + regexp.QuoteMeta(challengeID))
	for _, b := range bodies {
		if m := re.FindStringSubmatch(b); m != nil {
			return m[1]
		}
	}
	return ""
}

func (e *p72aEnv) plaintextMessages(t *testing.T) int64 {
	t.Helper()
	var n int64
	e.db.Raw(`SELECT count(*) FROM messages WHERE is_encrypted = false`).Scan(&n)
	return n
}

func (e *p72aEnv) messageCount(t *testing.T) int64 {
	t.Helper()
	var n int64
	e.db.Raw(`SELECT count(*) FROM messages`).Scan(&n)
	return n
}

func (e *p72aEnv) relayBotExists(t *testing.T) bool {
	t.Helper()
	var n int64
	e.db.Raw(`SELECT count(*) FROM users WHERE username = 'messenger_2fa_bot'`).Scan(&n)
	return n > 0
}

func challengesFor(user uuid.UUID) int {
	otpMu.Lock()
	defer otpMu.Unlock()
	n := 0
	for _, ch := range otpChallenges {
		if ch.UserID == user {
			n++
		}
	}
	return n
}

func (e *p72aEnv) passwordHash(t *testing.T, user uuid.UUID) string {
	t.Helper()
	var u models.User
	if err := e.db.First(&u, "id = ?", user).Error; err != nil {
		t.Fatalf("reload user: %v", err)
	}
	return u.PasswordHash
}

var logOTPRe = regexp.MustCompile(`code=\S+`)

// assertFailClosedStart is the whole no-delivery contract for one START call.
func assertFailClosedStart(t *testing.T, e *p72aEnv, a p66Account, status int, out map[string]any, raw, logged string) {
	t.Helper()
	if status != http.StatusServiceUnavailable || out["error"] != "password_reset_unavailable" {
		t.Errorf("start must fail closed with 503 password_reset_unavailable, got %d %s", status, raw)
	}
	if _, has := out["challenge_id"]; has {
		t.Errorf("a challenge was issued with no delivery mechanism: %s", raw)
	}
	if n := challengesFor(a.id); n != 0 {
		t.Errorf("%d reset challenge(s) exist for the account", n)
	}
	if n := e.plaintextMessages(t); n != 0 {
		t.Errorf("%d plaintext row(s) written to messages", n)
	}
	if e.relayBotExists(t) {
		t.Errorf("the DEV relay bot was created: the relay ran")
	}
	if logOTPRe.MatchString(logged) || strings.Contains(logged, a.email) || strings.Contains(logged, "dev_2fa_relay") {
		t.Errorf("the reset start logged relay output / an OTP / the email:\n%s", logged)
	}
}

// ================================================================ T1-T3, T5, T6: no relay, fail closed

// T1: flag off (ENV=development) - no relay, no code, no plaintext, safe response.
func TestP72A_T1_ResetNeverRelaysWhenFlagDisabled(t *testing.T) {
	e := p72aSetup(t, "development", "false")
	a := e.newAccount(t, false)
	var status int
	var out map[string]any
	var raw string
	logged := captureLog(func() { status, out, raw = e.start(t, a.email) })
	t.Logf("ENV=development DEV_2FA_ENABLED=false -> %d; challenges=%d plaintext rows=%d bot=%v code-in-log=%v",
		status, challengesFor(a.id), e.plaintextMessages(t), e.relayBotExists(t), logOTPRe.MatchString(logged))
	assertFailClosedStart(t, e, a, status, out, raw, logged)
}

// T2: production - no relay.
func TestP72A_T2_ProductionEnvCannotRelay(t *testing.T) {
	e := p72aSetup(t, "production", "false")
	a := e.newAccount(t, false)
	var status int
	var out map[string]any
	var raw string
	logged := captureLog(func() { status, out, raw = e.start(t, a.email) })
	t.Logf("ENV=production DEV_2FA_ENABLED=false -> %d; plaintext rows=%d bot=%v", status, e.plaintextMessages(t), e.relayBotExists(t))
	assertFailClosedStart(t, e, a, status, out, raw, logged)
}

// T3: the dev flag alone must not switch the relay on in production.
func TestP72A_T3_ProductionIgnoresDevFlag(t *testing.T) {
	e := p72aSetup(t, "production", "true")
	a := e.newAccount(t, false)
	var status int
	var out map[string]any
	var raw string
	logged := captureLog(func() { status, out, raw = e.start(t, a.email) })
	t.Logf("ENV=production DEV_2FA_ENABLED=true -> %d; plaintext rows=%d bot=%v", status, e.plaintextMessages(t), e.relayBotExists(t))
	assertFailClosedStart(t, e, a, status, out, raw, logged)
}

// T5: nothing reaches `messages` when the relay is off.
func TestP72A_T5_OtpNeverEntersMessagesWhenDisabled(t *testing.T) {
	e := p72aSetup(t, "production", "false")
	a := e.newAccount(t, false)
	before := e.messageCount(t)
	e.start(t, a.email)
	e.start(t, a.email)
	after := e.messageCount(t)
	var codeRows int64
	e.db.Raw(`SELECT count(*) FROM messages WHERE encrypted_content ~ '(^|\D)\d{6}(\D|$)'`).Scan(&codeRows)
	t.Logf("messages before=%d after=%d rows holding a 6-digit code=%d", before, after, codeRows)
	if after != before || codeRows != 0 {
		t.Fatalf("password reset wrote to messages: %d -> %d (code-bearing rows %d)", before, after, codeRows)
	}
}

// T6: a non-TOTP account cannot be reset at all without a delivery mechanism.
func TestP72A_T6_NonTotpResetFailsClosed(t *testing.T) {
	e := p72aSetup(t, "production", "false")
	a := e.newAccount(t, false)
	pw := e.passwordHash(t, a.id)
	var status int
	var out map[string]any
	var raw string
	logged := captureLog(func() { status, out, raw = e.start(t, a.email) })
	assertFailClosedStart(t, e, a, status, out, raw, logged)
	// Nothing to complete with: any id/code guess is refused and the password is untouched.
	guess := e.complete(t, uuid.New().String(), "000000", "", "an-attacker-chosen-password")
	t.Logf("non-TOTP start -> %d; blind complete -> %d; password changed=%v", status, guess, e.passwordHash(t, a.id) != pw)
	if guess != http.StatusUnauthorized || e.passwordHash(t, a.id) != pw {
		t.Fatalf("a reset completed without any delivered code (complete=%d)", guess)
	}
}

// T8: known, unknown and malformed emails are indistinguishable.
func TestP72A_T8_UnknownAccountIsIndistinguishable(t *testing.T) {
	e := p72aSetup(t, "production", "false")
	a := e.newAccount(t, false)
	k, _, known := e.start(t, a.email)
	u, _, unknown := e.start(t, "nobody-"+uuid.New().String()[:8]+"@t.local")
	m, _, empty := e.start(t, "")
	t.Logf("known=%d %s | unknown=%d %s | empty=%d %s", k, known, u, unknown, m, empty)
	if k != u || u != m || known != unknown || unknown != empty {
		t.Fatalf("reset start distinguishes accounts:\n known   %d %s\n unknown %d %s\n empty   %d %s",
			k, known, u, unknown, m, empty)
	}
}

// ================================================================ T4, T13: no OTP in logs, any config

// T4: even with the DEV relay on, the code is never written to a log line.
func TestP72A_T4_OtpNeverInResetLogs(t *testing.T) {
	e := p72aSetup(t, "development", "true")
	a := e.newAccount(t, false)
	var out map[string]any
	logged := captureLog(func() { _, out, _ = e.start(t, a.email) })
	id, _ := out["challenge_id"].(string)
	code := e.relayedCode(t, id)
	t.Logf("dev relay delivered a code=%v; code in log=%v; email in log=%v",
		code != "", code != "" && strings.Contains(logged, code), strings.Contains(logged, a.email))
	if code == "" {
		t.Fatalf("precondition: the DEV relay did not deliver a code (challenge %q)", id)
	}
	if strings.Contains(logged, code) || logOTPRe.MatchString(logged) {
		t.Fatalf("the reset OTP appears in the server log:\n%s", logged)
	}
	if strings.Contains(logged, a.email) {
		t.Fatalf("the reset target's email appears in the server log:\n%s", logged)
	}
}

// T13: the shared relay helper is gated for login 2FA too, and never logs the login code.
func TestP72A_T13_LoginDevRelayGatedAndNeverLogsCode(t *testing.T) {
	// Production + flag: login is plain password (TOTP off) - no dev challenge, no relay.
	prod := p72aSetup(t, "production", "true")
	pa := prod.newAccount(t, false)
	code, out := prod.login(t, pa, pa.password)
	if code != http.StatusOK || accessToken(out) == "" || prod.relayBotExists(t) {
		t.Fatalf("production login with the dev flag set must not use the dev relay: %d %v bot=%v",
			code, out, prod.relayBotExists(t))
	}
}

func TestP72A_T13b_LoginDevRelayInDevNeverLogsCode(t *testing.T) {
	dev := p72aSetup(t, "development", "true")
	da := dev.newAccount(t, false)
	var status int
	var out map[string]any
	logged := captureLog(func() { status, out = dev.login(t, da, da.password) })
	id, _ := out["challenge_id"].(string)
	relayed := dev.relayedCode(t, id)
	t.Logf("dev login -> %d requires_2fa=%v relayed=%v code-in-log=%v", status, out["requires_2fa"],
		relayed != "", relayed != "" && strings.Contains(logged, relayed))
	if out["requires_2fa"] != true || relayed == "" {
		t.Fatalf("dev login 2FA must still work through the relay: %d %v", status, out)
	}
	if strings.Contains(logged, relayed) || logOTPRe.MatchString(logged) || strings.Contains(logged, da.email) {
		t.Fatalf("the login OTP / email appears in the server log:\n%s", logged)
	}
}

// ================================================================ T7, T9-T12: challenge semantics (dev relay)

// T7 + R9/R10: a TOTP account still needs its second factor.
func TestP72A_T7_TotpSecondFactorStillRequired(t *testing.T) {
	e := p72aSetup(t, "development", "true")
	a := e.newAccount(t, true)
	pw := e.passwordHash(t, a.id)
	_, out, _ := e.start(t, a.email)
	id, _ := out["challenge_id"].(string)
	code := e.relayedCode(t, id)
	if code == "" {
		t.Fatalf("precondition: no relayed code")
	}
	noTotp := e.complete(t, id, code, "", "new-password-without-totp")
	wrongTotp := e.complete(t, id, code, "000000", "new-password-wrong-totp")
	unchanged := e.passwordHash(t, a.id) == pw
	good, _ := totpCodeAt(a.totp, time.Now().Unix())
	ok := e.complete(t, id, code, good, "new-password-with-totp")
	t.Logf("TOTP account: no totp -> %d, wrong totp -> %d (password unchanged=%v), correct totp -> %d",
		noTotp, wrongTotp, unchanged, ok)
	if noTotp != http.StatusBadRequest || wrongTotp != http.StatusUnauthorized || !unchanged {
		t.Fatalf("the second factor was not enforced: %d / %d unchanged=%v", noTotp, wrongTotp, unchanged)
	}
	if ok != http.StatusOK || e.passwordHash(t, a.id) == pw {
		t.Fatalf("a correct code + TOTP must still reset: %d", ok)
	}
}

// T9: an expired challenge cannot change the password.
func TestP72A_T9_ExpiredChallengeRejected(t *testing.T) {
	e := p72aSetup(t, "development", "true")
	a := e.newAccount(t, false)
	pw := e.passwordHash(t, a.id)
	_, out, _ := e.start(t, a.email)
	id, _ := out["challenge_id"].(string)
	code := e.relayedCode(t, id)
	otpMu.Lock()
	ch := otpChallenges[id]
	ch.ExpiresAt = time.Now().Add(-time.Second)
	otpChallenges[id] = ch
	otpMu.Unlock()
	got := e.complete(t, id, code, "", "new-password-after-expiry")
	t.Logf("expired challenge -> %d; password changed=%v", got, e.passwordHash(t, a.id) != pw)
	if got != http.StatusUnauthorized || e.passwordHash(t, a.id) != pw {
		t.Fatalf("an expired challenge reset the password (%d)", got)
	}
}

// T10: a consumed challenge cannot be replayed; a wrong code fails.
func TestP72A_T10_ConsumedChallengeCannotBeReused(t *testing.T) {
	e := p72aSetup(t, "development", "true")
	a := e.newAccount(t, false)
	_, out, _ := e.start(t, a.email)
	id, _ := out["challenge_id"].(string)
	code := e.relayedCode(t, id)
	wrong := e.complete(t, id, fmt.Sprintf("%06d", (atoiSafe(code)+1)%1000000), "", "wrong-code-password")
	first := e.complete(t, id, code, "", "first-new-password")
	afterFirst := e.passwordHash(t, a.id)
	replay := e.complete(t, id, code, "", "replayed-new-password")
	t.Logf("wrong code -> %d, first -> %d, replay -> %d, replay changed password=%v",
		wrong, first, replay, e.passwordHash(t, a.id) != afterFirst)
	if wrong != http.StatusUnauthorized || first != http.StatusOK || replay != http.StatusUnauthorized ||
		e.passwordHash(t, a.id) != afterFirst {
		t.Fatalf("single-use violated: wrong=%d first=%d replay=%d", wrong, first, replay)
	}
}

func atoiSafe(s string) int {
	n := 0
	for _, r := range s {
		if r >= '0' && r <= '9' {
			n = n*10 + int(r-'0')
		}
	}
	return n
}

// T11: concurrent completions of one challenge consume it exactly once.
func TestP72A_T11_ConcurrentCompletionConsumesOnce(t *testing.T) {
	e := p72aSetup(t, "development", "true")
	a := e.newAccount(t, false)
	_, out, _ := e.start(t, a.email)
	id, _ := out["challenge_id"].(string)
	code := e.relayedCode(t, id)
	const n = 16
	var wg sync.WaitGroup
	codes := make([]int, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			raw, _ := json.Marshal(map[string]string{"challenge_id": id, "code": code,
				"new_password": fmt.Sprintf("concurrent-password-%02d", i)})
			req := httptest.NewRequest(http.MethodPost, "/auth/password-reset/complete", bytes.NewReader(raw))
			req.Header.Set("Content-Type", "application/json")
			if resp, err := e.app.Test(req, 20000); err == nil {
				codes[i] = resp.StatusCode
			}
		}(i)
	}
	wg.Wait()
	ok := 0
	for _, c := range codes {
		if c == http.StatusOK {
			ok++
		}
	}
	t.Logf("%d concurrent completions of one challenge -> %d succeeded (%v)", n, ok, codes)
	if ok != 1 {
		t.Fatalf("one challenge was consumed %d times, want exactly once", ok)
	}
}

// T14: the reset code never travels over the WebSocket - not even to the relay account, not even in
// the development configuration (the relay only writes a row; it has no hub).
func TestP72A_T14_OtpNeverSentOverWebSocket(t *testing.T) {
	e := p71Setup(t) // production config: the relay account signs in with its password alone
	relay := e.newAccount(t, false)
	target := e.newAccount(t, false)
	ws := e.dial(t, e.fullLogin(t, relay), relay.id)
	ws.frames = drainFor(ws.frames, 300*time.Millisecond)

	// Only now switch to the development relay configuration, pointed at that account.
	t.Setenv("ENV", "development")
	t.Setenv("DEV_2FA_ENABLED", "true")
	t.Setenv("DEV_2FA_RELAY_USERNAME", "u"+relay.id.String()[:8])
	auth := &AuthService{}
	e.app.Post("/auth/password-reset/start", auth.StartPasswordReset)
	raw, _ := json.Marshal(map[string]string{"email": target.email})
	req := httptest.NewRequest(http.MethodPost, "/auth/password-reset/start", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.app.Test(req, 10000)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	id, _ := out["challenge_id"].(string)
	env := &p72aEnv{p66Env: e.p66Env}
	code := env.relayedCode(t, id)
	leaked := 0
	deadline := time.After(1500 * time.Millisecond)
	for done := false; !done; {
		select {
		case f, ok := <-ws.frames:
			if !ok {
				done = true
				break
			}
			if b, _ := json.Marshal(f); code != "" && strings.Contains(string(b), code) {
				leaked++
			}
		case <-deadline:
			done = true
		}
	}
	t.Logf("dev reset -> %d, relayed code=%v, frames carrying it to the relay socket=%d", resp.StatusCode, code != "", leaked)
	if code == "" {
		t.Fatalf("precondition: the DEV relay did not deliver a code")
	}
	if leaked != 0 {
		t.Fatalf("the reset code was pushed over the WebSocket %d time(s)", leaked)
	}
}

// drainFor discards whatever arrives on ch for d (connect-time presence frames) and returns ch.
func drainFor(ch chan map[string]any, d time.Duration) chan map[string]any {
	deadline := time.After(d)
	for {
		select {
		case <-ch:
		case <-deadline:
			return ch
		}
	}
}

// T12 (DEVELOPMENT-ONLY): the relay works exactly in the documented dev configuration.
func TestP72A_T12_DevRelayOnlyInExplicitDevConfig(t *testing.T) {
	e := p72aSetup(t, "development", "true")
	a := e.newAccount(t, false)
	status, out, _ := e.start(t, a.email)
	id, _ := out["challenge_id"].(string)
	code := e.relayedCode(t, id)
	var toRelay int64
	e.db.Raw(`SELECT count(*) FROM messages m
		JOIN chat_participants cp ON cp.chat_id::text = m.chat_id
		JOIN users u ON u.id = cp.user_id AND u.username = ?
		WHERE m.encrypted_content LIKE ?`, e.relayUsername, "%Challenge: "+id+"%").Scan(&toRelay)
	done := e.complete(t, id, code, "", "dev-relay-new-password")
	t.Logf("DEV start -> %d challenge=%v relayed to synthetic @%s rows=%d; complete -> %d",
		status, id != "", e.relayUsername, toRelay, done)
	if status != http.StatusOK || id == "" || code == "" || toRelay != 1 || done != http.StatusOK {
		t.Fatalf("the DEV relay flow is broken in its own configuration")
	}
}
