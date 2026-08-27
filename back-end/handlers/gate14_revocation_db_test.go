package handlers

import (
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/models"
)

// ============================= GATE 14: I11 — revocation is terminal
//
// I11: once a device is REVOKED, the server retains authoritative evidence of
// that revocation, and no ordinary authenticated operation can erase it or move
// the device back to ACTIVE.
//
// This is deliberately tested UNDER the broken Gate 11 identity layer: the
// attacker is allowed to send any X-Device-Id it likes, because device identity
// is still self-asserted. I11 must hold anyway.

// deleteInterleaver suspends the first DELETE against a table, so a racing
// actor can commit inside the window between a deletion's check and its write.
type deleteInterleaver struct {
	mu      sync.Mutex
	table   string
	atWrite chan struct{}
	release chan struct{}
	fired   bool
}

func newDeleteInterleaver(t *testing.T, table string) *deleteInterleaver {
	t.Helper()
	iv := &deleteInterleaver{
		table:   table,
		atWrite: make(chan struct{}),
		release: make(chan struct{}),
	}
	name := "gate14:suspend-delete:" + table
	err := database.DB.Callback().Delete().Before("gorm:delete").Register(name, func(d *gorm.DB) {
		if d.Statement == nil || d.Statement.Table != iv.table {
			return
		}
		iv.mu.Lock()
		first := !iv.fired
		if first {
			iv.fired = true
		}
		iv.mu.Unlock()
		if !first {
			return
		}
		close(iv.atWrite)
		<-iv.release
	})
	if err != nil {
		t.Fatalf("register delete callback: %v", err)
	}
	t.Cleanup(func() { _ = database.DB.Callback().Delete().Remove(name) })
	return iv
}

func (iv *deleteInterleaver) waitAtWrite(t *testing.T) {
	t.Helper()
	select {
	case <-iv.atWrite:
	case <-time.After(15 * time.Second):
		t.Fatal("the deleting actor never reached its write")
	}
}

func (iv *deleteInterleaver) resume() { close(iv.release) }

// gate14Devices mounts the full device lifecycle without DeviceRevocationGuard,
// so the interleavings under test are the handlers' own, not the guard's.
func gate14Devices(userID uuid.UUID) (*fiber.App, *E2EEHandler) {
	app, h := gate13App(userID)
	app.Post("/devices", h.RegisterDevice)
	app.Get("/devices", h.ListDevices)
	app.Post("/devices/:device_id/revoke", h.RevokeDevice)
	app.Delete("/devices/:device_id", h.DeleteDevice)
	return app, h
}

func deviceRow(t *testing.T, user uuid.UUID, deviceID string) (models.E2EEDevice, bool) {
	t.Helper()
	var d models.E2EEDevice
	tx := database.DB.Where("user_id = ? AND device_id = ?", user, deviceID).Limit(1).Find(&d)
	return d, tx.RowsAffected > 0
}

