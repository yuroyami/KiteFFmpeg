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

## Opening a live network stream

The same `open` takes the live addresses this build carries a protocol for: a `udp://` or `rtp://`
stream, an `rtsp://` camera, an `rtmp://` feed and a raw `tcp://` stream, as well as an SDP file
that describes an RTP session. A read waits for the sender. None of them reaches the web, where a
page has no raw sockets.

```kotlin
val camera = MediaSource.open(
    "rtsp://192.168.1.20/stream1",
    DemuxOptions(options = mapOf("rtsp_transport" to "tcp")),
)
```

- `rtsp_transport` chooses `tcp` or `udp`. Over TCP the media travels inside the connection the
  camera already answers on, which is what gets through a firewall.
- An SDP file read from disk names the protocols its session needs. FFmpeg lets an input opened
  from a file reach only `file`, `crypto` and `data`, so the open lists the rest itself:
  `DemuxOptions(protocolWhitelist = setOf("file", "udp", "rtp"))`.
- `rtmps://`, `https://` and `srt://` are not in the build. Each fails the open with
  `FFmpegError.ProtocolNotFound`.

A wider protocol list does not widen what a playlist reaches. Without a nested opener, FFmpeg's HLS
reader opens only `file`, `http` and `data` addresses, whatever else the build carries, and an
input opened from a file reaches only the three protocols above. Any other open that must reach no
further than it needs can say so with `protocolWhitelist`, which holds every nested open to the same
list.

The tests prove the RTSP demuxer by publishing to it with the `ffmpeg` command line over UDP and
over TCP. No camera, which is the server the demuxer dials, runs in them.

## Opening bytes from your own code

`MediaSource.open(io)` demuxes whatever a `MediaByteSource` reads, with no path. Use it when the
bytes come from your own HTTP client, a cache or an encrypted store. The source must block until it
has bytes, and the returned `MediaSource` closes it.

Three optional parameters say where the bytes come from:

- `url` is the address the bytes came from. FFmpeg recognises formats by it, as it does by a file
  name, and resolves relative addresses inside the media against it.
- `mimeType` is the type the bytes arrived with, such as a server's `Content-Type`. FFmpeg's probe
  uses it.
- `nestedOpener` is a `MediaByteOpener`. It opens the other addresses that the media names.

### HLS through your own HTTP client

An HLS playlist names its segments, its variant playlists and its keys by address. FFmpeg asks
`nestedOpener` for each one, and the opener answers with a `MediaByteSource`, or with null to
refuse the address. That is how an https playlist plays: the build has no https of its own, so
your client fetches every address.

!!! warning
    The addresses come from the playlist, and the playlist is untrusted input. With an opener,
    FFmpeg does not check the scheme of an address, so the opener decides what opens. Open only the
    schemes and hosts you expect, and return null for everything else.

```kotlin
val playlist = MediaSource.open(
    io = fetch("https://cdn.example/live/index"),
    url = "https://cdn.example/live/index",
    mimeType = "application/vnd.apple.mpegurl",
    nestedOpener = { address ->
        if (address.startsWith("https://cdn.example/")) fetch(address) else null
    },
)
```

Here `fetch` stands for your own HTTP client, and it returns a `MediaByteSource`.

- Every address reaches the opener absolute, already resolved against `url`.
- `mimeType` matters when `url` does not end in `.m3u8` or `.m3u`. Without it, the probe does not
  recognise the playlist and the open fails.
- An AES-128 segment reaches the opener as the address of its encrypted bytes. The key comes through
  the opener too, and KiteFFmpeg decrypts the segment.
- A `data:` address never reaches the opener. FFmpeg reads the bytes inside it itself.
- FFmpeg still checks each segment's file extension against the format it finds. A segment address
  with no media extension is refused, unless you pass `options = mapOf("extension_picky" to "0")`.
- A subtitle rendition is read only once a packet reader asks for it. Turned on in the middle of a
  cue, it delivers that cue and then the ones after it, never the ones that have already ended.
