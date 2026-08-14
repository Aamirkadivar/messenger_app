# E2EE Key Hierarchy

## Layers (must not collapse)

1. **Account Authentication Password** — bcrypt for login JWT. Independent of E2EE except that the same password string is also fed to Argon2id for `KEK_pw`.
2. **2FA** — optional TOTP (RFC 6238, 6 digits, ±1 period). DEV OTP relay remains tester-only when TOTP is not enabled.
3. **Password-derived KEK** — Argon2id, suite `kdf:argon2id-v1`, params `{ "m": 65536, "t": 3, "p": 1, "dklen": 32 }` (m in KiB = 64 MiB). **`p` must stay 1** (libsodium `crypto_pwhash`).
4. **Recovery-Key-derived KEK** — HKDF-SHA256 (`kdf:hkdf-sha256-v1`) over a 32-byte recovery secret (shown once as base64).
5. **E2EE Master Key (MK)** — 256-bit CSPRNG; stable across password change; never stored on the server in plaintext. **Also used as the vault AEAD key** (a separate VEK wrap was designed but not shipped).
6. **E2EE Identity keypair** — X25519; inside the vault; used for v1 box, v2 recipient open, and Sender Key wraps.
7. **Device id** — stable per install; registered for revocation. Not a second identity keypair.
8. **Sender Keys / peer Sender Keys / peer pubs** — inside the vault and on device disk.
9. **Message keys** — NaCl box shared secret or secretbox Sender Key; not a long-term root.

## Wrapping diagram

```
Password --Argon2id p=1--> KEK_pw --unwrap--> MK --AEAD--> Vault JSON
RecoveryKey --HKDF-SHA256--> KEK_rk --unwrap--> MK ─┘
Pairing (old device) --crypto_box--> MK (one-shot) ─┘
```

Password change: unwrap MK with old `KEK_pw`, rewrap with new `KEK_pw`. Do **not** rotate MK or re-encrypt messages.

Forgot password: replaces bcrypt only. Vault still needs recovery key or pairing.

## Suite identifiers

| ID | Meaning |
|----|---------|
| `suite:nacl-box-xsalsa20poly1305-v1` | Direct v1 static box |
| `suite:nacl-eph-box-xsalsa20poly1305-v2` | Direct v2 ephemeral box |
| `suite:nacl-secretbox-xsalsa20poly1305-v1` | Group Sender Key messages |
| `suite:aead-xchacha20poly1305-v1` | Vault + MK wraps |
| `kdf:argon2id-v1` | Password KEK |
| `kdf:hkdf-sha256-v1` | Recovery KEK |
