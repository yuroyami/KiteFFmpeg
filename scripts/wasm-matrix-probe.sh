#!/usr/bin/env bash
# Runs the 17.5 format matrix through the WEB decode path (KPKMP.md 17.14, toward X-14).
#
# Not the project's own suite: that is Kotlin and needs the engine, which the web does not have
# yet. This is the honest interim, and it says so. It answers one question the owner actually asks,
# "does the web play my formats", by demuxing and decoding each matrix clip through the same wasm
# codec the browser demo uses, and reporting per row rather than in aggregate.
#
# The web build carries the 17.6 LEAN codec set on purpose, so a STREAM whose codec is outside that
# set is EXPECTED to fail here and is marked accordingly. A stream failing for a reason other than
# "codec not in the lean set" is a real finding.
#
# It reports per STREAM, video and audio both, since 2026-08-18. It used to decode one stream per
# row, video where there was video, and that is how PAR-4 stayed invisible: vp9.webm reported PLAYS
# on the strength of its picture while its opus track had no decoder in the build at all. A probe
# that answers "does the web play my formats" has to look at the whole file.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/native-libs/lgpl/wasm32"
KC="$ROOT/native-libs/deps/wasm32/kiteffmpeg/libkitecodec.a"
MEDIA="${KITE_TESTMEDIA:-$ROOT/../KitePlayer/testmedia}"
[ -d "$MEDIA" ] || { echo "no testmedia at $MEDIA" >&2; exit 1; }
[ -f "$KC" ] || { echo "run :kiteffmpeg-core:compileKiteFFmpegCForWasm first" >&2; exit 1; }

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
python3 - "$ROOT/native-libs/deps/wasm32/binding/kiteffmpeg-exports.json" "$WORK/exports.json" <<'PY'
import json, sys
names = json.load(open(sys.argv[1]))
for extra in ("_ffkmp_fmt_open_input_io", "_malloc", "_free"):
    if extra not in names: names.append(extra)
json.dump(names, open(sys.argv[2], "w"))
PY

cat > "$WORK/matrix.mjs" <<'JS'
import factory from "./kite.mjs";
import { readFileSync, existsSync } from "node:fs";

const M = await factory();
const dir = process.argv[2];
// The 17.5 matrix, in the order FormatMatrix.kt lists it.
const ROWS = ["sync1080p30.mp4","baseline.mkv","multitrack.mkv","vp9.webm","mpeg4part2.mp4",
  "hevc4k10.mp4","rotated90ccw.mp4","truevfr720.mp4","tsoffset1400.ts","subbed.mkv","surround51.mp4",
  "audio-aac.m4a","audio-mp3.mp3","audio-flac.flac","av1.mkv","torture-truncated.mp4",
  "torture-garbage.mp4","avi-mpeg4.avi","wmv-msmpeg4.wmv","flv-flv1.flv","vob-mpeg2.vob",
  "audio-eac3.mkv","audio-dts.mkv","audio-truehd.mkv","audio-alac.m4a","asssubbed.mkv"];

// What the 17.6 LEAN web tier carries, BY CODEC rather than by row.
//
// Per row was the old shape and it is exactly what hid PAR-4. vp9.webm was marked in-tier, this
// probe decoded its video stream only, and the opus track that nothing in the build could decode
// never appeared in the report at all. A row is a CONTAINER; what is in or out of a tier is a
// codec, and a container can hold one of each.
const IN_TIER_VIDEO = new Set(["h264","hevc","vp9"]);
const IN_TIER_AUDIO = new Set(["aac","mp3","flac","opus","vorbis",
  "pcm_s16le","pcm_s16be","pcm_s24le","pcm_s24be","pcm_s32le","pcm_s32be",
  "pcm_u8","pcm_s8","pcm_f32le","pcm_f64le","pcm_alaw","pcm_mulaw"]);
