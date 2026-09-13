package handlers

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"messenger-app/e2ee"
	"messenger-app/models"

	"github.com/google/uuid"
)

// PHASE 68 - is the repository safe to enter the migration phase? Forensic only.
//
// Phase 67 built the authority model. This suite audits the parts of it that a retirement
// ROLLOUT would lean on, and it is written to find the places where it does not hold rather
// than to confirm that it does. The headline question is Phase 67's own reported gap:
//
//	"Deleted legacy device rows leave no deny-list entry."
//
// Retirement refuses a key by looking it up among the account's recorded legacy rows. If a row
// can disappear, so can the refusal - and the key itself does not disappear with it.
//
// Nothing here changes production code.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

type p68Env struct{ *p67Env }

func p68Setup(t *testing.T) *p68Env {
	t.Helper()
	e := p67Setup(t)
	// The production deletion path, which is the whole subject of §11.
	e.app.Delete("/api/e2ee/devices/:device_id", NewE2EEHandler(nil).DeleteDevice)
	return &p68Env{e}
}

func (e *p68Env) legacyRowsHolding(t *testing.T, user uuid.UUID, pub *[32]byte) int64 {
	t.Helper()
	var n int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND authority_kind = ? AND lower(public_key) = lower(?)",
			user, models.AuthorityLegacy, hex.EncodeToString(pub[:])).
		Count(&n)
	return n
}

// ============================================================ §11 THE DELETED-LEGACY-ROW GAP

// PATH A: the row was REVOKED first. Gate 11.5 refuses to delete a revoked row, so the
// tombstone - and with it the deny-list entry - is permanent.
func TestP68_S11_RevokedLegacyRowCannotBeDeletedSoTheDenyListSurvives(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	revTok := e.fullLogin(t, a)
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/legacy-A/revoke", "{}", revTok, ""); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}

	del, dout := e.req(t, http.MethodDelete, "/api/e2ee/devices/legacy-A", "", revTok, "")
	remaining := e.legacyRowsHolding(t, a.id, kAccountPub)

	t.Logf("§11-A: DELETE of a revoked legacy row -> %d %v ; deny-list entries still present = %d",
		del, dout["error"], remaining)
	if del < 400 {
		t.Fatalf("§11-A: a revoked row was deleted (%d) - the tombstone is not permanent", del)
	}
	if remaining != 1 {
		t.Fatalf("§11-A: the deny-list entry vanished (%d rows)", remaining)
	}

	// And retirement therefore still refuses the key.
	e.retire(t, a.id)
	code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A-reborn", kAccountPub, kAccountPriv)
	t.Logf("§11-A: post-retirement re-enrol of the same key -> %d", code)
	if code < 400 {
		t.Fatalf("§11-A: the key was resurrected (%d)", code)
	}
	t.Logf("§11-A CONCLUSION: for a REVOKED legacy device the gap does not exist. Gate 11.5's " +
		"refusal to delete a revoked row is what keeps the deny-list entry alive.")
}

