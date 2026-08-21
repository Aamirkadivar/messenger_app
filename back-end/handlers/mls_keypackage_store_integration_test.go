package handlers

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

// Integration verification for KeyPackage store-incarnation scoping.
//
// The sibling file pins individual query behaviour by writing rows straight into
// Postgres. This one goes through the real HTTP handlers end to end - publish,
// count, claim - because the publish path is where store_id enters the system
// and writing rows by hand would never have exercised it.

// fullKeyPackageApp mounts all three KeyPackage endpoints behind an injected
// identity, mirroring how they sit behind AuthMiddleware in main.go.
func fullKeyPackageApp(as uuid.UUID) *fiber.App {
	app := fiber.New()
	inject := func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: as})
		return c.Next()
	}
	app.Post("/e2ee/mls/keypackages", inject, (&MLSHandler{}).PublishKeyPackages)
	app.Get("/e2ee/mls/keypackages/count", inject, (&MLSHandler{}).CountKeyPackages)
	app.Post("/e2ee/mls/keypackages/claim", inject, (&MLSHandler{}).ClaimKeyPackage)
	return app
}

// publishPackages posts a batch the way the Android client does. An empty store
// reproduces a pre-store_id client, which must still be accepted.
func publishPackages(t *testing.T, app *fiber.App, device, store string, refs ...string) int {
	t.Helper()
	items := make([]map[string]any, 0, len(refs))
	for _, ref := range refs {
		items = append(items, map[string]any{
			"device_id":       device,
			"cipher_suite":    1,
			"key_package_b64": base64.StdEncoding.EncodeToString([]byte("kp:" + ref)),
			"ref_hash":        ref,
			"store_id":        store,
		})
	}
	raw, _ := json.Marshal(map[string]any{"key_packages": items})
	req := httptest.NewRequest(http.MethodPost, "/e2ee/mls/keypackages", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, 10000)
	if err != nil {
		t.Fatalf("publish request: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("publish returned %d: %s", resp.StatusCode, body)
	}
	var out struct {
		Stored int `json:"stored"`
	}
	_ = json.Unmarshal(body, &out)
	return out.Stored
}

// ---- Test 1: migration safety, with several representative rows ----

// Legacy rows must survive the migration byte for byte. The only acceptable
// change is the arrival of a NULL store_id column.
func TestMigrationPreservesEveryLegacyRowExactly(t *testing.T) {
	db := recreateTestDB(t)
	if err := db.Exec(`DROP TABLE IF EXISTS mls_key_packages`).Error; err != nil {
		t.Fatalf("drop: %v", err)
	}
	if err := db.AutoMigrate(&legacyKeyPackage{}); err != nil {
		t.Fatalf("build the pre-migration table: %v", err)
	}

	owner, claimer := uuid.New(), uuid.New()
	// Representative spread: unclaimed, claimed, two devices, two cipher suites.
	rows := []struct {
		ref     string
		device  string
		suite   int
		claimed bool
	}{
		{"legacy-a", "device-1", 1, false},
		{"legacy-b", "device-1", 1, true},
		{"legacy-c", "device-2", 1, false},
		{"legacy-d", "device-2", 2, false},
	}
	for _, r := range rows {
		if err := db.Exec(
			`INSERT INTO mls_key_packages (user_id, device_id, cipher_suite, key_package_data, ref_hash)
			 VALUES (?, ?, ?, ?, ?)`,
			owner, r.device, r.suite, []byte("kp:"+r.ref), r.ref).Error; err != nil {
			t.Fatalf("seed %s: %v", r.ref, err)
		}
		if r.claimed {
			if err := db.Exec(
				`UPDATE mls_key_packages SET claimed_at = now(), claimed_by = ? WHERE ref_hash = ?`,
				claimer, r.ref).Error; err != nil {
				t.Fatalf("claim %s: %v", r.ref, err)
			}
		}
	}

	// Every column as text, so the comparison is over VALUES. Comparing structs
	// with pointer fields would compare addresses and always differ.
	type snapshot struct {
		RefHash   string
		DeviceID  string
		Suite     string
		Payload   string
		Owner     string
		ClaimedBy string
		ClaimedAt string
		CreatedAt string
	}
	readAll := func() []snapshot {
		var out []snapshot
		if err := db.Raw(
			`SELECT ref_hash                        AS ref_hash,
			        device_id                       AS device_id,
			        cipher_suite::text              AS suite,
			        encode(key_package_data,'hex')  AS payload,
			        user_id::text                   AS owner,
			        COALESCE(claimed_by::text,'')   AS claimed_by,
			        COALESCE(claimed_at::text,'')   AS claimed_at,
			        created_at::text                AS created_at
			   FROM mls_key_packages ORDER BY ref_hash`).Scan(&out).Error; err != nil {
			t.Fatalf("read rows: %v", err)
		}
		return out
	}

	before := readAll()
	if len(before) != len(rows) {
		t.Fatalf("expected %d seeded rows, got %d", len(rows), len(before))
	}

	if err := db.AutoMigrate(&models.MLSKeyPackage{}); err != nil {
		t.Fatalf("AutoMigrate must succeed on a populated table: %v", err)
	}

	after := readAll()
	if len(after) != len(before) {
		t.Fatalf("migration changed the row count: %d -> %d", len(before), len(after))
	}
	for i := range before {
		if fmt.Sprintf("%+v", before[i]) != fmt.Sprintf("%+v", after[i]) {
			t.Errorf("row %s was rewritten by the migration:\n before %+v\n after  %+v",
				before[i].RefHash, before[i], after[i])
		}
	}

	var nonNull int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id IS NOT NULL`).Scan(&nonNull)
	if nonNull != 0 {
		t.Errorf("%d legacy rows were given a store_id; they must stay unattributed", nonNull)
	}

	var nullable string
	db.Raw(`SELECT is_nullable FROM information_schema.columns
	         WHERE table_name = 'mls_key_packages' AND column_name = 'store_id'`).Scan(&nullable)
	if nullable != "YES" {
		t.Errorf("store_id must be nullable, got %q", nullable)
	}

	var indexed int64
	db.Raw(`SELECT count(*) FROM pg_indexes
	         WHERE tablename = 'mls_key_packages' AND indexdef LIKE '%store_id%'`).Scan(&indexed)
	if indexed == 0 {
		t.Error("expected an index covering store_id")
	}
}

// ---- Test 6: publish -> count -> claim through the real handlers ----

// The whole path in one test. Publishing is where store_id enters the system,
// so a test that writes rows by hand can never prove the handler records it.
func TestPublishCountClaimEndToEnd(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)
	claimerApp := fullKeyPackageApp(uuid.New())

	if stored := publishPackages(t, ownerApp, device, "store-B", "b1", "b2", "b3"); stored != 3 {
		t.Fatalf("expected 3 packages stored, got %d", stored)
	}

	// The handler must have persisted the tag, not dropped it.
	var tagged int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id = 'store-B'`).Scan(&tagged)
	if tagged != 3 {
		t.Fatalf("expected 3 rows tagged store-B, got %d", tagged)
	}

	if got := countPackages(t, ownerApp, device, "store-B"); got != 3 {
		t.Fatalf("count should see the freshly published stock, got %d", got)
	}

	// Packages published in ONE batch all carry the same created_at, so which of
	// them FIFO serves first is a tie and deliberately not asserted. What must
	// hold is that it comes from the current store. Strict oldest-first ordering
	// is pinned by TestClaimIsFifoWithinTheIncarnation, where the timestamps are
	// explicitly distinct.
	ref := claimPackage(t, claimerApp, db, owner, device)
	if ref == "" || ref[:1] != "b" {
		t.Fatalf("expected a package of the current store, got %q", ref)
	}

	// The consumed row must be marked, and marked as consumed BY the claimer.
	var claimedBy string
	db.Raw(`SELECT COALESCE(claimed_by::text,'') FROM mls_key_packages WHERE ref_hash = ?`, ref).Scan(&claimedBy)
	if claimedBy == "" {
		t.Error("a claimed package must record who consumed it")
	}
	if got := countPackages(t, ownerApp, device, "store-B"); got != 2 {
		t.Errorf("count must drop after a claim, got %d", got)
	}

	// Single-use: the same package can never come back.
	seen := map[string]bool{ref: true}
	for i := 0; i < 2; i++ {
		r := claimPackage(t, claimerApp, db, owner, device)
		if r == "" || seen[r] {
			t.Fatalf("claim %d returned %q - packages must be single-use", i, r)
		}
		seen[r] = true
	}
	if r := claimPackage(t, claimerApp, db, owner, device); r != "" {
		t.Fatalf("expected exhaustion, got %q", r)
	}
	if got := countPackages(t, ownerApp, device, "store-B"); got != 0 {
		t.Errorf("an exhausted store must count 0 - this is the replenishment signal, got %d", got)
	}
}

