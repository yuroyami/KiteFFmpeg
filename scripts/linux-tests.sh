#!/usr/bin/env bash
# Runs KiteFFmpeg's Kotlin/Native test binaries on real Linux, from a macOS host.
#
# Gradle creates linuxX64Test / linuxArm64Test and then permanently disables them on a macOS host,
# so a gate that names those tasks is green by definition. Kotlin/Native CROSS-LINKS the binaries
# here; this script EXECUTES them, in a Linux container, against the cross-built FFmpeg under
# native-libs/lgpl/linux-*. PLANNING.md, register items W-06 and W-11.
#
#   ./scripts/linux-tests.sh                # linuxArm64, native speed on Apple silicon
#   ./scripts/linux-tests.sh linuxX64       # linuxX64, emulated, slow
#
# mingwX64 is NOT here: a PE binary needs Windows, and phase W records Windows as a link claim.
set -euo pipefail

TARGET="${1:-linuxArm64}"
case "$TARGET" in
  linuxArm64) PLATFORM=linux/arm64; LINK_SUFFIX=LinuxArm64 ;;
  linuxX64)   PLATFORM=linux/amd64; LINK_SUFFIX=LinuxX64 ;;
  *) echo "usage: $0 [linuxArm64|linuxX64]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${KITE_LINUX_IMAGE:-debian:bookworm-slim}"
SCOPE=(-Pkiteffmpeg.phoneTargetsOnly=true -Pkiteffmpeg.withDesktopTargets=true)

# Docker Desktop's credential helper blocks on the login keychain in a headless session, which
# hangs every pull. An empty config skips it; these are public images and need no credentials.
DOCKER_CONFIG="${DOCKER_CONFIG:-$(mktemp -d)}"
[ -f "$DOCKER_CONFIG/config.json" ] || echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_CONFIG

echo "== linking :kiteffmpeg-core:linkDebugTest$LINK_SUFFIX"
"$ROOT/gradlew" -p "$ROOT" ":kiteffmpeg-core:linkDebugTest$LINK_SUFFIX" "${SCOPE[@]}"

BINARY="kiteffmpeg-core/build/bin/$TARGET/debugTest/test.kexe"
[ -f "$ROOT/$BINARY" ] || { echo "MISSING $BINARY" >&2; exit 1; }

# TMPDIR is not set in a bare container, and the suite's own temp-file helper refuses to guess.
echo "== running $BINARY on $PLATFORM"
docker run --rm --platform "$PLATFORM" -e TMPDIR=/tmp -v "$ROOT:/w" -w /w "$IMAGE" "./$BINARY" | tail -3
