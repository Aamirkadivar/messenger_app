package handlers

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"
)

// MLSHandler implements the RFC 9420 Delivery Service.
//
// The DS is UNTRUSTED by design: it stores and relays opaque bytes. It never
// parses a KeyPackage, never runs TreeKEM, never derives group secrets, and
// cannot read application messages. Its security duties are authorization
// (only members touch a group; identity always comes from the session token,
// never the body) and ordering (one accepted commit per epoch).
type MLSHandler struct {
	hub *websocket.Hub
}

func NewMLSHandler(hub *websocket.Hub) *MLSHandler { return &MLSHandler{hub: hub} }

// isChatParticipant reports whether userID is an active participant of chatID.
// Every group-scoped endpoint goes through this — it is the IDOR guard.
func isChatParticipant(chatID string, userID uuid.UUID) bool {
	var count int64
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).
		Count(&count)
	return count > 0
}

// ---- KeyPackages ----

type keyPackageItem struct {
	DeviceID      string `json:"device_id"`
	CipherSuite   int    `json:"cipher_suite"`
	KeyPackageB64 string `json:"key_package_b64"`
	RefHash       string `json:"ref_hash"`
	// StoreID names the publisher's current MLS store incarnation. Opaque:
	// stored and compared, never parsed. Empty from a pre-store_id client, and
	// recorded as NULL so those rows stay distinguishable from any real one.
	StoreID string `json:"store_id"`
}

// storePtr normalizes a client-supplied store tag: empty means "not declared",
// which must persist as NULL rather than as the empty string, or every legacy
// row would collide into one indistinguishable pseudo-incarnation.
func storePtr(v string) *string {
	if v == "" {
		return nil
	}
	return &v
}

type publishKeyPackagesBody struct {
	KeyPackages []keyPackageItem `json:"key_packages"`
}

// PublishKeyPackages POST /e2ee/mls/keypackages
// Uploads a batch of single-use KeyPackages for the authenticated user.
func (h *MLSHandler) PublishKeyPackages(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body publishKeyPackagesBody
	if err := c.BodyParser(&body); err != nil || len(body.KeyPackages) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "key_packages required"})
	}
	if len(body.KeyPackages) > 200 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "too many key packages (max 200)"})
	}

	stored := 0
	for _, item := range body.KeyPackages {
		data, err := unb64(item.KeyPackageB64)
		if err != nil || len(data) == 0 || item.RefHash == "" {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid key package entry"})
		}
		kp := models.MLSKeyPackage{
			ID:             uuid.New(),
			UserID:         userID, // from the token, never the body
			DeviceID:       item.DeviceID,
			CipherSuite:    item.CipherSuite,
			KeyPackageData: data,
			RefHash:        item.RefHash,
			StoreID:        storePtr(item.StoreID),
			CreatedAt:      time.Now(),
		}
		// Re-publishing the same package is a no-op rather than an error, so a
		// client retrying after a dropped response does not fail.
		res := database.DB.Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "ref_hash"}}, DoNothing: true}).Create(&kp)
		if res.Error != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to store key packages"})
		}
		stored += int(res.RowsAffected)
	}
	return c.Status(http.StatusCreated).JSON(fiber.Map{"stored": stored})
}

// CountKeyPackages GET /e2ee/mls/keypackages/count — how many of THIS DEVICE's
// remain unclaimed, so a client knows when to top up.
//
// Scoped by device, not just user: a KeyPackage commits to one device's init
// key, and ClaimKeyPackage filters by device_id. Counting per-user let a newly
// registered device see its siblings' stock, conclude it was topped up, and
// never publish any of its own — so every attempt to add it to a group failed
// with "no key package available" and it could never join. That is why a second
// device on the same account could not read group messages.
// Scoped by store incarnation too, when the client declares one. A device that
// rebuilt its MLS store still owns its old rows on paper, but has no private
// init key for any of them; counting them would report a healthy stock and
// suppress the republish the device actually needs. Omitting store_id keeps the
// pre-store_id behaviour so older clients are unaffected.
func (h *MLSHandler) CountKeyPackages(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	deviceID := c.Get("X-Device-Id")
	storeID := c.Query("store_id")
	q := database.DB.Model(&models.MLSKeyPackage{}).
		Where("user_id = ? AND claimed_at IS NULL", userID)
	if deviceID != "" {
		q = q.Where("device_id = ?", deviceID)
	}
	if storeID != "" {
		q = q.Where("store_id = ?", storeID)
	}
	var n int64
	q.Count(&n)
	return c.JSON(fiber.Map{"available": n})
}

