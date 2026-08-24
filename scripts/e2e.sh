#!/usr/bin/env bash
# End-to-end pipeline check: generate a known clip with the system ffmpeg CLI,
# push it through the KiteCodec sample binary, assert the output with ffprobe.
#
# Usage: scripts/e2e.sh <path-to-sample-binary> [ffmpeg-cmd] [ffprobe-cmd]
set -euo pipefail

KEXE=${1:?usage: e2e.sh <path-to-sample-binary> [ffmpeg] [ffprobe]}
FFMPEG=${2:-ffmpeg}
FFPROBE=${3:-ffprobe}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Stream assertions: prefer `ffprobe -of json` + jq (exact field match, no substring surprises);
# fall back to the csv-substring grep when jq isn't installed.
has_stream() { # <file> <codec_name> <codec_type>
  if command -v jq >/dev/null 2>&1; then
    "$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of json "$1" \
      | jq -e --arg c "$2" --arg t "$3" 'any(.streams[]; .codec_name == $c and .codec_type == $t)' >/dev/null
  else
    "$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$1" | grep -q "$2,$3"
  fi
}
has_stream_type() { # <file> <codec_type>
  if command -v jq >/dev/null 2>&1; then
    "$FFPROBE" -v error -show_entries stream=codec_type -of json "$1" \
      | jq -e --arg t "$2" 'any(.streams[]; .codec_type == $t)' >/dev/null
  else
    "$FFPROBE" -v error -show_entries stream=codec_type -of csv=p=0 "$1" | grep -q "$2"
  fi
}

# Which video encoder the SAMPLE will pick against the FFmpeg it was linked with, and the
# codec_name ffprobe will then report. Never assume h264: libx264 is GPL-only, and KiteCodec's
# default vendored profile is LGPL, where the dependency-free baseline is mpeg4. Asserting a
# hard-coded h264 here is what made this suite silently require a GPL FFmpeg.
KC_INFO=$("$KEXE" info)
printf '%s\n' "$KC_INFO"
KC_VCODEC=$(printf '%s\n' "$KC_INFO" | sed -n 's/^KITECODEC_VIDEO_CODEC=//p' | head -1)
KC_VENCODER=$(printf '%s\n' "$KC_INFO" | sed -n 's/^KITECODEC_VIDEO_ENCODER=//p' | head -1)
[ -n "$KC_VCODEC" ] || { echo "FAIL: '$KEXE info' did not report KITECODEC_VIDEO_CODEC"; exit 1; }
echo "== kitecodec will encode video with $KC_VENCODER (ffprobe reports '$KC_VCODEC')"

# Same for audio, and for the same reason. This suite asserted a literal 'aac' in eight places
# while the shared LGPL profile enabled aac only on Apple and Android, so on Linux and Windows the
# sample died with "No encoder named 'aac'" inside Transcoder and this script reported a core dump.
# The video side learned this lesson from libx264; audio had simply never been asked.
KC_ACODEC=$(printf '%s\n' "$KC_INFO" | sed -n 's/^KITECODEC_AUDIO_CODEC=//p' | head -1)
KC_AENCODER=$(printf '%s\n' "$KC_INFO" | sed -n 's/^KITECODEC_AUDIO_ENCODER=//p' | head -1)
[ -n "$KC_ACODEC" ] || { echo "FAIL: '$KEXE info' did not report KITECODEC_AUDIO_CODEC"; exit 1; }
echo "== kitecodec will encode audio with $KC_AENCODER (ffprobe reports '$KC_ACODEC')"

# The generator uses the system ffmpeg CLI, which is a separate install from the FFmpeg KiteCodec
# links, so pick a codec it actually has rather than assuming libx264 there either.
if "$FFMPEG" -hide_banner -loglevel error -encoders 2>/dev/null | grep -qE '^ V[.A-Z]* +libx264 '; then
  GEN_VENC=libx264; GEN_VCODEC=h264
else
  GEN_VENC=mpeg4;   GEN_VCODEC=mpeg4
fi

echo "== generate 3s A/V test clip with $GEN_VENC (keyframe every second)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=duration=3:size=320x240:rate=30" \
  -f lavfi -i "sine=frequency=440:duration=3" \
  -c:v "$GEN_VENC" -pix_fmt yuv420p -g 30 -keyint_min 30 -c:a aac -shortest "$WORK/in.mp4"

