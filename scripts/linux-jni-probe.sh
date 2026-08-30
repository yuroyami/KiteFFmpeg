#!/usr/bin/env bash
# Proves the JVM artifact's LINUX JNI library actually works, by running it (PLANNING.md W-16).
#
# Not "the file is in the jar": a 137 KB library with unresolved FFmpeg symbols passed that check
# once, because ELF -shared allows undefined symbols by default. This loads the library through the
# ordinary loader, with no kiteffmpeg.jni.path override, and asserts what the API answers.
#
#   ./scripts/linux-jni-probe.sh                 # linux/arm64 against the published 0.0.9 jar
#   ./scripts/linux-jni-probe.sh linux/amd64     # the x64 library
#   KITE_JNI_JAR=/path/to.jar ./scripts/linux-jni-probe.sh
set -euo pipefail

PLATFORM="${1:-linux/arm64}"
VERSION="${KITE_JNI_VERSION:-0.0.9}"
JAR="${KITE_JNI_JAR:-$HOME/.m2/repository/io/github/yuroyami/kiteffmpeg-core-jvm/$VERSION/kiteffmpeg-core-jvm-$VERSION.jar}"
IMAGE="${KITE_JDK_IMAGE:-eclipse-temurin:21-jdk}"
[ -f "$JAR" ] || { echo "no jar at $JAR" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/cp"
cp "$JAR" "$WORK/cp/"
for pattern in "kotlin-stdlib-2.*.jar" "kotlinx-coroutines-core-jvm-*.jar" "atomicfu-jvm-*.jar"; do
  found=$(find "$HOME/.gradle/caches" -name "$pattern" 2>/dev/null | grep -v sources | head -1)
  [ -n "$found" ] || { echo "missing dependency matching $pattern" >&2; exit 1; }
  cp "$found" "$WORK/cp/"
done

cat > "$WORK/Probe.java" <<'JAVA'
import io.github.yuroyami.kiteffmpeg.FFmpeg;

public class Probe {
    public static void main(String[] args) {
        if (!FFmpeg.INSTANCE.getIdentity().isAcceptable()) {
            throw new AssertionError("identity refused: " + FFmpeg.INSTANCE.getIdentity().getProvisioning());
        }
        if (!FFmpeg.INSTANCE.hasDecoder("h264")) throw new AssertionError("no h264 decoder");
        if (!FFmpeg.INSTANCE.hasDecoder("hevc")) throw new AssertionError("no hevc decoder");
        System.out.println("OK avcodec=" + FFmpeg.INSTANCE.getVersions().getAvcodec()
            + " on " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    }
}
JAVA

# Docker Desktop's credential helper blocks on the login keychain in a headless session.
DOCKER_CONFIG="${DOCKER_CONFIG:-$(mktemp -d)}"
[ -f "$DOCKER_CONFIG/config.json" ] || echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_CONFIG

# NEVER mount at /lib: that shadows the container's own libc and loader, and every binary then
# fails with "no such file or directory", which reads like a missing file and is not.
docker run --rm --platform "$PLATFORM" --entrypoint java \
  -v "$WORK:/probe" "$IMAGE" -cp "/probe/cp/*:/probe" /probe/Probe.java
