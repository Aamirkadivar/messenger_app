package handlers

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"

	"github.com/google/uuid"

	"messenger-app/config"
)

// refreshTokenBytes is the size of a refresh credential. 32 bytes of CSPRNG
// output is far beyond guessing range, which is why the token needs no
// structure, no signature and no expiry baked into it: all of that lives on the
// session row where it can actually be changed.
const refreshTokenBytes = 32

// mintRefreshToken returns the transport form of a new refresh credential and
// the value that will be stored for it.
//
// The plaintext is returned to the caller and nowhere else. It is never
// persisted, never logged, and - critically - never passed to SQL as a bind
// parameter: GORM interpolates binds into its query log, and this repository
// runs with the logger enabled whenever Env is "development", which is the
// default. Only the hash reaches the database.
func mintRefreshToken(cfg *config.Config) (plaintext string, hash []byte, err error) {
	raw := make([]byte, refreshTokenBytes)
	// A short read here is not a degraded token, it is a predictable one.
	// io.ReadFull turns a partial read into an error instead of silently
	// producing a credential with fewer random bytes than it claims.
	if _, err := io.ReadFull(rand.Reader, raw); err != nil {
		return "", nil, fmt.Errorf("refresh token entropy unavailable: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(raw), refreshTokenHash(cfg, raw), nil
}

// decodeRefreshToken parses the transport form back to its raw bytes.
func decodeRefreshToken(s string) ([]byte, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(raw) != refreshTokenBytes {
		return nil, false
	}
	return raw, true
}

// refreshTokenHash is what the database stores.
//
// HMAC rather than a bare digest, keyed by a pepper that lives outside
// PostgreSQL. The threat this addresses is the database-only compromise - a
// leaked backup, a dump, an injection that can read rows - where the attacker
// holds every hash but not the application secret, and so cannot turn them back
// into usable credentials.
func refreshTokenHash(cfg *config.Config, raw []byte) []byte {
	mac := hmac.New(sha256.New, []byte(cfg.RefreshPepper))
	mac.Write(raw)
	return mac.Sum(nil)
}

// hashRefreshTransport hashes a token presented by a client.
func hashRefreshTransport(cfg *config.Config, presented string) ([]byte, bool) {
	raw, ok := decodeRefreshToken(presented)
	if !ok {
		return nil, false
	}
	return refreshTokenHash(cfg, raw), true
}

// responseCacheAAD binds a cached successor to the exact row it belongs to.
//
// Without this a database-write attacker could move ciphertext from one ledger
// row to another and have it open cleanly, handing one session's successor to a
// different session. Length-prefixing keeps the concatenation unambiguous.
func responseCacheAAD(consumedHash []byte, sessionID, requestID uuid.UUID) []byte {
	aad := make([]byte, 0, len(consumedHash)+32)
	aad = append(aad, byte(len(consumedHash)))
	aad = append(aad, consumedHash...)
	sid := sessionID
	rid := requestID
	aad = append(aad, sid[:]...)
	aad = append(aad, rid[:]...)
	return aad
}

// sealResponseCache encrypts the successor refresh plaintext for the
// lost-response window.
//
// Only the successor refresh token is sealed. No access token is ever stored:
// a replay re-mints one. That keeps the database free of any credential that is
// directly usable against the API, which is a property this codebase had before
// Gate 17 and should not lose to a cache.
func sealResponseCache(cfg *config.Config, consumedHash []byte, sessionID, requestID uuid.UUID,
	successorPlaintext string) (ciphertext, nonce []byte, err error) {
	key, err := cfg.ResponseCacheKeyBytes()
	if err != nil {
		return nil, nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, nil, err
	}
	nonce = make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, nil, fmt.Errorf("response cache nonce entropy unavailable: %w", err)
	}
	aad := responseCacheAAD(consumedHash, sessionID, requestID)
	return gcm.Seal(nil, nonce, []byte(successorPlaintext), aad), nonce, nil
}

// openResponseCache decrypts a cached successor. A failure here is treated as a
// cache miss by the caller, never as evidence of an attack: the row may simply
// have been purged mid-flight.
func openResponseCache(cfg *config.Config, consumedHash []byte, sessionID, requestID uuid.UUID,
	ciphertext, nonce []byte) (string, bool) {
	if len(ciphertext) == 0 || len(nonce) == 0 {
		return "", false
	}
	key, err := cfg.ResponseCacheKeyBytes()
	if err != nil {
		return "", false
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", false
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return "", false
	}
	aad := responseCacheAAD(consumedHash, sessionID, requestID)
	plain, err := gcm.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return "", false
	}
	return string(plain), true
}
