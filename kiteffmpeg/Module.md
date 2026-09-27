# Module kiteffmpeg

A coroutine-first Kotlin Multiplatform API over FFmpeg's libav* libraries.

`MediaSource` to demux and decode, `FilterGraph` for any FFmpeg filter chain,
`MediaSink` to encode and mux, and `Transcoder`/`Remuxer` for the whole pipeline
in one call. Kotlin/Native actuals use cinterop; JVM and Android use a dynamically registered JNI
adapter over the same opaque C helper boundary. **Both are published.** The Android AAR carries
`libkitecodec_jni.so` for `arm64-v8a`, `armeabi-v7a` and `x86_64` at `minSdk 26`; the JVM jar
carries the library for macOS arm64, Linux x64, Linux arm64 and Windows x64, so a JVM consumer on
any other platform gets the unsupported placeholder. `wasmJs` is a real playback backend over a generated binding,
once its wasm module is loaded. `js` is the placeholder: diagnostics are readable, capabilities are
empty, and media operations fail with typed `FFmpegError.Unsupported`. There is no subprocess. Since FFmpeg was embedded the FFmpeg binaries ride INSIDE
the published artifacts, so a consumer provisions nothing.
