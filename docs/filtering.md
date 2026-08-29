# Filter graphs

A filter graph is a chain of FFmpeg filters that transforms frames. A `FilterGraph` wraps a [libavfilter](https://ffmpeg.org/ffmpeg-filters.html) chain and passes frames through it. You describe the chain as a string (`scale`, `eq`, `vignette`, `format`, `volume`, `atempo`, anything FFmpeg ships), KiteFFmpeg compiles it into a `buffersrc → chain → buffersink` graph, and you feed it `Frame`s. The same chains you would give to the `ffmpeg` CLI work here, with no subprocess.

Most of the time you do not build a graph by hand. [`Transcoder.transcode`](transcoding.md) takes a `videoFilter` / `audioFilter` string and wires the graph for you. Use `FilterGraph` directly when you are composing several inputs, driving frames yourself, or filtering outside the transcode pipeline.

```kotlin
import io.github.yuroyami.kiteffmpeg.FilterGraph
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational

val graph = FilterGraph.buildVideo(
    description = "scale=1280:720,hue=b=0.1,vignette,format=yuv420p",
    width = 1920,
    height = 1080,
    pixelFormat = PixelFormat.Yuv420p,
    timeBase = Rational.Tb_us,
    frameRate = Rational.Fps30,
)
```

## Single-input video

`buildVideo` compiles a one-input, one-output video graph. The parameters describe the frames you will feed in: the graph needs the input format up front to configure its `buffersrc`.

```kotlin
val graph = FilterGraph.buildVideo(
    description = "scale=640:360,eq=contrast=1.2,format=yuv420p",
    width = 1280,
    height = 720,
    pixelFormat = PixelFormat.Yuv420p,
    timeBase = Rational.Tb_us,
    frameRate = Rational.Fps30,
    sampleAspectRatio = Rational(1, 1),   // square pixels, the default
)
```

| Parameter | Meaning |
|---|---|
| `description` | The filter chain, e.g. `scale=1280:720,hue=b=0.1,format=yuv420p`. Filters must exist in the FFmpeg you linked. `eq` and `boxblur` are GPL-only in FFmpeg itself, so they are absent from KiteFFmpeg's default LGPL profile. `hue` (which has a brightness parameter `b`), `colorlevels` and `curves` are the LGPL equivalents. `FFmpeg.hasFilter("eq")` tells you before you build a graph. |
| `width`, `height` | Dimensions of the frames you feed in. |
| `pixelFormat` | Input pixel format, typically the decoder's output (see [`PixelFormat`](https://yuroyami.github.io/KiteFFmpeg/api/)). |
| `timeBase` | The pts time-base of input frames. |
| `frameRate` | Input frame rate, used by `setpts` / `fps`-style filters. |
| `sampleAspectRatio` | Input SAR. Defaults to `Rational(1, 1)`. |

The `description` is a plain FFmpeg filter string. An empty chain (or `null` upstream) is a passthrough.

!!! tip "Match the decoder"
    When you are filtering frames straight from a [`MediaSource`](decoding.md), take `width`, `height`, `pixelFormat`, `timeBase`, and `frameRate` from the stream's `VideoStreamInfo` and `StreamInfo` rather than hard-coding them. That keeps the `buffersrc` configured exactly as the decoder emits.

## Single-input audio

`buildAudio` compiles a one-input audio graph. It carries the input audio format the same way the video builder carries the picture format.

```kotlin
import io.github.yuroyami.kiteffmpeg.SampleFormat

val graph = FilterGraph.buildAudio(
    description = "volume=0.5,atempo=1.25",
    sampleRate = 48_000,
    sampleFormat = SampleFormat.FltP,   // decoder output
    channels = 2,
    timeBase = Rational(1, 48_000),
)
```

The chain is again a plain FFmpeg string. `volume=0.5,atempo=1.25` halves the loudness and speeds the track up by 25%. An empty chain or `anull` is a passthrough.

### Encoder-ready output

Audio encoders are strict about sample rate, sample format, and channel layout. When you pass the `output*` parameters, the graph appends an `aformat` stage so every frame leaves the graph already resampled, reformatted, and remixed for the encoder.

```kotlin
val graph = FilterGraph.buildAudio(
    description = "volume=0.8",
    sampleRate = 48_000,
    sampleFormat = SampleFormat.FltP,
    channels = 2,
    // Pin the output to what the AAC encoder wants:
    outputSampleRate = 44_100,
    outputSampleFormat = SampleFormat.FltP,
    outputChannels = 2,
)
```

!!! note "Why the pin lives in the graph"
    The `aformat` stage is appended as a filter-string fragment, not as a `buffersink` option. Filter-string syntax is stable across FFmpeg versions, whereas `buffersink` option names have churned. Pinning the format inside the chain means the same code keeps working across FFmpeg 6, 7, and 8.

When the `output*` parameters are left at their defaults (`0`, `SampleFormat.None`, `0`), no `aformat` stage is added and frames leave in the input format.

### Frame size for fixed-frame codecs

Some audio codecs want a fixed number of samples per frame. AAC, for instance, encodes exactly 1024 samples at a time. `setOutputFrameSize` makes the `buffersink` chunk its output to that size:

```kotlin
graph.setOutputFrameSize(1024)   // AAC wants 1024-sample frames
```

You rarely call this by hand. The [`AudioEncoder`](encoding-muxing.md) exposes its `frameSize`, and `Transcoder` reads it to chunk the audio graph automatically.

## Driving a single-input graph

A single-input graph consumes a `Flow<Frame>` and emits the processed frames as they leave the `buffersink`:

```kotlin
import kotlinx.coroutines.flow.Flow
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.MediaSource

MediaSource.open("input.mp4").use { src ->
    val video = src.primaryVideo!!
    val info = video.video!!

    val graph = FilterGraph.buildVideo(
        description = "scale=640:360,format=yuv420p",
        width = info.width,
        height = info.height,
        pixelFormat = info.pixelFormat,
        timeBase = video.timeBase,
        frameRate = info.frameRate,
    )

    val filtered: Flow<Frame> = graph.process(src.decodedFrames(video))
    filtered.collect { frame ->
        // ... encode or inspect frame ...
    }
}
```

`process` owns the lifecycle. It closes each input frame after the graph consumes it, and it closes the graph itself when the returned flow terminates. You do not call `close()` on a graph you drove with `process`.

!!! warning "Frame ownership"
    Frames emitted by `process` are owned by the collector. They stay valid until you `close()` them, so buffering operators are safe. Close each one when done (see [frame ownership](decoding.md#frame-ownership)).

## Multi-input graphs

`buildVideoMulti` and `buildAudioMulti` compile graphs with several `buffersrc` inputs feeding one `buffersink` output. The description references pads by label: inputs are `[in0]` through `[inN-1]`, and the single output is `[out]`.

```text
[in0][in1]overlay=W-w-10:H-h-10[out]
[in0][in1]amix=inputs=2:duration=longest[out]
```

Each input has its own format, so you pass a list of `VideoInput` / `AudioInput` descriptors, one per `[inN]`, in order.

### Video: overlay a watermark

This composites a logo into the bottom-right corner of a main video. `[in0]` is the main picture, `[in1]` is the logo, and `overlay=W-w-10:H-h-10` places the smaller frame ten pixels in from the right and bottom edges.

```kotlin
import io.github.yuroyami.kiteffmpeg.VideoInput

val mainInput = VideoInput(
    width = 1920, height = 1080,
    pixelFormat = PixelFormat.Yuv420p,
    timeBase = Rational.Tb_us,
    frameRate = Rational.Fps30,
)
val logoInput = VideoInput(
    width = 256, height = 256,
    pixelFormat = PixelFormat.Rgba,
    timeBase = Rational.Tb_us,
    frameRate = Rational.Fps30,
)

val graph = FilterGraph.buildVideoMulti(
    description = "[in0][in1]overlay=W-w-10:H-h-10[out]",
    inputs = listOf(mainInput, logoInput),
)
```

### Audio: mix two tracks

`amix` blends several audio inputs into one. `[in0]` is the main track and `[in1]` is a second source. `duration=longest` runs the output until the longer input ends. Like `buildAudio`, the multi builder accepts `output*` pins, so the mix leaves the graph ready for the encoder.

```kotlin
import io.github.yuroyami.kiteffmpeg.AudioInput

val voice = AudioInput(
    sampleRate = 48_000,
    sampleFormat = SampleFormat.FltP,
    channels = 2,
    timeBase = Rational(1, 48_000),
)
val music = AudioInput(
    sampleRate = 44_100,
    sampleFormat = SampleFormat.FltP,
    channels = 2,
    timeBase = Rational(1, 44_100),
)

val graph = FilterGraph.buildAudioMulti(
    description = "[in0][in1]amix=inputs=2:duration=longest[out]",
    inputs = listOf(voice, music),
    outputSampleRate = 44_100,
    outputSampleFormat = SampleFormat.FltP,
    outputChannels = 2,
)
```

## Driving a multi-input graph

Multi-input graphs cannot use the single-input `process` flow, because there is more than one source to feed. You push frames into each input by index with `feedInput`, and every output frame that becomes available is handed to your callback.

```kotlin
graph.feedInput(0, mainFrame) { composited ->
    // ... encode the composited frame ...
}
graph.feedInput(1, logoFrame) { composited ->
    // ... usually nothing emerges yet ...
}
```

`feedInput` closes the frame you pass in. The graph keeps its own reference, so you must not touch that frame afterward. Output frames passed to the `onOutput` callback are valid **only for the duration of the callback**. Use [`Frame.copy`](decoding.md#frame-ownership) to keep one.

### Flushing

Some filters only emit their tail once every input has reached EOF. `overlay`, for example, holds frames back until both the main and the overlay streams are flushed. Signal EOF on each input with `flushInput`, in any order; the final flush delivers whatever remains.

```kotlin
graph.flushInput(0) { tail -> /* ... encode ... */ }
graph.flushInput(1) { tail -> /* ... encode ... */ }   // last flush drains the remainder
graph.close()
```

A graph is single-shot: once EOF has been flushed through it, it cannot accept more frames. For push-style use, call `close()` (it is idempotent) when you are done.

## Output time-base

Filters such as `fps` and `atempo` change the timing of the stream, so the time-base of frames leaving the graph is not always the input time-base. The graph reports the one it actually uses through `outputTimeBase`:

```kotlin
val graph = FilterGraph.buildAudio(
    description = "atempo=1.25",
    sampleRate = 48_000,
    sampleFormat = SampleFormat.FltP,
    channels = 2,
    timeBase = Rational(1, 48_000),
)

println("Output time-base: ${graph.outputTimeBase}")
```

When you feed filtered frames into an encoder or muxer, rescale timestamps against `outputTimeBase`, not against the input time-base. Inside `Transcoder` this is handled for you. When you do it by hand, use `Rational`'s overflow-safe `times` operator. See [Encoding & muxing](encoding-muxing.md) for how the encoder rescales onto the codec time-base from there.

!!! note "Other graph properties"
    `inputCount` reports how many `buffersrc` inputs the graph was built with. It is the upper bound on the `index` you can pass to `feedInput` / `flushInput` (`0` to `inputCount - 1`).

## Probing filter availability

Filters are part of the FFmpeg build you link against. Before relying on a less common one, you can check it exists:

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpeg

if (FFmpeg.hasFilter("vignette")) {
    // safe to use vignette in a chain
}
```

A description referencing a filter that is not in the build fails when the graph is constructed. See [Getting started](getting-started.md) for how the FFmpeg build is sourced (system libraries vs. a vendored static build).

## Errors

Graph construction and frame pushing surface FFmpeg failures as an `FFmpegException`. An unknown filter raises `FFmpegError.FilterNotFound`; a malformed description or a format the chain cannot negotiate raises a semantic `FFmpegError` subclass (falling back to `FFmpegError.AvError` with the underlying `AVERROR_*` code); an internal invariant failure raises `FFmpegError.Internal`.

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpegException

try {
    FilterGraph.buildVideo(
        description = "scale=bogus",
        width = 1280, height = 720,
        pixelFormat = PixelFormat.Yuv420p,
        timeBase = Rational.Tb_us,
        frameRate = Rational.Fps30,
    )
} catch (e: FFmpegException) {
    println("Graph build failed: code ${e.code}")
}
```

---

Next: feed filtered frames into an encoder in [Encoding & muxing](encoding-muxing.md), or let [`Transcoder`](transcoding.md) wire the whole `decode → filter → encode → mux` pipeline for you. For ready-to-paste compositions, see [Recipes](recipes.md).
