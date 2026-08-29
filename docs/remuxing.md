# Remuxing

Rewrite a media file into a different container without touching the encoded streams. Remuxing moves the same packets from one wrapper to another (mp4, mkv, mov), so it runs in seconds, produces bit-exact output, and never decodes or re-encodes a single frame.

## Overview

`Remuxer.remux(input, output)` opens the input via libavformat, reads its packets, and writes them straight into a new container chosen from the output file's extension. The encoded bitstream is copied verbatim: the pixels and samples in the output are the same bytes that were in the input. Only the container framing and the packet timestamps (rescaled onto the new container's time-base) change.

```kotlin
import io.github.yuroyami.kiteffmpeg.Remuxer

// mp4 -> mkv, lossless, runs in seconds
Remuxer.remux("input.mp4", "output.mkv")
```

The full signature:

```kotlin
suspend fun remux(
    input: String,
    output: String,
    streamIndices: List<Int>? = null,
    startMicros: Long = 0,
    endMicros: Long = Long.MAX_VALUE,
    metadata: Map<String, String> = emptyMap(),
    onProgress: ((packetsWritten: Long) -> Unit)? = null,
): Unit
```

| Parameter | Purpose |
|---|---|
| `input` | Path to the source media file. |
| `output` | Path to write. The container is inferred from the extension (`.mp4`, `.mkv`, `.mov`). |
| `streamIndices` | Which input streams to carry over. `null` copies every stream. |
| `startMicros` | Start of the copied range, in microseconds. Snapped back to the preceding keyframe. |
| `endMicros` | End of the copied range, in microseconds. Defaults to no upper bound. |
| `metadata` | Container-level metadata to set on the output, for example `mapOf("title" to "Episode 1")`. |
| `onProgress` | Optional callback invoked with the running count of packets written. |

`remux` is a `suspend` function, so call it from a coroutine. It uses constant memory regardless of how long the input runs, because it streams packets one at a time rather than buffering the file.

!!! note
    The output container is chosen entirely from the output path's extension. To rewrite an mp4 as a Matroska file, write to `output.mkv`. To repackage into a QuickTime movie, write to `output.mov`.

## When to remux versus transcode

Remuxing is the right tool when the streams you already have are acceptable and only the container needs to change. Transcoding is required when the encoded data itself must change.

