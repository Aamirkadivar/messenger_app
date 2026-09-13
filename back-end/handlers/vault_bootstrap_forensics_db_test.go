package handlers

import (
	"bytes"
	cryptorand "crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/nacl/box"
	"gorm.io/gorm"
)

// PHASE 61 - forensic model of the vault BOOTSTRAP trust boundary. No production change.
//
// Phase 60 stopped because attaching RequireDeviceIdentity to PUT /e2ee/vault refused a fresh
// account's first write. This suite establishes what material actually exists at that moment and
// what an attacker can do with it, so the next phase can choose an architecture on evidence.
//
// THE KEY THAT MATTERS. Both clients mint an account identity keypair locally BEFORE any vault
// exists and publish its public half to POST /crypto/public-key (Windows:
// AuthService::ensureE2EEKeysAndPublish; Android: ChatRepository around the savePublicKey call).
// The private half lives in DPAPI / EncryptedSharedPreferences, NOT only in the vault. So a device
// proof is cryptographically possible before the first vault write - the Phase 60 deadlock was the
// clients' call ORDER, not a missing secret.
//
// WHAT THAT KEY IS NOT. It is account-wide: every device that unlocks the vault receives the same
// keypair, and revocation does not rotate it. The revocation checks in CreateDeviceChallenge and
// RegisterDevice fire only for a device_id that already has a row, so a revoked holder of that key
// can enrol a FRESH id. TestP61_T2 measures exactly that.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.
type p61Env struct {
	app  *fiber.App
	db   *gorm.DB
	user uuid.UUID
}

