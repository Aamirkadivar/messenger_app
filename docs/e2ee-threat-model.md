# E2EE Threat Model

## Assets
- Message plaintext (text, voice, attachments)
- E2EE identity private key
- Group sender keys (all historical versions needed for history)
- Future: E2EE Master Key, Vault Encryption Key, Recovery Key, password-derived KEK
- Account credentials / JWT sessions

## Adversaries

| Adversary | Capabilities | Goal |
|-----------|--------------|------|
| Network attacker | Passive/active on wire | Read/modify traffic |
| Database thief | Read DB dump | Decrypt messages / steal keys |
| Malicious backend operator | Full app + DB control | Decrypt user messages |
| Stolen / lost device | Physical access to unlocked or locked device | Extract keys / read chats |
| Malicious authenticated device | Own vault unlock | Exfiltrate keys; revoke hard |
| Password compromise | Knows account password | Login; if also vault KEK, unlock E2EE |
| 2FA compromise | OTP/session second factor | Complete login |
| Recovery-key compromise | Has recovery secret | Unlock E2EE without password |
| MITM on QR pairing | Intercept pairing | Steal vault transfer |

## What current system protects / fails

| Scenario | Today | Target |
|----------|-------|--------|
| DB dump of ciphertext | Protected if clients encrypted | Same + no `users.private_key` |
| DB dump of `users.private_key` (register leftover) | **Broken** for any account with that field populated | Remove; never store |
| Malicious backend | Cannot decrypt properly encrypted msgs; **can** push malicious pubkeys / drop msgs | Same confidentiality; authenticity still needs identity trust UX |
| New device without old | **History lost** | Vault + password/recovery unlock |
| Password reset without recovery | N/A (no vault) | Account may reset; **E2EE history stays locked** |
| Revoked device | No revocation | Session kill + epoch bump; cannot erase already-copied keys |

## Explicit non-goals
- Perfect memory zeroization on Go/JVM/Qt/JS
- Cryptographic erasure of keys already exfiltrated by a malicious device before revoke
- Server-assisted "forgot everything" recovery of E2EE without Recovery Key / old password / QR from old device

## Residual risks after target design
- Password + recovery key both weak/stolen → full E2EE compromise
- Malicious server can still substitute peer public keys (users must verify security codes)
- Protocol v1 direct chat lacks forward secrecy