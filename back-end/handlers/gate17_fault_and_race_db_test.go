package handlers

import (
	"bytes"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"messenger-app/database"
	"messenger-app/models"
)

// ================================================================ F-1
//
// An unexpected database failure is not evidence about a credential.
//
// Both clients treat any HTTP status as a definitive answer from the server:
// Android clears the pending record and reports SessionOver, Windows clears it
// and logs out. The pending record is the ONLY thing that can recover a
// rotation the server already committed, so answering an infrastructure fault
// with 401 destroys the lost-response mechanism in exactly the situation it
// exists for - and does so while the transaction has actually rolled back and
// the credential is still perfectly good.

// injectLedgerFault installs a scratch-schema trigger that aborts the ledger
// insert with an ordinary (non-unique-violation) exception. It stands in for
// any infrastructure failure that aborts the refresh transaction: lock timeout,
// lost connection, pool exhaustion, failed commit. Production code is untouched.
func injectLedgerFault(t *testing.T) {
	t.Helper()
	if err := database.DB.Exec(`
		CREATE OR REPLACE FUNCTION gate17_test_fault() RETURNS trigger AS $$
		BEGIN
			RAISE EXCEPTION 'simulated infrastructure fault';
		END;
		$$ LANGUAGE plpgsql;`).Error; err != nil {
		t.Fatalf("create fault fn: %v", err)
	}
	if err := database.DB.Exec(`
		CREATE TRIGGER gate17_test_fault_trg BEFORE INSERT ON consumed_refresh
		FOR EACH ROW EXECUTE FUNCTION gate17_test_fault()`).Error; err != nil {
		t.Fatalf("create fault trg: %v", err)
	}
}

func clearLedgerFault(t *testing.T) {
	t.Helper()
	database.DB.Exec(`DROP TRIGGER IF EXISTS gate17_test_fault_trg ON consumed_refresh`)
	database.DB.Exec(`DROP FUNCTION IF EXISTS gate17_test_fault()`)
}

// TestGate17_UnexpectedDBFaultIsServerFaultNotDenial pins the distinction
// between "your credential is bad" and "we failed".
func TestGate17_UnexpectedDBFaultIsServerFaultNotDenial(t *testing.T) {
	e := gate17Setup(t)
	before := e.sessionRow(t)

	injectLedgerFault(t)
	reqID := uuid.New()
	res := e.refreshWith(t, e.refresh, reqID)
	clearLedgerFault(t)

	if res.code != http.StatusInternalServerError {
		t.Errorf("PROVEN BROKEN: an infrastructure fault answered with HTTP %d. "+
			"Both clients read any status as definitive and discard the pending "+
			"record, which is the only way to recover a committed rotation", res.code)
	}
	if res.access != "" || res.refresh != "" {
		t.Errorf("a failed transaction must not return credentials")
	}

	// The rollback must be complete, and nothing may be revoked merely because
	// the database failed.
	after := e.sessionRow(t)
	if after.RevokedAt != nil {
		t.Errorf("PROVEN BROKEN: a database fault revoked the session")
	}
	if after.Generation != before.Generation {
		t.Errorf("partial rotation survived a rollback: generation %d -> %d",
			before.Generation, after.Generation)
	}
	if !bytes.Equal(after.RefreshHash, before.RefreshHash) {
		t.Errorf("partial rotation survived a rollback: refresh_hash changed")
	}
	if n := e.ledgerCount(t); n != 0 {
		t.Errorf("a rolled-back rotation left %d ledger row(s)", n)
	}

	// The decisive property: the client may retry the SAME logical refresh with
	// the SAME request_id and succeed. Nothing about the credential changed.
	retry := e.refreshWith(t, e.refresh, reqID)
	if retry.code != http.StatusOK {
		t.Errorf("PROVEN BROKEN: after a server-side fault the same logical refresh "+
			"must still be retryable, got %d", retry.code)
	}
	if e.sessionRow(t).Generation != before.Generation+1 {
		t.Errorf("the retry should have performed exactly one rotation")
	}
}

