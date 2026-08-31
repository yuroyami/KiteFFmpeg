# About KiteFFmpeg

**One coroutine-first Kotlin API for video and audio.** KiteFFmpeg binds to FFmpeg's libav\*
libraries through Kotlin/Native cinterop or, in the local Android proof, a dynamically registered
JNI adapter over the same opaque C helpers, and both are published. 41 tests exercise that adapter over real FFmpeg on an arm64 Mac. Public
JVM, JS and WasmJs use an invariant unsupported placeholder implementation. There is no `ffmpeg`
subprocess, and memory stays constant regardless of input length.

This page covers the project's current status, its roadmap, the binding architecture, and the license. For the API itself, start with [Getting started](getting-started.md) or the [API reference](https://yuroyami.github.io/KiteFFmpeg/api/).

## Approach

Media work from Kotlin normally means launching the `ffmpeg` CLI and parsing its stderr output, or wrapping a prebuilt binary like FFmpegKit. You build arguments into a string, launch a process, and read progress back out of log lines.

KiteFFmpeg calls the libraries directly. `Transcoder.transcode(...)` opens the file via libavformat, demuxes once, routes packets to per-stream libavcodec decoders, pushes frames through libavfilter graphs, encodes, and interleaves the streams into a valid container. Progress arrives as a typed callback, errors arrive as typed exceptions, and frames flow as a coroutine `Flow`.

Everything routes through a single demux pass. When you decode several streams, or composite two inputs, the demuxer reads the file once and fans packets out to the decoders that need them.

## Current Status

KiteFFmpeg is pre-1.0 and actively developed. The full **demux -> decode -> filter -> encode -> mux**
API is implemented for both video and audio, in a single pass. Kotlin/Native has the standing
runtime evidence; Android actuals and an unpublished JVM harness share the JNI contracts, with host
tests and Android link/packaging checks. Public JVM and Web use the tested unavailable contract.
No target artifact has been publicly released.

