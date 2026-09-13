package handlers

import (
	"net/http"
	"sync"
	"testing"
	"time"

	"messenger-app/models"

	"github.com/google/uuid"
)

// PHASE 62 - is there an existing per-device cryptographic authority? Forensic only.
//
// Phase 61 left one question open: MLS key packages are the only material in the repository that is
// even nominally per-device, so could they (or anything else) carry device authorization?
//
// The static answer is no, and it is not close. The backend has NO MLS dependency in go.mod and
// PublishKeyPackages never parses a KeyPackage - it stores the bytes opaquely and takes device_id
// and ref_hash straight from the request body. So the signature key inside a KeyPackage is
// invisible to the server, and the device_id attached to it is a client assertion. There is no
// proof-verification path to build on.
//
// What remains is the account-wide X25519 identity key, and these tests measure what that key
// actually authorizes. Every one of them runs against an ISOLATED database.
//
// Nothing here changes production code.

// ============================================================ §4 revocation model

// The six-step proof, end to end: enrol, revoke, deny, then re-enrol the SAME key under a new id.
func TestP62_RevocationDoesNotBindTheAccountKey(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)

	// 1-2. Device A enrols with the account-wide identity key.
	sidA := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sidA); code >= 400 {
		t.Fatalf("step 1: device-A must enrol, got %d", code)
	}

	// 3. Revoke A.
	now := time.Now()
	if err := e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", e.user, "device-A").
		Updates(map[string]interface{}{"revoked_at": now}).Error; err != nil {
		t.Fatalf("step 3 revoke: %v", err)
	}

	// 4. A, naming itself, is denied.
	denied, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-A", sidA)
	if denied < 400 {
		t.Fatalf("step 4: a revoked device naming itself must be denied, got %d", denied)
	}

	// 5-6. The same private key enrols a brand-new id and becomes verified.
	sidB := e.session(t)
	reborn := e.enrol(t, "device-A-reborn", pub, priv, sidB)
	var d models.E2EEDevice
	verified := e.db.Where("user_id = ? AND device_id = ?", e.user, "device-A-reborn").
		First(&d).Error == nil && d.VerifiedAt != nil && d.RevokedAt == nil

	vcode, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-A-reborn", sidB)

	t.Logf("§4 RESULT: revoked-self=%d  re-enrol(new id)=%d verified=%v  gated vault PUT=%d",
		denied, reborn, verified, vcode)

	if reborn >= 400 {
		t.Logf("§4: revocation would bind the key - re-enrolment refused")
		return
	}
	if !verified {
		t.Fatalf("§4: re-enrolment returned %d but produced no verified device", reborn)
	}
	t.Logf("§4 CONFIRMED: possession of the account-wide identity key survives revocation and " +
		"re-establishes a verified device under a chosen id. The key proves ACCOUNT possession, " +
		"not DEVICE possession, so it cannot satisfy P1 or P2.")
}

// ============================================================ §9 replay and races

// P3: the same authority presented under two different device ids yields two verified devices.
//
// This is the property that disqualifies the account key: X-Device-Id is a free parameter, so the
// key does not identify a device at all.
func TestP62_OneAuthorityYieldsManyDevices(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)

	for _, id := range []string{"dev-1", "dev-2", "dev-3"} {
		sid := e.session(t)
		if code := e.enrol(t, id, pub, priv, sid); code >= 400 {
			t.Fatalf("%s: enrolment refused with %d", id, code)
		}
	}
	var n int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND verified_at IS NOT NULL AND revoked_at IS NULL", e.user).Count(&n)
	t.Logf("§9/P3: one private key produced %d verified devices", n)
	if n < 3 {
		t.Fatalf("expected 3 verified devices from one key, got %d", n)
	}
}

// Concurrent enrolment of two ids with one key: does anything serialise it?
func TestP62_ConcurrentEnrolmentWithOneAuthority(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)

	ids := []string{"race-1", "race-2", "race-3", "race-4"}
	sids := make([]uuid.UUID, len(ids))
	for i := range ids {
		sids[i] = e.session(t)
	}
	codes := make([]int, len(ids))
	var wg sync.WaitGroup
	wg.Add(len(ids))
	for i := range ids {
		go func(idx int) {
			defer wg.Done()
			codes[idx] = e.enrol(t, ids[idx], pub, priv, sids[idx])
		}(i)
	}
	wg.Wait()

	var n int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND verified_at IS NOT NULL", e.user).Count(&n)
	t.Logf("§9 concurrent enrolment: codes=%v verified devices=%d", codes, n)
}

// A verified device whose SESSION was revoked must not keep authority through that session.
func TestP62_AuthorityAfterSessionInvalidation(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := e.session(t)
	if code := e.enrol(t, "device-S", pub, priv, sid); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	// Works while the session is live.
	if c, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-S", sid); c >= 400 {
		t.Fatalf("expected the live session to write, got %d", c)
	}
	// Revoke the SESSION only; the device row stays verified.
	now := time.Now()
	// ck_sessions_revoked_implies_no_hash: a revoked session must not keep a usable refresh hash.
	if err := e.db.Model(&models.Session{}).Where("id = ?", sid).
		Updates(map[string]interface{}{"revoked_at": now, "refresh_hash": nil}).Error; err != nil {
		t.Fatalf("revoke session: %v", err)
	}
	code, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(2, 1), "device-S", sid)
	t.Logf("§9 session invalidated, device still verified: vault PUT=%d", code)
	if code < 400 {
		t.Fatalf("a revoked session must not retain device authority, got %d", code)
	}
}

// Revocation landing between enrolment and use.
func TestP62_RevocationDuringUse(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := e.session(t)
	if code := e.enrol(t, "device-D", pub, priv, sid); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	now := time.Now()
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", e.user, "device-D").
		Updates(map[string]interface{}{"revoked_at": now})

	code, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-D", sid)
	t.Logf("§9 revoked after enrolment, same session: vault PUT=%d", code)
	if code < 400 {
		t.Fatalf("a revoked device must lose authority immediately, got %d", code)
	}
}
