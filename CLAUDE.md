# KiteFFmpeg, for whoever works in this tree

KiteFFmpeg is a Kotlin Multiplatform binding to FFmpeg's libav* libraries, built for thirteen
targets. The player that consumes it lives in the sibling checkout, `../KitePlayer`. Open work
for both is in GitHub Issues, one tracker per repository.

`CONTRIBUTING.md` has the build prerequisites, the ground rules and the gate. This file has
only what reading the code or running the gate would not teach you.

## How work happens here

- Work on `main`. Never create a branch without asking. Commit locally, never push. The owner
  pushes, publishes and cuts every release.
- Commit subject is one imperative sentence about the outcome. Short prose body. No trailers.
- When the tree contradicts an issue, stop and report it. Do not improvise the issue back into
  truth. Prose drifting from the tree is this project's measured failure mode.
- A design act is its own commit. Deciding a public API shape and executing it never happen in
  the same breath.
- Size estimates rot the same way claims do. An estimate made behind a blocker is a guess about
  what the blocker hides; re-size when the blocker falls.

## Gotchas

Each line is something that bit someone. Delete a line when it stops being true.

### Build and toolchain

- `apiCheck`, `apiDump` and every cinterop call need `-Pkiteffmpeg.hostTargetsOnly=true` on a
  machine with one FFmpeg tree; a bare `apiCheck` compiles all thirteen targets and fails on the
  target header alone, which looks exactly like a real break, and re-dumping without the flag
  writes a thirteen-target baseline and turns CI red (it has happened once).
- Publishing for the sibling player needs all three flags together,
  `-Pkiteffmpeg.phoneTargetsOnly=true -Pkiteffmpeg.withDesktopTargets=true -Pkiteffmpeg.jni.linux=true`,
  because a publish regenerates the root module metadata and a host-only publish deletes the
  ios, linux and mingw variants from it.
- `-Pkiteffmpeg.jni.linux=true` needs a running Docker daemon: it extracts JDK headers from a
  container, and without it the jar carries no Linux JNI libraries and the Linux JVM tests
  cannot run.
- FFmpeg's configure cannot handle a `#` anywhere in its path, so the build tasks build in the
  system temp directory; Gradle's own `temporaryDir` is inside the project and fails.
- `--disable-postproc` does not exist in the vendored FFmpeg line and makes configure fail
  outright; postproc is already off by default.
- `--disable-asm` also disables SIMD, because SIMD is gated as an architecture extension, so a
  build labelled "simd" that carries the flag is a plain build and its measurement is a lie.
- The Android NDK is probed from `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`,
  `ANDROID_NDK_LATEST_HOME` and two default directories only, never from `sdk.dir` in
  `local.properties`, so an SDK outside the standard paths fails every Android target with
  "Android NDK not found" until the variable is exported.
- The NDK version is chosen by string sort in three build tasks here, which is right for the
  NDKs installed today and wrong on a two-digit minor, because `29.10` sorts below `29.2`.
- A rebaked FFmpeg tree does invalidate its consumers, and an earlier note claimed the
  opposite: the wiring is deliberate, documented beside the cinterop block, and reading a
  second invocation's UP-TO-DATE as evidence of a break is how the wrong claim was made.
- `symbol-audit.sh` prefers the Gradle-built archive and only falls back to the host one when
  Gradle produced nothing, so running `build-host.sh` and then the tool reports on whatever
  Gradle last built, which can be days old.
- `run-c-tests.sh` never builds anything, so on its own it proves nothing about a source
  change; run `build-host.sh <variant>` first, every time.
- Moving or renaming the checkout breaks the prebuilt C test binaries: they carry an absolute
  rpath to their interpose library from link time, so every suite aborts naming the old path,
  which reads like a broken test and is a stale binary.
- A Gradle compile task with no sources prints `NO-SOURCE` and exits zero, so "the target
  compiles now" can mean "there was never anything there to compile".
- Adding a dependency can poison Kotlin's incremental-compilation cache, and the failure names
  a stdlib function and reads like a compiler bug in your own code; delete the module's
  `build/kotlin` and build again.
- `./gradlew ... | tail` reports the exit code of `tail`, so a failed build can look like a
  successful one; pipe to a file and check the log for BUILD FAILED.
- Scraping every Gradle configuration gives a load-dependent answer, because which
  configurations are realised depends on the rest of the task graph; only `api`,
  `implementation`, `compileOnly` and `runtimeOnly` can reach a POM.
- The atomicfu Gradle plugin is banned in every module: its bytecode transform registers a task
  depending on `androidMainClasses`, which the Android plugin's multiplatform library variant
  does not create. The library dependency itself is fine.
- The two macOS CI jobs deliberately both build FFmpeg on a cold cache, one running the
  ratchets and one building the real reduced permissive profile users get; merging them was
  refused because independent failure signal is the point.
