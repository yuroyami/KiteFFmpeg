# Module kiteffmpeg

A coroutine-first Kotlin Multiplatform API over FFmpeg's libav* libraries.

`MediaSource` to demux and decode, `FilterGraph` for any FFmpeg filter chain,
`MediaSink` to encode and mux, and `Transcoder`/`Remuxer` for the whole pipeline
in one call. Kotlin/Native calls FFmpeg through cinterop, and the JVM and Android call it through a
JNI library over the same C helpers. The Android AAR carries `libkitecodec_jni.so` for
`arm64-v8a`, `armeabi-v7a` and `x86_64` at `minSdk` 26. The JVM jar carries the library for macOS
arm64, Linux x64, Linux arm64 and Windows x64, and on any other platform the first call throws
`UnsatisfiedLinkError`. `wasmJs` reads and decodes through a generated binding, once its
WebAssembly module is loaded. `js` is a placeholder whose media calls throw
`FFmpegError.Unsupported`. There is no `ffmpeg` process. The FFmpeg binaries are inside the
published artifacts, so an app installs nothing.