// currentStoreID reports the store incarnation a device is publishing under
// right now: the tag on its most recently created package, claimed or not.
//
// Derived rather than tracked separately so there is exactly one source of
// truth. Claimed rows count, because a store whose whole stock has been
// consumed is still the current one - ignoring them would fall back to an
// abandoned incarnation the moment the live one ran dry.
//
// A nil result means the device has only unattributed legacy rows, which is
// every device until it republishes under the new client.
func currentStoreID(tx *gorm.DB, userID uuid.UUID, deviceID string) (*string, error) {
	var newest models.MLSKeyPackage
	err := tx.Where("user_id = ? AND device_id = ?", userID, deviceID).
		Order("created_at desc, id desc").
		First(&newest).Error
	if err != nil {
		return nil, err
	}
	return newest.StoreID, nil
}

type claimBody struct {
	UserID      string `json:"user_id"`
	DeviceID    string `json:"device_id"`
	CipherSuite int    `json:"cipher_suite"`
}

// ClaimKeyPackage POST /e2ee/mls/keypackages/claim
// Atomically consumes one unclaimed KeyPackage belonging to the requested
// user/device so it can be added to a group.
func (h *MLSHandler) ClaimKeyPackage(c *fiber.Ctx) error {
	claimer := middleware.GetCurrentUserID(c)
	var body claimBody
	if err := c.BodyParser(&body); err != nil || body.UserID == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "user_id required"})
	}
	targetID, err := uuid.Parse(body.UserID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid user_id"})
	}

	var claimed models.MLSKeyPackage
	// Single transaction with a row lock: two concurrent adders must never get
	// the same package (that would reuse an init key).
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		q := tx.Clauses(clause.Locking{Strength: "UPDATE", Options: "SKIP LOCKED"}).
			Where("user_id = ? AND claimed_at IS NULL", targetID)
		if body.DeviceID != "" {
			q = q.Where("device_id = ?", body.DeviceID)
			// Hand out only packages the target device can still open. The
			// claimer cannot know the target's store incarnation, so the server
			// resolves it - by equality on an opaque tag, not by reading MLS.
			//
			// NULL is not a wildcard: a device that has republished under a real
			// incarnation must never be served its unattributed legacy rows,
			// whose private init keys died with the store that made them.
			store, err := currentStoreID(tx, targetID, body.DeviceID)
			if err != nil {
				return err
			}
			if store != nil {
				q = q.Where("store_id = ?", *store)
			} else {
				q = q.Where("store_id IS NULL")
			}
		}
		if body.CipherSuite != 0 {
			q = q.Where("cipher_suite = ?", body.CipherSuite)
		}
		// FIFO within the incarnation: oldest first is the right consumption
		// order for single-use prekeys, and was never what caused stale packages
		// to be served.
		//
		// created_at is the FIFO key and the only one that carries real order:
		// the id is a random v4 UUID, so ordering by it would consume packages
		// in an order unrelated to when they were published. A whole publish
		// batch shares one timestamp - the wall clock is coarser than the loop
		// that writes it - so within a batch created_at ties, and the ties are
		// harmless: every row in a batch is an equally valid single-use prekey
		// from the same incarnation. What FIFO guarantees is oldest-BATCH-first.
		//
		// id asc is spelled out only to settle those ties deterministically.
		// GORM's First() already appends the primary key to whatever ORDER BY
		// it is given, so this changes no SQL - it states the invariant in the
		// source instead of leaving it to an implicit driver behaviour that a
		// switch to Take/Find would silently drop.
		if err := q.Order("created_at asc, id asc").First(&claimed).Error; err != nil {
			return err
		}
		now := time.Now()
		claimed.ClaimedAt = &now
		claimed.ClaimedBy = &claimer
		return tx.Save(&claimed).Error
	})
	if txErr != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "no key package available for that user/device",
		})
	}
	return c.JSON(fiber.Map{
		"key_package_b64": b64(claimed.KeyPackageData),
		"user_id":         claimed.UserID.String(),
		"device_id":       claimed.DeviceID,
		"cipher_suite":    claimed.CipherSuite,
	})
}

// ---- Groups ----

type createGroupBody struct {
	ChatID      string `json:"chat_id"`
	GroupIDB64  string `json:"group_id_b64"`
	CipherSuite int    `json:"cipher_suite"`
}

