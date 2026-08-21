package e2ee

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/crypto/curve25519"
)

// Canonical cross-platform vectors for the v4 X3DH-lite ratchet.
//
// Two files are produced in ../../test-vectors/e2ee/:
//
//   x3dh-root.json      deterministic RK0 from fixed identity keys. Every
//                       platform MUST compute the same RK0 from either side.
//   x3dh-transcript.json a frozen two-party conversation (including glare):
//                       each entry is a ciphertext + expected plaintext. A
//                       platform rebuilds both sessions from the fixed
//                       identities and DECRYPTS each frozen ciphertext in
//                       order, asserting the plaintext. Decrypt is
//                       deterministic given state + ciphertext, so no platform
//                       needs to reproduce the sender's randomness.
//
// Regenerate with:  E2EE_GENVEC=1 go test ./e2ee/ -run TestX3DHVectors
// Verify (default): go test ./e2ee/ -run TestX3DHVectors

const vectorDir = "../../test-vectors/e2ee"

// fixedIdentity derives a deterministic Curve25519 identity keypair from a seed
// byte, so the vectors carry stable secret/public keys usable by every platform
// (libsodium crypto_box keys are the same Curve25519 keys).
func fixedIdentity(seed byte) (pk, sk []byte) {
	sk = make([]byte, 32)
	for i := range sk {
		sk[i] = seed ^ byte(i*7+1)
	}
	pkArr, _ := curve25519.X25519(sk, curve25519.Basepoint)
	return pkArr, sk
}

type rootVector struct {
	Suite string `json:"suite"`
	APk   string `json:"a_id_pk"`
	ASk   string `json:"a_id_sk"`
	BPk   string `json:"b_id_pk"`
	BSk   string `json:"b_id_sk"`
	RK0   string `json:"rk0"`
}

type transcriptEntry struct {
	From          string `json:"from"` // "A" or "B" (the sender)
	CiphertextHex string `json:"ciphertext_hex"`
	Plaintext     string `json:"plaintext"`
}

type transcriptVector struct {
	Suite     string            `json:"suite"`
	Note      string            `json:"note"`
	APk       string            `json:"a_id_pk"`
	ASk       string            `json:"a_id_sk"`
	BPk       string            `json:"b_id_pk"`
	BSk       string            `json:"b_id_sk"`
	Transcript []transcriptEntry `json:"transcript"`
}

func TestX3DHVectors(t *testing.T) {
	aPk, aSk := fixedIdentity(0xA1)
	bPk, bSk := fixedIdentity(0xB2)

	rootPath := filepath.Join(vectorDir, "x3dh-root.json")
	transcriptPath := filepath.Join(vectorDir, "x3dh-transcript.json")

	if os.Getenv("E2EE_GENVEC") == "1" {
		generateX3DHVectors(t, aPk, aSk, bPk, bSk, rootPath, transcriptPath)
		return
	}

	// ---- Verify root ----
	var rv rootVector
	readJSON(t, rootPath, &rv)
	// Recompute RK0 from A's side and from B's side; both must equal the vector.
	rk0A, err := symmetricRoot(mustHex(t, rv.ASk), mustHex(t, rv.APk), mustHex(t, rv.BPk))
	if err != nil {
		t.Fatal(err)
	}
	rk0B, err := symmetricRoot(mustHex(t, rv.BSk), mustHex(t, rv.BPk), mustHex(t, rv.APk))
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(rk0A) != rv.RK0 || hex.EncodeToString(rk0B) != rv.RK0 {
		t.Fatalf("RK0 mismatch:\n a=%x\n b=%x\n vec=%s", rk0A, rk0B, rv.RK0)
	}

	// ---- Verify transcript ----
	var tv transcriptVector
	readJSON(t, transcriptPath, &tv)
	sessA, err := InitSession(mustHex(t, tv.APk), mustHex(t, tv.ASk), mustHex(t, tv.BPk))
	if err != nil {
		t.Fatal(err)
	}
	sessB, err := InitSession(mustHex(t, tv.BPk), mustHex(t, tv.BSk), mustHex(t, tv.APk))
	if err != nil {
		t.Fatal(err)
	}
	for i, e := range tv.Transcript {
		ct := mustHex(t, e.CiphertextHex)
		var got []byte
		if e.From == "A" {
			got, err = sessB.DecryptMsg(ct) // B receives A's message
		} else {
			got, err = sessA.DecryptMsg(ct) // A receives B's message
		}
		if err != nil {
			t.Fatalf("transcript[%d] from %s: decrypt error: %v", i, e.From, err)
		}
		if string(got) != e.Plaintext {
			t.Fatalf("transcript[%d] from %s: got %q want %q", i, e.From, got, e.Plaintext)
		}
	}
	t.Logf("verified RK0 + %d transcript messages", len(tv.Transcript))
}

