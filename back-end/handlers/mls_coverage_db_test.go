package handlers

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"messenger-app/database"
	"messenger-app/middleware"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

// Database-backed tests for GetCoverage's pending/acked aggregation.
//
// A device's Welcomes are a chronological invitation ledger. Only the NEWEST
// one describes the device's current state, and it only proves a phantom once
// the group has moved past it. The first cut aggregated with "any unconsumed
// row wins", which made a device whose ancient Welcome could never be opened
// pending forever - and evictPhantomMembers, the one behavioural consumer of
// this field, evicted it on every pass. That is how a genuinely joined phone
// was removed and re-added in a loop that walked the live group from epoch 4
// to epoch 8.
//
// Requires a scratch database. NEVER point this at the live `messenger` DB:
// the setup drops and recreates the tables it uses.
//
//	TEST_DATABASE_URL="host=localhost port=5432 user=postgres password=... dbname=messenger_e2ee_scratch sslmode=disable"

// coverageSeed installs the tables GetCoverage reads plus one chat holding one
// MLS group at groupEpoch. No Welcomes: every test states its own ledger.
func coverageSeed(t *testing.T, db *gorm.DB, groupEpoch int64) (chatID string, member uuid.UUID) {
	t.Helper()

	stmts := []string{
		`DROP TABLE IF EXISTS mls_welcomes, mls_handshakes, mls_groups, chat_participants, e2ee_devices, mls_key_packages`,
		`CREATE TABLE chat_participants (
			chat_id UUID NOT NULL, user_id UUID NOT NULL, left_at TIMESTAMPTZ)`,
		`CREATE TABLE mls_groups (
			id UUID PRIMARY KEY,
			chat_id VARCHAR(64) NOT NULL,
			group_id_data BYTEA NOT NULL,
			cipher_suite INT NOT NULL,
			epoch BIGINT NOT NULL DEFAULT 0,
			created_by UUID NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			group_info_data BYTEA,
			group_info_epoch BIGINT NOT NULL DEFAULT 0)`,
		`CREATE UNIQUE INDEX idx_cov_mls_groups_chat_id ON mls_groups (chat_id)`,
		`CREATE TABLE mls_welcomes (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			chat_id VARCHAR(64) NOT NULL,
			recipient_user_id UUID NOT NULL, recipient_device_id VARCHAR(128),
			sender_user_id UUID NOT NULL, epoch BIGINT NOT NULL,
			payload BYTEA NOT NULL, consumed_at TIMESTAMPTZ,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
		`CREATE TABLE e2ee_devices (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			user_id UUID NOT NULL, device_id VARCHAR(128) NOT NULL,
			name VARCHAR(128), platform VARCHAR(32), public_key VARCHAR(512),
			revoked_at TIMESTAMPTZ, last_seen_at TIMESTAMPTZ,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
		`CREATE TABLE mls_key_packages (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			user_id UUID NOT NULL, device_id VARCHAR(128) NOT NULL,
			cipher_suite INT NOT NULL, key_package_data BYTEA NOT NULL,
			ref_hash VARCHAR(128) NOT NULL, store_id VARCHAR(128),
			claimed_at TIMESTAMPTZ,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	}
	for _, s := range stmts {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("seed schema: %v", err)
		}
	}

	chatUUID := uuid.New()
	chatID = chatUUID.String()
	member = uuid.New()

	if err := db.Exec(
		`INSERT INTO chat_participants (chat_id, user_id, left_at) VALUES (?, ?, NULL)`,
		chatUUID, member).Error; err != nil {
		t.Fatalf("seed participant: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO mls_groups (id, chat_id, group_id_data, cipher_suite, epoch, created_by)
		 VALUES (?, ?, ?, 1, ?, ?)`,
		uuid.New(), chatID, []byte(chatID), groupEpoch, member).Error; err != nil {
		t.Fatalf("seed group: %v", err)
	}
	return chatID, member
}

// addWelcome appends one row to the ledger. consumed_at is stamped a minute
// after creation so it can never be mistaken for an ordering key.
func addWelcome(
	t *testing.T, db *gorm.DB, chatID string, user uuid.UUID,
	device string, epoch int64, consumed bool, createdAt time.Time,
) {
	t.Helper()
	var consumedAt interface{}
	if consumed {
		consumedAt = createdAt.Add(time.Minute)
	}
	if err := db.Exec(
		`INSERT INTO mls_welcomes
		   (chat_id, recipient_user_id, recipient_device_id, sender_user_id,
		    epoch, payload, consumed_at, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		chatID, user, device, user, epoch, []byte("welcome"), consumedAt, createdAt,
	).Error; err != nil {
		t.Fatalf("seed welcome for %s: %v", device, err)
	}
}

func coverageApp(as uuid.UUID) *fiber.App {
	app := fiber.New()
	app.Get("/e2ee/mls/groups/:chat_id/coverage", func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: as})
		return (&MLSHandler{}).GetCoverage(c)
	})
	return app
}

func getCoverage(t *testing.T, app *fiber.App, chatID string) map[string]interface{} {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/e2ee/mls/groups/"+chatID+"/coverage", nil)
	resp, err := app.Test(req, 10000)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d (%s)", resp.StatusCode, string(payload))
	}
	var out map[string]interface{}
	if err := json.Unmarshal(payload, &out); err != nil {
		t.Fatalf("decode: %v (%s)", err, string(payload))
	}
	return out
}

func idList(t *testing.T, body map[string]interface{}, key string) []string {
	t.Helper()
	raw, ok := body[key].([]interface{})
	if !ok {
		t.Fatalf("%s missing or not a list in %v", key, body)
	}
	out := make([]string, 0, len(raw))
	for _, v := range raw {
		s, ok := v.(string)
		if !ok {
			t.Fatalf("%s holds a non-string entry: %v", key, v)
		}
		out = append(out, s)
	}
	return out
}

func containsID(ids []string, want string) bool {
	for _, id := range ids {
		if id == want {
			return true
		}
	}
	return false
}

// coverageFixture wires the scratch DB in and hands back a seeded chat.
func coverageFixture(t *testing.T, groupEpoch int64) (*gorm.DB, *fiber.App, string, uuid.UUID) {
	t.Helper()
	db := recreateTestDB(t)
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })
	chatID, member := coverageSeed(t, db, groupEpoch)
	return db, coverageApp(member), chatID, member
}

// Test 1 - the exact Samsung regression. An epoch-1 Welcome whose KeyPackage
// private key died with an old store incarnation can never be consumed, so it
// sits unconsumed forever. A later Welcome WAS consumed, which is proof the
// device genuinely joined. The dead row must not outrank that proof.
func TestCoverageOmitsDeviceWhoseNewerWelcomeWasConsumed(t *testing.T) {
	db, app, chatID, member := coverageFixture(t, 8)
	device := "samsung-device"
	base := time.Now().Add(-72 * time.Hour)

	addWelcome(t, db, chatID, member, device, 1, false, base)
	addWelcome(t, db, chatID, member, device, 4, true, base.Add(48*time.Hour))

	body := getCoverage(t, app, chatID)
	pending := idList(t, body, "pending_device_ids")
	acked := idList(t, body, "acked_device_ids")

	if containsID(pending, device) {
		t.Fatalf("a device whose newest Welcome was consumed must not be pending; "+
			"the stale epoch-1 row is what evicted a joined phone in a loop. pending=%v", pending)
	}
	if !containsID(acked, device) {
		t.Fatalf("consuming a Welcome is the join proof and must still show as acked; acked=%v", acked)
	}
}

// Test 2 - the converse, so the fix cannot degenerate into "nobody is ever
// pending". A device with one unconsumed Welcome the group has moved past is
// exactly the stranded-leaf case Fix B exists to recover.
func TestCoverageReportsADeviceWithOnlyAPendingWelcome(t *testing.T) {
	db, app, chatID, member := coverageFixture(t, 5)
	device := "stranded-device"

	addWelcome(t, db, chatID, member, device, 1, false, time.Now().Add(-24*time.Hour))

	body := getCoverage(t, app, chatID)
	pending := idList(t, body, "pending_device_ids")
	if !containsID(pending, device) {
		t.Fatalf("a device with only a stale unconsumed Welcome must stay pending; pending=%v", pending)
	}
}

// Test 3 - the loop breaker. A Welcome created by the commit that just landed
// is an invitation still in flight: the invitee has not had a chance to poll.
// Reporting it as pending is what let an adder evict a device milliseconds
// after inviting it, then re-invite, then evict again, forever.
func TestCoverageDoesNotReportAFreshlyInvitedDevice(t *testing.T) {
	db, app, chatID, member := coverageFixture(t, 3)
	device := "just-invited-device"

	addWelcome(t, db, chatID, member, device, 3, false, time.Now())

	body := getCoverage(t, app, chatID)
	pending := idList(t, body, "pending_device_ids")
	if containsID(pending, device) {
		t.Fatalf("a Welcome at the current epoch is in flight, not evidence of a phantom; pending=%v", pending)
	}
}

// Test 4 - kills the tempting "pending minus acked" shortcut. This device DID
// consume a Welcome once, then was genuinely removed and re-invited. Its newest
// Welcome is unconsumed and the group has moved past it, so it is a real
// phantom. Set subtraction would call it joined and strand it.
func TestCoverageReportsADeviceReInvitedAfterRemoval(t *testing.T) {
	db, app, chatID, member := coverageFixture(t, 6)
	device := "re-invited-device"
	base := time.Now().Add(-48 * time.Hour)

	addWelcome(t, db, chatID, member, device, 2, true, base)
	addWelcome(t, db, chatID, member, device, 5, false, base.Add(24*time.Hour))

	body := getCoverage(t, app, chatID)
	pending := idList(t, body, "pending_device_ids")
	if !containsID(pending, device) {
		t.Fatalf("a device re-invited after removal has an outstanding invitation and must be "+
			"pending; computing pending as a set difference against acked loses this. pending=%v", pending)
	}
}

// Test 5 - kills any implementation that orders the ledger by epoch. Recreate
// resets the group epoch to zero while keeping consumed rows, so a fresh
// incarnation's epoch-1 Welcome is NEWER than a dead incarnation's epoch-4 one.
// Only created_at survives a recreation.
func TestCoverageOrdersWelcomesByCreatedAtNotEpoch(t *testing.T) {
	db, app, chatID, member := coverageFixture(t, 2)
	device := "recreated-group-device"
	base := time.Now().Add(-48 * time.Hour)

	// Old incarnation: higher epoch, consumed, but OLDER in wall-clock terms.
	addWelcome(t, db, chatID, member, device, 4, true, base)
	// New incarnation after Recreate reset the epoch: lower epoch, NEWER.
	addWelcome(t, db, chatID, member, device, 1, false, base.Add(24*time.Hour))

	body := getCoverage(t, app, chatID)
	pending := idList(t, body, "pending_device_ids")
	if !containsID(pending, device) {
		t.Fatalf("Welcome recency is created_at, never epoch: Recreate zeroes the group epoch, so "+
			"an epoch-4 row from a dead incarnation must not outrank an epoch-1 row from the live "+
			"one. pending=%v", pending)
	}
}

// Test 6 - absence of evidence. No ledger, nobody pending.
func TestCoverageWithNoWelcomesReportsNobodyPending(t *testing.T) {
	_, app, chatID, _ := coverageFixture(t, 4)

	body := getCoverage(t, app, chatID)
	pending := idList(t, body, "pending_device_ids")
	acked := idList(t, body, "acked_device_ids")
	if len(pending) != 0 {
		t.Fatalf("no Welcomes means nothing outstanding; pending=%v", pending)
	}
	if len(acked) != 0 {
		t.Fatalf("no Welcomes means nobody acked; acked=%v", acked)
	}
}
