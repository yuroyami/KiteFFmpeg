#!/usr/bin/env bash
#
# The compatibility instrument for the `ffmpeg` cinterop surface.
#
# `apiCheck` guards kiteffmpeg-core's own klib. It says nothing about the cinterop klib, which is
# a separate artifact and is where KiteFFmpeg's opaque kc_/ffkmp_ bindings live. This script is that
# missing guard: it dumps the cinterop klib's metadata, first rejects any raw libav surface, then
# filters it, compares it against a committed baseline, and reports which declarations were added
# and which were removed.
#
# Why the filter. Every declaration carries a `@kotlinx/cinterop/internal/CCall(id =
# "knifunptr_ffmpeg<N>_<name>")` annotation whose N is a sequence number over the whole module.
# Adding or removing one binding renumbers every later one, which would bury a real change under
# hundreds of meaningless lines. The numbers have no Kotlin meaning, so every line mentioning
# `knifunptr_` is dropped before the comparison.
#
# What the historical B1.3 lift looked like. A helper that cinterop saw as `static inline` got a
# bridge stub and carried only the `CCall(id = ...)` annotation. A helper that cinterop saw as an
# ordinary external function additionally carried
# `@kotlinx/cinterop/internal/CCall.Direct(name = "_<symbol>")`, which is the mechanical proof that
# the call now goes straight to a real symbol instead of through a generated stub. So the B1.3 lift
# shows up here as one added `CCall.Direct` line per lifted helper, with no declaration removed and
# no signature touched.
#
# Usage:
#   ./scripts/klib-metadata-diff.sh                    compare the current klib with the baseline
#   ./scripts/klib-metadata-diff.sh --baseline FILE     compare against FILE instead
#   ./scripts/klib-metadata-diff.sh --check             as above, and exit non-zero on any difference
#   ./scripts/klib-metadata-diff.sh --update            rewrite the baseline from the current klib
#   ./scripts/klib-metadata-diff.sh --target macosArm64 pick another Kotlin/Native target directory
#
# Every mode, including `--update`, first enforces the post-S1.a.8 opaque boundary: none of the six
# libav version constants may survive, and every direct binding must begin `_ffkmp_` or `_kc_`.
#
# `--update` is how a sub-phase that deliberately changes the cinterop surface re-baselines after
# its own differential has been read and accepted. It is a normal commit, exactly like lowering a
# coupling-ratchet number, and the commit message says which declarations moved.
#
# Exit status: 0 when the klib matches the baseline, 2 on a usage error, 1 when the klib or the
# tooling is missing, 1 when the opaque-boundary invariant fails, and 1 when anything differs,
# WITH OR WITHOUT `--check`. The two forms agree
# since the interlude: the bare form used to exit 0 on a real mismatch, and the plan's own
# gate blocks invoked it bare in three places, so a red differential could scroll past a green
# exit. The bare form still prints the full differential as its output (that is what it is for,
# in the sub-phase that deliberately changes the surface); it just no longer calls a mismatch
# success. `--check` remains the documented gate spelling. `--update` exits 0 after rewriting.
#
# What B1.3 measured with this script, recorded so the numbers can be checked later. The pre-lift
# dump, taken at the parent of the lift commit, was 18684 filtered lines,
# sha256 0995efd057266fdbc133556a76c0d461028b89dbbbe04a14068a6109c1c9245c. Against it the post-lift
# dump (18844 lines) showed 172 added direct bindings, every one of them an ffkmp_ helper, zero
# added declarations, zero removed direct bindings, and 4 removed declarations: ffkmp_graph_finish_,
# ffkmp_graph_finish_multi_, ffkmp_codec_pix_fmts_ and ffkmp_ch_layout_mask_, the four internal
# helpers that stayed `static` and were never declared in the extracted header. To reproduce it,
# restore the def from the lift's parent commit, rebuild the cinterop, and run this script: it then
# reports the mirror image of those numbers.
#
# What B1.4 measured against that baseline, recorded the same way. The pre-B1.4 dump was 18844
# lines, sha256 a142ee53312e2700ec3fef8d431940daa9505f50646bf30afa2f8d114f748c27; the post-B1.4 dump
# is 18784 lines, sha256 361e94272da47423678c58c78fb19d9aee6ee932c74ec4a779a9271629284517. The
# differential was zero declarations added, 15 declarations removed, zero direct bindings added, 15
# direct bindings removed, and zero other changed lines added. The 15 are the dead exported helpers
# of the deleted helper surface, and the 30 removed "other" lines are those 15 declarations plus the
# `@kotlinx/cinterop/ExperimentalForeignApi` line each one carried. Nothing else moved: the 80
# structural lines realigned on each side are the per-fragment boilerplate this file already
# explains, and they cancel.
#
# What B1.6 measured against that baseline, and the correction it forced on this script. The pre-B1.6
# dump was 18784 lines, sha256 361e94272da47423678c58c78fb19d9aee6ee932c74ec4a779a9271629284517; the
# post-B1.6 dump is 19024 lines, sha256 5e90ff81806aec7e3b9087a50316a78c5045c6bb8dccda081030d01c69a6986c.
# The differential was 57 declarations added and 0 LOST, 6 direct bindings added (all six kc_ functions
# of native/kitecodec-c/include/kitecodec_abi.h, none of them an ffkmp_ helper), zero direct bindings
# removed, 177 other lines added and 4 removed. The 57 are kc_ffmpeg_report and its Companion, the six
# kc_ functions, the four kc_status/kc_verdict typealiases, and the report's own fields and constants.
#
# The correction, which is the part worth keeping. The line diff also reported 2 DECLARATIONS REMOVED,
# and reading that as a removal would have been wrong: they were `typealias AVAudioServiceType` and
# `AVAudioServiceTypeVar`, which the inserted kc_ typealiases pushed from line 7595 to 7714, and both
# appeared in the ADDED list as well. A line diff reports a moved block as a deletion plus an insertion.
# Proved by set difference: not one line of the B1.4 baseline is absent from the B1.6 one. So this script
# now reports DECLARATIONS LOST, GAINED and RELOCATED as set differences beside the diff-derived counts,
# and LOST is the number an acceptance condition for a purely additive sub-phase should read. It was 0.
#
# Environment:
#   KC_KLIB_TOOL   path to the Kotlin/Native `klib` tool, overriding the version-derived default
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"

