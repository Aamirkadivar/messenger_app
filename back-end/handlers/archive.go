package handlers

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// ArchiveHandler serves the encrypted history archive API.
//
// It never decrypts anything. The server holds no history root, no keyring, no
// master key and no MLS material, so every byte in this table is opaque to it.
// There is deliberately no hub: archives publish no events, and no archive
// deletion endpoint exists - archives are removed only by the Phase 0 lifetime
// rules (message, chat, or user deletion cascades; participant departure purge).
type ArchiveHandler struct{}

func NewArchiveHandler() *ArchiveHandler { return &ArchiveHandler{} }

// maxArchiveListed caps one page of a download. `since` is the pagination
// cursor: rows come back oldest-first, so a client walks forward by passing the
// created_at of the last row it received.
const maxArchiveListed = 500

// maxArchiveCiphertext bounds a single archive. A text message sealed with
// XChaCha20-Poly1305 is its plaintext plus 40 bytes; this leaves generous room
// while keeping a single row far below Fiber's 25MB body limit.
const maxArchiveCiphertext = 1 << 20 // 1 MiB

type archiveUploadBody struct {
	MessageID       string `json:"message_id"`
	RootVersion     int    `json:"root_version"`
	ProtocolVersion int    `json:"protocol_version"`
	CiphertextB64   string `json:"ciphertext_b64"`
}

// notFound is the single response for "no such message" AND "you may not touch
// that message".
//
// Distinguishing them would turn this endpoint into an existence oracle for
// message ids belonging to chats the caller is not in. Message ids are UUIDs
// that appear in every participant's message list, so an attacker holding one
// must not be able to learn whether it is real.
func archiveNotFound(c *fiber.Ctx) error {
	return c.Status(http.StatusNotFound).JSON(fiber.Map{
		"error":   "not found",
		"message": "No such message, or it is not available to you",
	})
}

// UploadArchive POST /e2ee/archives
//
// Immutable by construction: identity is (message_id, user_id) and the insert is
// ON CONFLICT DO NOTHING, so a retry is a no-op and a replayed or attacker-
// supplied ciphertext can never overwrite the original. Both outcomes return
// 200 with `stored` telling the client which happened.
func (h *ArchiveHandler) UploadArchive(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	var body archiveUploadBody
	if err := c.BodyParser(&body); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid body"})
	}

	messageID, err := uuid.Parse(body.MessageID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid message_id"})
	}
	if body.RootVersion < 1 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "root_version must be >= 1"})
	}
	if body.ProtocolVersion == 0 {
		body.ProtocolVersion = 1
	}
	if body.ProtocolVersion < 1 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "protocol_version must be >= 1"})
	}
	ct, err := unb64(body.CiphertextB64)
	if err != nil || len(ct) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid ciphertext_b64"})
	}
	if len(ct) > maxArchiveCiphertext {
		return c.Status(http.StatusRequestEntityTooLarge).JSON(fiber.Map{
			"error": "ciphertext too large",
		})
	}

	// The chat comes from the authoritative message row, never from the client.
	// Membership is checked against THAT chat, so an archive cannot be bound to
	// a conversation the message does not belong to.
	scope, err := AuthorizeArchiveAccess(database.DB, userID, messageID)
	if err != nil {
		if errors.Is(err, ErrArchiveNoSuchMessage) || errors.Is(err, ErrArchiveNotMember) {
			return archiveNotFound(c)
		}
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store archive",
		})
	}

	res := database.DB.Exec(
		`INSERT INTO message_archives
		   (message_id, user_id, chat_id, root_version, protocol_version, ciphertext, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (message_id, user_id) DO NOTHING`,
		scope.MessageID, scope.UserID, scope.ChatID,
		body.RootVersion, body.ProtocolVersion, ct, time.Now(),
	)
	if res.Error != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store archive",
		})
	}

	return c.JSON(fiber.Map{
		"message_id": scope.MessageID.String(),
		"chat_id":    scope.ChatID,
		// false means an archive already existed and was left untouched.
		"stored": res.RowsAffected == 1,
	})
}

// ListArchives GET /e2ee/archives?chat_id=&since=
//
// Returns only the caller's own archives. `user_id = current user` is applied
// unconditionally, so cross-user access is impossible even if a filter is
// wrong. `since` is RFC3339 and is exclusive, making it usable as a cursor.
func (h *ArchiveHandler) ListArchives(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	q := database.DB.Model(&models.MessageArchive{}).Where("user_id = ?", userID)

	if chatID := c.Query("chat_id"); chatID != "" {
		if _, err := uuid.Parse(chatID); err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid chat_id"})
		}
		// Defence in depth. The user_id filter already prevents cross-user
		// reads, and a departed member's archives were purged on departure -
		// but membership is verified anyway, so a chat the caller is not in
		// cannot be probed. Same generic 404 as upload: no existence oracle.
		var participant models.ChatParticipant
		if err := database.DB.Where("chat_id = ? AND user_id = ? AND left_at IS NULL",
			chatID, userID).First(&participant).Error; err != nil {
			return archiveNotFound(c)
		}
		q = q.Where("chat_id = ?", chatID)
	}

	if since := c.Query("since"); since != "" {
		// A '+' in an RFC3339 zone offset decodes to a space unless the client
		// percent-encodes it. Retrofit and most HTTP clients do; curl and hand
		// -written callers routinely do not, and the resulting 400 is baffling.
		// Restoring it costs nothing and cannot change the meaning of a
		// well-formed timestamp, which never contains a space.
		since = strings.Replace(since, " ", "+", 1)
		ts, err := time.Parse(time.RFC3339, since)
		if err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error": "invalid since; expected RFC3339",
			})
		}

		// KEYSET PAGINATION. created_at is not unique, so a cursor on it alone
		// is unsafe: rows sharing a timestamp have no defined order, and any of
		// them that fall past a page boundary are skipped forever by
		// `created_at > cursor`. The tie-break is message_id, which together
		// with created_at is unique (message_id is half the primary key and a
		// given user has at most one archive per message).
		//
		// since_id is optional so an older client sending only `since` keeps the
		// previous behaviour. That is unambiguous: with no tie-break supplied
		// there is nothing to compare against, and the two forms cannot be
		// confused for one another.
		if sinceID := c.Query("since_id"); sinceID != "" {
			parsed, err := uuid.Parse(sinceID)
			if err != nil {
				return c.Status(http.StatusBadRequest).JSON(fiber.Map{
					"error": "invalid since_id",
				})
			}
			q = q.Where("(created_at, message_id) > (?, ?)", ts, parsed)
		} else {
			q = q.Where("created_at > ?", ts)
		}
	}

	var rows []models.MessageArchive
	// Ordering must match the cursor comparison exactly, or pagination walks a
	// different sequence than it compares against.
	if err := q.Order("created_at ASC, message_id ASC").Limit(maxArchiveListed).Find(&rows).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not load archives",
		})
	}

	out := make([]fiber.Map, 0, len(rows))
	for _, r := range rows {
		out = append(out, fiber.Map{
			"message_id":       r.MessageID.String(),
			"chat_id":          r.ChatID,
			"root_version":     r.RootVersion,
			"protocol_version": r.ProtocolVersion,
			"ciphertext_b64":   b64(r.Ciphertext),
			"created_at":       r.CreatedAt,
		})
	}
	return c.JSON(fiber.Map{"archives": out, "count": len(out)})
}
