package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

// Database-backed tests for KeyPackage store-incarnation scoping.
//
// A device keeps its device id across a reinstall, a recovery reset, or a change
// of MLS implementation, but its local store does not: the private init keys
// behind everything it published die with the old store. Those rows stay on the
// server, and before store_id existed they were still handed out - producing a
// Welcome the recipient answered with "No matching key package was found in the
// key store", which is exactly how this was found in the field.
//
// These drive the real handlers against real Postgres, because the behaviour
// being fixed lives in the SQL, not in a decision function.
//
// Requires a scratch database. NEVER point this at the live messenger DB.
//
//	TEST_DATABASE_URL="host=localhost port=5432 user=postgres password=... dbname=messenger_e2ee_scratch sslmode=disable"

func seedKeyPackageTable(t *testing.T, db *gorm.DB) {
	t.Helper()
	stmts := []string{
		`DROP TABLE IF EXISTS mls_key_packages`,
		`CREATE TABLE mls_key_packages (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			user_id UUID NOT NULL,
			device_id VARCHAR(128) NOT NULL,
			cipher_suite BIGINT NOT NULL,
			key_package_data BYTEA NOT NULL,
			ref_hash VARCHAR(128) NOT NULL UNIQUE,
			store_id VARCHAR(128),
			claimed_at TIMESTAMPTZ,
			claimed_by UUID,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	}
	for _, s := range stmts {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("seed key package schema: %v", err)
		}
	}
}

// addPackage inserts one package. A blank store is stored as NULL, which is how
// a legacy row - published before store_id existed - looks.
func addPackage(t *testing.T, db *gorm.DB, user uuid.UUID, device, store, ref string, age time.Duration) {
	t.Helper()
	var storeVal interface{}
	if store != "" {
		storeVal = store
	}
	err := db.Exec(
		`INSERT INTO mls_key_packages
		   (user_id, device_id, cipher_suite, key_package_data, ref_hash, store_id, created_at)
		 VALUES (?, ?, 1, ?, ?, ?, ?)`,
		user, device, []byte("kp:"+ref), ref, storeVal, time.Now().Add(-age)).Error
	if err != nil {
		t.Fatalf("insert package %s: %v", ref, err)
	}
}

func keyPackageApp(as uuid.UUID) *fiber.App {
	app := fiber.New()
	inject := func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: as})
		return c.Next()
	}
	app.Get("/e2ee/mls/keypackages/count", inject, (&MLSHandler{}).CountKeyPackages)
	app.Post("/e2ee/mls/keypackages/claim", inject, (&MLSHandler{}).ClaimKeyPackage)
	return app
}

func countPackages(t *testing.T, app *fiber.App, device, store string) int {
	t.Helper()
	url := "/e2ee/mls/keypackages/count"
	if store != "" {
		url += "?store_id=" + store
	}
	req := httptest.NewRequest(http.MethodGet, url, nil)
	req.Header.Set("X-Device-Id", device)
	resp, err := app.Test(req, 10000)
	if err != nil {
		t.Fatalf("count request: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	var out struct {
		Available int `json:"available"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatalf("count response %q: %v", body, err)
	}
	return out.Available
}

// claimPackage returns the ref_hash of whatever was served, or "" on 404.
func claimPackage(t *testing.T, app *fiber.App, db *gorm.DB, target uuid.UUID, device string) string {
	t.Helper()
	raw, _ := json.Marshal(map[string]any{
		"user_id": target.String(), "device_id": device, "cipher_suite": 1,
	})
	req := httptest.NewRequest(http.MethodPost, "/e2ee/mls/keypackages/claim", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, 10000)
	if err != nil {
		t.Fatalf("claim request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return ""
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("unexpected claim status %d: %s", resp.StatusCode, body)
	}
	_, _ = io.ReadAll(resp.Body)

	// The handler returns opaque bytes; map them back to the row just consumed
	// so a test can name it.
	var ref string
	if err := db.Raw(
		`SELECT ref_hash FROM mls_key_packages
		  WHERE claimed_at IS NOT NULL ORDER BY claimed_at DESC, id DESC LIMIT 1`).
		Scan(&ref).Error; err != nil {
		t.Fatalf("read back claimed row: %v", err)
	}
	return ref
}

func useScratchDB(t *testing.T) *gorm.DB {
	t.Helper()
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })
	seedKeyPackageTable(t, db)
	return db
}

