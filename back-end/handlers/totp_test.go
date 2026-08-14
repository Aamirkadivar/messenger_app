package handlers

import (
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func TestTotpRoundTrip(t *testing.T) {
	secret, err := generateTotpSecret()
	if err != nil {
		t.Fatal(err)
	}
	unix := int64(1_700_000_010) // 10s into a 30s window
	code, err := totpCodeAt(secret, unix)
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 6 {
		t.Fatalf("code %q", code)
	}
	// Fixed-time window around that unix: validateTotp uses now, so test totpCodeAt equality.
	again, _ := totpCodeAt(secret, unix)
	if again != code {
		t.Fatal("not deterministic")
	}
	if totpCodeAtMust(t, secret, unix+29) != code {
		t.Fatal("same 30s window")
	}
	if totpCodeAtMust(t, secret, unix+30) == code {
		t.Fatal("next window should differ")
	}
}

func TestGenerateBackupCodes(t *testing.T) {
	plain, hashes, err := generateBackupCodes(8)
	if err != nil {
		t.Fatal(err)
	}
	if len(plain) != 8 || len(hashes) != 8 {
		t.Fatalf("got %d/%d", len(plain), len(hashes))
	}
	seen := map[string]struct{}{}
	for i, p := range plain {
		n := normalizeBackupCode(p)
		if len(n) != 8 {
			t.Fatalf("code %q", p)
		}
		if _, ok := seen[n]; ok {
			t.Fatalf("duplicate %q", p)
		}
		seen[n] = struct{}{}
		if bcrypt.CompareHashAndPassword([]byte(hashes[i]), []byte(n)) != nil {
			t.Fatalf("hash mismatch %q", p)
		}
	}
}

func totpCodeAtMust(t *testing.T, secret string, unix int64) string {
	t.Helper()
	c, err := totpCodeAt(secret, unix)
	if err != nil {
		t.Fatal(err)
	}
	return c
}