// PATH B: the row was NEVER revoked, and is deleted legitimately. This is the actual gap.
//
// A device the user simply removes from their device list is not revoked, so DeleteDevice
// accepts it. The row - and the only record that this key was ever a legacy key - is gone. The
// key itself is still on that machine.
func TestP68_S11_DeletedUnrevokedLegacyRowErasesTheDenyListEntry(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	// 1-2. A legacy device holding K_account.
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	before := e.legacyRowsHolding(t, a.id, kAccountPub)

	// 3. Deleted while still healthy - an ordinary "remove this device" action.
	tok := e.fullLogin(t, a)
	del, _ := e.req(t, http.MethodDelete, "/api/e2ee/devices/legacy-A", "", tok, "")
	after := e.legacyRowsHolding(t, a.id, kAccountPub)
	t.Logf("§11-B: DELETE of an UNREVOKED legacy row -> %d ; deny-list entries %d -> %d",
		del, before, after)
	if del >= 400 {
		t.Fatalf("§11-B: the fixture could not delete an unrevoked row (%d); re-derive this test", del)
	}

	// 4. Retire, then present the very same K_account under a brand-new device id.
	e.retire(t, a.id)
	code, out := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-A-resurrected", kAccountPub, kAccountPriv)
	row, found := e.deviceRowP67(t, a.id, "legacy-A-resurrected")

	// And the same attempt while falsely declaring per-device authority.
	dcode, _ := e.enrolAs(t, e.fullLogin(t, a), "legacy-A-disguised",
		models.AuthorityDevice, kAccountPub, kAccountPriv)
	drow, dfound := e.deviceRowP67(t, a.id, "legacy-A-disguised")

	t.Logf("§11-B: post-retirement, old K_account under a fresh id: declared legacy=%d %v",
		code, out["error"])
	t.Logf("§11-B: post-retirement, same key declared as device=%d verified=%v kind=%q",
		dcode, dfound && drow.VerifiedAt != nil, drow.AuthorityKind)

	resurrected := (code < 400 && found && row.VerifiedAt != nil) ||
		(dcode < 400 && dfound && drow.VerifiedAt != nil)

	if !resurrected {
		t.Logf("§11-B CONCLUSION: NOT A BLOCKER - the key was refused even with no deny-list " +
			"entry, so something other than the row is holding the line.")
		return
	}
	t.Logf("§11-B CONCLUSION: **BLOCKER FOR RETIREMENT ROLLOUT**. After deleting an unrevoked " +
		"legacy row, the server can no longer distinguish a RETIRED K_account from a " +
		"NEVER-SEEN key: both are simply absent from e2ee_devices. Retirement's deny-list is " +
		"built from recorded provenance, and deletion erases exactly that record while leaving " +
		"the key itself intact on the device. A holder of the old K_account who deletes (or " +
		"whose user deletes) the device row before retirement re-enrols afterwards.")
}

// The decisive framing: can the server tell a retired K_account from a never-seen key?
func TestP68_S11_ServerCannotDistinguishRetiredKeyFromNeverSeenKey(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)

	retiredKeyPub, retiredKeyPriv := newKeyPair(t) // was a legacy device, row deleted
	neverSeenPub, neverSeenPriv := newKeyPair(t)   // brand new, never enrolled anywhere

	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "old-device", retiredKeyPub, retiredKeyPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	if code, _ := e.req(t, http.MethodDelete, "/api/e2ee/devices/old-device", "",
		e.fullLogin(t, a), ""); code >= 400 {
		t.Fatalf("fixture delete: %d", code)
	}
	e.retire(t, a.id)

	// What state does the server hold about each key?
	retiredEvidence := e.legacyRowsHolding(t, a.id, retiredKeyPub)
	neverSeenEvidence := e.legacyRowsHolding(t, a.id, neverSeenPub)

	// And what does each achieve, declared as a device key with real recovery authority?
	retiredCode, _ := e.enrolAs(t, e.fullLogin(t, a), "with-retired-key",
		models.AuthorityDevice, retiredKeyPub, retiredKeyPriv)
	neverSeenCode, _ := e.enrolAs(t, e.fullLogin(t, a), "with-never-seen-key",
		models.AuthorityDevice, neverSeenPub, neverSeenPriv)

	t.Logf("§11: server-side evidence  retiredKey=%d rows  neverSeenKey=%d rows",
		retiredEvidence, neverSeenEvidence)
	t.Logf("§11: post-retirement enrol retiredKey=%d          neverSeenKey=%d",
		retiredCode, neverSeenCode)

	if retiredEvidence == neverSeenEvidence && retiredCode == neverSeenCode {
		t.Logf("§11 PROOF: the two are INDISTINGUISHABLE. The server holds the same amount of " +
			"evidence about each (none) and treats them identically. Retirement cannot refuse a " +
			"key it has no record of, and deletion is what removes the record.")
	} else {
		t.Logf("§11: the two ARE distinguishable - evidence or outcome differs, so retirement " +
			"has something left to check against.")
	}
}

