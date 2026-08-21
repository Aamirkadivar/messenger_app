# CLAUDE.md

Guidance for working in this repository.

> **The root `README.md` is wrong.** It claims Flutter, MongoDB and Firebase
> auth. The project is native Kotlin/Compose, PostgreSQL and JWT, and the
> README does not mention the Windows client at all. Trust this file and the
> code; treat the README as stale.

## What this is

An end-to-end encrypted messenger with three parts, all in one repo:

| Path                  | What                | Stack |
|-----------------------|---------------------|-------|
| `back-end/`           | API + realtime hub  | Go 1.21, Fiber, GORM, PostgreSQL |
| `application_android/`| Android client      | Kotlin, Jetpack Compose, Hilt, Room |
| `windows_app/`        | Windows desktop     | C++20, Qt 6.11 (QML), CMake + MinGW |

The server is deliberately a dumb relay for anything sensitive: it stores and
forwards ciphertext and signaling blobs, and cannot read message bodies,
attachments, or call audio.

## Build & run

Backend (needs PostgreSQL running; config comes from `back-end/.env`):

```bash
cd back-end && go build -o messenger-backend.exe . && ./messenger-backend.exe
```

Android:

```bash
cd application_android && ./gradlew.bat assembleDebug
```

Windows desktop — Qt lives at `D:\qt\6.11.1\mingw_64`, and its bundled MinGW
at `D:\Qt\Tools\mingw1310_64\bin` must be **first on PATH** for both vcpkg and
CMake, or the wrong compiler gets picked (see Gotchas):

```bash
cd windows_app/build && cmake .. -G "MinGW Makefiles" && cmake --build . --target messenger_app --parallel 4
```

Running it from Git Bash needs Qt's DLLs on PATH; `QT_FORCE_STDERR_LOGGING=1`
routes `qDebug` somewhere readable:

```bash
PATH="/d/qt/6.11.1/mingw_64/bin:/d/Qt/Tools/mingw1310_64/bin:$PATH" QT_FORCE_STDERR_LOGGING=1 ./messenger_app.exe
```

E2EE wire tests live in `back-end/e2ee/` (`cd back-end && go test ./e2ee/`) and
`test-vectors/e2ee/`. There is essentially no client test suite (one Android
accessibility test). Verify UI/call changes by building and exercising the apps.

## Architecture notes

**Encryption.** Direct chats: **v4 X3DH-lite ratchet** is the default for new
sends (`encryption_version=4`, libsodium — not AGPL libsignal). Decrypt v3
(Double Ratchet), v2 (ephemeral `crypto_box`) and v1 (static pairwise
`crypto_box`) forever. **v4 sends are always INITIAL-type chains rooted at the
immutable rk0** (fresh ephemeral, rotated every 1000 messages) — never
DH-turn/NORMAL. This is deliberate: clients re-download ciphertext from the
server and re-decrypt on every chat reopen, and turned-chain message keys are
consumed on first read, so NORMAL rows became "encrypted message" forever.
INITIAL rows decrypt statelessly from rk0 + identity key any number of times
(`decryptInitialStateless`). The sender's own fan-out blob is sealed with a
throwaway self-session (peer = own identity). Stored sessions whose
`peerIdent` doesn't match the expected identity are stale (early builds
created them with the wrong key) and are discarded on load. Group
chats stay WhatsApp/Signal-style **Sender Keys** (`crypto_secretbox`);
`Chat.KeyEpoch` is the membership rotation signal; `Message.KeyVersion` records
which key sealed a group message. One X25519 identity per account, shared
across devices via the vault. Wire formats must match byte-for-byte across
Android and Windows.

**Inner envelope (EM1).** Before NaCl, plaintext is
`EM1\n || u16be metaLen || UTF-8 JSON {fn,fwd,dur,sz,th,fu} || payload`.
New sends leave server `file_name`, `forwarded_from_name`, `duration_ms`,
`file_size`, `thumbnail_url`, and `file_url` empty/zero. Recipients decrypt
`content`, then read `fu`/`th` to fetch media. Classify bubbles by
`file_type` / `content_type` even when those URL columns are blank. Unknown
blobs unwrap as raw payload.

**Per-device fan-out (FN1).** Direct v3 with more than one live device uses
`GET /e2ee/chats/:id/devices` and wraps
`FN1\n || u16be n || (u8 idLen || id || u32be blobLen || blob)*n`. Session key
is `chatId|deviceId`. Include **this device** in the fan-out so the sender can
decrypt their own history after a refetch. `messages.sender_device_id` comes
from `X-Device-Id`. Single-device chats still send one shared `chatId` blob.

**Vault.** JSON AEAD (XChaCha20-Poly1305) under the E2EE master key; password
Argon2id wrap + recovery wrap; QR/text pairing (`sn1.` safety numbers). Ratchet
state is `direct_ratchets` keyed by `chatId` or `chatId|deviceId`; persist
bumps `seq`. Stale PUT returns **409** — merge by `seq` then retry. Pull on
reconnect.

