#!/usr/bin/env bash
#
# Source this file before a local `./kotlin publish mavenLocal`. `library.module-template.yaml` sets
# `signArtifacts: true`, so the Kotlin Toolchain signs a Maven Local publish too, with the key in
# KOTLIN_TOOLCHAIN_SIGNING_KEY. When that variable is empty, this file makes a throwaway key in a
# temporary GnuPG home and exports it. Use the artifacts that it signs for local checks only.
#
#   . scripts/test-signing-key.sh

if [ -z "${KOTLIN_TOOLCHAIN_SIGNING_KEY:-}" ]; then
    command -v gpg > /dev/null || { echo "test-signing-key.sh: no gpg; set KOTLIN_TOOLCHAIN_SIGNING_KEY" >&2; return 1; }
    _kf_gnupg=$(mktemp -d)
    chmod 700 "$_kf_gnupg"
    GNUPGHOME="$_kf_gnupg" gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
        --quick-gen-key "KiteFFmpeg local check <local-check@invalid>" rsa3072 sign never 2> /dev/null
    KOTLIN_TOOLCHAIN_SIGNING_KEY=$(GNUPGHOME="$_kf_gnupg" gpg --batch --pinentry-mode loopback --passphrase '' \
        --armor --export-secret-keys 2> /dev/null)
    export KOTLIN_TOOLCHAIN_SIGNING_KEY
    unset KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE
    GNUPGHOME="$_kf_gnupg" gpgconf --kill gpg-agent 2> /dev/null || true
    rm -rf "$_kf_gnupg"
    unset _kf_gnupg
    [ -n "$KOTLIN_TOOLCHAIN_SIGNING_KEY" ] || { echo "test-signing-key.sh: gpg made no key" >&2; return 1; }
    echo "test-signing-key.sh: signing with a throwaway key, for local checks only" >&2
fi
