package e2ee

import (
	"crypto/rand"
	"fmt"

	"golang.org/x/crypto/nacl/box"
)

const (
	// DeviceChallengeBytes is the size of the random proof value.
	DeviceChallengeBytes = 32
	// DeviceChallengeTTLSeconds bounds how long a challenge stays redeemable.
	// Short on purpose: it is answered by a local decryption, not by a human.
	DeviceChallengeTTLSeconds = 120
)

// NewDeviceChallenge returns fresh random challenge bytes.
func NewDeviceChallenge() ([]byte, error) {
	b := make([]byte, DeviceChallengeBytes)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, nil
}

// SealDeviceChallenge encrypts `challenge` to the device's registered public key.
//
// Identical construction to SealPairingMK - crypto_box_easy under a one-shot
// sender keypair, wire = nonce||ciphertext - because the registered device key
// is a NaCl crypto_box (X25519) key and every client already implements exactly
// this open operation for pairing. Deliberately NOT a signature scheme: an
// X25519 key cannot sign, and adding a second key type to prove possession of
// the first would be a new primitive where an existing one already suffices.
//
// Proof of possession is therefore "decrypt this", not "sign this". The security
// property is the same - only the private-key holder can produce the answer -
// and the value is single-use, so a replayed answer buys nothing.
func SealDeviceChallenge(recipientPub, challenge []byte) (senderPub, sealed []byte, err error) {
	if len(recipientPub) != 32 {
		return nil, nil, fmt.Errorf("device public key must be 32 bytes")
	}
	if len(challenge) != DeviceChallengeBytes {
		return nil, nil, fmt.Errorf("challenge must be %d bytes", DeviceChallengeBytes)
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
	ct := box.Seal(nil, challenge, &nonce, &their, priv)
	out := make([]byte, 0, 24+len(ct))
	out = append(out, nonce[:]...)
	out = append(out, ct...)
	return pub[:], out, nil
}
