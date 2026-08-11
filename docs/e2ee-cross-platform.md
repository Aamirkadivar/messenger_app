# E2EE Cross-Platform Spec

## Clients
| Client | Crypto lib today | Status |
|--------|------------------|--------|
| Android | LazySodium | Production path |
| Windows | libsodium | Production path |
| iOS | — | **Not in repo** — future must match suite IDs |
| Web | — | Design for WebCrypto / libsodium.js compatibility |
| Go backend | golang.org/x/crypto/nacl/box | Relay + verify harness only |

## Shared requirements
- Same vault CBOR schema
- Same suite / kdf identifiers
- Same Argon2id parameter set (benchmarked; document chosen m/t/p)
- Same device registration semantics
- Canonical test vectors in `test-vectors/e2ee/`

## Development aids (temporary)

### DEV 2FA code relay
**Problem:** 2FA does not exist yet; tester needs a way to receive codes during development.

**Plan (env-gated only):**
- `DEV_2FA_RELAY_USERNAME=koueosh` (or similar)
- When OTP is issued for *any* user in development, **also** deliver the code to that username via an in-app system/bot message and server log line tagged `dev_2fa_relay` (never in production).
- Hard-disable unless `APP_ENV=development` **and** relay username is set.
- Never enable in production builds; never log recovery keys or E2EE secrets.

This is **not** a security feature; it is a tester convenience and must be removed or kept behind env flags forever.