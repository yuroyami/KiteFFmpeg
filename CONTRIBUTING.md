# Contributing to KiteFFmpeg

Thanks for helping out. KiteFFmpeg is a Kotlin Multiplatform (Kotlin/Native) binding to FFmpeg's libav\* libraries, so contributing means having both a Kotlin toolchain and an FFmpeg to link against.

## Ground rules

These are not style preferences. Each one exists because ignoring it cost someone a day.

- **Red first, always.** Write the failing test, run it, watch it fail at the line you
  predicted, then fix it, then break the fix and watch it go red again. A test that was never
  seen red proves nothing.
- **A claim carries the strength of its evidence and no more.** Compilation is not support. A
  source-set declaration is not support. A laptop green is not a device green. A simulator
  green is not a device green. A cached up-to-date Gradle run proves only that the cache is not
  red. When code, artifacts, docs and measurements disagree, the weakest result is the truth.
- **No em dashes in any file**: code, comments, Markdown or commit messages. The gate scans for
  them.
- **No new dependency without asking first**: not a library, not a plugin, not a toolchain or
  Gradle bump. C or shader source we author ourselves is fine.
- **A design act is its own commit.** Deciding a public API shape and executing it never happen
  in the same commit.
- **Public API changes run `./gradlew apiDump -Pkiteffmpeg.hostTargetsOnly=true` in the same
  commit**, and every new public declaration carries KDoc. Explicit API mode is on.
- **When the tree contradicts an issue or a document, stop and say so.** Do not improvise the
  document back into truth.

### What we may and may not copy

- This library ships under a permissive licence. Any GPL or LGPL player checked out under
  `vendor/` is **study only**. Designs, algorithms and thresholds are facts and may be
  restated. Source text is expression, and a Kotlin transliteration inherits its licence.
  Never transliterate, and never name a study-only source in a comment as the origin of an
  implementation.
- The `ffmpeg` and `ffprobe` binaries as test oracles are always fine. Differential testing
  compares outputs, never source.

## Build prerequisites