TARGET="macosArm64"
BASELINE="$ROOT/klib-metadata-baseline.txt"
UPDATE=0
CHECK=0

while [ $# -gt 0 ]; do
    case "$1" in
        --update)   UPDATE=1; shift ;;
        --check)    CHECK=1; shift ;;
        --baseline) [ $# -ge 2 ] || { echo "klib-metadata-diff.sh: --baseline needs a path" >&2; exit 2; }
                    BASELINE="$2"; shift 2 ;;
        --target)   [ $# -ge 2 ] || { echo "klib-metadata-diff.sh: --target needs a name" >&2; exit 2; }
                    TARGET="$2"; shift 2 ;;
        -h|--help)  sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *)          echo "klib-metadata-diff.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

KLIB_DIR="$REPO/kiteffmpeg-core/build/classes/kotlin/$TARGET/main/cinterop/kiteffmpeg-core-cinterop-ffmpeg"
if [ ! -d "$KLIB_DIR" ]; then
    echo "klib-metadata-diff.sh: no cinterop klib at $KLIB_DIR" >&2
    echo "  build it first:  ./gradlew :kiteffmpeg-core:cinteropFfmpeg$TARGET" >&2
    exit 1
fi

# The `klib` tool ships inside the Kotlin/Native distribution, so the one that matches the Kotlin
# version this repository builds with is the only correct choice: an older tool cannot read a newer
# klib's metadata format.
if [ -n "${KC_KLIB_TOOL:-}" ]; then
    KLIB_TOOL="$KC_KLIB_TOOL"
else
    KOTLIN_VERSION="$(sed -n 's/^kotlin *= *"\(.*\)".*/\1/p' "$REPO/gradle/libs.versions.toml" | head -1)"
    [ -n "$KOTLIN_VERSION" ] || {
        echo "klib-metadata-diff.sh: cannot read the kotlin version from gradle/libs.versions.toml" >&2
        exit 1
    }
    case "$(uname -s)/$(uname -m)" in
        Darwin/arm64)  KONAN_HOST="macos-aarch64" ;;
        Darwin/x86_64) KONAN_HOST="macos-x86_64" ;;
        Linux/aarch64) KONAN_HOST="linux-aarch64" ;;
        Linux/x86_64)  KONAN_HOST="linux-x86_64" ;;
        *) echo "klib-metadata-diff.sh: unsupported host $(uname -s)/$(uname -m)" >&2; exit 1 ;;
    esac
    KLIB_TOOL="$HOME/.konan/kotlin-native-prebuilt-$KONAN_HOST-$KOTLIN_VERSION/bin/klib"
