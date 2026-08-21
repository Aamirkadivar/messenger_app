# MLS group messaging — architecture v2 (single Rust/OpenMLS core)

Design document. No implementation yet, by intent.

Supersedes the dual-implementation design (BouncyCastle on Android, mlspp on
Windows). See `mls-session-review.md` for why that failed; the summary is that
two independent MLS stacks had to interoperate byte-for-byte and did not.

---

## 0. Why this redesign is justified

Not a rewrite for its own sake. The failures observed were overwhelmingly
**interop and state-management** failures, not protocol failures:

| Observed failure | Root cause | Fixed by one shared core? |
|---|---|---|
| `Path required but not present` | mlspp omitted UpdatePath on Add-only commits; BouncyCastle requires it | **Yes** — one encoder, one decoder |
| `cannot rebuild self-created group` | BouncyCastle `MlsGroup` has no serialization | **Yes** — OpenMLS has a first-class `StorageProvider` |
| Reflection to read `LeafNode.credential` | BC field is package-private with no getter | **Yes** — we own the API surface |
| Divergent credential formats | Two codebases, two conventions | **Yes** — single definition |
| `mac check in GCM failed` | epoch/tree divergence between the two stacks | Mostly — one stack removes a whole class |

One cryptographic core with one wire encoder eliminates the largest observed bug
class. That is the argument for OpenMLS, and it is a good one.

**What it does not fix:** application-layer bugs. Tonight's other faults were a
hardcoded `encVer = 1`, per-account KeyPackage counting, a `chat_id` parsed from
the wrong JSON level, a Room migration wiping history, and suppressed `qInfo`
logging. None are MLS's fault and none disappear here. Sections 9–12 exist
specifically to make that class of bug impossible or immediately visible.

---

## 1. High-level architecture

```
┌──────────────────────────┐        ┌──────────────────────────┐
│  Android (Kotlin)        │        │  Windows (Qt/C++)        │
│  UI, chat list, cache    │        │  UI, chat list, cache    │
├──────────────────────────┤        ├──────────────────────────┤
│  Kotlin MLS facade       │        │  C++ MLS facade          │
├──────────────────────────┤        ├──────────────────────────┤
│  JNI thin bridge         │        │  C ABI thin bridge       │
├──────────────────────────┴────────┴──────────────────────────┤
│              mls-core  (Rust, cdylib + staticlib)            │
│   OpenMLS + provider (crypto, rand, storage)                 │
│   Owns: identities, key packages, group state, epochs         │
└───────────────────────────┬──────────────────────────────────┘
                            │ opaque bytes only
                            ▼
                  ┌───────────────────────┐
                  │  Go backend (DS/AS)   │
                  │  auth, routing,       │
                  │  queues, metadata     │
                  │  NEVER decrypts       │
                  └───────────────────────┘
```

Invariant: **all MLS cryptography happens inside `mls-core` on the device.** The
Kotlin and C++ layers marshal bytes and manage UI. The Go backend moves opaque
blobs. There is no server-side MLS.

---

## 2. Component diagram

```
mls-core/
├── api/         FFI surface (C ABI) — the only public boundary
├── client/      MlsClient: identity, key packages, group registry
├── group/       create / join / add / remove / update / commit handling
├── message/     encrypt, decrypt, process_message dispatch
├── storage/     StorageProvider impl over a host-supplied KV interface
├── crypto/      OpenMlsProvider wiring (rust-crypto backend)
└── ffi/         handles, error codes, buffer ownership, zeroization

clients/android/  Kotlin facade + JNI glue + Keystore-backed KV
clients/windows/  C++ facade + FFI glue + DPAPI-backed KV
backend/          Go: AS (auth) + DS (delivery)
```

**Key design point:** `mls-core` does **not** own its storage medium. The host
supplies a small key-value interface (get/put/delete/list) through the FFI, and
Rust implements OpenMLS's `StorageProvider` on top of it. This keeps OS-specific
secure storage (Keystore, DPAPI) where it belongs while all *serialization* of
MLS state stays in Rust — the exact thing BouncyCastle could not do.

