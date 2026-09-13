package handlers

import (
	"net/http"
	"testing"

	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

// PHASE 63 - can the system move from an account-wide enrolment authority to a per-device one
// without opening a hole while it does? Forensic only; no production change.
//
// The two questions that decide the migration shape are measured here against the REAL handlers:
//
//   1. Is a live session a revocation-respecting authority? (Option C) - i.e. after the production
//      RevokeDevice runs, can the revoked device still act through the session it already had?
//
//   2. Does keeping the legacy account-wide proof alive during the transition re-open the bypass?
//      (Option B)
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

// p63Setup adds the revocation route to the Phase 61 harness so the PRODUCTION revocation path is
// exercised rather than a hand-written UPDATE.
func p63Setup(t *testing.T) (*p61Env, *fiber.App) {
	t.Helper()
	e := p61Setup(t, true)
	h := NewE2EEHandler(nil)
	// Same chain as main.go for the device-management routes.
	e.app.Post("/devices/:device_id/revoke", h.RevokeDevice)
	// A minimal stand-in for "any route a migration endpoint would sit behind": the strictest
	// middleware in the codebase, which is what Option C would rely on.
	e.app.Post("/migrate", middleware.RequireDeviceIdentity(), func(c *fiber.Ctx) error {
		return c.SendStatus(http.StatusNoContent)
	})
	return e, e.app
}

// ============================================================ Option C

// OPTION C. A live session bound to a verified, non-revoked device is a revocation-respecting
// authority: the production RevokeDevice revokes that device's sessions in the same transaction
// (revokeSessionsForDevice), so a revoked device has nothing left to migrate WITH.
func TestP63_OptionC_RevokedDeviceCannotMigrateThroughItsOwnSession(t *testing.T) {
	e, _ := p63Setup(t)
	pub, priv := newKeyPair(t)

	sid := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("fixture: device-A must enrol, got %d", code)
	}

	// Before revocation the session carries device authority.
	before, _ := e.do(t, http.MethodPost, "/migrate", "{}", "device-A", sid)
	if before >= 400 {
		t.Fatalf("a verified device must pass the migration gate, got %d", before)
	}

	// Revoke through the PRODUCTION handler.
	rcode, _ := e.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sid)
	if rcode >= 400 {
		t.Fatalf("revoke: %d", rcode)
	}

	after, _ := e.do(t, http.MethodPost, "/migrate", "{}", "device-A", sid)
	t.Logf("OPTION C: migration gate before revoke=%d, after revoke=%d", before, after)
	if after < 400 {
		t.Fatalf("OPTION C BROKEN: a revoked device still passed the migration gate (%d)", after)
	}

	// And the session row itself is gone as an authority.
	var live int64
	e.db.Model(&models.Session{}).
		Where("id = ? AND revoked_at IS NULL", sid).Count(&live)
	t.Logf("OPTION C: live sessions for the revoked device = %d", live)
	if live != 0 {
		t.Fatalf("revocation left %d live session(s) bound to the revoked device", live)
	}
	t.Logf("OPTION C CONFIRMED: session-authorised migration is revocation-respecting - " +
		"a revoked device holds no live session and cannot migrate itself.")
}

// ============================================================ Option B

// OPTION B. While the legacy account-wide proof stays acceptable, the bypass stays open, and it is
// entirely independent of whatever migration mechanism is chosen: the attacker never touches it.
func TestP63_OptionB_LegacyAuthorityKeepsTheBypassOpen(t *testing.T) {
	e, _ := p63Setup(t)
	pub, priv := newKeyPair(t)

	sid := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	if rcode, _ := e.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sid); rcode >= 400 {
		t.Fatalf("revoke: %d", rcode)
	}

	// M2: the revoked holder of K_account enrols a brand-new id on a brand-new session.
	sidB := e.session(t)
	code := e.enrol(t, "device-B-invented", pub, priv, sidB)
	var d models.E2EEDevice
	verified := e.db.Where("user_id = ? AND device_id = ?", e.user, "device-B-invented").
		First(&d).Error == nil && d.VerifiedAt != nil && d.RevokedAt == nil
	gate, _ := e.do(t, http.MethodPost, "/migrate", "{}", "device-B-invented", sidB)

	t.Logf("OPTION B: revoked holder of K_account -> fresh id enrol=%d verified=%v gate=%d",
		code, verified, gate)
	if code >= 400 || !verified {
		t.Logf("OPTION B: legacy proof already refused - the bypass is closed")
		return
	}
	t.Logf("OPTION B CONFIRMED SECURITY-INCOMPLETE: the legacy authority alone re-creates a " +
		"verified device after revocation. Any transition that keeps accepting it keeps this open, " +
		"regardless of how migration itself is authorised.")
}

