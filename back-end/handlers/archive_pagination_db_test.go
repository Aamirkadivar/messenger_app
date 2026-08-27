package handlers

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
)

// GATE 4 PHASE 4 - keyset pagination, against real Postgres.
//
// The defect these close: `created_at` is not unique, so `created_at > cursor`
// with `ORDER BY created_at` silently skips rows that share a timestamp across a
// page boundary. The cursor is now (created_at, message_id), which is unique
// because message_id is half the primary key.
//
// The assertion that matters throughout: EVERY archive is observed exactly once,
// regardless of ties or page boundaries.
//
// Scratch database only - see archive_lifecycle_db_test.go.

// seedArchives inserts n archives for userA in the fixture chat. When sharedTs is
// non-nil every row gets that exact timestamp, which is how ties are forced.
func seedArchives(t *testing.T, e *archiveAPI, n int, sharedTs *time.Time) []string {
	t.Helper()
	ids := make([]string, 0, n)
	now := time.Now()
	for i := 0; i < n; i++ {
		msgID := uuid.New()
		if err := e.db.Exec(
			`INSERT INTO messages (id, sender_id, chat_id, chat_type, created_at, updated_at)
			 VALUES (?, ?, ?, 'group', ?, ?)`,
			msgID, e.userA, e.chatID, now, now).Error; err != nil {
			t.Fatalf("seed message: %v", err)
		}
		ts := now.Add(time.Duration(i) * time.Millisecond)
		if sharedTs != nil {
			ts = *sharedTs
		}
		if err := e.db.Exec(
			`INSERT INTO message_archives
			   (message_id, user_id, chat_id, root_version, protocol_version, ciphertext, created_at)
			 VALUES (?, ?, ?, 1, 1, ?, ?)`,
			msgID, e.userA, e.chatID, []byte(fmt.Sprintf("sealed-%d", i)), ts).Error; err != nil {
			t.Fatalf("seed archive: %v", err)
		}
		ids = append(ids, msgID.String())
	}
	return ids
}

// drainAll walks the keyset cursor exactly as the Android client does and returns
// every message_id observed, in order, including any duplicates.
func drainAll(t *testing.T, e *archiveAPI) []string {
	t.Helper()
	seen := make([]string, 0)
	since, sinceID := "", ""
	for page := 0; page < 100; page++ {
		q := "?chat_id=" + e.chatID
		if since != "" {
			q += "&since=" + urlq(since) + "&since_id=" + sinceID
		}
		code, body := e.get(t, e.device, q)
		if code != http.StatusOK {
			t.Fatalf("page %d: status %d", page, code)
		}
		archives, _ := body["archives"].([]any)
		if len(archives) == 0 {
			return seen
		}
		for _, a := range archives {
			row := a.(map[string]any)
			seen = append(seen, row["message_id"].(string))
		}
		if len(archives) < maxArchiveListed {
			return seen
		}
		last := archives[len(archives)-1].(map[string]any)
		since = last["created_at"].(string)
		sinceID = last["message_id"].(string)
	}
	t.Fatal("pagination did not terminate")
	return nil
}

func urlq(s string) string {
	// Only '+' needs escaping for an RFC3339 offset in a query string.
	out := ""
	for _, r := range s {
		if r == '+' {
			out += "%2B"
		} else {
			out += string(r)
		}
	}
	return out
}

func assertExactlyOnce(t *testing.T, want []string, seen []string) {
	t.Helper()
	if len(seen) != len(want) {
		t.Fatalf("observed %d archives, want %d", len(seen), len(want))
	}
	counts := map[string]int{}
	for _, id := range seen {
		counts[id]++
	}
	for _, id := range want {
		switch counts[id] {
		case 1: // exactly once
		case 0:
			t.Fatalf("archive %s was never observed - pagination skipped it", id)
		default:
			t.Fatalf("archive %s was observed %d times", id, counts[id])
		}
	}
}

func TestArchivePagination_ExactlyPageSize(t *testing.T) {
	e := archiveAPIEnv(t)
	want := seedArchives(t, e, maxArchiveListed, nil)
	assertExactlyOnce(t, want, drainAll(t, e))
}

func TestArchivePagination_OneOverPageSize(t *testing.T) {
	e := archiveAPIEnv(t)
	want := seedArchives(t, e, maxArchiveListed+1, nil)
	assertExactlyOnce(t, want, drainAll(t, e))
}

func TestArchivePagination_TwoRowsShareATimestamp(t *testing.T) {
	e := archiveAPIEnv(t)
	ts := time.Now()
	want := seedArchives(t, e, 2, &ts)
	assertExactlyOnce(t, want, drainAll(t, e))
}

