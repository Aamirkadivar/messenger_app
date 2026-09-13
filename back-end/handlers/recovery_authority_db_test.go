package handlers

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/nacl/box"
)

// PHASE 67 - the implemented authority model, exercised against the REAL handlers.
//
// Two authorities, and the whole suite exists to keep them apart:
//
//	ACCOUNT AUTHORITY   password + verified TOTP -> a short-lived, single-use marker on the
//	                    session, spendable ONCE to bootstrap a device identity. It is not
//	                    device authority and never opens a device-gated route by itself.
//	DEVICE AUTHORITY    a locally generated K_device, proved through the Phase 44 crypto_box
//	                    challenge, recorded on the device row and bound to the session.
//
// Phase 66 measured what was missing. This suite measures that it is now there, and - just as
// importantly - that nothing which used to hold has stopped holding.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

// p67Env reuses the Phase 66 harness: real AuthMiddleware, real Login/Verify2FA, real tokens.
type p67Env struct{ *p66Env }

func p67Setup(t *testing.T) *p67Env {
	t.Helper()
	e := p66Setup(t)
	// The Phase 67 vault gate, exactly as main.go now wires it.
	e.app.Put("/api/e2ee/vault-gated", middleware.RequireDeviceIdentity(), NewE2EEHandler(nil).PutVault)
	return &p67Env{e}
}

// enrolAs runs the real challenge/proof, declaring an authority kind the way a client does.
func (e *p67Env) enrolAs(t *testing.T, token, deviceID, kind string, pub, priv *[32]byte) (int, map[string]any) {
	t.Helper()
	chBody, _ := json.Marshal(map[string]string{
		"device_id": deviceID, "public_key": hex.EncodeToString(pub[:]),
	})
	code, out := e.req(t, http.MethodPost, "/api/e2ee/devices/challenge", string(chBody), token, deviceID)
	if code >= 400 {
		return code, out
	}
	proof := openChallenge(t, out, priv)
	regBody, _ := json.Marshal(map[string]string{
		"device_id": deviceID, "public_key": hex.EncodeToString(pub[:]),
		"challenge_id":   out["challenge_id"].(string),
		"proof_b64":      proof,
		"authority_kind": kind,
	})
	return e.req(t, http.MethodPost, "/api/e2ee/devices", string(regBody), token, deviceID)
}

// enrolDevice is the Phase 67 client flow: a freshly generated key, declared per-device.
func (e *p67Env) enrolDevice(t *testing.T, token, deviceID string, pub, priv *[32]byte) (int, map[string]any) {
	t.Helper()
	return e.enrolAs(t, token, deviceID, models.AuthorityDevice, pub, priv)
}

// enrolLegacy is the pre-Phase-67 client: the account-wide key, no declaration.
func (e *p67Env) enrolLegacy(t *testing.T, token, deviceID string, pub, priv *[32]byte) (int, map[string]any) {
	t.Helper()
	return e.enrolAs(t, token, deviceID, "", pub, priv)
}

func (e *p67Env) sessionRow(t *testing.T, user uuid.UUID) models.Session {
	t.Helper()
	var s models.Session
	if err := e.db.Where("user_id = ?", user).Order("created_at DESC").First(&s).Error; err != nil {
		t.Fatalf("session: %v", err)
	}
	return s
}

func (e *p67Env) hasRecoveryAuthority(t *testing.T, user uuid.UUID) bool {
	t.Helper()
	s := e.sessionRow(t, user)
	return s.RecoveryAuthorityExpiresAt != nil &&
		s.RecoveryAuthorityConsumedAt == nil &&
		s.RecoveryAuthorityExpiresAt.After(time.Now())
}

func (e *p67Env) retire(t *testing.T, user uuid.UUID) {
	t.Helper()
	if err := retireLegacyAuthority(e.db, user, time.Now()); err != nil {
		t.Fatalf("retire: %v", err)
	}
}

func (e *p67Env) deviceRowP67(t *testing.T, user uuid.UUID, deviceID string) (models.E2EEDevice, bool) {
	t.Helper()
	var d models.E2EEDevice
	err := e.db.Where("user_id = ? AND device_id = ?", user, deviceID).First(&d).Error
	return d, err == nil
}

// ============================================================ §15.1-5 recovery authority is earned