// The tier enables four DEMUXERS: mov, matroska, mp3, flac. A container outside that set cannot be
// opened at all, which is as deliberate as an absent decoder and must not read as a defect. Keyed
// by extension, because before the open succeeds the probe has no format name to key on.
const IN_TIER_CONTAINER = new Set(["mp4","mov","m4a","mkv","webm","mp3","flac"]);
const TORTURE = new Set(["torture-truncated.mp4","torture-garbage.mp4"]);
const extensionOf = (row) => row.slice(row.lastIndexOf(".") + 1).toLowerCase();

function run(path) {
  const bytes = readFileSync(path);
  let pos = 0;
  const readFn = M.addFunction((o,b,l)=>{ if(pos>=bytes.length) return -541478725;
    const n=Math.min(l,bytes.length-pos); M.HEAPU8.set(bytes.subarray(pos,pos+n),b); pos+=n; return n; },"iiii");
  const seekFn = M.addFunction((o,off,wh)=>{ const x=Number(off),w=Number(wh);
    if(w===0x10000) return BigInt(bytes.length);
    if(w===0)pos=x; else if(w===1)pos+=x; else if(w===2)pos=bytes.length+x; else return -1n;
    return BigInt(pos); },"jiji");
  const outPtr = M._malloc(4);
  try {
    const rc = M._ffkmp_fmt_open_input_io(outPtr,0,readFn,seekFn,BigInt(bytes.length),0,0,0,0);
    if (rc < 0) return { open:false, why:`open ${rc}` };
    const ctx = M.HEAPU32[outPtr>>2];
    if (M._ffkmp_fmt_find_stream_info(ctx) < 0) { M._ffkmp_fmt_close_input_io(outPtr); return { open:false, why:"stream info" }; }
    const n = M._ffkmp_fmt_nb_streams(ctx);
    let vi=-1, ai=-1, subs=0, vcodec="", acodec="";
    for (let i=0;i<n;i++){
      const p=M._ffkmp_stream_codecpar(M._ffkmp_fmt_stream(ctx,i));
      const t=M._ffkmp_codecpar_codec_type(p);
      const nm=M.UTF8ToString(M._ffkmp_codec_id_name(M._ffkmp_codecpar_codec_id(p)));
      if (t===M._ffkmp_media_type_video() && vi<0){vi=i;vcodec=nm;}
      else if (t===M._ffkmp_media_type_audio() && ai<0){ai=i;acodec=nm;}
      else if (t===M._ffkmp_media_type_subtitle()) subs++;
    }
    // The FIRST video stream and the FIRST audio stream, both, which is what a player opens.
    const streams = [];
    if (vi >= 0) streams.push({ index: vi, kind: "video", codec: vcodec, frames: 0 });
    if (ai >= 0) streams.push({ index: ai, kind: "audio", codec: acodec, frames: 0 });
    for (const st of streams) {
      const par = M._ffkmp_stream_codecpar(M._ffkmp_fmt_stream(ctx, st.index));
      const codec = M._ffkmp_find_decoder_by_id(M._ffkmp_codecpar_codec_id(par));
      if (!codec) { st.why = "no decoder in this build"; continue; }
      const dec = M._ffkmp_codecctx_alloc(codec);
      M._ffkmp_codecctx_from_par(dec, par);
      if (M._ffkmp_codecctx_open(dec, codec) < 0) { M._ffkmp_codecctx_free(dec); st.why = "decoder open failed"; continue; }
      st.dec = dec;
    }
    // ONE demux pass feeding every decoder, because a second pass would need a seek and the
    // torture rows are exactly the files where a seek is not guaranteed to work.
    const live = streams.filter(st => st.dec);
    if (live.length > 0) {
      const pkt = M._ffkmp_packet_alloc(), frame = M._ffkmp_frame_alloc();
      let guard = 0;
      while (guard++ < 12000 && live.some(st => st.frames < 2)) {
        if (M._ffkmp_fmt_read_frame(ctx, pkt) < 0) break;
        const si = M._ffkmp_packet_stream_index(pkt);
        const st = live.find(x => x.index === si);
        if (st && st.frames < 2 && M._ffkmp_codecctx_send_packet(st.dec, pkt) >= 0) {
          while (M._ffkmp_codecctx_receive_frame(st.dec, frame) >= 0) {
            st.frames++; M._ffkmp_frame_unref(frame); if (st.frames >= 2) break;
          }
        }
        M._ffkmp_packet_unref(pkt);
      }
      M._ffkmp_frame_free(frame); M._ffkmp_packet_free(pkt);
      live.forEach(st => { M._ffkmp_codecctx_free(st.dec); delete st.dec; });
    }
    M._ffkmp_fmt_close_input_io(outPtr);
    streams.forEach(st => { if (st.why === undefined) st.why = st.frames > 0 ? `${st.frames} frames` : "decoded nothing"; });
    return { open:true, streams, n, subs };
  } finally { M._free(outPtr); }
}

