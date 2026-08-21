package e2ee

import (
	"bytes"
	"crypto/rand"
	"testing"

	"golang.org/x/crypto/nacl/box"
)

// TestCurrentRatchetGlareFails documents the simultaneous-initiation (glare)
// defect in the current InitAlice/InitBob handshake: when both parties send
// before receiving, both initialize as Alice with independent random roots and
// neither can open the other's message. This mirrors the production failure
// (both koueosh and mehdi sent first; every v3 open failed).
//
// This test asserts the CURRENT broken behaviour so the fix can flip it.
func TestCurrentRatchetGlareFails(t *testing.T) {
	aPub, aPriv, _ := box.GenerateKey(rand.Reader)
	bPub, bPriv, _ := box.GenerateKey(rand.Reader)
	_ = aPriv
	_ = bPriv

	// Both sides send first, so both take the "sending" path → InitAlice.
	aSession, err := InitAlice(bPub[:])
	if err != nil {
		t.Fatal(err)
	}
	bSession, err := InitAlice(aPub[:])
	if err != nil {
		t.Fatal(err)
	}

	fromA, _ := aSession.Encrypt([]byte("hi from A"))
	fromB, _ := bSession.Encrypt([]byte("hi from B"))

	// Each reads the other's message using its own (Alice) session.
	_, errB := bSession.Decrypt(fromA)
	_, errA := aSession.Decrypt(fromB)

	if errA == nil && errB == nil {
		t.Fatal("expected glare to break decryption in the current handshake, but both succeeded")
	}
	t.Logf("confirmed glare breaks current handshake: A-open-B err=%v, B-open-A err=%v", errA, errB)
}

// TestX3DHLiteGlareConverges is the target behaviour: with the symmetric-root
// X3DH-lite handshake, both parties can send first and still open each other's
// initial messages, then continue a normal ratchet.
func TestX3DHLiteGlareConverges(t *testing.T) {
	aPub, aPriv, _ := box.GenerateKey(rand.Reader)
	bPub, bPriv, _ := box.GenerateKey(rand.Reader)

	a, err := InitSession(aPub[:], aPriv[:], bPub[:])
	if err != nil {
		t.Fatal(err)
	}
	b, err := InitSession(bPub[:], bPriv[:], aPub[:])
	if err != nil {
		t.Fatal(err)
	}

	// GLARE: both send before receiving.
	a1, err := a.EncryptMsg([]byte("A1"))
	if err != nil {
		t.Fatal(err)
	}
	b1, err := b.EncryptMsg([]byte("B1"))
	if err != nil {
		t.Fatal(err)
	}

	gotA1, err := b.DecryptMsg(a1)
	if err != nil {
		t.Fatalf("B could not open A1: %v", err)
	}
	if !bytes.Equal(gotA1, []byte("A1")) {
		t.Fatalf("B got %q", gotA1)
	}
	gotB1, err := a.DecryptMsg(b1)
	if err != nil {
		t.Fatalf("A could not open B1: %v", err)
	}
	if !bytes.Equal(gotB1, []byte("B1")) {
		t.Fatalf("A got %q", gotB1)
	}

	// Continue in both directions to prove the ratchet keeps working post-glare.
	a2, _ := a.EncryptMsg([]byte("A2"))
	if got, err := b.DecryptMsg(a2); err != nil || !bytes.Equal(got, []byte("A2")) {
		t.Fatalf("A2 err=%v got=%q", err, got)
	}
	b2, _ := b.EncryptMsg([]byte("B2"))
	if got, err := a.DecryptMsg(b2); err != nil || !bytes.Equal(got, []byte("B2")) {
		t.Fatalf("B2 err=%v got=%q", err, got)
	}
}

// TestX3DHLiteOrdered covers the clean one-initiator case and out-of-order
// delivery, matching the guarantees the old handshake had.
func TestX3DHLiteOrdered(t *testing.T) {
	aPub, aPriv, _ := box.GenerateKey(rand.Reader)
	bPub, bPriv, _ := box.GenerateKey(rand.Reader)
	a, _ := InitSession(aPub[:], aPriv[:], bPub[:])
	b, _ := InitSession(bPub[:], bPriv[:], aPub[:])

	// A speaks first, B replies, A replies — a normal conversation.
	steps := []struct {
		from *SessionV4
		to   *SessionV4
		msg  string
	}{
		{a, b, "hello"},
		{b, a, "hi back"},
		{a, b, "how are you"},
		{b, a, "good"},
	}
	for i, s := range steps {
		ct, err := s.from.EncryptMsg([]byte(s.msg))
		if err != nil {
			t.Fatalf("step %d encrypt: %v", i, err)
		}
		got, err := s.to.DecryptMsg(ct)
		if err != nil {
			t.Fatalf("step %d decrypt: %v", i, err)
		}
		if !bytes.Equal(got, []byte(s.msg)) {
			t.Fatalf("step %d got %q", i, got)
		}
	}

	// Out-of-order: two from A, deliver second then first.
	m0, _ := a.EncryptMsg([]byte("m0"))
	m1, _ := a.EncryptMsg([]byte("m1"))
	if got, err := b.DecryptMsg(m1); err != nil || !bytes.Equal(got, []byte("m1")) {
		t.Fatalf("ooo m1 err=%v got=%q", err, got)
	}
	if got, err := b.DecryptMsg(m0); err != nil || !bytes.Equal(got, []byte("m0")) {
		t.Fatalf("ooo m0 err=%v got=%q", err, got)
	}
}
