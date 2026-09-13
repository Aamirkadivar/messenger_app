package handlers

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

// PHASE 65 - a revoked device must not be able to hand trusted material to a new device.
//
// WHAT WAS WRONG. All four pairing routes sat outside RequireDeviceIdentity, and the group-level
// DeviceRevocationGuard only refuses a device that NAMES itself in X-Device-Id. So a revoked device
// could omit the header and complete a pairing handoff, storing the payload that the destination
// then collects.
//
// WHY ONLY ONE ROUTE MOVES. The four endpoints are not peers:
//
//	POST /pairing                      destination-side: a brand-new device asks for a session.
//	GET  /pairing/:id                  destination-side: polling.
//	GET  /pairing/:id/payload          destination-side: collecting what was handed over.
//	POST /pairing/:id/complete         SOURCE-side: an existing device hands over trusted material.
//
// Only the last one is an authorised device acting as a source. The other three are performed by a
// device that is BY DEFINITION not yet authorised - gating them would break the legitimate flow
// this feature exists for. So the gate belongs on completion alone.
//
// Everything runs against an ISOLATED database named by TEST_DATABASE_URL.

type p65Env struct {
	env   *p61Env
	gated bool
}

// p65Setup builds the pairing routes either as they stand (gated=false) or with the candidate gate
// on the completion route only, so before/after can be compared on identical fixtures.
func p65Setup(t *testing.T, gated bool) *p65Env {
	t.Helper()
	e := p61Setup(t, false) // vault route shape is irrelevant here
	h := NewE2EEHandler(nil)
	e.app.Post("/devices/:device_id/revoke", h.RevokeDevice)

	// Destination-side: deliberately deviceless, both before and after.
	e.app.Post("/pairing", h.CreatePairing)
	e.app.Get("/pairing/:session_id", h.GetPairing)
	e.app.Get("/pairing/:session_id/payload", h.TakePairingPayload)

	// Source-side: the only route this phase moves.
	if gated {
		e.app.Post("/pairing/:session_id/complete", middleware.RequireDeviceIdentity(), h.CompletePairing)
	} else {
		e.app.Post("/pairing/:session_id/complete", h.CompletePairing)
	}
	return &p65Env{env: e, gated: gated}
}

func (p *p65Env) createPairing(t *testing.T, sid uuid.UUID) string {
	t.Helper()
	ephPub, _ := newKeyPair(t)
	body, _ := json.Marshal(map[string]string{"ephemeral_pub_hex": hex.EncodeToString(ephPub[:])})
	code, out := p.env.do(t, http.MethodPost, "/pairing", string(body), "", sid)
	if code >= 400 {
		t.Fatalf("create pairing: %d", code)
	}
	id, _ := out["session_id"].(string)
	if id == "" {
		t.Fatalf("no session_id in %v", out)
	}
	return id
}

func (p *p65Env) complete(t *testing.T, pairingID, deviceID string, sid uuid.UUID, senderPub *[32]byte) int {
	t.Helper()
	body, _ := json.Marshal(map[string]string{
		"payload_b64":    "dHJ1c3RlZC1wYWlyaW5nLW1hdGVyaWFs",
		"sender_pub_hex": hex.EncodeToString(senderPub[:]),
	})
	code, _ := p.env.do(t, http.MethodPost, "/pairing/"+pairingID+"/complete", string(body), deviceID, sid)
	return code
}

// payloadStored reports whether trusted material actually reached the row - the thing that must not
// happen when the handoff is refused.
func (p *p65Env) payloadStored(t *testing.T, pairingID string) bool {
	t.Helper()
	var s models.E2EEPairingSession
	if err := p.env.db.Where("user_id = ? AND session_id = ?", p.env.user, pairingID).
		First(&s).Error; err != nil {
		return false
	}
	return len(s.Payload) > 0
}

func (p *p65Env) consumed(t *testing.T, pairingID string) bool {
	t.Helper()
	var s models.E2EEPairingSession
	if err := p.env.db.Where("user_id = ? AND session_id = ?", p.env.user, pairingID).
		First(&s).Error; err != nil {
		return false
	}
	return s.ConsumedAt != nil
}

