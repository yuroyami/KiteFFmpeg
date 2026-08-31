# KiteFFmpeg

Video and audio for Kotlin Multiplatform. Open a media file, change it, save it.

Things people build with it:

- Shrink a large video so it uploads faster
- Cut a clip between two timestamps
- Grab a thumbnail from any point in a video
- Add a watermark, a blur, or a colour change
- Change an `.mkv` into an `.mp4` without re-encoding it
- Read raw frames and draw them yourself

FFmpeg does the actual work, and it is **already compiled into the library** for every platform.
You do not install FFmpeg. You do not add a Gradle plugin or touch linker settings. There is no
`ffmpeg` command being launched behind your back, and no console output to parse. You add one
dependency and call Kotlin functions.

[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteFFmpeg/ci.yml?label=CI)](https://github.com/yuroyami/KiteFFmpeg/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/kiteffmpeg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.yuroyami/kiteffmpeg)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**[Documentation](docs/)** · a guide per task, from your first transcode to building filter graphs.

## Install

```kotlin
// build.gradle.kts
kotlin {
    macosArm64()          // or any target in the table below
    sourceSets.commonMain.dependencies {
        implementation("io.github.yuroyami:kiteffmpeg:0.1.0")
    }
}
```

That is the whole setup. Each native artifact carries its own FFmpeg build (about 10 MB) and its
own platform linker flags.

## One call does the whole job

This reads `input.mp4`, scales it down, tweaks the colour, adds a vignette, re-encodes the video
and audio, and writes `output.mp4`. Video and audio are handled together in a single pass, and
memory stays flat whether the file is one minute or three hours.

```kotlin
Transcoder.transcode(
    input  = "input.mp4",
    output = "output.mp4",
    spec = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = 320, height = 180,
        frameRate = Rational(30, 1),
        bitrateBps = 1_500_000,
    ),
    videoFilter = "scale=320:180,hue=b=0.1,vignette,format=yuv420p",
    audioSpec   = AudioEncoderSpec(codec = CodecId.Aac),
    audioFilter = "volume=0.8",
    onProgress  = { p -> println("encoded ${p.framesEncoded} frames") },
)
```

That filter string is FFmpeg's own syntax, so anything you already know how to write for
`ffmpeg -vf` works here unchanged.

Set `videoCopy` or `audioCopy` if you want to keep a stream exactly as it is instead of
re-encoding it. That is much faster and loses nothing.

Errors come back as one `FFmpegException` wrapping a sealed `FFmpegError`, so "this device has no
AAC encoder" is a case you can match on in code, not a string you have to read.

## The words FFmpeg uses

FFmpeg has its own vocabulary and this page uses it. Here is the whole of it, in plain terms.

| Word | What it means | Everyday version |
|---|---|---|
| **stream** | One track inside a file | The video track, or the English audio track |
| **packet** | A chunk of still-compressed data | One small piece of the video track |
| **frame** | One decoded picture, or a slice of sound | A single image you could display |
| **demux** | Split a file into its streams | Unpack the box |
| **decode** | Turn packets into frames | Unzip a picture so you can look at it |
| **encode** | Turn frames back into packets | Zip the picture back up |
| **mux** | Write streams into one file | Pack the box again |
| **transcode** | Decode, then encode differently | Re-save it smaller, or in another format |
| **remux** | Move streams to a new container, no re-encoding | Change the box, keep the contents |
| **filter** | A step that changes frames | Scale, blur, watermark, adjust colour |
| **pts** | The timestamp on a frame | When this frame should appear |

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
| Grab a single picture from a video | `MediaSource.extractFrame(...)` | [Decoding](docs/decoding.md) |
| Read every frame yourself | `MediaSource.decodedFrames(...)` | [Decoding](docs/decoding.md) |
| Read video and audio frames together | `MediaSource.decodeStreams(...)` | [Decoding](docs/decoding.md) |
| Scale, blur, watermark, adjust colour | `FilterGraph.buildVideo` / `buildVideoMulti` | [Filtering](docs/filtering.md) |
| Build a file out of frames you made | `MediaSink` plus `Frame.ofVideo` / `ofAudio` | [Encoding and muxing](docs/encoding-muxing.md) |
| Ask what this build supports | `FFmpeg.hasEncoder(...)` / `hasFilter(...)` | [Platform support](docs/platforms.md) |

Two details that catch people out. Cut times are **microseconds into the video**, counted from the
start of the content, not the raw numbers stored in the file. And a filter chain names its inputs
`[in0]`, `[in1]` and so on, with a single output called `[out]`.

### Reading frames

```kotlin
MediaSource.open("input.mp4").use { source ->
    val video = source.primaryVideo ?: error("no video track")
    source.decodedFrames(video).collect { frame ->
        val info = frame.info
        println("pts=${info.ptsSeconds}s ${info.width}x${info.height}")
    }
}
```

You cannot collect two of these at once from the same file: they would both try to move the read
position, so the second one is rejected. Use `decodeStreams(...)` when you want video and audio
together.

**Building a media player?** The API above reads a file front to back, which is right for
converting and wrong for playback: a player needs audio and video to advance separately, and to
jump around while both are running. There is a second, lower-level API for that, behind an opt-in
annotation (`@KiteFFmpegLowLevelApi`) because it hands you objects you must free yourself.
[KitePlayer](https://github.com/yuroyami/KitePlayer) is built on it. If you are not writing a
player, stay with the API above.

## Where it runs

| | Targets |
|---|---|
| **Plays real media** | `macosArm64`, `iosArm64`, `iosSimulatorArm64`, the Android AAR (`minSdk 26`, `arm64-v8a` and `x86_64`), `linuxX64`, `linuxArm64`, `mingwX64` |
| **Plays media, once you supply the wasm module** | `wasmJs`. Reading and decoding work, including seeking. Writing files (encode, mux) and filtering are refused by design |
| **Builds, nothing has run** | `macosX64`, `iosX64`, and the `androidNative*` targets, which are for Kotlin/Native on Android and are not what a normal Android app uses |
| **Placeholder** | `js`. The code compiles and you can ask it what it supports (nothing), but every media call throws `FFmpegError.Unsupported` |

All of these publish at 0.1.0.

Android and iOS play real media on real phones: this is the engine under
[KitePlayer](https://github.com/yuroyami/KitePlayer), which is device-tested on both, down to
per-frame GPU timings on a Redmi Note 8. What those platforms do not have is an **automated device
job in this repository's CI** (nobody runs a phone farm here), so their evidence is hand-verified
and app-shipped rather than green-on-every-push. Desktop and Windows are the reverse: CI-verified
on every push.

One thing to plan around: the JVM jar carries a **macOS arm64** native library and only that one, so
a JVM app on Linux or Windows resolves the artifact and then gets the typed unavailable placeholder.
Per-target detail is in [Platform support](docs/platforms.md).

`js` is a deliberate placeholder: a build that silently did nothing would be worse than one that
tells you it cannot.

**`wasmJs` is not a placeholder.** It carries a real playback backend over a generated binding.
FFmpeg cannot be linked into the same binary in a browser, so it arrives as a separate wasm module
you load once before anything else:

```kotlin
KiteFFmpegWeb.load("/kite.mjs")     // or attach() a module the page already instantiated
check(FFmpeg.identity.isAcceptable)
```

Calls before that throw `KiteFFmpegWeb.NotLoaded`. Two things to know before choosing it: the
module is not published with the artifact, so you build it yourself with
`:kiteffmpeg:buildFFmpegForWasm*` (needs emscripten); and the Kotlin side is tested against a fake
codec module, which proves the binding reads the right fields and proves nothing about the built
artifact. A real browser run against a real module has not been recorded yet.

## What it will not do

| Not available | What that means for you |
|---|---|
| A JVM distribution beyond macOS arm64 | Linux and Windows JVM apps get the typed unavailable placeholder, not a codec. |
| Writing or filtering media on the web | Both web targets refuse it. `wasmJs` can read and decode; it cannot produce a file. `js` refuses everything. |
| Any GPL FFmpeg | There is no GPL build and no way to swap the embedded one. Shipping GPL binaries would make your whole app GPL-3.0, which is not a choice a library should make for you. |
| `libx264`, `libx265`, `libsvtav1`, `libopus`, `libmp3lame` | No third-party encoder is linked. `mpeg4` is the software video baseline and `aac` the audio one. Decoding is far wider than encoding. |
| Choosing a bitstream filter yourself | These fix up packet formatting when moving between container types. You cannot pick one by hand, but the common ones are built in and FFmpeg applies them for you during a copy. |
| Hardware-accelerated *decoding* | Hardware **encoding** works. On virtual machines and CI runners pass `allow_sw`, where the encoder exists but the physical chip does not. |
| `https` | The embedded build has no TLS backend. Use `http`, a local file, or link your own FFmpeg tree. |
| An automated device job in CI | Android and iOS are verified by hand and by a shipping app, not by a phone farm on every push. Desktop and Windows are CI-verified. |
| A frozen API | 0.1.x is pre-1.0, so signatures can still change. What you do get: every public declaration is explicit, and a snapshot of the whole API is checked on every push, so a change fails the build here rather than surprising you at your call site. |

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
BSD-2-Clause. There is no GPL anywhere, so your application's own licence is untouched and what you
link is safe for closed source and for the App Store.

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

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