---

## 3. Data-flow diagram

### Send
```
Kotlin/C++: plaintext ──► FFI: mls_encrypt(group_handle, plaintext)
                             │  (Rust: MlsGroup::create_message)
                             ▼
                        MLS ciphertext (PrivateMessage, opaque)
                             │
                        POST /v2/groups/{gid}/messages
                             ▼
Go: store blob + fan out over WebSocket. Cannot read it.
```

### Receive
```
WebSocket ──► ciphertext ──► FFI: mls_process_message(group_handle, bytes)
                                  │ (Rust: process_message → ProcessedMessage)
                                  ▼
                        { ApplicationMessage → plaintext }
                        { StagedCommit      → apply, epoch++ }
                        { Proposal          → stage }
                             │
                        Kotlin/C++ renders plaintext, persists it locally
```

Plaintext exists only in: the sending UI, the receiving UI, and the local
message cache. Never in transit, never on the server.

---

## 4. MLS group lifecycle

One MLS group per chat. **Every device is its own leaf** — never one leaf per
user (that assumption is what excluded a participant's second device entirely in
v1).

| # | Event | Where decided | Wire effect |
|---|---|---|---|
| 1 | **Create** | elected creator device | `POST /groups` registers gid; epoch 0 |
| 2 | **Add member** | any current member | Add proposal + Commit (+ Welcome per joiner) |
| 3 | **Remove member** | any current member (policy: admin) | Remove proposal + Commit |
| 4 | **Rejoin** | joiner | prefer Welcome via re-add; external commit only as break-glass |
| 5 | **Leave** | leaver requests; a member commits Remove | self-Remove is not self-applying in MLS |
| 6 | **Key update** | each device, periodically | Update proposal + Commit (PCS) |
| 7 | **Epoch change** | consequence of any commit | DS enforces monotonic epoch |
| 8 | **Offline member** | — | DS queues handshakes; client replays on reconnect |
| 9 | **Multi-device** | — | N leaves for N devices of one account |
| 10 | **Lost/reinstalled device** | new device identity | old leaf Removed, new leaf Added |
| 11 | **Concurrent commits** | DS arbitrates | first wins; losers get 409 and re-sync |
| 12 | **Ordering** | DS assigns per-group sequence | handshakes strictly ordered by epoch |
| 13 | **Duplicates** | client | dedupe by message id; MLS keys are single-use |
| 14 | **Replay** | MLS + client | generation/epoch tracking rejects replays |
| 15 | **Out-of-order** | client | app messages tolerated within epoch; commits must be in order |

### Epoch synchronization through the Go backend

The DS is an **ordering authority, not a trust authority**:

1. Client submits a commit with `expected_epoch = E`.
2. DS accepts only if the group's current epoch is exactly `E`, atomically
   (`UPDATE ... WHERE epoch = E`), then stores the handshake at `E+1`.
3. On mismatch → **409 + current epoch**. Client fetches handshakes `> its epoch`,
   applies them in order, re-derives its commit, retries.
4. Every accepted commit triggers a `group.commit` push carrying `{gid, epoch}`.

This is what prevents a group fork. The DS never inspects commit contents — it
compares an integer.

**Uncertainty flagged:** whether to also require clients to publish a fresh
GroupInfo on each epoch (needed only if external commits are supported). If
external join is break-glass-only, GroupInfo publication can be on-demand rather
than per-epoch. Decide before implementing §5.

---

## 5. Backend API design

All endpoints authenticated; all bodies treat MLS fields as opaque base64.

