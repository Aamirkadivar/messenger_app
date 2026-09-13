package handlers

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

// PHASE 60 - is `PUT /e2ee/vault` inside the device trust boundary, and can it be moved inside?
//
// Phase 44 put every archive and history-keyring route behind RequireDeviceIdentity, which resolves
// authorization through the session's BOUND device rather than the client-supplied X-Device-Id.
// Phase 57 observed that the vault write never got the same treatment. This suite establishes two
// things that have to be true together before that can be corrected:
//
//  1. the defect is real and reaches the mutation - an authenticated caller with no usable device
//     identity rewrites the account's wrapped master key; and
//  2. what happens to legitimate provisioning if the guard is simply attached.
//
// The second question is the one that decides the shape of the fix, because a brand-new account's
// session has no bound device until enrolment completes, and enrolment is not free-standing: it
// proves possession of the account identity key. On Windows that key is read from the vault
// (authservice.cpp: "The proof needs the identity PRIVATE key, which lives in the vault, so this is
// a no-op while the vault is locked"), so the very first vault write cannot present a verified
// device - there is nothing to verify with until that write lands.
//
// Everything below runs against an ISOLATED database named by TEST_DATABASE_URL. Nothing here
// touches a production host.
type p60Env struct {
	app       *fiber.App
	db        *gorm.DB
	user      uuid.UUID
	sessionID uuid.UUID
	deviceRow uuid.UUID
	deviceID  string
}

