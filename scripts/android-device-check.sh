#!/usr/bin/env bash
# Builds kiteffmpeg-android-check, installs it on one emulator, and checks its result.
#
# The app loads libkitecodec_jni.so from the kiteffmpeg-android-natives jar and checks the FFmpeg identity.
# Start the emulator first. Use an arm64-v8a or x86_64 image; a 16 KB page image also checks the page alignment.
# The script uses the one running emulator. To use another device, set ANDROID_SERIAL to its serial.
#
#   ./scripts/android-device-check.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ADB="${ADB:-${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb}"
PACKAGE=io.github.yuroyami.kiteffmpeg.check

KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin build -m kiteffmpeg-android-check
APK="$(find build/tasks -path '*kiteffmpeg-android-check*' -name '*.apk' -type f | grep -v -i test | head -1)"
[ -n "$APK" ] || { echo "No APK of kiteffmpeg-android-check under build/tasks" >&2; exit 1; }
echo "== $APK"
unzip -l "$APK" | grep 'lib/.*libkitecodec_jni.so' || { echo "The APK carries no libkitecodec_jni.so" >&2; exit 1; }

# Never a device that the caller did not name: without ANDROID_SERIAL, take the one running emulator.
SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
    emulators="$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')"
    [ "$(printf '%s' "$emulators" | grep -c .)" = 1 ] || {
        echo "Start exactly one emulator, or set ANDROID_SERIAL. Running emulators: ${emulators:-none}" >&2
        exit 1
    }
    SERIAL="$emulators"
fi
adb_device() { "$ADB" -s "$SERIAL" "$@"; }

echo "== $SERIAL abi=$(adb_device shell getprop ro.product.cpu.abi | tr -d '\r') page_size=$(adb_device shell getconf PAGE_SIZE | tr -d '\r')"
adb_device logcat -c
adb_device install -r "$APK" > /dev/null
adb_device shell am start -W -n "$PACKAGE/.CheckActivity" > /dev/null
for _ in $(seq 1 30); do
    line="$(adb_device logcat -d -s KITEFFMPEG_CHECK:I | grep -E 'PASS|FAIL' | tail -1 || true)"
    [ -n "$line" ] && break
    sleep 1
done
adb_device logcat -d | grep -E 'UnsatisfiedLinkError|FATAL EXCEPTION' | head -5 || true
echo "${line:-no result line in the log}"
case "${line:-}" in *PASS*) exit 0 ;; *) exit 1 ;; esac
