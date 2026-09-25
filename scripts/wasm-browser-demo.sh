#!/usr/bin/env bash
# Builds and serves the browser playback proof.
#
# index.html decodes a clip with FFmpeg in wasm and draws it to a 2d canvas with putImageData. The
# converted RGBA already lives in the module's linear memory, which JavaScript reads directly, so a
# frame never crosses the Kotlin heap. hardware.html sends the same demuxed packets to the
# browser's WebCodecs decoder.
#
#   ./scripts/wasm-browser-demo.sh [port]     then open the printed URL
#
# KITE_DEMO_CLIP names an H.264 MP4 clip to serve. Without it, the ffmpeg command line makes a
# ten second 1080p H.264 and AAC test clip.
set -euo pipefail
PORT="${1:-8713}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/kiteffmpeg/build/kite-web"
OUT="$ROOT/build/wasm-browser-demo"

# The same module the web backend loads, linked by the same task that ships it.
"$ROOT/gradlew" -p "$ROOT" :kiteffmpeg:linkKiteFFmpegWasmModule

rm -rf "$OUT"; mkdir -p "$OUT"
cp "$MODULE/kite.mjs" "$MODULE/kite.wasm" "$OUT/"
if [ -n "${KITE_DEMO_CLIP:-}" ]; then
  cp "$KITE_DEMO_CLIP" "$OUT/clip.mp4"
else
  command -v ffmpeg >/dev/null || { echo "set KITE_DEMO_CLIP, or install the ffmpeg command line to make a clip" >&2; exit 1; }
  ffmpeg -loglevel error -f lavfi -i testsrc2=size=1920x1080:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 \
    -t 10 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart "$OUT/clip.mp4"
fi
cp "$ROOT/native/kitecodec-c/probe/browser/index.html" "$ROOT/native/kitecodec-c/probe/browser/hardware.html" "$OUT/"
echo "serving http://localhost:$PORT/index.html   software decode + audio"
echo "        http://localhost:$PORT/hardware.html  WebCodecs hardware decode"
cd "$OUT" && exec python3 -m http.server "$PORT"
