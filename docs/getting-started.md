# Getting Started

Learn how to add KiteFFmpeg to a project, probe what your build can do, inspect a media file, and
run your first transcode with KiteFFmpeg: a coroutine-first Kotlin Multiplatform API over
FFmpeg's libav* libraries.

!!! warning "Before you start"

    KiteFFmpeg is on Maven Central: `io.github.yuroyami:kiteffmpeg:0.3.0`, one dependency line,
    with FFmpeg embedded inside the artifacts. The Android AAR is real, declares `minSdkVersion 26`
    and carries `arm64-v8a`, `armeabi-v7a` and `x86_64` JNI libraries. The JVM jar carries a
    native library for macOS arm64, Linux x64, Linux arm64 and Windows x64; the Linux and Windows
    ones are link-checked and have not run yet. `wasmJs` is a real playback backend once you load
    its wasm module; `js` is a placeholder that makes dependency resolution predictable and performs no
    media work.
    The consumer script, release status and per-target evidence are in the
    [README](https://github.com/yuroyami/KiteFFmpeg#where-it-runs).

## Step 1: Add the dependency

One dependency line is the whole setup. FFmpeg is compiled into every published artifact, so there
is nothing to install, no Gradle plugin and no download step:

```kotlin
sourceSets.commonMain.dependencies {
    implementation("io.github.yuroyami:kiteffmpeg:0.3.0")
}
```

Your users need nothing installed either. To build KiteFFmpeg itself from this repository, see
[Building from source](building-from-source.md).

## Step 2: Probe what your build can do

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

## Step 3: Open and inspect a file

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

## Step 4: Your first transcode

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
    `mpeg4` is used above because it is in every profile. For H.264 or H.265, ask the linked build what it has rather than hard-coding a name. `EncoderId.Libx264` only exists in a GPL FFmpeg. The vendored default is LGPL, and asking for it there throws `FFmpegException` from `addVideoEncoder`.

    ```kotlin
    val encoders = FFmpeg.encodersFor(CodecId.H264)   // the ones this build has, its default first
    val spec = if (encoders.isEmpty()) null else VideoEncoderSpec(
        codec = CodecId.H264,
        encoder = encoders.first(),
        width = 1280, height = 720,
        frameRate = Rational(30, 1),
    )
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

## Step 5: Run the sample

The `:kiteffmpeg-sample` module is a small command-line program that exercises the whole API. It builds for macOS arm64, Linux x64, Linux arm64 and Windows x64, and CI runs it on macOS, Linux x64 and Windows. Build it, then point it at any media file.

```bash
# The repository build needs an FFmpeg tree; see Building from source.
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
- **[Troubleshooting](troubleshooting.md)**: repository build problems, Windows setup, VideoToolbox on VMs.
- **[API reference](https://yuroyami.github.io/KiteFFmpeg/api/)**: every public type and signature.