Docs: `docs/e2ee-architecture.md`, `docs/e2ee-protocol.md`,
`docs/e2ee-protocol-v3.md`.

**Calls.** 1:1 and group (max 4, full mesh) audio and video. Android
uses Google WebRTC (`io.github.webrtc-sdk`); Windows uses **libdatachannel +
libopus + libvpx**, because no official WebRTC build exists for MinGW — so
Windows hand-rolls Opus/VP8 encode/decode and RTP packetization (RFC 7741 for
VP8, in `videocallengine.cpp`) that Android gets for free. Group calls reuse
pairwise SDP/ICE under `group_call_id`, orchestrated by `call:group_invite` /
`join` / `leave` (server fans those out; media stays P2P). Windows group video
encodes VP8 once and fans RTP to each peer. VP8 is the video codec because
it's the one Android's prebuilt WebRTC always has (software libvpx fallback);
H.264 is device-dependent. Video-ness is fixed at invite time (`"video": true`
on invite; no mid-call renegotiation); camera on/off mid-call is signaled via
the relayed `call:media` type. Only SDP/ICE crosses the server; media is
DTLS-SRTP peer-to-peer.

**Realtime.** One WebSocket hub (`back-end/websocket/`). Multiple devices per
account stay connected (keyed by connection id; same `device_id` replaces the
previous socket). Clients must `join` a chat room to receive its messages.
`call:*` types are relayed 1:1 (and to the sender's other devices on
answer/reject/end).

## Gotchas

These each cost hours. Most are not discoverable by reading the code.

### Qt / QML
- **`Row` and `Column` forbid anchors on the axis they manage.** A `Row` child
  using `anchors.verticalCenter`, or a `Column` child using `anchors.right`,
  silently breaks the layout — this caused overlapping text and stray
  timestamps. Wrap in a plain `Item` and anchor inside that.
- **`Rectangle.clip` ignores `radius`** — it clips to the bounding box. For
  circular images use `Canvas` (`arc` + `clip` + `drawImage`).
- **`QtQuick.Effects` / `MultiEffect` renders nothing** in this Qt/MinGW/GPU
  combination. Do not rely on it; the "glass" look is faked with translucent
  fills (`GlassPanel.qml`) over painted gradients (`AmbientGlow.qml`).
- **`onXChanged` does not fire for a property's initial value.** Cached images
  resolving synchronously never triggered `Image.onStatusChanged`; needs a
  `Component.onCompleted` check too.
- **QML `int` is 32-bit.** `Date.now()` (~1.78e12) overflows it — this silently
  broke the call timer. Use `real`.
- **A frameless window reports `FullScreen`, not `Maximized`**, once
  `WM_NCCALCSIZE` removes the frame. Check both (see `windowMaximized` in
  `main.qml`).
- Every new `.qml`/`.h`/`.cpp` must be added to **both** `resources.qrc` and
  `CMakeLists.txt`, or it fails at runtime (or fails to link, for `Q_OBJECT`
  headers AUTOMOC never sees).

### Windows native
- `Qt.FramelessWindowHint` strips `WS_THICKFRAME`/`WS_MAXIMIZEBOX`, which is
  what the shell checks before offering Aero Snap and Snap Layouts. They are
  restored natively in `src/utils/win11frameless.cpp`.
- The Qt desktop app (`messenger_app.exe`, underscore) and the Go backend
  (`messenger-app.exe`, hyphen) differ by one character. A firewall rule for
  one does nothing for the other; a `*messenger_app*` glob also matches the
  repo *directory*, producing convincing false positives.

### vcpkg / MinGW
- vcpkg picks the compiler off `PATH`. With Swift's clang ahead of Qt's MinGW
  it selects the wrong one and dependencies fail confusingly.
