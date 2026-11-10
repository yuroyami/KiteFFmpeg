#!/usr/bin/env bash
# Fails unless ci.yml, the publish inputs action that publish.yml runs, and release-ass-chain.yml
# pin one and the same emscripten, so the compiler CI tests the web codec modules with is the one
# that builds what ships (#227, #228).
set -euo pipefail
cd "$(dirname "$0")/.."

versions=""
for file in .github/workflows/ci.yml .github/actions/publish-inputs/action.yml .github/workflows/release-ass-chain.yml; do
  found=$(sed -n 's/^ *EMSCRIPTEN_VERSION: *"\([^"]*\)".*/\1/p' "${file}" | sort -u)
  count=$(printf '%s\n' "${found}" | grep -c . || true)
  if [ "${count}" -ne 1 ]; then
    echo "${file} must pin exactly one EMSCRIPTEN_VERSION, found: ${found:-none}" >&2
    exit 1
  fi
  if grep -q 'brew install emscripten' "${file}"; then
    echo "${file} installs Homebrew's emscripten, which no pin controls" >&2
    exit 1
  fi
  echo "${file}: emscripten ${found}"
  versions="${versions}${found}"$'\n'
done
if grep -q 'brew install emscripten' .github/workflows/publish.yml; then
  echo ".github/workflows/publish.yml installs Homebrew's emscripten, which no pin controls" >&2
  exit 1
fi
if [ "$(printf '%s' "${versions}" | sort -u | grep -c .)" -ne 1 ]; then
  echo "the workflows pin different emscripten versions" >&2
  exit 1
fi