// -------------------------------------------------- PHASE 7: tombstone permanence
//
// The full sequence an attacker holding a still-valid credential would run.
// The assertion is not "the endpoint returned 403" but "authoritative evidence
// of the revocation is still in the database at the end".
func TestGate14_I11_RevocationEvidenceSurvivesEveryOrdinaryOperation(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "device-tombstone"

	if code := hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev)); code != http.StatusCreated {
		t.Fatalf("setup: register expected 201, got %d", code)
	}
	if code := hit(t, app, http.MethodPost, "/devices/"+dev+"/revoke", ""); code != http.StatusOK {
		t.Fatalf("setup: revoke expected 200, got %d", code)
	}
	before, ok := deviceRow(t, e.userA, dev)
	if !ok || before.RevokedAt == nil {
		t.Fatal("setup: device must be revoked")
	}
	revokedAt := *before.RevokedAt

	steps := []struct {
		name, method, path, body string
		wantCode                 int
	}{
		{"DELETE the tombstone", http.MethodDelete, "/devices/" + dev, "", http.StatusForbidden},
		{"REGISTER the same id", http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev), http.StatusForbidden},
		{"REGISTER with new metadata", http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q,"name":"renamed","platform":"ios","public_key":"NEW"}`, dev), http.StatusForbidden},
		{"DELETE again", http.MethodDelete, "/devices/" + dev, "", http.StatusForbidden},
	}
	for _, s := range steps {
		code := hit(t, app, s.method, s.path, s.body)
		if code != s.wantCode {
			t.Errorf("%s: expected %d, got %d", s.name, s.wantCode, code)
		}
		row, present := deviceRow(t, e.userA, dev)
		if !present {
			t.Fatalf("I11 VIOLATED: %s erased the device row entirely", s.name)
		}
		if row.RevokedAt == nil {
			t.Fatalf("I11 VIOLATED: %s cleared revoked_at", s.name)
		}
		if !row.RevokedAt.Equal(revokedAt) {
			t.Errorf("%s moved revoked_at (%v -> %v)", s.name, revokedAt, *row.RevokedAt)
		}
		t.Logf("  %-28s -> %d, tombstone intact", s.name, code)
	}

	// Registering a DIFFERENT id is still possible: that is the Gate 11
	// re-enrolment defect, deliberately unresolved. It must not, however,
	// disturb this device's tombstone.
	if code := hit(t, app, http.MethodPost, "/devices", `{"device_id":"device-tombstone-2"}`); code != http.StatusCreated {
		t.Logf("  note: new-id registration returned %d", code)
	}
	row, present := deviceRow(t, e.userA, dev)
	if !present || row.RevokedAt == nil {
		t.Fatal("I11 VIOLATED: registering a new device id disturbed the tombstone")
	}
	t.Logf("I11 HELD: revocation evidence survived every ordinary operation")
}

// An ACTIVE device can still be deleted — removing a device from the list is a
// legitimate operation and is NOT the same act as erasing revocation evidence.
func TestGate14_DeleteDeviceSemantics(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)

	t.Run("active device can still be deleted", func(t *testing.T) {
		hit(t, app, http.MethodPost, "/devices", `{"device_id":"del-active"}`)
		if code := hit(t, app, http.MethodDelete, "/devices/del-active", ""); code != http.StatusOK {
			t.Errorf("expected 200 deleting an active device, got %d", code)
		}
		if _, ok := deviceRow(t, e.userA, "del-active"); ok {
			t.Error("active device row should be gone")
		}
	})

	t.Run("a never-revoked deleted device may register again", func(t *testing.T) {
		code := hit(t, app, http.MethodPost, "/devices", `{"device_id":"del-active"}`)
		if code != http.StatusCreated {
			t.Errorf("expected 201 re-registering a never-revoked id, got %d", code)
		}
	})

	t.Run("unknown device is 404", func(t *testing.T) {
		if code := hit(t, app, http.MethodDelete, "/devices/never-existed", ""); code != http.StatusNotFound {
			t.Errorf("expected 404, got %d", code)
		}
	})

	t.Run("revoked device is 403 and not 404", func(t *testing.T) {
		hit(t, app, http.MethodPost, "/devices", `{"device_id":"del-revoked"}`)
		hit(t, app, http.MethodPost, "/devices/del-revoked/revoke", "")
		code := hit(t, app, http.MethodDelete, "/devices/del-revoked", "")
		if code != http.StatusForbidden {
			t.Errorf("expected 403 for a revoked device, got %d", code)
		}
	})
}

// -------------------------------------------------- PHASE 6: concurrency matrix

// Race B — revoke vs delete. The deletion has read an ACTIVE row and is about
// to remove it when the revocation commits.
func TestGate14_RaceB_RevokeVsDelete(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "race-b"
	hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev))

	iv := newDeleteInterleaver(t, "e2_ee_devices")
	var delCode, revCode int
	delDone := make(chan struct{})
	go func() {
		defer close(delDone)
		delCode = hit(t, app, http.MethodDelete, "/devices/"+dev, "")
	}()

	iv.waitAtWrite(t)
	revDone := make(chan struct{})
	go func() {
		defer close(revDone)
		revCode = hit(t, app, http.MethodPost, "/devices/"+dev+"/revoke", "")
	}()
	time.Sleep(300 * time.Millisecond)
	iv.resume()
	<-delDone
	<-revDone

	row, present := deviceRow(t, e.userA, dev)
	if revCode == http.StatusOK {
		if !present {
			t.Fatalf("I11 VIOLATED: revoke succeeded (200) but a concurrent delete "+
				"(status %d) removed the row, erasing the tombstone", delCode)
		}
		if row.RevokedAt == nil {
			t.Fatalf("I11 VIOLATED: revoke succeeded but revoked_at is NULL (delete=%d)", delCode)
		}
		t.Logf("race B: revoke=%d delete=%d -> tombstone intact", revCode, delCode)
		return
	}
	// The delete won. That is legal only because the device never reached
	// REVOKED — there is no evidence to preserve. What must NOT happen is the
	// revoker being told it succeeded while the row silently vanished, so the
	// losing revoke has to fail closed and say so.
	if present {
		t.Fatalf("incoherent: delete reported %d but the row is still present", delCode)
	}
	if revCode == http.StatusOK {
		t.Fatalf("I11 VIOLATED: revoke reported success (200) but no row survives to "+
			"record it (delete=%d). A revocation must never be silently discarded.", delCode)
	}
	if revCode != http.StatusNotFound {
		t.Fatalf("the losing revoke must fail closed with 404, got %d", revCode)
	}
	t.Logf("race B: delete won (delete=%d), losing revoke failed closed with %d; "+
		"device never reached REVOKED so no evidence was lost", delCode, revCode)
}

// Race D — register vs delete against the same active row.
func TestGate14_RaceD_RegisterVsDelete(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "race-d"
	hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev))

	iv := newInterleaver(t, "e2_ee_devices") // suspends the registration UPDATE
	var regCode, delCode int
	regDone := make(chan struct{})
	go func() {
		defer close(regDone)
		regCode = hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q,"name":"x"}`, dev))
	}()
	iv.waitAtWrite(t)
	delDone := make(chan struct{})
	go func() {
		defer close(delDone)
		delCode = hit(t, app, http.MethodDelete, "/devices/"+dev, "")
	}()
	time.Sleep(300 * time.Millisecond)
	iv.resume()
	<-regDone
	<-delDone

	// Neither outcome may leave a revoked row resurrected; this device was never
	// revoked, so the only requirement is that the result is coherent.
	row, present := deviceRow(t, e.userA, dev)
	if present && row.RevokedAt != nil {
		t.Fatal("device was never revoked yet has a revoked_at")
	}
	if regCode >= 500 || delCode >= 500 {
		t.Fatalf("contention surfaced as 5xx: register=%d delete=%d", regCode, delCode)
	}
	t.Logf("race D: register=%d delete=%d present=%v", regCode, delCode, present)
}