echo "== kitecodec probe"
"$KEXE" probe "$WORK/in.mp4"

echo "== error path: transcode of a nonexistent input must fail and create no output"
if "$KEXE" transcode "$WORK/does-not-exist.mp4" "$WORK/should-not-exist.mp4" >/dev/null 2>&1; then
  echo "FAIL: transcode of nonexistent input exited 0"; exit 1
fi
[ ! -e "$WORK/should-not-exist.mp4" ] || { echo "FAIL: output file created for nonexistent input"; exit 1; }

echo "== kitecodec transcode A/V with filter chain"
# `hue` rather than `eq`: eq is deps="gpl" in FFmpeg, so it does not exist in the LGPL profile
# KiteCodec ships by default. hue carries a brightness parameter and is available everywhere.
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out.mp4" "scale=160:120,hue=b=0.15,format=yuv420p"

"$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$WORK/out.mp4"
has_stream "$WORK/out.mp4" "$KC_VCODEC" video || { echo "FAIL: no $KC_VCODEC video stream in output"; exit 1; }
has_stream "$WORK/out.mp4" "$KC_ACODEC" audio  || { echo "FAIL: no $KC_ACODEC audio stream in output"; exit 1; }

width=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$WORK/out.mp4")
[ "$width" = "160" ] || { echo "FAIL: output width $width != 160 (scale filter not applied?)"; exit 1; }

# Content sanity beyond dimensions: the decoded first frame must not be a solid single color
# (catches "filter graph produced garbage/black" while width/duration still look right).
frame_stats=$("$FFMPEG" -hide_banner -loglevel error -i "$WORK/out.mp4" \
  -vf "signalstats,metadata=print:file=-" -frames:v 1 -f null - | tr -d '\r')
yavg=$(printf '%s\n' "$frame_stats" | sed -n 's/.*lavfi\.signalstats\.YAVG=//p' | head -1)
ymin=$(printf '%s\n' "$frame_stats" | sed -n 's/.*lavfi\.signalstats\.YMIN=//p' | head -1)
ymax=$(printf '%s\n' "$frame_stats" | sed -n 's/.*lavfi\.signalstats\.YMAX=//p' | head -1)
awk -v a="$yavg" 'BEGIN { exit !(a > 16 && a < 235) }' \
  || { echo "FAIL: first-frame YAVG $yavg outside sane range (black/white output?)"; exit 1; }
awk -v lo="$ymin" -v hi="$ymax" 'BEGIN { exit !(hi - lo >= 16) }' \
  || { echo "FAIL: first-frame luma range $ymin..$ymax too flat (solid-color output?)"; exit 1; }

dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/out.mp4")
awk -v d="$dur" 'BEGIN { exit !(d > 2.5 && d < 3.5) }' \
  || { echo "FAIL: output duration $dur not ~3s (timestamp bug?)"; exit 1; }

echo "== kitecodec transcode video-only (-an)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_an.mp4" "scale=160:120,format=yuv420p" -an
audio_streams=$("$FFPROBE" -v error -select_streams a -show_entries stream=index -of csv=p=0 "$WORK/out_an.mp4" | grep -c . || true)
[ "$audio_streams" = "0" ] || { echo "FAIL: audio stream present after -an"; exit 1; }

echo "== kitecodec transcode with audio stream-copy (-acopy)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_acopy.mp4" "scale=160:120,format=yuv420p" -acopy
has_stream "$WORK/out_acopy.mp4" aac audio || { echo "FAIL: copied aac stream missing"; exit 1; }
# Stream copy must preserve the source audio bit-exactly, so compare the extracted packets.
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/in.mp4" -map 0:a:0 -c copy "$WORK/a_src.aac"
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/out_acopy.mp4" -map 0:a:0 -c copy "$WORK/a_copy.aac"
cmp -s "$WORK/a_src.aac" "$WORK/a_copy.aac" || { echo "FAIL: -acopy audio differs from source (not bit-exact)"; exit 1; }

