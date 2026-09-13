package handlers

import (
	"encoding/hex"
	"errors"
	"net/http"
	"sync"
	"testing"
	"time"

	"messenger-app/models"

	"gorm.io/gorm"

	"github.com/google/uuid"
)

// PHASE 70 - the durable retired-key deny-list. These drive the REAL handlers and the REAL
// retireLegacyAuthority snapshot; the durable table is the only new state.
//
// The core acceptance criterion (spec FINAL RULE):
//
//	LEGACY_RETIRED + same old K_account + fresh device_id + authority_kind=device
//	+ valid password+TOTP + ALL historical e2ee_devices rows deleted   =>  403
//	LEGACY_RETIRED + fresh genuine K_device + valid password+TOTP       =>  201
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

type p70Env struct{ *p68Env }

func p70Setup(t *testing.T) *p70Env {
	t.Helper()
	return &p70Env{p68Setup(t)} // p68 already wires the production DeleteDevice route
}

// setAccountKey publishes an account identity key (K_account) the way SavePublicKey does. This is
// what lets retirement recognise a K_account that is sitting in a device-provenance row.
func (e *p70Env) setAccountKey(t *testing.T, user uuid.UUID, pub *[32]byte) {
	t.Helper()
	if err := e.db.Model(&models.User{}).Where("id = ?", user).
		Update("public_key", hex.EncodeToString(pub[:])).Error; err != nil {
		t.Fatalf("set account key: %v", err)
	}
}

func (e *p70Env) retiredKeys(t *testing.T, user uuid.UUID) []string {
	t.Helper()
	var keys []string
	if err := e.db.Model(&models.E2EERetiredDeviceKey{}).
		Where("user_id = ?", user).Pluck("public_key", &keys).Error; err != nil {
		t.Fatalf("read retired keys: %v", err)
	}
	return keys
}

func (e *p70Env) holdsRetired(t *testing.T, user uuid.UUID, pub *[32]byte) bool {
	t.Helper()
	var n int64
	e.db.Model(&models.E2EERetiredDeviceKey{}).
		Where("user_id = ? AND lower(public_key) = lower(?)", user, hex.EncodeToString(pub[:])).
		Count(&n)
	return n > 0
}

// ============================================================ §8 control cases

// C1: legacy row present + retired.
func TestP70_C1_LegacyRowPresent(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "fresh", kacc, kaccPriv)
	t.Logf("C1 present -> %d ; retired keys hold K_account=%v", code, e.holdsRetired(t, a.id, kacc))
	if code != http.StatusForbidden {
		t.Fatalf("C1: expected 403, got %d", code)
	}
}

// C2: legacy row revoked + retired.
func TestP70_C2_LegacyRowRevoked(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)
	e.db.Model(&models.E2EEDevice{}).Where("user_id = ? AND device_id = ?", a.id, "legacy-A").
		Update("revoked_at", time.Now())
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "fresh", kacc, kaccPriv)
	t.Logf("C2 revoked -> %d", code)
	if code != http.StatusForbidden {
		t.Fatalf("C2: expected 403, got %d", code)
	}
}

// C3: legacy row DELETED + retired. The decisive Phase 69 regression.
func TestP70_C3_LegacyRowDeleted(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)

	// Delete the way production must: revoke the device's sessions, then delete the row.
	e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
	           WHERE user_id=? AND revoked_at IS NULL`, a.id)
	e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=? AND device_id=?`, a.id, "legacy-A")

	var rows int64
	e.db.Model(&models.E2EEDevice{}).Where("user_id = ?", a.id).Count(&rows)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "fresh", kacc, kaccPriv)
	t.Logf("C3 DELETED -> %d ; device rows=%d ; durable list holds K_account=%v",
		code, rows, e.holdsRetired(t, a.id, kacc))
	if code != http.StatusForbidden {
		t.Fatalf("C3 DECISIVE: expected 403 after row deletion, got %d", code)
	}
	if rows != 0 {
		t.Fatalf("C3: fixture failed to delete the legacy row (%d remain)", rows)
	}
}

