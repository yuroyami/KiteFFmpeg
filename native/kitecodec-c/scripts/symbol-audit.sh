#!/usr/bin/env bash
#
# Audit the symbol table of the compiled FFmpeg helper archive.
#
# This is a test, not documentation: plan section 15.3 lists it beside the sanitizer runs, and it
# is the only instrument that checks what the archive PROMISES rather than what it does. Its symbol
# questions come from object code; its final declaration-shape question comes from the public
# headers that define the source and cinterop contract:
#
#   1. What does the archive need from outside itself? Anything beyond libav*, libsw* and a short
#      allowlist is a dependency nobody decided to take. A `printf` would mean a helper writes to
#      stdout inside a library; an `av_log` would mean it writes to FFmpeg's log inside a library;
#      an `objc_msgSend` or a `dispatch_` would mean C that looked portable has quietly become
#      Apple-only.
#   2. What does it export? Exactly the names in exported-symbols-baseline.txt, which check 6
#      compares one for one. That file is the authority and it carries the current count; this
#      header used to restate the number and the running total of which window added what, and
#      both had drifted (it claimed 195 while the baseline held 199, and the windows it added up
#      came to 185). A count in two places is a count that will disagree with itself.
#      That set is a compatibility promise, which is the whole reason B1.4 deleted the 15 helpers
#      no Kotlin file imported. Check 8 ties it to KITECODEC_C_ABI_MAJOR/MINOR, so the promise
#      cannot grow while kc_abi_version() reports the same number.
#   3. What does it keep to itself? The four trailing-underscore helpers, which are `static` and
#      must never appear as external symbols.
#   4. Does anything print? Nothing but the identity gate's diagnostic bypass warning, which plan
#      section 15.6 question 3 requires to be loud. Check 5 pins that to one source file.
#   5. Did any public C declaration change shape? Check 7 compares normalized declaration records
#      from the helper, handle and ABI headers, including opaque alias targets and aggregate bodies.
#
# The default archive is the SHIPPED one, built per konan target by
# :kiteffmpeg:compileKiteFFmpegCFor<Target> and embedded in the cinterop klib. That is the
# archive whose exported set a consumer sees. The host archive from scripts/build-host.sh is
# compiled with the same -fvisibility=hidden and answers the same way; `--host` points there. The
# local Apple proof produces three FFmpeg trees and corresponding helper archives: macos_arm64,
# ios_arm64 and ios_simulator_arm64. The default remains the macos_arm64 archive.
#
# Usage:
#   ./scripts/symbol-audit.sh                     the shipped macos_arm64 archive
#   ./scripts/symbol-audit.sh --target macos_x64  another konan target's archive
#   ./scripts/symbol-audit.sh --host              the host test archive of build-host.sh
#   ./scripts/symbol-audit.sh --archive PATH      any archive
#   ./scripts/symbol-audit.sh --write-baseline    deliberately rewrite the export-name baseline
#   ./scripts/symbol-audit.sh --write-signature-baseline
#                                                  deliberately rewrite the declaration baseline
#
# Environment:
#   KC_NM   the nm to use, default /usr/bin/nm. Mach-O archives are read by the system nm; an ELF
#           archive needs a cross nm, and the Android toolchain package ships one that runs on
#           macOS (aarch64-linux-android-nm). The proving machine has FFmpeg trees and C archives
#           for macos_arm64, ios_arm64 and ios_simulator_arm64; no other target is inferred.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"

TARGET="macos_arm64"
ARCHIVE=""
HOST=0
WRITE_BASELINE=0
WRITE_SIGNATURE_BASELINE=0
NM="${KC_NM:-/usr/bin/nm}"

while [ $# -gt 0 ]; do
    case "$1" in
        --target)  [ $# -ge 2 ] || { echo "symbol-audit.sh: --target needs a name" >&2; exit 2; }
                   TARGET="$2"; shift 2 ;;
        --archive) [ $# -ge 2 ] || { echo "symbol-audit.sh: --archive needs a path" >&2; exit 2; }
                   ARCHIVE="$2"; shift 2 ;;
        --host)    HOST=1; shift ;;
        --write-baseline) WRITE_BASELINE=1; shift ;;
        --write-signature-baseline) WRITE_SIGNATURE_BASELINE=1; shift ;;
        -h|--help) sed -n '2,43p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *)         echo "symbol-audit.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

if [ -z "$ARCHIVE" ]; then
    if [ "$HOST" = 1 ]; then
        ARCHIVE="$ROOT/build/plain/lib/libkitecodec_helpers_host.a"
        HINT="build it first:  ./scripts/build-host.sh plain"
    else
        ARCHIVE="$REPO/kiteffmpeg/build/kitecodec-c/$TARGET/libkitecodec.a"
        HINT="build it first:  ./gradlew :kiteffmpeg:compileKiteFFmpegCFor<Target>"
    fi
