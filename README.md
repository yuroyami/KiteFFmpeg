# KiteFFmpeg

<p align="center">
  <img src="art/kiteffmpeg-logo.png" width="180" alt="KiteFFmpeg logo">
</p>

FFmpeg's libav* libraries as a Kotlin Multiplatform API: demux, decode, filter, encode, mux,
transcode and remux, with FFmpeg compiled into the artifacts.

[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteFFmpeg/ci.yml?label=CI)](https://github.com/yuroyami/KiteFFmpeg/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/kiteffmpeg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.yuroyami/kiteffmpeg)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/KiteFFmpeg/)** · [API reference](https://yuroyami.github.io/KiteFFmpeg/api/) · [Changelog](CHANGELOG.md)

## What you get

You call Kotlin functions. No `ffmpeg` process runs, and no log is parsed. The native, JVM and
Android artifacts carry FFmpeg n9.0.2 (LGPL) with dav1d, and the web gets FFmpeg as a separate
wasm module. A dependency line is the whole setup, except for an iOS static framework
([iOS](#ios)) and the web module ([Where it runs](#where-it-runs)). Errors arrive as one
`FFmpegException` with a typed `FFmpegError`, and decoded frames arrive as a `Flow`.

Typical uses:

- Shrink a large video so it uploads faster
- Cut a clip between two timestamps
- Grab a thumbnail from any point in a video
- Add a watermark, a blur, or a colour change
- Change an `.mkv` into an `.mp4` without re-encoding it
- Read raw frames and draw them yourself

## Example

This reads a video, scales it down, re-encodes the video and the audio, and writes the result.
Video and audio are handled together in one pass, and memory stays flat whether the file is one
minute or three hours long.

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
            codec = CodecId.Mpeg4,                  // present in every published build
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

That filter string is FFmpeg's own syntax, so any chain built from the filters listed in
[Platform support](docs/platforms.md) works as it would with `ffmpeg -vf`.

Set `videoCopy` or `audioCopy` if you want to keep a stream exactly as it is instead of
re-encoding it. That is much faster and loses nothing.

Errors come back as one `FFmpegException` wrapping a sealed `FFmpegError`, so "this device has no
AAC encoder" is a case you can match on in code, not a string you have to read.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.3.0")
}
```

That goes in the `sourceSets` block you already have. Every target you declare gets FFmpeg: the
artifact for each platform carries its own FFmpeg build, about 10 MB. You do not install FFmpeg or
add a Gradle plugin. An iOS static framework needs linker flags ([iOS](#ios)), and the web needs
its codec module ([Where it runs](#where-it-runs)).

## The words FFmpeg uses

FFmpeg has its own vocabulary, and this page uses it.

| Word | Meaning |
|---|---|
| **stream** | One track in a file, such as the video or one audio language |
| **packet** | Compressed data for one stream |
| **frame** | One decoded picture, or a block of decoded audio samples |
| **demux** / **mux** | Split a file into its streams / write streams into a file |
| **decode** / **encode** | Turn packets into frames / turn frames into packets |
| **transcode** | Decode, then encode again with other settings |
| **remux** | Copy the packets into another container, with no decode |
| **filter** | A stage that changes frames, such as scale or volume |
| **pts** | The time at which a frame is shown, in its stream's time base |

You do not need any of it for the example above. It matters once you start reading frames
yourself.

## What you can call

These are suspend functions, so call them from a coroutine. Frame readers are Kotlin `Flow`s, so
you collect them.

| What you want to do | Call | Guide |
|---|---|---|
| Re-save a video smaller, or in another format | `Transcoder.transcode(...)` | [Transcoding](docs/transcoding.md) |
| Cut a clip between two times | `transcode(..., startMicros, endMicros)` | [Transcoding](docs/transcoding.md) |
| Change the file type without re-encoding | `Remuxer.remux(...)` | [Remuxing](docs/remuxing.md) |
| Convert bytes you hold, or a picked Android file, with no copy | the `transcode` and `remux` overloads with `inputOptions`, or with a `MediaByteSource` and a `MediaByteSink` | [Platform support](docs/platforms.md) |
| Grab a single picture from a video | `MediaSource.extractFrame(...)` | [Decoding](docs/decoding.md) |
| Read every frame yourself | `MediaSource.decodedFrames(...)` | [Decoding](docs/decoding.md) |
| Read video and audio frames together | `MediaSource.decodeStreams(...)` | [Decoding](docs/decoding.md) |
| Scale, blur, watermark, adjust colour | `FilterGraph.buildVideo` / `buildVideoMulti` | [Filtering](docs/filtering.md) |
| Build a file out of frames you made | `MediaSink` plus `Frame.ofVideo` / `ofAudio` | [Encoding and muxing](docs/encoding-muxing.md) |
| Ask what this build supports | `FFmpeg.hasEncoder(...)` / `hasFilter(...)` | [Platform support](docs/platforms.md) |
| See FFmpeg's own warnings and errors | `FFmpeg.setLogSink(level) { level, component, message -> }` | below |

Two details that catch people out. Cut times are **microseconds into the video**, counted from the
start of the content, not the raw numbers stored in the file. And a filter chain names its inputs
`[in0]`, `[in1]` and so on, with a single output called `[out]`.

### Reading frames

```kotlin
import io.github.yuroyami.kiteffmpeg.MediaSource

MediaSource.open("input.mp4").use { source ->
    val video = source.primaryVideo ?: error("no video track")
    source.decodedFrames(video).collect { frame ->
        frame.use {
            val info = it.info
            println("pts=${info.ptsSeconds}s ${info.width}x${info.height}")
        }
    }
}
```

Each collected frame holds native memory until you close it, so close every frame: `frame.use { }`
does that. To buffer frames, use `bufferFrames()`, not `buffer()`. You cannot collect two of
these at once from the same file: they would both try to move the read position, so the second one
is rejected. Use `decodeStreams(...)` when you want video and audio together.

FFmpeg's own log lines, such as `moov atom not found`, print nothing unless you install a sink with
`FFmpeg.setLogSink`. The sink runs on whichever thread FFmpeg logs from, so keep it quick and
thread safe, and do not call KiteFFmpeg from it.

**Building a media player?** The API above reads a file front to back, which is right for
converting and wrong for playback: a player needs audio and video to advance separately, and to
jump around while both are running. There is a second, lower-level API for that, behind an opt-in
annotation (`@KiteFFmpegLowLevelApi`) because it hands you objects you must free yourself.
[KitePlayer](https://github.com/yuroyami/KitePlayer) is built on it. If you are not writing a
player, stay with the API above.

## Where it runs

| | Targets |
|---|---|
| **Plays real media** | `macosArm64`, `iosArm64`, `iosSimulatorArm64`, the Android AAR (`minSdk 26`, `arm64-v8a`), `linuxX64`, `linuxArm64`, `mingwX64` |
| **Plays media, with the codec module from the `web` zip** | `wasmJs`. Reading and decoding work, including seeking. Writing files (encode, mux) and filtering are refused by design |
| **Builds, nothing has run** | `macosX64`, `iosX64`, the Android AAR's `armeabi-v7a` and `x86_64` libraries, and the `androidNative*` targets, which are for Kotlin/Native on Android and are not what a normal Android app uses |
| **Placeholder** | `js`. The code compiles and you can ask it what it supports (nothing), but every media call throws `FFmpegError.Unsupported` |

All of these publish at 0.3.0.

Android and iOS play real media on real phones: this is the engine under
[KitePlayer](https://github.com/yuroyami/KitePlayer), which is device-tested on both, down to
per-frame GPU timings on a Redmi Note 8. On Android that evidence is for `arm64-v8a`, including
FFmpeg's MediaCodec decoders, which KitePlayer selects by name. What those platforms do not have is an **automated device
job in this repository's CI** (nobody runs a phone farm here), so their evidence is hand-verified
and app-shipped rather than green-on-every-push. Desktop and Windows are the reverse: CI-verified
on every push.

The JVM jar carries a native library for macOS arm64, Linux x64, Linux arm64 and Windows x64. The
Linux and Windows ones are link-checked, and no Linux or Windows machine has run them yet. A JVM on
any other platform resolves the artifact and then gets the typed unavailable placeholder.
The jar is Java 11 bytecode, so it runs on Java 11 and later. Per-target detail is in
[Platform support](docs/platforms.md).

`js` is a deliberate placeholder: a build that silently did nothing would be worse than one that
tells you it cannot.

**`wasmJs` is not a placeholder.** It carries a real playback backend over a generated binding.
FFmpeg cannot be linked into the same binary in a browser, so it arrives as a separate wasm module
you load once before anything else:

```kotlin
KiteFFmpegWeb.load("/kite.mjs")     // or attach() a module the page already instantiated
check(FFmpeg.identity.isAcceptable)
```

Calls before that throw `KiteFFmpegWeb.NotLoaded`. The module is two files, `kite.mjs` and
`kite.wasm`. Each release attaches them to the `wasmJs` publication as one zip with the `web`
classifier (`kiteffmpeg-wasm-js-<version>-web.zip`), together with the FFmpeg licence texts that
must travel with them:

1. Download the zip for the version you depend on.
2. Unpack it beside your page, so the page serves `kite.mjs`, `kite.wasm` and `licenses/`.
3. Call `KiteFFmpegWeb.load()`, which fetches `./kite.mjs`. Under a bundler, instantiate the module
   from a plain `<script type="module">` and pass it to `KiteFFmpegWeb.attach()` instead.

To build the two files yourself, run `./gradlew :kiteffmpeg:kiteffmpegWebZip` (needs emscripten).
Only the single-threaded build ships: the threaded one hangs on import on a page without
cross-origin isolation. Most web tests run against a scripted fake module, which proves the
binding reads the right fields; `RealCodecModuleTest` decodes a real clip with the linked module
under Node when the module has been built.

## iOS

A dynamic framework needs nothing more. A static framework (`isStatic = true`) is linked by Xcode,
which never sees the linker options inside the klib, so add this to Other Linker Flags:

```text
-lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

FFmpeg's file reader calls `stat`, `fstat` and `lstat`, which Apple lists as file timestamp APIs.
Declare `NSPrivacyAccessedAPICategoryFileTimestamp` in the app's `PrivacyInfo.xcprivacy`, with the
reason that applies: `C617.1` for files inside the app container, and `3B52.1` for files the user
picked. App Store Connect refuses an upload that uses these APIs without a declared reason.

## What it will not do

| Not available | What that means for you |
|---|---|
| A JVM distribution beyond macOS arm64, Linux x64, Linux arm64 and Windows x64 | JVM apps on any other platform get the typed unavailable placeholder, not a codec. |
| Writing or filtering media on the web | Both web targets refuse it. `wasmJs` can read and decode; it cannot produce a file. `js` refuses everything. |
| Any GPL FFmpeg | There is no GPL build and no way to swap the embedded one. Shipping GPL binaries would make your whole app GPL-3.0, which is not a choice a library should make for you. |
| `libx264`, `libx265`, `libsvtav1`, `libopus`, `libmp3lame` | No third-party encoder is linked. `mpeg4` is the software video baseline and `aac` the audio one. Decoding is far wider than encoding. |
| Choosing a bitstream filter yourself | These fix up packet formatting when moving between container types. You cannot pick one by hand, but the common ones are built in and FFmpeg applies them for you during a copy. |
| Hardware decoding on Linux and the web | Hardware decoding is VideoToolbox on Apple platforms, MediaCodec on Android and Direct3D 11 (`HardwareAccel.D3d11va`) on Windows, which has not yet run on a Windows GPU. Linux decodes in software: VA-API would make libva a required system library. Hardware **encoding** is VideoToolbox and MediaCodec; on virtual machines and CI runners pass `allow_sw`, where the encoder exists but the physical chip does not. |
| `https` | The embedded build has no TLS backend. Use `http`, a local file, or link your own FFmpeg tree. |
| An automated device job in CI | Android and iOS are verified by hand and by a shipping app, not by a phone farm on every push. Desktop and Windows are CI-verified. |
| A frozen API | The 0.x line is pre-1.0, so signatures can still change. What you do get: every public declaration is explicit, and a snapshot of the whole API is checked on every push, so a change fails the build here rather than surprising you at your call site. |

### What the published builds can encode

This list was read out of the shipped binary itself, not copied from a build script.

| | macOS | Linux / Windows | iOS | Android |
|---|---|---|---|---|
| Video, software | `mpeg4`, `mjpeg`, `png`, `apng`, `h263`, `h263p` | same | same | same |
| Video, hardware | `h264_videotoolbox`, `hevc_videotoolbox`, `prores_videotoolbox` | none | none | `h264_mediacodec`, `hevc_mediacodec` |
| Audio | `aac`, `flac`, `pcm_s16le`, `pcm_s24le`, `pcm_f32le` | same | same | same |

FFmpeg builds differ, so probe rather than assume: `FFmpeg.hasEncoder(...)`, `FFmpeg.hasFilter(...)`,
`FFmpeg.versions` and `FFmpeg.buildConfiguration` all report what is actually linked. Filters marked
`deps="gpl"` upstream, such as `eq`, are absent here.

If the linked FFmpeg ever disagrees with the headers this artifact was compiled against, the first
call fails with a typed error naming both identities, rather than returning plausible wrong numbers
ten frames later. Full codec, container and filter lists are in
[Platform support](docs/platforms.md).

## Licensing

KiteFFmpeg's own code is Apache-2.0. The embedded FFmpeg is **LGPL-2.1-or-later** and dav1d is
BSD-2-Clause. There is no GPL anywhere, so your application keeps its own licence. The LGPL puts the
obligations below on an app that ships FFmpeg, and static linking on the iOS App Store is the
hardest case of them: [Licensing](https://yuroyami.github.io/KiteFFmpeg/licensing/) explains why.

Shipping an app that statically links LGPL code puts three obligations on you: say your app uses
FFmpeg under the LGPL, keep the corresponding FFmpeg source available, and let users relink against
a modified FFmpeg. The first is satisfied by a notice with the licence text, which the JVM jar
carries at `META-INF/licenses/kiteffmpeg-ffmpeg/`. The second is satisfied by the source tarball
attached to each release. [NOTICE](NOTICE) states this precisely; [Licensing](docs/licensing.md) is
the full guide.

## Build and test it here

The sample is a Kotlin/Native CLI over the whole API. It picks its encoder by probing the linked
FFmpeg, so it works against the embedded LGPL build and against your own tree alike.

```bash
./gradlew :kiteffmpeg-sample:linkDebugExecutableMacosArm64
KEXE=kiteffmpeg-sample/build/bin/macosArm64/debugExecutable/kiteffmpeg-sample.kexe
$KEXE transcode in.mp4 out.mp4 "scale=1280:720" -acopy   # also: info, probe, thumbnail, remux

./gradlew :kiteffmpeg:macosArm64Test          # or linuxX64Test / mingwX64Test
scripts/e2e.sh "$KEXE"
```

The C helper layer builds and tests on its own, outside Gradle:

```bash
cd native/kitecodec-c
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain   # also asan, tsan
./scripts/symbol-audit.sh         # what the archive needs, exports and keeps private
./scripts/replay-corpus.sh        # every committed fuzz seed, under ASan and UBSan
```

What each instrument can and cannot prove is written out in
[native/kitecodec-c/README.md](native/kitecodec-c/README.md). Every build step is in
[Getting started](docs/getting-started.md); the binding design and timestamp rules are in
[About KiteFFmpeg](docs/about.md).

## License

Apache-2.0. See [NOTICE](NOTICE) and [CHANGELOG.md](CHANGELOG.md).

Not affiliated with the FFmpeg project. FFmpeg is a trademark of Fabrice Bellard; this is an
independent Kotlin binding that links FFmpeg's LGPL libraries.

The logo is not official FFmpeg artwork, and KiteFFmpeg's Apache-2.0 grant does not cover it.
[The artwork credits](art/CREDITS.md) record its sources and the trademark notice.

Part of the Kite family: [KitePlayer](https://github.com/yuroyami/KitePlayer),
[Kite3D](https://github.com/yuroyami/Kite3D) and [KitePDF](https://github.com/yuroyami/KitePDF).
