#!/usr/bin/env bash
#
# Builds and runs the libass chain's own test against one built chain (#152).
#
#   scripts/test-ass-chain.sh <target>      the host's own target: macos-arm64 or linux-x64
#
# Compiles native/ass-chain/test_glyph_fallback.c against native-libs/deps/<target>/ass-chain, the
# output of :kiteffmpeg:buildAssChainFor<Target> or an unpacked ass-chain-<target>.zip, which has
# the same layout, and runs it with the fonts in native/ass-chain/fonts. Only a chain this machine
# can execute can be tested, so the other targets are covered by the same source and patches.
set -euo pipefail

cd "$(dirname "$0")/.."

target="${1:?usage: $0 <target>}"
chain="native-libs/deps/${target}/ass-chain"
for lib in libass libharfbuzz libfreetype libfribidi; do
  if [ ! -f "${chain}/lib/${lib}.a" ]; then
    echo "::error::${chain}/lib/${lib}.a is missing; run :kiteffmpeg:buildAssChainFor<Target> first" >&2
    exit 1
  fi
done

# HarfBuzz is C++, and on Apple libass also carries its CoreText provider, which the test does not
# select but whose code is in the archive.
case "$(uname -s)" in
  Darwin) system=(-lc++ -liconv -framework CoreText -framework CoreFoundation -framework CoreGraphics) ;;
  *) system=(-lstdc++ -lpthread) ;;
esac

out="$(mktemp -d)"
trap 'rm -rf "${out}"' EXIT
"${CC:-cc}" -std=c11 -O1 -Wall -Wextra -Werror -I"${chain}/include" \
  native/ass-chain/test_glyph_fallback.c \
  "${chain}/lib/libass.a" "${chain}/lib/libharfbuzz.a" "${chain}/lib/libfreetype.a" "${chain}/lib/libfribidi.a" \
  -lz -lm "${system[@]}" -o "${out}/test_glyph_fallback"
"${out}/test_glyph_fallback" native/ass-chain/fonts
