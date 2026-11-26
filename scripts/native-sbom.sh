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
# and the licence. For FFmpeg it also records each patch that the archives say was applied. For each
# archive it also records the libraries outside FFmpeg that its configure enabled.
#
#   ./scripts/native-sbom.sh                  # writes build/sbom/kiteffmpeg-<VERSION>-native.spdx.json
#   ./scripts/native-sbom.sh --out FILE       # writes FILE
#   ./scripts/native-sbom.sh --archives DIR   # reads the archives from DIR, and downloads the missing ones into it
#   ./scripts/native-sbom.sh --web-zip FILE   # also records a web jar (kiteffmpeg-web-<VERSION>.jar) or an older web zip; once for each file
#
# The archives are the ones that FFMPEG_VERSION and FFMPEG_ASSET_TAG in .github/workflows/publish.yml
# name, one for each target of the TargetTriple enum in build-plugins/native-build. Each archive needs its .sha256
# file beside it. The script refuses:
#
#   - an archive whose SHA-256 digest differs from its .sha256 file,
#   - an archive built from another FFmpeg version than FFMPEG_VERSION,
#   - an archive configured with --enable-gpl, --enable-nonfree or --enable-version3, because the
#     document then names the wrong licence,
#   - an archive whose BUILD-INFO.txt names other libraries outside FFmpeg than
#     ExternalLibraries.EXPECTED in build-plugins/native-build names for its target, because the
#     document then misses a library,
#   - a dav1d that is not the tag BuildDav1dTask pins,
#   - archives that disagree on the FFmpeg commit, the dav1d version or the patches,
#   - a patch that native/patches/ffmpeg does not hold at the same digest, because NOTICE promises
#     every patch in this repository,
#   - a web zip built from another FFmpeg version, commit or set of patches than the archives, or
#     with no recorded emscripten version. A web zip embeds FFmpeg without dav1d.
#
# Needs curl, unzip, shasum, strings and jq.
#
# Not listed: the libraries that the artifacts link from the platform and do not bundle, and the
# toolchain runtime code that every shared library links. Those libraries are zlib, iconv (glibc's on
# Linux, msys2's libiconv on Windows) and the platform's media frameworks. Each archive names them.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/publish.yml"
PATCHES="$ROOT/native/patches/ffmpeg"
REPOSITORY="yuroyami/KiteFFmpeg"

usage() { echo "usage: $0 [--out FILE] [--archives DIR] [--web-zip FILE]..." >&2; exit 2; }
refuse() { echo "native-sbom.sh: $*" >&2; exit 1; }

