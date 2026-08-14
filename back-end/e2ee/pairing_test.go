package e2ee

import (
	"bytes"
	"encoding/hex"
	"strings"
	"testing"
)

func TestPairingMKRoundTrip(t *testing.T) {
	recvPriv, recvPub, err := GenerateBoxKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	mk, err := RandomBytes(MasterKeyBytes)
	if err != nil {
		t.Fatal(err)
	}
	senderPub, sealed, err := SealPairingMK(recvPub, mk)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := OpenPairingMK(recvPriv, senderPub, sealed)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(opened, mk) {
		t.Fatal("mk mismatch")
	}
}

func TestParsePairingString(t *testing.T) {
	_, pub, err := GenerateBoxKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	s := FormatPairingString("abc-123", pub)
	sid, epk, err := ParsePairingString(s)
	if err != nil {
		t.Fatal(err)
	}
	if sid != "abc-123" || !bytes.Equal(epk, pub) {
		t.Fatal("parse mismatch")
	}
	bads := []string{
		"",
		"mp1",
		"mp1.",
		"mp1.abc.",
		"mp2.abc." + hex.EncodeToString(pub),
		"mp1.." + hex.EncodeToString(pub),
		"mp1.abc.zz",
		"mp1.abc." + hex.EncodeToString(pub[:16]),
		strings.Repeat("x", 300),
	}
	for _, b := range bads {
		if _, _, err := ParsePairingString(b); err == nil {
			t.Fatalf("expected reject %q", b)
		}
	}
}