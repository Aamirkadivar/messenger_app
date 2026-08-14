package e2ee

import (
	"fmt"

	"golang.org/x/crypto/chacha20poly1305"
)

// SealXChaCha encrypts plaintext with XChaCha20-Poly1305.
// Returns nonce (24) || ciphertext+tag. AAD must be identical on open.
func SealXChaCha(key, plaintext, aad []byte) ([]byte, error) {
	if len(key) != chacha20poly1305.KeySize {
		return nil, fmt.Errorf("key must be %d bytes", chacha20poly1305.KeySize)
	}
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	nonce, err := RandomBytes(chacha20poly1305.NonceSizeX)
	if err != nil {
		return nil, err
	}
	out := aead.Seal(nonce, nonce, plaintext, aad)
	return out, nil
}

// SealXChaChaWithNonce is the same construction with a caller-supplied 24-byte
// nonce. Production code must use SealXChaCha (random nonce). Tests use this
// for canonical vectors.
func SealXChaChaWithNonce(key, nonce, plaintext, aad []byte) ([]byte, error) {
	if len(key) != chacha20poly1305.KeySize {
		return nil, fmt.Errorf("key must be %d bytes", chacha20poly1305.KeySize)
	}
	if len(nonce) != chacha20poly1305.NonceSizeX {
		return nil, fmt.Errorf("nonce must be %d bytes", chacha20poly1305.NonceSizeX)
	}
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	n := append([]byte(nil), nonce...)
	return aead.Seal(n, n, plaintext, aad), nil
}

// OpenXChaCha decrypts nonce||ciphertext produced by SealXChaCha.
func OpenXChaCha(key, sealed, aad []byte) ([]byte, error) {
	if len(key) != chacha20poly1305.KeySize {
		return nil, fmt.Errorf("key must be %d bytes", chacha20poly1305.KeySize)
	}
	if len(sealed) < chacha20poly1305.NonceSizeX+16 {
		return nil, fmt.Errorf("ciphertext too short")
	}
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	nonce := sealed[:chacha20poly1305.NonceSizeX]
	ct := sealed[chacha20poly1305.NonceSizeX:]
	pt, err := aead.Open(nil, nonce, ct, aad)
	if err != nil {
		return nil, fmt.Errorf("authentication failed")
	}
	return pt, nil
}