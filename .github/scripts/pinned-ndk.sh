#!/usr/bin/env bash
# Prints the path of the one Android NDK every job builds with, installing it when the runner
# image lacks it (#190). The version is in .github/android-ndk-version.txt and nowhere else.
# Before this the jobs took whatever the image called its latest NDK, so the three Android trees
# of one release were built by two NDKs nine minutes apart, and a new image changed the binaries
# with no commit.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="$(tr -d '[:space:]' < "$repo/.github/android-ndk-version.txt")"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
    echo "pinned-ndk.sh: no Android SDK (ANDROID_HOME and ANDROID_SDK_ROOT are unset)" >&2
    exit 1
fi
dir="$sdk/ndk/$version"
if [ ! -f "$dir/source.properties" ]; then
    sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
    echo "pinned-ndk.sh: installing NDK $version" >&2
    { yes || true; } | "$sdkmanager" --install "ndk;$version" >&2
fi
if ! grep -q "^Pkg.Revision *= *$version\$" "$dir/source.properties"; then
    echo "pinned-ndk.sh: $dir is not NDK $version" >&2
    exit 1
fi
printf '%s\n' "$dir"
