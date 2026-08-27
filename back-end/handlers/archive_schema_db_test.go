package handlers

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// GATE 4 PHASE 0 - the schema the production migration actually produces, and
// the device gate that gnards archive routes.
//
// The lifetime tests hand-write their fixture schema; this one runs the real
// models.MigrateDB and reads the constraints back out of the catalog, so a
// mismatch between the declared design and what AutoMigrate + the fixups emit
// cannot pass unnoticed.
//
// Scratch database only - see archive_lifecycle_db_test.go.

// resetScratchSchema gives MigrateDB a genuinely empty database. The lifetime
// tests hand-build minimal tables of the same names, and AutoMigrate cannot
// widen those into the real schema (adding a NOT NULL column to a table that
// already has rows fails). Scratch database only - this drops everything.
func resetScratchSchema(t *testing.T, db *gorm.DB) {
	t.Helper()
	if err := db.Exec(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`).Error; err != nil {
		t.Fatalf("reset scratch schema: %v", err)
	}
}

func TestArchive_MigrationProducesCascadingConstraints(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)

	if err := models.MigrateDB(db); err != nil {
		t.Fatalf("MigrateDB: %v", err)
	}

	type fkRow struct {
		Name       string
		DeleteRule string
		Column     string
		RefTable   string
	}
	var rows []fkRow
	err := db.Raw(`
		SELECT tc.constraint_name AS name,
		       rc.delete_rule     AS delete_rule,
		       kcu.column_name    AS column,
		       ccu.table_name     AS ref_table
		FROM information_schema.table_constraints tc
		JOIN information_schema.referential_constraints rc
		  ON rc.constraint_name = tc.constraint_name
		JOIN information_schema.key_column_usage kcu
		  ON kcu.constraint_name = tc.constraint_name
		JOIN information_schema.constraint_column_usage ccu
		  ON ccu.constraint_name = tc.constraint_name
		WHERE tc.table_name = 'message_archives'
		  AND tc.constraint_type = 'FOREIGN KEY'
		ORDER BY tc.constraint_name`).Scan(&rows).Error
	if err != nil {
		t.Fatalf("read constraints: %v", err)
	}

	want := map[string]string{
		"fk_message_archives_message": "messages",
		"fk_message_archives_chat":    "chats",
		"fk_message_archives_user":    "users",
	}
	if len(rows) != len(want) {
		t.Fatalf("want %d cascading FKs, got %d: %+v", len(want), len(rows), rows)
	}
	for _, r := range rows {
		refWant, ok := want[r.Name]
		if !ok {
			t.Fatalf("unexpected constraint %q", r.Name)
		}
		if r.RefTable != refWant {
			t.Errorf("%s references %s, want %s", r.Name, r.RefTable, refWant)
		}
		if r.DeleteRule != "CASCADE" {
			t.Errorf("%s delete rule is %q, want CASCADE - an archive must never "+
				"outlive its message, chat or owner", r.Name, r.DeleteRule)
		}
	}

	// Composite primary key is what makes an archive immutable.
	var pkCols []string
	db.Raw(`
		SELECT kcu.column_name
		FROM information_schema.table_constraints tc
		JOIN information_schema.key_column_usage kcu
		  ON kcu.constraint_name = tc.constraint_name
		WHERE tc.table_name = 'message_archives'
		  AND tc.constraint_type = 'PRIMARY KEY'
		ORDER BY kcu.column_name`).Scan(&pkCols)
	if len(pkCols) != 2 || pkCols[0] != "message_id" || pkCols[1] != "user_id" {
		t.Fatalf("primary key must be (message_id, user_id), got %v", pkCols)
	}

	// And the server must not have grown a column for anything secret.
	var cols []string
	db.Raw(`SELECT column_name FROM information_schema.columns
	        WHERE table_name = 'message_archives' ORDER BY column_name`).Scan(&cols)
	forbidden := []string{"root", "history_root", "keyring", "plaintext", "master_key", "device_secret"}
	for _, c := range cols {
		for _, f := range forbidden {
			if c == f {
				t.Fatalf("message_archives must never hold %q", c)
			}
		}
	}
}

// ---------------------------------------------------------------- device gate

func deviceGateApp(t *testing.T, db *gorm.DB, userID uuid.UUID) *fiber.App {
	t.Helper()
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	app := fiber.New()
	// Stand in for AuthMiddleware: the gate is being tested, not JWT parsing.
	app.Use(func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: userID})
		return c.Next()
	})
	app.Use(middleware.RequireDeviceIdentity())
	app.Get("/archives", func(c *fiber.Ctx) error { return c.SendString("ok") })
	return app
}

func TestArchive_DeviceIdentityGate(t *testing.T) {
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	if err := models.MigrateDB(db); err != nil {
		t.Fatalf("MigrateDB: %v", err)
	}

	userID := uuid.New()
	db.Exec(`INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING`, userID)

	now := time.Now()
	live := "device-live"
	revoked := "device-revoked"
	db.Exec(`DELETE FROM e2ee_devices WHERE user_id = ?`, userID)
	db.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: userID, DeviceID: live, CreatedAt: now, UpdatedAt: now,
	})
	db.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: userID, DeviceID: revoked, RevokedAt: &now,
		CreatedAt: now, UpdatedAt: now,
	})

	app := deviceGateApp(t, db, userID)

	cases := []struct {
		name     string
		deviceID string
		want     int
	}{
		{"missing X-Device-Id is refused", "", http.StatusBadRequest},
		{"unknown device is refused, never auto-registered", "device-never-seen", http.StatusForbidden},
		{"revoked device is refused", revoked, http.StatusForbidden},
		{"registered live device passes", live, http.StatusOK},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/archives", nil)
			if tc.deviceID != "" {
				req.Header.Set("X-Device-Id", tc.deviceID)
			}
			resp, err := app.Test(req, -1)
			if err != nil {
				t.Fatalf("request: %v", err)
			}
			if resp.StatusCode != tc.want {
				t.Fatalf("status %d, want %d", resp.StatusCode, tc.want)
			}
		})
	}

	// The unknown device must NOT have been created as a side effect - that is
	// the specific difference from the permissive global DeviceRevocationGuard.
	var n int64
	db.Raw(`SELECT COUNT(*) FROM e2ee_devices WHERE user_id = ? AND device_id = ?`,
		userID, "device-never-seen").Scan(&n)
	if n != 0 {
		t.Fatalf("archive gate must never auto-register a device, found %d", n)
	}
}