// A client that does not declare a store must still be accepted, and its rows
// must land as NULL rather than as an empty-string pseudo-incarnation.
func TestPublishFromAPreStoreIDClientRecordsNull(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := fullKeyPackageApp(owner)

	if stored := publishPackages(t, app, device, "", "legacy1", "legacy2"); stored != 2 {
		t.Fatalf("a pre-store_id client must still be able to publish, stored %d", stored)
	}
	var nulls, empties int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id IS NULL`).Scan(&nulls)
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id = ''`).Scan(&empties)
	if nulls != 2 || empties != 0 {
		t.Errorf("undeclared stores must persist as NULL: %d NULL, %d empty-string", nulls, empties)
	}
}

// ---- Test 2: incarnation isolation, both directions ----

// A claimer cannot ask for an incarnation; the server resolves whichever the
// device published under last. So isolation is verified by running the same
// scenario twice with the roles reversed.
func TestIncarnationIsolationInBothDirections(t *testing.T) {
	for _, tc := range []struct {
		name             string
		firstStore       string
		secondStore      string
		expectedPrefixes string
	}{
		{"B published last", "store-A", "store-B", "b"},
		{"A published last", "store-B", "store-A", "a"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			db := useScratchDB(t)
			owner, device := uuid.New(), "device-1"
			ownerApp := fullKeyPackageApp(owner)
			claimerApp := fullKeyPackageApp(uuid.New())

			first, second := "a", "b"
			if tc.firstStore == "store-B" {
				first, second = "b", "a"
			}
			publishPackages(t, ownerApp, device, tc.firstStore, first+"1", first+"2")
			// A visible gap, so "most recently created" is unambiguous.
			time.Sleep(10 * time.Millisecond)
			publishPackages(t, ownerApp, device, tc.secondStore, second+"1", second+"2")

			if got := countPackages(t, ownerApp, device, tc.firstStore); got != 2 {
				t.Errorf("%s should count only its own 2, got %d", tc.firstStore, got)
			}
			if got := countPackages(t, ownerApp, device, tc.secondStore); got != 2 {
				t.Errorf("%s should count only its own 2, got %d", tc.secondStore, got)
			}

			// Both claims must come from the incarnation published last. Their
			// order relative to each other is a tie - one batch, one timestamp.
			served := map[string]bool{}
			for i := 0; i < 2; i++ {
				got := claimPackage(t, claimerApp, db, owner, device)
				if got == "" || got[:1] != tc.expectedPrefixes {
					t.Fatalf("claim %d: expected the current incarnation (%q...), got %q",
						i, tc.expectedPrefixes, got)
				}
				if served[got] {
					t.Fatalf("claim %d re-served %q; packages are single-use", i, got)
				}
				served[got] = true
			}
			if got := claimPackage(t, claimerApp, db, owner, device); got != "" {
				t.Fatalf("the other incarnation leaked: %q", got)
			}
		})
	}
}

