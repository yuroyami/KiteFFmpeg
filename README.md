<p align="center">
  <img src="art/kiteffmpeg-logo.svg" width="200" height="200" alt="KiteFFmpeg logo">
</p>

<h1 align="center">KiteFFmpeg</h1>

<p align="center">
  FFmpeg for Kotlin Multiplatform, as plain Kotlin calls: convert, cut, filter and read video and
  audio from <code>commonMain</code>, with FFmpeg already inside the artifact.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.yuroyami/kiteffmpeg"><img src="https://img.shields.io/maven-central/v/io.github.yuroyami/kiteffmpeg?label=Maven%20Central" alt="Maven Central"></a>
  <a href="https://github.com/yuroyami/KiteFFmpeg/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/yuroyami/KiteFFmpeg/ci.yml?label=CI" alt="CI"></a>
  <a href="https://yuroyami.github.io/KiteFFmpeg/"><img src="https://img.shields.io/badge/docs-yuroyami.github.io-1f6feb" alt="Docs"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.20"></a>
  <a href="https://ffmpeg.org"><img src="https://img.shields.io/badge/FFmpeg-n9.0.2%20LGPL-007808" alt="FFmpeg n9.0.2, LGPL"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center">
  <b><a href="https://yuroyami.github.io/KiteFFmpeg/">Documentation</a></b> ·
  <a href="https://yuroyami.github.io/KiteFFmpeg/api/">API reference</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

## What you get

You call Kotlin functions. No `ffmpeg` process starts, and no log text is parsed. Every native,
JVM and Android artifact carries its own copy of FFmpeg, so a dependency line is the whole setup
on most targets.

<table>
<tr>
<td width="33%" valign="top">

**Convert**<br>
Re-encode a video smaller, or into another format, with video and audio in one pass.

</td>
<td width="33%" valign="top">

**Cut and copy**<br>
Cut a clip between two times, or move an `.mkv` into an `.mp4` without re-encoding anything.

</td>
<td width="33%" valign="top">

**Grab a frame**<br>
One picture from any point in a video, for a thumbnail or a preview.

</td>
</tr>
<tr>
<td width="33%" valign="top">

**Filter**<br>
Scale, blur, watermark or change colour with FFmpeg's own filter syntax.

</td>
<td width="33%" valign="top">

**Read frames**<br>
Decoded pictures and audio as a Kotlin `Flow`, to draw or analyse yourself.

</td>
<td width="33%" valign="top">

**Bring your own bytes**<br>
Read from memory, a picked Android file, or your own HTTP client, HLS included.

</td>
</tr>
</table>

This shrinks a video to 640 by 360 and re-encodes its sound. Memory stays flat whether the file
lasts one minute or three hours.

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
"this build has no AAC encoder" is a case you match in code, not a message you read.

> [!NOTE]
> KiteFFmpeg is not at 1.0 yet, so the API can still change between minor versions. A snapshot
> of the whole public API is checked on every push, so no change slips through unseen.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.5.0")
}
```

Every target you declare gets FFmpeg, about 10 MB per platform. You do not install FFmpeg, and
there is no Gradle plugin. In a plain Android or JVM project, put the line in your usual
`dependencies { }` block.

### What else you need

> [!IMPORTANT]
> Three setups need one more step. Without it, the link, the App Store upload or the first call
> fails.

| If you build | You also need |
| --- | --- |
| An iOS app with a **static** framework (`isStatic = true`) | **Linker flags** in Xcode, because Xcode never reads the ones inside the klib. See [iOS setup](#ios-setup). |
| Any iOS app | **A privacy manifest entry** for file timestamp APIs, which FFmpeg's file reader uses. See [iOS setup](#ios-setup). |
| A web app (`wasmJs`) | **The codec module**, two files that the page serves. See [Web setup](#web-setup). |

<a name="ios-setup"></a>
<details>
<summary><b>iOS setup</b>: the linker flags and the privacy manifest entry</summary>
<br>

A dynamic framework needs no flags. For a static framework, add this to Other Linker Flags:

```text
-lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

