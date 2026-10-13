# Getting started

This page adds KiteFFmpeg to a project, sets up iOS and the web, and runs a first transcode.

## Add the dependency

FFmpeg is compiled into every published artifact, so one dependency line is the whole setup on
most targets. There is nothing to install and no Gradle plugin:

```kotlin
sourceSets.commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.5.1")
}
```

On Android the library needs `minSdk` 26, and on the JVM it needs Java 11 or later. Your users
need nothing installed either. To build KiteFFmpeg itself, see
[Building from source](building-from-source.md).

## Set up iOS

A dynamic framework needs no flags. For a static framework (`isStatic = true`), add these to Other
Linker Flags in Xcode, because Xcode does not read the flags inside the klib:

```text
-lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

FFmpeg's file reader calls `stat`, `fstat` and `lstat`, which Apple lists as file timestamp APIs.
App Store Connect refuses an upload that uses them without a declared reason. Declare
`NSPrivacyAccessedAPICategoryFileTimestamp` in the app's `PrivacyInfo.xcprivacy`, with the reasons
that apply: `C617.1` for files inside the app container, and `3B52.1` for files that the user
picked.

## Set up the web

A browser cannot link FFmpeg into the Kotlin binary, so `wasmJs` loads FFmpeg as a separate
WebAssembly module:

1. Download `kiteffmpeg-wasm-js-<version>-web.zip`. It sits beside the `wasmJs` artifact on Maven
   Central, for the version you depend on.
2. Unpack it beside your page, so that the page serves `kite.mjs`, `kite.wasm` and `licenses/`.
3. Call `KiteFFmpegWeb.load()` once, before any other KiteFFmpeg call.

```kotlin
KiteFFmpegWeb.load()                 // fetches ./kite.mjs
check(FFmpeg.identity.isAcceptable)
```

Under a bundler, instantiate the module from a plain `<script type="module">` and pass it to
`KiteFFmpegWeb.attach()` instead. A call before the module loads throws `KiteFFmpegWeb.NotLoaded`.

The web build reads, decodes and seeks. It cannot write a file, so a transcode, a remux and a
filter graph throw `FFmpegError.Unsupported` there. [Decoding frames](decoding.md) explains how a
page feeds the module its bytes, and [Asynchronous byte sources](async-byte-io.md) covers a source
that answers later, such as a Fetch response.

## Probe what your build can do

The `FFmpeg` object reports the linked library versions and tells you which encoders, decoders and
filters this build has:

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpeg

fun printCapabilities() {
    val v = FFmpeg.versions
    println("avcodec ${v.avcodec}, avformat ${v.avformat}, avfilter ${v.avfilter}")
    println("build config: ${FFmpeg.buildConfiguration}")

    println("libx264 available: ${FFmpeg.hasEncoder("libx264")}")
    println("aac available:     ${FFmpeg.hasEncoder("aac")}")
    println("h264 decoder:      ${FFmpeg.hasDecoder("h264")}")
    println("scale filter:      ${FFmpeg.hasFilter("scale")}")
}
```

Builds differ, which is why you probe. `h264_videotoolbox` exists in the macOS build but not in the
Linux one, so checking `FFmpeg.hasEncoder(...)` at run time lets you pick an encoder that is really
there.

Most public types live in `io.github.yuroyami.kiteffmpeg`. The option and filter builders, such as
`DemuxOptions`, `DecoderOptions` and `FilterChain`, live in `io.github.yuroyami.kiteffmpeg.dsl`.

## Open and inspect a file

`MediaSource.open(path)` opens an input and exposes its streams and metadata. It is
`AutoCloseable`, so wrap it in `use { }`:

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource

