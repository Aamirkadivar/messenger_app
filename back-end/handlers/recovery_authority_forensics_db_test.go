package handlers

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/crypto/nacl/box"
	"gorm.io/gorm"
)

// PHASE 66 - validating the frozen product policy. Forensic only; no production change.
//
// THE POLICY (given, not derived):
//
//	ACCOUNT_RECOVERY_AUTHORITY = valid account authentication + successful 2FA
//
// The whole phase turns on keeping two things apart that the code currently conflates:
//
//	ACCOUNT AUTHORITY   password + 2FA   -> may bootstrap/recover a device
//	DEVICE AUTHORITY    K_device         -> may perform device-gated operations
//
// Revocation removes the second. It is not meant to survive compromise of the first.
//
// Unlike the earlier phases, this suite drives the REAL AuthMiddleware with REAL access tokens
// minted by the REAL Login/Verify2FA handlers, because the question being asked is precisely what
// the authentication path knows and records - and a faked auth middleware would answer it by
// construction.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

type p66Env struct {
	app *fiber.App
	db  *gorm.DB
	cfg *config.Config
}

// p66Setup reproduces main.go's real chain: AuthMiddleware -> DeviceRevocationGuard -> per-route.
func p66Setup(t *testing.T) *p66Env {
	t.Helper()

	// Force the process into a known 2FA configuration. The handlers read config per request, so
	// this is what decides which Login branch is live.
	t.Setenv("ENV", "production")        // Dev2FAEnabled requires ENV=development
	t.Setenv("DEV_2FA_ENABLED", "false") // ... and this. Both off: TOTP is the only 2FA.
	t.Setenv("JWT_SECRET", "phase66-test-secret")

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
	e := &p66Env{db: db, cfg: cfg}

	app := fiber.New()
	auth := &AuthService{}
	app.Post("/auth/login", auth.Login)
	app.Post("/auth/2fa/verify", auth.Verify2FA)

	protected := app.Group("/api")
	protected.Use(middleware.AuthMiddleware(cfg))
	protected.Use(middleware.DeviceRevocationGuard())

	h := NewE2EEHandler(nil)
	protected.Post("/e2ee/devices/challenge", h.CreateDeviceChallenge)
	protected.Post("/e2ee/devices", h.RegisterDevice)
	protected.Post("/e2ee/devices/:device_id/revoke", h.RevokeDevice)
	protected.Put("/e2ee/vault", h.PutVault) // ungated, exactly as main.go has it today

	// Phase 65: only completion is device-gated.
	protected.Post("/e2ee/pairing", h.CreatePairing)
	protected.Get("/e2ee/pairing/:session_id/payload", h.TakePairingPayload)
	protected.Post("/e2ee/pairing/:session_id/complete",
		middleware.RequireDeviceIdentity(), h.CompletePairing)

	// Stand-in for any device-gated route.
	protected.Post("/e2ee/device-gated", middleware.RequireDeviceIdentity(), func(c *fiber.Ctx) error {
		return c.SendStatus(http.StatusNoContent)
	})

	e.app = app
	return e
}

type p66Account struct {
	id       uuid.UUID
	email    string
	password string
	totp     string // base32 secret; empty when TOTP is disabled
}

