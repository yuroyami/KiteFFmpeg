#!/usr/bin/env bash
#
# Writes the bill of materials for the native code that KiteFFmpeg's published artifacts embed,
# as an SPDX 2.3 JSON document.
#
# Every published artifact that runs native code embeds FFmpeg and the dav1d AV1 decoder, linked
# statically: the per-target klibs, the native library in the JVM jar and the JNI libraries in the
# Android AAR. Both come from the prebuilt archives on this repository's FFmpeg release, one
# archive for each target. For each archive the document records its download location and its
# SHA-256 digest. For FFmpeg and for dav1d it records the version, the source repository and tag,
# and the licence. For FFmpeg it also records each patch that the archives say was applied.
#
#   ./scripts/native-sbom.sh                  # writes build/sbom/kiteffmpeg-<VERSION>-native.spdx.json
#   ./scripts/native-sbom.sh --out FILE       # writes FILE
#   ./scripts/native-sbom.sh --archives DIR   # reads the archives from DIR, and downloads the missing ones into it
#
# The archives are the ones that FFMPEG_VERSION and FFMPEG_ASSET_TAG in .github/workflows/publish.yml
# name, one for each target of the TargetTriple enum in buildSrc. Each archive needs its .sha256
# file beside it. The script refuses:
#
#   - an archive whose SHA-256 digest differs from its .sha256 file,
#   - an archive built from another FFmpeg version than FFMPEG_VERSION,
#   - an archive configured with --enable-gpl, --enable-nonfree or --enable-version3, because the
#     document then names the wrong licence,
#   - a dav1d that is not the tag BuildDav1dTask pins,
#   - archives that disagree on the FFmpeg commit, the dav1d version or the patches,
#   - a patch that native/patches/ffmpeg does not hold at the same digest, because NOTICE promises
#     every patch in this repository.
#
# Needs curl, unzip, shasum, strings and jq.
#
# Not listed: zlib, which the artifacts link from the platform and do not bundle, and the toolchain
# runtime code that every shared library links.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/publish.yml"
PATCHES="$ROOT/native/patches/ffmpeg"
REPOSITORY="yuroyami/KiteFFmpeg"

usage() { echo "usage: $0 [--out FILE] [--archives DIR]" >&2; exit 2; }
refuse() { echo "native-sbom.sh: $*" >&2; exit 1; }

out=""
archives=""
while [ $# -gt 0 ]; do
    case "$1" in
        --out) [ $# -ge 2 ] || usage; out="$2"; shift 2 ;;
        --archives) [ $# -ge 2 ] || usage; archives="$2"; shift 2 ;;
        *) usage ;;
    esac
done

# One value from a file, or a refusal naming what is missing. More than one match is a refusal
# too, because the script cannot tell which one the build uses.
single() {
    local what="$1" value
    value="$(cat)"
    [ -n "$value" ] || refuse "could not read $what"
    [ "$(printf '%s\n' "$value" | wc -l | tr -d ' ')" = 1 ] || refuse "found more than one $what"
    printf '%s' "$value"
}

version=$(sed -n 's/^VERSION=//p' "$ROOT/gradle.properties" | tr -d ' \t\r' | single "VERSION in gradle.properties")
ffmpeg_version=$(sed -n 's/^  FFMPEG_VERSION: *//p' "$WORKFLOW" | tr -d '\r' | single "FFMPEG_VERSION in publish.yml")
asset_tag=$(sed -n 's/^  FFMPEG_ASSET_TAG: *//p' "$WORKFLOW" | tr -d '\r' | single "FFMPEG_ASSET_TAG in publish.yml")
dav1d_tag=$(sed -n 's/^ *const val DEFAULT_SOURCE_REF = "\(.*\)"$/\1/p' \
    "$ROOT/buildSrc/src/main/kotlin/BuildDav1dTask.kt" | single "the dav1d tag in BuildDav1dTask")