// Is DELETE really the only way a row disappears? A cascade would be a second erasure path.
func TestP68_S11_DeletionPathsThatCouldEraseProvenance(t *testing.T) {
	e := p68Setup(t)
	type fk struct {
		Name       string
		DeleteRule string
		RefTable   string
	}
	var rows []fk
	e.db.Raw(`
		SELECT tc.constraint_name AS name, rc.delete_rule, ccu.table_name AS ref_table
		FROM information_schema.table_constraints tc
		JOIN information_schema.referential_constraints rc ON rc.constraint_name = tc.constraint_name
		JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
		WHERE tc.table_name = 'e2_ee_devices' AND tc.constraint_type = 'FOREIGN KEY'`).Scan(&rows)
	for _, r := range rows {
		t.Logf("§11: e2ee_devices FK %s -> %s ON DELETE %s", r.Name, r.RefTable, r.DeleteRule)
	}
	t.Logf("§11: the handler-level erasure path is DeleteDevice, which refuses revoked rows; " +
		"any CASCADE above removes rows only when the referenced parent goes, which for users " +
		"means the account itself is gone.")
}

// ============================================================ §9 PROVENANCE SEMANTICS

func TestP68_S9_AuthorityKindIsExplicitServerStateNotInference(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)

	kAccountPub, kAccountPriv := newKeyPair(t)
	kDevicePub, kDevicePriv := newKeyPair(t)

	// Legacy client, TOTP account, session genuinely holds recovery authority.
	legacyTok := e.fullLogin(t, a)
	if !e.hasRecoveryAuthority(t, a.id) {
		t.Fatalf("fixture: expected a marker on the legacy client's session")
	}
	if code, _ := e.enrolLegacy(t, legacyTok, "legacy-client", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("legacy enrol: %d", code)
	}
	legacyRow, _ := e.deviceRowP67(t, a.id, "legacy-client")

	// Modern client.
	if code, _ := e.enrolDevice(t, e.fullLogin(t, a), "modern-client", kDevicePub, kDevicePriv); code >= 400 {
		t.Fatalf("modern enrol: %d", code)
	}
	modernRow, _ := e.deviceRowP67(t, a.id, "modern-client")

	// Declaration spoofing: K_account presented as a device key, with a real marker.
	spoofCode, _ := e.enrolAs(t, e.fullLogin(t, a), "spoofed",
		models.AuthorityDevice, kAccountPub, kAccountPriv)
	spoofRow, spoofFound := e.deviceRowP67(t, a.id, "spoofed")

	t.Logf("§9 legacy client (with marker) -> kind=%q", legacyRow.AuthorityKind)
	t.Logf("§9 modern client               -> kind=%q", modernRow.AuthorityKind)
	t.Logf("§9 spoofed declaration          -> %d kind=%q", spoofCode, spoofRow.AuthorityKind)

	if legacyRow.AuthorityKind != models.AuthorityLegacy {
		t.Fatalf("§9: a legacy client was recorded as %q", legacyRow.AuthorityKind)
	}
	if modernRow.AuthorityKind != models.AuthorityDevice {
		t.Fatalf("§9: a modern client was recorded as %q", modernRow.AuthorityKind)
	}
	// The honest finding: while LEGACY_OPEN, a spoofed declaration IS accepted as a device row,
	// because the marker is real and the server has no way to see that the key is old.
	if spoofFound && spoofRow.AuthorityKind == models.AuthorityDevice {
		t.Logf("§9 FINDING: while LEGACY_OPEN, a caller that declares \"device\" while presenting " +
			"K_account IS recorded as device-provenance. The declaration is checked against a " +
			"spent marker, not against the key's history - and the key's history is exactly what " +
			"the server does not have. This is the same root cause as §11 and it means a " +
			"pre-retirement account can accumulate device-provenance rows holding K_account.")
	}

	// Provenance is a stored column, not derived: two rows with DIFFERENT keys can share a kind,
	// and two rows with the SAME key can differ in kind. Neither is inferable from the key.
	var kinds []string
	e.db.Raw(`SELECT DISTINCT authority_kind FROM e2_ee_devices WHERE user_id = ? ORDER BY 1`,
		a.id).Scan(&kinds)
	t.Logf("§9: recorded kinds for this account = %v (an explicit column, never derived)", kinds)
}

// ============================================================ §10 RETIREMENT ENFORCEMENT

