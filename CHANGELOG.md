# Changelog

All notable changes to KiteFFmpeg are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KiteFFmpeg is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

## [0.1.3] - 2026-08-24

**Start here.** 0.1.0 and 0.1.1 are on Maven Central and stay there; 0.1.2 was tagged and never
published. This release supersedes all three, and everything they carried is listed below.

Consumer integration is one line, on every platform:

```kotlin
implementation("io.github.yuroyami:kiteffmpeg-core:0.1.3")
```

### Fixed in 0.1.3

- **AAC encoding was missing on Linux and Windows.** `aac` was enabled only in the Apple and
  Android profiles, so `Transcoder.transcode` with default audio settings failed with
  `No encoder named 'aac'` on those two platforms. Measured, not inferred: `ff_aac_encoder` was
  present in `macos-arm64` and absent from `linux-x64` and `mingw-x64`. It is now in the shared
  encoder list, and the companion binaries were rebuilt. Affected 0.1.1 and 0.1.2.

### Fixed, carried from 0.1.2 (never published)

Each of these was broken on some platforms and correct on others, which is why none of them looked
like bugs.

- **Audio encoders accepted video frames** (JVM, Android). The picture was encoded as sound instead
  of refused.
- **One file's stream was accepted by another file's remux** (JVM, Android). Matched by index alone,
  so the output carried the wrong time base and played at the wrong speed.
- **A missing encoder could not be caught by kind** (iOS, macOS, Linux, Windows). It threw an
  untyped internal error instead of `EncoderNotFound`, so callers could not fall back.
- **Video frames could be freed mid-draw** (iOS, macOS, Linux, Windows). `withPlanes` and
  `hardwareSurface` handed out pointers without holding the frame open.
- **`corruptDataSkipped` read zero during playback** (browser). Reset on every decode and written
  only at the end, so a second decode erased the first one's total.
- **Two leaks** (browser). A failed reader open leaked one codec context per stream; a failed
  decoder open wedged the source so it never opened another reader.
- **A failed `addCopyStream` left the writer usable** (all platforms). The muxer kept a
  half-configured stream and went on accepting calls.

### Carried from 0.1.1

- **FFmpeg lives inside the published klibs, and the Gradle plugin is gone.** Each native target's
  cinterop klib embeds the six libav\* archives plus libdav1d and carries its platform linker
  flags, so integration is the dependency line above and nothing else. The plugin module, its DSL
  and its `Local`/`System` consumer modes are deleted.
- **The Android AAR is a first-class artifact** (`kiteffmpeg-core-android`): self-contained
  `libkitecodec_jni.so` for `arm64-v8a` and `x86_64`, licence payload under `META-INF/licenses/`,
  consumer keep rules. Before this an `androidTarget` consumer resolved the JVM artifact and failed
  at first load.
- **A real JVM variant.** `jvmMain` used to compile the throw-everything placeholder, so every
  desktop consumer got a library whose entry points all threw. It builds the real tree now, with the
  host JNI library inside the jar.
- **dav1d is mandatory in every build**, so software AV1 decoding always works. All 11 native
  targets are published, and publication hard-fails if any configured target lacks its FFmpeg tree.
- **Every FFmpeg profile is portable**, macOS included, so a release asset links on a machine with
  nothing installed.

### Carried from 0.1.0

- **Apache-2.0 `LICENSE` and `NOTICE`.** The repository previously had no valid licence file at all,
  which made it legally unusable. `NOTICE` states the FFmpeg LGPL position and the obligations it
  puts on a consumer.
- **No GPL builds.** This project builds and publishes the LGPL flavour only; shipping a
  GPL-flavoured binary would make a consumer's whole application GPL-3.0, which is not a decision a
  library should make for them.

### Not tested

The three browser fixes and the `addCopyStream` fix ship without an automated test: there is no
browser test source set, and the muxer failure cannot be triggered through the public API. Every
other fix above landed with one.

### FFmpeg binaries

The companion zips for this release are on `ffmpeg-n8.0-r2`. The older `ffmpeg-n8.0` release is kept
unchanged, because `NOTICE` names it as the LGPL source offer for 0.1.0 and 0.1.1, which are on
Maven Central permanently. A consumer needs neither: FFmpeg is already inside the artifact.

## [0.1.0] - 2026-08-21

First public release. The repository went public on this date; everything before it
was private and unpublished.

### Added
- `LICENSE` (Apache-2.0) and `NOTICE`. The repository had **no licence file at all**
  until now, which made it legally unusable by anyone. `NOTICE` states the FFmpeg LGPL
  position and what shipping it obliges you to do.

### Removed
- **All GPL FFmpeg build tasks and release jobs.** This project builds and publishes the
  LGPL flavour only. Distributing a GPL-flavoured binary makes the consumer's whole
  application GPL-3.0, which is not a decision a library should make on their behalf.
  `FFmpegLicense.GPL` survives as a LABEL for a tree you built yourself (it is a path
  segment and rides into the identity report); what is gone is KiteFFmpeg producing one.
  This also deletes register row P0-14, where `portableDesktopArgs()` ignored the licence
  argument and wrote trees containing no GPL code into directories named `gpl` - a
  curiosity while private, a false public statement about licensing once published.

## [Unreleased]

Nothing yet.

## [0.1.1] - 2026-08-22

**The first release on Maven Central**, and the first anyone can consume with one dependency line. 0.1.0 existed only as source and local artifacts; everything below shipped publicly for the first time in 0.1.1, The 11 FFmpeg companion zips live on the `ffmpeg-n8.0` release, one canonical copy shared by every KiteFFmpeg version, because they are keyed to the FFmpeg version rather than to this one; they are build evidence and the LGPL source offer, and a consumer needs none of them.

