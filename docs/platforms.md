# Platform support

Decode, encode, transcode, remux, and filter video and audio from shared Kotlin code. Transcode
means decode and then re-encode. Remux means copy the existing streams into a different container.
The API lives in `commonMain`: Kotlin/Native actuals use cinterop, while JVM and Android use a
dynamically registered JNI bridge over the same opaque FFmpeg helper boundary. `wasmJs` uses a
generated binding over a wasm module you load at runtime. `js` is the one unsupported placeholder.
Everything is published on Maven Central with FFmpeg embedded.

## Target matrix

There is one target table for the project and it lives in the [README](https://github.com/yuroyami/KiteFFmpeg#where-it-runs). It records, per target, whether a public artifact exists, exactly what build/test evidence exists, and where FFmpeg comes from. This page covers the part it does not: how to obtain an FFmpeg for each target, and what is inside the one KiteFFmpeg builds.

Two points decide whether KiteFFmpeg is usable for you:

- Kotlin/Native implementations live in `nativeMain`; the Android implementation and the JVM one
  compile the JNI sources from `jvmAndAndroidMain`. **Both are published and both are real.** The
  Android AAR declares `minSdkVersion 26` in its own manifest and carries `libkitecodec_jni.so` for
  `arm64-v8a`, `armeabi-v7a` and `x86_64`, with packaging checks and 16 KiB alignment on the two
  64-bit ABIs. `armeabi-v7a` is there for the streaming sticks and budget television boxes, which
  are 32-bit only. The JVM jar carries `libkitecodec_jni` for macOS arm64, Linux x64, Linux arm64
  and Windows x64. The Linux and Windows libraries are cross-linked with Kotlin/Native's own
  toolchain and link-checked; no Linux or Windows machine has run them yet. A JVM on any other
  platform falls back to `unsupportedMain` and gets readable diagnostics rather than a codec.
  Android and iOS play real media on real phones as the engine under
  [KitePlayer](https://github.com/yuroyami/KitePlayer); what they lack is an automated device job in
  this repository's CI. `wasmJs` is a real playback backend once its wasm module is loaded, while
  `js` reports no capabilities and rejects every media operation with typed
  `FFmpegError.Unsupported`.
- **KiteFFmpeg is published**: `io.github.yuroyami:kiteffmpeg:0.3.0` on Maven Central, one
  dependency line, FFmpeg embedded inside the artifacts. There is no Gradle plugin and no FFmpeg
  download step. `mingwX64` builds and tests in CI; `linuxArm64` runs its native suite in an arm64
  container; `iosX64` and `macosX64` remain unqualified.

## FFmpeg comes with the library

KiteFFmpeg embeds FFmpeg's libav\* libraries plus dav1d in every published artifact: inside each
native target's klib, and as native libraries inside the JVM jar and the Android AAR. An app
provisions nothing, and its users install nothing. See the README's
[target table](https://github.com/yuroyami/KiteFFmpeg#where-it-runs).

The embedded FFmpeg is a minimal static build from source with a pinned codec and filter set. Its
desktop size is around 25 MB; no mobile size is claimed before it is measured. To build it inside
this repository, see [Building from source](building-from-source.md).

The static profile is **LGPL by default**: no `--enable-gpl`, no libx264 / libx265. A closed-source app can ship it, under the obligations that
[Licensing](licensing.md) lists.

The READ side of the profile is wide by class: every decoder, demuxer, parser, bitstream filter
and hwaccel FFmpeg `n9.0.2` can build without extra libraries is compiled, so what FFmpeg can play,
a vendored build can play. Note the boundary of that sentence: components FFmpeg gates behind an
external library (software AV1 via libdav1d/libaom on mobile profiles, for example) exist only in
the flavours that link those libraries. The WRITE side and the protocol list remain deliberately
small. If an encoder, muxer, filter or protocol is not listed here, it is not in the generated
profile. This table describes compiled profile contents, not per-target runtime qualification.
The recipe is `sharedCoreArgs()` in [`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteFFmpeg/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt). The encode rows below were read out of each tree's `libavcodec.a` as of `n9.0.2`, not copied from that recipe: `nm` lists one `ff_<name>_encoder` symbol for each encoder the archive carries.

Every profile is PORTABLE since 2026-08-22: no third-party desktop stack anywhere. The optional dav1d flavour adds the `libdav1d` AV1 software decoder to any column.

The filters crop, transpose, hflip, vflip, fps, fade, setsar, setdar, drawbox and pan joined the recipe after 0.3.0, so the trees of 0.3.0 do not carry them. The next release carries them in every column.

| | macOS LGPL | Mobile Apple LGPL | Linux / Windows LGPL | Android LGPL |
|---|---|---|---|---|
| **Video encode** | `mpeg4`, `mjpeg`, `png`, `apng`, `h263`, `h263p`, `h264_videotoolbox`, `hevc_videotoolbox`, `prores_videotoolbox` | `mpeg4`, `mjpeg`, `png`, `apng`, `h263`, `h263p` | same as Mobile Apple | `mpeg4`, `mjpeg`, `png`, `apng`, `h263`, `h263p`, `h264_mediacodec`, `hevc_mediacodec` |
| **Audio encode** | `aac`, `flac`, `pcm_s16le`/`s24le`/`f32le` | same | same | same |
| **Decode** | every native FFmpeg decoder; VideoToolbox hwaccel behind h264/hevc | every native FFmpeg decoder; VideoToolbox hwaccel behind h264/hevc | every native FFmpeg decoder; on Windows, the D3D11VA hwaccel behind h264/hevc/vp9/mpeg2/vc1/wmv3 | every native FFmpeg decoder + MediaCodec h264/hevc |
| **Demux** | every native FFmpeg demuxer | same | same | same |
| **Mux (write)** | mp4/mov, matroska/webm (including `.mka`), mpegts, mp3, wav, flac, ogg/opus, image2 | same | same | same |
| **Protocols** | `file`, `fd`, `pipe`, `data`, `http`, `tcp` | same | same | same |
| **Filters** | the shared set: scale, pad, crop, transpose, hflip, vflip, fps, fade, setsar, setdar, drawbox, overlay, hue, unsharp, vignette, colorbalance, colorlevels, curves, lut, colorchannelmixer, split, trim/setpts, the deinterlacers yadif and bwdif, and the audio set with pan and the loudness filters loudnorm, ebur128 and alimiter | same | same | same |
| **Bitstream filters** | all of them (they ride with the wide demuxer class) | same | same | same |

There is no GPL column and no `drawtext`/`eq`/`boxblur` anywhere: this project bakes the LGPL portable profile only. Use `hue` (it has a brightness parameter `b`), `colorlevels` or `curves` where you reached for `eq`. The bitstream filters are never named by KiteFFmpeg. libavformat inserts them during a stream copy, which is a copy of encoded packets with no decode or encode. Without them, a copy between container families produces a *corrupt file* rather than an error.

`mpeg4` is the dependency-free video baseline: it is always present, in every flavour, so code that must encode *something* without pulling in a GPL or hardware encoder has a target. `https` is **not** built. It needs a TLS backend cross-compiled for every target, and this profile does not include one. Use `http`, a local file, or link a system FFmpeg that has TLS.

The `fd` protocol is what makes an Android `content://` file playable. Open a descriptor with `ContentResolver.openFileDescriptor`, then open `"fd:"` with the pre-open option `fd` set to the descriptor number:

```kotlin
val pfd = contentResolver.openFileDescriptor(uri, "r")!!
val source = MediaSource.open("fd:", mapOf("fd" to pfd.fd.toString()))
```

`Transcoder.transcode` and `Remuxer.remux` take the same pair through their `inputOptions`
overloads, so a picked file converts without a copy:

```kotlin
Transcoder.transcode("fd:", mapOf("fd" to pfd.fd.toString()), output = outputPath, spec = spec)
```

To write the result into bytes the app keeps rather than a file, use the overloads that take a
`MediaByteSource` factory and a `MediaByteSink`, with the container named by `format`.

FFmpeg `dup()`s the descriptor, so the caller keeps ownership, and its `fstat` marks a regular file seekable. Do **not** reach for `/proc/self/fd/N` through the `file` protocol instead: that re-opens by path, the kernel rechecks permissions against the path, and a descriptor a process may legitimately read can still fail with `Permission denied`. `pipe:<fd>` also dups but hardcodes the stream as non-seekable, which costs you seeking.

Probe rather than assume. `FFmpeg.hasEncoder("libx264")` is cheap. It turns a runtime failure on a user's machine into a clear message.

Want libx264 / libx265? **KiteFFmpeg does not build them and has no task that will.** The GPL build
tasks were deleted on 2026-08-21: distributing a GPL-flavoured binary makes the consuming
application GPL-3.0, and that is not a decision a library should take on anyone's behalf.

What is still supported is linking a GPL tree **you** built and own:

```bash
# You produce this tree yourself, however you like, and put it here:
#   native-libs/gpl/<target>/{include,lib}
./gradlew build -Pkiteffmpeg.ffmpeg.license=gpl
```

The flavour name is a path segment and an entry in the identity report, so the build knows what it
linked. iOS targets refuse the GPL flavour outright rather than producing an App-Store-unsafe binary.
See [Licensing](#licensing) below before you ship one.

## Mobile Apple local substrate

On an arm64 Mac, the local phone selector registers exactly `macosArm64`, `iosArm64` and `iosSimulatorArm64`:

```bash
./gradlew :kiteffmpeg:buildFFmpegForMacosArm64 \
  :kiteffmpeg:buildFFmpegForIosArm64 \
  :kiteffmpeg:buildFFmpegForIosSimulatorArm64

./gradlew :kiteffmpeg:compileKotlinMacosArm64 \
  :kiteffmpeg:compileKotlinIosArm64 \
  :kiteffmpeg:compileKotlinIosSimulatorArm64 \
  -Pkiteffmpeg.applePhoneTargetsOnly=true
```

The mobile Apple profile is the current STANDARD software-playback set from `sharedCoreArgs()`, `--disable-autodetect`, SDK zlib and SDK cross flags. It has no desktop third-party archives, GPL flags or hardware encoders. It does carry VideoToolbox and AudioToolbox: `libavcodec.a` holds the ten VideoToolbox decode hwaccels, so the iOS link flags are `-lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox`, which `ffmpeg.def` passes. `buildFFmpegForIos*Gpl` tasks do not exist, and repository build/path resolution refuses GPL for every iOS target before tree lookup with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`

`-Pkiteffmpeg.applePhoneTargetsOnly=true` is mutually exclusive with the stable and host-only selectors. It is accepted by `publishToMavenLocal` for a private consumer proof and explicitly rejected by every remote publish. Generated `native-libs` trees and Maven-local files are never release evidence.

## Windows (mingwX64)

Windows has **no system-FFmpeg discovery**: `FFmpegPaths` resolves macOS (Homebrew) and Linux (apt) installs, but for `mingwX64` it requires a populated `native-libs/<license>/mingw-x64/` tree. You provide it in one of two ways.

**Option A: use this repository's own prebuilt static tree (what CI does).** Every KiteFFmpeg
release publishes an `ffmpeg-<version>-lgpl-mingw-x64.zip` beside a `.sha256`, built from the same
configure line the published klibs embed. CI downloads it, verifies the checksum and unzips it into
place, which is why the Windows job tests the SHIPPED profile rather than somebody else's build:

```powershell
# Tag and asset are pinned, never "latest", and the checksum is verified before use.
$tag  = "ffmpeg-n9.0.2"
$name = "ffmpeg-n9.0.2-lgpl-mingw-x64.zip"
Invoke-WebRequest -Uri "https://github.com/yuroyami/KiteFFmpeg/releases/download/$tag/$name" -OutFile $name
Expand-Archive $name -DestinationPath native-libs\lgpl\mingw-x64
```

The tree is static and LGPL, so the build needs no license property and the test binaries need no DLL on `PATH`. This is how [CI](https://github.com/yuroyami/KiteFFmpeg/blob/main/.github/workflows/ci.yml) runs the Windows tests and the e2e transcode on every push.

CI used to take a BtbN autobuild here. It stopped on 2026-08-24, for two reasons worth repeating:
BtbN prunes old autobuilds, so a pinned tag eventually 404s, and a third-party build is not the
build this project ships, so testing against it proved the wrong thing.

**Option B: vendored static cross-compile.** Run `:kiteffmpeg:buildFFmpegForMingwX64` with a mingw-w64 cross toolchain (`x86_64-w64-mingw32-gcc`) available. This is realistic from a Linux host or MSYS2; it needs the `vendor/ffmpeg` clone described above.

Windows builds, tests, and e2e-transcodes in CI via Option A, against this repository's own release tag, asset name and SHA-256, pinned in the workflow. There is no one-command onboarding path on a bare Windows machine and no system-FFmpeg discovery. You stage the tree yourself, from the release asset or with Option B.

## Android FFmpeg profiles

Kotlin/Native treats the Android NDK as just another native family, so the entire decode → filter → encode → mux pipeline (and `Remuxer`) compiles untouched for `androidNativeArm64`, `androidNativeArm32`, and `androidNativeX64`.

```bash
git clone --depth 1 --branch n9.0.2 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./gradlew :kiteffmpeg:buildFFmpegForAndroidArm64       # NDK cross-compile, ~6 min
./gradlew :kiteffmpeg:compileKotlinAndroidNativeArm64  # the klib
```

The same Android FFmpeg profile also supplies the JNI libraries for the regular Android target.
It is deliberately different from the desktop one:

- **LGPL only.** No `--enable-gpl`, no libx264 / libx265. Play-Store-safe and closed-source-safe.
- **FFmpeg's MediaCodec wrappers** (`h264_mediacodec`, `hevc_mediacodec`) replace the GPL software encoders in the generated profile. KiteFFmpeg reaches them only by an FFmpeg codec name; it does not call the Android codec API directly.
- **Full software decode set** (h264 / hevc / vp8 / vp9 / av1, plus audio) identical to desktop.

!!! warning "Named Android codecs and the JavaVM"
    The regular Android loader checks the complete FFmpeg identity before attaching the app's
    `JavaVM`. The low-level API can then request an exact FFmpeg decoder name, for example
    `source.openDecoder(stream, decoder = DecoderId.H264MediaCodec)`, and verifies that decoder
    against the stream before open. This is not a direct platform-codec call. KitePlayer selects
    `h264_mediacodec` and `hevc_mediacodec` this way and plays with them on `arm64-v8a` phones. No
    Android hardware encoder has run on a device.

!!! note "Two Android target models"
    The `compileKotlinAndroidNative*` flow above produces Kotlin/Native `.klib` files. Separately,
    `-Pkiteffmpeg.phoneTargetsOnly=true` narrows a LOCAL build to the Android KMP target and the
    three Apple targets. It is a build-scope selector, refused by remote publication; it is not what
    decides whether an artifact exists. The AAR carries exactly `arm64-v8a`, `armeabi-v7a` and
    `x86_64` JNI libraries at `minSdk 26`. All three arms are link- and package-checked, and the two
    64-bit ones with 16 KiB constraints; `armeabi-v7a` and `x86_64` have no runtime qualification.
    `js` remains a typed placeholder in every scope; `wasmJs` does not, and carries a real playback
    backend.

## Licensing

The Apache 2.0 license covers KiteFFmpeg's own Kotlin code. The FFmpeg you link against carries its own license, and that license decides what shipping your binary obliges you to do. The choice is made at the FFmpeg build level, as two flavours:

| Flavour | FFmpeg license | Encoders | Use for |
|---|---|---|---|
| **LGPL** (the only one built here) | LGPL-2.1+ (no `--enable-gpl`) | `mpeg4`, `mjpeg`, `png`, `apng`, `h263`, `h263p`, `aac`, `flac` and the three `pcm_*` everywhere; plus VideoToolbox encode on macOS and MediaCodec encode on Android. No third-party encoder is linked: no svtav1, opus or mp3lame. | Commercial / closed-source / App Store distribution (mind the [LGPL obligations](licensing.md)) |
| **GPL** (a tree you supply) | GPL, and GPL-3.0 if your own build sets `--enable-version3` | Whatever you configured, typically libx264 / libx265 | GPL-compatible projects only (open-source apps, server tools, internal use) |

`buildFFmpegFor<Target>` produces the LGPL flavour and that is what the build links by default.
**There is no `buildFFmpegFor<Target>Gpl` task**: those were deleted on 2026-08-21 rather than kept
as an opt-in, because publishing a GPL-flavoured Release asset makes the licence decision for every
consumer who downloads it. `-Pkiteffmpeg.ffmpeg.license=gpl` still selects `native-libs/gpl/<target>/`,
so a GPL tree is one you build, own and point the build at.

!!! warning "GPL is not App-Store-safe"
    A build linking libx264 / libx265 is GPL, and `--enable-version3` makes it GPL-3.0. A binary that
    links those must not ship through the iOS App Store or any other closed-source or commercial
    channel. The iOS targets refuse the GPL flavour rather than let that happen by accident.

!!! note "`kiteffmpeg-gpl` does not exist"
    A separate `kiteffmpeg-gpl` artifact packaging a GPL flavour is a README-only skeleton, commented
    out of `settings.gradle.kts`, and nothing schedules it. Do not plan around it.

For distribution obligations (shipping license texts, offering FFmpeg source, the LGPL relinking requirement for static builds), see the [Licensing guide](licensing.md).

### Picking an encoder per platform

`CodecId` names the format and `EncoderId` names the encoder that writes it; `EncoderId` has the
relevant FFmpeg encoder names as companions. Software libx264 is GPL; the standing
hardware-encoder runtime evidence here is VideoToolbox on the qualified macOS desktop profile.
The Android profile contains MediaCodec encoders, but no Android hardware encoder has run on a
device.

=== "Qualified macOS hardware"

    ```kotlin
    // macOS desktop profile: VideoToolbox
    VideoEncoderSpec(
        codec = CodecId.H264,
        encoder = EncoderId.H264VideoToolbox,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    )
    ```

=== "GPL software (libx264)"

    ```kotlin
    VideoEncoderSpec(
        codec = CodecId.H264,
        encoder = EncoderId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
        options = mapOf("preset" to "medium", "crf" to "20"),
    )
    ```

Encoder availability is resolved at runtime. Ask the build which encoders it has for a format before you commit to one:

```kotlin
FFmpeg.encodersFor(CodecId.H264)   // [h264_videotoolbox] on the macOS desktop profile, [libx264, ...] on a GPL build
FFmpeg.encodersFor(CodecId.Mpeg4)  // [mpeg4], present in every profile
```

## Related

- [Getting started](getting-started.md): build the sample and run your first transcode.
- [Encoding and muxing](encoding-muxing.md): `MediaSink`, encoder specs, and codec options.
- [API reference](https://yuroyami.github.io/KiteFFmpeg/api/): the full public surface.
