#!/usr/bin/env bash
#
# Makes the Dolby Vision fixture of the contract tests, DolbyVisionFixtures.kt, and the picture its
# composition is checked against.
#
# The clip is two 32x24 frames of profile 5 in MP4: a 10-bit HEVC base layer of smooth ramps,
# full range, with an RPU on each frame that rpu/ writes. The expected picture is libplacebo's
# composition of the same clip, an implementation independent of KiteFFmpeg's, turned by
# expected.py into the layout KiteFFmpeg's composer writes.
#
# The ramps are smooth on purpose. libplacebo upsamples chroma with its own filter before it
# reshapes, and KiteFFmpeg reshapes at each chroma site and interpolates the result, so at a sharp
# chroma edge the two answers differ by the filters and not by the composition. The chroma swing is
# kept moderate for the same reason: where a colour is so saturated that one linear channel sits
# near zero, the steep foot of the PQ curve turns a difference of a thousandth into tens of codes.
#
# Needs: ffmpeg with libx265 and libplacebo and a Vulkan device (a software one such as lavapipe is
# enough), dovi_tool (https://github.com/quietvoid/dovi_tool), MP4Box from GPAC, cargo and python3.
# Measured with FFmpeg 6.1.1, libplacebo 6.338, dovi_tool 2.3.4, dolby_vision 3.4.0 and GPAC 2.2.1.
#
# Usage: ./make-fixture.sh <work directory>
#
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${1:?usage: $0 <work directory>}"
mkdir -p "$WORK"
cd "$WORK"

cargo run --quiet --release --manifest-path "$HERE/rpu/Cargo.toml" -- rpu.bin
ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "color=black:s=32x24:r=24,format=yuv420p10le,geq=lum='60+880*X/W+40*sin(Y/4+N)':cb='512+80*sin(6.2832*X/W+N)':cr='512+70*cos(6.2832*Y/H+0.5*N)'" \
    -frames:v 2 -c:v libx265 -x265-params "crf=8:range=full:repeat-headers=1:info=0:log-level=error" bl.hevc
dovi_tool inject-rpu -i bl.hevc --rpu-in rpu.bin -o p5.hevc > /dev/null
rm -f p5.mp4
MP4Box -quiet -add p5.hevc:dvp=5:fps=24 -new p5.mp4
ffmpeg -hide_banner -loglevel error -y -init_hw_device vulkan=vk -filter_hw_device vk -i p5.mp4 \
    -vf "libplacebo=w=32:h=24:format=gbrpf32le:colorspace=gbr:color_primaries=bt2020:color_trc=smpte2084:range=full:tonemapping=clip:gamut_mode=clip:dithering=none:deband=0:peak_detect=0:apply_dolbyvision=1" \
    -f rawvideo composed.gbrpf32le
python3 "$HERE/expected.py" composed.gbrpf32le 32 24 2 expected.yuv

for file in p5.mp4 expected.yuv; do
    echo "$file  $(wc -c < "$file") bytes  sha256 $(sha256sum "$file" | cut -d' ' -f1)"
done
