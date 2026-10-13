# kitecodec-jni

The JNI adapter that lets JVM and Android consumers use KiteFFmpeg through its opaque C boundary.
It is deliberately narrow: no logic, no FFmpeg types, no policy.

What it is:

- One dynamically registered shared library exporting exactly `JNI_OnLoad`. There is no `Java_*`
  symbol; `methods.def` is the single manifest both `kj_registration.c` and the Kotlin bridge
  are written from.
- A generation-tagged handle table (`kj_handles.c`): every C object crosses as a `jlong` token,
  never a pointer, so stale, zero, double-closed and wrong-kind tokens throw a typed exception
  instead of corrupting memory.
- Category units (`kj_abi.c`, `kj_packet.c`, `kj_format.c`, `kj_codec.c`, `kj_frame.c`,
  `kj_filter.c`) that resolve tokens and operate only through the opaque `kc_`/`ffkmp_` helper
  boundary. Most rows are one helper call; identity reports, graph construction and array-copy
  operations use bounded compositions of those helpers. `kj_abi.c` is the canonical boundary
  pattern.

What it may never do, enforced by `scripts/source-discipline.sh` and `scripts/symbol-audit.sh`:
include a libav header, spell a direct FFmpeg call, or export anything but `JNI_OnLoad`.

The JVM suites test the linked library through the public API. Some paths cannot be reached that
way, such as a handle minted while a Java exception is pending. For those,
`native/kitecodec-c/tests/test_jni_bridge.c` compiles `kj_format.c`, `kj_handles.c` and the handle
table against a stand-in `jni.h` and drives them with a fake JNIEnv.

Built by these `:kiteffmpeg` tasks:

- `linkKiteFFmpegJniAndroidArm64`, `linkKiteFFmpegJniAndroidArm32` and
  `linkKiteFFmpegJniAndroidX64` link the AAR's `jniLibs` inputs. The two 64-bit libraries are
  aligned to 16 KiB pages.
- `linkKiteFFmpegJniMacosArm64`, `linkKiteFFmpegJniLinuxX64`, `linkKiteFFmpegJniLinuxArm64` and
  `linkKiteFFmpegJniMingwX64` link the desktop libraries that the jvm jar carries under
  `kiteffmpeg-native/<os>-<arch>/`. The Linux and Windows ones are cross-linked with Kotlin/Native's
  toolchain, and each is opt-in with `-Pkiteffmpeg.jni.linux=true` or
  `-Pkiteffmpeg.jni.windows=true`. A Maven Central publication must carry all four.

The manifest covers the full common/JVM
playback, frame, filter, encoder and mux composition surface. Streams, codec parameters,
dictionaries, dictionary entries
and filter contexts are borrowed child tokens; parent close invalidates the complete descendant
tree before FFmpeg frees it. Static codecs are explicitly released without freeing FFmpeg data.
Every close/release path decrements the live-handle ledger at most once.

`kj_util.c` is the sole Java-array conversion unit. Outbound bytes are copied into a fresh Java
array. Frame bytes move in place in both directions: `kj_util.c` hands `kj_frame.c` the Java
array's memory inside a critical region, so a frame is copied once. The array is a new one, or for
`Frame.copyPlanesInto` the caller's own. No native pointer or direct byte buffer is public.

On the desktop JVM, the loader tries three sources in order: `System.load` on the path in the
`kiteffmpeg.jni.path` property, then `System.loadLibrary("kitecodec_jni")`, then the copy in the
jar, which it extracts to a temporary directory. Android calls `System.loadLibrary`. Only after dynamic registration succeeds does the
bridge run `kc_init`, copy/map the full 31-field identity, and attach the current VM. Rejection is a
typed `IncompatibleFFmpegRuntime`; attach is never attempted first. Android requires `KC_JVM_OK`,
while desktop JVM also accepts the deliberately unsupported result.

The AAR consumer rules keep the `Internals` native methods and the exact binary names plus
`String` constructors of `JniHandleException` and `JniNativeException`. Those two classes are
resolved from `kj_util.c` with `FindClass` and constructed explicitly after strict standard-UTF-8
conversion, so their names and constructors are part of the boundary even though no Java bytecode
calls them directly.