// enrolAndRevoke gives the account a device that was verified and then revoked through the
// production revocation path.
func (p *p65Env) enrolAndRevoke(t *testing.T, deviceID string) (*[32]byte, *[32]byte, uuid.UUID) {
	t.Helper()
	pub, priv := newKeyPair(t)
	sid := p.env.session(t)
	if code := p.env.enrol(t, deviceID, pub, priv, sid); code >= 400 {
		t.Fatalf("fixture: enrol %s: %d", deviceID, code)
	}
	if rc, _ := p.env.do(t, http.MethodPost, "/devices/"+deviceID+"/revoke", "{}", deviceID, sid); rc >= 400 {
		t.Fatalf("fixture: revoke %s: %d", deviceID, rc)
	}
	return pub, priv, sid
}

// ============================================================ §2 reproduction

// The Phase 64 finding, reproduced against the routes exactly as they stand.
func TestP65_Reproduce_RevokedDeviceCompletesPairing(t *testing.T) {
	p := p65Setup(t, false)
	pub, _, sid := p.enrolAndRevoke(t, "device-A")

	// The device really is revoked and really has no live session.
	var live int64
	p.env.db.Model(&models.Session{}).Where("id = ? AND revoked_at IS NULL", sid).Count(&live)

	destSid := p.env.session(t)
	pairingID := p.createPairing(t, destSid)

	body, _ := json.Marshal(map[string]string{
		"payload_b64":    "dHJ1c3RlZC1wYWlyaW5nLW1hdGVyaWFs",
		"sender_pub_hex": hex.EncodeToString(pub[:]),
	})
	// Header omitted: the permissive guard only refuses a device that names itself.
	code, out := p.env.do(t, http.MethodPost, "/pairing/"+pairingID+"/complete", string(body), "", sid)
	stored := p.payloadStored(t, pairingID)

	// And the destination really does collect what the revoked device handed over.
	dcode, dout := p.env.do(t, http.MethodGet, "/pairing/"+pairingID+"/payload", "", "", destSid)
	_, delivered := dout["payload_b64"]

	t.Logf("REPRODUCED: revoked device completion=%d body=%v payloadStored=%v liveSessions=%d",
		code, out, stored, live)
	t.Logf("REPRODUCED: destination collect=%d materialDelivered=%v pairingConsumed=%v",
		dcode, delivered, p.consumed(t, pairingID))
	if code >= 400 || !stored {
		t.Fatalf("Phase 64 finding did not reproduce: status=%d stored=%v", code, stored)
	}
	if live != 0 {
		t.Fatalf("fixture: revoked device should hold no live session, found %d", live)
	}
}

// ============================================================ §5 required property

// After the gate, the same request is refused BEFORE any material is stored.
func TestP65_Gated_RevokedDeviceCannotCompletePairing(t *testing.T) {
	p := p65Setup(t, true)
	pub, _, sid := p.enrolAndRevoke(t, "device-A")

	destSid := p.env.session(t)
	pairingID := p.createPairing(t, destSid)
	code := p.complete(t, pairingID, "", sid, pub)

	t.Logf("GATED: revoked device completion=%d payloadStored=%v consumed=%v",
		code, p.payloadStored(t, pairingID), p.consumed(t, pairingID))
	if code < 400 {
		t.Fatalf("a revoked device must be refused, got %d", code)
	}
	if p.payloadStored(t, pairingID) {
		t.Fatalf("trusted material was stored despite the refusal")
	}
	if p.consumed(t, pairingID) {
		t.Fatalf("a refused completion consumed the pairing")
	}
}

// A: the legitimate source device still hands over material.
func TestP65_Gated_AuthorisedSourceStillCompletes(t *testing.T) {
	p := p65Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := p.env.session(t)
	if code := p.env.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}

	destSid := p.env.session(t)
	pairingID := p.createPairing(t, destSid)
	code := p.complete(t, pairingID, "device-A", sid, pub)

	t.Logf("A: authorised source completion=%d payloadStored=%v", code, p.payloadStored(t, pairingID))
	if code >= 400 {
		t.Fatalf("the legitimate flow must still work, got %d", code)
	}
	if !p.payloadStored(t, pairingID) {
		t.Fatalf("completion reported %d but stored nothing", code)
	}

	// And the destination still collects it - the deviceless side is untouched.
	pcode, pout := p.env.do(t, http.MethodGet, "/pairing/"+pairingID+"/payload", "", "", destSid)
	if pcode >= 400 {
		t.Fatalf("destination must still collect the payload, got %d", pcode)
	}
	if _, ok := pout["payload_b64"]; !ok {
		t.Fatalf("no payload delivered to the destination: %v", pout)
	}
	t.Logf("A: destination collected the payload (status=%d)", pcode)
}