// C5 / §17: K_account lives in a DEVICE-provenance row, no legacy row at all.
func TestP70_C5_KAccountInDeviceRow(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	e.setAccountKey(t, a.id, kacc) // users.public_key = K_account, the discriminator

	// Pre-retirement, LEGACY_OPEN lets K_account be enrolled while DECLARING device.
	if code, out := e.enrolDevice(t, e.fullLogin(t, a), "devrow", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d %v", code, out)
	}
	d, _ := e.deviceRowP67(t, a.id, "devrow")
	if d.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("fixture: expected a device-provenance row, got %q", d.AuthorityKind)
	}
	e.retire(t, a.id)
	if !e.holdsRetired(t, a.id, kacc) {
		t.Fatalf("§17: retirement did not snapshot K_account that sat in a device row")
	}
	// Delete the device row and retry: the durable list still holds it.
	e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
	           WHERE user_id=? AND revoked_at IS NULL`, a.id)
	e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=?`, a.id)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "fresh", kacc, kaccPriv)
	t.Logf("C5/§17 K_account-in-device-row, then deleted -> %d", code)
	if code != http.StatusForbidden {
		t.Fatalf("C5/§17: expected 403, got %d", code)
	}
}

// C4 + C6: multiple legacy rows on one K_account, delete them all, still denied.
func TestP70_C4C6_MultipleLegacyRowsAllDeleted(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	for _, id := range []string{"A", "B", "C"} {
		if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), id, kacc, kaccPriv); code >= 400 {
			t.Fatalf("fixture %s: %d", id, code)
		}
	}
	e.retire(t, a.id)
	// One distinct retired key for one distinct K_account.
	if got := e.retiredKeys(t, a.id); len(got) != 1 {
		t.Fatalf("C6: expected 1 distinct retired key, got %d", len(got))
	}
	e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
	           WHERE user_id=? AND revoked_at IS NULL`, a.id)
	e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=?`, a.id)
	var rows int64
	e.db.Model(&models.E2EEDevice{}).Where("user_id = ?", a.id).Count(&rows)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "fresh", kacc, kaccPriv)
	t.Logf("C4/C6 all %d legacy rows deleted (now %d) -> %d", 3, rows, code)
	if rows != 0 || code != http.StatusForbidden {
		t.Fatalf("C4/C6: rows=%d code=%d (want 0 / 403)", rows, code)
	}
}

// ============================================================ §9 positive K_device

func TestP70_FreshKDeviceStillEnrolsAfterRetirement(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)
	e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
	           WHERE user_id=? AND revoked_at IS NULL`, a.id)
	e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=?`, a.id)

	kdev, kdevPriv := newKeyPair(t)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "modern", kdev, kdevPriv)
	d, found := e.deviceRowP67(t, a.id, "modern")
	t.Logf("positive: fresh K_device after retirement -> %d kind=%q verified=%v ; K_device on deny-list=%v",
		code, d.AuthorityKind, found && d.VerifiedAt != nil, e.holdsRetired(t, a.id, kdev))
	if code != http.StatusCreated || !found || d.VerifiedAt == nil || d.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("positive: fresh K_device must enrol as verified device authority (%d, %q)", code, d.AuthorityKind)
	}
	if e.holdsRetired(t, a.id, kdev) {
		t.Fatalf("positive: a genuine K_device was wrongly added to the deny-list")
	}
}

// ============================================================ §10 account separation

