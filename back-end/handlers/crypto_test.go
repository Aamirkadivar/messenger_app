package handlers

import "testing"

// TestIdentityChangeBlocked pins the hardened identity-overwrite guard.
//
// The regression it guards against: the previous version only blocked a changed
// identity key when the account already had a vault or a live device. Both
// tables were empty in practice (the vault system was never activated), so the
// guard never engaged and every fresh login silently replaced the account
// identity — orphaning all history sealed to the old key. See
// docs/e2ee-architecture.md §1.5.
func TestIdentityChangeBlocked(t *testing.T) {
	const (
		keyA = "a1b2c3"
		keyB = "d4e5f6"
	)

	cases := []struct {
		name     string
		existing string
		incoming string
		reset    bool
		want     bool
	}{
		{
			name:     "first publish on a fresh account is allowed",
			existing: "",
			incoming: keyA,
			reset:    false,
			want:     false,
		},
		{
			name:     "republishing the same key is a no-op, allowed",
			existing: keyA,
			incoming: keyA,
			reset:    false,
			want:     false,
		},
		{
			name:     "same key with different casing/space is still the same key",
			existing: keyA,
			incoming: " A1B2C3 ",
			reset:    false,
			want:     false,
		},
		{
			// THE REGRESSION: established key, different incoming, no vault and
			// no device. The old guard returned false here (hole open); the
			// hardened guard must block.
			name:     "silent replacement of an established key is BLOCKED even with no vault/device",
			existing: keyA,
			incoming: keyB,
			reset:    false,
			want:     true,
		},
		{
			name:     "deliberate reset may replace an established key",
			existing: keyA,
			incoming: keyB,
			reset:    true,
			want:     false,
		},
		{
			name:     "reset on a fresh account is simply a first publish",
			existing: "",
			incoming: keyA,
			reset:    true,
			want:     false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := identityChangeBlocked(tc.existing, tc.incoming, tc.reset)
			if got != tc.want {
				t.Fatalf("identityChangeBlocked(%q, %q, reset=%v) = %v, want %v",
					tc.existing, tc.incoming, tc.reset, got, tc.want)
			}
		})
	}
}
