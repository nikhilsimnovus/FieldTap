#!/bin/sh
# Build fieldtap-diagd for the handset with the Android NDK.
#
#   ANDROID_NDK=/path/to/android-ndk-r26 ./build.sh          (arm64, API 29)
#   ANDROID_NDK=... ARCH=armv7a API=24 ./build.sh
#
# Output: fieldtap-diagd-<arch>, a static binary you can `adb push` and run as
# root, or pass to `fieldtap capture --adb --helper device/fieldtap-diagd-arm64`.
set -e
cd "$(dirname "$0")"
: "${ANDROID_NDK:?set ANDROID_NDK to the NDK root}"
ARCH="${ARCH:-aarch64}"
API="${API:-29}"
case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
  *) HOST=linux-x86_64 ;;
esac
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST/bin"
case "$ARCH" in
  aarch64|arm64) TRIPLE=aarch64-linux-android; OUT=fieldtap-diagd-arm64 ;;
  armv7a|arm) TRIPLE=armv7a-linux-androideabi; OUT=fieldtap-diagd-arm ;;
  *) echo "unknown ARCH $ARCH" >&2; exit 2 ;;
esac
CC="$TOOLCHAIN/${TRIPLE}${API}-clang"
[ -x "$CC" ] || CC="$CC.cmd"
"$CC" -O2 -Wall -Wextra -static -o "$OUT" fieldtap-diagd.c
echo "built $OUT"
