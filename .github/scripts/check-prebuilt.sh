#!/usr/bin/env bash
# Checks each fetched prebuilt FFmpeg zip against the digest committed in
# .github/prebuilt-ffmpeg.sha256 (#188). The release's own .sha256 is not trusted: a rerun of
# release-binaries.yml could replace a zip and its .sha256 together, and every check that read
# the pair would then pass on bytes no commit ever named.
#
# Usage: check-prebuilt.sh <zip>...   (FFMPEG_ASSET_TAG, when set, must name the pinned release)
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pins="$repo/.github/prebuilt-ffmpeg.sha256"
pinned_tag="$(sed -n 's/^release: *//p' "$pins" | tr -d '\r')"
if [ -n "${FFMPEG_ASSET_TAG:-}" ] && [ "$FFMPEG_ASSET_TAG" != "$pinned_tag" ]; then
    echo "check-prebuilt.sh: FFMPEG_ASSET_TAG is $FFMPEG_ASSET_TAG, but $pins pins $pinned_tag" >&2
    echo "  fix: write the digests of $FFMPEG_ASSET_TAG into that file in the same commit" >&2
    exit 1
fi
digest() {
    if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}
for zip in "$@"; do
    name="$(basename "$zip")"
    expected="$(awk -v n="$name" '$2 == n { print $1 }' "$pins")"
    if [ -z "$expected" ]; then
        echo "check-prebuilt.sh: $name has no pinned digest in $pins" >&2
        exit 1
    fi
    actual="$(digest "$zip")"
    if [ "$actual" != "$expected" ]; then
        echo "check-prebuilt.sh: $name has digest $actual, but the pinned one is $expected" >&2
        exit 1
    fi
    echo "$name: pinned digest matches"
done
