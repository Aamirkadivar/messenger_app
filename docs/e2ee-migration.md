# E2EE Migration Plan

## Principles
- Never destroy old local keys before vault upload validates round-trip decrypt.
- Never require re-encrypting historical messages.
- Fail open to current E2EE if migration aborts mid-way.

## Steps (per user, on first upgraded client)

1. Detect local identity sk/pk.
2. Collect historical material: identity keys; own sender keys (all versions still available); cached peer sender keys if present; peer pubs needed for open threads.
3. Generate MK + VEK; build vault CBOR; AEAD encrypt.
4. Wrap MK with Argon2id(password); show Recovery Key once; wrap MK with recovery KEK.
5. Upload opaque wraps + vault ciphertext (`vault_version=1`).
6. Local verify: download → unlock → compare identity sk.
7. Mark `e2ee_migration_complete` locally; stop server `private_key` writes globally.
8. Subsequent devices: login → unlock vault → restore (no new identity overwrite).

## Rollback
- If step 6 fails: keep using local keys; do not delete; surface error.
- Server retains previous vault versions for conflict resolution.

## Conflict with FS requirement
Sharing identity keys via vault preserves history but does not create forward secrecy for v1 direct chats. Documented; v2 is separate.