- CI fetches this repository's own prebuilt static FFmpeg trees, checksum verified. A
  distribution's FFmpeg cannot be linked by Kotlin/Native on a modern Linux, and the common
  third-party builds are shared-only and useless for a static embed.
- Two `kiteffmpeg-gradle-plugin` functional tests fail on a clean checkout and always have:
  `kiteffmpegDslConfiguredAfterKotlinBlockIsSeenByTasks` and
  `missingLicenseChoiceFailsConfigurationWithInstructions`. Ignore them, fix nothing about
  them, never let them block a gate.
- `recipeFingerprint` must stay idempotent, because the check fingerprints an
  already-fingerprinted set on the way back out; a non-idempotent token silently drops from the
  expected side and every tree reports stale.
- The macOS deployment floor is one constant in the FFmpeg build task, set to 12.0 because the
  Kotlin/Native compiler imposes it, and it is read by both macOS FFmpeg branches and both
  macOS C targets.
- FFmpeg bumps its library sonames only at a major release, so moving along a minor or point
  line is ABI-safe while a major move breaks all seven at once.
- Two FFmpeg pins exist and they are independent decisions: the vendored library that gets
  linked, and the host binary that generates test fixtures. Conflating them once already
  produced a wrong recommendation.
- `mavenLocal` accumulates per-target artifacts across publishes made with different flags, and
  a narrower publish neither refreshes nor removes the others, so judge freshness only for the
  variants the run actually published.
- One cheap check reads bytes rather than the build's opinion of them: unzip the published
  cinterop klib and grep it for the expected FFmpeg version string.
- The generated wasm binding has two copies, the generator's output and the committed one, and
  `checkWasmBindingMirror` keeps them identical; if it fires, regenerate and commit both rather
  than hand-editing the committed copy.

### Kotlin and language

- A property named `field` is unreachable by that name inside any accessor of the same class,
  because `field` is the backing-field keyword there; the compiler then reports "Property must
  be initialized" on a completely different property.
- A backtick test name containing a comma compiles on the JVM and breaks every Kotlin/Native
  target with "Name contains illegal characters", so a green JVM run says nothing; it has bitten
  twice.
- Kotlin/Native creates and then permanently disables the Linux test tasks on a macOS host, so
  naming them is green by definition; Linux evidence is the container script or the CI Linux
  job, and Windows native evidence on a Mac is a link claim only.

### The web target

- Kotlin/Wasm has no bulk typed-array bridge: naive per-byte crossings run at roughly 96,000
  calls per second of audio and killed the first web IO path, so cross per chunk with the tight
  loop living in JavaScript.
- The latin1 pack trick corrupts bytes over 0x7F if anything encodes the string as UTF-8 in
  transit; the 0 to 255 ramp test exists for exactly that and must never be weakened to ASCII.
- A per-pixel conversion loop on the web is about 5x slower than the same loop in JavaScript and
  about 10x slower than FFmpeg's own scaler inside the module; convert in C, beside the decoder.
- A 64-bit integer across a JavaScript function boundary needs `WASM_BIGINT` and arrives as a
  JavaScript BigInt, and the convenience call helper has no type spelling for it, so call the
  export directly; a silent truncation there corrupts every timestamp.
- Exporting all 196 binding entry points defeats dead-code elimination: the raw module is 4x
  bigger and the gzipped one only about 6% bigger, so judge web size gzipped.
- Webpack rewrites a dynamic import at build time, so the loader fails inside a bundler with
  "Cannot find module" even though the file serves; bundled applications use the attach path
  instead.
- Without cross-origin isolation headers, importing the threaded artifact hangs rather than
  erroring, which is why the default artifact stays single-threaded and why a feature detect
  must run before the import.
- C struct fields are read from JavaScript by byte offset, and those offsets come only from the
  committed generated layout file; a wrong offset reads the neighbouring field and answers
  something plausible.
- `-fPIC` is meaningless for the web target and the compiler warns about it.
- `runBlocking` does not exist on the web target because there is no thread to block, so a
  shared test written with it will not compile there; the fix is `runTest`, not moving the test
  to a narrower source set, because narrowing silently removes it from every target that no
  longer sees it.
- A browser test that runs longer than two seconds is killed rather than failed, because the
  test runner's per-test default is 2000 ms and Kotlin does not raise it; every module running
  browser tests needs a timeout config file copied from the one that has it.
- The browser half dies under concurrent load and reports "Test running process exited
  unexpectedly", naming whichever test was in flight, which reads like that test crashed;
  re-run it alone before believing it.

### Media behaviour

- FFmpeg's audio encoder segfaults on a frame whose channel count or sample format does not
  match it: `avcodec_send_frame` reads using the encoder's idea of the geometry and runs off the
  end of the buffers. Both mismatches crashed at the same address when measured against the aac
  encoder. A sample-rate mismatch is the quiet half of the same hole, accepted silently and
  encoded wrong. `requireEncodableAudio` refuses all three before FFmpeg sees the frame, and
  any new path reaching an audio encoder owes the same check.
