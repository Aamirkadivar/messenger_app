# E2EE Direct Message Protocol v4 — X3DH-lite Two-Root Ratchet

Status: **Reference implemented in Go** (`back-end/e2ee/ratchet_x3dh.go`), proven
by `back-end/e2ee/ratchet_glare_test.go` + `ratchet_x3dh_stress_test.go`.
Client ports (Kotlin, C++) pending. Date: 2026-08-15.

`encryption_version = 4` · suite `suite:dr-x25519-x3dhlite-v4`.

---

## 1. Why v4 exists

v3 (`ratchet.go`, `DoubleRatchet.kt`) chose initiator/responder by *who sends
first*. When both peers send before either receives (**glare**), both
initialise as "Alice" with independent random roots and neither can decrypt the
other. This was the production failure: in chat `08ca93d3…` both parties had
sent, and **every** v3 decrypt failed on both sides.

v4 removes roles entirely. It is **not a new primitive** — it composes the same
audited pieces already in use (X25519, HKDF-SHA256, `crypto_secretbox`
XSalsa20-Poly1305). It is the standard prekey idea with the long-term identity
key playing the signed-prekey role, plus a two-root split so concurrent chains
cannot corrupt each other.

Old v1/v2/v3 ciphertext continues to decode unchanged. Already-broken v3
messages are **not** recoverable (they were glare-corrupted at send time).

---

## 2. Cryptographic building blocks (unchanged from v3)

- `DH(sk, pk)` = X25519 (`curve25519`). Identity keys are `crypto_box`
  Curve25519 keypairs.
- `kdfRK(rk, dh) -> (rk', ck)` = HKDF-SHA256(ikm=`dh`, salt=`rk`,
  info=`"messenger-dr-root-v1"`, L=64); first 32 bytes = `rk'`, last 32 = `ck`.
- `kdfCK(ck) -> (ck', mk)` : `mk = HMAC-SHA256(ck, 0x01)`,
  `ck' = HMAC-SHA256(ck, 0x02)`.
- AEAD per message = `crypto_secretbox(mk, nonce24, plaintext)`.

---

## 3. Symmetric root RK0

Both sides derive the **same** initial root with no negotiation:

```
ikm  = DH(myIdentitySk, peerIdentityPk)              # symmetric: DH(a,B)=DH(b,A)
lo, hi = sort(myIdentityPk, peerIdentityPk)          # lexicographic byte order
salt = SHA256(lo || hi)                              # order-independent
RK0  = HKDF-SHA256(ikm, salt, info="messenger-x3dh-lite-v1", L=32)
```

`sort` makes `salt` identical regardless of which side computes it. RK0 is the
canonical interop anchor — see `test-vectors/e2ee/x3dh-root.json`. If two
implementations disagree on RK0, nothing else can interoperate.

---

## 4. Session state

```
RK0                       immutable symmetric root (§3)
RKs, CKs                  sending root + chain key
RKr, CKr                  receiving root + chain key
DHsSk, DHsPk              our current sending ephemeral keypair
DHr                       peer's current ephemeral public (receiving side)
PeerIdent                 peer identity public (target of our very first send)
IdentSk                   our identity secret (opens the peer's INITIAL message)
Ns, Nr, PN                send counter, receive counter, previous-chain length
Skipped[dhpk:n] -> mk     out-of-order message keys
SentFirst, RecvFirst      have we started a sending / receiving chain
TurnPending               received since our last send → next send DH-ratchets
```

`InitSession(myIKpk, myIKsk, peerIKpk)` sets `RKs = RKr = RK0`,
`PeerIdent = peerIKpk`, `IdentSk = myIKsk`, everything else empty/false. Both
peers call it identically.

Two roots (`RKs` vs `RKr`) is the key fix: v3 evolved one shared `RK` between
directions, so two concurrent glare chains corrupted each other. Here my `RKs`
mirrors the peer's `RKr` (identical DH inputs) and vice-versa, so the chains
always agree.

---

## 5. Message framing

```
byte  0      : type   (0x01 = INITIAL, 0x02 = NORMAL)
bytes 1..32  : dh_pk  (sender's current sending ephemeral public, 32)
bytes 33..36 : n      (uint32 big-endian, message index in its sending chain)
bytes 37..40 : pn     (uint32 big-endian, length of previous sending chain)
bytes 41..64 : nonce  (24)
bytes 65..   : secretbox ciphertext+tag
```

Header length = 41 (one byte longer than v3's 40; the extra leading byte is the
type). `type = INITIAL` while the sender has **never received** (its chain is
rooted against the peer identity); `NORMAL` thereafter.