func TestP67_RecoveryAuthorityIsGrantedOnlyByPasswordPlusTotp(t *testing.T) {
	// 1. password + TOTP -> present
	t.Run("password+TOTP grants it", func(t *testing.T) {
		e := p67Setup(t)
		a := e.newAccount(t, true)
		if tok := e.fullLogin(t, a); tok == "" {
			t.Fatalf("login failed")
		}
		if !e.hasRecoveryAuthority(t, a.id) {
			t.Fatalf("password+TOTP did not grant recovery authority")
		}
		s := e.sessionRow(t, a.id)
		t.Logf("1. granted, expires in %s", time.Until(*s.RecoveryAuthorityExpiresAt).Round(time.Second))
	})

	// 2. password only -> absent
	t.Run("password only does not", func(t *testing.T) {
		e := p67Setup(t)
		a := e.newAccount(t, false)
		if tok := e.fullLogin(t, a); tok == "" {
			t.Fatalf("login failed")
		}
		s := e.sessionRow(t, a.id)
		t.Logf("2. password-only session marker = %v", s.RecoveryAuthorityExpiresAt)
		if s.RecoveryAuthorityExpiresAt != nil {
			t.Fatalf("a password-only login earned recovery authority")
		}
	})

	// 3. invalid TOTP -> absent (and no session at all)
	t.Run("invalid TOTP does not", func(t *testing.T) {
		e := p67Setup(t)
		a := e.newAccount(t, true)
		_, out := e.login(t, a, a.password)
		cid, _ := out["challenge_id"].(string)
		code, vout := e.verify2FA(t, cid, "000000")
		var sessions int64
		e.db.Model(&models.Session{}).Where("user_id = ?", a.id).Count(&sessions)
		t.Logf("3. invalid TOTP -> %d, tokens=%v, sessions created=%d",
			code, accessToken(vout) != "", sessions)
		if code < 400 || sessions != 0 {
			t.Fatalf("an invalid TOTP code produced state: %d sessions=%d", code, sessions)
		}
	})

	// 4. missing TOTP (password accepted, second factor never supplied) -> absent
	t.Run("stopping at the password does not", func(t *testing.T) {
		e := p67Setup(t)
		a := e.newAccount(t, true)
		code, out := e.login(t, a, a.password)
		var sessions int64
		e.db.Model(&models.Session{}).Where("user_id = ?", a.id).Count(&sessions)
		t.Logf("4. password stage -> %d requires_2fa=%v sessions=%d", code, out["requires_2fa"], sessions)
		if accessToken(out) != "" || sessions != 0 {
			t.Fatalf("the password stage alone minted a session")
		}
	})

	// 5. wrong password -> absent
	t.Run("wrong password does not", func(t *testing.T) {
		e := p67Setup(t)
		a := e.newAccount(t, true)
		code, _ := e.login(t, a, "wrong")
		var sessions int64
		e.db.Model(&models.Session{}).Where("user_id = ?", a.id).Count(&sessions)
		t.Logf("5. wrong password -> %d sessions=%d", code, sessions)
		if code < 400 || sessions != 0 {
			t.Fatalf("a wrong password produced state")
		}
	})
}

// 6. The marker expires.
func TestP67_RecoveryAuthorityExpires(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	s := e.sessionRow(t, a.id)

	// Age it past its TTL rather than sleeping: the property under test is the
	// predicate, not the clock.
	past := time.Now().Add(-time.Minute)
	if err := e.db.Model(&models.Session{}).Where("id = ?", s.ID).
		Update("recovery_authority_expires_at", past).Error; err != nil {
		t.Fatalf("age marker: %v", err)
	}
	e.retire(t, a.id) // retirement is what makes the marker load-bearing

	pub, priv := newKeyPair(t)
	code, out := e.enrolDevice(t, tok, "late-device", pub, priv)
	t.Logf("6. enrolment with an expired marker -> %d %v", code, out["error"])
	if code < 400 {
		t.Fatalf("an expired recovery marker was accepted (%d)", code)
	}
	if _, found := e.deviceRowP67(t, a.id, "late-device"); found {
		t.Fatalf("a refused enrolment created a device row")
	}
}