out=""
archives=""
web_zips=()
while [ $# -gt 0 ]; do
    case "$1" in
        --out) [ $# -ge 2 ] || usage; out="$2"; shift 2 ;;
        --archives) [ $# -ge 2 ] || usage; archives="$2"; shift 2 ;;
        --web-zip) [ $# -ge 2 ] || usage; web_zips+=("$2"); shift 2 ;;
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

# The `version:` line under `publishing:` (the one under `kotlin:` is the Kotlin version).
version=$(awk '/^  publishing:/ { inside = 1; next } /^  [a-z]/ { inside = 0 }
    inside && /^    version:/ { print $2 }' "$ROOT/library.module-template.yaml" | tr -d '\r' |
    single "the publishing version in library.module-template.yaml")
ffmpeg_version=$(sed -n 's/^  FFMPEG_VERSION: *//p' "$WORKFLOW" | tr -d '\r' | single "FFMPEG_VERSION in publish.yml")
asset_tag=$(sed -n 's/^  FFMPEG_ASSET_TAG: *//p' "$WORKFLOW" | tr -d '\r' | single "FFMPEG_ASSET_TAG in publish.yml")
dav1d_tag=$(sed -n 's/^ *const val DEFAULT_SOURCE_REF = "\(.*\)"$/\1/p' \
    "$ROOT/build-plugins/native-build/src/BuildDav1dTask.kt" | single "the dav1d tag in BuildDav1dTask")
# The dirName of each TargetTriple entry, for example MacosArm64("macos-arm64", "MacosArm64").
triples=$(awk '/^enum class TargetTriple/ { inside = 1; next }
    inside && /^    ;/ { exit }
    inside && /^    [A-Za-z0-9]+\("[a-z0-9-]+"/ { split($0, part, "\""); print part[2] }' \
    "$ROOT/build-plugins/native-build/src/FFmpegPaths.kt")
[ -n "$triples" ] || refuse "could not read the TargetTriple enum"
# The entry name of each TargetTriple beside its dirName, for example "MacosArm64 macos-arm64".
entries=$(awk '/^enum class TargetTriple/ { inside = 1; next }
    inside && /^    ;/ { exit }
    inside && /^    [A-Za-z0-9]+\("[a-z0-9-]+"/ { split($0, part, "\""); name = $1; sub(/\(.*/, "", name); print name, part[2] }' \
    "$ROOT/build-plugins/native-build/src/FFmpegPaths.kt")
# The libraries outside FFmpeg that configure must enable for each target, one entry on each line:
# TargetTriple.MacosArm64 to of("libdav1d zlib | audiotoolbox videotoolbox"),
expected_libraries=$(sed -n 's/^ *TargetTriple\.\([A-Za-z0-9]*\) to of("\([^"]*\)"),$/\1 \2/p' \
    "$ROOT/build-plugins/native-build/src/ExternalLibraries.kt")
[ -n "$expected_libraries" ] || refuse "could not read ExternalLibraries.EXPECTED"
out="${out:-$ROOT/build/sbom/kiteffmpeg-$version-native.spdx.json}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
[ -n "$archives" ] || archives="$work/archives"
mkdir -p "$archives"

# A field of BUILD-INFO.txt, such as "FFmpeg version:   n8.1.2".
field() { sed -n "s/^$1: *//p" | head -1 | tr -d '\r'; }

# Library names separated by spaces, sorted, or "(none)" when there are none.
names() {
    local sorted
    sorted=$(printf '%s\n' $1 | awk 'NF && $0 != "(none)"' | sort | paste -sd' ' -)
    printf '%s' "${sorted:-(none)}"
}

# The dav1d tag with its dots escaped, and optionally the suffix that `git describe` adds, as the
# version string reads inside libdav1d.a: 1.5.5-0-g6c7bee6.
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
    # The digest this commit pins wins over the release's own .sha256, which a rerun of the
    # release could replace together with the zip (#188).
    pinned=$(awk -v n="$asset" '$2 == n { print $1 }' "$ROOT/.github/prebuilt-ffmpeg.sha256")
    [ "$digest" = "$pinned" ] || refuse "$asset has digest $digest, but .github/prebuilt-ffmpeg.sha256 pins ${pinned:-nothing}"

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
    entry=$(awk -v t="$triple" '$2 == t { print $1 }' <<<"$entries")
    spec=$(awk -v e="$entry" '$1 == e { $1 = ""; print }' <<<"$expected_libraries")
    [ -n "$spec" ] || refuse "ExternalLibraries.EXPECTED names no libraries for $triple"
    libraries=$(field "External libraries" <<<"$info")
    hardware=$(field "Hardware acceleration libraries" <<<"$info")
    [ -n "$libraries" ] && [ -n "$hardware" ] ||
        refuse "$asset names no libraries outside FFmpeg in BUILD-INFO.txt, so it was built before #223"
    libraries=$(names "$libraries")
    hardware=$(names "$hardware")
    if [ "$libraries" != "$(names "${spec%%|*}")" ] || [ "$hardware" != "$(names "${spec#*|}")" ]; then
        refuse "$asset links $libraries | $hardware, but ExternalLibraries.EXPECTED names $(names "${spec%%|*}") | $(names "${spec#*|}") for $triple"
    fi

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
        --arg libraries "$libraries" --arg hardware "$hardware" \
        '{asset: $asset, triple: $triple, url: $url, digest: $digest, libraries: $libraries, hardware: $hardware,
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

# Each web zip, when there is one: the same FFmpeg source as the archives, and its own compiler.
web_records="$work/web.jsonl"
: > "$web_records"
for web_zip in ${web_zips[@]+"${web_zips[@]}"}; do
    [ -f "$web_zip" ] || refuse "no web zip at $web_zip"
    web_info=$(unzip -p "$web_zip" build-info/web-build-info.txt) || refuse "$web_zip holds no build-info/web-build-info.txt"
    web_built=$(field "FFmpeg version" <<<"$web_info")
    [ "$web_built" = "$ffmpeg_version" ] || refuse "$web_zip was built from FFmpeg $web_built, but FFMPEG_VERSION is $ffmpeg_version"
    web_commit=$(field "Git commit" <<<"$web_info")
    [ "$web_commit" = "$commit" ] || refuse "$web_zip was built from FFmpeg commit $web_commit, but the archives from $commit"
    emscripten=$(field "Emscripten" <<<"$web_info")
    [ -n "$emscripten" ] && [ "$emscripten" != unknown ] || refuse "$web_zip names no emscripten version"
    web_record=$(unzip -p "$web_zip" build-info/ffmpeg-patches.txt) || refuse "$web_zip holds no build-info/ffmpeg-patches.txt"
    web_patches=$(printf '%s\n' "$web_record" | tr -d '\r' | awk '!/^#/ && NF && $0 != "(none)"')
    [ "$web_patches" = "$patches" ] || refuse "$web_zip carries other FFmpeg patches than the archives"
    # The codec modules the zip holds, which its package names.
    web_modules=$(unzip -Z1 "$web_zip" | grep -E '^[^/]+\.(mjs|wasm)$' | sort | paste -sd' ' -)
    [ -n "$web_modules" ] || refuse "$web_zip holds no codec module"
    web_digest=$(shasum -a 256 "$web_zip" | cut -d' ' -f1)
    web_id="web-zip"
    [ -s "$web_records" ] && web_id="web-zip-$(( $(wc -l < "$web_records") + 1 ))"
    jq -cn --arg id "$web_id" --arg name "$(basename "$web_zip")" --arg digest "$web_digest" \
        --arg emscripten "$emscripten" --arg modules "$web_modules" \
        '{id: $id, name: $name, digest: $digest, emscripten: $emscripten, modules: $modules}' >> "$web_records"
done

namespace_id=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || true)
mkdir -p "$(dirname "$out")"
jq -n --slurpfile archives "$records" --slurpfile patches "$patch_records" --slurpfile web "$web_records" \
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
                comment: ("Its klibs, the native library in its JVM jar and the JNI libraries in its Android AAR link the libraries below statically. "
                    + (if ($web | length) > 0 then "Each web jar (or web zip, up to 0.5.1) links FFmpeg alone. " else "" end)
                    + "Each package names its own licence.")
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
                comment: "Static FFmpeg and dav1d libraries for \(.triple), built by the release-binaries workflow of this repository. Its BUILD-INFO.txt names the FFmpeg commit and the configure line. Its FFmpeg configure enabled these libraries outside FFmpeg: \(.libraries); for hardware acceleration: \(.hardware). Apart from dav1d, they come from the platform and are not in the archive."
            }]
            + [$web[] | {
                SPDXID: "SPDXRef-\(.id)",
                name: .name,
                versionInfo: $version,
                downloadLocation: (if (.name | endswith(".jar"))
                    then "https://repo1.maven.org/maven2/io/github/yuroyami/\(.name | rtrimstr("-\($version).jar"))/\($version)/\(.name)"
                    else "https://repo1.maven.org/maven2/io/github/yuroyami/kiteffmpeg-wasm-js/\($version)/\(.name)" end),
                checksums: [{algorithm: "SHA256", checksumValue: .digest}],
                licenseDeclared: "NOASSERTION",
                licenseConcluded: "NOASSERTION",
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                comment: "Web codec modules: \(.modules). Each embeds FFmpeg \($ffmpeg) at commit \($commit), with the same patches as the archives, compiled and linked by the publish workflow with emscripten \(.emscripten). None carries dav1d."
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
            + [$web[] | {spdxElementId: "SPDXRef-\(.id)",
                relationshipType: "CONTAINS", relatedSpdxElement: "SPDXRef-ffmpeg"}]
            + [$web[] | {spdxElementId: "SPDXRef-\(.id)",
                relationshipType: "PACKAGE_OF", relatedSpdxElement: "SPDXRef-kiteffmpeg"}]
            + [$patches[] | {spdxElementId: "SPDXRef-patch-\(.name | spdxid)",
                relationshipType: "PATCH_APPLIED", relatedSpdxElement: "SPDXRef-ffmpeg"}]
        )
    }' > "$out"

echo "native-sbom.sh: $(jq '.packages | length' "$out") packages and $(jq '.files | length' "$out") patches from $(wc -l < "$records" | tr -d ' ') archives$( [ -s "$web_records" ] && echo " and $(wc -l < "$web_records" | tr -d ' ') web zips") in $out"