```
# Identity / credentials
POST   /v2/devices                      register device, publish signature pubkey
GET    /v2/users/{uid}/devices           list devices (public)

# KeyPackages — per DEVICE, never per account
POST   /v2/keypackages                   publish batch  {device_id, kp[]}
GET    /v2/keypackages/count             ?device_id=   own stock only
POST   /v2/keypackages/claim             {user_id, device_id} → exactly one, single-use

# Groups
POST   /v2/groups                        {chat_id, gid} → 409 if exists
GET    /v2/groups/{gid}                  {epoch, instance_id, created_at}
GET    /v2/groups/{gid}/members          public roster hint (advisory only)

# Handshakes (commits/proposals)
POST   /v2/groups/{gid}/handshakes       {expected_epoch, blob} → 201 | 409{epoch}
GET    /v2/groups/{gid}/handshakes       ?since_epoch=N  ordered

# Welcomes
GET    /v2/welcomes                      ?device_id=  pending, NOT consumed on read
POST   /v2/welcomes/ack                  {ids[]}      consumed only after join succeeds

# Application messages
POST   /v2/groups/{gid}/messages         {blob}
GET    /v2/groups/{gid}/messages         ?since=cursor
WS     /v2/ws                            push: message | group.commit | welcome
```

Two rules learned the hard way:

- **`instance_id`** on `GET /groups`: a deleted-and-recreated group reuses
  `chat_id`, so existence is not identity. Clients compare it and drop stale
  local state on mismatch.
- **Welcome ack is client-driven.** Marking consumed on handout locked a device
  out permanently whenever a join threw.

The backend implements **no encryption of its own** over MLS. TLS for transport,
MLS for content. Nothing else.

---

## 6. Rust API design (internal, not the FFI)

```rust
pub struct MlsClient { provider: HostProvider, identity: Identity }

impl MlsClient {
    pub fn new(storage: HostStorage, user_id: &str, device_id: &str) -> Result<Self>;
    pub fn credential(&self) -> Credential;            // BasicCredential: "uid|did"
    pub fn new_key_packages(&mut self, n: usize) -> Result<Vec<KeyPackageBundle>>;

    pub fn create_group(&mut self, gid: &[u8]) -> Result<GroupId>;
    pub fn join_from_welcome(&mut self, welcome: &[u8]) -> Result<GroupId>;

    pub fn add_members(&mut self, g: GroupId, kps: &[Vec<u8>]) -> Result<CommitBundle>;
    pub fn remove_members(&mut self, g: GroupId, leaves: &[u32]) -> Result<CommitBundle>;
    pub fn self_update(&mut self, g: GroupId) -> Result<CommitBundle>;

    pub fn encrypt(&mut self, g: GroupId, pt: &[u8]) -> Result<Vec<u8>>;
    pub fn process(&mut self, g: GroupId, msg: &[u8]) -> Result<Processed>;

    pub fn epoch(&self, g: GroupId) -> Result<u64>;
    pub fn roster(&self, g: GroupId) -> Result<Vec<String>>;   // credentials
    pub fn merge_pending(&mut self, g: GroupId) -> Result<()>;
}

pub enum Processed {
    Application(Vec<u8>),
    Commit { new_epoch: u64 },
    Proposal,
    Ignored(IgnoreReason),
}
```

`CommitBundle { commit, welcome: Option<Vec<u8>>, group_info: Option<Vec<u8>> }`
— one type, so a caller can never forget to relay the Welcome (v1's mlspp path
returned it in a tuple, BouncyCastle hung it on a public field, and each client
handled it differently).

**Critical: commits are staged, not merged, until the DS accepts them.** Merge on
201; discard on 409. This makes the epoch fence and local state agree by
construction.

**Uncertainty flagged:** exact OpenMLS signatures move between versions
(`StorageProvider` landed in 0.6, replacing `KeyStore`). Pin one version, wrap
it, and let the FFI shield the clients from churn.

---

## 7. C/FFI API design (the stable boundary)