// TestGate17_LostResponseRecoveryAfterServerFault is the end-to-end shape of the
// mechanism F-1 endangered: a fault, then a committed-but-unseen rotation, then
// recovery - all under ONE request_id, producing ONE logical rotation.
func TestGate17_LostResponseRecoveryAfterServerFault(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()

	// Attempt 1: the server fails before committing. Nothing rotated, so the
	// client's pending record (R0 + X) remains valid.
	injectLedgerFault(t)
	first := e.refreshWith(t, e.refresh, reqID)
	clearLedgerFault(t)
	if first.code == http.StatusOK {
		t.Fatalf("the injected fault should have prevented a rotation")
	}
	if e.ledgerCount(t) != 0 {
		t.Fatalf("no ledger row may exist after a failed attempt")
	}

	// Attempt 2: same R0, same X. This one commits; imagine the response is lost.
	second := e.refreshWith(t, e.refresh, reqID)
	if second.code != http.StatusOK {
		t.Fatalf("retry after the fault cleared should rotate, got %d", second.code)
	}
	gen := e.sessionRow(t).Generation

	// Attempt 3: the client never saw attempt 2. Same R0, same X again - it must
	// recover the SAME successor without rotating a second time.
	third := e.refreshWith(t, e.refresh, reqID)
	if third.code != http.StatusOK {
		t.Fatalf("in-window replay must recover the successor, got %d", third.code)
	}
	if third.refresh != second.refresh {
		t.Errorf("replay returned a DIFFERENT successor: a second logical rotation occurred")
	}
	if g := e.sessionRow(t).Generation; g != gen {
		t.Errorf("replay advanced the generation %d -> %d; exactly one rotation was allowed", gen, g)
	}
	if n := e.ledgerCount(t); n != 1 {
		t.Errorf("expected exactly one ledger row for one logical refresh, got %d", n)
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Errorf("an honest retry must never be read as reuse (reason=%v)", s.RevokeReason)
	}
}

// ================================================ refresh vs password reset
//
// The reset path writes users.password_hash; the trigger revokes sessions from
// inside that same transaction. Refresh and reset therefore contend for the
// same session rows. Either order is legitimate - what must never happen is a
// usable credential surviving the revocation, a resurrection, or the race
// being mistaken for a fork.

// startReset issues a real challenge through the production endpoint and
// returns (challengeID, code), through the development relay - the only
// delivery mechanism reset has since Phase 72A (see startDevReset).
func (e *g17Env) startReset(t *testing.T) (string, string) {
	t.Helper()
	return e.startDevReset(t)
}

func (e *g17Env) completeReset(t *testing.T, challengeID, otp string) int {
	t.Helper()
	code, _ := e.postJSON(t, "/auth/password-reset/complete", map[string]string{
		"challenge_id": challengeID,
		"code":         otp,
		"new_password": "another-long-enough-password",
	})
	return code
}

// assertNoUnsafeStateAfterReset holds for EVERY interleaving.
func assertNoUnsafeStateAfterReset(t *testing.T, e *g17Env, refreshCode int,
	successor string, reqID uuid.UUID) {
	t.Helper()

	s := e.sessionRow(t)
	if s.RevokedAt == nil {
		t.Errorf("PROVEN BROKEN: the password reset committed but left the session live")
	}
	if s.RefreshHash != nil {
		t.Errorf("PROVEN BROKEN: a revoked session retains a usable refresh hash")
	}
	reason := ""
	if s.RevokeReason != nil {
		reason = *s.RevokeReason
	}
	if reason == "refresh_reuse" {
		t.Errorf("PROVEN BROKEN: the race was misread as a fork; an honest client "+
			"was accused of reuse (reason=%q)", reason)
	}

	// Whatever the ordering, a successor minted before the revocation must not
	// outlive it.
	if refreshCode == http.StatusOK && successor != "" {
		if c := e.refreshWith(t, successor, uuid.New()).code; c != http.StatusUnauthorized {
			t.Errorf("PROVEN BROKEN: a successor minted during the race survived the "+
				"password reset (got %d)", c)
		}
	}
	// Nor may the cache resurrect the session.
	replay := e.refreshWith(t, e.refresh, reqID)
	if replay.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: cached replay resurrected a reset session (%d)", replay.code)
	}
	if replay.refresh != "" || replay.access != "" {
		t.Errorf("cached replay returned credentials after a reset")
	}
	var live int64
	database.DB.Model(&models.ConsumedRefresh{}).
		Where("session_id = ? AND response_ciphertext IS NOT NULL", e.session).Count(&live)
	if live != 0 {
		t.Errorf("%d decryptable cache row(s) survived the reset", live)
	}
	// The session must not be resurrectable.
	err := database.DB.Exec(`UPDATE sessions SET revoked_at = NULL WHERE id = ?`, e.session).Error
	if err == nil {
		t.Errorf("PROVEN BROKEN: a revoked session was resurrected by direct update")
	}
}