// CreateGroup POST /e2ee/mls/groups — registers an MLS group for a chat.
func (h *MLSHandler) CreateGroup(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body createGroupBody
	if err := c.BodyParser(&body); err != nil || body.ChatID == "" || body.GroupIDB64 == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "chat_id and group_id_b64 required"})
	}
	if !isChatParticipant(body.ChatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	gid, err := unb64(body.GroupIDB64)
	if err != nil || len(gid) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid group_id_b64"})
	}

	var existing models.MLSGroup
	if err := database.DB.Where("chat_id = ?", body.ChatID).First(&existing).Error; err == nil {
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error": "mls group already exists for this chat",
			"epoch": existing.Epoch,
		})
	}
	now := time.Now()
	g := models.MLSGroup{
		ID:          uuid.New(),
		ChatID:      body.ChatID,
		GroupIDData: gid,
		CipherSuite: body.CipherSuite,
		Epoch:       0,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}
	if err := database.DB.Create(&g).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "failed to create group"})
	}
	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"chat_id": g.ChatID, "epoch": g.Epoch, "cipher_suite": g.CipherSuite,
		"instance_id": g.ID,
	})
}

type recreateGroupBody struct {
	// GroupIDB64 is the new incarnation's opaque MLS group id. It must differ
	// from the one being replaced, so a stale client cannot "recreate" onto the
	// very tree it is already broken against.
	GroupIDB64  string `json:"group_id_b64"`
	CipherSuite int    `json:"cipher_suite"`
	// ExpectedInstanceID is the incarnation the caller observed. Recreation only
	// proceeds while it is still current - a compare-and-swap. Two devices racing
	// therefore produce exactly ONE new incarnation: the loser is told which one
	// won and adopts it instead of starting a third tree.
	ExpectedInstanceID string `json:"expected_instance_id"`
}

// RecreateGroup POST /e2ee/mls/groups/:chat_id/recreate
//
// Replaces a chat's MLS group with a fresh incarnation, keeping the same
// chat_id. This exists because a device that has lost its local MLS state cannot
// rejoin the existing tree: remove_members is not reachable from the clients and
// external-commit rejoin is not implemented. The only route back to a working
// group is to abandon the old tree and build one every current device is
// Welcomed into.
//
// Deliberately NOT touched:
//   - `messages`. Old v6 rows stay byte-for-byte as they are. They become
//     undecryptable because their tree is gone - accepted for this phase - but
//     nothing rewrites or deletes them.
//   - consumed Welcomes. They are an audit trail and can never be re-delivered
//     anyway, since GetWelcomes filters on consumed_at IS NULL.
//
// Cleaned up, because leaving it behind is actively harmful:
//   - handshakes for the chat: commits against the abandoned tree. A client
//     syncing the new group from epoch 0 would try to apply them and fail.
//   - unconsumed Welcomes: they admit a device into a tree that no longer exists.
//
// Authorization is identical to every other group operation - chat participants
// only, identity from the session token, never from the body.
func (h *MLSHandler) RecreateGroup(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}

	var body recreateGroupBody
	if err := c.BodyParser(&body); err != nil || body.GroupIDB64 == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "group_id_b64 required"})
	}
	gid, err := unb64(body.GroupIDB64)
	if err != nil || len(gid) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid group_id_b64"})
	}

	var newInstance uuid.UUID
	var conflict *models.MLSGroup
	var badRequest string

	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		var g models.MLSGroup
		// The row lock serializes concurrent attempts; the CAS below decides
		// which one may proceed.
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("chat_id = ?", chatID).First(&g).Error; err != nil {
			return err
		}

		if body.ExpectedInstanceID != "" && body.ExpectedInstanceID != g.ID.String() {
			existing := g
			conflict = &existing
			return nil
		}
		if len(g.GroupIDData) > 0 && string(g.GroupIDData) == string(gid) {
			badRequest = "new group_id must differ from the current one"
			return nil
		}

		// Replace in place, keyed on the row's ORIGINAL primary key.
		//
		// Not Save(): GORM upserts on the primary key, so assigning a fresh id
		// first made it emit INSERT ... ON CONFLICT ("id"). The conflict clause
		// never fires for a brand-new id, so Postgres attempted a real insert and
		// hit UNIQUE(chat_id) against the row still sitting there. An explicit
		// UPDATE ... WHERE id = <original> mutates the row that exists, which is
		// what "recreate in place" actually means.
		//
		// A map, not a struct: GORM omits zero values from a struct update, and
		// epoch 0 and an empty GroupInfo are exactly what a new incarnation needs.
		originalID := g.ID
		newID := uuid.New()
		cipherSuite := g.CipherSuite
		if body.CipherSuite != 0 {
			cipherSuite = body.CipherSuite
		}

		res := tx.Model(&models.MLSGroup{}).Where("id = ?", originalID).Updates(map[string]interface{}{
			"id":               newID,
			"group_id_data":    gid,
			"cipher_suite":     cipherSuite,
			"epoch":            0,
			"group_info_data":  nil,
			"group_info_epoch": 0,
			"created_by":       userID,
			"updated_at":       time.Now(),
		})
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected != 1 {
			// The locked row went missing between the SELECT and the UPDATE.
			// Treat it as a real failure rather than reporting a recreation that
			// did not happen.
			return fmt.Errorf("recreate %s: expected to update 1 row, updated %d", chatID, res.RowsAffected)
		}
		newInstance = newID

		if err := tx.Where("chat_id = ?", chatID).Delete(&models.MLSHandshake{}).Error; err != nil {
			return err
		}
		return tx.Where("chat_id = ? AND consumed_at IS NULL", chatID).
			Delete(&models.MLSWelcome{}).Error
	})

	if txErr != nil {
		// Only a genuinely absent group is a 404. Everything else - constraint
		// violations, lock failures, connection errors - is a server fault, and
		// reporting it as 404 sent a previous investigation chasing a missing row
		// that was sitting in the table the whole time.
		if errors.Is(txErr, gorm.ErrRecordNotFound) {
			return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "no mls group for this chat"})
		}
		// Logged in full server-side; the client is told only that it failed.
		log.Printf("RecreateGroup %s: %v", chatID, txErr)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "could not recreate the mls group",
		})
	}
	if badRequest != "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": badRequest})
	}
	if conflict != nil {
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error":        "group was already recreated",
			"chat_id":      conflict.ChatID,
			"instance_id":  conflict.ID,
			"group_id_b64": b64(conflict.GroupIDData),
			"cipher_suite": conflict.CipherSuite,
			"epoch":        conflict.Epoch,
		})
	}

	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"chat_id":      chatID,
		"instance_id":  newInstance,
		"group_id_b64": body.GroupIDB64,
		"epoch":        0,
	})
}

