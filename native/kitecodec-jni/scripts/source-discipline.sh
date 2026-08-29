#!/bin/sh
# Source discipline of the JNI adapter (S1.c.1). The adapter may include only <jni.h>, the C
# runtime and KiteFFmpeg's three opaque headers, and may call only kc_*/ffkmp_* helpers, JNI and
# the C runtime. Four bans, with falsifiability controls in the S1.c.1 gate and this audit's local
# plants:
#   1. every direct include is on the exact JNI/runtime/opaque-boundary allowlist;
#   2. no direct libav/libsw call spelled in any unit;
#   3. no raw FFmpeg struct, typedef, constant or other identifier is reproduced;
#   4. no Java_* symbol defined (dynamic registration only).
# Prints the violations and exits 1 on any; prints a PASS line and exits 0 otherwise.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

echo "source-discipline.sh (kitecodec-jni): four bans over $DIR"

# Keep this deliberately exact. A broad "not libav" test would let a second native boundary enter
# unnoticed, while a broad system-header wildcard would let platform policy creep into category
# units. Add a header here only when the adapter has a reviewed, in-fence need for it.
HITS=$(awk '
    /^[[:space:]]*#[[:space:]]*include[[:space:]]*/ {
        if ($0 !~ /^[[:space:]]*#[[:space:]]*include[[:space:]]*(<jni\.h>|<stdint\.h>|<pthread\.h>|<stdio\.h>|<stdlib\.h>|<string\.h>|"kj_internal\.h"|"kitecodec_abi\.h"|"kitecodec_handles\.h"|"kitecodec_helpers\.h"|"methods\.def")[[:space:]]*$/) {
            print FILENAME ":" FNR ":" $0
        }
    }
' "$DIR"/*.c "$DIR"/*.h "$DIR"/methods.def 2>/dev/null)
if [ -n "$HITS" ]; then echo "FAIL: include outside the JNI/runtime/opaque-boundary allowlist:"; echo "$HITS"; FAIL=1
else echo "  ok: every direct include is allowlisted"; fi

# FFmpeg's public names do not share one `av_` prefix: ordinary entry points include
# avcodec_version(), avformat_open_input(), avio_open(), swscale_version() and others. Keep the
# left token boundary strict so allowed opaque helpers such as ffkmp_averror_eagain() do not match.
# Do not filter whole lines containing ffkmp_/kc_: that would let a forbidden call hide beside an
# allowed helper on the same source line.
HITS=$(awk '
    /(^|[^A-Za-z0-9_])(av_|avcodec_|avformat_|avfilter_|avutil_|avio_|sws_|swr_|swscale_|swresample_)[A-Za-z0-9_]*[[:space:]]*\(/ {
        print FILENAME ":" FNR ":" $0
    }
' "$DIR"/*.c "$DIR"/*.h 2>/dev/null)
MANIFEST_HITS=$(awk '
    /^[[:space:]]*\/\// { next }
    /(^|[^A-Za-z0-9_])(av_|avcodec_|avformat_|avfilter_|avutil_|avio_|sws_|swr_|swscale_|swresample_)[A-Za-z0-9_]*[[:space:]]*\(/ {
        print FILENAME ":" FNR ":" $0
    }
' "$DIR"/methods.def 2>/dev/null)
if [ -n "$MANIFEST_HITS" ]; then HITS="${HITS:+$HITS
}$MANIFEST_HITS"; fi
if [ -n "$HITS" ]; then echo "FAIL: direct libav/libsw call:"; echo "$HITS"; FAIL=1
else echo "  ok: no direct libav or libsw call"; fi

# The opaque headers deliberately rename the eleven FFmpeg objects to kc_* handles. Catching the
# complete raw AV*/Sws*/Swr* identifier families also rejects a locally reproduced struct or typedef
# and constants that would otherwise rebuild a second FFmpeg surface without an include or call.
HITS=$(awk '
    /(^|[^A-Za-z0-9_])(AV[A-Za-z0-9_]+|Sws[A-Za-z0-9_]+|Swr[A-Za-z0-9_]+)([^A-Za-z0-9_]|$)/ {
        print FILENAME ":" FNR ":" $0
    }
' "$DIR"/*.c "$DIR"/*.h 2>/dev/null)
MANIFEST_HITS=$(awk '
    /^[[:space:]]*\/\// { next }
    /(^|[^A-Za-z0-9_])(AV[A-Za-z0-9_]+|Sws[A-Za-z0-9_]+|Swr[A-Za-z0-9_]+)([^A-Za-z0-9_]|$)/ {
        print FILENAME ":" FNR ":" $0
    }
' "$DIR"/methods.def 2>/dev/null)
if [ -n "$MANIFEST_HITS" ]; then HITS="${HITS:+$HITS
}$MANIFEST_HITS"; fi
if [ -n "$HITS" ]; then echo "FAIL: raw FFmpeg identifier/type:"; echo "$HITS"; FAIL=1
else echo "  ok: no raw FFmpeg identifier or reproduced type"; fi

HITS=$(grep -nE 'JNIEXPORT[^(]*Java_' "$DIR"/*.c 2>/dev/null)
if [ -n "$HITS" ]; then echo "FAIL: Java_* export (dynamic registration only):"; echo "$HITS"; FAIL=1
else echo "  ok: no Java_* symbol"; fi

if [ "$FAIL" -ne 0 ]; then
    echo "source-discipline.sh (kitecodec-jni): FAILED"
    exit 1
fi
echo "source-discipline.sh (kitecodec-jni): PASS, 4 of 4 bans hold"