// 7 + 8. Single-use, and a replay of the same flow on the same session fails.
func TestP67_RecoveryAuthorityIsSingleUseAndCannotBeReplayed(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	e.retire(t, a.id)
	tok := e.fullLogin(t, a)

	pub1, priv1 := newKeyPair(t)
	first, _ := e.enrolDevice(t, tok, "device-1", pub1, priv1)
	if first >= 400 {
		t.Fatalf("the first enrolment must succeed, got %d", first)
	}
	s := e.sessionRow(t, a.id)
	if s.RecoveryAuthorityConsumedAt == nil {
		t.Fatalf("a successful enrolment did not consume the marker")
	}

	// A second, different device on the SAME session: the marker is spent, and the
	// session is already bound. Both reasons are sufficient; either refusal is correct.
	pub2, priv2 := newKeyPair(t)
	second, sout := e.enrolDevice(t, tok, "device-2", pub2, priv2)
	// And a literal replay of the first device's enrolment.
	replay, _ := e.enrolDevice(t, tok, "device-1", pub1, priv1)

	t.Logf("7/8. first=%d consumed=%v second-device=%d (%v) replay-of-first=%d",
		first, s.RecoveryAuthorityConsumedAt != nil, second, sout["error"], replay)
	if second < 400 {
		t.Fatalf("a spent marker bootstrapped a second device (%d)", second)
	}
	if _, found := e.deviceRowP67(t, a.id, "device-2"); found {
		t.Fatalf("the refused second enrolment created a device row")
	}
	// The replay is the SAME device this session is bound to, so it is the
	// idempotent reconnect path and must keep working - a client that reconnects
	// must not be locked out by retirement.
	if replay >= 400 {
		t.Fatalf("re-registering the already-bound device was refused (%d); reconnects would break", replay)
	}
}

// 9. The marker cannot be moved to another session.
func TestP67_RecoveryAuthorityIsNotTransferableBetweenSessions(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	e.retire(t, a.id)

	_ = e.fullLogin(t, a) // this session holds the marker; it is the LENDER
	recoverySession := e.sessionRow(t, a.id)

	// A second, ordinary session for the same account: password-only is impossible
	// here (TOTP is enabled), so model the transfer attempt directly - a caller
	// holding a DIFFERENT session's token tries to spend this account's authority.
	otherTok := e.fullLogin(t, a)
	otherSession := e.sessionRow(t, a.id)
	if otherSession.ID == recoverySession.ID {
		t.Fatalf("fixture: expected two distinct sessions")
	}
	// Strip the second session's own marker so the only authority in play belongs
	// to the first session.
	if err := e.db.Model(&models.Session{}).Where("id = ?", otherSession.ID).
		Update("recovery_authority_expires_at", nil).Error; err != nil {
		t.Fatalf("clear marker: %v", err)
	}

	pub, priv := newKeyPair(t)
	code, out := e.enrolDevice(t, otherTok, "borrowed-authority", pub, priv)
	t.Logf("9. enrolling on a marker-less session while another session holds one -> %d %v",
		code, out["error"])
	if code < 400 {
		t.Fatalf("a session borrowed another session's recovery authority (%d)", code)
	}

	// The lender's marker is untouched.
	after := models.Session{}
	e.db.Where("id = ?", recoverySession.ID).First(&after)
	if after.RecoveryAuthorityConsumedAt != nil {
		t.Fatalf("the other session's failed attempt spent this session's marker")
	}
	t.Logf("9. the holder's marker is still unspent")
}

// ============================================================ §15.10-17 device identity

// 10 + 11 + 12. A fresh K_device enrols, and the row carries exactly that key.
func TestP67_FreshDeviceKeyEnrolsAndIsRecordedAsPerDeviceAuthority(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	kAccountPub, _ := newKeyPair(t) // what the vault would hand every device
	kDevicePub, kDevicePriv := newKeyPair(t)

	code, _ := e.enrolDevice(t, tok, "device-A", kDevicePub, kDevicePriv)
	d, found := e.deviceRowP67(t, a.id, "device-A")

	t.Logf("10/11/12. enrol=%d authority_kind=%q key==K_device=%v key==K_account=%v",
		code, d.AuthorityKind, d.PublicKey == hex.EncodeToString(kDevicePub[:]),
		d.PublicKey == hex.EncodeToString(kAccountPub[:]))
	if code >= 400 || !found {
		t.Fatalf("fresh K_device enrolment failed: %d", code)
	}
	if d.PublicKey != hex.EncodeToString(kDevicePub[:]) {
		t.Fatalf("the stored key is not the key that was proved")
	}
	if d.PublicKey == hex.EncodeToString(kAccountPub[:]) {
		t.Fatalf("K_device collided with K_account")
	}
	if d.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("expected authority_kind=%q, got %q", models.AuthorityDevice, d.AuthorityKind)
	}
}

// A legacy client is recorded as legacy even when its user logged in with TOTP.
//
// This is what stops provenance from being an accident of how the user signed in:
// per-device authority needs the client to SAY it is presenting a device key AND a
// marker to have been spent.
func TestP67_LegacyClientIsRecordedAsLegacyEvenWithARecoveryMarker(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	if !e.hasRecoveryAuthority(t, a.id) {
		t.Fatalf("fixture: expected a marker")
	}

	kAccountPub, kAccountPriv := newKeyPair(t)
	code, _ := e.enrolLegacy(t, tok, "legacy-device", kAccountPub, kAccountPriv)
	d, _ := e.deviceRowP67(t, a.id, "legacy-device")
	t.Logf("legacy client + marker -> enrol=%d authority_kind=%q", code, d.AuthorityKind)
	if code >= 400 {
		t.Fatalf("a legacy client must still enrol while LEGACY_OPEN, got %d", code)
	}
	if d.AuthorityKind != models.AuthorityLegacy {
		t.Fatalf("an undeclared enrolment was recorded as %q", d.AuthorityKind)
	}
}