// p60Setup builds the real middleware chain from main.go around a chosen vault route shape.
//
// gated=false reproduces the tree as it stands; gated=true is the candidate fix, so the two can be
// compared on identical fixtures.
func p60Setup(t *testing.T, gated bool) *p60Env {
	t.Helper()
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

	e := &p60Env{db: db, user: uuid.New(), sessionID: uuid.New(), deviceID: "device-A"}
	now := time.Now()
	if err := db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, 'x', ?, ?)`,
		e.user, "u"+e.user.String()[:8], e.user.String()[:8]+"@t.local", now, now).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}

	e.app = fiber.New()
	// Stand-in for AuthMiddleware: the account is authenticated and the session is identified,
	// which is exactly the state an attacker holding a valid token is in.
	e.app.Use(func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{
			UserID:    e.user,
			SessionID: e.sessionID,
		})
		return c.Next()
	})
	e.app.Use(middleware.DeviceRevocationGuard()) // exactly main.go's group-level guard

	vh := NewE2EEHandler(nil)
	if gated {
		e.app.Put("/vault", middleware.RequireDeviceIdentity(), vh.PutVault)
	} else {
		e.app.Put("/vault", vh.PutVault)
	}
	return e
}

// seedSession creates the session row. boundDevice=true also enrols a verified device and binds the
// session to it, which is the state a client reaches only AFTER enrolment has succeeded.
func (e *p60Env) seedSession(t *testing.T, boundDevice bool, revoked bool) {
	t.Helper()
	now := time.Now()
	hash := make([]byte, 32)
	for i := range hash {
		hash[i] = byte(i)
	}
	s := models.Session{
		ID: e.sessionID, UserID: e.user, RefreshHash: hash, HashKeyVersion: 1,
		RefreshExpiresAt: now.Add(24 * time.Hour), AbsoluteExpiresAt: now.Add(96 * time.Hour),
		CreatedAt: now, UpdatedAt: now, LastUsedAt: now,
	}
	if boundDevice {
		e.deviceRow = uuid.New()
		d := models.E2EEDevice{
			ID: e.deviceRow, UserID: e.user, DeviceID: e.deviceID,
			VerifiedAt: &now, CreatedAt: now, UpdatedAt: now,
		}
		if revoked {
			d.RevokedAt = &now
		}
		if err := e.db.Create(&d).Error; err != nil {
			t.Fatalf("seed device: %v", err)
		}
		s.DeviceID = &e.deviceRow
	}
	if err := e.db.Create(&s).Error; err != nil {
		t.Fatalf("seed session: %v", err)
	}
}

func (e *p60Env) put(t *testing.T, deviceHeader string, sendHeader bool, expectedVersion int) int {
	t.Helper()
	req := httptest.NewRequest(http.MethodPut, "/vault",
		bytes.NewBufferString(gate11VaultBody(1, expectedVersion)))
	req.Header.Set("Content-Type", "application/json")
	if sendHeader {
		req.Header.Set("X-Device-Id", deviceHeader)
	}
	resp, err := e.app.Test(req, 5000)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	return resp.StatusCode
}

// vaultFingerprint is the state a denial must leave untouched: the wrapped master key and the
// version, which together are what an attacker would be rewriting.
func (e *p60Env) vaultFingerprint(t *testing.T) string {
	t.Helper()
	var v models.E2EEVault
	err := e.db.Where("user_id = ?", e.user).First(&v).Error
	if err != nil {
		return "<absent>"
	}
	return fmt.Sprintf("v=%d wrap=%q ct=%q", v.VaultVersion, string(v.PwWrappedMaster), string(v.VaultCiphertext))
}

// ============================================================ the defect, as the tree stands

// An authenticated caller with a REVOKED device rewrites the vault simply by omitting the header.
//
// This is the Phase 43 pattern surviving on one route: the group guard only refuses a device the
// client names, and PutVault itself never asks who the device is.
func TestP60_RevokedDeviceMutatesVaultByOmittingTheHeader(t *testing.T) {
	e := p60Setup(t, false)
	e.seedSession(t, true, true) // bound, but revoked

	before := e.vaultFingerprint(t)
	code := e.put(t, "", false, 0)
	after := e.vaultFingerprint(t)

	t.Logf("UNGATED revoked+no-header: status=%d before=%s after=%s", code, before, after)
	if before == after {
		t.Fatalf("expected the vault to have been mutated, but it did not change (status %d)", code)
	}
	if code >= 400 {
		t.Fatalf("expected the write to be accepted, got %d", code)
	}
}

// The same request against a device-gated route is refused and mutates nothing.
func TestP60_GatingTheRouteRefusesTheRevokedDeviceAndMutatesNothing(t *testing.T) {
	e := p60Setup(t, true)
	e.seedSession(t, true, true)

	before := e.vaultFingerprint(t)
	code := e.put(t, "", false, 0)
	after := e.vaultFingerprint(t)

	if code < 400 {
		t.Fatalf("a revoked device must be refused, got %d", code)
	}
	if before != after {
		t.Fatalf("a denied request mutated vault state: %s -> %s", before, after)
	}
	t.Logf("GATED revoked+no-header: status=%d state unchanged (%s)", code, after)
}

// ============================================================ the adversarial matrix

// Each row asserts BOTH the denial and that nothing was written.
func TestP60_AdversarialMatrixAgainstAGatedRoute(t *testing.T) {
	type row struct {
		name         string
		boundDevice  bool
		revoked      bool
		header       string
		sendHeader   bool
		otherAccount bool
	}
	rows := []row{
		{name: "B revoked device", boundDevice: true, revoked: true, header: "device-A", sendHeader: true},
		{name: "C revoked + invented id", boundDevice: true, revoked: true, header: "device-INVENTED", sendHeader: true},
		{name: "D unknown device", boundDevice: false, header: "device-UNKNOWN", sendHeader: true},
		{name: "E session/device mismatch", boundDevice: true, header: "device-B", sendHeader: true},
		// The real cross-account case: this session has NO device of its own, and the header names
		// a verified device belonging to somebody else. Adopting it would let one account borrow
		// another's enrolment. (A session bound to its OWN device that also matches the header is
		// the legitimate owner, not an attacker, so that shape is covered by E instead.)
		{name: "F another account's device", boundDevice: false, header: "device-OTHER", sendHeader: true, otherAccount: true},
		{name: "G missing device identity", boundDevice: true, sendHeader: false},
		{name: "H overlong identity", boundDevice: true, header: string(make([]byte, 512)), sendHeader: true},
	}

	for _, r := range rows {
		t.Run(r.name, func(t *testing.T) {
			e := p60Setup(t, true)
			e.seedSession(t, r.boundDevice, r.revoked)

			if r.otherAccount {
				// A device row that belongs to somebody else entirely.
				other := uuid.New()
				now := time.Now()
				e.db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
					VALUES (?, ?, ?, 'x', ?, ?)`,
					other, "o"+other.String()[:8], other.String()[:8]+"@t.local", now, now)
				e.db.Create(&models.E2EEDevice{
					ID: uuid.New(), UserID: other, DeviceID: "device-OTHER",
					VerifiedAt: &now, CreatedAt: now, UpdatedAt: now,
				})
			}

			before := e.vaultFingerprint(t)
			code := e.put(t, r.header, r.sendHeader, 0)
			after := e.vaultFingerprint(t)

			if code < 400 {
				t.Fatalf("%s: expected denial, got %d", r.name, code)
			}
			if before != after {
				t.Fatalf("%s: DENIED (%d) but vault state changed: %s -> %s", r.name, code, before, after)
			}
			// Nothing may be auto-registered by a rejected attempt either.
			var devices int64
			e.db.Model(&models.E2EEDevice{}).Where("user_id = ? AND device_id = ?", e.user, r.header).Count(&devices)
			if !r.boundDevice && devices != 0 {
				t.Fatalf("%s: a denied request registered a device", r.name)
			}
			t.Logf("%-32s status=%d state unchanged", r.name, code)
		})
	}
}