---

## 6. Encrypt

```
if not SentFirst or TurnPending:
    target = DHr if RecvFirst else PeerIdent      # ephemeral vs identity
    (sk, pk) = generateX25519()
    PN, Ns = Ns, 0
    RKs, CKs = kdfRK(RKs, DH(sk, target))
    DHsSk, DHsPk = sk, pk
    SentFirst, TurnPending = true, false
type = NORMAL if RecvFirst else INITIAL
CKs, mk = kdfCK(CKs)
frame = type || DHsPk || Ns++ || PN || nonce || secretbox(mk, nonce, plaintext)
```

The first send targets `PeerIdent` (so the receiver can open it with its
identity secret); every send after receiving does a fresh DH ratchet against the
peer's ephemeral.

---

## 7. Decrypt (transactional)

Decrypt advances a **clone** of the session and commits it back only if the AEAD
tag verifies, so a forged or truncated message can never desync the ratchet
(threat-model scenario K). On any parse/auth failure the live session is
untouched.

```
parse type, dh, n, pn, nonce, ct     # reject if too short
if Skipped has (dh:n): return open with that mk (and drop it)
if DHr != dh:                        # new receiving chain
    skip current recv chain up to pn (store skipped mks)
    if type == INITIAL and not RecvFirst:
        RKr, CKr = kdfRK(RK0, DH(IdentSk, dh))     # glare-safe: from RK0
    else:
        require DHsSk present
        RKr, CKr = kdfRK(RKr, DH(DHsSk, dh))       # normal turn
    DHr, Nr, RecvFirst, TurnPending = dh, 0, true, true
skip recv chain up to n (store skipped mks)
CKr, mk = kdfCK(CKr); Nr++
return secretbox_open(mk, nonce, ct)     # failure ⇒ discard clone
```

`MaxSkip = 200` bounds stored out-of-order keys.

---

## 8. Forward secrecy & limitations

- **Per-message**: `kdfCK` is one-way, so a leaked chain key does not expose
  earlier messages in that chain.
- **Per-turn**: each direction change introduces a fresh X25519 ephemeral (DH
  ratchet), healing the root forward.
- **Root limitation**: `RK0` derives from the long-term identity keys, so a
  compromise of an identity secret exposes `RK0` and therefore the *first* chain
  in each direction until the first turn. This is the accepted cost of a
  prekey-less handshake; full X3DH with signed + one-time prekeys would remove
  it and is the documented future upgrade (needs server-published prekey
  storage). Do not describe v4 as having Signal-equivalent initial FS.
- v4 does not by itself authenticate the peer identity key — that is TOFU-pinned
  and safety-number-verifiable exactly as in v3 (unchanged).

---

## 9. Cross-platform test vectors

`test-vectors/e2ee/`:

- **`x3dh-root.json`** — fixed identity keypairs → `rk0`. Every platform must
  compute the same `rk0` from either side. Pins §3.
- **`x3dh-transcript.json`** — a frozen one-directional A→B conversation
  delivered out of order (`m0,m2,m4,m1,m3`). Rebuild B's session from the fixed
  identities and `DecryptMsg` each ciphertext in listed order; each must yield
  its plaintext. Pins §3, §5, the INITIAL path of §7, the chain KDF, and
  skipped-key handling.

Turn/glare *continuation* depends on the receiver's own randomness, so it is not
a frozen vector; each platform instead ports the in-code stress tests
(`TestX3DHLiteGlareConverges`, `…Ordered`, `…LongInterleaved`,
`…BurstsThenReorder`, `…DeepGlare`, `…MalformedFailsSafe`) which run live.

Regenerate: `E2EE_GENVEC=1 go test ./e2ee/ -run TestX3DHVectors`
Verify: `go test ./e2ee/ -run TestX3DHVectors`

---

## 10. Version plumbing (for the client ports)

- New direct sends produce `encryption_version = 4`; keep decoding 1/2/3.
- The session serialises to JSON with the §4 fields (hex byte arrays, ints,
  bools); Kotlin `DoubleRatchet.State`-style `toJson`/`fromJson` extended for the
  new fields. Stored in the vault under `direct_ratchets` keyed by the peer
  device, exactly as v3 sessions are today.
- Fan-out (FN1) is unchanged: encrypt one v4 frame per recipient device.
- Send-path preference becomes v4 → (fallback) v2 → v1 for direct chats. The
  broken v3 send path is retired for new messages.

---

See also: `e2ee-protocol.md` (index), `e2ee-protocol-v3.md` (superseded direct
handshake), `e2ee-architecture.md` §1.5 (why v3 was inert/broken in practice).