// 14. Re-proving an existing device id with a DIFFERENT key is refused, unchanged.
// 15. One session binds at most one device (§9).
// 16. Session/device mismatch. 17. Revoked device.
func TestP67_ExistingDeviceInvariantsAreUnchanged(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)

	tok := e.fullLogin(t, a)
	pubA, privA := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tok, "device-A", pubA, privA); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}

	// 14. same device id, different key, fresh session.
	pubX, privX := newKeyPair(t)
	swap, _ := e.enrolDevice(t, e.fullLogin(t, a), "device-A", pubX, privX)
	t.Logf("14. key swap on an existing device id -> %d", swap)
	if swap < 400 {
		t.Fatalf("14: a different key was installed under an existing identity (%d)", swap)
	}

	// 15/§9. The session bound to A cannot then bind B.
	pubB, privB := newKeyPair(t)
	bindTwo, out := e.enrolDevice(t, tok, "device-B", pubB, privB)
	t.Logf("15. binding a second device on the same session -> %d %v", bindTwo, out["error"])
	if bindTwo < 400 {
		t.Fatalf("15: one session bound two devices (%d)", bindTwo)
	}

	// 16. session A + device B header on a device-gated route.
	mismatch, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "device-B")
	t.Logf("16. session bound to A, header says B -> %d", mismatch)
	if mismatch < 400 {
		t.Fatalf("16: a mismatched device header was accepted (%d)", mismatch)
	}

	// 17. revoked device.
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-A/revoke", "{}", tok, "device-A"); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	revoked, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "device-A")
	t.Logf("17. revoked device -> %d", revoked)
	if revoked < 400 {
		t.Fatalf("17: a revoked device kept authority (%d)", revoked)
	}
}

// §9 explicitly: NULL -> may bind once; bound to A -> A ok, B refused.
func TestP67_OneSessionOneDeviceStateMachine(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	s := e.sessionRow(t, a.id)
	if s.DeviceID != nil {
		t.Fatalf("a fresh session must start unbound")
	}
	pubA, privA := newKeyPair(t)
	pubB, privB := newKeyPair(t)

	bindA, _ := e.enrolDevice(t, tok, "A", pubA, privA)
	reA, _ := e.enrolDevice(t, tok, "A", pubA, privA) // idempotent
	bindB, _ := e.enrolDevice(t, tok, "B", pubB, privB)

	t.Logf("§9. NULL->A=%d  A->A=%d  A->B=%d", bindA, reA, bindB)
	if bindA >= 400 {
		t.Fatalf("an unbound session could not bind once (%d)", bindA)
	}
	if reA >= 400 {
		t.Fatalf("re-proving the bound device was refused (%d)", reA)
	}
	if bindB < 400 {
		t.Fatalf("a bound session adopted a second device (%d)", bindB)
	}
	if _, found := e.deviceRowP67(t, a.id, "B"); found {
		t.Fatalf("the refused second binding still created device B")
	}
}

// ============================================================ §15.18-23 legacy retirement

// 18 + 19. Legacy enrolment works while LEGACY_OPEN and stops at LEGACY_RETIRED.
func TestP67_LegacyEnrolmentWorksUntilRetirement(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	open, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-1", kAccountPub, kAccountPriv)
	t.Logf("18. LEGACY_OPEN legacy enrolment -> %d", open)
	if open >= 400 {
		t.Fatalf("18: legacy enrolment must work while LEGACY_OPEN, got %d", open)
	}

	e.retire(t, a.id)

	kOtherPub, kOtherPriv := newKeyPair(t)
	closed, out := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-2", kOtherPub, kOtherPriv)
	t.Logf("19. LEGACY_RETIRED legacy enrolment -> %d %v", closed, out["error"])
	if closed < 400 {
		t.Fatalf("19: legacy enrolment survived retirement (%d)", closed)
	}
	if _, found := e.deviceRowP67(t, a.id, "legacy-2"); found {
		t.Fatalf("19: a refused legacy enrolment created a device row")
	}
}

