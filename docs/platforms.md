# Platform support

This page lists the targets that KiteFFmpeg publishes, and what the FFmpeg inside each one can
read, write and filter. To build FFmpeg yourself, see [Building from source](building-from-source.md).

## Targets

Every target shares the API in `commonMain`. Kotlin/Native calls FFmpeg through cinterop, the JVM
and Android call it through a small JNI library, and `wasmJs` calls a WebAssembly build of FFmpeg
that the page loads.

| Target | What it carries |
| --- | --- |
| Android library | FFmpeg and the JNI library for `arm64-v8a`, `armeabi-v7a` and `x86_64`, at `minSdk` 26. The two 64-bit libraries are aligned to 16 KiB pages, as Android asks. |
| `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64` | FFmpeg inside the klib, for Kotlin/Native code on Android |
| `iosArm64`, `iosSimulatorArm64`, `iosX64` | FFmpeg inside the klib |
| `macosArm64`, `macosX64` | FFmpeg inside the klib |
| `linuxX64`, `linuxArm64`, `mingwX64` | FFmpeg inside the klib |
| `jvm` | FFmpeg and the JNI library for macOS arm64, Linux x64, Linux arm64 and Windows x64, in one jar for Java 11 or later |
| `wasmJs` | The API only. The page serves the FFmpeg module ([Getting started](getting-started.md#set-up-the-web)). |
| `js` | A placeholder. It compiles, and every media call throws `FFmpegError.Unsupported`. |

Some targets build but have not played media yet: `macosX64`, `iosX64`, the Android `armeabi-v7a`
and `x86_64` libraries, the desktop JVM on Linux and Windows, and the `androidNative*` targets.

### The JVM

The JVM looks for the JNI library in this order:

1. The path in the system property `kiteffmpeg.jni.path`.
2. `java.library.path`, which is how a packager such as `jpackage` supplies its own copy.
3. The copy inside the jar for this operating system and processor, extracted once to a temporary
   directory.

On a platform that the jar does not carry, and with neither property set, the first call throws
`UnsatisfiedLinkError`.

### The web

`wasmJs` reads, decodes and seeks. It cannot write a file, so `Transcoder`, `Remuxer`, `MediaSink`,
`FilterGraph` and `Resampler` throw `FFmpegError.Unsupported` there, and `FFmpeg.hasFilter` returns
`false`. The web module is single-threaded and opens no address by itself. Every byte comes from a
`MediaByteSource` of yours, as [Decoding frames](decoding.md) describes.

The web module carries a smaller set of formats than the other builds:

- **Decoders:** H.264, HEVC, VP9, AAC, MP3, FLAC, Opus, Vorbis and PCM. There is no AV1 decoder.
- **Demuxers:** MP4 and MOV, Matroska and WebM, MP3, FLAC, HLS (with MPEG-TS and raw AAC, AC-3 and
  E-AC-3 segments) and WebVTT.

## What each build carries

Every build except the web one comes from the same FFmpeg n9.0.2 recipe. It is LGPL, with no
`--enable-gpl`, and it links no third-party library except dav1d.

The read side is wide. Every decoder, demuxer, parser and bitstream filter that FFmpeg can build
without an external library is in, plus dav1d for AV1. So a file that FFmpeg can play, these builds
can play. The write side, the filters and the protocols are small, and a name that this page does
not list is not in the build.

### Encoders

These lists come from the encoders that each shipped `libavcodec.a` registers.

| | macOS | iPhone | iOS simulator | Linux and Windows | Android |
| --- | --- | --- | --- | --- | --- |
| Video, software | `mpeg4`, `h263`, `mjpeg`, `png` | same | same | same | same |
| Video, hardware | `h264_videotoolbox`, `hevc_videotoolbox` | `h264_videotoolbox`, `hevc_videotoolbox` | none | none | `h264_mediacodec`, `hevc_mediacodec` |
| Audio | `aac`, `flac`, `pcm_s16le`, `pcm_s24le`, `pcm_f32le` | same | same | same | same |
| Subtitles | `mov_text`, `srt`, `subrip`, `ass`, `webvtt` | same | same | same | same |

No third-party encoder is linked: no `libx264`, `libx265`, `libsvtav1`, `libopus` or `libmp3lame`.
`mpeg4` is in every build, so code that must encode something without a GPL or hardware encoder
always has one.

### Hardware decoding

| Platform | Hardware decoders |
| --- | --- |
| macOS, iOS | VideoToolbox for H.264, HEVC and AV1 |
| Windows | Direct3D 11 for H.264, HEVC, VP9, MPEG-2, VC-1 and WMV3. It has not run on a real Windows GPU. |
| Android | MediaCodec for H.264, HEVC, AV1, VP8, VP9, MPEG-2, MPEG-4, AAC, MP3, AMR-NB and AMR-WB |
| Linux | None. Linux decodes in software. |

FFmpeg uses a MediaCodec decoder only when you ask for it by name, for example
`source.openDecoder(stream, decoder = DecoderId.H264MediaCodec)` with the low-level API.
[Decoding frames](decoding.md) describes it.

### Muxers, protocols and filters

| Part | Contents |
| --- | --- |
| Muxers | `mp4`, `mov`, `ipod`, `webm`, `matroska`, `matroska_audio`, `mp3`, `wav`, `flac`, `ogg`, `opus`, `mpegts`, `image2`, and the subtitle files `srt`, `ass` and `webvtt` |
| Protocols | `file`, `fd`, `pipe`, `data`, `http`, `tcp`, `udp`, `rtp`, `rtmp` |
| Video filters | `scale`, `pad`, `crop`, `transpose`, `hflip`, `vflip`, `fps`, `fade`, `setsar`, `setdar`, `setparams`, `drawbox`, `overlay`, `hue`, `unsharp`, `vignette`, `colorbalance`, `colorlevels`, `curves`, `lut`, `colorchannelmixer`, `format`, `split`, `null`, `trim`, `setpts`, `loop`, `tpad`, `yadif`, `bwdif` |
| Audio filters | `aresample`, `aformat`, `volume`, `atempo`, `adelay`, `afade`, `amix`, `pan`, `anull`, `atrim`, `asetpts`, `asetrate`, `loudnorm`, `ebur128`, `alimiter` |
| Bitstream filters | All of them. FFmpeg inserts the right one during a stream copy. |

There is no `drawtext`, `eq` or `boxblur`, because `eq` and `boxblur` are GPL in FFmpeg. Use `hue`
(its `b` parameter sets the brightness), `colorlevels` or `curves` where you would use `eq`.

You never name a bitstream filter. FFmpeg adds the one that a stream copy needs, and without it a
copy between container families would write a broken file.

## Ask the build at run time

Builds differ, so ask rather than assume. `FFmpeg.hasEncoder(...)` is cheap, and it turns a failure
on a user's machine into a clear branch in your code:

```kotlin
FFmpeg.encodersFor(CodecId.H264)   // [h264_videotoolbox] on macOS, empty on Linux
FFmpeg.encodersFor(CodecId.Mpeg4)  // [mpeg4] in every build
```

`CodecId` names the format, and `EncoderId` names the encoder that writes it:

```kotlin
VideoEncoderSpec(
    codec = CodecId.H264,
    encoder = EncoderId.H264VideoToolbox,
    width = 1280, height = 720,
    frameRate = Rational.Fps30,
)
```

On a virtual machine or a CI runner, `h264_videotoolbox` exists but has no hardware to use. Pass
the option `allow_sw` to let it fall back to software, as
[Troubleshooting](troubleshooting.md) shows.

## https

The builds carry no TLS, so FFmpeg cannot open an `https` address. Fetch the bytes with your own
HTTP client and open them through a `MediaByteSource`. An HLS playlist also needs a
`MediaByteOpener` for its segments, as
[Decoding frames](decoding.md#hls-through-your-own-http-client) shows.

## A file that the user picked on Android

The `fd` protocol reads an Android `content://` file without a copy. Open a descriptor with
`ContentResolver.openFileDescriptor`, then open `"fd:"` with the option `fd` set to the descriptor
number:

```kotlin
val pfd = contentResolver.openFileDescriptor(uri, "r")!!
val source = MediaSource.open("fd:", mapOf("fd" to pfd.fd.toString()))
```

`Transcoder.transcode` and `Remuxer.remux` take the same pair through their `inputOptions`
overloads:

```kotlin
Transcoder.transcode("fd:", mapOf("fd" to pfd.fd.toString()), output = outputPath, spec = spec)
```

To write the result into bytes that the app keeps, use the overloads that take a `MediaByteSource`
factory and a `MediaByteSink`, and name the container with `format`.

FFmpeg duplicates the descriptor, so you still own yours, and it treats a regular file as
seekable. Don't open `/proc/self/fd/N` through the `file` protocol instead. That opens the file
again by path, and the permission check on the path can fail with `Permission denied`. The `pipe:`
protocol also duplicates the descriptor, but it treats the stream as not seekable, so you lose
seeking.

## GPL

No GPL build is published. A GPL FFmpeg (with `libx264` or `libx265`) makes the app that ships it
GPL, and GPL-3.0 with `--enable-version3`, which a library should not decide for you. If your
project can take the GPL, build your own FFmpeg tree and point the KiteFFmpeg build at it, as
[Building from source](building-from-source.md#use-a-gpl-ffmpeg-of-your-own) shows. The iOS targets
refuse a GPL tree, because the App Store cannot ship one.

## Related

- [Getting started](getting-started.md): the dependency and a first transcode.
- [Encoding and muxing](encoding-muxing.md): `MediaSink`, encoder specs and codec options.
- [Licensing](licensing.md): what shipping the LGPL FFmpeg asks of your app.
