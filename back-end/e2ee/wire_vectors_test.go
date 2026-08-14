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

type ctrReader struct{ n byte }

func (c *ctrReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = c.n
		c.n++
	}
	return len(p), nil
}

func mustKeyPair(t *testing.T, seed byte) (pk, sk [32]byte) {
	t.Helper()
	pub, priv, err := box.GenerateKey(&ctrReader{n: seed})
	if err != nil {
		t.Fatal(err)
	}
	return *pub, *priv
}

func vectorsDir(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	dir := filepath.Clean(filepath.Join(filepath.Dir(file), "..", "..", "test-vectors", "e2ee"))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	return dir
}

func writeOrCheckJSON(t *testing.T, name string, got any) {
	t.Helper()
	path := filepath.Join(vectorsDir(t), name)
	raw, err := json.MarshalIndent(got, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	raw = append(raw, '\n')
	if os.Getenv("WRITE_VECTORS") == "1" {
		if err := os.WriteFile(path, raw, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("missing %s (run WRITE_VECTORS=1 go test ./e2ee/): %v", path, err)
	}
	if !bytes.Equal(bytes.ReplaceAll(want, []byte("\r\n"), []byte("\n")), raw) {
		t.Fatalf("%s mismatch\n got:\n%s\nwant:\n%s", name, raw, want)
	}
}

func TestVectorDirectBoxV1(t *testing.T) {
	alicePk, aliceSk := mustKeyPair(t, 1)
	bobPk, bobSk := mustKeyPair(t, 40)
	var nonce [24]byte
	for i := range nonce {
		nonce[i] = byte(0xA0 + i)
	}
	plain := []byte("hello-e2ee-v1")
	wire := box.Seal(nonce[:], plain, &nonce, &bobPk, &aliceSk)
	opened, ok := box.Open(nil, wire[24:], &nonce, &alicePk, &bobSk)
	if !ok || !bytes.Equal(opened, plain) {
		t.Fatal("v1 box roundtrip failed")
	}
	writeOrCheckJSON(t, "wire-v1-box.json", map[string]any{
		"suite":              SuiteNaclBox,
		"encryption_version": 1,
		"wire":               "hex(nonce[24] || crypto_box_easy(ct))",
		"plaintext":          string(plain),
		"alice_pk_hex":       hex.EncodeToString(alicePk[:]),
		"alice_sk_hex":       hex.EncodeToString(aliceSk[:]),
		"bob_pk_hex":         hex.EncodeToString(bobPk[:]),
		"bob_sk_hex":         hex.EncodeToString(bobSk[:]),
		"nonce_hex":          hex.EncodeToString(nonce[:]),
		"wire_hex":           hex.EncodeToString(wire),
	})
}

func TestVectorDirectBoxV2(t *testing.T) {
	_, _ = mustKeyPair(t, 1) // alice unused for v2 sender identity
	bobPk, bobSk := mustKeyPair(t, 40)
	ephPk, ephSk := mustKeyPair(t, 80)
	var nonce [24]byte
	for i := range nonce {
		nonce[i] = byte(0xB0 + i)
	}
	plain := []byte("hello-e2ee-v2")
	ct := box.Seal(nil, plain, &nonce, &bobPk, &ephSk)
	wire := append(append(ephPk[:], nonce[:]...), ct...)
	opened, ok := box.Open(nil, ct, &nonce, &ephPk, &bobSk)
	if !ok || !bytes.Equal(opened, plain) {
		t.Fatal("v2 eph box roundtrip failed")
	}
	writeOrCheckJSON(t, "wire-v2-eph-box.json", map[string]any{
		"suite":              SuiteEphBoxV2,
		"encryption_version": 2,
		"wire":               "hex(eph_pk[32] || nonce[24] || crypto_box_easy(ct))",
		"plaintext":          string(plain),
		"bob_pk_hex":         hex.EncodeToString(bobPk[:]),
		"bob_sk_hex":         hex.EncodeToString(bobSk[:]),
		"eph_pk_hex":         hex.EncodeToString(ephPk[:]),
		"eph_sk_hex":         hex.EncodeToString(ephSk[:]),
		"nonce_hex":          hex.EncodeToString(nonce[:]),
		"wire_hex":           hex.EncodeToString(wire),
	})
}

func TestVectorSenderKeySecretBox(t *testing.T) {
	var key [32]byte
	for i := range key {
		key[i] = byte(0xC0 + i)
	}
	var nonce [24]byte
	for i := range nonce {
		nonce[i] = byte(0xD0 + i)
	}
	plain := []byte("hello-group")
	wire := secretbox.Seal(nonce[:], plain, &nonce, &key)
	opened, ok := secretbox.Open(nil, wire[24:], &nonce, &key)
	if !ok || !bytes.Equal(opened, plain) {
		t.Fatal("secretbox roundtrip failed")
	}
	writeOrCheckJSON(t, "wire-secretbox.json", map[string]any{
		"suite":              SuiteNaclSecretBox,
		"encryption_version": 1,
		"key_version":        3,
		"wire_text":          "hex(nonce[24] || crypto_secretbox_easy(ct))",
		"wire_binary":        "raw nonce[24] || ct (not hex) for voice/attachments",
		"plaintext":          string(plain),
		"key_hex":            hex.EncodeToString(key[:]),
		"nonce_hex":          hex.EncodeToString(nonce[:]),
		"wire_hex":           hex.EncodeToString(wire),
	})
}

func TestVectorVaultAEAD(t *testing.T) {
	vek := bytes.Repeat([]byte{0x11}, 32)
	nonce := bytes.Repeat([]byte{0x22}, 24)
	userID := "11111111-2222-4333-8444-555555555555"
	plain, err := EncodeVaultPlaintext(VaultPlaintext{
		FormatVersion:   VaultFormatV1,
		ProtocolVersion: ProtocolVersionV1,
		SuiteIDs:        []string{SuiteNaclBox, SuiteVaultAEAD, SuiteEphBoxV2},
		IdentityPubHex:  "aa",
		IdentityPrivHex: "bb",
		OwnSenderKeys:   map[string]string{"chat-id|1": "cc", "chat-id|2": "dd"},
		PeerPubs:        map[string]string{"direct-id": "ee"},
	})
	if err != nil {
		t.Fatal(err)
	}
	aad := []byte("vault|" + userID + "|1|" + SuiteVaultAEAD)
	sealed, err := SealXChaChaWithNonce(vek, nonce, plain, aad)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := OpenXChaCha(vek, sealed, aad)
	if err != nil || !bytes.Equal(opened, plain) {
		t.Fatalf("vault aead roundtrip: %v", err)
	}
	writeOrCheckJSON(t, "vault-aead.json", map[string]any{
		"suite":            SuiteVaultAEAD,
		"aad":              string(aad),
		"user_id":          userID,
		"vault_version":    1,
		"vek_hex":          hex.EncodeToString(vek),
		"nonce_hex":        hex.EncodeToString(nonce),
		"plaintext_utf8":   string(plain),
		"plaintext_hex":    hex.EncodeToString(plain),
		"sealed_hex":       hex.EncodeToString(sealed),
		"sealed_layout":    "nonce[24] || ciphertext || tag[16]",
	})
}

func TestVectorKDFs(t *testing.T) {
	salt, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f")
	p := Argon2idParams{MemoryKiB: 8, Time: 1, Threads: 1, KeyLen: 32}
	kek, err := DerivePasswordKEK("test-password", salt, p)
	if err != nil {
		t.Fatal(err)
	}
	rk, _ := hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f")
	rkek, err := DeriveRecoveryKEK(rk, salt)
	if err != nil {
		t.Fatal(err)
	}
	writeOrCheckJSON(t, "kdf.json", map[string]any{
		"argon2id": map[string]any{
			"kdf":          KDFArgon2id,
			"password":     "test-password",
			"salt_hex":     hex.EncodeToString(salt),
			"m_kib":        8,
			"t":            1,
			"p":            1,
			"dklen":        32,
			"output_hex":   hex.EncodeToString(kek),
			"note":         "test-only small m; production DefaultArgon2idParams is 64MiB t=3 p=1",
		},
		"hkdf_sha256": map[string]any{
			"kdf":        KDFHKDFSHA256,
			"info":       "messenger-e2ee-recovery-kek-v1",
			"salt_hex":   hex.EncodeToString(salt),
			"ikm_hex":    hex.EncodeToString(rk),
			"output_hex": hex.EncodeToString(rkek),
		},
	})
}