// GetGroup GET /e2ee/mls/groups/:chat_id — public metadata (epoch fence value).
func (h *MLSHandler) GetGroup(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	var g models.MLSGroup
	if err := database.DB.Where("chat_id = ?", chatID).First(&g).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "no mls group for this chat"})
	}
	return c.JSON(fiber.Map{
		"chat_id":      g.ChatID,
		"group_id_b64": b64(g.GroupIDData),
		"cipher_suite": g.CipherSuite,
		"epoch":        g.Epoch,
		// Identity of THIS incarnation of the group. A group that was deleted
		// and recreated reuses the same chat_id, so existence alone cannot tell
		// a client whether its local bundle still belongs here — it kept state
		// for the old tree and every unprotect failed the AEAD check. Clients
		// compare this and drop local state when it differs.
		"instance_id": g.ID,
		"created_at":  g.CreatedAt,
	})
}

// GetCoverage GET /e2ee/mls/groups/:chat_id/coverage
//
// The DS cannot read the ratchet tree, but it does know which devices have
// acked a Welcome and which still have an unclaimed KeyPackage. Clients use
// that to (a) elect a creator only among devices that can actually join, and
// (b) refuse to send MLS until every other live device has joined. Local
// "I have a group" is not the same as "everyone can decrypt".
//
// acked_device_ids means "has joined through some Welcome, ever".
// pending_device_ids means "the device's NEWEST Welcome is unconsumed AND the
// group has already moved past it" - an invitation that is outstanding rather
// than merely in flight. The two are computed independently and a device may
// appear in both: joined once, removed, re-invited, and not back yet.
func (h *MLSHandler) GetCoverage(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}

	var parts []models.ChatParticipant
	database.DB.Where("chat_id = ? AND left_at IS NULL", chatID).Find(&parts)
	ids := make([]uuid.UUID, 0, len(parts))
	for _, p := range parts {
		ids = append(ids, p.UserID)
	}

	live := make([]fiber.Map, 0)
	claimableIDs := make([]string, 0)
	if len(ids) > 0 {
		var devices []models.E2EEDevice
		database.DB.Where("user_id IN ? AND revoked_at IS NULL", ids).Find(&devices)

		type kpRow struct {
			UserID   uuid.UUID
			DeviceID string
		}
		var kps []kpRow
		database.DB.Model(&models.MLSKeyPackage{}).
			Select("user_id, device_id").
			Where("user_id IN ? AND claimed_at IS NULL", ids).
			Group("user_id, device_id").
			Find(&kps)
		claimable := make(map[string]bool, len(kps))
		for _, k := range kps {
			claimable[k.UserID.String()+"|"+k.DeviceID] = true
		}

		for _, d := range devices {
			key := d.UserID.String() + "|" + d.DeviceID
			canClaim := claimable[key]
			live = append(live, fiber.Map{
				"user_id":   d.UserID,
				"device_id": d.DeviceID,
				"claimable": canClaim,
			})
			if canClaim && d.DeviceID != "" {
				claimableIDs = append(claimableIDs, d.DeviceID)
			}
		}
	}

	var g models.MLSGroup
	exists := database.DB.Where("chat_id = ?", chatID).First(&g).Error == nil

	acked := make([]string, 0)
	pending := make([]string, 0)
	epoch := uint64(0)
	instanceID := ""
	if exists {
		epoch = g.Epoch
		instanceID = g.ID.String()
		var welcomes []models.MLSWelcome
		database.DB.Where("chat_id = ?", chatID).
			Order("created_at asc, epoch asc").Find(&welcomes)

		// A device's Welcomes are a chronological invitation ledger, and only
		// the NEWEST row describes where that device stands now. Aggregating
		// with "any unconsumed row wins" made a device whose ancient Welcome
		// could never be opened - its KeyPackage private key died with an old
		// store incarnation - pending forever, even after it had genuinely
		// joined through a later one. The client's phantom eviction reads this
		// field, so "pending forever" meant "evicted on every pass": a joined
		// phone was removed and re-added in a loop that walked a live group
		// from epoch 4 to epoch 8.
		//
		// Recency is created_at, never epoch: RecreateGroup zeroes the group
		// epoch while keeping consumed rows, so an epoch-4 row from a dead
		// incarnation would otherwise outrank an epoch-1 row from the live one.
		// Epoch is only a tiebreaker here - Welcomes for one commit are written
		// in a single transaction and share a timestamp.
		seenAcked := make(map[string]bool)
		newest := make(map[string]models.MLSWelcome)
		devices := make([]string, 0, len(welcomes))
		for _, w := range welcomes {
			id := w.RecipientDeviceID
			if id == "" {
				continue
			}
			// Unchanged: acked means "has ever joined through a Welcome".
			if w.ConsumedAt != nil && !seenAcked[id] {
				acked = append(acked, id)
				seenAcked[id] = true
			}
			prev, seen := newest[id]
			if !seen {
				devices = append(devices, id)
				newest[id] = w
				continue
			}
			if w.CreatedAt.After(prev.CreatedAt) ||
				(w.CreatedAt.Equal(prev.CreatedAt) && w.Epoch > prev.Epoch) {
				newest[id] = w
			}
		}
		for _, id := range devices {
			w := newest[id]
			// Consumed newest: joined, nothing outstanding.
			if w.ConsumedAt != nil {
				continue
			}
			// Unconsumed but at the current epoch: the invitation was issued by
			// the commit that just landed and the invitee has not had a chance
			// to poll for it. In flight is not the same as abandoned, and
			// treating it as abandoned is what closed the eviction loop.
			if w.Epoch >= g.Epoch {
				continue
			}
			pending = append(pending, id)
		}
	}

	return c.JSON(fiber.Map{
		"exists":               exists,
		"epoch":                epoch,
		"instance_id":          instanceID,
		"live_devices":         live,
		"claimable_device_ids": claimableIDs,
		"acked_device_ids":     acked,
		"pending_device_ids":   pending,
	})
}

