# Encoding and muxing

Use `MediaSink` to open an output file, attach encoders, and write a valid container. Muxing means writing encoded streams into a container file. This is the low-level path underneath [Transcoder](transcoding.md): you control the encoders, you feed the frames, and you decide when streams open and close.

If you only need file in, file out, use [`Transcoder.transcode`](transcoding.md) first. Use `MediaSink` when you need to drive encoders by hand: generated frames, a custom pipeline, or several encoders fed from different sources.

## The shape of an output file

A `MediaSink` is a muxer over an open output file. The workflow is always the same three phases, in order:

1. **Open** the sink and **add every encoder** (and every copy stream). The muxer's header freezes the stream list, so nothing can be added once the first frame is written.
2. **Drive** each encoder by draining a `Flow<Frame>` through it. The encoder pushes frames in, pulls packets out, and hands them to the muxer.
3. **Close** the sink. This flushes the encoders, writes the container trailer, and releases native resources.

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSink
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational

MediaSink.open("output.mp4").use { sink ->
    val video = sink.addVideoEncoder(
        VideoEncoderSpec(
            codec = CodecId.Libx264,
            width = 1280, height = 720,
            frameRate = Rational(30, 1),
            bitrateBps = 4_000_000,
        )
    )
    video.drive(frames)          // frames: Flow<Frame>
}                                // close() writes the trailer
```

The output format is inferred from the file extension. `.mp4`, `.mkv`, `.mov` and the rest are chosen for you; you never name a muxer.

!!! note "Add streams before the first frame"
    `addVideoEncoder`, `addAudioEncoder`, `addCopyStream`, and `setMetadata` all have to be called before any frame or packet reaches the muxer. The header is written once, on the first write, and it carries the full stream list and the metadata tags. After that the set is frozen.

## Adding a video encoder

`addVideoEncoder(VideoEncoderSpec)` configures and opens an encoder, returning a `VideoEncoder` handle. The spec carries the geometry, the codec, and a free-form options map:

```kotlin
val spec = VideoEncoderSpec(
    codec = CodecId.Libx264,
    width = 1920, height = 1080,
    pixelFormat = PixelFormat.Yuv420p,       // default
    frameRate = Rational(30, 1),
    bitrateBps = 6_000_000,                  // default 4_000_000
    options = mapOf("preset" to "veryfast", "crf" to "20"),
)
val encoder = sink.addVideoEncoder(spec)
```

`VideoEncoderSpec` fields:

| Field | Type | Default | Notes |
|---|---|---|---|
| `codec` | `CodecId` | required | `H264`, `Hevc`, `Av1`, `Vp9`, `Libx264`, a hardware id, etc. |
| `width` / `height` | `Int` | required | Output frame size. Match your filter output. |
| `pixelFormat` | `PixelFormat` | `Yuv420p` | Most codecs want `Yuv420p`. |
| `frameRate` | `Rational` | required | Exact fraction, e.g. `Rational(30000, 1001)` for 29.97. |
| `bitrateBps` | `Long` | `4_000_000` | Target bitrate. Ignored when you set `crf`. |
| `keyframeIntervalFrames` | `Int` | `frameRate × 2` | GOP length. Computed from the frame rate unless you override it. |
| `options` | `Map<String,String>` | empty | Codec-specific options passed straight to `av_opt_set`. |

### Per-encoder options: preset, crf, and others

The `options` map is passed through verbatim to the underlying encoder. The keys are exactly the FFmpeg option names, so anything `ffmpeg -h encoder=libx264` lists is valid:

```kotlin
VideoEncoderSpec(
    codec = CodecId.Libx264,
    width = 1280, height = 720,
    frameRate = Rational(30, 1),
    options = mapOf(
        "preset" to "slow",      // speed vs. compression
        "crf" to "18",           // constant quality (lower = better)
        "tune" to "film",
    ),
)
```

!!! tip "crf overrides bitrate"
    When you pass `crf`, libx264 and libx265 run in constant-quality mode and ignore `bitrateBps`. Use one or the other, not both. A `crf` of 18 to 23 is the usual quality band for H.264.

## Driving the encoder

A `VideoEncoder` does not take frames one at a time. You hand it a `Flow<Frame>` and it drains the whole flow:

```kotlin
suspend fun encode(sink: MediaSink, frames: Flow<Frame>) {
    val encoder = sink.addVideoEncoder(spec)
    encoder.drive(
        input = frames,
        onProgress = { count -> println("encoded $count frames") },
        progressEveryNFrames = 30,           // default
    )
}
```

`drive` pushes each frame into the encoder, pulls every packet that comes back out, hands those packets to the muxer, and flushes the encoder when the flow completes. It returns when the input flow is exhausted.

The encode core is **EAGAIN-correct**: it respects the codec's "I need more input before I can give you output" signal instead of busy-looping or dropping frames. You never see `EAGAIN`; the loop handles it.

### Timestamps are handled for you

You do not compute output timestamps. The encoder takes each incoming frame's pts (in the frame's own time-base) and rescales it onto the codec time-base. Frames that arrive with no pts at all fall back to a frame counter. Either way the output is **monotonic and zero-based**: the first written frame lands at pts 0 and timestamps only ever increase.

KiteFFmpeg does this the same way `ffmpeg.c` does, and it forces strict monotonicity at the encoder boundary. See the [transcoding guide](transcoding.md) for how trim offsets are rebased to zero on top of this.

## Adding an audio encoder

`addAudioEncoder(AudioEncoderSpec)` mirrors the video path:

```kotlin
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec

