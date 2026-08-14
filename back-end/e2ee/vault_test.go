package e2ee

import (
	"bytes"
	"encoding/hex"
	"testing"
)

func TestArgon2idDeterministic(t *testing.T) {
	// libsodium crypto_pwhash requires exactly 16-byte salts.
	salt, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f")
	p := Argon2idParams{MemoryKiB: 8, Time: 1, Threads: 1, KeyLen: 32} // tiny for unit test
	a, err := DerivePasswordKEK("test-password", salt, p)
	if err != nil {
		t.Fatal(err)
	}
	b, err := DerivePasswordKEK("test-password", salt, p)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(a, b) {
		t.Fatal("argon2id not deterministic")
	}
	c, _ := DerivePasswordKEK("other-password", salt, p)
	if bytes.Equal(a, c) {
		t.Fatal("different passwords must differ")
	}
	t.Logf("argon2id vector hex=%s", hex.EncodeToString(a))
	want, _ := hex.DecodeString("9fa0abb11199ab693762aa0c705ad4a8b9e61e60d0d6c7607164d6e54fb15e73")
	if !bytes.Equal(a, want) {
		t.Fatalf("argon2id golden mismatch: got %s", hex.EncodeToString(a))
	}
}

func TestWrapRoundTrip(t *testing.T) {
	kek, _ := RandomBytes(32)
	mk, _ := RandomBytes(32)
	aad := MasterKeyAAD("user-1", "password")
	sealed, err := WrapKey(kek, mk, aad)
	if err != nil {
		t.Fatal(err)
	}
	out, err := UnwrapKey(kek, sealed, aad)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(out, mk) {
		t.Fatal("unwrap mismatch")
	}
	if _, err := UnwrapKey(kek, sealed, MasterKeyAAD("user-1", "wrong")); err == nil {
		t.Fatal("expected AAD failure")
	}
}

func TestVaultSealRoundTrip(t *testing.T) {
	v := VaultPlaintext{
		FormatVersion:   VaultFormatV1,
		ProtocolVersion: ProtocolVersionV1,
		SuiteIDs:        []string{SuiteNaclBox, SuiteVaultAEAD},
		IdentityPubHex:  "aa",
		IdentityPrivHex: "bb",
		OwnSenderKeys: map[string]string{
			"chat|1": "aa",
			"chat|2": "bb",
		},
		PeerSenderKeys: map[string]string{
			"chat|sender|1": "ff",
		},
	}
	plain, err := EncodeVaultPlaintext(v)
	if err != nil {
		t.Fatal(err)
	}
	vek, _ := RandomBytes(32)
	sealed, err := SealVault(vek, plain, "user-uuid", 1)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := OpenVault(vek, sealed, "user-uuid", 1)
	if err != nil {
		t.Fatal(err)
	}
	got, err := DecodeVaultPlaintext(opened)
	if err != nil {
		t.Fatal(err)
	}
	if got.IdentityPrivHex != "bb" || got.OwnSenderKeys["chat|1"] != "aa" || got.OwnSenderKeys["chat|2"] != "bb" {
		t.Fatalf("vault decode mismatch: %+v", got)
	}
	if got.PeerSenderKeys["chat|sender|1"] != "ff" {
		t.Fatalf("peer sender keys missing: %+v", got.PeerSenderKeys)
	}
	if _, err := OpenVault(vek, sealed, "user-uuid", 2); err == nil {
		t.Fatal("expected version AAD failure")
	}
}

func TestRecoveryKekDeterministic(t *testing.T) {
	salt, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f")
	rk, _ := hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f")
	a, err := DeriveRecoveryKEK(rk, salt)
	if err != nil {
		t.Fatal(err)
	}
	b, err := DeriveRecoveryKEK(rk, salt)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(a, b) {
		t.Fatal("recovery kek not deterministic")
	}
	t.Logf("recovery kek hex=%s", hex.EncodeToString(a))
}

func TestTamperFails(t *testing.T) {
	kek, _ := RandomBytes(32)
	mk, _ := RandomBytes(32)
	aad := []byte("aad")
	sealed, _ := WrapKey(kek, mk, aad)
	sealed[len(sealed)-1] ^= 0xff
	if _, err := UnwrapKey(kek, sealed, aad); err == nil {
		t.Fatal("expected auth failure on tamper")
	}
}