// A device that rebuilt its store must see only what the CURRENT store made.
// Counting the abandoned stock is what let a device conclude it was well
// supplied and never republish.
func TestCountIsScopedToTheStoreIncarnation(t *testing.T) {
	db := useScratchDB(t)
	user, device := uuid.New(), "device-1"
	app := keyPackageApp(user)

	for i := 0; i < 5; i++ {
		addPackage(t, db, user, device, "store-A", fmt.Sprintf("a%d", i), time.Duration(50-i)*time.Minute)
	}
	for i := 0; i < 3; i++ {
		addPackage(t, db, user, device, "store-B", fmt.Sprintf("b%d", i), time.Duration(10-i)*time.Minute)
	}

	if got := countPackages(t, app, device, "store-A"); got != 5 {
		t.Errorf("store-A should see its own 5, got %d", got)
	}
	if got := countPackages(t, app, device, "store-B"); got != 3 {
		t.Errorf("store-B should see its own 3, got %d", got)
	}
	if got := countPackages(t, app, device, ""); got != 8 {
		t.Errorf("an unscoped count stays unscoped for older clients, got %d", got)
	}
}

// The core fix: a package belonging to an abandoned store must never be served,
// no matter how much older - and therefore how FIFO-preferred - it is.
func TestClaimNeverServesAnAbandonedIncarnation(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New()) // someone else does the claiming

	// The abandoned store's packages are the oldest, so FIFO would reach them
	// first if nothing scoped the query.
	for i := 0; i < 4; i++ {
		addPackage(t, db, owner, device, "store-OLD", fmt.Sprintf("old%d", i), time.Duration(100-i)*time.Minute)
	}
	for i := 0; i < 2; i++ {
		addPackage(t, db, owner, device, "store-NEW", fmt.Sprintf("new%d", i), time.Duration(20-i)*time.Minute)
	}

	for i := 0; i < 2; i++ {
		ref := claimPackage(t, app, db, owner, device)
		if ref == "" {
			t.Fatalf("claim %d: expected a package from the current store", i)
		}
		if ref[:3] != "new" {
			t.Fatalf("claim %d served %q - a package from an abandoned store", i, ref)
		}
	}

	// Current store exhausted. The right answer is "none available", NOT a
	// package the owner can no longer open.
	if ref := claimPackage(t, app, db, owner, device); ref != "" {
		t.Fatalf("expected 404 once the current store ran dry, got %q", ref)
	}

	var stranded int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id = 'store-OLD' AND claimed_at IS NULL`).
		Scan(&stranded)
	if stranded != 4 {
		t.Errorf("the abandoned store's packages must be left untouched, %d of 4 remain", stranded)
	}
}

// Legacy rows carry no store at all. They must be just as unreachable once the
// device has republished under a real incarnation.
func TestLegacyRowsAreNotServedToANewStore(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New())

	for i := 0; i < 3; i++ {
		addPackage(t, db, owner, device, "", fmt.Sprintf("legacy%d", i), time.Duration(200-i)*time.Minute)
	}
	addPackage(t, db, owner, device, "store-NEW", "new0", 5*time.Minute)

	if ref := claimPackage(t, app, db, owner, device); ref != "new0" {
		t.Fatalf("expected the current store's package, got %q", ref)
	}
	if ref := claimPackage(t, app, db, owner, device); ref != "" {
		t.Fatalf("a legacy row was served to a rebuilt store: %q", ref)
	}

	var untouched int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE store_id IS NULL AND claimed_at IS NULL`).
		Scan(&untouched)
	if untouched != 3 {
		t.Errorf("legacy rows must be left exactly as found, %d of 3 remain", untouched)
	}
}

