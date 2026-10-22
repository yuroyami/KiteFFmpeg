# Building from source

This page is for working inside the KiteFFmpeg repository. An app that uses KiteFFmpeg needs only
the dependency line in [Getting started](getting-started.md), because every published artifact
already contains FFmpeg.

The repository builds with the Kotlin Toolchain. The `./kotlin` script in the root (`kotlin.bat` on
Windows) downloads the toolchain, a JDK and Kotlin/Native on first use, so you need neither a JDK
nor Gradle. Android platforms also need the Android SDK in `ANDROID_HOME`.

```bash
./kotlin build -m kiteffmpeg -p macosArm64   # one platform of the library
./kotlin test -m kiteffmpeg -p jvm           # the tests of one platform
./kotlin show commands                       # every command that `./kotlin do` runs
```

## Where the build finds FFmpeg

The build compiles KiteFFmpeg's C layer against an FFmpeg tree. `FFmpegPaths` looks in this order:

1. The static tree under `native-libs/<license>/<target>/`. This is what the published artifacts
   embed.
2. A system FFmpeg, for the host's own desktop target only. On macOS that is Homebrew. Its prefix is
   the `homebrewPrefix` setting of the `kiteffmpeg-native` plugin in `kiteffmpeg/module.yaml`
   (`/opt/homebrew` by default). It is fine for a quick local build, and a binary linked this way
   needs that FFmpeg at run time.

```bash
# The system FFmpeg, for a quick host build on macOS
brew install ffmpeg
```

You get a static tree in one of two ways. Download the prebuilt tree from this repository's
release, which is what CI does, or build it from source with the commands below.

A platform without a tree does not build. Pass `-p` to build only the platforms that have one.

### Download a prebuilt tree

Each FFmpeg release of this repository holds a zip for each target, with a `.sha256` beside it:

```bash
tag=ffmpeg-n9.0.2-r3
asset=ffmpeg-n9.0.2-lgpl-linux-x64.zip   # or ffmpeg-n9.0.2-lgpl-linux-arm64.zip
curl -fLO "https://github.com/yuroyami/KiteFFmpeg/releases/download/$tag/$asset"
curl -fLO "https://github.com/yuroyami/KiteFFmpeg/releases/download/$tag/$asset.sha256"
shasum -a 256 -c "$asset.sha256"
mkdir -p native-libs/lgpl/linux-x64       # or linux-arm64
unzip -q "$asset" -d native-libs/lgpl/linux-x64
```

On Linux, use this tree rather than the apt packages. The lookup finds the apt packages, but a
Kotlin/Native link against them fails: Ubuntu's libav\* libraries need glibc 2.29 or later, and
Kotlin/Native links against its own glibc 2.19 sysroot.

## Build the FFmpeg trees

The commands expect git checkouts of FFmpeg at `vendor/ffmpeg` and of dav1d at `vendor/dav1d`. Every
FFmpeg build compiles dav1d first, so clone both before anything else:

```bash
git clone --depth 1 --branch n9.0.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
git clone --depth 1 --branch 1.5.4 https://code.videolan.org/videolan/dav1d.git vendor/dav1d

./kotlin do buildFFmpegForMacosArm64
# or all eleven native targets:
./kotlin do buildFFmpegForAll
```

