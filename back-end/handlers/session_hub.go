package handlers

import (
	"github.com/google/uuid"

	"messenger-app/database"
	"messenger-app/websocket"
)

// sessionHub is the process-wide WebSocket hub, registered once at startup.
//
// Revocation happens in handlers that have no hub of their own (password
// change, password reset, logout), and a revoked session whose socket stays
// open would keep receiving live traffic until the client happened to
// reconnect. A package-level registration is the smallest way to reach the hub
// from every revocation path without threading it through constructors that
// several call sites - including tests - already depend on.
//
// Nil in tests that never start a hub; every use is nil-guarded.
var sessionHub *websocket.Hub

// SetSessionHub is called once from main.
func SetSessionHub(h *websocket.Hub) { sessionHub = h }

// kickSessions closes the sockets belonging to the given sessions.
//
// Keyed on session id, never device id: a session with a NULL device - which is
// every session these authentication paths create, because none of them knows a
// trusted device - would be invisible to any device-keyed kick.
func kickSessions(sessionIDs []uuid.UUID) {
	if sessionHub == nil {
		return
	}
	for _, id := range sessionIDs {
		sessionHub.KickSession(id.String())
	}
}

// kickSessionsOfUser closes every socket for an account. Called after the
// revoking transaction has committed: the database is the authority, and the
// socket close is the courtesy that stops an already-open connection from
// lingering until its next authenticated action.
func kickSessionsOfUser(userID uuid.UUID) {
	if sessionHub == nil || database.DB == nil {
		return
	}
	var ids []uuid.UUID
	if err := database.DB.Raw(
		`SELECT id FROM sessions WHERE user_id = ? AND revoked_at IS NOT NULL`, userID).
		Scan(&ids).Error; err != nil {
		return
	}
	kickSessions(ids)
}
