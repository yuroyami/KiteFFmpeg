# Getting Started

Learn how to install FFmpeg, wire the module, probe what your build can do, inspect a media file,
and run your first transcode with KiteFFmpeg: a coroutine-first Kotlin Multiplatform API over
FFmpeg's libav* libraries.

!!! warning "Before you start"

    KiteFFmpeg is on Maven Central: `io.github.yuroyami:kiteffmpeg:0.1.0`, one dependency line,
    with FFmpeg embedded inside the artifacts. The Android AAR is real, declares `minSdkVersion 26`
    and carries `arm64-v8a` and `x86_64` JNI libraries. The JVM jar carries a **macOS arm64**
    library and only that one, so a JVM consumer on Linux or Windows gets the typed unavailable
    placeholder instead of a codec. `wasmJs` is a real playback backend once you load its wasm
    module; `js` is a placeholder that makes dependency resolution predictable and performs no
    media work.
    The consumer script, release status and per-target evidence are in the
    [README](https://github.com/yuroyami/KiteFFmpeg#where-it-runs).

## Step 1: Get FFmpeg

KiteFFmpeg links against FFmpeg's libav* libraries. You need them present before you build. There are two ways to source them.

=== "System FFmpeg (default)"

    Install FFmpeg with your package manager. This is what the macOS arm64 build does today.

    ```bash
    # macOS
    brew install ffmpeg

    # Debian / Ubuntu
    sudo apt install -y \
        libavformat-dev libavcodec-dev libavfilter-dev \
        libavutil-dev libswscale-dev libswresample-dev
    ```

    `FFmpegPaths` finds Homebrew on macOS (override with `kiteffmpeg.macos.homebrew.prefix` in `gradle.properties`) or apt-installed libraries on Linux, compiles KiteFFmpeg's C archive against their headers, and links their shared libraries. The cinterop def itself parses only KiteFFmpeg's opaque helper, handle and ABI headers; the module build still passes the FFmpeg include path to cinterop redundantly, where the reduced header set leaves it unused. Your users need their own FFmpeg installed at runtime.

=== "Vendored static build"

    For a release where you do not want a runtime FFmpeg dependency, build a minimal static FFmpeg from source with the Gradle task. It drops `.a` libraries under `native-libs/<license>/<target>/`; `FFmpegPaths` compiles the C archive against that tree and switches the final link to the static libraries automatically.

    The task expects the FFmpeg source tree at `vendor/ffmpeg`. Cloning it is a **mandatory first step**:

    ```bash
    git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg

    ./gradlew :kiteffmpeg:buildFFmpegForMacosArm64
    # or build every target you have toolchains for:
    ./gradlew :kiteffmpeg:buildFFmpegForAll
    ```

    Configure, make and install run in a unique hash-free directory under `java.io.tmpdir`. The task installs the normalized configure invocation as the single-line `lib/kiteffmpeg/ffmpeg-configure.txt` provenance record, requires it during verification, copies the verified install to a sibling staging directory and only then replaces `native-libs`. A failed build preserves the last good tree even when the checkout path contains `#`; packaging reads only that installed record.

    Every profile is portable (2026-08-22): no third-party libraries are needed on any target. The prerequisites are `make`, a C toolchain and, for the x86_64 targets' assembly, `nasm`. The dav1d flavour additionally needs `meson` and `ninja`. On macOS: `brew install nasm meson ninja`. See [Troubleshooting](troubleshooting.md#vendored-build-prerequisites) if configure fails.

    Every bake is **LGPL** (no libx264 / libx265). There are no GPL build tasks: a GPL tree is something you build and own yourself, consumed through `FFmpegSource.Local`.

!!! tip "Android"

    Android uses a separate LGPL-only FFmpeg profile with FFmpeg's MediaCodec wrappers. The
    Kotlin/Native flow cross-compiles that profile before building a klib. The regular Android
    source model uses the same profile through JNI, packages only `arm64-v8a` and `x86_64`, and
    reaches a platform codec only through an FFmpeg name such as `h264_mediacodec`. The current
    proof stops at source, host tests, link and packaging; it is not a public install or playback
    result. See [Platform support](platforms.md) for both target models.

!!! tip "Mobile Apple local trees"

    On an arm64 Mac, build the host, device and simulator trees in one producer invocation:

    ```bash
    ./gradlew :kiteffmpeg:buildFFmpegForMacosArm64 \
      :kiteffmpeg:buildFFmpegForIosArm64 \
      :kiteffmpeg:buildFFmpegForIosSimulatorArm64
    ```

    Generated trees remain untracked. iOS has no GPL task; `buildFFmpegForIos*Gpl` is deliberately not registered. Repository build/path resolution refuses GPL for every iOS target before tree lookup with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`

## Step 2: Wire the module

**The normal way is one dependency line**, and it needs nothing else on disk because FFmpeg rides
inside the published artifacts:

```kotlin
sourceSets.commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.1.0")
}
```

The rest of this step covers working INSIDE the repository, which is what a contributor needs and
what the runnable examples below assume. A consumer does not need any of it.

**Inside the KiteFFmpeg repository.** The `:kiteffmpeg-sample` module already depends on `:kiteffmpeg` and is the fastest way to run the API against real arguments. Everything below works from a plain clone.

**From your own project.** Clone KiteFFmpeg alongside it and compose the builds:

=== "settings.gradle.kts"

    ```kotlin
    includeBuild("../KiteFFmpeg")
    ```

=== "build.gradle.kts"

    ```kotlin
    kotlin {
        macosArm64()
        sourceSets.commonMain.dependencies {
            implementation("io.github.yuroyami:kiteffmpeg")
        }
    }
    ```

    A composite build substitutes the dependency with the included project, so the version is omitted deliberately. Your FFmpeg comes from KiteFFmpeg's own `FFmpegPaths` resolution (Step 1), not from the Gradle plugin.

For a private consumer proof, publish the three Apple variants locally in a separate invocation with `./gradlew publishToMavenLocal -Pkiteffmpeg.applePhoneTargetsOnly=true`. Then configure the consumer plugin with `source = FFmpegSource.Local`, `license = FFmpegLicense.LGPL` and `localRoot` pointing at this checkout's absolute `native-libs` directory. Its fixed layout is `<localRoot>/<license.id>/<target-triple>/{include,lib}`. The plugin validates every wired tree and performs no download. This selector is local-only; any remote `publish` task refuses it during configuration.

Once `kiteffmpeg` is publicly published, a native
consumer build script can replace the composite build. It is written out in full in the
[README](https://github.com/yuroyami/KiteFFmpeg#install); the plugin is mandatory for Kotlin/Native
because the klib's `ffmpeg.def` carries no `-L`, and so is the `license` choice. This is not a
promise that a JVM jar or Android AAR is already available.

!!! note "`kiteffmpeg-gpl` does not exist"

    `kiteffmpeg` is LGPL and is safe for commercial distribution. A `kiteffmpeg-gpl` add-on packaging libx264 / libx265 has a README in the repository and nothing else: no build script, and commented out of `settings.gradle.kts`. There are no `Gpl` build tasks either, so a GPL flavour is a tree you build and own, selected with `-Pkiteffmpeg.ffmpeg.license=gpl`.

## Step 3: Probe what your build can do

Every public type lives under `io.github.yuroyami.kiteffmpeg`. Start with the `FFmpeg` object: it reports the linked library versions and tells you which encoders, decoders, and filters are available in this particular build.

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

Capability probing matters because builds differ. A hardware encoder like `h264_videotoolbox` exists on macOS but not in a Linux VM; checking `FFmpeg.hasEncoder(...)` at runtime lets you pick a codec that is actually present.

## Step 4: Open and inspect a file

`MediaSource.open(path)` opens an input via libavformat and exposes its streams and metadata. It is `AutoCloseable`, so wrap it in `use { }`.

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

    // Convenience accessors for the streams you usually want:
    val v = src.primaryVideo
    val a = src.primaryAudio
}
```

