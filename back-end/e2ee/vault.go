package e2ee

import (
	"encoding/json"
	"fmt"
)

// VaultPlaintext is the portable E2EE state carried inside the encrypted vault.
// Wire encoding is JSON (not CBOR) so Android LazySodium / Windows Qt clients
// can encode/decode without a second CBOR stack. Field names are stable.
type VaultPlaintext struct {
	FormatVersion   int               `json:"format_version"`
	ProtocolVersion int               `json:"protocol_version"`
	SuiteIDs        []string          `json:"suite_ids"`
	IdentityPubHex  string            `json:"identity_pub_hex"`
	IdentityPrivHex string            `json:"identity_priv_hex"`
	// OwnSenderKeys: "chatId|version" -> key hex (historical + current).
	OwnSenderKeys map[string]string `json:"own_sender_keys,omitempty"`
	// PeerSenderKeys: "chatId|senderId|version" -> key hex (other members).
	PeerSenderKeys map[string]string `json:"peer_sender_keys,omitempty"`
	// PeerPubs: chatId -> peer public key hex (for historical direct decrypt).
	PeerPubs map[string]string `json:"peer_pubs,omitempty"`
	// DirectRatchets: chatId -> Double Ratchet session JSON (v3).
	DirectRatchets map[string]string `json:"direct_ratchets,omitempty"` // chatId or chatId|deviceId
	// Extra opaque migration metadata (string map).
	Meta map[string]string `json:"meta,omitempty"`
}

// EncodeVaultPlaintext JSON-encodes vault contents.
func EncodeVaultPlaintext(v VaultPlaintext) ([]byte, error) {
	if v.FormatVersion == 0 {
		v.FormatVersion = VaultFormatV1
	}
	if v.ProtocolVersion == 0 {
		v.ProtocolVersion = ProtocolVersionV1
	}
	return json.Marshal(v)
}

// DecodeVaultPlaintext JSON-decodes vault contents.
func DecodeVaultPlaintext(b []byte) (VaultPlaintext, error) {
	var v VaultPlaintext
	if err := json.Unmarshal(b, &v); err != nil {
		return v, err
	}
	if v.FormatVersion != VaultFormatV1 {
		return v, fmt.Errorf("unsupported vault format %d", v.FormatVersion)
	}
	return v, nil
}

// SealVault envelope-encrypts vault plaintext with VEK.
// AAD binds userID + vaultVersion + suite.
func SealVault(vek []byte, plaintext []byte, userID string, vaultVersion int) ([]byte, error) {
	aad := []byte(fmt.Sprintf("vault|%s|%d|%s", userID, vaultVersion, SuiteVaultAEAD))
	return SealXChaCha(vek, plaintext, aad)
}

// OpenVault decrypts a SealVault blob.
func OpenVault(vek []byte, sealed []byte, userID string, vaultVersion int) ([]byte, error) {
	aad := []byte(fmt.Sprintf("vault|%s|%d|%s", userID, vaultVersion, SuiteVaultAEAD))
	return OpenXChaCha(vek, sealed, aad)
}

// MasterKeyAAD is AAD for wrapping the E2EE Master Key.
func MasterKeyAAD(userID, purpose string) []byte {
	return []byte(fmt.Sprintf("mk|%s|%s|%s", userID, purpose, SuiteVaultAEAD))
}

// VEKAAD is AAD for wrapping the Vault Encryption Key under MK.
func VEKAAD(userID string) []byte {
	return []byte(fmt.Sprintf("vek|%s|%s", userID, SuiteVaultAEAD))
}