type welcomeItem struct {
	UserID     string `json:"user_id"`
	DeviceID   string `json:"device_id"`
	WelcomeB64 string `json:"welcome_b64"`
}

type commitBody struct {
	// Epoch the sender observed. The commit moves the group to Epoch+1.
	ExpectedEpoch uint64        `json:"expected_epoch"`
	CommitB64     string        `json:"commit_b64"`
	SenderDevice  string        `json:"sender_device_id"`
	Welcomes      []welcomeItem `json:"welcomes"`
}

// SubmitCommit POST /e2ee/mls/groups/:chat_id/commit
//
// The ordering fence. MLS requires a single total order of commits: if two
// members commit against the same epoch, whoever loses must rebase. The server
// accepts a commit only when ExpectedEpoch matches the stored epoch, and does
// the check and the bump inside one transaction so a race cannot admit both.
// A loser gets 409 with the current epoch and re-fetches before retrying.
func (h *MLSHandler) SubmitCommit(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	var body commitBody
	if err := c.BodyParser(&body); err != nil || body.CommitB64 == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "commit_b64 required"})
	}
	payload, err := unb64(body.CommitB64)
	if err != nil || len(payload) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid commit_b64"})
	}

	var newEpoch uint64
	var conflictEpoch uint64
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		var g models.MLSGroup
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("chat_id = ?", chatID).First(&g).Error; err != nil {
			return err
		}
		if g.Epoch != body.ExpectedEpoch {
			conflictEpoch = g.Epoch
			return gorm.ErrInvalidValue // signals stale-epoch below
		}
		newEpoch = g.Epoch + 1
		g.Epoch = newEpoch
		g.UpdatedAt = time.Now()
		if err := tx.Save(&g).Error; err != nil {
			return err
		}
		hs := models.MLSHandshake{
			ID:             uuid.New(),
			ChatID:         chatID,
			Epoch:          newEpoch,
			Kind:           "commit",
			SenderUserID:   userID,
			SenderDeviceID: body.SenderDevice,
			Payload:        payload,
			CreatedAt:      time.Now(),
		}
		if err := tx.Create(&hs).Error; err != nil {
			return err
		}
		// Welcomes ride with the commit that added those members, so a joiner
		// never sees a Welcome for a commit that was rejected.
		for _, w := range body.Welcomes {
			rid, err := uuid.Parse(w.UserID)
			if err != nil {
				continue
			}
			wdata, err := unb64(w.WelcomeB64)
			if err != nil || len(wdata) == 0 {
				continue
			}
			if err := tx.Create(&models.MLSWelcome{
				ID:                uuid.New(),
				ChatID:            chatID,
				RecipientUserID:   rid,
				RecipientDeviceID: w.DeviceID,
				SenderUserID:      userID,
				Epoch:             newEpoch,
				Payload:           wdata,
				CreatedAt:         time.Now(),
			}).Error; err != nil {
				return err
			}
		}
		return nil
	})

	if conflictEpoch != 0 || (txErr != nil && conflictEpoch > 0) {
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error":          "stale epoch — refetch handshakes and rebase",
			"server_epoch":   conflictEpoch,
			"expected_epoch": body.ExpectedEpoch,
		})
	}
	if txErr != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "no mls group for this chat"})
	}

	// Nudge members to pull the new handshake. The notification carries only
	// the chat id and epoch — no protocol material.
	if h.hub != nil {
		if notice, err := json.Marshal(fiber.Map{
			"type":    "mls:commit",
			"chat_id": chatID,
			"epoch":   newEpoch,
		}); err == nil {
			var parts []models.ChatParticipant
			database.DB.Where("chat_id = ? AND left_at IS NULL", chatID).Find(&parts)
			for _, p := range parts {
				_ = h.hub.BroadcastToUser(p.UserID, notice)
			}
		}
	}
	return c.Status(http.StatusCreated).JSON(fiber.Map{"epoch": newEpoch})
}

