# kitecodec-jni

The JNI adapter that lets JVM and Android consumers use KiteFFmpeg through its opaque C boundary
(PLANNING.md register item S1C-01). It is deliberately narrow: no logic, no FFmpeg types, no policy.

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

Built by `:kiteffmpeg-core:linkKiteFFmpegJni{MacosArm64,AndroidArm64,AndroidX64}`. The macOS dylib
is test-only (jvmTest loads it through the `kiteffmpeg.jni.path` system property); the two Android
arms are the AAR's `jniLibs` inputs, linked with 16 KiB page alignment.

Source status: the S1.c.2 bridge surface is implemented. The manifest covers the full common/JVM
playback, frame, filter, encoder and mux composition surface. Streams, codec parameters,
dictionaries, dictionary entries
and filter contexts are borrowed child tokens; parent close invalidates the complete descendant
tree before FFmpeg frees it. Static codecs are explicitly released without freeing FFmpeg data.
Every close/release path decrements the live-handle ledger at most once.

`kj_util.c` is the sole Java-array conversion unit. Outbound bytes are copied into a fresh Java
array; inbound `Frame.ofVideo`/`Frame.ofAudio` bytes are copied to owned C memory there and consumed
by `kj_frame.c`. No native pointer or direct byte buffer is public.

The leaf loader calls `System.load` from the test-only `kiteffmpeg.jni.path` override on JVM or
`System.loadLibrary("kitecodec_jni")` otherwise. Only after dynamic registration succeeds does the
bridge run `kc_init`, copy/map the full 31-field identity, and attach the current VM. Rejection is a
typed `IncompatibleFFmpegRuntime`; attach is never attempted first. Android requires `KC_JVM_OK`,
while desktop JVM also accepts the deliberately unsupported result.

The AAR consumer rules keep the `Internals` native methods and the exact binary names plus
`String` constructors of `JniHandleException` and `JniNativeException`. Those two classes are
resolved from `kj_util.c` with `FindClass` and constructed explicitly after strict standard-UTF-8
conversion, so their names and constructors are part of the boundary even though no Java bytecode
calls them directly.
