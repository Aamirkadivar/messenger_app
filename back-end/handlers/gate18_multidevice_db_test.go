package handlers

import (
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/models"
)

// ===================================== GATE 18: multi-device session model
//
//	User
//	  |- Device A -> Session A1, Session A2
//	  |- Device B -> Session B1
//	  \- (no device) -> Session W          <- future web
//
// Sessions are the credential boundary; devices are the grouping boundary.
// Deleting a device is a revocation event, never a detachment of a live session.

func g18Device(t *testing.T, userID uuid.UUID, name string) models.E2EEDevice {
	t.Helper()
	now := time.Now()
	d := models.E2EEDevice{ID: uuid.New(), UserID: userID, DeviceID: name,
		CreatedAt: now, UpdatedAt: now}
	if err := database.DB.Create(&d).Error; err != nil {
		t.Fatalf("seed device %s: %v", name, err)
	}
	return d
}

// deleteDevice drives the REAL DeleteDevice handler over HTTP, as the account
// named by userID. Replicating the transaction in the test instead would leave
// the production handler unexercised - mutation testing caught exactly that.
func deleteDevice(t *testing.T, userID uuid.UUID, deviceName string) int {
	t.Helper()
	app, _ := gate14Devices(userID)
	return hit(t, app, http.MethodDelete, "/devices/"+deviceName, "")
}

func deviceExists(t *testing.T, id uuid.UUID) bool {
	t.Helper()
	var n int64
	database.DB.Model(&models.E2EEDevice{}).Where("id = ?", id).Count(&n)
	return n > 0
}

// ------------------------------------------------- 1,3,4: deletion & isolation

func TestGate18_DeleteDeviceRevokesItsSessionsAndSparesOthers(t *testing.T) {
	e := gate17Setup(t)
	devA := g18Device(t, e.user.ID, "phone-a")
	devB := g18Device(t, e.user.ID, "desktop-b")
	devC := g18Device(t, e.user.ID, "tablet-c")

	a1, refA1 := e.secondSession(t, e.user.ID, &devA.ID)
	a2, refA2 := e.secondSession(t, e.user.ID, &devA.ID) // two sessions, one device
	b1, refB1 := e.secondSession(t, e.user.ID, &devB.ID)
	c1, refC1 := e.secondSession(t, e.user.ID, &devC.ID)
	w, refW := e.secondSession(t, e.user.ID, nil) // web-style, no device

	// Give A1 a live lost-response cache entry to try to resurrect later.
	reqID := uuid.New()
	seeded := e.refreshWith(t, refA1, reqID)
	if seeded.code != http.StatusOK {
		t.Fatalf("seed rotation on A1: %d", seeded.code)
	}
	if w2 := e.refreshWith(t, refA1, reqID); w2.refresh != seeded.refresh {
		t.Fatalf("precondition: A1 replay should be live")
	}

	if code := deleteDevice(t, e.user.ID, "phone-a"); code != http.StatusOK {
		t.Fatalf("DeleteDevice(A) returned %d, want 200", code)
	}

	// Device A is gone; the others remain.
	if deviceExists(t, devA.ID) {
		t.Errorf("device A row should be gone")
	}
	if !deviceExists(t, devB.ID) || !deviceExists(t, devC.ID) {
		t.Errorf("unrelated devices must survive")
	}

	// Both of A's sessions are dead, credentials destroyed, cache purged.
	for name, sid := range map[string]uuid.UUID{"A1": a1, "A2": a2} {
		s := e.rowFor(t, sid)
		if s.RevokedAt == nil {
			t.Errorf("PROVEN BROKEN: %s survived deletion of its device", name)
		}
		if s.RefreshHash != nil {
			t.Errorf("PROVEN BROKEN: %s kept a usable refresh hash", name)
		}
		if s.DeviceID != nil {
			t.Errorf("%s should have been detached by the FK once revoked", name)
		}
	}
	if r := e.refreshWith(t, seeded.refresh, uuid.New()); r.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: A1 successor still rotates after device deletion (%d)", r.code)
	}
	if r := e.refreshWith(t, refA2, uuid.New()); r.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: A2 still rotates after device deletion (%d)", r.code)
	}
	// Lost-response replay must not resurrect it either.
	replay := e.refreshWith(t, refA1, reqID)
	if replay.code != http.StatusUnauthorized || replay.refresh != "" || replay.access != "" {
		t.Errorf("PROVEN BROKEN: cached successor resurrected after device deletion (%d)", replay.code)
	}
	var liveCache int64
	database.DB.Model(&models.ConsumedRefresh{}).
		Where("session_id = ? AND response_ciphertext IS NOT NULL", a1).Count(&liveCache)
	if liveCache != 0 {
		t.Errorf("%d decryptable cache row(s) survived device deletion", liveCache)
	}
	// Fork evidence is retained.
	var evidence int64
	database.DB.Model(&models.ConsumedRefresh{}).Where("session_id = ?", a1).Count(&evidence)
	if evidence == 0 {
		t.Errorf("fork evidence was destroyed by device deletion")
	}

	// Every unrelated session is untouched and still rotates.
	for name, ref := range map[string]string{"B1": refB1, "C1": refC1, "W": refW} {
		if r := e.refreshWith(t, ref, uuid.New()); r.code != http.StatusOK {
			t.Errorf("PROVEN BROKEN: %s was invalidated by deleting device A (%d)", name, r.code)
		}
	}
	for name, sid := range map[string]uuid.UUID{"B1": b1, "C1": c1, "W": w} {
		if e.rowFor(t, sid).RevokedAt != nil {
			t.Errorf("PROVEN BROKEN: %s was revoked by deleting device A", name)
		}
	}
}

