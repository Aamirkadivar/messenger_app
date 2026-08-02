package websocket

import (
	"encoding/json"
	"log"
	"time"

	"messenger-app/database"
	"messenger-app/models"

	"github.com/google/uuid"
)

// CallSignalTypes are the WS message types relayed 1:1 between a call's two
// participants. Only signaling (SDP offer/answer, ICE candidates) ever
// touches the server - the actual audio is DTLS-SRTP directly between the
// two peers via libdatachannel, negotiated from this exchange, so the server
// can no more listen in on a call than it can read an E2EE message.
var CallSignalTypes = map[string]bool{
	"call:invite":        true,
	"call:answer":        true,
	"call:ice_candidate": true,
	"call:reject":        true,
	"call:end":           true,
}

// handleCallSignal validates and relays a call signaling message straight to
// its one intended recipient (BroadcastToUser), and keeps CallLog in sync so
// call history ("Missed call", "Answered", duration) works without the
// server ever needing to understand the call's media.
func handleCallSignal(hub *Hub, fromUserID uuid.UUID, wsMsg models.WebSocketMessage) {
	data, ok := wsMsg.Data.(map[string]interface{})
	if !ok {
		return
	}

	toUserIDStr, _ := data["to_user_id"].(string)
	toUserID, err := uuid.Parse(toUserIDStr)
	if err != nil {
		log.Printf("call signal %s: invalid to_user_id %q", wsMsg.Type, toUserIDStr)
		return
	}

	callIDStr, _ := data["call_id"].(string)

	switch wsMsg.Type {
	case "call:invite":
		chatID, _ := data["chat_id"].(string)
		if !areChatParticipants(fromUserID, toUserID, chatID) {
			log.Printf("call:invite rejected: %s and %s are not both participants of chat %s", fromUserID, toUserID, chatID)
			return
		}

		if callIDStr == "" {
			callIDStr = uuid.NewString()
			data["call_id"] = callIDStr
			wsMsg.Data = data
		}
		callID, err := uuid.Parse(callIDStr)
		if err != nil {
			return
		}

		database.DB.Create(&models.CallLog{
			ID:        callID,
			ChatID:    chatID,
			CallerID:  fromUserID,
			CalleeID:  toUserID,
			Status:    "ringing",
			StartedAt: time.Now(),
		})

		if !hub.IsUserOnline(toUserID) {
			database.DB.Model(&models.CallLog{}).Where("id = ?", callID).Update("status", "missed")
			sendCallEventTo(hub, fromUserID, "call:end", map[string]interface{}{
				"call_id": callIDStr,
				"reason":  "offline",
			})
			return
		}

	case "call:answer":
		database.DB.Model(&models.CallLog{}).Where("id = ?", callIDStr).Updates(map[string]interface{}{
			"status":       "answered",
			"connected_at": time.Now(),
		})

	case "call:reject":
		now := time.Now()
		database.DB.Model(&models.CallLog{}).Where("id = ?", callIDStr).Updates(map[string]interface{}{
			"status":   "rejected",
			"ended_at": now,
		})

	case "call:end":
		finalizeCallLog(callIDStr)
	}

	out, err := json.Marshal(wsMsg)
	if err != nil {
		return
	}
	if err := hub.BroadcastToUser(toUserID, out); err != nil {
		// Callee disconnected mid-call (or between invite and answer) -
		// report back to the caller instead of leaving them hanging with a
		// call that will never ring or connect.
		if wsMsg.Type == "call:invite" || wsMsg.Type == "call:ice_candidate" {
			finalizeCallLog(callIDStr)
			sendCallEventTo(hub, fromUserID, "call:end", map[string]interface{}{
				"call_id": callIDStr,
				"reason":  "unreachable",
			})
		}
	}
}

// finalizeCallLog closes out a CallLog row when a call ends, computing talk
// time from connected_at when the call was actually answered, or marking it
// missed if it never got past ringing.
func finalizeCallLog(callIDStr string) {
	callID, err := uuid.Parse(callIDStr)
	if err != nil {
		return
	}

	var call models.CallLog
	if err := database.DB.Where("id = ?", callID).First(&call).Error; err != nil {
		return
	}
	if call.Status == "ended" || call.Status == "missed" || call.Status == "rejected" {
		return // already finalized
	}

	now := time.Now()
	updates := map[string]interface{}{"ended_at": now}
	if call.ConnectedAt != nil {
		updates["status"] = "ended"
		updates["duration_sec"] = int(now.Sub(*call.ConnectedAt).Seconds())
	} else {
		updates["status"] = "missed"
	}
	database.DB.Model(&models.CallLog{}).Where("id = ?", callID).Updates(updates)
}

// sendCallEventTo builds and relays a call:* event the server itself
// originates (e.g. "the callee is offline"), rather than one relayed
// verbatim from the other participant.
func sendCallEventTo(hub *Hub, toUserID uuid.UUID, eventType string, data map[string]interface{}) {
	wsMsg := models.WebSocketMessage{
		Type:      eventType,
		Data:      data,
		Timestamp: time.Now(),
	}
	out, err := json.Marshal(wsMsg)
	if err != nil {
		return
	}
	_ = hub.BroadcastToUser(toUserID, out)
}

// areChatParticipants checks that both users are (still) active participants
// of the same direct chat - a call, like a message, shouldn't be dialable
// between users who don't actually share a conversation.
func areChatParticipants(userA, userB uuid.UUID, chatID string) bool {
	if chatID == "" {
		return false
	}
	var count int64
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id IN ? AND left_at IS NULL", chatID, []uuid.UUID{userA, userB}).
		Count(&count)
	return count == 2
}
