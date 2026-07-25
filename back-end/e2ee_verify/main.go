package main

import (
	"encoding/hex"
	"fmt"
	"os"

	"golang.org/x/crypto/nacl/box"
)

// Verifies our client E2EE wire format: hex(nonce[24] || crypto_box_easy_output).
// Usage: go run . <ciphertextHex> <senderPublicHex> <recipientPrivateHex>
func main() {
	if len(os.Args) != 4 {
		fmt.Println("usage: <ciphertextHex> <senderPubHex> <recipientPrivHex>")
		os.Exit(1)
	}
	payload, _ := hex.DecodeString(os.Args[1])
	senderPub, _ := hex.DecodeString(os.Args[2])
	recipientPriv, _ := hex.DecodeString(os.Args[3])

	if len(payload) < 24 || len(senderPub) != 32 || len(recipientPriv) != 32 {
		fmt.Printf("bad sizes: payload=%d senderPub=%d priv=%d\n", len(payload), len(senderPub), len(recipientPriv))
		os.Exit(1)
	}

	var nonce [24]byte
	copy(nonce[:], payload[:24])
	var pub, priv [32]byte
	copy(pub[:], senderPub)
	copy(priv[:], recipientPriv)

	opened, ok := box.Open(nil, payload[24:], &nonce, &pub, &priv)
	if !ok {
		fmt.Println("DECRYPT FAILED (key mismatch or format error)")
		os.Exit(2)
	}
	fmt.Printf("DECRYPTED OK: %q\n", string(opened))
}
