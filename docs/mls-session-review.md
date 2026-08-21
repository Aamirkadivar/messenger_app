# MLS group messaging — process review

Written 2026-08-17 after a full session on multi-device group E2EE. Records what
is true, what is unproven, and what went wrong in how the work was done.

## Where the system stands

Group text has three schemes live at once:

| version | scheme | status |
|---|---|---|
| 1 | Sender Keys (`crypto_secretbox`) | **works**, ~90 messages readable |
| 4 | X3DH-lite Double Ratchet | direct chats only, works |
| 5 | MLS / TreeKEM (RFC 9420) | groups, **not working end to end** |

Windows uses mlspp, Android uses BouncyCastle `bcmls-jdk18on:1.85`, the Go backend
is an untrusted Delivery Service.

## Fixed, with proof in the database

1. **KeyPackage count was per-account, not per-device.** A second device saw a
   sibling's stock, concluded it was topped up, published nothing, and could
   never be added. Proof: new per-device rows appeared after the fix.
2. **Android hardcoded `encVer = 1` on group send.** The group branch called
   `encryptGroupText` directly; the MLS path in `encryptFor()` was unreachable.
   Proof: `bd63c60a | 5` in `messages`.
3. **Epoch churn.** Android external-rejoined on every launch because
   BouncyCastle cannot serialize group state, so two devices leapfrogged each
   other's epochs. Proof: epoch went from 8-and-climbing to stable at 1.
4. **Own messages lost on every Android build.** Room used
   `fallbackToDestructiveMigration()`, so any forward schema bump silently
   deleted the whole database. For MLS that is permanent data loss, not a cache
   miss: an MLS sender cannot decrypt its own ciphertext, so the local plaintext
   row is the only readable copy that exists. Now downgrade-only, and cached
   own-sends record the real `encVer` instead of a hardcoded 1.
   Confirmed fixed on device by the user, 2026-08-17.

## Fixed by reasoning, never observed working

- `force_path = true` in mlspp commits (BC rejects path-less Add-only commits
  with "Path required but not present"). Windows→emulator decrypted once after
  this, then regressed for unrelated reasons.
- `mls:commit` parse: server puts `chat_id` at the top level, both clients read
  `data.chat_id`. Self-inflicted.
- GroupInfo republished when the epoch moves (stale GroupInfo blocks rejoin).
- Self-heal on server 404, then on group **instance mismatch** — a recreated
  group reuses `chat_id`, so existence alone is not identity.
- Client-acknowledged Welcome consumption. The server used to stamp
  `consumed_at` on handout, so one silent join failure locked a device out
  permanently.
- Creator election (lowest `user_id|device_id`), fails open.
- Membership read from the ratchet tree instead of a persisted "invited" set,
  on both clients. Leaf credentials changed to `userId|deviceId` so the tree
  can distinguish devices of one account.
(The Room migration fix moved up to the proven list — confirmed on device.)

## Current blocker

Android publishes KeyPackages and logs `not a member; awaiting Welcome`.
Windows never invites it. Windows had no logging on that path for the entire
session; it now does (`[mls-ensure]` / `[mls-invite]`).

Prime suspect: `mlsEnsureGroup` returns early when `m_chatType` does not yet say
`"group"`, and the invite only runs inside it. Same shape as bug 2 above —
correct logic behind a gate that never opens.

## What went wrong in the process

**Theories shipped ahead of measurement.** The user said "still the same" three
times before any instrumentation was added. Each round cost a build, install,
and manual test. Measure first was the whole lesson and it was learned late.

**Diagnostic placed inside the branch under test.** The `send gate` log sat
inside `if (chatType == "group")` — the branch most likely never entered. It
printed nothing, which was read as "the log isn't running" rather than "this
code is not on the path". That was the answer, missed for a cycle.

**Tested against a stale binary.** `go build ./...` type-checks without emitting
an executable, so a "fixed" server was never running. Later, the Windows app was
started *before* the fixed backend and its one-shot startup path had already run.
Always verify the running binary's timestamp against the source.

**Overstated a result.** Consumed Welcomes were reported as devices having
"joined". The server stamped `consumed_at` on handout, so it only ever proved a
fetch. This inverted a conclusion and had to be corrected.

**Wrote rules then did not implement them.** `docs/mls-multi-device.md` says
membership must come from the ratchet tree, not a local invited set. Windows
kept trusting the set for several more rounds — and that is the current deadlock.

**Asymmetric observability drove asymmetric diagnosis.** Android had logcat;
Windows logs to stderr and was never launched from a terminal. Every early
hypothesis was Android-shaped because Android was the only half that could be
seen. Windows should have been instrumented first, being the opaque side.

**Repeated resets destroyed evidence.** Each `delete from mls_*` cleared the
state that would have explained the previous failure. Some observations were
also corrupted by Room wiping message history on schema bumps — meaning several
"still encrypted" reports were a different bug entirely.

## Recommendation

Ship v1 Sender Keys for groups now — it demonstrably works — and finish MLS
against a stable baseline instead of a live system the user cannot use. Eight
real bugs in series, each revealed only after the previous fix, is not a pattern
with a predictable end.

If MLS continues: read `[mls-invite]` output first. Do not change code before
that output names the cause.

## Invariants worth keeping (from mls-multi-device.md)

1. Restart any device → rebuilds from disk, epoch unchanged, **no new commit**.
2. Two devices restart concurrently → no churn, no duplicate leaves.
3. Device added at epoch N reads everything from N onward, on every device.
4. Sender's own message is readable on its other devices.
5. Server MLS wipe → clients self-heal with no manual step.

Invariant 1 now passes. The rest are unverified.
