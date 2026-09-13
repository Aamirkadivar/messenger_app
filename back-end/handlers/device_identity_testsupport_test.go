package handlers

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/nacl/box"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/models"
)

// Phase 44 changed what "an authorized device" means, and these helpers exist so
// the DB suites can express the new meaning.
//
// Before, a device row was enough: RequireDeviceIdentity looked up X-Device-Id
// and admitted whatever it found, so a test could seed a row and be authorized.
// Phase 43 proved that authorized nothing - an attacker minted rows at will -
// so authorization now resolves through the SESSION instead:
//
//	access token -> sid -> sessions.device_id -> a device with verified_at set
//
// A test therefore needs three things rather than one: a device that has proven
// possession, a live session bound to it, and claims carrying that session's id.
// These helpers build exactly that, without weakening any assertion: they only
// put the fixture into the state a real client reaches by completing the
// proof-of-possession flow.

// seedVerifiedDevice inserts (or upgrades) a device row that has proven
// possession of its key. Returns the row's primary key.
func seedVerifiedDevice(t *testing.T, db *gorm.DB, userID uuid.UUID, deviceID string) uuid.UUID {
	t.Helper()
	now := time.Now()
	var existing models.E2EEDevice
	err := db.Where("user_id = ? AND device_id = ?", userID, deviceID).First(&existing).Error
	if err == nil {
		if err := db.Model(&models.E2EEDevice{}).
			Where("id = ?", existing.ID).
			Update("verified_at", now).Error; err != nil {
			t.Fatalf("seed verified device: %v", err)
		}
		return existing.ID
	}
	d := models.E2EEDevice{
		ID:         uuid.New(),
		UserID:     userID,
		DeviceID:   deviceID,
		VerifiedAt: &now,
		CreatedAt:  now,
		UpdatedAt:  now,
	}
	if err := db.Create(&d).Error; err != nil {
		t.Fatalf("seed verified device: %v", err)
	}
	return d.ID
}

// seedBoundSession creates a live session already bound to deviceID, the state a
// client reaches by completing registration. Returns the session id to put in
// the test's JWT claims.
//
// device_id is written at INSERT rather than by a later UPDATE, so this does not
// depend on the gate17 trigger's write-once transition and cannot be confused
// with testing it.
func seedBoundSession(t *testing.T, db *gorm.DB, userID uuid.UUID, deviceRowID uuid.UUID) uuid.UUID {
	t.Helper()
	now := time.Now()
	// The refresh hash carries a unique index; every session needs its own.
	hash := make([]byte, 32)
	if _, err := rand.Read(hash); err != nil {
		t.Fatalf("seed session hash: %v", err)
	}
	s := models.Session{
		ID:                uuid.New(),
		UserID:            userID,
		DeviceID:          &deviceRowID,
		RefreshHash:       hash,
		HashKeyVersion:    1,
		Generation:        0,
		RefreshExpiresAt:  now.Add(24 * time.Hour),
		AbsoluteExpiresAt: now.Add(96 * time.Hour),
		CreatedAt:         now,
		UpdatedAt:         now,
		LastUsedAt:        now,
	}
	if err := db.Create(&s).Error; err != nil {
		t.Fatalf("seed bound session: %v", err)
	}
	return s.ID
}

// seedVerifiedDeviceSession is the usual one-liner: a verified device plus a
// live session bound to it.
func seedVerifiedDeviceSession(t *testing.T, db *gorm.DB, userID uuid.UUID, deviceID string) uuid.UUID {
	t.Helper()
	return seedBoundSession(t, db, userID, seedVerifiedDevice(t, db, userID, deviceID))
}

// ---------------------------------------------------------------- gate suites

// gate13CurrentUser is the account the currently-built gate app authenticates
// as. The gate suites build one app per test and drive it from helpers that do
// not carry the user id, so it is recorded here at construction time.
var gate13CurrentUser uuid.UUID

// gateDeviceKeys keeps one keypair per (user, device) for the life of a test.
// Re-registering a device must present the SAME key - re-keying an existing
// identity is refused - so the key cannot be regenerated per call.
var gateDeviceKeys = map[string][32]byte{}
var gateDeviceSecrets = map[string][32]byte{}

// gateDeviceKeysMu guards both maps. The concurrency suites call this from
// several goroutines at once (TestGate14_RaceG_ConcurrentRegistrations is the
// usual one), and an unguarded map read racing a write is not a flaky
// assertion - the Go runtime kills the whole test BINARY. Every remaining test
// in the package then never runs, and the output contains no "--- FAIL" line at
// all, so a run that proved nothing looks exactly like a clean one.
var gateDeviceKeysMu sync.Mutex

