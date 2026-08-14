# E2EE Migration Plan

Status: **Done** for Android and Windows. New installs create a vault on first password login. Existing local identity keys are imported into the vault before relying on it.

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
