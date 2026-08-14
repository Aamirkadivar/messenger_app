# E2EE Protocol Version 2 (Direct-message forward secrecy)

Status: **Implemented for direct chats** (Android + Windows). Groups remain Sender Keys (v1).

## Why not libsignal Double Ratchet in this phase

- Docs require a **maintained** library, not a hand-rolled ratchet.
- `org.signal:libsignal-*` is **AGPL-3.0** and unsupported outside Signal; Windows MinGW has no first-class binding matching this app's Qt/MinGW stack.
- Full X3DH + DR + multi-device sender keys is a separate product track.

Protocol v2 here uses **ephemeral NaCl crypto_box** (libsodium), available on every client we ship.

## Construction (direct text + binary)

Sender:
1. Generate ephemeral X25519 keypair `(eph_sk, eph_pk)`.
2. `ct = crypto_box_easy(plaintext, nonce, recipient_identity_pk, eph_sk)`.
3. Discard `eph_sk`.
4. Wire: `hex(eph_pk[32] || nonce[24] || ct)` for JSON text; raw bytes for media.
5. Set `encryption_version = 2` on the message.

Recipient:
1. Parse `eph_pk || nonce || ct`.
2. `crypto_box_open_easy(..., eph_pk, recipient_identity_sk)`.

Suite id: `suite:nacl-eph-box-xsalsa20poly1305-v2`

## Security properties

| Property | v1 static box | v2 ephemeral box |
|----------|---------------|------------------|
| Confidentiality | yes | yes |
| Sender auth (cryptographic) | yes (sender identity sk) | no — relies on authenticated session + `sender_id` (same as today's server trust for identity of ciphertext origin) |
| FS if **sender** identity later stolen | no | **yes** (eph_sk discarded) |
| FS if **recipient** identity stolen | no | no (recipient sk still opens history) |
| Post-compromise security | no | no |

Honest scope: **sender-side forward secrecy** for new direct messages. Recipient-side FS and PCS need a ratchet library (future, license-aware).

## Compatibility

- Clients **always decrypt** v1 (`encryption_version` missing/0/1) with the static pairwise box.
- New sends use v2 when E2EE keys are available.
- Group messages unchanged (`encryption_version` stays 1; `key_version` is Sender Key epoch).

## Server

Opaque relay only. Persists and returns `encryption_version`. Never decrypts.