echo "== kitecodec remux mp4 → mkv (full stream copy)"
"$KEXE" remux "$WORK/in.mp4" "$WORK/remuxed.mkv"
"$FFPROBE" -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$WORK/remuxed.mkv"
has_stream "$WORK/remuxed.mkv" "$GEN_VCODEC" video || { echo "FAIL: remux lost video stream"; exit 1; }
has_stream "$WORK/remuxed.mkv" aac audio  || { echo "FAIL: remux lost audio stream"; exit 1; }
remux_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/remuxed.mkv")
awk -v d="$remux_dur" 'BEGIN { exit !(d > 2.5 && d < 3.5) }' \
  || { echo "FAIL: remux duration $remux_dur not ~3s"; exit 1; }

echo "== kitecodec remux mkv → mp4 (round-trip back)"
"$KEXE" remux "$WORK/remuxed.mkv" "$WORK/remuxed_back.mp4"
has_stream "$WORK/remuxed_back.mp4" "$GEN_VCODEC" video || { echo "FAIL: mkv→mp4 remux lost video stream"; exit 1; }
has_stream "$WORK/remuxed_back.mp4" aac audio  || { echo "FAIL: mkv→mp4 remux lost audio stream"; exit 1; }
back_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/remuxed_back.mp4")
awk -v d="$back_dur" 'BEGIN { exit !(d > 2.5 && d < 3.5) }' \
  || { echo "FAIL: mkv→mp4 remux duration $back_dur not ~3s"; exit 1; }

echo "== kitecodec transcode with trim (--ss 1 --to 2, frame-exact)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_trim.mp4" "scale=160:120,format=yuv420p" --ss 1 --to 2 --title "trimmed by kitecodec"
trim_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/out_trim.mp4")
awk -v d="$trim_dur" 'BEGIN { exit !(d > 0.8 && d < 1.3) }' \
  || { echo "FAIL: trimmed duration $trim_dur not ~1s"; exit 1; }
trim_start=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=start_time -of csv=p=0 "$WORK/out_trim.mp4")
awk -v s="$trim_start" 'BEGIN { exit !(s < 0.2 && s > -0.2) }' \
  || { echo "FAIL: trimmed output starts at $trim_start, expected ~0 (ts rebase broken)"; exit 1; }
trim_title=$("$FFPROBE" -v error -show_entries format_tags=title -of csv=p=0 "$WORK/out_trim.mp4")
[ "$trim_title" = "trimmed by kitecodec" ] || { echo "FAIL: metadata title '$trim_title'"; exit 1; }

echo "== kitecodec remux with trim (keyframe-snapped)"
"$KEXE" remux "$WORK/in.mp4" "$WORK/remux_trim.mp4" --ss 1 --to 2
rtrim_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/remux_trim.mp4")
awk -v d="$rtrim_dur" 'BEGIN { exit !(d > 0.8 && d < 1.6) }' \
  || { echo "FAIL: remux-trim duration $rtrim_dur not in keyframe-snap range"; exit 1; }

echo "== kitecodec thumbnail (jpg + png)"
"$KEXE" thumbnail "$WORK/in.mp4" "$WORK/thumb.jpg" 1.5
"$KEXE" thumbnail "$WORK/in.mp4" "$WORK/thumb.png" 1.5
for img in thumb.jpg thumb.png; do
  [ -s "$WORK/$img" ] || { echo "FAIL: $img empty"; exit 1; }
  codec=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$WORK/$img")
  case "$img:$codec" in
    thumb.jpg:mjpeg|thumb.png:png) ;;
    *) echo "FAIL: $img decoded as '$codec'"; exit 1;;
  esac
done

echo "== kitecodec audio-only transcode (no video input)"
"$FFMPEG" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=330:duration=2" -c:a pcm_s16le "$WORK/tone.wav"
"$KEXE" transcode "$WORK/tone.wav" "$WORK/tone.m4a"
has_stream "$WORK/tone.m4a" "$KC_ACODEC" audio || { echo "FAIL: audio-only output missing $KC_ACODEC"; exit 1; }
if has_stream_type "$WORK/tone.m4a" video; then echo "FAIL: audio-only output has video"; exit 1; fi
ao_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/tone.m4a")
awk -v d="$ao_dur" 'BEGIN { exit !(d > 1.7 && d < 2.3) }' \
  || { echo "FAIL: audio-only duration $ao_dur not ~2s"; exit 1; }