func gateDeviceKey(userID uuid.UUID, deviceID string) ([32]byte, [32]byte) {
	k := userID.String() + "|" + deviceID
	gateDeviceKeysMu.Lock()
	defer gateDeviceKeysMu.Unlock()
	if pub, ok := gateDeviceKeys[k]; ok {
		return pub, gateDeviceSecrets[k]
	}
	pub, priv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		panic(err)
	}
	gateDeviceKeys[k] = *pub
	gateDeviceSecrets[k] = *priv
	return *pub, *priv
}

// enrolBodyFor completes the Phase 44 proof-of-possession flow for a plain
// registration body and returns the body that actually satisfies it, plus the
// session that proved it.
//
// This exists so the gate suites keep exercising the REAL registration endpoint
// rather than seeding rows behind it. Every assertion they already make about
// registration semantics - revoked identities refused, missing device_id
// rejected, metadata retained - is therefore evaluated against the new
// implementation. A challenge that fails is reported as-is, because the
// client-visible outcome of "register this device" is its first failing step.
func enrolBodyFor(t *testing.T, app *fiber.App, userID uuid.UUID, body string) (string, uuid.UUID, int) {
	t.Helper()
	var parsed map[string]any
	if err := json.Unmarshal([]byte(body), &parsed); err != nil {
		return body, uuid.Nil, 0
	}
	deviceID, _ := parsed["device_id"].(string)
	if deviceID == "" {
		// Nothing to prove, but still a real authenticated caller: hand back a
		// live session so the handler judges the BODY rather than bouncing the
		// request for having no session at all.
		return body, seedUnboundSession(t, database.DB, userID), 0
	}
	if _, already := parsed["challenge_id"]; already {
		return body, seedUnboundSession(t, database.DB, userID), 0
	}

	pub, priv := gateDeviceKey(userID, deviceID)
	pubHex := hex.EncodeToString(pub[:])
	sid := seedUnboundSession(t, database.DB, userID)

	chBody, _ := json.Marshal(map[string]string{"device_id": deviceID, "public_key": pubHex})
	req := httptest.NewRequest(http.MethodPost, "/devices/challenge", bytes.NewReader(chBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-Id", deviceID)
	req.Header.Set("X-Test-Session", sid.String())
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("challenge request: %v", err)
	}
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusCreated {
		return body, sid, resp.StatusCode
	}
	var ch struct {
		ChallengeID  string `json:"challenge_id"`
		SenderPubHex string `json:"sender_pub_hex"`
		SealedB64    string `json:"sealed_b64"`
	}
	if err := json.Unmarshal(raw, &ch); err != nil {
		t.Fatalf("challenge decode: %v", err)
	}
	sp, err := hex.DecodeString(ch.SenderPubHex)
	if err != nil || len(sp) != 32 {
		t.Fatalf("bad sender pub")
	}
	sealed, err := base64.StdEncoding.DecodeString(ch.SealedB64)
	if err != nil || len(sealed) < 24+box.Overhead {
		t.Fatalf("bad sealed challenge")
	}
	var nonce [24]byte
	copy(nonce[:], sealed[:24])
	var their [32]byte
	copy(their[:], sp)
	proof, ok := box.Open(nil, sealed[24:], &nonce, &their, &priv)
	if !ok {
		t.Fatalf("could not open the challenge")
	}

	parsed["public_key"] = pubHex
	parsed["challenge_id"] = ch.ChallengeID
	parsed["proof_b64"] = base64.StdEncoding.EncodeToString(proof)
	out, _ := json.Marshal(parsed)
	return string(out), sid, 0
}

// seedUnboundSession is a live session that has proven nothing yet - the state
// immediately after login, and the only state registration will bind.
func seedUnboundSession(t *testing.T, db *gorm.DB, userID uuid.UUID) uuid.UUID {
	t.Helper()
	now := time.Now()
	hash := make([]byte, 32)
	if _, err := rand.Read(hash); err != nil {
		t.Fatalf("seed session hash: %v", err)
	}
	s := models.Session{
		ID: uuid.New(), UserID: userID, RefreshHash: hash, HashKeyVersion: 1,
		RefreshExpiresAt: now.Add(24 * time.Hour), AbsoluteExpiresAt: now.Add(96 * time.Hour),
		CreatedAt: now, UpdatedAt: now, LastUsedAt: now,
	}
	if err := db.Create(&s).Error; err != nil {
		t.Fatalf("seed unbound session: %v", err)
	}
	return s.ID
}
