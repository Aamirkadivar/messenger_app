# E2EE Recovery

## Paths
1. **Password unlock** — Argon2id → KEK_pw → MK → vault (MK is the vault AEAD key).
2. **Recovery Key** — independent KEK_rk → MK → vault.
3. **Device pairing (QR-compatible code)** — new device creates a short-lived session with an ephemeral `crypto_box` public key; shows `mp1.<session>.<pub_hex>` as text and QR. Old unlocked device scans (Android) or pastes the code under Settings → Link a device, seals MK with a one-shot sender keypair, and uploads opaque ciphertext to `POST /e2ee/pairing/:id/complete`. New device polls `GET .../payload` (single-use consume). Server never sees MK plaintext.
4. **Password reset without recovery** — account auth may reset; **E2EE history unrecoverable**. No server master key.

## Password reset (forgot password)
1. `POST /auth/password-reset/start` with `{ "email" }` issues a 5-minute OTP (DEV: same 2FA bot / server log). Response is generic so emails cannot be enumerated; `challenge_id` is included when a user exists.
2. `POST /auth/password-reset/complete` with `{ challenge_id, code, new_password, totp_code? }` updates the **login** bcrypt hash only. The vault wrap is unchanged. If the account has TOTP enabled, `totp_code` (authenticator or unused backup code) is required. Wrong codes increment the challenge attempt counter (eight failures drop it).
3. Sign in with the new password. Password-derived KEK will fail; enter the **Recovery Key** to unwrap MK and rewrap under the new password.
4. Without the recovery key (and without an old unlocked device to pair), historical ciphertext stays on the server but this device cannot decrypt it.

## Password change
Old password unwraps MK; new password rewraps MK. Messages untouched.

## New device without old device
Login → (2FA) → download vault ciphertext → local unlock → restore → register device → decrypt history.

TOTP backup codes are issued once at enable and can be rotated while signed in (`POST /auth/2fa/totp/backup-codes` with password + authenticator/remaining backup code). Login 2FA challenges are dropped after eight failed attempts.

## Dev / test note
DEV 2FA and password-reset OTPs share the in-memory challenge store and the configured relay bot / server log.