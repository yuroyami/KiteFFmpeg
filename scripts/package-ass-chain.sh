#!/usr/bin/env bash
#
# Packages one target's libass chain install into the release asset kiteplayer-libass downloads.
#
#   scripts/package-ass-chain.sh <target> [<target> ...]     e.g. macos-arm64 android-arm64
#   scripts/package-ass-chain.sh --all
#
# Reads native-libs/deps/<target>/ass-chain (the output of :kiteffmpeg:buildAssChainFor<Target>)
# and writes dist/ass-chain-<target>.zip plus dist/ass-chain-<target>.zip.sha256. The zip holds the
# install's include/ and lib/ trees and the four libraries' license texts, taken from the vendor
# checkouts the chain was built from: libass (ISC), HarfBuzz (MIT), FreeType (FTL), FriBidi
# (LGPL-2.1-or-later). The archives are stripped of timestamps and ordered, so the same install
# produces the same bytes and the pin in KitePlayer's kiteplayer-libass/ass-chain.sha256 holds.
#
# Attach the zips to the release named by kiteplayer-libass/build.gradle.kts (assChainReleaseTag),
# for example:  gh release create ass-chain-r1 dist/ass-chain-*.zip dist/ass-chain-*.sha256
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "${1:-}" = "" ]; then
  echo "usage: $0 <target> [<target> ...] | --all" >&2
  exit 2
fi
if [ "$1" = "--all" ]; then
  targets=()
  for dir in native-libs/deps/*/ass-chain; do
    [ -f "$dir/lib/libass.a" ] && targets+=("$(basename "$(dirname "$dir")")")
  done
else
  targets=("$@")
fi

mkdir -p dist
for target in "${targets[@]}"; do
  src="native-libs/deps/${target}/ass-chain"
  for lib in libass libharfbuzz libfreetype libfribidi; do
    if [ ! -f "${src}/lib/${lib}.a" ]; then
      echo "::error::${src}/lib/${lib}.a is missing; run :kiteffmpeg:buildAssChainFor<Target> first" >&2
      exit 1
    fi
  done
  [ -f "${src}/include/ass/ass.h" ] || { echo "::error::${src}/include/ass/ass.h is missing" >&2; exit 1; }

  stage="$(mktemp -d)"
  trap 'rm -rf "${stage}"' EXIT
  cp -R "${src}/include" "${stage}/include"
  mkdir -p "${stage}/lib"
  cp "${src}/lib/"*.a "${stage}/lib/"
  # The .la and pkg-config files carry the build machine's paths and serve no consumer of the zip.
  mkdir -p "${stage}/licenses"
  cp vendor/libass/COPYING "${stage}/licenses/libass-COPYING"
  cp vendor/harfbuzz/COPYING "${stage}/licenses/harfbuzz-COPYING"
  cp vendor/freetype/docs/FTL.TXT "${stage}/licenses/freetype-FTL.TXT"
  cp vendor/fribidi/COPYING "${stage}/licenses/fribidi-COPYING"
  {
    echo "ass-chain for ${target}"
    echo "built by KiteFFmpeg :kiteffmpeg:buildAssChainFor* from:"
    for name in fribidi freetype harfbuzz libass; do
      echo "  ${name} $(git -C "vendor/${name}" describe --tags --always 2>/dev/null || echo unknown)"
    done
  } > "${stage}/CHAIN.txt"

  out="dist/ass-chain-${target}.zip"
  rm -f "${out}" "${out}.sha256"
  # -X drops extra attributes, -D drops directory entries, and the file list is sorted, so the zip
  # is the same bytes for the same install. Timestamps are pinned to the epoch for the same reason.
  ( cd "${stage}" && find . -type f -print0 | sort -z | xargs -0 touch -t 198001010000 && \
    find . -type f | sort | zip -X -D -q "${OLDPWD}/${out}" -@ )
  ( cd dist && shasum -a 256 "ass-chain-${target}.zip" > "ass-chain-${target}.zip.sha256" )
  rm -rf "${stage}"
  trap - EXIT
  echo "packaged ${out} ($(wc -c < "${out}" | tr -d ' ') bytes): $(cut -d' ' -f1 "${out}.sha256")"
done