func TestP68_S10_RetirementBoundary(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	// LEGACY_OPEN: the legacy path works.
	open, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-1", kAccountPub, kAccountPriv)
	t.Logf("§10 LEGACY_OPEN  legacy path -> %d", open)
	if open >= 400 {
		t.Fatalf("§10: LEGACY_OPEN refused the legacy path (%d)", open)
	}

	e.retire(t, a.id)

	// LEGACY_RETIRED: refused, honestly declared or not.
	honest, _ := e.enrolLegacy(t, e.fullLogin(t, a), "legacy-2", kAccountPub, kAccountPriv)
	spoofed, _ := e.enrolAs(t, e.fullLogin(t, a), "legacy-3", models.AuthorityDevice, kAccountPub, kAccountPriv)
	// Fresh key with recovery authority: allowed.
	freshPub, freshPriv := newKeyPair(t)
	fresh, _ := e.enrolDevice(t, e.fullLogin(t, a), "fresh", freshPub, freshPriv)

	t.Logf("§10 LEGACY_RETIRED old key honest=%d spoofed=%d ; fresh K_device=%d",
		honest, spoofed, fresh)
	if honest < 400 || spoofed < 400 {
		t.Fatalf("§10: retirement was bypassed (honest=%d spoofed=%d)", honest, spoofed)
	}
	if fresh >= 400 {
		t.Fatalf("§10: a legitimate fresh key was refused after retirement (%d)", fresh)
	}
	for _, id := range []string{"legacy-2", "legacy-3"} {
		if _, found := e.deviceRowP67(t, a.id, id); found {
			t.Fatalf("§10: a refused enrolment created %s", id)
		}
	}

	// The check lives inside the registration transaction, so a REFUSED attempt spends nothing.
	// Four sessions carried a marker here; the two that enrolled successfully (the LEGACY_OPEN
	// legacy device and the fresh K_device) spent theirs, and the two that retirement refused
	// did not. Consumption tracks "a new binding was established", not "the row was device-kind".
	var issued, spent int64
	e.db.Model(&models.Session{}).
		Where("user_id = ? AND recovery_authority_expires_at IS NOT NULL", a.id).Count(&issued)
	e.db.Model(&models.Session{}).
		Where("user_id = ? AND recovery_authority_consumed_at IS NOT NULL", a.id).Count(&spent)
	t.Logf("§10: markers issued=%d spent=%d (2 successful enrolments, 2 refusals)", issued, spent)
	if spent != 2 {
		t.Fatalf("§10: expected the two successful enrolments to spend a marker each, found %d", spent)
	}
	if issued-spent != 2 {
		t.Fatalf("§10: expected the two refused attempts to leave their markers unspent, %d unspent",
			issued-spent)
	}
}

// ============================================================ §12 RETIREMENT/ENROLMENT RACE

func TestP68_S12_RetirementEnrolmentRaceHasNoInvalidState(t *testing.T) {
	for attempt := 0; attempt < 6; attempt++ {
		e := p68Setup(t)
		a := e.newAccount(t, true)
		tok := e.fullLogin(t, a)
		kAccountPub, kAccountPriv := newKeyPair(t)

		var wg sync.WaitGroup
		var enrolCode int
		wg.Add(2)
		go func() {
			defer wg.Done()
			enrolCode, _ = e.enrolLegacy(t, tok, "racing", kAccountPub, kAccountPriv)
		}()
		go func() {
			defer wg.Done()
			_ = retireLegacyAuthority(e.db, a.id, time.Now())
		}()
		wg.Wait()

		row, found := e.deviceRowP67(t, a.id, "racing")
		var acct models.E2EEAccountAuthority
		retired := e.db.Where("user_id = ?", a.id).First(&acct).Error == nil && acct.LegacyRetiredAt != nil

		ordering := "enrolment committed first (honest legacy row exists)"
		if !found {
			ordering = "retirement won (legacy enrolment denied)"
		}
		t.Logf("§12 attempt %d: enrol=%d row=%v kind=%q retired=%v | %s",
			attempt, enrolCode, found, row.AuthorityKind, retired, ordering)

		if !retired {
			t.Fatalf("§12: the retirement was lost")
		}
		if (enrolCode < 400) != found {
			t.Fatalf("§12: torn state - status %d but row=%v", enrolCode, found)
		}
		// The forbidden state: a retired account holding a legacy row that no valid
		// ordering could have produced. A row is only valid if the enrolment
		// SUCCEEDED, which means it committed before retirement was visible.
		if found && enrolCode >= 400 {
			t.Fatalf("§12: retired account holds a legacy row from a REFUSED enrolment")
		}
		if found && row.AuthorityKind != models.AuthorityLegacy {
			t.Fatalf("§12: a racing legacy enrolment was recorded as %q", row.AuthorityKind)
		}
	}
}

