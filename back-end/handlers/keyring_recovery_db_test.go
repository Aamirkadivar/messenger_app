package handlers

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// GATE 5 PHASE 1 - the per-user MK-sealed history keyring, against real Postgres.
//
// The server stores one opaque versioned blob per user and can decrypt nothing.
// What is proven here is ownership, the device gate, optimistic concurrency, and
// that the blob dies with its owner.
//
// Scratch database only - see archive_lifecycle_db_test.go.

type keyringEnv struct {
	app         *fiber.App
	userA       uuid.UUID
	userB       uuid.UUID
	deviceA     string
	deviceB     string
	revokedA    string
	currentUser uuid.UUID
}

func keyringEnvSetup(t *testing.T) *keyringEnv {
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
		// Release the pool: the scratch server has a finite max_connections
		// and these suites open one pool per test.
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
	})

	e := &keyringEnv{userA: uuid.New(), userB: uuid.New()}
	e.deviceA = "device-" + e.userA.String()[:6]
	e.deviceB = "device-" + e.userB.String()[:6]
	e.revokedA = "revoked-" + e.userA.String()[:6]

	now := time.Now()
	for _, u := range []uuid.UUID{e.userA, e.userB} {
		if err := db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
			VALUES (?, ?, ?, 'x', ?, ?)`,
			u, "u"+u.String()[:8], u.String()[:8]+"@test.local", now, now).Error; err != nil {
			t.Fatalf("seed user: %v", err)
		}
	}
	db.Create(&models.E2EEDevice{ID: uuid.New(), UserID: e.userA, DeviceID: e.deviceA, CreatedAt: now, UpdatedAt: now})
	db.Create(&models.E2EEDevice{ID: uuid.New(), UserID: e.userB, DeviceID: e.deviceB, CreatedAt: now, UpdatedAt: now})
	db.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.userA, DeviceID: e.revokedA,
		RevokedAt: &now, CreatedAt: now, UpdatedAt: now,
	})

	e.currentUser = e.userA
	e.app = fiber.New()
	e.app.Use(func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: e.currentUser})
		return c.Next()
	})
	e.app.Use(middleware.RequireDeviceIdentity())
	h := NewKeyringRecoveryHandler()
	e.app.Get("/history-keyring", h.GetHistoryKeyring)
	e.app.Put("/history-keyring", h.PutHistoryKeyring)
	e.app.Delete("/history-keyring", h.DeleteHistoryKeyring)
	// The vault handler shares the app so a GATE 6 test can prove that the vault
	// and the recovery row are genuinely independent objects.
	vh := NewE2EEHandler(nil)
	e.app.Put("/vault", vh.PutVault)
	return e
}

func (e *keyringEnv) call(t *testing.T, method, device, body string) (int, map[string]any) {
	t.Helper()
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, "/history-keyring", nil)
	} else {
		req = httptest.NewRequest(method, "/history-keyring", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
	}
	if device != "" {
		req.Header.Set("X-Device-Id", device)
	}
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

// callAsync is the goroutine-safe twin of call: a test helper may not call
// t.Fatalf off the main goroutine, so this returns the status instead.
func (e *keyringEnv) callAsync(method, device, body string) int {
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, "/history-keyring", nil)
	} else {
		req = httptest.NewRequest(method, "/history-keyring", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("X-Device-Id", device)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		return -1
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func putBody(expectedVersion int, ciphertext string) string {
	return fmt.Sprintf(`{"expected_version":%d,"ciphertext_b64":%q}`,
		expectedVersion, base64.StdEncoding.EncodeToString([]byte(ciphertext)))
}

// ---------------------------------------------------------------- lifecycle

func TestKeyring_CreateAndRetrieveOwnObject(t *testing.T) {
	e := keyringEnvSetup(t)

	code, body := e.call(t, http.MethodGet, e.deviceA, "")
	if code != http.StatusNotFound {
		t.Fatalf("a user with no keyring must get 404, got %d", code)
	}

	code, body = e.call(t, http.MethodPut, e.deviceA, putBody(0, "sealed-v1"))
	if code != http.StatusOK {
		t.Fatalf("create: status %d (%v)", code, body)
	}
	if body["version"] != float64(1) || body["created"] != true {
		t.Fatalf("create must report version 1: %v", body)
	}

	code, body = e.call(t, http.MethodGet, e.deviceA, "")
	if code != http.StatusOK {
		t.Fatalf("get: status %d", code)
	}
	got, _ := base64.StdEncoding.DecodeString(body["ciphertext_b64"].(string))
	if string(got) != "sealed-v1" {
		t.Fatalf("blob round-trip failed: %q", got)
	}
	if body["version"] != float64(1) {
		t.Fatalf("version must be 1, got %v", body["version"])
	}
}

func TestKeyring_CrossUserAccessIsImpossible(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "A-secret"))

	// B has its own device and its own session; it must not see A's object.
	e.currentUser = e.userB
	code, _ := e.call(t, http.MethodGet, e.deviceB, "")
	if code != http.StatusNotFound {
		t.Fatalf("B must not see A's keyring, got %d", code)
	}

	// B writes its own; A's must be untouched.
	if code, _ := e.call(t, http.MethodPut, e.deviceB, putBody(0, "B-secret")); code != http.StatusOK {
		t.Fatalf("B create: %d", code)
	}
	e.currentUser = e.userA
	_, body := e.call(t, http.MethodGet, e.deviceA, "")
	got, _ := base64.StdEncoding.DecodeString(body["ciphertext_b64"].(string))
	if string(got) != "A-secret" {
		t.Fatalf("A's blob was disturbed by B: %q", got)
	}
}

// Ownership comes only from the session. A body field named user_id must be
// ignored entirely - it is not part of the contract and must not redirect writes.
func TestKeyring_CallerSuppliedUserIdCannotRedirectOwnership(t *testing.T) {
	e := keyringEnvSetup(t)
	body := fmt.Sprintf(`{"expected_version":0,"ciphertext_b64":%q,"user_id":%q}`,
		base64.StdEncoding.EncodeToString([]byte("A-writes")), e.userB.String())
	if code, _ := e.call(t, http.MethodPut, e.deviceA, body); code != http.StatusOK {
		t.Fatalf("write: %d", code)
	}

	e.currentUser = e.userB
	if code, _ := e.call(t, http.MethodGet, e.deviceB, ""); code != http.StatusNotFound {
		t.Fatalf("the write must have landed on A, not B; B got %d", code)
	}
}

// ---------------------------------------------------------------- device gate

func TestKeyring_DeviceIdentityIsMandatory(t *testing.T) {
	e := keyringEnvSetup(t)
	cases := []struct {
		name, device string
		want         int
	}{
		{"missing device", "", http.StatusBadRequest},
		{"unknown device", "never-seen", http.StatusForbidden},
		{"revoked device", e.revokedA, http.StatusForbidden},
	}
	for _, tc := range cases {
		t.Run("get "+tc.name, func(t *testing.T) {
			if code, _ := e.call(t, http.MethodGet, tc.device, ""); code != tc.want {
				t.Fatalf("status %d, want %d", code, tc.want)
			}
		})
		t.Run("put "+tc.name, func(t *testing.T) {
			if code, _ := e.call(t, http.MethodPut, tc.device, putBody(0, "x")); code != tc.want {
				t.Fatalf("status %d, want %d", code, tc.want)
			}
		})
	}
}

// ---------------------------------------------------------------- concurrency

func TestKeyring_OptimisticVersioning(t *testing.T) {
	e := keyringEnvSetup(t)

	if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(0, "v1")); code != http.StatusOK {
		t.Fatalf("create failed")
	}

	t.Run("create when one already exists is a conflict", func(t *testing.T) {
		code, body := e.call(t, http.MethodPut, e.deviceA, putBody(0, "clobber"))
		if code != http.StatusConflict {
			t.Fatalf("status %d, want 409", code)
		}
		if body["server_version"] != float64(1) {
			t.Fatalf("conflict must report the server version: %v", body)
		}
	})

	t.Run("update at the current version succeeds and increments", func(t *testing.T) {
		code, body := e.call(t, http.MethodPut, e.deviceA, putBody(1, "v2"))
		if code != http.StatusOK || body["version"] != float64(2) {
			t.Fatalf("status %d body %v", code, body)
		}
	})

	t.Run("stale expected version is rejected", func(t *testing.T) {
		code, body := e.call(t, http.MethodPut, e.deviceA, putBody(1, "stale"))
		if code != http.StatusConflict {
			t.Fatalf("status %d, want 409", code)
		}
		if body["server_version"] != float64(2) {
			t.Fatalf("want server_version 2, got %v", body)
		}
	})

	t.Run("a replayed successful update cannot clobber a newer version", func(t *testing.T) {
		// Replaying the v1->v2 write must not overwrite v2.
		if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(1, "replayed")); code != http.StatusConflict {
			t.Fatalf("replay must conflict")
		}
		_, body := e.call(t, http.MethodGet, e.deviceA, "")
		got, _ := base64.StdEncoding.DecodeString(body["ciphertext_b64"].(string))
		if string(got) != "v2" {
			t.Fatalf("stored blob was clobbered by a replay: %q", got)
		}
	})

	t.Run("a future version is rejected", func(t *testing.T) {
		if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(99, "future")); code != http.StatusConflict {
			t.Fatalf("a version the server never issued must conflict")
		}
	})
}

// Two writers naming the same version: exactly one may win.
func TestKeyring_ConcurrentUpdatesCannotBothSucceed(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "v1"))

	first, _ := e.call(t, http.MethodPut, e.deviceA, putBody(1, "writer-one"))
	second, _ := e.call(t, http.MethodPut, e.deviceA, putBody(1, "writer-two"))

	if first != http.StatusOK || second != http.StatusConflict {
		t.Fatalf("exactly one writer may win: first=%d second=%d", first, second)
	}
	_, body := e.call(t, http.MethodGet, e.deviceA, "")
	got, _ := base64.StdEncoding.DecodeString(body["ciphertext_b64"].(string))
	if string(got) != "writer-one" {
		t.Fatalf("the loser overwrote the winner: %q", got)
	}
}

// ---------------------------------------------------------------- validation

func TestKeyring_RequestValidation(t *testing.T) {
	e := keyringEnvSetup(t)
	cases := []struct {
		name, body string
		want       int
	}{
		{"malformed json", `{`, http.StatusBadRequest},
		{"negative expected_version", putBody(-1, "x"), http.StatusBadRequest},
		{"malformed base64", `{"expected_version":0,"ciphertext_b64":"!!!"}`, http.StatusBadRequest},
		{"empty ciphertext", `{"expected_version":0,"ciphertext_b64":""}`, http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if code, _ := e.call(t, http.MethodPut, e.deviceA, tc.body); code != tc.want {
				t.Fatalf("status %d, want %d", code, tc.want)
			}
		})
	}
	if code, _ := e.call(t, http.MethodGet, e.deviceA, ""); code != http.StatusNotFound {
		t.Fatal("no rejected request may have created an object")
	}
}

func TestKeyring_CiphertextSizeLimitEnforced(t *testing.T) {
	e := keyringEnvSetup(t)
	oversized := make([]byte, maxKeyringCiphertext+1)
	body := fmt.Sprintf(`{"expected_version":0,"ciphertext_b64":%q}`,
		base64.StdEncoding.EncodeToString(oversized))
	if code, _ := e.call(t, http.MethodPut, e.deviceA, body); code != http.StatusRequestEntityTooLarge {
		t.Fatalf("want 413, got %d", code)
	}
}

// ---------------------------------------------------------------- schema

func TestKeyring_SchemaHoldsNoSecretMaterialAndCascades(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "sealed"))

	var cols []string
	database.DB.Raw(`SELECT column_name FROM information_schema.columns
	                 WHERE table_name = 'history_keyring_recoveries' ORDER BY column_name`).Scan(&cols)
	want := map[string]bool{"user_id": true, "ciphertext": true, "version": true,
		"created_at": true, "updated_at": true}
	for _, c := range cols {
		if !want[c] {
			t.Fatalf("unexpected column %q - the server must know only that a user owns a blob", c)
		}
	}
	for _, forbidden := range []string{"root", "history_root", "keyring", "plaintext",
		"master_key", "mk", "password", "recovery_key", "device_secret", "chat_id", "message_id"} {
		for _, c := range cols {
			if c == forbidden {
				t.Fatalf("history_keyring_recoveries must never hold %q", c)
			}
		}
	}

	// The blob dies with its owner.
	var rule string
	database.DB.Raw(`
		SELECT rc.delete_rule
		FROM information_schema.table_constraints tc
		JOIN information_schema.referential_constraints rc ON rc.constraint_name = tc.constraint_name
		WHERE tc.table_name = 'history_keyring_recoveries' AND tc.constraint_type = 'FOREIGN KEY'`).
		Scan(&rule)
	if rule != "CASCADE" {
		t.Fatalf("user_id FK delete rule is %q, want CASCADE", rule)
	}

	database.DB.Exec(`DELETE FROM users WHERE id = ?`, e.userA)
	var n int64
	database.DB.Raw(`SELECT COUNT(*) FROM history_keyring_recoveries WHERE user_id = ?`, e.userA).Scan(&n)
	if n != 0 {
		t.Fatalf("the keyring must be removed with its owner, %d remain", n)
	}
}

// ------------------------------------------------- GATE 5 PHASE 4: deletion
//
// DELETE exists to break one specific deadlock. A blob sealed under a retired
// master key can never be opened, and PUT with expected_version=0 is create-only,
// so without deletion that account's recovery is stranded forever. These tests
// pin the behaviour that makes it safe to expose: it removes the CALLER's row and
// only the caller's, it is idempotent, it is device-gated like its siblings, and
// it genuinely frees the account to create again.

func TestKeyring_DeleteRemovesOnlyTheCallersObject(t *testing.T) {
	e := keyringEnvSetup(t)

	if code, _ := e.call(t, "PUT", e.deviceA, putBody(0, "keyring-A")); code != http.StatusOK {
		t.Fatalf("seed A: %d", code)
	}
	e.currentUser, _ = e.userB, 0
	if code, _ := e.call(t, "PUT", e.deviceB, putBody(0, "keyring-B")); code != http.StatusOK {
		t.Fatalf("seed B: %d", code)
	}

	// A deletes its own.
	e.currentUser = e.userA
	code, body := e.call(t, "DELETE", e.deviceA, "")
	if code != http.StatusOK {
		t.Fatalf("delete: %d", code)
	}
	if body["deleted"] != true {
		t.Fatalf("expected deleted=true, got %v", body["deleted"])
	}
	if code, _ := e.call(t, "GET", e.deviceA, ""); code != http.StatusNotFound {
		t.Fatalf("A should be gone, got %d", code)
	}

	// B is untouched. A DELETE is authorised by session alone, so there is no
	// parameter a caller could bend to reach someone else's row - but the whole
	// point of the endpoint is destructive, so prove the blast radius directly.
	e.currentUser = e.userB
	code, got := e.call(t, "GET", e.deviceB, "")
	if code != http.StatusOK {
		t.Fatalf("B must survive A's delete, got %d", code)
	}
	raw, _ := base64.StdEncoding.DecodeString(got["ciphertext_b64"].(string))
	if string(raw) != "keyring-B" {
		t.Fatalf("B's blob altered: %q", raw)
	}
}

func TestKeyring_DeleteIsIdempotent(t *testing.T) {
	e := keyringEnvSetup(t)

	// Deleting when nothing is stored is a success, not an error: the caller's
	// goal is "no stale blob remains", and that is already true. The fresh-MK
	// path runs this on brand-new accounts, where absence is the normal case.
	code, body := e.call(t, "DELETE", e.deviceA, "")
	if code != http.StatusOK {
		t.Fatalf("delete on empty: %d", code)
	}
	if body["deleted"] != false {
		t.Fatalf("nothing was there; expected deleted=false, got %v", body["deleted"])
	}

	e.call(t, "PUT", e.deviceA, putBody(0, "keyring-A"))
	if code, _ := e.call(t, "DELETE", e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("first delete: %d", code)
	}
	code, body = e.call(t, "DELETE", e.deviceA, "")
	if code != http.StatusOK {
		t.Fatalf("repeat delete must succeed, got %d", code)
	}
	if body["deleted"] != false {
		t.Fatalf("repeat delete should report nothing removed, got %v", body["deleted"])
	}
}

func TestKeyring_DeleteRequiresDeviceIdentity(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, "PUT", e.deviceA, putBody(0, "keyring-A"))

	for _, tc := range []struct {
		name, device string
		want         int
	}{
		{"missing", "", http.StatusBadRequest},
		{"unknown", "device-never-registered", http.StatusForbidden},
		{"revoked", e.revokedA, http.StatusForbidden},
	} {
		if code, _ := e.call(t, "DELETE", tc.device, ""); code != tc.want {
			t.Fatalf("%s device: want %d got %d", tc.name, tc.want, code)
		}
	}

	// None of those attempts may have destroyed anything.
	if code, _ := e.call(t, "GET", e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("blob must survive rejected deletes, got %d", code)
	}
}

func TestKeyring_DeleteBreaksTheCreateOnlyDeadlock(t *testing.T) {
	e := keyringEnvSetup(t)

	// This is the exact stranding scenario. Version 2 is stored under the old MK.
	e.call(t, "PUT", e.deviceA, putBody(0, "sealed-under-old-mk"))
	e.call(t, "PUT", e.deviceA, putBody(1, "sealed-under-old-mk-v2"))

	// A fresh MK cannot name the current version - it cannot even read the blob -
	// so its only available call is the create, which conflicts forever.
	code, body := e.call(t, "PUT", e.deviceA, putBody(0, "sealed-under-new-mk"))
	if code != http.StatusConflict {
		t.Fatalf("expected the deadlock (409), got %d", code)
	}
	if body["server_version"] != float64(2) {
		t.Fatalf("server_version: %v", body["server_version"])
	}

	if code, _ := e.call(t, "DELETE", e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("delete: %d", code)
	}

	// After deletion the create succeeds and the version counter starts over at 1,
	// so the new MK's keyring is not saddled with the retired one's history.
	code, body = e.call(t, "PUT", e.deviceA, putBody(0, "sealed-under-new-mk"))
	if code != http.StatusOK {
		t.Fatalf("create after delete: %d (%v)", code, body)
	}
	if body["version"] != float64(1) || body["created"] != true {
		t.Fatalf("expected a fresh create at v1, got %v", body)
	}
	_, got := e.call(t, "GET", e.deviceA, "")
	raw, _ := base64.StdEncoding.DecodeString(got["ciphertext_b64"].(string))
	if string(raw) != "sealed-under-new-mk" {
		t.Fatalf("stale blob survived: %q", raw)
	}
}

func TestKeyring_DeleteReturnsNoCiphertext(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, "PUT", e.deviceA, putBody(0, "secret-keyring-material"))

	_, body := e.call(t, "DELETE", e.deviceA, "")
	for k := range body {
		if k != "deleted" {
			t.Fatalf("delete response must carry nothing but the outcome, saw %q", k)
		}
	}
}

// ------------------------------------------------- GATE 5 PHASE 4: real races
//
// The CAS test above is sequential, which proves the intended semantics but not
// atomicity: a read-then-write implementation passes it every time and still
// loses writes under genuine contention. These drive real goroutines against
// real Postgres, where a non-atomic update would let two writers both win.

func TestKeyring_ParallelWritersExactlyOneWins(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "v1"))

	const writers = 8
	var wg sync.WaitGroup
	codes := make([]int, writers)
	start := make(chan struct{})
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start // release them together to maximise overlap
			codes[i] = e.callAsync(http.MethodPut, e.deviceA, putBody(1, fmt.Sprintf("writer-%d", i)))
		}(i)
	}
	close(start)
	wg.Wait()

	wins, conflicts := 0, 0
	for _, c := range codes {
		switch c {
		case http.StatusOK:
			wins++
		case http.StatusConflict:
			conflicts++
		default:
			t.Fatalf("unexpected status under contention: %d", c)
		}
	}
	if wins != 1 {
		t.Fatalf("exactly one writer may win, got %d wins and %d conflicts", wins, conflicts)
	}

	// The version must have advanced by exactly one. A lost update would show up
	// here as a version that skipped or repeated.
	_, body := e.call(t, http.MethodGet, e.deviceA, "")
	if body["version"] != float64(2) {
		t.Fatalf("version after one winning write must be 2, got %v", body["version"])
	}
}

func TestKeyring_ParallelCreatesExactlyOneWins(t *testing.T) {
	e := keyringEnvSetup(t)

	// Several devices of one account coming online at once all believe nothing is
	// stored, so they all attempt the create. Only one row may result.
	const creators = 8
	var wg sync.WaitGroup
	codes := make([]int, creators)
	start := make(chan struct{})
	for i := 0; i < creators; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			codes[i] = e.callAsync(http.MethodPut, e.deviceA, putBody(0, fmt.Sprintf("create-%d", i)))
		}(i)
	}
	close(start)
	wg.Wait()

	wins := 0
	for _, c := range codes {
		if c == http.StatusOK {
			wins++
		} else if c != http.StatusConflict {
			t.Fatalf("unexpected status: %d", c)
		}
	}
	if wins != 1 {
		t.Fatalf("exactly one create may succeed, got %d", wins)
	}

	var rows int64
	database.DB.Raw(`SELECT count(*) FROM history_keyring_recoveries WHERE user_id = ?`, e.userA).Scan(&rows)
	if rows != 1 {
		t.Fatalf("the primary key must leave exactly one row, got %d", rows)
	}
	_, body := e.call(t, http.MethodGet, e.deviceA, "")
	if body["version"] != float64(1) {
		t.Fatalf("a contended create must still land at v1, got %v", body["version"])
	}
}

func TestKeyring_DeleteRacingWritersLeavesConsistentState(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "v1"))

	// Deletes and creates interleaving is the realistic shape of a fresh-MK mint
	// racing a live session that is still publishing. The outcome is genuinely
	// nondeterministic, so what is asserted is the invariant, not a winner:
	// whatever survives must be a coherent single row - never two rows, never a
	// version that outran the writes that produced it.
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			if i%2 == 0 {
				e.callAsync(http.MethodDelete, e.deviceA, "")
			} else {
				e.callAsync(http.MethodPut, e.deviceA, putBody(0, fmt.Sprintf("recreate-%d", i)))
			}
		}(i)
	}
	close(start)
	wg.Wait()

	var rows int64
	database.DB.Raw(`SELECT count(*) FROM history_keyring_recoveries WHERE user_id = ?`, e.userA).Scan(&rows)
	if rows > 1 {
		t.Fatalf("a race must never produce more than one row, got %d", rows)
	}

	code, body := e.call(t, http.MethodGet, e.deviceA, "")
	switch code {
	case http.StatusNotFound:
		if rows != 0 {
			t.Fatalf("404 disagrees with %d stored rows", rows)
		}
	case http.StatusOK:
		if rows != 1 {
			t.Fatalf("200 disagrees with %d stored rows", rows)
		}
		v, ok := body["version"].(float64)
		if !ok || v < 1 {
			t.Fatalf("a surviving row must carry a sane version, got %v", body["version"])
		}
		if _, err := base64.StdEncoding.DecodeString(body["ciphertext_b64"].(string)); err != nil {
			t.Fatalf("a surviving row must hold intact ciphertext: %v", err)
		}
	default:
		t.Fatalf("unexpected read status after the race: %d", code)
	}

	// And the account must still be usable afterwards, not wedged.
	if code, _ := e.call(t, http.MethodDelete, e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("delete after the race: %d", code)
	}
	if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(0, "after-the-race")); code != http.StatusOK {
		t.Fatalf("the account must remain able to publish, got %d", code)
	}
}

// ------------------------------------------------------- GATE 6: MK replacement
//
// The server cannot tell a same-MK rewrap from a wholesale MK replacement - it
// never sees MK - so it must not try. What it CAN guarantee, and what these
// tests pin, is that the vault object and the recovery object are independent:
// no vault write of any shape touches the recovery row, and invalidating the
// recovery row is only ever the client's explicit act.

func (e *keyringEnv) putVault(t *testing.T, device string, vaultVersion, expected int) int {
	t.Helper()
	body := fmt.Sprintf(`{"vault_version":%d,"protocol_version":1,"suite":"xchacha20poly1305",
		"vault_ciphertext_b64":%q,"pw_kdf":"argon2id","pw_salt_b64":%q,"pw_params":"{}",
		"pw_wrapped_master_b64":%q,"expected_version":%d}`,
		vaultVersion,
		base64.StdEncoding.EncodeToString([]byte("vault-ciphertext")),
		base64.StdEncoding.EncodeToString([]byte("salt")),
		base64.StdEncoding.EncodeToString([]byte("wrapped-master")),
		expected)
	req := httptest.NewRequest(http.MethodPut, "/vault", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-Id", device)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("vault put: %v", err)
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func (e *keyringEnv) recoveryRowCount(t *testing.T) int64 {
	t.Helper()
	var n int64
	database.DB.Raw(`SELECT count(*) FROM history_keyring_recoveries WHERE user_id = ?`, e.userA).Scan(&n)
	return n
}

func TestKeyring_OrdinaryVaultWritesNeverTouchTheRecoveryRow(t *testing.T) {
	e := keyringEnvSetup(t)

	if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(0, "recovery-material")); code != http.StatusOK {
		t.Fatalf("seed keyring: %d", code)
	}

	// Create the vault, then perform the two shapes of ordinary update that a
	// same-MK lifetime produces: a password rewrap and a contents refresh. Both
	// replace pw_wrapped_master and the ciphertext, and neither is a reset.
	if code := e.putVault(t, e.deviceA, 1, 0); code != http.StatusCreated {
		t.Fatalf("vault create: %d", code)
	}
	if code := e.putVault(t, e.deviceA, 2, 1); code != http.StatusOK {
		t.Fatalf("vault rewrap: %d", code)
	}
	if code := e.putVault(t, e.deviceA, 3, 2); code != http.StatusOK {
		t.Fatalf("vault refresh: %d", code)
	}

	if n := e.recoveryRowCount(t); n != 1 {
		t.Fatalf("vault writes must leave the recovery row alone, found %d rows", n)
	}
	code, body := e.call(t, http.MethodGet, e.deviceA, "")
	if code != http.StatusOK {
		t.Fatalf("recovery blob must survive vault updates, got %d", code)
	}
	raw, _ := base64.StdEncoding.DecodeString(body["ciphertext_b64"].(string))
	if string(raw) != "recovery-material" {
		t.Fatalf("recovery ciphertext altered by a vault write: %q", raw)
	}
	if body["version"] != float64(1) {
		t.Fatalf("recovery version moved during vault writes: %v", body["version"])
	}
}

func TestKeyring_InvalidationDoesNotTouchTheVault(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "recovery-material"))
	if code := e.putVault(t, e.deviceA, 1, 0); code != http.StatusCreated {
		t.Fatalf("vault create: %d", code)
	}

	if code, _ := e.call(t, http.MethodDelete, e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("invalidate: %d", code)
	}

	// The vault is a separate object and must be exactly where it was; the
	// client, not the server, decides when a new one replaces it.
	var vaults int64
	database.DB.Raw(`SELECT count(*) FROM e2_ee_vaults WHERE user_id = ?`, e.userA).Scan(&vaults)
	if vaults != 1 {
		t.Fatalf("recovery invalidation must not delete the vault, found %d", vaults)
	}
	if n := e.recoveryRowCount(t); n != 0 {
		t.Fatalf("recovery row should be gone, found %d", n)
	}
}

func TestKeyring_StaleWriterAfterInvalidationCannotReplayItsVersion(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "v1"))
	e.call(t, http.MethodPut, e.deviceA, putBody(1, "v2")) // now at version 2

	// Reset: the slot is emptied.
	if code, _ := e.call(t, http.MethodDelete, e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("invalidate: %d", code)
	}
	// The new key claims the freed slot immediately - which is what closes the
	// window a stale writer could otherwise use.
	if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(0, "new-mk-blob")); code != http.StatusOK {
		t.Fatalf("new-key create: %d", code)
	}

	// A device still holding the pre-reset version replays it. It must lose.
	code, body := e.call(t, http.MethodPut, e.deviceA, putBody(2, "stale-old-mk-blob"))
	if code != http.StatusConflict {
		t.Fatalf("a stale versioned writer must be rejected, got %d", code)
	}
	if body["server_version"] != float64(1) {
		t.Fatalf("server_version: %v", body["server_version"])
	}

	_, got := e.call(t, http.MethodGet, e.deviceA, "")
	raw, _ := base64.StdEncoding.DecodeString(got["ciphertext_b64"].(string))
	if string(raw) != "new-mk-blob" {
		t.Fatalf("stale writer clobbered the new blob: %q", raw)
	}
}

func TestKeyring_StaleWriterCanWinAnUnclaimedSlotButOnlyBeforeItIsClaimed(t *testing.T) {
	e := keyringEnvSetup(t)
	e.call(t, http.MethodPut, e.deviceA, putBody(0, "old-mk-blob"))
	e.call(t, http.MethodDelete, e.deviceA, "")

	// Documented, deliberately: between invalidation and the new key's first
	// publish the slot is unclaimed, and a create-shaped write from any live
	// device of this account succeeds. That is why the reset sequence revokes
	// other devices FIRST and publishes immediately after invalidating.
	if code, _ := e.call(t, http.MethodPut, e.deviceA, putBody(0, "stale-old-mk-blob")); code != http.StatusOK {
		t.Fatalf("an unclaimed slot accepts a create: %d", code)
	}

	// The state is recoverable with the same primitive and no new protocol.
	if code, _ := e.call(t, http.MethodDelete, e.deviceA, ""); code != http.StatusOK {
		t.Fatalf("re-invalidate: %d", code)
	}
	if code, body := e.call(t, http.MethodPut, e.deviceA, putBody(0, "new-mk-blob")); code != http.StatusOK {
		t.Fatalf("re-claim: %d (%v)", code, body)
	}
	_, got := e.call(t, http.MethodGet, e.deviceA, "")
	raw, _ := base64.StdEncoding.DecodeString(got["ciphertext_b64"].(string))
	if string(raw) != "new-mk-blob" {
		t.Fatalf("account did not converge on the new key: %q", raw)
	}
}

// ------------------------------------------------- GATE 10: vault CAS atomicity
//
// PutVault's update branch reads the row, compares expected_version, and then
// issues an unconditional Save by primary key. Read and write are separate
// statements with no transaction, no row lock, and no conditional UPDATE - so the
// version check is advisory. This test determines whether that is exploitable
// under real concurrency rather than assuming either way.
//
// The keyring handler next door does it correctly (UPDATE ... WHERE version = ?
// plus a RowsAffected check), which is the shape this test measures against.

func (e *keyringEnv) putVaultAsync(vaultVersion, expected int, marker string) int {
	body := fmt.Sprintf(`{"vault_version":%d,"protocol_version":1,"suite":"xchacha20poly1305",
		"vault_ciphertext_b64":%q,"pw_kdf":"argon2id","pw_salt_b64":%q,"pw_params":"{}",
		"pw_wrapped_master_b64":%q,"expected_version":%d}`,
		vaultVersion,
		base64.StdEncoding.EncodeToString([]byte("ciphertext-"+marker)),
		base64.StdEncoding.EncodeToString([]byte("salt")),
		base64.StdEncoding.EncodeToString([]byte("wrapped-master-"+marker)),
		expected)
	req := httptest.NewRequest(http.MethodPut, "/vault", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-Id", e.deviceA)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		return -1
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func TestVault_ConcurrentWritersAtSameExpectedVersion(t *testing.T) {
	e := keyringEnvSetup(t)
	if code := e.putVault(t, e.deviceA, 1, 0); code != http.StatusCreated {
		t.Fatalf("seed vault: %d", code)
	}

	// Every writer names the SAME expected_version. At most one may win: this is
	// the invariant that stops a stale device from overwriting a newer master key.
	const writers = 8
	var wg sync.WaitGroup
	codes := make([]int, writers)
	start := make(chan struct{})
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			codes[i] = e.putVaultAsync(2, 1, fmt.Sprintf("w%d", i))
		}(i)
	}
	close(start)
	wg.Wait()

	wins := 0
	for _, c := range codes {
		switch c {
		case http.StatusOK, http.StatusCreated:
			wins++
		case http.StatusConflict, http.StatusBadRequest:
			// rejected, fine
		default:
			t.Fatalf("unexpected status under contention: %d", c)
		}
	}

	var stored models.E2EEVault
	if err := database.DB.Where("user_id = ?", e.userA).First(&stored).Error; err != nil {
		t.Fatalf("read back: %v", err)
	}

	if wins != 1 {
		t.Fatalf("VAULT CAS IS NOT ATOMIC: %d writers were told they succeeded at the "+
			"same expected_version (stored wrap=%q). A stale device can therefore "+
			"overwrite a newer master key.", wins, string(stored.PwWrappedMaster))
	}
}

// A stale writer that names a version the server has already moved past must be
// refused, even when it races other writers.
func TestVault_StaleExpectedVersionIsAlwaysRejected(t *testing.T) {
	e := keyringEnvSetup(t)
	e.putVault(t, e.deviceA, 1, 0)
	if code := e.putVault(t, e.deviceA, 2, 1); code != http.StatusOK {
		t.Fatalf("advance to v2: %d", code)
	}

	const writers = 6
	var wg sync.WaitGroup
	codes := make([]int, writers)
	start := make(chan struct{})
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			// All of these are stale: they believe the vault is still at v1.
			codes[i] = e.putVaultAsync(2, 1, fmt.Sprintf("stale%d", i))
		}(i)
	}
	close(start)
	wg.Wait()

	for i, c := range codes {
		if c == http.StatusOK || c == http.StatusCreated {
			t.Fatalf("stale writer %d was accepted (status %d); authority moved backward", i, c)
		}
	}

	var stored models.E2EEVault
	database.DB.Where("user_id = ?", e.userA).First(&stored)
	if stored.VaultVersion != 2 {
		t.Fatalf("vault version regressed to %d", stored.VaultVersion)
	}
	if string(stored.PwWrappedMaster) == "wrapped-master-stale0" {
		t.Fatalf("a stale writer's master-key wrap became authoritative")
	}
}

// GATE 10 - the atomicity proof, not a probability test.
//
// The HTTP-level race above did not reproduce in repeated runs, which proves
// nothing: the window between the SELECT and the Save is microseconds wide. This
// test removes the timing question entirely by issuing the two statements in the
// exact order the handler issues them, from two real connections, with the
// interleaving forced. It measures the PATTERN, and compares it against the
// pattern the keyring handler uses.
func TestVault_ReadThenUnconditionalWriteLosesUpdates(t *testing.T) {
	e := keyringEnvSetup(t)
	if code := e.putVault(t, e.deviceA, 1, 0); code != http.StatusCreated {
		t.Fatalf("seed vault: %d", code)
	}

	sqlDB, err := database.DB.DB()
	if err != nil {
		t.Fatalf("raw db: %v", err)
	}
	ctx := context.Background()
	t1, err := sqlDB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatalf("begin t1: %v", err)
	}
	defer t1.Rollback()
	t2, err := sqlDB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatalf("begin t2: %v", err)
	}
	defer t2.Rollback()

	// Both readers observe version 1 - exactly what PutVault's First() does.
	var v1, v2 int
	if err := t1.QueryRowContext(ctx,
		`SELECT vault_version FROM e2_ee_vaults WHERE user_id = $1`, e.userA).Scan(&v1); err != nil {
		t.Fatalf("t1 read: %v", err)
	}
	if err := t2.QueryRowContext(ctx,
		`SELECT vault_version FROM e2_ee_vaults WHERE user_id = $1`, e.userA).Scan(&v2); err != nil {
		t.Fatalf("t2 read: %v", err)
	}
	if v1 != 1 || v2 != 1 {
		t.Fatalf("both readers must see v1, got %d and %d", v1, v2)
	}
	// Both now pass `body.ExpectedVersion != existing.VaultVersion`.

	// --- PATTERN A: what PutVault does - unconditional write by identity.
	if _, err := t1.ExecContext(ctx,
		`UPDATE e2_ee_vaults SET vault_version = 2, pw_wrapped_master = $1 WHERE user_id = $2`,
		[]byte("MK-from-T1"), e.userA); err != nil {
		t.Fatalf("t1 update: %v", err)
	}
	if err := t1.Commit(); err != nil {
		t.Fatalf("t1 commit: %v", err)
	}
	res, err := t2.ExecContext(ctx,
		`UPDATE e2_ee_vaults SET vault_version = 2, pw_wrapped_master = $1 WHERE user_id = $2`,
		[]byte("MK-from-T2"), e.userA)
	if err != nil {
		t.Fatalf("t2 update: %v", err)
	}
	n, _ := res.RowsAffected()
	if err := t2.Commit(); err != nil {
		t.Fatalf("t2 commit: %v", err)
	}

	var stored models.E2EEVault
	database.DB.Where("user_id = ?", e.userA).First(&stored)

	unconditionalLostUpdate := n == 1 && string(stored.PwWrappedMaster) == "MK-from-T2"
	t.Logf("GATE10 unconditional-write pattern: t2 rows=%d stored=%q lostUpdate=%v",
		n, string(stored.PwWrappedMaster), unconditionalLostUpdate)

	// --- PATTERN B: what PutHistoryKeyring does - conditional on the version.
	// Reset to a known state and repeat the same interleaving.
	database.DB.Exec(
		`UPDATE e2_ee_vaults SET vault_version = 1, pw_wrapped_master = $1 WHERE user_id = $2`,
		[]byte("base"), e.userA)

	t3, _ := sqlDB.BeginTx(ctx, nil)
	defer t3.Rollback()
	t4, _ := sqlDB.BeginTx(ctx, nil)
	defer t4.Rollback()
	if _, err := t3.ExecContext(ctx,
		`UPDATE e2_ee_vaults SET vault_version = 2, pw_wrapped_master = $1
		 WHERE user_id = $2 AND vault_version = 1`,
		[]byte("MK-from-T3"), e.userA); err != nil {
		t.Fatalf("t3: %v", err)
	}
	t3.Commit()
	res4, err := t4.ExecContext(ctx,
		`UPDATE e2_ee_vaults SET vault_version = 2, pw_wrapped_master = $1
		 WHERE user_id = $2 AND vault_version = 1`,
		[]byte("MK-from-T4"), e.userA)
	if err != nil {
		t.Fatalf("t4: %v", err)
	}
	n4, _ := res4.RowsAffected()
	t4.Commit()

	var stored2 models.E2EEVault
	database.DB.Where("user_id = ?", e.userA).First(&stored2)
	t.Logf("GATE10 conditional-write pattern: t4 rows=%d stored=%q",
		n4, string(stored2.PwWrappedMaster))

	if n4 != 0 || string(stored2.PwWrappedMaster) != "MK-from-T3" {
		t.Fatalf("the conditional pattern must reject the second writer: rows=%d stored=%q",
			n4, string(stored2.PwWrappedMaster))
	}

	// This is the reason PutVault was changed to the conditional form. The
	// unconditional pattern demonstrably loses updates under this interleaving,
	// which for this row means a stale device destroying a newer master-key wrap.
	// The assertion is on the pattern the handler now ships; the other is recorded
	// so the reason for the change stays visible.
	if !unconditionalLostUpdate {
		t.Logf("note: the unconditional pattern did not lose an update in this run; " +
			"the conditional assertion below is the load-bearing one")
	}
}

// GATE 10 - a DETERMINISTIC handler-level test for the vault CAS.
//
// The concurrency test above never reproduced the defect in dozens of runs: the
// window between the handler's SELECT and its UPDATE is microseconds wide, so
// probability is a poor detector. This test removes the timing entirely.
//
// A row lock is held while the handler runs. Under READ COMMITTED the handler's
// plain SELECT still sees the old version (MVCC does not block reads), so it
// passes its expected_version check - then its UPDATE blocks on the lock. The
// lock holder advances the version and commits. When the handler's UPDATE
// resumes, Postgres re-evaluates its WHERE clause against the new row:
//
//	unconditional (WHERE user_id)                  -> still matches -> LOST UPDATE
//	conditional  (WHERE user_id AND vault_version) -> no match      -> 0 rows -> 409
//
// So this distinguishes the two patterns exactly, every time.
func TestVault_HandlerRejectsAWriteRacedByANewerCommit(t *testing.T) {
	e := keyringEnvSetup(t)
	if code := e.putVault(t, e.deviceA, 1, 0); code != http.StatusCreated {
		t.Fatalf("seed vault: %d", code)
	}

	sqlDB, err := database.DB.DB()
	if err != nil {
		t.Fatalf("raw db: %v", err)
	}
	ctx := context.Background()
	blocker, err := sqlDB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatalf("begin blocker: %v", err)
	}
	defer blocker.Rollback()

	var locked int
	if err := blocker.QueryRowContext(ctx,
		`SELECT vault_version FROM e2_ee_vaults WHERE user_id = $1 FOR UPDATE`,
		e.userA).Scan(&locked); err != nil {
		t.Fatalf("lock row: %v", err)
	}

	// The handler runs while the row is locked. Its read succeeds; its write waits.
	type result struct{ code int }
	done := make(chan result, 1)
	go func() {
		done <- result{e.putVaultAsync(2, 1, "racer")}
	}()

	// Give the handler time to get past its read and block on the write.
	time.Sleep(300 * time.Millisecond)

	// A newer generation commits underneath it - this is the concurrent device.
	if _, err := blocker.ExecContext(ctx,
		`UPDATE e2_ee_vaults SET vault_version = 2, pw_wrapped_master = $1 WHERE user_id = $2`,
		[]byte("MK-from-newer-device"), e.userA); err != nil {
		t.Fatalf("blocker update: %v", err)
	}
	if err := blocker.Commit(); err != nil {
		t.Fatalf("blocker commit: %v", err)
	}

	var got result
	select {
	case got = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("handler did not complete; the row lock may not have been released")
	}

	var stored models.E2EEVault
	if err := database.DB.Where("user_id = ?", e.userA).First(&stored).Error; err != nil {
		t.Fatalf("read back: %v", err)
	}
	t.Logf("GATE10 raced handler: status=%d storedWrap=%q version=%d",
		got.code, string(stored.PwWrappedMaster), stored.VaultVersion)

	if got.code == http.StatusOK || got.code == http.StatusCreated {
		t.Fatalf("the handler accepted a write that a newer commit had already "+
			"superseded (status %d): the version check and the write are not atomic",
			got.code)
	}
	if string(stored.PwWrappedMaster) != "MK-from-newer-device" {
		t.Fatalf("a superseded writer overwrote the newer master-key wrap: stored %q",
			string(stored.PwWrappedMaster))
	}
}

// ================================================= GATE 11: device trust boundary
//
// Device identity arrives as a plain X-Device-Id header. These tests establish
// what that header is actually worth: whether revoking a device removes its
// authority, or merely inconveniences a cooperative client.

// gate11Env wires the SAME middleware chain main.go uses: AuthMiddleware's
// identity is injected directly, then DeviceRevocationGuard at group level, then
// per-route RequireDeviceIdentity where main.go applies it.
type gate11Env struct {
	app     *fiber.App
	userA   uuid.UUID
	deviceA string
}

func gate11Setup(t *testing.T) *gate11Env {
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
		// Release the pool: the scratch server has a finite max_connections
		// and these suites open one pool per test.
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
	})

	e := &gate11Env{userA: uuid.New(), deviceA: "device-A"}
	now := time.Now()
	if err := db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, 'x', ?, ?)`,
		e.userA, "u"+e.userA.String()[:8], e.userA.String()[:8]+"@t.local", now, now).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}
	// Device A exists and is REVOKED.
	db.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.userA, DeviceID: e.deviceA,
		RevokedAt: &now, CreatedAt: now, UpdatedAt: now,
	})

	e.app = fiber.New()
	e.app.Use(func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: e.userA})
		return c.Next()
	})
	// Exactly main.go's ordering.
	e.app.Use(middleware.DeviceRevocationGuard())
	vh := NewE2EEHandler(nil)
	kh := NewKeyringRecoveryHandler()
	e.app.Put("/vault", vh.PutVault) // no RequireDeviceIdentity
	e.app.Put("/history-keyring", middleware.RequireDeviceIdentity(), kh.PutHistoryKeyring)
	return e
}