```c
// ---- lifecycle ----
typedef struct MlsClient MlsClient;   // opaque

typedef struct {                       // host-supplied secure KV
  void* ctx;
  int32_t (*get)(void* ctx, const uint8_t* k, size_t kl, uint8_t** v, size_t* vl);
  int32_t (*put)(void* ctx, const uint8_t* k, size_t kl, const uint8_t* v, size_t vl);
  int32_t (*del)(void* ctx, const uint8_t* k, size_t kl);
} MlsStorage;

int32_t mls_client_new(const MlsStorage* st, const char* user_id,
                       const char* device_id, MlsClient** out);
void    mls_client_free(MlsClient*);

// ---- buffers: caller always frees with mls_buf_free ----
typedef struct { uint8_t* ptr; size_t len; } MlsBuf;
void    mls_buf_free(MlsBuf);

// ---- operations (all return MLS_OK or an error code) ----
int32_t mls_key_packages(MlsClient*, uint32_t n, MlsBuf* out_json);
int32_t mls_group_create(MlsClient*, const uint8_t* gid, size_t, MlsBuf* out_info);
int32_t mls_group_join_welcome(MlsClient*, const uint8_t*, size_t, MlsBuf* out_gid);
int32_t mls_group_add(MlsClient*, const uint8_t* gid, size_t,
                      const uint8_t* kps_json, size_t, MlsBuf* out_bundle);
int32_t mls_group_remove(MlsClient*, const uint8_t* gid, size_t,
                         const uint32_t* leaves, size_t n, MlsBuf* out_bundle);
int32_t mls_group_update(MlsClient*, const uint8_t* gid, size_t, MlsBuf* out_bundle);
int32_t mls_group_merge_pending(MlsClient*, const uint8_t* gid, size_t);
int32_t mls_group_discard_pending(MlsClient*, const uint8_t* gid, size_t);
int32_t mls_encrypt(MlsClient*, const uint8_t* gid, size_t,
                    const uint8_t* pt, size_t, MlsBuf* out_ct);
int32_t mls_process(MlsClient*, const uint8_t* gid, size_t,
                    const uint8_t* msg, size_t, MlsBuf* out_json);
int32_t mls_group_epoch(MlsClient*, const uint8_t* gid, size_t, uint64_t* out);
int32_t mls_group_roster(MlsClient*, const uint8_t* gid, size_t, MlsBuf* out_json);

// ---- diagnostics ----
const char* mls_last_error(void);      // thread-local, never contains secrets
void        mls_set_log_callback(void (*cb)(int32_t lvl, const char* msg));
```

Contracts, stated explicitly because ambiguity here causes leaks and crashes:

- **Ownership.** Rust allocates every `MlsBuf`; the caller frees it with
  `mls_buf_free`. Hosts never `free()` Rust memory directly.
- **Errors.** Integer codes only; no panics cross the boundary
  (`catch_unwind` at every entry point). `mls_last_error` is thread-local and
  must never include key material.
- **Thread safety.** `MlsClient` is internally `Mutex`-guarded; safe to call
  from any thread, serialized per client. Document that group operations are
  not reentrant.
- **Serialization.** Structured returns are JSON for stability; raw MLS blobs
  stay binary. Never re-encode MLS wire formats in Kotlin/C++.
- **Zeroization.** `ZeroizeOnDrop` on secret-bearing types; secrets never enter
  a returned buffer.
- **No secret ever crosses the FFI.** Not private keys, not epoch secrets,
  not exporter secrets. Only ciphertext, public material, and plaintext the
  host already owns.
- **ABI stability.** Additive changes only; new functions rather than changed
  signatures.

### JNI layer (Android)

Thin `extern "C"` JNI functions in Rust (or `jni` crate) wrapping the same core:

- `MlsClient` handle held as a Kotlin `Long`, wrapped in a `Closeable`.
- `ByteArray` in/out; no `String` for binary data (v1 had a UTF-16/base64 class
  of bug from exactly this).
- One `MlsException` carrying the error code; no silent nulls — v1's silent
  `joinFromWelcome` failure cost hours.
- Kotlin holds **zero** MLS logic. If Kotlin is parsing an MLS structure, the
  design has been violated.

