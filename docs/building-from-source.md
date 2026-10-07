# Building from source

This page is for working inside the KiteFFmpeg repository. An app that uses KiteFFmpeg needs only
the dependency line in [Getting started](getting-started.md), because every published artifact
already contains FFmpeg.

## Where the build finds FFmpeg

The build compiles KiteFFmpeg's C layer against an FFmpeg tree, and `FFmpegPaths` looks in this
order:

1. The vendored static tree under `native-libs/<license>/<target>/`. This is what the published
   artifacts embed.
2. A system FFmpeg, for the host's own desktop target only: Homebrew on macOS (override the prefix
   with `kiteffmpeg.macos.homebrew.prefix` in `gradle.properties`). It is a convenience for a quick
   local build, and a binary linked this way needs that FFmpeg at run time.

```bash
# The system fallback, for a quick host build on macOS
brew install ffmpeg
```

On Linux the lookup also finds the apt packages, but a Kotlin/Native link against them fails:
Ubuntu's libav\* libraries need glibc 2.29 or later, and Kotlin/Native links against its own
glibc 2.19 sysroot. Use the prebuilt static tree from this repository's release instead, which is
what CI does:

```bash
tag=ffmpeg-n9.0.2-r3
asset=ffmpeg-n9.0.2-lgpl-linux-x64.zip   # or ffmpeg-n9.0.2-lgpl-linux-arm64.zip
curl -fLO "https://github.com/yuroyami/KiteFFmpeg/releases/download/$tag/$asset"
curl -fLO "https://github.com/yuroyami/KiteFFmpeg/releases/download/$tag/$asset.sha256"
shasum -a 256 -c "$asset.sha256"
mkdir -p native-libs/lgpl/linux-x64       # or linux-arm64
unzip -q "$asset" -d native-libs/lgpl/linux-x64
```

## Building the vendored tree

The published artifacts embed a minimal static FFmpeg built from source by a Gradle task. The task drops `.a` libraries under `native-libs/<license>/<target>/`; `FFmpegPaths` compiles the C archive against that tree and switches the final link to the static libraries automatically.

The task expects git checkouts of FFmpeg at `vendor/ffmpeg` and of dav1d, which every FFmpeg build compiles first, at `vendor/dav1d`. Cloning them is a **mandatory first step**:

```bash
git clone --depth 1 --branch n9.0.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
git clone --depth 1 --branch 1.5.4 https://code.videolan.org/videolan/dav1d.git vendor/dav1d

./gradlew :kiteffmpeg:buildFFmpegForMacosArm64
# or build every target you have toolchains for:
./gradlew :kiteffmpeg:buildFFmpegForAll
```

Each checkout must hold exactly the commit its tag names, with nothing changed, added or ignored in it, or the build stops before it compiles anything; [Troubleshooting](troubleshooting.md#vendored-build-prerequisites) says why and what to do. A change to FFmpeg belongs in a patch under `native/patches/ffmpeg`, and one to libass under `native/patches/libass`.

Configure, make and install run in a unique hash-free directory under `java.io.tmpdir`. The task installs the normalised configure invocation as the single-line `lib/kiteffmpeg/ffmpeg-configure.txt` provenance record, requires it during verification, copies the verified install to a sibling staging directory and only then replaces `native-libs`. A failed build preserves the last good tree even when the checkout path contains `#`; packaging reads only that installed record.

Every profile is portable (2026-08-22): no third-party libraries are needed on any target. The prerequisites are `make`, a C toolchain and, for the x86_64 targets' assembly, `nasm`. dav1d's build additionally needs `meson` and `ninja`. On macOS: `brew install nasm meson ninja`. See [Troubleshooting](troubleshooting.md#vendored-build-prerequisites) if configure fails.

Every bake is **LGPL** (no libx264 / libx265). There are no GPL build tasks: a GPL tree is something you build and own yourself, and point this repository's build at.

!!! tip "Android"

    Android uses a separate LGPL-only FFmpeg profile with FFmpeg's MediaCodec wrappers. The
    Kotlin/Native flow cross-compiles that profile before building a klib. The regular Android
    source model uses the same profile through JNI, packages only `arm64-v8a`, `armeabi-v7a` and
    `x86_64`, and
    reaches a platform codec only through an FFmpeg name such as `h264_mediacodec`. A local
    build of that model is not a public install; the published AAR is. `arm64-v8a` plays real
    media on phones as the engine under KitePlayer, and `armeabi-v7a` and `x86_64` have not run.
    See [Platform support](platforms.md) for both target models.

!!! tip "Mobile Apple local trees"

    On an arm64 Mac, build the host, device and simulator trees in one producer invocation:

    ```bash
    ./gradlew :kiteffmpeg:buildFFmpegForMacosArm64 \
      :kiteffmpeg:buildFFmpegForIosArm64 \
      :kiteffmpeg:buildFFmpegForIosSimulatorArm64
    ```

    Generated trees remain untracked. iOS has no GPL task; `buildFFmpegForIos*Gpl` is deliberately not registered. Repository build/path resolution refuses GPL for every iOS target before tree lookup with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`

## Using a local build from another project

The `:kiteffmpeg-sample` module already depends on `:kiteffmpeg` and is the fastest way to run the
API against real arguments. From your own project, clone KiteFFmpeg beside it and compose the builds:

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

    A composite build substitutes the dependency with the included project, so the version is
    omitted deliberately.

To test a consumer against locally published artifacts instead, run `./gradlew publishToMavenLocal`.
On an arm64 Mac, `-Pkiteffmpeg.applePhoneTargetsOnly=true` narrows that to macosArm64, iosArm64 and
iosSimulatorArm64. Both selectors are local only, and any remote `publish` task refuses them during
configuration.

!!! note "`kiteffmpeg-gpl` does not exist"

    `kiteffmpeg` is LGPL and is safe for commercial distribution. A `kiteffmpeg-gpl` add-on packaging libx264 / libx265 has a README in the repository and nothing else: no build script, and commented out of `settings.gradle.kts`. There are no `Gpl` build tasks either, so a GPL flavour is a tree you build and own, selected with `-Pkiteffmpeg.ffmpeg.license=gpl`.

