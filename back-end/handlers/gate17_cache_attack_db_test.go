package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// Adversarial attacks on the response cache. Every one drives the real handler
// against the real database; none mocks the security boundary.

// secondSession creates another live session, optionally device-bound.
func (e *g17Env) secondSession(t *testing.T, userID uuid.UUID, dev *uuid.UUID) (uuid.UUID, string) {
	t.Helper()
	var (
		id    uuid.UUID
		plain string
	)
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		var err error
		id, plain, err = createSession(tx, e.cfg, userID, dev, nil)
		return err
	}); err != nil {
		t.Fatalf("create session: %v", err)
	}
	return id, plain
}

func (e *g17Env) otherUser(t *testing.T) models.User {
	t.Helper()
	u := models.User{
		ID: uuid.New(), Username: "atk" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "ATK",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := database.DB.Create(&u).Error; err != nil {
		t.Fatalf("seed other user: %v", err)
	}
	return u
}

func (e *g17Env) rowFor(t *testing.T, sessionID uuid.UUID) models.Session {
	t.Helper()
	var s models.Session
	if err := database.DB.Where("id = ?", sessionID).First(&s).Error; err != nil {
		t.Fatalf("session %s: %v", sessionID, err)
	}
	return s
}

// ---------------------------------------------------------------- identity

// The cache is keyed ONLY by consumed_hash. request_id is identity checked
// after the lookup, so the same request_id used by two different sessions -
// and two different accounts - must not let either see the other's successor.
func TestGate17Attack_DuplicateRequestIDAcrossSessionsAndAccounts(t *testing.T) {
	e := gate17Setup(t)
	shared := uuid.New() // the SAME request_id everywhere

	// A second session on the same account, and one on a different account.
	sidB, refB := e.secondSession(t, e.user.ID, nil)
	other := e.otherUser(t)
	sidC, refC := e.secondSession(t, other.ID, nil)

	a := e.refreshWith(t, e.refresh, shared)
	b := e.refreshWith(t, refB, shared)
	c := e.refreshWith(t, refC, shared)
	for n, r := range map[string]refreshResult{"A": a, "B": b, "C": c} {
		if r.code != http.StatusOK {
			t.Fatalf("rotation %s failed: %d", n, r.code)
		}
	}
	if a.refresh == b.refresh || a.refresh == c.refresh || b.refresh == c.refresh {
		t.Fatalf("PROVEN BROKEN: distinct sessions received the same successor")
	}

	// Each replay must return that session's OWN successor, never a neighbour's.
	if ra := e.refreshWith(t, e.refresh, shared); ra.refresh != a.refresh {
		t.Errorf("PROVEN BROKEN: session A replay returned %q, expected its own successor", ra.refresh)
	}
	if rb := e.refreshWith(t, refB, shared); rb.refresh != b.refresh {
		t.Errorf("PROVEN BROKEN: session B replay crossed sessions")
	}
	if rc := e.refreshWith(t, refC, shared); rc.refresh != c.refresh {
		t.Errorf("PROVEN BROKEN: cross-ACCOUNT cache leak on a shared request_id")
	}

	// Exactly one rotation each; a shared request_id must not merge lineages.
	for _, sid := range []uuid.UUID{e.session, sidB, sidC} {
		if g := e.rowFor(t, sid).Generation; g != 1 {
			t.Errorf("session %s generation = %d, want exactly 1", sid, g)
		}
	}
}

// A ledger row moved to another session (or another request) must not open.
// This is what stops a database-write attacker handing one session's successor
// to a different one.
func TestGate17Attack_CacheCiphertextIsBoundToItsRow(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()
	if r := e.refreshWith(t, e.refresh, reqID); r.code != http.StatusOK {
		t.Fatalf("seed rotation: %d", r.code)
	}
	var cr models.ConsumedRefresh
	if err := database.DB.Where("session_id = ?", e.session).First(&cr).Error; err != nil {
		t.Fatalf("ledger: %v", err)
	}

	if _, ok := openResponseCache(e.cfg, cr.ConsumedHash, cr.SessionID, cr.RequestID,
		cr.ResponseCiphertext, cr.ResponseNonce); !ok {
		t.Fatal("precondition: the row must open with its own AAD")
	}
	for _, tc := range []struct {
		name    string
		hash    []byte
		session uuid.UUID
		request uuid.UUID
	}{
		{"moved to another session", cr.ConsumedHash, uuid.New(), cr.RequestID},
		{"replayed under another request_id", cr.ConsumedHash, cr.SessionID, uuid.New()},
		{"re-keyed to another consumed hash", make([]byte, 32), cr.SessionID, cr.RequestID},
	} {
		if _, ok := openResponseCache(e.cfg, tc.hash, tc.session, tc.request,
			cr.ResponseCiphertext, cr.ResponseNonce); ok {
			t.Errorf("PROVEN BROKEN: cached successor opened when %s", tc.name)
		}
	}
}

// ---------------------------------------------------------------- dominance

// Cached replay must die with the session, for every revoking event.
//
// Each case may first REPLACE the harness session (the device case needs one
// bound to a device, and device_id is immutable after creation). The shared
// body then seeds the rotation, proves the cache would replay, fires the event
// and asserts nothing survives - all against whichever session the case chose.
func TestGate17Attack_CachedReplayDiesWithEveryRevocationEvent(t *testing.T) {
	for _, tc := range []struct {
		name    string
		prepare func(t *testing.T, e *g17Env) // may swap e.session/e.refresh
		event   func(t *testing.T, e *g17Env)
	}{
		{
			name:    "logout_all over HTTP",
			prepare: func(t *testing.T, e *g17Env) {},
			event: func(t *testing.T, e *g17Env) {
				body, _ := json.Marshal(map[string]string{"scope": "all"})
				req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/logout",
					strings.NewReader(string(body)))
				req.Header.Set("Content-Type", "application/json")
				req.Header.Set("Authorization", "Bearer "+e.freshAccess(t))
				resp, err := e.app.Test(req, -1)
				if err != nil || resp.StatusCode != http.StatusNoContent {
					t.Fatalf("logout_all: %v status=%v", err, resp.StatusCode)
				}
			},
		},
		{
			name:    "password change (trigger path)",
			prepare: func(t *testing.T, e *g17Env) {},
			event: func(t *testing.T, e *g17Env) {
				if err := database.DB.Model(&models.User{}).Where("id = ?", e.user.ID).
					Update("password_hash", "ROTATED-"+uuid.New().String()).Error; err != nil {
					t.Fatalf("password change: %v", err)
				}
			},
		},
		{
			name: "device revocation",
			prepare: func(t *testing.T, e *g17Env) {
				now := time.Now()
				dev := models.E2EEDevice{ID: uuid.New(), UserID: e.user.ID,
					DeviceID:  "atk-" + uuid.New().String()[:6],
					CreatedAt: now, UpdatedAt: now}
				if err := database.DB.Create(&dev).Error; err != nil {
					t.Fatalf("seed device: %v", err)
				}
				bound, plain := e.secondSession(t, e.user.ID, &dev.ID)
				e.session, e.refresh, e.device = bound, plain, &dev.ID
			},
			event: func(t *testing.T, e *g17Env) {
				if err := database.DB.Transaction(func(tx *gorm.DB) error {
					_, err := revokeSessionsForDevice(tx, e.user.ID, *e.device, "device_revoke")
					return err
				}); err != nil {
					t.Fatalf("device revoke: %v", err)
				}
			},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := gate17Setup(t)
			tc.prepare(t, e)
			reqID := uuid.New()

			first := e.refreshWith(t, e.refresh, reqID)
			if first.code != http.StatusOK {
				t.Fatalf("seed rotation: %d", first.code)
			}
			// The cache is live and would replay right now.
			if w := e.refreshWith(t, e.refresh, reqID); w.refresh != first.refresh {
				t.Fatalf("precondition: in-window replay should work")
			}

			tc.event(t, e)

			if s := e.rowFor(t, e.session); s.RevokedAt == nil {
				t.Fatalf("precondition: the event must have revoked the session")
			}
			replay := e.refreshWith(t, e.refresh, reqID)
			if replay.code != http.StatusUnauthorized {
				t.Errorf("PROVEN BROKEN: cached replay survived %s (got %d)", tc.name, replay.code)
			}
			if replay.refresh != "" || replay.access != "" {
				t.Errorf("PROVEN BROKEN: %s left credentials recoverable from the cache", tc.name)
			}
			if c := e.refreshWith(t, first.refresh, uuid.New()); c.code != http.StatusUnauthorized {
				t.Errorf("PROVEN BROKEN: the successor still rotates after %s (%d)", tc.name, c.code)
			}
			var live int64
			database.DB.Model(&models.ConsumedRefresh{}).
				Where("session_id = ? AND response_ciphertext IS NOT NULL", e.session).Count(&live)
			if live != 0 {
				t.Errorf("%d decryptable cache row(s) survived %s", live, tc.name)
			}
		})
	}
}

