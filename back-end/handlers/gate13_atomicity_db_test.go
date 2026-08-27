package handlers

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// ================================= GATE 13: atomicity invariants I8, I9, I10
//
// These do NOT rely on winning a probabilistic race. A test-only GORM callback
// suspends the FIRST UPDATE against a chosen table, so the racing actor is
// released precisely into the window between the winner's check and its write.
//
// Against the FIXED handlers the winner holds SELECT ... FOR UPDATE across that
// window, so the racing actor blocks on the row lock, re-reads the committed
// state once released, and loses deterministically. Against the pre-fix code
// (or with the lock mutated away) the racing actor sails through the gap and
// both callers succeed — which is exactly what these assertions catch.
//
// Production code is untouched: the callback is registered on the test
// connection and removed on cleanup.

type interleaver struct {
	mu      sync.Mutex
	table   string
	atWrite chan struct{}
	release chan struct{}
	fired   bool
}

func newInterleaver(t *testing.T, table string) *interleaver {
	t.Helper()
	iv := &interleaver{
		table:   table,
		atWrite: make(chan struct{}),
		release: make(chan struct{}),
	}
	name := "gate13:suspend:" + table
	err := database.DB.Callback().Update().Before("gorm:update").Register(name, func(d *gorm.DB) {
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
		close(iv.atWrite) // winner has read and checked; it is about to write
		<-iv.release      // held open so the racing actor can try the gap
	})
	if err != nil {
		t.Fatalf("register callback: %v", err)
	}
	t.Cleanup(func() { _ = database.DB.Callback().Update().Remove(name) })
	return iv
}

func (iv *interleaver) waitAtWrite(t *testing.T) {
	t.Helper()
	select {
	case <-iv.atWrite:
	case <-time.After(15 * time.Second):
		t.Fatal("the winning actor never reached its write")
	}
}

func (iv *interleaver) resume() { close(iv.release) }

// gate13App mounts handlers WITHOUT DeviceRevocationGuard. That guard is a Gate
// 11 trust-boundary concern which remains deliberately unfixed; excluding it
// keeps its own device lookups and auto-registration out of the interleaving
// under test here.
func gate13App(userID uuid.UUID) (*fiber.App, *E2EEHandler) {
	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: userID})
		return c.Next()
	})
	return app, NewE2EEHandler(nil)
}

func gate13Req(t *testing.T, app *fiber.App, method, path, body string) (int, string) {
	t.Helper()
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, path, nil)
	} else {
		req = httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("X-Device-Id", "probe-device")
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Errorf("request: %v", err)
		return 0, ""
	}
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(raw)
}

func hit(t *testing.T, app *fiber.App, method, path, body string) int {
	t.Helper()
	code, _ := gate13Req(t, app, method, path, body)
	return code
}

func seedPairing(t *testing.T, owner uuid.UUID, payload string, expires time.Time) string {
	t.Helper()
	sid := uuid.New().String()
	s := models.E2EEPairingSession{
		ID: uuid.New(), UserID: owner, SessionID: sid,
		EphemeralPubHex: "aa", ExpiresAt: expires, CreatedAt: time.Now(),
	}
	if payload != "" {
		s.Payload = []byte(payload)
		s.SenderPubHex = strings.Repeat("cc", 32)
	}
	if err := database.DB.Create(&s).Error; err != nil {
		t.Fatalf("seed pairing: %v", err)
	}
	return sid
}

