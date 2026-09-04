#!/usr/bin/env bash
#
# Keeps dependency resolution predictable: same inputs in, same artifacts out.
#
# Why this shape and not a lockfile. The plan's row offered a choice, so both were tried.
#
#   Artifact checksum verification (gradle/verification-metadata.xml) does not fit this build yet.
#   Generating it here produced 486 components from a SINGLE jvm compile, and the very next task
#   failed with "Dependency verification failed for configuration ':detachedConfiguration1'":
#   Kotlin/Native and the Node setup resolve through detached configurations that the generator
#   never saw. Worse, CI runs on macOS, Linux and Windows, and each host resolves its own toolchain
#   artifacts. A file written on one machine cannot carry the other two, and there is no way to
#   produce their halves from here. Turning it on would redden every CI job on two OSes to buy
#   verification on one. The row that records this stays open with that finding attached.
#
#   Version lockfiles would mostly restate gradle/libs.versions.toml, which already pins every
#   direct version, and they fight this project's preference for staying on current releases.
#
# So this checks the things that actually leak, all of which are true in the tree today and none
# of which anything stops going wrong tomorrow. Each rule is a real failure mode, not a style
# preference:
#
#   1. A dynamic version in the catalog. `1.+` or `latest.release` means two checkouts of the same
#      commit can build different bytes, and the difference shows up as a bug nobody can reproduce.
#   2. A dependency coordinate written inline in a build file. It bypasses the catalog, so the
#      version is invisible to anyone reading the one file that is supposed to list them all.
#   3. A `repositories { }` block in a module. Settings owns resolution for the whole build; a
#      module that adds its own can pull the same coordinate from somewhere else entirely.
#   4. mavenLocal anywhere. This build does not use it and must not start by accident: when it is
#      on it is consulted FIRST and wins SILENTLY, so a locally published artifact replaces the
#      real one under the same name with nothing in the log to tell them apart. The sibling
#      repository does allow it, behind an explicit flag, and explains the trade there.
#   5. A plain-http repository or distribution URL. Anyone on the path can serve their own bytes.
#   6. A wrapper with no distributionSha256Sum, which is the only thing pinning the Gradle build
#      itself.
#
# NOT checked here, because CI already does it better: the wrapper JAR's own checksum.
# gradle/actions/setup-gradle validates it on every job by default, against Gradle's published
# list of known-good jars. That list needs the network and goes stale in a checked-in copy, so
# this script does not keep one. The two sibling repositories carry wrapper jars with DIFFERENT
# checksums, which is expected and not a finding: the jar comes from whichever Gradle ran
# `wrapper`, not from the distribution it points at.
#
#   ./scripts/check-dependency-hygiene.sh             # must PASS
#   ./scripts/check-dependency-hygiene.sh --falsify   # plants each violation in turn, must FAIL

set -euo pipefail

MODE="${1:-real}"
case "$MODE" in real|--falsify) ;; *) echo "usage: $0 [--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Build files, excluding generated trees. Every rule below reads this same list, so a rule can
# never quietly cover fewer files than another.
build_files() {
    find . \
        \( -name build -o -name .git -o -name node_modules -o -name native-libs -o -name .claude \) -prune -o \
        \( -name "*.gradle.kts" -o -name "libs.versions.toml" -o -name "gradle-wrapper.properties" \) -print
}