func (e *gate11Env) send(t *testing.T, method, path, deviceHeader, body string, sendHeader bool) int {
	t.Helper()
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, path, nil)
	} else {
		req = httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
	}
	if sendHeader {
		req.Header.Set("X-Device-Id", deviceHeader)
	}
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func gate11VaultBody(vaultVersion, expected int) string {
	return fmt.Sprintf(`{"vault_version":%d,"protocol_version":1,"suite":"xchacha20poly1305",
		"vault_ciphertext_b64":%q,"pw_kdf":"argon2id","pw_salt_b64":%q,"pw_params":"{}",
		"pw_wrapped_master_b64":%q,"expected_version":%d}`,
		vaultVersion,
		base64.StdEncoding.EncodeToString([]byte("attacker-ciphertext")),
		base64.StdEncoding.EncodeToString([]byte("salt")),
		base64.StdEncoding.EncodeToString([]byte("ATTACKER-MK-WRAP")),
		expected)
}

// Baseline: the guard does work when the revoked device identifies itself.
func TestGate11_RevokedDeviceIsBlockedWhenItIdentifiesItself(t *testing.T) {
	e := gate11Setup(t)
	if code := e.send(t, http.MethodPut, "/vault", e.deviceA, gate11VaultBody(1, 0), true); code != http.StatusForbidden {
		t.Fatalf("a revoked device naming itself must be refused, got %d", code)
	}
}

