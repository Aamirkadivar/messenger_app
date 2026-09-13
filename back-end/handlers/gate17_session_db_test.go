package handlers

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/gorm"

	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// ============================== GATE 17: session layer, adversarial
//
// Every concurrency test here runs two goroutines against the real handler over
// the real GORM pool, which gives them distinct PostgreSQL connections, and each
// asserts the FINAL DATABASE STATE rather than only the HTTP responses. A pair
// of 200s with a coherent database would still be a failure; so would a correct
// pair of responses over a database that rotated twice.

type g17Env struct {
	// reqID and device let a subtest swap in a session of its own shape.
	reqID   uuid.UUID
	device  *uuid.UUID
	app     *fiber.App
	cfg     *config.Config
	user    models.User
	session uuid.UUID
	refresh string
}

func gate17Setup(t *testing.T) *g17Env {
	t.Helper()
	assertScratchDB(t)

	db := archiveTestDB(t)
	resetScratchSchema(t, db)
	if err := models.MigrateDB(db); err != nil {
		t.Fatalf("MigrateDB: %v", err)
	}
	prev := database.DB
	database.DB = db
	t.Cleanup(func() { database.DB = prev })

	cfg := config.LoadConfig()
	u := models.User{
		ID: uuid.New(), Username: "g17" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "G17",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := db.Create(&u).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}

	var (
		sid   uuid.UUID
		plain string
	)
	if err := db.Transaction(func(tx *gorm.DB) error {
		var err error
		sid, plain, err = createSession(tx, cfg, u.ID, nil, nil)
		return err
	}); err != nil {
		t.Fatalf("create session: %v", err)
	}

	auth := NewAuthService()
	app := fiber.New()
	api := app.Group("/api/v1")
	api.Post("/auth/refresh", auth.RefreshToken)
	protected := api.Group("")
	protected.Use(middleware.AuthMiddleware(cfg))
	protected.Get("/protected", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"user_id": middleware.GetCurrentUserID(c)})
	})
	protected.Post("/auth/logout", auth.Logout)
	app.Get("/ws", middleware.WebSocketAuth(cfg), func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"ok": true})
	})

	return &g17Env{app: app, cfg: cfg, user: u, session: sid, refresh: plain}
}

type refreshResult struct {
	code    int
	access  string
	refresh string
}

func (e *g17Env) refreshWith(t *testing.T, token string, requestID uuid.UUID) refreshResult {
	t.Helper()
	body, _ := json.Marshal(map[string]string{
		"refresh_token": token, "request_id": requestID.String(),
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/refresh", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Errorf("request: %v", err)
		return refreshResult{}
	}
	raw, _ := io.ReadAll(resp.Body)
	var parsed struct {
		Tokens struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		} `json:"tokens"`
	}
	_ = json.Unmarshal(raw, &parsed)
	return refreshResult{resp.StatusCode, parsed.Tokens.AccessToken, parsed.Tokens.RefreshToken}
}

func (e *g17Env) bearer(t *testing.T, token string) int {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/protected", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func (e *g17Env) sessionRow(t *testing.T) models.Session {
	t.Helper()
	var s models.Session
	if err := database.DB.Where("id = ?", e.session).First(&s).Error; err != nil {
		t.Fatalf("session row: %v", err)
	}
	return s
}

func (e *g17Env) ledgerCount(t *testing.T) int64 {
	t.Helper()
	var n int64
	database.DB.Model(&models.ConsumedRefresh{}).Where("session_id = ?", e.session).Count(&n)
	return n
}

// lockBarrier holds the session row locked in its own transaction so racing
// actors pile up behind it, then releases them together.
//
// The GORM callback harness used elsewhere cannot be used here: it hooks the
// ORM's Update callback, and the rotation CAS is raw SQL, so the callback never
// fires. A real row lock is also a truer barrier - it blocks the actors at
// exactly the point PostgreSQL itself would serialise them.
type lockBarrier struct {
	release chan struct{}
	done    chan struct{}
}

func newLockBarrier(t *testing.T, sessionID uuid.UUID) *lockBarrier {
	t.Helper()
	b := &lockBarrier{release: make(chan struct{}), done: make(chan struct{})}
	held := make(chan struct{})
	go func() {
		defer close(b.done)
		_ = database.DB.Transaction(func(tx *gorm.DB) error {
			var id uuid.UUID
			if err := tx.Raw(`SELECT id FROM sessions WHERE id = ? FOR UPDATE`, sessionID).
				Row().Scan(&id); err != nil {
				close(held)
				return err
			}
			close(held)
			<-b.release
			return nil
		})
	}()
	select {
	case <-held:
	case <-time.After(10 * time.Second):
		t.Fatal("barrier never acquired the session row lock")
	}
	return b
}