type groupInfoBody struct {
	Epoch        uint64 `json:"epoch"`
	GroupInfoB64 string `json:"group_info_b64"`
}

// PutGroupInfo POST /e2ee/mls/groups/:chat_id/group-info
//
// A member publishes the GroupInfo for the epoch it just committed, so a member
// who lost local state can rejoin by external commit. Only ever moves forward:
// an older epoch's GroupInfo must not overwrite a newer one, or a rejoiner would
// build against a stale tree and have its commit rejected.
func (h *MLSHandler) PutGroupInfo(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	var body groupInfoBody
	if err := c.BodyParser(&body); err != nil || body.GroupInfoB64 == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "group_info_b64 required"})
	}
	data, err := unb64(body.GroupInfoB64)
	if err != nil || len(data) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid group_info_b64"})
	}

	var stored uint64
	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		var g models.MLSGroup
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("chat_id = ?", chatID).First(&g).Error; err != nil {
			return err
		}
		if body.Epoch < g.GroupInfoEpoch {
			stored = g.GroupInfoEpoch
			return nil // keep the newer one; not an error
		}
		g.GroupInfoData = data
		g.GroupInfoEpoch = body.Epoch
		g.UpdatedAt = time.Now()
		stored = body.Epoch
		return tx.Save(&g).Error
	})
	if txErr != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "no mls group for this chat"})
	}
	return c.JSON(fiber.Map{"group_info_epoch": stored})
}

