package handlers

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"messenger-app/models"
)

// PHASE 68A - the backend half of the Android registration wire contract.
//
// androidRegisterBody is BYTE FOR BYTE what Android's production serializer emits for
// E2EEDeviceRegisterRequest after the Phase 68A fix - pinned on the Android side by
// DeviceRegisterWireContractTest.exactRegistrationWireBody against AppModule.provideJson() and
// AppModule.provideRetrofit(). Only the sentinel tokens are replaced with live values; field
// names, field order and the authority_kind literal are exactly Android's.
//
// Nothing here changes production code. The point is the opposite: the backend was right all
// along, and these tests pin that the now-correct Android body is accepted for the right reason.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.
const androidRegisterBody = `{"device_id":"__DEVICE_ID__","name":"__NAME__","platform":"android",` +
	`"public_key":"__PUBLIC_KEY__","challenge_id":"__CHALLENGE_ID__",` +
	`"proof_b64":"__PROOF_B64__","authority_kind":"device"}`

// androidEnrol runs the real challenge, then POSTs the Android wire body with live values.
// authorityKind lets a test substitute the one literal a negative case needs to change.
func (e *p67Env) androidEnrol(t *testing.T, token, deviceID, authorityKind string, pub, priv *[32]byte) (int, map[string]any, string) {
	t.Helper()
	chBody, _ := json.Marshal(map[string]string{"device_id": deviceID, "public_key": hex.EncodeToString(pub[:])})
	code, out := e.req(t, http.MethodPost, "/api/e2ee/devices/challenge", string(chBody), token, deviceID)
	if code >= 400 {
		return code, out, ""
	}
	body := strings.NewReplacer(
		"__DEVICE_ID__", deviceID,
		"__NAME__", "SM-G780G",
		"__PUBLIC_KEY__", hex.EncodeToString(pub[:]),
		"__CHALLENGE_ID__", out["challenge_id"].(string),
		"__PROOF_B64__", openChallenge(t, out, priv),
	).Replace(androidRegisterBody)
	if authorityKind != models.AuthorityDevice {
		body = strings.Replace(body, `"authority_kind":"device"`, `"authority_kind":"`+authorityKind+`"`, 1)
	}
	code, out = e.req(t, http.MethodPost, "/api/e2ee/devices", body, token, deviceID)
	return code, out, body
}

// §5 + §8: the Android body, a fresh K_device, real recovery authority, a RETIRED account.
func TestP68A_AndroidWireBodyIsAcceptedByTheBackend(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	e.retire(t, a.id)

	kAccountPub, _ := newKeyPair(t) // what the vault would carry; never presented here
	kDevicePub, kDevicePriv := newKeyPair(t)

	tok := e.fullLogin(t, a)
	code, out, sent := e.androidEnrol(t, tok, "android-s20", models.AuthorityDevice, kDevicePub, kDevicePriv)
	row, found := e.deviceRowP67(t, a.id, "android-s20")
	s := e.sessionRow(t, a.id)

	t.Logf("§5 wire body carries authority_kind=device: %v", strings.Contains(sent, `"authority_kind":"device"`))
	t.Logf("§5 registration -> %d %v", code, out["error"])
	t.Logf("§8 row: kind=%q verified=%v key==K_device=%v key==K_account=%v markerSpent=%v bound=%v",
		row.AuthorityKind, row.VerifiedAt != nil,
		row.PublicKey == hex.EncodeToString(kDevicePub[:]),
		row.PublicKey == hex.EncodeToString(kAccountPub[:]),
		s.RecoveryAuthorityConsumedAt != nil, s.DeviceID != nil)

	if code != http.StatusCreated || !found {
		t.Fatalf("§5: the Android body was refused: %d %v", code, out)
	}
	if row.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("§5: recorded as %q - the fix must not register as legacy", row.AuthorityKind)
	}
	if row.VerifiedAt == nil {
		t.Fatalf("§5: verified_at is NULL")
	}
	if row.PublicKey != hex.EncodeToString(kDevicePub[:]) || row.PublicKey == hex.EncodeToString(kAccountPub[:]) {
		t.Fatalf("§8: the stored key is not exactly K_device")
	}
	if s.RecoveryAuthorityConsumedAt == nil || s.DeviceID == nil {
		t.Fatalf("§5: the marker was not spent or the session was not bound")
	}
}

