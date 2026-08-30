#!/usr/bin/env bash
#
# Build and run the six libFuzzer targets. This is the REAL fuzzer, and it does not run on macOS.
#
# Measured again while writing this script rather than quoted from the plan:
#
#   /usr/bin/clang -fsanitize=fuzzer            Apple clang 17.0.0 (clang-1700.3.19.1)
#     ld: library '.../lib/clang/17/lib/darwin/libclang_rt.fuzzer_osx.a' not found      exit 1
#   ~/.konan/dependencies/llvm-21-aarch64-macos-essentials-97/bin/clang -fsanitize=fuzzer
#     clang version 21.1.6
#     ld: library '.../lib/clang/21/lib/darwin/libclang_rt.fuzzer_osx.a' not found      exit 1
#   /opt/homebrew/opt/llvm/bin/clang            not installed
#
# The runtime archive is absent from both toolchains on this host. Only the header directory
# lib/clang/17/include/fuzzer is present, which is not enough to link. -fsanitize=fuzzer-no-link
# does compile, exit 0, but that is instrumentation with no driver and produces no fuzzing.
#
# So on macOS this script does one useful thing: it detects the missing runtime, says so in one
# sentence, and exits 3 rather than dumping a linker error. The local gate is
# scripts/replay-corpus.sh, which replays a committed corpus through the same target bodies under
# ASan and UBSan. That is a regression test and not a fuzz run, and neither script pretends
# otherwise.
#
# Usage:  ./scripts/run-fuzz.sh [target ...]
#         target names are the stems after fuzz_, for example filter_audio. With none given, all
#         six run, which is what the CI job does.
#
# Environment:
#   KC_CC             compiler to use. Default /usr/bin/clang here, clang-18 or similar in CI.
#   KC_FUZZ_SECONDS   wall clock budget per target, default 300, which is the plan's five minutes.
#   KC_FUZZ_JOBS      parallel libFuzzer workers per target, default 1. CI uses 1 so the five
#                     minutes is five minutes of one process and the budget means what it says.
#   KC_FUZZ_MAX_LEN   maximum generated input length, default 8192. Explicit on purpose: with no
#                     flag libFuzzer derives it from the largest seed, and the D27 length vectors
#                     would push it to 4096 or beyond and spend the budget on padding.
#   KC_FFMPEG_PREFIX  when set, FFmpeg flags come from it instead of pkg-config
#   KC_ARTIFACT_DIR   where crash, timeout and out-of-memory artifacts are written.
#                     Default build/fuzz-artifacts, which the CI job uploads.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# The six targets of plan section 15.3. Keep this list, the fuzz/fuzz_*.c files, the
# fuzz/corpus subdirectories and replay-corpus.sh in agreement.
ALL_TARGETS="filter_video filter_audio codec_option format_option metadata format_name"
TARGETS="${*:-$ALL_TARGETS}"

CC="${KC_CC:-/usr/bin/clang}"
SECONDS_PER_TARGET="${KC_FUZZ_SECONDS:-300}"
JOBS="${KC_FUZZ_JOBS:-1}"
MAX_LEN="${KC_FUZZ_MAX_LEN:-8192}"
ARTIFACTS="${KC_ARTIFACT_DIR:-$ROOT/build/fuzz-artifacts}"

[ -x "$CC" ] || command -v "$CC" >/dev/null 2>&1 || {
    echo "run-fuzz.sh: no compiler at $CC" >&2
    exit 1
}