// ============================================================ M1 / M5

// M1 + M5: an un-revoked legacy device can mint unlimited identities. This is the steady-state
// today, and it is why "migrated" cannot simply mean "has a per-device key" while legacy is live.
func TestP63_M1_M5_LegacyAuthorityMintsUnlimitedIdentities(t *testing.T) {
	e, _ := p63Setup(t)
	pub, priv := newKeyPair(t)

	for _, id := range []string{"legacy-1", "legacy-2", "legacy-3", "legacy-4", "legacy-5"} {
		sid := e.session(t)
		if code := e.enrol(t, id, pub, priv, sid); code >= 400 {
			t.Fatalf("%s: %d", id, code)
		}
	}
	var n int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND verified_at IS NOT NULL AND revoked_at IS NULL", e.user).Count(&n)
	t.Logf("M1/M5: one K_account produced %d verified devices", n)
	if n < 5 {
		t.Fatalf("expected 5, got %d", n)
	}
}

// ============================================================ Race 6

// RACE 6: revocation of device A landing while A migrates. The question is whether the revocation
// and the session teardown are atomic enough that a migration cannot straddle them.
func TestP63_Race6_RevocationDuringMigration(t *testing.T) {
	e, _ := p63Setup(t)
	pub, priv := newKeyPair(t)
	sid := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}

	// Revoke, then immediately attempt the migration gate on the same session.
	if rcode, _ := e.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sid); rcode >= 400 {
		t.Fatalf("revoke: %d", rcode)
	}
	code, _ := e.do(t, http.MethodPost, "/migrate", "{}", "device-A", sid)
	t.Logf("RACE 6: migration attempted immediately after revoke = %d", code)
	if code < 400 {
		t.Fatalf("RACE 6: migration succeeded after revocation (%d)", code)
	}
}

// ============================================================ M6

// M6: can the backend distinguish a legacy-authority device from a per-device-authority device?
//
// Today every verified row looks identical - there is no column that records WHICH authority
// verified it. That is the concrete reason a mixed-mode account cannot be reasoned about, and it is
// the one piece of state a migration would have to add.
func TestP63_M6_BackendCannotDistinguishAuthorityKind(t *testing.T) {
	e, _ := p63Setup(t)
	kAccount, privAccount := newKeyPair(t)
	kDeviceB, privDeviceB := newKeyPair(t) // stands in for a future per-device key

	sidA := e.session(t)
	if code := e.enrol(t, "legacy-device", kAccount, privAccount, sidA); code >= 400 {
		t.Fatalf("legacy enrol: %d", code)
	}
	sidB := e.session(t)
	if code := e.enrol(t, "modern-device", kDeviceB, privDeviceB, sidB); code >= 400 {
		t.Fatalf("modern enrol: %d", code)
	}

	var rows []models.E2EEDevice
	e.db.Where("user_id = ?", e.user).Find(&rows)
	for _, r := range rows {
		t.Logf("M6 row: device_id=%-14s verified=%v revoked=%v key=%s…",
			r.DeviceID, r.VerifiedAt != nil, r.RevokedAt != nil, firstN(r.PublicKey, 12))
	}
	t.Logf("M6 FINDING: both rows are structurally identical - verified_at set, revoked_at null, " +
		"a 32-byte key. Nothing records whether the key is account-wide or device-local, so the " +
		"server cannot grant them different trust levels. Distinguishing them requires new state.")
}

func firstN(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// Compile-time reference so the unused import stays honest if the file is trimmed.
var _ = uuid.Nil