Each `StreamInfo` carries an `index`, a `type` (`MediaType.Video`, `Audio`, `Subtitle`, ...), a `codec` (`CodecId`), a `timeBase` (`Rational`), and either a `video` (`VideoStreamInfo`) or `audio` (`AudioStreamInfo`) detail block. Reading frames out of a stream is covered in [Decoding](decoding.md).

## Step 5: Your first transcode

`Transcoder.transcode(...)` runs the full pipeline in one pass: demux -> decode -> filter -> encode -> mux. Demux means split a container file into its separate streams. Mux means write streams back into a container file. It is a `suspend` function, so call it from a coroutine.

```kotlin
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Rational
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Transcoder.transcode(
        input  = "input.mp4",
        output = "output.mp4",
        spec = VideoEncoderSpec(
            // mpeg4 is the dependency-free baseline present in every FFmpeg profile.
            codec = CodecId("mpeg4"),
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

!!! tip "Pick the video encoder by probing"
    `mpeg4` is used above because it is in every profile. For H.264 or H.265, ask the linked build what it has rather than hard-coding a name. `CodecId.Libx264` only resolves in a GPL FFmpeg. The vendored default is LGPL, and asking for it there throws `FFmpegException` from `addVideoEncoder`.

    ```kotlin
    val codec = listOf(
        CodecId.H264VideoToolbox,   // macOS desktop profile, LGPL-safe
        CodecId.Libx264,            // GPL builds only
        CodecId("mpeg4"),           // always present
    ).first { FFmpeg.hasEncoder(it.name) }
    ```

    See [Platform support](platforms.md#licensing) and [Licensing](licensing.md).

A few defaults worth knowing:

- Pass `audioSpec = null` to drop audio, or `audioCopy = true` to stream-copy it instead of re-encoding. A stream copy moves the encoded packets across unchanged, so the audio stays bit-exact.
- Pass `spec = null` for an audio-only transcode (for example mp3 -> aac).
- `startMicros` and `endMicros` cut a frame-exact clip; output timestamps rebase to zero. `endMicros` has no upper bound unless you set it.
- `onProgress` receives a `TranscodeProgress` with `framesEncoded`, `outputMicros`, and a nullable `percent`. It fires roughly every 30 video frames (or every 100 frames for audio-only work), not on an exact count.

```kotlin
// Cut a clip from 12.3s to 45.6s, re-encoded frame-exact:
Transcoder.transcode(
    input = "input.mp4",
    output = "clip.mp4",
    spec = spec,
    startMicros = 12_300_000,
    endMicros   = 45_600_000,
)
```

If you do not need to touch the codecs at all, skip the transcoder and rewrite the container losslessly:

```kotlin
import io.github.yuroyami.kiteffmpeg.Remuxer