// ---- Test 3: NULL is a value, not a wildcard ----

// The failure this whole column exists to prevent: a device that rebuilt its
// store being served the packages of the store it abandoned. Legacy rows carry
// no tag at all, and "no tag" must not mean "matches anything".
func TestNullStoreIsNotAWildcard(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)
	claimerApp := fullKeyPackageApp(uuid.New())

	// A generous legacy stock, well above any low-water mark.
	legacy := make([]string, 0, 8)
	for i := 0; i < 8; i++ {
		legacy = append(legacy, fmt.Sprintf("legacy%d", i))
	}
	publishPackages(t, ownerApp, device, "", legacy...)
	time.Sleep(10 * time.Millisecond)
	publishPackages(t, ownerApp, device, "store-B", "b1")

	// Counting: the 8 NULL rows are invisible to store B.
	if got := countPackages(t, ownerApp, device, "store-B"); got != 1 {
		t.Errorf("store B must see only its own package, got %d", got)
	}

	// Claiming: B's single package, then nothing - never a legacy row.
	if ref := claimPackage(t, claimerApp, db, owner, device); ref != "b1" {
		t.Fatalf("expected b1, got %q", ref)
	}
	if ref := claimPackage(t, claimerApp, db, owner, device); ref != "" {
		t.Fatalf("NULL was treated as a wildcard: served %q", ref)
	}

	var untouched int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id IS NULL AND claimed_at IS NULL`).Scan(&untouched)
	if untouched != 8 {
		t.Errorf("legacy rows must be left exactly as found, %d of 8 remain", untouched)
	}
}

// A store that has published nothing sees an empty shelf regardless of how much
// legacy inventory the device is sitting on. That zero is what makes the client
// replenish instead of concluding it is well supplied.
func TestNewStoreSeesZeroAndSoReplenishes(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := fullKeyPackageApp(owner)

	legacy := make([]string, 0, 12)
	for i := 0; i < 12; i++ {
		legacy = append(legacy, fmt.Sprintf("legacy%d", i))
	}
	publishPackages(t, app, device, "", legacy...)

	const lowWater = 3 // KEY_PACKAGE_LOW_WATER in MlsV2Repository
	got := countPackages(t, app, device, "store-FRESH")
	if got != 0 {
		t.Fatalf("a store that has published nothing must count 0, got %d", got)
	}
	if got >= lowWater {
		t.Fatalf("count %d would suppress replenishment", got)
	}

	// And once it does replenish, it sees its own stock and nobody else's.
	publishPackages(t, app, device, "store-FRESH", "f1", "f2", "f3", "f4")
	if got := countPackages(t, app, device, "store-FRESH"); got != 4 {
		t.Errorf("expected the new store's own 4, got %d", got)
	}
	var stillLegacy int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id IS NULL`).Scan(&stillLegacy)
	if stillLegacy != 12 {
		t.Errorf("replenishment must not disturb legacy rows, %d of 12 remain", stillLegacy)
	}
}

