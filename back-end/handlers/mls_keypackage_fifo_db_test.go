package handlers

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

// FIFO invariants for the KeyPackage claim path.
//
// What FIFO means here is not obvious and was mis-stated in the bug report, so
// it is worth writing down. The claim query orders by created_at, and that is
// the ONLY column carrying real order:
//
//   - the primary key is a random v4 UUID minted in PublishKeyPackages, so it
//     encodes nothing about when a row arrived - see
//     TestPrimaryKeyDoesNotEncodeInsertionOrder;
//   - created_at is written by the application as time.Now(), one call per row,
//     and the Windows wall clock advances in ~0.5ms steps - coarser than the
//     loop that writes a batch - so rows inside one publish share a timestamp.
//
// The contract is therefore: oldest-BATCH-first, exactly. Order among rows that
// share a timestamp is unspecified and harmless - every row in a batch is an
// equally valid single-use prekey from the same incarnation. These tests assert
// that contract and nothing stronger; asserting an order among ties would pin
// an implementation detail rather than a guarantee.
//
// Requires a scratch database. NEVER point this at the live messenger DB.
//
//	TEST_DATABASE_URL="host=localhost port=5432 user=postgres password=... dbname=messenger_e2ee_scratch sslmode=disable"

// addPackageAt inserts one package at an EXACT created_at, so a test can build
// a timestamp tie on purpose instead of hoping the clock produces one.
func addPackageAt(t *testing.T, db *gorm.DB, user uuid.UUID, device, store, ref string, at time.Time) {
	t.Helper()
	var storeVal interface{}
	if store != "" {
		storeVal = store
	}
	err := db.Exec(
		`INSERT INTO mls_key_packages
		   (user_id, device_id, cipher_suite, key_package_data, ref_hash, store_id, created_at)
		 VALUES (?, ?, 1, ?, ?, ?, ?)`,
		user, device, []byte("kp:"+ref), ref, storeVal, at).Error
	if err != nil {
		t.Fatalf("insert package %s: %v", ref, err)
	}
}

// refsInPKOrder is what a claim path ordered by the primary key would consume.
func refsInPKOrder(t *testing.T, db *gorm.DB) []string {
	t.Helper()
	var got []string
	if err := db.Raw(`SELECT ref_hash FROM mls_key_packages ORDER BY id`).Scan(&got).Error; err != nil {
		t.Fatalf("read pk order: %v", err)
	}
	return got
}

// drainClaims consumes n packages and fails if the shelf empties early.
func drainClaims(t *testing.T, app *fiber.App, db *gorm.DB, owner uuid.UUID, device string, n int) []string {
	t.Helper()
	got := make([]string, 0, n)
	for i := 0; i < n; i++ {
		ref := claimPackage(t, app, db, owner, device)
		if ref == "" {
			t.Fatalf("claim %d: expected a package, got 404 with stock remaining", i)
		}
		got = append(got, ref)
	}
	return got
}

// The premise the whole ordering rests on: the primary key is random, so it
// cannot stand in for insertion order. If this ever fails the key has become
// sequential and the choice of ordering column deserves a fresh look - it does
// NOT mean the claim path may start ordering by id, because the contract is
// written in created_at.
func TestPrimaryKeyDoesNotEncodeInsertionOrder(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := fullKeyPackageApp(owner)

	inserted := make([]string, 0, 10)
	for i := 0; i < 10; i++ {
		inserted = append(inserted, fmt.Sprintf("q%02d", i))
	}
	publishPackages(t, app, device, "store-A", inserted...)

	pkOrder := refsInPKOrder(t, db)
	if equalRefs(pkOrder, inserted) {
		t.Fatalf("the primary key now tracks insertion order (%v). A random v4 UUID "+
			"does that 1 time in 3628800 by chance, so the key type has changed", pkOrder)
	}
	t.Logf("insertion order %v vs primary-key order %v", inserted, pkOrder)
}

