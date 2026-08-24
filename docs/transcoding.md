# Transcoding A/V in one call

Use `Transcoder.transcode` to run a complete audio and video pipeline from a single suspending call. It opens the input, demuxes it once, decodes the selected streams, optionally filters them, encodes with the codecs you ask for, and interleaves everything back into a valid output container. Demuxing means splitting a container file into its separate streams.

## Basic workflow

```kotlin
import io.github.yuroyami.kitecodec.Transcoder
import io.github.yuroyami.kitecodec.VideoEncoderSpec
import io.github.yuroyami.kitecodec.AudioEncoderSpec
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.Rational

Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        // mpeg4 is the dependency-free baseline present in every FFmpeg profile.
        // See "Choosing a codec" below before hard-coding anything else.
        codec = CodecId("mpeg4"),
        width = 320, height = 180,
        frameRate = Rational(30, 1),
        bitrateBps = 1_500_000,
    ),
    videoFilter = "scale=320:180,hue=b=0.1,vignette,format=yuv420p",
    audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),
    audioFilter = "volume=0.8",
    onProgress  = { progress -> println("encoded ${progress.framesEncoded} frames") },
)
```

The call opens the file through libavformat, demuxes it **once**, routes packets to per-stream
libavcodec decoders, pushes video frames through a libavfilter graph, resamples and chunks audio
through a second graph, encodes with the codecs you named, and interleaves both streams into the
output as they are produced. There is no `ffmpeg` subprocess. Kotlin/Native reaches the opaque C
helpers through cinterop; JVM and Android actuals reach them through JNI. Memory stays constant
regardless of how long the input is.

`transcode` is a `suspend fun`, so call it from a coroutine. It suspends until the whole file is written, and it honors cancellation at the demux loop.

!!! note "Requires FFmpeg present at link time"
    KiteCodec binds to FFmpeg's libav\* libraries. The library is consumed by building from source today; install FFmpeg first (`brew install ffmpeg` on macOS, `apt install` on Linux) or use a vendored static build. See [Platform support](platforms.md) for what runs where.

## The option surface

The full signature, with every default:

```kotlin
suspend fun transcode(
    input: String,
    output: String,
    spec: VideoEncoderSpec? = null,
    videoFilter: String? = null,
    videoCopy: Boolean = false,
    audioSpec: AudioEncoderSpec? = null,
    audioFilter: String? = null,
    audioCopy: Boolean = false,
    subtitleCopy: Boolean = false,
    startMicros: Long = 0L,
    endMicros: Long = Long.MAX_VALUE,
    metadata: Map<String, String> = emptyMap(),
    onProgress: ((TranscodeProgress) -> Unit)? = null,
)
```

| Parameter | Default | What it does |
|---|---|---|
| `input` | required | Input file path. |
| `output` | required | Output file path. The container is chosen from the extension. |
| `spec` | `null` | Video encoder spec. `null` (with `videoCopy` false) produces audio-only output (any input video is dropped). |
| `videoFilter` | `null` | Filter graph for the video stream. `null` passes decoded frames straight to the encoder. Requires `spec`. |
| `videoCopy` | `false` | Stream-copy video instead of re-encoding (`-c:v copy`). A stream copy moves the encoded packets across unchanged, so it is bit-exact and near-free. Mutually exclusive with `spec` and `videoFilter`. Trimming a copied video stream is keyframe-snapped. |
| `audioSpec` | `null` | Audio encoder spec. `null` (with `audioCopy` false) drops audio. |
| `audioFilter` | `null` | Filter chain for the audio stream. `null` plain resamples and reformats to what the encoder needs. |
| `audioCopy` | `false` | Stream-copy audio instead of re-encoding. Mutually exclusive with `audioSpec` and `audioFilter`. |
| `subtitleCopy` | `false` | Stream-copy every subtitle stream the output container accepts. |
| `startMicros` | `0L` | Trim start, in microseconds. |
| `endMicros` | `Long.MAX_VALUE` | Trim end, in microseconds. The default means no upper bound. |
| `metadata` | `emptyMap()` | Container tags written into the output header. |
| `onProgress` | `null` | Progress callback, fired periodically during the run. |