// A device that has only legacy stock is a device that has not upgraded yet.
// Nothing about it may change until it republishes.
func TestLegacyOnlyDeviceKeepsWorking(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New())

	addPackage(t, db, owner, device, "", "legacy-old", 90*time.Minute)
	addPackage(t, db, owner, device, "", "legacy-new", 30*time.Minute)

	if ref := claimPackage(t, app, db, owner, device); ref != "legacy-old" {
		t.Fatalf("a legacy-only device must still be served, oldest first; got %q", ref)
	}
	if ref := claimPackage(t, app, db, owner, device); ref != "legacy-new" {
		t.Fatalf("expected the second legacy package, got %q", ref)
	}
}

// FIFO is not the bug and must survive the fix: within one incarnation the
// oldest package is still consumed first.
func TestClaimIsFifoWithinTheIncarnation(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New())

	addPackage(t, db, owner, device, "store-A", "third", 10*time.Minute)
	addPackage(t, db, owner, device, "store-A", "first", 90*time.Minute)
	addPackage(t, db, owner, device, "store-A", "second", 50*time.Minute)

	for _, want := range []string{"first", "second", "third"} {
		if got := claimPackage(t, app, db, owner, device); got != want {
			t.Fatalf("FIFO broken: wanted %q, got %q", want, got)
		}
	}
}

// The replenishment trap: a new store must see an empty shelf even when the
// device's legacy stock is far above the low-water mark, or it publishes
// nothing and stays permanently unaddable.
func TestNewStoreSeesNoStockDespiteLegacyInventory(t *testing.T) {
	db := useScratchDB(t)
	user, device := uuid.New(), "device-1"
	app := keyPackageApp(user)

	for i := 0; i < 9; i++ {
		addPackage(t, db, user, device, "", fmt.Sprintf("legacy%d", i), time.Duration(300-i)*time.Minute)
	}

	if got := countPackages(t, app, device, "store-FRESH"); got != 0 {
		t.Fatalf("a store that has published nothing must count 0, got %d", got)
	}
}

// One incarnation's healthy stock must not mask another's empty one.
func TestOneIncarnationCannotSuppressAnothersReplenishment(t *testing.T) {
	db := useScratchDB(t)
	user, device := uuid.New(), "device-1"
	app := keyPackageApp(user)

	for i := 0; i < 10; i++ {
		addPackage(t, db, user, device, "store-OLD", fmt.Sprintf("old%d", i), time.Duration(300-i)*time.Minute)
	}
	addPackage(t, db, user, device, "store-NEW", "new0", time.Minute)

	if got := countPackages(t, app, device, "store-NEW"); got != 1 {
		t.Fatalf("the new store must see only its own single package, got %d", got)
	}
}

// Scoping is per device as well as per store: one device rebuilding must not
// make a sibling device's packages unreachable.
func TestScopingIsPerDevice(t *testing.T) {
	db := useScratchDB(t)
	owner := uuid.New()
	app := keyPackageApp(uuid.New())

	addPackage(t, db, owner, "device-1", "store-A", "d1", 60*time.Minute)
	addPackage(t, db, owner, "device-2", "store-B", "d2", 10*time.Minute)

	if ref := claimPackage(t, app, db, owner, "device-1"); ref != "d1" {
		t.Fatalf("device-1 should be served its own package, got %q", ref)
	}
	if ref := claimPackage(t, app, db, owner, "device-2"); ref != "d2" {
		t.Fatalf("device-2 should be served its own package, got %q", ref)
	}
}