// EXPLOIT 1: omit the header entirely.
func TestGate11_RevokedDeviceBypassesGuardByOmittingTheHeader(t *testing.T) {
	e := gate11Setup(t)
	code := e.send(t, http.MethodPut, "/vault", "", gate11VaultBody(1, 0), false)
	if code == http.StatusForbidden || code == http.StatusBadRequest {
		return // safe
	}
	var v models.E2EEVault
	database.DB.Where("user_id = ?", e.userA).First(&v)
	t.Fatalf("GATE11 EXPLOIT: a revoked device wrote the vault by omitting X-Device-Id "+
		"(status %d, stored wrap=%q). Revocation does not remove authority on this route.",
		code, string(v.PwWrappedMaster))
}

// EXPLOIT 2: present an unknown device id; the guard AUTO-REGISTERS it, which
// also satisfies the per-route RequireDeviceIdentity that runs afterwards.
func TestGate11_RevokedDeviceBypassesGuardByInventingADeviceId(t *testing.T) {
	e := gate11Setup(t)
	code := e.send(t, http.MethodPut, "/vault", "device-freshly-invented", gate11VaultBody(1, 0), true)
	if code == http.StatusForbidden || code == http.StatusBadRequest {
		return
	}
	t.Fatalf("GATE11 EXPLOIT: a revoked device wrote the vault under a self-chosen "+
		"device id (status %d). The guard auto-registered it as trusted.", code)
}