// ------------------------------------------------- 2: re-registration

func TestGate18_ReRegisteredDeviceInheritsNothing(t *testing.T) {
	e := gate17Setup(t)
	devA := g18Device(t, e.user.ID, "reused-id")
	old, oldRef := e.secondSession(t, e.user.ID, &devA.ID)

	if code := deleteDevice(t, e.user.ID, "reused-id"); code != http.StatusOK {
		t.Fatalf("delete returned %d, want 200", code)
	}
	// Gate 14 semantics: a never-revoked deleted device id may be registered
	// again, and must come back as a genuinely new row.
	fresh := g18Device(t, e.user.ID, "reused-id")
	if fresh.ID == devA.ID {
		t.Fatalf("re-registration must produce a new device row id")
	}

	var n int64
	database.DB.Model(&models.Session{}).Where("device_id = ?", fresh.ID).Count(&n)
	if n != 0 {
		t.Errorf("PROVEN BROKEN: re-registered device inherited %d session(s)", n)
	}
	if r := e.refreshWith(t, oldRef, uuid.New()); r.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: the old credential works against the new device (%d)", r.code)
	}
	if e.rowFor(t, old).RevokedAt == nil {
		t.Errorf("the old session must remain revoked")
	}
}

// ------------------------------------------------- 5: cross-account

func TestGate18_CrossAccountDeviceDeletionIsImpossible(t *testing.T) {
	e := gate17Setup(t)
	victim := e.otherUser(t)
	vDev := g18Device(t, victim.ID, "victim-phone")
	vSid, vRef := e.secondSession(t, victim.ID, &vDev.ID)

	// The attacker asks to delete a device NAME they do not own.
	if code := deleteDevice(t, e.user.ID, "victim-phone"); code == http.StatusOK {
		t.Errorf("PROVEN BROKEN: account A deleted account B's device (200)")
	} else {
		t.Logf("attacker delete refused with %d", code)
	}
	if !deviceExists(t, vDev.ID) {
		t.Errorf("PROVEN BROKEN: account A deleted account B's device")
	}
	if e.rowFor(t, vSid).RevokedAt != nil {
		t.Errorf("PROVEN BROKEN: account A revoked account B's session")
	}
	if r := e.refreshWith(t, vRef, uuid.New()); r.code != http.StatusOK {
		t.Errorf("PROVEN BROKEN: the victim session stopped working (%d)", r.code)
	}
}