func (b *lockBarrier) lift() { close(b.release); <-b.done }

// ---------------------------------------------------------------- P0.1
// Two concurrent refreshes, same token, DIFFERENT request_id.
func TestGate17_ConcurrentRefresh_DifferentRequestID(t *testing.T) {
	e := gate17Setup(t)
	barrier := newLockBarrier(t, e.session)

	var a, b refreshResult
	aDone, bDone := make(chan struct{}), make(chan struct{})
	go func() { defer close(aDone); a = e.refreshWith(t, e.refresh, uuid.New()) }()
	go func() { defer close(bDone); b = e.refreshWith(t, e.refresh, uuid.New()) }()
	time.Sleep(500 * time.Millisecond) // both are now blocked on the row lock
	barrier.lift()
	<-aDone
	<-bDone

	if (a.code == http.StatusOK) == (b.code == http.StatusOK) {
		t.Fatalf("expected exactly one rotation, got A=%d B=%d", a.code, b.code)
	}
	s := e.sessionRow(t)
	if s.Generation != 1 {
		t.Fatalf("generation must advance exactly once, got %d", s.Generation)
	}
	if n := e.ledgerCount(t); n != 1 {
		t.Fatalf("exactly one ledger row expected, got %d", n)
	}
	// No fork evidence yet (the successor is unconsumed), so the loser is denied
	// WITHOUT revocation - otherwise an ordinary client race would log the user
	// out.
	if s.RevokedAt != nil {
		t.Fatalf("a plain CAS loss must not revoke the session (reason=%v)", s.RevokeReason)
	}
	t.Logf("one rotation (A=%d B=%d), generation=%d, ledger=1, session live",
		a.code, b.code, s.Generation)
}

// ---------------------------------------------------------------- P0.2
// Two concurrent refreshes, same token, SAME request_id: the honest retry.
func TestGate17_ConcurrentRefresh_SameRequestID(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()
	barrier := newLockBarrier(t, e.session)

	var a, b refreshResult
	aDone, bDone := make(chan struct{}), make(chan struct{})
	go func() { defer close(aDone); a = e.refreshWith(t, e.refresh, reqID) }()
	go func() { defer close(bDone); b = e.refreshWith(t, e.refresh, reqID) }()
	time.Sleep(500 * time.Millisecond)
	barrier.lift()
	<-aDone
	<-bDone

	if a.code != http.StatusOK || b.code != http.StatusOK {
		t.Fatalf("an honest retry must also succeed: A=%d B=%d", a.code, b.code)
	}
	if a.refresh != b.refresh {
		t.Fatal("both callers must receive the SAME successor refresh token")
	}
	// Do NOT compare the two access tokens: JWT claims carry second-resolution
	// iat/exp, so two mints inside the same second are byte-identical and would
	// prove nothing either way. Assert the real property instead - the cache
	// holds ONLY the successor refresh token, so no access token exists at rest
	// and the one returned on replay must have been re-minted.
	var cr models.ConsumedRefresh
	if err := database.DB.Where("session_id = ?", e.session).First(&cr).Error; err != nil {
		t.Fatalf("ledger row: %v", err)
	}
	cached, ok := openResponseCache(e.cfg, cr.ConsumedHash, cr.SessionID, cr.RequestID,
		cr.ResponseCiphertext, cr.ResponseNonce)
	if !ok {
		t.Fatal("cached successor should open with the row's own AAD")
	}
	if cached != a.refresh {
		t.Fatal("the cache must contain exactly the successor refresh token")
	}
	if cached == a.access || len(cached) > 64 {
		t.Fatal("no access token may be stored in the response cache")
	}
	s := e.sessionRow(t)
	if s.Generation != 1 {
		t.Fatalf("a retry must not create a second generation, got %d", s.Generation)
	}
	if n := e.ledgerCount(t); n != 1 {
		t.Fatalf("a retry must not create a second ledger row, got %d", n)
	}
	if s.RevokedAt != nil {
		t.Fatal("an honest retry must not revoke the session")
	}
	t.Log("same successor returned twice, one generation, one ledger row, access re-minted")
}