// EXPLOIT 3: the same trick against a route that DOES carry RequireDeviceIdentity.
func TestGate11_InventedDeviceIdDefeatsRequireDeviceIdentity(t *testing.T) {
	e := gate11Setup(t)
	body := putBody(0, "attacker-keyring")
	code := e.send(t, http.MethodPut, "/history-keyring", "device-freshly-invented-2", body, true)
	if code == http.StatusForbidden || code == http.StatusBadRequest {
		return
	}
	t.Fatalf("GATE11 EXPLOIT: RequireDeviceIdentity accepted a self-chosen device id "+
		"(status %d) because the group-level guard registered it first.", code)
}

// EXPLOIT 4: the decisive one. Revocation marks a row; it does not invalidate the
// access token. RegisterDevice only refuses re-use of the SAME revoked id, so the
// revoked device enrols a new id through the legitimate endpoint and is trusted
// again. If this passes, no amount of route-level hardening restores revocation.
func TestGate11_RevokedDeviceReEnrolsUnderANewIdWithTheSameToken(t *testing.T) {
	e := gate11Setup(t)
	h := NewE2EEHandler(nil)
	e.app.Post("/devices", h.RegisterDevice)

	// Same JWT identity as the revoked device; only the label changes.
	body := `{"device_id":"device-A-reborn","display_name":"same physical device"}`
	code := e.send(t, http.MethodPost, "/devices", "device-A-reborn", body, true)
	if code >= 400 {
		return // re-enrolment refused: revocation is durable
	}
	var d models.E2EEDevice
	if err := database.DB.Where("user_id = ? AND device_id = ?", e.userA, "device-A-reborn").
		First(&d).Error; err != nil {
		t.Fatalf("expected the device row: %v", err)
	}
	if d.RevokedAt != nil {
		return
	}
	// And it now passes the strictest middleware in the codebase.
	kcode := e.send(t, http.MethodPut, "/history-keyring", "device-A-reborn",
		putBody(0, "post-revocation-keyring"), true)
	t.Fatalf("GATE11 EXPLOIT: a revoked device re-enrolled with its still-valid token "+
		"(register=%d) and then passed RequireDeviceIdentity (keyring PUT=%d). "+
		"Revocation is not an authorization boundary.", code, kcode)
}