// ---- Test 4: exhaustion must not fall back ----

// Store B runs dry while store A still holds unclaimed packages. The correct
// answer is "none available", which the client reads as "republish" - not a
// package from A that the device could never open.
func TestExhaustionDoesNotFallBackToAnotherIncarnation(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)
	claimerApp := fullKeyPackageApp(uuid.New())

	publishPackages(t, ownerApp, device, "store-A", "a1", "a2", "a3", "a4")
	time.Sleep(10 * time.Millisecond)
	publishPackages(t, ownerApp, device, "store-B", "b1", "b2")

	for i := 0; i < 2; i++ {
		if got := claimPackage(t, claimerApp, db, owner, device); got == "" || got[:1] != "b" {
			t.Fatalf("claim %d: expected a store-B package, got %q", i, got)
		}
	}

	if got := countPackages(t, ownerApp, device, "store-B"); got != 0 {
		t.Errorf("store B must count 0 once exhausted, got %d", got)
	}
	// Repeated attempts must keep refusing rather than eventually yielding an A.
	for i := 0; i < 3; i++ {
		if got := claimPackage(t, claimerApp, db, owner, device); got != "" {
			t.Fatalf("attempt %d fell back to another incarnation: %q", i, got)
		}
	}
	var aLeft int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id = 'store-A' AND claimed_at IS NULL`).Scan(&aLeft)
	if aLeft != 4 {
		t.Errorf("store A's packages must be untouched, %d of 4 remain", aLeft)
	}

	// Replenishing store B restores service - and only with B's own packages.
	publishPackages(t, ownerApp, device, "store-B", "b3")
	if got := claimPackage(t, claimerApp, db, owner, device); got != "b3" {
		t.Fatalf("after replenishment the current store must serve again, got %q", got)
	}
}

// ---- Documented limitation, pinned so it cannot change silently ----

// The server learns about a new incarnation only from its first publish. If a
// recovered device's first publish fails, the previous incarnation is still the
// most recent one on record, and claims keep being served from it - packages the
// recovered device can no longer open.
//
// This is a CHARACTERIZATION test: it asserts the behaviour as it is, not as it
// ought to be. Closing the window needs the client to declare its incarnation
// outside the publish path, which is a protocol change and deliberately out of
// scope. If this test ever fails, the limitation was fixed - update the docs.
func TestFailedFirstPublishLeavesTheOldIncarnationCurrent(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)
	claimerApp := fullKeyPackageApp(uuid.New())

	publishPackages(t, ownerApp, device, "store-A", "a1", "a2")
	// Recovery happens here: the device now runs store B. Its first publish
	// fails, so nothing tagged store-B ever reaches the server.

	if got := countPackages(t, ownerApp, device, "store-B"); got != 0 {
		t.Fatalf("store B has published nothing and must count 0, got %d", got)
	}
	// The device correctly sees an empty shelf and will retry publishing. But
	// until it succeeds, a claimer is still served store A.
	got := claimPackage(t, claimerApp, db, owner, device)
	if got == "" || got[:1] != "a" {
		t.Fatalf("expected the known limitation (an A package served), got %q", got)
	}
	t.Log("known limitation: until the recovered store publishes once, claims " +
		"are still served from the abandoned incarnation")

	// The self-heal: one successful publish makes B current immediately.
	time.Sleep(10 * time.Millisecond)
	publishPackages(t, ownerApp, device, "store-B", "b1")
	if got := claimPackage(t, claimerApp, db, owner, device); got != "b1" {
		t.Fatalf("a successful publish must make the new incarnation current, got %q", got)
	}
}

// FIFO operates on created_at, and a whole publish batch is written with one
// timestamp - so ordering WITHIN a batch is a tie, in the scratch database and
// in the live one alike. That is harmless: every package in a batch belongs to
// the same incarnation and is an equally valid single-use prekey. What FIFO
// actually guarantees is oldest-BATCH-first, which is what this pins.
func TestFifoIsAcrossBatchesNotWithinThem(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)
	claimerApp := fullKeyPackageApp(uuid.New())

	// Two batches from the SAME store, separated in time.
	publishPackages(t, ownerApp, device, "store-A", "early1", "early2")
	time.Sleep(10 * time.Millisecond)
	publishPackages(t, ownerApp, device, "store-A", "late1", "late2")

	first := map[string]bool{}
	for i := 0; i < 2; i++ {
		got := claimPackage(t, claimerApp, db, owner, device)
		if got == "" || got[:5] != "early" {
			t.Fatalf("claim %d: the older batch must be consumed first, got %q", i, got)
		}
		first[got] = true
	}
	if len(first) != 2 {
		t.Fatal("both packages of the older batch should have been served")
	}
	for i := 0; i < 2; i++ {
		if got := claimPackage(t, claimerApp, db, owner, device); got == "" || got[:4] != "late" {
			t.Fatalf("claim %d: expected the later batch, got %q", i, got)
		}
	}
}