- `libsrtp` will not build under MinGW with its default `-Werror` (its debug
  prints use `%08x` while MinGW's `ntohl()` returns `u_long`). Fixed by the
  overlay port in `windows_app/vcpkg-ports/libsrtp`, which sets
  `ENABLE_WARNINGS_AS_ERRORS=OFF`. Injecting `-Wno-error` via a triplet does
  **not** work — libsrtp appends its own flags afterwards.
- **libdatachannel must be built with the `srtp` feature.** Without it ICE
  connects and then DTLS fails instantly, which looks like a network problem.
  Verify with `nm -C --defined-only libdatachannel.a | grep srtp_`.

### Backend / WebSocket
- **Same `device_id` replaces the previous socket** for that install (reconnect).
  Other devices for the same account stay connected. Answer/reject/end on one
  device is echoed to the account's other sockets so they stop ringing.
- A test harness that reuses a real client's `device_id` will kick that
  install's socket. Use a distinct device id for harnesses.
- **Go files saved as UTF-16 fail with `unexpected NUL`.** Prefer `StrReplace`.
  After a `Write` of `.go`, if `go test` complains, convert
  `decode('utf-16')` → UTF-8. This bit `back-end/e2ee/envelope.go`.
- Media messages need **`file_type` even when `file_url` is empty** (EM1 holds
  `fu`). Restart the backend after that handler change.
- GORM adds `sender_device_id` on migrate; restart after that column lands.

### Android
- **A Hilt `@AndroidEntryPoint` BroadcastReceiver injects nothing on its own.**
  Injection runs inside the generated `super.onReceive()`, which Kotlin cannot
  call — `BroadcastReceiver.onReceive` is abstract in the source superclass, so
  it is a compile error. The `@Inject lateinit` field then throws on first
  touch. Use `EntryPointAccessors.fromApplication(...)` instead (see
  `service/CallActionReceiver.kt`). This silently broke Decline on the
  incoming-call notification: nothing was ever sent, so the caller rang on.
- **`CallRepository.myUserId` is only set by the outgoing-call and accept
  paths.** A process started by `RealtimeService` that has only ever *received*
  a call has it empty, so replies went out with a blank `from_user_id`.

### WebRTC
- **Never let a state transition sit behind media cleanup.**
  `CallService::teardown` closed the PeerConnection before `setStatus(Ended)`.
  `close()` on a connection still gathering — exactly where an unanswered
  outgoing call is — can throw, skipping the transition and leaving the caller
  ringing forever after the far end declined. Wrap the cleanup so the state
  change cannot be skipped.
- **Buffer remote ICE candidates.** The caller trickles immediately, but the
  callee has no PeerConnection until the user answers; dropping those loses the
  entire first burst and ICE may never pair.
- **Android's WebRTC follows the OS "active network".** With any VPN up it
  gathers candidates only from the tunnel, even a split-tunnel one that bypasses
  the LAN. `networkIgnoreMask` excludes VPN/loopback adapters.
- **Set `MODE_IN_COMMUNICATION`** or incoming audio decodes correctly into a
  silent stream. Then route explicitly: `setSpeakerphoneOn()` is deprecated and
  ignored on Android 12+; use `setCommunicationDevice()`.
- **libdatachannel auto-negotiates.** `setRemoteDescription(offer)` already
  produces the answer; calling `setLocalDescription` again throws. On the
  answering side do *not* `addTrack()` — use the track from `onTrack`, which
  carries the offer's `mid` (Google WebRTC uses `"0"`, not `"audio"`).
- **RTP payload starts after the extension header.** Use `getBody()`, not
  `data() + getSize()`; the latter leaves extension bytes attached and every
  `opus_decode` returns `OPUS_INVALID_PACKET` (-4).
- A websocket permits **one writer at a time**. The server's ping replies raced
  `writePump` and corrupted frames, dropping connections every 15–60s with code
  1006 — all writes now go through one mutex.

## Debugging calls

Reach for evidence early; this stack fails silently in ways that invite wrong
theories.

- Windows logs `ICE state` / `PeerConnection state` (`Checking=1, Connected=2`;
  `Connecting=1, Connected=2, Failed=4, Closed=5`), plus TX/RX frame counters
  and mic peak level.
- Android logs `RTP inbound-rtp/outbound-rtp` counters via `getStats()`.
  **`packetsReceived` is the ground truth for "is audio arriving".**
  AudioTrack's "frames delivered" is not — it counts silence too.
- Server-side truth lives in the `call_logs` table (`ringing`/`answered`/
  `missed`/`ended` plus duration).
- `back-end/calltest/` is a throwaway two-client harness that exercises
  signaling without the real apps. It hardcodes the dev JWT secret.

## Known gaps

- **iOS / Web** — no clients in this repo.
- **Group Double Ratchet** — groups stay Sender Keys on purpose (not libsignal).
- **TURN:** `GET /calls/ice-servers` advertises the API Host (not `localhost`)
  when `TURN_HOST` is loopback, so a phone talking to a LAN IP gets
  `turn:<that-ip>:3478`. Start coturn with `docker compose up -d coturn` in
  `back-end/` (`TURN_SECRET` must match `.env`). Cross-NAT still needs
  `TURN_EXTERNAL_IP` / a public `TURN_HOST` and those UDP/TCP ports forwarded.
  Windows must parse `turn:host:port?transport=` itself (`QUrl` does not) and
  set `enableIceTcp` so TCP TURN is actually gathered.
- Group calls are full mesh (max 4); audio and video. Video uses lower
  capture settings on Android (480×360@15); Windows encodes VP8 once and
  fans RTP to each peer. Cross-NAT still needs TURN (same as 1:1).
- Windows video advertises NACK/PLI and forces a keyframe on PLI (and
  requests one after decode loss). Periodic keyframes remain a fallback.
- Uploaded media still sits on disk at unguessable UUID `.bin` paths; the
  message row no longer stores `file_url` / `thumbnail_url` for new sends.
  Coarse `content_type` remains visible to the server.