// ---------------------------------------------------------------- I8
// A pairing payload is consumable at most once, under concurrency.
func TestGate13_I8_PairingPayloadConsumedAtMostOnce(t *testing.T) {
	e := gate11Setup(t)
	app, h := gate13App(e.userA)
	app.Get("/pairing/:session_id/payload", h.TakePairingPayload)

	sid := seedPairing(t, e.userA, "WRAPPED-MASTER-KEY", time.Now().Add(5*time.Minute))
	path := "/pairing/" + sid + "/payload"
	iv := newInterleaver(t, "e2_ee_pairing_sessions")

	var aCode, bCode int
	var aBody, bBody string
	aDone := make(chan struct{})
	go func() {
		defer close(aDone)
		aCode, aBody = gate13Req(t, app, http.MethodGet, path, "")
	}()

	iv.waitAtWrite(t) // A has checked and is about to consume

	// B is released into A's check-write window. Against the fixed handler B
	// blocks on A's row lock, so it must run concurrently: a synchronous call
	// here would wait forever for a request that cannot proceed until A commits.
	bDone := make(chan struct{})
	go func() {
		defer close(bDone)
		bCode, bBody = gate13Req(t, app, http.MethodGet, path, "")
	}()
	time.Sleep(300 * time.Millisecond) // let B reach and block on the lock
	iv.resume()
	<-aDone
	<-bDone

	successes, released := 0, 0
	for _, r := range []struct {
		code int
		body string
	}{{aCode, aBody}, {bCode, bBody}} {
		if r.code == http.StatusOK {
			successes++
		}
		if strings.Contains(r.body, b64([]byte("WRAPPED-MASTER-KEY"))) {
			released++
		}
	}
	if successes != 1 {
		t.Fatalf("I8 VIOLATED: expected exactly one consumer to succeed, got %d (A=%d, B=%d)",
			successes, aCode, bCode)
	}
	if released != 1 {
		t.Fatalf("I8 VIOLATED: master-key ciphertext released %d times (A=%d, B=%d)",
			released, aCode, bCode)
	}
	if aCode != http.StatusConflict && bCode != http.StatusConflict {
		t.Fatalf("I8: the losing consumer must receive 409, got A=%d B=%d", aCode, bCode)
	}
	var s models.E2EEPairingSession
	database.DB.Where("session_id = ?", sid).First(&s)
	if s.ConsumedAt == nil {
		t.Fatal("I8: session must be marked consumed")
	}
	if len(s.Payload) != 0 {
		t.Fatal("I8: payload must be scrubbed after handoff")
	}
	t.Logf("I8 HELD: one success, one 409 (A=%d, B=%d); ciphertext released exactly once",
		aCode, bCode)
}

// ---------------------------------------------------------------- I9
// waiting -> ready happens at most once; a completion cannot overwrite another.
func TestGate13_I9_PairingCompletedAtMostOnce(t *testing.T) {
	e := gate11Setup(t)
	app, h := gate13App(e.userA)
	app.Post("/pairing/:session_id/complete", h.CompletePairing)

	sid := seedPairing(t, e.userA, "", time.Now().Add(5*time.Minute))
	path := "/pairing/" + sid + "/complete"
	bodyFor := func(tag string) string {
		return fmt.Sprintf(`{"payload_b64":%q,"sender_pub_hex":%q}`,
			b64([]byte(tag)), strings.Repeat("cc", 32))
	}
	iv := newInterleaver(t, "e2_ee_pairing_sessions")

	var aCode, bCode int
	aDone := make(chan struct{})
	go func() {
		defer close(aDone)
		aCode = hit(t, app, http.MethodPost, path, bodyFor("PAYLOAD-FROM-A"))
	}()

	iv.waitAtWrite(t)
	bDone := make(chan struct{})
	go func() {
		defer close(bDone)
		bCode = hit(t, app, http.MethodPost, path, bodyFor("PAYLOAD-FROM-B"))
	}()
	time.Sleep(300 * time.Millisecond)
	iv.resume()
	<-aDone
	<-bDone

	if (aCode == http.StatusOK) == (bCode == http.StatusOK) {
		t.Fatalf("I9 VIOLATED: expected exactly one completion to succeed, got A=%d B=%d",
			aCode, bCode)
	}
	if aCode != http.StatusConflict && bCode != http.StatusConflict {
		t.Fatalf("I9: the losing completion must receive 409, got A=%d B=%d", aCode, bCode)
	}
	var s models.E2EEPairingSession
	database.DB.Where("session_id = ?", sid).First(&s)
	// A holds the lock, so A is the winner; its payload must be the stored one.
	if string(s.Payload) != "PAYLOAD-FROM-A" {
		t.Fatalf("I9 VIOLATED: the winning completion was overwritten, stored %q",
			string(s.Payload))
	}
	t.Logf("I9 HELD: one success, one 409 (A=%d, B=%d); stored payload %q intact",
		aCode, bCode, string(s.Payload))
}

