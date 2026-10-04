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
over TCP, and they dial a small RTSP server of their own that ends a session after two silent
seconds, as a strict camera does.

### Pausing a live stream

```kotlin
camera.pause()             // the camera stops sending
while (viewerIsPaused()) {
    delay(1_000)
    camera.pause()         // keeps the paused session alive
}
camera.resume()            // the camera plays on, from the live edge
```

`pause` asks the server to stop sending, so a paused viewer costs neither side any bandwidth, and
`resume` asks it to play on, which for a live stream is the live edge rather than the moment of the
pause. An `rtsp://` stream sends PAUSE and PLAY, and an `rtmp://` feed sends RTMP's pause and
unpause. Every other source answers false and nothing changes, so a player can pause whatever it
plays without asking what it is first.

A camera ends a session that hears nothing for its timeout, which it states as the session starts,
sixty seconds unless it says otherwise and a few seconds on some cameras. FFmpeg sends its keepalive
only from inside a read, and a paused player does not read, so keep calling `pause` while paused:
each call sends the keepalive once half that timeout has passed since the last request, and does
nothing in between. A `resume` after the session ended throws, with the server's 454 Session Not
Found, and the source stays paused; open the address again for a new session. The keepalive comes
from an FFmpeg patch, so it reaches a platform only with an FFmpeg tree built from it.

Stop reading before you pause, because a read then waits for media the server no longer sends, and
call neither from another thread while a read or another call runs.

## Opening bytes from your own code

`MediaSource.open(io)` demuxes whatever a `MediaByteSource` reads, with no path. Use it when the
bytes come from your own HTTP client, a cache or an encrypted store. The source must block until it
has bytes, and the returned `MediaSource` closes it.

Three optional parameters say where the bytes come from:

- `url` is the address the bytes came from. FFmpeg recognises formats by it, as it does by a file
  name, and resolves relative addresses inside the media against it, unless the source names its
  `location`, as after a redirect.
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

- Every address reaches the opener absolute, already resolved against the address of the playlist
  that names it, which for the playlist you open is `url`.
- A source that came through a redirect names the address the redirect led to in
  `MediaByteSource.location`. The addresses inside it then resolve against that, as they would if
  FFmpeg's own `http` had followed the redirect, so a playlist that a CDN moved to another host
  asks for its segments, keys and variants on that host. This holds for the source handed to
  `open` and for every source the opener returns. The location is read once, as the source opens,
  so follow the redirects before you return the source; a getter that throws fails that address,
  and the open carries its exception as the cause. `url` still names the input.
- `mimeType` matters when `url` does not end in `.m3u8` or `.m3u`. Without it, the probe does not
  recognise the playlist and the open fails.
- An AES-128 segment reaches the opener as the address of its encrypted bytes. The key comes through
  the opener too, and KiteFFmpeg decrypts the segment.
- A `data:` address never reaches the opener. FFmpeg reads the bytes inside it itself.
- FFmpeg still checks each segment's file extension against the format it finds. A segment address
  with no media extension is refused, unless you pass `options = mapOf("extension_picky" to "0")`.
- A subtitle rendition is read only once a packet reader asks for it. Turned on in the middle of a
  cue, it delivers that cue and then the ones after it, never the ones that have already ended.
- A playlist's variables are replaced wherever the HLS specification allows them: in addresses and
  in the attributes of the variant, rendition, key and initialization section tags. A variable
  defined with `QUERYPARAM` takes its value from the query of the playlist's own address, which for
  the master playlist is `url`, so pass the address with its query. A playlist that uses a variable
  nothing defined, or defines one in a way the specification forbids, fails the open, and FFmpeg's
  log names the variable. This needs the FFmpeg patch `0011`, which the trees of 0.4.0 do not carry.
- A playlist of WebM or Matroska segments seeks after it has been read to its end, as MP4 and
  MPEG-TS segments do.

The opener runs on the thread that drives the demuxer, and it may block. The `MediaSource` closes
every source the opener returned.

On the web, a read cannot wait for the network, so the opener has to answer at once. A page can
serve the bytes it already holds, and a Web Worker, where a synchronous `XMLHttpRequest` is still
allowed, can fetch each address as it is asked. Each source the opener returns is read whole into
the codec module's memory and closed straight away, so a segment is held in memory while FFmpeg
reads it, up to 512 MB per source. `url` and `mimeType` reach the probe as on the other platforms,
and a source's `location` counts as it does there.

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

