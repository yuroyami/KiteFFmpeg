#!/usr/bin/env bash
# Demuxes a REAL media file whose bytes are supplied by JavaScript (PLANNING.md).
#
# This is the one piece the generator deliberately does not emit. `ffkmp_fmt_open_input_io` takes
# two function pointers, and a callback crossing into JS is a lifetime problem rather than a
# signature problem, so it is hand-written and proved here against real media rather than a stub.
#
#   ./scripts/wasm-io-probe.sh [path/to/media.mp4]
#   ./scripts/wasm-io-probe.sh --falsify    # a source that always fails must be REFUSED, not faked
set -euo pipefail

MEDIA_DEFAULT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../KitePlayer" && pwd)/testmedia/sync1080p30.mp4"
MODE="real"; MEDIA="$MEDIA_DEFAULT"
case "${1:-}" in
  --falsify) MODE="--falsify" ;;
  "") ;;
  *) MEDIA="$1" ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/native-libs/lgpl/wasm32"
KC="$ROOT/native-libs/deps/wasm32/kiteffmpeg/libkitecodec.a"
EXPORTS="$ROOT/native-libs/deps/wasm32/binding/kiteffmpeg-exports.json"
[ -f "$MEDIA" ] || { echo "no media at $MEDIA. Run KitePlayer's ./scripts/testmedia.sh" >&2; exit 1; }
[ -f "$KC" ] || { echo "missing $KC" >&2; exit 1; }

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
python3 - "$EXPORTS" "$WORK/exports.json" <<'PY'
import json, sys
names = json.load(open(sys.argv[1]))
# The hand-written entry point the generator excludes on purpose; the probe needs it exported.
for extra in ("_ffkmp_fmt_open_input_io", "_malloc", "_free"):
    if extra not in names: names.append(extra)
json.dump(names, open(sys.argv[2], "w"))
PY

cat > "$WORK/harness.mjs" <<'JS'
import factory from "./kite.mjs";
import { readFileSync } from "node:fs";

const M = await factory();
const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };
const bytes = readFileSync(process.argv[2]);
const FALSIFY = process.argv[3] === "--falsify";

// The JS-resident source. FFmpeg calls these synchronously; in a browser they live in a Worker,
// which is the whole reason X-08 exists. Here node is already the right thread.
let position = 0;
const readFn = M.addFunction((opaque, buf, len) => {
  if (FALSIFY) return -1;                     // a source that can never satisfy a read
  if (position >= bytes.length) return -541478725; // AVERROR_EOF
  const n = Math.min(len, bytes.length - position);
  M.HEAPU8.set(bytes.subarray(position, position + n), buf);
  position += n;
  return n;
}, "iiii");
const seekFn = M.addFunction((opaque, offset, whence) => {
  const off = Number(offset);
  const w = Number(whence);
  if (w === 0x10000) return BigInt(bytes.length);  // AVSEEK_SIZE
  if (w === 0) position = off;
  else if (w === 1) position += off;
  else if (w === 2) position = bytes.length + off;
  else return -1n;
  return BigInt(position);
}, "jiji");

const outPtr = M._malloc(4);
// Called directly rather than through ccall: the `size` parameter is int64_t, and with
// WASM_BIGINT that argument must arrive as a BigInt. ccall's type vocabulary has no spelling for
// it and silently hands the number over, which fails as "Cannot convert N to a BigInt".
const rc = M._ffkmp_fmt_open_input_io(
  outPtr, 0, readFn, seekFn, BigInt(bytes.length), 0, 0, 0, 0,
);
const ctx = M.HEAPU32[outPtr >> 2];

if (FALSIFY) {
  if (rc >= 0 && ctx !== 0) fail(`a source that never reads was accepted (rc=${rc})`);
  console.log(`OK(falsify): a failing source was refused, rc=${rc}`);
  process.exit(0);
}
if (rc < 0 || ctx === 0) fail(`open_input_io returned ${rc}`);

if (M._ffkmp_fmt_find_stream_info(ctx) < 0) fail("find_stream_info failed");
const n = M._ffkmp_fmt_nb_streams(ctx);
if (n < 1) fail(`no streams, got ${n}`);
const durationUs = Number(M._ffkmp_fmt_duration(ctx));
const container = M.UTF8ToString(M._ffkmp_fmt_iformat_name(ctx));

let video = -1, audio = -1, vcodec = "", acodec = "", w = 0, h = 0;
for (let i = 0; i < n; i++) {
  const st = M._ffkmp_fmt_stream(ctx, i);
  const par = M._ffkmp_stream_codecpar(st);
  const type = M._ffkmp_codecpar_codec_type(par);
  const name = M.UTF8ToString(M._ffkmp_codec_id_name(M._ffkmp_codecpar_codec_id(par)));
  if (type === M._ffkmp_media_type_video() && video < 0) {
    video = i; vcodec = name;
    w = M._ffkmp_codecpar_width(par); h = M._ffkmp_codecpar_height(par);
  } else if (type === M._ffkmp_media_type_audio() && audio < 0) {
    audio = i; acodec = name;
  }
}
if (video < 0) fail("no video stream found");
if (w <= 0 || h <= 0) fail(`video stream reports ${w}x${h}`);
if (position === 0) fail("the JS read callback was never called, so nothing was proved");

