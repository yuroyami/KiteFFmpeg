<p align="center">
  <img src="art/kiteffmpeg-logo.svg" width="200" height="200" alt="KiteFFmpeg logo">
</p>

<h1 align="center">KiteFFmpeg</h1>

<p align="center">
  FFmpeg for Kotlin Multiplatform, as plain Kotlin calls: convert, cut, filter and read video and
  audio from <code>commonMain</code>, with FFmpeg already inside the artifact.
</p>

<p align="center">
  <sub>AI tools wrote much of the code, tests and documentation in this project, under the direction
  and review of its author.</sub>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.yuroyami/kiteffmpeg"><img src="https://img.shields.io/maven-central/v/io.github.yuroyami/kiteffmpeg?label=Maven%20Central" alt="Maven Central"></a>
  <a href="https://github.com/yuroyami/KiteFFmpeg/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/yuroyami/KiteFFmpeg/ci.yml?label=CI" alt="CI"></a>
  <a href="https://yuroyami.github.io/KiteFFmpeg/"><img src="https://img.shields.io/badge/docs-yuroyami.github.io-1f6feb" alt="Docs"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.21"></a>
  <a href="https://ffmpeg.org"><img src="https://img.shields.io/badge/FFmpeg-n9.0.2%20LGPL-007808" alt="FFmpeg n9.0.2, LGPL"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center">
  <b><a href="https://yuroyami.github.io/KiteFFmpeg/">Documentation</a></b> ·
  <a href="https://yuroyami.github.io/KiteFFmpeg/api/">API reference</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

## Why KiteFFmpeg

An app that uses FFmpeg usually starts the `ffmpeg` command line and reads the text it prints, or
it carries a JNI or cinterop binding of its own for each platform. With KiteFFmpeg you call Kotlin
functions from `commonMain` instead. No `ffmpeg` process starts, and no log text is parsed. Every
native, JVM and Android artifact carries its own copy of FFmpeg, so a dependency line is the whole
setup on most targets.

This shrinks a video to 640 by 360 and re-encodes its sound:

```kotlin
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec

suspend fun shrink(input: String, output: String) {
    Transcoder.transcode(
        input = input,
        output = output,
        spec = VideoEncoderSpec(
            codec = CodecId.Mpeg4,              // in every published build
            width = 640, height = 360,
            frameRate = Rational(30, 1),
            bitrateBps = 1_500_000,
        ),
        videoFilter = "scale=640:360,format=yuv420p",
        audioSpec = AudioEncoderSpec(codec = CodecId.Aac),
        onProgress = { p -> println("${p.framesEncoded} frames") },
    )
}
```

When something goes wrong, you get one `FFmpegException` that carries a typed `FFmpegError`. So
"this build has no AAC encoder" is a case you match in code (`FFmpegError.EncoderNotFound`), not a
message you read.

> [!NOTE]
> KiteFFmpeg has not reached 1.0, so the API can still change between minor versions.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.5.1")
}
```

You don't install FFmpeg (it comes inside the artifact), and there is no Gradle plugin to apply.
Each platform adds about 10 MB to download. In a plain Android or JVM project, put the line in
your usual `dependencies { }` block. On Android the library needs `minSdk` 26, and on the JVM it
needs Java 11 or later.

### What else you need

> [!IMPORTANT]
> Some setups need one more step. If you skip it, the link, the App Store upload or the first call
> fails.

| If you build | You also need |
| --- | --- |
| An iOS app with a static framework (`isStatic = true`) | Linker flags in Xcode ([iOS setup](#ios-setup)) |
| Any iOS app | An entry in the privacy manifest ([iOS setup](#ios-setup)) |
| A web app (`wasmJs`) | The FFmpeg WebAssembly module beside the page ([Web setup](#web-setup)) |

<a name="ios-setup"></a>
<details name="setup">
<summary><b>iOS setup</b>: the linker flags and the privacy manifest entry</summary>
<br>

A dynamic framework needs no flags, because Kotlin links it with the system frameworks. Xcode links
a static framework instead, which needs these Other Linker Flags:

```text
-lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

FFmpeg's file reader calls `stat`, `fstat` and `lstat`, which Apple lists as file timestamp APIs.
App Store Connect refuses the upload until `PrivacyInfo.xcprivacy` declares a reason for them. Keep
only the reasons that apply to your app: `C617.1` is files inside the app container, and `3B52.1`
is files that the user picked.

```xml
<key>NSPrivacyAccessedAPITypes</key>
<array>
    <dict>
        <key>NSPrivacyAccessedAPIType</key>
        <string>NSPrivacyAccessedAPICategoryFileTimestamp</string>
        <key>NSPrivacyAccessedAPITypeReasons</key>
        <array><string>C617.1</string><string>3B52.1</string></array>
    </dict>
</array>
```

</details>

<a name="web-setup"></a>
<details name="setup">
<summary><b>Web setup</b>: the module that a page serves</summary>
<br>

A browser cannot link FFmpeg into the Kotlin binary, so the page serves it as a separate
WebAssembly module:

1. Download `kiteffmpeg-wasm-js-<version>-web.zip`. It sits beside the `wasmJs` artifact on Maven
   Central, for the version you depend on.
2. Unpack it beside your page, so that the page serves `kite.mjs`, `kite.wasm` and `licenses/`.
3. Call `KiteFFmpegWeb.load()` once, before any other KiteFFmpeg call. Under a bundler,
   instantiate the module from a plain `<script type="module">` and pass it to
   `KiteFFmpegWeb.attach()` instead.

```kotlin
KiteFFmpegWeb.load()                 // fetches ./kite.mjs
check(FFmpeg.identity.isAcceptable)
```

A call before the module loads throws `KiteFFmpegWeb.NotLoaded`. The module is single-threaded,
which means the page needs no cross-origin isolation headers.

The module does not open URLs by itself. Your own `MediaByteSource` supplies the bytes. In a web
worker, FFmpeg reads the source as it needs the bytes, so a large file can stream through
synchronous range requests. On the page's main thread, the whole source is read into memory when
it opens, up to 512 MiB. An HLS playlist plays through a `nestedOpener` that serves each segment.
That opener has to answer at once, either from bytes the page already holds or from a synchronous
request in a web worker.

A source that answers later, such as a Fetch response, needs the asynchronous runtime. Its two
codec modules come in `kiteffmpeg-wasm-js-<version>-web-async.zip`, beside the first zip.
[Asynchronous byte sources](docs/async-byte-io.md) shows how to load them.

</details>

## Usage