// The FIFO contract at full strength: consumption follows created_at, and
// follows NOTHING else. The fixture is built so created_at order differs from
// insertion order AND from primary-key order, which is what makes this test
// kill an ORDER BY id, an unordered query, and a reversed one alike.
func TestClaimFollowsCreatedAtNotInsertionOrderOrPrimaryKey(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New())

	// Inserted deliberately out of chronological order. Age is how far in the
	// past a row was created, so a LARGER age is an OLDER package.
	seeds := []struct {
		ref string
		age int
	}{
		{"k07", 40}, {"k02", 95}, {"k11", 5}, {"k05", 60},
		{"k00", 120}, {"k09", 25}, {"k03", 85}, {"k08", 30},
		{"k01", 110}, {"k06", 50}, {"k04", 70}, {"k10", 15},
	}
	insertion := make([]string, 0, len(seeds))
	for _, s := range seeds {
		addPackage(t, db, owner, device, "store-A", s.ref, time.Duration(s.age)*time.Minute)
		insertion = append(insertion, s.ref)
	}

	// k00..k11 is oldest-first by construction.
	want := append([]string(nil), insertion...)
	sort.Strings(want)

	// Preconditions. Without them the assertion below could pass for the wrong
	// reason: if the fixture happened to be inserted chronologically, a claim
	// path that ignored created_at entirely could still look correct.
	if equalRefs(insertion, want) {
		t.Fatal("fixture is inserted in chronological order and would not detect a claim path that ignores created_at")
	}
	if pk := refsInPKOrder(t, db); equalRefs(pk, want) {
		t.Fatalf("primary-key order coincides with chronological order (%v); the fixture proves nothing this run", pk)
	}

	got := drainClaims(t, app, db, owner, device, len(seeds))
	if !equalRefs(got, want) {
		t.Fatalf("claims must follow created_at ascending\n want %v\n  got %v", want, got)
	}
	if ref := claimPackage(t, app, db, owner, device); ref != "" {
		t.Fatalf("stock is exhausted, expected 404, got %q", ref)
	}
}

// Ties are the case the bug report was really about, so build one on purpose
// rather than waiting for the clock to produce one.
//
// Five rows share a timestamp exactly. The contract does not order them among
// themselves - but it does say each is consumed after the strictly older row,
// before the strictly newer ones, and exactly once.
func TestClaimUnderExplicitTimestampTies(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New())

	base := time.Now().Add(-time.Hour).Truncate(time.Microsecond)
	addPackageAt(t, db, owner, device, "store-A", "oldest", base.Add(-time.Minute))
	tied := []string{"tie0", "tie1", "tie2", "tie3", "tie4"}
	for _, ref := range tied {
		addPackageAt(t, db, owner, device, "store-A", ref, base)
	}
	addPackageAt(t, db, owner, device, "store-A", "newer0", base.Add(time.Minute))
	addPackageAt(t, db, owner, device, "store-A", "newer1", base.Add(2*time.Minute))

	// The tie has to exist in Postgres, or this test proves nothing.
	var distinct int64
	db.Raw(`SELECT count(DISTINCT created_at) FROM mls_key_packages WHERE ref_hash LIKE 'tie%'`).Scan(&distinct)
	if distinct != 1 {
		t.Fatalf("the five tied rows must share one created_at, found %d distinct values", distinct)
	}

	got := drainClaims(t, app, db, owner, device, 8)

	if got[0] != "oldest" {
		t.Fatalf("the strictly oldest package must come first, got %q (full order %v)", got[0], got)
	}
	middle := append([]string(nil), got[1:6]...)
	sort.Strings(middle)
	if !equalRefs(middle, tied) {
		t.Fatalf("every tied package must be consumed before any newer one, got %v", got)
	}
	if got[6] != "newer0" || got[7] != "newer1" {
		t.Fatalf("newer packages must follow in created_at order, got %v", got[6:])
	}
	if dupes := duplicateRefs(got); len(dupes) != 0 {
		t.Fatalf("a package was served twice under a timestamp tie: %v", dupes)
	}
}

// End to end through the publish handler: several batches, each written with
// whatever timestamps the real clock hands it. Every package of an older batch
// must be gone before any package of a newer one is touched.
func TestFifoIsStrictAcrossManyPublishBatches(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)
	claimerApp := fullKeyPackageApp(uuid.New())

	batches := [][]string{
		{"b0r0", "b0r1", "b0r2"},
		{"b1r0", "b1r1", "b1r2"},
		{"b2r0", "b2r1", "b2r2"},
		{"b3r0", "b3r1", "b3r2"},
	}
	for i, refs := range batches {
		if i > 0 {
			time.Sleep(15 * time.Millisecond)
		}
		publishPackages(t, ownerApp, device, "store-A", refs...)
	}

	// Report the ties this produced. They are expected and permitted; the point
	// is that the guarantee below holds with them present.
	var rows, stamps int64
	if err := db.Raw(`SELECT count(*), count(DISTINCT created_at) FROM mls_key_packages`).
		Row().Scan(&rows, &stamps); err != nil {
		t.Fatalf("count timestamps: %v", err)
	}
	t.Logf("%d rows across %d batches carry %d distinct created_at values", rows, len(batches), stamps)

	got := drainClaims(t, claimerApp, db, owner, device, int(rows))
	for i, refs := range batches {
		slice := append([]string(nil), got[i*len(refs):(i+1)*len(refs)]...)
		sort.Strings(slice)
		if !equalRefs(slice, refs) {
			t.Fatalf("batch %d must be consumed as a whole before the next one\n want %v\n  got %v (full order %v)",
				i, refs, slice, got)
		}
	}
	if dupes := duplicateRefs(got); len(dupes) != 0 {
		t.Fatalf("packages served more than once: %v", dupes)
	}
}