// The revocation primitive is account-scoped in its own right.
//
// DeleteDevice/RevokeDevice already resolve the device by (user_id, device_id)
// and 404 before reaching this, so the predicate below is defence in depth -
// but it is the layer that would save a future caller that forgets the
// ownership check, so it is asserted directly rather than assumed.
func TestGate18_DeviceRevocationPrimitiveIsAccountScoped(t *testing.T) {
	e := gate17Setup(t)
	victim := e.otherUser(t)
	vDev := g18Device(t, victim.ID, "primitive-victim")
	vSid, vRef := e.secondSession(t, victim.ID, &vDev.ID)

	// Attacker's user id paired with the victim's device row id.
	var n int64
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		var err error
		n, err = revokeSessionsForDevice(tx, e.user.ID, vDev.ID, "device_revoke")
		return err
	}); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if n != 0 {
		t.Errorf("PROVEN BROKEN: the primitive revoked %d session(s) across accounts", n)
	}
	if e.rowFor(t, vSid).RevokedAt != nil {
		t.Errorf("PROVEN BROKEN: another account's session was revoked")
	}
	if r := e.refreshWith(t, vRef, uuid.New()); r.code != http.StatusOK {
		t.Errorf("the victim session must still rotate (%d)", r.code)
	}
}

// ------------------------------------------------- 11: immutability

func TestGate18_LiveSessionDeviceBindingIsImmutable(t *testing.T) {
	e := gate17Setup(t)
	devA := g18Device(t, e.user.ID, "imm-a")
	devB := g18Device(t, e.user.ID, "imm-b")
	live, _ := e.secondSession(t, e.user.ID, &devA.ID)

	if err := database.DB.Exec(`UPDATE sessions SET device_id = NULL WHERE id = ?`, live).Error; err == nil {
		t.Errorf("PROVEN BROKEN: a LIVE session was detached from its device")
	}
	if err := database.DB.Exec(`UPDATE sessions SET device_id = ? WHERE id = ?`,
		devB.ID, live).Error; err == nil {
		t.Errorf("PROVEN BROKEN: a LIVE session was rebound to another device")
	}
	// A no-op write of the same value stays legal - it is not a transition.
	if err := database.DB.Exec(`UPDATE sessions SET device_id = ? WHERE id = ?`,
		devA.ID, live).Error; err != nil {
		t.Errorf("a same-value write should not be rejected: %v", err)
	}

	// Deleting a device that still has a LIVE session must FAIL CLOSED.
	if err := database.DB.Where("id = ?", devA.ID).Delete(&models.E2EEDevice{}).Error; err == nil {
		t.Errorf("PROVEN BROKEN: a device with a LIVE session was deleted")
	} else if !deviceExists(t, devA.ID) {
		t.Errorf("PROVEN BROKEN: the device row vanished despite the error")
	}

	// Once revoked, the FK may null the pointer - but never rebind it.
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		_, err := revokeOneSession(tx, e.user.ID, live, "logout")
		return err
	}); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if err := database.DB.Exec(`UPDATE sessions SET device_id = ? WHERE id = ?`,
		devB.ID, live).Error; err == nil {
		t.Errorf("PROVEN BROKEN: a REVOKED session was rebound to another device")
	}
	if err := database.DB.Where("id = ?", devA.ID).Delete(&models.E2EEDevice{}).Error; err != nil {
		t.Errorf("deleting a device whose sessions are revoked should succeed: %v", err)
	}
	if s := e.rowFor(t, live); s.DeviceID != nil {
		t.Errorf("the revoked session should have been detached by the FK")
	}
}

// ------------------------------------------------- 10: account-wide events

func TestGate18_PasswordChangeKillsEveryDeviceSession(t *testing.T) {
	e := gate17Setup(t)
	devA := g18Device(t, e.user.ID, "pw-a")
	devB := g18Device(t, e.user.ID, "pw-b")
	a1, refA1 := e.secondSession(t, e.user.ID, &devA.ID)
	b1, _ := e.secondSession(t, e.user.ID, &devB.ID)
	w, _ := e.secondSession(t, e.user.ID, nil)

	reqID := uuid.New()
	if r := e.refreshWith(t, refA1, reqID); r.code != http.StatusOK {
		t.Fatalf("seed: %d", r.code)
	}

	if err := database.DB.Model(&models.User{}).Where("id = ?", e.user.ID).
		Update("password_hash", "NEW-"+uuid.New().String()).Error; err != nil {
		t.Fatalf("password change: %v", err)
	}

	for name, sid := range map[string]uuid.UUID{"A1": a1, "B1": b1, "W": w} {
		s := e.rowFor(t, sid)
		if s.RevokedAt == nil {
			t.Errorf("PROVEN BROKEN: %s survived an account-wide password change", name)
		}
		if s.DeviceID == nil && sid != w {
			t.Errorf("%s should keep its device association: revocation is not detachment", name)
		}
	}
	if r := e.refreshWith(t, refA1, reqID); r.code != http.StatusUnauthorized {
		t.Errorf("PROVEN BROKEN: cached replay survived a password change (%d)", r.code)
	}
}

