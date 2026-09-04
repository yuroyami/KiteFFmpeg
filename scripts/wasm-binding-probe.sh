#!/usr/bin/env bash
# Calls the generated web binding from JavaScript, for real.
#
# The generator emitting 196 names proves nothing on its own: a name can be exported and still be
# uncallable, and a signature shape can cross the boundary wrong while returning something that
# looks plausible. This links the real archives with the generated export list and exercises one
# call of every shape the ABI actually uses.
#
#   ./scripts/wasm-binding-probe.sh              # must PASS
#   ./scripts/wasm-binding-probe.sh --falsify    # drops an export, must FAIL
set -euo pipefail

MODE="${1:-real}"
case "$MODE" in real|--falsify) ;; *) echo "usage: $0 [--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/native-libs/lgpl/wasm32"
KC="$ROOT/native-libs/deps/wasm32/kiteffmpeg/libkitecodec.a"
EXPORTS="$ROOT/native-libs/deps/wasm32/binding/kiteffmpeg-exports.json"
for required in "$KC" "$EXPORTS" "$FF/lib/libavfilter.a"; do
  [ -e "$required" ] || { echo "missing $required. Run :kiteffmpeg:generateWasmBinding and :compileKiteFFmpegCForWasm." >&2; exit 1; }
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$EXPORTS" "$WORK/exports.json"
if [ "$MODE" = "--falsify" ]; then
  # Remove one export the harness calls. The link still succeeds; the CALL must not.
  python3 - "$WORK/exports.json" <<'PY'
import json, sys
p = sys.argv[1]
names = json.load(open(p))
names = [n for n in names if n != "_ffkmp_filter_exists"]
json.dump(names, open(p, "w"))
PY
  echo "== FALSIFICATION arm: _ffkmp_filter_exists removed from the export list"
fi

cat > "$WORK/harness.mjs" <<'JS'
import factory from "./kite.mjs";
const M = await factory();
const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };

// int(void)
const eof = M._ffkmp_averror_eof();
if (typeof eof !== "number" || eof >= 0) fail(`averror_eof returned ${eof}`);

// const char*(void), a pointer that must decode as UTF-8
const cfg = M.UTF8ToString(M._kc_ffmpeg_configuration());
if (!cfg.includes("wasm32")) fail(`configuration did not mention wasm32: ${cfg}`);

// int(const char*), an argument that has to be marshalled INTO wasm memory
const exists = M.ccall("ffkmp_filter_exists", "number", ["string"], ["buffersink"]);
if (exists !== 1) fail(`filter_exists('buffersink') returned ${exists}`);
const missing = M.ccall("ffkmp_filter_exists", "number", ["string"], ["nosuchfilter"]);
if (missing !== 0) fail(`filter_exists('nosuchfilter') returned ${missing}`);

// pointer(void) then int64_t(pointer) then void(pointer): the object lifecycle shape
const frame = M._ffkmp_frame_alloc();
if (!frame) fail("frame_alloc returned null");
const pts = M._ffkmp_frame_pts(frame);
if (typeof pts !== "bigint" && typeof pts !== "number") fail(`frame_pts type ${typeof pts}`);
M._ffkmp_frame_set_width(frame, 1920);
if (M._ffkmp_frame_width(frame) !== 1920) fail("width did not round-trip through the frame");
M._ffkmp_frame_free(frame);

// int(void) constants that the engine compares against, so a wrong value is silent corruption
if (M._ffkmp_media_type_video() === M._ffkmp_media_type_audio()) fail("media type constants collide");

console.log(`OK: binding callable from JS (config ${cfg.slice(0, 24)}..., eof ${eof}, pts ${pts})`);
JS

set +e
emcc -O2 -I"$ROOT/native/kitecodec-c/include" -I"$ROOT/native/kitecodec-handles" -I"$FF/include" \
  "$KC" "$FF"/lib/libav{filter,format,codec,util}.a "$FF"/lib/libsw{scale,resample}.a \
  -sEXPORTED_FUNCTIONS=@"$WORK/exports.json" \
  -sEXPORTED_RUNTIME_METHODS='["ccall","cwrap","UTF8ToString"]' \
  -sMODULARIZE=1 -sEXPORT_ES6=1 -sALLOW_MEMORY_GROWTH=1 \
  -o "$WORK/kite.mjs" > "$WORK/link.log" 2>&1
link=$?
set -e
[ "$link" -eq 0 ] || { echo "link failed:"; tail -20 "$WORK/link.log"; exit 1; }
echo "== linked $(wc -c < "$WORK/kite.wasm" | tr -d ' ') bytes of wasm with $(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))))" "$WORK/exports.json") exports"

set +e
node "$WORK/harness.mjs"
run=$?
set -e

if [ "$MODE" = "--falsify" ]; then
  [ "$run" -ne 0 ] || { echo "FALSIFICATION FAILED: the harness passed without the export" >&2; exit 1; }
  echo "falsification arm failed as required (exit $run)"
  exit 0
fi
exit $run