// A claimed package is spent. It must never be served again, and a later
// claimant must never overwrite the record of who consumed it.
func TestAlreadyClaimedPackagesAreExcludedAndNotRewritten(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	firstClaimer := uuid.New()
	app := keyPackageApp(uuid.New())

	for i := 0; i < 4; i++ {
		addPackage(t, db, owner, device, "store-A", fmt.Sprintf("s%d", i), time.Duration(100-i*10)*time.Minute)
	}
	// s0 and s1 are the two OLDEST - the ones FIFO reaches first - and are
	// already spent, so a claim path that lost its claimed_at predicate fails
	// here rather than quietly reissuing an init key.
	spentAt := time.Now().Add(-30 * time.Minute).Truncate(time.Microsecond)
	if err := db.Exec(
		`UPDATE mls_key_packages SET claimed_at = ?, claimed_by = ? WHERE ref_hash IN ('s0','s1')`,
		spentAt, firstClaimer).Error; err != nil {
		t.Fatalf("pre-claim: %v", err)
	}

	if got := drainClaims(t, app, db, owner, device, 2); !equalRefs(got, []string{"s2", "s3"}) {
		t.Fatalf("only unclaimed packages may be served, oldest first; got %v", got)
	}
	if ref := claimPackage(t, app, db, owner, device); ref != "" {
		t.Fatalf("expected 404 once the unclaimed stock ran out, got %q", ref)
	}

	var intact int64
	db.Raw(`SELECT count(*) FROM mls_key_packages
	         WHERE ref_hash IN ('s0','s1') AND claimed_by = ? AND claimed_at = ?`,
		firstClaimer, spentAt).Scan(&intact)
	if intact != 2 {
		t.Errorf("a spent package's claim record was rewritten: %d of 2 intact", intact)
	}
}