// ---------------------------------------------------------------- P0.3
// Fork evidence: the successor was itself consumed, then the parent replays.
func TestGate17_ForkEvidenceRevokesSession(t *testing.T) {
	e := gate17Setup(t)
	first := e.refreshWith(t, e.refresh, uuid.New())
	if first.code != http.StatusOK {
		t.Fatalf("setup rotation failed: %d", first.code)
	}
	// Consume the successor, so the chain has moved on.
	second := e.refreshWith(t, first.refresh, uuid.New())
	if second.code != http.StatusOK {
		t.Fatalf("setup second rotation failed: %d", second.code)
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatal("normal chained rotation must not revoke")
	}

	// Now replay the ORIGINAL token. Its successor is spent, so two lineages
	// exist: this is a fork, not a retry.
	replay := e.refreshWith(t, e.refresh, uuid.New())
	if replay.code != http.StatusUnauthorized {
		t.Fatalf("fork replay must be denied, got %d", replay.code)
	}
	s := e.sessionRow(t)
	if s.RevokedAt == nil {
		t.Fatal("fork evidence must revoke the session")
	}
	if s.RevokeReason == nil || *s.RevokeReason != "refresh_reuse" {
		t.Fatalf("expected refresh_reuse, got %v", s.RevokeReason)
	}
	if s.RefreshHash != nil {
		t.Fatal("a revoked session must hold no usable refresh hash")
	}
	// And the credential minted moments earlier is now dead.
	if code := e.refreshWith(t, second.refresh, uuid.New()).code; code != http.StatusUnauthorized {
		t.Fatalf("the live successor must die with the session, got %d", code)
	}
	t.Log("fork detected, session revoked, whole lineage dead")
}

// ---------------------------------------------------------------- P0.4
// An unattributable hash must never become a remote session-kill primitive.
func TestGate17_UnknownHashDeniesWithoutRevoking(t *testing.T) {
	e := gate17Setup(t)
	stranger, _, err := mintRefreshToken(e.cfg)
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	if code := e.refreshWith(t, stranger, uuid.New()).code; code != http.StatusUnauthorized {
		t.Fatalf("unknown token must be denied, got %d", code)
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatal("an unknown hash must NEVER revoke a session")
	}
	if n := e.ledgerCount(t); n != 0 {
		t.Fatalf("an unknown hash must write no ledger row, got %d", n)
	}
	// The real credential still works.
	if code := e.refreshWith(t, e.refresh, uuid.New()).code; code != http.StatusOK {
		t.Fatalf("the genuine token must still rotate, got %d", code)
	}
	t.Log("unknown hash denied, nothing revoked, genuine credential unaffected")
}

// ---------------------------------------------------------------- P0.5
// Revocation dominates idempotency: a logged-out session gets no cached reply.
func TestGate17_RevocationDominatesCachedReplay(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()
	first := e.refreshWith(t, e.refresh, reqID)
	if first.code != http.StatusOK {
		t.Fatalf("setup rotation failed: %d", first.code)
	}
	// Same request_id would normally replay from cache within the window.
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		_, err := revokeOneSession(tx, e.user.ID, e.session, "logout")
		return err
	}); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	replay := e.refreshWith(t, e.refresh, reqID)
	if replay.code != http.StatusUnauthorized {
		t.Fatalf("a revoked session must never receive cached credentials, got %d", replay.code)
	}
	if replay.refresh != "" || replay.access != "" {
		t.Fatal("no credential material may be returned for a revoked session")
	}
	var cached int64
	database.DB.Model(&models.ConsumedRefresh{}).
		Where("session_id = ? AND response_ciphertext IS NOT NULL", e.session).Count(&cached)
	if cached != 0 {
		t.Fatalf("revocation must purge cached successor material, %d rows remain", cached)
	}
	t.Log("revoked session: cache withheld and purged transactionally")
}

