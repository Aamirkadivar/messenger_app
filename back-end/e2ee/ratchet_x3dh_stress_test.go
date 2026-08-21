package e2ee

import (
	"bytes"
	"crypto/rand"
	"fmt"
	"testing"

	"golang.org/x/crypto/nacl/box"
)

func newPair(t *testing.T) (*SessionV4, *SessionV4) {
	t.Helper()
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
	return a, b
}

// TestX3DHLiteLongInterleaved runs many back-and-forth turns, which forces
// repeated DH ratchets in both directions.
func TestX3DHLiteLongInterleaved(t *testing.T) {
	a, b := newPair(t)
	for i := 0; i < 50; i++ {
		msg := []byte(fmt.Sprintf("a->b %d", i))
		ct, err := a.EncryptMsg(msg)
		if err != nil {
			t.Fatalf("a enc %d: %v", i, err)
		}
		if got, err := b.DecryptMsg(ct); err != nil || !bytes.Equal(got, msg) {
			t.Fatalf("b dec %d: err=%v got=%q", i, err, got)
		}
		msg2 := []byte(fmt.Sprintf("b->a %d", i))
		ct2, err := b.EncryptMsg(msg2)
		if err != nil {
			t.Fatalf("b enc %d: %v", i, err)
		}
		if got, err := a.DecryptMsg(ct2); err != nil || !bytes.Equal(got, msg2) {
			t.Fatalf("a dec %d: err=%v got=%q", i, err, got)
		}
	}
}

// TestX3DHLiteBurstsThenReorder sends bursts in each direction and delivers each
// burst out of order, crossing DH-ratchet boundaries.
func TestX3DHLiteBurstsThenReorder(t *testing.T) {
	a, b := newPair(t)

	// A burst, delivered reversed.
	var aCts [][]byte
	for i := 0; i < 5; i++ {
		ct, _ := a.EncryptMsg([]byte(fmt.Sprintf("A%d", i)))
		aCts = append(aCts, ct)
	}
	for i := len(aCts) - 1; i >= 0; i-- {
		want := []byte(fmt.Sprintf("A%d", i))
		if got, err := b.DecryptMsg(aCts[i]); err != nil || !bytes.Equal(got, want) {
			t.Fatalf("reorder A%d: err=%v got=%q", i, err, got)
		}
	}

	// B burst back, delivered reversed (this is a new DH ratchet turn).
	var bCts [][]byte
	for i := 0; i < 5; i++ {
		ct, _ := b.EncryptMsg([]byte(fmt.Sprintf("B%d", i)))
		bCts = append(bCts, ct)
	}
	for i := len(bCts) - 1; i >= 0; i-- {
		want := []byte(fmt.Sprintf("B%d", i))
		if got, err := a.DecryptMsg(bCts[i]); err != nil || !bytes.Equal(got, want) {
			t.Fatalf("reorder B%d: err=%v got=%q", i, err, got)
		}
	}
}

// TestX3DHLiteDeepGlare has both sides send several messages before either
// receives, then cross-delivers everything.
func TestX3DHLiteDeepGlare(t *testing.T) {
	a, b := newPair(t)
	var aCts, bCts [][]byte
	for i := 0; i < 4; i++ {
		ca, _ := a.EncryptMsg([]byte(fmt.Sprintf("A%d", i)))
		cb, _ := b.EncryptMsg([]byte(fmt.Sprintf("B%d", i)))
		aCts = append(aCts, ca)
		bCts = append(bCts, cb)
	}
	for i := 0; i < 4; i++ {
		if got, err := b.DecryptMsg(aCts[i]); err != nil || !bytes.Equal(got, []byte(fmt.Sprintf("A%d", i))) {
			t.Fatalf("deep glare A%d: err=%v got=%q", i, err, got)
		}
		if got, err := a.DecryptMsg(bCts[i]); err != nil || !bytes.Equal(got, []byte(fmt.Sprintf("B%d", i))) {
			t.Fatalf("deep glare B%d: err=%v got=%q", i, err, got)
		}
	}
	// And keep talking afterwards.
	ct, _ := a.EncryptMsg([]byte("after"))
	if got, err := b.DecryptMsg(ct); err != nil || !bytes.Equal(got, []byte("after")) {
		t.Fatalf("post-glare: err=%v got=%q", err, got)
	}
}

// TestX3DHLiteMalformedFailsSafe feeds corrupt/truncated ciphertext and requires
// a clean error, never a panic or a false-positive open.
func TestX3DHLiteMalformedFailsSafe(t *testing.T) {
	a, b := newPair(t)
	good, _ := a.EncryptMsg([]byte("real"))

	cases := [][]byte{
		nil,
		{},
		{msgTypeInitial},
		good[:10],
		good[:x3dhHeaderLen], // header only, no body
	}
	// A body-tampered copy.
	tampered := append([]byte(nil), good...)
	tampered[len(tampered)-1] ^= 0xff
	cases = append(cases, tampered)
	// A wrong type byte on an otherwise valid frame.
	wrongType := append([]byte(nil), good...)
	wrongType[0] = msgTypeNormal
	cases = append(cases, wrongType)

	for i, c := range cases {
		func() {
			defer func() {
				if r := recover(); r != nil {
					t.Fatalf("case %d panicked: %v", i, r)
				}
			}()
			if pt, err := b.DecryptMsg(c); err == nil {
				t.Fatalf("case %d: expected error, opened %q", i, pt)
			}
		}()
	}

	// After all the garbage, the real message still opens (state not corrupted).
	if got, err := b.DecryptMsg(good); err != nil || !bytes.Equal(got, []byte("real")) {
		t.Fatalf("good after garbage: err=%v got=%q", err, got)
	}
}