# ── The preflight, which is the whole point of this script on this machine ─────────────────────
#
# A two line program is enough: if it links, libFuzzer is available; if it does not, the error names
# the missing archive. Checking by compiling is the only honest check. Looking for the file by path
# would guess at the toolchain layout, and asking `clang --print-runtime-dir` answers even when the
# archive it names is absent.
PREFLIGHT_DIR="$ROOT/build/fuzz-preflight"
rm -rf "$PREFLIGHT_DIR"
mkdir -p "$PREFLIGHT_DIR"
cat > "$PREFLIGHT_DIR/preflight.c" <<'EOF'
#include <stddef.h>
#include <stdint.h>
int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) { (void)data; (void)size; return 0; }
EOF
if ! "$CC" -fsanitize=fuzzer -o "$PREFLIGHT_DIR/preflight" "$PREFLIGHT_DIR/preflight.c" \
        > "$PREFLIGHT_DIR/preflight.log" 2>&1; then
    echo "run-fuzz.sh: this toolchain cannot link libFuzzer, so no fuzzing can happen here." >&2
    echo "  compiler   $CC ($("$CC" --version | head -1))" >&2
    echo "  the error:" >&2
    sed 's/^/    /' "$PREFLIGHT_DIR/preflight.log" | head -10 >&2
    echo >&2
    echo "  This is expected on macOS: libclang_rt.fuzzer_osx.a" >&2
    echo "  ships with neither Apple clang nor konan's LLVM. The real fuzzer runs in the" >&2
    echo "  fuzz-linux job of .github/workflows/ci.yml on ubuntu-24.04." >&2
    echo "  The local gate is:  ./scripts/build-host.sh asan && ./scripts/replay-corpus.sh" >&2
    echo "  which replays the committed corpus through the same target bodies under ASan and" >&2
    echo "  UBSan. It is a regression test, not a fuzz run." >&2
    exit 3
fi
echo "run-fuzz.sh: libFuzzer links on this toolchain, proceeding"

# ── FFmpeg flags. Same environment contract as build-host.sh and replay-corpus.sh ──────────────
FFMPEG_LIBS="libavformat libavcodec libavfilter libavutil libswscale libswresample"
if [ -n "${KC_FFMPEG_PREFIX:-}" ]; then
    [ -d "$KC_FFMPEG_PREFIX/include" ] || {
        echo "run-fuzz.sh: KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX has no include directory" >&2
        exit 1
    }
    FF_CFLAGS="-I$KC_FFMPEG_PREFIX/include"
    FF_LDFLAGS="-L$KC_FFMPEG_PREFIX/lib"
    FF_LIBS="-lavformat -lavcodec -lavfilter -lavutil -lswscale -lswresample"
    FF_ORIGIN="KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX"
else
    command -v pkg-config >/dev/null 2>&1 || {
        echo "run-fuzz.sh: pkg-config not found and KC_FFMPEG_PREFIX is not set" >&2
        exit 1
    }
    # FFMPEG_LIBS is a list of six module names and must word split. Annotated rather than
    # quoted, three times below, because quoting it would pass one nonexistent module name.
    # shellcheck disable=SC2086
    pkg-config --exists $FFMPEG_LIBS || {
        echo "run-fuzz.sh: pkg-config cannot find all of: $FFMPEG_LIBS" >&2
        exit 1
    }
    # shellcheck disable=SC2086
    FF_CFLAGS="$(pkg-config --cflags $FFMPEG_LIBS)"
    FF_LDFLAGS=""
    # shellcheck disable=SC2086
    FF_LIBS="$(pkg-config --libs $FFMPEG_LIBS)"
    FF_ORIGIN="pkg-config ($(pkg-config --modversion libavcodec) libavcodec)"
fi

# The fuzz build is its own variant and never reuses build-host.sh's archive. The helper units have
# to carry the same coverage instrumentation as the target, or the fuzzer is guided by the harness
# alone and explores nothing: a coverage build that instruments only the driver is the most common
# way a fuzzing setup ends up doing no work while looking busy.
BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"
FUZZ_FLAGS="-fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer -O1"

OUT="$ROOT/build/fuzz"
OBJ="$OUT/obj"
BIN="$OUT/bin"
CORPUS_WORK="$OUT/corpus"
rm -rf "$OBJ" "$BIN"
mkdir -p "$OBJ" "$BIN" "$CORPUS_WORK" "$ARTIFACTS"

echo "  compiler   $CC ($("$CC" --version | head -1))"
echo "  ffmpeg     $FF_ORIGIN"
echo "  flags      $BASE_FLAGS $FUZZ_FLAGS"
echo "  budget     ${SECONDS_PER_TARGET}s per target, $JOBS job(s), max_len $MAX_LEN"
echo "  artifacts  $ARTIFACTS"
echo

# LeakSanitizer IS available on Linux, unlike here, and the plan asks for it in this job. It is the
# one instrument the macOS side cannot have, so it is the reason the Linux job
# is corroboration and not a duplicate.
export ASAN_OPTIONS="detect_leaks=1:abort_on_error=1:print_stacktrace=1:strict_string_checks=1"
export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1"

