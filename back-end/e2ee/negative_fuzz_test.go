package e2ee

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
)

func TestNegativeWireDecrypt(t *testing.T) {
	_, file, _, _ := runtime.Caller(0)
	dir := filepath.Clean(filepath.Join(filepath.Dir(file), "..", "..", "test-vectors", "e2ee"))

	v1 := loadJSONMap(t, filepath.Join(dir, "wire-v1-box.json"))
	alicePk := must32(t, v1["alice_pk_hex"])
	bobSk := must32(t, v1["bob_sk_hex"])
	wire, _ := hex.DecodeString(v1["wire_hex"].(string))
	tamper := append([]byte(nil), wire...)
	tamper[len(tamper)-1] ^= 0xff
	if _, ok := openV1(alicePk, bobSk, tamper); ok {
		t.Fatal("tampered v1 box must not open")
	}
	if _, ok := openV1(alicePk, bobSk, wire[:10]); ok {
		t.Fatal("truncated v1 box must not open")
	}

	v2 := loadJSONMap(t, filepath.Join(dir, "wire-v2-eph-box.json"))
	bobSk2 := must32(t, v2["bob_sk_hex"])
	wire2, _ := hex.DecodeString(v2["wire_hex"].(string))
	bad2 := append([]byte(nil), wire2...)
	bad2[len(bad2)-1] ^= 0xff
	if _, ok := openV2(bobSk2, bad2); ok {
		t.Fatal("tampered v2 box must not open")
	}
	if _, ok := openV2(bobSk2, wire2[:20]); ok {
		t.Fatal("truncated v2 box must not open")
	}

	sb := loadJSONMap(t, filepath.Join(dir, "wire-secretbox.json"))
	var key [32]byte
	copy(key[:], mustN(t, sb["key_hex"].(string), 32))
	swire, _ := hex.DecodeString(sb["wire_hex"].(string))
	swire[len(swire)-1] ^= 0xff
	var nonce [24]byte
	copy(nonce[:], swire[:24])
	if _, ok := secretbox.Open(nil, swire[24:], &nonce, &key); ok {
		t.Fatal("tampered secretbox must not open")
	}
}

func TestNegativeVaultAEAD(t *testing.T) {
	vek := bytes.Repeat([]byte{0x11}, 32)
	aad := []byte("vault|u|1|" + SuiteVaultAEAD)
	sealed, err := SealXChaCha(vek, []byte(`{"format_version":1}`), aad)
	if err != nil {
		t.Fatal(err)
	}
	flipped := append([]byte(nil), sealed...)
	flipped[len(flipped)-1] ^= 0x01
	if _, err := OpenXChaCha(vek, flipped, aad); err == nil {
		t.Fatal("tamper must fail")
	}
	if _, err := OpenXChaCha(vek, sealed, []byte("wrong-aad")); err == nil {
		t.Fatal("wrong AAD must fail")
	}
	if _, err := OpenXChaCha(vek, sealed[:8], aad); err == nil {
		t.Fatal("short blob must fail")
	}
	if _, err := DecodeVaultPlaintext([]byte("{")); err == nil {
		t.Fatal("broken JSON must fail")
	}
	if _, err := DecodeVaultPlaintext([]byte(`{"format_version":99}`)); err == nil {
		t.Fatal("unknown vault format must fail")
	}
}

func FuzzParsePairingString(f *testing.F) {
	_, pub, err := GenerateBoxKeyPair()
	if err != nil {
		f.Fatal(err)
	}
	f.Add(FormatPairingString("sess-1", pub))
	f.Add("")
	f.Add("mp1.a.bb")
	f.Fuzz(func(t *testing.T, s string) {
		_, _, _ = ParsePairingString(s)
	})
}

func FuzzOpenXChaCha(f *testing.F) {
	key := bytes.Repeat([]byte{0x09}, 32)
	aad := []byte("fuzz-aad")
	good, err := SealXChaCha(key, []byte("pt"), aad)
	if err != nil {
		f.Fatal(err)
	}
	f.Add(good)
	f.Add([]byte{})
	f.Add([]byte{0, 1, 2, 3})
	f.Fuzz(func(t *testing.T, blob []byte) {
		_, _ = OpenXChaCha(key, blob, aad)
	})
}

func FuzzOpenPairingMK(f *testing.F) {
	priv, pub, err := GenerateBoxKeyPair()
	if err != nil {
		f.Fatal(err)
	}
	mk := bytes.Repeat([]byte{0x07}, MasterKeyBytes)
	sp, sealed, err := SealPairingMK(pub, mk)
	if err != nil {
		f.Fatal(err)
	}
	f.Add(sealed)
	f.Add([]byte{})
	f.Fuzz(func(t *testing.T, blob []byte) {
		_, _ = OpenPairingMK(priv, sp, blob)
	})
}

func FuzzDecodeVaultPlaintext(f *testing.F) {
	good, _ := EncodeVaultPlaintext(VaultPlaintext{
		FormatVersion:   VaultFormatV1,
		ProtocolVersion: 1,
		IdentityPubHex:  "aa",
		IdentityPrivHex: "bb",
	})
	f.Add(string(good))
	f.Add("{")
	f.Add("")
	f.Fuzz(func(t *testing.T, s string) {
		_, _ = DecodeVaultPlaintext([]byte(s))
	})
}

func loadJSONMap(t *testing.T, path string) map[string]any {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func must32(t *testing.T, v any) [32]byte {
	t.Helper()
	var out [32]byte
	copy(out[:], mustN(t, v.(string), 32))
	return out
}

func mustN(t *testing.T, h string, n int) []byte {
	t.Helper()
	b, err := hex.DecodeString(h)
	if err != nil || len(b) != n {
		t.Fatalf("bad hex len=%d", len(b))
	}
	return b
}

func openV1(alicePk, bobSk [32]byte, wire []byte) ([]byte, bool) {
	if len(wire) < 24+box.Overhead {
		return nil, false
	}
	var nonce [24]byte
	copy(nonce[:], wire[:24])
	return box.Open(nil, wire[24:], &nonce, &alicePk, &bobSk)
}

func openV2(bobSk [32]byte, wire []byte) ([]byte, bool) {
	if len(wire) < 32+24+box.Overhead {
		return nil, false
	}
	var ephPk [32]byte
	var n [24]byte
	copy(ephPk[:], wire[:32])
	copy(n[:], wire[32:56])
	return box.Open(nil, wire[56:], &n, &ephPk, &bobSk)
}
