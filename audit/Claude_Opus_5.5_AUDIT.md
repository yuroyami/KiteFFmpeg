# KiteFFmpeg audit by Claude Opus 5.5

Written on 2026-11-08 against KiteFFmpeg `f79ba08` on `main`. This is a draft for the owner to check by hand before anything is posted to the issue tracker. Nothing here has been filed.

This file holds the 31 findings of a joint audit of KiteFFmpeg and a player built on it that need a change here. Each one is written so it can be fixed in KiteFFmpeg with no knowledge of any consumer. Entries marked Both also describe a consumer-side half, which is noted in their text and does not need to be acted on here. Ids keep the numbering of the joint audit, so gaps in the sequence are findings that belong elsewhere.

The trees were read, not built. Every finding was retraced by a second reader who tried to disprove it. NAT-01 was also given to a third reader, and GKF-01 was spot checked against the code once more. **Plausible** means the reasoning holds in the code but a run is needed to prove it. Claims about FFmpeg's own behaviour come from the vendored 9.0.2 source where it was read, and each says how to confirm it. Already-open issues (#22, #44, #85, #112, #183 to #222) were excluded.

| Id | Repo | Severity | Kind | Verdict | Title |
|---|---|---|---|---|---|
| [GKF-01](#gkf-01-a-remuxed-iphone-hevc-video-is-retagged-hev1-so-the-mp4-or-mov-it-becomes-no-longer-plays-in-apples-players) | KiteFFmpeg | high | defect | Confirmed | A remuxed iPhone HEVC video is retagged hev1, so the MP4 or MOV it becomes no longer plays in Apple's players |
| [SUB-03](#sub-03-hls-webvtt-subtitles-ignore-x-timestamp-map-so-on-most-packaged-streams-every-line-shows-at-the-wrong-time) | Both | high | defect | Plausible | HLS WebVTT subtitles ignore X-TIMESTAMP-MAP, so on most packaged streams every line shows at the wrong time |
| [API-01](#api-01-a-web-page-that-serves-the-codec-module-of-another-kiteffmpeg-release-is-accepted-and-then-read-at-the-wrong-offsets) | KiteFFmpeg | medium | risk | Confirmed | A web page that serves the codec module of another KiteFFmpeg release is accepted and then read at the wrong offsets |
| [API-04](#api-04-no-artifact-carries-dav1ds-copyright-notice-and-licence-text-which-its-bsd-licence-requires-in-every-binary) | KiteFFmpeg | medium | defect | Confirmed | No artifact carries dav1d's copyright notice and licence text, which its BSD licence requires in every binary |
| [API-15](#api-15-what-plays-end-to-end-on-each-platform-today-and-where-the-gaps-are) | Both | medium | opportunity | Confirmed | What plays end to end on each platform today, and where the gaps are |
| [API-16](#api-16-apple-tv-cannot-play-anything-because-the-media-library-has-no-tvos-build) | Both | medium | opportunity | Confirmed | Apple TV cannot play anything, because the media library has no tvOS build |
| [BRG-02](#brg-02-decoders-are-opened-without-a-packet-time-base-so-the-first-sound-after-encoder-priming-keeps-its-untrimmed-time) | KiteFFmpeg | medium | defect | Plausible | Decoders are opened without a packet time base, so the first sound after encoder priming keeps its untrimmed time |
| [BRG-03](#brg-03-a-packet-sent-to-a-drained-decoder-is-reported-as-decoded-and-silently-thrown-away) | KiteFFmpeg | medium | defect | Confirmed | A packet sent to a drained decoder is reported as decoded and silently thrown away |
| [BRG-04](#brg-04-webvtt-captions-read-through-ffmpeg-lose-their-position-line-and-alignment-settings) | Both | medium | defect | Confirmed | WebVTT captions read through FFmpeg lose their position, line and alignment settings |
| [GKF-02](#gkf-02-a-trimmed-remux-or-copy-transcode-ends-its-sound-early-because-every-stream-stops-at-the-first-picture-packet-past-the-end) | KiteFFmpeg | medium | defect | Confirmed | A trimmed remux or copy transcode ends its sound early, because every stream stops at the first picture packet past the end |
| [GKF-03](#gkf-03-an-mp4-written-to-a-byte-sink-with-the-faststart-option-the-docs-suggest-loses-its-index-and-cannot-be-played-at-all) | KiteFFmpeg | medium | defect | Confirmed | An MP4 written to a byte sink with the faststart option the docs suggest loses its index and cannot be played at all |
| [NAT-01](#nat-01-encrypted-hls-that-cuts-one-file-into-byte-ranges-plays-corrupted-after-the-first-segment-when-a-nested-opener-serves-it) | KiteFFmpeg | medium | defect | Confirmed | Encrypted HLS that cuts one file into byte ranges plays corrupted after the first segment when a nested opener serves it |
| [NAT-02](#nat-02-on-the-web-an-hls-key-or-playlist-given-inline-as-a-data-address-fails-to-open-though-the-opener-documentation-says-ffmpeg-reads-it) | KiteFFmpeg | medium | defect | Confirmed | On the web an HLS key or playlist given inline as a data: address fails to open, though the opener documentation says FFmpeg reads it |
| [NAT-03](#nat-03-no-fuzz-target-reaches-the-aes-128-reader-the-nested-opener-or-the-dolby-vision-and-display-converters-that-kiteffmpeg-wrote-itself) | KiteFFmpeg | medium | test | Confirmed | No fuzz target reaches the AES-128 reader, the nested opener or the Dolby Vision and display converters that KiteFFmpeg wrote itself |
| [VID-01](#vid-01-hdr-video-bands-on-every-software-path-because-pq-is-tone-mapped-from-8-bit-values) | Both | medium | defect | Confirmed | HDR video bands on every software path because PQ is tone mapped from 8-bit values |
| [WEB-07](#web-07-the-published-web-codec-modules-are-built-with-an-emscripten-no-test-ever-runs) | KiteFFmpeg | medium | test | Confirmed | The published web codec modules are built with an emscripten no test ever runs |
| [WEB-08](#web-08-the-shipped-libass-web-module-is-compiled-and-linked-by-three-different-unpinned-emscripten-versions) | Both | medium | risk | Plausible | The shipped libass web module is compiled and linked by three different unpinned emscripten versions |
| [API-14](#api-14-on-a-processor-the-desktop-jar-does-not-carry-it-loads-the-x64-linux-library-and-fails-with-a-confusing-error) | KiteFFmpeg | low | defect | Confirmed | On a processor the desktop jar does not carry, it loads the x64 Linux library and fails with a confusing error |
| [API-19](#api-19-files-on-a-home-network-share-cannot-be-played-without-a-web-server-in-front-of-them) | Both | low | opportunity | Confirmed | Files on a home network share cannot be played without a web server in front of them |
| [BRG-07](#brg-07-interrupting-a-source-while-it-closes-reads-a-freed-ffmpeg-context-although-a-safe-interrupt-already-exists) | Both | low | risk | Confirmed | Interrupting a source while it closes reads a freed FFmpeg context, although a safe interrupt already exists |
| [BRG-10](#brg-10-taking-several-thumbnails-opens-and-tears-down-a-new-video-decoder-for-every-picture) | KiteFFmpeg | low | opportunity | Confirmed | Taking several thumbnails opens and tears down a new video decoder for every picture |
| [GKF-04](#gkf-04-trimming-a-song-or-audiobook-that-carries-cover-art-reads-the-whole-input-because-the-cover-picture-becomes-the-stream-that-ends-the-cut) | KiteFFmpeg | low | defect | Confirmed | Trimming a song or audiobook that carries cover art reads the whole input, because the cover picture becomes the stream that ends the cut |
| [GKF-05](#gkf-05-a-playlist-read-from-a-byte-source-without-an-opener-can-make-ffmpeg-fetch-plain-http-addresses-and-local-media-files-on-its-own) | KiteFFmpeg | low | risk | Plausible | A playlist read from a byte source without an opener can make FFmpeg fetch plain http addresses and local media files on its own |
| [GKF-06](#gkf-06-closing-a-dolby-vision-composition-while-another-thread-still-composes-a-band-frees-the-pictures-under-it) | KiteFFmpeg | low | risk | Plausible | Closing a Dolby Vision composition while another thread still composes a band frees the pictures under it |
| [GKF-07](#gkf-07-every-ffmpeg-log-line-from-a-decoder-worker-thread-attaches-that-thread-to-the-jvm-and-detaches-it-again) | KiteFFmpeg | low | risk | Plausible | Every FFmpeg log line from a decoder worker thread attaches that thread to the JVM and detaches it again |
| [NAT-04](#nat-04-no-published-artifact-or-release-zip-carries-the-dav1d-copyright-notice-that-its-bsd-licence-asks-a-binary-distributor-to-reproduce) | KiteFFmpeg | low | docs | Confirmed | No published artifact or release zip carries the dav1d copyright notice that its BSD licence asks a binary distributor to reproduce |
| [NAT-05](#nat-05-the-libass-chain-release-ships-fribidi-under-the-lgpl-with-no-source-attached-and-no-provenance-unlike-the-ffmpeg-release) | KiteFFmpeg | low | docs | Confirmed | The libass chain release ships FriBidi under the LGPL with no source attached and no provenance, unlike the FFmpeg release |
| [NAT-06](#nat-06-the-ffmpeg-recipe-comments-point-at-desktop-gpl-and-base-profiles-that-no-longer-exist) | KiteFFmpeg | low | docs | Confirmed | The FFmpeg recipe comments point at desktop GPL and base profiles that no longer exist |
| [SUB-11](#sub-11-captions-on-cc2-cc3-and-cc4-of-a-broadcast-can-never-be-chosen) | Both | low | opportunity | Confirmed | Captions on CC2, CC3 and CC4 of a broadcast can never be chosen |
| [VID-11](#vid-11-the-web-canvas-tone-maps-every-hdr-stream-from-a-fixed-1000-nits-and-kiteffmpegs-documentation-wrongly-says-that-matches-kiteplayer) | Both | low | docs | Confirmed | The web canvas tone maps every HDR stream from a fixed 1000 nits, and KiteFFmpeg's documentation wrongly says that matches KitePlayer |
| [WEB-16](#web-16-hdr-video-on-the-web-canvas-is-tone-mapped-after-it-has-been-cut-to-8-bits-so-smooth-gradients-can-band) | KiteFFmpeg | low | opportunity | Confirmed | HDR video on the web canvas is tone mapped after it has been cut to 8 bits, so smooth gradients can band |

## Findings

### VID-01. HDR video bands on every software path because PQ is tone mapped from 8-bit values

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | defect | medium (was high) | Confirmed | high | none |

**Second check:** Downgraded from high to medium and merged in spirit with BRG-05, which is the same code path (`Conversions.kt:251` and `:362`, `HdrToneMap.kt:28-36` and `:61`, `SoftwareConverter.native.kt:93`). It is a visible quality limit in tone mapped HDR gradients, reasoned from the PQ step size and not measured. On Apple and Android the hardware and shader paths normally draw HDR, so there this path is only the fallback, while the desktop AWT view always takes it.

**Same root as:** BRG-05, WEB-16. Post these as one issue.

**Where:**
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:251`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:255-258`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:362`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:28-36`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:58-63`
- `KitePlayer/kiteplayer-ffmpeg/src@native/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt:93`
- `KitePlayer/kiteplayer-view-bindings/src@jvm/io/github/yuroyami/kiteplayer/mobile/DesktopPlayerViewRenderer.kt:41`
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:9-13`
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:302`

**What happens:** HDR10 pictures are stored with 10 bits per sample because the PQ curve that encodes their brightness needs that many steps to look smooth. The software converter, which is the only video path on the desktop JVM view and the fallback on iOS, macOS and Android, first throws away the lowest two bits of each sample, converts the picture to 8-bit RGB, and only then tone maps it to standard range. The tone map looks every pixel up in a table with just 256 entries indexed by those 8-bit PQ bytes. One 8-bit PQ step in the mid tones is about four 10-bit steps, a brightness jump of several percent, so smooth gradients turn into visible stairs once the tone map stretches them into the SDR range. The Metal renderer tone maps from the full sample in floating point and does not band. KiteFFmpeg's web display converter copies the same 8-bit law on purpose, through swscale's 8-bit RGB output, so the web canvas has the same weakness. Ten-bit standard range video also loses its low bits by truncation rather than rounding, which darkens every level by about 0.375 of an 8-bit step on average.

**Evidence:**
```kotlin
// Conversions.kt:255-258
private enum class PackedSampleLayout(val bytesPerSample: Int, val dropBits: Int) {
    Eight(1, 0),
    TenLowAligned(2, 2),
    TenHighAligned(2, 8),
}
// Conversions.kt:362
((this[offset + 1].toInt() and 0xFF) shl 8 or (this[offset].toInt() and 0xFF)) shr layout.dropBits
// Conversions.kt:251, after the 8-bit RGBA is written
HdrToneMap.forColorSpaceOrNull(colorSpace, hdrPeakNits)?.mapInPlace(out)
```
```kotlin
// HdrToneMap.kt:28-35
private val eotf = DoubleArray(256) { code ->
    val e = code / 255.0
    ...
        pqDecode(e) * 10000.0
// HdrToneMap.kt:61
var r = eotf[rgba[at].toInt() and 0xFF]
```
```c
/* helpers_display.c:12-13 */
 *   BT.2390 EETF from a 1000 nit peak to 203 nit reference white, then gamma 2.2. The law runs on
 *   the 8-bit RGB bytes, as the Kotlin one does, so the two paths give the same picture. */
```
The desktop adapter converts every frame with `SoftwareConverter.toRgba(frame as KiteFFmpegVideoFrame, buffers)` (DesktopPlayerViewRenderer.kt:41), which lands in `tightlyPackedToRgba` and the code above; the native converter runs the same `mapInPlace` (SoftwareConverter.native.kt:93).

**Failure scenario:** Play an HDR10 film (yuv420p10le, PQ, BT.2020) in the desktop JVM view, in the UIKit or AppKit CPU fallback, in Android's software KiteVideo fallback, or on the web canvas. A clear sky, fog, or a dim wall shows stair-step bands that the same file does not show on the Metal renderer.

**Suggested fix:** Tone map before quantizing. In KitePlayer, read the full 10-bit sample, run the YUV to RGB step in float or 16-bit integers, index the PQ table with at least 10 (ideally 12) bits of input or evaluate it directly, apply the EETF, and only then encode to 8 bits, ideally with a small ordered dither. Round rather than truncate when 10-bit standard range is reduced to 8 bits. In KiteFFmpeg, have the display converter ask swscale for a 16-bit RGB intermediate (rgb48 or rgba64) when the frame's transfer is PQ or HLG, and run its tone map on those values before writing 8-bit RGBA.

**How to check:** Read `Conversions.kt` around lines 255 and 362 and `HdrToneMap.kt` lines 28 to 63. To see it, make a 10-bit PQ gradient with `ffmpeg -f lavfi -i gradients=s=1920x1080:c0=0x101010:c1=0x606060 -pix_fmt yuv420p10le -color_trc smpte2084 -color_primaries bt2020 -colorspace bt2020nc` and play it in the desktop view next to the Metal renderer on a Mac.

### VID-11. The web canvas tone maps every HDR stream from a fixed 1000 nits, and KiteFFmpeg's documentation wrongly says that matches KitePlayer

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | docs | low | Confirmed | high | none |

**Where:**
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:9-13`
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:103`
- `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebRgba.kt:31-35`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:147-150`

**What happens:** KiteFFmpeg's display converter hard-codes a 1000 nit source peak and its comments say this is "the law KitePlayer's software converters use", so "the canvas shows the same picture they do". KitePlayer's tone map now takes the content's own peak from its HDR metadata and falls back to 1000 only when there is none, so the two paths differ for any master that is not 1000 nits. The KiteFFmpeg prose also describes KitePlayer's internals, which an independent library should not depend on, and it has already drifted.

**Evidence:**
```c
/* helpers_display.c:10-12 */
 *   PQ and HLG, which are tone mapped to SDR with the law KitePlayer's software converters use:
 *   ... BT.2390 EETF from a 1000 nit peak to 203 nit reference white
/* helpers_display.c:103 */
#define KC_SRC_PEAK 1000.0
```
```kotlin
// HdrToneMap.kt:147-149
internal fun forColorSpaceOrNull(colorSpace: ColorSpaceInfo, peakNits: Float? = null): HdrToneMap? {
    if (!colorSpace.isHdr) return null
    val gamut2020 = colorSpace.primaries == ColorPrimaries.Bt2020
    val srcPeak = peakNits?.toDouble() ?: SRC_PEAK
```

**Failure scenario:** A 4000 nit HDR10 master plays in the desktop view and on the web canvas. The desktop rolls highlights off from 4000 nits and keeps detail; the web canvas clamps everything above 1000 nits, so highlights blow out on one platform and not the other, against what the KiteFFmpeg documentation promises.

**Suggested fix:** In KiteFFmpeg, give `ffkmp_frame_convert_display` a source peak, taken from the frame's mastering display or content light side data when present and 1000 nits otherwise, and describe the law on its own terms without naming KitePlayer, in both `helpers_display.c` and `WebRgba.kt`. KiteFFmpeg needs no knowledge of the player for this.

**How to check:** Read the two comments and `forColorSpaceOrNull`.

### BRG-02. Decoders are opened without a packet time base, so the first sound after encoder priming keeps its untrimmed time

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Plausible | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Playback.native.kt:574-595`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Playback.jvm.kt:324-326`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/MediaSource.wasmJs.kt:572-579`, `KiteFFmpeg/native/kitecodec-c/src/helpers_codec.c:17-19`, `KiteFFmpeg/native/kitecodec-c/src/helpers_subtitle.c:37`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1646-1650`

**What happens:** Many audio formats begin with a few hundred samples of encoder warm-up that a player must cut: Opus states a pre-skip, and MP3 and AAC files with a gapless header state an encoder delay. libavcodec does the cut itself, and when it cuts part of a frame it moves that frame's timestamp forward by the cut, but only when the caller has told the decoder the stream's time base through `pkt_timebase`. FFmpeg documents that field as "decoding: set by user", and both `ffmpeg` and `ffplay` set it. KiteFFmpeg never sets it for audio or video decoders on any backend, so FFmpeg keeps the trimmed frame at the time of its first untrimmed sample and logs a warning. The subtitle open does set it, which shows the gap is only on this path.

**Evidence:**

```c
// helpers_codec.c:17-19, the whole of what the audio and video open does with the stream
KC_API int   ffkmp_codecctx_from_par(AVCodecContext *c, AVCodecParameters *p) {
    return (c && p) ? avcodec_parameters_to_context(c, p) : AVERROR(EINVAL);
}
```

```c
// helpers_subtitle.c:37, the subtitle open
        c->pkt_timebase = st->time_base;
```

A search of `native/` and every `kiteffmpeg/src*` folder finds no other `pkt_timebase` write outside a patch. The native open (Playback.native.kt:577 `ffkmp_codecctx_from_par(codecCtx, codecpar)`), the JVM open (Playback.jvm.kt:326 `Internals.codecCtxFromPar(context, parameters)`) and the wasm open (MediaSource.wasmJs.kt:577) all go straight from parameters to `avcodec_open2`. KitePlayer then anchors its audio clock on exactly the frame's own time (KiteFFmpegSource.kt:1646 `val mapped = mapper.mapTimestamp(frame.ptsMicros)`, then `anchorMicros = mapped.micros`). The libavcodec behaviour (in `discard_samples` in `decode.c`, the timestamp shift is guarded by `avctx->pkt_timebase.num`, else "Could not update timestamps for skipped samples") is from knowledge of FFmpeg, not read in this container.

**Failure scenario:** An Ogg or WebM Opus file with the usual 312-sample pre-skip: the first decoded frame keeps 648 of its 960 samples but is dated at the start of the 960, so it is dated 6.5 ms early and the next frame starts 6.5 ms after the first one ends, a timestamp gap the engine's playout timeline then has to absorb on every such file. An MP3 with a LAME header (about 1105 samples of delay at 44.1 kHz) gives the same shape at about 25 ms. Each such file also writes FFmpeg's warning into the app's log sink. KiteFFmpeg users with their own decode loops get the same early timestamps.

**Suggested fix:** In KiteFFmpeg, set `pkt_timebase` to the stream's time base between `avcodec_parameters_to_context` and `avcodec_open2` in `StreamDecoder.open` on native, JVM and wasm, and in the batch decoder open, as the subtitle helper already does. Add a test that decodes an Opus file with a pre-skip and checks that the first frame's time equals the stream's start.

**How to check:** Decode any Opus file through `StreamDecoder` with the log sink on and look for "Could not update timestamps for skipped samples". Then compare the first frame's pts with what `ffprobe -show_frames -select_streams a -read_intervals %+#2` prints for the same file.

### BRG-03. A packet sent to a drained decoder is reported as decoded and silently thrown away

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Playback.native.kt:452-454`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Playback.jvm.kt:223-224`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/Playback.wasmJs.kt:299-300`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/Playback.kt:275-288`

**What happens:** Once a decoder has been told "no more input" (a null packet) and has not been flushed since, FFmpeg refuses every further packet with an end-of-file answer. All three backends translate that answer into `true`, which the public contract defines as "consumed". So a caller that forgets the flush, or does not know it is needed, keeps sending packets that are all thrown away, with no frame, no error and no counter. The KDoc of `send` already tells the story of the same silent loss for a different cause, and that one was fixed by refusing loudly.

**Evidence:**

```kotlin
// Playback.native.kt:452-454
        return when {
            rc == 0 -> true
            rc == FFErrors.EOF -> true
```

```kotlin
// Playback.jvm.kt:223-224
        when (rc) {
            0, Internals.errorEof -> true
```

```kotlin
// Playback.wasmJs.kt:299-300, whose comment also misreads the answer: EOF means drained, not flushed
            // Already flushed. Nothing more to send, so the input is not owed another attempt.
            rc == ffkmp_averror_eof(m) -> true
```

```kotlin
// Playback.kt:275-285
     * A packet belonging to a different stream is REFUSED rather than decoded. Feeding one used to
     * reach FFmpeg, which answered INVALIDDATA, which every backend swallowed as consumed, so the
     * input vanished and nothing said why.
     ...
     * @return true when consumed; false when output must be drained before retrying the same packet
```

KitePlayer's own scripted decoders refuse this case on purpose, and its CLAUDE.md says they do so because FFmpeg answers it with an error, so the test doubles are stricter than the real binding. KitePlayer's current paths flush before feeding again, so this is a contract defect in KiteFFmpeg rather than an observed KitePlayer bug.

**Failure scenario:** A KiteFFmpeg user drains a decoder at the end of a live input, the input resumes, and they keep calling `send` without `flush`. Every call returns `true`, `receive` returns null forever, and nothing says why. A future KitePlayer path that drains a lane and reopens it without the flush would stall the same way while every scripted test stays green.

**Suggested fix:** On a non-null packet, map end-of-file to an `IllegalStateException` or a typed `FFmpegException` that names the missing flush, on all three backends. Keep `true` only for a second null drain packet. Correct the wasm comment and state the rule in the `send` KDoc.

**How to check:** A three-line test: open a decoder, send a null packet, receive until null, then send a real packet and assert it is refused. Today it returns `true`.

### BRG-04. WebVTT captions read through FFmpeg lose their position, line and alignment settings

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | defect | medium | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSubtitleDecoder.kt:41-43`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSubtitleDecoder.kt:135-137`, `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt:91`, `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt:116-150`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHls.kt:42-45`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/Playback.kt:16-100`

**What happens:** A WebVTT cue can say where it goes: `align:start`, `position:20%`, `line:10%`, `size:50%`. FFmpeg's webvtt reader, which reads every WebVTT segment of an HLS stream and the WebVTT that KitePlayer's DASH reader serves to FFmpeg, and its Matroska reader both keep those settings beside the packet as side data, not in the text. KiteFFmpeg's `Packet` gives no access to side data, and KitePlayer's packet decoder parses the body only. So every such cue is drawn at the default bottom centre. The same cues keep their placement when the file is loaded as an external `.vtt`, because the file parser reads the settings from the timing line.

**Evidence:**

```kotlin
// KiteFFmpegSubtitleDecoder.kt:135-137
/** A WebVTT packet's cue. Matroska keeps the cue settings outside the body, so only the text is here. */
private fun webVttCue(track: WebVttTrackParser, body: String, startMicros: Long, endMicros: Long): SubtitleCue.Text? =
    track.parseCueBody(body).takeIf { it.isNotEmpty() }?.let { SubtitleCue.Text(startMicros, endMicros, it) }
```

```kotlin
// WebVttParser.kt:91, no settings
    public fun parseCueBody(body: String): List<StyledSpan> = vttSpans(body.trim(), VttStyleSheet.EMPTY, null)
```

The file parser's `parseTiming` (WebVttParser.kt:116-150) reads `ALIGN`, `POSITION`, `LINE` and `SIZE` from the text after the timing. KiteFFmpeg's `Packet` (Playback.kt:16-100) exposes timing, flags, size, position, tag and stream changes, and `copy()`, but no side data. DashHls.kt:42-45 says FFmpeg's HLS reader takes subtitles only as WebVTT text, which is why DASH subtitles take the same road. That FFmpeg stores the settings as `AV_PKT_DATA_WEBVTT_SETTINGS` comes from knowledge of FFmpeg's `webvttdec.c` and `matroskadec.c`.

**Failure scenario:** An HLS or DASH stream whose captions use `line:10%` to stay clear of burnt-in lower thirds, or `align:start` with `position:` to place a speaker's line on their side, as broadcast captioning commonly does. KitePlayer draws every cue at the bottom centre, over the on-screen text the author moved it away from. An MKV with positioned WebVTT does the same. Note that the integer form `line:0` is not read even by the file parser, which says so in a comment, so a test of this should use the percentage form.

**Suggested fix:** KiteFFmpeg: add a packet accessor for side data by type on every backend, at least the WebVTT settings and identifier, phrased as a general packet feature. KitePlayer: pass the settings string into a `WebVttTrackParser` call that reuses the timing-line settings code the file parser already has.

**How to check:** Make a two-cue `.vtt` with `align:start position:10% line:10%` on one cue, play it once as an external file and once wrapped in MKV with `ffmpeg -i x.vtt -c copy x.mkv`. The external file places the cue at the top left and the MKV draws it bottom centre.

### BRG-07. Interrupting a source while it closes reads a freed FFmpeg context, although a safe interrupt already exists

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | risk | low | Confirmed | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:748-753`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:78-81`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt:495-501`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/OpenInterrupt.kt:42-70`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/ItemSources.kt:95-96`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:462-470`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt:36-42`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt:135-137`

**What happens:** `MediaSource.interrupt` is the one call meant to come from another thread, yet it reads the FFmpeg context with no guard, and both backends say in a comment that calling it during or after close is forbidden. KiteFFmpeg's `OpenInterrupt` already solves this properly: its targets run under a lock that the close's unbind takes, so a close waits for an interrupt in flight. KitePlayer creates an `OpenInterrupt` for every open but then calls the unguarded `source.interrupt()` instead. The audio scan links its cancellation to that call from a job completion handler, which runs on the cancelling thread, while the scan thread may at that moment complete the link and close the session; `complete()` does not wait for a handler that is already running.

**Evidence:**

```kotlin
// MediaSource.native.kt:748-753
    public actual fun interrupt() {
        /* Deliberately NOT under stateLock: the whole point is reaching a context another thread
           is blocked on. The contract forbids calling this concurrently with or after close, so
           the pointer is live for the duration of the call. */
        ffkmp_fmt_interrupt(ctx)
    }
```

```c
// helpers_format.c:78-81
KC_API void ffkmp_fmt_interrupt(AVFormatContext *ctx) {
    if (!kc_ctx_has_cell(ctx)) return;
    kc_cell_raise((int *)ctx->interrupt_callback.opaque);
}
```

```kotlin
// AudioScanning.kt:41-42 and 135-137
    val link = currentCoroutineContext()[Job]?.let { Job(it) }
    link?.invokeOnCompletion { cause -> if (cause != null) session.source.interrupt() }
...
    } finally {
        link?.complete()
        session.close()
    }
```

The comment above line 41 says the link "is detached before the session closes, because an interrupt must never run after the close", which `complete()` does not guarantee for a handler already running on another thread. ItemSources.kt:95-96 says the `OpenInterrupt` "stays with the source the open returns, as MediaSource.interrupt() does", but `KiteFFmpegSource.interrupt` (KiteFFmpegSource.kt:462-470) does not use it.

**Failure scenario:** A waveform or loudness scan is cancelled at the instant it finishes. The cancelling thread enters `ffkmp_fmt_interrupt` and reads the context while the scan thread runs `avformat_close_input`. On Apple and native targets that reads, and may write, freed memory. The window is a few instructions wide, so it would show up as a rare, unexplained crash.

**Suggested fix:** KitePlayer: keep the `OpenInterrupt` in the opened item and have `KiteFFmpegSource.interrupt` raise it, plus the bridge, never `MediaSource.interrupt`. KiteFFmpeg: let `interrupt` and `close` share a small lock, so a close waits for an interrupt in progress and a later interrupt does nothing, which turns the documented undefined behaviour into a safe call for every user.

**How to check:** Read the two comments in the native and JVM `interrupt`, then the `finally` block of `AudioScanning`. A stress test that cancels scans of a short file at random moments under AddressSanitizer on Linux native would catch the read.

### BRG-10. Taking several thumbnails opens and tears down a new video decoder for every picture

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | opportunity | low | Confirmed | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:356-394`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:593-620`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Thumbnails.kt:72-74`

**What happens:** `extractFrame` runs the shared demux pass, which opens a fresh decoder for the stream on every call and frees it at the end, and it decodes every frame from the keyframe to the target, including frames nothing else refers to. KitePlayer's `Thumbnails.at` calls it once per position. A strip of a hundred thumbnails of a 4K HEVC file therefore opens a hundred frame-threaded decoders, each starting its own thread pool, and fully decodes each run up to its target.

**Evidence:**

```kotlin
// MediaSource.native.kt:371 and 391
            decode.forEach { stream -> decoders += DecoderState.open(ctx, stream) }
...
            decoders.forEach { it.free() }
```

```kotlin
// Thumbnails.kt:72-74
                return@withContext positions.map { position ->
                    ensureActive()
                    val decoded = source.extractFrame(position.inWholeMicroseconds, stream)
```

**Failure scenario:** A library grid or seek-bar preview of long 4K files takes noticeably longer and allocates more than a loop that reuses one decoder with a flush between positions, and on a phone it competes with playback for cores.

**Suggested fix:** In KiteFFmpeg, offer a batch frame extraction or a reusable extractor that opens the decoder once, flushes it between seeks, and skips non-reference frames while decoding toward a target, restoring full decoding for the last stretch before it. Then have `Thumbnails.at` use it.

**How to check:** Time `Thumbnails.at` over 50 positions of the `hevc4k10.mp4` fixture against a hand loop over one `PacketReader` and one `StreamDecoder` with a flush between seeks.

### NAT-01. Encrypted HLS that cuts one file into byte ranges plays corrupted after the first segment when a nested opener serves it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium (was high) | Confirmed | medium | none |

**Second check:** Downgraded from high to medium. The second reader retraced the same path (`helpers_format.c:1242`, `:1249`, `:1267`, `:1289`, `:1140`, and in the vendored hls.c lines 700, 1764 to 1765 and 1810) and confirmed it is a regression against stock FFmpeg's crypto protocol, but whole-segment AES-128 combined with byte ranges in one file is an uncommon packaging, and the result is a stream that will not play rather than corruption. Through KitePlayer the exposure is narrower still, because a multi-variant stream is served segment by segment with its byte range lines rewritten, so the failure mostly reaches KiteFFmpeg consumers who call `MediaSource.open(byteSource, opener)` directly.

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1242`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1249`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1267`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1289`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1140`, `KiteFFmpeg/native/patches/ffmpeg/0002-hls-trust-io-open.patch`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaByteOpener.kt:47`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaByteOpener.kt:40`, `KiteFFmpeg/native/kitecodec-c/tests/test_nested_io.c`

**What happens:** An HLS playlist can describe its segments as byte ranges inside one big file (the `EXT-X-BYTERANGE` tag), and it can encrypt each of those segments on its own with AES-128, where each segment restarts the cipher with its own starting value and ends with its own padding. FFmpeg asks for such a segment by handing the opener the file address plus two options that say where the range starts and ends, and expects the opened stream to start at that range. KiteFFmpeg's nested opener reads only the key and the starting value from those options and opens the whole file from its first byte. Because KiteFFmpeg's patch makes FFmpeg skip its protocol check, FFmpeg no longer thinks the address is an http one, so it then tries to seek the opened stream to the start of the range. An encrypted nested stream is never seekable, so FFmpeg reads and decrypts every byte from the start of the file up to the range, and it does so with the wrong chaining: the first block of the segment is decrypted against the last cipher block of the previous segment instead of the segment's own starting value, so its first 16 bytes come out as garbage, and the segment's own padding is never removed because the source does not end there.

**Evidence:**

The nested opener uses only two options and opens the bare address:

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1242
if (kc_hex_block(given, "key", key) < 0 || kc_hex_block(given, "iv", iv) < 0)
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1249
int rc = p->opener.open_fn(p->opener.opaque, inner, &source, &size, &seekable);
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1267
int can_seek = !encrypted && seekable && p->opener.seek_fn;
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1289
n->pb->seekable = can_seek ? AVIO_SEEKABLE_NORMAL : 0;
```

The padding is stripped only when the whole source ends:

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1140
if (n->ended && n->cipher_len == 0) {
    /* The final block: its last byte says how many padding bytes to drop. */
    int pad = n->plain[used - 1];
```

FFmpeg n9.0.2 hands the range as options and, when the address is not http, seeks after the open:

```c
// libavformat/hls.c:1764 (FFmpeg n9.0.2)
av_dict_set_int(&opts, "offset", seg->url_offset, 0);
av_dict_set_int(&opts, "end_offset", end_offset, 0);
// libavformat/hls.c:1810
if (ret == 0 && !is_http && seg->url_offset) {
    int64_t seekret = avio_seek(*in, seg->url_offset, SEEK_SET);
```

Patch 0002 jumps past the place where `is_http` is set, so `is_http` stays 0 for every address:

```c
// libavformat/hls.c:700 after patch 0002
if (c->trust_io_open)
    goto open;
```

A seek on a stream that is not seekable reads forward from where it is (`libavformat/aviobuf.c:281`, `while(s->pos < offset && !s->eof_reached) fill_buffer(s);`), and `hls.c:1477` then limits each read to `seg->size` bytes, which counts the padding. `MediaByteOpener.open(url: String)` at `MediaByteOpener.kt:47` has no range parameter, and `test_nested_io.c` has no byte-range case at all (a search for "BYTERANGE" or "offset" there finds only its own cursor seek).

**Failure scenario:** A single-file HLS stream, one `.ts` or `.mp4` file split into `EXT-X-BYTERANGE` segments with `METHOD=AES-128`, is opened through `MediaSource.open(byteSource, opener)`. The first segment starts at byte 0 and decrypts correctly apart from up to 16 bytes of padding passed on as trailing data. For a later segment at offset N, the bridge downloads and decrypts bytes 0 to N only to throw them away, then hands FFmpeg a first block of garbage and a tail of padding. In fragmented MP4 the first 16 bytes are the `moof` box header, so every segment after the first fails to parse; in MPEG-TS the first packet of every segment is lost. Unencrypted byte ranges from a forward-only opener take the same read from byte 0 on every segment (FFmpeg's connection reuse at `hls.c:1726` needs a seekable stream), so playback cost grows with the square of the file size, and on a browser page each reopen stages the whole child source again, up to 512 MiB (`MediaByteOpener.kt:40`).

**Suggested fix:** In `kc_io_open_nested`, read `offset` and `end_offset` from the options. When an offset is given, seek the caller's source there before wrapping it (or pass the range to the opener through a new entry point), set the nested context's position to that offset so FFmpeg's follow-up seek is a no-op, and treat `end_offset` as the end of the stream, so that an encrypted range starts its cipher at the range with the segment's own starting value and strips its padding at the range's end. This is entirely inside KiteFFmpeg.

**How to check:** Add a case to `test_nested_io.c` that encrypts two WAV payloads separately with different starting values, concatenates them into one resource, and serves a playlist with two `EXT-X-BYTERANGE` segments over that resource; read both and compare with the plain bytes. The second segment's first 16 bytes will differ today. A second case with a forward-only cursor that counts bytes read shows the read from byte 0.

### NAT-02. On the web an HLS key or playlist given inline as a data: address fails to open, though the opener documentation says FFmpeg reads it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1231`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:120`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:133`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:387`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaByteOpener.kt:15`, `KiteFFmpeg/native/kitecodec-c/tests/test_nested_io.c:412`

**What happens:** The nested opener never gives a `data:` address to the caller's opener; it sends it to FFmpeg's own default open, trusting FFmpeg's `data` protocol to read the bytes inside the address. The native builds compile that protocol in, but the web build starts from nothing and adds back only the `file` protocol, and the HLS demuxer does not pull `data` in by itself. So on the web the default open has no protocol for the address, and the caller's opener, which could have answered, is never asked.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1230
/* A data: URL carries its bytes inline, and FFmpeg's data protocol reads them. */
if (av_strstart(url, "data:", NULL)) return p->default_io_open(s, pb, url, flags, options);
```

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:120
"--disable-everything", "--disable-autodetect", "--disable-network",
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:133
"--enable-protocol=file",
// against the native profile, KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:387
"--enable-protocol=file,fd,pipe,data,http,tcp,udp,rtp,rtmp",
```

FFmpeg n9.0.2 `configure:3943` reads `hls_demuxer_select="aac_demuxer ac3_demuxer adts_header ac3_parser eac3_demuxer mov_demuxer mpegts_demuxer"`, with no `data_protocol`. The web demuxer list at `BuildFFmpegWasmTask.kt:258` includes `hls`, and the web backend drives the same C opener (`kiteffmpeg/src@wasmJs/.../WebNestedOpener.kt` and `wasm/OpenerLayout.kt`). The public KDoc promises the opposite at `MediaByteOpener.kt:15`: "A `data:` address never arrives. FFmpeg reads the bytes inside it itself." The native C suite proves the data: key only against a native FFmpeg (`test_nested_io.c:412`, `case_an_aes128_segment_decrypts(1)`), and no web test uses a data: address in a playlist.

**Failure scenario:** An HLS stream with `#EXT-X-KEY:METHOD=AES-128,URI="data:application/octet-stream;base64,..."` plays on Android, the JVM and Apple. Opened through `kiteffmpeg-web` or `kiteffmpeg-web-async`, FFmpeg's key read (`hls.c:1691`) fails with "protocol not found", and no segment can be decrypted. A master playlist that inlines a media playlist as a data: address fails the same way.

**Suggested fix:** Add `data` to the web build's protocol list; it is FFmpeg's own small protocol with no dependency and no network access. If the web build must stay at `file` only, hand a data: address to the caller's opener whenever `avio_find_protocol_name(url)` finds no protocol, and correct the KDoc line to say what each platform does.

**How to check:** Read the two configure lists above, then run the existing data-key case of `test_nested_io` in the web test runner (or add one to the wasmJs tests that opens a one-segment AES-128 playlist with a data: key); it fails on the web and passes natively.

### NAT-03. No fuzz target reaches the AES-128 reader, the nested opener or the Dolby Vision and display converters that KiteFFmpeg wrote itself

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | test | medium | Confirmed | high | KiteFFmpeg#205 |

**Where:** `KiteFFmpeg/native/kitecodec-c/fuzz/kc_fuzz.c:106`, `KiteFFmpeg/native/kitecodec-c/fuzz/fuzz_decode.c:117`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1117`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1360`, `KiteFFmpeg/native/kitecodec-c/src/helpers_dovi.c:444`, `KiteFFmpeg/native/kitecodec-c/src/helpers_dovi.c:536`, `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:298`, `KiteFFmpeg/.github/workflows/fuzz.yml:80`

**What happens:** Issue #205 is about FFmpeg's patched parsers never being fuzzed or run under a sanitizer, and it proposes an HLS fuzz target whose opener refuses every segment. This finding is the part of the same gap that lives in KiteFFmpeg's own C rather than in the patches. The media fuzz targets open their bytes with no nested opener at all, and a target with an opener that refuses every segment would still never reach the code that decrypts segments, parses the hex key and starting value, strips the padding, asks the opener for a redirect location, or frees and unlinks a nested source after a failed open. Likewise, the decode target converts frames only through the plain pixel converter, so the per-pixel Dolby Vision compose and the display converter, which both read values that come straight from the media, are never fuzzed.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/fuzz/kc_fuzz.c:106
int rc = ffkmp_fmt_open_input_io(out, media, kc_fuzz_media_read,
```

A search of `fuzz/*.c` finds no call to `open_input_io2`, no `kc_io_opener`, and no `dovi` or `convert_display`; `fuzz_decode.c:117` calls only `ffkmp_frame_convert_pixfmt`. The opener path refuses itself on an FFmpeg without the patch:

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1360
if (opener && !ffkmp_fmt_nested_io_available()) return AVERROR(ENOSYS);
```

and `fuzz.yml:80` installs Ubuntu 24.04's FFmpeg 6.1 packages, which do not carry the `trust_io_open` option, so an opener target added as things stand would stop at that line.

**Failure scenario:** A hostile segment or key drives an edge of `kc_io_crypt_read` (a short final read across a block boundary, a source that answers more than it was asked for, a final block whose pad byte is out of range) or of the nested free and unlink order during a failed open, or a hostile Dolby Vision RPU drives an edge of the compose. libFuzzer never generates such inputs, so a memory bug there ships unseen on the exact path that handles untrusted network playlists and HDR streams.

**Suggested fix:** Add a target whose in-memory opener serves a fuzzer-split playlist, segments and key so that AES-128 decryption is reached, built against the patched vendored tree (which is the same build #205 asks for). Add a target, or extend `fuzz_decode`, that passes every decoded frame through `ffkmp_frame_convert_display` and, when it carries Dolby Vision metadata, through `ffkmp_frame_dovi_compose_prepare` and `ffkmp_frame_dovi_compose_rows`.

**How to check:** `grep -l "open_input_io2\|dovi\|convert_display" native/kitecodec-c/fuzz/*.c` returns nothing.

### NAT-04. No published artifact or release zip carries the dav1d copyright notice that its BSD licence asks a binary distributor to reproduce

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | docs | low | Confirmed | high | KiteFFmpeg#185 |

**Same root as:** API-04. Post these as one issue.

**Where:** `KiteFFmpeg/.github/scripts/package-ffmpeg.sh:81`, `KiteFFmpeg/.github/scripts/package-ffmpeg.sh:121`, `KiteFFmpeg/kiteffmpeg/resources@jvm/META-INF/licenses/kiteffmpeg-ffmpeg/THIRD-PARTY.txt`, `KiteFFmpeg/kiteffmpeg/resources@android/META-INF/licenses/kiteffmpeg-ffmpeg/THIRD-PARTY.txt`, `KiteFFmpeg/kiteffmpeg/module.yaml:86`, `KiteFFmpeg/kiteffmpeg-android-natives/module.yaml:26`, `KiteFFmpeg/docs/licensing.md:93`

**What happens:** dav1d is compiled into every release zip, native klib, JVM jar and Android library, and its BSD-2-Clause licence asks anyone who redistributes it in binary form to reproduce its copyright notice, conditions and disclaimer. The project's own licensing page says the same of every permissive component. Yet the release zips carry only FFmpeg's licence files, the jar and the Android library carry FFmpeg's LGPL text and a `THIRD-PARTY.txt` that gives dav1d only as a web address, and the POM points at dav1d's moving `master` branch rather than the 1.5.4 tag that is built. Issue #185 covers the missing libiconv and IJG notices and FFmpeg's `LICENSE.md`; it does not mention dav1d.

**Evidence:**

```bash
# KiteFFmpeg/.github/scripts/package-ffmpeg.sh:81
legal_files=("COPYING.LGPLv2.1" "LICENSE.md")
# KiteFFmpeg/.github/scripts/package-ffmpeg.sh:121, in the same zip
if [ ! -f "${src}/lib/libdav1d.a" ]; then
```

```text
# THIRD-PARTY.txt (JVM copy)
dav1d: https://code.videolan.org/videolan/dav1d (BSD-2-Clause, no source obligation)
```

```yaml
# KiteFFmpeg/kiteffmpeg/module.yaml:86, the same at kiteffmpeg-android-natives/module.yaml:26
url: https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING
```

`docs/licensing.md:93` says: "Permissive components (BSD, zlib) only require you to reproduce their license text and copyright notice." The only licence files in the published resources are `COPYING.LGPLv2.1` and `THIRD-PARTY.txt`, and `BuildDav1dTask.kt` copies no licence file into the tree.

**Failure scenario:** A consumer who builds an app from the release zip or the Maven artifacts and follows the shipped texts has no copy of dav1d's notice to reproduce, so the app's attribution misses a component it ships. If dav1d's `master` ever changes its `COPYING`, the POM link stops describing the version that is embedded.

**Suggested fix:** Copy dav1d's `COPYING` from the pinned checkout into each release zip and into `META-INF/licenses/kiteffmpeg-ffmpeg/` of the jar and the Android library, and pin the POM link to the 1.5.4 tag.

**How to check:** `unzip -l` any release zip or the published jar and look for a dav1d licence file; there is none.

### NAT-05. The libass chain release ships FriBidi under the LGPL with no source attached and no provenance, unlike the FFmpeg release

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | docs | low | Confirmed | medium | KiteFFmpeg#187 |

**Where:** `KiteFFmpeg/.github/workflows/release-ass-chain.yml:137`, `KiteFFmpeg/.github/workflows/release-ass-chain.yml:165`, `KiteFFmpeg/.github/workflows/release-binaries.yml:221`, `KiteFFmpeg/.github/workflows/release-binaries.yml:231`, `KiteFFmpeg/scripts/package-ass-chain.sh:80`, `KiteFFmpeg/docs/licensing.md:34`, `KiteFFmpeg/docs/licensing.md:88`

**What happens:** The chain release distributes static FriBidi (LGPL-2.1-or-later) and a patched libass as object code. Its workflow attaches only the zips and their checksums, with no source tarball and no build provenance attestation, while the FFmpeg release workflow attaches the exact source tarball and attests the binaries. The licensing page argues for attaching FFmpeg's source because the offer then "does not depend on a third-party URL staying alive", and then points the chain's source at the upstream tags each zip's `CHAIN.txt` names. The licensing page also says an upstream tag "works in practice", so this is an inconsistency of care rather than a clear breach, which is why it stays low.

**Evidence:**

```yaml
# KiteFFmpeg/.github/workflows/release-ass-chain.yml:137 onwards, the whole release job
permissions:
  contents: write
...
for asset in assets/*; do
  ...
  gh release upload "$TAG" "$asset" --repo "$REPO"
```

No `attest-build-provenance` step and no source fetch appear anywhere in the file, against `release-binaries.yml:221` ("Fetch FFmpeg source tarball (source offer)") and `release-binaries.yml:231` (`actions/attest-build-provenance@v2`). `docs/licensing.md:88`: "Their source is the tagged upstream checkout that each zip's `CHAIN.txt` names".

**Failure scenario:** A product that embeds the chain's archives must offer FriBidi's source. If the upstream tag is moved or the repository is renamed, the offer that the release relies on points at nothing, and a consumer cannot verify that a chain zip came from this workflow.

**Suggested fix:** Attach `git archive` tarballs of the four pinned checkouts, with the libass patches applied, to the chain release, and add `actions/attest-build-provenance` for the zips, as the FFmpeg release does.

**How to check:** Read the release job of `release-ass-chain.yml` beside the one in `release-binaries.yml`.

### NAT-06. The FFmpeg recipe comments point at desktop GPL and base profiles that no longer exist

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | docs | low | Confirmed | high | KiteFFmpeg#222 |

**Where:** `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:422`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:423`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:61`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:122`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:236`

**What happens:** The comment above the shared filter list says that `drawtext` lives in `desktopBaseArgs` and that `eq` and `boxblur` live in `desktopGplArgs`. Neither function exists any more, and the GPL flavour is refused for every target, including desktop. The property KDoc at line 61 also still says desktop targets honour the GPL flavour. A reader is sent looking for a place where those filters supposedly ship. The user-facing docs (`docs/filtering.md:40`, `docs/platforms.md:99`) already say correctly that no profile carries them.

**Evidence:**

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:422
//   - drawtext needs libfreetype/libharfbuzz → desktopBaseArgs (Android links neither).
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:423
//   - eq and boxblur are `deps="gpl"` in FFmpeg's own configure → desktopGplArgs.
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:61
/** Licence flavour. Android and iOS are LGPL-only; only desktop targets honour [FFmpegLicense.GPL]. */
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:236
require(license != FFmpegLicense.GPL) {
```

A search of `build-plugins` finds no definition of `desktopBaseArgs` or `desktopGplArgs`.

**Failure scenario:** A contributor picking up #222 (drawtext and blur missing from the filter graph) reads this comment, concludes drawtext already ships on desktop through a base profile, and starts from a wrong picture of the build.

**Suggested fix:** Say in the comment that no profile carries `drawtext`, `eq` or `boxblur`, and why (drawtext needs FreeType and HarfBuzz, which no profile links; the other two are GPL-only in FFmpeg and the GPL flavour is refused), point at #222, and correct the KDoc at line 61.

**How to check:** `grep -rn "desktopBaseArgs\|desktopGplArgs" build-plugins` finds only the comment.

### SUB-03. HLS WebVTT subtitles ignore X-TIMESTAMP-MAP, so on most packaged streams every line shows at the wrong time

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | defect | high | Plausible | medium | none |

**Second check:** Upheld at high by the second reader, who also grepped the vendored FFmpeg, KitePlayer and KiteFFmpeg for `TIMESTAMP-MAP` and found nothing. One claim is narrowed: "most packaged streams" is too strong. The error is zero when the map's LOCAL already equals the MPEG-TS time, about 0.1 s with Shaka's default, about 10 s with Apple's tools, and arbitrary on live streams. Upstream FFmpeg and therefore mpv share this gap, while VLC, ExoPlayer, hls.js and Safari apply the map. Only text WebVTT segments are affected; fMP4 wvtt and stpp are not.

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:783-786`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/HlsTtml.kt:129`, FFmpeg 9.0.2 `libavformat/hls.c:2173-2241` and `libavformat/webvttdec.c:95` as vendored by `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt`

**What happens:** The HLS specification (RFC 8216, section 3.5) says each WebVTT subtitle segment carries a header line `X-TIMESTAMP-MAP=MPEGTS:m,LOCAL:l` that ties the cue clock to the video clock: local time `l` in the cues is media time `m / 90000` seconds. Nothing in either repository reads that line, and FFmpeg's HLS reader does not either: it opens each subtitle segment with the plain WebVTT reader, which skips the header block. The player then shifts every stream, cues included, by the one start time of the whole container, which is the video's first timestamp. Cues written on the WebVTT local clock therefore move by the video's start offset.

**Evidence:**

```c
// FFmpeg 9.0.2 libavformat/hls.c:2201-2203 (init_subtitle_context)
in_fmt = av_find_input_format("webvtt");
av_dict_copy(&opts, c->seg_format_opts, 0);
ret = avformat_open_input(&pls->ctx, current_segment(pls)->url, in_fmt, &opts);
```

```c
// FFmpeg 9.0.2 libavformat/webvttdec.c:95
        /* ignore header chunk */
```

```kotlin
// KitePlayer/kiteplayer-ffmpeg/src/.../KiteFFmpegSource.kt:786
fun mapTimestamp(micros: Long?): Pts? = micros?.let { Pts(it - containerStartMicros) }
```

```kotlin
// KitePlayer/kiteplayer-adaptive/src/.../HlsTtml.kt:129
if (looksLikeWebVtt(bytes)) return bytes.decodeToString()
```

A search of both repositories and of the vendored FFmpeg source for `TIMESTAMP-MAP` finds nothing. In `hls.c` the per-playlist `ts_offset` (lines 2989-3012) only steers the catch-up seek of a playlist that joins late; it never shifts a packet's timestamps. KitePlayer's own TTML stand-in passes a segment that is already WebVTT through untouched, and plain WebVTT subtitle playlists go straight to FFmpeg.

**Failure scenario:** A stream packaged the common way has MPEG-TS video starting at PTS 900000 (10 s) and subtitle segments headed `X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000`. A cue at local 00:00:12.000 belongs at position 12 s. The container starts at 10 s, so the player shows the cue at 2 s, ten seconds before the speech, and every line of the film is ten seconds early. Live streams, whose MPEG-TS clocks are usually hours in, are off by hours and show nothing. The project's HLS subtitle fixtures are made by FFmpeg, which writes no map and starts near zero, so the existing tests cannot see this. It is marked plausible because no stream with a nonzero map has been played.

**Suggested fix:** Read the map from each WebVTT segment and shift its cues by `m / 90000 - l` seconds, minding the 33-bit wrap of MPEG-TS time. The fix fits best in KiteFFmpeg as an `hls.c` patch beside `0004-hls-keep-the-subtitle-cue-on-screen`, which helps every consumer and needs no knowledge of the player; the alternative is a KitePlayer stand-in for WebVTT subtitle playlists that rewrites segment times, the way `HlsTtmlMediaIo` already serves TTML ones. Either way, add a fixture with a nonzero map and assert cue times against the picture.

**How to check:** Take any FFmpeg-made HLS fixture with WebVTT subtitles, offset the video by 10 s with `-output_ts_offset 10`, add `X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000` to each `.vtt` segment, and compare when the first cue appears with the same file played in Safari or hls.js.

### SUB-11. Captions on CC2, CC3 and CC4 of a broadcast can never be chosen

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/InVideoCaptions.kt:74-82`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegCaptionDecoder.kt:14-21`, `KiteFFmpeg/native/kitecodec-c/src/helpers_subtitle.c:103-107`

**What happens:** The engine lists one CEA-608 track, titled CC1, for the captions carried inside a picture. KiteFFmpeg opens FFmpeg's caption decoder on the first field only, so the second field, which carries CC3 and CC4 and in US broadcasts often the Spanish captions, is never decoded. CC2 is not offered either. Media3 lets a viewer pick any of CC1 to CC4. The 708 services are already separate tracks.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-core/src/.../InVideoCaptions.kt:74-79
internal fun captionStreamOf(video: PlayerStreamInfo): PlayerStreamInfo = PlayerStreamInfo(
    index = captionTrackIndex(video.index),
    ...
    title = "CC1",
```

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_subtitle.c:107
int rc = av_dict_set(&options, "data_field", "first", 0);
```

The decoder's own comment explains why the field is pinned: left on auto, it locks onto whichever field it sees first. In the vendored FFmpeg 9.0.2, `data_field` can choose the second field (`libavcodec/ccaption_dec.c:948-951`), which would reach CC3. The decoder does not track which channel within a field a command belongs to: it treats CC2's control byte `0x1c` like CC1's `0x14` and has no handling for CC2's row codes (`ccaption_dec.c:773-781`), so CC2 and CC4 need decoder work, not just an option.

**Failure scenario:** A US over-the-air recording carries English on CC1 and Spanish on CC3. A viewer who needs Spanish captions has nothing to select. With 708 present the Spanish may be offered as a service, but many MPEG-2 recordings carry 608 only.

**Suggested fix:** In KiteFFmpeg, let the caption decoder open take a field, so a consumer can decode the second field. In KitePlayer, list CC3 as a track once a picture carries second-field bytes, the way 708 services are adopted on first sight. CC2 and CC4 wait for a decoder that separates channels.

**How to check:** Read the cited lines, and play a recording known to carry CC3 to see only CC1 offered.

### WEB-07. The published web codec modules are built with an emscripten no test ever runs

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | test | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/.github/workflows/ci.yml:285-301`, `KiteFFmpeg/.github/workflows/publish.yml:182-227`, `KiteFFmpeg/.github/workflows/publish.yml:103-108`

**What happens:** The CI job for the web codec module installs whatever emscripten Homebrew offers that day, builds the web FFmpeg and links the modules with it, and runs the web tests against that build. The publish workflow runs the whole of CI first and then builds both web jars again with emsdk pinned at 6.0.11, checks only that the expected files are in the jars, and uploads them. So the bytes that go to Maven Central are never loaded by any test, and the compiler that was tested is not the one that shipped. The CI job also hardcodes the FFmpeg tag `n9.0.2` in its clone and cache key instead of using the workflow's `FFMPEG_VERSION`.

**Evidence:**

```yaml
# ci.yml:285-289
- name: Install emscripten
  id: emscripten
  run: |
    brew install emscripten
# ci.yml:301
git clone --depth 1 --branch n9.0.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
# publish.yml:184 and :197-198
EMSCRIPTEN_VERSION: "6.0.11"
./kotlin build -m kiteffmpeg-web -m kiteffmpeg-web-async
./kotlin check webPackage
```

The checks after it are `unzip -l` presence checks (publish.yml:208-227); no step of the publish job runs `scripts/web-tests/run.py`.

**Failure scenario:** Homebrew moves to an emscripten whose JSPI or Asyncify output differs from 6.0.11 in a way CLAUDE.md already warns about, such as how a parked export handles a 64-bit value. CI stays green on the Homebrew build while the 6.0.11 build that ships carries a defect no run executed, or the reverse, CI goes red for a Homebrew-only break while the release compiler is fine.

**Suggested fix:** Install the same pinned emsdk in the CI job from one shared `EMSCRIPTEN_VERSION`, use `${FFMPEG_VERSION}` for the clone and key, and in the publish job run `run.py --platform wasmJs --platform wasmJsBrowser --require-modules` against the jars just built, before the upload.

**How to check:** Read the two cited blocks side by side and search `publish.yml` for `run.py`.

### WEB-08. The shipped libass web module is compiled and linked by three different unpinned emscripten versions

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | risk | medium | Plausible | medium | none |

**Where:** `KiteFFmpeg/.github/workflows/release-ass-chain.yml:87-89`, `KitePlayer/.github/workflows/ci.yml:1172-1175`, `KitePlayer/.github/workflows/publish.yml:129-130`

**What happens:** The wasm32 libass chain archives (libass, HarfBuzz, FreeType, FriBidi) are compiled in KiteFFmpeg's release job with Homebrew's emscripten of that day. KitePlayer's CI links `kiteass.mjs` from those archives with the `latest` emsdk, and KitePlayer's publish links the shipped `kiteass.wasm` with Homebrew's emscripten of the release day. None of the three is pinned or recorded, so the libass module that ships is linked by a compiler CI never used, against objects built by possibly a third. KiteFFmpeg pins emscripten for its own web modules for exactly this reason.

**Evidence:**

```yaml
# KiteFFmpeg release-ass-chain.yml:87-89
- name: Install emscripten
  if: matrix.target == 'wasm32'
  run: brew install emscripten
# KitePlayer ci.yml:1173-1175
- uses: mymindstorm/setup-emsdk@6ab9eb1bda2574c4ddb79809fc9247783eaf9021 # v14
  with:
    version: latest
# KitePlayer publish.yml:129-130
- name: Install emscripten
  run: brew install emscripten
```

KiteFFmpeg's publish says it pins emsdk "so a later publish cannot ship different bytes from the same inputs" (publish.yml:179-182), and the KitePlayer CLAUDE.md records one break already caused by a newer emscripten clang in the HarfBuzz build.

**Failure scenario:** Emscripten changes its object format version or a system library layout between the day the chain was baked and the day KitePlayer publishes. The link either fails at release time only, or succeeds and the module misbehaves at run time on ASS tracks in browsers, with no CI run having used that combination. Two publishes of one KitePlayer commit can also ship different libass bytes.

**Suggested fix:** Pin one emsdk version in all three workflows, record it in the chain's build info and in `kiteplayer-libass/ass-chain.sha256`, and have KitePlayer's link step refuse a chain baked by a different emcc version. The KiteFFmpeg half (pinning and recording the version of the chain it releases) needs no knowledge of KitePlayer.

**How to check:** Read the three cited steps; `emcc --version` is not written anywhere in the chain's release assets.

### WEB-16. HDR video on the web canvas is tone mapped after it has been cut to 8 bits, so smooth gradients can band

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | opportunity | low | Confirmed | medium | none |

**Same root as:** VID-01, BRG-05. Post these as one issue.

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:1-13`, `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:244-261`, `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:298-311`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebRgba.kt:84`

**What happens:** The display conversion first turns a 10-bit PQ or HLG frame into 8-bit RGB with swscale's fast path, and then applies the transfer curve, the roll-off and the gamut fold to those 8-bit codes through 256-entry tables. In 8-bit PQ the range up to reference white gets only about 150 codes before the curve, so a slow sky or a fade loses steps the 10-bit source had. The file's header says this is deliberate, so that it matches a Kotlin software converter step for step, which is why this is listed as an opportunity rather than a defect. That header also names KitePlayer, which a KiteFFmpeg source comment should not need to.

**Evidence:**

```c
/* helpers_display.c:12-13 */
 *   BT.2390 EETF from a 1000 nit peak to 203 nit reference white, then gamma 2.2. The law runs on
 *   the 8-bit RGB bytes, as the Kotlin one does, so the two paths give the same picture. */
/* helpers_display.c:302 */
dst = kc_is_ycgco(src) ? kc_convert_ycgco(src, dst_fmt, &lay) : kc_sws_convert(src, dst_fmt, KC_SWS_FAST);
/* helpers_display.c:258-261, the table index is the 8-bit code */
last_r = p[lay->r];
...
r = eotf[last_r];
```

**Failure scenario:** A 10-bit HDR10 clip with a slow dark-to-bright gradient shows contour bands on the web canvas that a GPU renderer working at higher precision does not show.

**Suggested fix:** Convert to a 16-bit RGB intermediate, apply the tone map at that precision with a larger table or direct evaluation, then pack to 8 bits with optional dither, and measure the cost against the per-frame budget. Reword the header so it describes the law on its own terms without naming the consuming player.

**How to check:** Play a 10-bit PQ gradient clip through the web canvas renderer and compare with the same frame tone mapped from a 16-bit intermediate.

### API-01. A web page that serves the codec module of another KiteFFmpeg release is accepted and then read at the wrong offsets

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:55-78`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:343-345`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:186-196`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebIdentity.kt:15-46`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/wasm/KiteFFmpegWasm.kt:693-694`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Internals.jvm.kt:322-328`

**What happens:** On the web, FFmpeg lives in a separate module (`kite.mjs` and `kite.wasm`) that the page serves as plain files, while the Kotlin code that reads it comes from Maven. The two can come from different releases. `KiteFFmpegWeb.attach`, which `load` also goes through, only checks that ten Emscripten runtime names exist on the module. It never asks the module which version of the C layout it was built with, although the binding to ask exists (`kc_abi_version`) and has no caller. Every C struct is then read at the byte offsets of the committed layout file that the Kotlin side was generated with. The desktop JVM path compares the C ABI version before it goes on, and the asynchronous web path refuses a module whose bridge version differs, so the plain web path is the one place with no check at all. Even the identity report is read at those offsets, so `FFmpeg.identity` cannot catch a moved struct either.

**Evidence:**

```kotlin
// KiteFFmpegWeb.kt:73-75
val missing = missingRuntimeMethods(codecModule)
if (missing.isNotEmpty()) throw IncompleteModule(missing)
module = codecModule
```

```kotlin
// KiteFFmpegWeb.kt:344
@JsFun("""(m) => ["ccall","UTF8ToString","stringToUTF8","lengthBytesUTF8","addFunction","removeFunction","HEAP32","HEAPU8","_malloc","_free"].filter(k => m[k] === undefined).join(", ")""")
```

```kotlin
// KiteFFmpegWeb.kt:188-194, the asynchronous path does check
val version = asyncBridgeVersion(codecModule)
if (version != ASYNC_BRIDGE_VERSION) {
    throw FFmpegException(FFmpegError.Unsupported(0, "this codec module holds version $version of the asynchronous bridge, ..."))
```

```kotlin
// KiteFFmpegWasm.kt:693-694, bound but never called from the web loader
@JsFun("(m) => m._kc_abi_version()")
internal external fun kc_abi_version(module: JsAny): Int
```

`WebIdentity.kt` reads `cAbiVersion` itself out of the report at `ReportLayout.abiMajor` and `ReportLayout.abiMinor`, so a report whose struct moved answers with whatever sits at the old offsets. The KiteFFmpeg contributor notes say a wrong offset "reads the neighbouring field and answers something plausible".

**Failure scenario:** An app raises its `io.github.yuroyami:kiteffmpeg` dependency to a release whose C structs changed shape, and keeps the `kite.mjs` and `kite.wasm` it unpacked by hand beside its page last time. `KiteFFmpegWeb.load()` succeeds. Frames then come back with plausible but wrong widths, strides or timestamps, and reads may walk past the intended buffer inside wasm memory, with no error anywhere that names the cause.

**Suggested fix:** In `attach`, call `kc_abi_version(codecModule)` before installing the module, compare its major and minor with the version the generated layout belongs to, and throw `FFmpegException(FFmpegError.Unsupported(...))` naming both versions on a mismatch, the same way `attachAsyncRuntime` already refuses a bridge of another version. This is wholly inside KiteFFmpeg.

**How to check:** `grep -rn "kc_abi_version(" KiteFFmpeg/kiteffmpeg/src@wasmJs` finds only the declaration. Read `attach` at KiteFFmpegWeb.kt:55-78 beside the async check at 188-196.

### API-04. No artifact carries dav1d's copyright notice and licence text, which its BSD licence requires in every binary

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | KiteFFmpeg#185 |

**Same root as:** NAT-04. Post these as one issue.

**Where:** `KiteFFmpeg/kiteffmpeg/resources@android/META-INF/licenses/kiteffmpeg-ffmpeg/THIRD-PARTY.txt:1-9`, `KiteFFmpeg/kiteffmpeg/resources@jvm/META-INF/licenses/kiteffmpeg-ffmpeg/`, `KiteFFmpeg/docs/licensing.md:77`, `KiteFFmpeg/docs/licensing.md:93-94`, `KiteFFmpeg/NOTICE:97-98`

**What happens:** dav1d, the software AV1 decoder, is statically linked into every native, JVM and Android artifact. Its licence is BSD-2-Clause, whose second condition says a binary redistribution must reproduce the copyright notice, the list of conditions and the disclaimer. The licence folder the artifacts ship holds only FFmpeg's LGPL text and a short THIRD-PARTY.txt that names dav1d with a web address and the words "no source obligation". dav1d's copyright line and licence text appear nowhere in the repository outside the vendored trees, so no artifact carries them, and the Apple and other native klibs carry no licence file at all. KiteFFmpeg#185 is about libiconv and IJG notices, which is a different component, so this is a related but new gap.

**Evidence:**

```text
# THIRD-PARTY.txt (both the android and jvm copies)
This artifact embeds compiled FFmpeg libraries (LGPL-2.1-or-later) and dav1d
(BSD-2-Clause) inside its native library. KiteFFmpeg's own code is Apache-2.0.
...
dav1d: https://code.videolan.org/videolan/dav1d (BSD-2-Clause, no source obligation)
```

```text
# docs/licensing.md:93
Permissive components (BSD, zlib) only require you to reproduce their license text and copyright
notice.
```

`ls kiteffmpeg/resources@android/META-INF/licenses/kiteffmpeg-ffmpeg/` lists `COPYING.LGPLv2.1` and `THIRD-PARTY.txt` only, and the same for `resources@jvm`. A search for "dav1d authors" outside `native-libs` and build output finds nothing.

**Failure scenario:** An Android app builds its open source notices screen from the licence files of its dependencies, or copies `META-INF/licenses` as the docs suggest. It shows FFmpeg's LGPL text but not dav1d's notice, so every app that ships KiteFFmpeg is out of compliance with dav1d's licence without knowing it, and the docs tell it the reproduction is its job while giving it nothing to reproduce.

**Suggested fix:** Copy dav1d's `COPYING` from the pinned dav1d checkout at bake time into `META-INF/licenses/kiteffmpeg-ffmpeg/` of the JVM and Android artifacts, so it follows the version, quote the same text in `NOTICE` for native consumers, and reword THIRD-PARTY.txt to say the notice must be reproduced.

**How to check:** `ls KiteFFmpeg/kiteffmpeg/resources@*/META-INF/licenses/kiteffmpeg-ffmpeg/` and read THIRD-PARTY.txt.

### API-14. On a processor the desktop jar does not carry, it loads the x64 Linux library and fails with a confusing error

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | low | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@jvm/io/github/yuroyami/kiteffmpeg/JniLibrary.jvm.kt:69-79`, `KiteFFmpeg/docs/platforms.md:35`

**What happens:** The lookup of the bundled native library maps every processor that is not `aarch64` or `arm64` to `x64`, and every system that is not a Mac or Windows to `linux`. A JVM on 32-bit ARM, RISC-V, ppc64le, s390x or 32-bit x86 Linux therefore finds the `linux-x64` manifest, extracts that library and fails inside `System.load` with a low-level loader message about a temp file. The clear message the code already has for a platform the jar does not carry, which names the override property and which `docs/platforms.md` promises, is reached only when no manifest is found.

**Evidence:**

```kotlin
// JniLibrary.jvm.kt:72-78
val os = when {
    "mac" in name || "darwin" in name -> "macos"
    "win" in name -> "windows"
    else -> "linux"
}
val cpu = if (arch in setOf("aarch64", "arm64")) "arm64" else "x64"
```

**Failure scenario:** A desktop app runs on a Raspberry Pi with a 32-bit userspace (`os.arch` is `arm`). The first FFmpeg call fails with "wrong ELF class: ELFCLASS64" about a file in the temp directory, which reads like a broken jar instead of "this platform is not supported, supply one with -Dkiteffmpeg.jni.path".

**Suggested fix:** Map only known names (`amd64` and `x86_64` to `x64`, `aarch64` and `arm64` to `arm64`) and throw the existing friendly `UnsatisfiedLinkError` for anything else, naming `os.name` and `os.arch`. Intel Macs and Windows on ARM already get the friendly error, because no `macos-x64` or `windows-arm64` manifest exists.

**How to check:** Read the lines above, or run any JVM test with `-Dos.arch=riscv64`.

### API-15. What plays end to end on each platform today, and where the gaps are

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | medium | Confirmed | high | none |

**Where:** `KitePlayer/README.md:47-58`, `KitePlayer/README.md:478-494`, `KitePlayer/kiteplayer/module.yaml:5`, `KitePlayer/kiteplayer-audioviz/module.yaml:5`, `KitePlayer/kiteplayer-compose/module.yaml:5`, `KitePlayer/kiteplayer-core/module.yaml:7-28`, `KiteFFmpeg/kiteffmpeg/module.yaml:3-17`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:236-258`

**What happens:** A capability matrix read from the module platform lists, the README and the code. Android has the full stack: FFmpeg with MediaCodec, Compose and View, HDR in the view, HLS and DASH, background, picture-in-picture and the visualiser. iOS has the full stack on `iosArm64` and the simulator. Native macOS has the engine, Metal and AppKit, picture-in-picture and Now Playing, but no Compose UI and no visualiser. The desktop JVM has Compose and AWT, VideoToolbox on Apple silicon only, an untried Direct3D 11 route on Windows (#101), no HDR output, no Intel Mac (KiteFFmpeg#212), software-only video on Linux (KiteFFmpeg#217) and sound never heard on real Linux or Windows (#639). On the web the main-thread player plays only bytes already in memory, network media plays only through the worker player, the codec set is small (H.264, HEVC and VP9 video, no AV1, only mov, matroska, mp3, flac, hls and webvtt demuxers, KiteFFmpeg#211), there is no visualiser, and Compose is unreleased. `js` is a placeholder. Native Linux and Windows carry the engine and FFmpeg only, by decision. tvOS and watchOS build the engine modules only, and KiteFFmpeg has no target there.

**Evidence:** `kiteplayer` platforms are `[ jvm, android, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, linuxArm64, mingwX64, js, wasmJs ]`, `kiteplayer-audioviz` is `[ jvm, android, iosArm64, iosSimulatorArm64 ]`, `kiteplayer-compose` is `[ jvm, android, iosArm64, iosSimulatorArm64, wasmJs ]`, `kiteplayer-core` adds tvOS, watchOS and Android Native, and KiteFFmpeg lists no tvOS or watchOS target.

**Failure scenario:** Not a failure. The gaps an app meets first, roughly by value, are no Apple TV, no network playback inside a Compose web app, no visualiser on the Mac or the web, and no HDR or Intel Mac on the desktop JVM. The next opportunities take up the ones no open issue covers.

**Suggested fix:** Keep a capability table generated from the module platform lists in `docs/`, so the README tables cannot drift from the build.

**How to check:** `grep -n "platforms" */module.yaml` in both repositories.

### API-16. Apple TV cannot play anything, because the media library has no tvOS build

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/module.yaml:3-17`, `KitePlayer/kiteplayer-core/module.yaml:14-15`, `KitePlayer/kiteplayer-ffmpeg/module.yaml:4-13`, `KitePlayer/kiteplayer-output/module.yaml:4-13`, `KitePlayer/README.md:57-58`, `KitePlayer/README.md:493-494`

**What happens:** The README says the engine "also builds for Linux and Windows native, tvOS and watchOS, where your app supplies the missing parts". On tvOS the missing part is the whole demuxer and decoder, because KiteFFmpeg has no `tvosArm64` or `tvosSimulatorArm64` target, and `kiteplayer-ffmpeg`, `kiteplayer-output` and `kiteplayer` have no tvOS variant. Under the decision that FFmpeg is the one media truth, an app has no sanctioned backend to supply there. Apple TV is the screen where long-form video is watched most, and the players KitePlayer measures itself against (VLC, Infuse) ship on it.

**Evidence:** KiteFFmpeg's module platform list stops at the iOS, macOS, Linux, Windows, Android Native and web targets; a search for tvOS in KiteFFmpeg finds only a Dokka mapping. The Apple output code uses CoreAudio, AVSampleBufferDisplayLayer, Metal and MediaPlayer, all of which tvOS has.

**Failure scenario:** An app that uses KitePlayer on iPhone for MKV with ASS subtitles cannot offer the same on Apple TV and has to keep a second engine there.

**Suggested fix:** Add tvOS FFmpeg trees to KiteFFmpeg (the iOS profile with the tvOS minimum version and VideoToolbox decode), then add tvOS to `kiteplayer-ffmpeg`, `kiteplayer-output` and `kiteplayer`, with a UIKit view and focus-engine controls. Meanwhile, make the README sentence say plainly that nothing plays on tvOS or watchOS today.

**How to check:** `grep -n tvos KiteFFmpeg/kiteffmpeg/module.yaml` returns nothing.

### API-19. Files on a home network share cannot be played without a web server in front of them

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | low | Confirmed | medium | none |

**Where:** `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:387`, `KiteFFmpeg/docs/platforms.md:95`

**What happens:** FFmpeg's protocol list is `file,fd,pipe,data,http,tcp,udp,rtp,rtmp`. There is no `ftp`, which FFmpeg has built in with no extra library, and KitePlayer has no SMB, NFS or SFTP reader. VLC, Kodi, Infuse and nPlayer all play straight from SMB shares, where most home media libraries sit.

**Evidence:**

```kotlin
// BuildFFmpegTask.kt:387
"--enable-protocol=file,fd,pipe,data,http,tcp,udp,rtp,rtmp",
```

`grep -rli "smb://"` over KitePlayer's Kotlin sources finds nothing.

**Failure scenario:** A user points the app at `smb://nas/Movies/film.mkv` and gets a source-unavailable error; the app author has to write an SMB client and feed it through `MediaIo`.

**Suggested fix:** Enable FFmpeg's `ftp` protocol in KiteFFmpeg (no dependency). In KitePlayer, an optional pure Kotlin SMB2/3 resolver module discovered like the network module would fit the "Kotlin first" decision.

**How to check:** Read the protocol line.

### GKF-01. A remuxed iPhone HEVC video is retagged hev1, so the MP4 or MOV it becomes no longer plays in Apple's players

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | high | Confirmed | high | none |

**Second check:** Spot checked by the coordinator after the gap lane: `helpers_codecpar.c:83-89` zeroes `codec_tag` after every copy, and no other Kotlin or C source in KiteFFmpeg sets a tag, as described. The claims about the vendored `mux.c`, `movenc.c` and `ffmpeg_mux_init.c` were not re-read here, because FFmpeg's source is not in the container; check them against the pinned release.

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_codecpar.c:83-89`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSink.native.kt:309`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSink.jvm.kt:248`, `KiteFFmpeg/native/kitecodec-jni/kj_format.c:691-692`

**What happens:** Every copied stream has its codec tag set to zero before it reaches the muxer. The muxer then takes the first tag its table lists for the codec. For HEVC that is `hev1`, in both the mp4 and the mov muxer. So a source tagged `hvc1`, which is every iPhone recording and most HEVC made for Apple devices, comes out tagged `hev1`, and a Dolby Vision `dvh1` track comes out as plain `hev1`. QuickTime, Safari, Photos and AVPlayer will not play an `hev1` track. The code comment says ffmpeg.c does the same thing. It does not: ffmpeg.c keeps the source tag whenever the output muxer maps that tag to the same codec. Nothing else in KiteFFmpeg sets a tag (no Kotlin or C source mentions `codec_tag` or `hvc1` apart from this helper), so a caller cannot work around it.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_codecpar.c:83-89
/* Stream-copy parameter clone. codec_tag is container-specific (mp4 'avc1' means nothing
   to mkv), and zeroing it lets the destination muxer pick its own tag; ffmpeg.c does the same. */
KC_API int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src) {
    if (!dst || !src) return AVERROR(EINVAL);
    int rc = avcodec_parameters_copy(dst, src);
    if (rc >= 0) dst->codec_tag = 0;
    return rc;
}
```

Every copy stream goes through it. Native: `MediaSink.native.kt:309` `check0(ffkmp_codecpar_copy_for_mux(outPar, sourcePar), ...)`. JVM and Android: `MediaSink.jvm.kt:248` `Internals.codecParCopy(...)`, which is `kj_codecpar_copy` at `kj_format.c:691-692`, which calls `ffkmp_codecpar_copy_for_mux`.

In the vendored FFmpeg 9.0.2, a zero tag is filled from the format's table:

```c
// libavformat/mux.c:323-324
            } else
                par->codec_tag = av_codec_get_tag(of->p.codec_tag, par->codec_id);
```

and the first HEVC entry in both tables is `hev1`:

```c
// libavformat/movenc.c:9306-9308 (codec_mp4_tags)
    { AV_CODEC_ID_HEVC,            MKTAG('h', 'e', 'v', '1') },
    { AV_CODEC_ID_HEVC,            MKTAG('h', 'v', 'c', '1') },
    { AV_CODEC_ID_HEVC,            MKTAG('d', 'v', 'h', '1') },
// libavformat/isom_tags.c:124 (ff_codec_movvideo_tags)
    { AV_CODEC_ID_HEVC, MKTAG('h', 'e', 'v', '1') },
```

What ffmpeg.c actually does keeps the source tag:

```c
// fftools/ffmpeg_mux_init.c:1031-1036
    if (!codec_tag) {
        const struct AVCodecTag * const *ct = mux->fc->oformat->codec_tag;
        unsigned int codec_tag_tmp;
        if (!ct || av_codec_get_id (ct, par->codec_tag) == par->codec_id ||
            !av_codec_get_tag2(ct, par->codec_id, &codec_tag_tmp))
            codec_tag = par->codec_tag;
    }
```

The muxer also changes how it writes the stream by the tag: `movenc.c:1646`, `1662` and `7137` test `track->tag == MKTAG('h','v','c','1')`, so the hvcC box and the in-band parameter set filtering follow the wrong tag too.

**Failure scenario:** An app calls `Remuxer.remux` to rewrap `IMG_0001.MOV` into `out.mp4`, or runs `Transcoder` with video copy on. The output plays in VLC and Chrome, but QuickTime, Safari and the iOS Photos app report it as unplayable, and a Dolby Vision iPhone clip loses its `dvh1` sample entry as well. The same file run through `ffmpeg -c copy` keeps `hvc1` and plays.

**Suggested fix:** Do what ffmpeg.c does. Give the copy helper the output format (or add a sibling that takes the output `AVFormatContext`), and after `avcodec_parameters_copy` clear the tag only when the output format has a tag table, `av_codec_get_id(table, tag)` is not the stream's codec id, and the table has some other tag for that codec. Add a contract test that remuxes an `hvc1` MP4 into MP4 and MOV and reads the sample entry back.

**How to check:** Remux any `hvc1` HEVC MP4 with `Remuxer.remux` and run `ffprobe -show_streams out.mp4 | grep codec_tag_string`. It prints `hev1`, while `ffmpeg -i in.mp4 -c copy ref.mp4` gives `hvc1`.

### GKF-02. A trimmed remux or copy transcode ends its sound early, because every stream stops at the first picture packet past the end

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:127-128`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Remuxer.native.kt:135-136`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt:346`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Transcoder.native.kt:376`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/OutputOrigin.kt:139-142`

**What happens:** The copy loop throws `StopDemux` as soon as the lead stream, which is the video when there is one, gives a packet whose decode time is past `endMicros`. That ends the read for every stream at once. A demuxer does not hand out packets in time order across streams. FFmpeg's MP4 reader takes samples in file order while the streams are within one second of each other, and in file order alone on an input that cannot seek. Sound packets that belong before the end but sit later in the file than the first late picture are never read, so the sound of the cut stops before its picture: by up to about a second on an ordinary MP4, and by more on a badly interleaved file or a byte source that cannot seek. Packets of other streams that are past the end are dropped while the loop waits for the lead, which is right, but the lead's own stop leaves nothing to wait for the others.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:127-128
                        if (micros != Long.MIN_VALUE && micros > endMicros) {
                            if (info.index == leadIndex) throw StopDemux()
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt:346
                                        pastEnd -> if (info.index == lead.index) throw StopDemux()
// KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Remuxer.native.kt:135-136
                        if (micros != Long.MIN_VALUE && micros > endMicros) {
                            if (info.index == leadIndex) throw StopDemux()
```

The library already knows about the reordering in another place:

```kotlin
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/OutputOrigin.kt:139-141
         * that has shown nothing. FFmpeg's MP4 reader takes samples in file order while the
         * streams are within a second of each other, so a stream can deliver its first packet up
         * to that much behind another, ...
```

And the vendored reader picks the next sample like this:

```c
// libavformat/mov.c:11616-11630
    int no_interleave = !mov->interleaved_read || !(s->pb->seekable & AVIO_SEEKABLE_NORMAL);
    ...
            if (!sample || (no_interleave && current_sample->pos < sample->pos) ||
                ((s->pb->seekable & AVIO_SEEKABLE_NORMAL) &&
                 ((msc->pb != s->pb && dts < best_dts) || (msc->pb == s->pb && dts != AV_NOPTS_VALUE &&
                 ((dtsdiff <= AV_TIME_BASE && current_sample->pos < sample->pos) ||
```

ffmpeg.c instead finishes each output stream on its own and keeps reading until every stream is finished.

**Failure scenario:** `Remuxer.remux` with `endMicros = 60_000_000` on `talk.mp4`, where the video chunk covering 59.6 s to 60.4 s sits before the audio chunk covering 59.5 s to 60.5 s. The first video packet past 60 s is read, `StopDemux` fires, and the sound from about 59.5 s on is never read. The clip's sound ends half a second before its picture, and a clip joined after it has a gap. `Transcoder` with a copied video stream and an end bound stops the same way.

**Suggested fix:** Stop each stream at its own end instead of stopping the reader at the lead's. Mark a stream finished when its own packet passes `endMicros`, keep reading while any selected audio or video stream is unfinished, and bound the extra read (for example by the one second window FFmpeg's reader uses, plus a byte or packet limit) so a stream that never reaches the end cannot make a short cut read the whole file.

**How to check:** Cut a phone MP4 with `Remuxer.remux(..., endMicros = 60_000_000)` and compare the two durations with `ffprobe -show_entries stream=codec_type,duration out.mp4`. The audio duration falls short of the video one, while `ffmpeg -t 60 -c copy` gives two that match.

### GKF-03. An MP4 written to a byte sink with the faststart option the docs suggest loses its index and cannot be played at all

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:90-91`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:104`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1512-1544`

**What happens:** The path overload of `MediaSink.open` gives `"movflags" to "+faststart"` as its example option, and the byte-sink overload says its options are "as for the path variant". Faststart makes FFmpeg's MP4 trailer open the output again, by its address, for reading, so it can move the data along and put the index in front. A byte-sink output has no address (FFmpeg sets it to the empty string) and its I/O context has no read callback, so the reopen fails. The trailer then returns before it writes the `moov` box, and the bytes already written form a file with no index. The header check that refuses a non-seekable sink does not help: this sink is seekable, and the failure only comes at the very end.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:90-91
         * @param options muxer private options applied before the header is written, such as
         *                `"movflags" to "+faststart"` (mp4 web-ready) or `"movie_timescale"`
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:104
         * @param options muxer private options, as for the path variant
```

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1520-1524
    int rc = avformat_alloc_output_context2(&c, NULL, format, NULL);
    ...
        ? avio_alloc_context(buffer, KC_IO_BUFFER_SIZE, 1, writer, NULL, kc_io_write_packet,
                             seek_fn ? kc_io_write_seek : NULL)
```

The vendored FFmpeg then does:

```c
// libavformat/mux.c:210   if (!s->url && !(s->url = av_strdup(""))) {
// libavformat/mux_utils.c:90-94
    ret = s->io_open(s, &read_pb, s->url, AVIO_FLAG_READ, NULL);
    if (ret < 0) {
        av_log(s, AV_LOG_ERROR, "Unable to re-open %s output file for shifting data\n", s->url);
        goto end;
    }
// libavformat/movenc.c:9149-9153
        if (mov->flags & FF_MOV_FLAG_FASTSTART) {
            ...
            res = shift_data(s);
            if (res < 0)
                return res;
```

KiteFFmpeg installs no `io_open` on an output context (the only `io_open` it sets is the nested opener of an input), so the default open of an empty address is what runs.

**Failure scenario:** An Android app records to a seekable `MediaByteSink` over a `FileChannel` from the Storage Access Framework and passes `movflags=+faststart` to get a web-ready file. Encoding runs for the whole recording, `MediaSink.close` throws at the trailer, and the document holds an `mdat` with no `moov`. The recording cannot be played or easily recovered.

**Suggested fix:** Refuse `faststart` (and any other movflag that needs a second pass over the output) on a byte sink when it is opened, with a typed `InvalidArgument` that names the reason, and correct the byte-sink KDoc so it does not inherit the faststart example. Supporting it instead would need a read callback on the output bridge and an `io_open` that serves the output's own bytes back.

**How to check:** Write a short MP4 through `MediaSink.open(sink = seekableByteSink, format = "mp4", options = mapOf("movflags" to "+faststart"))`. Close fails, the FFmpeg log shows "Unable to re-open  output file for shifting data", and `ffprobe` on the bytes reports "moov atom not found".

### GKF-04. Trimming a song or audiobook that carries cover art reads the whole input, because the cover picture becomes the stream that ends the cut

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | low | Confirmed | high | GKF-02 |

**Where:** `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:106`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Remuxer.native.kt:108`

**What happens:** The remux takes the first selected video stream as the one whose end stops the read. When no stream list is given, every stream that is not of unknown type is selected, cover art included. A cover picture is a video stream with one packet at the start, so it never passes `endMicros`. Every sound packet after the end is then read and dropped until the end of the file. On a byte source backed by the network, that downloads the whole file. `TrackSelector.selectVideo` already skips attached pictures for this reason, but this line does not use it.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:80
                source.streams.filter { it.type != MediaType.Unknown }
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:106
            val leadIndex = (selected.firstOrNull { it.type == MediaType.Video } ?: selected.first()).index
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:128
                            if (info.index == leadIndex) throw StopDemux()
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/TrackSelector.kt:42
        streams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
```

The native copy at `Remuxer.native.kt:108` is the same line.

**Failure scenario:** `Remuxer.remux` from `book.m4b` to `sample.m4a` with `endMicros = 30_000_000`, on a ten hour audiobook with a cover. The 30 second sample takes as long as reading ten hours of packets, and through a `MediaByteSource` backed by HTTP it downloads the whole book.

**Suggested fix:** Choose the lead the way `TrackSelector` does: the first video stream that is not an attached picture, else the first audio stream, else the first selected stream. With per-stream ends as in GKF-02, the lead is no longer needed at all.

**How to check:** Trim the first 10 seconds of a long MP3 or M4A that has embedded cover art, and log how many packets the loop reads: it reads every packet of the file instead of stopping near 10 seconds.

### GKF-05. A playlist read from a byte source without an opener can make FFmpeg fetch plain http addresses and local media files on its own

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | low | Plausible | medium | none |

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1399-1412`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSource.kt:417-419`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:383-387`

**What happens:** With no `nestedOpener`, the byte-source open leaves FFmpeg's own `io_open` in place and sets no `protocol_whitelist` on the context. FFmpeg copies a whitelist from the input only when the I/O context carries one, and `avio_alloc_context` gives it none. So the HLS reader opens every segment, key and variant address through any protocol this build has. Plain FFmpeg protects this case: a playlist fetched through its http protocol inherits http's default whitelist, which has no `file`, so a network playlist cannot read local files. A playlist handed over as bytes loses that protection. An app that fetched untrusted bytes with its own HTTPS client gets cleartext requests that skip its proxy, certificate pinning and cleartext policy, to any host the playlist names, and reads of local files whose names have a media extension. The KDoc says FFmpeg opens those addresses with its own protocols, but not that this reaches local files, and the build comment reasons only about inputs opened from a file.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1399-1412
    c->pb = pb;
    c->flags |= AVFMT_FLAG_CUSTOM_IO;
    ...
    if (opener) {
        bridge->default_io_open = c->io_open;
        ...
        c->io_open = kc_io_open_nested;
```

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:383-387
        // A wider list does not widen what a playlist reaches: FFmpeg's HLS reader opens only
        // file, http and data, and an input opened from a file may reach only file, crypto and
        // data unless DemuxOptions.protocolWhitelist names more.
        "--enable-network",
        "--enable-protocol=file,fd,pipe,data,http,tcp,udp,rtp,rtmp",
```

In the vendored FFmpeg, `demux.c:270-271` copies the whitelist only from `s->pb->protocol_whitelist`; `hls.c:717-730` accepts `file` (with an extension check) and `http`; `http.c:2250` gives http the default whitelist `"http,https,tls,rtp,tcp,udp,crypto,httpproxy,data"`, which is the protection a byte source does not get. No KiteFFmpeg C source sets `protocol_whitelist`; only `DemuxOptions.protocolWhitelist` passes one, and only when the caller asks.

**Failure scenario:** A consumer downloads an m3u8 over HTTPS from a link a user pasted and opens it with `MediaSource.open(io = bytes, url = link)`. The playlist lists segments at `http://192.168.1.1/admin/backup.ts` and `file:///sdcard/DCIM/Camera/private.mp4`. FFmpeg requests the router address in cleartext from inside the app's network, and plays the local video as part of the stream.

**Suggested fix:** When a byte source is opened without an opener and the caller passed no `DemuxOptions.protocolWhitelist`, set the context's `protocol_whitelist` to `"data"`, so a nested address fails with a typed refusal that says to pass a `nestedOpener`. State the default in the KDoc at `MediaSource.kt:417-419`.

**How to check:** Serve a two line m3u8 whose segment is `file:///tmp/x.ts` (an existing MPEG-TS file) from memory through `MediaSource.open(io = ..., url = "https://example.com/a.m3u8")` and read packets: the local file plays.

### GKF-06. Closing a Dolby Vision composition while another thread still composes a band frees the pictures under it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | low | Plausible | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:308-309`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:353-370`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Frame.native.kt:539-545`, `KiteFFmpeg/native/kitecodec-jni/kj_frame.c:352-364`

**What happens:** `DolbyVisionComposition` is a public class built so that bands of rows run on several threads, and `composeRows` runs without the frames' locks on purpose. `close` and `finish` free the source and output frames with nothing that waits for bands still running. The KDoc asks the caller to wait, so this is a stated contract, but breaking it gives a use after free inside the C composer instead of a typed error. Everywhere else the library uses its handle table and per-wrapper locks to turn this kind of mistake into an exception. On the JVM the handle lookup happens once at the start of the band, so a close that lands after the lookup is not caught.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:308-309
 * Threading. [composeRows] calls may run at the same time on different threads, as long as their
 * rows do not overlap. [finish] and [close] must wait until every [composeRows] call has returned.
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:365-370
    override fun close() {
        output?.close()
        output = null
        source?.close()
        source = null
    }
```

```c
// KiteFFmpeg/native/kitecodec-jni/kj_frame.c:352-364
/* Runs without the Kotlin frame lock, so bands of one composition can run on several threads; the
 * composition owns both frames, and the table lookups are safe from any thread. */
    kc_frame *src = (kc_frame *)kj_handle_get(env, src_token, KJ_KIND_FRAME);
    ...
    return (jint)ffkmp_frame_dovi_compose_rows(src, dst, (int)row_start, (int)row_end);
```

`Frame.native.kt:540-545` passes `source.nativeFrame` and `output.nativeFrame` straight to C. `source` and `output` are plain `var`s with no count of bands in flight.

**Failure scenario:** An app splits a 4K frame into eight bands on a thread pool. One band throws `FFmpegException` for a bad RPU, and the coordinating code closes the composition in a `finally` while the other seven bands are still inside `ffkmp_frame_dovi_compose_rows`. Those bands read and write freed frame buffers, which corrupts the heap or crashes a later decode.

**Suggested fix:** Count bands in flight with an atomic counter. Make `close` and `finish` wait for it to reach zero (or refuse with `IllegalStateException` while it is not zero), and make `composeRows` refuse once closing has begun. The frames' own locks can stay out of the hot path.

**How to check:** Read `DolbyVision.kt:320-370`: nothing between `composeRows` and `close` coordinates the two, and the C side holds raw pointers for the whole band.

### GKF-07. Every FFmpeg log line from a decoder worker thread attaches that thread to the JVM and detaches it again

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | low | Plausible | medium | none |

**Where:** `KiteFFmpeg/native/kitecodec-jni/kj_abi.c:41-73`, `KiteFFmpeg/native/kitecodec-c/src/helpers_log.c:83-88`

**What happens:** The JNI log bridge attaches any thread the JVM does not know, calls the Kotlin sink, and detaches it, for every line. FFmpeg's frame-threaded and slice-threaded decoders log damage reports from their own worker threads, often many lines a second on a broken stream (for example HEVC's "Could not find ref with POC" at error level). Each attach creates and tears down a `java.lang.Thread` peer, which on Android is a full ART thread registration. With a sink installed at error or warning level, the log path can cost more than the decoding it reports on and can hold up the decoder's worker threads. No sink is installed by default, so this only bites an app that turns logging on, which is exactly when it is debugging a damaged stream.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-jni/kj_abi.c:50-57 and 72
    if ((*kj_log_vm)->GetEnv(kj_log_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
#ifdef __ANDROID__
        if ((*kj_log_vm)->AttachCurrentThread(kj_log_vm, &env, NULL) != JNI_OK) return;
#else
        if ((*kj_log_vm)->AttachCurrentThread(kj_log_vm, (void **)&env, NULL) != JNI_OK) return;
#endif
        attached = 1;
    }
    ...
    if (attached) (*kj_log_vm)->DetachCurrentThread(kj_log_vm);
```

`ffkmp_log_set_sink` at `helpers_log.c:83-88` installs the forwarder as FFmpeg's log callback for every thread. The library already keeps per-thread state that lives until the thread ends with a pthread key (`kc_convert.h:94-103`, the swscale cache), so the pattern is at hand.

**Failure scenario:** An Android app sets `FFmpegLog` to warnings for diagnostics and plays an HEVC stream with packet loss. Each decoder worker thread logs several lines for every damaged frame, every line attaches and detaches a JVM thread, playback stutters, and the app's thread list churns with short-lived threads.

**Suggested fix:** Attach a native thread once and keep it attached, detaching it from a pthread key destructor when the thread exits. Or queue lines from threads the JVM does not know to one attached forwarding thread.

**How to check:** Install a sink at warning level, decode a damaged HEVC file with frame threading on, and count `AttachCurrentThread` calls (or watch thread creation in Android Studio's profiler): there is one per logged line.