echo "== kitecodec subtitle passthrough (mkv)"
printf '1\n00:00:00,500 --> 00:00:02,000\nkitecodec was here\n' > "$WORK/subs.srt"
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/in.mp4" -i "$WORK/subs.srt" \
  -map 0 -map 1 -c copy -c:s srt "$WORK/in_subs.mkv"
"$KEXE" transcode "$WORK/in_subs.mkv" "$WORK/out_subs.mkv" "scale=160:120,format=yuv420p" -scopy
has_stream_type "$WORK/out_subs.mkv" subtitle || { echo "FAIL: subtitle stream lost"; exit 1; }

# Regression: a container whose timeline does NOT start at zero. Every timestamp libavformat
# reports for an MPEG-TS stream is offset by its start_time (~5s here), while --ss/--to mean
# "n seconds into the content". Getting that conversion wrong silently shifts the whole trim
# window, and mp4 (start_time 0) can never catch it.
echo "== kitecodec trim on a nonzero-start container (mpegts, start_time≈5s)"
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/in.mp4" \
  -c copy -output_ts_offset 5 -f mpegts "$WORK/offset.ts"
ts_start=$("$FFPROBE" -v error -show_entries format=start_time -of csv=p=0 "$WORK/offset.ts")
if ! awk -v s="$ts_start" 'BEGIN { exit !(s > 4.0) }'; then
  echo "SKIP: generated ts start_time '$ts_start' is not offset, cannot exercise the conversion"
else
  "$KEXE" transcode "$WORK/offset.ts" "$WORK/offset_trim.mp4" "scale=160:120,format=yuv420p" --ss 1 --to 2 -an
  off_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/offset_trim.mp4")
  awk -v d="$off_dur" 'BEGIN { exit !(d > 0.8 && d < 1.3) }' \
    || { echo "FAIL: mpegts trim duration $off_dur not ~1s (container start_time not compensated)"; exit 1; }
  off_start=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=start_time -of csv=p=0 "$WORK/offset_trim.mp4")
  awk -v s="$off_start" 'BEGIN { exit !(s < 0.2 && s > -0.2) }' \
    || { echo "FAIL: mpegts trim output starts at $off_start, expected ~0"; exit 1; }

  echo "== kitecodec remux trim on the same nonzero-start container"
  "$KEXE" remux "$WORK/offset.ts" "$WORK/offset_remux.mp4" --ss 1 --to 2
  offr_dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$WORK/offset_remux.mp4")
  awk -v d="$offr_dur" 'BEGIN { exit !(d > 0.8 && d < 1.6) }' \
    || { echo "FAIL: mpegts remux-trim duration $offr_dur outside keyframe-snap range"; exit 1; }
fi

# Regression: an unfiltered transcode must work. With no filter graph the encoder receives the
# DECODER's frames, whose pixel format need not match the encoder's, and the pipeline is responsible
# for converting rather than failing with a bare EINVAL.
echo "== kitecodec transcode with no filter chain (pixel-format reconciliation)"
"$KEXE" transcode "$WORK/in.mp4" "$WORK/out_nofilter.mp4" "" -an
has_stream "$WORK/out_nofilter.mp4" "$KC_VCODEC" video \
  || { echo "FAIL: unfiltered transcode produced no $KC_VCODEC video stream"; exit 1; }

# Hardware encode is a property of the LINKED FFmpeg, not of the host OS: a vendored build without
# the h264_videotoolbox ENCODER (--enable-videotoolbox alone does not add it) has no VT path even
# on macOS. Probe rather than assume, and say so when skipping.
if printf '%s\n' "$KC_INFO" | grep -q 'h264_videotoolbox=✓'; then
  echo "== kitecodec VideoToolbox hardware encode (-vt)"
  "$KEXE" transcode "$WORK/in.mp4" "$WORK/out_vt.mp4" "scale=320:240,format=yuv420p" -vt -an
  vt_codec=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$WORK/out_vt.mp4")
  [ "$vt_codec" = "h264" ] || { echo "FAIL: VT output codec '$vt_codec'"; exit 1; }
elif [ "$(uname)" = "Darwin" ]; then
  echo "== SKIP VideoToolbox encode: linked FFmpeg has no h264_videotoolbox encoder"
fi

echo "e2e OK (A/V=${dur}s, remux=${remux_dur}s, trim=${trim_dur}s, audio-only=${ao_dur}s)"
