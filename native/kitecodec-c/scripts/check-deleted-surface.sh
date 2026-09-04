#!/usr/bin/env bash
#
# The repository-wide cross-check for the 15 helpers that were deleted.
#
# Why it exists. Those 15 were exported symbols of a versioned library that no Kotlin file
# imported: a compatibility promise nobody meant to make. Deleting them is only safe if nothing
# anywhere refers to them, and "nothing" has to mean both repositories and every file type, not
# just the ones a Kotlin developer thinks to grep.
#
# Why it must run with kiteffmpeg/src/nativeInterop/cinterop/archived/ already deleted. That
# directory held six def files that no build file referenced and that redefined the same helper
# names. A grep run while it still existed reported a definition for almost every deleted name and
# would have masked a real reference behind duplicate noise. Plan section 15.2 B1.4 step 3 puts the
# deletion first for exactly that reason, and this script fails outright if the directory is back.
#
# What counts as a reference, stated exactly so the check is not fuzzy. Three questions:
#
#   1. Is any deleted name USED anywhere? A use is the name followed by an open parenthesis: a
#      call, a definition or a declaration. Prose cannot match that shape, so this question has a
#      mechanical answer and zero is the only acceptable one.
#   2. Does any Kotlin source or any def file mention one at all, in any form? Those are the two
#      file kinds where a mention is never bookkeeping: a Kotlin `import ffmpeg.<name>` or a def
#      body line is a real dependency. Zero again.
#   3. Which files still mention a deleted name as prose? Those are the record of the deletion
#      itself, and they are confined to an allowlist below. A mention outside it fails, so a new
#      reference cannot arrive disguised as a comment.
#
# The exclusions are --exclude-dir and never a `| grep -v build/` pipe. The pipe filters the OUTPUT
# LINE, so it silently drops a real hit whose own text happens to contain the word, which is the
# mistake plan section 9 records against the em dash scan: three real em dashes hid behind lines
# that mentioned "vendor/ffmpeg" and "build/install". `.claude/worktrees` holds gitignored scratch
# checkouts of this same repository at older commits, where every deleted helper is still present
# and correct, so it is excluded as a directory too.
#
# `native-libs` and `node_modules` are excluded for SPEED, not correctness. Both hold only
# gitignored build output, and a static archive cannot match this scan's pattern anyway because the
# pattern requires a following `(`. But the scan runs once per deleted name over both repositories,
# and `native-libs` reached 284 MB once the wasm32 tree landed, which made a check that plan
# section 9 budgets in seconds take minutes instead. Generated trees stay out so Tier 1 stays fast.
#
# Usage:  ./scripts/check-deleted-surface.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
OTHER="$(cd "$REPO/../KitePlayer" 2>/dev/null && pwd || true)"

# The names live in ONE committed data file since the interlude: deleted-surface.txt,
# beside this script's parent directory. Before that they were hardcoded in three places (here,
# verify-lift.sh and the extractor's DELETED table) with no move procedure, so resurrecting a
# name for a real need had no legal path. Now a resurrection is one status change in that file
# plus one Execution log sentence, and this script keeps its full power over every name still
# marked deleted. The file's own integrity is check 4 below.
SURFACE_FILE="$ROOT/deleted-surface.txt"
DELETED=""
RESURRECTED=""

# Files allowed to mention a deleted name in prose, each because it is part of the record of the
# deletion rather than a use of it: the data file that IS the list, the two test files that record
# why their cases went, and the tree's own README. This script itself is deliberately NOT on the
# list: it reads the names instead of containing them, so a mention appearing in it again would be
# a regression worth failing on. Paths are relative to the repository root.
#
# The list is short on purpose. A new entry means someone wrote a deleted name into prose, and the
# check refuses it until a reason is given here.
#
# The list held a path in the sibling repository once, and that is the failure mode a cross-repository
# allowlist has: the other repository renamed the file, this check went red, and the allowlist was
# pointing at a path that no longer existed while the prose it excused had moved somewhere unlisted.
# Do not add a path outside this repository again.
#
# It happened AGAIN, and this time nothing went red. The docs reset of 2026-08-29 deleted every
# planning file in KitePlayer except MASTER_PLAN.md and GOTCHAS.md, so the entry pointed at a ghost
# for a day while the script printed a NOTE and passed. A note is not a guard, which is why a
# missing allowlist path is now check 5 and fails. The entry is gone rather than repointed: under
# the two-files rule the record of a deletion is the commit that made it, not a document.
ALLOWED_PROSE="
native/kitecodec-c/deleted-surface.txt
native/kitecodec-c/tests/test_ownership.c
native/kitecodec-c/tests/test_rescale.c
native/kitecodec-c/README.md
"

