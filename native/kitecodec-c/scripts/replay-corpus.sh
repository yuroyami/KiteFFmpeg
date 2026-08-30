#!/usr/bin/env bash
#
# Replay the committed fuzz corpus through the six fuzz targets, under ASan and UBSan.
#
# This is the LOCAL gate for plan sub-phase B1.5, and it is not a fuzz run. Say it plainly, because
# the difference decides what the result is worth. Coverage-guided fuzzing cannot happen on this
# machine at all: -fsanitize=fuzzer needs libclang_rt.fuzzer_osx.a, which is absent from Apple
# clang 17 and from konan's LLVM 21, and Homebrew LLVM is not installed. Register item B1-13.
# scripts/run-fuzz.sh is the real fuzzer and runs on ubuntu-24.04 in CI. What THIS script earns is
# the other half of B1-13's fix: the same LLVMFuzzerTestOneInput bodies, driven by
# fuzz/replay_main.c over a committed corpus, as an ordinary sanitized regression test that runs in
# every later gate. It discovers nothing. It refuses to forget.
#
# Usage:  ./scripts/replay-corpus.sh [variant] [target ...]
#         ./scripts/replay-corpus.sh --prove-power [variant]
#
#         variant is one of: plain asan tsan. Default asan, which is the gate's variant, because
#         ASan and UBSan are the instruments that make a replay worth running.
#         target names are the stems after fuzz_, for example filter_audio. With none given, all
#         six run, which is what a gate does.
#
# Build the helper archive first:  ./scripts/build-host.sh <variant>
# This script compiles only the fuzz sources; it never builds the helper layer, so a gate cannot
# pass against a helper archive that was never recompiled. Same discipline as run-c-tests.sh, and
# note that build-host.sh deletes build/<variant> on every run, so the order matters.
#
# --prove-power is the answer to "a green fuzzer that has never caught anything is not evidence".
# See prove_power() below.
#
# Environment, same contract as build-host.sh:
#   KC_CC             compiler to use, default /usr/bin/clang
#   KC_FFMPEG_PREFIX  when set, FFmpeg include and library flags come from it instead of pkg-config
#   KC_FFMPEG_LOG     when set to 1, FFmpeg's own diagnostics are kept instead of silenced
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# The six targets of plan section 15.3, in the order sub-phase B1.5 step 1 lists them. Keep this
# list, the fuzz/fuzz_*.c files, the fuzz/corpus subdirectories and run-fuzz.sh in agreement.
ALL_TARGETS="filter_video filter_audio codec_option format_option metadata format_name"

PROVE_POWER=0
if [ "${1:-}" = "--prove-power" ]; then
    PROVE_POWER=1
    shift
fi

VARIANT="${1:-asan}"
case "$VARIANT" in
    plain|asan|tsan)
        # Only consume an argument when one was actually given. `[ $# -gt 0 ] && shift` as the last
        # command of the branch would return 1 with no arguments and `set -e` would end the script
        # before it printed anything, which is the least helpful possible failure.
        if [ $# -gt 0 ]; then shift; fi
        ;;
    *)  echo "replay-corpus.sh: unknown variant '$VARIANT', expected plain, asan or tsan" >&2
        exit 2 ;;
esac
TARGETS="${*:-$ALL_TARGETS}"