val audio = sink.addAudioEncoder(
    AudioEncoderSpec(
        codec = CodecId.Aac,
        sampleRate = 48_000,     // default 44_100
        channels = 2,            // default 2
        bitrateBps = 192_000,    // default 128_000
        options = mapOf("profile" to "aac_low"),
    )
)
audio.drive(audioFrames)         // audioFrames: Flow<Frame>
```

`AudioEncoderSpec` fields:

| Field | Type | Default | Notes |
|---|---|---|---|
| `codec` | `CodecId` | required | `Aac`, `Opus`, `Flac`, `Mp3`, `LibOpus`, `PcmS16`, etc. |
| `sampleRate` | `Int` | `44_100` | Output sample rate in Hz. |
| `channels` | `Int` | `2` | Channel count. |
| `sampleFormat` | `SampleFormat` | `None` | `None` lets the encoder pick its preferred format (`fltp` for AAC). |
| `bitrateBps` | `Long` | `128_000` | Target bitrate. |
| `options` | `Map<String,String>` | empty | Codec-specific options. |

After the encoder opens, the `AudioEncoder` handle exposes the values that were actually negotiated:

```kotlin
val audio = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId.Aac))
println(audio.sampleFormat)   // resolved from None, e.g. FltP for aac
println(audio.sampleRate)     // 44100
println(audio.channels)       // 2
println(audio.frameSize)      // 1024 for aac, 0 for codecs taking any chunk size
```

### AAC's 1024-sample framing is handled for you

AAC will not accept arbitrary chunk sizes. It wants exactly 1024 samples per frame, every frame, and the encoder rejects anything else. KiteFFmpeg handles this so you do not have to count samples by hand.

The mechanism is `frameSize`: an opened `AudioEncoder` reports the samples-per-frame the codec demands (1024 for AAC, 0 for codecs that take any chunk size). Route your audio through an [audio filter graph](filtering.md) and pin its output to that size:

```kotlin
import io.github.yuroyami.kiteffmpeg.FilterGraph
import io.github.yuroyami.kiteffmpeg.MediaSource

val source = MediaSource.open("input.mp4")
val audioStream = source.primaryAudio ?: error("no audio track")
val inAudio = audioStream.audio!!            // AudioStreamInfo: the stream's audio detail block

val audio = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId.Aac))

val graph = FilterGraph.buildAudio(
    description = "anull",
    sampleRate = inAudio.sampleRate,
    sampleFormat = inAudio.sampleFormat,
    channels = inAudio.channels,
    timeBase = audioStream.timeBase,
    outputSampleRate = audio.sampleRate,
    outputSampleFormat = audio.sampleFormat,
    outputChannels = audio.channels,
)
graph.setOutputFrameSize(audio.frameSize)   // 1024: the graph re-chunks to exact AAC frames