## Video encoding

`VideoEncoderSpec` describes the output video stream:

```kotlin
import io.github.yuroyami.kitecodec.VideoEncoderSpec
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.PixelFormat
import io.github.yuroyami.kitecodec.Rational

val spec = VideoEncoderSpec(
    codec = CodecId.Libx264,            // codec selector
    width = 1280,
    height = 720,
    pixelFormat = PixelFormat.Yuv420p,  // default
    frameRate = Rational(30, 1),        // exact fraction, not a float
    bitrateBps = 4_000_000L,            // default
    options = mapOf("preset" to "slow", "crf" to "20"),
)
```

The `frameRate` is a [`Rational`](https://yuroyami.github.io/KiteCodec/api/), not a `Double`, so rates like NTSC's 29.97 are exact rather than approximated:

```kotlin
Rational(30, 1)        // 30 fps
Rational(24, 1)        // 24 fps
Rational(30000, 1001)  // 29.97 fps, exact
```

`Rational` also ships common rates as companion constants: `Rational.Fps24`, `Rational.Fps25`, `Rational.Fps30`, `Rational.Fps60`, `Rational.Fps2997`, `Rational.Fps2398`.

The `options` map passes codec-specific settings straight through (`preset`, `crf`, `allow_sw`, and so on). KiteCodec does not validate them. They reach the encoder unchanged.

### Choosing a codec

`CodecId` is a thin value class wrapping the FFmpeg codec name. Pick whichever the linked FFmpeg build provides:

- Always present, every profile: `CodecId("mpeg4")`, `CodecId.Mjpeg`, `CodecId.Png`
- Software video encoders: `CodecId.Libx264`, `CodecId.Libx265`, both GPL-only and in neither published artifact. The software video encoder every KiteCodec build carries is `mpeg4`.
- Generic codec ids: `CodecId.H264`, `CodecId.Hevc`, `CodecId.Av1`, `CodecId.Vp9`
- Hardware video encoders with standing runtime evidence: `CodecId.H264VideoToolbox`, `CodecId.HevcVideoToolbox` on the qualified macOS profile. MediaCodec names exist in the Android FFmpeg profile, but this stage only claims named-decoder selection through `openDecoder`, not Android encoder or playback qualification.

!!! tip "Probe before you encode"
    Whether a given encoder is present depends on how FFmpeg was built. Check at runtime rather than hard-coding a name:

    ```kotlin
    import io.github.yuroyami.kitecodec.FFmpeg

    val codec = listOf(
        CodecId.H264VideoToolbox,
        CodecId.Libx264,
        CodecId("mpeg4"),
    ).first { FFmpeg.hasEncoder(it.name) }
    ```

    `libx264` and `libx265` exist only in a GPL-flavour FFmpeg, and no published KiteCodec artifact carries one, so asking for either throws `FFmpegException` from `addVideoEncoder` before a frame is read. There is no task that will build one for you: point the build at your own GPL tree under `native-libs/gpl/<target>/` with `-Pkitecodec.ffmpeg.license=gpl`. Read [Licensing](licensing.md) first, because that choice makes your whole application GPL.

## Audio encoding, copy, or drop

There are three ways to treat audio, and they are mutually exclusive.

=== "Re-encode"

    Pass an `AudioEncoderSpec`. The audio is decoded, optionally filtered, and re-encoded:

    ```kotlin
    import io.github.yuroyami.kitecodec.AudioEncoderSpec
    import io.github.yuroyami.kitecodec.CodecId

    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec   = videoSpec,
        audioSpec = AudioEncoderSpec(
            codec = CodecId.Aac,
            sampleRate = 44_100,   // default
            channels = 2,          // default
            bitrateBps = 128_000L, // default
        ),
    )
    ```

=== "Copy"

    Set `audioCopy = true` to stream-copy the audio bit-for-bit. No decode, no encode, just a timestamp rescale. This is near-free:

    ```kotlin
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec   = videoSpec,
        audioCopy = true,   // bit-exact passthrough
    )
    ```

    !!! warning
        `audioCopy = true` is mutually exclusive with `audioSpec` and `audioFilter`. Pass one or the other, not both.

    The reverse also works. Keep the video bit-exact and re-encode only the audio (`-c:v copy -c:a aac`):

    ```kotlin
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        videoCopy = true,                              // bit-exact video passthrough
        audioSpec = AudioEncoderSpec(codec = CodecId.Aac),
        audioFilter = "loudnorm",                      // e.g. fix loudness
    )
    ```

    `videoCopy` is mutually exclusive with `spec` and `videoFilter`, and trimming a copied video stream is keyframe-snapped rather than frame-exact.

=== "Drop"

    Leave `audioSpec` null and `audioCopy` false (the defaults). The output has no audio:

    ```kotlin
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec   = videoSpec,
        // no audioSpec, audioCopy stays false -> audio dropped
    )
    ```

### Audio-only transcode

Leave `spec` null to drop video entirely and produce an audio-only file. This is how you convert formats:

```kotlin
// mp3 -> aac (m4a)
Transcoder.transcode(
    input  = "song.mp3",
    output = "song.m4a",
    audioSpec = AudioEncoderSpec(codec = CodecId.Aac),
)
```

When `spec` is null, `onProgress` reports `framesEncoded = 0` and counts the audio timeline instead.

## Filters

`videoFilter` and `audioFilter` take FFmpeg filter-graph descriptions as plain strings. They run on the decoded frames before encoding.

```kotlin
Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec   = videoSpec,
    videoFilter = "scale=1280:720,hue=b=0.1,format=yuv420p",
    audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),
    audioFilter = "volume=0.5,atempo=1.25",
)
```

`videoFilter` requires `spec`. A filter that changes the frame rate or sample rate (such as `fps` or `atempo`) is handled correctly: the output time-base from the filter graph is what stamps the frames, and the encoder rescales onto its own codec time-base.

For the full filter syntax, multi-input composition (overlay, amix), and how to drive filter graphs by hand, see [Filtering](filtering.md).

## Frame-exact trim

`startMicros` and `endMicros` cut a clip out of the input. Both are in microseconds.

Both values count from the start of the content. They are not raw container timestamps. Some containers begin their timeline at a nonzero point, and MPEG-TS files typically begin near 1.4 s. KiteCodec converts your value onto that timeline for you, so `startMicros = 0` always means the first frame of the content.

```kotlin
// ffmpeg -ss 12.3 -to 45.6
Transcoder.transcode(
    input  = "input.mp4",
    output = "clip.mp4",
    spec   = videoSpec,
    startMicros = 12_300_000,  // 12.3 s
    endMicros   = 45_600_000,  // 45.6 s
)
```

Output begins at the first frame whose pts is at or past `startMicros`, and demuxing stops once the lead stream passes `endMicros`. **Output timestamps are rebased to zero**, so the clip starts at 0 in its own timeline rather than carrying the original offset.

The trim is frame-exact for re-encoded streams. A stream that is being copied (`audioCopy = true`, or a copied subtitle) starts at the preceding keyframe instead, because copying cannot synthesize an intermediate frame.

!!! note "Lossless trim"
    If you only want to cut a clip and do not need to re-encode anything, [`Remuxer.remux`](remuxing.md) does a keyframe-snapped trim with no decode or encode, in seconds. Use `transcode` only when you need to change codecs, resolution, or filters.

## Subtitles

Set `subtitleCopy = true` to stream-copy every subtitle stream into the output. It works for subtitle codecs the output container accepts: MKV takes almost all of them, MP4 takes `mov_text`. If the container cannot hold a given subtitle codec, the muxer raises a typed error.

```kotlin
Transcoder.transcode(
    input  = "input.mkv",
    output = "output.mkv",
    spec   = videoSpec,
    audioCopy = true,
    subtitleCopy = true,
)
```

## Metadata

`metadata` writes container-level tags into the output header. Only the keys you provide are written.

```kotlin
Transcoder.transcode(
    input  = "input.mp4",
    output = "clip.mp4",
    spec   = videoSpec,
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
    metadata = mapOf(
        "title"  to "My clip",
        "artist" to "KiteCodec",
    ),
)
```

## Progress reporting

Pass an `onProgress` lambda to observe the run. It receives a `TranscodeProgress`:

```kotlin
import io.github.yuroyami.kitecodec.TranscodeProgress

Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec   = videoSpec,
    onProgress = { p: TranscodeProgress ->
        val pct = p.percent?.let { "${(it * 100).toInt()}%" } ?: "?"
        println("frames=${p.framesEncoded} t=${p.outputMicros}us $pct")
    },
)
```

`TranscodeProgress` carries three fields:

| Field | Type | Meaning |
|---|---|---|
| `framesEncoded` | `Long` | Video frames encoded so far. `0` for audio-only runs. |
| `outputMicros` | `Long` | Where the output timeline currently ends, in microseconds. |
| `percent` | `Double?` | Progress from 0.0 to 1.0 against the trim window, or `null` when the input duration is unknown. |

The callback fires roughly every 30 encoded video frames, or roughly every 100 frames for audio-only runs. It is for UI updates and logging, not for exact frame accounting.

## Error handling

Every failure inside FFmpeg surfaces as an `FFmpegException` carrying a typed `FFmpegError`:

```kotlin
import io.github.yuroyami.kitecodec.FFmpegException
import io.github.yuroyami.kitecodec.FFmpegError

try {
    Transcoder.transcode(input, output, spec = videoSpec)
} catch (e: FFmpegException) {
    when (val err = e.error) {
        is FFmpegError.FileNotFound    -> println("input does not exist")
        is FFmpegError.EncoderNotFound -> println("this FFmpeg build lacks the encoder")
        is FFmpegError.InvalidData     -> println("corrupt or unrecognized input")
        is FFmpegError.Internal        -> println("internal invariant: ${err.message}")
        else                           -> println("libav failed: ${err.message} (code ${err.code})")
    }
}
```

`FFmpegError` is a sealed hierarchy of semantic categories mapped from the raw `AVERROR_*` codes: `FileNotFound`, `PermissionDenied`, `InvalidData`, `EncoderNotFound`, `DecoderNotFound`, `MuxerNotFound`, `FilterNotFound`, and more. Anything unmapped arrives as `FFmpegError.AvError`, and every subclass keeps the raw code in `code`. `FFmpegError.Internal` signals a library-side invariant failure. Asking for a `spec` when the input has no video stream throws. A missing audio stream with `audioSpec` set is tolerated, and the output is video-only.

## Complete example

A frame-exact clip, scaled down, with re-encoded audio, copied subtitles, metadata, and live progress:

```kotlin
import io.github.yuroyami.kitecodec.Transcoder
import io.github.yuroyami.kitecodec.VideoEncoderSpec
import io.github.yuroyami.kitecodec.AudioEncoderSpec
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.Rational

suspend fun makeClip() {
    Transcoder.transcode(
        input  = "input.mkv",
        output = "clip.mp4",
        spec = VideoEncoderSpec(
            codec = CodecId.Libx264,
            width = 1280, height = 720,
            frameRate = Rational.Fps30,
            bitrateBps = 3_000_000,
            options = mapOf("preset" to "medium", "crf" to "22"),
        ),
        videoFilter = "scale=1280:720,format=yuv420p",
        audioSpec   = AudioEncoderSpec(codec = CodecId.Aac, bitrateBps = 160_000),
        audioFilter = "volume=0.9",
        subtitleCopy = true,
        startMicros = 12_300_000,   // 12.3 s
        endMicros   = 45_600_000,   // 45.6 s
        metadata = mapOf("title" to "Highlight reel"),
        onProgress = { p -> println("frames=${p.framesEncoded} ${p.percent}") },
    )
}
```

## See also

- [Remuxing](remuxing.md): the lossless, no-re-encode path for container rewrites and keyframe-snapped trims.
- [Filtering](filtering.md): full filter syntax and multi-input composition.
- [Encoding and muxing](encoding-muxing.md): drive encoders and the muxer directly when you need more control than one call gives.
- [Decoding](decoding.md): open a source and pull frames yourself.
- [API reference](https://yuroyami.github.io/KiteCodec/api/)