func p61Setup(t *testing.T, gateVault bool) *p61Env {
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

	e := &p61Env{db: db, user: uuid.New()}
	now := time.Now()
	if err := db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, 'x', ?, ?)`,
		e.user, "u"+e.user.String()[:8], e.user.String()[:8]+"@t.local", now, now).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}

	e.app = fiber.New()
	e.app.Use(func(c *fiber.Ctx) error {
		claims := &middleware.JWTClaims{UserID: e.user}
		if sid, err := uuid.Parse(c.Get("X-Test-Session")); err == nil {
			claims.SessionID = sid
		}
		c.Locals(middleware.ContextKeyUser, claims)
		return c.Next()
	})
	e.app.Use(middleware.DeviceRevocationGuard())

	h := NewE2EEHandler(nil)
	e.app.Post("/devices/challenge", h.CreateDeviceChallenge)
	e.app.Post("/devices", h.RegisterDevice)
	if gateVault {
		e.app.Put("/vault", middleware.RequireDeviceIdentity(), h.PutVault)
	} else {
		e.app.Put("/vault", h.PutVault)
	}
	return e
}

func (e *p61Env) session(t *testing.T) uuid.UUID {
	t.Helper()
	return seedUnboundSession(t, e.db, e.user)
}

func (e *p61Env) do(t *testing.T, method, path, body, deviceID string, sid uuid.UUID) (int, map[string]any) {
	t.Helper()
	var rdr *bytes.Reader
	if body == "" {
		rdr = bytes.NewReader(nil)
	} else {
		rdr = bytes.NewReader([]byte(body))
	}
	req := httptest.NewRequest(method, path, rdr)
	req.Header.Set("Content-Type", "application/json")
	if deviceID != "" {
		req.Header.Set("X-Device-Id", deviceID)
	}
	if sid != uuid.Nil {
		req.Header.Set("X-Test-Session", sid.String())
	}
	resp, err := e.app.Test(req, 5000)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// enrol runs the REAL Phase 44 challenge/proof for deviceID using the supplied keypair.
//
// The keypair is a parameter on purpose: the whole question in T2 is what happens when the SAME
// account-wide key is reused under a different device id.
func (e *p61Env) enrol(t *testing.T, deviceID string, pub *[32]byte, priv *[32]byte, sid uuid.UUID) int {
	t.Helper()
	chBody, _ := json.Marshal(map[string]string{
		"device_id":  deviceID,
		"public_key": hex.EncodeToString(pub[:]),
	})
	code, out := e.do(t, http.MethodPost, "/devices/challenge", string(chBody), deviceID, sid)
	if code != http.StatusCreated && code != http.StatusOK {
		return code
	}
	sealedB64, _ := out["sealed_b64"].(string)
	senderPubHex, _ := out["sender_pub_hex"].(string)
	challengeID, _ := out["challenge_id"].(string)
	sealed, err := base64.StdEncoding.DecodeString(sealedB64)
	if err != nil || len(sealed) < 24 {
		t.Fatalf("challenge response not usable: %v (%v)", out, err)
	}
	senderPub, err := hex.DecodeString(senderPubHex)
	if err != nil || len(senderPub) != 32 {
		t.Fatalf("sender key not usable: %v", out)
	}
	var nonce [24]byte
	copy(nonce[:], sealed[:24])
	var their [32]byte
	copy(their[:], senderPub)
	proof, ok := box.Open(nil, sealed[24:], &nonce, &their, priv)
	if !ok {
		t.Fatalf("could not open the challenge with this key")
	}
	regBody, _ := json.Marshal(map[string]string{
		"device_id":    deviceID,
		"public_key":   hex.EncodeToString(pub[:]),
		"challenge_id": challengeID,
		"proof_b64":    base64.StdEncoding.EncodeToString(proof),
	})
	code, _ = e.do(t, http.MethodPost, "/devices", string(regBody), deviceID, sid)
	return code
}

func (e *p61Env) vaultState(t *testing.T) string {
	t.Helper()
	var v models.E2EEVault
	if err := e.db.Where("user_id = ?", e.user).First(&v).Error; err != nil {
		return "<absent>"
	}
	return fmt.Sprintf("v=%d wrap=%q", v.VaultVersion, string(v.PwWrappedMaster))
}

func newKeyPair(t *testing.T) (*[32]byte, *[32]byte) {
	t.Helper()
	pub, priv, err := box.GenerateKey(cryptorand.Reader)
	if err != nil {
		t.Fatalf("keygen: %v", err)
	}
	return pub, priv
}

// ============================================================ T1

// An account-authenticated caller that is not an authorized device creates the first vault.
func TestP61_T1_AccountAuthenticatedAttackerBootstrapsTheVault(t *testing.T) {
	e := p61Setup(t, false)
	sid := e.session(t)

	before := e.vaultState(t)
	code, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "", sid)
	after := e.vaultState(t)

	t.Logf("T1 account-authenticated, no device: status=%d %s -> %s", code, before, after)
	if code >= 400 || after == before {
		t.Fatalf("T1 expected the bootstrap to succeed on the current route, got %d", code)
	}
}

// ============================================================ T2 - the decisive one

// A REVOKED device that still holds the account-wide identity key enrols a FRESH device id through
// the real challenge/proof flow, and then mutates the vault through the gated route.
//
// This measures whether pre-vault enrolment (Option A/B) would actually be a trust boundary, or
// only a restatement of "holds the account identity key".
func TestP61_T2_RevokedHolderOfTheAccountKeyReEnrolsUnderAFreshId(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)

	// Device A enrols legitimately, then is revoked.
	sidA := e.session(t)
	if code := e.enrol(t, "device-A", pub, priv, sidA); code >= 400 {
		t.Fatalf("fixture: device-A must enrol, got %d", code)
	}
	now := time.Now()
	if err := e.db.Model(&models.E2EEDevice{}).
		Where("user_id = ? AND device_id = ?", e.user, "device-A").
		Updates(map[string]interface{}{"revoked_at": now}).Error; err != nil {
		t.Fatalf("revoke: %v", err)
	}

	// Same key, brand-new id, brand-new session.
	sidB := e.session(t)
	code := e.enrol(t, "device-A-reborn", pub, priv, sidB)
	t.Logf("T2 re-enrolment of a fresh id with the same account key: status=%d", code)

	if code >= 400 {
		t.Logf("T2 RESULT: revocation blocks re-enrolment - the identity key IS a usable boundary")
		return
	}

	before := e.vaultState(t)
	vcode, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-A-reborn", sidB)
	after := e.vaultState(t)
	t.Logf("T2 RESULT: re-enrolled (%d) and then vault PUT=%d  %s -> %s", code, vcode, before, after)

	if vcode < 400 && before != after {
		t.Logf("T2 FINDING: a revoked holder of the account-wide identity key passes " +
			"RequireDeviceIdentity under a new id and mutates the vault. Gating the route does " +
			"not stop this actor; only rotating or per-device keying would.")
	}
}

// ============================================================ T3 / T4

func TestP61_T3_InventedDeviceIdCannotMutateAGatedVault(t *testing.T) {
	e := p61Setup(t, true)
	sid := e.session(t)
	before := e.vaultState(t)
	code, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-INVENTED", sid)
	after := e.vaultState(t)
	if code < 400 || before != after {
		t.Fatalf("T3: invented id was accepted (%d) or mutated state %s -> %s", code, before, after)
	}
	var n int64
	e.db.Model(&models.E2EEDevice{}).Where("user_id = ?", e.user).Count(&n)
	if n != 0 {
		t.Fatalf("T3: a denied request auto-registered %d device(s)", n)
	}
	t.Logf("T3 invented id: status=%d, no state change, no auto-registration", code)
}

func TestP61_T4_CrossAccountDeviceIdCannotMutateAGatedVault(t *testing.T) {
	e := p61Setup(t, true)
	other := uuid.New()
	now := time.Now()
	e.db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
		VALUES (?, ?, ?, 'x', ?, ?)`,
		other, "o"+other.String()[:8], other.String()[:8]+"@t.local", now, now)
	e.db.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: other, DeviceID: "device-OTHER",
		VerifiedAt: &now, CreatedAt: now, UpdatedAt: now,
	})

	sid := e.session(t)
	before := e.vaultState(t)
	code, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "device-OTHER", sid)
	after := e.vaultState(t)
	if code < 400 || before != after {
		t.Fatalf("T4: another account's device was accepted (%d) or mutated state", code)
	}
	t.Logf("T4 cross-account device id: status=%d, no state change", code)
}

