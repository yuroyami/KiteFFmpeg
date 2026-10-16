# kitecodec-jni

The JNI adapter that lets JVM and Android code call KiteFFmpeg through its opaque C API. It is
narrow on purpose: it holds no logic, no FFmpeg types and no policy of its own.

## What it contains

- **One shared library that exports only `JNI_OnLoad`.** It registers its native methods
  dynamically, so there is no `Java_*` symbol. `methods.def` is the single list of methods. Both
  `kj_registration.c` and the Kotlin bridge are written from it.
- **A handle table (`kj_handles.c`).** Every C object crosses to Java as a `jlong` token, never as a
  pointer. Each token carries a generation number. A stale, zero, closed or wrong-kind token
  throws a typed exception instead of corrupting memory.
- **One file per category:** `kj_abi.c`, `kj_packet.c`, `kj_format.c`, `kj_codec.c`, `kj_frame.c`
  and `kj_filter.c`. Each resolves tokens and works only through the opaque `kc_` and `ffkmp_`
  helper functions. Most methods are one helper call. Identity reports, graph construction and
  array copies combine a few helpers. `kj_abi.c` is the reference example of the pattern.

Two scripts enforce the limits: `scripts/source-discipline.sh` and `scripts/symbol-audit.sh`. The
adapter must never include a libav header, call an FFmpeg function directly, or export anything
other than `JNI_OnLoad`.

## Ownership

The methods cover all of the common and JVM API: playback, frames, filters, encoders and muxing.

- Streams, codec parameters, dictionaries, dictionary entries and filter contexts are borrowed
  child tokens. Closing a parent invalidates all of its children before FFmpeg frees them.
- Static codecs are released without freeing any FFmpeg data.
- Every close or release counts down the live handle count at most once.

## Arrays

`kj_util.c` is the only file that converts Java arrays.

- Bytes going out to Java are copied into a new Java array.
- Frame bytes move in place, in both directions. `kj_util.c` gives `kj_frame.c` the Java array's
  memory inside a critical region, so a frame is copied only once. The array is either a new one,
  or the caller's own array for `Frame.copyPlanesInto`.
- No native pointer or direct byte buffer is public.

## Loading

On the desktop JVM, the loader tries three sources in this order:

1. `System.load` on the path in the `kiteffmpeg.jni.path` system property
2. `System.loadLibrary("kitecodec_jni")`
3. the copy inside the jar, extracted to a temporary directory

Android calls `System.loadLibrary`.

Only after dynamic registration succeeds does the bridge run `kc_init`, copy the FFmpeg identity
and attach the current VM. A rejected identity throws a typed `IncompatibleFFmpegRuntime`, and the
bridge never tries to attach first. Android requires `KC_JVM_OK`. The desktop JVM also accepts
`KC_JVM_UNSUPPORTED`.

## Keep rules

The AAR's consumer rules keep the `Internals` native methods. They also keep the exact class names
and `String` constructors of `JniHandleException` and `JniNativeException`. `kj_util.c` finds those
two classes with `FindClass` and constructs them after a strict UTF-8 conversion. No Java bytecode
calls them, but their names and constructors are still part of the boundary.

## Tests

The JVM test suites test the linked library through the public API. Some paths cannot be reached
that way, such as a handle created while a Java exception is pending. For those,
`native/kitecodec-c/tests/test_jni_bridge.c` compiles `kj_format.c`, `kj_handles.c` and the handle
table against a stand-in `jni.h`, and drives them with a fake `JNIEnv`.

## Build tasks

These `:kiteffmpeg` tasks build the library:

- `linkKiteFFmpegJniAndroidArm64`, `linkKiteFFmpegJniAndroidArm32` and
  `linkKiteFFmpegJniAndroidX64` link the libraries for the AAR's `jniLibs`. The two 64-bit
  libraries are aligned to 16 KiB pages.
- `linkKiteFFmpegJniMacosArm64`, `linkKiteFFmpegJniLinuxX64`, `linkKiteFFmpegJniLinuxArm64` and
  `linkKiteFFmpegJniMingwX64` link the desktop libraries. The JVM jar carries them under
  `kiteffmpeg-native/<os>-<arch>/`. The Linux and Windows libraries are cross-linked with the
  Kotlin/Native toolchain. Each is opt-in, with `-Pkiteffmpeg.jni.linux=true` or
  `-Pkiteffmpeg.jni.windows=true`. A Maven Central release must carry all four.