// A: the legitimate case must keep working once the route is gated.
func TestP60_ValidEnrolledDeviceStillWrites(t *testing.T) {
	e := p60Setup(t, true)
	e.seedSession(t, true, false)

	before := e.vaultFingerprint(t)
	code := e.put(t, e.deviceID, true, 0)
	after := e.vaultFingerprint(t)

	if code >= 400 {
		t.Fatalf("a valid enrolled device must still write, got %d", code)
	}
	if before == after {
		t.Fatalf("the write was accepted (%d) but nothing changed", code)
	}
	t.Logf("valid enrolled device: status=%d %s -> %s", code, before, after)
}

// ============================================================ the provisioning question

// THE BLOCKER. A brand-new account has no bound device, because binding happens during enrolment -
// and enrolment proves possession of the identity key that the first vault write is what creates.
//
// So gating the route unconditionally refuses the ONLY request that could ever make enrolment
// possible. This test states that plainly rather than leaving it to be discovered in the field.
func TestP60_GatingBreaksFirstVaultProvisioning(t *testing.T) {
	e := p60Setup(t, true)
	e.seedSession(t, false, false) // authenticated, session exists, NO device bound yet

	before := e.vaultFingerprint(t)
	code := e.put(t, "device-A", true, 0)
	after := e.vaultFingerprint(t)

	if code < 400 {
		t.Fatalf("expected the gated route to refuse an unbound session, got %d", code)
	}
	if before != "<absent>" || after != "<absent>" {
		t.Fatalf("expected no vault to exist before or after, got %s -> %s", before, after)
	}
	t.Logf("PROVISIONING DEADLOCK: a fresh account's first vault write is refused with %d "+
		"(session has no bound device, and enrolment cannot run until the vault exists)", code)
}

// The same request on the ungated route succeeds, which is how provisioning works today.
func TestP60_UngatedRouteAllowsFirstVaultProvisioning(t *testing.T) {
	e := p60Setup(t, false)
	e.seedSession(t, false, false)

	code := e.put(t, "device-A", true, 0)
	after := e.vaultFingerprint(t)
	if code >= 400 {
		t.Fatalf("provisioning must work on the current route, got %d", code)
	}
	if after == "<absent>" {
		t.Fatalf("provisioning reported %d but stored nothing", code)
	}
	t.Logf("current tree: first vault write succeeds with %d", code)
}
