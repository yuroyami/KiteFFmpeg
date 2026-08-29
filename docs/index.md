# KiteFFmpeg

**One coroutine-first Kotlin API for video and audio.** Decode, encode, transcode and filter media from a single suspend-friendly surface, backed by FFmpeg's libav\* libraries. Kotlin/Native uses cinterop; JVM and Android use a narrow JNI bridge, and both are published. The JVM jar carries a macOS arm64 library only, so JVM consumers on other hosts, along with `js` and `wasmJs`, get an invariant unsupported placeholder contract. There is no `ffmpeg` subprocess, and memory stays constant regardless of input length.

```kotlin
// One call: demux -> decode -> filter -> encode -> mux, in a single pass.
Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        // mpeg4 is the dependency-free baseline present in every FFmpeg profile.
        codec = CodecId("mpeg4"),
        width = 320, height = 180,
        frameRate = Rational(30, 1),
        bitrateBps = 1_500_000,
    ),
    videoFilter = "scale=320:180,hue=b=0.1,vignette,format=yuv420p",
    audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),   // null drops audio
    audioFilter = "volume=0.8",                            // optional
    onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
)
```

For H.264 or H.265, probe first and pick what the linked build has.
`h264_videotoolbox` has standing macOS runtime evidence; `libx264` exists only in a GPL FFmpeg.
The generated Android profile contains MediaCodec names, but the current Android claim stops at
source, host tests, link and packaging, with exact named-decoder selection documented in
[Decoding](decoding.md). See [Platform support](platforms.md) and [Licensing](licensing.md).