Remuxer.remux("input.mp4", "output.mkv")   // no re-encode, runs in seconds
```

See [Transcoding](transcoding.md) for filters, hardware encoders, and progress in depth, and [Remuxing](remuxing.md) for stream-copy and keyframe-snapped trim.

## Step 6: Run the sample

The `:kiteffmpeg-sample` module is a small macOS arm64 CLI that exercises the whole API. Build it, then point it at any media file.

```bash
brew install ffmpeg                     # macOS prereq
./gradlew :kiteffmpeg-sample:linkDebugExecutableMacosArm64

KEXE=kiteffmpeg-sample/build/bin/macosArm64/debugExecutable/kiteffmpeg-sample.kexe

# Capability probe (same data as FFmpeg.versions / hasEncoder):
$KEXE info

# Inspect any media file (streams, duration, metadata):
$KEXE probe path/to/clip.mp4

# Full transcode: decode, filter, video + aac encode, interleaved mux.
# The sample probes for its video encoder (libx264, else mpeg4, libsvtav1, mjpeg).
$KEXE transcode input.mp4 output.mp4 "scale=1280:720,format=yuv420p"

# Video only / audio passthrough / hardware encode:
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -an
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -acopy
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -vt     # h264_videotoolbox

# Frame-exact clip + metadata:
$KEXE transcode input.mp4 clip.mp4 "scale=1280:720" --ss 12.3 --to 45.6 --title "My clip"

# Audio-only (mp3 in, aac out):
$KEXE transcode song.mp3 song.m4a

# Thumbnail at 90s:
$KEXE thumbnail input.mp4 frame.jpg 90.0

# Lossless container rewrite:
$KEXE remux input.mp4 output.mkv
```

Reading the sample source is the fastest way to see each API used against real arguments.

## Where to next?

- **[Decoding](decoding.md)**: pull `Frame`s out of a stream, decode several streams in one demux pass, extract thumbnails.
- **[Transcoding](transcoding.md)**: the full `Transcoder.transcode(...)` surface: filters, hardware encoders, trim, progress.
- **[Filtering](filtering.md)**: build single-input and multi-input `FilterGraph`s for scaling, overlay, and audio mixing.
- **[Encoding & muxing](encoding-muxing.md)**: drive `VideoEncoder` / `AudioEncoder` directly through a `MediaSink`.
- **[Remuxing](remuxing.md)**: lossless `Remuxer.remux(...)` and stream-copy.
- **[Concurrency](concurrency.md)**: the threading, confinement, and cancellation rules.
- **[Recipes](recipes.md)**: copy-paste patterns for common tasks.
- **[Troubleshooting](troubleshooting.md)**: FFmpeg not found, Windows setup, VideoToolbox on VMs.
- **[API reference](https://yuroyami.github.io/KiteFFmpeg/api/)**: every public type and signature.