# The dirName of each TargetTriple entry, for example MacosArm64("macos-arm64", "MacosArm64").
triples=$(awk '/^enum class TargetTriple/ { inside = 1; next }
    inside && /^    ;/ { exit }
    inside && /^    [A-Za-z0-9]+\("[a-z0-9-]+"/ { split($0, part, "\""); print part[2] }' \
    "$ROOT/buildSrc/src/main/kotlin/FFmpegPaths.kt")
[ -n "$triples" ] || refuse "could not read the TargetTriple enum"
out="${out:-$ROOT/build/sbom/kiteffmpeg-$version-native.spdx.json}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
[ -n "$archives" ] || archives="$work/archives"
mkdir -p "$archives"

# A field of BUILD-INFO.txt, such as "FFmpeg version:   n8.1.2".
field() { sed -n "s/^$1: *//p" | head -1 | tr -d '\r'; }

# The dav1d tag with its dots escaped, and optionally the suffix that `git describe` adds, as the
# version string reads inside libdav1d.a: 1.5.4-0-g54706fc.
dav1d_pattern="$(printf '%s' "$dav1d_tag" | sed 's/\./\\./g')(-[0-9]+-g[0-9a-f]+)?"

records="$work/archives.jsonl"
: > "$records"
base="https://github.com/$REPOSITORY/releases/download/$asset_tag"
for triple in $triples; do
    asset="ffmpeg-$ffmpeg_version-lgpl-$triple.zip"
    url="$base/$asset"
    [ -f "$archives/$asset" ] || curl -fsSL --retry 3 -o "$archives/$asset" "$url"
    [ -f "$archives/$asset.sha256" ] || curl -fsSL --retry 3 -o "$archives/$asset.sha256" "$url.sha256"

    expected=$(awk 'NR == 1 { print $1 }' "$archives/$asset.sha256")
    digest=$(shasum -a 256 "$archives/$asset" | cut -d' ' -f1)
    [ "$digest" = "$expected" ] || refuse "$asset has digest $digest, but $asset.sha256 says $expected"

    info=$(unzip -p "$archives/$asset" BUILD-INFO.txt) || refuse "$asset holds no BUILD-INFO.txt"
    built=$(field "FFmpeg version" <<<"$info")
    [ "$built" = "$ffmpeg_version" ] || refuse "$asset was built from FFmpeg $built, but FFMPEG_VERSION is $ffmpeg_version"
    # The header the archive's own libraries were compiled with, which the release tarball spells
    # without the n.
    header=$({ unzip -p "$archives/$asset" include/libavutil/ffversion.h 2>/dev/null || true; } |
        sed -n 's/^#define FFMPEG_VERSION "\(.*\)"$/\1/p')
    [ "$header" = "$ffmpeg_version" ] || [ "$header" = "${ffmpeg_version#n}" ] ||
        refuse "$asset holds FFmpeg headers for '$header', not $ffmpeg_version"
    commit=$(field "Git commit" <<<"$info")
    [ -n "$commit" ] || refuse "$asset names no FFmpeg commit in BUILD-INFO.txt"
    configure=$(field "Configure" <<<"$info")
    [ -n "$configure" ] || refuse "$asset names no configure line in BUILD-INFO.txt"
    for flag in --enable-gpl --enable-nonfree --enable-version3; do
        case " $configure " in
            *" $flag "*) refuse "$asset was configured with $flag, so its licence is not LGPL-2.1-or-later" ;;
        esac
    done

    # The patch record the build writes beside the configure line: a comment, then "(none)" or
    # one "<name>  sha256=<digest>" line per patch, in the order they were applied.
    record=$(unzip -p "$archives/$asset" lib/kiteffmpeg/ffmpeg-patches.txt) ||
        refuse "$asset holds no lib/kiteffmpeg/ffmpeg-patches.txt"
    patches=$(printf '%s\n' "$record" | tr -d '\r' | awk '!/^#/ && NF && $0 != "(none)"')
    if [ -n "$patches" ] && printf '%s\n' "$patches" | grep -qvE '^[^ ]+  sha256=[0-9a-f]{64}$'; then
        refuse "$asset has a patch record this script cannot read: $patches"
    fi

    unzip -o -q "$archives/$asset" lib/libdav1d.a -d "$work/$triple" || refuse "$asset holds no lib/libdav1d.a"
    dav1d=$(strings -a "$work/$triple/lib/libdav1d.a" | { grep -xE "$dav1d_pattern" || true; } | sort -u)
    [ -n "$dav1d" ] || refuse "$asset holds a libdav1d.a that is not dav1d $dav1d_tag"
    [ "$(printf '%s\n' "$dav1d" | wc -l | tr -d ' ')" = 1 ] || refuse "$asset holds more than one dav1d version: $dav1d"
    rm -rf "${work:?}/$triple"

    jq -cn --arg asset "$asset" --arg triple "$triple" --arg url "$url" --arg digest "$digest" \
        --arg commit "$commit" --arg dav1d "$dav1d" --arg patches "$patches" \
        '{asset: $asset, triple: $triple, url: $url, digest: $digest,
          built: {commit: $commit, dav1d: $dav1d, patches: $patches}}' >> "$records"
