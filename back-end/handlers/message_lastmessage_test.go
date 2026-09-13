package handlers

import (
	"encoding/json"
	"testing"
	"time"
)

// The chat-list preview decrypts last_message. For MLS (v6) the client must be
// able to tell its OWN row from a peer's: handing its own ciphertext to OpenMLS
// earns "Cannot decrypt own messages" on every refresh, because the sending
// leaf's key is dropped at encrypt time for forward secrecy.
//
// That decision needs sender_device_id on the wire, which MessageResp did not
// carry. This pins the JSON contract so the field cannot be dropped or renamed.
func TestLastMessageCarriesSenderDeviceID(t *testing.T) {
	const deviceID = "5a454f70-f707-4c9b-862e-2ae24b227d00"

	resp := MessageResp{
		ID:                "11111111-1111-4111-8111-111111111111",
		SenderID:          "22222222-2222-4222-8222-222222222222",
		Content:           "deadbeef",
		Encrypted:         true,
		EncryptionVersion: 6,
		SenderDeviceID:    deviceID,
		CreatedAt:         time.Unix(0, 0).UTC(),
	}

	raw, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	v, ok := got["sender_device_id"]
	if !ok {
		t.Fatalf("last_message is missing sender_device_id; the chat-list preview "+
			"cannot identify its own MLS rows without it. keys=%v", keysOf(got))
	}
	if v != deviceID {
		t.Fatalf("sender_device_id = %v, want %s", v, deviceID)
	}

	// Additive only: the fields the client already relies on must survive.
	for _, k := range []string{"id", "sender_id", "content", "encrypted", "encryption_version", "key_version", "created_at"} {
		if _, ok := got[k]; !ok {
			t.Errorf("existing field %q disappeared from last_message", k)
		}
	}
}

func keysOf(m map[string]any) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
