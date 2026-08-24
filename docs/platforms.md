# Platform support

Decode, encode, transcode, remux, and filter video and audio from shared Kotlin code. Transcode
means decode and then re-encode. Remux means copy the existing streams into a different container.
The API lives in `commonMain`: Kotlin/Native actuals use cinterop, while the local Android proof
uses a dynamically registered JNI bridge over the same opaque FFmpeg helper boundary. A separate
unpublished JVM compilation tests that bridge. Public JVM, JS and WasmJs expose an invariant
unsupported placeholder, not an FFmpeg-backed runtime. No target artifact is publicly available yet.

## Target matrix

There is one target table for the project and it lives in the [README](https://github.com/yuroyami/KiteCodec#targets). It records, per target, whether a public artifact exists, exactly what build/test evidence exists, and where FFmpeg comes from. This page covers the part it does not: how to obtain an FFmpeg for each target, and what is inside the one KiteCodec builds.

Two points decide whether KiteCodec is usable for you:

- Kotlin/Native implementations live in `nativeMain`; the Android implementation and the JVM one
  compile the JNI sources from `jvmAndAndroidMain`. **Both are published and both are real.** The
  Android AAR on Maven Central declares `minSdkVersion 26` in its own manifest and carries
  `libkitecodec_jni.so` for `arm64-v8a` and `x86_64` with 16 KiB alignment and packaging checks. The
  JVM jar carries `libkitecodec_jni.dylib` for **macOS arm64 and no other host**, so a JVM consumer
  on Linux or Windows still falls back to `unsupportedMain` and gets readable diagnostics rather
  than a codec. No Android playback is qualified on a physical device. `js` and `wasmJs` compile and
  publish the common API but report no capabilities and reject every media operation with typed
  `FFmpegError.Unsupported`.
- **KiteCodec is published**: `io.github.yuroyami:kitecodec-core:0.1.3` on Maven Central, one
  dependency line, FFmpeg embedded inside the artifacts. There is no Gradle plugin and no FFmpeg
  download step. `mingwX64` builds and tests in CI and has prebuilt FFmpeg assets; `iosX64`,
  `macosX64` and `linuxArm64` remain unqualified. This paragraph said "Nothing is published" until
  2026-08-24, which was three published versions out of date.

## FFmpeg is a prerequisite

KiteCodec EMBEDS FFmpeg's libav\* libraries plus dav1d inside each native target's klib (KC-EMBED, 2026-08-22), so a consumer provisions nothing. Inside this repository the vendored trees under `native-libs/` are what gets embedded; a host without them falls back to a system FFmpeg for its own desktop target only. See the README's [release status](https://github.com/yuroyami/KiteCodec#release-status).

### Mode 1: dynamic against system FFmpeg

This is the default, and what the macOS arm64 build does today. The Gradle build's `FFmpegPaths`
finds your system FFmpeg, compiles the C archive against its headers and links the shared libraries
dynamically. The cinterop def parses only KiteCodec's opaque helper, handle and ABI headers. The
module build still supplies the FFmpeg include path redundantly to cinterop, where that reduced
header set does not use it. Your users need FFmpeg installed at runtime.

=== "macOS"

    ```bash
    brew install ffmpeg
    ```

    Override the discovered prefix with `kitecodec.macos.homebrew.prefix` in `gradle.properties` if Homebrew lives somewhere non-standard.

=== "Linux"

    ```bash
    sudo apt install ffmpeg libavformat-dev libavcodec-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev
    ```

`FFmpegPaths` discovers the apt-installed headers and libraries for the C archive and final link.

### Mode 2: vendored static (release)

For a self-contained binary, build a minimal FFmpeg from source. The build expects the FFmpeg source tree at `vendor/ffmpeg`, so clone it first:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg

./gradlew :kitecodec-core:buildFFmpegForMacosArm64
# or build every configured target at once:
./gradlew :kitecodec-core:buildFFmpegForAll
```

The Gradle task cross-compiles a pinned codec and filter set and drops `.a` libraries under `native-libs/<license>/<target>/` (`lgpl` or `gpl`). `FFmpegPaths` notices, compiles the C archive against that tree and switches the final link to the static libraries. Desktop size is around 25 MB; no mobile size is claimed before it is measured.

Configure and make never see the checkout path or final output path. The task copies source to a unique hash-free directory under `java.io.tmpdir`, excluding `.git` and every `build` subtree, installs the normalized configure invocation at `lib/kitecodec/ffmpeg-configure.txt`, verifies that record plus the archives and headers there, copies with Java/NIO to a verified sibling staging tree, then replaces the final tree. Packaging reads only that exact single-line evidence. A failure leaves the previous output intact and prints the retained scratch path.

The static profile is **LGPL by default**: no `--enable-gpl`, no libx264 / libx265. That is the App-Store- and closed-source-safe flavor.

The READ side of the profile is wide by class: every decoder, demuxer, parser, bitstream filter
and hwaccel FFmpeg `n8.0` can build without extra libraries is compiled, so what FFmpeg can play,
a vendored build can play. Note the boundary of that sentence: components FFmpeg gates behind an
external library (software AV1 via libdav1d/libaom on mobile profiles, for example) exist only in
the flavors that link those libraries. The WRITE side and the protocol list remain deliberately
small. If an encoder, muxer, filter or protocol is not listed here, it is not in the generated
profile. This table describes compiled profile contents, not per-target runtime qualification.
The authoritative list is `sharedCoreArgs()` in [`BuildFFmpegTask.kt`](https://github.com/yuroyami/KiteCodec/blob/main/buildSrc/src/main/kotlin/BuildFFmpegTask.kt); as of `n8.0`:

Every profile is PORTABLE since 2026-08-22: no third-party desktop stack anywhere. The optional dav1d flavour adds the `libdav1d` AV1 software decoder to any column.

| | macOS LGPL | Mobile Apple LGPL | Linux / Windows LGPL | Android LGPL |
|---|---|---|---|---|
| **Video encode** | `mpeg4`, `mjpeg`, `png`, `h264_videotoolbox`, `hevc_videotoolbox` | `mpeg4`, `mjpeg`, `png` | `mpeg4`, `mjpeg`, `png` | `mpeg4`, `mjpeg`, `png`, `h264_mediacodec`, `hevc_mediacodec` |
| **Audio encode** | `aac`, `flac`, `pcm_s16le`/`s24le`/`f32le` | `flac`, `pcm_*` | `flac`, `pcm_*` | `aac`, `flac`, `pcm_*` |
| **Decode** | every native FFmpeg decoder; VideoToolbox hwaccel behind h264/hevc | every native FFmpeg decoder; VideoToolbox hwaccel behind h264/hevc | every native FFmpeg decoder | every native FFmpeg decoder + MediaCodec h264/hevc |
| **Demux** | every native FFmpeg demuxer | same | same | same |
| **Mux (write)** | mp4/mov, matroska/webm (including `.mka`), mpegts, mp3, wav, flac, ogg/opus, image2 | same | same | same |
| **Protocols** | `file`, `fd`, `pipe`, `data`, `http`, `tcp` | same | same | same |
| **Filters** | the shared set: scale, pad, overlay, hue, unsharp, vignette, colorbalance, colorlevels, curves, lut, colorchannelmixer, split, trim/setpts, and the audio set | same | same | same |
| **Bitstream filters** | all of them (they ride with the wide demuxer class) | same | same | same |

There is no GPL column and no `drawtext`/`eq`/`boxblur` anywhere: this project bakes the LGPL portable profile only. Use `hue` (it has a brightness parameter `b`), `colorlevels` or `curves` where you reached for `eq`. The bitstream filters are never named by KiteCodec. libavformat inserts them during a stream copy, which is a copy of encoded packets with no decode or encode. Without them, a copy between container families produces a *corrupt file* rather than an error.

`mpeg4` is the dependency-free video baseline: it is always present, in every flavor, so code that must encode *something* without pulling in a GPL or hardware encoder has a target. `https` is **not** built. It needs a TLS backend cross-compiled for every target, and this profile does not include one. Use `http`, a local file, or link a system FFmpeg that has TLS.

The `fd` protocol is what makes an Android `content://` file playable. Open a descriptor with `ContentResolver.openFileDescriptor`, then open `"fd:"` with the pre-open option `fd` set to the descriptor number:

```kotlin
val pfd = contentResolver.openFileDescriptor(uri, "r")!!
val source = MediaSource.open("fd:", mapOf("fd" to pfd.fd.toString()))
```

FFmpeg `dup()`s the descriptor, so the caller keeps ownership, and its `fstat` marks a regular file seekable. Do **not** reach for `/proc/self/fd/N` through the `file` protocol instead: that re-opens by path, the kernel rechecks permissions against the path, and a descriptor a process may legitimately read can still fail with `Permission denied`. `pipe:<fd>` also dups but hardcodes the stream as non-seekable, which costs you seeking.

Probe rather than assume. `FFmpeg.hasEncoder("libx264")` is cheap. It turns a runtime failure on a user's machine into a clear message.

Want libx264 / libx265? That is the **GPL flavor, and it is opt-in**. It is currently the only route to libx264 in a vendored build:

```bash
# builds --enable-gpl --enable-version3 + libx264/libx265 into native-libs/gpl/<target>/
./gradlew :kitecodec-core:buildFFmpegForMacosArm64Gpl     # or :buildFFmpegForAllGpl

# then select the GPL tree when building KiteCodec:
./gradlew build -Pkitecodec.ffmpeg.license=gpl
```

See [Licensing](#licensing) below before you ship a GPL-flavor binary.

## Mobile Apple local substrate

On an arm64 Mac, the local phone selector registers exactly `macosArm64`, `iosArm64` and `iosSimulatorArm64`:

```bash
./gradlew :kitecodec-core:buildFFmpegForMacosArm64 \
  :kitecodec-core:buildFFmpegForIosArm64 \
  :kitecodec-core:buildFFmpegForIosSimulatorArm64

./gradlew :kitecodec-core:compileKotlinMacosArm64 \
  :kitecodec-core:compileKotlinIosArm64 \
  :kitecodec-core:compileKotlinIosSimulatorArm64 \
  -Pkitecodec.applePhoneTargetsOnly=true
```

The mobile Apple profile is the current STANDARD software-playback set from `sharedCoreArgs()`, `--disable-autodetect`, SDK zlib and SDK cross flags. It has no desktop third-party archives, GPL flags, hardware encode or VideoToolbox. Final iOS static link flags are exactly `-lz`. `buildFFmpegForIos*Gpl` tasks do not exist, and repository build/path resolution refuses GPL for every iOS target before tree lookup with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`

`-Pkitecodec.applePhoneTargetsOnly=true` is mutually exclusive with the stable and host-only selectors. It is accepted by `publishToMavenLocal` for a private consumer proof and explicitly rejected by every remote publish. Generated `native-libs` trees and Maven-local files are never release evidence.

## Windows (mingwX64)

Windows has **no system-FFmpeg discovery**: `FFmpegPaths` resolves macOS (Homebrew) and Linux (apt) installs, but for `mingwX64` it requires a populated `native-libs/<license>/mingw-x64/` tree. You provide it in one of two ways.

**Option A: drop in a BtbN build (what CI does).** The [BtbN FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds/releases) shared builds carry `include/` and `lib/` (import libraries) in exactly the layout `FFmpegPaths` expects. Download one, unzip, and move it into place:

```powershell
# The "gpl" BtbN variant contains libx264, so it lands under the gpl flavor.
# CI pins an exact autobuild tag, asset name and SHA-256 rather than using `latest`,
# which is a moving target. Do the same for anything reproducible.
Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl-shared.zip" -OutFile ffmpeg.zip
Expand-Archive ffmpeg.zip -DestinationPath ffmpeg-tmp
Move-Item ffmpeg-tmp\ffmpeg-master-latest-win64-gpl-shared native-libs\gpl\mingw-x64
```

Then build with `-Pkitecodec.ffmpeg.license=gpl` (matching the flavor directory), and make sure the `bin\` directory with the DLLs is on `PATH` at run time. An LGPL BtbN variant exists too (`...-win64-lgpl-shared.zip`); put it under `native-libs\lgpl\mingw-x64` and skip the property. This is exactly how [CI](https://github.com/yuroyami/KiteCodec/blob/main/.github/workflows/ci.yml) runs the Windows tests and e2e transcode on every push.

**Option B: vendored static cross-compile.** Run `:kitecodec-core:buildFFmpegForMingwX64` (or the `Gpl` variant) with a mingw-w64 cross toolchain (`x86_64-w64-mingw32-gcc`) available. This is realistic from a Linux host or MSYS2; it needs the `vendor/ffmpeg` clone described above.

Windows builds, tests, and e2e-transcodes in CI via Option A, against a BtbN tag, asset name and SHA-256 pinned in the workflow. There is no one-command onboarding path on a bare Windows machine, no system-FFmpeg discovery, and no prebuilt KiteCodec asset for `mingw-x64`. You stage the tree yourself.

## Android FFmpeg profiles

Kotlin/Native treats the Android NDK as just another native family, so the entire decode → filter → encode → mux pipeline (and `Remuxer`) compiles untouched for `androidNativeArm64`, `androidNativeArm32`, and `androidNativeX64`.

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./gradlew :kitecodec-core:buildFFmpegForAndroidArm64       # NDK cross-compile, ~6 min
./gradlew :kitecodec-core:compileKotlinAndroidNativeArm64  # the klib
```

The same Android FFmpeg profile also supplies the JNI libraries for the regular Android target.
It is deliberately different from the desktop one:

- **LGPL only.** No `--enable-gpl`, no libx264 / libx265. Play-Store-safe and closed-source-safe.
- **FFmpeg's MediaCodec wrappers** (`h264_mediacodec`, `hevc_mediacodec`) replace the GPL software encoders in the generated profile. KiteCodec reaches them only by an FFmpeg codec name; it does not call the Android codec API directly.
- **Full software decode set** (h264 / hevc / vp8 / vp9 / av1, plus audio) identical to desktop.

!!! warning "Named Android codecs and the JavaVM"
    The regular Android loader checks the complete FFmpeg identity before attaching the app's
    `JavaVM`. The low-level API can then request an exact FFmpeg decoder name, for example
    `source.openDecoder(stream, decoder = CodecId("h264_mediacodec"))`, and verifies that decoder
    against the stream before open. This is not a direct platform-codec call. The present evidence
    is source, host tests, two JNI link arms and packaging checks; it does not qualify device
    playback or a hardware encoder.

!!! note "Two Android target models"
    The `compileKotlinAndroidNative*` flow above produces Kotlin/Native `.klib` files. Separately,
    `-Pkitecodec.phoneTargetsOnly=true` narrows a LOCAL build to the Android KMP target and the
    three Apple targets. It is a build-scope selector, refused by remote publication; it is not what
    decides whether an artifact exists. The published AAR carries exactly `arm64-v8a` and `x86_64`
    JNI libraries at `minSdk 26`. Both arms are link- and package-checked with 16 KiB constraints;
    x86_64 has no runtime qualification. `js` and `wasmJs` remain typed placeholders in every
    scope.

## Licensing

The Apache 2.0 license covers KiteCodec's own Kotlin code. The FFmpeg you link against carries its own license, and that is what determines whether your binary is App-Store-safe. The choice is made at the FFmpeg build level, as two flavors:

| Flavor | FFmpeg license | Encoders | Use for |
|---|---|---|---|
| **LGPL** (default) | LGPL-2.1+ (no `--enable-gpl`) | Desktop VideoToolbox, Android MediaCodec and desktop svtav1 / opus / mp3lame. The mobile Apple local profile is software playback and does not add VideoToolbox. | Commercial / closed-source / App Store distribution (mind the [LGPL obligations](licensing.md)) |
| **GPL** (opt-in) | GPL, effectively **GPL-3.0**, since the build also sets `--enable-version3` | Adds libx264 / libx265 for quality-focused software encode | GPL-compatible projects only (open-source apps, server tools, internal use) |

The LGPL flavor is what `buildFFmpegFor<Target>` produces and what the build links by default. The GPL flavor is a loud opt-in: build with `buildFFmpegFor<Target>Gpl` and select it with `-Pkitecodec.ffmpeg.license=gpl`. This GPL opt-in is currently the **only** route to libx264 / libx265 in a vendored build.

!!! warning "GPL is not App-Store-safe"
    The GPL flavor adds libx264 / libx265 by enabling `--enable-gpl` (and `--enable-version3`, making the effective license GPL-3.0). A binary that links those must not ship through the iOS App Store or any other closed-source / commercial channel. Stay on LGPL; the local mobile Apple profile is software playback only and does not pretend to offer a hardware encoder.

!!! note "`kitecodec-gpl` is planned, not published"
    A separate `kitecodec-gpl` artifact that packages the GPL flavor as a drop-in dependency is planned but does not exist yet. It is a README-only skeleton, commented out of `settings.gradle.kts`. Today the GPL flavor is reached only through the build tasks and the `kitecodec.ffmpeg.license` property described above.

For distribution obligations (shipping license texts, offering FFmpeg source, the LGPL relinking requirement for static builds), see the [Licensing guide](licensing.md).

### Picking an encoder per platform

`CodecId` exposes the relevant FFmpeg names as companions. Software libx264 is GPL; the standing
hardware-encoder runtime evidence here is VideoToolbox on the qualified macOS desktop profile.
The Android profile contains MediaCodec wrappers, but this stage does not qualify device encoding.

=== "Qualified macOS hardware"

    ```kotlin
    // macOS desktop profile: VideoToolbox
    VideoEncoderSpec(
        codec = CodecId.H264VideoToolbox,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
    )
    ```

=== "GPL software (libx264)"

    ```kotlin
    VideoEncoderSpec(
        codec = CodecId.Libx264,
        width = 1280, height = 720,
        frameRate = Rational.Fps30,
        options = mapOf("preset" to "medium", "crf" to "20"),
    )
    ```

Encoder availability is resolved at runtime. Probe before you commit to a codec:

```kotlin
val codec = listOf(
    CodecId.H264VideoToolbox,   // macOS desktop profile, LGPL-safe
    CodecId.Libx264,            // GPL builds only
    CodecId("mpeg4"),           // always present, every profile
).first { FFmpeg.hasEncoder(it.name) }
```

## Related

- [Getting started](getting-started.md): build the sample and run your first transcode.
- [Encoding and muxing](encoding-muxing.md): `MediaSink`, encoder specs, and codec options.
- [API reference](https://yuroyami.github.io/KiteCodec/api/): the full public surface.