- The neighbouring encode surfaces were probed at the same time and are clean, so do not
  re-check them on a hunch: the frame factories refuse an undersized buffer with a typed error
  naming the geometry they wanted, the video encoder refuses a size mismatch and converts a
  format one, and the filter graph refuses a frame that does not match it.
- Never apply the fast-seek format flag, and never pass demuxer options from a config map
  without an allowlist: MP3 seeking is correct only when the table-of-contents option is off and
  fast seek is unset, together.
- `avcodec_find_decoder` for AV1 returns the software dav1d decoder ahead of the native one in
  every consumer build, so hardware AV1 can never engage without choosing a decoder by name.
  That makes hardware AV1 a policy problem, not a hardware problem.
- FFmpeg's `fd:` protocol dups the descriptor but never rewinds it, and a POSIX dup shares the
  file offset, so reopening a descriptor-backed item mutates the caller's descriptor.
- FFmpeg polls the interrupt callback only inside stream-info discovery and the URL protocol
  loop. The HLS header reader leaves the callback zeroed on its sample-AES and subtitle-context
  branches, so child contexts need the callback copied explicitly.
- `PacketReader.reselect` is a committed, tested primitive with no caller in the player on
  purpose: the engine's all-lanes subtitle cache made the SPI member unnecessary and it was
  deleted. Do not re-add the SPI half without its caller.

## Decisions already made

Do not reopen these without new evidence.

- FFmpeg is the one media truth. No platform demuxers or decoders as a source of truth;
  hardware acceleration only as FFmpeg-internal decoders and hwaccels, with software fallback.
- No new mandatory native libraries. Kotlin, or shader source we author, first. A native library
  only as an optional module when no Kotlin path can exist, or when correctness parity demands
  it. Verdicts already given: libxml2 never, because manifests parse in Kotlin; mbedtls and curl
  rejected, because vendored crypto is a recurring security duty and TLS comes from the
  operating system; libplacebo rejected as a dependency, because its viewer-visible value is
  roughly 150 lines of shader we can author and it cannot follow the engine to the web.
- The C surface keeps the Java bridge adapter, the ABI and identity probe, the `get_format`
  callback, and FFmpeg itself. Everything that is a one-line forward becomes direct interop. The
  goal is no redundant C, not no C.
- Gradle artifact checksum verification is off, and the reason is measured: generating the
  metadata recorded 486 components from one JVM compile and the next task failed on a detached
  configuration that Kotlin/Native and the Node setup resolve through and the generator never
  sees. CI also runs on three operating systems, each resolving its own toolchain artifacts, so
  a file written on one host cannot carry the other two. `scripts/check-dependency-hygiene.sh`
  guards what can be guarded instead. Reopen only with a way to merge metadata per host.
- Native Linux and Windows have no https: those targets have no operating-system TLS to
  delegate to and no output backend. Desktop rides the JVM, which has https.
- A composite Gradle build was declined. The twin repositories resolve through published pins,
  or through an explicit opt-in to the local Maven repository.
- A diagnostic bypass of the FFmpeg identity gate exists on purpose: opt-in only, it warns once
  naming the exact mismatch and records itself in diagnostics. An unbypassable gate turns a
  false rejection into an outage inside a consumer's product.
- Splitting this library into one module per concern was declined. Payload weight lives in the
  native profile rather than in Kotlin modules, shared types drag the mass into a base module
  anyway, and thirteen targets multiplied by several modules multiplies the configuration drift.

## Facts about the pair of repositories

- One product, two repositories. The player is `../KitePlayer`. Each has its own issue tracker
  and neither has a private planning file.
- There is no KiteFFmpeg Gradle plugin, and consumers need no build script. It died when FFmpeg
  moved inside the published klibs, so a dependency line is the whole integration. Any note
  saying a plugin supplies the link-time search path predates that and is wrong.
- Release tags carry all 22 prebuilts, eleven triples times two flavours. macOS uses the
  portable profile. Deleted release pages keep their tags.
- Two artifact names are served publicly. The old `kitecodec-core` line is finished and receives
  nothing further. This repository's artifact is `kiteffmpeg`, and the Gradle module is
  `:kiteffmpeg`; the artifact id lost its `-core` suffix in the rename.
- The version went backwards on purpose. `kiteffmpeg` 0.1.0 is strictly newer than
  `kitecodec-core` 0.1.3, because a new artifact id is a new artifact and the line restarted
  with the name. Never "fix" the number by bumping past the old line, and keep saying this in
  the README so a stranger reading two version numbers does not read a regression.
- The permissive source offer names a specific FFmpeg release tag, and that tag is kept forever,
  because published versions can never be withdrawn from the public repository.
- The consumer application pins the player in its own version catalog and picks it on a
  home-screen selector; another engine is its default on Android.
