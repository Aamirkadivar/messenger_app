# E2EE test vectors

Canonical byte-level fixtures for Android (LazySodium), Windows (libsodium),
Go (`golang.org/x/crypto/nacl` + XChaCha), and any future iOS/Web client.

Regenerate (only if the construction changes):

```bash
cd back-end
WRITE_VECTORS=1 go test ./e2ee/ -count=1
```

Verify:

```bash
cd back-end && go test ./e2ee/ -count=1
```

## Suites
| ID | File | Use |
|----|------|-----|
| `suite:nacl-box-xsalsa20poly1305-v1` | `wire-v1-box.json` | Direct v1: `hex(nonce[24]\|\|ct)`, `encryption_version=1` |
| `suite:nacl-eph-box-xsalsa20poly1305-v2` | `wire-v2-eph-box.json` | Direct v2: `hex(eph_pk[32]\|\|nonce[24]\|\|ct)`, `encryption_version=2` |
| `suite:nacl-secretbox-xsalsa20poly1305-v1` | `wire-secretbox.json` | Group Sender Keys: text is hex; media is raw `nonce\|\|ct` |
| `suite:aead-xchacha20poly1305-v1` | `vault-aead.json` | Vault / key wraps: `nonce[24]\|\|ciphertext\|\|tag[16]` |
| `em1` / `fn1` | `wire-em1-fn1.json` | Inner EM1 envelope + per-device FN1 fan-out |
| `suite:dr-x25519-x3dhlite-v4` | `x3dh-root.json`, `x3dh-transcript.json` | Direct v4 X3DH-lite ratchet: symmetric root + one-directional decrypt transcript. See `docs/e2ee-protocol-v4.md` |

### v4 X3DH-lite vectors
`x3dh-root.json` pins the symmetric root RK0 (both sides must agree).
`x3dh-transcript.json` is a frozen A→B conversation delivered out of order; rebuild
B's session from the fixed identities and `DecryptMsg` each ciphertext in order.
Regenerate with `E2EE_GENVEC=1 go test ./e2ee/ -run TestX3DHVectors`; verify with
`go test ./e2ee/ -run TestX3DHVectors`. Glare/turn continuation is covered by the
live stress tests (`TestX3DHLite*`), not a frozen vector.

## AAD (must match `back-end/e2ee/vault.go`)
- Vault: `vault|{userID}|{vaultVersion}|suite:aead-xchacha20poly1305-v1`
- Master key wrap: `mk|{userID}|{purpose}|suite:aead-xchacha20poly1305-v1`
- VEK wrap: `vek|{userID}|suite:aead-xchacha20poly1305-v1`

## Keys in these files
Deterministic **test-only** keypairs from a counter RNG. Do not use in production.

## Go tests
`back-end/e2ee/wire_vectors_test.go` round-trips each construction and diffs the JSON.
`vault_test.go` covers tamper / AAD mismatch. `negative_fuzz_test.go` rejects truncated/tampered wires and pairing codes (`go test ./e2ee/ -fuzz=FuzzParsePairingString -fuzztime=10s`).
