package websocket

import (
	"encoding/json"
	"log"
	"time"

	"messenger-app/database"
	"messenger-app/models"

	"github.com/google/uuid"
)

// CallSignalTypes are the WS message types for calls. Pairwise types
// (invite/answer/ICE/reject/end/media) are relayed 1:1. Group session types
// (group_invite/join/leave) are fanned out to chat members. Only signaling
// ever touches the server - media is DTLS-SRTP peer-to-peer (full mesh for
// group calls, one PeerConnection per remote participant).
var CallSignalTypes = map[string]bool{
	"call:invite":        true,
	"call:answer":        true,
	"call:ice_candidate": true,
	"call:reject":        true,
	"call:end":           true,
	"call:media":         true,
	"call:group_invite":  true,
	"call:group_join":    true,
	"call:group_leave":   true,
}

var groupCallSignalTypes = map[string]bool{
	"call:group_invite": true,
	"call:group_join":   true,
	"call:group_leave":  true,
}

// MaxGroupCallParticipants caps a mesh group call. Full mesh is O(N²) media
// paths; Windows software Opus/VP8 and mobile upload make larger rooms
// impractical without an SFU.
const MaxGroupCallParticipants = 4

// handleCallSignal validates and relays call signaling. Pairwise messages go
// to one recipient; group session messages fan out to other chat members.
func handleCallSignal(hub *Hub, from *Client, wsMsg models.WebSocketMessage) {
	if from == nil {
		return
	}
	fromUserID := from.UserID
	data, ok := wsMsg.Data.(map[string]interface{})
	if !ok {
		return
	}

	if groupCallSignalTypes[wsMsg.Type] {
		handleGroupCallSignal(hub, from, wsMsg, data)
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

		isVideo, _ := data["video"].(bool)
		database.DB.Create(&models.CallLog{
			ID:        callID,
			ChatID:    chatID,
			CallerID:  fromUserID,
			CalleeID:  toUserID,
			Status:    "ringing",
			IsVideo:   isVideo,
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
		if wsMsg.Type == "call:invite" || wsMsg.Type == "call:ice_candidate" {
			finalizeCallLog(callIDStr)
			sendCallEventTo(hub, fromUserID, "call:end", map[string]interface{}{
				"call_id": callIDStr,
				"reason":  "unreachable",
			})
		}
	}

	switch wsMsg.Type {
	case "call:answer":
		notifySiblings(hub, from, "call:end", map[string]interface{}{
			"call_id": callIDStr,
			"reason":  "answered_elsewhere",
		})
	case "call:reject":
		notifySiblings(hub, from, "call:end", map[string]interface{}{
			"call_id": callIDStr,
			"reason":  "rejected_elsewhere",
		})
	case "call:end":
		reason, _ := data["reason"].(string)
		if reason == "" {
			reason = "ended_elsewhere"
		}
		notifySiblings(hub, from, "call:end", map[string]interface{}{
			"call_id": callIDStr,
			"reason":  reason,
		})
	}
}

func notifySiblings(hub *Hub, from *Client, typ string, data map[string]interface{}) {
	if from == nil {
		return
	}
	out, err := json.Marshal(models.WebSocketMessage{
		Type:      typ,
		Data:      data,
		Timestamp: time.Now(),
	})
	if err != nil {
		return
	}
	hub.BroadcastToUserExcept(from.UserID, from.ID, out)
}

// handleGroupCallSignal fans a group-session event to other members of the
// chat (or an explicit participant_ids list). Pairwise SDP/ICE still uses
// call:invite etc. under a shared group_call_id on the clients.
func handleGroupCallSignal(hub *Hub, from *Client, wsMsg models.WebSocketMessage, data map[string]interface{}) {
	if from == nil {
		return
	}
	fromUserID := from.UserID
	chatID, _ := data["chat_id"].(string)
	if chatID == "" || !isChatMember(fromUserID, chatID) {
		log.Printf("%s rejected: %s is not a member of chat %s", wsMsg.Type, fromUserID, chatID)
		return
	}

	callIDStr, _ := data["call_id"].(string)
	if callIDStr == "" {
		callIDStr = uuid.NewString()
		data["call_id"] = callIDStr
	}
	data["from_user_id"] = fromUserID.String()
	wsMsg.Data = data

	var targets []uuid.UUID
	switch wsMsg.Type {
	case "call:group_invite":
		// Prefer the caller's capped roster when present (clients truncate to
		// MaxGroupCallParticipants). Fall back to every active member.
		members := parseUUIDList(data["participant_ids"])
		if len(members) == 0 {
			members = activeMemberIDs(chatID)
		}
		if len(members) > MaxGroupCallParticipants {
			log.Printf("call:group_invite rejected: %d participants (max %d)", len(members), MaxGroupCallParticipants)
			sendCallEventTo(hub, fromUserID, "call:group_leave", map[string]interface{}{
				"call_id":      callIDStr,
				"chat_id":      chatID,
				"from_user_id": fromUserID.String(),
				"reason":       "too_large",
			})
			return
		}
		for _, id := range members {
			if id != fromUserID {
				targets = append(targets, id)
			}
		}
	case "call:group_join", "call:group_leave":
		if ids := parseUUIDList(data["participant_ids"]); len(ids) > 0 {
			for _, id := range ids {
				if id != fromUserID {
					targets = append(targets, id)
				}
			}
		} else {
			for _, id := range activeMemberIDs(chatID) {
				if id != fromUserID {
					targets = append(targets, id)
				}
			}
		}
	}

	out, err := json.Marshal(wsMsg)
	if err != nil {
		return
	}
	for _, to := range targets {
		if !hub.IsUserOnline(to) {
			continue
		}
		if err := hub.BroadcastToUser(to, out); err != nil {
			log.Printf("%s fanout to %s failed: %v", wsMsg.Type, to, err)
		}
	}

	if wsMsg.Type == "call:group_join" || wsMsg.Type == "call:group_leave" {
		reason := "answered_elsewhere"
		if wsMsg.Type == "call:group_leave" {
			reason, _ = data["reason"].(string)
			if reason == "" {
				reason = "ended_elsewhere"
			}
		}
		notifySiblings(hub, from, "call:group_leave", map[string]interface{}{
			"call_id":      callIDStr,
			"chat_id":      chatID,
			"from_user_id": fromUserID.String(),
			"reason":       reason,
		})
	}
}

func parseUUIDList(raw interface{}) []uuid.UUID {
	arr, ok := raw.([]interface{})
	if !ok {
		return nil
	}
	out := make([]uuid.UUID, 0, len(arr))
	for _, v := range arr {
		s, _ := v.(string)
		id, err := uuid.Parse(s)
		if err != nil {
			continue
		}
		out = append(out, id)
	}
	return out
}

func isChatMember(userID uuid.UUID, chatID string) bool {
	if chatID == "" {
		return false
	}
	var count int64
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND user_id = ? AND left_at IS NULL", chatID, userID).
		Count(&count)
	return count == 1
}

func activeMemberIDs(chatID string) []uuid.UUID {
	var ids []uuid.UUID
	database.DB.Model(&models.ChatParticipant{}).
		Where("chat_id = ? AND left_at IS NULL", chatID).
		Pluck("user_id", &ids)
	return ids
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

// areChatParticipants checks that both users are active participants of the
// same chat (direct or group). Pairwise mesh edges inside a group call use
// this the same way 1:1 invites do.
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