// §6: the corrected request succeeds ONLY because it genuinely declares device authority.
//
// Ordering matters and is deliberate. The legacy-declared attempt goes FIRST, on the same session
// and the same K_device: it must be refused without spending the marker or leaving a row. Only
// then does the device-declared body, unchanged in every other byte, succeed. (The reverse order
// would test something else: once the session is bound to that device, re-presenting the same key
// is the idempotent reconnect path by design.)
func TestP68A_RetirementStillRefusesTheSameBodyDeclaredLegacy(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	e.retire(t, a.id)
	kDevicePub, kDevicePriv := newKeyPair(t)
	tok := e.fullLogin(t, a)

	legacyCode, legacyOut, legacyBody := e.androidEnrol(t, tok, "android-s20", models.AuthorityLegacy, kDevicePub, kDevicePriv)
	_, rowAfterLegacy := e.deviceRowP67(t, a.id, "android-s20")
	sAfterLegacy := e.sessionRow(t, a.id)

	deviceCode, _, _ := e.androidEnrol(t, tok, "android-s20", models.AuthorityDevice, kDevicePub, kDevicePriv)
	row, _ := e.deviceRowP67(t, a.id, "android-s20")

	t.Logf("§6 same session, same K_device, body declared legacy -> %d %v (sent legacy literal: %v)",
		legacyCode, legacyOut["error"], strings.Contains(legacyBody, `"authority_kind":"legacy"`))
	t.Logf("§6 after the refusal: rowCreated=%v markerSpent=%v", rowAfterLegacy, sAfterLegacy.RecoveryAuthorityConsumedAt != nil)
	t.Logf("§6 same session, same K_device, body declared device -> %d kind=%q", deviceCode, row.AuthorityKind)

	if legacyCode != http.StatusForbidden {
		t.Fatalf("§6: a legacy-declared enrolment was not refused after retirement (%d)", legacyCode)
	}
	if rowAfterLegacy {
		t.Fatalf("§6: the refused legacy attempt created a device row")
	}
	if sAfterLegacy.RecoveryAuthorityConsumedAt != nil {
		t.Fatalf("§6: the refused legacy attempt spent the recovery marker")
	}
	if deviceCode != http.StatusCreated || row.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("§6: the device-declared body did not succeed as device authority (%d, %q)", deviceCode, row.AuthorityKind)
	}

	// And declaring legacy for that now-enrolled key on a FRESH session is still refused.
	again, _, _ := e.androidEnrol(t, e.fullLogin(t, a), "android-s20", models.AuthorityLegacy, kDevicePub, kDevicePriv)
	t.Logf("§6 fresh session, same key, declared legacy -> %d", again)
	if again != http.StatusForbidden {
		t.Fatalf("§6: retirement did not refuse a legacy declaration on a fresh session (%d)", again)
	}
}

// §7: the old K_account cannot come back wearing the Android body after retirement.
func TestP68A_RetiredAccountKeyCannotRideTheAndroidBody(t *testing.T) {
	e := p67Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	// LEGACY_OPEN: an unchanged legacy client enrols K_account (no declaration at all).
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-phone", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)

	code, out, _ := e.androidEnrol(t, e.fullLogin(t, a), "resurrected", models.AuthorityDevice, kAccountPub, kAccountPriv)
	_, found := e.deviceRowP67(t, a.id, "resurrected")
	t.Logf("§7 retired K_account + fresh device_id + Android body (authority_kind=device) + recovery authority -> %d %v rowCreated=%v",
		code, out["error"], found)
	if code != http.StatusForbidden || found {
		t.Fatalf("§7: the retired account key re-enrolled (%d, created=%v)", code, found)
	}
}
