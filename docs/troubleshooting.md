# Troubleshooting

Most build-time problems have one cause: KiteCodec links against an FFmpeg **you** provide, and the build could not find or produce it.

## "No FFmpeg install found for \<target\>"

`FFmpegPaths.resolve` looks for a vendored static tree under `native-libs/<license>/<target>/{include,lib}` first, then falls back to a system install. This error means neither existed. Fix one of the two:

- install FFmpeg system-wide (`brew install ffmpeg` on macOS; the `libav*-dev` packages via apt on Linux), or
- vendor a static build: `./gradlew :kitecodec-core:buildFFmpegFor<Target>` (see [prerequisites](#vendored-build-prerequisites) below).

Note the `<license>` path segment: if you put your own GPL tree under `native-libs/gpl/<target>/` but did not pass `-Pkitecodec.ffmpeg.license=gpl`, the build looks under `native-libs/lgpl/` and misses your libraries. Flavour and property must match. (KiteCodec itself builds only the LGPL flavour; the `buildFFmpegFor<Target>Gpl` tasks were deleted on 2026-08-21.)

## "Local FFmpeg tree is incomplete"

The consumer plugin's `FFmpegSource.Local` accepts one layout only:
`<localRoot>/<license.id>/<target-triple>/{include,lib}`. Every wired target must contain
`include/libavformat/avformat.h` and `libavcodec.a`, `libavformat.a`, `libavutil.a`,
`libavfilter.a`, `libswscale.a` and `libswresample.a`. The configuration error lists every missing
file. Point `localRoot` at the directory above `lgpl/` or finish the producer build first. Local
never downloads a missing file. Local with GPL on any iOS target is refused before tree validation
with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`

## macOS: Homebrew in a non-standard prefix

On macOS the build probes `/opt/homebrew` (Apple Silicon) and `/usr/local` (Intel) for `include/libavformat/avformat.h`. If your Homebrew is elsewhere, or you want to point at a custom FFmpeg prefix, set the override in `gradle.properties`:

```properties
kitecodec.macos.homebrew.prefix=/custom/prefix
```

The prefix must contain `include/libavformat/avformat.h` and the FFmpeg dylibs under `lib/`.

## Windows: nothing is auto-discovered

There is **no system-FFmpeg discovery on Windows**. `FFmpegPaths` resolves Homebrew (macOS) and apt (Linux) installs only. For `mingwX64` it requires a populated `native-libs/<license>/mingw-x64/` tree. Installing an `ffmpeg.exe` from anywhere will not help. The build needs headers and import libraries.

Stage them yourself, either by dropping in a [BtbN build](https://github.com/BtbN/FFmpeg-Builds/releases) (shared zips carry `include/` + `lib/` in the exact expected layout, and this is what CI does) or by cross-compiling the vendored build with a mingw-w64 toolchain. The steps are in [Platform support, Windows](platforms.md#windows-mingwx64). Remember two things. BtbN "gpl" zips go under `native-libs/gpl/mingw-x64` and need `-Pkitecodec.ffmpeg.license=gpl`. At run time the DLL `bin\` directory must be on `PATH`.

## VideoToolbox fails on VMs / CI runners

`h264_videotoolbox` refuses to open when no hardware encode block is available. That is the usual case on virtualized macOS (CI runners, VMs). The encoder returns an error at `addVideoEncoder` / during `transcode` even though `FFmpeg.hasEncoder("h264_videotoolbox")` is true, because availability of the *encoder* and availability of the *hardware* are different questions.

Pass `allow_sw` to let VideoToolbox fall back to its software path instead of failing:

```kotlin
VideoEncoderSpec(
    codec = CodecId.H264VideoToolbox,
    width = 1280, height = 720,
    frameRate = Rational(30, 1),
    options = mapOf("allow_sw" to "1"),
)
```

## Vendored build prerequisites

The `buildFFmpegFor<Target>` tasks compile FFmpeg from source. They fail early when something they need is missing.

**1. The FFmpeg source tree.** The task expects it at `vendor/ffmpeg` and stops with this exact instruction otherwise:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
```

**2. Build tools.** `make`, a C toolchain (clang/gcc) and `nasm` (x86 assembly: configure fails without it on x86 targets). The dav1d flavour additionally needs `meson` and `ninja`:

```bash
brew install nasm meson ninja
```

**3. That is the whole list.** Every profile is portable (2026-08-22): no third-party encoder or
text library is linked on any target, so configure never asks the host package manager for one.
Apple targets use SDK zlib plus VideoToolbox/AudioToolbox from the SDK; Linux and Windows use
konan's own sysroot; Android uses the NDK. There are no GPL tasks.

**Path safety.** A checkout or final output path may contain `#`. Configure, make and install run
only in a unique hash-free workspace under `java.io.tmpdir`; source copying excludes `.git` and
every `build` subtree. After install, the normalized configure invocation is written as exactly one
line at `lib/kitecodec/ffmpeg-configure.txt`; verification and packaging require that record, and
packaging does not consult a vendor build log. On success, the verified install is copied to a
sibling staging directory and replaces the output. On failure, the old output remains and the
retained scratch path is printed for diagnosis.

**4. Idempotence.** The task skips when `native-libs/<license>/<target>/lib/libavformat.a` already exists. To force a rebuild, delete that directory.

## Android: "Android NDK not found"

The NDK cross-compile resolves its toolchain from, in order: the `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, or `ANDROID_NDK_LATEST_HOME` environment variables, then the newest `ndk/<version>` under the default SDK locations (`~/Library/Android/sdk/ndk` on macOS, `~/Android/Sdk/ndk` on Linux). If none resolve:

```bash
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./gradlew :kitecodec-core:buildFFmpegForAndroidArm64
```

The vendored `vendor/ffmpeg` clone is required here too. The repository's Android FFmpeg builds
always use the LGPL MediaCodec profile. There is no GPL Android profile, and requesting one fails
with an explanatory error.

## Android: regular AAR/JNI versus `androidNative*`

These are separate target models. `buildFFmpegForAndroid*` plus
`compileKotlinAndroidNative*` produces Kotlin/Native klibs. The regular Android KMP source model
uses a dynamically registered JNI bridge, is `minSdk 26`, and packages only `arm64-v8a` and
`x86_64` inputs with 16 KiB ELF/app-packaging checks. Its local proof scope is
`-Pkitecodec.phoneTargetsOnly=true` and needs both `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME` plus
the complete local FFmpeg trees.

There is no public Android AAR to troubleshoot in a consumer build yet. The macOS JNI dylib is a
JVM test fixture, not a desktop distribution, and x86_64 Android has link/package evidence only.
FFmpeg's MediaCodec wrapper is selected only by an FFmpeg codec name after
the Android loader accepts the linked FFmpeg identity and attaches the VM; KiteCodec does not call
the platform codec API directly.

## "libx264 not found" / `CodecId.Libx264` encoder missing at runtime

libx264 only exists in GPL-flavour FFmpeg builds. A system FFmpeg from Homebrew or apt usually has
it; **no KiteCodec artifact does, and no KiteCodec task builds one.** The GPL build tasks were
deleted on 2026-08-21.

Two ways forward:

- **Use an encoder that is actually there.** `CodecId.H264VideoToolbox` or
  `CodecId.HevcVideoToolbox` on macOS, `h264_mediacodec` on Android, or the universal `mpeg4`
  baseline, which every profile carries. The Android MediaCodec names are present in the profile,
  but the current evidence does not qualify device encoding.
- **Link an FFmpeg tree you built.** Put it under `native-libs/gpl/<target>/` and select it with
  `-Pkitecodec.ffmpeg.license=gpl`. Read the [licence consequences](licensing.md) first: it makes
  your whole application GPL.

Either way, probe at runtime with `FFmpeg.hasEncoder("libx264")` before committing to a codec.

## Still stuck?

Check the capability probe first. It tells you which FFmpeg you actually linked:

```kotlin
println(FFmpeg.versions)
println(FFmpeg.buildConfiguration)   // the exact ./configure line of the linked FFmpeg
```

Then [open an issue](https://github.com/yuroyami/KiteCodec/issues) with the probe output, your platform, and how you sourced FFmpeg.
