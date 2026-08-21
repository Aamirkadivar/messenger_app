# E2EE Threat Model

## Assets
- Message plaintext (text, voice, attachments)
- E2EE identity private key
- Group sender keys (own and peer, all versions needed for history)
- E2EE Master Key, password-derived KEK, Recovery Key
- Account credentials / JWT sessions

## Adversaries

| Adversary | Capabilities | Goal |
|-----------|--------------|------|
| Network attacker | Passive/active on wire | Read/modify traffic |
| Database thief | Read DB dump | Decrypt messages / steal keys |
| Malicious backend operator | Full app + DB control | Decrypt user messages |
| Stolen / lost device | Physical access | Extract keys / read chats |
| Malicious authenticated device | Own vault unlock | Exfiltrate keys |
| Password compromise | Knows account password | Login; if also vault KEK, unlock E2EE |
| Recovery-key compromise | Has recovery secret | Unlock E2EE without password |
| MITM on QR pairing | Intercept pairing | Steal vault transfer |

## What the system protects / fails

| Scenario | Today |
|----------|--------|
| DB dump of ciphertext | Protected (clients encrypt; no server private keys) |
| DB dump of `users.private_key` | Column dropped; leftover rows cleared then dropped |
| Malicious backend | Cannot decrypt properly encrypted msgs; **can** substitute peer public keys / drop msgs |
| New device without old | Vault + password, recovery key, or pairing |
| Password reset without recovery | Account may reset (TOTP still required if enabled); **E2EE history stays locked** |
| Revoked device | API/WS blocked; cannot erase keys already copied |

## Explicit non-goals
- Perfect memory zeroization on Go/JVM/Qt
- Cryptographic erasure of keys already exfiltrated before revoke
- Server-assisted recovery of E2EE without Recovery Key / old password / pairing from an old device

## Residual risks
- Password + recovery key both stolen → full E2EE compromise
- Malicious server can still substitute peer public keys. Direct chats **pin** the first identity (TOFU) and keep encrypting to it if the server later reports a different key; the user must tap **Accept new code**. Safety numbers can be compared as text or via QR (`sn1.` + SHA-256 hex). A successful scan is stored as verified until the pinned key changes.
- Attachment filenames, duration, file size, forward attribution, thumbnail URLs, and media `file_url` live in the inner `EM1` ciphertext (`th`, `fu`), not in server columns (those are empty or zero on new sends). Coarse `content_type` / `file_type` remains visible so clients know which bubbles to render.
- Direct v2 is sender-side FS only (historical); new directs use v3 Double Ratchet
- Multiple devices can stay signed in; revoke still kicks a chosen device
- Optional TOTP 2FA (password still unlocks the vault if stolen)