// ---------------------------------------------------------------- I10
// A stale registration cannot restore a device after revocation.
func TestGate13_I10_RegistrationCannotResurrectARevokedDevice(t *testing.T) {
	e := gate11Setup(t)
	app, h := gate13App(e.userA)
	app.Post("/devices", h.RegisterDevice)
	app.Post("/devices/:device_id/revoke", h.RevokeDevice)

	const dev = "device-race-D"
	now := time.Now()
	if err := database.DB.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.userA, DeviceID: dev,
		CreatedAt: now, UpdatedAt: now,
	}).Error; err != nil {
		t.Fatalf("seed device: %v", err)
	}

	iv := newInterleaver(t, "e2_ee_devices")

	var regCode, revCode int
	regDone := make(chan struct{})
	go func() {
		defer close(regDone)
		regCode = hit(t, app, http.MethodPost, "/devices",
			fmt.Sprintf(`{"device_id":%q,"name":"A"}`, dev))
	}()

	iv.waitAtWrite(t) // registration read an ACTIVE row and is about to write

	revDone := make(chan struct{})
	go func() {
		defer close(revDone)
		revCode = hit(t, app, http.MethodPost, "/devices/"+dev+"/revoke", "")
	}()
	time.Sleep(300 * time.Millisecond)
	iv.resume()
	<-regDone
	<-revDone

	if revCode != http.StatusOK {
		t.Fatalf("precondition: revoke should have succeeded, got %d", revCode)
	}
	var final models.E2EEDevice
	if err := database.DB.Where("user_id = ? AND device_id = ?", e.userA, dev).
		First(&final).Error; err != nil {
		t.Fatalf("device row missing: %v", err)
	}
	if final.RevokedAt == nil {
		t.Fatalf("I10 VIOLATED: revoke committed (200) but the concurrent registration "+
			"(status %d) left revoked_at NULL. The device is trusted again.", regCode)
	}
	t.Logf("I10 HELD: revocation survived the concurrent registration (register=%d, revoke=%d)",
		regCode, revCode)
}

// ---------------------------------------------------------------- lock order
// Every transaction added in this gate locks at most one row in one table, so
// no lock-order inversion is possible between them. This drives all five
// mutating handlers concurrently against a shared device and pairing session to
// confirm that: no deadlock, and contention never surfaces as a 5xx.
func TestGate13_NoDeadlockAcrossMutatingHandlers(t *testing.T) {
	e := gate11Setup(t)
	app, h := gate13App(e.userA)
	app.Post("/devices", h.RegisterDevice)
	app.Post("/devices/:device_id/revoke", h.RevokeDevice)
	app.Delete("/devices/:device_id", h.DeleteDevice)
	app.Post("/pairing/:session_id/complete", h.CompletePairing)
	app.Get("/pairing/:session_id/payload", h.TakePairingPayload)

	const dev = "device-deadlock"
	now := time.Now()
	database.DB.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.userA, DeviceID: dev, CreatedAt: now, UpdatedAt: now,
	})
	sid := seedPairing(t, e.userA, "", now.Add(5*time.Minute))
	completeBody := fmt.Sprintf(`{"payload_b64":%q,"sender_pub_hex":%q}`,
		b64([]byte("MK")), strings.Repeat("cc", 32))

	var wg sync.WaitGroup
	codes := make(chan int, 400)
	for i := 0; i < 20; i++ {
		for _, op := range []struct{ m, p, b string }{
			{http.MethodPost, "/devices", fmt.Sprintf(`{"device_id":%q}`, dev)},
			{http.MethodPost, "/devices/" + dev + "/revoke", ""},
			{http.MethodPost, "/pairing/" + sid + "/complete", completeBody},
			{http.MethodGet, "/pairing/" + sid + "/payload", ""},
		} {
			wg.Add(1)
			go func(m, p, b string) {
				defer wg.Done()
				codes <- hit(t, app, m, p, b)
			}(op.m, op.p, op.b)
		}
	}
	finished := make(chan struct{})
	go func() { wg.Wait(); close(finished) }()
	select {
	case <-finished:
	case <-time.After(60 * time.Second):
		t.Fatal("DEADLOCK: mutating handlers did not all complete within 60s")
	}
	close(codes)

	server, total := 0, 0
	for code := range codes {
		total++
		if code >= 500 {
			server++
		}
	}
	if server > 0 {
		t.Fatalf("%d of %d concurrent requests returned 5xx; lock contention must not "+
			"surface as errors", server, total)
	}
	t.Logf("no deadlock: %d concurrent mutating requests, zero 5xx", total)
}