FFmpeg's file reader calls `stat`, `fstat` and `lstat`, which Apple lists as file timestamp APIs.
Declare `NSPrivacyAccessedAPICategoryFileTimestamp` in the app's `PrivacyInfo.xcprivacy`, with the
reason that applies: `C617.1` for files inside the app container, and `3B52.1` for files that the
user picked. App Store Connect refuses an upload that uses these APIs without a declared reason.

</details>

<a name="web-setup"></a>
<details>
<summary><b>Web setup</b>: loading the codec module in a browser</summary>
<br>

A browser cannot link FFmpeg into the Kotlin binary, so FFmpeg comes as a separate WebAssembly
module that you load once, before any other call:

```kotlin
KiteFFmpegWeb.load()                 // fetches ./kite.mjs; or attach() a module the page made
check(FFmpeg.identity.isAcceptable)
```

1. Download `kiteffmpeg-wasm-js-<version>-web.zip`, attached to the `wasmJs` publication on Maven
   Central for the version you depend on.
2. Unpack it beside your page, so that the page serves `kite.mjs`, `kite.wasm` and `licenses/`.
3. Call `KiteFFmpegWeb.load()`. Under a bundler, instantiate the module from a plain
   `<script type="module">` and pass it to `KiteFFmpegWeb.attach()` instead.

A call before the module loads throws `KiteFFmpegWeb.NotLoaded`. The module is single-threaded,
so the page needs no cross-origin isolation headers. To build the two files yourself, run
`./gradlew :kiteffmpeg:kiteffmpegWebZip`, which needs emscripten.