Each checkout must hold exactly the commit that its tag names, with nothing changed, added or
ignored in it. Otherwise the build stops before it compiles anything, and
[Troubleshooting](troubleshooting.md#vendored-build-prerequisites) says what to do.

You need `make`, a C toolchain and `nasm` (for the x86_64 assembly), and dav1d's build needs `meson`
and `ninja`. On macOS, run `brew install nasm meson ninja`. No other third-party library is needed
on any target.

Configure, make and install run in a temporary directory. The command records the configure line in
`lib/kiteffmpeg/ffmpeg-configure.txt`, checks the install, and only then replaces the tree under
`native-libs`. So a failed build keeps the last good tree.

A command skips its build when the tree already matches the recipe. A tree that was built before the
build kept a record of its inputs is reused as it is. Delete the tree to build it again.

To build a missing LGPL tree during a normal build, set `KITEFFMPEG_AUTO_BAKE=true`.

Every tree is LGPL, with no `libx264` or `libx265`. There are no GPL build commands.

### Patches

A change to FFmpeg belongs in a patch under `native/patches/ffmpeg`, and a change to libass in one
under `native/patches/libass`. The build applies every patch to the clean checkout. FFmpeg n9.0.2
gets these:

| Patch | What it does |
| --- | --- |
| `0001` | Makes `h264_mp4toannexb` always write 4-byte start codes |
| `0002` | Lets the caller's own open callback decide every HLS address, which a nested opener needs |
| `0003` | Lets a playlist of WebM or Matroska segments seek after its end |
| `0004` | Keeps the subtitle cue that is showing when an HLS subtitle rendition starts |
| `0005` | Writes a BCP 47 language into MP4, MOV, MPEG-TS and Matroska as its ISO 639 code |
| `0006` | Reads a Matroska track's `LanguageBCP47` |
| `0007` | Puts the headers of MPEG-4 Part 2 video in the stream of an MPEG-TS copy |
| `0008` | Reads and writes the extended language tag of an MP4 or MOV track |
| `0009` | Reads Google's and Apple's 360 degree and stereo boxes in one MP4 or MOV as one description |
| `0010` | Reads the initial view that Google's spherical box states |
| `0011` | Lets an HLS playlist define variables and use them in its addresses |
| `0012` | Keeps a paused RTSP session alive |
| `0013` | Takes the chapters of a Matroska file's default edition |
| `0014` | Exports the Matroska info and chapters payloads |
| `0015` | Takes the parameters of an audio stream that the probe never saw from its first packet |

## Apple targets

On an arm64 Mac, build the host, device and simulator trees, then compile those three platforms:

```bash
./kotlin do buildFFmpegForMacosArm64
./kotlin do buildFFmpegForIosArm64
./kotlin do buildFFmpegForIosSimulatorArm64
./kotlin build -m kiteffmpeg -p macosArm64 -p iosArm64 -p iosSimulatorArm64
```

The iPhone tree carries the VideoToolbox H.264 and HEVC encoders, and the simulator trees carry no
hardware encoder. Every Apple tree carries the VideoToolbox decoders, which is why the iOS link
flags in `ffmpeg.def` name VideoToolbox and its frameworks. The iOS targets refuse a GPL tree with
`iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`

## Android

The Android tree serves two kinds of target: the `androidNative*` klibs, and the JNI libraries of
the Android library. Build it with the NDK:

```bash
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
./kotlin do buildFFmpegForAndroidArm64                 # NDK cross-compile, about 6 minutes
./kotlin build -m kiteffmpeg -p androidNativeArm64     # the klib
```

The Android tree is LGPL, like the others. It adds FFmpeg's MediaCodec encoders and decoders, which
KiteFFmpeg reaches only by their FFmpeg names (such as `h264_mediacodec`), never through the
Android codec API.

The JNI libraries of the Android library live in a separate module, `kiteffmpeg-android-natives`. It
links `libkitecodec_jni.so` for each ABI whose tree exists and puts it at `lib/<abi>/` in its jar.
The Android variant of `kiteffmpeg` depends on that jar, and the Android Gradle plugin packages the
libraries into the app. `./kotlin check androidJni` fails while an ABI has no library:

```bash
./kotlin build -m kiteffmpeg -p android
./kotlin check androidJni
```

## Windows

Windows has no system FFmpeg lookup, so `mingwX64` needs a tree in `native-libs/lgpl/mingw-x64/`.
Get one in either of two ways.

**Download the release tree**, as CI does. It is built from the same configure line as the
published klibs:

```powershell
# Pin the tag and the asset. Check the .sha256 beside it before use.
$tag  = "ffmpeg-n9.0.2-r3"
$name = "ffmpeg-n9.0.2-lgpl-mingw-x64.zip"
Invoke-WebRequest -Uri "https://github.com/yuroyami/KiteFFmpeg/releases/download/$tag/$name" -OutFile $name
Expand-Archive $name -DestinationPath native-libs\lgpl\mingw-x64
```

The tree is static and LGPL, so the build needs no license setting, and the test binaries need no
DLL on `PATH`.

**Cross-compile it.** Run `./kotlin do buildFFmpegForMingwX64` with a mingw-w64 cross toolchain
(`x86_64-w64-mingw32-gcc`) on the `PATH`. This works from a Linux host or from MSYS2, with the
`vendor/` checkouts described in [Build the FFmpeg trees](#build-the-ffmpeg-trees).

## The web module

The web module is a separate FFmpeg build for WebAssembly, with a smaller set of formats.
`./kotlin do buildFFmpegForWasm` builds its tree with emscripten, from the same `vendor/ffmpeg`
checkout and the same patches. Then:

- `./kotlin do linkKiteFFmpegWasmModule` links `kite.mjs` and `kite.wasm` into `build/kite-web`.
- `./kotlin do linkKiteFFmpegAsyncWasmModules` links the two modules of the asynchronous runtime
  into `build/kite-web-async`.
- `./kotlin build -m kiteffmpeg-web -m kiteffmpeg-web-async` links the modules again and builds the
  two published jars. `kiteffmpeg-web` holds `kite.mjs`, `kite.wasm`, `build-info/` and `licenses/`
  at its root. `kiteffmpeg-web-async` holds the asynchronous modules in the same layout.
  `./kotlin check webPackage` fails while a jar has no codec module.
- `./kotlin do kiteffmpegWebZip` packs the files of the `kiteffmpeg-web` jar into
  `build/kite-web-zip/web/kiteffmpeg-wasm-js-<version>-web.zip`.
- `./kotlin do kiteffmpegWebAsyncZip` packs the files of the `kiteffmpeg-web-async` jar into
  `build/kite-web-zip/web-async/kiteffmpeg-wasm-js-<version>-web-async.zip`.

If emscripten's LLVM is not at the Homebrew path, set `KITEFFMPEG_EMSCRIPTEN_LLVM_BIN` to its `bin`
folder.

`./kotlin test` cannot run the JS tests, and its browser page cannot reach the codec modules. Run
the web tests with `python3 scripts/web-tests/run.py` instead. `--platform` picks `js`, `wasmJs`,
`wasmJsBrowser` or `worker`, and `--require-modules` fails a test that needs a codec module that is
not linked.

## Use a GPL FFmpeg of your own

A GPL tree is one that you build and own. Put it at `native-libs/gpl/<target>/`, with its
`include` and `lib` folders, and select it with an environment variable:

```bash
KITEFFMPEG_FFMPEG_LICENSE=gpl ./kotlin build -m kiteffmpeg -p macosArm64
```

The license name is part of the path and of the identity report, so the build knows what it
linked. The Android platforms always use their LGPL tree. Read [Licensing](licensing.md) before you
ship a GPL build.

## Use a local build from another project

The sample modules already depend on `kiteffmpeg`, so they are the fastest way to run the API
against real arguments ([CONTRIBUTING.md](https://github.com/yuroyami/KiteFFmpeg/blob/main/CONTRIBUTING.md)
shows their commands).

To use your local build from another project, publish it to `~/.m2`:

```bash
. scripts/test-signing-key.sh
./kotlin publish mavenLocal -m kiteffmpeg -m kiteffmpeg-android-natives
```

The publish signs every file, also for `~/.m2`. `scripts/test-signing-key.sh` makes a throwaway key
when `KOTLIN_TOOLCHAIN_SIGNING_KEY` is empty. The publish builds every platform that the host can
build, so each of them needs its FFmpeg tree. A web app that takes the codec module from `~/.m2`
also needs `./kotlin publish mavenLocal -m kiteffmpeg-web`, and `-m kiteffmpeg-web-async` for the
asynchronous runtime.
Then add `mavenLocal()` to the repositories of your project and use the version in
`library.module-template.yaml`:

```kotlin
kotlin {
    macosArm64()
    sourceSets.commonMain.dependencies {
        implementation("io.github.yuroyami:kiteffmpeg:0.5.1")
    }
}
```

A Gradle composite build (`includeBuild("../KiteFFmpeg")`) no longer works, because KiteFFmpeg is
not a Gradle build.
