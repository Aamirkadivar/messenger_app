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

There is essentially **no test suite** (one Android accessibility test). Verify
changes by building and exercising the real apps.

## Architecture notes

**Encryption.** Direct chats use pairwise NaCl `crypto_box` (X25519 +
XSalsa20-Poly1305). Group chats use WhatsApp/Signal-style **Sender Keys**: each
member generates a `crypto_secretbox` key, encrypts a copy for every other
member pairwise, and publishes those blobs. `Chat.KeyEpoch` (bumped server-side
on every membership change) is the rotation signal; `Message.KeyVersion` records
which key encrypted a message. Wire formats must match byte-for-byte across all
three clients — hex(`nonce||ciphertext`) for text, raw bytes for binary.

**Calls.** 1:1 audio. Android uses Google WebRTC (`io.github.webrtc-sdk`);
Windows uses **libdatachannel + libopus**, because no official WebRTC build
exists for MinGW — so Windows hand-rolls Opus encode/decode and RTP
packetization that Android gets for free. Only SDP/ICE crosses the server;
media is DTLS-SRTP peer-to-peer.

**Realtime.** One WebSocket hub (`back-end/websocket/`). Clients must `join` a
chat room to receive its messages. `call:*` types are relayed 1:1 rather than
broadcast.

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
- **`Hub.Clients` is keyed by user id alone - one live connection per account.**
  A second login for the same user overwrites the first, and when that second
  connection drops it used to `delete` the entry unconditionally, evicting the
  connection that was still live. The survivor stays connected but unregistered:
  no error, no reconnect, silently receiving nothing until restarted.
  Unregister now only evicts when the stored client *is* the departing one.
- **Never point a test harness at an account a real client is signed into.**
  Because of the above, doing so knocks that client off the hub and makes it
  look like the feature under test is broken. This burned a whole debugging
  round - the Windows app appeared to ignore `call:reject` when in fact it was
  receiving nothing at all.

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

- **coturn is configured but has never been started** (`docker-compose.yml`,
  `turnserver.conf`). Calls work on a LAN; users behind different NATs need
  TURN. `TURN_HOST` still defaults to `localhost`, which is unreachable from a
  phone.
- Group calling is not implemented — the call button is direct-chat only.
- Group *attachments* and *voice notes* are sent unencrypted; only group text
  uses Sender Keys.