- **JDK 21** (the build sets `jvmToolchain(21)`).
- **FFmpeg**: the fastest path on a dev machine is a system install:
  - macOS: `brew install ffmpeg`
  - Debian/Ubuntu: `sudo apt install libavformat-dev libavcodec-dev libavfilter-dev libavutil-dev libswscale-dev libswresample-dev`
  - Windows has no auto-discovery; stage a [BtbN build](https://github.com/BtbN/FFmpeg-Builds/releases) under `native-libs/gpl/mingw-x64/` and build with `-Pkiteffmpeg.ffmpeg.license=gpl` (see [docs/platforms.md](docs/platforms.md)).
- If Homebrew lives in a non-standard prefix, set `kiteffmpeg.macos.homebrew.prefix` in `gradle.properties`.
- The `ffmpeg` and `ffprobe` CLIs on `PATH` (used by the e2e script only).

macOS arm64 is the reference development target. It is the one verified end-to-end.

### Vendored FFmpeg (optional)

To work on the static-linking path or the FFmpeg build tasks themselves:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
brew install nasm meson ninja                             # nasm for x86_64 asm, meson/ninja for dav1d
./gradlew :kiteffmpeg:buildFFmpegForMacosArm64        # LGPL, and the only flavour built here
```

Every profile is portable as of 2026-08-22: no third-party media libraries are needed on any target,
which is why that `brew install` line is three packages rather than eleven. **There is no
`buildFFmpegForMacosArm64Gpl` task**; the GPL build tasks were deleted on 2026-08-21.

Outputs land in `native-libs/lgpl/<target>/`, and the cinterop picks them up on the next sync. If
you build your own GPL tree, put it under `native-libs/gpl/<target>/` and select it with
`-Pkiteffmpeg.ffmpeg.license=gpl`. Full prerequisites: [docs/troubleshooting.md](docs/troubleshooting.md#vendored-build-prerequisites).

## Running the tests

```bash
# Unit + native tests (pick your host target):
./gradlew :kiteffmpeg:macosArm64Test        # or linuxX64Test / mingwX64Test

# End-to-end: build the sample CLI, then transcode a generated clip and ffprobe-assert it:
./gradlew :kiteffmpeg-sample:linkDebugExecutableMacosArm64
scripts/e2e.sh kiteffmpeg-sample/build/bin/macosArm64/debugExecutable/kiteffmpeg-sample.kexe
```

Pure-logic tests (`Rational`, `FrameInfo`) live in `commonTest`; `nativeTest` runs against the actually-linked FFmpeg. CI runs all of this on macOS, Ubuntu, and Windows on every push, a green local `macosArm64Test` + `e2e.sh` is the bar before opening a PR.

## The gate before every commit

Pick the tier by which paths changed, never by how confident you feel. Say which tier you ran
and which rule selected it.

**Tier 1, every change without exception, seconds:**

```bash
./gradlew checkCinteropCoupling
./gradlew :kiteffmpeg:checkFFmpegRecipes
./native/kitecodec-c/scripts/check-deleted-surface.sh
./native/kitecodec-c/scripts/run-c-tests.sh plain
git ls-files -z | xargs -0 grep -n $'\u2014'   # em dash scan: printing nothing is the pass
```

Tier 1 cannot catch data races, wrong-architecture archives, cinterop surface changes,
real-media regressions, or anything about a target it did not build. It does catch a vendored
FFmpeg tree baked from a different recipe than the checkout describes.

**Tier 2, roughly 10 to 15 minutes.** Selected by any of: files under `native/` or `buildSrc/`,
`kiteffmpeg-gradle-plugin/src/`, any `.def` file, any `build.gradle.kts`, any version catalog,
or any Kotlin under a platform source set. Contents: Tier 1, plus host cinterop and `apiCheck`
(both need `-Pkiteffmpeg.hostTargetsOnly=true` on a machine with one FFmpeg tree), the build
logic and plugin tests, the sanitizer and interpose C runs, corpus replay, the symbol check,
the klib metadata diff, the host target's test task, `jvmTest`, and `./scripts/linux-tests.sh`.

Run the aggregate task, not a hand-written list of modules. `run-c-tests.sh` never builds
anything: run `build-host.sh <variant>` first or you are testing yesterday's binaries.

## Pull request expectations

- **Keep PRs focused**: one change per PR, with tests where the change is testable.
- **Tests must pass**: the host-target test task and, for anything touching the pipeline, `scripts/e2e.sh`.
- **New public API needs KDoc**: the docs site and the API reference are generated from it, and the KDoc contracts (frame ownership, confinement, timestamps) are part of the API.
- **Docs**: if behavior described under `docs/` changes, update the page in the same PR.
- **Commit messages**: imperative subject line; explain the *why* in the body when it is not obvious.
- CI must be green before review.

## Code style

- Standard Kotlin style (official code style, four-space indent); match the formatting of the file you are editing.
- Public API lives flat in `io.github.yuroyami.kiteffmpeg`, no internal subpackages.
- `commonMain` declares `expect`; `nativeMain` holds the `actual`s. Do not leak `kotlinx.cinterop` types (or the `ffmpeg.*` package) into `commonMain`.
- C bridge helpers in `ffmpeg.def` are prefixed `ffkmp_*`; keep them `static inline` and single-purpose.
- Native resources follow the `AutoCloseable` + `use { }` pattern; anything acquiring native memory must free it in `finally`.
- The public API is compiled in [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors), every public declaration states `public` and its return type.

## Binary compatibility

The build wires [kotlinx binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) in klib mode. `./gradlew apiDump` regenerates the ABI baseline, but it compiles **every** native target's klib, so it needs FFmpeg present for all of them (vendored builds under `native-libs/`), in practice it runs on the release CI machine, not a laptop with only Homebrew FFmpeg. API-breaking changes must be intentional and called out in `CHANGELOG.md`.

## Reporting issues

GitHub Issues is the only tracker for this repository: open work, plans and findings all live there, and
there is no private planning file. Use the [issue templates](.github/ISSUE_TEMPLATE/). For anything security-sensitive (malformed-media crashes, memory corruption), see [SECURITY.md](SECURITY.md) instead of opening a public issue.
