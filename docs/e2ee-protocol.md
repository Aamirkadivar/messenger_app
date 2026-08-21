# E2EE Protocol Specification

## Protocol version 1

### Direct
- Alg: NaCl `crypto_box` (X25519 + XSalsa20-Poly1305)
- Content: `hex(nonce[24] || ciphertext)` for JSON text; raw bytes for media
- Identity: one X25519 keypair per user, vault-backed, shared across devices
- `encryption_version = 1` (historical; new sends use v2)

### Group Sender Keys
- Membership change → server increments `chats.key_epoch`
- Sender generates `crypto_secretbox` key, wraps to each member with pairwise box
- Message carries `key_version`
- Clients keep **all** own and peer versions they have seen (disk + vault), capped per group

### Vault plaintext (JSON, `format_version = 1`)

```
identity_pub_hex, identity_priv_hex
own_sender_keys:  "chatId|version" → hex
peer_sender_keys: "chatId|senderId|version" → hex   (omitempty)
peer_pubs:        chatId → hex                      (omitempty)
suite_ids, protocol_version
```

Not CBOR.

## Protocol version 2 (direct FS — see `e2ee-protocol-v2.md`)

- Direct: ephemeral X25519 `crypto_box`; `encryption_version = 2`
- Keep v1 decrypt forever
- Groups unchanged
- Direct v3 Double Ratchet: `e2ee-protocol-v3.md`

## Inner plaintext envelope (EM1)

Before NaCl, clients wrap plaintext as:

```
magic "EM1\n" (4 bytes) || u16be metaLen || UTF-8 JSON {"fn","fwd","dur","sz","th","fu"} || payload
```

Direct v3 fan-out (FN1) encrypts a copy per live recipient/own device (`chatId|deviceId` sessions) on Android and Windows send. Legacy single-blob v3 still decrypts. Two devices sending at once no longer share one sending chain.

Unknown / legacy blobs decrypt as raw payload.

## Backend (opaque only)

```
GET/PUT  /api/v1/e2ee/vault
GET      /api/v1/e2ee/vault/versions
GET/POST /api/v1/e2ee/devices
DELETE   /api/v1/e2ee/devices/:device_id
POST     /api/v1/e2ee/devices/:device_id/revoke
POST     /api/v1/e2ee/pairing …
POST     /messages   # encrypted:false → 400 encryption_required
```

Ownership from JWT subject. Optimistic concurrency on `vault_version` / `expected_version`.

Reference: `back-end/e2ee/`. Vectors: `test-vectors/e2ee/` (`go test ./e2ee/`).