done

if [ "$(jq -c '.built' "$records" | sort -u | wc -l | tr -d ' ')" != 1 ]; then
    echo "native-sbom.sh: the archives were not built from the same sources:" >&2
    jq -r '"  \(.triple): \(.built)"' "$records" >&2
    exit 1
fi
commit=$(head -1 "$records" | jq -r '.built.commit')
dav1d=$(head -1 "$records" | jq -r '.built.dav1d')
patches=$(head -1 "$records" | jq -r '.built.patches')

# Each patch the archives carry, as it is committed here: name, SHA-1 and SHA-256.
patch_records="$work/patches.jsonl"
: > "$patch_records"
if [ -n "$patches" ]; then
    while read -r name digest_field; do
        recorded="${digest_field#sha256=}"
        [ -f "$PATCHES/$name" ] || refuse "the archives carry $name, which native/patches/ffmpeg does not hold"
        sha256=$(shasum -a 256 "$PATCHES/$name" | cut -d' ' -f1)
        [ "$sha256" = "$recorded" ] ||
            refuse "the archives carry $name with digest $recorded, but native/patches/ffmpeg holds $sha256"
        sha1=$(shasum -a 1 "$PATCHES/$name" | cut -d' ' -f1)
        jq -cn --arg name "$name" --arg sha1 "$sha1" --arg sha256 "$sha256" \
            '{name: $name, sha1: $sha1, sha256: $sha256}' >> "$patch_records"
    done <<<"$patches"
fi