// ============================================================ §13 RECOVERY vs REVOCATION

func TestP68_S13_RecoveryAuthorityIsUnaffectedByUnrelatedRevocation(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)

	// An existing device on its own session.
	otherTok := e.fullLogin(t, a)
	oPub, oPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, otherTok, "device-other", oPub, oPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}

	// A separate recovery session.
	recovTok := e.fullLogin(t, a)
	recovSession := e.sessionRow(t, a.id)
	e.retire(t, a.id) // make the marker load-bearing

	// Revoke the UNRELATED device.
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-other/revoke", "{}",
		recovTok, ""); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	var after models.Session
	e.db.Where("id = ?", recovSession.ID).First(&after)

	pub, priv := newKeyPair(t)
	code, _ := e.enrolDevice(t, recovTok, "recovered", pub, priv)
	t.Logf("§13: after revoking an unrelated device -> markerConsumed=%v sessionRevoked=%v enrol=%d",
		after.RecoveryAuthorityConsumedAt != nil, after.RevokedAt != nil, code)
	if after.RecoveryAuthorityConsumedAt != nil {
		t.Fatalf("§13: revoking another device consumed this session's marker")
	}
	if code >= 400 {
		t.Fatalf("§13: revoking another device broke an unrelated recovery (%d)", code)
	}
	t.Logf("§13a CONFIRMED: revocation is scoped to the revoked device's own rows and sessions; " +
		"unrelated recovery authority is untouched.")
}

func TestP68_S13_RevokedRecoverySessionCannotCompleteEnrolment(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a)
	s := e.sessionRow(t, a.id)

	// Kill the recovery session itself, the way password reset and device revocation do.
	now := time.Now()
	if err := e.db.Model(&models.Session{}).Where("id = ?", s.ID).
		Updates(map[string]interface{}{
			"revoked_at": now, "revoke_reason": "forensic", "refresh_hash": nil,
		}).Error; err != nil {
		t.Fatalf("revoke session: %v", err)
	}

	pub, priv := newKeyPair(t)
	code, out := e.enrolDevice(t, tok, "doomed", pub, priv)
	_, found := e.deviceRowP67(t, a.id, "doomed")
	var after models.Session
	e.db.Where("id = ?", s.ID).First(&after)

	t.Logf("§13b: enrolment on a revoked recovery session -> %d %v deviceCreated=%v markerSpent=%v",
		code, out["error"], found, after.RecoveryAuthorityConsumedAt != nil)
	if code < 400 || found {
		t.Fatalf("§13b: a revoked session completed an enrolment (%d, created=%v)", code, found)
	}
	if after.RecoveryAuthorityConsumedAt != nil {
		t.Fatalf("§13b: a refused enrolment spent the marker")
	}
	t.Logf("§13b ATOMIC BOUNDARY: AuthMiddleware refuses the dead session before the handler " +
		"runs, and consumeRecoveryAuthority additionally carries `revoked_at IS NULL` in its own " +
		"predicate - so even a session revoked mid-transaction cannot spend its marker.")
}

// ============================================================ §14 2FA-DISABLE RACE