// Claimed packages still name the current incarnation. A store whose whole
// stock has been consumed must not silently fall back to an older one.
func TestExhaustedStoreDoesNotFallBackToAnOlderOne(t *testing.T) {
	db := useScratchDB(t)
	owner, device := uuid.New(), "device-1"
	app := keyPackageApp(uuid.New())

	addPackage(t, db, owner, device, "store-OLD", "old0", 100*time.Minute)
	addPackage(t, db, owner, device, "store-NEW", "new0", 10*time.Minute)

	if ref := claimPackage(t, app, db, owner, device); ref != "new0" {
		t.Fatalf("expected the current store's only package, got %q", ref)
	}
	// store-NEW is now fully consumed; its newest row is claimed, not gone.
	if ref := claimPackage(t, app, db, owner, device); ref != "" {
		t.Fatalf("an exhausted current store fell back to %q", ref)
	}
}

// legacyKeyPackage is MLSKeyPackage exactly as it was before store_id existed.
// The pre-migration table is built through GORM from this, not hand-rolled SQL,
// so the starting point matches what the running server actually created -
// including GORM's own index and constraint naming.
type legacyKeyPackage struct {
	ID             uuid.UUID  `gorm:"type:uuid;default:gen_random_uuid();primaryKey"`
	UserID         uuid.UUID  `gorm:"type:uuid;index;not null"`
	DeviceID       string     `gorm:"size:128;index;not null"`
	CipherSuite    int        `gorm:"not null"`
	KeyPackageData []byte     `gorm:"type:bytea;not null"`
	RefHash        string     `gorm:"size:128;uniqueIndex;not null"`
	ClaimedAt      *time.Time `gorm:"index"`
	ClaimedBy      *uuid.UUID `gorm:"type:uuid"`
	CreatedAt      time.Time
}

func (legacyKeyPackage) TableName() string { return "mls_key_packages" }

// The migration itself: store_id has to arrive as a NULLABLE column on a table
// that already has rows, leaving every existing row unattributed. A NOT NULL
// column would either refuse to migrate or need a default - and any default
// would be a lie about which store made those packages.
func TestAutoMigrateAddsStoreIDWithoutDisturbingExistingRows(t *testing.T) {
	db := recreateTestDB(t)

	if err := db.Exec(`DROP TABLE IF EXISTS mls_key_packages`).Error; err != nil {
		t.Fatalf("drop: %v", err)
	}
	if err := db.AutoMigrate(&legacyKeyPackage{}); err != nil {
		t.Fatalf("build the pre-migration table: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO mls_key_packages (user_id, device_id, cipher_suite, key_package_data, ref_hash)
		 VALUES (?, 'device-1', 1, ?, 'pre-existing')`,
		uuid.New(), []byte("kp")).Error; err != nil {
		t.Fatalf("seed legacy row: %v", err)
	}

	if err := db.AutoMigrate(&models.MLSKeyPackage{}); err != nil {
		t.Fatalf("AutoMigrate must succeed on a populated table: %v", err)
	}

	var col struct {
		IsNullable string
		DataType   string
	}
	if err := db.Raw(
		`SELECT is_nullable, data_type FROM information_schema.columns
		  WHERE table_name = 'mls_key_packages' AND column_name = 'store_id'`).
		Scan(&col).Error; err != nil {
		t.Fatalf("read column metadata: %v", err)
	}
	if col.IsNullable != "YES" {
		t.Errorf("store_id must be nullable so legacy rows stay unattributed, got %q", col.IsNullable)
	}

	var nulls int64
	db.Raw(`SELECT count(*) FROM mls_key_packages WHERE ref_hash = 'pre-existing' AND store_id IS NULL`).
		Scan(&nulls)
	if nulls != 1 {
		t.Error("the pre-existing row must survive the migration with a NULL store_id")
	}

	// The claim path filters on store_id; without an index it degrades to a scan.
	var indexed int64
	db.Raw(`SELECT count(*) FROM pg_indexes
	         WHERE tablename = 'mls_key_packages' AND indexdef LIKE '%store_id%'`).Scan(&indexed)
	if indexed == 0 {
		t.Error("expected an index covering store_id")
	}
}