// ============================================ GATE 11.5: blast-radius capability probe
//
// Non-destructive. Instead of mutating real cryptographic state on every route,
// this mounts the REAL middleware chains behind a sentinel handler and records
// whether a request reaches the handler. Reaching the handler is exactly the
// authorization decision under audit; what the handler then does is already
// covered by that handler's own tests.
//
// Two chains exist in main.go:
//
//	chainJWT    = AuthMiddleware + DeviceRevocationGuard          (vault, pairing,
//	              devices, crypto/public-key, and all 15 /e2ee/mls/* routes)
//	chainDevice = the above + RequireDeviceIdentity               (archives, history-keyring)
func TestGate115_CapabilityMatrixForARevokedActor(t *testing.T) {
	e := gate11Setup(t)
	reached := func(c *fiber.Ctx) error { return c.SendString("REACHED") }
	e.app.Get("/probe/jwt-only", reached)
	e.app.Get("/probe/device-required", middleware.RequireDeviceIdentity(), reached)

	// A device the revoked actor freshly enrols with its still-valid token.
	now := time.Now()
	database.DB.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.userA, DeviceID: "device-reborn",
		CreatedAt: now, UpdatedAt: now,
	})

	profiles := []struct {
		name   string
		header string
		send   bool
	}{
		{"JWT only (no X-Device-Id)", "", false},
		{"JWT + old REVOKED device id", e.deviceA, true},
		{"JWT + fabricated device id", "device-fabricated-" + uuid.New().String()[:8], true},
		{"JWT + newly registered device id", "device-reborn", true},
	}
	chains := []string{"/probe/jwt-only", "/probe/device-required"}

	t.Log("BLAST RADIUS - does the request reach the handler?")
	openCells := 0
	for _, pr := range profiles {
		for _, chain := range chains {
			code := e.send(t, http.MethodGet, chain, pr.header, "", pr.send)
			verdict := "BLOCKED"
			if code == http.StatusOK {
				verdict = "REACHABLE"
				openCells++
			}
			t.Logf("  %-34s %-24s -> %d %s", pr.name, chain, code, verdict)
		}
	}
	// 8 cells; only "old REVOKED device id" should ever be refused. Record the
	// real number so a future fix visibly moves it.
	t.Logf("REACHABLE CELLS: %d of 8", openCells)
	if openCells <= 2 {
		t.Logf("boundary largely holds")
	}
}

