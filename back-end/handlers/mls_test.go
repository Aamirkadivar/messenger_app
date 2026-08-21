package handlers

import "testing"

// The Delivery Service's two correctness duties are ordering (one accepted
// commit per epoch) and single-use consumption (a KeyPackage is never handed
// out twice). Both are enforced in SQL transactions in mls.go; these tests pin
// the decision logic those transactions implement.

// epochAccepted mirrors the fence in SubmitCommit: a commit is applied only when
// the sender's observed epoch matches the stored one, and it always advances by
// exactly one.
func epochAccepted(storedEpoch, expectedEpoch uint64) (accepted bool, newEpoch uint64) {
	if storedEpoch != expectedEpoch {
		return false, storedEpoch
	}
	return true, storedEpoch + 1
}

func TestCommitEpochFence(t *testing.T) {
	cases := []struct {
		name         string
		stored       uint64
		expected     uint64
		wantAccepted bool
		wantEpoch    uint64
	}{
		{"first commit on a fresh group", 0, 0, true, 1},
		{"in-step commit advances by one", 7, 7, true, 8},
		{"stale commit (raced and lost) is rejected", 8, 7, false, 8},
		{"commit from the future is rejected", 3, 9, false, 3},
		{"replay of an applied commit is rejected", 5, 4, false, 5},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ok, ep := epochAccepted(tc.stored, tc.expected)
			if ok != tc.wantAccepted || ep != tc.wantEpoch {
				t.Fatalf("epochAccepted(stored=%d, expected=%d) = (%v,%d), want (%v,%d)",
					tc.stored, tc.expected, ok, ep, tc.wantAccepted, tc.wantEpoch)
			}
		})
	}
}

// TestGlareCommitsCannotBothApply is the group-fork scenario: two members commit
// against the same epoch. Exactly one must win; the loser must be told to
// rebase. If both applied, the group would silently fork into two states.
func TestGlareCommitsCannotBothApply(t *testing.T) {
	const observed uint64 = 4
	stored := observed

	okA, afterA := epochAccepted(stored, observed)
	if !okA {
		t.Fatal("first commit should win")
	}
	stored = afterA

	okB, serverEpoch := epochAccepted(stored, observed)
	if okB {
		t.Fatal("second commit against the same epoch must be rejected (group would fork)")
	}
	if serverEpoch != afterA {
		t.Fatalf("loser must learn the current epoch %d, got %d", afterA, serverEpoch)
	}
}

// claimAllowed mirrors ClaimKeyPackage: a package is claimable only while
// unclaimed. The real handler enforces this with SELECT ... FOR UPDATE SKIP
// LOCKED so concurrent adders cannot take the same row.
func claimAllowed(alreadyClaimed bool) bool { return !alreadyClaimed }

func TestKeyPackageIsSingleUse(t *testing.T) {
	if !claimAllowed(false) {
		t.Fatal("an unclaimed key package must be claimable")
	}
	// Reuse would repeat the init key across two Adds and undermine the
	// forward secrecy of the joins.
	if claimAllowed(true) {
		t.Fatal("a claimed key package must never be handed out again")
	}
}

// mlsSendReady is the Delivery Service's view of the client send gate:
// every live device other than the sender must have acked a Welcome, and
// the sender's local epoch must match the stored one. Local membership
// alone is not enough — the adder's tree already contains a leaf for a
// device that has not processed its Welcome yet.
func mlsSendReady(meDev string, live, acked []string, localEpoch, serverEpoch uint64, hasGroup bool) bool {
	if !hasGroup || localEpoch != serverEpoch {
		return false
	}
	ackedSet := make(map[string]bool, len(acked))
	for _, id := range acked {
		ackedSet[id] = true
	}
	n := 0
	for _, id := range live {
		if id == "" {
			continue
		}
		n++
		if id == meDev {
			continue
		}
		if !ackedSet[id] {
			return false
		}
	}
	return n > 0
}

func TestMlsSendWaitsForWelcomeAcks(t *testing.T) {
	live := []string{"me-dev", "phone", "pc"}
	if mlsSendReady("me-dev", live, []string{"phone"}, 2, 2, true) {
		t.Fatal("must not send MLS while a live device has not acked its Welcome")
	}
	if !mlsSendReady("me-dev", live, []string{"phone", "pc"}, 2, 2, true) {
		t.Fatal("all other devices acked and epochs match: send is allowed")
	}
	if mlsSendReady("me-dev", live, []string{"phone", "pc"}, 1, 2, true) {
		t.Fatal("behind the server epoch must not send")
	}
	if mlsSendReady("me-dev", live, []string{"phone", "pc"}, 2, 2, false) {
		t.Fatal("no local group: must not send")
	}
}