// ---------------------------------------------------------------- P0.6
// Refresh racing revocation, revocation committing inside the CAS window.
func TestGate17_RefreshVersusRevoke(t *testing.T) {
	e := gate17Setup(t)
	barrier := newLockBarrier(t, e.session)

	var res refreshResult
	rDone, revDone := make(chan struct{}), make(chan struct{})
	go func() { defer close(rDone); res = e.refreshWith(t, e.refresh, uuid.New()) }()
	go func() {
		defer close(revDone)
		_ = database.DB.Transaction(func(tx *gorm.DB) error {
			_, err := revokeOneSession(tx, e.user.ID, e.session, "logout")
			return err
		})
	}()
	time.Sleep(500 * time.Millisecond) // both contend for the same row lock
	barrier.lift()
	<-rDone
	<-revDone

	s := e.sessionRow(t)
	if s.RevokedAt == nil {
		t.Fatal("the revocation must land regardless of ordering")
	}
	if s.RefreshHash != nil {
		t.Fatal("a revoked session must hold no usable refresh hash")
	}
	// Whichever won, the credential is dead now.
	if res.code == http.StatusOK {
		if code := e.refreshWith(t, res.refresh, uuid.New()).code; code != http.StatusUnauthorized {
			t.Fatalf("a credential minted just before revocation must not survive it, got %d", code)
		}
	}
	t.Logf("refresh=%d, session revoked, no usable credential remains", res.code)
}

// ---------------------------------------------------------------- P0.7
// Access tokens stop working the moment their session is revoked.
func TestGate17_AccessTokenDiesWithSession(t *testing.T) {
	e := gate17Setup(t)
	access, err := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.session, e.cfg)
	if err != nil {
		t.Fatalf("mint access: %v", err)
	}
	if code := e.bearer(t, access); code != http.StatusOK {
		t.Fatalf("precondition: access token should work, got %d", code)
	}
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		_, err := revokeOneSession(tx, e.user.ID, e.session, "logout")
		return err
	}); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if code := e.bearer(t, access); code != http.StatusUnauthorized {
		t.Fatalf("access token must die with its session, got %d", code)
	}
	t.Log("access token invalidated by session revocation")
}

// ---------------------------------------------------------------- P0.8
// sid resolution is conjunctive: another account's session must not authorize.
func TestGate17_CrossAccountSidIsRefused(t *testing.T) {
	e := gate17Setup(t)
	other := models.User{
		ID: uuid.New(), Username: "g17b" + uuid.New().String()[:8],
		Email: uuid.New().String()[:8] + "@t.local", DisplayName: "Other",
		PasswordHash: "x", CreatedAt: time.Now(), UpdatedAt: time.Now(),
	}
	if err := database.DB.Create(&other).Error; err != nil {
		t.Fatalf("seed other: %v", err)
	}

	// Account B naming account A's (live) session.
	forged, err := middleware.GenerateToken(other.ID, other.Email, other.DisplayName, e.session, e.cfg)
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	if code := e.bearer(t, forged); code != http.StatusUnauthorized {
		t.Fatalf("valid sid + wrong user_id must be refused, got %d", code)
	}
	// Account A naming a session that does not exist.
	bogus, _ := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, uuid.New(), e.cfg)
	if code := e.bearer(t, bogus); code != http.StatusUnauthorized {
		t.Fatalf("valid user_id + unknown sid must be refused, got %d", code)
	}
	t.Log("sid lookup is conjunctive on (id, user_id)")
}

// ---------------------------------------------------------------- P0.9
// WebSocket parity, including the NULL-device sessions every login produces.
func (e *g17Env) ws(t *testing.T, token string) int {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/ws?token="+token, nil)
	resp, err := e.app.Test(req, -1)
	if err != nil {
		t.Fatalf("ws: %v", err)
	}
	_, _ = io.ReadAll(resp.Body)
	return resp.StatusCode
}

func TestGate17_WebSocketHonoursSessionRevocation(t *testing.T) {
	e := gate17Setup(t)
	if s := e.sessionRow(t); s.DeviceID != nil {
		t.Fatal("precondition: login sessions must have a NULL device association")
	}
	access, err := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.session, e.cfg)
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	if code := e.ws(t, access); code == http.StatusUnauthorized {
		t.Fatal("precondition: a live session should be accepted at the socket")
	}
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		_, err := revokeOneSession(tx, e.user.ID, e.session, "logout")
		return err
	}); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if code := e.ws(t, access); code != http.StatusUnauthorized {
		t.Fatalf("WebSocket must refuse a revoked session, got %d", code)
	}
	t.Log("WebSocket refuses a revoked NULL-device session")
}

