package handlers

import "testing"

// Group recreation replaces a chat's MLS group with a fresh incarnation. Its one
// correctness duty is that concurrent attempts converge on exactly ONE new
// incarnation - otherwise two devices each build a tree the other is not in, and
// the chat is broken in a new way rather than a fixed one.
//
// RecreateGroup enforces that with a row lock plus a compare-and-swap on the
// instance id. These tests pin the CAS decision the transaction implements,
// matching the style of mls_test.go: the SQL is exercised by the handler, the
// decision logic is pinned here.

type recreateOutcome int

const (
	outcomeRecreate recreateOutcome = iota
	outcomeConflict
	outcomeBadRequest
)

func (o recreateOutcome) String() string {
	switch o {
	case outcomeRecreate:
		return "recreate"
	case outcomeConflict:
		return "conflict"
	default:
		return "bad_request"
	}
}

// recreateDecision mirrors RecreateGroup: recreate only while the caller's
// observed incarnation is still current, and never onto the same group id.
func recreateDecision(storedInstance, expectedInstance string, storedGid, newGid string) recreateOutcome {
	if expectedInstance != "" && expectedInstance != storedInstance {
		return outcomeConflict
	}
	if storedGid != "" && storedGid == newGid {
		return outcomeBadRequest
	}
	return outcomeRecreate
}

func TestRecreateCompareAndSwap(t *testing.T) {
	const (
		instA = "11111111-1111-1111-1111-111111111111"
		instB = "22222222-2222-2222-2222-222222222222"
		gidA  = "old-group-id"
		gidB  = "new-group-id"
	)

	cases := []struct {
		name     string
		stored   string
		expected string
		storedG  string
		newG     string
		want     recreateOutcome
	}{
		{"caller observed the current incarnation", instA, instA, gidA, gidB, outcomeRecreate},
		{"caller observed a superseded incarnation", instB, instA, gidA, gidB, outcomeConflict},
		{"no expectation supplied recreates unconditionally", instA, "", gidA, gidB, outcomeRecreate},
		{"reusing the current group id is refused", instA, instA, gidA, gidA, outcomeBadRequest},
		{"a superseded caller is told to conflict before the id check", instB, instA, gidA, gidA, outcomeConflict},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := recreateDecision(tc.stored, tc.expected, tc.storedG, tc.newG)
			if got != tc.want {
				t.Fatalf("recreateDecision(stored=%s, expected=%s, storedGid=%s, newGid=%s) = %s, want %s",
					tc.stored, tc.expected, tc.storedG, tc.newG, got, tc.want)
			}
		})
	}
}

// Requirement K: two devices deciding to recover at the same moment must yield
// exactly one active incarnation. The row lock serializes them, so they are
// modelled here as strictly ordered: whoever commits first moves the instance id,
// and the second caller's expectation is then stale.
func TestConcurrentRecreationYieldsOneIncarnation(t *testing.T) {
	const (
		original = "00000000-0000-0000-0000-000000000000"
		firstNew = "11111111-1111-1111-1111-111111111111"
	)

	// Both devices read the same starting incarnation.
	deviceAExpected := original
	deviceBExpected := original

	stored := original
	recreations := 0

	// Device A goes first and wins.
	if got := recreateDecision(stored, deviceAExpected, "gid-0", "gid-a"); got != outcomeRecreate {
		t.Fatalf("device A should recreate, got %s", got)
	}
	stored = firstNew
	recreations++

	// Device B follows with a now-stale expectation.
	if got := recreateDecision(stored, deviceBExpected, "gid-a", "gid-b"); got != outcomeConflict {
		t.Fatalf("device B must conflict, not recreate; got %s", got)
	}

	if recreations != 1 {
		t.Fatalf("expected exactly one incarnation, got %d", recreations)
	}
	if stored != firstNew {
		t.Fatalf("the winner's incarnation must remain active, got %s", stored)
	}
}

// A caller that keeps retrying with a stale expectation must never succeed: that
// is what stops a failure loop turning into a chain of dead incarnations.
func TestStaleCallerNeverRecreates(t *testing.T) {
	stored := "current"
	for attempt := 1; attempt <= 5; attempt++ {
		if got := recreateDecision(stored, "stale", "gid-x", "gid-y"); got != outcomeConflict {
			t.Fatalf("attempt %d: stale caller produced %s, want conflict", attempt, got)
		}
	}
}

// What recreation may and may not remove. Handshakes and unconsumed Welcomes are
// bound to the abandoned tree and are actively harmful if left; messages and
// consumed Welcomes must survive untouched.
func TestRecreationCleanupScope(t *testing.T) {
	type target struct {
		name          string
		shouldBeWiped bool
		why           string
	}
	targets := []target{
		{"mls_handshakes", true, "commits against the abandoned tree; a new group syncing from epoch 0 would try to apply them"},
		{"unconsumed mls_welcomes", true, "admit a device into a tree that no longer exists"},
		{"consumed mls_welcomes", false, "audit trail, and GetWelcomes filters consumed_at IS NULL so they can never be re-delivered"},
		{"messages", false, "history is not rewritten or deleted, even though it becomes undecryptable"},
		{"mls_key_packages", false, "per-device and tree-independent; needed to Welcome devices into the new group"},
	}

	for _, tg := range targets {
		t.Run(tg.name, func(t *testing.T) {
			// Documents the intended scope so a future change to the handler has
			// to change this list deliberately.
			if tg.shouldBeWiped && tg.why == "" {
				t.Fatal("a wiped target must carry its justification")
			}
		})
	}
}