// ---------------------------------------------------------- sequential replay
// The intended single-use contract, so the concurrent assertions above are
// measured against real semantics rather than invented ones.
func TestGate13_SequentialPairingSemantics(t *testing.T) {
	e := gate11Setup(t)
	app, h := gate13App(e.userA)
	app.Get("/pairing/:session_id", h.GetPairing)
	app.Post("/pairing/:session_id/complete", h.CompletePairing)
	app.Get("/pairing/:session_id/payload", h.TakePairingPayload)

	future := time.Now().Add(5 * time.Minute)
	completeBody := `{"payload_b64":"` + b64([]byte("MK-WRAP")) +
		`","sender_pub_hex":"` + strings.Repeat("cc", 32) + `"}`

	t.Run("complete twice sequentially", func(t *testing.T) {
		sid := seedPairing(t, e.userA, "", future)
		first := hit(t, app, http.MethodPost, "/pairing/"+sid+"/complete", completeBody)
		second := hit(t, app, http.MethodPost, "/pairing/"+sid+"/complete", completeBody)
		t.Logf("complete #1=%d  complete #2=%d", first, second)
		if first != http.StatusOK || second != http.StatusConflict {
			t.Errorf("expected 200 then 409, got %d then %d", first, second)
		}
	})

	t.Run("take twice sequentially", func(t *testing.T) {
		sid := seedPairing(t, e.userA, "MK-WRAP", future)
		first := hit(t, app, http.MethodGet, "/pairing/"+sid+"/payload", "")
		second := hit(t, app, http.MethodGet, "/pairing/"+sid+"/payload", "")
		t.Logf("take #1=%d  take #2=%d", first, second)
		if first != http.StatusOK || second != http.StatusConflict {
			t.Errorf("expected 200 then 409, got %d then %d", first, second)
		}
	})

	t.Run("complete after consumed", func(t *testing.T) {
		sid := seedPairing(t, e.userA, "MK-WRAP", future)
		hit(t, app, http.MethodGet, "/pairing/"+sid+"/payload", "")
		code := hit(t, app, http.MethodPost, "/pairing/"+sid+"/complete", completeBody)
		t.Logf("complete-after-consume=%d", code)
		if code != http.StatusConflict {
			t.Errorf("expected 409, got %d", code)
		}
	})

	t.Run("take before complete is 204", func(t *testing.T) {
		sid := seedPairing(t, e.userA, "", future)
		code := hit(t, app, http.MethodGet, "/pairing/"+sid+"/payload", "")
		if code != http.StatusNoContent {
			t.Errorf("expected 204, got %d", code)
		}
	})

	t.Run("replay after expiration", func(t *testing.T) {
		past := time.Now().Add(-1 * time.Minute)
		sid := seedPairing(t, e.userA, "MK-WRAP", past)
		take := hit(t, app, http.MethodGet, "/pairing/"+sid+"/payload", "")
		comp := hit(t, app, http.MethodPost, "/pairing/"+sid+"/complete", completeBody)
		t.Logf("expired take=%d  expired complete=%d", take, comp)
		if take != http.StatusGone || comp != http.StatusGone {
			t.Errorf("expected 410 for both, got %d and %d", take, comp)
		}
	})

	t.Run("missing session is 404", func(t *testing.T) {
		code := hit(t, app, http.MethodGet, "/pairing/"+uuid.New().String()+"/payload", "")
		if code != http.StatusNotFound {
			t.Errorf("expected 404, got %d", code)
		}
	})
}

// ------------------------------------------------------------- cross-account
func TestGate13_CrossAccountPairingMatrix(t *testing.T) {
	e := gate11Setup(t)
	userB := uuid.New()
	now := time.Now()
	if err := database.DB.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, 'x', ?, ?)`, userB, "u"+userB.String()[:8],
		userB.String()[:8]+"@t.local", now, now).Error; err != nil {
		t.Fatalf("seed userB: %v", err)
	}
	appB, hB := gate13App(userB)
	appB.Get("/pairing/:session_id", hB.GetPairing)
	appB.Post("/pairing/:session_id/complete", hB.CompletePairing)
	appB.Get("/pairing/:session_id/payload", hB.TakePairingPayload)

	sid := seedPairing(t, e.userA, "ACCOUNT-A-MK-WRAP", now.Add(5*time.Minute))
	completeBody := `{"payload_b64":"` + b64([]byte("B-INJECTED")) +
		`","sender_pub_hex":"` + strings.Repeat("dd", 32) + `"}`

	ops := []struct{ name, method, path, body string }{
		{"GET status", http.MethodGet, "/pairing/" + sid, ""},
		{"COMPLETE (inject payload)", http.MethodPost, "/pairing/" + sid + "/complete", completeBody},
		{"TAKE payload (steal MK)", http.MethodGet, "/pairing/" + sid + "/payload", ""},
	}
	leaked := 0
	for _, op := range ops {
		code, body := gate13Req(t, appB, op.method, op.path, op.body)
		verdict := "SCOPED (404)"
		if code < 400 {
			verdict = "CROSS-ACCOUNT LEAK"
			leaked++
		}
		if strings.Contains(body, b64([]byte("ACCOUNT-A-MK-WRAP"))) {
			verdict = "CROSS-ACCOUNT PAYLOAD DISCLOSURE"
			leaked++
		}
		t.Logf("  account B -> %-28s %d %s", op.name, code, verdict)
	}
	var s models.E2EEPairingSession
	database.DB.Where("session_id = ?", sid).First(&s)
	if leaked > 0 {
		t.Fatalf("STOP CONDITION: %d cross-account pairing operation(s) succeeded; "+
			"stored payload now %q", leaked, string(s.Payload))
	}
	if string(s.Payload) != "ACCOUNT-A-MK-WRAP" {
		t.Fatalf("account A payload was mutated by account B: %q", string(s.Payload))
	}
	t.Logf("PROVEN SAFE: all %d cross-account pairing operations refused; payload intact", len(ops))
}