There is one status table for the whole project, and it lives in the [README](https://github.com/yuroyami/KiteFFmpeg#targets). It records, per target, whether a public artifact exists, what build/test evidence exists, and where FFmpeg comes from.

The two things a reader most often needs from it:

- **KiteFFmpeg IS on Maven Central.** `io.github.yuroyami:kiteffmpeg:0.1.0`, one dependency line,
  FFmpeg embedded inside the artifacts. There is no Gradle plugin any more and no FFmpeg download
  step: the plugin was deleted and FFmpeg moved inside the published klibs, so a consumer needs
  nothing on disk. This paragraph said the opposite until 2026-08-24, which was three published
  versions out of date.
- **JVM, Android and Web.** The published Android AAR is real: its own manifest declares
  `minSdkVersion 26` and it carries `libkitecodec_jni.so` for `arm64-v8a` and `x86_64`. The
  published JVM jar carries a **macOS arm64** library and only that one, so a JVM consumer on Linux
  or Windows still gets the typed unavailable placeholder. No Android playback is qualified on a
  physical device. JS and WasmJs are tested placeholders whose media operations fail with typed
  `FFmpegError.Unsupported`.

!!! note "Frame ownership"
    Frames emitted by the public `Flow` APIs (`MediaSource.decodedFrames`, `MediaSource.decodeStreams`, `FilterGraph.process`) are **owned by the collector**. Each stays valid until you close it, so buffering operators such as `buffer()` and `toList()` are safe. Every collected frame must be closed, or its native buffers leak. Frames passed to a callback (`FilterGraph.feedInput`'s `onOutput`) are valid only for the duration of that call. `Frame.copy()` takes an O(1) owned snapshot. The native `AVFrame*` is deliberately not exposed in `commonMain`.

## What's next

- **The FFmpeg binary release**: unblocking `release-binaries.yml` is what turns `FFmpegSource.Prebuilt` from a 404 into the default path, and is the prerequisite for publishing `kiteffmpeg` at all.
- **Android runtime qualification and publication**: the JNI/AAR source and packaging model is in
  place; playback qualification, physical-device evidence, consumer publication and app/UI
  integration remain.
- **Bitstream filters** (h264 to/from Annex B) so stream copy reaches MPEG-TS.
- **Hardware decode and full hwframes pipelines**: zero-copy VideoToolbox / CUDA.
- **iOS qualification**: the repository now has an LGPL mobile Apple FFmpeg build and local-consumption path for `iosArm64` and `iosSimulatorArm64`. It is a private/local substrate, not a public artifact or CI claim. Runtime and application qualification belong to the consuming player stages.

## Architecture

### One opaque native boundary

The Kotlin/Native binding is **one** cinterop module (`kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def`), but
the def parses only KiteFFmpeg's helper, handle and ABI headers. It no longer parses libav\*
functions, constants or struct layouts. Eleven incomplete forward tags remain behind the eleven
`kc_*` aliases, and Kotlin source is forbidden to name those tags directly. The aliases and
`ffkmp_*` functions are the complete native boundary; the compiled C archive owns the direct
FFmpeg headers and calls internally. JVM and Android reach that same boundary through
`native/kitecodec-jni`: a dynamically registered method manifest, generation-tagged handles,
copied arrays and typed error conversion.

Earlier revisions consolidated every libav header into that one cinterop to avoid the duplicate
`AVCodec` / `AVFrame` / `AVPacket` types produced by six separate cinterops. The opaque boundary
removes that type-sharing problem entirely while retaining one package and one archive.

### The `ffkmp_*` C helpers

Kotlin consumes 171 C helpers, all prefixed `ffkmp_*`, through eleven opaque `kc_*` handle aliases.
They live in `native/kitecodec-c/`, compiled per Kotlin/Native target into a static archive that the
def names and cinterop embeds. ABI 2.0 is the breaking C-source boundary: 140 legacy declarations
were respelled from raw FFmpeg pointer types to the aliases, and the seven wrappers plus five
media-type accessors added compatibly at ABI 1.1 now carry every Kotlin call across that boundary.
ABI 2.1 added packet cloning and VM attachment; ABI 2.2 adds selected-codec identity so a named
decoder is verified against its stream before open.

The helpers used to be `static inline` text inside the def, which meant no translation unit, no object
file and no test. The C layer now has nine translation units, seven C test suites, three sanitizer
variants and six fuzz targets. Its historical extraction was byte-compared before the generator and
`scripts/verify-lift.sh` were retired; the proof remains in the execution record.

They exist because some of FFmpeg's surface does not survive cinterop cleanly:

- **Macros that don't survive cinterop.** `av_err2str` is a compound-statement macro; `AVERROR(EAGAIN)` and `AVERROR_EOF` are function-style macros. Each gets a real C function the indexer can actually read.
- **Struct field accessors.** `ffkmp_stream_codecpar(kc_stream*)`, `ffkmp_frame_pts(kc_frame*)`, and similar accessors. Modern FFmpeg marks many fields "do not access directly," and several vary across libav versions. A thin C accessor pins one stable read path per field.
- **128-bit-safe timestamp math.** `ffkmp_rescale_q` wraps `av_rescale_q`, the only overflow-safe way to convert timestamps between time-bases.
- **Double-pointer ceremony for alloc/free pairs.** `avformat_close_input(AVFormatContext**)` and similar become single-pointer wrappers that Kotlin/Native interop calls cleanly.
- **One-shot pipeline helpers.** `ffkmp_fmt_open_input(...)` does alloc plus open in one call; `ffkmp_graph_build_video(...)` / `ffkmp_graph_build_audio(...)` build a complete buffer -> chain -> buffersink graph from a single filter description. The audio variant appends `aformat` so output arrives ready for the encoder. Filter-string syntax stays stable across FFmpeg versions, while buffersink option names do not.

### Source layout

```
native/kitecodec-c/                  ← the C helper layer: nine units, its own tests and fuzz targets
├── include/kitecodec_helpers.h      ← maintained opaque helper declarations, no FFmpeg header
├── include/kitecodec_handles.h      ← eleven opaque handle aliases
├── include/kitecodec_abi.h          ← the FFmpeg identity gate's contract, no FFmpeg header in it
├── src/helpers_*.c                  ← maintained implementations, one per subsystem
├── src/kitecodec_abi.c              ← the identity gate itself
├── tests/ fuzz/ scripts/            ← seven suites, six fuzz targets and the audits
native/kitecodec-jni/                ← dynamically registered JNI adapter; no libav headers
kiteffmpeg/src/
├── nativeInterop/cinterop/
│   └── ffmpeg.def                   ← opaque cinterop; names the compiled helper archive
├── commonMain/kotlin/io/github/yuroyami/kiteffmpeg/
│   ├── FFmpeg.kt                    ← capability probing + Versions
│   ├── MediaSource.kt               ← demuxer + decode flows (expect)
│   ├── Frame.kt                     ← owned frame contract
│   ├── FilterGraph.kt               ← video + audio graph wrappers
│   ├── MediaSink.kt                 ← muxer + Video/AudioEncoder + specs
│   ├── Transcoder.kt                ← high-level one-pass A/V pipeline (expect)
│   ├── StreamInfo.kt / Errors.kt
│   └── MediaType.kt / Rational.kt   ← value types for FFmpeg's small types
├── nativeMain/kotlin/io/github/yuroyami/kiteffmpeg/
    ├── FFmpeg.native.kt
    ├── MediaSource.native.kt        ← single-pass multi-stream decode loop
    ├── Frame.native.kt              ← frame acquire / wrap
    ├── FilterGraph.native.kt        ← Flow + push-style graph driving
    ├── MediaSink.native.kt          ← muxer + shared encode core
    ├── Transcoder.native.kt         ← interleaved A/V orchestration
│   └── Internals.kt                 ← error mapping, format mapping
├── jvmAndAndroidMain/kotlin/io/github/yuroyami/kiteffmpeg/
    ├── *.jvm.kt                     ← JVM/Android actuals for the common API
    └── Internals.jvm.kt             ← checked JNI handles, identity and error mapping
└── unsupportedMain/kotlin/io/github/yuroyami/kiteffmpeg/
    └── *.unsupported.kt             ← public JVM + JS/WasmJs; no media runtime
```

Every common contract has Kotlin/Native, JVM/Android and Web actuals. All public types live flat
under `io.github.yuroyami.kiteffmpeg`, with no internal subpackages.

### Timestamp handling

KiteFFmpeg follows `ffmpeg.c`'s own rules at every stage:

- **Decode.** Decoders promote `best_effort_timestamp` to `pts`, so files with missing or broken pts still cut correctly.
- **Filter.** Filter graphs report their **output** time-base, which is not always the input's: `fps` and `atempo` change it. Frames leaving a graph are stamped with the output time-base. The `FilterGraph.outputTimeBase` property exposes it.
- **Encode.** Encoders rescale incoming pts onto the codec time-base via `av_rescale_q` and force strict monotonicity. Frames with no pts at all fall back to a synthetic timeline: frame count for video, accumulated sample count for audio.
- **Mux.** Packet timestamps are rescaled once more onto whatever stream time-base the muxer actually chose after `avformat_write_header`.

!!! tip "Why `Rational` is its own type"
    FFmpeg time-bases and frame rates are exact fractions, not floats. `Rational` is always normalized, and its `times(scalar: Long)` operator does overflow-safe rescaling. Use it instead of converting to seconds and back, where rounding accumulates. See the [API reference](https://yuroyami.github.io/KiteFFmpeg/api/) for the full `Rational` surface.

## FFmpeg sourcing

KiteFFmpeg links against an FFmpeg you provide. Inside this repository it either discovers a host system install or builds a vendored tree. A consumer plugin has a third, no-network Local mode for reusing a complete generated tree.

=== "Dynamic (default)"

    Links against a system FFmpeg. This is what the macOS arm64 build does today. The Gradle build finds Homebrew on macOS or apt-installed libraries on Linux, compiles the C archive against those headers, and links the dylibs. The reduced def parses no FFmpeg header. The module build still supplies the include path redundantly to cinterop, but it is unused by the opaque header set. Your users need their own FFmpeg installed at run time.

    ```bash
    brew install ffmpeg                     # macOS
    sudo apt install ffmpeg libavcodec-dev libavformat-dev \
        libavfilter-dev libavutil-dev libswscale-dev libswresample-dev   # Linux
    ```

=== "Vendored static (release)"

    Cross-compiles a minimal FFmpeg from source through a Gradle task, with a pinned codec and filter set, and drops `.a` libraries under `native-libs/<license>/<target>/` (`lgpl` by default; `gpl` for the opt-in `Gpl` task variants). The build notices them, compiles the C archive against their headers and switches the final link to the static libraries, so the resulting binary carries everything it needs.

    ```bash
    git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
    ./gradlew :kiteffmpeg:buildFFmpegForMacosArm64   # or :buildFFmpegForAll
    ```

    The build copies source to a unique hash-free temporary workspace, configures and installs there, records the normalized configure invocation at `lib/kiteffmpeg/ffmpeg-configure.txt`, verifies that record plus all six archives and headers, stages a Java/NIO copy beside the declared output and only then replaces the old tree. Packaging reads only that installed single-line record. The mobile Apple tasks use the shared STANDARD software-playback profile plus SDK zlib. They do not use the desktop third-party stack, GPL, VideoToolbox or hardware encode.

=== "Local consumer tree"

    After a private `publishToMavenLocal`, set `source = FFmpegSource.Local` and point `localRoot` at the absolute `native-libs` directory. The plugin requires `<localRoot>/<license.id>/<target-triple>/{include,lib}` for every wired target and never downloads. Local iOS is LGPL-only and links SDK zlib; local macOS searches its tree before the host fallback and uses the desktop static link set. Nothing about this mode implies a public artifact or CI result.

See [Platform support](platforms.md) for the per-target detail.

## License

KiteFFmpeg's own code is licensed under the **Apache License 2.0**. You can freely use, modify, and distribute it in commercial and open-source projects.

The FFmpeg you link against carries its own license, separate from KiteFFmpeg's. It is **LGPL-2.1+** when FFmpeg is built without `--enable-gpl`, and **GPL** with it. **Every KiteFFmpeg artifact is LGPL**, which is commercial- and App-Store-safe (with the usual [LGPL distribution obligations](licensing.md)). A `kiteffmpeg-gpl` module that would package a GPL flavour (libx264 / libx265) as a drop-in artifact does not exist: it is a README and nothing else, with no `build.gradle.kts`, commented out of `settings.gradle.kts`.

When you build a vendored static FFmpeg here, there is only one flavour to build: `buildFFmpegFor<Target>` produces LGPL. **The `buildFFmpegFor<Target>Gpl` tasks were deleted on 2026-08-21**, because publishing a GPL-flavoured binary decides the licence of every application that links it. `-Pkiteffmpeg.ffmpeg.license=gpl` still selects `native-libs/gpl/<target>/`, so a GPL tree is one you build and own. Path resolution refuses GPL for every iOS target before it looks for a tree, with `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.` Stay on LGPL if you ship through a GPL-hostile channel such as the iOS App Store. Full compliance guidance lives in the [Licensing guide](licensing.md).

## Acknowledgments

- **FFmpeg** and the libav\* libraries: the codec, container, and filter engine KiteFFmpeg binds to.
- **`ffmpeg.c`**: the reference for correct demux, decode, filter, encode, and mux orchestration, especially timestamp handling.