### Added
- **The Android AAR is a first-class published artifact** (`kiteffmpeg-core-android`): self-contained `libkitecodec_jni.so` for `arm64-v8a` and `x86_64` with FFmpeg + dav1d statically inside, the LGPL licence payload under `META-INF/licenses/`, and consumer keep rules. Before this, an `androidTarget` consumer resolved the JVM artifact and failed at first load.

### Changed
- **KC-EMBED: FFmpeg now lives INSIDE the published klibs, and the Gradle plugin is gone.**
  Owner decision 2026-08-22. Each native target's cinterop klib embeds the six libav\*
  archives plus libdav1d (the same `staticLibraries` slot `libkitecodec.a` always rode) and
  carries its platform linker flags, so the whole consumer integration is
  `implementation("io.github.yuroyami:kiteffmpeg-core:<v>")`. Proven the day it landed: a
  project with nothing but that line linked a macOS executable (which ran, identity gate
  green), an iOS simulator framework and a Windows PE32+ executable. The plugin module,
  its DSL (`source`/`license`/`dav1d`/`libass`/`repo`/`releaseTag`/`pinnedSha256`), its
  tasks and its docs page are DELETED; `Local` and `System` consumer modes die with it
  (a dev host without vendored trees still falls back to a system FFmpeg internally, via
  `ffmpeg-system.def`). The version-mismatch corruption class (B1-03) is gone by
  construction, so the plugin-side version gate went with it. Artifact POMs now declare
  Apache-2.0 + LGPL-2.1-or-later (embedded FFmpeg) + BSD-2-Clause (dav1d), the JVM jar
  carries the licence texts, and NOTICE states the consumer obligations.
- **The dav1d axis is dead: dav1d is mandatory in every FFmpeg build.** Measured in-app
  cost ~0.7-0.9 MB on arm64, ~1.6-2.0 MB on x86_64; without it FFmpeg plays zero AV1 in
  software. One flavour per triple (11 zips, `-dav1d` suffix gone), the two-way contract
  deleted, `--enable-libdav1d` now part of the recipe fingerprint, and every
  `buildFFmpegFor<Target>` depends on its `buildDav1dFor<Target>`.
- **All 11 native targets are published.** The stable/experimental publication split is
  gone; publication still hard-fails if any configured target lacks its FFmpeg tree.
- **Every FFmpeg profile is now PORTABLE, macOS included.** The fat macOS desktop profile
  (vpx/aom/opus/lame/webp encoders, the freetype/harfbuzz/fribidi/libass text stack, drawtext)
  is gone. Every one of those libraries had to come from Homebrew, Homebrew ships graphite2
  shared-only, and a Release asset that only links on a machine with Homebrew is not an asset.
  macOS now builds exactly like iOS (SDK zlib, VideoToolbox, AudioToolbox) plus the VideoToolbox
  encoders and the native aac encoder. Decoding is untouched: the read side is wide by class,
  and software AV1 is the dav1d flavour's job. A consumer's macOS link set shrinks to
  `-lz` plus the five media frameworks; an old fat Local tree reads as stale in
  `checkFFmpegRecipes` and rebakes portable.
- **Release assets moved to the KiteFFmpeg version tag.** Prebuilts now live on `v<version>`
  (`v0.1.0`), not on `ffmpeg-<ffversion>`. The plugin's new `ffmpeg.releaseTag` property
  defaults to the plugin's OWN version tag through a generated constant, so a plugin version
  always fetches the assets released with it. Every KiteFFmpeg release ships the FULL set:
  11 triples x 2 flavours (plain and dav1d) = 22 zips, built by `release-binaries.yml`.
- **`BuildDav1dTask` covers all 11 triples.** android-arm32, ios-x64 and macos-x64 gained
  cross files (all three proven on this machine), so every triple has a dav1d flavour.

### Added
- **`checkFFmpegRecipes`, and `-Pkiteffmpeg.ffmpeg.autoBake=true`.** A vendored FFmpeg tree is a dead artifact: nothing rebuilt it and nothing compared it, so a recipe change in `buildSrc` and the `.a` files on disk drifted apart in silence. Measured: `av1_videotoolbox` was pinned into the Apple hwaccel list on 2026-08-19 and every Apple tree still lacked it a day later with no check red anywhere. Every bake already stamped its exact configure line into the tree; nobody read it. `checkFFmpegRecipes` now reads it and names the capability flags that moved, with the task to re-run. `-Pkiteffmpeg.ffmpeg.autoBake=true` is the automatic half: the compile tasks depend on the bake, so Gradle re-bakes exactly when its inputs moved and skips it as UP-TO-DATE when they did not. Opt-in, because a first bake is tens of minutes. Machine-specific flags (`--prefix`, `--cc`, SDK paths) are excluded so an Xcode update never reads as drift, and the dav1d toggle is excluded because the plugin's dav1d contract already guards it in both directions.

- **`kiteffmpegCleanCache` and `kiteffmpeg { cleanCacheOnClean = true }`.** `clean` wipes `build/`, but nothing ever wiped what the plugin GRABBED: downloaded FFmpeg archives live in the shared Gradle cache (`<gradle-user-home>/caches/kiteffmpeg`) and outlived every project clean invisibly. The task is the visible handle; the property hooks it into `clean` for consumers who want a cleared project to mean cleared provisioning too. Default off, because the cache is shared by every project on the machine. `ffmpeg.localRoot` is never touched either way: the plugin only reads that tree and must not delete what it did not create.
- **`kiteffmpegInfo`.** Prints one line per wired Kotlin/Native target: source, license, version, dav1d, libass, and where the binaries come from (the download URL or the resolved lib directory). The provisioning decisions all happen across lazy providers at configuration time, which made them invisible; this makes them a sentence instead of a link-failure autopsy.

