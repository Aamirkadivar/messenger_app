# E2EE Architecture

Status: **Code-complete but DORMANT.** The vault/device/pairing system is fully
written and wired into routes, but has never run in production. iOS / Web are not
in this repo.
Date: 2026-08-14 (design); operational state re-audited **2026-08-15**.
Scope: `back-end/`, `application_android/`, `windows_app/`

Trust the code and the measured-state section below. The design sections that
follow are accurate to the source; the *operational* claims in the original
draft ("Implemented", "that world is gone", most "Mitigated" rows) were
aspirational — they describe what the code would do **once a vault exists**, not
the live system. See §1.5.

---

## 1.5 Current operational state (measured 2026-08-15)

Direct database inspection of the running `messenger` DB:

```
e2_ee_vaults:      0 rows
e2_ee_devices:     0 rows
e2_ee_pairing:     0 rows
messages:        218 × encryption_version=1 (static box, no device id)
                   9 × encryption_version=3
```

**Nothing has ever populated the vault, device, or pairing tables.** Every
security property that depends on them is therefore inert:

1. **Identity-overwrite lock — FIXED 2026-08-15 (pending backend restart).**
   Previously `identityChangeBlocked` (`handlers/crypto.go`) only blocked a
   changed `public_key` when `hasVault || hasLiveDevice`; with both tables empty
   both flags were false, so **any login could silently replace the account
   identity** — observed live when a user's `public_key` was rewritten as a
   second client signed in. The guard now blocks *any* change to an
   already-published identity regardless of vault/device state; the only way to
   replace an established key is an explicit, user-acknowledged reset
   (`reset:true`, audit-logged). Regression pinned in
   `handlers/crypto_test.go`. **The running server must be restarted to load
   this** — the live process predates the fix.