---

## 8. Persistent state model

```
Client (source of truth for private state)
├── secure KV (Keystore / DPAPI-wrapped)
│   ├── identity/{device_id}          signature keypair
│   ├── kp/{ref}                      KeyPackage bundles incl. init keys
│   ├── group/{gid}                   OpenMLS-serialized group state
│   └── group_meta/{gid}              { instance_id, epoch, joined_at }
└── message cache (SQLite/Room)
    └── own-send plaintext            THE ONLY readable copy (see below)
```

Rules:

1. **The server is never the source of truth for private MLS state.**
2. **OpenMLS serializes its own state** through `StorageProvider` — no
   hand-rolled encoding, which is what made v1's self-created groups
   unrecoverable.
3. **Own-message plaintext must be durable.** An MLS sender cannot decrypt its
   own application message. The local plaintext row is the only readable copy in
   existence, so losing it is permanent data loss — v1 destroyed history on every
   Room schema bump via `fallbackToDestructiveMigration()`. Never destructive on
   upgrade; write a migration.
4. **Decrypt once.** Message keys are single-use; re-decrypting history yields
   `Request for expired key`. Persist plaintext on first successful decrypt and
   render from the store thereafter.

Recovery:

| Situation | Action |
|---|---|
| clean restart | restore from KV; epoch unchanged; **no commit** |
| offline → reconnect | replay handshakes `since_epoch` |
| `instance_id` mismatch (or absent) | drop local group state, re-establish |
| group 404 on server | drop local group state |
| corrupted group state | delete `group/{gid}`, publish KeyPackage, await re-add |
| lost device | new identity; old leaf Removed by a member |

---

## 9. Server blindness — data classification

| Stored by backend | Class | Why the server still cannot decrypt |
|---|---|---|
| user/device ids, display names | plaintext metadata | no key material |
| signature public keys | public crypto | verification only |
| KeyPackages | public crypto | contain public init/leaf keys; **private halves never leave the device** |
| GroupInfo (if published) | public crypto | ratchet tree public keys; joining still needs a Welcome secret or external-commit secret |
| Welcome blobs | MLS ciphertext | HPKE-sealed **to the joiner's init public key**; server lacks the private key |
| Commits / proposals | MLS ciphertext | UpdatePath secrets HPKE-sealed to member public keys |
| Application messages | MLS ciphertext | AEAD under keys from the epoch secret tree |
| epoch counter, instance_id | plaintext metadata | an integer and a row id |
| `expected_epoch` on submit | plaintext metadata | ordering only |
| — | **sensitive client-only** | init private keys, leaf private keys, epoch secrets, exporter secrets, message keys, own plaintext |

**Argument.** Group encryption keys derive from the epoch secret, which derives
from the commit secret, which derives from path secrets HPKE-encrypted to member
*public* keys. The server holds only public keys and sealed ciphertext. Deriving
the epoch secret would require breaking HPKE (X25519 + HKDF + AEAD). Adding
itself to the group requires signing as an authorized member, which requires a
member's signature private key — never transmitted.

**What the server still learns (MLS does not hide this):** who is in which
group, when, message sizes and timing, device counts. Metadata protection is out
of scope; say so rather than implying otherwise.

---

## 10. Threat model

| Threat | Protected? | Notes |
|---|---|---|
| Malicious backend reading messages | **Yes** | ciphertext only |
| Backend DB compromise | **Yes** | same |
| Backend adds a ghost member | **Partly** | requires a member's signing key; **detectable** only if clients verify roster changes — see gap below |
| Network attacker | **Yes** | MLS + TLS |
| Replay | **Yes** | single-use keys, epoch/generation tracking |
| Rollback / stale-state serving | **Partly** | epoch monotonicity check needed client-side |
| Message reordering by server | **Yes** (integrity) | commits must apply in order; DoS still possible |
| Compromised client | **Partly** | PCS restores security after a key update + commit |
| Stolen unlocked device | **No** | OS-level protection only |
| Malicious current member | **No** | can read everything by definition |
| Removed member | **Yes** | forward secrecy: cannot read post-removal epochs |
| New member reading old messages | **Yes (by design)** | no backward secrecy — messages before join are unreadable, **not a bug** |
| Metadata leakage | **No** | server sees the social graph |
| Local state corruption | Mitigated | §8 recovery |
| Concurrent commits forking the group | **Yes** | DS epoch fence |
| Server withholding messages | **No** | availability is not confidentiality |

