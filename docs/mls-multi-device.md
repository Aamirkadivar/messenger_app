# Multi-device MLS structure

Status: design. Supersedes the current external-join strategy.

## The invariant everything else follows from

**A device may only join a group in a way it can rebuild from disk after a restart.**

BouncyCastle's `MlsGroup` has no serialization (see `MlsGroupCrypto.kt`), and mlspp's
`State` is not TLS-serializable either. So group state cannot be snapshotted. It can
only be *reconstructed* by replaying the same inputs:

```
join material (Welcome + the KeyPackage private key it was sealed to)
  + every commit since that epoch (the DS keeps them)
  = current group state
```

A device that joined by **Welcome** holds both inputs and can rebuild forever.
A device that **created** the group, or joined by **external commit**, holds neither —
there is no Welcome addressed to it, so on the next launch it must external-join again.

That is the whole bug. Each external join is a commit that advances the epoch, so
two Android devices permanently desync each other: A rejoins to epoch N+1, B rejoins
to N+2, A is now behind and every message fails `mac check in GCM`.

## Roles

| Role | Who | Why |
|---|---|---|
| **Creator** | exactly one device, elected | someone must make epoch 0 |
| **Member** | every other device | joins by Welcome only |

External join is **not** part of normal operation. It stays only as a break-glass
path for a device whose Welcome was lost, and it must be followed by re-persisting
join material (see "Recovery").

### Creator election

Deterministic, so two devices never both create:

```
creator = the participant device with the lowest (user_id, device_id) tuple
          that has a published KeyPackage
```

Any device may *attempt* creation; the DS enforces uniqueness by returning **409**
on a second `POST /e2ee/mls/groups`. A 409 is not an error — it means "someone
else created it, join by Welcome instead."

## Device lifecycle

```
register device
   ↓
publish KeyPackages          (per-device stock, never per-account)
   ↓
an existing member adds it   → commit + Welcome
   ↓
fetch Welcome, join, PERSIST { welcome, kp_private, epoch }
   ↓
replay handshakes since epoch on every launch
```

The persisted bundle is the contract. If a code path stores `welcome = null`, that
device is unrecoverable — treat it as a bug, not a state.

## Who adds whom

Uncoordinated adding caused duplicate leaves (one device received two Welcomes
140 ms apart for a group it was already in). Rules:

1. **Only a current member commits.** A non-member never adds anyone.
2. **The adder is the lowest-ordered member**, same tuple ordering as creator
   election. Others wait.
3. **Check membership before adding.** Never add a device already holding a leaf.
   Local "invited" sets are not sufficient — they don't survive a server reset.
   Membership is read from the group's own ratchet tree.
4. **One add per commit.** Batch adds are allowed but must go in a single commit,
   never a loop of commits.

## Epoch discipline

- The DS fences commits: `expected_epoch` mismatch → **409**. Never retry blindly;
  re-sync, then re-derive the commit.
- Commits are applied **in order**, skipping none. A gap means re-sync from the
  earliest missing epoch.
- **Every commit carries an UpdatePath** (`force_path = true` in mlspp). BouncyCastle
  rejects a path-less Add-only commit with "Path required but not present".
- **Republish GroupInfo whenever the epoch moves.** Stale GroupInfo makes external
  join impossible and hides the group from recovery.

## Reading your own messages

MLS senders cannot decrypt their own application messages, and there is no stateless
re-derivation (unlike the v4 pairwise ratchet). So:

- On send, cache the plaintext locally keyed by `message_id`.
- The cache is authoritative for own messages; server ciphertext must never
  overwrite a cached plaintext (`keepPlain` guard).
- **Other devices on the same account read the message normally** — they are
  separate leaves, not the sender. This is the multi-device requirement, and it
  works as long as those devices are members at that epoch.

### What is genuinely unreadable

A device added at epoch N cannot read anything sent before N. MLS has no backward
secrecy. This is protocol behavior, not a defect — do not add a "fix" for it.
History predating a device's join stays on whatever scheme carried it (v1/v4).

## Message keys are single-use

`Request for expired key` means history is being re-decrypted after its key was
consumed. Decrypt once, store the plaintext, and render from the store on reopen.
Re-decrypting server ciphertext on every repaint is both wrong and a CPU burn.

## Recovery

| Situation | Action |
|---|---|
| Server has no group (404) | drop local bundle, re-establish |
| Have Welcome, behind N epochs | replay handshakes |
| No Welcome (creator/external) | publish KeyPackage, ask to be re-added |
| GroupInfo stale | current member republishes |
| Welcome consumed but join threw | **do not** burn the Welcome — see below |

`GET /e2ee/mls/welcomes` currently stamps `consumed_at` when it *hands over* the
Welcome, before the client proves it worked. One silent BouncyCastle exception
then locks that device out permanently. Consumption must be **acknowledged by the
client after a successful join**, not assumed by the server.

## Dead devices

Stale registrations keep attracting adds that never answer, burning an epoch each
time. Prune a device when it has no unclaimed KeyPackages and has not connected
within a retention window; remove its leaf with a Remove proposal.

## Invariants to test

1. Restart any device → it rebuilds from disk, epoch unchanged, **no new commit**.
2. Two devices restart concurrently → no epoch churn, no duplicate leaves.
3. Device added at epoch N reads everything from N onward on every device.
4. Sender's own message is readable on its other devices.
5. Server MLS wipe → every client self-heals without manual intervention.

Invariant 1 is the one that is failing today; it is the acceptance test for this work.