The module opens no address by itself. In a Web Worker, a `MediaByteSource` is read as FFmpeg
asks for its bytes, so it can stream a large file through synchronous range requests; on the page's
main thread it is read whole into memory when it opens, up to 512 MB. An HLS playlist plays through
a `nestedOpener` that serves each of its segments, and that opener has to answer at once: from
bytes the page already holds, or from a synchronous request inside a Web Worker. [Decoding](https://yuroyami.github.io/KiteFFmpeg/decoding/#hls-through-your-own-http-client)
has the details.

</details>

## A quick tour

Run these from a coroutine, because the work calls suspend. The [documentation](https://yuroyami.github.io/KiteFFmpeg/)
has one guide per task.

### Convert, cut and copy

```kotlin
import io.github.yuroyami.kiteffmpeg.Remuxer

// Change the container and keep every stream as it is. Fast, and it loses nothing.
Remuxer.remux(input = "movie.mkv", output = "movie.mp4")

// Keep 0:10 to 0:40, still without re-encoding.
Remuxer.remux(input = "movie.mp4", output = "clip.mp4", startMicros = 10_000_000, endMicros = 40_000_000)
```

Times are **microseconds from the start of the content**, not the raw timestamps stored in the
file. A cut without re-encoding starts at the keyframe at or before `startMicros`, because a copy
cannot make a new keyframe. `Transcoder.transcode` takes the same two times when you need the
exact frame. See [Remuxing](docs/remuxing.md) and [Transcoding](docs/transcoding.md).

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
`bufferFrames()`, not `buffer()`, so that each frame still gets closed. See
[Decoding](docs/decoding.md).

### Filter

A filter string uses FFmpeg's own syntax, so a chain that works with `ffmpeg -vf` works here.
Pass it as `videoFilter` or `audioFilter` to `transcode`, or build a `FilterGraph` to run frames
through yourself. A graph names its inputs `[in0]`, `[in1]` and so on, and its one output `[out]`.
[Platform support](docs/platforms.md) lists the filters that every build carries, and
[Filtering](docs/filtering.md) shows the graph API.

### Read bytes from your own code

A `MediaByteSource` feeds FFmpeg bytes from memory, a cache, an encrypted store or your own HTTP
client. For an HLS playlist, a `MediaByteOpener` also serves the segments and keys that the
playlist names, which is how an https stream plays with no TLS inside FFmpeg:

```kotlin
val stream = MediaSource.open(
    io = fetch("https://cdn.example/live/index.m3u8"),
    url = "https://cdn.example/live/index.m3u8",
    nestedOpener = { address -> if (address.startsWith("https://cdn.example/")) fetch(address) else null },
)
```

Here `fetch` stands for your own HTTP client. The addresses come from the playlist, so open only
the ones you expect. See [Decoding](docs/decoding.md#hls-through-your-own-http-client). On
Android, `"fd:"` with the `fd` option reads a file the user picked, without a copy, as
[Platform support](docs/platforms.md) shows.

### Ask what the build can do

```kotlin
FFmpeg.hasEncoder("h264_videotoolbox")      // true on macOS
FFmpeg.hasFilter("scale")                   // true everywhere but the web
FFmpeg.setLogSink(FFmpegLogLevel.Warning) { level, component, message -> println("$component: $message") }
```

FFmpeg prints nothing unless you install a log sink. The sink runs on whichever thread FFmpeg logs
from, so keep it quick, and do not call KiteFFmpeg from inside it.

> [!TIP]
> **Building a media player?** The calls above read a file from front to back, which suits
> converting and does not suit playback. A player needs audio and video to move separately and to
> jump while both run. The lower-level API behind `@KiteFFmpegLowLevelApi` does that, and
> [KitePlayer](https://github.com/yuroyami/KitePlayer) is built on it. You free its objects
> yourself, so stay with the calls above unless you write a player.

<details>
<summary><b>The words FFmpeg uses</b>, for when you start reading frames yourself</summary>
<br>

| Word | Meaning |
| --- | --- |
| **stream** | One track in a file, such as the video or one audio language |
| **packet** | Compressed data for one stream |
| **frame** | One decoded picture, or one block of decoded audio samples |
| **demux** / **mux** | Split a file into its streams / write streams into a file |
| **decode** / **encode** | Turn packets into frames / turn frames into packets |
| **transcode** | Decode, then encode again with other settings |
| **remux** | Copy the packets into another container, with no decode |
| **filter** | A step that changes frames, such as scale or volume |
| **pts** | The time at which a frame is shown, in its stream's time base |

</details>

## Where it runs

| | Targets |
| --- | --- |
| **Plays real media** | Android (`minSdk` 26, `arm64-v8a`), `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64`, and the desktop JVM on macOS arm64 |
| **Plays media with the web codec module** | `wasmJs`: reading, decoding and seeking. Writing files and filtering are refused by design. |
| **Builds, not run yet** | `macosX64`, `iosX64`, the Android `armeabi-v7a` and `x86_64` libraries, the desktop JVM on Linux and Windows, and the `androidNative*` targets, which serve Kotlin/Native on Android, not a normal Android app |
| **Placeholder** | `js`. It compiles, and every media call throws `FFmpegError.Unsupported`, so a build never does nothing in silence. |

Android and iOS play real media on phones every day, because this is the engine under KitePlayer.
No phone runs in this repository's CI, so that evidence is checked by hand and by a shipping app.
Desktop and Windows are the reverse: CI checks them on every push.
[Platform support](docs/platforms.md) has the detail for each target.

## Good to know

<details>
<summary><b>Known limits</b>: what KiteFFmpeg does not do, in one table</summary>
<br>

| Topic | What to expect |
| --- | --- |
| Encoders | No third-party encoder is linked: no `libx264`, `libx265`, `libsvtav1`, `libopus` or `libmp3lame`. `mpeg4` is the software video baseline and `aac` the audio one. Decoding covers far more than encoding. |
| GPL | There is no GPL build, and the embedded FFmpeg cannot be swapped. A GPL binary would make your whole app GPL-3.0, which a library should not decide for you. |
| `https` | FFmpeg has no TLS here. Fetch https bytes with your own HTTP client and open them through a `MediaByteSource`, with a `MediaByteOpener` for HLS. |
| The web | `wasmJs` reads and decodes, and it cannot write a file or filter. Its nested opener has to answer at once, so it fetches only in a Worker. |
| Hardware decoding | VideoToolbox on Apple platforms, MediaCodec on Android, and Direct3D 11 on Windows, which has not run on a Windows GPU yet. Linux decodes in software. |
| Hardware encoding | VideoToolbox and MediaCodec. On virtual machines and CI runners, pass `allow_sw`, because the encoder exists and the chip does not. |
| JVM | The jar carries native libraries for macOS arm64, Linux x64, Linux arm64 and Windows x64. Any other platform gets the typed unavailable placeholder. |
| Bitstream filters | You cannot pick one by hand. FFmpeg applies the right one during a stream copy. |

</details>

<details>
<summary><b>What each build can encode</b>, read from the shipped binaries</summary>
<br>

| | macOS | Linux and Windows | iOS | Android |
| --- | --- | --- | --- | --- |
| Video, software | `mpeg4`, `mjpeg`, `png`, `apng`, `h263`, `h263p` | same | same | same |
| Video, hardware | `h264_videotoolbox`, `hevc_videotoolbox`, `prores_videotoolbox` | none | `h264_videotoolbox`, `hevc_videotoolbox` on the iPhone from 0.4.0, not the simulators | `h264_mediacodec`, `hevc_mediacodec` |
| Audio | `aac`, `flac`, `pcm_s16le`, `pcm_s24le`, `pcm_f32le` | same | same | same |

Builds differ, so ask rather than assume: `FFmpeg.hasEncoder(...)`, `FFmpeg.hasFilter(...)`,
`FFmpeg.versions` and `FFmpeg.buildConfiguration` report what is really linked. If the linked
FFmpeg ever disagrees with the headers this artifact was built against, the first call fails with
a typed error that names both, instead of returning wrong numbers ten frames later.

</details>

<details>
<summary><b>Licensing</b>: what an app that ships FFmpeg must do</summary>
<br>

KiteFFmpeg's own code is Apache-2.0. The embedded FFmpeg is **LGPL-2.1-or-later**, and dav1d is
BSD-2-Clause. There is no GPL anywhere, so your app keeps its own licence. An app that links LGPL
code statically has three duties:

| Duty | How to meet it |
| --- | --- |
| Say that the app uses FFmpeg, under the LGPL | Ship a notice with the licence text. The JVM jar carries it at `META-INF/licenses/kiteffmpeg-ffmpeg/`. |
| Keep the matching FFmpeg source available | Point at the source that each release names. [NOTICE](NOTICE) says where. |
| Let users relink against a modified FFmpeg | Publish your object files, or give a written offer for them. |

Static linking on the iOS App Store is the hardest case.
[Licensing](https://yuroyami.github.io/KiteFFmpeg/licensing/) explains why, step by step.

</details>

<details>
<summary><b>Build and test it here</b>: for contributors</summary>
<br>

The sample is a command-line tool over the whole API. It probes the linked FFmpeg for its encoder,
so it works against the embedded build and against your own FFmpeg alike.

```bash
./gradlew :kiteffmpeg-sample:linkDebugExecutableMacosArm64
KEXE=kiteffmpeg-sample/build/bin/macosArm64/debugExecutable/kiteffmpeg-sample.kexe
$KEXE transcode in.mp4 out.mp4 "scale=1280:720" -acopy   # also: info, probe, thumbnail, remux

./gradlew :kiteffmpeg:macosArm64Test          # or linuxX64Test, mingwX64Test
scripts/e2e.sh "$KEXE"
```

The C layer under the Kotlin API builds and tests on its own, outside Gradle:

```bash
cd native/kitecodec-c
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain   # also asan, tsan
```

[CONTRIBUTING.md](CONTRIBUTING.md) has the ground rules and the test gate,
[Getting started](docs/getting-started.md) has every build step, and
[native/kitecodec-c/README.md](native/kitecodec-c/README.md) says what each C check proves.

</details>

## License

Apache-2.0. See [NOTICE](NOTICE) and the [changelog](CHANGELOG.md).

Not affiliated with the FFmpeg project. FFmpeg is a trademark of Fabrice Bellard, and KiteFFmpeg
is an independent Kotlin binding that links FFmpeg's LGPL libraries. The logo is not official
FFmpeg artwork, and the Apache-2.0 grant does not cover it; [the artwork credits](art/CREDITS.md)
record its sources.

Part of the Kite family: [KitePlayer](https://github.com/yuroyami/KitePlayer),
[Kite3D](https://github.com/yuroyami/Kite3D) and [KitePDF](https://github.com/yuroyami/KitePDF).