func (e *p66Env) newAccount(t *testing.T, withTotp bool) p66Account {
	t.Helper()
	id := uuid.New()
	pw := "correct-horse-battery-staple"
	hash, err := bcrypt.GenerateFromPassword([]byte(pw), bcrypt.MinCost)
	if err != nil {
		t.Fatalf("bcrypt: %v", err)
	}
	acct := p66Account{id: id, email: id.String()[:8] + "@t.local", password: pw}
	now := time.Now()
	// RFC 4648 base32, no padding - what validateTotp decodes.
	secret := "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
	if withTotp {
		acct.totp = secret
	}
	if err := e.db.Exec(`INSERT INTO users
		(id, username, email, password_hash, totp_secret, totp_enabled, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		id, "u"+id.String()[:8], acct.email, string(hash),
		map[bool]string{true: secret, false: ""}[withTotp], withTotp, now, now).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}
	return acct
}

func (e *p66Env) req(t *testing.T, method, path, body, bearer, deviceID string) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader([]byte(body)))
	req.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	if deviceID != "" {
		req.Header.Set("X-Device-Id", deviceID)
	}
	resp, err := e.app.Test(req, 5000)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func (e *p66Env) login(t *testing.T, a p66Account, password string) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"email": a.email, "password": password})
	return e.req(t, http.MethodPost, "/auth/login", string(body), "", "")
}

func (e *p66Env) verify2FA(t *testing.T, challengeID, code string) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"challenge_id": challengeID, "code": code})
	return e.req(t, http.MethodPost, "/auth/2fa/verify", string(body), "", "")
}

func accessToken(out map[string]any) string {
	tok, ok := out["tokens"].(map[string]any)
	if !ok {
		return ""
	}
	s, _ := tok["access_token"].(string)
	return s
}

// fullLogin performs the complete password (+ TOTP when enabled) flow and returns an access token.
// This is what "ACCOUNT RECOVERY AUTHORITY" means under the frozen policy.
func (e *p66Env) fullLogin(t *testing.T, a p66Account) string {
	t.Helper()
	code, out := e.login(t, a, a.password)
	if code >= 400 {
		t.Fatalf("login: %d %v", code, out)
	}
	if tok := accessToken(out); tok != "" {
		return tok
	}
	if req2fa, _ := out["requires_2fa"].(bool); !req2fa {
		t.Fatalf("login returned neither tokens nor a 2FA challenge: %v", out)
	}
	challengeID, _ := out["challenge_id"].(string)
	valid, err := totpCodeAt(a.totp, time.Now().Unix())
	if err != nil {
		t.Fatalf("totp: %v", err)
	}
	vcode, vout := e.verify2FA(t, challengeID, valid)
	if vcode >= 400 {
		t.Fatalf("2fa verify: %d %v", vcode, vout)
	}
	tok := accessToken(vout)
	if tok == "" {
		t.Fatalf("2fa verify returned no tokens: %v", vout)
	}
	return tok
}

// enrolWith runs the REAL Phase 44 challenge/proof for deviceID using the supplied keypair and the
// supplied access token. The keypair is a parameter because the entire §6 question is whether a
// BRAND NEW key - not K_account - can be enrolled by an account-authenticated caller.
func (e *p66Env) enrolWith(t *testing.T, token, deviceID string, pub, priv *[32]byte) (int, string) {
	t.Helper()
	chBody, _ := json.Marshal(map[string]string{
		"device_id": deviceID, "public_key": hex.EncodeToString(pub[:]),
	})
	code, out := e.req(t, http.MethodPost, "/api/e2ee/devices/challenge", string(chBody), token, deviceID)
	if code >= 400 {
		return code, fmt.Sprintf("challenge refused: %v", out)
	}
	sealedB64, _ := out["sealed_b64"].(string)
	senderPubHex, _ := out["sender_pub_hex"].(string)
	challengeID, _ := out["challenge_id"].(string)
	sealed, err := base64.StdEncoding.DecodeString(sealedB64)
	if err != nil || len(sealed) < 24 {
		t.Fatalf("challenge not usable: %v", out)
	}
	senderPub, err := hex.DecodeString(senderPubHex)
	if err != nil || len(senderPub) != 32 {
		t.Fatalf("sender key not usable: %v", out)
	}
	var nonce [24]byte
	copy(nonce[:], sealed[:24])
	var their [32]byte
	copy(their[:], senderPub)
	proof, ok := box.Open(nil, sealed[24:], &nonce, &their, priv)
	if !ok {
		return -1, "the challenge did not open with this private key"
	}
	regBody, _ := json.Marshal(map[string]string{
		"device_id": deviceID, "public_key": hex.EncodeToString(pub[:]),
		"challenge_id": challengeID, "proof_b64": base64.StdEncoding.EncodeToString(proof),
	})
	code, out = e.req(t, http.MethodPost, "/api/e2ee/devices", string(regBody), token, deviceID)
	return code, fmt.Sprintf("%v", out)
}

func (e *p66Env) deviceRow(t *testing.T, user uuid.UUID, deviceID string) (models.E2EEDevice, bool) {
	t.Helper()
	var d models.E2EEDevice
	err := e.db.Where("user_id = ? AND device_id = ?", user, deviceID).First(&d).Error
	return d, err == nil
}

// ============================================================ §4 AUTHENTICATION TRACE

// T4. Password alone, on an account with no second factor configured, mints tokens outright.
//
// The spec asks for RECOVERY DENIED "unless the existing authentication implementation proves that
// 2FA is not required for the relevant account state". Login proves exactly that: the TOTP branch
// is entered only when user.TotpEnabled, and the dev-OTP branch only in development. Outside those,
// password alone reaches issueLoginTokens. This is measured, not weakened.
func TestP66_T4_PasswordOnlyAccount_NoSecondFactorConfigured(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, false)

	code, out := e.login(t, a, a.password)
	tok := accessToken(out)
	req2fa, _ := out["requires_2fa"].(bool)

	t.Logf("T4: TOTP-disabled account, password only -> status=%d requires_2fa=%v tokensIssued=%v",
		code, req2fa, tok != "")
	if code >= 400 || tok == "" {
		t.Fatalf("T4: expected the password-only branch to mint tokens, got %d %v", code, out)
	}
	t.Logf("T4 FINDING: for an account with no second factor configured, ACCOUNT AUTHORITY is " +
		"password alone. The policy's '+ 2FA' is therefore only as strong as TOTP enrolment; " +
		"enforcing it universally requires refusing recovery for accounts with TotpEnabled=false, " +
		"which is a policy decision, not an implementation gap.")
}

// T5. A TOTP account: password alone yields NO tokens, and a wrong code still yields none.
func TestP66_T5_TotpAccount_PasswordAloneAndBadCodeBothDenied(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	code, out := e.login(t, a, a.password)
	tok := accessToken(out)
	req2fa, _ := out["requires_2fa"].(bool)
	challengeID, _ := out["challenge_id"].(string)

	t.Logf("T5 step 1: password accepted -> status=%d requires_2fa=%v tokensIssued=%v",
		code, req2fa, tok != "")
	if tok != "" {
		t.Fatalf("T5: password alone minted tokens on a TOTP account")
	}
	if !req2fa || challengeID == "" {
		t.Fatalf("T5: expected a 2FA challenge, got %v", out)
	}

	// Wrong code.
	bcode, bout := e.verify2FA(t, challengeID, "000000")
	t.Logf("T5 step 2: invalid 2FA code -> status=%d tokensIssued=%v", bcode, accessToken(bout) != "")
	if bcode < 400 || accessToken(bout) != "" {
		t.Fatalf("T5: an invalid 2FA code produced credentials (%d)", bcode)
	}

	// Wrong password never reaches 2FA at all.
	wcode, wout := e.login(t, a, "not-the-password")
	t.Logf("T5 step 3: wrong password -> status=%d requires_2fa=%v", wcode, wout["requires_2fa"])
	if wcode < 400 {
		t.Fatalf("T5: a wrong password was accepted (%d)", wcode)
	}
	t.Logf("T5 CONFIRMED: RECOVERY DENIED for password-only and for invalid 2FA on a TOTP account.")
}

// THE §4 CENTRAL QUESTION: at what point does the server KNOW "password + 2FA succeeded"?
//
// Answer: transiently, inside Verify2FA, and nowhere afterwards. Both branches call the same
// issueLoginTokens primitive with the same arguments, and neither the session row nor the JWT
// carries any record of how authentication was reached.
func TestP66_SessionDoesNotRecordThatTwoFactorHappened(t *testing.T) {
	e := p66Setup(t)
	plain := e.newAccount(t, false) // password-only
	strong := e.newAccount(t, true) // password + TOTP

	tokPlain := e.fullLogin(t, plain)
	tokStrong := e.fullLogin(t, strong)
	if tokPlain == "" || tokStrong == "" {
		t.Fatalf("fixture: both logins must succeed")
	}

	shape := func(u uuid.UUID) string {
		var s models.Session
		if err := e.db.Where("user_id = ?", u).First(&s).Error; err != nil {
			t.Fatalf("session: %v", err)
		}
		return fmt.Sprintf("device_id=%v generation=%d revoked=%v hash_key_version=%d",
			s.DeviceID, s.Generation, s.RevokedAt != nil, s.HashKeyVersion)
	}
	sPlain, sStrong := shape(plain.id), shape(strong.id)

	// And no column anywhere in `sessions` mentions authentication strength.
	var cols []string
	e.db.Raw(`SELECT column_name FROM information_schema.columns
	          WHERE table_name = 'sessions' ORDER BY column_name`).Scan(&cols)

	t.Logf("password-only session : %s", sPlain)
	t.Logf("password+2FA session  : %s", sStrong)
	t.Logf("sessions columns      : %v", cols)

	if sPlain != sStrong {
		t.Fatalf("the two sessions differ structurally - re-examine, this test's premise is wrong")
	}
	for _, c := range cols {
		switch c {
		case "auth_level", "two_factor_at", "amr", "recovery_authority", "authenticated_with":
			t.Fatalf("a strength column already exists (%s); S12 may already be satisfiable", c)
		}
	}
	t.Logf("§4/§5 FINDING: the session row produced by password+2FA is INDISTINGUISHABLE from the " +
		"one produced by password alone, and the JWT carries only user_id/email/token_use/sid. The " +
		"server knows '2FA succeeded' for the duration of one handler call and then discards it. " +
		"Representing ACCOUNT-RECOVERY-AUTHENTICATED therefore requires NEW SERVER STATE (S12).")
}

// A session is created without a device on every authentication path, and stays that way until a
// Phase 44 proof binds it. So the intermediate state "authenticated, no device authority" already
// exists - it is simply not distinguished by HOW it was authenticated.
func TestP66_SessionsAreDevicelessUntilProof(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	var s models.Session
	if err := e.db.Where("user_id = ?", a.id).First(&s).Error; err != nil {
		t.Fatalf("session: %v", err)
	}
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "any-device")

	var devices int64
	e.db.Model(&models.E2EEDevice{}).Where("user_id = ?", a.id).Count(&devices)

	t.Logf("after password+2FA: session.device_id=%v device rows=%d device-gated route=%d",
		s.DeviceID, devices, gated)
	if s.DeviceID != nil {
		t.Fatalf("authentication bound a device it never verified")
	}
	if devices != 0 {
		t.Fatalf("login registered %d device(s); it must register none", devices)
	}
	if gated < 400 {
		t.Fatalf("an unbound session reached a device-gated route (%d)", gated)
	}
	t.Logf("§4 FINDING: login registers NO device and binds NO device. ACCOUNT AUTHORITY and " +
		"DEVICE AUTHORITY are already separate at the session layer; what is missing is only the " +
		"record of which authentication produced the account authority.")
}

// ============================================================ §5/§6/§13 THE DECISIVE TEST

// Can an account-authenticated, deviceless caller enrol a BRAND NEW keypair it generated locally?
//
// This is the feasibility question for the whole policy. If yes, "password + 2FA -> new K_device"
// needs no new cryptographic primitive: the Phase 44 challenge/proof already accepts whatever public
// key the caller submits and verifies possession of the matching private half.
func TestP66_AccountAuthenticatedCallerCanEnrolAFreshlyGeneratedKey(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	// A key that has never existed anywhere - no vault, no account material, no prior device.
	kDevicePub, kDevicePriv := newKeyPair(t)

	code, detail := e.enrolWith(t, tok, "recovered-device-1", kDevicePub, kDevicePriv)
	d, found := e.deviceRow(t, a.id, "recovered-device-1")
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "recovered-device-1")

	t.Logf("fresh-key enrolment after password+2FA: status=%d %s", code, detail)
	t.Logf("device row: found=%v verified=%v public_key==K_device=%v",
		found, found && d.VerifiedAt != nil, found && d.PublicKey == hex.EncodeToString(kDevicePub[:]))
	t.Logf("device-gated route with the new device: %d", gated)

	if code >= 400 || !found || d.VerifiedAt == nil {
		t.Fatalf("a freshly generated key could not be enrolled: %d %s", code, detail)
	}
	if d.PublicKey != hex.EncodeToString(kDevicePub[:]) {
		t.Fatalf("the stored public key is not the one that was proved")
	}
	if gated >= 400 {
		t.Fatalf("enrolment succeeded but the device did not gain device authority (%d)", gated)
	}
	t.Logf("§5/§6/S6 CONFIRMED: the existing crypto_box challenge already accepts an arbitrary " +
		"caller-generated key and binds the session to it. K_device generation and enrolment need " +
		"NO new trust primitive - only a client change to stop feeding it K_account.")
}

// §6: the server stores exactly the key that was proved, so K_device and K_account produce
// distinguishable rows. Nothing in the backend copies account material into E2EEDevice.public_key.
func TestP66_EnrolledKeyIsTheProvedKeyNotAccountMaterial(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	kAccountPub, kAccountPriv := newKeyPair(t) // stands in for the legacy account-wide key
	kDevicePub, kDevicePriv := newKeyPair(t)

	// One session per enrolment. A session binds at most one device (errSessionBound), which is
	// itself a Phase 44 property worth stating: a caller cannot move an authorization it already
	// holds onto a second identity without authenticating the account again.
	if code, d := e.enrolWith(t, e.fullLogin(t, a), "legacy-style", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("legacy-style enrolment: %d %s", code, d)
	}
	if code, d := e.enrolWith(t, e.fullLogin(t, a), "device-local", kDevicePub, kDevicePriv); code >= 400 {
		t.Fatalf("device-local enrolment: %d %s", code, d)
	}

	legacy, _ := e.deviceRow(t, a.id, "legacy-style")
	local, _ := e.deviceRow(t, a.id, "device-local")
	t.Logf("legacy-style row public_key=%s…", legacy.PublicKey[:16])
	t.Logf("device-local row public_key=%s…", local.PublicKey[:16])

	if legacy.PublicKey == local.PublicKey {
		t.Fatalf("two different proved keys produced identical rows")
	}
	if local.PublicKey != hex.EncodeToString(kDevicePub[:]) {
		t.Fatalf("the device-local row does not carry the key that was proved")
	}
	t.Logf("§6 CONFIRMED: E2EEDevice.public_key is whatever was PROVED, never derived from " +
		"account material. K_account != K_device is achievable client-side alone. What the rows " +
		"do NOT carry is any marker of WHICH kind of key they are - the Phase 63 M6 gap.")
}

// ============================================================ §3 THREAT MODEL

// T1: legitimate user, every device lost. Password + 2FA must recover.
func TestP66_T1_AllDevicesLost_PasswordPlus2FARecovers(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	// The user's only device is enrolled, then lost - modelled as revoked, the strongest form.
	oldTok := e.fullLogin(t, a)
	oldPub, oldPriv := newKeyPair(t)
	if code, d := e.enrolWith(t, oldTok, "lost-device", oldPub, oldPriv); code >= 400 {
		t.Fatalf("fixture: %d %s", code, d)
	}
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/lost-device/revoke", "{}",
		oldTok, "lost-device"); rc >= 400 {
		t.Fatalf("fixture revoke: %d", rc)
	}

	// A brand-new machine: fresh login, fresh key, nothing carried over.
	newTok := e.fullLogin(t, a)
	newPub, newPriv := newKeyPair(t)
	code, detail := e.enrolWith(t, newTok, "replacement-device", newPub, newPriv)
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", newTok, "replacement-device")

	t.Logf("T1: recovery enrolment=%d %s ; device-gated=%d", code, detail, gated)
	if code >= 400 || gated >= 400 {
		t.Fatalf("T1 RECOVERY ALLOWED is not achievable: enrol=%d gated=%d", code, gated)
	}
	t.Logf("T1 CONFIRMED: RECOVERY ALLOWED. Losing every device does not lock the account out, " +
		"and the replacement holds a key the lost device never had.")
}

// T2: revoked device whose operator also holds the credentials. ALLOWED - the accepted tradeoff.
//
// The security-relevant part is not that it succeeds; it is that it succeeds by minting a NEW key
// rather than resurrecting the revoked one. Both are measured.
func TestP66_T2_RevokedDeviceWithCredentials_RecoversWithANewKey(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	tok := e.fullLogin(t, a)
	kAccountPub, kAccountPriv := newKeyPair(t)
	if code, d := e.enrolWith(t, tok, "device-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d %s", code, d)
	}
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-A/revoke", "{}", tok, "device-A"); rc >= 400 {
		t.Fatalf("fixture revoke: %d", rc)
	}

	// The operator authenticates the account again - which they can, by policy.
	recovTok := e.fullLogin(t, a)

	// Path 1 (intended): a NEW key.
	newPub, newPriv := newKeyPair(t)
	newCode, _ := e.enrolWith(t, recovTok, "device-A-replacement", newPub, newPriv)

	// Path 2 (the thing that must not silently happen): re-presenting the OLD key. This needs its
	// OWN session - reusing recovTok would be refused as errSessionBound, and reading that as
	// "the old key was rejected" would be flatly wrong.
	oldCode, _ := e.enrolWith(t, e.fullLogin(t, a), "device-A-resurrected", kAccountPub, kAccountPriv)
	res, resFound := e.deviceRow(t, a.id, "device-A-resurrected")

	// The revoked row itself must stay revoked either way.
	old, _ := e.deviceRow(t, a.id, "device-A")

	t.Logf("T2: new-key enrolment=%d  old-key re-enrolment=%d  revoked row still revoked=%v",
		newCode, oldCode, old.RevokedAt != nil)
	if newCode >= 400 {
		t.Fatalf("T2 RECOVERY ALLOWED is not achievable with a new key: %d", newCode)
	}
	if old.RevokedAt == nil {
		t.Fatalf("T2: recovery un-revoked the original device row")
	}
	if oldCode < 400 && resFound && res.VerifiedAt != nil {
		t.Logf("T2 ACCEPTED TRADEOFF + §14 HAZARD: the recovery path also accepts the OLD key "+
			"under a new device id (status %d), so an unchanged client would resurrect K_account "+
			"as a device identity. Nothing in the server forbids it, because nothing in the server "+
			"knows the key is old. Race 14.x mitigation must therefore be CLIENT-SIDE key "+
			"generation plus, after retirement, server-side rejection of known-retired keys.", oldCode)
	}
}

// T3: a revoked device holding only K_account, with no credentials, gets nothing.
func TestP66_T3_RevokedDeviceWithoutCredentialsIsFullyLockedOut(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	tok := e.fullLogin(t, a)
	kAccountPub, kAccountPriv := newKeyPair(t)
	if code, d := e.enrolWith(t, tok, "device-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d %s", code, d)
	}
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-A/revoke", "{}", tok, "device-A"); rc >= 400 {
		t.Fatalf("fixture revoke: %d", rc)
	}

	// RevokeDevice kills the device's sessions, so the token it held is dead. That token plus
	// K_account is the entirety of what a credential-less revoked device has.
	type probe struct {
		name, method, path, body, device string
	}
	probes := []probe{
		{"enrol a new device", http.MethodPost, "/api/e2ee/devices/challenge",
			`{"device_id":"device-A-reborn","public_key":"` + hex.EncodeToString(kAccountPub[:]) + `"}`, "device-A-reborn"},
		{"access a device-gated route", http.MethodPost, "/api/e2ee/device-gated", "{}", "device-A"},
		{"mutate the vault", http.MethodPut, "/api/e2ee/vault", gate11VaultBody(1, 0), "device-A"},
		{"create a pairing", http.MethodPost, "/api/e2ee/pairing",
			`{"ephemeral_pub_hex":"` + hex.EncodeToString(kAccountPub[:]) + `"}`, "device-A"},
	}
	for _, p := range probes {
		code, _ := e.req(t, p.method, p.path, p.body, tok, p.device)
		t.Logf("T3 %-28s -> %d", p.name, code)
		if code < 400 {
			t.Fatalf("T3: a credential-less revoked device could %s (%d)", p.name, code)
		}
	}

	// And it cannot re-authenticate without the password.
	wcode, _ := e.login(t, a, "guessed-password")
	t.Logf("T3 %-28s -> %d", "log in without the password", wcode)
	if wcode < 400 {
		t.Fatalf("T3: login succeeded without the password")
	}
	t.Logf("T3 CONFIRMED: RECOVERY DENIED. Revocation destroys the device's sessions in the same " +
		"transaction, so K_account alone reaches nothing - not because the key is refused, but " +
		"because there is no live session to present it through.")
}

// T6: an existing authorized device keeps working through its own device authority.
func TestP66_T6_AuthorizedDeviceUsesItsOwnDeviceAuthority(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	pub, priv := newKeyPair(t)
	if code, d := e.enrolWith(t, tok, "device-A", pub, priv); code >= 400 {
		t.Fatalf("enrol: %d %s", code, d)
	}
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "device-A")
	t.Logf("T6: authorized device on a device-gated route -> %d", gated)
	if gated >= 400 {
		t.Fatalf("T6: an authorized device was refused (%d)", gated)
	}
}

// ============================================================ §10 PAIRING BOUNDARY

// Phase 66 must not turn QR pairing into an account-recovery mechanism. Measured: an
// account-authenticated but deviceless caller can OPEN a pairing and POLL it, but cannot complete
// one - completion still demands device authority, exactly as Phase 65 left it.
func TestP66_PairingIsNotAnAccountRecoveryMechanism(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a) // account authority only; no device anywhere

	ephPub, _ := newKeyPair(t)
	body, _ := json.Marshal(map[string]string{"ephemeral_pub_hex": hex.EncodeToString(ephPub[:])})
	ccode, cout := e.req(t, http.MethodPost, "/api/e2ee/pairing", string(body), tok, "")
	if ccode >= 400 {
		t.Fatalf("a deviceless account session must still open a pairing, got %d", ccode)
	}
	sessionID, _ := cout["session_id"].(string)

	comp, _ := json.Marshal(map[string]string{
		"payload_b64":    "YWNjb3VudC1hdXRob3JpdHktb25seQ==",
		"sender_pub_hex": hex.EncodeToString(ephPub[:]),
	})
	compCode, _ := e.req(t, http.MethodPost, "/api/e2ee/pairing/"+sessionID+"/complete",
		string(comp), tok, "")
	pcode, pout := e.req(t, http.MethodGet, "/api/e2ee/pairing/"+sessionID+"/payload", "", tok, "")
	_, delivered := pout["payload_b64"]

	t.Logf("§10: deviceless create=%d complete=%d payload=%d materialDelivered=%v",
		ccode, compCode, pcode, delivered)
	if compCode < 400 {
		t.Fatalf("§10: account authority alone completed a pairing (%d)", compCode)
	}
	if delivered {
		t.Fatalf("§10: pairing material was delivered without any device authority")
	}
	t.Logf("§10 CONFIRMED: ACCOUNT RECOVERY and DEVICE PAIRING stay distinct. Pairing completion " +
		"still requires an existing authorized device (Phase 65 intact).")
}

// ============================================================ §13 CENTRAL INVARIANT

// The post-retirement invariant, measured in the two halves that can be measured today.
//
// HALF ONE (provable now): K_account without a live session reaches nothing. That is T3.
//
// HALF TWO (was NOT provable in Phase 66): K_account WITH a live account session still enrols,
// because the server had no notion of a retired key.
//
// PHASE 67 SUPPLIED THAT STATE. e2ee_devices.authority_kind records which authority minted a row,
// and e2ee_account_authorities.legacy_retired_at carries the account lifecycle. This test therefore
// now measures the LEGACY_OPEN half - which is deliberately still permissive, because Phase 67
// established the mechanism without rolling retirement out. The DENY half lives in
// TestP67_RetiredAccountKeyCannotBeResurrected.
func TestP66_S13_RetirementRequiresNewServerState(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	tok := e.fullLogin(t, a)
	kAccountPub, kAccountPriv := newKeyPair(t)
	if code, d := e.enrolWith(t, tok, "legacy-device", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d %s", code, d)
	}
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/legacy-device/revoke", "{}",
		tok, "legacy-device"); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}

	// A fresh account-authenticated session, then the SAME retired key under a new id.
	fresh := e.fullLogin(t, a)
	code, _ := e.enrolWith(t, fresh, "legacy-device-reborn", kAccountPub, kAccountPriv)
	row, found := e.deviceRow(t, a.id, "legacy-device-reborn")

	var cols []string
	e.db.Raw(`SELECT column_name FROM information_schema.columns
	          WHERE table_name = 'e2_ee_devices' ORDER BY column_name`).Scan(&cols)
	hasProvenance := false
	for _, c := range cols {
		if c == "authority_kind" {
			hasProvenance = true
		}
	}

	t.Logf("§13: LEGACY_OPEN re-enrolment of the old key under a new id -> %d verified=%v kind=%q",
		code, found && row.VerifiedAt != nil, row.AuthorityKind)
	t.Logf("§13: provenance column present = %v", hasProvenance)

	if !hasProvenance {
		t.Fatalf("§13: e2ee_devices.authority_kind is gone - the Phase 67 retirement mechanism " +
			"has nothing to build its deny-list from")
	}
	if code >= 400 {
		t.Fatalf("§13: LEGACY_OPEN must stay permissive so existing clients are not stranded, "+
			"got %d", code)
	}
	if row.AuthorityKind != models.AuthorityLegacy {
		t.Fatalf("§13: an undeclared K_account enrolment was recorded as %q", row.AuthorityKind)
	}
	t.Logf("§13 RESOLVED BY PHASE 67: the gap this test was written to pin - no server state " +
		"recording which authority minted a device - is closed. The re-enrolment above still " +
		"succeeds because the account is LEGACY_OPEN, which is intended: Phase 67 built the " +
		"retirement mechanism and deliberately did not roll it out. Once retired, the same " +
		"attempt is refused - see TestP67_RetiredAccountKeyCannotBeResurrected.")
}

// ============================================================ §14 RACES

// Race 1 + 9: recovery enrolment concurrent with revocation of another device.
func TestP66_Race_RecoveryConcurrentWithRevocationOfAnotherDevice(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	adminTok := e.fullLogin(t, a)
	oldPub, oldPriv := newKeyPair(t)
	if code, d := e.enrolWith(t, adminTok, "device-old", oldPub, oldPriv); code >= 400 {
		t.Fatalf("fixture: %d %s", code, d)
	}

	recovTok := e.fullLogin(t, a)
	newPub, newPriv := newKeyPair(t)

	var wg sync.WaitGroup
	var enrolCode, revokeCode int
	wg.Add(2)
	go func() { defer wg.Done(); enrolCode, _ = e.enrolWith(t, recovTok, "device-new", newPub, newPriv) }()
	go func() {
		defer wg.Done()
		revokeCode, _ = e.req(t, http.MethodPost, "/api/e2ee/devices/device-old/revoke", "{}",
			adminTok, "device-old")
	}()
	wg.Wait()

	oldRow, _ := e.deviceRow(t, a.id, "device-old")
	newRow, newFound := e.deviceRow(t, a.id, "device-new")
	t.Logf("race 1/9: enrol=%d revoke=%d | old revoked=%v new verified=%v",
		enrolCode, revokeCode, oldRow.RevokedAt != nil, newFound && newRow.VerifiedAt != nil)
	if oldRow.RevokedAt == nil {
		t.Fatalf("race 1/9: the revocation was lost")
	}
	t.Logf("race 1/9 BOUNDARY: the two touch disjoint device rows and disjoint sessions, so the " +
		"existing per-row transaction is sufficient. No new atomic boundary is required here.")
}

// Race 3: two simultaneous recovery attempts on the same account.
func TestP66_Race_TwoSimultaneousRecoveryAttempts(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)

	tokA := e.fullLogin(t, a)
	tokB := e.fullLogin(t, a)
	pubA, privA := newKeyPair(t)
	pubB, privB := newKeyPair(t)

	var wg sync.WaitGroup
	codes := make([]int, 2)
	wg.Add(2)
	go func() { defer wg.Done(); codes[0], _ = e.enrolWith(t, tokA, "recovery-A", pubA, privA) }()
	go func() { defer wg.Done(); codes[1], _ = e.enrolWith(t, tokB, "recovery-B", pubB, privB) }()
	wg.Wait()

	var verified int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND verified_at IS NOT NULL AND revoked_at IS NULL", a.id).Count(&verified)
	t.Logf("race 3: codes=%v verified devices=%d", codes, verified)
	t.Logf("race 3 BOUNDARY: recovery is not exclusive by design - a user may legitimately recover " +
		"two machines. Each attempt is independently authenticated and each mints its own key, so " +
		"the required boundary is per-(account, device_id), which the unique index already provides.")
}

// Race 5: recovery concurrent with a password change. The password change must win by killing the
// sessions the recovery is running on.
func TestP66_Race_RecoveryVersusPasswordChange(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	// Model the password reset's security-relevant effect: revoke every session for the account,
	// which is what CompletePasswordReset does.
	now := time.Now()
	if err := e.db.Model(&models.Session{}).
		Where("user_id = ? AND revoked_at IS NULL", a.id).
		Updates(map[string]interface{}{
			"revoked_at": now, "revoke_reason": "password_reset", "refresh_hash": nil,
		}).Error; err != nil {
		t.Fatalf("revoke sessions: %v", err)
	}

	pub, priv := newKeyPair(t)
	code, detail := e.enrolWith(t, tok, "post-reset-device", pub, priv)
	_, found := e.deviceRow(t, a.id, "post-reset-device")
	t.Logf("race 5: enrolment on a session killed by a password reset -> %d (%s) deviceCreated=%v",
		code, detail, found)
	if code < 400 || found {
		t.Fatalf("race 5: recovery survived a password reset (%d, created=%v)", code, found)
	}
	t.Logf("race 5 BOUNDARY: session revocation is the choke point and it already dominates. " +
		"A recovery in flight dies with its session, so no extra boundary is needed - PROVIDED " +
		"the future recovery marker lives ON the session row and not in a separate cache.")
}

// Race 7: enrolment succeeds but the session binding is what actually confers authority.
//
// The two are one transaction in RegisterDevice, so the failure mode "device created, session
// unbound" should not be observable. Measured by checking both after a successful enrolment.
func TestP66_Race_DeviceCreationAndSessionBindingAreOneOutcome(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	pub, priv := newKeyPair(t)
	if code, d := e.enrolWith(t, tok, "device-A", pub, priv); code >= 400 {
		t.Fatalf("enrol: %d %s", code, d)
	}

	row, _ := e.deviceRow(t, a.id, "device-A")
	var s models.Session
	if err := e.db.Where("user_id = ? AND device_id = ?", a.id, row.ID).First(&s).Error; err != nil {
		t.Fatalf("race 7: the device was verified but no session is bound to it: %v", err)
	}
	t.Logf("race 7: device verified=%v and session %s bound to it", row.VerifiedAt != nil, s.ID)
	t.Logf("race 7 BOUNDARY: RegisterDevice already makes creation+binding a single outcome. The " +
		"future recovery flow inherits this and needs no new boundary.")
}

// Race 8: enrolment succeeds, vault creation then fails. Device authority must survive, otherwise
// the user is stranded holding a key the server will not honour.
func TestP66_Race_DeviceAuthoritySurvivesAFailedVaultWrite(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	pub, priv := newKeyPair(t)
	if code, d := e.enrolWith(t, tok, "device-A", pub, priv); code >= 400 {
		t.Fatalf("enrol: %d %s", code, d)
	}

	// A vault write that is refused (expected_version on an absent vault).
	bad, _ := e.req(t, http.MethodPut, "/api/e2ee/vault", gate11VaultBody(1, 7), tok, "device-A")
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "device-A")
	var vaults int64
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)

	t.Logf("race 8: refused vault write=%d vault rows=%d device-gated afterwards=%d", bad, vaults, gated)
	if bad < 400 {
		t.Fatalf("race 8: the fixture's vault write was supposed to be refused")
	}
	if vaults != 0 {
		t.Fatalf("race 8: a refused vault write created %d row(s)", vaults)
	}
	if gated >= 400 {
		t.Fatalf("race 8: a failed vault write cost the device its authority (%d)", gated)
	}
	t.Logf("race 8 BOUNDARY: enrolment and vault creation are independent, and must STAY " +
		"independent once the vault is device-gated - otherwise a failed first vault write would " +
		"strand a legitimately recovered device.")
}

// Race 6: a 2FA change while an account-authenticated session is live.
//
// This decides where a future ACCOUNT-RECOVERY-AUTHENTICATED marker may live. If disabling or
// rotating TOTP leaves existing sessions untouched, then a marker stamped on the session at login
// outlives the very factor that justified it.
func TestP66_Race_TwoFactorChangeDoesNotInvalidateLiveSessions(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a) // authorised by password + TOTP

	// The account's second factor is removed (or rotated - the session-level effect is the same).
	if err := e.db.Exec(`UPDATE users SET totp_enabled = false, totp_secret = '' WHERE id = ?`,
		a.id).Error; err != nil {
		t.Fatalf("disable totp: %v", err)
	}

	var live int64
	e.db.Model(&models.Session{}).Where("user_id = ? AND revoked_at IS NULL", a.id).Count(&live)

	pub, priv := newKeyPair(t)
	code, _ := e.enrolWith(t, tok, "post-2fa-change-device", pub, priv)

	t.Logf("race 6: after disabling TOTP -> live sessions=%d, enrolment on the old session=%d", live, code)
	if live == 0 || code >= 400 {
		t.Logf("race 6: the 2FA change already tore the session down - a session-stamped marker " +
			"would be safe as-is")
		return
	}
	t.Logf("race 6 BOUNDARY: changing the second factor does NOT revoke existing sessions, so a " +
		"recovery marker stamped at login would survive the removal of the factor that earned it. " +
		"Either the 2FA-change paths must revoke sessions (as password reset does), or the marker " +
		"must be short-lived and single-use rather than a durable session attribute.")
}

// Race 4: recovery racing an ordinary enrolment of the SAME device id from two sessions.
func TestP66_Race_ConcurrentEnrolmentOfTheSameDeviceId(t *testing.T) {
	e := p66Setup(t)
	a := e.newAccount(t, true)
	tokA := e.fullLogin(t, a)
	tokB := e.fullLogin(t, a)
	pubA, privA := newKeyPair(t)
	pubB, privB := newKeyPair(t)

	var wg sync.WaitGroup
	codes := make([]int, 2)
	wg.Add(2)
	go func() { defer wg.Done(); codes[0], _ = e.enrolWith(t, tokA, "shared-id", pubA, privA) }()
	go func() { defer wg.Done(); codes[1], _ = e.enrolWith(t, tokB, "shared-id", pubB, privB) }()
	wg.Wait()

	var rows int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", a.id, "shared-id").Count(&rows)
	row, _ := e.deviceRow(t, a.id, "shared-id")
	winner := "neither"
	switch row.PublicKey {
	case hex.EncodeToString(pubA[:]):
		winner = "A"
	case hex.EncodeToString(pubB[:]):
		winner = "B"
	}
	t.Logf("race 4: codes=%v rows=%d surviving key=%s", codes, rows, winner)
	if rows != 1 {
		t.Fatalf("race 4: expected exactly one row for the id, found %d", rows)
	}
	if codes[0] < 400 && codes[1] < 400 {
		t.Fatalf("race 4: both proofs were accepted for one identity (%v)", codes)
	}
	t.Logf("race 4 BOUNDARY: the loser is REFUSED, not overwritten - RegisterDevice re-reads the " +
		"row under lock and rejects a proof whose key differs from the one already bound " +
		"(\"the key that was proved is the key that stays\"). So one device_id keeps exactly one " +
		"key for its lifetime, and a recovery flow needs no additional boundary here: it must " +
		"simply use a NEW device_id rather than re-proving an existing one.")
}

// ============================================================ §11 THE PHASE 60 DEADLOCK

// Phase 60 stopped because gating PUT /e2ee/vault refused a fresh account's FIRST write: the client
// had no device authority yet, and the only way to get it appeared to need the vault.
//
// Under this policy that ordering dissolves, and this test measures it end to end on a GATED vault
// route: password + 2FA -> enrol a locally generated K_device -> device authority -> vault CREATE.
// No vault existed at any point before the enrolment.
func TestP66_S9_VaultCreateWorksAfterRecoveryEnrolmentOnAGatedRoute(t *testing.T) {
	e := p66Setup(t)

	// The same handler, but behind the device gate the target architecture wants.
	e.app.Put("/api/e2ee/vault-gated", middleware.RequireDeviceIdentity(), NewE2EEHandler(nil).PutVault)

	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	var vaults int64
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)
	if vaults != 0 {
		t.Fatalf("fixture: the account must start with no vault, found %d", vaults)
	}

	// Step 1: with account authority only, the gated vault route is closed. This IS Phase 60.
	before, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), tok, "new-device")
	t.Logf("§11 step 1: gated vault CREATE with account authority only -> %d (the Phase 60 deadlock)", before)
	if before < 400 {
		t.Fatalf("§11: the gated route accepted a deviceless caller (%d)", before)
	}

	// Step 2: enrol a locally generated K_device. No vault is read or written to do this.
	kDevicePub, kDevicePriv := newKeyPair(t)
	code, detail := e.enrolWith(t, tok, "new-device", kDevicePub, kDevicePriv)
	if code >= 400 {
		t.Fatalf("§11 step 2: enrolment before any vault exists failed: %d %s", code, detail)
	}
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)
	t.Logf("§11 step 2: K_device enrolled=%d with vault rows still = %d", code, vaults)
	if vaults != 0 {
		t.Fatalf("§11: enrolment created a vault; it must not")
	}

	// Step 3: the same gated CREATE now succeeds.
	after, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), tok, "new-device")
	// Step 4: and UPDATE too.
	upd, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(2, 1), tok, "new-device")
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)

	t.Logf("§11 step 3/4: gated vault CREATE=%d UPDATE=%d rows=%d", after, upd, vaults)
	if after >= 400 || upd >= 400 || vaults != 1 {
		t.Fatalf("§11: gated CREATE/UPDATE after enrolment failed: create=%d update=%d rows=%d",
			after, upd, vaults)
	}
	t.Logf("§11/S9/S10 CONFIRMED: the Phase 60 deadlock was an ORDERING problem, not a missing " +
		"secret. Enrolment needs no vault, so account recovery can precede vault CREATE and both " +
		"CREATE and UPDATE can be device-gated with no permanent account-only exception.")
}