// Race F — concurrent revokes must be idempotent and must not error.
func TestGate14_RaceF_ConcurrentRevokes(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "race-f"
	hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev))

	var wg sync.WaitGroup
	codes := make(chan int, 16)
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes <- hit(t, app, http.MethodPost, "/devices/"+dev+"/revoke", "")
		}()
	}
	wg.Wait()
	close(codes)
	for code := range codes {
		if code != http.StatusOK {
			t.Errorf("concurrent revoke returned %d, expected 200", code)
		}
	}
	row, present := deviceRow(t, e.userA, dev)
	if !present || row.RevokedAt == nil {
		t.Fatal("I11 VIOLATED: concurrent revokes left the device unrevoked")
	}
	t.Logf("race F: 16 concurrent revokes, device revoked, no errors")
}

// Race G — concurrent registrations of a brand-new id must not create duplicates.
func TestGate14_RaceG_ConcurrentRegistrations(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "race-g"

	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev))
		}()
	}
	wg.Wait()

	var n int64
	database.DB.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", e.userA, dev).Count(&n)
	if n != 1 {
		t.Fatalf("expected exactly one device row, got %d", n)
	}
	t.Logf("race G: 12 concurrent registrations produced exactly 1 row")
}

// Race H — revoke, then immediately every device mutation, all must fail closed.
func TestGate14_RaceH_MutationsImmediatelyAfterRevoke(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "race-h"
	hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev))
	hit(t, app, http.MethodPost, "/devices/"+dev+"/revoke", "")

	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q,"name":"n"}`, dev))
			hit(t, app, http.MethodDelete, "/devices/"+dev, "")
		}()
	}
	wg.Wait()

	row, present := deviceRow(t, e.userA, dev)
	if !present {
		t.Fatal("I11 VIOLATED: concurrent mutations erased the tombstone")
	}
	if row.RevokedAt == nil {
		t.Fatal("I11 VIOLATED: concurrent mutations cleared revoked_at")
	}
	t.Logf("race H: 20 post-revocation mutations, tombstone intact")
}

// -------------------------------------------------- PHASE 8: refresh interaction
//
// This CHARACTERIZES the boundary; it does not fix it. I11 is about the
// tombstone, not about credentials. A revoked device keeps a usable JWT because
// the session layer is a Gate 15 problem that remains deliberately unresolved.
func TestGate14_RevocationDoesNotInvalidateCredentials(t *testing.T) {
	e := gate11Setup(t)
	app, _ := gate14Devices(e.userA)
	const dev = "refresh-dev"
	hit(t, app, http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev))
	hit(t, app, http.MethodPost, "/devices/"+dev+"/revoke", "")

	// The same authenticated identity still reaches device routes and can still
	// enrol a different device id. Documented, not fixed.
	listCode := hit(t, app, http.MethodGet, "/devices", "")
	newCode := hit(t, app, http.MethodPost, "/devices", `{"device_id":"refresh-dev-other"}`)
	t.Logf("after revocation, the SAME credential: list=%d, register-new-id=%d", listCode, newCode)

	if listCode != http.StatusOK {
		t.Logf("note: list returned %d", listCode)
	}
	// What I11 does guarantee, even here:
	row, present := deviceRow(t, e.userA, dev)
	if !present || row.RevokedAt == nil {
		t.Fatal("I11 VIOLATED: the revoked device's tombstone did not survive")
	}
	t.Log("CHARACTERIZED: credentials remain valid after revocation (Gate 15 scope); " +
		"the tombstone is nonetheless permanent (I11 holds)")
}