// Serialized: the reset lands FIRST.
func TestGate17_PasswordResetThenRefresh_Serialized(t *testing.T) {
	e := gate17Setup(t)
	e.mountPasswordReset()
	reqID := uuid.New()

	ch, otp := e.startReset(t)
	if c := e.completeReset(t, ch, otp); c != http.StatusOK {
		t.Fatalf("reset: %d", c)
	}
	res := e.refreshWith(t, e.refresh, reqID)
	if res.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: refresh succeeded (%d) after the session was reset-revoked", res.code)
	}
	if g := e.sessionRow(t).Generation; g != 0 {
		t.Errorf("a revoked session must not rotate; generation reached %d", g)
	}
	assertNoUnsafeStateAfterReset(t, e, res.code, res.refresh, reqID)
}

// Serialized: the refresh lands FIRST.
func TestGate17_RefreshThenPasswordReset_Serialized(t *testing.T) {
	e := gate17Setup(t)
	e.mountPasswordReset()
	reqID := uuid.New()

	res := e.refreshWith(t, e.refresh, reqID)
	if res.code != http.StatusOK {
		t.Fatalf("seed rotation: %d", res.code)
	}
	ch, otp := e.startReset(t)
	if c := e.completeReset(t, ch, otp); c != http.StatusOK {
		t.Fatalf("reset: %d", c)
	}
	assertNoUnsafeStateAfterReset(t, e, res.code, res.refresh, reqID)
}

// Genuinely interleaved, serialized by a real row lock rather than by sleeps.
//
// Both actors are made to queue behind the same session row, so PostgreSQL -
// not the test - decides the order. Either winner is acceptable; the invariants
// asserted afterwards are the ones that must hold for both.
func TestGate17_RefreshVersusPasswordReset_Concurrent(t *testing.T) {
	e := gate17Setup(t)
	e.mountPasswordReset()
	reqID := uuid.New()

	// The challenge is issued before the barrier so the race is purely between
	// the rotation and the revoking write.
	ch, otp := e.startReset(t)

	barrier := newLockBarrier(t, e.session)

	var (
		res       refreshResult
		resetCode int
		wg        sync.WaitGroup
	)
	wg.Add(2)
	go func() { defer wg.Done(); res = e.refreshWith(t, e.refresh, reqID) }()
	go func() { defer wg.Done(); resetCode = e.completeReset(t, ch, otp) }()

	time.Sleep(500 * time.Millisecond) // let both pile up behind the lock
	barrier.lift()
	wg.Wait()

	if resetCode != http.StatusOK {
		t.Fatalf("the password reset must complete regardless of ordering, got %d", resetCode)
	}
	var u models.User
	if err := database.DB.First(&u, "id = ?", e.user.ID).Error; err != nil {
		t.Fatalf("reload user: %v", err)
	}
	if u.PasswordHash == e.user.PasswordHash {
		t.Errorf("password_hash unchanged after a successful reset")
	}

	gen := e.sessionRow(t).Generation
	if res.code == http.StatusOK && gen != 1 {
		t.Errorf("refresh reported success but generation is %d", gen)
	}
	if res.code != http.StatusOK && gen != 0 {
		t.Errorf("refresh did not succeed yet generation advanced to %d", gen)
	}
	t.Logf("interleaving: refresh=%d generation=%d", res.code, gen)

	assertNoUnsafeStateAfterReset(t, e, res.code, res.refresh, reqID)
}
