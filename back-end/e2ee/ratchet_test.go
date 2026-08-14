package e2ee

import (
	"bytes"
	"crypto/rand"
	"testing"

	"golang.org/x/crypto/nacl/box"
)

func TestDoubleRatchetRoundTrip(t *testing.T) {
	_, alicePriv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	bobPub, bobPriv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	alice, err := InitAlice(bobPub[:])
	if err != nil {
		t.Fatal(err)
	}
	bob := InitBob(bobPub[:], bobPriv[:])

	c1, err := alice.Encrypt([]byte("hello bob"))
	if err != nil {
		t.Fatal(err)
	}
	p1, err := bob.Decrypt(c1)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(p1, []byte("hello bob")) {
		t.Fatalf("p1 %q", p1)
	}

	c2, err := bob.Encrypt([]byte("hi alice"))
	if err != nil {
		t.Fatal(err)
	}
	p2, err := alice.Decrypt(c2)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(p2, []byte("hi alice")) {
		t.Fatalf("p2 %q", p2)
	}

	c3, err := alice.Encrypt([]byte("third"))
	if err != nil {
		t.Fatal(err)
	}
	p3, err := bob.Decrypt(c3)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(p3, []byte("third")) {
		t.Fatalf("p3 %q", p3)
	}

	// Out-of-order: two Alice messages, decrypt second first.
	alice2, err := InitAlice(bobPub[:])
	if err != nil {
		t.Fatal(err)
	}
	bob2 := InitBob(bobPub[:], bobPriv[:])
	a0, _ := alice2.Encrypt([]byte("A0"))
	a1, _ := alice2.Encrypt([]byte("A1"))
	got1, err := bob2.Decrypt(a1)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got1, []byte("A1")) {
		t.Fatal("ooo A1")
	}
	got0, err := bob2.Decrypt(a0)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got0, []byte("A0")) {
		t.Fatal("ooo A0")
	}

	_ = alicePriv
}