func generateX3DHVectors(t *testing.T, aPk, aSk, bPk, bSk []byte, rootPath, transcriptPath string) {
	rk0, err := symmetricRoot(aSk, aPk, bPk)
	if err != nil {
		t.Fatal(err)
	}
	rv := rootVector{
		Suite: SuiteDRX3DHLiteV4,
		APk:   hex.EncodeToString(aPk), ASk: hex.EncodeToString(aSk),
		BPk: hex.EncodeToString(bPk), BSk: hex.EncodeToString(bSk),
		RK0: hex.EncodeToString(rk0),
	}
	writeJSON(t, rootPath, rv)

	// One-directional transcript: A sends a chain of messages to B (all INITIAL,
	// since A never receives), recorded so a pure-decrypt replay reproduces them.
	// This pins RK0, the INITIAL handshake, the wire framing, the sequential
	// chain KDF, and skipped-key (out-of-order) handling. Turn/glare continuation
	// depends on the receiver's own randomness and is covered instead by the
	// in-code stress tests each platform ports (TestX3DHLite*).
	a, _ := InitSession(aPk, aSk, bPk)

	msgs := []string{"m0", "m1", "m2", "m3", "m4"}
	cts := make(map[string]string, len(msgs))
	for _, m := range msgs {
		ct, err := a.EncryptMsg([]byte(m))
		if err != nil {
			t.Fatal(err)
		}
		cts[m] = hex.EncodeToString(ct)
	}

	// Deliver out of order to exercise skipped-key handling: m0, m2, m4, m1, m3.
	order := []string{"m0", "m2", "m4", "m1", "m3"}
	var tr []transcriptEntry
	for _, m := range order {
		tr = append(tr, transcriptEntry{From: "A", CiphertextHex: cts[m], Plaintext: m})
	}

	// Self-check the exact replay a verifier will perform.
	check, _ := InitSession(bPk, bSk, aPk)
	for _, e := range tr {
		got, err := check.DecryptMsg(mustHexNoT(e.CiphertextHex))
		if err != nil || string(got) != e.Plaintext {
			t.Fatalf("generator self-check failed for %q: err=%v got=%q", e.Plaintext, err, got)
		}
	}

	tv := transcriptVector{
		Suite:      SuiteDRX3DHLiteV4,
		Note:       "One-directional v4 transcript (A->B), delivered out of order (m0,m2,m4,m1,m3). Rebuild B's session from the fixed identities and DecryptMsg each ciphertext in listed order; each must yield the plaintext. Turn/glare continuation is covered by the ported TestX3DHLite* stress tests.",
		APk:        hex.EncodeToString(aPk), ASk: hex.EncodeToString(aSk),
		BPk: hex.EncodeToString(bPk), BSk: hex.EncodeToString(bSk),
		Transcript: tr,
	}
	writeJSON(t, transcriptPath, tv)
	t.Logf("wrote %s and %s (%d messages)", rootPath, transcriptPath, len(tr))
}

func readJSON(t *testing.T, path string, v interface{}) {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v (regenerate with E2EE_GENVEC=1)", path, err)
	}
	if err := json.Unmarshal(b, v); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}
}

func writeJSON(t *testing.T, path string, v interface{}) {
	t.Helper()
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
}

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func mustHexNoT(s string) []byte {
	b, _ := hex.DecodeString(s)
	return b
}
