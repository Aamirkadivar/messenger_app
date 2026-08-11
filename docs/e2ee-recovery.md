# E2EE Recovery

## Paths
1. **Password unlock** — Argon2id → KEK_pw → MK → VEK → vault.
2. **Recovery Key** — independent KEK_rk → MK → vault.
3. **QR device migration** — ephemeral X25519 + AEAD transfer of MK or vault unlock material; short-lived, single-use, bound to devices; no plaintext MK through backend.
4. **Password reset without recovery** — account auth may reset; **E2EE history unrecoverable**. No server master key.

## Password change
Old password unwraps MK; new password rewraps MK. Messages untouched.

## New device without old device
Login → (2FA) → download vault ciphertext → local unlock → restore → register device → decrypt history.

## Dev / test note
2FA is **not implemented** as of audit date. See temporary DEV OTP relay plan in `e2ee-cross-platform.md` § Development aids.