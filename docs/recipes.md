# Recipes

Short, working solutions to common KiteFFmpeg tasks. Each snippet is copy-pasteable and uses only real APIs from `io.github.yuroyami.kiteffmpeg`. The high-level calls (`Transcoder.transcode`, `Remuxer.remux`) are `suspend` functions, so run them inside a coroutine.

!!! note "Imports"
    Every public type lives in the flat `io.github.yuroyami.kiteffmpeg` package. The snippets below assume the relevant types are imported. The `suspend` calls run inside `runBlocking { }` or any coroutine scope.

## Probe what this build can do

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpeg

val v = FFmpeg.versions
println("avcodec ${v.avcodec}, avformat ${v.avformat}, avfilter ${v.avfilter}")
println("build config: ${FFmpeg.buildConfiguration}")

// Pick a codec that is actually present in this build.
val codec = when {
    FFmpeg.hasEncoder("h264_videotoolbox") -> CodecId.H264VideoToolbox
    FFmpeg.hasEncoder("libx264")           -> CodecId.Libx264
    else                                   -> CodecId.H264
}
```

Builds differ. A hardware encoder like `h264_videotoolbox` exists on macOS but not in a Linux VM, so check `hasEncoder` / `hasDecoder` / `hasFilter` at runtime before you commit to a codec or filter.

## Extract one thumbnail at a timestamp, encode to JPEG bytes

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.CodecId

MediaSource.open("input.mp4").use { src ->
    src.extractFrame(atMicros = 90_000_000).use { frame ->
        val jpeg: ByteArray = frame.encodeImage(CodecId.Mjpeg)
        writeFile("thumb.jpg", jpeg)
    }
}
```

`extractFrame` seeks, decodes one frame at the requested timestamp, and returns it. `encodeImage(CodecId.Mjpeg)` returns JPEG bytes. Pass `CodecId.Png` for PNG. Both `MediaSource` and `Frame` are `AutoCloseable`, so wrap them in `use { }`.

## Cut a frame-exact clip

```kotlin
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational

Transcoder.transcode(
    input  = "input.mp4",
    output = "clip.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    ),
    audioCopy   = true,             // keep the audio bit-exact
    startMicros = 12_300_000,       // 12.3 s
    endMicros   = 45_600_000,       // 45.6 s
)
```

`startMicros` / `endMicros` cut a frame-exact clip with the video re-encoded; output timestamps rebase to zero. `endMicros` has no upper bound unless you set it.

## Watermark overlay (two inputs)

```kotlin
import io.github.yuroyami.kiteffmpeg.FilterGraph

// Composite a logo into the bottom-right corner.
val graph = FilterGraph.buildVideoMulti(
    description = "[in0][in1]overlay=W-w-10:H-h-10[out]",
    inputs = listOf(mainVideoInput, logoInput),   // VideoInput per source
)

graph.feedInput(0, videoFrame) { composited -> /* encode composited */ }
graph.feedInput(1, logoFrame)  { /* logo input produces no output on its own */ }
```

Multi-input graphs take a push callback rather than a `Flow`. Label inputs `[in0]…[inN-1]` in order and emit a single `[out]`. Each `VideoInput` describes one source (`width`, `height`, `pixelFormat`, `timeBase`, `frameRate`). Close the graph with `graph.close()` when done.

!!! tip "Single-input filters use a Flow"
    For a one-input chain (scale, crop, eq), build with `FilterGraph.buildVideo(...)` and call `process(input: Flow<Frame>): Flow<Frame>` instead. See [Filtering](filtering.md).

## Audio-only: mp3 to aac

```kotlin
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId

Transcoder.transcode(
    input  = "song.mp3",
    output = "song.m4a",
    spec      = null,                                   // no video stream
    audioSpec = AudioEncoderSpec(codec = CodecId.Aac),  // re-encode to AAC
)
```

Passing `spec = null` runs an audio-only pipeline. AAC's fixed 1024-sample frame chunking is handled for you: the transcoder reads the encoder's `frameSize` and sets the filter graph's output frame size automatically.

## Stream-copy passthrough (audioCopy)

```kotlin
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational

Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    ),
    videoFilter = "scale=1280:720,format=yuv420p",
    audioCopy   = true,        // copy audio packets verbatim, no decode/encode
    subtitleCopy = true,       // copy subtitle streams too
)
```

`audioCopy = true` re-muxes the audio packets bit-exact (timestamps rescaled only), so the audio is untouched while the video is re-encoded. `subtitleCopy = true` carries subtitle streams through the same way.

!!! note "Nothing to re-encode at all?"
    If you do not need to touch any codec, skip the transcoder entirely and rewrite the container losslessly with `Remuxer.remux(...)`. See [Remuxing](remuxing.md).

## Report progress while transcoding

```kotlin
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational

Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    ),
    audioSpec = AudioEncoderSpec(codec = CodecId.Aac),
    onProgress = { p ->
        val pct = p.percent?.let { "${(it * 100).toInt()}%" } ?: "?"
        println("$pct  frames=${p.framesEncoded}  t=${p.outputMicros / 1_000_000.0}s")
    },
)
```

`onProgress` receives a `TranscodeProgress` carrying `framesEncoded`, `outputMicros` (the output timeline end), and a nullable `percent` (0.0 to 1.0, or null when the input duration is unknown). It fires roughly every 30 video frames (or every 100 frames for audio-only work), not on an exact count.

!!! tip "Progress for a lossless remux"
    `Remuxer.remux(...)` takes its own `onProgress: ((packetsWritten: Long) -> Unit)?` callback that reports the running packet count instead of a `TranscodeProgress`.

## See also

- [Transcoding](transcoding.md): the full `Transcoder.transcode(...)` surface, hardware encoders, trim, progress.
- [Decoding & frames](decoding.md): `MediaSource`, decode flows, single-pass multi-stream, thumbnails.
- [Filtering](filtering.md): single-input and multi-input `FilterGraph`s.
- [Encoding & muxing](encoding-muxing.md): drive `VideoEncoder` / `AudioEncoder` through a `MediaSink`.
- [Remuxing](remuxing.md): lossless `Remuxer.remux(...)` and keyframe-snapped trim.
- [API reference](https://yuroyami.github.io/KiteFFmpeg/api/): every public type and signature.
