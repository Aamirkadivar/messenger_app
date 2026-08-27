package handlers

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// GATE 4 PHASE 1 - the archive upload/download API, end to end against real
// Postgres through a real Fiber app including the device gate.
//
// Scratch database only - see archive_lifecycle_db_test.go.

type archiveAPI struct {
	app    *fiber.App
	db     *gorm.DB
	chatID string
	userA  uuid.UUID
	userB  uuid.UUID
	msg1   uuid.UUID
	msg2   uuid.UUID
	device string
	// currentUser is what the stand-in auth middleware injects; subtests swap it
	// to act as a different account without rebuilding the app.
	currentUser uuid.UUID
}

// archiveAPIEnv builds the real schema via MigrateDB, one group chat with two
// members and two messages, and a Fiber app wired exactly like main.go: a stand
// -in for AuthMiddleware, then RequireDeviceIdentity, then the handlers.
func archiveAPIEnv(t *testing.T) *archiveAPI {
	t.Helper()
	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	if err := models.MigrateDB(db); err != nil {
		t.Fatalf("MigrateDB: %v", err)
	}

	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	env := &archiveAPI{
		db:     db,
		chatID: uuid.New().String(),
		userA:  uuid.New(),
		userB:  uuid.New(),
		msg1:   uuid.New(),
		msg2:   uuid.New(),
		device: "device-a",
	}

	now := time.Now()
	for _, u := range []uuid.UUID{env.userA, env.userB} {
		if err := db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
			VALUES (?, ?, ?, 'x', ?, ?)`,
			u, "u"+u.String()[:8], u.String()[:8]+"@test.local", now, now).Error; err != nil {
			t.Fatalf("seed user: %v", err)
		}
		db.Create(&models.E2EEDevice{
			ID: uuid.New(), UserID: u, DeviceID: "device-" + u.String()[:4],
			CreatedAt: now, UpdatedAt: now,
		})
	}
	env.device = "device-" + env.userA.String()[:4]

	db.Exec(`INSERT INTO chats (id, type, created_at, updated_at) VALUES (?, 'group', ?, ?)`,
		env.chatID, now, now)
	for _, u := range []uuid.UUID{env.userA, env.userB} {
		db.Exec(`INSERT INTO chat_participants (id, chat_id, user_id, joined_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?)`, uuid.New(), env.chatID, u, now, now, now)
	}
	for i, m := range []uuid.UUID{env.msg1, env.msg2} {
		db.Exec(`INSERT INTO messages (id, sender_id, chat_id, chat_type, created_at, updated_at)
			VALUES (?, ?, ?, 'group', ?, ?)`,
			m, env.userA, env.chatID, now.Add(time.Duration(i)*time.Second), now)
	}

	env.app = fiber.New()
	env.currentUser = env.userA
	env.app.Use(func(c *fiber.Ctx) error {
		c.Locals(middleware.ContextKeyUser, &middleware.JWTClaims{UserID: env.currentUser})
		return c.Next()
	})
	env.app.Use(middleware.RequireDeviceIdentity())
	h := NewArchiveHandler()
	env.app.Post("/archives", h.UploadArchive)
	env.app.Get("/archives", h.ListArchives)
	return env
}

func (e *archiveAPI) post(t *testing.T, device string, body string) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/archives", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	if device != "" {
		req.Header.Set("X-Device-Id", device)
	}
	return e.do(t, req)
}

func (e *archiveAPI) get(t *testing.T, device, query string) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/archives"+query, nil)
	if device != "" {
		req.Header.Set("X-Device-Id", device)
	}
	return e.do(t, req)
}

func (e *archiveAPI) do(t *testing.T, req *http.Request) (int, map[string]any) {
	t.Helper()
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func uploadBody(messageID uuid.UUID, rootVersion int, ciphertext string) string {
	return fmt.Sprintf(`{"message_id":%q,"root_version":%d,"protocol_version":1,"ciphertext_b64":%q}`,
		messageID.String(), rootVersion, base64.StdEncoding.EncodeToString([]byte(ciphertext)))
}

// ---------------------------------------------------------------- upload

func TestArchiveAPI_UploadAndIdempotency(t *testing.T) {
	e := archiveAPIEnv(t)

	t.Run("successful upload", func(t *testing.T) {
		code, body := e.post(t, e.device, uploadBody(e.msg1, 1, "sealed-one"))
		if code != http.StatusOK {
			t.Fatalf("status %d: %v", code, body)
		}
		if body["stored"] != true {
			t.Fatalf("first upload must store: %v", body)
		}
		if body["chat_id"] != e.chatID {
			t.Fatalf("chat_id must be derived from the message row: %v", body["chat_id"])
		}
	})

	t.Run("duplicate upload is idempotent and does not replace", func(t *testing.T) {
		code, body := e.post(t, e.device, uploadBody(e.msg1, 99, "attacker-supplied"))
		if code != http.StatusOK {
			t.Fatalf("status %d: %v", code, body)
		}
		if body["stored"] != false {
			t.Fatalf("duplicate must report stored=false, got %v", body)
		}

		var rootVersion int
		var ct []byte
		e.db.Raw(`SELECT root_version, ciphertext FROM message_archives
		          WHERE message_id = ? AND user_id = ?`, e.msg1, e.userA).Row().
			Scan(&rootVersion, &ct)
		if rootVersion != 1 || string(ct) != "sealed-one" {
			t.Fatalf("original archive was replaced: v%d %q", rootVersion, ct)
		}

		var n int64
		e.db.Raw(`SELECT COUNT(*) FROM message_archives`).Scan(&n)
		if n != 1 {
			t.Fatalf("want exactly 1 row, got %d", n)
		}
	})
}

func TestArchiveAPI_UploadValidation(t *testing.T) {
	e := archiveAPIEnv(t)

	cases := []struct {
		name string
		body string
		want int
	}{
		{"malformed json", `{`, http.StatusBadRequest},
		{"invalid message_id", `{"message_id":"not-a-uuid","root_version":1,"ciphertext_b64":"YQ=="}`, http.StatusBadRequest},
		{"root_version zero", uploadBodyRaw(e.msg1, 0, 1, "YQ=="), http.StatusBadRequest},
		{"root_version negative", uploadBodyRaw(e.msg1, -1, 1, "YQ=="), http.StatusBadRequest},
		{"protocol_version negative", uploadBodyRaw(e.msg1, 1, -3, "YQ=="), http.StatusBadRequest},
		{"malformed base64", uploadBodyRaw(e.msg1, 1, 1, "!!!not base64!!!"), http.StatusBadRequest},
		{"empty ciphertext", uploadBodyRaw(e.msg1, 1, 1, ""), http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			code, _ := e.post(t, e.device, tc.body)
			if code != tc.want {
				t.Fatalf("status %d, want %d", code, tc.want)
			}
		})
	}

	// Nothing may have been written by any rejected request.
	var n int64
	e.db.Raw(`SELECT COUNT(*) FROM message_archives`).Scan(&n)
	if n != 0 {
		t.Fatalf("rejected uploads must write nothing, got %d rows", n)
	}
}

func uploadBodyRaw(messageID uuid.UUID, root, proto int, ctB64 string) string {
	return fmt.Sprintf(`{"message_id":%q,"root_version":%d,"protocol_version":%d,"ciphertext_b64":%q}`,
		messageID.String(), root, proto, ctB64)
}

// ---------------------------------------------------------------- authorization

func TestArchiveAPI_Authorization(t *testing.T) {
	e := archiveAPIEnv(t)

	t.Run("nonexistent message is rejected", func(t *testing.T) {
		code, _ := e.post(t, e.device, uploadBody(uuid.New(), 1, "x"))
		if code != http.StatusNotFound {
			t.Fatalf("status %d, want 404", code)
		}
	})

	t.Run("non-member gets the SAME response as nonexistent - no oracle", func(t *testing.T) {
		outsider := uuid.New()
		now := time.Now()
		e.db.Exec(`INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
			VALUES (?, 'outsider', 'outsider@test.local', 'x', ?, ?)`, outsider, now, now)
		e.db.Create(&models.E2EEDevice{
			ID: uuid.New(), UserID: outsider, DeviceID: "device-outsider",
			CreatedAt: now, UpdatedAt: now,
		})

		prev := e.currentUser
		e.currentUser = outsider
		defer func() { e.currentUser = prev }()

		realCode, realBody := e.post(t, "device-outsider", uploadBody(e.msg1, 1, "x"))
		fakeCode, fakeBody := e.post(t, "device-outsider", uploadBody(uuid.New(), 1, "x"))

		if realCode != http.StatusNotFound || fakeCode != http.StatusNotFound {
			t.Fatalf("both must be 404: real=%d fake=%d", realCode, fakeCode)
		}
		if fmt.Sprint(realBody) != fmt.Sprint(fakeBody) {
			t.Fatalf("responses must be indistinguishable:\n real=%v\n fake=%v", realBody, fakeBody)
		}

		var n int64
		e.db.Raw(`SELECT COUNT(*) FROM message_archives WHERE user_id = ?`, outsider).Scan(&n)
		if n != 0 {
			t.Fatalf("non-member wrote %d archives", n)
		}
	})

	t.Run("departed member is rejected", func(t *testing.T) {
		e.db.Exec(`UPDATE chat_participants SET left_at = ? WHERE chat_id = ? AND user_id = ?`,
			time.Now(), e.chatID, e.userB)
		prev := e.currentUser
		e.currentUser = e.userB
		defer func() { e.currentUser = prev }()

		code, _ := e.post(t, "device-"+e.userB.String()[:4], uploadBody(e.msg1, 1, "x"))
		if code != http.StatusNotFound {
			t.Fatalf("departed member status %d, want 404", code)
		}
		// Restore for later subtests.
		e.db.Exec(`UPDATE chat_participants SET left_at = NULL WHERE chat_id = ? AND user_id = ?`,
			e.chatID, e.userB)
	})
}

func TestArchiveAPI_DeviceIdentityRequired(t *testing.T) {
	e := archiveAPIEnv(t)
	now := time.Now()
	e.db.Create(&models.E2EEDevice{
		ID: uuid.New(), UserID: e.userA, DeviceID: "device-revoked",
		RevokedAt: &now, CreatedAt: now, UpdatedAt: now,
	})

	cases := []struct {
		name, device string
		want         int
	}{
		{"missing device id", "", http.StatusBadRequest},
		{"unknown device id", "device-never-seen", http.StatusForbidden},
		{"revoked device id", "device-revoked", http.StatusForbidden},
	}
	for _, tc := range cases {
		t.Run("upload "+tc.name, func(t *testing.T) {
			if code, _ := e.post(t, tc.device, uploadBody(e.msg1, 1, "x")); code != tc.want {
				t.Fatalf("status %d, want %d", code, tc.want)
			}
		})
		t.Run("download "+tc.name, func(t *testing.T) {
			if code, _ := e.get(t, tc.device, ""); code != tc.want {
				t.Fatalf("status %d, want %d", code, tc.want)
			}
		})
	}
}

// ---------------------------------------------------------------- download

func TestArchiveAPI_Download(t *testing.T) {
	e := archiveAPIEnv(t)

	if code, _ := e.post(t, e.device, uploadBody(e.msg1, 1, "sealed-one")); code != http.StatusOK {
		t.Fatalf("seed upload 1: %d", code)
	}
	if code, _ := e.post(t, e.device, uploadBody(e.msg2, 2, "sealed-two")); code != http.StatusOK {
		t.Fatalf("seed upload 2: %d", code)
	}

	t.Run("owner downloads both archives", func(t *testing.T) {
		code, body := e.get(t, e.device, "")
		if code != http.StatusOK {
			t.Fatalf("status %d", code)
		}
		archives, _ := body["archives"].([]any)
		if len(archives) != 2 {
			t.Fatalf("want 2 archives, got %d", len(archives))
		}
		first, _ := archives[0].(map[string]any)
		for _, k := range []string{"message_id", "chat_id", "root_version", "protocol_version",
			"ciphertext_b64", "created_at"} {
			if _, ok := first[k]; !ok {
				t.Fatalf("response missing %q: %v", k, first)
			}
		}
		got, err := base64.StdEncoding.DecodeString(first["ciphertext_b64"].(string))
		if err != nil {
			t.Fatalf("ciphertext_b64 must be valid base64: %v", err)
		}
		if string(got) != "sealed-one" {
			t.Fatalf("ciphertext round-trip failed: %q", got)
		}
	})

	t.Run("response carries no root, key or plaintext material", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/archives", nil)
		req.Header.Set("X-Device-Id", e.device)
		resp, _ := e.app.Test(req, -1)
		raw, _ := io.ReadAll(resp.Body)
		for _, forbidden := range []string{"root\"", "history_root", "keyring", "master_key",
			"plaintext", "session_mk", "snapshot", "sender_key"} {
			if strings.Contains(string(raw), forbidden) {
				t.Fatalf("response leaked %q: %s", forbidden, raw)
			}
		}
	})

	t.Run("cross-user isolation", func(t *testing.T) {
		prev := e.currentUser
		e.currentUser = e.userB
		defer func() { e.currentUser = prev }()

		code, body := e.get(t, "device-"+e.userB.String()[:4], "")
		if code != http.StatusOK {
			t.Fatalf("status %d", code)
		}
		archives, _ := body["archives"].([]any)
		if len(archives) != 0 {
			t.Fatalf("userB must see none of userA's archives, got %d", len(archives))
		}
	})

	t.Run("chat filter returns that chat", func(t *testing.T) {
		code, body := e.get(t, e.device, "?chat_id="+e.chatID)
		if code != http.StatusOK {
			t.Fatalf("status %d", code)
		}
		archives, _ := body["archives"].([]any)
		if len(archives) != 2 {
			t.Fatalf("want 2, got %d", len(archives))
		}
	})

	t.Run("chat filter for a chat the caller is not in is rejected", func(t *testing.T) {
		code, _ := e.get(t, e.device, "?chat_id="+uuid.New().String())
		if code != http.StatusNotFound {
			t.Fatalf("status %d, want 404", code)
		}
	})

	t.Run("since filter is an exclusive cursor", func(t *testing.T) {
		_, all := e.get(t, e.device, "")
		archives, _ := all["archives"].([]any)
		firstRow, _ := archives[0].(map[string]any)
		cursor, _ := firstRow["created_at"].(string)

		code, body := e.get(t, e.device, "?since="+url.QueryEscape(cursor))
		if code != http.StatusOK {
			t.Fatalf("status %d", code)
		}
		rest, _ := body["archives"].([]any)
		if len(rest) != 1 {
			t.Fatalf("since must exclude the cursor row: got %d", len(rest))
		}
	})

	t.Run("malformed since is rejected", func(t *testing.T) {
		if code, _ := e.get(t, e.device, "?since=yesterday"); code != http.StatusBadRequest {
			t.Fatalf("status %d, want 400", code)
		}
	})
}

// ---------------------------------------------------------------- lifetime still holds

func TestArchiveAPI_LifetimeInvariantsThroughTheAPI(t *testing.T) {
	e := archiveAPIEnv(t)
	e.post(t, e.device, uploadBody(e.msg1, 1, "sealed-one"))
	e.post(t, e.device, uploadBody(e.msg2, 1, "sealed-two"))

	count := func() int64 {
		var n int64
		e.db.Raw(`SELECT COUNT(*) FROM message_archives WHERE user_id = ?`, e.userA).Scan(&n)
		return n
	}
	if count() != 2 {
		t.Fatalf("setup: want 2, got %d", count())
	}

	// delete-for-me must NOT remove the archive.
	e.db.Exec(`INSERT INTO message_deletions (message_id, user_id, created_at)
		VALUES (?, ?, ?) ON CONFLICT DO NOTHING`, e.msg1, e.userA, time.Now())
	if count() != 2 {
		t.Fatalf("delete-for-me must not remove archives, got %d", count())
	}

	// delete-for-everyone cascades.
	e.db.Exec(`DELETE FROM messages WHERE id = ?`, e.msg1)
	if count() != 1 {
		t.Fatalf("message deletion must cascade, got %d", count())
	}

	// participant departure purges the leaver.
	if err := e.db.Transaction(func(tx *gorm.DB) error {
		return models.PurgeArchivesForParticipant(tx, e.chatID, e.userA)
	}); err != nil {
		t.Fatalf("purge: %v", err)
	}
	if count() != 0 {
		t.Fatalf("departure must purge the leaver's archives, got %d", count())
	}
}