The calls below suspend, so run them from a coroutine. The
[documentation](https://yuroyami.github.io/KiteFFmpeg/) has one guide for each task.

### Convert, cut and copy

```kotlin
import io.github.yuroyami.kiteffmpeg.Remuxer

// Change the container and keep every stream as it is. Fast, and it loses nothing.
Remuxer.remux(input = "movie.mkv", output = "movie.mp4")

// Keep 0:10 to 0:40, still without re-encoding.
Remuxer.remux(input = "movie.mp4", output = "clip.mp4", startMicros = 10_000_000, endMicros = 40_000_000)
```

Times are microseconds from the start of the content, not the raw timestamps stored in the file. A
cut without re-encoding starts at the keyframe at or before `startMicros`, because a copy cannot
make a new keyframe. `Transcoder.transcode` takes the same two times when you need the exact frame.
See [Remuxing](docs/remuxing.md) and [Transcoding](docs/transcoding.md).

### Grab one frame

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource

MediaSource.open("movie.mp4").use { source ->
    source.extractFrame(atMicros = 5_000_000).use { frame ->
        println("${frame.info.width}x${frame.info.height}")
        val pixels = frame.copyPlanesToByteArray()
    }
}
```

A `Frame` holds native memory until you close it, so close every frame, as `use { }` does.

### Read every frame

```kotlin
MediaSource.open("movie.mp4").use { source ->
    val video = source.primaryVideo ?: error("no video track")
    source.decodedFrames(video).collect { frame ->
        frame.use { println("shown at ${it.info.ptsSeconds}s") }
    }
}
```

Use `decodeStreams(...)` to read video and audio together in one pass. To buffer frames, use
`bufferFrames()` rather than `buffer()`, so that each frame still gets closed. See
[Decoding](docs/decoding.md).

### Filter

A filter string uses FFmpeg's own syntax, so a chain that works with `ffmpeg -vf` works here. Pass
it as `videoFilter` or `audioFilter` to `transcode`, or build a `FilterGraph` to run frames through
it yourself. A graph names its inputs `[in0]`, `[in1]` and so on, and its one output `[out]`.
[Platform support](docs/platforms.md) lists the filters that every build carries, and
[Filter graphs](docs/filtering.md) shows the graph API.

### Read bytes from your own code

A `MediaByteSource` feeds FFmpeg bytes from memory, a cache, an encrypted store or your own HTTP
client. For an HLS playlist, a `MediaByteOpener` also serves the segments and keys that the
playlist names. This is how an `https` stream plays, even though FFmpeg has no TLS here:

```kotlin
val stream = MediaSource.open(
    io = fetch("https://cdn.example/live/index.m3u8"),
    url = "https://cdn.example/live/index.m3u8",
    nestedOpener = { address -> if (address.startsWith("https://cdn.example/")) fetch(address) else null },
)
```

Here `fetch` stands for your own HTTP client. The addresses come from the playlist, so open only
the ones you expect. See [Decoding](docs/decoding.md#hls-through-your-own-http-client). On Android,
`"fd:"` with the `fd` option reads a file that the user picked without a copy, as
[Platform support](docs/platforms.md) shows.

### Ask what the build can do

```kotlin
FFmpeg.hasEncoder("h264_videotoolbox")      // true on macOS
FFmpeg.hasFilter("scale")                   // true everywhere but the web
FFmpeg.setLogSink(FFmpegLogLevel.Warning) { level, component, message -> println("$component: $message") }
```

Builds differ, so ask instead of assuming. `FFmpeg.versions` and `FFmpeg.buildConfiguration` report
what is really linked. If the linked FFmpeg disagrees with the headers that the artifact was built
against, the first call fails with a typed error that names both.

FFmpeg prints nothing unless you install a log sink. The sink runs on whichever thread FFmpeg logs
from, so keep it quick, and don't call KiteFFmpeg from inside it.

### Build a media player

The calls above read a file from start to end. That suits conversion, but not playback, where
audio and video move separately and the user can seek at any time. The lower-level API behind
`@KiteFFmpegLowLevelApi` handles that, and [KitePlayer](https://github.com/yuroyami/KitePlayer) is
built on it. You free its objects yourself, so use the calls above unless you are writing a
player.

## Platforms

| Family | Targets |
| --- | --- |
| Android | The Android library (`minSdk` 26, with `arm64-v8a`, `armeabi-v7a` and `x86_64`), and `androidNativeArm32`, `androidNativeArm64` and `androidNativeX64` for Kotlin/Native on Android |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `macosX64` |
| Desktop | `jvm` (Java 11 or later), `linuxX64`, `linuxArm64`, `mingwX64` |
| Web | `wasmJs` with the [web module](#web-setup), and `js` as a placeholder |

- The JVM jar carries FFmpeg for macOS on Apple silicon, Linux (x64 and arm64) and Windows (x64).
  On any other platform, the first call throws `UnsatisfiedLinkError`, unless
  `-Dkiteffmpeg.jni.path` or `java.library.path` names a native library that you built.
- `wasmJs` reads, decodes and seeks. It cannot write a file, so `Transcoder`, `Remuxer`,
  `MediaSink` and `FilterGraph` throw `FFmpegError.Unsupported` there.
- `js` compiles, and every media call throws `FFmpegError.Unsupported`.

[Platform support](docs/platforms.md) has the detail for each target.

## A few limitations to know about

- No third-party encoder is linked: no `libx264`, `libx265`, `libsvtav1`, `libopus` or
  `libmp3lame`. The software video encoders are `mpeg4`, `h263`, `mjpeg` and `png`, and the audio
  ones are `aac`, `flac` and three PCM formats. The hardware encoders are VideoToolbox H.264 and
  HEVC on macOS and the iPhone (not the simulator), and MediaCodec H.264 and HEVC on Android.
  Decoding covers far more formats than encoding.
- No GPL build is published, because a GPL FFmpeg would make your whole app GPL-3.0, which a
  library should not decide for you. You can still build KiteFFmpeg against a GPL FFmpeg of your
  own ([Licensing](docs/licensing.md)).
- FFmpeg has no TLS here. Fetch `https` bytes with your own HTTP client and open them through a
  `MediaByteSource`, with a `MediaByteOpener` for HLS.
- Hardware decoding uses VideoToolbox on Apple platforms, MediaCodec on Android and Direct3D 11 on
  Windows. Linux decodes in software. The Direct3D 11 path has not run on a real Windows GPU.
- You cannot pick a bitstream filter by hand. FFmpeg applies the right one during a stream copy.
- Some targets build but have not played media yet: `macosX64`, `iosX64`, the Android
  `armeabi-v7a` and `x86_64` libraries, the desktop JVM on Linux and Windows, and the
  `androidNative*` targets.

Missing features and known bugs are [open issues](https://github.com/yuroyami/KiteFFmpeg/issues).

## Licensing

KiteFFmpeg's own code is Apache-2.0. The embedded FFmpeg is LGPL-2.1-or-later, and dav1d is
BSD-2-Clause. Nothing in the published artifacts is GPL, so your app keeps its own license. An app
that links LGPL code statically has three duties:

| Duty | How to meet it |
| --- | --- |
| Say that the app uses FFmpeg, under the LGPL | Ship a notice with the license text. The JVM jar carries it at `META-INF/licenses/kiteffmpeg-ffmpeg/`. |
| Keep the matching FFmpeg source available | Point to the source that each release names. [NOTICE](NOTICE) says where. |
| Let users relink against a modified FFmpeg | Publish your object files, or give a written offer for them. |

Static linking on the iOS App Store is the hardest case.
[Licensing](https://yuroyami.github.io/KiteFFmpeg/licensing/) explains why, step by step.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the sample tool and the tests, and
[Building from source](docs/building-from-source.md) covers the FFmpeg trees.

## License

Apache-2.0. See [NOTICE](NOTICE) and the [changelog](CHANGELOG.md).

Not affiliated with the FFmpeg project. FFmpeg is a trademark of Fabrice Bellard, and KiteFFmpeg
is an independent Kotlin binding that links FFmpeg's LGPL libraries. The logo is not official
FFmpeg artwork, and the Apache-2.0 grant does not cover it. [The artwork credits](art/CREDITS.md)
record its sources.

Part of the Kite family: [KitePlayer](https://github.com/yuroyami/KitePlayer),
[Kite3D](https://github.com/yuroyami/Kite3D) and [KitePDF](https://github.com/yuroyami/KitePDF).