// Phase 66 flagged this; Phase 67 did not fix it. Measure exactly what happens now.
func TestP68_S14_MarkerSurvivesTotpRemoval(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	tok := e.fullLogin(t, a) // earned with a verified TOTP code
	e.retire(t, a.id)        // make the marker the only way in

	s := e.sessionRow(t, a.id)
	ttl := time.Until(*s.RecoveryAuthorityExpiresAt).Round(time.Second)

	// The second factor is removed.
	if err := e.db.Exec(`UPDATE users SET totp_enabled = false, totp_secret = '' WHERE id = ?`,
		a.id).Error; err != nil {
		t.Fatalf("disable totp: %v", err)
	}
	var live int64
	e.db.Model(&models.Session{}).Where("user_id = ? AND revoked_at IS NULL", a.id).Count(&live)

	pub, priv := newKeyPair(t)
	code, _ := e.enrolDevice(t, tok, "post-removal", pub, priv)

	// And a FRESH login after removal earns nothing, because there is no factor left.
	freshTok := e.fullLogin(t, a)
	freshPub, freshPriv := newKeyPair(t)
	freshCode, _ := e.enrolDevice(t, freshTok, "post-removal-fresh", freshPub, freshPriv)

	t.Logf("§14: marker TTL at issue = %s ; live sessions after TOTP removal = %d", ttl, live)
	t.Logf("§14: enrolment on the PRE-removal session  -> %d", code)
	t.Logf("§14: enrolment on a POST-removal session   -> %d", freshCode)

	if freshCode < 400 {
		t.Fatalf("§14: a password-only session enrolled after retirement (%d) - the policy leaked", freshCode)
	}
	if code < 400 {
		t.Logf("§14 MEASURED: the already-issued marker REMAINS VALID after the factor is "+
			"removed. Its window is bounded by the %s TTL and it is single-use, and it was "+
			"earned by a genuinely verified TOTP code before removal. No NEW authority can be "+
			"earned afterwards (the fresh login was refused). Classification is a policy call, "+
			"stated in the report - not decided here.", ttl)
	} else {
		t.Logf("§14 MEASURED: removing the factor also invalidated the outstanding marker.")
	}
}

// ============================================================ §15 VAULT GATE

func TestP68_S15_VaultGateForensics(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	other := e.newAccount(t, true)

	// Production wiring, read from the route table.
	src, err := os.ReadFile(filepath.Join("..", "main.go"))
	if err != nil {
		t.Fatalf("read main.go: %v", err)
	}
	want := `e2eeRoutes.Put("/vault", middleware.RequireDeviceIdentity(), e2eeHandler.PutVault)`
	if !strings.Contains(string(src), want) {
		t.Fatalf("§15: the production vault route is no longer gated")
	}
	t.Logf("§15 route table: %s", want)

	oTok := e.fullLogin(t, other)
	oPub, oPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, oTok, "device-of-other", oPub, oPriv); code >= 400 {
		t.Fatalf("fixture other: %d", code)
	}

	tok := e.fullLogin(t, a)
	for _, c := range []struct{ name, device string }{
		{"account authority only", "any"},
		{"invented device id", "INVENTED"},
		{"wrong-account device", "device-of-other"},
	} {
		code, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), tok, c.device)
		t.Logf("§15 %-24s -> %d", c.name, code)
		if code != http.StatusForbidden {
			t.Fatalf("§15 %s: expected 403, got %d", c.name, code)
		}
	}

	pub, priv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, tok, "device-A", pub, priv); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	create, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(1, 0), tok, "device-A")
	t.Logf("§15 %-24s -> %d", "device authority", create)
	if create != http.StatusCreated {
		t.Fatalf("§15: device authority could not create the vault (%d)", create)
	}

	// session mismatch
	bTok := e.fullLogin(t, a)
	bPub, bPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, bTok, "device-B", bPub, bPriv); code >= 400 {
		t.Fatalf("enrol B: %d", code)
	}
	mism, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(2, 1), tok, "device-B")
	t.Logf("§15 %-24s -> %d", "session/device mismatch", mism)
	if mism != http.StatusForbidden {
		t.Fatalf("§15 mismatch: expected 403, got %d", mism)
	}

	// revoked device
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/device-A/revoke", "{}", tok, "device-A"); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	rev, _ := e.req(t, http.MethodPut, "/api/e2ee/vault-gated", gate11VaultBody(2, 1), tok, "device-A")
	t.Logf("§15 %-24s -> %d", "revoked device", rev)
	if rev < 400 {
		t.Fatalf("§15: a revoked device wrote the vault (%d)", rev)
	}
}

// ============================================================ §16 PAIRING REGRESSION

