# Decoding

Open a media file, inspect its streams, and pull decoded frames out as a coroutine `Flow`. Demuxing means splitting a container file into its separate streams. KiteFFmpeg runs the demux loop, the EAGAIN retry handling, and the best-effort timestamp promotion for you. You work with whole `Frame` objects instead of raw packets.

This page covers reading and decoding. To re-encode and write a new file, see [Encoding & muxing](encoding-muxing.md). To run a one-call pipeline instead of a manual loop, see [Transcoding](transcoding.md).

## Opening a file

Open an input file with `MediaSource.open(path)`. It wraps an `AVFormatContext` and reads the container header so the stream list and metadata are available immediately:

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource

val source = MediaSource.open("input.mp4")
println("Format: ${source.formatName}")
println("Duration: ${source.durationMicros} us")
println("Streams: ${source.streams.size}")
```

`MediaSource` is `AutoCloseable`. Close it when you are done, or use `use { }` so it closes even on failure:

```kotlin
MediaSource.open("input.mp4").use { source ->
    // work with source here
}   // demuxer freed automatically
```

!!! note
    Opening a file does not start decoding. It only reads the container header. Decoding happens lazily when you collect one of the frame flows below.

## Streams

Every input carries a list of `StreamInfo`. Each entry describes one track: its index, type, codec, and time-base. Iterate the full list, or read a primary track directly:

```kotlin
for (stream in source.streams) {
    println("[${stream.index}] ${stream.type} ${stream.codec.name}")
}

val video = source.primaryVideo   // StreamInfo?: the primary video track, or null
val audio = source.primaryAudio   // StreamInfo?: the primary audio track, or null
```

`primaryVideo` and `primaryAudio` are nullable. An audio-only file has no `primaryVideo`, so guard for null before you decode.

### What a stream tells you

`StreamInfo` exposes the shape of the track. Video and audio specifics live in nested holders that are populated only for the matching type:

```kotlin
val stream = source.primaryVideo ?: error("no video track")

println("Codec:     ${stream.codec.name}")
println("Time-base: ${stream.timeBase}")
println("Duration:  ${stream.durationMicros} us")
println("Bitrate:   ${stream.bitrateBps} bps")

stream.video?.let { v ->
    println("Size:  ${v.width} x ${v.height}")
    println("Pixfmt: ${v.pixelFormat.name}")
    println("FPS:   ${v.frameRate}")
}
```

For an audio track, read `stream.audio` instead:

```kotlin
source.primaryAudio?.audio?.let { a ->
    println("Sample rate: ${a.sampleRate} Hz")
    println("Channels:    ${a.channels}")
    println("Sample fmt:  ${a.sampleFormat.name}")
}
```

| Property | Type | Notes |
|---|---|---|
| `index` | `Int` | Position of the stream in the container |
| `type` | `MediaType` | `Video`, `Audio`, `Subtitle`, `Data`, `Attachment`, `Unknown` |
| `codec` | `CodecId` | The decoder codec, e.g. `H264`, `Aac` |
| `timeBase` | `Rational` | Stream time-base. Use it to convert a pts (presentation timestamp) to seconds |
| `durationMicros` | `Long?` | Track duration, or null if the container omits it |
| `bitrateBps` | `Long?` | Declared bitrate, or null |
| `video` | `VideoStreamInfo?` | Non-null for video streams |
| `audio` | `AudioStreamInfo?` | Non-null for audio streams |

### Container metadata

The container's own tag dictionary is a plain map:

```kotlin
val title = source.metadata["title"]
val artist = source.metadata["artist"]
```

## Decoding one stream

`decodedFrames(stream)` returns a cold `Flow<Frame>`. Collecting it runs an EAGAIN-correct decode loop: it demuxes packets, feeds them to the right decoder, and emits one `Frame` per decoded picture or audio buffer.

```kotlin
import kotlinx.coroutines.flow.collect

val video = source.primaryVideo ?: error("no video track")