// 20 + 23. The old K_account cannot come back under a new device id after retirement,
// and being revoked does not help.
func TestP67_RetiredAccountKeyCannotBeResurrected(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	revTok := e.fullLogin(t, a)
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/legacy-A/revoke", "{}", revTok, ""); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	e.retire(t, a.id)

	// Declared honestly...
	asLegacy, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A-reborn", kAccountPub, kAccountPriv)
	// ...and declared dishonestly, by a caller with a real marker claiming the old
	// key is a device key. The deny-list is applied whatever is declared.
	asDevice, dout := e.enrolAs(t, e.fullLogin(t, a), "legacy-A-disguised",
		models.AuthorityDevice, kAccountPub, kAccountPriv)

	t.Logf("20/23. retired K_account re-enrol: declared legacy=%d declared device=%d %v",
		asLegacy, asDevice, dout["error"])
	if asLegacy < 400 {
		t.Fatalf("20: the retired account key re-enrolled (%d)", asLegacy)
	}
	if asDevice < 400 {
		t.Fatalf("23: declaring the old key as a device key evaded retirement (%d)", asDevice)
	}
	for _, id := range []string{"legacy-A-reborn", "legacy-A-disguised"} {
		if _, found := e.deviceRowP67(t, a.id, id); found {
			t.Fatalf("a refused enrolment created %s", id)
		}
	}
}

// 21. A fresh K_device with recovery authority succeeds after retirement.
func TestP67_FreshDeviceKeySucceedsAfterRetirement(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	e.retire(t, a.id)

	tok := e.fullLogin(t, a)
	pub, priv := newKeyPair(t)
	code, _ := e.enrolDevice(t, tok, "modern-device", pub, priv)
	d, _ := e.deviceRowP67(t, a.id, "modern-device")
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "modern-device")

	t.Logf("21. post-retirement fresh K_device -> enrol=%d kind=%q device-gated=%d",
		code, d.AuthorityKind, gated)
	if code >= 400 || gated >= 400 {
		t.Fatalf("21: recovery with a fresh key failed after retirement: enrol=%d gated=%d", code, gated)
	}
	if d.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("21: recorded as %q", d.AuthorityKind)
	}
}

// A non-TOTP account cannot earn recovery authority, so it cannot enrol after retirement.
// That is the policy's stated consequence, pinned here so it cannot regress silently.
func TestP67_NonTotpAccountCannotEnrolAfterRetirement(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, false) // no second factor configured
	e.retire(t, a.id)

	tok := e.fullLogin(t, a) // password only
	pub, priv := newKeyPair(t)
	code, out := e.enrolDevice(t, tok, "no-2fa-device", pub, priv)
	t.Logf("non-TOTP account after retirement -> %d %v", code, out["error"])
	if code < 400 {
		t.Fatalf("a password-only session enrolled after retirement (%d)", code)
	}
	t.Logf("CONSEQUENCE (intended): retirement requires a second factor. An account with " +
		"TotpEnabled=false must configure TOTP before it can enrol again.")
}

// 22. Retirement racing an enrolment is atomic: never a half state.
func TestP67_RetirementRaceWithEnrolmentIsAtomic(t *testing.T) {
	for attempt := 0; attempt < 4; attempt++ {
		e := p67Setup(t)
		a := e.newAccount(t, true)
		tok := e.fullLogin(t, a)
		kAccountPub, kAccountPriv := newKeyPair(t)

		var wg sync.WaitGroup
		var enrolCode int
		wg.Add(2)
		go func() {
			defer wg.Done()
			enrolCode, _ = e.enrolLegacy(t, tok, "racing-legacy", kAccountPub, kAccountPriv)
		}()
		go func() {
			defer wg.Done()
			_ = retireLegacyAuthority(e.db, a.id, time.Now())
		}()
		wg.Wait()

		d, found := e.deviceRowP67(t, a.id, "racing-legacy")
		var acct models.E2EEAccountAuthority
		retiredNow := e.db.Where("user_id = ?", a.id).First(&acct).Error == nil &&
			acct.LegacyRetiredAt != nil

		t.Logf("22. attempt %d: enrol=%d rowExists=%v retired=%v", attempt, enrolCode, found, retiredNow)
		if !retiredNow {
			t.Fatalf("22: the retirement was lost")
		}
		// The only two coherent outcomes: it committed before retirement was
		// visible (row exists, 2xx) or it was refused (no row). Never a 2xx with
		// no row, and never a refusal that still left one.
		if (enrolCode < 400) != found {
			t.Fatalf("22: torn outcome - status %d but rowExists=%v", enrolCode, found)
		}
		// A row that survived the race must still carry honest provenance: it was
		// a legacy enrolment and must be recorded as one, or the deny-list that
		// retirement depends on would have a hole in it.
		if found && d.AuthorityKind != models.AuthorityLegacy {
			t.Fatalf("22: a racing legacy enrolment was recorded as %q", d.AuthorityKind)
		}
	}
}