// ------------------------------------------------- 8,9: races

// refresh vs DeleteDevice, serialized by a real row lock. PostgreSQL picks the
// winner; both orderings must leave a safe state.
func TestGate18_RefreshVersusDeleteDevice_Concurrent(t *testing.T) {
	e := gate17Setup(t)
	dev := g18Device(t, e.user.ID, "race-del")
	sid, ref := e.secondSession(t, e.user.ID, &dev.ID)
	e.session, e.refresh = sid, ref

	barrier := newLockBarrier(t, sid)
	var (
		res     refreshResult
		delCode int
		wg      sync.WaitGroup
	)
	wg.Add(2)
	go func() { defer wg.Done(); res = e.refreshWith(t, ref, uuid.New()) }()
	go func() { defer wg.Done(); delCode = deleteDevice(t, e.user.ID, "race-del") }()
	time.Sleep(500 * time.Millisecond)
	barrier.lift()
	wg.Wait()

	s := e.rowFor(t, sid)
	t.Logf("interleaving: refresh=%d deleteHTTP=%d revoked=%v deviceGone=%v",
		res.code, delCode, s.RevokedAt != nil, !deviceExists(t, dev.ID))

	// Whatever the order, these must hold.
	if !deviceExists(t, dev.ID) {
		// Deletion won (or followed the refresh): nothing usable may remain.
		if s.RevokedAt == nil {
			t.Errorf("PROVEN BROKEN: device deleted but its session is still live")
		}
		if s.RefreshHash != nil {
			t.Errorf("PROVEN BROKEN: device deleted but the session kept a usable hash")
		}
		if res.code == http.StatusOK {
			if c := e.refreshWith(t, res.refresh, uuid.New()).code; c != http.StatusUnauthorized {
				t.Errorf("PROVEN BROKEN: credential minted during the race outlived the deletion (%d)", c)
			}
		}
	} else {
		// The delete failed: it may only have failed closed, leaving the session
		// live and its device intact.
		if s.RevokedAt != nil && s.DeviceID == nil {
			t.Errorf("PROVEN BROKEN: session detached without its device being deleted")
		}
	}
	reason := ""
	if s.RevokeReason != nil {
		reason = *s.RevokeReason
	}
	if reason == "refresh_reuse" {
		t.Errorf("PROVEN BROKEN: the race was misread as a fork")
	}
}

func TestGate18_RefreshVersusDeviceRevoke_Concurrent(t *testing.T) {
	e := gate17Setup(t)
	dev := g18Device(t, e.user.ID, "race-rev")
	sid, ref := e.secondSession(t, e.user.ID, &dev.ID)
	e.session, e.refresh = sid, ref

	barrier := newLockBarrier(t, sid)
	var (
		res refreshResult
		wg  sync.WaitGroup
	)
	wg.Add(2)
	go func() { defer wg.Done(); res = e.refreshWith(t, ref, uuid.New()) }()
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

	s := e.rowFor(t, sid)
	if s.RevokedAt == nil {
		t.Fatal("the device revocation must land regardless of ordering")
	}
	if s.RefreshHash != nil {
		t.Fatal("PROVEN BROKEN: a revoked session retains a usable refresh hash")
	}
	if s.DeviceID == nil {
		t.Errorf("revocation must not detach: the device row still exists")
	}
	if res.code == http.StatusOK {
		if c := e.refreshWith(t, res.refresh, uuid.New()).code; c != http.StatusUnauthorized {
			t.Errorf("PROVEN BROKEN: a successor outlived the device revocation (%d)", c)
		}
	}
	t.Logf("interleaving: refresh=%d", res.code)
}