# Every excluded directory is gitignored in one repository or the other: build output, the Gradle
# and Kotlin caches, the vendored study clones, the generated test clips, and the scratch worktrees.
# `.kotlin` earns its place for a reason worth naming: the commonizer keeps binary `.knm` metadata
# there, and those files carry the old helper names as text until Gradle regenerates them, so
# without this exclusion the check reports seven stale cache files and buries the answer.
EXCLUDES="--exclude-dir=build --exclude-dir=.claude --exclude-dir=.git --exclude-dir=vendor \
--exclude-dir=.gradle --exclude-dir=.kotlin --exclude-dir=testmedia --exclude-dir=native-libs \
--exclude-dir=node_modules"

ARCHIVED="$REPO/kiteffmpeg/src/nativeInterop/cinterop/archived"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

status=0
fail() {
    echo "  FAIL: $*"
    status=1
}

echo "check-deleted-surface.sh: the deleted helper surface"
echo "  KiteFFmpeg   $REPO"
echo "  KitePlayer  ${OTHER:-not found beside this repository}"
echo "  list        $SURFACE_FILE"

# Check 4 runs FIRST, as the file's integrity pass: everything below trusts what it parses here.
# Fifteen names total, each exactly once, each with a valid status; a malformed line, a lost line
# or a typo fails before it can quietly weaken checks 1 to 3.
echo
echo "4. deleted-surface.txt parses, holds exactly the 15 known names, and every status is legal"
if [ ! -f "$SURFACE_FILE" ]; then
    fail "$SURFACE_FILE does not exist"
else
    integrity_before=$status
    while read -r entry_name entry_status entry_extra; do
        case "$entry_name" in ''|'#'*) continue ;; esac
        if [ -n "$entry_extra" ]; then
            fail "malformed line (more than two fields): $entry_name $entry_status $entry_extra"; continue
        fi
        case "$entry_name" in
            ffkmp_[a-z0-9_]*) ;;
            *) fail "not a helper name: '$entry_name'"; continue ;;
        esac
        case "$entry_status" in
            deleted)             DELETED="$DELETED $entry_name" ;;
            resurrected-in-?*)   RESURRECTED="$RESURRECTED $entry_name"
                                 echo "  note: $entry_name is $entry_status and is exempt from checks 1 to 3" ;;
            *)                   fail "unknown status '$entry_status' for $entry_name" ;;
        esac
    done < "$SURFACE_FILE"
    TOTAL=$(( $(echo $DELETED | wc -w) + $(echo $RESURRECTED | wc -w) ))
    if [ "$TOTAL" -ne 15 ]; then
        fail "the file lists $TOTAL names and the deletion was 15; a lost or added line"
    fi
    DUPES="$(echo $DELETED $RESURRECTED | tr ' ' '\n' | sort | uniq -d)"
    if [ -n "$DUPES" ]; then
        fail "duplicate name(s): $DUPES"
    fi
    # A list where nothing is held dead is not a passing state, it is the guard switched off.
    # Checks 1 to 3 loop over $DELETED, so an empty set makes all three print "ok" over zero work
    # and the script announces that every name marked deleted is gone. Measured on 2026-08-30 by
    # marking all fifteen resurrected: three greens and an exit code of zero.
    if [ -z "$(echo $DELETED | tr -d ' ')" ]; then
        fail "no name is marked deleted, so checks 1 to 3 below would pass over nothing."
        echo "        Resurrecting the last name is a real decision; it is not this check going quiet."
    fi
    if [ "$status" -eq "$integrity_before" ]; then
        echo "  ok: 15 names, $(echo $DELETED | wc -w | tr -d ' ') deleted, $(echo $RESURRECTED | wc -w | tr -d ' ') resurrected"
    fi
fi
echo "  names checked below: $(echo $DELETED | wc -w | tr -d ' ')"
echo

echo "0. the archived/ directory is gone, so duplicate definitions cannot mask a reference"
if [ -e "$ARCHIVED" ]; then
    fail "$ARCHIVED still exists. Delete it before this check means anything: it redefines"
    echo "        almost every deleted name and would answer question 1 for the wrong reason."
else
    echo "  ok: $ARCHIVED does not exist"
fi
echo

TREES="$REPO"
[ -n "$OTHER" ] && TREES="$TREES $OTHER"

