#!/usr/bin/env bash
# Builds and serves the browser playback proof.
#
# Decodes a real clip with FFmpeg in wasm and draws it to a 2d canvas with putImageData. This is
# the path S6-D6 correction 2 named: the converted RGBA already lives in emscripten linear memory,
# which IS a JS-visible ArrayBuffer, so the frame never crosses the Kotlin heap the web probe measured
# at 107 to 153 ms per frame.
#
#   ./scripts/wasm-browser-demo.sh [port]     then open the printed URL
set -euo pipefail
PORT="${1:-8713}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/native-libs/lgpl/wasm32"
KC="$ROOT/native-libs/deps/wasm32/kiteffmpeg/libkitecodec.a"
CLIP="${KITE_DEMO_CLIP:-$ROOT/../KitePlayer/testmedia/sync1080p30.mp4}"
OUT="$ROOT/build/wasm-browser-demo"
[ -f "$KC" ] || { echo "run :kiteffmpeg:compileKiteFFmpegCForWasm first" >&2; exit 1; }
[ -f "$CLIP" ] || { echo "no clip at $CLIP" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"
python3 - "$ROOT/native-libs/deps/wasm32/binding/kiteffmpeg-exports.json" "$OUT/exports.json" <<'PY'
import json, sys
names = json.load(open(sys.argv[1]))
# open_input_io is the hand-written callback entry the generator excludes; the demo needs it.
for extra in ("_ffkmp_fmt_open_input_io", "_malloc", "_free"):
    if extra not in names: names.append(extra)
json.dump(names, open(sys.argv[2], "w"))
PY
emcc -O3 -I"$ROOT/native/kitecodec-c/include" -I"$ROOT/native/kitecodec-handles" -I"$FF/include" \
  "$KC" "$FF"/lib/libav{filter,format,codec,util}.a "$FF"/lib/libsw{scale,resample}.a \
  -sEXPORTED_FUNCTIONS=@"$OUT/exports.json" \
  -sEXPORTED_RUNTIME_METHODS='["ccall","cwrap","UTF8ToString","stringToUTF8","lengthBytesUTF8","addFunction","removeFunction","HEAP32","HEAPU8","HEAPU32"]' \
  -sALLOW_TABLE_GROWTH=1 -sMODULARIZE=1 -sEXPORT_ES6=1 -sALLOW_MEMORY_GROWTH=1 \
  -o "$OUT/kite.mjs"
cp "$ROOT/native/kitecodec-c/probe/browser/index.html" "$OUT/index.html"
cp "$ROOT/native/kitecodec-c/probe/browser/hardware.html" "$OUT/hardware.html"
cp "$CLIP" "$OUT/clip.mp4"
echo "serving http://localhost:$PORT/index.html   software decode + audio"
echo "        http://localhost:$PORT/hardware.html  WebCodecs hardware decode"
cd "$OUT" && exec python3 -m http.server "$PORT"