let plays = 0, omitted = 0, surprises = [];
console.log("row                        result           streams");
for (const row of ROWS) {
  const path = `${dir}/${row}`;
  if (!existsSync(path)) { console.log(`${row.padEnd(26)} SKIP (no fixture)`); continue; }
  let r;
  try { r = run(path); } catch (e) { r = { open:false, why:"threw " + e.message }; }

  if (TORTURE.has(row)) {                 // must not crash; decoding is not required
    console.log(`${row.padEnd(26)} ${"SURVIVED".padEnd(16)} ${r.open ? "opened" : r.why}`);
    continue;
  }
  if (!r.open) {
    if (!IN_TIER_CONTAINER.has(extensionOf(row))) {   // no demuxer for it, and that is the tier
      omitted++;
      console.log(`${row.padEnd(26)} ${"not in web tier".padEnd(16)} container, ${r.why}`);
    } else {
      surprises.push(`${row}: ${r.why}`);
      console.log(`${row.padEnd(26)} ${"FAIL".padEnd(16)} ${r.why}`);
    }
    continue;
  }
  const parts = [], failed = [];
  for (const st of r.streams) {
    const inTier = st.kind === "video" ? IN_TIER_VIDEO.has(st.codec) : IN_TIER_AUDIO.has(st.codec);
    const ok = st.frames > 0;
    if (ok) { parts.push(`${st.kind}:${st.codec} plays`); }
    else if (!inTier) { parts.push(`${st.kind}:${st.codec} not in web tier`); omitted++; }
    else { parts.push(`${st.kind}:${st.codec} FAIL (${st.why})`); failed.push(`${row}: ${st.kind} ${st.codec}, ${st.why}`); }
  }
  if (r.streams.length === 0) { failed.push(`${row}: no audio or video stream`); parts.push("no a/v stream"); }
  const verdict = failed.length ? "FAIL" : (parts.some(p => p.includes("plays")) ? "PLAYS" : "not in web tier");
  if (!failed.length && verdict === "PLAYS") plays++;
  surprises.push(...failed);
  console.log(`${row.padEnd(26)} ${verdict.padEnd(16)} ${parts.join(", ")}`);
}
console.log("");
console.log(`plays ${plays}, omitted by the lean web tier ${omitted}, unexpected failures ${surprises.length}`);
surprises.forEach(s => console.log("  UNEXPECTED " + s));
process.exit(surprises.length === 0 ? 0 : 1);
JS

emcc -O2 -I"$ROOT/native/kitecodec-c/include" -I"$ROOT/native/kitecodec-handles" -I"$FF/include" \
  "$KC" "$FF"/lib/libav{filter,format,codec,util}.a "$FF"/lib/libsw{scale,resample}.a \
  -sEXPORTED_FUNCTIONS=@"$WORK/exports.json" \
  -sEXPORTED_RUNTIME_METHODS='["ccall","cwrap","UTF8ToString","addFunction","HEAPU8","HEAPU32"]' \
  -sALLOW_TABLE_GROWTH=1 -sMODULARIZE=1 -sEXPORT_ES6=1 -sALLOW_MEMORY_GROWTH=1 \
  -o "$WORK/kite.mjs" > "$WORK/link.log" 2>&1 || { tail -20 "$WORK/link.log"; exit 1; }
node "$WORK/matrix.mjs" "$MEDIA"