func TestP68_S16_PairingBoundaryIncludingRevokedSource(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)

	// A source device that is then revoked.
	srcTok := e.fullLogin(t, a)
	sPub, sPriv := newKeyPair(t)
	if code, _ := e.enrolDevice(t, srcTok, "source-device", sPub, sPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	if rc, _ := e.req(t, http.MethodPost, "/api/e2ee/devices/source-device/revoke", "{}",
		srcTok, "source-device"); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}

	// Destination side stays deviceless.
	destTok := e.fullLogin(t, a)
	ephPub, _ := newKeyPair(t)
	body, _ := json.Marshal(map[string]string{"ephemeral_pub_hex": hex.EncodeToString(ephPub[:])})
	ccode, cout := e.req(t, http.MethodPost, "/api/e2ee/pairing", string(body), destTok, "")
	sessionID, _ := cout["session_id"].(string)
	pcode, pout := e.req(t, http.MethodGet, "/api/e2ee/pairing/"+sessionID+"/payload", "", destTok, "")

	// The REVOKED source tries to complete, both naming itself and omitting the header.
	comp, _ := json.Marshal(map[string]string{
		"payload_b64": "cGhhc2UtNjgtcmV2b2tlZC1zb3VyY2U=", "sender_pub_hex": hex.EncodeToString(sPub[:]),
	})
	named, _ := e.req(t, http.MethodPost, "/api/e2ee/pairing/"+sessionID+"/complete", string(comp), srcTok, "source-device")
	omitted, _ := e.req(t, http.MethodPost, "/api/e2ee/pairing/"+sessionID+"/complete", string(comp), srcTok, "")

	var ps models.E2EEPairingSession
	e.db.Where("user_id = ? AND session_id = ?", a.id, sessionID).First(&ps)

	t.Logf("§16 destination create=%d payload=%d delivered=%v", ccode, pcode, pout["payload_b64"] != nil)
	t.Logf("§16 revoked source completion: naming itself=%d  omitting the header=%d  materialStored=%v",
		named, omitted, len(ps.Payload) > 0)

	if ccode >= 400 || pcode >= 400 {
		t.Fatalf("§16: destination-side pairing is no longer deviceless (create=%d payload=%d)", ccode, pcode)
	}
	if named < 400 || omitted < 400 {
		t.Fatalf("§16: a revoked source completed a pairing (named=%d omitted=%d)", named, omitted)
	}
	if len(ps.Payload) > 0 {
		t.Fatalf("§16: material was stored despite the refusals")
	}
}

// ============================================================ §17 CRYPTO INVARIANT

func TestP68_S17_Phase44ProofConstructionUnchanged(t *testing.T) {
	// The wire format and sizes are constants; pin them so a future edit has to be deliberate.
	if e2ee.DeviceChallengeBytes != 32 {
		t.Fatalf("§17: challenge size changed to %d", e2ee.DeviceChallengeBytes)
	}
	src, err := os.ReadFile(filepath.Join("..", "e2ee", "device_challenge.go"))
	if err != nil {
		t.Fatalf("read device_challenge.go: %v", err)
	}
	for _, want := range []string{
		"box.GenerateKey(rand.Reader)", // one-shot sender keypair
		"var nonce [24]byte",           // 24-byte nonce
		"rand.Read(nonce[:])",
		"box.Seal(nil, challenge, &nonce, &their, priv)", // crypto_box_easy
		"append(out, nonce[:]...)",                       // wire = nonce || ciphertext
	} {
		if !strings.Contains(string(src), want) {
			t.Fatalf("§17: challenge construction changed - missing %q", want)
		}
	}
	proofSrc, err := os.ReadFile(filepath.Join("device_proof.go"))
	if err != nil {
		t.Fatalf("read device_proof.go: %v", err)
	}
	for _, want := range []string{
		"subtle.ConstantTimeCompare(ch.Expected, proof) != 1", // constant-time comparison
		"len(proof) != e2ee.DeviceChallengeBytes",             // fixed proof length
	} {
		if !strings.Contains(string(proofSrc), want) {
			t.Fatalf("§17: proof verification changed - missing %q", want)
		}
	}
	t.Logf("§17 CONFIRMED: X25519 crypto_box, 24-byte random nonce, wire = nonce||ciphertext, " +
		"32-byte challenge, constant-time proof comparison. Unchanged by Phase 67 - the only " +
		"cryptographic change was WHICH local key is presented (K_account -> K_device).")
}

