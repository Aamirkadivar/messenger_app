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

func TestIdentityChangeBlocked(t *testing.T) {
	if identityChangeBlocked("", "new", true, true) {
		t.Fatal("first publish must be allowed")
	}
	if identityChangeBlocked("abc", "ABC", true, true) {
		t.Fatal("same key republish must be allowed")
	}
	if !identityChangeBlocked("old", "new", true, false) {
		t.Fatal("vault should lock identity")
	}
	if !identityChangeBlocked("old", "new", false, true) {
		t.Fatal("live device should lock identity")
	}
	if identityChangeBlocked("old", "new", false, false) {
		t.Fatal("empty account may still take over")
	}
}