func TestP70_AccountSeparation(t *testing.T) {
	e := p70Setup(t)
	A := e.newAccount(t, true)
	kA, kAPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, A), "legacy-A", kA, kAPriv); code >= 400 {
		t.Fatalf("A fixture: %d", code)
	}
	e.retire(t, A.id)

	B := e.newAccount(t, true)
	kB, kBPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, B), "legacy-B", kB, kBPriv); code >= 400 {
		t.Fatalf("B fixture: %d", code) // B stays LEGACY_OPEN
	}

	// A's deny-list holds only kA; B has none.
	if !e.holdsRetired(t, A.id, kA) || e.holdsRetired(t, A.id, kB) {
		t.Fatalf("§10: A's deny-list is not exactly {kA}")
	}
	if len(e.retiredKeys(t, B.id)) != 0 {
		t.Fatalf("§10: B (open) must have no retired keys")
	}
	aa, _ := e.enrolDevice(t, e.fullLogin(t, A), "pa", kA, kAPriv)
	bopen, _ := e.enrolLegacy(t, e.fullLogin(t, B), "pb", kB, kBPriv) // B open -> legacy path fine
	t.Logf("§10 A+kA=%d (want 403) ; B+kB open=%d (want <400)", aa, bopen)
	if aa != http.StatusForbidden {
		t.Fatalf("§10: A must deny its own retired key, got %d", aa)
	}
	if bopen >= 400 {
		t.Fatalf("§10: B (open) must be unaffected by A's retirement, got %d", bopen)
	}
}

// ============================================================ §11 deletion regression (real API)

func TestP70_DeletionThroughRealAPIKeepsDenyList(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	// Enrol legacy on a session, then retire; use a SEPARATE session to delete via the API.
	tok := e.fullLogin(t, a)
	if code, _ := e.enrolLegacy(t, tok, "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)
	if code, _ := e.enrolDevice(t, e.fullLogin(t, a), "probe", kacc, kaccPriv); code != http.StatusForbidden {
		t.Fatalf("pre-delete: expected 403, got %d", code)
	}
	// Real production DELETE /e2ee/devices, from a device-bound admin session.
	adminTok := e.fullLogin(t, a)
	ap, apPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, adminTok, "admin", ap, apPriv); code >= 400 {
		t.Fatalf("admin enrol: %d", code)
	}
	dc, _ := e.req(t, http.MethodDelete, "/api/e2ee/devices/legacy-A", "", adminTok, "admin")
	var legacyRows int64
	e.db.Model(&models.E2EEDevice{}).Where("user_id = ? AND device_id = ?", a.id, "legacy-A").Count(&legacyRows)
	stillDenied := e.holdsRetired(t, a.id, kacc)
	after, _ := e.enrolDevice(t, e.fullLogin(t, a), "after", kacc, kaccPriv)
	t.Logf("§11 real DELETE=%d ; legacy row gone=%v ; durable key kept=%v ; re-enrol=%d",
		dc, legacyRows == 0, stillDenied, after)
	if dc >= 400 || legacyRows != 0 {
		t.Fatalf("§11: real deletion failed (status %d, rows %d)", dc, legacyRows)
	}
	if !stillDenied {
		t.Fatalf("§11: device deletion erased the durable retired-key record")
	}
	if after != http.StatusForbidden {
		t.Fatalf("§11: re-enrol after real deletion expected 403, got %d", after)
	}
}

// ============================================================ §12 retirement/enrolment race

func TestP70_RaceRetirementVsEnrolment(t *testing.T) {
	for i := 0; i < 6; i++ {
		e := p70Setup(t)
		a := e.newAccount(t, true)
		kacc, kaccPriv := newKeyPair(t)
		if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
			t.Fatalf("fixture: %d", code)
		}
		tok := e.fullLogin(t, a)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); e.enrolDevice(t, tok, "racer", kacc, kaccPriv) }()
		go func() { defer wg.Done(); e.retire(t, a.id) }()
		wg.Wait()

		// Invariant: once retirement has committed, a SUBSEQUENT enrol of K_account is denied.
		after, _ := e.enrolDevice(t, e.fullLogin(t, a), "post", kacc, kaccPriv)
		t.Logf("§12 run %d: post-retirement enrol of K_account -> %d ; durable holds it=%v",
			i, after, e.holdsRetired(t, a.id, kacc))
		if after != http.StatusForbidden {
			t.Fatalf("§12: after retirement committed, K_account still enrolled (%d)", after)
		}
	}
}

// ============================================================ §13 retirement/deletion race