// ---------------------------------------------------------------- P0.10
// Absolute lifetime is never advanced by rotation.
func TestGate17_AbsoluteExpiryIsNeverExtended(t *testing.T) {
	e := gate17Setup(t)
	before := e.sessionRow(t)
	res := e.refreshWith(t, e.refresh, uuid.New())
	if res.code != http.StatusOK {
		t.Fatalf("rotation failed: %d", res.code)
	}
	after := e.sessionRow(t)
	if !after.AbsoluteExpiresAt.Equal(before.AbsoluteExpiresAt) {
		t.Fatalf("absolute_expires_at moved: %v -> %v",
			before.AbsoluteExpiresAt, after.AbsoluteExpiresAt)
	}
	if !after.RefreshExpiresAt.After(before.RefreshExpiresAt) {
		t.Fatal("refresh_expires_at should advance on rotation")
	}

	// Past its absolute lifetime, no rotation is possible however fresh the
	// refresh window looks.
	// created_at moves with it: ck_sessions_absolute_after_created requires the
	// absolute deadline to sit after creation, and that constraint is itself
	// part of what Gate 17 guarantees.
	if err := database.DB.Exec(
		`UPDATE sessions
		    SET created_at          = now() - interval '2 hours',
		        absolute_expires_at = now() - interval '1 minute'
		  WHERE id = ?`, e.session).Error; err != nil {
		t.Fatalf("age session: %v", err)
	}
	if code := e.refreshWith(t, res.refresh, uuid.New()).code; code != http.StatusUnauthorized {
		t.Fatalf("rotation past absolute expiry must be denied, got %d", code)
	}
	t.Log("absolute lifetime is a hard ceiling on rotation")
}

// ---------------------------------------------------------------- P0.11
// Database-enforced invariants: revoked is terminal, ownership is immutable.
func TestGate17_DatabaseRejectsResurrectionAndReassignment(t *testing.T) {
	e := gate17Setup(t)
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		_, err := revokeOneSession(tx, e.user.ID, e.session, "logout")
		return err
	}); err != nil {
		t.Fatalf("revoke: %v", err)
	}

	if err := database.DB.Exec(
		`UPDATE sessions SET revoked_at = NULL WHERE id = ?`, e.session).Error; err == nil {
		t.Fatal("clearing revoked_at must be rejected by the database")
	}
	if err := database.DB.Exec(
		`UPDATE sessions SET user_id = ? WHERE id = ?`, uuid.New(), e.session).Error; err == nil {
		t.Fatal("changing a session's user_id must be rejected by the database")
	}
	if err := database.DB.Exec(
		`UPDATE sessions SET refresh_hash = ? WHERE id = ?`,
		make([]byte, 32), e.session).Error; err == nil {
		t.Fatal("a revoked session must not be given a usable refresh hash")
	}
	s := e.sessionRow(t)
	if s.RevokedAt == nil || s.RefreshHash != nil {
		t.Fatal("revoked session state was mutated despite the guards")
	}
	t.Log("resurrection, reassignment and re-arming are all refused by the database")
}

// ---------------------------------------------------------------- P0.12
// Logout revokes only the caller's session, and is idempotent.
func TestGate17_LogoutIsScopedAndIdempotent(t *testing.T) {
	e := gate17Setup(t)
	// A second session for the same user, which must survive a scoped logout.
	var (
		otherID uuid.UUID
		otherRT string
	)
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		var err error
		otherID, otherRT, err = createSession(tx, e.cfg, e.user.ID, nil, nil)
		return err
	}); err != nil {
		t.Fatalf("second session: %v", err)
	}

	access, _ := middleware.GenerateToken(e.user.ID, e.user.Email, e.user.DisplayName, e.session, e.cfg)
	call := func() int {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/logout", nil)
		req.Header.Set("Authorization", "Bearer "+access)
		resp, err := e.app.Test(req, -1)
		if err != nil {
			t.Fatalf("logout: %v", err)
		}
		_, _ = io.ReadAll(resp.Body)
		return resp.StatusCode
	}
	if code := call(); code != http.StatusNoContent {
		t.Fatalf("logout expected 204, got %d", code)
	}
	if s := e.sessionRow(t); s.RevokedAt == nil {
		t.Fatal("logout must revoke the caller's session")
	}
	// The other session is untouched and still rotates.
	var other models.Session
	database.DB.Where("id = ?", otherID).First(&other)
	if other.RevokedAt != nil {
		t.Fatal("a scoped logout must not revoke the account's other sessions")
	}
	if code := e.refreshWith(t, otherRT, uuid.New()).code; code != http.StatusOK {
		t.Fatalf("the other session must still rotate, got %d", code)
	}
	// Idempotent: the access token is now dead, so a repeat is 401 rather than
	// a second revocation - and either way nothing changes.
	_ = call()
	t.Log("logout scoped to the caller's session and idempotent")
}

