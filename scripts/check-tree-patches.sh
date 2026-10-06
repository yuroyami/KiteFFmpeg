#!/usr/bin/env bash
#
# Fails when an FFmpeg tree was not built with exactly the patches in native/patches/ffmpeg (#175).
#
# Usage:  scripts/check-tree-patches.sh <tree> [<tree> ...]
#         Each <tree> is an FFmpeg install, such as native-libs/lgpl/linux-x64, whose
#         lib/kiteffmpeg/ffmpeg-patches.txt the build task wrote.
#
# WHY. A prebuilt tree is fetched from the release FFMPEG_ASSET_TAG names, and that release does
# not move when a patch is added. Nothing else compares a fetched tree's patches with the
# repository's: checkFFmpegRecipes compares configure flags only, and native-sbom.sh refuses a
# patch the archives carry that the repository lacks, not the other way round. The ffmpeg-n9.0.2-r2
# trees carried 2 of 15 patches, so every job linked FFmpeg without the other 13, and a publish
# stopped only after its longest build, at the web zip's bill of materials. This runs right after
# the fetch and names what differs.
#
# A patch counts by its file name and its SHA-256, in both directions: one the repository holds
# that the tree lacks, one the tree carries that the repository does not, and one whose digest
# moved, which is a patch edited after the tree was built.
set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "check-tree-patches.sh: name at least one tree, such as native-libs/lgpl/linux-x64" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCHES="$ROOT/native/patches/ffmpeg"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# What the repository holds, one "<name>  sha256=<digest>" line each in name order, which is the
# order the build applies them and the form it records them in.
expected=""
for patch in "$PATCHES"/*.patch; do
    [ -f "$patch" ] || continue
    expected+="$(basename "$patch")  sha256=$(sha256_of "$patch")"$'\n'
done
expected="$(printf '%s' "$expected" | sed '/^$/d' | sort)"

failed=0
for tree in "$@"; do
    record="$tree/lib/kiteffmpeg/ffmpeg-patches.txt"
    if [ ! -f "$record" ]; then
        echo "check-tree-patches.sh: $tree has no lib/kiteffmpeg/ffmpeg-patches.txt, so nothing says which patches it carries" >&2
        failed=1
        continue
    fi
    carried="$(tr -d '\r' < "$record" | awk '!/^#/ && NF && $0 != "(none)"' | sort)"
    if [ "$carried" = "$expected" ]; then
        echo "check-tree-patches.sh: $tree carries the $(printf '%s\n' "$expected" | sed '/^$/d' | wc -l | tr -d ' ') committed patches"
        continue
    fi
    failed=1
    echo "check-tree-patches.sh: $tree was built with other FFmpeg patches than native/patches/ffmpeg holds:" >&2
    missing="$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$carried") | sed '/^$/d')"
    extra="$(comm -13 <(printf '%s\n' "$expected") <(printf '%s\n' "$carried") | sed '/^$/d')"
    if [ -n "$missing" ]; then
        echo "  not in the tree, or changed since it was built:" >&2
        printf '%s\n' "$missing" | sed 's/^/    /' >&2
    fi
    if [ -n "$extra" ]; then
        echo "  in the tree but not in the repository, or an older copy:" >&2
        printf '%s\n' "$extra" | sed 's/^/    /' >&2
    fi
    echo "  fix: rebuild the prebuilt trees with release-binaries.yml and raise FFMPEG_ASSET_TAG, or bake this tree with :kiteffmpeg:buildFFmpegFor<Target>" >&2
done
exit "$failed"