HELPER_SOURCES="$(find "$ROOT/src" -maxdepth 1 -name '*.c' | sort)"
[ -n "$HELPER_SOURCES" ] || { echo "run-fuzz.sh: no .c sources under $ROOT/src" >&2; exit 1; }

HELPER_OBJECTS=""
for source in $HELPER_SOURCES; do
    object="$OBJ/$(basename "${source%.c}").o"
    echo "  cc  $(basename "$source")"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $FUZZ_FLAGS $FF_CFLAGS -I "$ROOT/include" -fvisibility=hidden \
        -c "$source" -o "$object"
    HELPER_OBJECTS="$HELPER_OBJECTS $object"
done

echo "  cc  kc_fuzz.c"
# shellcheck disable=SC2086
"$CC" $BASE_FLAGS $FUZZ_FLAGS $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/fuzz" \
    -c "$ROOT/fuzz/kc_fuzz.c" -o "$OBJ/kc_fuzz.o"

# replay_main.c is NOT linked here. libFuzzer supplies its own main(), and linking both would be a
# duplicate symbol. That is the one place the two drivers differ, and it is why the target bodies
# never define main() themselves.
for target in $TARGETS; do
    source="$ROOT/fuzz/fuzz_$target.c"
    [ -f "$source" ] || { echo "run-fuzz.sh: missing $source" >&2; exit 1; }
    echo "  cc  fuzz_$target.c"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $FUZZ_FLAGS $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/fuzz" \
        -c "$source" -o "$OBJ/fuzz_$target.o"
    echo "  ld  fuzz_$target"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $FUZZ_FLAGS -o "$BIN/fuzz_$target" \
        "$OBJ/fuzz_$target.o" "$OBJ/kc_fuzz.o" $HELPER_OBJECTS \
        $FF_LDFLAGS $FF_LIBS
done
echo

passed=""
failed=""

for target in $TARGETS; do
    seeds="$ROOT/fuzz/corpus/$target"
    [ -d "$seeds" ] || { echo "run-fuzz.sh: no corpus directory at $seeds" >&2; failed="$failed $target"; continue; }

    # libFuzzer writes newly interesting inputs into the FIRST corpus directory it is given, so the
    # working copy comes first and the committed seeds second. The committed corpus is never written
    # to by a fuzz run: growing it is a deliberate commit after reading what was added, not a side
    # effect of CI.
    work="$CORPUS_WORK/$target"
    mkdir -p "$work"

    # -jobs is passed only when more than one is asked for, and that is not tidiness. With -jobs=1
    # libFuzzer does not fuzz in this process: it forks a worker and redirects that worker's whole
    # output to fuzz-0.log, so a CI log would show the run's summary and none of its findings. In
    # process is the readable default, and -workers is meaningless without -jobs.
    parallel_flags=""
    if [ "$JOBS" -gt 1 ]; then
        parallel_flags="-jobs=$JOBS -workers=$JOBS"
    fi

    echo "=== $target for ${SECONDS_PER_TARGET}s"
    set +e
    # shellcheck disable=SC2086
    "$BIN/fuzz_$target" \
        -max_total_time="$SECONDS_PER_TARGET" \
        -max_len="$MAX_LEN" \
        -timeout=25 \
        -rss_limit_mb=4096 \
        -malloc_limit_mb=3072 \
        -print_final_stats=1 \
        -artifact_prefix="$ARTIFACTS/$target-" \
        $parallel_flags \
        "$work" "$seeds"
    code=$?
    set -e
    if [ "$code" -eq 0 ]; then
        echo "=== $target: no finding"
        passed="$passed $target"
    else
        echo "=== $target: FINDING, libFuzzer exited $code" >&2
        echo "    artifacts under $ARTIFACTS with the prefix $target-" >&2
        failed="$failed $target"
    fi
    echo
done

count() { echo $# ; }
# shellcheck disable=SC2086
echo "run-fuzz.sh: $(count $passed) targets clean, $(count $failed) with findings"
[ -n "$failed" ] && echo "  findings in: ${failed# }"

if [ -n "$failed" ]; then
    exit 1
fi
exit 0
