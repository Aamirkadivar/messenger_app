package e2ee

import (
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/hkdf"
)

// DerivePasswordKEK runs Argon2id(password, salt, params) → 32-byte KEK.
func DerivePasswordKEK(password string, salt []byte, p Argon2idParams) ([]byte, error) {
	if len(salt) != 16 {
		return nil, fmt.Errorf("salt must be 16 bytes (libsodium crypto_pwhash)")
	}
	if p.KeyLen == 0 {
		p.KeyLen = 32
	}
	if p.MemoryKiB == 0 || p.Time == 0 || p.Threads == 0 {
		return nil, fmt.Errorf("invalid argon2id params")
	}
	out := argon2.IDKey([]byte(password), salt, p.Time, p.MemoryKiB, p.Threads, p.KeyLen)
	return out, nil
}

// DeriveRecoveryKEK derives a KEK from a high-entropy recovery key using HKDF-SHA256.
// Recovery keys are CSPRNG; Argon2id is unnecessary and slower for UX.
func DeriveRecoveryKEK(recoveryKey, salt []byte) ([]byte, error) {
	if len(recoveryKey) < 32 {
		return nil, fmt.Errorf("recovery key too short")
	}
	if len(salt) < 16 {
		return nil, fmt.Errorf("salt too short")
	}
	r := hkdf.New(sha256.New, recoveryKey, salt, []byte("messenger-e2ee-recovery-kek-v1"))
	out := make([]byte, 32)
	if _, err := io.ReadFull(r, out); err != nil {
		return nil, err
	}
	return out, nil
}

// RandomBytes returns n cryptographically secure random bytes.
func RandomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, nil
}