// A refused enrolment must not spend the marker (§6).
func TestP67_FailedEnrolmentDoesNotConsumeTheMarker(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)

	tok := e.fullLogin(t, a)
	s := e.sessionRow(t, a.id)

	// Refused: the retired key, presented with a real marker.
	bad, _ := e.enrolAs(t, tok, "doomed", models.AuthorityDevice, kAccountPub, kAccountPriv)
	afterBad := models.Session{}
	e.db.Where("id = ?", s.ID).First(&afterBad)

	// The same session must still be able to recover properly.
	pub, priv := newKeyPair(t)
	good, _ := e.enrolDevice(t, tok, "proper", pub, priv)

	t.Logf("§6. refused enrolment=%d markerSpentByIt=%v  subsequent proper enrolment=%d",
		bad, afterBad.RecoveryAuthorityConsumedAt != nil, good)
	if bad < 400 {
		t.Fatalf("the fixture's refusal did not happen")
	}
	if afterBad.RecoveryAuthorityConsumedAt != nil {
		t.Fatalf("a failed enrolment spent the marker")
	}
	if good >= 400 {
		t.Fatalf("the session could not recover after a failed attempt (%d)", good)
	}
}

// ============================================================ §16 ADVERSARIAL

// The Phase 66 resurrection attack, before and after retirement.
func TestP67_Adversarial_ResurrectionAttackBeforeAndAfterRetirement(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	// 1-2. A legacy device on K_account, then revoked.
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "device-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("step 1: %d", code)
	}
	revTok := e.fullLogin(t, a)
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-A/revoke", "{}", revTok, ""); rc >= 400 {
		t.Fatalf("step 2: %d", rc)
	}

	// 3-4. Valid password + TOTP, then a fresh device id on the OLD key.
	beforeCode, _ := e.enrolLegacy(t, e.fullLogin(t, a), "resurrect-open", kAccountPub, kAccountPriv)
	t.Logf("§16 BEFORE retirement: old K_account under a fresh device id -> %d "+
		"(LEGACY_OPEN, so migration is deliberately still possible)", beforeCode)

	e.retire(t, a.id)

	afterCode, aout := e.enrolLegacy(t, e.fullLogin(t, a), "resurrect-retired", kAccountPub, kAccountPriv)
	t.Logf("§16 AFTER retirement:  old K_account under a fresh device id -> %d %v",
		afterCode, aout["error"])
	if afterCode < 400 {
		t.Fatalf("§16: the resurrection attack survived retirement (%d)", afterCode)
	}
	if _, found := e.deviceRowP67(t, a.id, "resurrect-retired"); found {
		t.Fatalf("§16: the refused attack created a device row")
	}

	// 5-7. A fresh K_device through account recovery must succeed.
	tok := e.fullLogin(t, a)
	pub, priv := newKeyPair(t)
	okCode, _ := e.enrolDevice(t, tok, "legitimate-replacement", pub, priv)
	gated, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tok, "legitimate-replacement")
	t.Logf("§16 legitimate recovery after retirement: enrol=%d device-gated=%d", okCode, gated)
	if okCode >= 400 || gated >= 400 {
		t.Fatalf("§16: legitimate recovery was blocked: enrol=%d gated=%d", okCode, gated)
	}
}

// ============================================================ §17 CROSS-ACCOUNT

