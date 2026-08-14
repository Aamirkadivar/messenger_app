package handlers

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
)

// CallService exposes call history and TURN credentials over REST. Call
// signaling itself (invite/answer/ICE/end) is real-time-only and lives in
// websocket/calls.go - there's nothing to poll for during a call, only a log
// of past ones afterward and the ICE server list needed to start one.
type CallService struct {
	cfg *config.Config
}

func NewCallService(cfg *config.Config) *CallService {
	return &CallService{cfg: cfg}
}

// GetIceServers returns STUN/TURN servers for the client's PeerConnection,
// including a short-lived TURN credential (coturn's REST API / long-term
// credential mechanism: username is "<expiry-unix-ts>:<user-id>", password
// is base64(HMAC-SHA1(sharedSecret, username))). Nothing here needs auth
// beyond "is a logged-in user" - it's the same credential every device of
// every user could compute if they had the shared secret, just scoped to a
// short TTL so a leaked credential stops working quickly.
func (s *CallService) GetIceServers(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	iceServers := []fiber.Map{
		{"urls": []string{"stun:stun.l.google.com:19302"}},
	}

	if s.cfg.TURNSecret != "" {
		expiry := time.Now().Add(6 * time.Hour).Unix()
		username := fmt.Sprintf("%d:%s", expiry, userID.String())

		mac := hmac.New(sha1.New, []byte(s.cfg.TURNSecret))
		mac.Write([]byte(username))
		credential := base64.StdEncoding.EncodeToString(mac.Sum(nil))

		for _, host := range turnAdvertiseHosts(s.cfg.TURNHost, requestAdvertiseHost(c)) {
			turnHostPort := net.JoinHostPort(host, s.cfg.TURNPort)
			iceServers = append(iceServers, fiber.Map{
				"urls": []string{
					"stun:" + turnHostPort,
					"turn:" + turnHostPort + "?transport=udp",
					"turn:" + turnHostPort + "?transport=tcp",
				},
				"username":   username,
				"credential": credential,
			})
		}
	}

	return c.JSON(fiber.Map{"ice_servers": iceServers})
}

// CallLogResponse mirrors models.CallLog but adds isIncoming, computed
// relative to the requesting user rather than making the client work it out
// from caller_id.
type CallLogResponse struct {
	ID          string  `json:"id"`
	ChatID      string  `json:"chat_id"`
	CallerID    string  `json:"caller_id"`
	CalleeID    string  `json:"callee_id"`
	IsIncoming  bool    `json:"is_incoming"`
	IsVideo     bool    `json:"is_video"`
	Status      string  `json:"status"`
	StartedAt   string  `json:"started_at"`
	ConnectedAt *string `json:"connected_at,omitempty"`
	EndedAt     *string `json:"ended_at,omitempty"`
	DurationSec int     `json:"duration_sec"`
}

// GetCallHistory returns the caller's calls (both directions), newest first.
func (s *CallService) GetCallHistory(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	limit := c.QueryInt("limit", 50)
	if limit <= 0 || limit > 200 {
		limit = 50
	}

	var calls []models.CallLog
	if err := database.DB.
		Where("caller_id = ? OR callee_id = ?", userID, userID).
		Order("started_at DESC").
		Limit(limit).
		Find(&calls).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to load call history",
		})
	}

	resp := make([]CallLogResponse, 0, len(calls))
	for _, call := range calls {
		item := CallLogResponse{
			ID:          call.ID.String(),
			ChatID:      call.ChatID,
			CallerID:    call.CallerID.String(),
			CalleeID:    call.CalleeID.String(),
			IsIncoming:  call.CalleeID == userID,
			IsVideo:     call.IsVideo,
			Status:      call.Status,
			StartedAt:   call.StartedAt.Format("2006-01-02T15:04:05Z07:00"),
			DurationSec: call.DurationSec,
		}
		if call.ConnectedAt != nil {
			s := call.ConnectedAt.Format("2006-01-02T15:04:05Z07:00")
			item.ConnectedAt = &s
		}
		if call.EndedAt != nil {
			s := call.EndedAt.Format("2006-01-02T15:04:05Z07:00")
			item.EndedAt = &s
		}
		resp = append(resp, item)
	}

	return c.JSON(fiber.Map{"data": resp})
}

func isLoopbackHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "" || h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "[::1]" {
		return true
	}
	ip := net.ParseIP(strings.Trim(h, "[]"))
	return ip != nil && ip.IsLoopback()
}

func requestAdvertiseHost(c *fiber.Ctx) string {
	host := c.Get("X-Forwarded-Host")
	if host == "" {
		host = c.Hostname()
	}
	host = strings.TrimSpace(strings.Split(host, ",")[0])
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	return host
}

// turnAdvertiseHosts picks TURN URIs that the calling device can actually
// reach. A configured loopback host is skipped for remote clients (phones
// cannot use TURN_HOST=localhost); the Host the client used to hit the API
// is advertised instead.
func turnAdvertiseHosts(configured, requestHost string) []string {
	var out []string
	seen := map[string]struct{}{}
	add := func(h string) {
		h = strings.TrimSpace(h)
		if h == "" {
			return
		}
		if _, ok := seen[h]; ok {
			return
		}
		seen[h] = struct{}{}
		out = append(out, h)
	}
	if configured != "" && !isLoopbackHost(configured) {
		add(configured)
	}
	if requestHost != "" {
		add(requestHost)
	}
	if len(out) == 0 && configured != "" {
		add(configured)
	}
	return out
}

