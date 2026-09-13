package handlers

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
)

// PHASE 64 - Case F: bootstrapping a device when NO authorized device exists. Forensic only.
//
// The question is whether the server can tell these two apart once the legacy account-wide
// authority is retired:
//
//	T1  legitimate user, lost every device, knows the password (and 2FA)
//	T2  revoked device, holds the old K_account, has no live session
//
// Both arrive with account authentication and no device authority. If the only evidence the server
// has is "authenticated account, no bound device", the two are the same request.
//
// This suite also settles what QR pairing actually is: whether it can bootstrap from account
// authentication alone, or whether it needs a source device that can already open the vault.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

func p64Setup(t *testing.T) *p61Env {
	t.Helper()
	e := p61Setup(t, true)
	h := NewE2EEHandler(nil)
	e.app.Post("/devices/:device_id/revoke", h.RevokeDevice)
	// Exactly main.go: none of the pairing routes carry RequireDeviceIdentity.
	e.app.Post("/pairing", h.CreatePairing)
	e.app.Get("/pairing/:session_id", h.GetPairing)
	e.app.Post("/pairing/:session_id/complete", h.CompletePairing)
	e.app.Get("/pairing/:session_id/payload", h.TakePairingPayload)
	// Stand-in for any route that would demand device authority after legacy retirement.
	e.app.Post("/device-gated", middleware.RequireDeviceIdentity(), func(c *fiber.Ctx) error {
		return c.SendStatus(http.StatusNoContent)
	})
	return e
}

// ============================================================ T1 vs T2 indistinguishability

// THE CASE-F CORE. A legitimate deviceless user and a revoked device present the server with the
// same evidence: a live account session and no device authority. Both are refused identically.
//
// The refusal is correct for T2 and fatal for T1 - which is why Case F needs an authority the
// server can actually check, not merely a stricter gate.
func TestP64_T1AndT2AreIndistinguishableToTheServer(t *testing.T) {
	e := p64Setup(t)
	pub, priv := newKeyPair(t)

	// T2: a device that WAS verified and is now revoked.
	sidRevoked := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sidRevoked); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	if rc, _ := e.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sidRevoked); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	t2Code, _ := e.do(t, http.MethodPost, "/device-gated", "{}", "device-A", sidRevoked)

	// T1: a legitimate user on a brand-new session with no device at all.
	sidFresh := e.session(t)
	t1Code, _ := e.do(t, http.MethodPost, "/device-gated", "{}", "", sidFresh)

	t.Logf("CASE F: T1 (legitimate, no device)=%d   T2 (revoked device)=%d", t1Code, t2Code)
	if t1Code < 400 || t2Code < 400 {
		t.Fatalf("expected both to be refused under device gating, got T1=%d T2=%d", t1Code, t2Code)
	}
	t.Logf("CASE F FINDING: both are refused, and for the same reason - no bound device on the " +
		"session. Session/device state alone cannot separate legitimate recovery from a revoked " +
		"device, so device gating cannot be the whole answer.")
}

// ============================================================ QR pairing

// Pairing is authorised by account authentication alone - no route carries RequireDeviceIdentity -
// so a caller with no device can open one. What it CANNOT do is produce the payload.
func TestP64_PairingIsAccountAuthenticatedNotDeviceAuthorised(t *testing.T) {
	e := p64Setup(t)
	ephPub, _ := newKeyPair(t)

	// No device anywhere on the account.
	sid := e.session(t)
	body, _ := json.Marshal(map[string]string{"ephemeral_pub_hex": hex.EncodeToString(ephPub[:])})
	code, out := e.do(t, http.MethodPost, "/pairing", string(body), "", sid)
	t.Logf("pairing created by a deviceless account session: status=%d", code)
	if code >= 400 {
		t.Fatalf("expected a deviceless session to open a pairing, got %d", code)
	}
	sessionID, _ := out["session_id"].(string)
	if sessionID == "" {
		t.Fatalf("no session_id in %v", out)
	}

	// The destination can poll, but there is nothing to collect: only a device that can open the
	// vault can complete it.
	pcode, pout := e.do(t, http.MethodGet, "/pairing/"+sessionID+"/payload", "", "", sid)
	t.Logf("payload before any source device completes it: status=%d body=%v", pcode, pout)
	// 204 is the polling answer for "nothing has been handed over yet" - the destination waits.
	// What matters is that no material is delivered, not the particular status.
	if _, got := pout["payload_b64"]; got {
		t.Fatalf("a deviceless account session obtained pairing material without any source device")
	}
	if pcode == http.StatusOK && len(pout) > 0 {
		t.Fatalf("unexpected pairing material delivered: %v", pout)
	}
	t.Logf("PAIRING CLASSIFICATION: SOURCE-DEVICE RECOVERY. Creation needs only account auth, but " +
		"completion needs a device that can open the vault, so it cannot bootstrap an account whose " +
		"devices are all lost.")
}

