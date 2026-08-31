# KiteFFmpeg

Video and audio processing for Kotlin Multiplatform: read a media file, change it, write it back.

FFmpeg is compiled into the library, for every target. No FFmpeg install, no Gradle plugin, no
linker setup, no `ffmpeg` process to launch and no log output to parse. You add one dependency and
call Kotlin functions.

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

## The whole pipeline in one call

Demux, decode, filter, encode, mux. One pass, video and audio together, and memory does not grow
with the length of the input.

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

Filters use FFmpeg's own filtergraph syntax, so anything you can write for `ffmpeg -vf` works here
unchanged. Set `videoCopy` or `audioCopy` to stream-copy instead: the equivalent of `-c copy`,
timestamp rescale only, bit-exact.

Frames arrive as a `Flow<Frame>`. Progress arrives as a typed callback. Failures arrive as one
`FFmpegException` over a sealed `FFmpegError`, so a missing encoder is something you branch on
rather than a string you match.

<details>
<summary>New to FFmpeg? The words this page uses</summary>

| Term | What it means |
|---|---|
| demux | Split one media file into its separate streams of compressed packets. |
| decode | Turn compressed packets into raw frames: pixels, or audio samples. |
| encode | Turn raw frames back into compressed packets. |
| mux | Write the packets of several streams into one container file. |
| filter graph | A chain of processing steps applied to frames, written as one text string. |
| transcode | Decode, then encode again, usually into a different codec or size. |
| remux | Move packets into a different container. Nothing is decoded. |
| stream copy | Pass packets through without decoding or encoding. The result is bit-exact. |
| pts | Presentation timestamp. When a frame should appear or play. |

</details>

## What it does

`transcode`, `remux`, `extractFrame` and the encoders' `drive` are suspend functions, and decode
flows are collected. Call them from a coroutine.

| Task | Entry point | Guide |
|---|---|---|
| Transcode a file | `Transcoder.transcode(...)` | [Transcoding](docs/transcoding.md) |
| Cut a frame-exact clip | `transcode(..., startMicros, endMicros)` | [Transcoding](docs/transcoding.md) |
| Rewrite a container losslessly | `Remuxer.remux(...)` | [Remuxing](docs/remuxing.md) |
| Read decoded frames | `MediaSource.decodedFrames(...)` | [Decoding](docs/decoding.md) |
| Decode several streams in one pass | `MediaSource.decodeStreams(...)` | [Decoding](docs/decoding.md) |
| Grab a thumbnail | `MediaSource.extractFrame(...)` | [Decoding](docs/decoding.md) |
| Apply a filter chain | `FilterGraph.buildVideo` / `buildVideoMulti` | [Filtering](docs/filtering.md) |
| Encode frames you generate | `MediaSink` plus `Frame.ofVideo` / `ofAudio` | [Encoding and muxing](docs/encoding-muxing.md) |
| Probe what is linked | `FFmpeg.hasEncoder(...)` / `hasFilter(...)` | [Platform support](docs/platforms.md) |

Trim bounds are microseconds into the content, not raw container timestamps. A filter graph names
its inputs `[in0]` to `[inN-1]` and its single output `[out]`.

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

Two concurrent `decodedFrames` flows would race the demuxer and are rejected. To read video and
audio together, use `decodeStreams(...)`, which decodes them in one pass.

For a player, which needs audio and video decoding to advance independently and to seek while both
run, there is a lower-level surface behind the `@KiteFFmpegLowLevelApi` opt-in: `openPacketReader`
for owned packets with a real seek window, and `openDecoder` for one independently driven decoder
per stream. [KitePlayer](https://github.com/yuroyami/KitePlayer) is built on it. The batch API
above is the front door.

## Where it runs

| | Targets |
|---|---|
| **Plays media** | `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64`, `iosArm64`, `iosSimulatorArm64`, JVM on macOS arm64 |
| **Builds, nothing has run** | `macosX64`, `iosX64`, `androidNativeArm64` / `Arm32` / `X64`, and the Android AAR (`minSdk 26`, JNI for `arm64-v8a` and `x86_64`) |
| **Plays media, once you supply the wasm module** | `wasmJs`. Demux, decode and seek are real. Encode, mux and filter are refused by design |
| **Placeholder** | `js`. The API resolves and capability probes answer, every media call throws `FFmpegError.Unsupported` |

All of these publish at 0.1.0. Two caveats worth reading before you plan around the first row: the
iOS entries are proven on a development Mac rather than in CI, and the JVM jar carries a **macOS
arm64** native library and only that one, so a JVM app on Linux or Windows resolves the artifact
and then gets the typed unavailable placeholder. Per-target detail is in
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

Calls before that throw `KiteFFmpegWeb.NotLoaded`. Two things to know before choosing it: the
module is not published with the artifact, so you build it yourself with
`:kiteffmpeg:buildFFmpegForWasm*` (needs emscripten); and the Kotlin side is tested against a fake
codec module, which proves the binding reads the right fields and proves nothing about the built
artifact. A real browser run against a real module has not been recorded yet.

## What it will not do

| Not available | What that means for you |
|---|---|
| A JVM distribution beyond macOS arm64 | Linux and Windows JVM apps get the typed unavailable placeholder, not a codec. |
| Encode, mux or filter on the web | Both web targets refuse them. `wasmJs` is a playback backend: demux, decode, seek. `js` refuses everything. |
| Any GPL FFmpeg | There is no GPL build and no way to swap the embedded one. Shipping GPL binaries would make your whole app GPL-3.0, which is not a choice a library should make for you. |
| `libx264`, `libx265`, `libsvtav1`, `libopus`, `libmp3lame` | No third-party encoder is linked. `mpeg4` is the software video baseline and `aac` the audio one. Decoding is far wider than encoding. |
| A bitstream filter API | Nothing binds `av_bsf_*`. The common ones are compiled in, so libavformat still inserts them automatically during a stream copy. |
| Hardware *decode* and zero-copy hwframes | Hardware **encode** works. On VMs and CI runners pass `allow_sw`, where the encoder exists but the hardware block does not. |
| `https` | The embedded build has no TLS backend. Use `http`, a local file, or link your own FFmpeg tree. |
| Android playback qualification | The AAR builds, links and packages correctly. Nothing has been played on a physical device. |
| A stable API | 0.1.x is pre-1.0. `explicitApi()` is on and a committed klib dump is verified by `apiCheck` on every push, so a signature change fails a build rather than surprising you. That is visibility, not a promise of no change. |

### What the published builds can encode

Read out of the shipped `libavcodec.a`, not from a configure line.

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
