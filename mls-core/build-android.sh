#!/usr/bin/env bash
# Builds mls-core for Android and drops the .so files where Gradle expects them.
#
# Requires ANDROID_NDK_HOME. The linker is selected from it here rather than
# hardcoded in .cargo/config.toml, which used to carry absolute D:/sdk paths and
# so only worked on one machine. Cargo's CARGO_TARGET_<TRIPLE>_LINKER takes
# precedence over .cargo/config.toml, so this is the authoritative source.
#
#   ANDROID_NDK_HOME=/d/Sdk/ndk/30.0.15729638 ./build-android.sh
#
# Install an NDK with:
#   sdkmanager "ndk;30.0.15729638"
set -euo pipefail

CORE_DIR="$(cd "$(dirname "$0")" && pwd)"
JNI_LIBS="$CORE_DIR/../application_android/app/src/main/jniLibs"

# API 24 is the floor: it matches the app's minSdk and names the clang wrappers.
ANDROID_API=24

# arm64 covers modern phones; x86_64 covers the emulator. Add armeabi-v7a only
# if you still support 32-bit devices - each ABI adds to the APK.
declare -A TARGETS=(
  [aarch64-linux-android]=arm64-v8a
  [x86_64-linux-android]=x86_64
)

die() { echo "error: $*" >&2; exit 1; }

[ -n "${ANDROID_NDK_HOME:-}" ] || die \
  "ANDROID_NDK_HOME is not set. Point it at an Android NDK, e.g.
       export ANDROID_NDK_HOME=/d/Sdk/ndk/30.0.15729638
   Refusing to guess: silently picking a different NDK would change the ABI
   the .so files are built against."
[ -d "$ANDROID_NDK_HOME" ] || die "ANDROID_NDK_HOME does not exist: $ANDROID_NDK_HOME"

# The NDK ships one prebuilt toolchain per host. On Windows the usable wrappers
# are the .cmd ones - the extensionless files next to them are shell scripts
# that cargo cannot exec.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST_TAG=windows-x86_64; EXE=.cmd ;;
  Linux)                HOST_TAG=linux-x86_64;   EXE= ;;
  Darwin)               HOST_TAG=darwin-x86_64;  EXE= ;;
  *) die "unsupported host $(uname -s); add its NDK prebuilt tag here" ;;
esac

NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$NDK_BIN" ] || die \
  "no NDK toolchain at $NDK_BIN
   ANDROID_NDK_HOME=$ANDROID_NDK_HOME does not look like an Android NDK
   (expected toolchains/llvm/prebuilt/$HOST_TAG/bin)."

export PATH="$HOME/.cargo/bin:$PATH"

# CARGO_TARGET_<TRIPLE>_LINKER, with the triple upper-cased and - turned to _.
for target in "${!TARGETS[@]}"; do
  arch="${target%%-*}"
  linker="$NDK_BIN/${arch}-linux-android${ANDROID_API}-clang${EXE}"
  [ -x "$linker" ] || [ -f "$linker" ] || die \
    "linker missing: $linker
     The NDK at $ANDROID_NDK_HOME has no API-$ANDROID_API wrapper for $arch."
  var="CARGO_TARGET_$(echo "$target" | tr 'a-z-' 'A-Z_')_LINKER"
  export "$var=$linker"
  echo "==> $var=$linker"
done

for target in "${!TARGETS[@]}"; do
  abi="${TARGETS[$target]}"
  echo "==> $target -> $abi"
  cargo build --release --target "$target" --manifest-path "$CORE_DIR/Cargo.toml"
  mkdir -p "$JNI_LIBS/$abi"
  cp "$CORE_DIR/target/$target/release/libmls_core.so" "$JNI_LIBS/$abi/"
done

echo "==> done. .so files in $JNI_LIBS"
ls -la "$JNI_LIBS"/*/libmls_core.so