| You want to... | Use |
|---|---|
| Change the container (mp4 to mkv, mov to mp4) | [`Remuxer.remux`](#overview) |
| Drop a stream or keep only a subset | [`Remuxer.remux`](#selecting-streams) with `streamIndices` |
| Cut a clip without re-encoding (keyframe-aligned) | [`Remuxer.remux`](#keyframe-snapped-trim) with `startMicros` / `endMicros` |
| Edit container metadata only | [`Remuxer.remux`](#setting-metadata) with `metadata` |
| Resize, change codec, change bitrate, or apply a filter | [`Transcoder.transcode`](transcoding.md) |
| Cut a frame-exact clip (any start time, not just keyframes) | [`Transcoder.transcode`](transcoding.md) with `startMicros` / `endMicros` |

The deciding question is whether the encoded bitstream has to be rebuilt. Remuxing keeps every packet exactly as it was. Transcoding decodes, processes, and re-encodes, which costs time and CPU and is lossy for lossy codecs. If you do not need to touch the pixels or samples, remux.

!!! tip "Need a frame-exact cut?"
    Remux trim snaps the start to the nearest preceding keyframe, because copied packets cannot begin mid-GOP. If you need the clip to start on an exact, arbitrary timestamp, use [`Transcoder.transcode`](transcoding.md) with the same `startMicros` / `endMicros`. It re-encodes from the start point, so the cut is frame-exact, at the cost of decode and encode.

## Lossless container rewrite

The simplest call copies every stream into a new container. This is the fastest operation in the library: no decode, no encode, just packet copy and timestamp rescale.

```kotlin
// Repackage an mp4 as Matroska
Remuxer.remux("input.mp4", "output.mkv")

// Or the other way
Remuxer.remux("input.mkv", "output.mp4")

// Into a QuickTime movie
Remuxer.remux("input.mov", "output.mp4")
```

The output is bit-exact for the media data: the H.264, HEVC, AAC, or Opus packets in `output.mkv` are byte-identical to those in `input.mp4`. Quality is unchanged because nothing was re-encoded.

!!! note "Container compatibility"
    Not every codec fits in every container. A stream copied from a permissive container into a stricter one can fail if the target format does not accept that codec. When the muxer rejects a stream, `remux` raises an `FFmpegException`. See [Errors](#errors) below.

### Watching progress

Pass `onProgress` to observe the packet count as the rewrite runs. The callback receives the running total of packets written.

```kotlin
Remuxer.remux(
    input = "input.mp4",
    output = "output.mkv",
    onProgress = { packets -> println("wrote $packets packets") },
)
```

Because remuxing copies packets rather than encoding frames, progress is reported in packets written, not frames. For a typical file this completes too quickly to need a progress bar, but the callback is there for large inputs.

## Selecting streams

By default every stream is carried over. Pass `streamIndices` to keep only the streams you want, using the indices from [`MediaSource.streams`](decoding.md).

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource

MediaSource.open("input.mkv").use { src ->
    val video = src.primaryVideo ?: error("no video stream")
    val audio = src.primaryAudio ?: error("no audio stream")

    // Keep only the primary video and audio, drop everything else
    Remuxer.remux(
        input = "input.mkv",
        output = "output.mp4",
        streamIndices = listOf(video.index, audio.index),
    )
}
```

Every entry in `streamIndices` is a `StreamInfo.index` value from the source. Streams you omit are not written to the output. This is how you drop a second audio track, a subtitle stream, or a data stream that the target container will not accept.

## Keyframe-snapped trim

Pass `startMicros` and `endMicros` to copy only a time range. Because remuxing copies encoded packets and a video packet can only be decoded from a keyframe, the start is snapped back to the nearest preceding keyframe.

```kotlin
// Copy roughly 60s to 120s, no re-encode
Remuxer.remux(
    input = "input.mp4",
    output = "clip.mp4",
    startMicros = 60_000_000,
    endMicros   = 120_000_000,
)
```

The result keeps the start on a keyframe boundary, so the clip may begin slightly earlier than the exact `startMicros` you asked for. The output timeline is preserved relative to the copied packets. This is the copy-mode equivalent of `ffmpeg -ss ... -to ... -c copy`.

!!! warning "Start lands on a keyframe, not your exact timestamp"
    With stream copy there is no way to begin in the middle of a GOP, so `startMicros` is snapped to the keyframe at or before it. The first frames of the clip are the frames between that keyframe and your requested start. If you need the clip to start on the exact frame, [transcode the range](transcoding.md) instead.

`endMicros` defaults to `Long.MAX_VALUE`, meaning "to the end of the file". To copy from a point to the end, set only `startMicros`:

```kotlin
// From 60s to the end, lossless
Remuxer.remux("input.mp4", "tail.mp4", startMicros = 60_000_000)
```

## Setting metadata

The `metadata` map writes container-level metadata onto the output. Keys are standard container metadata names such as `title`, `artist`, or `comment`.

```kotlin
Remuxer.remux(
    input = "input.mp4",
    output = "output.mkv",
    metadata = mapOf(
        "title"   to "Episode 1",
        "comment" to "Remuxed with KiteFFmpeg",
    ),
)
```

You can combine metadata with trimming and stream selection in a single call.

## Worked example: trim, select, and tag in one pass

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.Remuxer

MediaSource.open("input.mkv").use { src ->
    val video = src.primaryVideo ?: error("no video stream")
    val audio = src.primaryAudio ?: error("no audio stream")

    Remuxer.remux(
        input  = "input.mkv",
        output = "highlight.mp4",
        streamIndices = listOf(video.index, audio.index),  // drop extra tracks
        startMicros = 90_000_000,                           // ~90s, keyframe-snapped
        endMicros   = 120_000_000,                          // ~120s
        metadata    = mapOf("title" to "Highlight"),
        onProgress  = { packets -> println("$packets packets") },
    )
}
```

This opens the source once, keeps only the primary video and audio, copies the keyframe-aligned range from 90s to 120s into an mp4, tags it, and reports progress. No frame is decoded or re-encoded.

## Errors

Failures surface as `FFmpegException`, carrying an `FFmpegError`:

- Semantic subclasses (`FFmpegError.FileNotFound`, `FFmpegError.MuxerNotFound`, `FFmpegError.InvalidData`, and more) classify the common `AVERROR_*` codes. Two examples are a codec that cannot be muxed into the chosen container, and an input that cannot be opened.
- `FFmpegError.AvError` carries any code without a dedicated category.
- `FFmpegError.Internal` signals a library-side invariant failure.

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpegException

try {
    Remuxer.remux("input.mp4", "output.mov")
} catch (e: FFmpegException) {
    println("remux failed: code=${e.code} ${e.error}")
}
```

A common cause is a codec the target container does not accept. If `remux` rejects a stream, either drop it with [`streamIndices`](#selecting-streams), choose a container that accepts it, or [transcode](transcoding.md) the stream to a compatible codec.

## See also

- [Transcoding](transcoding.md): re-encode, resize, filter, and frame-exact trim.
- [Decoding & frames](decoding.md): `MediaSource`, stream inspection, and stream indices.
- [Encoding & muxing](encoding-muxing.md): `MediaSink` and per-stream copy with `addCopyStream`.
- [Recipes](recipes.md): copy-paste patterns for common tasks.
- [API reference](https://yuroyami.github.io/KiteFFmpeg/api/): full signatures for every type.