audio.drive(graph.process(source.decodedFrames(audioStream)))
```

The graph re-chunks the audio stream into exact 1024-sample frames before they reach the encoder. [Transcoder](transcoding.md) wires this up automatically, reading `frameSize` from the encoder and calling `setOutputFrameSize` for you. When you drive `MediaSink` by hand, this one call is what satisfies AAC's framing requirement.

!!! note "Why a filter graph for plain copy of samples?"
    Even when you are not changing the audio, the filter graph is doing real work: resampling to the encoder's negotiated format and re-chunking to the codec's frame size. `"anull"` is the no-op filter description; the resampling and chunking happen in the buffer sink regardless.

## Stream copy: `addCopyStream`

When a stream should pass through untouched, do not decode and re-encode it. `addCopyStream` declares a verbatim copy of one input stream into the output. This is FFmpeg's `-c copy`: no decode, no encode, only timestamp rescaling into the output's time-base.

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource

val source = MediaSource.open("input.mp4")
val audioStream = source.primaryAudio!!

MediaSink.open("output.mp4").use { sink ->
    val video = sink.addVideoEncoder(videoSpec)   // re-encode video
    sink.addCopyStream(source, audioStream)        // copy audio bit-exact
    // ... drive the video encoder; the copy stream is written too
}
```

`addCopyStream` returns a `CopyStream`, an opaque handle that declares the mapping. The packets themselves are pulled by [Transcoder](transcoding.md) or [Remuxer](remuxing.md); the handle just tells the muxer that this output stream exists and where its packets come from. This is also how `audioCopy = true` is implemented inside `Transcoder`.

!!! warning "Bitstream filters are not applied"
    A copy stream rescales timestamps but does not run bitstream filters. Format pairs that need one (for example H.264 in MP4 going to MPEG-TS Annex B) are not yet supported on the copy path. Re-encode those, or pick a container that accepts the source bitstream as-is. Bitstream filters are on the [roadmap](about.md).

For a whole-file lossless container rewrite (every stream copied, no encoders at all), use [`Remuxer.remux`](remuxing.md) instead. It runs in seconds.

## Hardware encode

Hardware encoders are selected by `CodecId`, the same way software encoders are. They produce the same kind of `VideoEncoder` and drive identically; only the codec id changes.

=== "macOS (VideoToolbox)"

    ```kotlin
    val spec = VideoEncoderSpec(
        codec = CodecId.H264VideoToolbox,   // or HevcVideoToolbox
        width = 1920, height = 1080,
        frameRate = Rational(30, 1),
        bitrateBps = 8_000_000,
    )
    val encoder = sink.addVideoEncoder(spec)
    ```

    Verified end-to-end on macOS arm64. `h264_videotoolbox` encodes on the Apple media engine instead of the CPU.

=== "Android (MediaCodec)"

    ```kotlin
    val spec = VideoEncoderSpec(
        codec = CodecId.H264MediaCodec,     // or HevcMediaCodec
        width = 1920, height = 1080,
        frameRate = Rational(30, 1),
        bitrateBps = 8_000_000,
    )
    val encoder = sink.addVideoEncoder(spec)
    ```

    `h264_mediacodec` targets the device's hardware encoder. See [Platform support](platforms.md) for the current Android status.

### `allow_sw` for VMs and headless machines

VideoToolbox refuses to run when there is no hardware encode block available, which is common on CI runners and virtual machines. The `allow_sw` option lets it fall back to a software path instead of failing to open:

```kotlin
VideoEncoderSpec(
    codec = CodecId.H264VideoToolbox,
    width = 1280, height = 720,
    frameRate = Rational(30, 1),
    options = mapOf("allow_sw" to "1"),
)
```

### Check before you commit