source.decodedFrames(video).collect { frame ->
    val info = frame.info
    println("frame pts=${info.pts} (${info.ptsSeconds}s) ${info.width}x${info.height}")
}
```

The flow is cold. Nothing decodes until you collect, and each fresh collection restarts from the demuxer's current position. The loop drains the decoder correctly at end-of-stream, so you receive every buffered frame before the flow completes.

!!! note "Best-effort timestamps"
    Each frame's `pts` is promoted from FFmpeg's `best_effort_timestamp` (the same rule `ffmpeg.c` uses), so files with missing or irregular pts still decode with usable timestamps. When a frame genuinely has no timestamp, `info.hasPts` is false and `info.pts` equals `FrameInfo.NOPTS`. The full timestamp contract is in [About → Timestamp handling](about.md#timestamp-handling).

## Decoding several streams in one pass

If you need both video and audio, do not open two flows. Two concurrently collected `decodedFrames` flows **race on the shared demuxer**. A `MediaSource` wraps one `AVFormatContext`, which is not safe to drive from concurrent coroutines. The result is corrupted reads, not just wasted work. `decodeStreams(streams)` is the only correct way to decode several streams together. It runs a single demux pass and interleaves frames from every requested stream into one `Flow<Frame>`:

```kotlin
val wanted = listOfNotNull(source.primaryVideo, source.primaryAudio)

source.decodeStreams(wanted).collect { frame ->
    when (frame.info.type) {
        MediaType.Video -> handleVideo(frame)
        MediaType.Audio -> handleAudio(frame)
        else -> {}
    }
}
```

Frames arrive in demux (roughly presentation) order, mixed across streams. Use `frame.info.streamIndex` or `frame.info.type` to route each one. This is the same single-pass machinery the [Transcoder](transcoding.md) uses internally. The confinement rules for `MediaSource` as a whole are collected in [Concurrency](concurrency.md).

!!! tip
    Pass only the streams you actually consume. Packets for streams you leave out of the list are skipped, so a video-only `decodeStreams(listOf(primaryVideo))` does no audio decode work at all.

## Working with a Frame

A `Frame` is a snapshot of one decoded picture or audio buffer plus a `FrameInfo` describing it. `FrameInfo` carries different fields depending on the media type:

```kotlin
val info = frame.info
when (info.type) {
    MediaType.Video -> {
        println("${info.width}x${info.height} ${info.pixelFormat.name}")
    }
    MediaType.Audio -> {
        println("${info.sampleCount} samples @ ${info.sampleRate} Hz, " +
                "${info.channelCount} ch, ${info.sampleFormat.name}")
    }
    else -> {}
}
```

| Field | Applies to | Meaning |
|---|---|---|
| `streamIndex` | all | Source stream index |
| `type` | all | `MediaType` of this frame |
| `pts` | all | Presentation timestamp in `timeBase` units, or `NOPTS` |
| `timeBase` | all | Units for `pts` |
| `hasPts` | all | False when `pts == NOPTS` |
| `ptsSeconds` | all | `pts` in seconds, or `NaN` when absent |
| `width`, `height`, `pixelFormat` | video | Picture geometry |
| `sampleCount`, `sampleRate`, `channelCount`, `sampleFormat` | audio | Buffer shape |

### Reading the pixels or samples

`copyPlanesToByteArray()` copies the frame's raw data into a fresh `ByteArray`. For video that is the pixel planes; for audio it is the sample data. It always copies, so the returned array is yours to keep:

```kotlin
val bytes = frame.copyPlanesToByteArray()
// video: packed pixel planes in info.pixelFormat
// audio: PCM samples in info.sampleFormat
```

The layout follows `info.pixelFormat` (video) or `info.sampleFormat` (audio). A planar format such as `Yuv420p` or `S16p` packs each plane back-to-back; an interleaved format such as `Rgb24` or `S16` packs samples together.

### Frame ownership

Frames emitted by the flow APIs (`decodedFrames`, `decodeStreams`, `FilterGraph.process`) are **owned by you**: each one stays valid until you `close()` it, so buffering operators (`buffer()`, `toList()`, holding frames in a list) are safe. Internally these are O(1) reference-counted clones of the decoder's landing frame, so no pixel copies are made.

There is one obligation: **close every frame you collect**, or its native buffers leak.

```kotlin
// Fine: frames stay valid past the next emission…
val frames = source.decodedFrames(video).toList()
// …but each one is yours to release.
frames.forEach { it.close() }
```

Callback-style APIs are different. A frame handed to `FilterGraph.feedInput`'s `onOutput` callback is valid **only for the duration of the callback**. Call `copy()` there to keep one.

`copy()` is O(1): it shares the underlying pixel data via reference counting rather than duplicating bytes, and the result is owned. Both collected frames and copies are `AutoCloseable`. Close them (or use `use { }`) when you are finished. `copyPlanesToByteArray()` is always safe. It copies into a fresh array the moment you call it.

!!! note
    The native `AVFrame*` pointer is deliberately not exposed in common code. You interact with frames only through `info`, `copyPlanesToByteArray()`, `copy()`, and `encodeImage()`.

## Seeking

`seekMicros(micros)` is a `suspend` function that repositions the demuxer to (approximately) the requested time, so call it from a coroutine. FFmpeg seeks to the nearest keyframe at or before the target, so the next frames you decode may start slightly earlier than the exact microsecond you asked for:

```kotlin
source.seekMicros(30_000_000)   // jump to ~30 seconds
source.decodedFrames(video).collect { frame ->
    // frames from the keyframe at or before 30s onward
}
```

Seeking affects the shared demuxer position, so it influences every flow you collect afterward. Seek before you start collecting, not in the middle of an active flow.

## Thumbnails

To read a single frame at a point in time, use `extractFrame(atMicros, stream)`, which is also a `suspend` function. It seeks, decodes forward to the target, and returns one `Frame`. Pass a specific `stream`, or leave it null to use the primary video track:

```kotlin
val thumb = source.extractFrame(atMicros = 90_000_000)   // one frame at 90s
```

Pair it with `encodeImage(codec)` to get JPEG or PNG bytes you can write to disk. `encodeImage` re-encodes the frame as a still image and returns the encoded bytes:

```kotlin
import io.github.yuroyami.kiteffmpeg.CodecId