// The revocation tombstone is erasable: E2EEDevice has no gorm.DeletedAt, so

// Revocation is fully REVERSIBLE by the attacker, using only legitimate endpoints.
// The revoked device cannot delete itself (the guard refuses a device that names
// itself). But the guard waves through any OTHER device id, so the attacker sends
// a fabricated one, deletes its own revoked row -- E2EEDevice has no
// gorm.DeletedAt, so this is a HARD delete that erases the tombstone -- and the
// original device id is then merely "unknown", which the guard auto-registers.
func TestGate115_RevocationIsFullyReversibleByTheAttacker(t *testing.T) {
	e := gate11Setup(t)
	h := NewE2EEHandler(nil)
	e.app.Delete("/devices/:device_id", h.DeleteDevice)

	// Step 1: prove the honest path is refused.
	if code := e.send(t, http.MethodDelete, "/devices/"+e.deviceA, e.deviceA, "", true); code != http.StatusForbidden {
		t.Logf("note: self-named delete returned %d, expected 403", code)
	}
	// Step 2: same JWT, fabricated header. The guard STILL admits the request --
	// that half is the Gate 11 identity defect and remains deliberately
	// unresolved, proven red by the four TestGate11_* proofs. What Gate 14
	// changed is the second half: the delete can no longer erase the tombstone.
	code := e.send(t, http.MethodDelete, "/devices/"+e.deviceA, "device-fabricated", "", true)
	if code == http.StatusOK {
		t.Fatalf("I11 VIOLATED: a fabricated header deleted a REVOKED device (%d). "+
			"The revocation tombstone must be permanent.", code)
	}
	var n int64
	database.DB.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", e.userA, e.deviceA).Count(&n)
	if n != 1 {
		t.Fatalf("I11 VIOLATED: the revoked row is gone (%d remain) after a delete "+
			"attempt that returned %d", n, code)
	}
	var row models.E2EEDevice
	database.DB.Where("user_id = ? AND device_id = ?", e.userA, e.deviceA).First(&row)
	if row.RevokedAt == nil {
		t.Fatal("I11 VIOLATED: revoked_at was cleared")
	}
	// Step 3: because the tombstone survives, the ORIGINAL device id is refused
	// by the strictest chain in the codebase instead of sailing through as an
	// unknown-and-therefore-auto-registered device.
	kcode := e.send(t, http.MethodPut, "/history-keyring", e.deviceA,
		putBody(0, "keyring-written-by-a-revoked-device"), true)
	if kcode == http.StatusForbidden || kcode == http.StatusBadRequest {
		t.Logf("GATE11.5 chain BLOCKED by Gate 14: delete=%d, tombstone intact, "+
			"original device id refused at the keyring route (%d)", code, kcode)
		return
	}
	t.Fatalf("GATE11.5 EXPLOIT: a revoked device erased its own revocation tombstone "+
		"via a fabricated header (DELETE=%d, hard delete) and its ORIGINAL device id "+
		"then passed RequireDeviceIdentity (keyring PUT=%d). Revocation is reversible.",
		code, kcode)
}