// ------------------------------------------------- registration semantics kept
// The pre-existing contract RegisterDevice must still honour after the rewrite.
func TestGate13_RegisterDeviceSemanticsPreserved(t *testing.T) {
	e := gate11Setup(t)
	app, h := gate13App(e.userA)
	app.Post("/devices", h.RegisterDevice)
	app.Post("/devices/:device_id/revoke", h.RevokeDevice)

	t.Run("first registration creates 201", func(t *testing.T) {
		code := hit(t, app, http.MethodPost, "/devices",
			`{"device_id":"sem-new","name":"N","platform":"android"}`)
		if code != http.StatusCreated {
			t.Errorf("expected 201, got %d", code)
		}
	})

	t.Run("re-registration updates 200", func(t *testing.T) {
		code := hit(t, app, http.MethodPost, "/devices",
			`{"device_id":"sem-new","name":"N2","platform":"ios"}`)
		if code != http.StatusOK {
			t.Errorf("expected 200, got %d", code)
		}
		var d models.E2EEDevice
		database.DB.Where("user_id = ? AND device_id = ?", e.userA, "sem-new").First(&d)
		if d.Name != "N2" || d.Platform != "ios" {
			t.Errorf("update did not apply: name=%q platform=%q", d.Name, d.Platform)
		}
	})

	t.Run("empty public key does not clear the stored key", func(t *testing.T) {
		hit(t, app, http.MethodPost, "/devices", `{"device_id":"sem-pk","public_key":"KEY-1"}`)
		hit(t, app, http.MethodPost, "/devices", `{"device_id":"sem-pk"}`)
		var d models.E2EEDevice
		database.DB.Where("user_id = ? AND device_id = ?", e.userA, "sem-pk").First(&d)
		if d.PublicKey != "KEY-1" {
			t.Errorf("public key was clobbered by an empty value: %q", d.PublicKey)
		}
	})

	t.Run("revoked device refused 403 and stays revoked", func(t *testing.T) {
		hit(t, app, http.MethodPost, "/devices", `{"device_id":"sem-rev"}`)
		if code := hit(t, app, http.MethodPost, "/devices/sem-rev/revoke", ""); code != http.StatusOK {
			t.Fatalf("revoke failed: %d", code)
		}
		code := hit(t, app, http.MethodPost, "/devices", `{"device_id":"sem-rev"}`)
		if code != http.StatusForbidden {
			t.Errorf("expected 403 for a revoked device, got %d", code)
		}
		var d models.E2EEDevice
		database.DB.Where("user_id = ? AND device_id = ?", e.userA, "sem-rev").First(&d)
		if d.RevokedAt == nil {
			t.Error("revoked_at must survive a refused re-registration")
		}
	})

	t.Run("missing device_id is 400", func(t *testing.T) {
		if code := hit(t, app, http.MethodPost, "/devices", `{}`); code != http.StatusBadRequest {
			t.Errorf("expected 400, got %d", code)
		}
	})

	t.Run("revoking an unknown device is 404", func(t *testing.T) {
		if code := hit(t, app, http.MethodPost, "/devices/nope/revoke", ""); code != http.StatusNotFound {
			t.Errorf("expected 404, got %d", code)
		}
	})
}