// Concurrency. Two properties, and they are different in kind.
//
// Exclusivity is the one that matters for correctness: handing one KeyPackage
// to two adders reuses its init key, which is exactly what single-use is meant
// to prevent. That is what FOR UPDATE ... SKIP LOCKED buys.
//
// Ordering survives too, at the granularity the contract is written in. A
// claimer that finds a row held by a peer steps over it and takes the next, so
// the ORDER among simultaneous claims is not fixed - but the SET is: with N
// claimers in flight, at most N rows can be locked or spent, so no claimer can
// ever reach past the N oldest. Publish in batches of exactly N and each round
// of N simultaneous claims must consume exactly one batch. A claim path that
// ordered by anything other than created_at would scatter refs across batches
// and fail here.
func TestConcurrentClaimersNeverShareAPackage(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	ownerApp := fullKeyPackageApp(owner)

	const claimers = 8
	const rounds = 3
	batches := make([][]string, rounds)
	for r := 0; r < rounds; r++ {
		if r > 0 {
			// Separate the batches in time by far more than the ~0.5ms clock
			// tick, so the batch boundaries are real orderings rather than ties.
			time.Sleep(15 * time.Millisecond)
		}
		refs := make([]string, 0, claimers)
		for i := 0; i < claimers; i++ {
			refs = append(refs, fmt.Sprintf("b%dr%d", r, i))
		}
		publishPackages(t, ownerApp, device, "store-A", refs...)
		batches[r] = refs
	}
	stock := rounds * claimers

	// One app per claimer, built up front: the identity a claim records is the
	// app's, so distinct apps are what make "8 distinct claimers" checkable.
	apps := make([]*fiber.App, claimers)
	for i := range apps {
		apps[i] = fullKeyPackageApp(uuid.New())
	}

	var served []string
	for r := 0; r < rounds; r++ {
		var (
			mu    sync.Mutex
			round []string
			errs  []string
			wg    sync.WaitGroup
			ready sync.WaitGroup
		)
		// Release all eight into the claim at once. Without the barrier the
		// first goroutine scheduled drains the shelf on its own and the test
		// proves nothing about contention.
		start := make(chan struct{})
		ready.Add(claimers)
		for g := 0; g < claimers; g++ {
			wg.Add(1)
			go func(g int) {
				defer wg.Done()
				ready.Done()
				<-start
				ref, status, err := claimConcurrently(apps[g], owner, device)
				mu.Lock()
				defer mu.Unlock()
				switch {
				case err != nil:
					errs = append(errs, fmt.Sprintf("claimer %d: %v", g, err))
				case status != http.StatusOK:
					errs = append(errs, fmt.Sprintf("claimer %d: status %d with stock remaining", g, status))
				default:
					round = append(round, ref)
				}
			}(g)
		}
		ready.Wait()
		close(start)
		wg.Wait()

		if len(errs) != 0 {
			t.Fatalf("round %d: %v", r, errs)
		}
		got := append([]string(nil), round...)
		sort.Strings(got)
		if !equalRefs(got, batches[r]) {
			t.Fatalf("round %d of %d simultaneous claims must consume exactly batch %d\n want %v\n  got %v",
				r, claimers, r, batches[r], got)
		}
		served = append(served, round...)
	}

	if dupes := duplicateRefs(served); len(dupes) != 0 {
		t.Fatalf("the same package was handed to two claimers: %v", dupes)
	}
	if len(served) != stock {
		t.Fatalf("every package must be claimable exactly once: %d of %d served", len(served), stock)
	}
	// The shelf is empty and stays empty; no round may resurrect a spent row.
	if _, status, err := claimConcurrently(apps[0], owner, device); err != nil || status != http.StatusNotFound {
		t.Fatalf("exhausted stock must answer 404, got status %d err %v", status, err)
	}

	// Counted with the error checked. An unchecked Scan leaves its destination
	// at zero when the query fails, which reads back as "the database says
	// nothing was claimed" - a false accusation against the handler.
	count := func(where string) int64 {
		t.Helper()
		var n int64
		if err := db.Raw(`SELECT count(*) FROM mls_key_packages WHERE ` + where).Scan(&n).Error; err != nil {
			t.Fatalf("count %s: %v", where, err)
		}
		return n
	}
	total := count(`true`)
	unclaimed := count(`claimed_at IS NULL`)
	claimed := count(`claimed_at IS NOT NULL AND claimed_by IS NOT NULL`)
	var distinctClaimers int64
	if err := db.Raw(`SELECT count(DISTINCT claimed_by) FROM mls_key_packages WHERE claimed_by IS NOT NULL`).
		Scan(&distinctClaimers).Error; err != nil {
		t.Fatalf("count distinct claimers: %v", err)
	}
	if total != int64(stock) || unclaimed != 0 || claimed != int64(stock) {
		t.Fatalf("database disagrees with the responses: %d rows, %d unclaimed, %d claimed by a named claimer; want %d, 0, %d",
			total, unclaimed, claimed, stock, stock)
	}
	// Every claimer won a package in every round, so all eight identities must
	// appear. Fewer would mean the rounds were not actually contended.
	if distinctClaimers != claimers {
		t.Fatalf("expected all %d claimers to have taken a package, %d distinct claimed_by recorded",
			claimers, distinctClaimers)
	}
	t.Logf("%d packages consumed by %d distinct claimers across %d contended rounds, no duplicate",
		claimed, distinctClaimers, rounds)
}

// claimConcurrently is claimPackage without the *testing.T: a goroutine may not
// call t.Fatalf, so it returns what it found instead.
func claimConcurrently(app *fiber.App, target uuid.UUID, device string) (string, int, error) {
	raw, _ := json.Marshal(map[string]any{
		"user_id": target.String(), "device_id": device, "cipher_suite": 1,
	})
	req := httptest.NewRequest(http.MethodPost, "/e2ee/mls/keypackages/claim", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, 30000)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", resp.StatusCode, nil
	}
	var out struct {
		KeyPackageB64 string `json:"key_package_b64"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", resp.StatusCode, fmt.Errorf("response %q: %w", body, err)
	}
	data, err := base64.StdEncoding.DecodeString(out.KeyPackageB64)
	if err != nil {
		return "", resp.StatusCode, fmt.Errorf("key_package_b64 %q: %w", out.KeyPackageB64, err)
	}
	return string(bytes.TrimPrefix(data, []byte("kp:"))), resp.StatusCode, nil
}

func equalRefs(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func duplicateRefs(in []string) []string {
	seen, dupes := map[string]bool{}, []string{}
	for _, s := range in {
		if seen[s] {
			dupes = append(dupes, s)
		}
		seen[s] = true
	}
	return dupes
}