// freshAccess mints an access token for the harness session via a real rotation.
func (e *g17Env) freshAccess(t *testing.T) string {
	t.Helper()
	var s models.Session
	if err := database.DB.Where("id = ?", e.session).First(&s).Error; err != nil {
		t.Fatalf("session: %v", err)
	}
	at, err := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.session, e.cfg)
	if err != nil {
		t.Fatalf("mint access: %v", err)
	}
	return at
}

// An expired cache entry must deny - and must not fall through into minting a
// brand new successor for a token that was already consumed.
func TestGate17Attack_ExpiredCacheCannotMintAFreshSuccessor(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()
	first := e.refreshWith(t, e.refresh, reqID)
	if first.code != http.StatusOK {
		t.Fatalf("seed rotation: %d", first.code)
	}
	gen := e.rowFor(t, e.session).Generation

	if err := database.DB.Exec(
		`UPDATE consumed_refresh SET response_expires_at = now() - interval '1 second'
		  WHERE session_id = ?`, e.session).Error; err != nil {
		t.Fatalf("age cache: %v", err)
	}

	after := e.refreshWith(t, e.refresh, reqID)
	if after.code != http.StatusUnauthorized {
		t.Fatalf("expired replay must be refused, got %d", after.code)
	}
	if after.refresh != "" {
		t.Errorf("PROVEN BROKEN: an expired cache entry produced a credential")
	}
	if g := e.rowFor(t, e.session).Generation; g != gen {
		t.Errorf("PROVEN BROKEN: an expired replay minted a fresh successor (generation %d -> %d)", gen, g)
	}
	if n := e.ledgerCount(t); n != 1 {
		t.Errorf("expired replay wrote a ledger row: %d", n)
	}
	if s := e.rowFor(t, e.session); s.RevokedAt != nil {
		t.Errorf("lateness is not an attack; the session must not be revoked")
	}
	// The successor itself must still be usable - expiry of the WINDOW must not
	// harm the credential the client may already hold.
	if c := e.refreshWith(t, first.refresh, uuid.New()); c.code != http.StatusOK {
		t.Errorf("the successor should still rotate after the window closes, got %d", c.code)
	}
}

