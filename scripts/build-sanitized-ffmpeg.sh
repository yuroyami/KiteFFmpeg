#!/usr/bin/env bash
#
# Builds FFmpeg with AddressSanitizer and libFuzzer's coverage instrumentation, from the recipe of a
# shipped tree and the patches under native/patches/ffmpeg (#205).
#
# Usage:  scripts/build-sanitized-ffmpeg.sh <recipe tree> <ffmpeg source> <install dir>
#         <recipe tree>   a shipped FFmpeg tree, such as native-libs/lgpl/linux-x64. Its
#                         lib/kiteffmpeg/ffmpeg-configure.txt gives the profile: which demuxers,
#                         decoders, filters and protocols the library embeds.
#         <ffmpeg source> a pristine FFmpeg checkout of the pinned release. It is copied, never changed.
#         <install dir>   where the instrumented tree goes, with include/ and lib/, ready for
#                         KC_FFMPEG_PREFIX.
#
# WHY. The prebuilt trees carry the patches but no instrumentation, so libFuzzer steered by the
# helper layer only, and ASan did not see a stray read inside a patched FFmpeg reader. This keeps
# the profile flags of the shipped recipe and replaces only its toolchain: the compiler, the
# cross-compile setup and the install prefix. dav1d is left out, because no patch touches it.
#
# Environment:
#   KC_CC    the clang to build with, default clang
#   KC_JOBS  parallel compile jobs, default the number of processors
set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <recipe tree> <ffmpeg source> <install dir>" >&2
    exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RECIPE="$(cd "$1" && pwd)/lib/kiteffmpeg/ffmpeg-configure.txt"
SOURCE="$(cd "$2" && pwd)"
mkdir -p "$3"
INSTALL="$(cd "$3" && pwd)"
CC="${KC_CC:-clang}"
JOBS="${KC_JOBS:-$(getconf _NPROCESSORS_ONLN)}"

[ -f "$RECIPE" ] || { echo "build-sanitized-ffmpeg.sh: no recipe at $RECIPE" >&2; exit 1; }
[ -x "$SOURCE/configure" ] || { echo "build-sanitized-ffmpeg.sh: no FFmpeg configure in $SOURCE" >&2; exit 1; }

# FFmpeg's configure cannot handle a '#' in its path, so the build runs in the temporary directory.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kiteffmpeg-sanitized-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
cp -R "$SOURCE" "$WORK/source"
rm -rf "$WORK/source/.git"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

record="$WORK/ffmpeg-patches.txt"
echo "# Source patches applied to the scratch FFmpeg before configure, in order." > "$record"
for patch in "$ROOT"/native/patches/ffmpeg/*.patch; do
    [ -f "$patch" ] || continue
    (cd "$WORK/source" && patch -p1 --forward --fuzz=0 --silent -i "$patch")
    echo "$(basename "$patch")  sha256=$(sha256_of "$patch")" >> "$record"
done

# The profile flags of the recipe, one per line, without its toolchain and its dav1d.
python3 -I - "$RECIPE" > "$WORK/profile.txt" <<'EOF'
import shlex, sys
tokens = shlex.split(open(sys.argv[1]).read())[1:]
toolchain = {"--cc", "--cxx", "--ar", "--nm", "--ranlib", "--host-cc", "--prefix", "--pkg-config",
             "--sysroot", "--arch", "--target-os", "--extra-cflags", "--extra-ldflags", "--extra-libs"}
dropped = {"--enable-cross-compile", "--enable-libdav1d", "--enable-decoder=libdav1d"}
for token in tokens:
    if token in dropped or token.split("=", 1)[0] in toolchain:
        continue
    print(token)
EOF
profile=()
while IFS= read -r flag; do profile+=("$flag"); done < "$WORK/profile.txt"

cd "$WORK/source"
# A native build would find the host's libraries, so autodetection is off and the recipe's own
# enables win. The recipes turn it off too since #223; the flag stays for a tree baked before that.
./configure --disable-autodetect "${profile[@]}" \
    --toolchain=clang-asan --cc="$CC" \
    --extra-cflags="-fsanitize=fuzzer-no-link -fno-omit-frame-pointer" \
    --prefix="$INSTALL"
make -j"$JOBS"
make install
mkdir -p "$INSTALL/lib/kiteffmpeg"
cp "$record" "$INSTALL/lib/kiteffmpeg/ffmpeg-patches.txt"
cp "$RECIPE" "$INSTALL/lib/kiteffmpeg/recipe-configure.txt"
echo "build-sanitized-ffmpeg.sh: installed an instrumented FFmpeg at $INSTALL"
