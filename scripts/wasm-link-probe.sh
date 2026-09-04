#!/usr/bin/env bash
# Links the wasm codec archive against the wasm FFmpeg and RUNS it.
#
# Compiling proves nothing here. `native/kitecodec-c` is portable C, so it was always going to
# compile; what was in doubt is whether the web FFmpeg profile actually carries what it calls into.
# This links the real archives and executes the result under node.
#
#   ./scripts/wasm-link-probe.sh              # must PASS
#   ./scripts/wasm-link-probe.sh --falsify    # links without libavfilter, must FAIL
set -euo pipefail

MODE="${1:-real}"
case "$MODE" in real|--falsify) ;; *) echo "usage: $0 [--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/native-libs/lgpl/wasm32"
KC="$ROOT/native-libs/deps/wasm32/kiteffmpeg/libkitecodec.a"
for required in "$KC" "$FF/lib/libavfilter.a" "$FF/include/libavformat/avformat.h"; do
  [ -e "$required" ] || { echo "missing $required. Run :kiteffmpeg:compileKiteFFmpegCForWasm first." >&2; exit 1; }
done
command -v emcc >/dev/null || { echo "emcc is not on PATH" >&2; exit 1; }
command -v node >/dev/null || { echo "node is not on PATH" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# .js and not .mjs: an ES module build exports a factory and never runs main, so the probe would
# exit 0 having asserted nothing. That silence is exactly what this script exists to avoid.
LIBS=("$FF/lib/libavformat.a" "$FF/lib/libavcodec.a" "$FF/lib/libswscale.a" "$FF/lib/libswresample.a" "$FF/lib/libavutil.a")
if [ "$MODE" = "--falsify" ]; then
  echo "== FALSIFICATION arm: linking without libavfilter"
else
  LIBS=("$FF/lib/libavfilter.a" "${LIBS[@]}")
fi

set +e
emcc -O2 -I"$ROOT/native/kitecodec-c/include" -I"$ROOT/native/kitecodec-handles" -I"$FF/include" \
  "$ROOT/native/kitecodec-c/probe/wasm_link_probe.c" "$KC" "${LIBS[@]}" \
  -o "$WORK/probe.js" > "$WORK/link.log" 2>&1
link=$?
set -e

if [ "$MODE" = "--falsify" ]; then
  if [ "$link" -ne 0 ]; then
    echo "falsification arm failed at LINK as required (undefined avfilter symbols)"
    grep -c "undefined symbol" "$WORK/link.log" | sed 's/^/  undefined symbols: /' || true
    exit 0
  fi
  # It linked, so it must at least fail at run time; a pass here would mean the probe proves nothing.
  if node "$WORK/probe.js"; then
    echo "FALSIFICATION FAILED: the probe passed with no avfilter linked" >&2
    exit 1
  fi
  echo "falsification arm failed at RUN as required"
  exit 0
fi

[ "$link" -eq 0 ] || { echo "link failed:"; tail -20 "$WORK/link.log"; exit 1; }
echo "== linked $(wc -c < "$WORK/probe.wasm") bytes of wasm"
node "$WORK/probe.js"
