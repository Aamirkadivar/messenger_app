# E2EE Architecture (Phase 0 Audit + Phase 1 Target)

Status: **Audit complete. Implementation not started.**  
Date: 2026-08-11  
Scope: `back-end/`, `application_android/`, `windows_app/` (no iOS client in repo)

---

## 1. Current E2EE architecture

The messenger uses a **client-side NaCl/libsodium** design. The Go backend is intentionally a **dumb relay**: it stores and forwards ciphertext and encrypted sender-key blobs; it does not decrypt message bodies on the live path.

| Layer | Role today |
|-------|------------|
| Account auth | Email + bcrypt password → JWT access + refresh. **No 2FA.** |
| E2EE identity | One X25519 (`crypto_box`) keypair per account, device-local private key |
| Direct messages | Pairwise `crypto_box` (X25519 + XSalsa20-Poly1305) |
| Group messages | WhatsApp/Signal-style **Sender Keys** (`crypto_secretbox`) wrapped pairwise to members |
| Server storage | `users.public_key`, `messages.encrypted_content`, `group_sender_keys.encrypted_key`, `chats.key_epoch` |

Wire format (text): `hex(nonce[24] || ciphertext)`. Binary media: raw `nonce || ciphertext`.

Platforms today: **Android** (LazySodium), **Windows** (libsodium). **No iOS / Web client** in this repository.

---

## 2. Current key hierarchy

```
Account password ──► bcrypt hash (auth only; NOT E2EE)
JWT session ───────► API / WebSocket auth only

E2EE identity keypair (X25519)     ← generated on client when missing
  ├── public  → POST /crypto/public-key → users.public_key (ONE per user)
  └── private → device-local only

Direct chat:
  crypto_box(recipient_pub, sender_priv)

Group:
  SenderKey_v (secretbox key for key_version ≈ key_epoch)
    └── wrapped to each member via crypto_box(member_pub, sender_priv)
    └── stored server-side as opaque blob in group_sender_keys
```

**Missing today (target architecture):** E2EE Master Key, Vault Encryption Key, password-derived KEK (Argon2id), Recovery Key, per-device identity keys, encrypted vault, vault versioning.

---

## 3. Current device model

- **One identity public key per user** (`users.public_key`), overwritten on new-device publish.
- **One live WebSocket** per user id (`Hub.Clients[userID]`); second connection replaces the first.
- **No** `device_id`, device list, revocation, or multi-device ciphertext fan-out.
- New login without local private key → **new keypair** → overwrite server public key → **key takeover**.

---

## 4. Current message encryption flow

1. Client ensures local identity keys; publishes public key if needed.
2. Direct: load peer `public_key` from chat list / API; `crypto_box_easy`; send `{ encrypted: true, content: hex(...) }`.
3. Group: ensure own Sender Key for current `key_epoch`; encrypt with `crypto_secretbox`; set `key_version`; publish wrapped keys to members if rotated.
4. Backend stores opaque `encrypted_content` and broadcasts over WebSocket.

**Fallback weakness:** if keys are missing, clients may send **plaintext** (`encrypted: false`).

---

## 5. Current message decryption flow

1. Load ciphertext from REST or WebSocket.
2. Direct: `crypto_box_open_easy` with peer pub + my priv.
3. Group: fetch sender-key blob for `(sender_id, key_version)` if needed; unwrap with crypto_box; `crypto_secretbox_open_easy`.
4. On failure → UI placeholder ("Encrypted message").

---

## 6. Current key persistence

| Platform | Private identity key | Own group sender keys | Peer sender keys |
|----------|----------------------|-----------------------|------------------|
| Android | Plain SharedPreferences hex | Prefs latest only (`version:hex`) | Memory after fetch |
| Windows | AppData `credentials.json` hex (DPAPI helpers unused) | Same JSON latest only | Memory after fetch |
| Server | **Also writes `users.private_key` on Register** via server `crypto.GenerateKeyPair` — unused by live clients but **violates "no private keys on backend"** |

No password wrap, no recovery key, no vault, no cloud backup of private material.

---

## 7. Why historical messages cannot decrypt on a new device

Hard requirement failure today:

1. Private keys never leave the old device.
2. New device generates a **new** X25519 pair and **overwrites** `users.public_key`.
3. Old direct ciphertext was sealed under the **old** identity; new private key cannot open it.
4. Group sender-key wraps were sealed to the **old** public key; own historical sender keys lived only on the old device (and disk keeps **latest version only**).
5. No vault / recovery path exists.

Ciphertext remains on the server; the new device simply lacks the matching secrets.

---

## 8. Security vulnerabilities / gaps

| ID | Issue | Severity |
|----|-------|----------|
| V1 | Server stores `users.private_key` at register | High (server-side secret material) |
| V2 | Android E2EE privkey in **plaintext** SharedPreferences | High |
| V3 | Windows keys in plain JSON; DPAPI unused | High |
| V4 | Single pubkey overwrite = silent multi-device break | High (availability of history) |
| V5 | Plaintext message fallback when keys missing | High (confidentiality) |
| V6 | No FS for direct chats (static identity box forever) | Medium (protocol property) |
| V7 | Own group sender key: latest only → can't decrypt own older versions after rotation | Medium |
| V8 | No 2FA | Medium (account takeover → key takeover) |
| V9 | Dead `DecryptMessageForUser` helper + server encrypt APIs in `back-end/crypto` | Low/Medium (attack surface / confusion) |
| V10 | Unused Android `MessageEncryption` AES stub (custom DH) still in DI | Low (must not be wired) |
| V11 | One WS slot / no device revocation | Medium |

**Custom protocol risk:** This is **not** Signal Double Ratchet / X3DH. It is a recognized **NaCl box + Sender Keys** construction. Risks are mainly **key management / multi-device / persistence**, not inventing ciphers. Do **not** replace primitives blindly; wrap and migrate state.

---

## 9. Compatibility constraints

- Preserve wire formats for existing ciphertext (hex box / raw box / secretbox + `key_version`).
- Preserve `key_epoch` membership rotation semantics.
- Preserve REST/WS message shapes where possible.
- Migration must import existing identity private key + historical sender keys into vault **before** discarding local copies.
- Android/Windows must stay byte-compatible; future iOS/Web must implement the **same** suite IDs.

---

## 10. Recommended migration strategy (high level)

**Do not invent a new cipher suite.** Keep libsodium `crypto_box` / `crypto_secretbox` for message traffic (protocol_version 1).

Add a **recoverable vault layer** beside it:

1. Client generates 256-bit **E2EE Master Key** (stable across password changes).
2. Random **Vault Encryption Key** wrapped by Master Key (envelope encryption; XChaCha20-Poly1305 or AES-256-GCM — choose one suite ID for all platforms).
3. **Vault** (versioned CBOR) holds: identity sk/pk, historical sender keys by version, known peer pubs needed for history, protocol/suite metadata.
4. Master Key wrapped by Argon2id(password) KEK **and** independently by Recovery Key KEK; ciphertext + salts/params stored on server as opaque blobs.
5. New device: login (+ future 2FA) → download vault ciphertext → unlock locally → restore keys → decrypt history → register **device** public key (multi-device) without destroying identity.
6. Stop writing `users.private_key` on register; clear existing rows in a controlled migration.
7. Multi-device: identity private key shared via vault; optional per-device signing/auth keys for revocation; message protocol stays identity-based until a later ratchet migration (explicit, not silent).

**Forward secrecy:** Document that protocol_v1 direct chat is **not** FS. Do not claim FS. Future protocol_v2 may adopt an established library (e.g. libsignal) — separate project phase.

**Conflict note:** Requirements demand historical decrypt on every authorized device **and** FS. With static box keys, sharing identity/history keys via vault enables multi-device history but **does not add FS**. Achieving both requires a protocol upgrade for *new* messages while keeping v1 keys in the vault for *old* messages. That is the honest migration path.

---

## Target layered concepts (must stay separate)

```
Account Authentication (password + 2FA + JWT)
  ≠ E2EE Identity (X25519 long-term)
  ≠ E2EE Master Key (256-bit root)
  ≠ Vault Encryption Key (envelope)
  ≠ Conversation / Sender Keys
  ≠ Message ciphertext
```

See also: `e2ee-key-hierarchy.md`, `e2ee-protocol.md`, `e2ee-migration.md`, `e2ee-threat-model.md`, `e2ee-recovery.md`, `e2ee-cross-platform.md`.