- A playlist of WebM or Matroska segments seeks after it has been read to its end, as MP4 and
  MPEG-TS segments do.

The opener runs on the thread that drives the demuxer, and it may block. The `MediaSource` closes
every source the opener returned.

On the web, a read cannot wait for the network, so the opener has to answer at once. A page can
serve the bytes it already holds, and a Web Worker, where a synchronous `XMLHttpRequest` is still
allowed, can fetch each address as it is asked. Each source the opener returns is read whole into
the codec module's memory and closed straight away, so a segment is held in memory while FFmpeg
reads it, up to 512 MB per source. `url` and `mimeType` reach the probe as on the other platforms.

The `MediaByteSource` handed to `open` itself is read on demand in a Web Worker: FFmpeg calls its
`read` and `seek` as it needs bytes, so a source that answers with synchronous range requests plays
after its first few reads, with no size cap and no copy of the whole file, and it is closed when the
`MediaSource` closes. On a page's main thread, where nothing may block, it is read whole into the
codec module's memory during the open instead, up to 512 MB, and closed then.

## Streams

Every input carries a list of `StreamInfo`. Each entry describes one track: its index, type, codec, and time-base. Iterate the full list, or read a primary track directly:

```kotlin
for (stream in source.streams) {
    println("[${stream.index}] ${stream.type} ${stream.codec.name}")
}

val video = source.primaryVideo   // StreamInfo?: the primary video track, or null
val audio = source.primaryAudio   // StreamInfo?: the primary audio track, or null
```

`primaryVideo` and `primaryAudio` are nullable. An audio-only file has no `primaryVideo`, so guard for null before you decode. A file whose only picture is its cover art does have one: `primaryVideo` skips cover art when another video stream exists and returns the cover art otherwise.

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
    frame.use {
        val info = it.info
        println("frame pts=${info.pts} (${info.ptsSeconds}s) ${info.width}x${info.height}")
    }
}
```

The flow is cold. Nothing decodes until you collect, and each fresh collection restarts from the demuxer's current position. The loop drains the decoder correctly at end-of-stream, so you receive every buffered frame before the flow completes.

!!! note "Best-effort timestamps"
    Each frame's `pts` is promoted from FFmpeg's `best_effort_timestamp` (the same rule `ffmpeg.c` uses), so files with missing or irregular pts still decode with usable timestamps. When a frame genuinely has no timestamp, `info.hasPts` is false and `info.pts` equals `FrameInfo.NOPTS`. The full timestamp contract is in [About → Timestamp handling](about.md#timestamp-handling).

## Decoding several streams in one pass

If you need both video and audio, do not open two flows. A `MediaSource` wraps one `AVFormatContext`, whose demuxer reads from one position, so only one decode flow may collect at a time. A second `decodedFrames` collection that starts while the first runs throws `IllegalStateException`. `decodeStreams(streams)` is the way to decode several streams together. It runs a single demux pass and interleaves frames from every requested stream into one `Flow<Frame>`:

```kotlin
val wanted = listOfNotNull(source.primaryVideo, source.primaryAudio)