**Named gaps requiring explicit product decisions:**

1. **Ghost-member insertion.** MLS makes membership changes *authenticated*, not
   *authorized*. Without a policy layer, any member can add any device. Needs:
   an admin/permission model, and clients surfacing roster changes to users.
2. **Identity binding.** `BasicCredential` binds `uid|did` but nothing proves the
   server reported the right public key. Mitigations: safety numbers / key
   verification UI, or X.509 credentials, or a transparency log. **Unresolved —
   pick one before shipping.**
3. **Rollback.** Clients must reject a group epoch lower than their own.

---

## 11. Directory structure

```
messenger/
├── mls-core/                     # Rust workspace
│   ├── Cargo.toml                # cdylib + staticlib
│   ├── src/{api,client,group,message,storage,crypto,ffi}.rs
│   ├── include/mls_core.h        # generated by cbindgen
│   └── tests/                    # unit + interop vectors
├── clients/
│   ├── android/
│   │   ├── mls-jni/              # Rust JNI crate
│   │   └── app/.../mls/          # Kotlin facade, Keystore KV
│   └── windows/
│       └── src/mls/              # C++ facade, DPAPI KV
├── backend/                      # Go AS + DS
├── test-vectors/mls/             # cross-platform golden vectors
└── docs/
    ├── mls-v2-architecture.md    # this file
    ├── mls-multi-device.md       # invariants (still valid)
    └── mls-session-review.md     # why v1 failed
```

---

## 11b. What the specs and shipped products actually do

Researched rather than assumed. Sources at the end of this section.

**Per-device leaf is the standard, not our invention.** RFC 9750 (MLS
Architecture): "If a user has multiple devices, the user will generally be
represented in a group by multiple clients", and "it is up to the application to
track which clients belong to a given user." Wire — the first production MLS
messenger — "treats each device as a separate entity in all group key exchange
keys." Our `uid|did` credential is exactly the application-level tracking the RFC
delegates to us. **v1's one-leaf-per-user assumption was the anomaly.**

**A sender being unable to decrypt its own message is by design and universal.**
The OpenMLS book is explicit: application keys are "derived from the `SecretTree`
and immediately discarded after encrypting the message to guarantee the best
possible Forward Secrecy", therefore "the message author cannot decrypt
application messages. If access to the message's content is required after
creating the message, a copy of the plaintext message should be kept by the
application." So §8 rule 3 is not a workaround — it is the documented,
intended integration. This also confirms the Room destructive-migration bug was
destroying data the protocol can never regenerate.

**OpenMLS exposes tolerance knobs v1 had no equivalent of.** Three settings
trade forward secrecy for delivery robustness:

| Setting | Purpose | v1 failure it addresses |
|---|---|---|
| `max_past_epochs` | keep past-epoch secrets for late messages | messages arriving after a commit failed outright |
| `out_of_order_tolerance` (`SenderRatchetConfiguration`) | tolerate reordering within an epoch | — |
| `maximum_forward_distance` | tolerate dropped messages | — |

`max_past_epochs > 0` is likely required for us: our DS fans out over WebSocket
while commits land concurrently, so an application message from epoch N can
arrive after the client reaches N+1. **Decision needed:** pick a value (Wire-like
apps use a small window, e.g. 3) and document the forward-secrecy trade-off
explicitly. Note this does *not* make history re-decryptable — keys are still
single-use, so "decrypt once, persist plaintext" stands.