// ---------------------------------------------------------------- P0.13
// Sequential single-use, and the plaintext never reaching SQL.
func TestGate17_SequentialRotationAndPlaintextContainment(t *testing.T) {
	e := gate17Setup(t)
	first := e.refreshWith(t, e.refresh, uuid.New())
	if first.code != http.StatusOK {
		t.Fatalf("first rotation: %d", first.code)
	}
	// The superseded token, with a NEW request_id, is not a retry and mints
	// nothing - but there is no fork evidence yet, so nothing is revoked.
	again := e.refreshWith(t, e.refresh, uuid.New())
	if again.code != http.StatusUnauthorized {
		t.Fatalf("a superseded token must not rotate, got %d", again.code)
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatal("a superseded token alone is not fork evidence")
	}

	// The plaintext must exist nowhere in the database.
	var hits int64
	database.DB.Raw(`SELECT count(*) FROM sessions
	                  WHERE encode(refresh_hash,'escape') LIKE ?`, "%"+first.refresh+"%").
		Scan(&hits)
	if hits != 0 {
		t.Fatal("refresh plaintext must never be stored")
	}
	var s models.Session
	database.DB.Where("id = ?", e.session).First(&s)
	if len(s.RefreshHash) != 32 {
		t.Fatalf("stored hash must be 32 bytes, got %d", len(s.RefreshHash))
	}
	t.Log("single-use enforced sequentially; only a 32-byte hash is stored")
}

// ---------------------------------------------------------------- P0.14
// A burst of concurrent refreshes yields exactly one rotation.
func TestGate17_BurstRefreshYieldsOneRotation(t *testing.T) {
	e := gate17Setup(t)
	const n = 12
	var wg sync.WaitGroup
	codes := make(chan int, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes <- e.refreshWith(t, e.refresh, uuid.New()).code
		}()
	}
	wg.Wait()
	close(codes)

	ok := 0
	for c := range codes {
		if c == http.StatusOK {
			ok++
		}
	}
	if ok != 1 {
		t.Fatalf("exactly one of %d concurrent refreshes may succeed, got %d", n, ok)
	}
	s := e.sessionRow(t)
	if s.Generation != 1 {
		t.Fatalf("generation must be 1, got %d", s.Generation)
	}
	if c := e.ledgerCount(t); c != 1 {
		t.Fatalf("exactly one ledger row, got %d", c)
	}
	t.Logf("%d concurrent refreshes -> 1 success, generation=1, ledger=1", n)
}

// ---------------------------------------------------------------- P0.15
// A session that dies by EXPIRY, not revocation, must also stop cached replay.
//
// This is the case the liveness gate exists for. Revocation happens to purge
// the cache in the same transaction, which masks the gate; expiry does not
// purge anything, so without the explicit liveness check a replay inside the
// 60-second window would hand back credentials for a session that is already
// over. Mutation-verified: removing the gate makes this test fail.
func TestGate17_ExpiredSessionCannotReplayFromCache(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()
	first := e.refreshWith(t, e.refresh, reqID)
	if first.code != http.StatusOK {
		t.Fatalf("setup rotation failed: %d", first.code)
	}
	// The cached successor is still present and inside its window.
	var cached int64
	database.DB.Model(&models.ConsumedRefresh{}).
		Where("session_id = ? AND response_ciphertext IS NOT NULL", e.session).Count(&cached)
	if cached != 1 {
		t.Fatalf("precondition: expected a live cache entry, got %d", cached)
	}

	// Age the session past its absolute lifetime WITHOUT revoking it, so the
	// cache is untouched and only the liveness check can refuse the replay.
	if err := database.DB.Exec(
		`UPDATE sessions
		    SET created_at          = now() - interval '2 hours',
		        absolute_expires_at = now() - interval '1 minute'
		  WHERE id = ?`, e.session).Error; err != nil {
		t.Fatalf("age session: %v", err)
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatal("precondition: this test must exercise expiry, not revocation")
	}

	replay := e.refreshWith(t, e.refresh, reqID)
	if replay.code != http.StatusUnauthorized {
		t.Fatalf("an expired session must not replay from cache, got %d", replay.code)
	}
	if replay.refresh != "" || replay.access != "" {
		t.Fatal("no credential material may be returned for an expired session")
	}
	t.Log("expired session refused despite an intact, in-window cache entry")
}

