#!/usr/bin/env bash
# Lists the files a publish run hands out, as checksum lines, for the build attestation (#215).
#
#   scripts/attestation-subjects.sh maven       the published Maven files of this version
#   scripts/attestation-subjects.sh release     the bill of materials
#
# Each line is `<sha256>  <name>`, the form `sha256sum` writes and the attestation action reads.
# A Maven file is named by its path in the repository, for example
# io/github/yuroyami/kiteffmpeg/0.5.1/kiteffmpeg-0.5.1.module, and a bill of materials by its
# folder under build and its file name. Lines are sorted by name, so two listings compare with
# diff.
#
# The Maven files are read from the local Maven repository that `./kotlin publish mavenLocal`
# writes: every jar, klib, AAR, POM, module metadata and other file of each kiteffmpeg artifact at
# the version in library.module-template.yaml. Signatures, checksum files and the local metadata
# are left out, because a signature is made again for Maven Central with another key and the rest
# are not artifacts.
#
# KITEFFMPEG_POM_REPOSITORY names another local Maven repository than ~/.m2/repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
kind="${1:-}"

digest() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# The `version:` line under `publishing:` (the one under `kotlin:` is the Kotlin version).
version="$(awk '/^  publishing:/ { inside = 1; next } /^  [a-z]/ { inside = 0 }
    inside && /^    version:/ { print $2 }' "$ROOT/library.module-template.yaml" | tr -d '[:space:]')"
if [ -z "$version" ]; then
    echo "attestation-subjects.sh: no publishing version in library.module-template.yaml" >&2
    exit 2
fi

case "$kind" in
    maven)
        repository="${KITEFFMPEG_POM_REPOSITORY:-$HOME/.m2/repository}"
        group="$repository/io/github/yuroyami"
        if [ ! -d "$group" ]; then
            echo "attestation-subjects.sh: nothing is published under $group" >&2
            exit 1
        fi
        found=0
        while IFS= read -r file; do
            found=$((found + 1))
            printf '%s  %s\n' "$(digest "$file")" "${file#"$repository"/}"
        done < <(find "$group" -path "$group/kiteffmpeg*/$version/*" -type f \
            ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
            ! -name 'maven-metadata*' ! -name '_remote.repositories' | LC_ALL=C sort)
        if [ "$found" -eq 0 ]; then
            echo "attestation-subjects.sh: no kiteffmpeg file of version $version under $group" >&2
            exit 1
        fi
        ;;
    release)
        found=0
        while IFS= read -r file; do
            found=$((found + 1))
            printf '%s  %s\n' "$(digest "$file")" "${file#"$ROOT/build"/}"
        done < <(find "$ROOT/build/sbom" -type f 2>/dev/null | LC_ALL=C sort)
        if [ "$found" -eq 0 ]; then
            echo "attestation-subjects.sh: no bill of materials under build/sbom" >&2
            exit 1
        fi
        ;;
    *)
        echo "usage: scripts/attestation-subjects.sh maven|release" >&2
        exit 2
        ;;
esac