// A REVOKED device can still complete a pairing: the pairing routes are outside the device gate.
func TestP64_RevokedDeviceCanStillCompleteAPairing(t *testing.T) {
	e := p64Setup(t)
	pub, priv := newKeyPair(t)
	ephPub, _ := newKeyPair(t)

	sid := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	if rc, _ := e.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sid); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}

	// A fresh session for the destination opens the pairing.
	destSid := e.session(t)
	body, _ := json.Marshal(map[string]string{"ephemeral_pub_hex": hex.EncodeToString(ephPub[:])})
	code, out := e.do(t, http.MethodPost, "/pairing", string(body), "", destSid)
	if code >= 400 {
		t.Fatalf("create pairing: %d", code)
	}
	sessionID, _ := out["session_id"].(string)

	// The REVOKED device completes it, omitting X-Device-Id so the permissive guard waves it past.
	comp, _ := json.Marshal(map[string]string{
		"payload_b64":    "cGF5bG9hZC1mcm9tLWEtcmV2b2tlZC1kZXZpY2U=",
		"sender_pub_hex": hex.EncodeToString(pub[:]),
	})
	ccode, _ := e.do(t, http.MethodPost, "/pairing/"+sessionID+"/complete", string(comp), "", sid)
	t.Logf("revoked device completing a pairing: status=%d", ccode)
	if ccode < 400 {
		t.Logf("FINDING: a revoked device completed a pairing handoff. The pairing routes sit " +
			"outside RequireDeviceIdentity, and the group guard only refuses a device that names " +
			"itself, so revocation does not reach this path.")
	}
}

// ============================================================ recovery factor

// The recovery key is an INDEPENDENT secret - it is generated locally, shown once, and is NOT part
// of the sealed vault plaintext (VaultPlaintext carries identity keys and ratchets only, while
// recoveryKeyDisplay lives on BuiltVault). But the server never receives it: only rk_salt and
// rk_wrapped_master are stored, so there is nothing for the server to check possession against.
func TestP64_ServerHoldsNoVerifiableRecoveryFactor(t *testing.T) {
	e := p64Setup(t)
	sid := e.session(t)

	if c, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "", sid); c >= 400 {
		// The route is gated in this harness; seed directly so the columns can be inspected.
		now := time.Now()
		v := models.E2EEVault{
			UserID: e.user, VaultVersion: 1, ProtocolVersion: 1,
			VaultCiphertext: []byte("ct"), PwWrappedMaster: []byte("pw-wrap"),
			RkSalt: []byte("rk-salt"), RkWrappedMaster: []byte("rk-wrap"),
			CreatedAt: now, UpdatedAt: now,
		}
		if err := e.db.Create(&v).Error; err != nil {
			t.Fatalf("seed vault: %v", err)
		}
	}

	var v models.E2EEVault
	if err := e.db.Where("user_id = ?", e.user).First(&v).Error; err != nil {
		t.Fatalf("vault: %v", err)
	}
	t.Logf("server-side recovery columns: rk_kdf=%q rk_salt=%d bytes rk_wrapped_master=%d bytes",
		v.RkKDF, len(v.RkSalt), len(v.RkWrappedMaster))
	t.Logf("RECOVERY FINDING: the server stores a WRAP and a SALT, never the recovery key. It can " +
		"hand these back, but it cannot verify that a caller knows the key, cannot consume it, and " +
		"cannot revoke it. The factor is independent but not a server-verifiable authority.")
}