fi
[ -f "$ARCHIVE" ] || {
    echo "symbol-audit.sh: no archive at $ARCHIVE" >&2
    echo "  ${HINT:-}" >&2
    exit 1
}
[ -x "$NM" ] || command -v "$NM" >/dev/null 2>&1 || {
    echo "symbol-audit.sh: no nm at $NM" >&2
    exit 1
}

HEADER="$ROOT/include/kitecodec_helpers.h"
HANDLES_HEADER="$ROOT/include/kitecodec_handles.h"
ABI_HEADER="$ROOT/include/kitecodec_abi.h"
SRC="$ROOT/src"
for path in "$HEADER" "$HANDLES_HEADER" "$ABI_HEADER" "$SRC"; do
    [ -e "$path" ] || { echo "symbol-audit.sh: missing $path" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------------------------
# The ABI version, and the reason the baselines carry it.
#
# KITECODEC_C_ABI_MAJOR/MINOR say what this C surface is, and kc_abi_version() reports it at
# runtime so a consumer can refuse a library it does not understand. Nothing made those numbers
# TRUE. The two baselines below already stop the surface changing silently, but the move procedure
# for both is "rewrite the baseline in the same commit", and a rewrite never asked about the
# version. So the surface could grow through the front door, one deliberate --write-baseline at a
# time, while kc_abi_version() went on reporting the same number forever.
#
# Each baseline now records the version it was written at. Check 8 holds the stamps equal to the
# header, and a rewrite that CHANGES records refuses unless the version rose. Rewriting with the
# same records is always allowed, which is what makes a version-only bump a one-command follow-up
# rather than an argument with the tool.
abi_field() {
    sed -n "s/^#define $1[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p" "$ABI_HEADER" | head -1
}
ABI_MAJOR="$(abi_field KITECODEC_C_ABI_MAJOR)"
ABI_MINOR="$(abi_field KITECODEC_C_ABI_MINOR)"
if [ -z "$ABI_MAJOR" ] || [ -z "$ABI_MINOR" ]; then
    echo "symbol-audit.sh: cannot read KITECODEC_C_ABI_MAJOR/MINOR from $ABI_HEADER" >&2
    exit 1
fi
ABI_VERSION="$ABI_MAJOR.$ABI_MINOR"
ABI_STAMP_PREFIX="# abi-version"

# The version a baseline was written at, or empty when the file predates the stamp.
stamped_version() {
    [ -f "$1" ] || return 0
    sed -n "s|^$ABI_STAMP_PREFIX[[:space:]]\{1,\}\([0-9]\{1,\}\.[0-9]\{1,\}\).*|\1|p" "$1" | head -1
}

# True when the current header version is strictly newer than $1 (empty counts as older).
abi_is_newer_than() {
    local was="$1"
    [ -n "$was" ] || return 0
    local was_major="${was%%.*}" was_minor="${was##*.}"
    [ "$ABI_MAJOR" -gt "$was_major" ] && return 0
    [ "$ABI_MAJOR" -eq "$was_major" ] && [ "$ABI_MINOR" -gt "$was_minor" ]
}

# The allowlist of undefined symbols that are not libav or libsw.
#
# Re-measured at B1.4 rather than taken from the plan, which lists memcpy, memset, snprintf, strlen
# and strerror. The measurement on this machine, against the macos_arm64 archive built by konan's
# clang 21.1.6, is different in both directions and the measurement wins:
#
#   _memcpy _snprintf _strstr   the three C library calls the helper bodies actually make, and each
#                               one is in the source rather than compiler-generated: memcpy at
#                               src/helpers_frame.c lines 94 and 128, the two audio plane copies;
#                               snprintf at the 18 filter description sites; strstr at
#                               src/helpers_filter.c line 278, where the multi input graph builder
#                               checks a caller's description for "[out]". memset, strlen and
#                               strerror do not appear at all: the layer calls av_strerror and never
#                               strerror, and it never zeroes or measures a string itself.
#   ___stack_chk_fail           clang's stack protector, emitted by the compiler and not called by
#   ___stack_chk_guard          any line of the source. Removing it would mean turning the stack
#                               protector off, which would be a worse trade than allowing it.
#   __tlv_bootstrap             the Mach-O thread-local bootstrap for `static __thread char
#                               buf[256]` in ffkmp_strerror. It is the object-code fingerprint of
#                               thread affine, so its presence here is expected and its
#                               ABSENCE would mean the thread-affine buffer stopped being thread
#                               local. Mach-O only; an ELF build resolves TLS differently.
#
# Seven more arrived with B1.6's identity gate, src/kitecodec_abi.c, and each was measured rather than
# assumed. All of them are in that one unit and nowhere else, which check 5 asserts at source level:
#
#   _pthread_once             the gate's once-only guard. Its ABSENCE would mean the gate had become a
#                             per-translation-unit flag, which is the exact construction plan section
#                             15.2 B1.6 step 3 rejects and the reason the gate needs external linkage.
#   _getenv                   reads KITECODEC_FFMPEG_ABI_BYPASS. The only environment read in the
#                             library, and the only way the diagnostic bypass can be turned on.
#   _fputs                    writes the bypass warning.
#   ___stderrp                the stream it writes to. These two are the ONE place the C layer writes
#                             anything, and they exist because plan section 15.6 question 3 makes the
#                             bypass warning mandatory: an escape hatch nobody can hear is the silently
#                             bypassed gate the owner ruled out. `printf` stays forbidden by check 2.
#   _strcmp                   compares the six *_configuration() strings and the bypass value.
#   _strlen                   bounds every copy into the report's fixed char arrays.
#   ___memcpy_chk             clang's bounds-checked memcpy, emitted for the report copies at -O2 from
#                             the same source line as _memcpy. Compiler output, not a source call.
# _bzero joined 2026-08-30 with the filter builders' source-array clear: a loop that writes NULL
# over an array is what clang folds into it, so it is libc under a synthesised name rather than a
# new dependency anyone wrote. _strstr stayed when the [out] label scan replaced it, because the
# list is a permit list and removing an entry is its own measured act.
# _kc_init joined 2026-09-04 with the identity gate inside the constructor helpers. It is this
# library's OWN symbol, in kitecodec_abi.c, and it is undefined in the helper archive only because
# the two are separate translation units; the linked product always carries both. It is on the list
# rather than exempted by a rule, because the rule this check enforces is "the helper layer takes no
# dependency nobody wrote down", and this one is written down here.
ALLOWED_UNDEFINED="_memcpy _snprintf _strstr _bzero ___stack_chk_fail ___stack_chk_guard __tlv_bootstrap
_pthread_once _getenv _fputs ___stderrp _strcmp _strlen ___memcpy_chk _kc_init"

# Calls that must never appear. A library does not print, does not log through its host's logger,
# and does not reach into an Apple runtime from portable C.
FORBIDDEN_PATTERNS="printf av_log objc_msgSend dispatch_ _exit abort"

status=0
fail() {
    echo "  FAIL: $*"
    status=1
}

echo "symbol-audit.sh: $ARCHIVE"
echo "  nm        $NM"
echo "  header    $HEADER"

# ---------------------------------------------------------------------------------------------
# The three sets, all derived and none written down twice.
#
#   expected exported   the KC_API declarations in the maintained helper header, plus the KC_API
#                       declarations of the hand written identity gate header
#   expected internal   the `static` trailing-underscore definitions in the maintained units
#   actual              what nm reports
# ---------------------------------------------------------------------------------------------

{
    sed -n 's/^KC_API [^(]*[^A-Za-z0-9_]\(ffkmp_[A-Za-z0-9_]*\)(.*/\1/p' "$HEADER"
    sed -n 's/^KC_API [^(]*[^A-Za-z0-9_]\(kc_[A-Za-z0-9_]*\)(.*/\1/p' "$ABI_HEADER"
} | sort -u > "$WORK/expected_exported.txt"
sed -n 's/^static [^(]*[^A-Za-z0-9_]\(ffkmp_[A-Za-z0-9_]*_\)(.*/\1/p' "$SRC"/*.c \
    | sort -u > "$WORK/expected_internal.txt"

"$NM" -m "$ARCHIVE" > "$WORK/nm_m.txt" 2>/dev/null

# nm prints one `archive.a(member.o):` banner per member and blank lines between them; neither is
# a symbol line.
grep -v -e '^$' -e ':$' "$WORK/nm_m.txt" > "$WORK/symbols.txt"

grep 'undefined' "$WORK/symbols.txt" | awk '{print $NF}' | sort -u > "$WORK/undefined.txt"
grep -v 'undefined' "$WORK/symbols.txt" | grep ' external ' | awk '{print $NF}' | sort -u \
    > "$WORK/external.txt"
grep -v 'undefined' "$WORK/symbols.txt" \
    | grep -E ' (non-external|was private external|private external) ' \
    | awk '{print $NF}' | sort -u > "$WORK/internal.txt"

echo "  members   $(grep -c ':$' "$WORK/nm_m.txt" | tr -d ' ')"
echo "  undefined $(wc -l < "$WORK/undefined.txt" | tr -d ' ')"
echo "  external  $(wc -l < "$WORK/external.txt" | tr -d ' ')"
echo

# ---------------------------------------------------------------------------------------------
# 1. Undefined symbols.
# ---------------------------------------------------------------------------------------------
echo "1. undefined symbols resolve only to libav*, libsw* and the allowlist"
: > "$WORK/unexpected_undefined.txt"
while read -r symbol; do
    [ -n "$symbol" ] || continue
    # The libav and libsw entry points. `_swscale_*` and `_swresample_*` are the library-level
    # queries (swscale_version, swscale_configuration and their siblings); the helper layer only ever
    # called `_sws_*` and `_swr_*`, so B1.6's identity gate is what made those two prefixes appear.
    case "$symbol" in
        _av_*|_avcodec_*|_avformat_*|_avfilter_*|_avutil_*|_avio_*) continue ;;
        _sws_*|_swr_*|_swscale_*|_swresample_*) continue ;;
    esac
    allowed=0
    for name in $ALLOWED_UNDEFINED; do
        [ "$symbol" = "$name" ] && allowed=1 && break
    done
    [ "$allowed" = 1 ] || echo "$symbol" >> "$WORK/unexpected_undefined.txt"
done < "$WORK/undefined.txt"
if [ -s "$WORK/unexpected_undefined.txt" ]; then
    fail "$(wc -l < "$WORK/unexpected_undefined.txt" | tr -d ' ') undefined symbol(s) are neither"
    echo "        a libav/libsw symbol nor on the allowlist:"
    sed 's/^/          /' "$WORK/unexpected_undefined.txt"
    echo "        Either the helper layer took a new dependency, or the allowlist needs a"
    echo "        measured entry with a reason, which is a normal commit and a log line."
else
    echo "  ok: every undefined symbol is libav/libsw or allowlisted"
    printf '      allowlisted and present:'
    for name in $ALLOWED_UNDEFINED; do
        grep -qx "$name" "$WORK/undefined.txt" && printf ' %s' "$name"
    done
    echo
    printf '      allowlisted and absent: '
    absent=""
    for name in $ALLOWED_UNDEFINED; do
        grep -qx "$name" "$WORK/undefined.txt" || absent="$absent $name"
    done
    echo "${absent:- none}"
fi
echo

# ---------------------------------------------------------------------------------------------
# 2. Forbidden calls. Checked by pattern over every symbol line, defined and undefined, so a
#    helper that DEFINED a printf wrapper would be caught too.
# ---------------------------------------------------------------------------------------------
echo "2. no printf, no av_log, no objc_msgSend, no dispatch_, no exit path"
for pattern in $FORBIDDEN_PATTERNS; do
    hits="$(grep -E "(^|[^A-Za-z0-9_])$pattern" "$WORK/symbols.txt" \
        | grep -v -E '(^|[^A-Za-z0-9_])_snprintf($|[^A-Za-z0-9_])' || true)"
    if [ -n "$hits" ]; then
        fail "the archive mentions '$pattern':"
        echo "$hits" | sed 's/^/          /'
    else
        echo "  ok: no '$pattern'"
    fi
done
echo

# ---------------------------------------------------------------------------------------------
# 3. The exported set is exactly the header's KC_API set.
# ---------------------------------------------------------------------------------------------
echo "3. exported symbols are exactly the KC_API declarations of the two headers"
sed 's/^/_/' "$WORK/expected_exported.txt" | sort > "$WORK/expected_external_symbols.txt"
comm -23 "$WORK/expected_external_symbols.txt" "$WORK/external.txt" > "$WORK/missing.txt"
comm -13 "$WORK/expected_external_symbols.txt" "$WORK/external.txt" > "$WORK/extra.txt"
echo "  header declares $(wc -l < "$WORK/expected_exported.txt" | tr -d ' ') KC_API functions"
echo "  archive exports $(wc -l < "$WORK/external.txt" | tr -d ' ') symbols"
if [ -s "$WORK/missing.txt" ]; then
    fail "declared KC_API but not exported:"
    sed 's/^/          /' "$WORK/missing.txt"
fi
if [ -s "$WORK/extra.txt" ]; then
    fail "exported but not a KC_API declaration; the archive promises more than the header:"
    sed 's/^/          /' "$WORK/extra.txt"
fi
[ -s "$WORK/missing.txt" ] || [ -s "$WORK/extra.txt" ] || echo "  ok: the two sets are equal"
echo

# ---------------------------------------------------------------------------------------------
# 4. The internals are not external.
# ---------------------------------------------------------------------------------------------
# Three outcomes are possible for a `static` helper and only one of them is a failure. Measured on
# the macos_arm64 archive at B1.4: ffkmp_graph_finish_ and ffkmp_graph_finish_multi_ are emitted as
# non-external symbols, and ffkmp_codec_pix_fmts_ and ffkmp_ch_layout_mask_ are absent entirely,
# because at -O2 clang inlined both into their only callers and had no reason to keep a symbol. An
# absent static helper is therefore correct rather than suspicious. What must never happen is the
# third outcome, an EXTERNAL one, which would mean the `static` was lost and the archive had
# started promising an internal helper to consumers.
echo "4. the trailing-underscore helpers are private to their unit"
echo "  units define $(wc -l < "$WORK/expected_internal.txt" | tr -d ' ') static helper(s)"
while read -r name; do
    [ -n "$name" ] || continue
    if grep -qx "_$name" "$WORK/external.txt"; then
        fail "$name is an EXTERNAL symbol; it is declared static, so this cannot happen"
    elif grep -qx "_$name" "$WORK/internal.txt"; then
        echo "  ok: $name is present and not external"
    else
        echo "  ok: $name is not in the symbol table at all, inlined into its caller"
    fi
done < "$WORK/expected_internal.txt"
echo

# ---------------------------------------------------------------------------------------------
# 5. Only the identity gate may write to a stream.
#
# Check 2 forbids printf outright, and check 1 allowlists _fputs and ___stderrp because plan section
# 15.6 question 3 makes the diagnostic bypass warning mandatory. An allowlist alone would let any
# future unit start printing under cover of that entry, so the permission is pinned to one file here.
# Source level rather than symbol level on purpose: nm cannot say which unit an archive-wide undefined
# symbol came from without per-member bookkeeping, and the source is the thing a reviewer reads.
echo "5. only src/kitecodec_abi.c writes to a stream"
PRINTING_UNITS="$(grep -l -E '\b(stderr|stdout|fputs|fputc|fwrite|puts|vfprintf|fprintf)\b' "$SRC"/*.c \
    | xargs -n1 basename | sort || true)"
if [ "$PRINTING_UNITS" = "kitecodec_abi.c" ]; then
    echo "  ok: kitecodec_abi.c and nothing else, which is the identity gate's bypass warning"
elif [ -z "$PRINTING_UNITS" ]; then
    fail "no unit mentions a stream at all; the diagnostic bypass warning that plan section 15.6"
    echo "        question 3 requires has gone missing, so a bypassed gate would now be silent."
else
    fail "these units mention a stream, and only kitecodec_abi.c may:"
    echo "$PRINTING_UNITS" | sed 's/^/          /'
fi
echo

# ---------------------------------------------------------------------------------------------
# 6. The exported set equals the COMMITTED baseline (interlude item I-09).
#
# Check 3 is a header-against-archive consistency check and not a baseline: declaring a new
# KC_API function beside its neighbours makes the new export "expected", which was measured at
# the interlude with a probe export that sailed through every check while nm confirmed the new
# symbol. This check is the baseline check 3 was mistaken for. The move procedure is in the
# file's own header; there is no second copy of it. Regenerate deliberately with
#   ./scripts/symbol-audit.sh --write-baseline
# in the same commit as the export change, and name every added or removed symbol in the
# commit message, which under the two-files rule is the record of the change.
# ---------------------------------------------------------------------------------------------
BASELINE_FILE="$ROOT/exported-symbols-baseline.txt"
sed 's/^_//' "$WORK/external.txt" | LC_ALL=C sort -u > "$WORK/actual_names.txt"
if [ "$WRITE_BASELINE" = 1 ]; then
    EXPORT_WAS="$(stamped_version "$BASELINE_FILE")"
    EXPORT_CHANGED=1
    if [ -f "$BASELINE_FILE" ]; then
        grep -v '^#' "$BASELINE_FILE" | grep -v '^$' | LC_ALL=C sort -u > "$WORK/prev_names.txt"
        cmp -s "$WORK/prev_names.txt" "$WORK/actual_names.txt" && EXPORT_CHANGED=0
    fi
    if [ "$EXPORT_CHANGED" = 1 ] && ! abi_is_newer_than "$EXPORT_WAS"; then
        fail "refusing to rewrite $BASELINE_FILE: the exported set changed but the ABI version"
        echo "        did not rise. The baseline was written at ${EXPORT_WAS:-no version} and"
        echo "        kitecodec_abi.h still says $ABI_VERSION. Raise KITECODEC_C_ABI_MINOR (or"
        echo "        MAJOR, if a declaration changed shape) in the same commit, then rerun."
        echo "        Growing the surface one deliberate rewrite at a time, with kc_abi_version()"
        echo "        answering the same number forever, is what this refusal exists to stop."
        echo
        exit "$status"
    fi
    {
        echo "# The exported symbol baseline of the KiteFFmpeg C archive (interlude item I-09)."
        echo "#"
        echo "$ABI_STAMP_PREFIX $ABI_VERSION"
        echo "#"
        echo "# Every external symbol the archive may export, one per line, without the Mach-O"
        echo "# underscore. symbol-audit.sh check 6 compares the archive against this file, so the"
        echo "# exported surface cannot grow or shrink silently even when the headers agree with"
        echo "# the archive (check 3 proves that agreement; it is consistency, not a ceiling)."
        echo "#"
        echo "# THE MOVE, and this file is the only place it is written down: change the exports"
        echo "# deliberately, run ./scripts/symbol-audit.sh --write-baseline in the same commit,"
        echo "# and name every added or removed symbol in that commit message."
        cat "$WORK/actual_names.txt"
    } > "$BASELINE_FILE"
    echo "6. baseline REWRITTEN at $BASELINE_FILE ($(wc -l < "$WORK/actual_names.txt" | tr -d ' ') names)"
    echo "   commit it with the export change it records, and log every added or removed name"
    echo
else
    echo "6. the exported set equals the committed baseline, name for name"
    if [ ! -f "$BASELINE_FILE" ]; then
        fail "$BASELINE_FILE does not exist; create it with: $0 --write-baseline"
    else
        grep -v '^#' "$BASELINE_FILE" | grep -v '^$' | LC_ALL=C sort -u > "$WORK/baseline_names.txt"
        comm -23 "$WORK/baseline_names.txt" "$WORK/actual_names.txt" > "$WORK/baseline_missing.txt"
        comm -13 "$WORK/baseline_names.txt" "$WORK/actual_names.txt" > "$WORK/baseline_extra.txt"
        echo "  baseline lists  $(wc -l < "$WORK/baseline_names.txt" | tr -d ' ') names"
        echo "  archive exports $(wc -l < "$WORK/actual_names.txt" | tr -d ' ') names"
        if [ -s "$WORK/baseline_missing.txt" ]; then
            fail "in the baseline but not exported (a removal nobody recorded):"
            sed 's/^/          /' "$WORK/baseline_missing.txt"
        fi
        if [ -s "$WORK/baseline_extra.txt" ]; then
            fail "exported but not in the baseline (a growth nobody recorded):"
            sed 's/^/          /' "$WORK/baseline_extra.txt"
            echo "        Deliberate? Rerun with --write-baseline in the same commit and log the names."
        fi
        [ -s "$WORK/baseline_missing.txt" ] || [ -s "$WORK/baseline_extra.txt" ] ||             echo "  ok: the two sets are equal"
    fi
    echo
fi

# ---------------------------------------------------------------------------------------------
# 7. The normalized public declaration set equals the COMMITTED signature baseline (S1.a.8).
#
# The export-name baseline in check 6 cannot detect a changed parameter type or an opaque alias
# silently retargeted to another C tag. This check records declaration SHAPES from all three public
# headers. Selection is per header and deliberate:
#
#   kitecodec_helpers.h  every KC_API prototype (190)
#   kitecodec_handles.h  every opaque typedef (11)
#   kitecodec_abi.h      KC_API prototypes (7), enum definitions (3), report typedef (1)
#
# Comments and preprocessor lines are discarded. Declarations may span lines, and a semicolon ends
# a record only at brace depth zero, so enum fields and the report fields remain inside their one
# complete record. Whitespace is normalized, records are C-locale sorted WITHOUT deduplication, and
# the exact installed scope is 216 records, two more since K2 bound the field order and the container bit rate.
#
# THE MOVE, written down here and nowhere else: change a public declaration
# deliberately, run
#   ./scripts/symbol-audit.sh --write-signature-baseline
# in the same commit, and name every changed record in that commit message. This is deliberately
# distinct from --write-baseline, which owns only export names.
# ---------------------------------------------------------------------------------------------
generate_signature_records() {
    awk '
        function strip_comments(line,    out, i, c, next_c) {
            out = ""
            i = 1
            while (i <= length(line)) {
                c = substr(line, i, 1)
                next_c = i < length(line) ? substr(line, i + 1, 1) : ""
                if (in_block_comment) {
                    if (c == "*" && next_c == "/") {
                        in_block_comment = 0
                        i += 2
                    } else {
                        i++
                    }
                } else if (c == "/" && next_c == "*") {
                    out = out " "
                    in_block_comment = 1
                    i += 2
                } else if (c == "/" && next_c == "/") {
                    break
                } else {
                    out = out c
                    i++
                }
            }
            return out
        }

        function normalized(value) {
            gsub(/[[:space:]]+/, " ", value)
            sub(/^ /, "", value)
            sub(/ $/, "", value)
            return value
        }

        FNR == 1 {
            if (NR > 1 && collecting) {
                print "symbol-audit.sh: unterminated selected declaration before " FILENAME > "/dev/stderr"
                exit 3
            }
            if (FILENAME ~ /kitecodec_helpers[.]h$/) role = "helpers"
            else if (FILENAME ~ /kitecodec_handles[.]h$/) role = "handles"
            else if (FILENAME ~ /kitecodec_abi[.]h$/) role = "abi"
            else {
                print "symbol-audit.sh: unexpected signature header " FILENAME > "/dev/stderr"
                exit 3
            }
            in_block_comment = 0
            in_preprocessor = 0
            collecting = 0
            brace_depth = 0
            record = ""
        }

        {
            clean = strip_comments($0)
            trimmed = clean
            sub(/^[[:space:]]+/, "", trimmed)

            if (in_preprocessor) {
                if (clean !~ /\\[[:space:]]*$/) in_preprocessor = 0
                next
            }
            if (trimmed ~ /^#/) {
                if (clean ~ /\\[[:space:]]*$/) in_preprocessor = 1
                next
            }

            if (!collecting) {
                selected = 0
                if (role == "helpers" && trimmed ~ /^KC_API[[:space:]]/) selected = 1
                if (role == "handles" && trimmed ~ /^typedef[[:space:]]/) selected = 1
                abi_selected = trimmed ~ /^KC_API[[:space:]]/ ||
                    trimmed ~ /^enum[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*[{]/ ||
                    trimmed ~ /^typedef[[:space:]]+struct[[:space:]]+kc_ffmpeg_report[[:space:]]*[{]/
                if (role == "abi" && abi_selected) selected = 1
                if (!selected) next
                collecting = 1
                brace_depth = 0
                record = ""
            }

            if (trimmed != "") {
                if (record != "") record = record " "
                record = record clean
            }
            for (i = 1; i <= length(clean); i++) {
                c = substr(clean, i, 1)
                if (c == "{") brace_depth++
                else if (c == "}") brace_depth--
                else if (c == ";" && brace_depth == 0) {
                    print normalized(record)
                    collecting = 0
                    brace_depth = 0
                    record = ""
                    break
                }
            }
        }

        END {
            if (collecting) {
                print "symbol-audit.sh: unterminated selected declaration at end of input" > "/dev/stderr"
                exit 3
            }
        }
    ' "$HEADER" "$HANDLES_HEADER" "$ABI_HEADER"
}

SIGNATURE_BASELINE_FILE="$ROOT/signature-baseline.txt"
generate_signature_records > "$WORK/actual_signatures_unsorted.txt"
LC_ALL=C sort "$WORK/actual_signatures_unsorted.txt" > "$WORK/actual_signatures.txt"
ACTUAL_SIGNATURE_COUNT="$(wc -l < "$WORK/actual_signatures.txt" | tr -d ' ')"

if [ "$WRITE_SIGNATURE_BASELINE" = 1 ]; then
    echo "7. normalized public declaration baseline"
    SIGNATURE_WAS="$(stamped_version "$SIGNATURE_BASELINE_FILE")"
    SIGNATURE_CHANGED=1
    if [ -f "$SIGNATURE_BASELINE_FILE" ]; then
        awk '!/^[[:space:]]*#/ && NF { print }' "$SIGNATURE_BASELINE_FILE" \
            | LC_ALL=C sort > "$WORK/prev_signatures.txt"
        cmp -s "$WORK/prev_signatures.txt" "$WORK/actual_signatures.txt" && SIGNATURE_CHANGED=0
    fi
    if [ "$ACTUAL_SIGNATURE_COUNT" -ne 216 ]; then
        fail "refusing to rewrite $SIGNATURE_BASELINE_FILE: expected 216 records, found $ACTUAL_SIGNATURE_COUNT"
    elif [ "$SIGNATURE_CHANGED" = 1 ] && ! abi_is_newer_than "$SIGNATURE_WAS"; then
        fail "refusing to rewrite $SIGNATURE_BASELINE_FILE: a public declaration changed but the"
        echo "        ABI version did not rise. The baseline was written at ${SIGNATURE_WAS:-no"
        echo "        version} and kitecodec_abi.h still says $ABI_VERSION. A declaration that"
        echo "        changed SHAPE is a major bump; one that was added compatibly is a minor one."
    else
        {
            echo "# The normalized public C declaration baseline of KiteFFmpeg (S1.a.8)."
            echo "#"
            echo "$ABI_STAMP_PREFIX $ABI_VERSION"
            echo "#"
            echo "# Exact scope: 188 helper KC_API prototypes, eleven opaque handle typedefs, seven"
            echo "# ABI KC_API prototypes, three ABI enum definitions and the full kc_ffmpeg_report"
            echo "# typedef. Comments and preprocessor lines are absent; whitespace is normalized;"
            echo "# records are sorted without deduplication. There must be exactly 216 records."
            echo "#"
            echo "# THE MOVE, and this file is the only place it is written down: change the public"
            echo "# declaration deliberately, run ./scripts/symbol-audit.sh"
            echo "# --write-signature-baseline in the same commit, and name every changed record in"
            echo "# that commit message. --write-baseline is separate and changes export names."
            cat "$WORK/actual_signatures.txt"
        } > "$SIGNATURE_BASELINE_FILE"
        echo "  baseline REWRITTEN at $SIGNATURE_BASELINE_FILE (216 records)"
        echo "  commit it with the declaration change it records and log every changed record"
    fi
    echo
else
    echo "7. public declaration shapes equal the committed signature baseline"
    echo "  selected $ACTUAL_SIGNATURE_COUNT normalized record(s); expected 216"
    if [ "$ACTUAL_SIGNATURE_COUNT" -ne 216 ]; then
        fail "public declaration selection changed scope: expected 216 records, found $ACTUAL_SIGNATURE_COUNT"
    fi
    if [ ! -f "$SIGNATURE_BASELINE_FILE" ]; then
        fail "$SIGNATURE_BASELINE_FILE does not exist; create it with: $0 --write-signature-baseline"
    else
        awk '!/^[[:space:]]*#/ && NF { print }' "$SIGNATURE_BASELINE_FILE" \
            > "$WORK/baseline_signatures_unsorted.txt"
        LC_ALL=C sort "$WORK/baseline_signatures_unsorted.txt" > "$WORK/baseline_signatures.txt"
        BASELINE_SIGNATURE_COUNT="$(wc -l < "$WORK/baseline_signatures.txt" | tr -d ' ')"
        comm -23 "$WORK/baseline_signatures.txt" "$WORK/actual_signatures.txt" \
            > "$WORK/signatures_missing.txt"
        comm -13 "$WORK/baseline_signatures.txt" "$WORK/actual_signatures.txt" \
            > "$WORK/signatures_extra.txt"
        echo "  baseline lists $BASELINE_SIGNATURE_COUNT record(s)"
        if [ "$BASELINE_SIGNATURE_COUNT" -ne 216 ]; then
            fail "signature baseline scope is not 216 records"
        fi
        if [ -s "$WORK/signatures_missing.txt" ]; then
            fail "in the signature baseline but not in the headers (removed or changed):"
            sed 's/^/          /' "$WORK/signatures_missing.txt"
        fi
        if [ -s "$WORK/signatures_extra.txt" ]; then
            fail "in the headers but not in the signature baseline (added or changed):"
            sed 's/^/          /' "$WORK/signatures_extra.txt"
            echo "        Deliberate? Rerun with --write-signature-baseline in the same commit."
        fi
        [ -s "$WORK/signatures_missing.txt" ] || [ -s "$WORK/signatures_extra.txt" ] || \
            echo "  ok: all 216 records are equal"
    fi
    echo
fi

echo "8. both baselines were written at the ABI version the headers now claim"
echo "  kitecodec_abi.h says $ABI_VERSION"
for baseline in "$BASELINE_FILE" "$SIGNATURE_BASELINE_FILE"; do
    [ -f "$baseline" ] || continue
    stamp="$(stamped_version "$baseline")"
    if [ -z "$stamp" ]; then
        fail "$(basename "$baseline") carries no '$ABI_STAMP_PREFIX' line, so nothing ties the"
        echo "        surface it records to a version. Rewrite it with the matching --write flag."
    elif [ "$stamp" != "$ABI_VERSION" ]; then
        fail "$(basename "$baseline") was written at $stamp, the headers say $ABI_VERSION."
        echo "        If the version rose on purpose, rewrite the baseline in the same commit so"
        echo "        the stamp follows. If it did not, something moved the version alone."
    else
        echo "  ok: $(basename "$baseline") stamped $stamp"
    fi
done
echo

if [ "$status" -eq 0 ]; then
    echo "symbol-audit.sh: PASS"
else
    echo "symbol-audit.sh: FAILED, see the lines marked FAIL above" >&2
fi
exit "$status"
