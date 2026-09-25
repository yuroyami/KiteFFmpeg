#!/bin/sh
# Symbol audit of one built libkitecodec_jni. Usage:
#   symbol-audit.sh <shared-library> [nm-binary]
# Asserts the dynamic defined-symbol set is exactly JNI_OnLoad (after platform decoration) and
# never Java_*, kc_*, ffkmp_* or av_*. The ELF PT_LOAD 16 KiB check lives in the Android link task
# instead, because it needs llvm-readelf, which is NDK-supplied and target-specific.
set -u
LIB="${1:?usage: symbol-audit.sh <shared-library> [nm]}"
NM="${2:-nm}"
[ -f "$LIB" ] || { echo "symbol-audit.sh (kitecodec-jni): no library at $LIB" >&2; exit 1; }

# ELF (llvm-nm/GNU nm) first; Mach-O (system nm) fallback. Both mean "defined, externally
# visible" and both matter: the Android arms are ELF, the test-only macOS dylib is Mach-O.
DEFINED=$("$NM" -D --defined-only "$LIB" 2>/dev/null | awk 'NF>=3 {print $3} NF==2 {print $2}' | sort -u)
[ -n "$DEFINED" ] || DEFINED=$("$NM" -gU "$LIB" 2>/dev/null | awk 'NF>=3 {print $3}' | sort -u)
[ -n "$DEFINED" ] || { echo "FAIL: nm returned no defined dynamic symbols" >&2; exit 1; }

STRIPPED=$(printf '%s\n' "$DEFINED" | sed 's/^_//' )
if [ "$STRIPPED" != "JNI_OnLoad" ]; then
    echo "symbol-audit.sh (kitecodec-jni): FAIL, dynamic defined set is not exactly JNI_OnLoad:"
    printf '%s\n' "$DEFINED"
    exit 1
fi
if printf '%s\n' "$DEFINED" | grep -qE 'Java_|(^|_)(kc_|ffkmp_|av_)'; then
    echo "symbol-audit.sh (kitecodec-jni): FAIL, forbidden name visible"
    exit 1
fi
echo "symbol-audit.sh (kitecodec-jni): PASS, exactly JNI_OnLoad is exported by $LIB"