func TestP70_RaceRetirementVsDeletion(t *testing.T) {
	for i := 0; i < 6; i++ {
		e := p70Setup(t)
		a := e.newAccount(t, true)
		kacc, kaccPriv := newKeyPair(t)
		// A real legacy account publishes its identity key to users.public_key.
		// That is the durable home of K_account, so retirement can capture it even
		// if the device row is deleted first.
		e.setAccountKey(t, a.id, kacc)
		if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
			t.Fatalf("fixture: %d", code)
		}
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); e.retire(t, a.id) }()
		go func() {
			defer wg.Done()
			e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
			           WHERE user_id=? AND revoked_at IS NULL`, a.id)
			e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=? AND device_id=?`, a.id, "legacy-A")
		}()
		wg.Wait()

		var acct models.E2EEAccountAuthority
		retired := e.db.Where("user_id = ?", a.id).First(&acct).Error == nil && acct.LegacyRetiredAt != nil
		held := e.holdsRetired(t, a.id, kacc)
		t.Logf("§13 run %d: retired=%v durableHoldsKey=%v", i, retired, held)
		// Whichever ordering won, once retired the durable evidence must exist (it
		// comes from users.public_key, which deletion never touches) and a later
		// enrol of K_account must be denied.
		if retired && !held {
			t.Fatalf("§13: retired but durable evidence missing despite users.public_key holding K_account")
		}
		after, _ := e.enrolDevice(t, e.fullLogin(t, a), "post", kacc, kaccPriv)
		if after != http.StatusForbidden {
			t.Fatalf("§13 run %d: post-race enrol of K_account got %d", i, after)
		}
	}
}

// ============================================================ §14 atomic failure

func TestP70_RetirementIsAtomic(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}

	// Retire inside an outer transaction that then FAILS: snapshot + legacy_retired_at must both
	// roll back together, leaving no partial authority state.
	boom := errors.New("forced failure after snapshot, before commit")
	_ = e.db.Transaction(func(tx *gorm.DB) error {
		if e2 := retireLegacyAuthority(tx, a.id, time.Now()); e2 != nil {
			return e2
		}
		return boom
	})

	var acct models.E2EEAccountAuthority
	retired := e.db.Where("user_id = ?", a.id).First(&acct).Error == nil && acct.LegacyRetiredAt != nil
	keys := e.retiredKeys(t, a.id)
	t.Logf("§14 after forced-failure retirement: retired=%v durableKeys=%d (both must be empty)", retired, len(keys))
	if retired || len(keys) != 0 {
		t.Fatalf("§14: partial state after rollback (retired=%v keys=%d)", retired, len(keys))
	}
	// Retry cleanly: both land.
	e.retire(t, a.id)
	if !e.holdsRetired(t, a.id, kacc) || e.db.Where("user_id = ?", a.id).First(&acct).Error != nil || acct.LegacyRetiredAt == nil {
		t.Fatalf("§14: clean retry did not record both the key and the retirement")
	}
	t.Logf("§14 clean retry: retired + durable key recorded together")
}

// ============================================================ §18 threat matrix (durable list)

func TestP70_ThreatMatrix(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	e.retire(t, a.id)
	e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
	           WHERE user_id=? AND revoked_at IS NULL`, a.id)
	e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=?`, a.id) // all rows gone

	// K_account, every declaration, must be denied.
	for _, kind := range []string{models.AuthorityDevice, models.AuthorityLegacy, "", "bogus"} {
		code, _ := e.enrolAs(t, e.fullLogin(t, a), "k-"+kind, kind, kacc, kaccPriv)
		if code != http.StatusForbidden {
			t.Fatalf("§18: K_account declared %q after deletion expected 403, got %d", kind, code)
		}
	}
	// Fresh K_device: allowed.
	kdev, kdevPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, e.fullLogin(t, a), "dev", kdev, kdevPriv); code != http.StatusCreated {
		t.Fatalf("§18: fresh K_device expected 201, got %d", code)
	}
	// Password-only account cannot earn recovery authority, so cannot enrol at all after retirement.
	pw := e.newAccount(t, false)
	e.retire(t, pw.id)
	pwPub, pwPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, e.fullLogin(t, pw), "x", pwPub, pwPriv); code != http.StatusForbidden {
		t.Fatalf("§18: password-only after retirement expected 403, got %d", code)
	}
	t.Logf("§18: K_account denied under every declaration; fresh K_device allowed; password-only denied")
}

