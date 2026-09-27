#!/usr/bin/env bash
# The local macOS arm64 commit gate. CI owns execution on other host operating systems.
# Usage: ./scripts/check-gate.sh tier1|tier2 [--from=STEP] [--dry-run]
# CONTRIBUTING.md says which tier a change needs.
set -euo pipefail

TIER="${1:-}"
case "$TIER" in tier1|tier2) ;; *) echo "usage: $0 tier1|tier2 [--from=STEP] [--dry-run]" >&2; exit 2 ;; esac
shift
DRY_RUN=false
FROM=""
for option in "$@"; do
    case "$option" in
        --dry-run) DRY_RUN=true ;;
        --from=*) FROM="${option#--from=}" ;;
        *) echo "unknown option: $option" >&2; exit 2 ;;
    esac
done

GATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$GATE_ROOT"

run() {
    printf 'gate:'
    printf ' %q' "$@"
    printf '\n'
    if ! "$DRY_RUN"; then "$@"; fi
}
gradle() { run ./gradlew --console=plain --stacktrace "$@"; }

scan_em_dashes() {
    # git grep reads the working copies of tracked files. Add new files to the index before the
    # final commit gate.
    local status
    if git grep -n "$(printf '\342\200\224')"; then
        echo 'Em dash found in tracked source.' >&2
        return 1
    else
        status=$?
        [ "$status" -eq 1 ]
    fi
}

if ! "$DRY_RUN"; then
    [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || {
        echo 'The local gate requires a macOS arm64 host; CI covers other hosts.' >&2
        exit 2
    }
fi

base() {
    gradle checkCinteropCoupling :kiteffmpeg:checkFFmpegRecipes
    run native/kitecodec-c/scripts/check-deleted-surface.sh
    run native/kitecodec-jni/scripts/source-discipline.sh
    # run-c-tests.sh never builds, so the build comes first.
    run native/kitecodec-c/scripts/build-host.sh plain
    run native/kitecodec-c/scripts/run-c-tests.sh plain
    run scan_em_dashes
}
ratchets() {
    # The committed API dump covers every klib target, so this needs all eleven native trees.
    gradle :kiteffmpeg:apiCheck -Pkiteffmpeg.requireAllTargets=true
    # The shared native source sets compile only as metadata, which apiCheck never builds.
    gradle -Pkiteffmpeg.requireAllTargets=true \
        :kiteffmpeg:compileNativeMainKotlinMetadata :kiteffmpeg:compileAppleMainKotlinMetadata \
        :kiteffmpeg:compileIosMainKotlinMetadata :kiteffmpeg:compileMacosMainKotlinMetadata \
        :kiteffmpeg:compileLinuxMainKotlinMetadata :kiteffmpeg:compileAndroidNativeMainKotlinMetadata
    gradle :kiteffmpeg:checkWasmBindingMirror checkReleaseTargetMirror
    run ./scripts/check-dependency-hygiene.sh
}
cinterop_metadata() {
    gradle :kiteffmpeg:cinteropFfmpegMacosArm64 -Pkiteffmpeg.hostTargetsOnly=true
    run native/kitecodec-c/scripts/klib-metadata-diff.sh --check
}
build_logic() { gradle :buildSrc:test; }
c_sanitizers() {
    # The interpose mode reuses the plain build from base. When resuming here, that build must exist.
    run native/kitecodec-c/scripts/run-c-tests.sh interpose
    run native/kitecodec-c/scripts/symbol-audit.sh --host
    for variant in asan tsan; do
        run native/kitecodec-c/scripts/build-host.sh "$variant"
        run native/kitecodec-c/scripts/run-c-tests.sh "$variant"
    done
    run native/kitecodec-c/scripts/replay-corpus.sh
}
macos() { gradle :kiteffmpeg:macosArm64Test; }
jvm() { gradle :kiteffmpeg:jvmTest; }
jni() {
    # The phone target scope is the only one that registers these tasks. It needs the Android SDK
    # and NDK, and the macOS, iOS and Android FFmpeg trees under native-libs/.
    gradle -Pkiteffmpeg.phoneTargetsOnly=true :kiteffmpeg:jniJvmTest :kiteffmpeg:compareJvmNativeContract
}
e2e() {
    gradle :kiteffmpeg-sample:linkDebugExecutableMacosArm64
    run scripts/e2e.sh kiteffmpeg-sample/build/bin/macosArm64/debugExecutable/kiteffmpeg-sample.kexe
}
linux() { run ./scripts/linux-tests.sh; }

STEPS=(base)
if [ "$TIER" = tier2 ]; then
    STEPS=(base ratchets cinterop_metadata build_logic c_sanitizers macos jvm jni e2e linux)
fi
FROM="${FROM:-${STEPS[0]}}"
case " ${STEPS[*]} " in
    *" $FROM "*) ;;
    *) echo "unknown step '$FROM'; available: ${STEPS[*]}" >&2; exit 2 ;;
esac
started=false
for step in "${STEPS[@]}"; do
    if [ "$step" = "$FROM" ]; then started=true; fi
    if "$started"; then
        echo "gate step: $step"
        "$step"
    fi
done

if "$DRY_RUN"; then
    echo "$TIER command plan only; no checks ran."
elif [ "$FROM" != "${STEPS[0]}" ]; then
    echo "$TIER passed from $FROM. Earlier steps did not run; retain their previous evidence."
else
    echo "$TIER passed. Physical-device and browser execution are separate evidence."
fi