fi
[ -x "$KLIB_TOOL" ] || {
    echo "klib-metadata-diff.sh: no klib tool at $KLIB_TOOL" >&2
    echo "  it appears once Gradle has downloaded the Kotlin/Native distribution, or set" >&2
    echo "  KC_KLIB_TOOL to another one." >&2
    exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Every line mentioning a knifunptr id is dropped, per the note at the top of this file.
"$KLIB_TOOL" dump-metadata "$KLIB_DIR" | grep -v 'knifunptr_' > "$WORK/current.txt"

# S1.a.8 deliberately removed every FFmpeg header from ffmpeg.def. The cinterop metadata must now
# contain only KiteFFmpeg-owned bindings: seeing any one of the former version constants proves a
# raw FFmpeg header leaked back in, and seeing any other direct-binding prefix proves a raw C entry
# point crossed the boundary. This runs before the --update branch so no write mode can bless a
# forbidden dump. It is target-independent and therefore applies to every --target value.
assert_opaque_metadata() {
    local metadata="$1"
    local failed=0
    local name line binding
    local direct_count=0

    for name in \
        LIBAVUTIL_VERSION_INT \
        LIBAVFORMAT_VERSION_INT \
        LIBAVCODEC_VERSION_INT \
        LIBAVFILTER_VERSION_INT \
        LIBSWSCALE_VERSION_INT \
        LIBSWRESAMPLE_VERSION_INT
    do
        if grep -Eq "(^|[^A-Za-z0-9_])${name}([^A-Za-z0-9_]|$)" "$metadata"; then
            echo "klib-metadata-diff.sh: forbidden raw libav constant in metadata: $name" >&2
            failed=1
        fi
    done

    while IFS= read -r line; do
        binding="$(printf '%s\n' "$line" | sed -n 's/^.*CCall\.Direct(name = "\([^"]*\)".*$/\1/p')"
        if [ -z "$binding" ]; then
            echo "klib-metadata-diff.sh: unrecognised CCall.Direct annotation: $line" >&2
            failed=1
            continue
        fi
        direct_count=$((direct_count + 1))
        case "$binding" in
            _ffkmp_*|_kc_*) ;;
            *)
                echo "klib-metadata-diff.sh: forbidden direct binding in metadata: $binding" >&2
                failed=1
                ;;
        esac
    done < <(grep 'CCall\.Direct' "$metadata" || true)

    if [ "$failed" != 0 ]; then
        echo "klib-metadata-diff.sh: the cinterop metadata is not opaque." >&2
        return 1
    fi

    echo "OPAQUE METADATA BOUNDARY"
    echo "  raw libav version constants           0"
    echo "  direct bindings                       $direct_count"
    echo "  direct bindings outside _kc_/_ffkmp_  0"
    echo
}

assert_opaque_metadata "$WORK/current.txt"

digest() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

if [ "$UPDATE" = 1 ]; then
    cp "$WORK/current.txt" "$BASELINE"
    echo "klib-metadata-diff.sh: baseline rewritten"
    echo "  target    $TARGET"
    echo "  baseline  $BASELINE"
    echo "  lines     $(wc -l < "$BASELINE" | tr -d ' ')"
    echo "  sha256    $(digest "$BASELINE")"
    exit 0
fi

[ -f "$BASELINE" ] || {
    echo "klib-metadata-diff.sh: no baseline at $BASELINE" >&2
    echo "  create it with:  $0 --update" >&2
    exit 1
}

echo "klib-metadata-diff.sh: cinterop metadata differential"
echo "  target    $TARGET"
echo "  klib      $KLIB_DIR"
echo "  tool      $KLIB_TOOL"
echo "  baseline  $BASELINE"
echo "            $(wc -l < "$BASELINE" | tr -d ' ') lines, sha256 $(digest "$BASELINE")"
echo "  current   $(wc -l < "$WORK/current.txt" | tr -d ' ') lines, sha256 $(digest "$WORK/current.txt")"
echo

# diff exits 1 when the files differ, which is information rather than failure here.
diff -u "$BASELINE" "$WORK/current.txt" > "$WORK/diff.txt" || true

grep '^+' "$WORK/diff.txt" | grep -v '^+++' | sed 's/^+//' > "$WORK/added.txt" || true
grep '^-' "$WORK/diff.txt" | grep -v '^---' | sed 's/^-//' > "$WORK/removed.txt" || true

# A declaration line is what carries a name onto the surface: a function, a property, a typealias,
# a class, or cinterop's own `// class name:` marker. Everything else in the dump is an annotation,
# a brace or a blank line.
declaration_names() {
    sed -n \
        -e 's/^[[:space:]]*\/\/ class name: \(.*\)$/class \1/p' \
        -e 's/^.*[[:space:]]fun \([A-Za-z0-9_]*\)(.*$/fun \1/p' \
        -e 's/^.*[[:space:]]val \([A-Za-z0-9_]*\):.*$/val \1/p' \
        -e 's/^.*[[:space:]]var \([A-Za-z0-9_]*\):.*$/var \1/p' \
        -e 's/^.*[[:space:]]typealias \([A-Za-z0-9_]*\).*$/typealias \1/p' \
        "$1" | sort
}

# The direct-binding annotation, which is the substance of the B1.3 lift.
direct_names() {
    sed -n 's/^.*CCall\.Direct(name = "\([^"]*\)").*$/\1/p' "$1" | sort
}

declaration_names "$WORK/added.txt"   > "$WORK/decl_added.txt"
declaration_names "$WORK/removed.txt" > "$WORK/decl_removed.txt"
direct_names "$WORK/added.txt"        > "$WORK/direct_added.txt"
direct_names "$WORK/removed.txt"      > "$WORK/direct_removed.txt"

count() { wc -l < "$1" | tr -d ' '; }

report() {
    # report <title> <file>
    local title="$1" file="$2"
    local n
    n="$(count "$file")"
    echo "$title ($n)"
    if [ "$n" = 0 ]; then
        echo "  none"
    else
        sed 's/^/  /' "$file"
    fi
    echo
}

report "DECLARATIONS ADDED"      "$WORK/decl_added.txt"
report "DECLARATIONS REMOVED"    "$WORK/decl_removed.txt"
report "DIRECT BINDINGS ADDED"   "$WORK/direct_added.txt"
report "DIRECT BINDINGS REMOVED" "$WORK/direct_removed.txt"

# The three sets above are derived from a LINE diff, and a line diff reports a block that MOVED as a
# deletion in one place and an insertion in another. So a declaration can appear in both lists while
# nothing about the surface changed, which measured at B1.6: inserting the kc_ typealiases pushed
# `typealias AVAudioServiceType` and `AVAudioServiceTypeVar` from line 7595 to 7714, and both were
# reported removed AND added. Reading "declarations removed 2" there would have been wrong.
#
# So the three sets below are the answer a compatibility question actually wants, and they are set
# differences rather than diff hunks:
#
#   LOST       a name the baseline had and the current dump does not. This is the only one that can
#              break a consumer, and the only one the acceptance condition of a purely additive
#              sub-phase is allowed to see at zero.
#   GAINED     a name the current dump has and the baseline did not.
#   RELOCATED  a name in both, whose line the diff moved. Always benign.
sort -u "$WORK/decl_added.txt"   > "$WORK/decl_added_set.txt"
sort -u "$WORK/decl_removed.txt" > "$WORK/decl_removed_set.txt"
comm -23 "$WORK/decl_removed_set.txt" "$WORK/decl_added_set.txt" > "$WORK/decl_lost.txt"
comm -13 "$WORK/decl_removed_set.txt" "$WORK/decl_added_set.txt" > "$WORK/decl_gained.txt"
comm -12 "$WORK/decl_removed_set.txt" "$WORK/decl_added_set.txt" > "$WORK/decl_relocated.txt"

report "DECLARATIONS LOST, set difference"      "$WORK/decl_lost.txt"
report "DECLARATIONS RELOCATED, in both lists"  "$WORK/decl_relocated.txt"

# What is left after the four reports above, so nothing hides in a count nobody reads. Two kinds of
# line are dropped from it:
#
#   - blank lines, and
#   - the structural boilerplate of the dump (`library fragment {`, `package {`,
#     `// package name: ...` and a bare closing brace). The dump repeats that block once per
#     source-set fragment, 25 times for this module, and it is identical every time, so `diff` is
#     free to realign it and report the same number of those lines added and removed. Net zero and
#     no meaning, which is why they are counted separately below instead of read.
#
# Everything else stays, including the declaration and annotation lines the reports above already
# named. Read this block against those reports: every line in it must belong to a declaration that
# was reported added or removed. A line here that does not is the case this script exists to catch,
# for instance a `@kotlinx/cinterop/internal/CCall.CString` that quietly left a signature.
other_lines() {
    grep -v -e '^[[:space:]]*$' \
            -e 'CCall\.Direct' \
            -e '^[[:space:]]*library fragment {[[:space:]]*$' \
            -e '^[[:space:]]*package {[[:space:]]*$' \
            -e '^[[:space:]]*// package name: ' \
            -e '^[[:space:]]*}[[:space:]]*$' \
            "$1" || true
}
structural() {
    grep -c -e '^[[:space:]]*library fragment {[[:space:]]*$' \
            -e '^[[:space:]]*package {[[:space:]]*$' \
            -e '^[[:space:]]*// package name: ' \
            -e '^[[:space:]]*}[[:space:]]*$' \
            "$1" || true
}

other_lines "$WORK/added.txt"   > "$WORK/other_added.txt"
other_lines "$WORK/removed.txt" > "$WORK/other_removed.txt"

report "OTHER CHANGED LINES, ADDED"   "$WORK/other_added.txt"
report "OTHER CHANGED LINES, REMOVED" "$WORK/other_removed.txt"

DIRECT_ADDED="$(count "$WORK/direct_added.txt")"
DIRECT_ADDED_FFKMP="$(grep -c '^_ffkmp_' "$WORK/direct_added.txt" || true)"

echo "SUMMARY"
echo "  changed lines, added                   $(count "$WORK/added.txt")"
echo "  changed lines, removed                 $(count "$WORK/removed.txt")"
echo "  declarations added                     $(count "$WORK/decl_added.txt")"
echo "  declarations removed                   $(count "$WORK/decl_removed.txt")"
echo "  declarations LOST, set difference      $(count "$WORK/decl_lost.txt")"
echo "  declarations gained, set difference    $(count "$WORK/decl_gained.txt")"
echo "  declarations relocated, in both lists  $(count "$WORK/decl_relocated.txt")"
echo "  direct bindings added                  $DIRECT_ADDED"
echo "  direct bindings added, _ffkmp_ prefix  $DIRECT_ADDED_FFKMP"
echo "  direct bindings removed                $(count "$WORK/direct_removed.txt")"
echo "  structural lines realigned, added      $(structural "$WORK/added.txt")"
echo "  structural lines realigned, removed    $(structural "$WORK/removed.txt")"
echo "  other changed lines, added             $(count "$WORK/other_added.txt")"
echo "  other changed lines, removed           $(count "$WORK/other_removed.txt")"

if [ "$DIRECT_ADDED" != "$DIRECT_ADDED_FFKMP" ]; then
    echo
    echo "note: $((DIRECT_ADDED - DIRECT_ADDED_FFKMP)) added direct binding(s) are not ffkmp_ helpers."
fi

# ────────────────────────────────────────────────────────────────────────────────────────────────
# RETIRED AT S1.a.8: the interlude I-07 two-bakings probe used to compare
# LIBAVUTIL_VERSION_INT parsed into cinterop metadata with the embedded archive's frozen identity
# report. It caught a real stale C compile while both halves of the klib independently processed
# the same FFmpeg headers, and that historical result remains valid evidence. S1.a.8 removed the
# second processing entirely: ffmpeg.def now parses no FFmpeg header, the six LIB*_VERSION_INT
# constants must be absent by the invariant above, and the compiled archive's identity gate is the
# one intentional FFmpeg-header baking. Keeping the old link probe would require the raw metadata
# leak this phase exists to forbid, so there is deliberately no host-only replacement here.

# A mismatch is a failure in BOTH forms since the interlude: the bare form exiting 0 on a
# real difference was measured to let the gate read green while the report above said red.
if [ -s "$WORK/diff.txt" ]; then
    echo
    echo "klib-metadata-diff.sh: the cinterop metadata does not match $BASELINE." >&2
    echo "  Read the report above. If the change is deliberate, re-baseline with --update in the" >&2
    echo "  same commit and name the moved declarations in the commit message." >&2
    exit 1
fi