func TestP67_CrossAccountAuthorityCannotBeBorrowed(t *testing.T) {
	e := p67Setup(t)
	accountA := e.newAccount(t, true)
	accountB := e.newAccount(t, true)

	// A recovers with its own key.
	tokA := e.fullLogin(t, accountA)
	pubA, privA := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tokA, "device-of-A", pubA, privA); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	dA, _ := e.deviceRowP67(t, accountA.id, "device-of-A")
	if dA.UserID != accountA.id {
		t.Fatalf("A's device landed under %s", dA.UserID)
	}
	if _, foundUnderB := e.deviceRowP67(t, accountB.id, "device-of-A"); foundUnderB {
		t.Fatalf("A's enrolment created a row under B")
	}
	t.Logf("§17. A's recovery created a device under A only")

	// B enrols its own device, then A tries to use B's device id.
	tokB := e.fullLogin(t, accountB)
	pubB, privB := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tokB, "device-of-B", pubB, privB); code >= 400 {
		t.Fatalf("fixture B: %d", code)
	}
	borrow, _ := e.req(t, http.MethodPost, "/api/e2ee/device-gated", "{}", tokA, "device-of-B")
	t.Logf("§17. A's session naming B's device on a device-gated route -> %d", borrow)
	if borrow < 400 {
		t.Fatalf("§17: a device header moved authority across accounts (%d)", borrow)
	}

	// A enrolling B's PUBLIC KEY under A's own account creates authority for A only,
	// and B's row is untouched.
	freshSessionA := e.fullLogin(t, accountA)
	code, _ := e.enrolDevice(t, freshSessionA, "b-key-under-a", pubB, privB)
	underA, foundA := e.deviceRowP67(t, accountA.id, "b-key-under-a")
	dBAfter, _ := e.deviceRowP67(t, accountB.id, "device-of-B")
	t.Logf("§17. B's key enrolled under A -> %d ownerIsA=%v B's row still verified=%v",
		code, foundA && underA.UserID == accountA.id, dBAfter.VerifiedAt != nil && dBAfter.RevokedAt == nil)
	if foundA && underA.UserID != accountA.id {
		t.Fatalf("§17: enrolment created a row under the wrong account")
	}
	if dBAfter.VerifiedAt == nil || dBAfter.RevokedAt != nil {
		t.Fatalf("§17: A's enrolment disturbed B's device")
	}
}

// ============================================================ §14 VAULT MATRIX

func TestP67_VaultNegativeMatrix(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	other := e.newAccount(t, true)

	// A foreign verified device for the wrong-account row.
	tokOther := e.fullLogin(t, other)
	pubO, privO := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tokOther, "device-of-other", pubO, privO); code >= 400 {
		t.Fatalf("fixture other: %d", code)
	}

	// account-authority session, no device.
	recoveryTok := e.fullLogin(t, a)
	noDevice, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), recoveryTok, "any")
	t.Logf("account-authority session, no device -> %d", noDevice)
	if noDevice != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", noDevice)
	}

	// password-only session.
	plain := e.newAccount(t, false)
	plainTok := e.fullLogin(t, plain)
	pwOnly, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), plainTok, "any")
	t.Logf("password-only session          -> %d", pwOnly)
	if pwOnly != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", pwOnly)
	}

	// invented device id.
	invented, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), recoveryTok, "INVENTED")
	t.Logf("invented device id             -> %d", invented)
	if invented != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", invented)
	}

	// wrong-account device id.
	foreign, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), recoveryTok, "device-of-other")
	t.Logf("wrong-account device           -> %d", foreign)
	if foreign != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", foreign)
	}

	var vaults int64
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)
	if vaults != 0 {
		t.Fatalf("a refused caller created %d vault(s)", vaults)
	}

	// valid device-bound session: CREATE then UPDATE.
	pub, priv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, recoveryTok, "device-A", pub, priv); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	create, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), recoveryTok, "device-A")
	update, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(2, 1), recoveryTok, "device-A")
	t.Logf("valid device-bound CREATE      -> %d", create)
	t.Logf("valid device-bound UPDATE      -> %d", update)
	if create != http.StatusCreated || update >= 400 {
		t.Fatalf("CREATE=%d UPDATE=%d", create, update)
	}

	// session A + device B header.
	tokB := e.fullLogin(t, a)
	pubB, privB := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tokB, "device-B", pubB, privB); code >= 400 {
		t.Fatalf("enrol B: %d", code)
	}
	mismatch, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(3, 2), recoveryTok, "device-B")
	t.Logf("session A + device B           -> %d", mismatch)
	if mismatch != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", mismatch)
	}

	// revoked device session.
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-A/revoke", "{}", recoveryTok, "device-A"); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	revoked, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(3, 2), recoveryTok, "device-A")
	t.Logf("revoked device session         -> %d", revoked)
	if revoked < 400 {
		t.Fatalf("a revoked device wrote the vault (%d)", revoked)
	}
}