CC="${KC_CC:-/usr/bin/clang}"
# A bare NAME resolves through PATH, an explicit path does not. `[ -x clang-18 ]` tests a file
# named clang-18 in the CURRENT DIRECTORY, so the Linux CI job, which sets KC_CC=clang-18 and has
# clang-18 installed at /usr/bin, was told there was no compiler and exited 1 every run after a
# thirty-minute fuzz. Resolve first, then test what was resolved.
case "$CC" in
    */*) ;;                                    # an explicit path, absolute or relative: use as given
    *)   CC="$(command -v "$CC" || true)" ;;   # a bare name: ask PATH, exactly as the shell would
esac
[ -n "$CC" ] && [ -x "$CC" ] || {
    echo "replay-corpus.sh: no compiler at ${KC_CC:-/usr/bin/clang}" >&2
    exit 1
}

# FFmpeg flags. Resolved the same way and from the same environment variables as build-host.sh.
# The duplication is deliberate: this script must not source that one, because that one builds and
# would wipe build/<variant> out from under a replay.
FFMPEG_LIBS="libavformat libavcodec libavfilter libavutil libswscale libswresample"
if [ -n "${KC_FFMPEG_PREFIX:-}" ]; then
    [ -d "$KC_FFMPEG_PREFIX/include" ] || {
        echo "replay-corpus.sh: KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX has no include directory" >&2
        exit 1
    }
    FF_CFLAGS="-I$KC_FFMPEG_PREFIX/include"
    FF_LDFLAGS="-L$KC_FFMPEG_PREFIX/lib"
    FF_LIBS="-lavformat -lavcodec -lavfilter -lavutil -lswscale -lswresample"
    FF_ORIGIN="KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX"
else
    command -v pkg-config >/dev/null 2>&1 || {
        echo "replay-corpus.sh: pkg-config not found and KC_FFMPEG_PREFIX is not set" >&2
        exit 1
    }
    # FFMPEG_LIBS is a list of six module names and must word split. Annotated rather than
    # quoted, three times below, because quoting it would pass one nonexistent module name.
    # shellcheck disable=SC2086
    pkg-config --exists $FFMPEG_LIBS || {
        echo "replay-corpus.sh: pkg-config cannot find all of: $FFMPEG_LIBS" >&2
        exit 1
    }
    # shellcheck disable=SC2086
    FF_CFLAGS="$(pkg-config --cflags $FFMPEG_LIBS)"
    FF_LDFLAGS=""
    # shellcheck disable=SC2086
    FF_LIBS="$(pkg-config --libs $FFMPEG_LIBS)"
    FF_ORIGIN="pkg-config ($(pkg-config --modversion libavcodec) libavcodec)"
fi

BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"
case "$VARIANT" in
    plain) VARIANT_FLAGS="-O2" ;;
    asan)  VARIANT_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -O1" ;;
    tsan)  VARIANT_FLAGS="-fsanitize=thread -O1" ;;
esac

OUT="$ROOT/build/$VARIANT/fuzz"
OBJ="$OUT/obj"
BIN="$OUT/bin"
GENERATED="$OUT/generated"
HELPER_LIB="$ROOT/build/$VARIANT/lib/libkitecodec_helpers_host.a"

[ -f "$HELPER_LIB" ] || {
    echo "replay-corpus.sh: no helper archive at $HELPER_LIB." >&2
    echo "                  Run ./scripts/build-host.sh $VARIANT first." >&2
    exit 1
}

# Sanitizer options, identical to run-c-tests.sh so a finding here reads the same as a finding
# there. detect_leaks=0 is explicit because LeakSanitizer is unsupported on macOS arm64 and asking
# for it returns a message instead of evidence.
export ASAN_OPTIONS="detect_leaks=0:abort_on_error=1:print_stacktrace=1:strict_string_checks=1"
export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1"
export TSAN_OPTIONS="halt_on_error=1:second_deadlock_stack=1"

# ── The generated corpus, and why part of the corpus is not committed ─────────────────────────
#
# Plan sub-phase B1.5 step 2 names the D27 vectors as seeds: descriptions of length 0, 2047, 2048,
# 4096 and 1048576. The first four are committed under fuzz/corpus/filter_audio. The 1048576 one is
# generated here instead, for two measured reasons rather than for tidiness:
#
#   1. Plan section 15.3 requires the corpus to be "small and textual". One megabyte of padding in
#      a repository whose whole committed corpus is 38077 bytes would be 27 times everything else,
#      and the plan asks for two of them, single input and multi input.
#   2. libFuzzer derives -max_len from the largest seed when the flag is absent, and generating
#      inputs up to a megabyte would spend the five minute budget on length instead of on shape.
#      run-fuzz.sh passes an explicit -max_len for this reason and the CI job cannot use the seed
#      anyway.
#
# So the vector is a real file, replayed through the same driver over the same code path, and it
# lands under build/ where it is gitignored. The length is the plan's number, not a rounded one.
#
# It is generated for filter_audio ONLY, and that is a measured decision rather than a shortcut.
# D27 is a defect in the two AUDIO builders by name: they are the ones that compose the description
# into char full_desc[2048], so a 1048576 byte description is refused by the first length check in
# microseconds and the vector costs nothing. The video builders have no composition buffer and no
# length limit of any kind, so the same input goes whole to avfilter_graph_parse_ptr, and under
# ASan's strict_string_checks=1 (which this script sets, matching run-c-tests.sh) it does not
# finish: measured at over 120 seconds against 0.25 seconds with that one option off, because every
# string operation the parser makes over a 1 MB buffer becomes a validated pass over the whole
# buffer. That cost is the interceptor's, not the library's, and putting it in a gate that runs
# after every sub-phase would buy nothing and cost minutes. The observation itself is recorded in
# fuzz/README.md, because "the video builder applies no length limit at all" is worth knowing.
generate_corpus() {
    local target="$1"
    local dir="$GENERATED/$target"
    rm -rf "$dir"
    mkdir -p "$dir"
    case "$target" in
        filter_audio)
            # A syntactically valid single-filter chain whose numeric argument carries the padding,
            # the same construction as FilterDescriptionLengthTest.description() in
            # kiteffmpeg-core's own test, at the one length that is not committed. Once plain and
            # once behind an [in0] label, for the single and multi input builders.
            python3 -c '
import sys
length, path = int(sys.argv[1]), sys.argv[2]
head = "volume=1."
open(path, "w").write(head + "0" * (length - len(head)))
' 1048576 "$dir/d27_generated_len_1048576"
            python3 -c '
import sys
length, path = int(sys.argv[1]), sys.argv[2]
head = "[in0]volume=1."
open(path, "w").write(head + "0" * (length - len(head)))
' 1048576 "$dir/d27_generated_multi_len_1048576"
            ;;
        *)
            # The other five targets have no length vector the plan names, so they get none. An
            # empty generated directory is not an error and the counts below say zero.
            ;;
    esac
}

# ── Building one replay binary ────────────────────────────────────────────────────────────────
#
# Three translation units per binary: the target's own body, the shared plumbing, and the driver.
# fuzz/kc_fuzz.h is what makes the target compile against either driver, so this is the only place
# the choice of driver is made.
build_one() {
    local target="$1"
    local source="$ROOT/fuzz/fuzz_$target.c"
    [ -f "$source" ] || { echo "replay-corpus.sh: missing $source" >&2; return 1; }
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/fuzz" \
        -c "$source" -o "$OBJ/fuzz_$target.o"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS -o "$BIN/${target}_replay" \
        "$OBJ/fuzz_$target.o" "$OBJ/kc_fuzz.o" "$OBJ/replay_main.o" "$HELPER_LIB" \
        $FF_LDFLAGS $FF_LIBS
    echo "  ld  ${target}_replay"
}

build_shared() {
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/fuzz" \
        -c "$ROOT/fuzz/kc_fuzz.c" -o "$OBJ/kc_fuzz.o"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/fuzz" \
        -c "$ROOT/fuzz/replay_main.c" -o "$OBJ/replay_main.o"
    echo "  cc  kc_fuzz.c replay_main.c"
}

# ── prove_power: the deliberate defect ───────────────────────────────────────────────────────
#
# Plan sub-phase B1.5's tests say: plant one deliberate defect, prove the harness catches it, then
# remove it in the same change. This function is that requirement turned into something repeatable,
# which is strictly more than the plan asked for and costs the same.
#
# The defect is planted in a COPY of the helper sources under build/, never in the repository. Two
# reasons: B1.4 established the pattern (its suites were proved load bearing by mutation against
# copies in a scratch directory), and B1.5 does not own src/helpers_filter.c, so mutating the file
# in place is not available to it. The consequence is that the defect is never in any commit at all,
# which is a stronger version of "removed in the same change" than the plan asked for.
#
# The mutation is one deletion: the running-length check that D27 installed after the ",aformat="
# append in ffkmp_graph_build_audio. That is the exact defect D27 records, at the exact site. With
# it gone, a 2047 byte description plus pinned output leaves the running total at 2056, so the next
# append addresses full_desc + 2056 in a char[2048] and its size argument wraps.
#
# The corpus already has that input: fuzz/corpus/filter_audio/d27_len_2047, and the target runs the
# pinned matrix on every seed. So the proof needs no special input; it needs only the defect.
prove_power() {
    local mutant="$OUT/mutant"
    local target="filter_audio"

    echo "prove-power: planting one deliberate defect in a COPY of the helper sources"
    echo "  copy       $mutant"
    rm -rf "$mutant"
    mkdir -p "$mutant/src" "$mutant/include"
    cp "$ROOT"/src/*.c "$mutant/src/"
    cp "$ROOT"/include/*.h "$mutant/include/"

    # The mutation, applied by exact text match and refused unless it matches exactly once. A sed
    # that silently matched nothing would make this whole function a no-op that reports success.
    python3 - "$mutant/src/helpers_filter.c" <<'PY'
import sys

path = sys.argv[1]
text = open(path).read()

append = '        n += snprintf(full_desc + n, sizeof(full_desc) - n, ",aformat=");\n'
check = ('        if (n < 0 || n >= (int)sizeof(full_desc)) '
         '{ avfilter_graph_free(&graph); return AVERROR(EINVAL); }\n')
pair = append + check

count = text.count(pair)
if count != 1:
    sys.exit("prove-power: the mutation site matched %d times, expected exactly 1. "
             "The helper source changed shape; update the mutation in "
             "scripts/replay-corpus.sh." % count)

open(path, "w").write(text.replace(pair, append))
print("  mutation   deleted the running-length check after the \",aformat=\" append "
      "in ffkmp_graph_build_audio")
PY

    local mobj="$OUT/mutant-obj"
    rm -rf "$mobj"
    mkdir -p "$mobj"
    local objects=""
    for source in "$mutant"/src/*.c; do
        local object
        object="$mobj/$(basename "${source%.c}").o"
        # shellcheck disable=SC2086
        "$CC" $BASE_FLAGS $VARIANT_FLAGS $FF_CFLAGS -I "$mutant/include" -fvisibility=hidden \
            -c "$source" -o "$object"
        objects="$objects $object"
    done
    local mlib="$mobj/libkitecodec_helpers_mutant.a"
    # shellcheck disable=SC2086
    "${KC_AR:-/usr/bin/ar}" rcs "$mlib" $objects
    echo "  ar         libkitecodec_helpers_mutant.a"

    mkdir -p "$OBJ" "$BIN"
    build_shared
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS $FF_CFLAGS -I "$mutant/include" -I "$ROOT/fuzz" \
        -c "$ROOT/fuzz/fuzz_$target.c" -o "$OBJ/fuzz_${target}_mutant.o"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS -o "$BIN/${target}_replay_mutant" \
        "$OBJ/fuzz_${target}_mutant.o" "$OBJ/kc_fuzz.o" "$OBJ/replay_main.o" "$mlib" \
        $FF_LDFLAGS $FF_LIBS
    echo "  ld         ${target}_replay_mutant"
    echo

    generate_corpus "$target"
    local files=()
    while IFS= read -r file; do files+=("$file"); done < <(find "$ROOT/fuzz/corpus/$target" -type f | sort)
    while IFS= read -r file; do files+=("$file"); done < <(find "$GENERATED/$target" -type f | sort)

    local log="$OUT/prove-power.log"
    # Run it inside a subshell whose stderr is discarded, and end that subshell with an explicit
    # `exit` so bash cannot apply its last-command optimisation and exec the binary in place of the
    # subshell. The mutant is EXPECTED to die on a signal, and whichever shell reaps it prints
    # "Abort trap: 6" prefixed with this script's name and a line number, which reads like a bug in
    # the script rather than the success it is. With the fork forced, the message is the subshell's
    # and goes to /dev/null, and the subshell exits normally carrying the code.
    set +e
    ( "$BIN/${target}_replay_mutant" "${files[@]}" > "$log" 2>&1; exit $? ) 2>/dev/null
    local code=$?
    set -e

    echo "prove-power: the mutant replay exited $code"
    echo "prove-power: the finding, verbatim"
    echo "───────────────────────────────────────────────────────────────────────────────"
    # The sanitizer report, from its first marker line to the end. Printed whole rather than
    # summarised, because the finding text IS the evidence and a paraphrase is not. awk rather than
    # sed: BSD sed's basic expressions have no alternation, so the three markers would need three
    # passes and the first one would win by accident.
    awk '/ERROR: |runtime error: |SUMMARY: /{found = 1} found' "$log" | head -40
    echo "───────────────────────────────────────────────────────────────────────────────"
    echo "prove-power: full output in $log"
    echo

    # The mutant is not deleted. It lives under build/, which is gitignored, so it cannot reach a
    # commit, and leaving it there means the finding can be re-read without rebuilding.
    if [ "$code" -eq 0 ]; then
        echo "prove-power: FAILED. The planted defect produced no finding, so this harness has not" >&2
        echo "             been shown to have any power. Do not treat a green replay as evidence" >&2
        echo "             until this passes." >&2
        return 1
    fi
    if ! grep -qE 'ERROR: |runtime error: ' "$log"; then
        echo "prove-power: FAILED. The mutant exited $code but printed no sanitizer report, so the" >&2
        echo "             non-zero exit is not evidence of the defect being detected." >&2
        return 1
    fi
    echo "prove-power: PASSED. The planted defect was caught, and it was never in the repository."
    return 0
}

echo "replay-corpus.sh: variant $VARIANT"
echo "  compiler   $CC ($("$CC" --version | head -1))"
echo "  ffmpeg     $FF_ORIGIN"
echo "  flags      $BASE_FLAGS $VARIANT_FLAGS"
echo "  archive    $HELPER_LIB"
echo "  output     $OUT"
echo

# Per-target cleanup, not a wipe of the whole directory. Removing $BIN wholesale would mean that
# `replay-corpus.sh asan filter_audio`, which the README offers as the fast loop while writing a
# target, silently deleted the other five binaries and left a later timing or debugging run
# measuring a missing file. Measured the hard way while writing this script: a single-target run
# followed by a direct invocation of another binary gave exit 127 and 0.02 seconds, which reads
# exactly like a fast pass.
mkdir -p "$OBJ" "$BIN" "$GENERATED"

if [ "$PROVE_POWER" -eq 1 ]; then
    # No cleanup in this mode. --prove-power builds one extra binary next to the real ones and must
    # not delete them: removing them here made a later direct invocation of another target exit 127
    # in 0.02 seconds, which reads exactly like a fast pass.
    prove_power
    exit $?
fi

rm -f "$OBJ/kc_fuzz.o" "$OBJ/replay_main.o"
for target in $TARGETS; do
    rm -f "$OBJ/fuzz_$target.o" "$BIN/${target}_replay"
done

build_shared
for target in $TARGETS; do
    build_one "$target"
done
echo

passed=""
failed=""
total_files=0

for target in $TARGETS; do
    corpus="$ROOT/fuzz/corpus/$target"
    [ -d "$corpus" ] || {
        echo "replay-corpus.sh: no corpus directory at $corpus" >&2
        failed="$failed $target"
        continue
    }
    generate_corpus "$target"

    files=()
    while IFS= read -r file; do files+=("$file"); done < <(find "$corpus" -type f | sort)
    committed=${#files[@]}
    while IFS= read -r file; do files+=("$file"); done < <(find "$GENERATED/$target" -type f | sort)
    generated=$(( ${#files[@]} - committed ))

    if [ "${#files[@]}" -eq 0 ]; then
        echo "replay-corpus.sh: corpus for $target is empty" >&2
        failed="$failed $target"
        continue
    fi

    echo "=== $target  ($committed committed, $generated generated)"
    if "$BIN/${target}_replay" "${files[@]}"; then
        passed="$passed $target"
    else
        code="$?"
        echo "=== $target exited $code"
        failed="$failed $target"
    fi
    total_files=$(( total_files + ${#files[@]} ))
    echo
done

count() { echo $# ; }
# shellcheck disable=SC2086
echo "replay-corpus.sh: variant $VARIANT, $(count $passed) targets passed, $(count $failed) failed, $total_files corpus files replayed"
[ -n "$failed" ] && echo "  failed: ${failed# }"

if [ -n "$failed" ]; then
    exit 1
fi
exit 0