// GetGroupInfo GET /e2ee/mls/groups/:chat_id/group-info
// Served only to participants; opaque signed bytes the server never parses.
func (h *MLSHandler) GetGroupInfo(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	var g models.MLSGroup
	if err := database.DB.Where("chat_id = ?", chatID).First(&g).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "no mls group for this chat"})
	}
	if len(g.GroupInfoData) == 0 {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "no group info published"})
	}
	return c.JSON(fiber.Map{
		"group_info_b64":   b64(g.GroupInfoData),
		"group_info_epoch": g.GroupInfoEpoch,
		"epoch":            g.Epoch,
		// A rejoiner must not build against a stale tree.
		"is_current": g.GroupInfoEpoch == g.Epoch,
	})
}

// GetHandshakes GET /e2ee/mls/groups/:chat_id/handshakes?since_epoch=N
// Returns commits/proposals after the epoch the caller has already applied.
func (h *MLSHandler) GetHandshakes(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	chatID := c.Params("chat_id")
	if !isChatParticipant(chatID, userID) {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{"error": "not a participant"})
	}
	since := c.QueryInt("since_epoch", 0)
	var rows []models.MLSHandshake
	database.DB.Where("chat_id = ? AND epoch > ?", chatID, since).
		Order("epoch asc, created_at asc").Limit(500).Find(&rows)

	out := make([]fiber.Map, 0, len(rows))
	for _, r := range rows {
		out = append(out, fiber.Map{
			"epoch":            r.Epoch,
			"kind":             r.Kind,
			"sender_user_id":   r.SenderUserID,
			"sender_device_id": r.SenderDeviceID,
			"payload_b64":      b64(r.Payload),
			"created_at":       r.CreatedAt,
		})
	}
	return c.JSON(fiber.Map{"handshakes": out})
}

// GetWelcomes GET /e2ee/mls/welcomes — Welcomes addressed to this user.
// Single-use: fetching marks them consumed so a replay cannot re-deliver.
func (h *MLSHandler) GetWelcomes(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	deviceID := c.Get("X-Device-Id")

	q := database.DB.Where("recipient_user_id = ? AND consumed_at IS NULL", userID)
	if deviceID != "" {
		q = q.Where("recipient_device_id = ? OR recipient_device_id = ''", deviceID)
	}
	var rows []models.MLSWelcome
	q.Order("created_at asc").Limit(50).Find(&rows)

	out := make([]fiber.Map, 0, len(rows))
	for _, r := range rows {
		out = append(out, fiber.Map{
			"id":          r.ID,
			"chat_id":     r.ChatID,
			"epoch":       r.Epoch,
			"welcome_b64": b64(r.Payload),
			"created_at":  r.CreatedAt,
		})
	}
	// NOTE: deliberately NOT marking consumed here. Stamping on handout meant a
	// client whose join threw (a BouncyCastle exception, a crash mid-join) could
	// never be offered the Welcome again and was locked out of the group for
	// good. The client acks below once the join actually succeeded.
	return c.JSON(fiber.Map{"welcomes": out})
}

type welcomeAckBody struct {
	IDs []string `json:"ids"`
}

// AckWelcome POST /e2ee/mls/welcomes/ack — the client confirms it joined with
// these Welcomes, so they can be retired. Scoped to the caller so one account
// cannot retire another's pending invites.
func (h *MLSHandler) AckWelcome(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	var body welcomeAckBody
	if err := c.BodyParser(&body); err != nil || len(body.IDs) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "ids required"})
	}
	res := database.DB.Model(&models.MLSWelcome{}).
		Where("id IN ? AND recipient_user_id = ? AND consumed_at IS NULL", body.IDs, userID).
		Update("consumed_at", time.Now())
	return c.JSON(fiber.Map{"acked": res.RowsAffected})
}