failures=0
note() { printf '  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }
pass() { printf 'ok    %s\n' "$1"; }

CATALOG="gradle/libs.versions.toml"
SETTINGS="settings.gradle.kts"

echo "check-dependency-hygiene.sh: $ROOT"
echo

# ---- 1. No dynamic versions in the catalog ----
#
# The three spellings Gradle accepts: a trailing +, a `latest.` selector, and a Maven range in
# brackets. Anchored to the version string so a plugin id containing a bracket cannot match.
dynamic=$(grep -nE '=\s*"[^"]*(\+|latest\.)[^"]*"|=\s*"[]\[][^"]*[]\[)]"' "$CATALOG" || true)
if [ -n "$dynamic" ]; then
    fail "the version catalog carries a dynamic version"
    printf '%s\n' "$dynamic" | while IFS= read -r line; do note "$CATALOG:$line"; done
else
    pass "every version in the catalog is exact"
fi

# ---- 2. No dependency coordinate written inline ----
#
# group:artifact:version inside a dependency call. The version segment must start with a digit, so
# `kotlin("test")` and a project accessor cannot match, and neither can a plugin id.
inline=$(build_files | grep -v "gradle-wrapper.properties" | xargs grep -nE \
    '(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|classpath|ksp|kapt)\("[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[0-9]' 2>/dev/null || true)
if [ -n "$inline" ]; then
    fail "a dependency is declared inline instead of through the catalog"
    printf '%s\n' "$inline" | while IFS= read -r line; do note "$line"; done
else
    pass "every dependency goes through the version catalog"
fi

# ---- 3. Repositories are declared in settings and buildSrc only ----
#
# buildSrc is a separate build with its own settings, so it declares its own and that is correct.
repos=$(build_files | xargs grep -ln '^[[:space:]]*repositories[[:space:]]*{' 2>/dev/null | \
    grep -vE '^\./(settings\.gradle\.kts|buildSrc/(build|settings)\.gradle\.kts)$' || true)
if [ -n "$repos" ]; then
    fail "a module declares its own repositories, so settings no longer owns resolution"
    printf '%s\n' "$repos" | while IFS= read -r f; do note "$f"; done
else
    pass "resolution is owned by settings and buildSrc alone"
fi

# ---- 4. mavenLocal is not in this build at all ----
#
# Prose mentioning mavenLocal is fine; a call is what matters, so the pattern requires the
# parentheses.
unguarded=$(build_files | xargs grep -n 'mavenLocal()' 2>/dev/null || true)
if [ -n "$unguarded" ]; then
    fail "mavenLocal is reachable, and it wins silently over Central when it is"
    printf '%s\n' "$unguarded" | while IFS= read -r line; do note "$line"; done
else
    pass "mavenLocal is absent, so nothing can shadow Central"
fi

# ---- 5. Nothing resolves over plain http ----
plainhttp=$(build_files | xargs grep -nE 'http://[a-zA-Z0-9]' 2>/dev/null | grep -v 'http://localhost' || true)
if [ -n "$plainhttp" ]; then
    fail "something resolves over plain http"
    printf '%s\n' "$plainhttp" | while IFS= read -r line; do note "$line"; done
else
    pass "every repository and distribution URL is https"
fi

# ---- 6. The Gradle distribution is pinned by checksum ----
for props in $(build_files | grep "gradle-wrapper.properties"); do
    if grep -q '^distributionSha256Sum=[0-9a-f]\{64\}$' "$props"; then
        pass "$props pins its distribution by sha256"
    else
        fail "$props does not pin distributionSha256Sum, so the Gradle build itself is unverified"
    fi
done

echo
if [ "$MODE" = "--falsify" ]; then
    echo "== FALSIFICATION arm: each rule is planted in turn and must be caught"
    echo
    scratch=$(mktemp -d)
    trap 'rm -rf "$scratch"' EXIT

    # Every file any plant touches, backed up once and restored after each one. A plant that
    # forgot to restore would leave the next plant testing two violations at a time, and the arm
    # would still print "caught" for the wrong reason.
    WRAPPER="gradle/wrapper/gradle-wrapper.properties"
    MODULE="kiteffmpeg/build.gradle.kts"
    TOUCHED="$CATALOG $SETTINGS $WRAPPER $MODULE"
    for f in $TOUCHED; do cp "$f" "$scratch/$(echo "$f" | tr / _)"; done
    restore() { for f in $TOUCHED; do cp "$scratch/$(echo "$f" | tr / _)" "$f"; done; }

    caught=0
    total=0
    plant() {
        # plant <description> <command that introduces one violation>
        total=$((total + 1))
        eval "$2"
        if "$0" > /dev/null 2>&1; then
            echo "NOT CAUGHT: $1"
        else
            echo "caught:     $1"
            caught=$((caught + 1))
        fi
        restore
    }

    plant "a dynamic version in the catalog" \
        "printf '\nfalsify-dynamic = \"1.+\"\n' >> '$CATALOG'"
    plant "a mavenLocal appearing in settings" \
        "printf '\n// falsify\nval falsifyRepos = { mavenLocal() }\n' >> '$SETTINGS'"
    plant "a plain-http repository in settings" \
        "printf '\n// falsify http://insecure.example.com\n' >> '$SETTINGS'"
    plant "a dependency coordinate written inline in a module" \
        "printf '\ndependencies { implementation(\"com.example:falsify:1.0.0\") }\n' >> '$MODULE'"
    plant "a module declaring its own repositories" \
        "printf '\nrepositories {\n    mavenCentral()\n}\n' >> '$MODULE'"
    plant "a wrapper that stopped pinning its distribution" \
        "grep -v '^distributionSha256Sum=' '$WRAPPER' > '$scratch/w' && cp '$scratch/w' '$WRAPPER'"

    restore
    echo
    if [ "$caught" -eq "$total" ]; then
        echo "check-dependency-hygiene.sh: FALSIFICATION PASS, $caught of $total planted violations caught"
        exit 0
    fi
    echo "check-dependency-hygiene.sh: FALSIFICATION FAIL, only $caught of $total caught"
    exit 1
fi

if [ "$failures" -eq 0 ]; then
    echo "check-dependency-hygiene.sh: PASS"
    exit 0
fi
echo "check-dependency-hygiene.sh: FAIL, $failures rule(s) broken"
exit 1