namespace_id=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || true)
mkdir -p "$(dirname "$out")"
jq -n --slurpfile archives "$records" --slurpfile patches "$patch_records" \
    --arg version "$version" --arg ffmpeg "$ffmpeg_version" --arg release "$asset_tag" \
    --arg commit "$commit" --arg dav1d_tag "$dav1d_tag" --arg dav1d "$dav1d" \
    --arg namespace "${namespace_id:-$(date -u +%Y%m%d%H%M%S)}" \
    --arg created "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
    def plain: sub("^n"; "");
    def spdxid: gsub("[^A-Za-z0-9.-]"; "-");
    def libraries: [
        {id: "ffmpeg", name: "FFmpeg", tag: $ffmpeg, version: ($ffmpeg | plain),
         source: "https://github.com/FFmpeg/FFmpeg", homepage: "https://ffmpeg.org",
         licence: "LGPL-2.1-or-later", cpe: "cpe:2.3:a:ffmpeg:ffmpeg:\($ffmpeg | plain):*:*:*:*:*:*:*",
         sourceInfo: ("Built from the \($ffmpeg) tag of https://github.com/FFmpeg/FFmpeg, commit \($commit), configured without --enable-gpl, --enable-nonfree or --enable-version3. "
             + (if ($patches | length) > 0 then "The files that are PATCH_APPLIED to this package were applied to the source before configure."
                else "No patch was applied." end))},
        {id: "dav1d", name: "dav1d", tag: $dav1d_tag, version: $dav1d_tag,
         source: "https://code.videolan.org/videolan/dav1d.git", homepage: "https://code.videolan.org/videolan/dav1d",
         licence: "BSD-2-Clause", cpe: "cpe:2.3:a:videolan:dav1d:\($dav1d_tag):*:*:*:*:*:*:*",
         sourceInfo: "Built from the \($dav1d_tag) tag of https://code.videolan.org/videolan/dav1d.git. The libdav1d.a in every archive reports \($dav1d)."}
    ];
    {
        spdxVersion: "SPDX-2.3",
        dataLicense: "CC0-1.0",
        SPDXID: "SPDXRef-DOCUMENT",
        name: "KiteFFmpeg \($version) embedded native code",
        documentNamespace: "https://github.com/yuroyami/KiteFFmpeg/spdx/kiteffmpeg-\($version)-native-\($namespace)",
        creationInfo: {created: $created, creators: ["Tool: KiteFFmpeg scripts/native-sbom.sh"]},
        packages: (
            [{
                SPDXID: "SPDXRef-kiteffmpeg",
                name: "io.github.yuroyami:kiteffmpeg",
                versionInfo: $version,
                downloadLocation: "https://repo1.maven.org/maven2/io/github/yuroyami/kiteffmpeg/\($version)/",
                homepage: "https://github.com/yuroyami/KiteFFmpeg",
                licenseDeclared: "Apache-2.0",
                licenseConcluded: "NOASSERTION",
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                comment: "Its klibs, the native library in its JVM jar and the JNI libraries in its Android AAR link the libraries below statically. Each package names its own licence."
            }]
            + [libraries[] | {
                SPDXID: "SPDXRef-\(.id)",
                name: .name,
                versionInfo: .version,
                downloadLocation: "git+\(.source)@\(.tag)",
                homepage: .homepage,
                sourceInfo: .sourceInfo,
                licenseDeclared: .licence,
                licenseConcluded: .licence,
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                externalRefs: [{referenceCategory: "SECURITY", referenceType: "cpe23Type", referenceLocator: .cpe}]
            }]
            + [$archives[] | {
                SPDXID: "SPDXRef-archive-\(.triple)",
                name: .asset,
                versionInfo: $release,
                downloadLocation: .url,
                checksums: [{algorithm: "SHA256", checksumValue: .digest}],
                licenseDeclared: "NOASSERTION",
                licenseConcluded: "NOASSERTION",
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                comment: "Static FFmpeg and dav1d libraries for \(.triple), built by the release-binaries workflow of this repository. Its BUILD-INFO.txt names the FFmpeg commit and the configure line."
            }]
        ),
        files: [$patches[] | {
            SPDXID: "SPDXRef-patch-\(.name | spdxid)",
            fileName: "./native/patches/ffmpeg/\(.name)",
            checksums: [{algorithm: "SHA1", checksumValue: .sha1}, {algorithm: "SHA256", checksumValue: .sha256}],
            licenseConcluded: "NOASSERTION",
            copyrightText: "NOASSERTION",
            comment: "Applied to the FFmpeg source before configure, in name order. Committed in this repository."
        }],
        relationships: (
            [{spdxElementId: "SPDXRef-DOCUMENT", relationshipType: "DESCRIBES",
              relatedSpdxElement: "SPDXRef-kiteffmpeg"}]
            + [libraries[] | {spdxElementId: "SPDXRef-kiteffmpeg",
                relationshipType: "STATIC_LINK", relatedSpdxElement: "SPDXRef-\(.id)"}]
            + [$archives[] as $archive | libraries[] | {spdxElementId: "SPDXRef-archive-\($archive.triple)",
                relationshipType: "CONTAINS", relatedSpdxElement: "SPDXRef-\(.id)"}]
            + [$archives[] | {spdxElementId: "SPDXRef-archive-\(.triple)",
                relationshipType: "BUILD_DEPENDENCY_OF", relatedSpdxElement: "SPDXRef-kiteffmpeg"}]
            + [$patches[] | {spdxElementId: "SPDXRef-patch-\(.name | spdxid)",
                relationshipType: "PATCH_APPLIED", relatedSpdxElement: "SPDXRef-ffmpeg"}]
        )
    }' > "$out"

echo "native-sbom.sh: $(jq '.packages | length' "$out") packages and $(jq '.files | length' "$out") patches from $(wc -l < "$records" | tr -d ' ') archives in $out"