// ============================================================ T5 replay

func TestP61_T5_AConsumedChallengeCannotBeReplayed(t *testing.T) {
	e := p61Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := e.session(t)

	chBody, _ := json.Marshal(map[string]string{
		"device_id": "device-R", "public_key": hex.EncodeToString(pub[:]),
	})
	code, out := e.do(t, http.MethodPost, "/devices/challenge", string(chBody), "device-R", sid)
	if code >= 400 {
		t.Fatalf("challenge: %d", code)
	}
	sealedB64, _ := out["sealed_b64"].(string)
	senderPubHex, _ := out["sender_pub_hex"].(string)
	challengeID, _ := out["challenge_id"].(string)
	sealed, _ := base64.StdEncoding.DecodeString(sealedB64)
	senderPub, _ := hex.DecodeString(senderPubHex)
	var nonce [24]byte
	copy(nonce[:], sealed[:24])
	var their [32]byte
	copy(their[:], senderPub)
	proof, ok := box.Open(nil, sealed[24:], &nonce, &their, priv)
	if !ok {
		t.Fatalf("open failed")
	}
	regBody, _ := json.Marshal(map[string]string{
		"device_id": "device-R", "public_key": hex.EncodeToString(pub[:]),
		"challenge_id": challengeID, "proof_b64": base64.StdEncoding.EncodeToString(proof),
	})
	first, _ := e.do(t, http.MethodPost, "/devices", string(regBody), "device-R", sid)
	if first >= 400 {
		t.Fatalf("first redemption should succeed, got %d", first)
	}
	// Replay the SAME challenge on a fresh session.
	replaySid := e.session(t)
	second, _ := e.do(t, http.MethodPost, "/devices", string(regBody), "device-R", replaySid)
	t.Logf("T5 replay of a consumed challenge: first=%d replay=%d", first, second)
	if second < 400 {
		t.Fatalf("T5: a consumed challenge was accepted again (%d)", second)
	}
}

// ============================================================ T6 create race

// Two clients race to create the first vault. The unique index on user_id is what bounds this.
func TestP61_T6_ConcurrentFirstVaultCreation(t *testing.T) {
	e := p61Setup(t, false)
	sid1 := e.session(t)
	sid2 := e.session(t)

	var wg sync.WaitGroup
	codes := make([]int, 2)
	wg.Add(2)
	for i, sid := range []uuid.UUID{sid1, sid2} {
		go func(idx int, s uuid.UUID) {
			defer wg.Done()
			c, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "", s)
			codes[idx] = c
		}(i, sid)
	}
	wg.Wait()

	var n int64
	e.db.Model(&models.E2EEVault{}).Where("user_id = ?", e.user).Count(&n)
	t.Logf("T6 concurrent first-create: codes=%v rows=%d state=%s", codes, n, e.vaultState(t))
	if n != 1 {
		t.Fatalf("T6: expected exactly one vault row, found %d", n)
	}
}

// ============================================================ CREATE vs UPDATE

// The server decides create-vs-update from its own read, not from the client's expected_version.
func TestP61_CreateVersusUpdateIsServerDecided(t *testing.T) {
	e := p61Setup(t, false)
	sid := e.session(t)

	// A client claiming to update a vault that does not exist is refused.
	code, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 7), "", sid)
	if code != http.StatusConflict {
		t.Fatalf("expected 409 for expected_version on an absent vault, got %d", code)
	}
	// Create, then a stale expected_version is refused.
	if c, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(1, 0), "", sid); c != http.StatusCreated {
		t.Fatalf("create: %d", c)
	}
	afterCreate := e.vaultState(t)
	if c, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(2, 99), "", sid); c != http.StatusConflict {
		t.Fatalf("expected 409 for a stale expected_version, got %d", c)
	}
	if e.vaultState(t) != afterCreate {
		t.Fatalf("a refused update mutated state")
	}
	// A correct expected_version replaces the wrapped master key.
	if c, _ := e.do(t, http.MethodPut, "/vault", gate11VaultBody(2, 1), "", sid); c >= 400 {
		t.Fatalf("legitimate update: %d", c)
	}
	t.Logf("CREATE/UPDATE: absent+expected!=0 -> 409; stale expected -> 409; correct -> replaces wrap")
}