// The case the old cursor lost outright: a tie straddling the page boundary.
func TestArchivePagination_TieExactlyAcrossThePageBoundary(t *testing.T) {
	e := archiveAPIEnv(t)
	now := time.Now()

	// 499 distinct, then 3 sharing one timestamp so the tie spans rows 500-502.
	want := seedArchives(t, e, maxArchiveListed-1, nil)
	boundary := now.Add(time.Hour)
	want = append(want, seedArchives(t, e, 3, &boundary)...)

	assertExactlyOnce(t, want, drainAll(t, e))
}

func TestArchivePagination_ManyIdenticalTimestamps(t *testing.T) {
	e := archiveAPIEnv(t)
	ts := time.Now()
	// Every row shares one timestamp and the set spans two pages: without the
	// message_id tie-break this cannot paginate correctly at all.
	want := seedArchives(t, e, maxArchiveListed+50, &ts)
	assertExactlyOnce(t, want, drainAll(t, e))
}

func TestArchivePagination_RepeatedSyncDoesNotDuplicate(t *testing.T) {
	e := archiveAPIEnv(t)
	want := seedArchives(t, e, 20, nil)
	for i := 0; i < 3; i++ {
		assertExactlyOnce(t, want, drainAll(t, e))
	}
}

func TestArchivePagination_CursorBoundaries(t *testing.T) {
	e := archiveAPIEnv(t)
	want := seedArchives(t, e, 5, nil)

	code, body := e.get(t, e.device, "?chat_id="+e.chatID)
	if code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	archives, _ := body["archives"].([]any)
	first := archives[0].(map[string]any)
	last := archives[len(archives)-1].(map[string]any)

	// Newest cursor: nothing after it.
	code, body = e.get(t, e.device, "?chat_id="+e.chatID+
		"&since="+urlq(last["created_at"].(string))+"&since_id="+last["message_id"].(string))
	if code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	if rest, _ := body["archives"].([]any); len(rest) != 0 {
		t.Fatalf("newest cursor must return nothing, got %d", len(rest))
	}

	// Oldest cursor: everything except the first row.
	code, body = e.get(t, e.device, "?chat_id="+e.chatID+
		"&since="+urlq(first["created_at"].(string))+"&since_id="+first["message_id"].(string))
	if rest, _ := body["archives"].([]any); len(rest) != len(want)-1 {
		t.Fatalf("oldest cursor must exclude only itself: got %d want %d", len(rest), len(want)-1)
	}
}

func TestArchivePagination_MalformedCursorIsRejected(t *testing.T) {
	e := archiveAPIEnv(t)
	seedArchives(t, e, 2, nil)

	if code, _ := e.get(t, e.device, "?chat_id="+e.chatID+"&since=not-a-time"); code != http.StatusBadRequest {
		t.Fatalf("malformed since: want 400, got %d", code)
	}
	code, _ := e.get(t, e.device, "?chat_id="+e.chatID+
		"&since="+urlq(time.Now().Format(time.RFC3339))+"&since_id=not-a-uuid")
	if code != http.StatusBadRequest {
		t.Fatalf("malformed since_id: want 400, got %d", code)
	}
}

// An older client sending only `since` keeps the previous timestamp-only
// semantics. It is weaker, but it must still work and must never error.
func TestArchivePagination_BackwardCompatibleTimestampOnlyCursor(t *testing.T) {
	e := archiveAPIEnv(t)
	seedArchives(t, e, 5, nil)

	code, body := e.get(t, e.device, "?chat_id="+e.chatID)
	if code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	archives, _ := body["archives"].([]any)
	first := archives[0].(map[string]any)

	code, body = e.get(t, e.device, "?chat_id="+e.chatID+
		"&since="+urlq(first["created_at"].(string)))
	if code != http.StatusOK {
		t.Fatalf("timestamp-only cursor must still be accepted, got %d", code)
	}
	if rest, _ := body["archives"].([]any); len(rest) != 4 {
		t.Fatalf("want 4 remaining, got %d", len(rest))
	}
}

// Ordering must match the cursor comparison, or pagination walks a different
// sequence than it compares against.
func TestArchivePagination_OrderingIsStableAcrossRepeatedRequests(t *testing.T) {
	e := archiveAPIEnv(t)
	ts := time.Now()
	seedArchives(t, e, 20, &ts) // all tied

	var firstOrder []string
	for i := 0; i < 5; i++ {
		code, body := e.get(t, e.device, "?chat_id="+e.chatID)
		if code != http.StatusOK {
			t.Fatalf("status %d", code)
		}
		archives, _ := body["archives"].([]any)
		order := make([]string, 0, len(archives))
		for _, a := range archives {
			order = append(order, a.(map[string]any)["message_id"].(string))
		}
		if firstOrder == nil {
			firstOrder = order
			continue
		}
		for j := range order {
			if order[j] != firstOrder[j] {
				t.Fatalf("ordering is not stable at %d across identical timestamps", j)
			}
		}
	}
}

var _ = base64.StdEncoding
