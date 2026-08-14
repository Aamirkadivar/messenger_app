package e2ee

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"

	"golang.org/x/crypto/nacl/box"
)

const (
	// PairingPrefix is the human/QR wire prefix for device-link codes.
	PairingPrefix = "mp1"
	// PairingTTLSeconds is how long a pairing session stays valid.
	PairingTTLSeconds = 5 * 60
)

// PairingAAD is documented for clients; crypto_box itself does not take AAD,
// so user+session binding is enforced by authenticating only for this session
// on the server and including session_id in the pairing string.
func PairingAAD(userID, sessionID string) []byte {
	return []byte(fmt.Sprintf("pair|%s|%s|%s", userID, sessionID, SuiteVaultAEAD))
}

// SealPairingMK encrypts MK to recipientPub with a one-shot sender keypair.
// Returns sender public key and nonce||ciphertext (crypto_box_easy wire).
func SealPairingMK(recipientPub, mk []byte) (senderPub, sealed []byte, err error) {
	if len(recipientPub) != 32 || len(mk) != MasterKeyBytes {
		return nil, nil, fmt.Errorf("invalid pairing seal inputs")
	}
	var their [32]byte
	copy(their[:], recipientPub)
	pub, priv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, nil, err
	}
	ct := box.Seal(nil, mk, &nonce, &their, priv)
	out := make([]byte, 0, 24+len(ct))
	out = append(out, nonce[:]...)
	out = append(out, ct...)
	return pub[:], out, nil
}

// OpenPairingMK decrypts SealPairingMK output on the new device.
func OpenPairingMK(ourPriv, senderPub, sealed []byte) ([]byte, error) {
	if len(ourPriv) != 32 || len(senderPub) != 32 || len(sealed) < 24+box.Overhead {
		return nil, fmt.Errorf("invalid pairing open inputs")
	}
	var sk, their [32]byte
	copy(sk[:], ourPriv)
	copy(their[:], senderPub)
	var nonce [24]byte
	copy(nonce[:], sealed[:24])
	pt, ok := box.Open(nil, sealed[24:], &nonce, &their, &sk)
	if !ok {
		return nil, fmt.Errorf("pairing open failed")
	}
	if len(pt) != MasterKeyBytes {
		return nil, fmt.Errorf("unexpected mk length")
	}
	return pt, nil
}

// FormatPairingString builds mp1.<session_id>.<ephemeral_pub_hex>.
func FormatPairingString(sessionID string, ephemeralPub []byte) string {
	return PairingPrefix + "." + sessionID + "." + hex.EncodeToString(ephemeralPub)
}

// ParsePairingString parses mp1.<session_id>.<ephemeral_pub_hex>.
func ParsePairingString(s string) (sessionID string, ephemeralPub []byte, err error) {
	s = strings.TrimSpace(s)
	if len(s) < 8 || len(s) > 256 {
		return "", nil, fmt.Errorf("invalid pairing code")
	}
	parts := strings.Split(s, ".")
	if len(parts) != 3 || parts[0] != PairingPrefix {
		return "", nil, fmt.Errorf("invalid pairing code")
	}
	sessionID = parts[1]
	if sessionID == "" || len(sessionID) > 80 {
		return "", nil, fmt.Errorf("missing session id")
	}
	if len(parts[2]) != 64 {
		return "", nil, fmt.Errorf("invalid ephemeral public key")
	}
	ephemeralPub, err = hex.DecodeString(parts[2])
	if err != nil || len(ephemeralPub) != 32 {
		return "", nil, fmt.Errorf("invalid ephemeral public key")
	}
	return sessionID, ephemeralPub, nil
}

// GenerateBoxKeyPair returns a crypto_box keypair (priv, pub).
func GenerateBoxKeyPair() (priv, pub []byte, err error) {
	p, s, err := box.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	return s[:], p[:], nil
}