// ---------------------------------------------------------------- races

// refresh vs device revocation, serialized by a real row lock.
func TestGate17Attack_RefreshVersusDeviceRevoke_Concurrent(t *testing.T) {
	e := gate17Setup(t)
	now := time.Now()
	dev := models.E2EEDevice{ID: uuid.New(), UserID: e.user.ID, DeviceID: "race-dev",
		CreatedAt: now, UpdatedAt: now}
	if err := database.DB.Create(&dev).Error; err != nil {
		t.Fatalf("seed device: %v", err)
	}
	// Bound at creation: device_id cannot be changed afterwards.
	bound, plain := e.secondSession(t, e.user.ID, &dev.ID)
	e.session, e.refresh = bound, plain

	barrier := newLockBarrier(t, e.session)
	var (
		res refreshResult
		wg  sync.WaitGroup
	)
	wg.Add(2)
	go func() { defer wg.Done(); res = e.refreshWith(t, e.refresh, uuid.New()) }()
	go func() {
		defer wg.Done()
		_ = database.DB.Transaction(func(tx *gorm.DB) error {
			_, err := revokeSessionsForDevice(tx, e.user.ID, dev.ID, "device_revoke")
			return err
		})
	}()
	time.Sleep(500 * time.Millisecond)
	barrier.lift()
	wg.Wait()

	s := e.rowFor(t, e.session)
	if s.RevokedAt == nil {
		t.Fatal("the device revocation must land regardless of ordering")
	}
	if s.RefreshHash != nil {
		t.Fatal("PROVEN BROKEN: a revoked session retains a usable refresh hash")
	}
	if res.code == http.StatusOK {
		if c := e.refreshWith(t, res.refresh, uuid.New()).code; c != http.StatusUnauthorized {
			t.Errorf("PROVEN BROKEN: a successor minted during the race outlived the revocation (%d)", c)
		}
	}
	reason := ""
	if s.RevokeReason != nil {
		reason = *s.RevokeReason
	}
	if reason == "refresh_reuse" {
		t.Errorf("PROVEN BROKEN: the race was misread as a fork")
	}
	t.Logf("interleaving: refresh=%d reason=%q", res.code, reason)
}