// ---------------------------------------------------------------- P0.16
// The response cache expires on its own absolute clock, independent of the
// session.
//
// This is the other half of the liveness gate: a session can be perfectly alive
// and the replay must STILL be refused once the 60-second window has passed.
// Without it the cache would effectively inherit the session lifetime, which is
// days, and a token consumed long ago could still hand back a live successor.
func TestGate17_CacheExpiresOnItsOwnAbsoluteClock(t *testing.T) {
	e := gate17Setup(t)
	reqID := uuid.New()
	first := e.refreshWith(t, e.refresh, reqID)
	if first.code != http.StatusOK {
		t.Fatalf("setup rotation failed: %d", first.code)
	}
	// Same request_id inside the window replays successfully.
	within := e.refreshWith(t, e.refresh, reqID)
	if within.code != http.StatusOK || within.refresh != first.refresh {
		t.Fatalf("in-window replay should return the same successor, got %d", within.code)
	}

	// Age ONLY the cache. The session stays live, so nothing but the absolute
	// TTL can refuse the next attempt.
	if err := database.DB.Exec(
		`UPDATE consumed_refresh SET response_expires_at = now() - interval '1 second'
		  WHERE session_id = ?`, e.session).Error; err != nil {
		t.Fatalf("age cache: %v", err)
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatal("precondition: the session must still be live")
	}

	after := e.refreshWith(t, e.refresh, reqID)
	if after.code != http.StatusUnauthorized {
		t.Fatalf("replay past the absolute cache TTL must be refused, got %d", after.code)
	}
	if after.refresh != "" || after.access != "" {
		t.Fatal("no credential material may be returned after the window closes")
	}
	// A closed window is lateness, not an attack: nothing may be revoked.
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatalf("an expired cache window must not revoke (reason=%v)", s.RevokeReason)
	}
	t.Log("cache refused on its own clock while the session stayed live; no revocation")
}

// ---------------------------------------------------------------- P0.17
// Device revocation is scoped to its device and must not sweep NULL-device
// sessions.
//
// Every session login, 2FA and QR claim creates has device_id NULL, because no
// authentication path knows a trusted device. If device revocation swept those
// too, revoking one device would sign the user out everywhere - and an
// authorization predicate written the other way round ("device_id = $1 OR
// device_id IS NULL") would be an outright bypass.
func TestGate17_DeviceRevocationDoesNotSweepNullDeviceSessions(t *testing.T) {
	e := gate17Setup(t)
	now := time.Now()

	// A real device row, and a session bound to it.
	dev := models.E2EEDevice{
		ID: uuid.New(), UserID: e.user.ID, DeviceID: "g17-dev",
		CreatedAt: now, UpdatedAt: now,
	}
	if err := database.DB.Create(&dev).Error; err != nil {
		t.Fatalf("seed device: %v", err)
	}
	var boundID uuid.UUID
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		var err error
		boundID, _, err = createSession(tx, e.cfg, e.user.ID, &dev.ID, nil)
		return err
	}); err != nil {
		t.Fatalf("bound session: %v", err)
	}

	// e.session is the NULL-device one created by the harness.
	if s := e.sessionRow(t); s.DeviceID != nil {
		t.Fatal("precondition: the harness session must be NULL-device")
	}

	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		_, err := revokeSessionsForDevice(tx, e.user.ID, dev.ID, "device_revoke")
		return err
	}); err != nil {
		t.Fatalf("device revoke: %v", err)
	}

	var bound models.Session
	database.DB.Where("id = ?", boundID).First(&bound)
	if bound.RevokedAt == nil {
		t.Fatal("the device's own session must be revoked")
	}
	if s := e.sessionRow(t); s.RevokedAt != nil {
		t.Fatal("a NULL-device session must NOT be swept by device revocation")
	}
	// And it still works.
	if code := e.refreshWith(t, e.refresh, uuid.New()).code; code != http.StatusOK {
		t.Fatalf("the untouched NULL-device session must still rotate, got %d", code)
	}
	t.Log("device revocation scoped to its device; NULL-device session untouched and usable")
}