// §13/§18: vault absent + K_device present = enrolment possible, then CREATE. End to end.
func TestP67_Phase60DeadlockIsClosedEndToEnd(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	e.retire(t, a.id) // the strictest possible account state

	var vaults int64
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)
	if vaults != 0 {
		t.Fatalf("fixture: expected no vault")
	}

	tok := e.fullLogin(t, a) // password + TOTP
	closed, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), tok, "new-device")

	pub, priv := newKeyPair(t) // K_device, generated with no vault in existence
	enrol, _ := e.enrolDevice(t, tok, "new-device", pub, priv)
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", a.id).Count(&vaults)
	if vaults != 0 {
		t.Fatalf("enrolment created a vault; it must not")
	}

	create, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), tok, "new-device")
	update, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(2, 1), tok, "new-device")

	t.Logf("§18. before enrolment CREATE=%d | enrol with no vault=%d | CREATE=%d UPDATE=%d",
		closed, enrol, create, update)
	if closed < 400 {
		t.Fatalf("the gate did not hold before enrolment (%d)", closed)
	}
	if enrol >= 400 || create != http.StatusCreated || update >= 400 {
		t.Fatalf("deadlock not closed: enrol=%d create=%d update=%d", enrol, create, update)
	}
	t.Logf("§18 CONFIRMED: vault absent + K_device present = enrolment possible, and the vault " +
		"is created afterwards through the gated route.")
}

// ============================================================ §15.30-33 existing invariants

func TestP67_PairingAndRouteTableUnchanged(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)

	// 32. destination-side pairing stays deviceless.
	ephPub, _ := newKeyPair(t)
	body, _ := json.Marshal(map[string]string{"ephemeral_pub_hex": hex.EncodeToString(ephPub[:])})
	ccode, cout := e.req(t, http.MethodPost, "/api/e2ee/pairing", string(body), tok, "")
	sessionID, _ := cout["session_id"].(string)
	pcode, _ := e.req(t, http.MethodGet, "/api/e2ee/pairing/"+sessionID+"/payload", "", tok, "")

	// 31. completion stays device-gated.
	comp, _ := json.Marshal(map[string]string{
		"payload_b64": "cGhhc2UtNjctY2hlY2s=", "sender_pub_hex": hex.EncodeToString(ephPub[:]),
	})
	compCode, _ := e.req(t, http.MethodPost, "/api/e2ee/pairing/"+sessionID+"/complete", string(comp), tok, "")

	t.Logf("31/32. deviceless create=%d payload=%d complete=%d", ccode, pcode, compCode)
	if ccode >= 400 || pcode >= 400 {
		t.Fatalf("32: destination-side pairing stopped being deviceless: create=%d payload=%d", ccode, pcode)
	}
	if compCode < 400 {
		t.Fatalf("31: pairing completion is no longer device-gated (%d)", compCode)
	}

	// 33 + the vault gate, read from the production route table itself.
	src, err := os.ReadFile(filepath.Join("..", "main.go"))
	if err != nil {
		t.Fatalf("read main.go: %v", err)
	}
	for _, want := range []string{
		`e2eeRoutes.Put("/vault", middleware.RequireDeviceIdentity(), e2eeHandler.PutVault)`,
		`e2eeRoutes.Post("/pairing/:session_id/complete", middleware.RequireDeviceIdentity(), e2eeHandler.CompletePairing)`,
		`e2eeRoutes.Post("/pairing", e2eeHandler.CreatePairing)`,
		`e2eeRoutes.Get("/pairing/:session_id/payload", e2eeHandler.TakePairingPayload)`,
		`e2eeRoutes.Post("/archives", middleware.RequireDeviceIdentity(), archiveHandler.UploadArchive)`,
		`e2eeRoutes.Get("/history-keyring", middleware.RequireDeviceIdentity(), keyringHandler.GetHistoryKeyring)`,
	} {
		if !strings.Contains(string(src), want) {
			t.Fatalf("route table no longer contains:\n\t%s", want)
		}
	}
	t.Logf("33. vault gated; pairing completion gated; pairing destination routes and " +
		"archive/history-keyring routes unchanged")
}

// 30. Phase 44 session binding still happens on enrolment.
func TestP67_Phase44SessionBindingIntact(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	pub, priv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tok, "device-A", pub, priv); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	d, _ := e.deviceRowP67(t, a.id, "device-A")
	var s models.Session
	if err := e.db.Where("user_id = ? AND device_id = ?", a.id, d.ID).First(&s).Error; err != nil {
		t.Fatalf("30: no session bound to the verified device: %v", err)
	}
	t.Logf("30. session %s bound to device %s", s.ID, d.DeviceID)
}

// ============================================================ helpers

var _ = fiber.New
var _ = database.DB

// openChallenge answers a Phase 44 challenge with the private half of the key it was sealed to.
// Unchanged crypto: crypto_box, 24-byte nonce prefix, exactly as the clients do it.
func openChallenge(t *testing.T, out map[string]any, priv *[32]byte) string {
	t.Helper()
	sealedB64, _ := out["sealed_b64"].(string)
	senderPubHex, _ := out["sender_pub_hex"].(string)
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
		t.Fatalf("the challenge did not open with this private key")
	}
	return base64.StdEncoding.EncodeToString(proof)
}