MediaSource.open("input.mp4").use { source ->
    source.extractFrame(atMicros = 90_000_000).use { frame ->
        val jpeg = frame.encodeImage(CodecId.Mjpeg)   // or CodecId.Png
        writeFile("thumb.jpg", jpeg)
    }
}
```

`CodecId.Mjpeg` produces JPEG bytes; `CodecId.Png` produces PNG bytes. Both `MediaSource` and the extracted `Frame` are `AutoCloseable`, so the `use { }` blocks above release everything once the bytes are written.

!!! tip
    `extractFrame` does its own seek internally. You do not need to call `seekMicros` first.

## Error handling

Decode calls surface FFmpeg failures as an `FFmpegException` carrying an `FFmpegError`. The error is either an `AvError` (a concrete `AVERROR_*` from libav, with its `code`) or an `Internal` invariant failure on the library side:

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.FFmpegError

try {
    MediaSource.open("missing.mp4").use { source ->
        source.decodedFrames(source.primaryVideo!!).collect { /* … */ }
    }
} catch (e: FFmpegException) {
    when (val err = e.error) {
        is FFmpegError.FileNotFound -> println("no such file")
        is FFmpegError.InvalidData  -> println("corrupt or unrecognized input")
        is FFmpegError.Internal     -> println("internal: ${err.message}")
        else                        -> println("libav error ${err.code}: ${err.message}")
    }
}
```

## Status and platforms

The decoding contracts have Kotlin/Native, JVM/Android and `wasmJs` actuals. Native uses cinterop;
JVM and Android use opaque, generation-tagged JNI handles. A JVM consumer on a host other than
macOS arm64 gets no capabilities and typed `FFmpegError.Unsupported`, because the jar bundles only
the macOS arm64 library. Android and iOS decode real media on real phones as the engine under
[KitePlayer](https://github.com/yuroyami/KitePlayer). See
[Platform support](platforms.md) for the exact matrix and [Getting started](getting-started.md) for
the repository-local path.

The low-level decoder API also accepts an exact FFmpeg decoder name. On an Android FFmpeg build,
`source.openDecoder(stream, decoder = CodecId("h264_mediacodec"))` selects FFmpeg's named
MediaCodec decoder after the bridge has attached the app VM. It verifies that the named decoder
matches the stream before opening. KiteFFmpeg does not call Android's codec API directly, and this
selection seam is not by itself a device playback result.

## Next

- [Filtering](filtering.md): run any FFmpeg filter chain over the frames you decode.
- [Encoding & muxing](encoding-muxing.md): turn frames back into an output file.
- [Transcoding](transcoding.md): the one-call decode → filter → encode → mux pipeline.
- Full signatures: the [API reference](https://yuroyami.github.io/KiteFFmpeg/api/).
