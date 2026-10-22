# About KiteFFmpeg

KiteFFmpeg is one Kotlin API for video and audio over FFmpeg's libav\* libraries. Kotlin/Native
reaches them through cinterop, the JVM and Android through a JNI library over the same C helpers,
and `wasmJs` through a generated binding over a WebAssembly build. `js` is a placeholder. There is
no `ffmpeg` process.

This page covers the design, the native boundary and the license. For the API itself, start with
[Getting started](getting-started.md) or the
[API reference](https://yuroyami.github.io/KiteFFmpeg/api/).

## Approach

An app that uses FFmpeg usually starts the `ffmpeg` command line and reads the text it prints, or
it wraps a prebuilt binary such as FFmpegKit. You build the arguments into a string, start a
process, and read the progress back out of log lines.

KiteFFmpeg calls the libraries directly. `Transcoder.transcode(...)` opens the file with
libavformat and demuxes it once. It sends the packets to a libavcodec decoder for each stream,
pushes the frames through libavfilter graphs, encodes them, and interleaves the streams into a
valid container. Progress arrives as a typed callback, errors arrive as typed exceptions, and
frames arrive as a coroutine `Flow`.

Every pipeline reads its input once. When you decode several streams, or composite two inputs, the
demuxer reads the file a single time and hands the packets to the decoders that need them.

## Status

KiteFFmpeg has not reached 1.0, so the API can still change between minor versions. It is on Maven
Central, with FFmpeg inside every artifact, and it is the engine under
[KitePlayer](https://github.com/yuroyami/KitePlayer). [Platform support](platforms.md) lists what
each target carries, and the [open issues](https://github.com/yuroyami/KiteFFmpeg/issues) list the
work that is still to do.

!!! note "Frame ownership"
    Frames that the `Flow` APIs emit (`MediaSource.decodedFrames`, `MediaSource.decodeStreams` and
    `FilterGraph.process`) belong to the collector. Each one stays valid until you close it, so
    `toList()` is safe. `buffer()`, `flowOn`, `conflate` and `produceIn` are not safe, because a
    canceled flow drops what they queued without closing it. Use `bufferFrames()` to decode
    ahead. Close every frame you collect, or its native buffers leak. A frame passed to a callback
    (the `onOutput` of `FilterGraph.feedInput`) is valid only during that call. `Frame.copy()`
    takes an owned snapshot in constant time. The native `AVFrame*` is not exposed in `commonMain`.

## Architecture

### One opaque native boundary

The Kotlin/Native binding is one cinterop module
(`kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def`). The def parses only KiteFFmpeg's helper,
handle and ABI headers, not the libav\* functions, constants or struct layouts. Twelve `kc_*`
aliases stand for FFmpeg's structs as incomplete types, and Kotlin code never names those structs
directly. The aliases and the `ffkmp_*` functions are the whole native boundary. The compiled C
archive includes the FFmpeg headers and makes the FFmpeg calls.

The JVM and Android reach the same boundary through `native/kitecodec-jni`. It registers its
methods from a manifest, tags each handle with a generation, copies arrays, and converts errors to
typed ones.

### The `ffkmp_*` C helpers

Kotlin calls more than 270 C helpers, all named `ffkmp_*`, through the twelve `kc_*` aliases. They
live in `native/kitecodec-c/`, with one source file for each subsystem, and the build compiles them
into a static archive for each Kotlin/Native target. The C layer has its own test suites, three
sanitizer builds and fuzz targets.

The ABI of the C layer has a version. The major number changes when a declaration changes shape,
and the minor number grows when helpers are added compatibly. `KITECODEC_C_ABI_MAJOR` and
`KITECODEC_C_ABI_MINOR` in `kitecodec_abi.h` hold the current version, and `kc_abi_version()`
reports it at run time.

The helpers exist because some of FFmpeg's surface does not survive cinterop cleanly:

- **Macros.** `av_err2str` is a compound-statement macro, and `AVERROR(EAGAIN)` and `AVERROR_EOF`
  are function-style macros. Each one gets a real C function that cinterop can read.
- **Struct field accessors.** `ffkmp_stream_codecpar(kc_stream*)`, `ffkmp_frame_pts(kc_frame*)`
  and similar accessors give one stable read path for each field. FFmpeg marks many fields as "do
  not access directly", and several change between libav versions.
- **Timestamp math.** `ffkmp_rescale_q` wraps `av_rescale_q`, which converts a timestamp between
  time bases without overflow.
- **Pointer-to-pointer calls.** `avformat_close_input(AVFormatContext**)` and similar calls become
  wrappers that take a single pointer.
- **One-call pipeline helpers.** `ffkmp_fmt_open_input(...)` allocates and opens in one call.
  `ffkmp_graph_build_video(...)` and `ffkmp_graph_build_audio(...)` build a whole graph (buffer,
  chain, buffersink) from one filter string. The audio one appends `aformat`, so its output is
  ready for the encoder. The filter string syntax stays stable across FFmpeg versions, while the
  buffersink option names do not.

### Source layout

```
native/kitecodec-c/                  the C helper layer, with its tests and fuzz targets
├── include/kitecodec_helpers.h      helper declarations, with no FFmpeg header
├── include/kitecodec_handles.h      the twelve opaque handle aliases
├── include/kitecodec_abi.h          the contract of the FFmpeg identity check
├── src/helpers_*.c                  one implementation file for each subsystem
├── src/kitecodec_abi.c              the identity check itself
└── tests/ fuzz/ scripts/            the C suites, the fuzz targets and the audits
native/kitecodec-jni/                the JNI library, with no libav headers
native/kitecodec-web/                the web module around the WebAssembly FFmpeg
kiteffmpeg/src/
├── nativeInterop/cinterop/ffmpeg.def   the opaque cinterop, which names the helper archive
├── commonMain/                      the public API: FFmpeg, MediaSource, Frame, FilterGraph,
│                                    MediaSink, Transcoder, Remuxer and the value types
├── nativeMain/                      the Kotlin/Native implementation
├── jvmAndAndroidMain/               the JVM and Android implementation over JNI
├── wasmJsMain/                      the web implementation over the generated binding
├── webRefusedMain/                  what the web build refuses: transcode, remux, mux, filter
└── unsupportedMain/                 the js placeholder
```

Most public types live in `io.github.yuroyami.kiteffmpeg`. The option and filter builders, such as
`DemuxOptions`, `DecoderOptions`, `FilterChain` and the filter steps, live in
`io.github.yuroyami.kiteffmpeg.dsl`.

### Timestamp handling

KiteFFmpeg follows the rules of `ffmpeg.c` at every stage:

- **Decode.** A decoder promotes `best_effort_timestamp` to `pts`, so a file with missing or broken
  timestamps still cuts correctly.
- **Filter.** A filter graph reports its output time base, which is not always the input's (`fps`
  and `atempo` change it). Frames that leave a graph carry the output time base, and
  `FilterGraph.outputTimeBase` exposes it.
- **Encode.** An encoder rescales each incoming `pts` onto the codec time base with
  `av_rescale_q`, and keeps the timestamps strictly increasing. A frame with no `pts` at all falls
  back to a counted timeline: the frame count for video, and the sample count for audio.
- **Mux.** The packet timestamps are rescaled once more, onto the stream time base that the muxer
  chose in `avformat_write_header`.

!!! tip "Why `Rational` is its own type"
    FFmpeg time bases and frame rates are exact fractions, not floats. `Rational` is always
    reduced, and its `times(scalar: Long)` operator rescales without overflow. Use it rather than
    converting to seconds and back, where rounding errors add up.

## Where FFmpeg comes from

An app provides nothing, because FFmpeg is compiled into every published artifact. Inside the
repository, the build compiles against the static tree under `native-libs/`, and it falls back to a
system FFmpeg only for the host's own desktop target.
[Building from source](building-from-source.md) covers both.

## License

KiteFFmpeg's own code is licensed under the Apache License 2.0. You can use, change and distribute
it in commercial and open-source projects.

The FFmpeg that you link carries its own license. It is LGPL-2.1-or-later when FFmpeg is built
without `--enable-gpl`, and GPL with it. Every published KiteFFmpeg artifact is LGPL, which a
commercial, closed-source app can ship under the [LGPL duties](licensing.md). Static linking on the
iOS App Store is the hardest case of those.

The build makes only LGPL trees. A GPL tree is one that you build and own, and
the environment variable `KITEFFMPEG_FFMPEG_LICENSE=gpl` selects it. The iOS targets refuse a GPL tree. The
[Licensing guide](licensing.md) has the full guidance.

## Acknowledgments

- **FFmpeg** and the libav\* libraries: the codec, container and filter engine that KiteFFmpeg
  binds to.
- **`ffmpeg.c`**: the reference for demux, decode, filter, encode and mux, and for timestamp
  handling above all.