- **A real JVM variant, so a desktop app is one dependency line.** `jvmMain` compiled
  `unsupportedMain` until now, so every JVM consumer got a library whose every entry point threw,
  while the working JNI implementation was compiled only for Android. The jvm target builds the
  real tree now, its test source set runs the shared codec-contract suite (41 tests green over real
  FFmpeg), and the host JNI library rides inside the jar under `kiteffmpeg-native/<os>-<arch>/`,
  self-contained: the libraries the link pulls from a package manager travel with it, their load
  commands rewritten to `@loader_path` and each one re-signed, because Apple silicon refuses an
  invalidated signature with SIGKILL and no exception. `JniLibrary` tries an explicit
  `kiteffmpeg.jni.path`, then `java.library.path`, then the bundle. The JVM API dump gains
  `MediaByteSource` and the `MediaSource.open` overload that takes one, which the placeholder never
  had.
- **FFmpeg for Linux and Windows.** `buildFFmpegForLinuxX64`, `...LinuxArm64` and `...MingwX64`
  produce real trees for the first time, cross-built from the Kotlin/Native toolchains so the ABI
  matches what Kotlin/Native links against, at a reduced profile (software codecs plus zlib, no
  third-party encoder or text stack). Measured: 109 native tests pass on linuxArm64 in a container
  over the result, and the whole stack links to a PE32+ binary for Windows.
  `-Pkiteffmpeg.withDesktopTargets=true` adds the three triples to a publication instead of
  replacing its Apple and Android variants.
- **Owned stream colour and typed VP9 metadata.** Video stream snapshots now carry container/probe
  colour declarations plus typed VP9 profile, level, bit depth and chroma subsampling across both
  native and JNI builders. Nine compatible C accessors advance the C ABI from 2.5 to 2.6, the
  export set from 186 to 195 names, and the signature baseline from 201 to 210 records.
- **Owned codec-configuration snapshots for hardware decoders.** `StreamInfo.codecExtradata`
  carries a copy of records such as avcC and hvcC across both the native and JNI boundaries,
  without exposing an FFmpeg pointer. The bounded C copy helper rejects invalid sizes and advances
  the compatible C ABI from 2.4 to 2.5, the export set from 185 to 186 names, and the signature
  baseline from 200 to 201 records.
- **VideoToolbox hardware decode behind the opaque boundary (window 3).**
  Every Apple FFmpeg build (macOS, iOS device, iOS simulator) now enables the `h264_videotoolbox`
  and `hevc_videotoolbox` HWACCELs, and two C funnels carry them: `ffkmp_codecctx_use_videotoolbox`
  attaches the device context between allocation and open and installs a format negotiation that
  falls back to software when the hardware withdraws mid-stream, and `ffkmp_frame_hw_download`
  copies a hardware frame's pixels and presentation properties back to an ordinary frame. On
  Kotlin: `MediaSource.openDecoder(..., hardware = HardwareAccel.VideoToolbox)` and
  `Frame.downloadFromHardware()`, capability-honest on every platform (a build without the
  framework refuses typed at open). The JNI bridge carries both rows, so macOS JVM decodes
  through VideoToolbox too, proven by a differential contract arm that runs identically on the
  cinterop and JNI boundaries. C ABI minor 3 to 4; export names 183 to 185; signature records
  198 to 200. iOS links gain the CoreMedia/CoreVideo/VideoToolbox frameworks.
- **JVM and Android actuals over a dynamically registered JNI bridge.** The common decode,
  playback, frame, filter, sink, remux and transcode contracts now have JVM/Android
  implementations over generation-tagged opaque handles. The bridge validates the full FFmpeg
  identity before attaching the VM, maps native failures into the public typed error hierarchy,
  copies Java arrays at the boundary, and invalidates borrowed descendants when their parent
  closes. A test-only macOS arm64 dylib drives JVM contract and registration tests. The local
  Android target is `minSdk 24` and feeds `arm64-v8a` plus `x86_64` JNI libraries into the AAR
  model with 16 KiB ELF alignment and packaging-model checks. This is source and host/build
  evidence only: no jar or AAR is public, no Android playback or UI surface is claimed, and
  MediaCodec selection is only through FFmpeg named decoders such as `h264_mediacodec`.
