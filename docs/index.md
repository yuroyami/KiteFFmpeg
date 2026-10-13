# KiteFFmpeg

KiteFFmpeg is FFmpeg for Kotlin Multiplatform, as plain Kotlin calls. You decode, encode,
transcode and filter video and audio from `commonMain`, and FFmpeg is already inside the artifact.
No `ffmpeg` process starts, and no log text is parsed. Progress arrives as a typed callback, errors
arrive as typed exceptions, and frames arrive as a coroutine `Flow`.

```kotlin
// One call: demux, decode, filter, encode and mux, in a single pass.
Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Mpeg4,                             // in every published build
        width = 320, height = 180,
        frameRate = Rational(30, 1),
        bitrateBps = 1_500_000,
    ),
    videoFilter = "scale=320:180,hue=b=0.1,vignette,format=yuv420p",
    audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),   // null drops the audio
    audioFilter = "volume=0.8",                            // optional
    onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
)
```

For H.264 or HEVC, ask the build which encoder it has. `h264_videotoolbox` is in the macOS and
iPhone builds, `h264_mediacodec` is in the Android build, and `libx264` exists only in a GPL FFmpeg
that you build yourself. [Platform support](platforms.md) lists what each build carries.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.5.1")
}
```

FFmpeg is compiled into each published artifact, so there is nothing else to install. An iOS app
and a web app need one more step each, which [Getting started](getting-started.md) describes.

## How it works

Kotlin/Native calls FFmpeg's libraries through cinterop. The JVM and Android call them through a
small JNI library that the artifact carries. `wasmJs` calls a WebAssembly build of FFmpeg that the
page loads. Every target shares the same common API, so the code you write in `commonMain` runs on
each of them.

Every pipeline reads its input once. When you decode several streams, or composite two inputs, the
demuxer reads the file a single time and hands the packets to the decoders that need them.

## What you can do

### Read

Open a file, look at its streams, and collect decoded frames as a `Flow`:

```kotlin
MediaSource.open("input.mp4").use { src ->
    println("${src.formatName}, ${src.durationMicros} us")

    val video = src.primaryVideo ?: error("no video stream")
    src.decodedFrames(video).collect { frame ->
        frame.use {
            val info = it.info                   // width, height, pts, pixel format
            val pixels = it.copyPlanesToByteArray()
        }
    }
}
```

Each frame that the flow emits is yours, so close it, or its native memory leaks. A single frame
for a thumbnail can go straight to image bytes:

```kotlin
MediaSource.open("input.mp4").use { src ->
    src.extractFrame(atMicros = 90_000_000).use { frame ->
        val jpeg = frame.encodeImage(CodecId.Mjpeg)
    }
}
```

See [Decoding frames](decoding.md).

### Transcode

One call handles the trim, the audio copy, the subtitle copy, the metadata and the progress. Pass
`spec = null` for an audio-only transcode, such as MP3 to AAC.

```kotlin
// A clip that starts on the exact frame, with its timestamps rebased to zero.
Transcoder.transcode(
    input = "input.mp4",
    output = "clip.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId.Mpeg4,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    ),
    audioCopy = true,                    // copy the audio bit for bit
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
    metadata = mapOf("title" to "My clip"),
)
```

When nothing needs a new encode, rewrite the container instead. A remux decodes nothing, so it
loses nothing:

```kotlin
Remuxer.remux("input.mp4", "output.mkv")
```

See [Transcoding](transcoding.md) and [Remuxing](remuxing.md).

### Filter

Build a filter graph from an FFmpeg filter string and push frames through it. A graph with one
input works as a `Flow` step, and a graph with several inputs (such as `overlay` or `amix`) takes a
callback:

```kotlin
// Two inputs, one output: a watermark in the bottom-right corner.
val graph = FilterGraph.buildVideoMulti(
    "[in0][in1]overlay=W-w-10:H-h-10[out]",
    listOf(mainVideoInput, logoInput),
)
graph.feedInput(0, videoFrame) { composited -> /* encode it */ }
graph.feedInput(1, logoFrame) { }
```

See [Filter graphs](filtering.md).

## Guides

| Guide | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | The dependency, the iOS and web setup, and a first transcode |
| [Transcoding](transcoding.md) | `Transcoder.transcode`: specs, trim, audio copy and progress |
| [Decoding frames](decoding.md) | `MediaSource`, decode flows, several streams in one pass, thumbnails and your own bytes |
| [Asynchronous byte sources](async-byte-io.md) | Byte sources that answer later, such as a Fetch response on the web |
| [Filter graphs](filtering.md) | Video and audio graphs, with one input or several |
| [Encoding and muxing](encoding-muxing.md) | `MediaSink`, the encoders, their specs and options |
| [Remuxing](remuxing.md) | Rewriting the container with no loss, and cuts at keyframes |
| [Concurrency](concurrency.md) | Threads, ownership and cancellation |
| [Recipes](recipes.md) | Short patterns for common tasks |
| [Platform support](platforms.md) | What each build carries and where it runs |
| [Licensing](licensing.md) | What shipping the LGPL FFmpeg asks of your app |
| [Troubleshooting](troubleshooting.md) | Common errors and their fixes |

The [API reference](https://yuroyami.github.io/KiteFFmpeg/api/) lists every public type, and
[About KiteFFmpeg](about.md) explains the design.
