package handlers

import "testing"

func TestPublicKeysEqual(t *testing.T) {
	if !publicKeysEqual("Ab", "ab") || !publicKeysEqual(" x ", "x") {
		t.Fatal("expected equal")
	}
	if publicKeysEqual("aa", "bb") {
		t.Fatal("expected different")
	}
}

// TestIdentityChangeBlocked now lives in crypto_test.go against the hardened
// three-argument guard. The prior version here asserted "empty account may
// still take over" — i.e. that a silent identity replacement was allowed when no
// vault/device existed. That was the security hole (docs/e2ee-architecture.md
// §1.5), not intended behavior, so it has been removed rather than updated.
