# E2EE Key Hierarchy (Target)

## Layers (must not collapse)

1. **Account Authentication Password** — bcrypt (today) / may stay bcrypt for auth; independent of E2EE.
2. **2FA secret** — TOTP or OTP (not implemented yet); unlocks authenticated session only.
3. **Password-derived KEK** — Argon2id(password, salt, params) → wraps E2EE Master Key.
4. **Recovery-Key-derived KEK** — KDF(recovery_key) → independent wrap of E2EE Master Key.
5. **E2EE Master Key (MK)** — 256-bit CSPRNG; stable across password change; never on server plaintext.
6. **Vault Encryption Key (VEK)** — random; wrapped by MK; encrypts vault payload.
7. **E2EE Identity keypair** — existing X25519; lives inside vault; used for crypto_box.
8. **Device keys** — per-device auth/identity for registration & revocation (public on server).
9. **Sender Keys / conversation material** — inside vault for historical decrypt; current epoch for new sends.
10. **Message keys** — derived by existing NaCl constructions (box shared secret / secretbox key); not a separate long-term root.

## Wrapping diagram

```
Password --Argon2id--> KEK_pw --unwrap--> MK --unwrap--> VEK --AEAD--> Vault
RecoveryKey --KDF----> KEK_rk --unwrap--> MK ─┘
```

Password change: unwrap MK with old KEK_pw, rewrap with new KEK_pw. **Do not** rotate MK or re-encrypt messages.

## Suite identifiers (proposed)

| ID | Meaning |
|----|---------|
| `suite:nacl-box-xsalsa20poly1305-v1` | Direct messages (current) |
| `suite:nacl-secretbox-xsalsa20poly1305-v1` | Group sender-key messages (current) |
| `suite:aead-xchacha20poly1305-v1` | Vault / key wraps (preferred if available everywhere) |
| `kdf:argon2id-v1` | Password KEK |

Final AEAD choice for vault must be confirmed against LazySodium + libsodium + WebCrypto availability (XChaCha20-Poly1305 preferred; AES-256-GCM acceptable fallback with explicit suite id).