- **A local-only mobile Apple substrate.** On an arm64 Mac, `-Pkiteffmpeg.applePhoneTargetsOnly=true` registers exactly macosArm64, iosArm64 and iosSimulatorArm64, is mutually exclusive with the standing target selectors, is accepted only by `publishToMavenLocal` and is refused by remote publication before repository work. The iOS FFmpeg tasks use the shared STANDARD software-playback set, `--disable-autodetect`, SDK zlib and SDK cross flags, with no desktop third-party stack, GPL build or hardware encode. (VideoToolbox DECODE was added to every Apple target later, by the window 3 entry below; encode remains desktop-only.) `BuildFFmpegTask`, repository path resolution, the Apple-phone selector and Local-plugin validation refuse their iOS GPL cases before tree lookup with the stable diagnostic `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.` `FFmpegSource.Local` consumes a complete `<localRoot>/<license>/<target>/{include,lib}` tree without network access, validates all six archives and headers for every wired target, links iOS with exactly zlib and puts the local macOS search path before its host fallback. Nothing was publicly published or released.
- **The FFmpeg helper layer is real C now, with its own build, tests, sanitizer runs and fuzz targets.** It used to be 949 lines of text inside `kiteffmpeg-core/src/nativeInterop/cinterop/ffmpeg.def`, which had no translation unit and therefore no object file, no test, no sanitizer run and no coverage; 19 of the 176 helpers were never called from Kotlin at all. The extraction produced `native/kitecodec-c/`: nine translation units, one per subsystem, compiled per Kotlin/Native target into a static archive that cinterop embeds, with `KC_API` on the exported helpers and `-fvisibility=hidden` on everything else. The generator and `scripts/verify-lift.sh` proved that historical move byte for byte and were then retired; these are ordinary maintained sources now. The opaque migration below subsequently changed the def and Kotlin call sites without changing the public Kotlin API.
- **The compatible half of the opaque C surface, ABI 1.1.** `kitecodec_handles.h` adds the eleven forward-declared aliases `kc_codec`, `kc_codec_ctx`, `kc_codec_par`, `kc_dict`, `kc_dict_entry`, `kc_filter_ctx`, `kc_filter_graph`, `kc_fmt_ctx`, `kc_frame`, `kc_packet` and `kc_stream`, with no FFmpeg include. The helper surface adds the seven wrappers `ffkmp_codecctx_send_packet`, `ffkmp_codecctx_receive_frame`, `ffkmp_codecctx_send_frame`, `ffkmp_codecctx_receive_packet`, `ffkmp_find_encoder_by_name`, `ffkmp_find_decoder_by_name` and `ffkmp_filter_exists`, plus the five accessors `ffkmp_media_type_video`, `ffkmp_media_type_audio`, `ffkmp_media_type_subtitle`, `ffkmp_media_type_data` and `ffkmp_media_type_attachment`. Those twelve functions were compatible additions: the export set moved from 163 to 175, comprising 169 `ffkmp_` and six `kc_` symbols, and the C ABI moved from 1.0 to 1.1. They remained dormant from Kotlin until the ABI 2.0 migration adopted them.
- **Breaking C and cinterop change: the opaque boundary is complete at ABI 2.0.** The 140 original helper declarations that named FFmpeg types now use the eleven `kc_*` aliases, `kitecodec_helpers.h` no longer supplies FFmpeg typedefs or layouts transitively, and the cinterop def parses only the helper, handle and ABI headers. Raw libav functions, constants and struct layouts disappear from the klib; eleven incomplete forward tags remain behind the aliases and Kotlin source is forbidden to name them directly. Native consumers must use the `kc_*` handles and `ffkmp_*` functions; six Kotlin implementation files migrate to those names while the public Kotlin API remains byte-for-byte unchanged. The export set stays at 175, the new 189-record signature ratchet holds declaration shape, and nothing has been publicly published.
- **Required C arguments now fail with `AVERROR(EINVAL)` instead of reaching FFmpeg as invalid pointers.** The sixteen guarded entry points are `ffkmp_frame_get_buffer`, `ffkmp_codecpar_from_context`, `ffkmp_codecpar_copy_for_mux`, `ffkmp_fmt_open_input`, `ffkmp_fmt_find_stream_info`, `ffkmp_fmt_read_frame`, `ffkmp_fmt_alloc_output2`, `ffkmp_fmt_write_frame`, `ffkmp_codecctx_open`, `ffkmp_codecctx_from_par`, `ffkmp_graph_build_video`, `ffkmp_graph_build_audio`, `ffkmp_graph_build_video_multi`, `ffkmp_graph_build_audio_multi`, `ffkmp_graph_send` and `ffkmp_graph_receive`. Six nullable controls preserve the intentional FFmpeg meanings: a NULL audio-filter description selects `anull`; a NULL graph-send frame signals EOF; a NULL mux packet flushes; a NULL output-format name permits inference; a NULL codec lets `ffkmp_codecctx_open` use the codec remembered by its context; and a NULL path is valid for `ffkmp_fmt_alloc_output2` when a nonempty format is supplied.
- **New public types for the identity gate.** `FFmpegIdentity` and `FFmpegLibraryIdentity` carry the whole report as Kotlin values (per-library header and runtime version triples, verdicts, both licence strings, the provisioning sentence, whether the bypass was used); `FFmpegError.IncompatibleFFmpegRuntime` is the typed failure a rejection throws, with the identity attached; and `Versions` gains per-library header/runtime accessors (`avutilHeader` and siblings). All are in the committed API dump.
- **An FFmpeg header versus runtime identity gate, called before anything allocates.** In the direction that matters, older headers against a newer runtime, every symbol resolves and the link succeeds while measured field offsets are wrong and 48 of the helpers read or write through one of them; reconnaissance reproduced wrong values read and then a SIGSEGV inside `av_frame_free`. A generated unit inside the same C compilation freezes the six `LIB*_VERSION_INT` macros, and `kc_init` compares them to the six `*_version()` functions under `pthread_once`. Policy: major must be equal, runtime minor at or above header minor, micro reported and never fatal, plus a cross-library `*_configuration()` agreement check that catches a mixed install. The verdict carries a report with both licence strings, the provisioning sentence and the runtime configuration, and KitePlayer surfaces a rejection as an ordinary typed playback error rather than a crash. `KITECODEC_FFMPEG_ABI_BYPASS=1`, and only that exact value, downgrades a rejection to a warning printed once per process for diagnosis; the report records that it was used.
- **`ffmpeg.version` in the Gradle plugin DSL is validated.** A consumer writing `version = "n7.1"` with the default prebuilt source used to download FFmpeg 7.1 archives and link them against a klib whose stubs were compiled against n8.0 headers, which links cleanly and corrupts at runtime. Configuration now fails with a sentence naming both refs and the two ways out. One build-time assertion also holds the `n8.0` expectation in `BuildFFmpegTask`, the plugin and `publish.yml` to the same value, and to the vendored checkout when it is present.
- **A committed klib ABI baseline and coupling ratchets.** `kiteffmpeg-core/api/kiteffmpeg-core.klib.api` exists and `apiCheck` runs in the macOS CI job, so an accidental public signature change now fails a build instead of shipping. `native/kitecodec-c/coupling-baseline.txt` plus `./gradlew checkCinteropCoupling` require zero direct FFmpeg imports, calls and named raw structs from Kotlin while reporting opaque `ffkmp_*` traffic separately. The C signature baseline independently holds all 189 public declaration records, so an alias retarget or parameter change cannot hide behind an unchanged symbol name.
- **A C test suite, three sanitizer variants and six fuzz targets.** Seven suites run 274 cases per variant and 822 across plain, ASan and TSan. They cover the 39 ownership helpers for exact allocation pairing under a Mach-O interposer, I-12's two argument guards, all 12 fixed buffer sites and the four size-taking copy helpers at their limit and one byte past it, the arithmetic helpers at their overflow vectors, `ffkmp_strerror`'s thread affinity, the per call `SwsContext` in `ffkmp_frame_convert_pixfmt`, one case per identity verdict against doctored header trees, and the 22 cases in `test_args`. Each of the six suites that preceded `test_args` was proved load bearing by mutating copies of the sources and requiring the failure. The six fuzz targets cover every C entry point that parses a caller's string; they run as a corpus replay over 103 committed textual seeds in every gate, and a Linux CI job is configured to build them as libFuzzer targets but has not run yet, so no coverage-guided search has happened so far.
- The low-level playback layer, behind the `@KiteFFmpegLowLevelApi` opt-in, built for and consumed by [KitePlayer](https://github.com/yuroyami/KitePlayer): `MediaSource.openPacketReader` (owned packets, transactional stream selection, `avformat_seek_file` with a real min/max window and flag set), `MediaSource.openDecoder` (one independent decoder per stream: `send`/`receive`/`flush`/`isDrained`; the first exposure of `avcodec_flush_buffers` anywhere in the binding), `Frame.withPlanes` (zero-copy plane pointers with row pitches; video frames only, audio rejects with a clear message) and `Frame.hardwareSurface`.
- Overflow-safe timestamp helpers on the low-level types: `Packet.ptsMicros`/`dtsMicros`/`durationMicros` and `Frame.ptsMicros`/`durationMicros`, all through the 128-bit `av_rescale_q`, null on `AV_NOPTS_VALUE`.
- Colour metadata on `FrameInfo` (`ColorInfo`: matrix, primaries, transfer, range, chroma siting, with the conventional SD/HD guess applied at frame height), plus frame duration, keyframe flag, sample aspect ratio and `isHardware`.
- On `StreamInfo`: `Disposition` flags, `rotationDegrees` from the display matrix, per-stream start times, and `channelLayoutMask` (also on audio `FrameInfo`), the native channel order mask so 5.1 side and 5.1 back are distinguishable.
- `MediaSource.isSeekable`, read from the real I/O context instead of assumed.
- `MediaSource.startTimeMicros`: where a container's timeline begins. Raw stream/frame timestamps are absolute and include it; every parameter KiteFFmpeg takes (`seekMicros`, `extractFrame`, trim bounds) is content-relative. Exposed so callers can convert between the two.
- Vendored FFmpeg profile now also builds `mpeg4` (encode + decode), `flac`, and the `pcm_s16le`/`s24le`/`f32le` encoders, the dependency-free baseline every profile shares. Previously the LGPL build had **no** video encoder except libsvtav1 and mjpeg, and the already-enabled `wav`/`flac` muxers had no encoder to feed them.
- Vendored FFmpeg profile gained the MPEG-TS muxer/demuxer, the `matroska_audio` muxer (`.mka`), and the `http`/`tcp` protocols. (`https` still needs a TLS backend and is not built; see the note in `BuildFFmpegTask`.)
- CI job `vendored-lgpl`: builds the shipped LGPL profile from source and runs the unit tests, native tests and full e2e against **that**, not against Homebrew's FFmpeg.
- Full single-pass `demux → decode → filter → encode → mux` pipeline for video and audio (`Transcoder.transcode`): frame-exact trim, `videoCopy`/`audioCopy`/`subtitleCopy` stream copy, container metadata, typed progress (`TranscodeProgress`).
- `Frame.ofVideo` / `Frame.ofAudio`: build frames from raw bytes for generative pipelines (images-to-video, synthesized audio).
- `MediaSink.open(path, format, options)`: explicit container selection and muxer private options (`movflags=+faststart`).
- Semantic error hierarchy: `FFmpegError` now classifies `AVERROR_*` codes into `FileNotFound`, `PermissionDenied`, `InvalidData`, `EncoderNotFound`, `DecoderNotFound`, `MuxerNotFound`, `FilterNotFound`, and more (raw code retained; unmapped codes fall back to `AvError`).
- `Rational`: `Comparable`, `plus`/`minus`/`div`/`unaryMinus`, overflow-safe construction and scalar multiply.
- `StreamInfo.metadata` (per-stream tags, `language`, `title`, …), 10-bit pixel format constants (`yuv420p10le`, `p010le`, …), `s64`/`s64p` sample formats.
- Explicit API mode + `@Throws` annotations across the public surface; kotlinx binary-compatibility-validator wired (klib mode).
- Maven publishing (vanniktech plugin, Central Portal, signing, Dokka javadoc jar) for `kiteffmpeg-core`; Gradle Plugin Portal metadata + a TestKit functional test for `kiteffmpeg-gradle-plugin`.

### Changed
- **BREAKING: the `ffmpeg.dav1d` toggle is now a contract enforced in BOTH directions.** Before, `if (archive.exists()) linkerOpts("-ldav1d")` meant the tree decided and the toggle only validated one way: a consumer whose tree carried dav1d linked it without one line of their build saying so, and `dav1d = false` silently linked it anyway. dav1d is compiled into `libavcodec` when FFmpeg itself is built, so a link-time toggle can neither add nor subtract it; what it now does is refuse a mismatch loudly at task realisation, with the one-line fix in the message. Consumers whose Local tree carries dav1d must state `ffmpeg { dav1d = true }` from this release on.

- **Every public entry point can now refuse to start.** `kc_init` runs first inside 15 C entry points, so a mismatched FFmpeg runtime produces a typed error naming what disagreed instead of undefined behaviour later. Nothing else about the failure behaviour of the Kotlin API changed, and micro version differences never reject.
- **15 helper symbols were deleted, and the `archived/` directory with them.** The 15 were exported surface that nothing imported; in a versioned library that is a compatibility promise nobody meant to make. `scripts/check-deleted-surface.sh` proves neither repository refers to any of them. Safe because nothing has ever been published from here and there are no tags. The six def files under `nativeInterop/cinterop/archived/` were referenced by no build file and duplicated 176 helper names, which made every later grep report false hits.
- The vendored FFmpeg build and the plugin now agree on their expected FFmpeg ref by assertion rather than by a comment asking three files to be kept in sync.
- **Breaking (behaviour):** `MediaSource.seekMicros`, `extractFrame`, and `Transcoder`/`Remuxer` trim bounds are now consistently **content-relative**. They previously mixed the two conventions, `extractFrame` compensated for a container's start time, `Transcoder`/`Remuxer` did not, so trimming an MPEG-TS capture silently shifted the window by the container's start (~1.4s, and more with an offset).
- `MediaSink.addVideoEncoder` now converts frames whose pixel format differs from the encoder's instead of failing. An unfiltered transcode of a 10-bit source, or a filter chain without a trailing `format=`, used to die with a bare `EINVAL`. Frame *dimensions* still throw, a size mismatch is a config error, and silently rescaling would hide it.
- `MediaSink.close()` now drains every encoder before writing the trailer, as its documentation always claimed. A sink closed without an explicit `finish()` used to discard whatever the encoder still had buffered.
- `AudioEncoder.sampleRate` / `.channels` now report what the encoder actually opened with rather than what was requested.
- The sample and `scripts/e2e.sh` pick their video encoder by probing the linked FFmpeg instead of hard-coding `libx264` (GPL-only). `kiteffmpeg-sample info` prints the choice for scripts to read.
- **Breaking:** `FFmpegError` no longer extends `RuntimeException`. It is a plain sealed hierarchy carried by `FFmpegException` (the only thrown type).
- **Breaking (contract):** frames emitted by `decodedFrames`/`decodeStreams`/`FilterGraph.process` are now OWNED by the collector, safe to buffer (`toList()`, `buffer()`), and each must be closed. Callback-style outputs (`feedInput`) keep the callback-scope rule.
- `Rational.Zero.inverse` and division by zero now throw instead of constructing an invalid rational.
- Concurrent misuse of one `MediaSource` (second decode flow, seek/close mid-decode) is rejected with `IllegalStateException` instead of racing native code.

### Security
- **The JNI identity report accumulated into a fixed buffer with no bound at all.** `kj_abi.c` used `off += snprintf(buf + off, sizeof buf - (size_t)off, ...)`, and `snprintf` returns the length it WOULD have written: once `off` passed the buffer size, `buf + off` pointed outside the array and `sizeof buf - (size_t)off` wrapped to an enormous `size_t`, so the next append wrote past the end with a length that disabled every bound the call had. Seven of the report's fields are strings of unbounded length; the reachable maximum today is about 2.3 KB against 4 KB, so this was latent rather than live. `kj_append.h` now refuses rather than truncates, because the Kotlin side splits the report into a fixed 31 fields and a short one parses into wrong values instead of failing. It is the same guard `helpers_filter.c` already applied by hand, in one place, and it carries no `jni.h` so a host suite can compile the shipped arithmetic: `test_append` is the eighth C suite, and it caught a real bug in the fix while it was being written, since `vsnprintf` writes the part that fits before the overflow is detectable.
- **A filter value reached the graph unescaped.** `AudioFormat.compile()` interpolated `sample_fmts=$it` raw, one line above a neighbour that routed its value through `escapeFilterValue`, so `AudioFormat(sampleFormat = "fltp,volume=0")` silently appended an entire extra filter to the graph. Now escaped like every other typed value. The compiled output for ordinary values is unchanged, because `escapeFilterValue` quotes only values carrying a structural character.

### Fixed
- **Apple builds never compiled the AV1 hardware decode path.** `--enable-hwaccel` named only `h264_videotoolbox` and `hevc_videotoolbox`, so `av1_videotoolbox` was absent from every macOS and iOS tree. It is pinned now, and the two configure goldens moved with it. **This alone does not give you hardware AV1, and it is not claimed to:** an hwaccel attaches to a decoder, `libdav1d` is an external decoder that carries none, and `avcodec_find_decoder(AV_CODEC_ID_AV1)` returns `libdav1d` ahead of FFmpeg's native `av1` on every build that has both. Reaching VideoToolbox needs a decoder chosen by name plus a software fallback policy, which this library does not have yet. Pinning the hwaccel is the half that can be done without that work, and it is the half that has to exist first. Untested on AV1 silicon: the proving machine is an M2, which has none.
- **cinterop embedded a stale helper archive during incremental development.** The cinterop task uses its own up-to-date check, over the def and the headers, and it does not track a library the def merely names. So editing only a `.c` body rebuilt the archive and left the previous one inside the klib, with the configuration cache on or off. The archive is now declared an input of the cinterop task. Measured both ways: before the fix the embedded archive stayed at one digest while the built one moved, and after it the same edit re-executes cinterop and the object inside the klib disassembles to the new body. A missing archive always failed loudly, so this only ever affected a local edit, which is what every sub-phase of this work does.
- **A wrong-architecture archive could be embedded silently.** A Linux ELF archive placed where the macOS arm64 one belongs was embedded without complaint and failed at the consumer's final link with `archive member not a mach-o file`. The compile task's output directory is keyed by the konan target name and never shared, and the task asserts the produced object's architecture before archiving.
- The def declared `linkerOpts` for macOS, Linux, mingw and Android but not for iOS, although three iOS targets are registered, so the six `-l` flags never reached an iOS link. Added. The local mobile Apple proof now exercises the arm64 device and simulator trees; no CI or public-artifact result is inferred.
- **A stack buffer overflow reachable from public filter descriptions.** Both audio filter-graph builders accumulated `snprintf` results into a fixed buffer and checked the total only after every append; on truncation `snprintf` returns the length it wanted, so a long description moved the write pointer past the stack array with a wrapped remaining size. Every append is now bounds-checked before the next pointer is computed, and a length test drives descriptions from 0 bytes to 1 MB through both builders.
- `openPacketReader` left unselected streams on `AVDISCARD_ALL` after the reader closed, so a later batch decode of those streams silently returned nothing. Discard flags are restored when the reader closes and on the open failure path.
- `FilterGraph` multi-input feeding could spin forever when the graph needed a different input pad (EAGAIN with no progress now throws a typed error naming the starved graph), and `drainTo` did not release its landing frame when a callback declined to; it now always does.
- `MediaSink` stepped missing and non-monotonic audio timestamps by the current frame's sample count instead of the previous frame's, so frames of 960 then 1024 samples started at 0 and 1024 rather than 0 and 960.
- `seekMicros` while a `PacketReader` owns the demuxer cursor now throws instead of moving the cursor out from under it.
- Reading any getter of a closed `Packet`, or sending one to a decoder, dereferenced freed native memory and returned plausible garbage; both now throw.
- **The vendored static path had never linked.** `ffmpeg.def` names only the six libav* libraries, which is all a shared FFmpeg needs; a static `libavcodec.a` also needs every third-party archive it draws symbols from named at the final link. The library's own build never did that, so `native-libs/` was unusable, `_svt_av1_enc_init`, `_png_set_tRNS_to_alpha`, `_gr_*`, and the CoreGraphics/CoreText/VideoToolbox frameworks all went unresolved. `BuildFFmpegTask` now bundles those archives into the tree and `StaticLinkFlags` names them.
- **The vendored profile enabled no bitstream filters at all.** libavformat inserts these itself during stream copy, so their absence produced a corrupt output file rather than an error, copying h264 from MPEG-TS into mp4 yielded a file ffprobe rejects with "No start code is found". `extract_extradata`, `aac_adtstoasc`, `h264/hevc_mp4toannexb`, `vp9_superframe` are now built.
- **`eq` and `boxblur` are `deps="gpl"` in FFmpeg**, so the LGPL profile silently dropped them, including `eq`, the filter every example and the e2e suite reached for. They moved to the GPL flavour; the examples now use `hue=b=…`, which exists everywhere.
- `.m4a` and `.mka` map to FFmpeg's separate `ipod` and `matroska_audio` muxers. Neither was enabled, so writing either extension failed with "Unable to choose an output format".
- Decoding no longer aborts the whole file on a recoverable error. Every seek into a stream carrying its parameter sets in band (MPEG-TS) lands before the next SPS/PPS, so the first packets after it decode to nothing; that used to throw instead of being skipped, which made trimming a broadcast capture impossible.
- Seeking before a decode now aims deliberately early (`seekForDecode`). Indexless containers resolve a seek by searching byte positions and can land past the keyframe they aimed at, after which the decoder emits nothing until the next IDR, a whole GOP of requested content silently dropped.
- `BuildFFmpegTask` verified nothing after `make install`. A prefix GNU make cannot parse (any path containing `#`) truncates `libdir` to empty, so the install becomes a silent no-op that still exits 0, and `FFmpegPaths` then falls back to the system FFmpeg, exactly the "publication silently drops a target" failure the publish guard exists to prevent. It now copies source to a unique hash-free temporary tree, excluding `.git` and every `build` subtree while preserving executability, and runs configure, make and install only there. It normalizes the first `ffbuild/config.log` line into the installed `lib/kiteffmpeg/ffmpeg-configure.txt`, requires that single-line provenance record while verifying the six archives and headers in scratch and in a Java/NIO sibling staging copy, and only then replaces the final tree. Packaging reads that installed record alone and refuses missing, blank, multiline or obsolete unavailable evidence. Failure retains scratch for diagnosis and never replaces the last good output.
- `kiteffmpeg-sample` ignored `-Pkiteffmpeg.ffmpeg.license`, always resolving the LGPL tree even when the library it links was built against the GPL one.
- **The vendored macOS FFmpeg build never configured.** It died on `ERROR: libmp3lame >= 3.98.3 not found` even with the dependency installed: `BuildFFmpegTask` passed no `--extra-cflags`/`--extra-ldflags` for the Homebrew prefix, and lame ships no pkg-config file. It now passes both, plus `PKG_CONFIG_PATH`.
- **`--enable-videotoolbox` never produced a VideoToolbox encoder.** Under `--disable-everything` each encoder must be named explicitly; `h264_videotoolbox`/`hevc_videotoolbox` were not, so vendored Apple builds advertised hardware encode and had none. (The Android profile always listed `h264_mediacodec` correctly.)
- Trim windows on containers that do not start at zero (MPEG-TS) selected the wrong range, see the seek/trim change above.
- `StreamInfo.codec` reported the *decoder's* name, so an AV1 stream read as `libdav1d`, Opus as `libopus`, and any stream with no decoder compiled in as `codec_<number>`. It now reports the codec's canonical name.
- `Frame.encodeImage()` overwrote the source frame's timestamp with 0 when no pixel-format conversion was needed (it encodes the caller's own frame in that path).
- `Transcoder` produced **no output file at all**, and no error, when the trim window selected nothing: the muxer header is written lazily on the first packet. It is now written eagerly, as `Remuxer` already did.
- Non-monotonic audio timestamps were bumped by one tick. With a codec time-base of `1/sample_rate` that collapses a 1024-sample AAC frame into a single sample; the bump is now the frame's own duration.
- Encoder packets were rescaled from the time-base requested in the spec rather than the one `avcodec_open2` settled on, which the muxer stream was already (correctly) told about.
- Output timestamps could go negative: streams share one rebase origin, so a stream starting before the claiming one (AAC priming samples) fell below zero. The muxer's `avoid_negative_ts` policy is now pinned to `make_zero`, which shifts every stream by the same amount and keeps the A/V offset intact.
- `FFmpegPaths` (and the Gradle plugin's system-FFmpeg lookup) matched library paths by existence, never by architecture, so `macosX64` on an Apple-silicon Mac resolved to the arm64 Homebrew libraries and `linuxArm64` on an x64 host to the x86_64 ones. System resolution is now restricted to the host's own target, with an error that says why.
- `FetchFFmpegTask` wrote a shared Gradle-user-home cache with delete-then-move and no locking, so concurrent builds could pull the tree out from under a running link. It now holds an exclusive lock for the whole fetch and marks completion with a file written last.
- `FetchFFmpegTask` threw a `NullPointerException` on a relative redirect `Location` instead of resolving it against the request URL.
- Muxer crash on header-write failure: a failed `avformat_write_header` no longer leads `close()` into `av_write_trailer` on a headerless context.
- A/V desync after trims: all streams of one sink now rebase timestamps against a single shared origin instead of each stream's own first timestamp.
- `Remuxer.remux` and copy-only transcodes are now cancellable (cancellation checked every packet).
- Filter graphs: `EAGAIN` from `av_buffersrc_add_frame` retries the same frame instead of silently dropping it; feeding a closed graph throws instead of use-after-free.
- Trim end detection gates on dts (monotonic) instead of pts, B-frame reordering no longer stops the demux a GOP early; graph-buffered frames past the trim end are filtered at the encoder.
- `extractFrame` accounts for nonzero container start times (MPEG-TS).
- `Rational.inverse` normalizes its result (no more negative denominators).
- Released FFmpeg zips now bundle LGPL/GPL license texts, a BUILD-INFO provenance record, and the source URL (LGPL compliance); release assets are attested and checksummed.

### Baseline surface

Everything below grew from `0.0.1` and is listed for orientation rather than as a change.

- `MediaSource`: probing (`streams`, `metadata`, `durationMicros`), `decodedFrames`/`decodeStreams` frame flows (EAGAIN-correct, single demux pass for multiple streams), `seekMicros`, `extractFrame` + `Frame.encodeImage` thumbnails.
- `MediaSink`: `addVideoEncoder`/`addAudioEncoder` (shared EAGAIN-correct encode core, monotonic zero-based pts, per-encoder `options`), `addCopyStream`, `setMetadata`.
- `FilterGraph`: single- and multi-input video/audio graphs (overlay, amix), encoder-ready audio output, `setOutputFrameSize` for AAC's 1024-sample framing.
- `Remuxer.remux`: lossless container rewrite with keyframe-snapped trim.
- Capability probing (`FFmpeg.versions`, `hasEncoder`/`hasDecoder`/`hasFilter`, `buildConfiguration`).
- Hardware encode via `h264_videotoolbox` (verified on macOS arm64; `allow_sw` for VMs). Android exposes FFmpeg MediaCodec names, but this changelog does not turn them into an Android playback or encoder qualification.
- FFmpeg build tasks (`buildFFmpegFor<Target>[Gpl]`): vendored static FFmpeg cross-compile, LGPL by default with a GPL opt-in flavour, Android NDK MediaCodec profile.
- `kiteffmpeg-gradle-plugin`: provisions prebuilt/system FFmpeg for consumer builds with SHA-256 verification (in-repo; not yet published).
- Documentation site (MkDocs Material) and CI (macOS / Ubuntu / Windows unit + e2e, plus a vendored-LGPL job that exercises the shipped profile).

### Known gaps
- Remote publishing has produced no artifact; Maven Central still needs a real release run with Central Portal credentials and signing in CI. The KLIB and JVM API dumps are committed and `apiCheck` guards them locally.
- No public JVM runtime jar or Android AAR yet; the JNI/AAR source and packaging proof is local,
  and there is no Android playback, physical-device, Compose or Android View qualification.
- No custom AVIO (in-memory/pipe sources and sinks), no chapter read/write, no subtitle decode (copy only). Channel layouts expose the native order mask, but named/custom layout objects, `extended_data` access and more than 8 channels remain absent.
- No hardware decode / hwframes pipelines; no bitstream filters on the copy path (MPEG-TS Annex B unsupported).
- iOS targets lack CI verification.

## [0.0.1] - 2026

Initial development baseline: project structure, consolidated FFmpeg cinterop binding (`ffmpeg.def` + `ffkmp_*` helpers), and the first working decode/encode paths on macOS arm64. Everything listed under [Unreleased] grew from here; treat 0.0.1 as the "it exists and transcodes" milestone rather than a supported release.

[Unreleased]: https://github.com/yuroyami/KiteFFmpeg/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/yuroyami/KiteFFmpeg/releases/tag/v0.0.1