`primaryVideo` and `primaryAudio` are nullable. An audio-only file has no `primaryVideo`, so guard for null before you decode. A file whose only picture is its cover art does have one: `primaryVideo` skips cover art when another video stream exists and returns the cover art otherwise. In a source with [programmes](#programmes), `primaryAudio` is the sound of `primaryVideo`'s own channel. A live transport stream can add to the list while it plays, as [Streams that start after the open](#streams-that-start-after-the-open) shows.

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

### 360 degree and stereo video

A 360 degree video is an ordinary picture that its container says to wrap around the viewer, and a stereo video holds the views of both eyes in each picture. FFmpeg reads what the container says and applies none of it, so a renderer that draws the picture as it comes shows a stretched panorama, or both views at once. `stream.video?.spherical` and `stream.video?.stereo3d` pass it on, and each is null when the container says nothing. They come from Google's spherical video and stereoscopic boxes in MP4, the XML document of Google's first spherical box in an MP4 track, Apple's video extension box in MP4 and MOV, which FFmpeg reads from 7.1 on, and a Matroska track's `Projection` and `StereoMode`.

```kotlin
val video = stream.video ?: return
video.spherical?.let { mapping ->
    when (val projection = mapping.projection) {
        SphericalProjection.Equirectangular -> println("the whole sphere")
        is SphericalProjection.EquirectangularTile -> println("a part of the sphere, ${projection.left} of it to the left")
        is SphericalProjection.Cubemap -> println("a cube map with ${projection.padding} pixels of padding")
        else -> println(projection)
    }
    println("turned by yaw ${mapping.yaw}, pitch ${mapping.pitch} and roll ${mapping.roll} degrees")
}
video.stereo3d?.let { stereo ->
    println("${stereo.type}, ${if (stereo.inverted) "the right eye first" else "the left eye first"}")
}
```

A renderer splits each picture into the eyes' views as `stereo3d.type` says, maps each view onto the sphere as the projection says, and turns the sphere by the rotation `Ry(yaw) * Rx(pitch) * Rz(roll)` with the viewer at its centre. The angles are exactly the 16.16 fixed-point numbers FFmpeg holds, which a Matroska file's floating-point angles are rounded toward zero to fit. Google's first spherical box states its initial view as heading, pitch and roll in degrees, drawn on the same axes, and they read as yaw, pitch and roll with their signs as they are, a fraction rounded to the nearest step. That needs an FFmpeg tree built with the patch `0010`, without which all three read as 0. Apple's video extension box can say which eyes a picture holds without saying how they are packed, and then the type reads `Unspecified`. Apple's boxes also carry the eye to show in 2D, the distance between the lenses, how far to shift the views against each other, and the field of view, which the other containers do not state. A file that carries both Google's boxes and Apple's, as FFmpeg writes an MP4 under `-strict unofficial`, reads as one description whichever comes first: Google's mapping and packing, joined by Apple's primary eye, baseline and disparity adjustment when the two agree on the packing, and by Apple's field of view either way. That needs an FFmpeg tree built with the patch `0009`, as [Platforms](platforms.md) says.

### Container metadata

The container's own tag dictionary is a plain map:

```kotlin
val title = source.metadata["title"]
val artist = source.metadata["artist"]
```

### Tags that change during playback

A radio station, a chained Ogg and a live HLS stream change their tags while they play. The read
that brings a change hands it out on the first packet after it:

```kotlin
source.openPacketReader(listOf(audio)).use { reader ->
    while (true) {
        val packet = reader.read() ?: break
        packet.newContainerTags?.let { tags -> nowPlaying.at(packet.ptsMicros, tags["title"]) }
        packet.newStreamTags?.let { tags -> nowPlaying.at(packet.ptsMicros, tags["title"]) }
        decoder.send(packet)
    }
}
```

`newContainerTags` is the container's whole new set, which a station's ICY title through FFmpeg's
own `http`, an ID3 tag between ADTS frames and an FLV `onMetaData` change. `newStreamTags` is the
whole new set of that packet's own stream: the next song of a chained Ogg replaces its comments, so
a key the second song lacks is gone, and a timed ID3 packet of an MPEG-TS or HLS data stream adds to
what came before, so read that data stream to receive it. Both are null on every other packet, and
a stream's change waits for that stream's next packet, so a reader that did not select the stream
never sees it. Showing a change when its packet plays, rather than when it was read, keeps a title
from appearing seconds before its song.

`source.metadata` holds the container's latest set from the same read on, whether the reads ran in
a packet reader or a decode flow, and a decode flow drops a stream's changes because it hands out
no packet to carry them. `StreamInfo.metadata` keeps what the stream said at open, so a stream you
already hold still selects.

The open of a seekable MPEG-TS file reads its end to measure the duration, which applies a timed ID3
stream's last tag, so that stream's `StreamInfo.metadata` can already hold the last one. Reading then
starts again from the beginning, and every timed ID3 packet brings its own set as it comes.

### A station read through your own HTTP client

FFmpeg's own `http` asks a station for its titles and takes them out of the audio, but a station you
read through your own client reaches FFmpeg as bytes alone. Ask for the titles with the header
`Icy-MetaData: 1`, read the interval between title blocks from the `icy-metaint` response header,
take each block out of the bytes before `read` hands them over, and hand the title over from
`takeTags`, which is asked after every read that brought bytes:

```kotlin
override fun read(into: ByteArray, offset: Int, length: Int): Int {
    if (untilBlock == 0) {
        // One length byte times 16, then text such as StreamTitle='Artist - Song';
        streamTitle(readBlock())?.takeIf { it != lastTitle }?.let { title ->
            lastTitle = title
            pending = mapOf("title" to title)
        }
        untilBlock = metaInterval
    }
    val n = body.read(into, offset, minOf(length, untilBlock))
    if (n > 0) untilBlock -= n
    return n
}

override fun takeTags(): Map<String, String>? = pending.also { pending = null }
```

The tags belong at the first byte of the read that reported them, so stopping each read at the
next block, as above and as FFmpeg's `http` does, puts the title on the first packet of the song
after it, as `newContainerTags`, and in `source.metadata` from then on. A source that reads past
the block puts the title early by what it read past. Keys go through as you give them: report
`title` for a title to read as one, or `StreamTitle` to read as FFmpeg's `http` reports it. Report
each change once. Tags reported during the open's own reads are in `source.metadata` when the open
returns, and an exception thrown from `takeTags` fails the read it followed, with that exception as
the cause.

Only the source handed to `open` is asked. FFmpeg reads no tags from a source a nested opener
returns, because the HLS reader reads each segment through an input of its own.

On the web a station streams in a Web Worker, where the source is read on demand and asked as it is
read. On a page's main thread a source is read whole during the open, which needs a size a station
does not have; a source of known size there is asked after each read of that drain, and each answer
reaches FFmpeg when it reads the byte the answer belongs at, again every time it reads that byte.

### Programmes

A transport stream from a DVB tuner or an IPTV multiplex can carry several channels at once, each with its own picture, sound and subtitles, and the flat stream list cannot say which sound belongs to which picture. `source.programs` says it, as the container's programme tables state it:

```kotlin
for (program in source.programs) {
    val streams = source.streams.filter { it.index in program.streamIndexes }
    println("${program.serviceName ?: "programme ${program.number}"}: ${streams.map { it.type }}")
}
```

A `Program` holds FFmpeg's id for it, the programme number the container states, which in a transport stream is the service id a channel guide names the channel by, the indexes of its streams, and its own tags, where a transport stream keeps the channel's name and provider under `service_name` and `service_provider`. FFmpeg also makes one programme for each variant of an HLS master playlist, with the variant's bit rate under `variant_bitrate`, and one holding every stream of a DASH presentation, and neither states a number. MP4, Matroska and the other containers without such tables have none, so the list is empty. A stream can sit in two programmes, as a sound that two channels share does, or in none, as a transport stream's stream does when FFmpeg found it by its packets rather than in a programme table. A channel that only the service description table names has a name but no number and no streams. `MediaSource.probe(path).programs` reads the same list without keeping the source open.

A `TrackSelector` with your own language preferences keeps a channel together when you hand it the picture and the programmes:

```kotlin
val selector = TrackSelector(preferredAudioLanguages = listOf("en"))
val video = selector.selectVideo(source.streams)
val audio = selector.selectAudio(source.streams, source.programs, video)
```

The picture fixes the channel, and the language preference chooses only among that channel's sound, so a preference for English never pairs one channel's picture with another channel's English sound. When the picture's programmes hold no sound, a sound in no programme is taken, and otherwise none. The `selectAudio` that takes the streams alone knows nothing of programmes.

### Streams that start after the open

A live transport stream can start its sound after its picture, add subtitles at a programme boundary or move a channel's sound to a new stream, and FFmpeg adds such a stream while it reads, long after the open. `source.streams` grows when it does, in index order and never shorter, and `source.programs` follows the programme tables, so a stream the container stopped carrying shows by leaving its programme. The first packet a reader hands out after a change carries the whole new list as `newStreams` or `newPrograms`:

```kotlin
source.openPacketReader(listOf(video)).use { reader ->
    var known = source.streams
    while (true) {
        val packet = reader.read() ?: break
        packet.newStreams?.let { streams ->
            val sound = streams.drop(known.size).firstOrNull { it.type == MediaType.Audio }
            if (sound != null && !playingSound) {
                reader.reselect(listOf(video, sound))
                playingSound = true
            }
            known = streams
        }
        route(packet)
    }
}
```

FFmpeg may hand out the new stream's first packets before the packet that announces it, so they are held: add the stream with `reselect` before the next `read`, and that read hands them out first, so the stream starts from its first packet. They come after the announcing packet although FFmpeg read them before it, which a player that queues each stream apart does not notice. Read on or seek without adding the stream and they are dropped, and the stream is skipped as any stream the reader does not select is. At most 16 MB of them are held, the oldest going first, which matters only when the selected streams fall silent for a long time and no packet can announce the new one.

An entry read before FFmpeg had any packet of its stream says only what the programme table could say, so a late MP2 sound reads as MP3 with no sample rate and no channels. FFmpeg's parser corrects that at the stream's first packet, the entry is read again then, and the corrected list rides the next packet handed out as `newStreams` too, which is the stream's own first packet once you added it. So find a new stream by its index rather than by comparing entries, and open its decoder at its first packet, from the entry the latest list holds. The same goes for a seekable file whose open found a stream by reading ahead without parsing it. The entry a correction replaced still names its stream, so a selection or a decoder made from it stays valid.

A decode flow keeps both lists current but hands out no packet, so the first packet a reader hands out after it carries what changed during it.

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
- Built against an FFmpeg older than 7.1, which exports no extension blocks, `sceneBrightness` is always null and the composed picture carries no content light level.

`frame.dolbyVisionRpu()` reads the whole RPU, for a caller that composes the picture somewhere else, such as in a shader, or wants to look inside it: the header, each component's reshaping curve in pieces between pivots, the inverse quantization of an enhancement layer's residual, and the colour matrices and signal levels. Every number is the one FFmpeg's decoder holds, which is the one `ffprobe -show_frames` prints for that frame. Coefficients are fixed-point numbers over two to the power of `header.coefficientLog2Denominator`, and `coefficientValue` turns one into a `Double`:

```kotlin
frame.dolbyVisionRpu()?.let { rpu ->
    val luma = rpu.mapping.curves[0]
    val polynomials = luma.pieces.filterIsInstance<DolbyVisionPiece.Polynomial>()
        .map { piece -> piece.coefficients.map(rpu::coefficientValue) }
    uploadLumaCurve(luma.pivots, polynomials)
}
```

Built against an FFmpeg older than 7.1, the inverse quantization's two pivots read null.

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
            // The packets have run out: take what the decoder still holds, such as a last caption.
            decoder.drain()?.images?.forEach { draw(it.x, it.y, it.width, it.height, it.rgba) }
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
- Call `drain()` when the packets run out. A CEA-608 caption decoder gives a caption only when the
  screen next changes, because only then is its end known, so the caption on screen at the end of
  the stream comes out of the drain and nothing else; teletext holds its page the same way. Every
  other decoder drains to null. Call `flush()` before decoding again.

### Closed captions

CEA-608 and CEA-708 captions reach you in one of two ways:

- **As a track of their own**, such as a MOV `c608` track. The stream's codec is `eia_608`, and `openSubtitleDecoder` decodes it to text like any other subtitle stream.
- **Inside the video**, as H.264 and HEVC SEI messages or MPEG-2 user data. The decoder attaches them to the frame they arrived with, and `frame.closedCaptions()` returns them as the cc_data triplets of ATSC A/53 part 4: three bytes per caption pair. It returns null for a frame that carries none.

## Seeking

`seekMicros(micros)` is a `suspend` function that repositions the demuxer to the keyframe at or before the requested time, so call it from a coroutine. The next frames you decode start on that keyframe, which may be slightly earlier than the exact microsecond you asked for:

```kotlin
source.seekMicros(30_000_000)   // jump to ~30 seconds
source.decodedFrames(video).collect { frame ->
    frame.use { /* frames from the keyframe at or before 30s onward */ }
}
```

Seeking affects the shared demuxer position, so it influences every flow you collect afterward. Seek before you start collecting, not in the middle of an active flow.

The keyframe is the last one that shows at or before the target, in the first video stream that is not a cover picture. That takes a check, because FFmpeg finds a keyframe by when it decodes, and in video with B-frames a keyframe decodes before it shows, while in an open group of pictures the B-frames after it show before it. FFmpeg's MP4 reader therefore picked a keyframe that showed after the target in an open group of pictures, its FLV reader did so with any B-frames, and MPEG-TS, which has no index and seeks by searching byte positions, landed among pictures whose first keyframe showed late. The seek now reads on to the keyframe it landed on and aims earlier when that one shows too late. The packets it read on the way are handed to the reads after it, so a seek FFmpeg landed right costs no extra reading. Keyframes more than 32 MB of input apart are not checked, and the seek then stays where FFmpeg put it. The audio and the other streams start where the container puts them beside the keyframe, which can be a little before it.

A `PacketReader` seeks the same way when it selects that video stream. A stream it selects after a seek and before its first read starts where the seek would have landed it; after a read, a newly selected stream joins where the demuxer has read to, as `reselect` describes.

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