source.decodeStreams(wanted).collect { frame ->
    frame.use {
        when (it.info.type) {
            MediaType.Video -> handleVideo(it)
            MediaType.Audio -> handleAudio(it)
            else -> {}
        }
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

To reuse one array for every frame, size it with `planesByteCount()` and copy into it with `copyPlanesInto`. The copy writes the same bytes from index 0 and returns their count. It refuses an array that is too short with an `FFmpegException`:

```kotlin
var planes = ByteArray(0)
source.decodedFrames(video).collect { frame ->
    frame.use {
        val size = it.planesByteCount()
        if (planes.size < size) planes = ByteArray(size)
        it.copyPlanesInto(planes)
        // the frame's bytes are planes[0 until size]
    }
}
```

### Frame ownership

Frames emitted by the flow APIs (`decodedFrames`, `decodeStreams`, `FilterGraph.process`) are **owned by you**: each one stays valid until you `close()` it, so `toList()` and holding frames in a list are safe. Internally these are O(1) reference-counted clones of the decoder's landing frame, so no pixel copies are made.

`buffer()`, `flowOn`, `conflate` and `produceIn` are **not** safe for frames. They queue frames in a channel that cannot close them, so a flow cancelled part way drops the queued frames without releasing them. Use `bufferFrames()` to decode ahead, and pass it a `context` where you would have used `flowOn`.

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
    The native `AVFrame*` pointer is deliberately not exposed in common code. You interact with frames only through `info`, `copyPlanesToByteArray()`, `copyPlanesInto()`, `copy()`, and `encodeImage()`.

### Dolby Vision

A Dolby Vision stream is an ordinary HEVC or AV1 stream, the base layer, with a reference processing unit (the RPU) on every frame that says how to turn the base layer into the picture that was graded. `stream.video?.dolbyVision` holds the configuration record the container declares, and null means the stream is not Dolby Vision.

The first question is whether the base layer is a picture of its own. For profiles 8.1, 8.4 and 7 it is an HDR10 or HLG picture, and showing it as it is gives a correct image. For profile 5, which most streaming services deliver, and profile 10.0, it is coded in Dolby's IPT colour space and shows green and purple until it is composed:

```kotlin
val dv = stream.video?.dolbyVision
if (dv != null && !dv.baseLayerPlaysAlone) {
    // Every frame needs composeDolbyVision() before it means anything.
}
```

FFmpeg's decoders parse the RPU on their own and attach it to each frame, whether they decode in software or hand the picture to hardware. `frame.dolbyVision()` reads it: the source's darkest and brightest level, and the brightness of the frame's scene when the stream carries it, each as a 12-bit PQ code that `DolbyVisionMetadata.nitsOfPq` turns into nits. A tone mapper that knows the scene's peak keeps more of a dark scene than one that knows only the title's.

`frame.composeDolbyVision()` returns a new frame that is ordinary HDR10: 10-bit 4:2:0 in BT.2020 with the PQ curve, limited range, chroma sited left, the source's range as its mastering display and no Dolby Vision metadata left on it. Any renderer that shows HDR10 shows it. It returns null for a frame without an RPU, and the source frame is untouched:

```kotlin
frame.composeDolbyVision()?.use { hdr10 ->
    render(hdr10)
}
```

The composition runs on the CPU and costs about 70 ms for a 1080p frame on one core of a 2.1 GHz Xeon, so a player spreads it over several threads. `beginDolbyVisionComposition()` returns a `DolbyVisionComposition` whose bands of rows may run at the same time, as long as they do not overlap and each starts on an even row:

```kotlin
frame.beginDolbyVisionComposition()?.use { composition ->
    val bands = 4
    val rows = (composition.height / bands + 1) and 1.inv()
    coroutineScope {
        (0 until composition.height step rows).forEach { start ->
            launch(Dispatchers.Default) { composition.composeRows(start, minOf(start + rows, composition.height)) }
        }
    }
    composition.finish().use { hdr10 -> render(hdr10) }
}
```

A few things to know:

- The frame must be in memory. Download a hardware frame with `downloadFromHardware()` first.
- Reshaping happens at each chroma sample, and the result is interpolated to every pixel. Held against libplacebo's composition of the same clip, every sample landed within 4 codes, with a mean difference under half a code.
- A profile 7 stream with a full enhancement layer adds a residual that FFmpeg does not decode, so its composition is the base layer's share of the picture. `usesEnhancementLayer` says when a frame is one of those.
- Built against an FFmpeg older than 7.0, which exports no extension blocks, `sceneBrightness` is always null and the composed picture carries no content light level.

## Subtitles

`openSubtitleDecoder(stream)` decodes a subtitle stream. Blu-ray (PGS), DVB and DVD subtitles decode to images, and the text formats decode to text. It belongs to the low-level API, so opt in with `@OptIn(KiteFFmpegLowLevelApi::class)`. Read the packets with a `PacketReader` and hand each one to `decode`:

```kotlin
@OptIn(KiteFFmpegLowLevelApi::class)
fun showSubtitles(path: String) = MediaSource.open(path).use { source ->
    val stream = source.streams.first { it.type == MediaType.Subtitle }
    source.openSubtitleDecoder(stream).use { decoder ->
        source.openPacketReader(listOf(stream)).use { reader ->
            while (true) {
                val packet = reader.read() ?: break
                val subtitle = packet.use { decoder.decode(it) } ?: continue
                subtitle.images.forEach { draw(it.x, it.y, it.width, it.height, it.rgba) }
            }
        }
    }
}
```

A `Subtitle` holds:

| Field | Meaning |
|---|---|
| `startMicros` | When it starts, on the stream's own timeline. Null when the packet had no timestamp. |
| `endMicros` | When it ends. Null when the stream does not say: a Blu-ray subtitle stays until the next one. |
| `canvasWidth`, `canvasHeight` | The picture the subtitle was authored for. 0 means the video's own size. |
| `images` | Positioned images in canvas pixels, as premultiplied RGBA with no row padding. |
| `texts` | For a text format, each rectangle as an ASS event: ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text. |

Three rules to know:

- `decode` returns null for a packet that completes no subtitle. A Blu-ray stream sends its palette and its image as separate packets before the one that shows them.
- A subtitle with no images and no texts clears the screen. That is how a Blu-ray stream ends a line.
- Scale the canvas onto your output to place the images, and call `flush()` after a seek.

### Closed captions

CEA-608 and CEA-708 captions reach you in one of two ways:

- **As a track of their own**, such as a MOV `c608` track. The stream's codec is `eia_608`, and `openSubtitleDecoder` decodes it to text like any other subtitle stream.
- **Inside the video**, as H.264 and HEVC SEI messages or MPEG-2 user data. The decoder attaches them to the frame they arrived with, and `frame.closedCaptions()` returns them as the cc_data triplets of ATSC A/53 part 4: three bytes per caption pair. It returns null for a frame that carries none.

## Seeking

`seekMicros(micros)` is a `suspend` function that repositions the demuxer to (approximately) the requested time, so call it from a coroutine. FFmpeg seeks to the nearest keyframe at or before the target, so the next frames you decode may start slightly earlier than the exact microsecond you asked for:

```kotlin
source.seekMicros(30_000_000)   // jump to ~30 seconds
source.decodedFrames(video).collect { frame ->
    frame.use { /* frames from the keyframe at or before 30s onward */ }
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
JVM and Android use opaque, generation-tagged JNI handles. The JVM jar bundles the library for
macOS arm64, Linux x64, Linux arm64 and Windows x64; on any other host a JVM consumer gets no
capabilities and a typed `FFmpegError.Unsupported`. Android and iOS decode real media on real phones as the engine under
[KitePlayer](https://github.com/yuroyami/KitePlayer). See
[Platform support](platforms.md) for the exact matrix and [Getting started](getting-started.md) for
the repository-local path.

The low-level decoder API also accepts an exact FFmpeg decoder name. On an Android FFmpeg build,
`source.openDecoder(stream, decoder = DecoderId.H264MediaCodec)` selects FFmpeg's named
MediaCodec decoder after the bridge has attached the app VM. It verifies that the named decoder
matches the stream before opening. KiteFFmpeg does not call Android's codec API directly.
KitePlayer selects `h264_mediacodec` and `hevc_mediacodec` this way and plays with them on
`arm64-v8a` phones.

## Next

- [Filtering](filtering.md): run any FFmpeg filter chain over the frames you decode.
- [Encoding & muxing](encoding-muxing.md): turn frames back into an output file.
- [Transcoding](transcoding.md): the one-call decode → filter → encode → mux pipeline.
- Full signatures: the [API reference](https://yuroyami.github.io/KiteFFmpeg/api/).