2. **v1 backlog is cryptographically lost.** The 218 `encryption_version=1`
   messages were sealed with static `crypto_box(peerPub, myIdentityPriv)` to an
   identity that has since been replaced (see #1). No device holds the matching
   private key; no vault preserved it. Per the 2026-08-15 decision, this loss is
   **accepted** — the goal is to protect everything from activation forward, not
   to recover the orphaned backlog.
3. **New-device recovery cannot work yet.** The restore flow reads a vault that
   does not exist. Until clients create + upload a vault and register a device on
   login, "sign in on a new phone → history restored" fails by construction.

**Activation deadlock to resolve:** the identity lock needs a vault to exist, but
the vault never gets created, so every fresh login re-orphans history. Activation
must make the client **create + upload the vault and register the device before
(or atomically with) publishing an identity key**, so the lock has something to
protect from the very first login.

**Known design simplification vs. the target spec (§5 "envelope encryption"):**
the persisted schema wraps the **Master Key** directly (`pw_wrapped_master`,
`rk_wrapped_master`) and seals the vault with the MK as the AEAD key. The
separate **Vault Encryption Key (VEK)** layer is present in code
(`e2ee.VEKAAD`, the `vek` parameter of `SealVault`) but not in the stored model,
so in practice `vek == MK`. This is cryptographically sound (MK is a 256-bit
CSPRNG key), but it means rotating the vault key requires re-deriving both KEK
wraps rather than just re-wrapping a VEK. Flagged, not yet changed.

---

## 1. What ships today

The messenger uses **client-side NaCl/libsodium**. The Go backend is a **dumb relay**: ciphertext, sender-key wraps, and vault blobs only. It does not decrypt message bodies, attachments, or call audio.

| Layer | Role |
|-------|------|
| Account auth | Email + bcrypt → JWT. Optional TOTP (RFC 6238). DEV OTP relay still used when TOTP is off and `DEV_2FA_ENABLED`. Forgot-password resets login bcrypt, not the vault. |
| E2EE identity | One X25519 (`crypto_box`) keypair per account, shared across devices via the vault |
| Direct v1 | Pairwise static `crypto_box` (`encryption_version=1`) |
| Direct v2 | Ephemeral box `eph_pk(32)\|\|nonce(24)\|\|ct` (`encryption_version=2`) |
| Direct v3 | Double Ratchet (`encryption_version=3`, default for new direct sends) |
| Groups | Sender Keys (`crypto_secretbox`); `Chat.KeyEpoch` / `Message.KeyVersion` |
| Vault | JSON AEAD (XChaCha20-Poly1305) under the E2EE Master Key; password + recovery wraps; QR/text pairing; `direct_ratchets` for v3 sessions |
| Devices | `e2ee_devices` registry; revoke kicks WS and blocks API via `X-Device-Id` |
| Server storage | `users.public_key`, opaque `messages.encrypted_content`, `group_sender_keys`, `e2ee_vaults` |

Wire (text): v1 `hex(nonce\|\|ct)`; v2 `hex(eph_pk\|\|nonce\|\|ct)`. Binary media: the same layout as raw bytes. Inner plaintext uses an `EM1` envelope so filenames, duration, thumbnail, and media URLs are not stored in server columns.

Platforms: **Android** (LazySodium), **Windows** (libsodium). **No iOS / Web client.**

---

## 2. Key hierarchy (as implemented)

```
Account password ──► bcrypt (auth only)
                 └──► Argon2id (m=64MiB, t=3, p=1) ──► KEK_pw ──► wrap MK

Recovery key ──────► HKDF-SHA256 ──► KEK_rk ──► wrap MK

E2EE Master Key (MK, 256-bit) ──► XChaCha20-Poly1305 ──► vault JSON
  (MK is also the vault AEAD key; a separate VEK layer was not shipped)

Vault JSON holds:
  identity_pub_hex / identity_priv_hex
  own_sender_keys:  "chatId|version" → hex
  peer_sender_keys: "chatId|senderId|version" → hex
  peer_pubs:        chatId → peer identity pub hex

Direct:
  v1 crypto_box(recipient_identity_pub, sender_identity_priv)
  v2 crypto_box(recipient_identity_pub, ephemeral_sk) + eph_pk on the wire

Group:
  SenderKey_v (secretbox) wrapped pairwise to each member
```

Identity private keys never leave the client except inside vault ciphertext or a pairing MK wrap. `users.private_key` has been **dropped**.

---

## 3. Device model

- Identity public key remains **one per user** (`users.public_key`). New devices restore the same pair from the vault. `POST /crypto/public-key` with a *different* key is **409 identity_locked** if a vault or live device already exists.
- WebSocket hub allows **multiple live connections per user** (phone + desktop). The same `device_id` replaces its previous socket. Revoke still kicks that device.
- Each client registers `device_id` (`POST /e2ee/devices`). Settings can list and **revoke** other devices. Revoked devices get API `device_revoked` and a WS close.
- Pairing: `mp1.<session>.<pub_hex>` (text + QR; scan or paste on Android and Windows). Old device seals MK; new device consumes payload once.

---

## 4. Encrypt / decrypt

**Send:** fail closed if keys are missing. `POST /messages` with `encrypted: false` → 400 `encryption_required`.

**Direct:** prefer v3 Double Ratchet; still decrypt v2 and v1.

**Group:** own Sender Key for current `key_epoch`; persist own and peer keys by version (disk + vault). Decrypt uses `key_version`, not “latest only”.

**Failure:** UI placeholder (“Encrypted message”), not plaintext fallback.

---

## 5. At-rest secrets

| Platform | Identity priv | Own / peer Sender Keys | Session tokens |
|----------|---------------|------------------------|----------------|
| Android | Keystore AES-GCM (`ks1:`) + vault | Same wrap; prefs keys `group_senderkey_*` / `peer_senderkey_*` | Refresh token wrapped |
| Windows | DPAPI user-scope `credentials.json` (`DP1\n`) + vault | Same store | DPAPI |
| Server | Not stored | Opaque wraps only | JWT secrets in env |

---

## 6. New-device history

1. Sign in → download vault → unlock with password, recovery key, or pairing from an old device.
2. Restore identity + sender-key maps + peer pubs.
3. Register this `device_id`. Do **not** mint a new identity and overwrite `users.public_key`.
4. Password reset without recovery/pairing: account works; **history stays locked**.

---

## 7. Gaps that remain

| ID | Issue | Severity |
|----|-------|----------|
| V1 | Server `users.private_key` | **Closed** — column dropped |
| V2 / V3 | Client at-rest identity | Mitigated (Keystore / user DPAPI) |
| V4 | New device overwriting identity | **FIXED 2026-08-15** (needs backend restart) — guard now blocks any change to an established identity independent of vault/device state; only an explicit acknowledged reset can rotate. Regression test `handlers/crypto_test.go`. |
| V5 | Cleartext send | Mitigated (API reject + client fail-closed) |
| V6 | Direct chat FS | **Mitigated** — v3 Double Ratchet (identity SK does not open post-ratchet messages); v2 still sender-only |
| V7 | Historical own Sender Keys | Mitigated (disk + vault) |
| V8 | No production 2FA | **Mitigated** — TOTP + backup codes; password reset also requires TOTP when enabled |
| V11 | One WS slot / revoke | **Mitigated** — multi-device hub; revoke still kicks that device |
| — | Peer Sender Keys only in RAM | Mitigated (disk Phase 20, vault Phase 21) |
| — | iOS / Web | Not in repo |
| — | Full Double Ratchet | **Mitigated** — libsodium DR (`encryption_version=3`); not AGPL libsignal |
| — | Windows camera QR scan | Mitigated (camera + paste) |
| — | Multi-device v3 vault | Mitigated — 409 merge by `seq`; pull on reconnect; **FN1** fan-out per device (`GET /e2ee/chats/:id/devices`) so simultaneous send does not share one chain |
| — | Peer key substitution | Mitigated — TOFU pin; QR/text safety-number verify (`sn1.`) |
| — | Filename / forward-name / duration / size / thumbnail / media URL on server | Mitigated — `EM1` (`fn`,`fwd`,`dur`,`sz`,`th`,`fu`); API columns empty/zero on new sends |
| — | Cross-NAT calls | Needs public TURN / `TURN_EXTERNAL_IP` |
| — | Windows video loss | Mitigated (RTCP NACK responder + PLI keyframe) |

**Custom protocol:** NaCl box + Sender Keys, not Signal. Do not replace primitives; keep suite IDs.

---

## 8. Compatibility

- Keep v1 ciphertext decryptable forever.
- Keep `key_epoch` membership rotation.
- Vault is **JSON** (not CBOR). Field names are stable; unknown fields ignored; `peer_sender_keys` and `direct_ratchets` omitempty.
- Test vectors: `test-vectors/e2ee/` — `cd back-end && go test ./e2ee/`.

See also: `e2ee-key-hierarchy.md`, `e2ee-protocol.md`, `e2ee-protocol-v2.md`, `e2ee-migration.md`, `e2ee-threat-model.md`, `e2ee-recovery.md`, `e2ee-cross-platform.md`.
