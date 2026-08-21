# E2EE Migration Plan

Status: **Code-present, NOT yet effective.** The client migration path
(`E2EEVaultRepository.kt`, `registerDevice`) is written, but the live database
holds **0 vaults and 0 devices** (see `e2ee-architecture.md` §1.5) — no client
has successfully created or uploaded a vault. So the flow below describes the
intended behaviour, not a completed migration. Activation must make this path
actually run and verify a vault row appears server-side.

Per the 2026-08-15 decision: the pre-existing 218 `encryption_version=1`
messages are **accepted as unrecoverable** (their identity key was already
replaced). Migration protects state from activation forward; it does not attempt
to resurrect the v1 backlog.

## Principles
- Never destroy old local keys before vault upload validates round-trip decrypt.
- Never require re-encrypting historical messages.
- `users.private_key` is dropped at migrate; clients never used it on the live path.

## What a first upgraded client did
1. Detect local identity sk/pk.
2. Collect own sender keys (all versions), peer sender keys, peer pubs.
3. Generate MK; AEAD-encrypt vault JSON (MK is the vault key).
4. Wrap MK with Argon2id(password); show Recovery Key once; wrap MK with recovery KEK.
5. `PUT /e2ee/vault` with `expected_version = 0`.
6. Download → unlock → compare identity sk.
7. Subsequent devices: login → unlock vault or pair → restore → register `device_id`.

## Rollback
If unlock fails: keep using local keys; surface error; do not delete.

## FS vs history
Sharing identity keys via vault preserves history but does not give recipient-side forward secrecy. Direct v2 adds sender-side FS for **new** messages only. Full Double Ratchet is a separate track.
