#!/usr/bin/env bash
#
# Keeps dependency resolution predictable: same inputs in, same artifacts out.
#
# The build uses the Kotlin Toolchain. Its build files are project.yaml, every module.yaml, the
# *.module-template.yaml files, libs.versions.toml (the version catalog) and the `kotlin` wrapper.
#
# Why this shape and not a lockfile. The toolchain has no lockfile and no checksum verification of
# artifacts. Version lockfiles would also mostly restate libs.versions.toml, which already pins every
# direct version, and they fight this project's preference for staying on current releases.
#
# So this checks the things that actually leak. Each rule is a real failure mode, not a style
# preference:
#
#   1. A dynamic version in the catalog. `1.+` or `latest.release` means two checkouts of the same
#      commit can build different bytes, and the difference shows up as a bug nobody can reproduce.
#   2. A dependency coordinate written inline in a module file. It bypasses the catalog, so the
#      version is invisible to anyone reading the one file that is supposed to list them all.
#      The build plugins in build-plugins/ are not checked: they pin the versions of their own tools,
#      and a plugin.yaml classpath cannot read the catalog.
#   3. A `repositories:` block in a module. The toolchain resolves from Maven Central and Google, and
#      a module that adds its own repository can pull the same coordinate from somewhere else.
#      library.module-template.yaml may declare mavenLocal, for publishing only (rule 4).
#   4. A mavenLocal that resolves. When it resolves, a locally published artifact can replace the
#      real one under the same name with nothing in the log to tell them apart. It must say
#      `resolve: false`, so it only receives `./kotlin publish mavenLocal`.
#   5. A plain-http repository or distribution URL. Anyone on the path can serve their own bytes.
#   6. A wrapper with no kotlin_cli_sha256, which is the only thing pinning the toolchain itself.
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
        \( -name build -o -name .git -o -name node_modules -o -name native-libs -o -name vendor -o -name .claude \) -prune -o \
        \( -name project.yaml -o -name module.yaml -o -name "*.module-template.yaml" -o -name libs.versions.toml \) -print
}

failures=0
note() { printf '  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }
pass() { printf 'ok    %s\n' "$1"; }

CATALOG="libs.versions.toml"
TEMPLATE="library.module-template.yaml"
WRAPPERS="kotlin kotlin.bat"

echo "check-dependency-hygiene.sh: $ROOT"
echo

# ---- 1. No dynamic versions in the catalog ----
#
# The three dynamic spellings: a trailing +, a `latest.` selector, and a Maven range in
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
# group:artifact:version as a list entry. The version segment must start with a digit, so a
# `$libs.` reference and a `//module` reference cannot match.
inline=$(build_files | grep -v '^\./build-plugins/' | xargs grep -nE \
    '^[[:space:]]*-[[:space:]]+[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[0-9]' 2>/dev/null || true)
if [ -n "$inline" ]; then
    fail "a dependency is declared inline instead of through the catalog"
    printf '%s\n' "$inline" | while IFS= read -r line; do note "$line"; done
else
    pass "every dependency goes through the version catalog"
fi

# ---- 3. No module declares its own repositories ----
repos=$(build_files | grep -v "^\./$TEMPLATE\$" | xargs grep -ln '^repositories:' 2>/dev/null || true)
if [ -n "$repos" ]; then
    fail "a module declares its own repositories"
    printf '%s\n' "$repos" | while IFS= read -r f; do note "$f"; done
else
    pass "resolution is owned by the toolchain defaults"
fi

# ---- 4. mavenLocal never resolves ----
#
# Each `- url: mavenLocal` entry must carry `resolve: false` before the next entry starts.
resolving=$(build_files | while IFS= read -r f; do
    awk -v f="$f" '
        /^[[:space:]]*-[[:space:]]/ { if (local && !off) print f ":" line; local = 0; off = 0 }
        /url:[[:space:]]*mavenLocal/ { local = 1; line = NR }
        local && /resolve:[[:space:]]*false/ { off = 1 }
        END { if (local && !off) print f ":" line }
    ' "$f"
done)
if [ -n "$resolving" ]; then
    fail "mavenLocal can resolve, and it can then shadow Central"
    printf '%s\n' "$resolving" | while IFS= read -r line; do note "$line"; done
else
    pass "mavenLocal is publish-only, so nothing can shadow Central"
fi

# ---- 5. Nothing resolves over plain http ----
plainhttp=$( (build_files; for w in $WRAPPERS; do echo "./$w"; done) | xargs grep -nE 'http://[a-zA-Z0-9]' 2>/dev/null | grep -v 'http://localhost' || true)
if [ -n "$plainhttp" ]; then
    fail "something resolves over plain http"
    printf '%s\n' "$plainhttp" | while IFS= read -r line; do note "$line"; done
else
    pass "every repository and distribution URL is https"
fi

# ---- 6. The toolchain distribution is pinned by checksum ----
for wrapper in $WRAPPERS; do
    if grep -qE '^(set )?kotlin_cli_sha256=[0-9a-f]{64}[[:space:]]*$' "$wrapper"; then
        pass "$wrapper pins its distribution by sha256"
    else
        fail "$wrapper does not pin kotlin_cli_sha256, so the toolchain itself is unverified"
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
    MODULE="kiteffmpeg/module.yaml"
    TOUCHED="$CATALOG $TEMPLATE kotlin $MODULE"
    for f in $TOUCHED; do cp -p "$f" "$scratch/$(echo "$f" | tr / _)"; done
    restore() { for f in $TOUCHED; do cp -p "$scratch/$(echo "$f" | tr / _)" "$f"; done; }

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
    plant "a mavenLocal that resolves" \
        "sed 's/resolve: false/resolve: true/' '$scratch/$TEMPLATE' > '$TEMPLATE'"
    plant "a plain-http repository in the template" \
        "printf '\n# falsify http://insecure.example.com\n' >> '$TEMPLATE'"
    plant "a dependency coordinate written inline in a module" \
        "printf '\ndependencies:\n  - com.example:falsify:1.0.0\n' >> '$MODULE'"
    plant "a module declaring its own repositories" \
        "printf '\nrepositories:\n  - url: https://repo.example.com\n' >> '$MODULE'"
    plant "a wrapper that stopped pinning its distribution" \
        "grep -v '^kotlin_cli_sha256=' '$scratch/kotlin' > kotlin"

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