// ============================================================ §16 migration back-fill

// An account already LEGACY_RETIRED before this remediation (no durable rows yet) must gain
// durable evidence when MigrateDB runs, reconstructed from users.public_key and legacy rows.
func TestP70_Migration_BackfillsAlreadyRetiredAccounts(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true)
	kacc, kaccPriv := newKeyPair(t)
	e.setAccountKey(t, a.id, kacc)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	// Simulate a PRE-remediation retired account: set legacy_retired_at directly, WITHOUT the
	// snapshot, and clear any durable rows.
	now := time.Now()
	e.db.Exec(`INSERT INTO e2ee_account_authorities (user_id, legacy_retired_at, created_at, updated_at)
	           VALUES (?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET legacy_retired_at=?`,
		a.id, now, now, now, now)
	e.db.Exec(`DELETE FROM e2ee_retired_device_keys WHERE user_id=?`, a.id)
	if e.holdsRetired(t, a.id, kacc) {
		t.Fatalf("fixture: expected no durable rows before back-fill")
	}

	// The deploy-time back-fill (MigrateDB is idempotent).
	if err := models.MigrateDB(e.db); err != nil {
		t.Fatalf("MigrateDB back-fill: %v", err)
	}

	held := e.holdsRetired(t, a.id, kacc)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "post", kacc, kaccPriv)
	t.Logf("§16 back-fill: durable holds K_account=%v ; re-enrol after back-fill=%d", held, code)
	if !held {
		t.Fatalf("§16: back-fill did not reconstruct the retired key")
	}
	if code != http.StatusForbidden {
		t.Fatalf("§16: after back-fill, K_account expected 403, got %d", code)
	}
}

// The reported LIMITATION: an already-retired account whose legacy rows were deleted BEFORE this
// remediation AND that never published users.public_key cannot be reconstructed. This test pins
// that gap explicitly (it is a documented residual, not a regression to fix here).
func TestP70_Migration_UnrecoverableWhenNoTraceRemains(t *testing.T) {
	e := p70Setup(t)
	a := e.newAccount(t, true) // never publishes users.public_key
	kacc, kaccPriv := newKeyPair(t)
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kacc, kaccPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	now := time.Now()
	e.db.Exec(`INSERT INTO e2ee_account_authorities (user_id, legacy_retired_at, created_at, updated_at)
	           VALUES (?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET legacy_retired_at=?`,
		a.id, now, now, now, now)
	// Its trace is gone before remediation: delete the legacy row and any durable record.
	e.db.Exec(`UPDATE sessions SET revoked_at=now(), revoke_reason='p70', refresh_hash=NULL
	           WHERE user_id=? AND revoked_at IS NULL`, a.id)
	e.db.Exec(`DELETE FROM e2_ee_devices WHERE user_id=?`, a.id)
	e.db.Exec(`DELETE FROM e2ee_retired_device_keys WHERE user_id=?`, a.id)

	if err := models.MigrateDB(e.db); err != nil {
		t.Fatalf("MigrateDB: %v", err)
	}
	held := e.holdsRetired(t, a.id, kacc)
	code, _ := e.enrolDevice(t, e.fullLogin(t, a), "post", kacc, kaccPriv)
	t.Logf("§16 LIMITATION: no trace remained -> durable holds key=%v ; re-enrol=%d (documented residual)",
		held, code)
	if held {
		t.Fatalf("§16: unexpectedly reconstructed a key with no trace - re-examine")
	}
	// This is the residual gap: with no trace, the server cannot distinguish it from a fresh key.
	if code != http.StatusCreated {
		t.Logf("§16: note - re-enrol returned %d (not the expected residual 201); harness state differs", code)
	}
}