console.log(`OK: demuxed ${container} via a JS byte source`);
console.log(`    streams=${n} video=${vcodec} ${w}x${h} audio=${acodec || "none"} duration=${(durationUs/1e6).toFixed(2)}s`);
console.log(`    the JS source served ${position} of ${bytes.length} bytes`);

// ---- Decode, which is what makes this a player and not a file inspector. ----
const stream = M._ffkmp_fmt_stream(ctx, video);
const par = M._ffkmp_stream_codecpar(stream);
const codec = M._ffkmp_find_decoder_by_id(M._ffkmp_codecpar_codec_id(par));
if (!codec) fail(`no decoder for ${vcodec}`);
const dec = M._ffkmp_codecctx_alloc(codec);
if (!dec) fail("codecctx_alloc failed");
if (M._ffkmp_codecctx_from_par(dec, par) < 0) fail("codecctx_from_par failed");
if (M._ffkmp_codecctx_open(dec, codec) < 0) fail("codecctx_open failed");

const pkt = M._ffkmp_packet_alloc();
const frame = M._ffkmp_frame_alloc();
const EAGAIN = M._ffkmp_averror_eagain();
let decoded = 0, packets = 0, firstPts = null;
while (decoded < 3 && packets < 4000) {
  const rf = M._ffkmp_fmt_read_frame(ctx, pkt);
  if (rf < 0) break;
  packets++;
  if (M._ffkmp_packet_stream_index(pkt) === video) {
    if (M._ffkmp_codecctx_send_packet(dec, pkt) >= 0) {
      while (M._ffkmp_codecctx_receive_frame(dec, frame) >= 0) {
        if (firstPts === null) firstPts = M._ffkmp_frame_pts(frame);
        decoded++;
        if (decoded >= 3) break;
      }
    }
  }
  M._ffkmp_packet_unref(pkt);
}
if (decoded === 0) fail(`decoded no frames after ${packets} packets`);

const fw = M._ffkmp_frame_width(frame), fh = M._ffkmp_frame_height(frame);
if (fw !== w || fh !== h) fail(`decoded frame is ${fw}x${fh}, stream said ${w}x${h}`);

// To RGBA, which is the form a canvas can take directly.
const rgbaFmt = M.ccall("ffkmp_pix_fmt_from_name", "number", ["string"], ["rgba"]);
if (rgbaFmt < 0) fail("no rgba pixel format");
const conv = M._ffkmp_frame_convert_pixfmt(frame, rgbaFmt);
if (!conv) fail("convert_pixfmt returned null");
// The dedicated size query, not copy_to_buffer with a null destination: that one answered -28,
// which is an error code and not a length, and treating it as one would have allocated nothing.
const size = M._ffkmp_image_get_buffer_size(rgbaFmt, fw, fh, 1);
const expect = fw * fh * 4;
if (size !== expect) fail(`rgba buffer is ${size}, expected ${expect}`);
const buf = M._malloc(size);
if (M._ffkmp_frame_copy_to_buffer(conv, buf, size) !== size) fail("copy_to_buffer short write");
const pixels = M.HEAPU8.subarray(buf, buf + size);

// A frame of pure zeroes would satisfy every assertion above, so look at the pixels.
let nonZero = 0, alphaOk = true;
for (let i = 0; i < size; i += 4) {
  if (pixels[i] || pixels[i+1] || pixels[i+2]) nonZero++;
  if (pixels[i+3] !== 255) alphaOk = false;
}
if (nonZero < size / 400) fail(`decoded frame is essentially blank (${nonZero} lit pixels)`);
if (!alphaOk) fail("rgba alpha channel is not opaque");

console.log(`OK: decoded ${decoded} frames, first pts ${firstPts}`);
console.log(`    ${fw}x${fh} rgba, ${size} bytes, ${nonZero} non-black pixels, alpha opaque`);

M._free(buf);
M._ffkmp_frame_free(conv);
M._ffkmp_frame_free(frame);
M._ffkmp_packet_free(pkt);
M._ffkmp_codecctx_free(dec);
M._ffkmp_fmt_close_input_io(outPtr);
M._free(outPtr);
JS

set +e
emcc -O2 -I"$ROOT/native/kitecodec-c/include" -I"$ROOT/native/kitecodec-handles" -I"$FF/include" \
  "$KC" "$FF"/lib/libav{filter,format,codec,util}.a "$FF"/lib/libsw{scale,resample}.a \
  -sEXPORTED_FUNCTIONS=@"$WORK/exports.json" \
  -sEXPORTED_RUNTIME_METHODS='["ccall","cwrap","UTF8ToString","addFunction","HEAPU8","HEAPU32"]' \
  -sALLOW_TABLE_GROWTH=1 -sMODULARIZE=1 -sEXPORT_ES6=1 -sALLOW_MEMORY_GROWTH=1 \
  -o "$WORK/kite.mjs" > "$WORK/link.log" 2>&1
link=$?
set -e
[ "$link" -eq 0 ] || { echo "link failed:"; tail -25 "$WORK/link.log"; exit 1; }

if [ "$MODE" = "--falsify" ]; then
  node "$WORK/harness.mjs" "$MEDIA" --falsify
else
  node "$WORK/harness.mjs" "$MEDIA"
fi