**Our epoch fence is the RFC's sanctioned model.** RFC 9750 offers two: strongly
consistent, where "the Delivery Service is trusted to break ties when two members
send a Commit message at the same time", or eventually consistent, where clients
apply "a deterministic tie-breaking policy". Our 409-on-stale-epoch is the first —
legitimate, and simpler. Worth stating that we chose it deliberately.

**Threat-model correction.** I had rated DS-driven ghost-member insertion
"partly" protected. RFC 9750 is stronger: "even a malicious DS cannot add itself
to groups or recover the group key." The real exposure is a compromised
**Authentication Service**, which "could allow an adversary to impersonate group
members." Fix the classification: DS compromise → **protected**; AS compromise →
**not protected**, and that is where verification effort belongs.

**Key transparency is not solved by anyone.** Contrary to my §10 gap #2 implying
we should pick a transparency log: as of 2026, transparency approaches "are not
practical in real-world applications and… are not implemented in any of the
communication platforms that deployed MLS." Wire instead binds identity through
the customer's **IdP** (device/user verification at login). Revised
recommendation: rely on the AS + IdP binding plus user-facing device
verification (safety numbers, which this codebase already has for direct chats).
Do not build a transparency log.

Sources: [RFC 9750](https://www.rfc-editor.org/rfc/rfc9750.html) ·
[RFC 9420](https://www.rfc-editor.org/rfc/rfc9420.html) ·
[OpenMLS: forward secrecy](https://book.openmls.tech/forward_secrecy.html) ·
[OpenMLS: application messages](https://book.openmls.tech/user_manual/application_messages.html) ·
[Wire on MLS + identity](https://wire.com/en/blog/secure-communication-architecture-e2ee-mls-identity)

---

## 12. Incremental implementation plan

Each phase ends with something **testable without a phone**. That is the point:
v1's bugs surfaced only on real devices, one build at a time.

**Phase 0 — harness (before any MLS).** Rust workspace, cbindgen, CI on
Windows + Android NDK. A `mls_ping` round-trip through JNI and C ABI. Proves the
build and FFI plumbing in isolation.

**Phase 1 — core, headless.** OpenMLS pinned; identity, KeyPackages, create,
add, Welcome-join, encrypt, decrypt. **Two in-process clients exchanging
messages in a Rust unit test.** The `force_path` bug would have been caught here
in seconds.

**Phase 2 — storage.** `StorageProvider` over the host KV. Test: create group,
drop the client, restore, decrypt. This is invariant 1 — the one v1 could never
satisfy.

**Phase 3 — FFI hardening.** Ownership, `catch_unwind`, error codes,
zeroization, thread-safety tests, ASan/Miri where applicable.

**Phase 4 — Go DS.** Endpoints in §5. Integration tests driving *two Rust
clients* through the real server, no UI.

**Phase 5 — Android.** Kotlin facade, Keystore KV, replace BouncyCastle. Keep v1
Sender Keys live behind a flag for rollback.

**Phase 6 — Windows.** C++ facade, DPAPI KV, replace mlspp.

**Phase 7 — multi-device + lifecycle.** Remove, update, rejoin, lost device,
concurrent commits. Golden vectors in `test-vectors/mls/` asserted by both
clients and the Rust core.

**Phase 8 — cutover.** Enable v2 for new groups, migrate old ones, retire
Sender Keys once telemetry is clean.

### Non-negotiables carried from v1

1. Every device is its own leaf; **never** one leaf per user.
2. KeyPackage counts are **per device**.
3. Membership comes from the **ratchet tree**, never a local "invited" set.
4. Welcomes are consumed on **client ack**, never on handout.
5. Own-send plaintext is **durable**; decrypt history once.
6. Group identity is `instance_id`, not `chat_id`.
7. Restart must produce **no commit**.
8. A silent failure is a bug. Every error path logs and surfaces a code.
9. Verify the running binary matches the build before believing any test result.
```
