# E2EE Protocol Specification

## Protocol version 1 (current, preserved)

### Direct
- Alg: NaCl `crypto_box` (X25519 + XSalsa20-Poly1305)
- Content encoding: `hex(nonce[24] || ciphertext)` for JSON text; raw bytes for media
- Identity: one X25519 keypair per user (to become vault-backed, multi-device shared)

### Group Sender Keys
- Membership change → server increments `chats.key_epoch`
- Sender generates `crypto_secretbox` key, wraps to each member with pairwise box
- Message carries `key_version` (= epoch used)
- Distribution table: `group_sender_keys`

### Serialization rules
- Never invent `key+nonce+ciphertext` string concat without length prefixes / structured fields
- Vault (future): versioned CBOR map with explicit `alg`, `kdf`, `nonce`, `ciphertext`, `vault_version`

## Protocol version 2 (future, not Phase 1 implementation)
- Evaluate maintained Signal-protocol library for FS on *new* messages
- Keep v1 keys in vault for historical decrypt
- Do not implement Double Ratchet from a paper by hand

## Backend API (opaque ciphertext only) — planned

```
POST/GET/PUT /api/v1/e2ee/vault
GET          /api/v1/e2ee/vault/versions
POST/GET     /api/v1/e2ee/devices
DELETE       /api/v1/e2ee/devices/{id}
POST         /api/v1/e2ee/devices/{id}/revoke
POST         /api/v1/e2ee/recovery/prepare
POST         /api/v1/e2ee/recovery/complete
```

Ownership from JWT subject only — never trust client-supplied `user_id`.
Optimistic concurrency on `vault_version`.