// ============================================================ §9 negative matrix

func TestP65_Gated_NegativeMatrix(t *testing.T) {
	type row struct {
		name         string
		bindDevice   bool
		header       string
		sendHeader   bool
		otherAccount bool
	}
	rows := []row{
		{name: "C unknown device", bindDevice: false, header: "device-UNKNOWN", sendHeader: true},
		{name: "D session/device mismatch", bindDevice: true, header: "device-OTHER", sendHeader: true},
		{name: "E wrong-account device", bindDevice: false, header: "device-FOREIGN", sendHeader: true, otherAccount: true},
		{name: "no device header at all", bindDevice: false, sendHeader: false},
	}
	for _, r := range rows {
		t.Run(r.name, func(t *testing.T) {
			p := p65Setup(t, true)
			pub, priv := newKeyPair(t)
			sid := p.env.session(t)
			if r.bindDevice {
				if code := p.env.enrol(t, "device-A", pub, priv, sid); code >= 400 {
					t.Fatalf("enrol: %d", code)
				}
			}
			if r.otherAccount {
				other := uuid.New()
				now := time.Now()
				p.env.db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
					VALUES (?, ?, ?, 'x', ?, ?)`,
					other, "o"+other.String()[:8], other.String()[:8]+"@t.local", now, now)
				p.env.db.Create(&models.E2EEDevice{
					ID: uuid.New(), UserID: other, DeviceID: "device-FOREIGN",
					VerifiedAt: &now, CreatedAt: now, UpdatedAt: now,
				})
			}

			destSid := p.env.session(t)
			pairingID := p.createPairing(t, destSid)

			hdr := r.header
			if !r.sendHeader {
				hdr = ""
			}
			body, _ := json.Marshal(map[string]string{
				"payload_b64":    "dHJ1c3RlZC1wYWlyaW5nLW1hdGVyaWFs",
				"sender_pub_hex": hex.EncodeToString(pub[:]),
			})
			code, _ := p.env.do(t, http.MethodPost, "/pairing/"+pairingID+"/complete", string(body), hdr, sid)

			if code < 400 {
				t.Fatalf("%s: expected refusal, got %d", r.name, code)
			}
			if p.payloadStored(t, pairingID) {
				t.Fatalf("%s: refused (%d) but material was stored", r.name, code)
			}
			t.Logf("%-28s status=%d no material stored", r.name, code)
		})
	}
}

// F: revocation landing between creation and completion.
func TestP65_Gated_SourceRevokedBetweenCreationAndCompletion(t *testing.T) {
	p := p65Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := p.env.session(t)
	if code := p.env.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}

	destSid := p.env.session(t)
	pairingID := p.createPairing(t, destSid) // T1: pairing exists, source still authorised

	if rc, _ := p.env.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sid); rc >= 400 {
		t.Fatalf("revoke: %d", rc) // T2
	}

	code := p.complete(t, pairingID, "device-A", sid, pub) // T3
	t.Logf("F: completion after mid-flight revocation=%d payloadStored=%v",
		code, p.payloadStored(t, pairingID))
	if code < 400 {
		t.Fatalf("F: completion must be refused after revocation, got %d", code)
	}
	if p.payloadStored(t, pairingID) {
		t.Fatalf("F: material stored despite refusal")
	}
}

// The reverse ordering: a legitimate completion stands, and revocation afterwards removes the
// device's authority for subsequent protected operations.
func TestP65_Gated_CompletionBeforeRevocationStands(t *testing.T) {
	p := p65Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := p.env.session(t)
	if code := p.env.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	destSid := p.env.session(t)
	pairingID := p.createPairing(t, destSid)

	if code := p.complete(t, pairingID, "device-A", sid, pub); code >= 400 {
		t.Fatalf("legitimate completion: %d", code)
	}
	if !p.payloadStored(t, pairingID) {
		t.Fatalf("legitimate completion stored nothing")
	}

	if rc, _ := p.env.do(t, http.MethodPost, "/devices/device-A/revoke", "{}", "device-A", sid); rc >= 400 {
		t.Fatalf("revoke: %d", rc)
	}
	// A second pairing attempt by the now-revoked device must fail.
	second := p.createPairing(t, p.env.session(t))
	after := p.complete(t, second, "device-A", sid, pub)
	t.Logf("reverse ordering: legitimate completion stood; post-revocation attempt=%d", after)
	if after < 400 {
		t.Fatalf("post-revocation completion must be refused, got %d", after)
	}
}

// G/H/I: pre-existing pairing semantics must be untouched by the gate.
func TestP65_Gated_ExistingPairingSemanticsPreserved(t *testing.T) {
	p := p65Setup(t, true)
	pub, priv := newKeyPair(t)
	sid := p.env.session(t)
	if code := p.env.enrol(t, "device-A", pub, priv, sid); code >= 400 {
		t.Fatalf("enrol: %d", code)
	}
	destSid := p.env.session(t)

	// G: replay after a successful completion.
	pairingID := p.createPairing(t, destSid)
	if code := p.complete(t, pairingID, "device-A", sid, pub); code >= 400 {
		t.Fatalf("first completion: %d", code)
	}
	replay := p.complete(t, pairingID, "device-A", sid, pub)
	t.Logf("G replay after completion: %d", replay)
	if replay < 400 {
		t.Fatalf("G: replay must be refused, got %d", replay)
	}

	// H: expired pairing.
	expired := p.createPairing(t, destSid)
	p.env.db.Model(&models.E2EEPairingSession{}).
		Where("user_id = ? AND session_id = ?", p.env.user, expired).
		Update("expires_at", time.Now().Add(-time.Hour))
	ecode := p.complete(t, expired, "device-A", sid, pub)
	t.Logf("H expired pairing: %d", ecode)
	if ecode < 400 {
		t.Fatalf("H: expired pairing must be refused, got %d", ecode)
	}

	// I: unknown pairing identifier.
	icode := p.complete(t, uuid.New().String(), "device-A", sid, pub)
	t.Logf("I unknown pairing id: %d", icode)
	if icode < 400 {
		t.Fatalf("I: unknown pairing id must be refused, got %d", icode)
	}
}

// J: the destination side stays deviceless - creation and polling must not require a device.
func TestP65_Gated_DevicelessDestinationPreserved(t *testing.T) {
	p := p65Setup(t, true)
	sid := p.env.session(t) // no device anywhere on this session

	pairingID := p.createPairing(t, sid)
	gcode, _ := p.env.do(t, http.MethodGet, "/pairing/"+pairingID, "", "", sid)
	pcode, pout := p.env.do(t, http.MethodGet, "/pairing/"+pairingID+"/payload", "", "", sid)

	t.Logf("J deviceless destination: pairing created, poll=%d payload=%d body=%v", gcode, pcode, pout)
	if gcode >= 400 {
		t.Fatalf("J: a deviceless destination must still poll its pairing, got %d", gcode)
	}
	if _, got := pout["payload_b64"]; got {
		t.Fatalf("J: material delivered with no source device")
	}
}

var _ = fiber.New

// ============================================================ production wiring

// The suites above prove the GATE works. This one proves it is actually ON, by reading the route
// table itself - a middleware silently dropped from a route is precisely the bug being fixed, and
// nothing else in the suite would notice.
func TestP65_ProductionRouteTableGatesOnlyCompletion(t *testing.T) {
	src, err := os.ReadFile(filepath.Join("..", "main.go"))
	if err != nil {
		t.Fatalf("read main.go: %v", err)
	}
	for _, want := range []struct {
		line  string
		gated bool
	}{
		{`e2eeRoutes.Post("/pairing", e2eeHandler.CreatePairing)`, false},
		{`e2eeRoutes.Get("/pairing/:session_id", e2eeHandler.GetPairing)`, false},
		{`e2eeRoutes.Get("/pairing/:session_id/payload", e2eeHandler.TakePairingPayload)`, false},
		{`e2eeRoutes.Post("/pairing/:session_id/complete", middleware.RequireDeviceIdentity(), e2eeHandler.CompletePairing)`, true},
	} {
		if !strings.Contains(string(src), want.line) {
			t.Fatalf("route table no longer contains (gated=%v):\n\t%s", want.gated, want.line)
		}
		t.Logf("wiring OK (gated=%-5v) %s", want.gated, want.line)
	}
}