- [Getting started](getting-started.md): install FFmpeg, wire the build, run your first transcode.
- [API reference](https://yuroyami.github.io/KiteFFmpeg/api/): every public type and signature.

## Why KiteFFmpeg

Media work from Kotlin normally means launching the `ffmpeg` CLI and parsing its stderr, or wrapping a prebuilt binary like FFmpegKit. You build arguments into a string, launch a process, and read progress back out of log lines. The codec engine lives outside your program.

KiteFFmpeg is a **single Kotlin API over libav\* directly**. You call `Transcoder.transcode(...)` and it opens the file via libavformat, demuxes **once**, routes packets to per-stream libavcodec decoders, pushes frames through libavfilter graphs, encodes, and interleaves the streams into a valid container. There is no process to spawn and no log output to parse. Progress arrives as a typed callback, errors arrive as typed exceptions, and frames flow as a coroutine `Flow`.

Everything routes through one demux pass. When you decode several streams, or composite two inputs, the demuxer reads the file a single time and fans packets out to the decoders that need them.

## Install

!!! warning "Not consumable from Maven Central today"
    Neither `kiteffmpeg-core` nor the Gradle plugin has been published, and the FFmpeg Release assets the plugin's default `FFmpegSource.Prebuilt` downloads do not exist. The [README](https://github.com/yuroyami/KiteFFmpeg#install) carries the complete consumer build script and the [release status](https://github.com/yuroyami/KiteFFmpeg#release-status), and is the single place either is tracked. Until that changes, you work inside the KiteFFmpeg checkout.

The bindings link against libav\*, so FFmpeg has to be present at build time. For a dynamically linked build, it must be present at run time as well.

=== "macOS"

    ```bash
    brew install ffmpeg
    ./gradlew :kiteffmpeg-sample:linkDebugExecutableMacosArm64
    ```

=== "Linux"

    ```bash
    sudo apt install ffmpeg libavcodec-dev libavformat-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev
    ./gradlew :kiteffmpeg-core:linuxX64Test
    ```

JVM and Android are published artifacts, not source-only actuals. The Android AAR on Maven Central
declares `minSdkVersion 26` in its own manifest and carries `libkitecodec_jni.so` for `arm64-v8a`
and `x86_64` with 16 KiB ELF/app packaging. The JVM jar carries a **macOS arm64** library and only
that one, so a JVM consumer on Linux or Windows gets the typed unavailable placeholder rather than a
codec. No Android playback is qualified on a physical device. JS and WasmJs compile as unsupported
placeholders: they report no capabilities and reject media operations predictably. See
[Platform support](platforms.md).

## What you can do

### Read

Open a file, inspect its streams, and pull decoded frames as a `Flow`. The demuxer wraps `AVFormatContext`; the decode loop is EAGAIN-correct and promotes `best_effort_timestamp` to `pts`.

```kotlin
MediaSource.open("input.mp4").use { src ->
    println("${src.formatName}, ${src.durationMicros} us")

    val video = src.primaryVideo ?: error("no video stream")
    src.decodedFrames(video).collect { frame ->
        try {
            val info = frame.info           // width, height, pts, pixelFormat...
            val pixels = frame.copyPlanesToByteArray()
            // ...
        } finally {
            frame.close()                   // emitted frames are OWNED; close or they leak
        }
    }
}
```

Read a single frame for a thumbnail and encode it straight to image bytes:

```kotlin
MediaSource.open("input.mp4").use { src ->
    src.extractFrame(atMicros = 90_000_000).use { frame ->
        writeFile(frame.encodeImage(CodecId.Mjpeg))
    }
}
```

See **[Decoding & frames](decoding.md)**.

### Transcode

The high-level pipeline handles trim, audio copy, subtitle copy, metadata and progress in one call. Skip the video codec (`spec = null`) for an audio-only transcode such as mp3 to aac.

```kotlin
// Frame-exact clip, output timestamps rebased to zero.
Transcoder.transcode(
    input = "input.mp4",
    output = "clip.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    ),
    audioCopy = true,                    // stream-copy audio bit-exact
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
    metadata = mapOf("title" to "My clip"),
)
```

Nothing to re-encode at all? Rewrite the container losslessly:

```kotlin
Remuxer.remux("input.mp4", "output.mkv")   // no decode, no encode, runs in seconds
```

See **[Transcoding](transcoding.md)** and **[Remuxing](remuxing.md)**.

### Filter

Build a libavfilter graph from a plain FFmpeg filter description and drive frames through it. Single-input graphs expose a `Flow` pipeline; multi-input graphs (overlay, amix) take a push callback.

```kotlin
// Two inputs -> one output: watermark in the bottom-right corner.
val graph = FilterGraph.buildVideoMulti(
    "[in0][in1]overlay=W-w-10:H-h-10[out]",
    listOf(mainVideoInput, logoInput),
)
graph.feedInput(0, videoFrame) { composited -> /* encode */ }
graph.feedInput(1, logoFrame)  { /* ... */ }
```

See **[Filtering](filtering.md)**.

## Guides

| | |
|---|---|
| **[Getting started](getting-started.md)** | Install FFmpeg, build, and run your first transcode. |
| **[Transcoding](transcoding.md)** | The `Transcoder.transcode` pipeline: specs, trim, audio copy, progress. |
| **[Decoding & frames](decoding.md)** | `MediaSource`, decode flows, single-pass multi-stream, thumbnails. |
| **[Filtering](filtering.md)** | `FilterGraph` video and audio graphs, single and multi-input. |
| **[Encoding & muxing](encoding-muxing.md)** | `MediaSink`, `VideoEncoder` / `AudioEncoder`, encoder specs and options. |
| **[Remuxing](remuxing.md)** | Lossless container rewrite and keyframe-snapped trim. |
| **[Concurrency](concurrency.md)** | Threading, confinement, and cancellation rules. |
| **[Recipes](recipes.md)** | Copy-paste patterns for common tasks. |
| **[Platform support](platforms.md)** | What runs where, and how FFmpeg is sourced. |
| **[Licensing](licensing.md)** | LGPL/GPL flavors and what shipping them obligates. |
| **[Troubleshooting](troubleshooting.md)** | FFmpeg discovery, Windows setup, VMs, NDK. |

## Status

KiteFFmpeg is pre-1.0 and actively developed. The public pipeline is implemented for Kotlin/Native
and JVM and Android are published actuals for the same common contracts. Native runtime evidence
remains the qualified baseline; the JVM JNI boundary is proved by 41 tests over real FFmpeg on an
arm64 Mac, and the Android evidence stops at source, link and packaging checks. It does not establish physical-device playback, UI integration
or a full product tier. Web variants are T1 placeholders, not a codec-runtime claim. Nothing is
published, and the FFmpeg release the Gradle plugin fetches from does not exist yet.

One target table covers the whole project and lives in the [README](https://github.com/yuroyami/KiteFFmpeg#targets). For the design, the FFmpeg sourcing modes, and what is next, see **[About KiteFFmpeg](about.md)**.