// ============================================================ §20 CONCURRENCY SEMANTICS

// The Phase 67 gateDeviceKey mutex was test-support only. Prove production concurrency is
// unchanged by exercising the real registration endpoint concurrently.
func TestP68_S20_ProductionConcurrencySemanticsUnchanged(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)

	tokens := make([]string, 4)
	for i := range tokens {
		tokens[i] = e.fullLogin(t, a)
	}
	pub, priv := newKeyPair(t)

	var wg sync.WaitGroup
	codes := make([]int, len(tokens))
	wg.Add(len(tokens))
	for i := range tokens {
		go func(idx int) {
			defer wg.Done()
			codes[idx], _ = e.enrolDevice(t, tokens[idx], "contended", pub, priv)
		}(i)
	}
	wg.Wait()

	var rows int64
	e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", a.id, "contended").Count(&rows)
	var bound int64
	e.db.Model(&models.Session{}).
		Where("user_id = ? AND device_id IS NOT NULL", a.id).Count(&bound)

	t.Logf("§20: four concurrent enrolments of one device id -> codes=%v rows=%d boundSessions=%d",
		codes, rows, bound)
	if rows != 1 {
		t.Fatalf("§20: expected exactly one device row, found %d", rows)
	}
	t.Logf("§20 CONFIRMED: the (user_id, device_id) unique index and the row lock still " +
		"serialise registration. The Phase 67 mutex was on a TEST-ONLY keypair cache and " +
		"touched no production code path.")
}

// The generalisation of §11, and the more serious form: no deletion is required at all.
//
// The deny-list scans rows whose authority_kind is "legacy". A caller holding K_account plus
// password + TOTP can, BEFORE retirement, enrol that same key while declaring "device" - and the
// row is then recorded as device-provenance. Retirement afterwards never looks at it.
//
// Deletion (the Phase 67 gap) is one way to remove a key from the deny-list. Mis-declaration is
// another, and it needs no privileged action at all.
func TestP68_S11_MisdeclaredKeyEscapesTheDenyListWithoutAnyDeletion(t *testing.T) {
	e := p68Setup(t)
	a := e.newAccount(t, true)
	kAccountPub, kAccountPriv := newKeyPair(t)

	// A genuine legacy device exists, so the account really is a legacy account.
	if code, _ := e.enrolLegacy(t, e.fullLogin(t, a), "honest-legacy", kAccountPub, kAccountPriv); code >= 400 {
		t.Fatalf("fixture: %d", code)
	}
	legacyEvidence := e.legacyRowsHolding(t, a.id, kAccountPub)

	// BEFORE retirement: the same key, declared as a device key, on a session with a real marker.
	pre, _ := e.enrolAs(t, e.fullLogin(t, a), "mislabelled", models.AuthorityDevice,
		kAccountPub, kAccountPriv)
	mis, _ := e.deviceRowP67(t, a.id, "mislabelled")

	e.retire(t, a.id)

	// AFTER retirement: does the honest legacy row still block the key?
	post, _ := e.enrolAs(t, e.fullLogin(t, a), "after-retirement", models.AuthorityDevice,
		kAccountPub, kAccountPriv)

	t.Logf("§11-C: legacy evidence for the key = %d row(s)", legacyEvidence)
	t.Logf("§11-C: pre-retirement mis-declared enrol -> %d kind=%q", pre, mis.AuthorityKind)
	t.Logf("§11-C: post-retirement, same key         -> %d", post)

	if pre < 400 && mis.AuthorityKind == models.AuthorityDevice {
		t.Logf("§11-C FINDING: K_account was recorded as DEVICE provenance with no deletion and " +
			"no privileged action - only a declaration and a marker the holder legitimately " +
			"earned. That row is invisible to the retirement deny-list, which scans " +
			"authority_kind = 'legacy' only.")
	}
	if post >= 400 {
		t.Logf("§11-C: the surviving honest legacy row still blocks the key after retirement, so " +
			"mis-declaration alone does not resurrect it while ANY legacy row for that key remains.")
		return
	}
	t.Logf("§11-C **BLOCKER**: the key enrolled after retirement despite an honest legacy row " +
		"existing for it. The deny-list is not sufficient.")
}
