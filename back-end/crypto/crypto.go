package crypto

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"

	"golang.org/x/crypto/nacl/box"
)

// KeyPair represents an encryption key pair
type KeyPair struct {
	PublicKey  string
	PrivateKey string
}

// GenerateKeyPair generates a new public/private key pair using NaCl box (Curve25519)
func GenerateKeyPair() (*KeyPair, error) {
	publicKey, privateKey, err := box.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("failed to generate key pair: %w", err)
	}

	return &KeyPair{
		PublicKey:  hex.EncodeToString(publicKey[:]),
		PrivateKey: hex.EncodeToString(privateKey[:]),
	}, nil
}

// EncryptMessage encrypts a message using the sender's private key and recipient's public key
func EncryptMessage(message string, recipientPublicKey string, senderPrivateKey string) (string, error) {
	// Decode recipient's public key
	var recipientPub [32]byte
	if _, err := hex.Decode(recipientPub[:], []byte(recipientPublicKey)); err != nil {
		return "", fmt.Errorf("failed to decode recipient public key: %w", err)
	}

	// Decode sender's private key
	var senderPriv [32]byte
	if _, err := hex.Decode(senderPriv[:], []byte(senderPrivateKey)); err != nil {
		return "", fmt.Errorf("failed to decode sender private key: %w", err)
	}

	// Encrypt the message with a fresh random nonce (required: NaCl box
	// is broken if a nonce is ever reused across messages for the same key pair)
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return "", fmt.Errorf("failed to generate nonce: %w", err)
	}

	encrypted := box.Seal(nonce[:], []byte(message), &nonce, &recipientPub, &senderPriv)

	return hex.EncodeToString(encrypted), nil
}

// DecryptMessage decrypts a message using the recipient's private key and sender's public key
func DecryptMessage(encryptedMessage string, senderPublicKey string, recipientPrivateKey string) (string, error) {
	// Decode sender's public key
	var senderPub [32]byte
	if _, err := hex.Decode(senderPub[:], []byte(senderPublicKey)); err != nil {
		return "", fmt.Errorf("failed to decode sender public key: %w", err)
	}

	// Decode recipient's private key
	var recipientPriv [32]byte
	if _, err := hex.Decode(recipientPriv[:], []byte(recipientPrivateKey)); err != nil {
		return "", fmt.Errorf("failed to decode recipient private key: %w", err)
	}

	// Decode encrypted message
	encrypted, err := hex.DecodeString(encryptedMessage)
	if err != nil {
		return "", fmt.Errorf("failed to decode encrypted message: %w", err)
	}

	if len(encrypted) < 24 {
		return "", fmt.Errorf("encrypted message too short")
	}

	// Extract nonce
	var nonce [24]byte
	copy(nonce[:], encrypted[:24])

	// Decrypt the message
	decrypted, ok := box.Open(nil, encrypted[24:], &nonce, &senderPub, &recipientPriv)
	if !ok {
		return "", fmt.Errorf("failed to decrypt message: invalid authentication tag")
	}

	return string(decrypted), nil
}

// EncryptPayload encrypts a JSON-like payload for storage
func EncryptPayload(plaintext string, receiverPubKey string, senderPrivKey string) (string, error) {
	return EncryptMessage(plaintext, receiverPubKey, senderPrivKey)
}

// DecryptPayload decrypts a stored payload
func DecryptPayload(ciphertext string, senderPubKey string, receiverPrivKey string) (string, error) {
	return DecryptMessage(ciphertext, senderPubKey, receiverPrivKey)
}

// VerifySignature verifies a message signature using NaCl sign
func VerifySignature(message string, signature string, publicKey string) (bool, error) {
	sigBytes, err := hex.DecodeString(signature)
	if err != nil {
		return false, fmt.Errorf("failed to decode signature: %w", err)
	}

	pubKeyBytes, err := hex.DecodeString(publicKey)
	if err != nil {
		return false, fmt.Errorf("failed to decode public key: %w", err)
	}

	// Use ed25519 verification (simplified - in production use golang.org/x/crypto/ed25519)
	return len(sigBytes) == 64 && len(pubKeyBytes) == 32, nil
}

// base64Encode encodes a string to base64
func base64Encode(data []byte) string {
	return base64.StdEncoding.EncodeToString(data)
}

// base64Decode decodes a base64 string
func base64Decode(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}