MediaSource.open("input.mp4").use { src ->
    println("container: ${src.formatName}")
    println("duration:  ${src.durationMicros?.let { it / 1_000_000.0 } ?: "unknown"} s")
    println("metadata:  ${src.metadata}")

    for (stream in src.streams) {
        print("  stream #${stream.index}  ${stream.type}  ${stream.codec.name}")
        stream.video?.let { print("  ${it.width}x${it.height} @ ${it.frameRate}") }
        stream.audio?.let { print("  ${it.sampleRate} Hz  ${it.channels}ch") }
        println()
    }

    // The streams you usually want:
    val v = src.primaryVideo
    val a = src.primaryAudio
}
```

Each `StreamInfo` carries an `index`, a `type` (`MediaType.Video`, `Audio`, `Subtitle` and so on),
a `codec` (`CodecId`) and a `timeBase` (`Rational`). It also carries a `video` block
(`VideoStreamInfo`) or an `audio` block (`AudioStreamInfo`). [Decoding frames](decoding.md) shows
how to read frames from a stream.

## Your first transcode

`Transcoder.transcode(...)` runs the whole pipeline in one pass: demux, decode, filter, encode and
mux. Demux means split a container file into its streams, and mux means write streams back into a
container file. The function suspends, so call it from a coroutine:

```kotlin
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec = VideoEncoderSpec(
            codec = CodecId.Mpeg4,       // in every published build
            width = 320, height = 180,
            frameRate = Rational(30, 1),
            bitrateBps = 1_500_000,
        ),
        videoFilter = "scale=320:180,format=yuv420p",
        audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),
        onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
    )
}
```

The example uses `mpeg4` because every published build has it. For H.264 or HEVC, ask the build
what it has rather than hard-coding a name. `EncoderId.Libx264` exists only in a GPL FFmpeg, and
the published builds are LGPL, so asking for it there throws `FFmpegException`:

```kotlin
val encoders = FFmpeg.encodersFor(CodecId.H264)   // the ones this build has, its default first
val spec = if (encoders.isEmpty()) null else VideoEncoderSpec(
    codec = CodecId.H264,
    encoder = encoders.first(),
    width = 1280, height = 720,
    frameRate = Rational(30, 1),
)
```

A few defaults to know:

- Pass `audioSpec = null` to drop the audio, or `audioCopy = true` to copy it. A stream copy moves
  the encoded packets across unchanged, so the audio stays the same bit for bit.
- Pass `spec = null` for an audio-only transcode, such as MP3 to AAC.
- `startMicros` and `endMicros` cut a clip that starts on the exact frame, and the output
  timestamps start at zero. `endMicros` has no upper bound unless you set one.
- `onProgress` receives a `TranscodeProgress` with `framesEncoded`, `outputMicros` and a nullable
  `percent`. It arrives about every 30 video frames (or every 100 frames for audio-only work), not
  on an exact count.

```kotlin
// A clip from 12.3 s to 45.6 s, re-encoded from the exact frame:
Transcoder.transcode(
    input = "input.mp4",
    output = "clip.mp4",
    spec = spec,
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
)
```

If the codecs can stay as they are, skip the transcoder and rewrite the container with no loss:

```kotlin
import io.github.yuroyami.kiteffmpeg.Remuxer

Remuxer.remux("input.mp4", "output.mkv")   // no decode and no encode
```

## The words FFmpeg uses

| Word | Meaning |
| --- | --- |
| stream | One track in a file, such as the video or one audio language |
| packet | Compressed data for one stream |
| frame | One decoded picture, or one block of decoded audio samples |
| demux, mux | Split a file into its streams, or write streams into a file |
| decode, encode | Turn packets into frames, or turn frames into packets |
| transcode | Decode, then encode again with other settings |
| remux | Copy the packets into another container, with no decode |
| filter | A step that changes frames, such as scale or volume |
| pts | The time at which a frame is shown, in its stream's time base |

## Next steps

- [Decoding frames](decoding.md): read frames from a stream, decode several streams in one pass,
  and take thumbnails.
- [Transcoding](transcoding.md): filters, hardware encoders, trims and progress.
- [Filter graphs](filtering.md): graphs with one input or several, for scaling, overlays and audio
  mixing.
- [Encoding and muxing](encoding-muxing.md): drive the encoders yourself through a `MediaSink`.
- [Remuxing](remuxing.md): `Remuxer.remux(...)` and stream copies.
- [Concurrency](concurrency.md): threads, ownership and cancellation.
- [Recipes](recipes.md): short patterns for common tasks.
- [Troubleshooting](troubleshooting.md): common errors and their fixes.