echo "1. no deleted name is used, that is followed by an open parenthesis, anywhere"
: > "$WORK/uses.txt"
for name in $DELETED; do
    # shellcheck disable=SC2086
    # The braces around name are not decoration. Written bare, `$name[[:space:]]` reads to a human
    # and to shellcheck (SC1087) as an array subscript, and shellcheck grades that an error rather
    # than a style note. bash expands it correctly either way, so this is a legibility fix and not
    # a behaviour fix: the script's output was verified identical before and after.
    grep -rnE "(^|[^A-Za-z0-9_])${name}[[:space:]]*\(" $EXCLUDES $TREES >> "$WORK/uses.txt" 2>/dev/null || true
done
if [ -s "$WORK/uses.txt" ]; then
    fail "$(wc -l < "$WORK/uses.txt" | tr -d ' ') use site(s) survive:"
    sed 's|^|          |' "$WORK/uses.txt"
else
    echo "  ok: zero use sites in either repository, in any file type"
fi
echo

echo "2. no Kotlin source and no def file mentions a deleted name at all"
: > "$WORK/kotlin.txt"
for name in $DELETED; do
    # shellcheck disable=SC2086
    grep -rnw "$name" --include="*.kt" --include="*.kts" --include="*.def" \
        $EXCLUDES $TREES >> "$WORK/kotlin.txt" 2>/dev/null || true
done
if [ -s "$WORK/kotlin.txt" ]; then
    fail "$(wc -l < "$WORK/kotlin.txt" | tr -d ' ') mention(s) in Kotlin or def files:"
    sed 's|^|          |' "$WORK/kotlin.txt"
else
    echo "  ok: zero mentions in *.kt, *.kts and *.def"
fi
echo

echo "3. every surviving prose mention is in a file that records the deletion"
: > "$WORK/prose.txt"
for name in $DELETED; do
    # shellcheck disable=SC2086
    grep -rlw "$name" $EXCLUDES $TREES >> "$WORK/prose.txt" 2>/dev/null || true
done
sort -u "$WORK/prose.txt" > "$WORK/prose_files.txt"
: > "$WORK/allowed.txt"
for path in $ALLOWED_PROSE; do
    case "$path" in
        ../*) echo "$(cd "$REPO/.." && pwd)/${path#../}" >> "$WORK/allowed.txt" ;;
        *)    echo "$REPO/$path" >> "$WORK/allowed.txt" ;;
    esac
done
sort -u "$WORK/allowed.txt" -o "$WORK/allowed.txt"
comm -23 "$WORK/prose_files.txt" "$WORK/allowed.txt" > "$WORK/unexpected.txt"
comm -13 "$WORK/prose_files.txt" "$WORK/allowed.txt" > "$WORK/silent.txt"
echo "  files mentioning a deleted name: $(wc -l < "$WORK/prose_files.txt" | tr -d ' ')"
while read -r file; do
    [ -n "$file" ] || continue
    echo "    ${file#"$REPO"/}"
done < "$WORK/prose_files.txt"
if [ -s "$WORK/unexpected.txt" ]; then
    fail "not on the allowlist:"
    sed 's|^|          |' "$WORK/unexpected.txt"
    echo "        A prose mention outside the record of the deletion is how a real reference"
    echo "        arrives disguised. Either remove it or add the file with a reason."
else
    echo "  ok: every one is on the allowlist"
fi
if [ -s "$WORK/silent.txt" ]; then
    echo "  note: allowlisted but mentioning nothing, so the entry is stale:"
    sed 's|^|          |' "$WORK/silent.txt"
fi
echo

# An allowlist entry excuses prose in a file. A path that does not exist excuses nothing, and it is
# how the allowlist rots without anyone seeing: check 3 reports it as merely "stale" and passes.
# That is not hypothetical here. An entry pointing into the sibling repository outlived the file it
# named twice, once at a rename and once at a documentation reset.
echo "5. every allowlisted path exists, so no entry is excusing a file that is gone"
: > "$WORK/ghosts.txt"
while read -r file; do
    [ -n "$file" ] || continue
    [ -e "$file" ] || echo "$file" >> "$WORK/ghosts.txt"
done < "$WORK/allowed.txt"
if [ -s "$WORK/ghosts.txt" ]; then
    fail "$(wc -l < "$WORK/ghosts.txt" | tr -d ' ') allowlisted path(s) do not exist:"
    sed 's|^|          |' "$WORK/ghosts.txt"
    echo "        Remove the entry, or repoint it at wherever the prose went."
else
    echo "  ok: all $(wc -l < "$WORK/allowed.txt" | tr -d ' ') allowlisted paths exist"
fi
echo


if [ "$status" -eq 0 ]; then
    echo "check-deleted-surface.sh: PASS, every name marked deleted is gone and nothing refers to it"
else
    echo "check-deleted-surface.sh: FAILED, see the lines marked FAIL above" >&2
fi
exit "$status"