Hardware encoder availability is a runtime property of the machine and the FFmpeg build. Probe it with [`FFmpeg.hasEncoder`](https://yuroyami.github.io/KiteFFmpeg/api/) before you choose:

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteffmpeg.CodecId

val codec = if (FFmpeg.hasEncoder(CodecId.H264VideoToolbox.name)) {
    CodecId.H264VideoToolbox
} else {
    CodecId.Libx264
}
```

!!! tip "One encode core for hardware and software"
    The same EAGAIN-correct encode loop drives software and hardware encoders. There is no separate code path and no separate API: pick the `CodecId`, build the spec, call `drive`. Everything downstream is identical.

## Both streams in one sink

A real output usually carries video and audio together. Add both encoders up front, then drive them from their respective flows:

```kotlin
MediaSink.open("output.mp4").use { sink ->
    val video = sink.addVideoEncoder(
        VideoEncoderSpec(
            codec = CodecId.Libx264,
            width = 1280, height = 720,
            frameRate = Rational(30, 1),
        )
    )
    val audio = sink.addAudioEncoder(
        AudioEncoderSpec(codec = CodecId.Aac)
    )

    video.drive(videoFrames)
    audio.drive(audioFrames)
}   // close() flushes both encoders, then writes the trailer
```

The muxer interleaves packets from both encoders into the container. `close()` waits until both encoders have flushed before writing the trailer, so the file is complete and seekable.

!!! warning "Do not drive one sink's encoders from concurrent coroutines"
    All encoders attached to a `MediaSink` share the underlying muxer, and libav contexts are not thread-safe. Drive them from a single coroutine, one after the other, as shown above. When both streams come from the same input file, you can instead use `decodeStreams` (one demux pass, frames already interleaved) and route each frame to the right encoder as it arrives. That is what [Transcoder](transcoding.md) does internally. See [Concurrency](concurrency.md) for the full threading rules.

For the common case of demux one file, re-encode, and mux, the [Transcoder](transcoding.md) already orchestrates this interleaving for you, including the AAC frame-size wiring and the trim rebasing. Use raw `MediaSink` when your frames come from a source a single input file cannot describe.

## Container metadata

Tag the output before the first frame:

```kotlin
sink.setMetadata(
    mapOf(
        "title" to "Holiday clip",
        "artist" to "KiteFFmpeg",
        "comment" to "Encoded with VideoToolbox",
    )
)
```

These are written into the container header, so the call has to come before any frame is written, alongside the encoder declarations.

## Closing is not optional

`close()` does three things: it flushes every encoder (draining the packets still buffered inside the codec), it writes the container trailer (the index that makes the file seekable), and it frees the native handles. A file that is never closed is truncated and usually unplayable.

`MediaSink` is `AutoCloseable`, so the idiomatic form is Kotlin's `use`:

```kotlin
MediaSink.open("output.mp4").use { sink ->
    // add encoders, drive frames
}   // close() runs here, even on exception
```

The encoders (`VideoEncoder`, `AudioEncoder`) are also `AutoCloseable`, but `drive` flushes them when its input flow completes, so you rarely close them by hand. Closing the sink is the call that matters.

## Errors

Every libav failure surfaces as an [`FFmpegException`](https://yuroyami.github.io/KiteFFmpeg/api/) wrapping an `FFmpegError`. `FFmpegError` is a sealed hierarchy of semantic categories (`FileNotFound`, `EncoderNotFound`, `MuxerNotFound`, `InvalidData`, and more) mapped from the raw `AVERROR_*` codes. Unmapped codes arrive as `FFmpegError.AvError`. A library-side invariant violation arrives as `FFmpegError.Internal`. Every subclass exposes the numeric `code`:

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpegException

try {
    MediaSink.open("output.mp4").use { sink ->
        sink.addVideoEncoder(spec).drive(frames)
    }
} catch (e: FFmpegException) {
    println("encode failed: ${e.error} (code ${e.code})")
}
```

A common one to expect: opening a `CodecId` whose encoder is not present in the linked FFmpeg build. Probe with `FFmpeg.hasEncoder(...)` first to fail early with a clear message rather than at `addVideoEncoder`.

## See also

- [Transcoding](transcoding.md): the one-call pipeline built on top of `MediaSink`.
- [Decoding](decoding.md): produce the `Flow<Frame>` you feed to an encoder.
- [Filtering](filtering.md): scale, resample, and re-chunk frames before they reach an encoder.
- [Remuxing](remuxing.md): copy every stream into a new container with no encoders at all.
- [Recipes](recipes.md): copy-paste patterns for common encode and mux tasks.
- [API reference](https://yuroyami.github.io/KiteFFmpeg/api/): full signatures for `MediaSink`, `VideoEncoderSpec`, and `AudioEncoderSpec`.
