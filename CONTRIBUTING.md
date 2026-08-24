# Contributing to KiteCodec

Thanks for helping out. KiteCodec is a Kotlin Multiplatform (Kotlin/Native) binding to FFmpeg's libav\* libraries, so contributing means having both a Kotlin toolchain and an FFmpeg to link against.

## Build prerequisites

- **JDK 21** (the build sets `jvmToolchain(21)`).
- **FFmpeg**: the fastest path on a dev machine is a system install:
  - macOS: `brew install ffmpeg`
  - Debian/Ubuntu: `sudo apt install libavformat-dev libavcodec-dev libavfilter-dev libavutil-dev libswscale-dev libswresample-dev`
  - Windows has no auto-discovery; stage a [BtbN build](https://github.com/BtbN/FFmpeg-Builds/releases) under `native-libs/gpl/mingw-x64/` and build with `-Pkitecodec.ffmpeg.license=gpl` (see [docs/platforms.md](docs/platforms.md)).
- If Homebrew lives in a non-standard prefix, set `kitecodec.macos.homebrew.prefix` in `gradle.properties`.
- The `ffmpeg` and `ffprobe` CLIs on `PATH` (used by the e2e script only).

macOS arm64 is the reference development target. It is the one verified end-to-end.

### Vendored FFmpeg (optional)

To work on the static-linking path or the FFmpeg build tasks themselves:

```bash
git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg
brew install nasm meson ninja                             # nasm for x86_64 asm, meson/ninja for dav1d
./gradlew :kitecodec-core:buildFFmpegForMacosArm64        # LGPL, and the only flavour built here
```

Every profile is portable as of 2026-08-22: no third-party media libraries are needed on any target,
which is why that `brew install` line is three packages rather than eleven. **There is no
`buildFFmpegForMacosArm64Gpl` task**; the GPL build tasks were deleted on 2026-08-21.

Outputs land in `native-libs/lgpl/<target>/`, and the cinterop picks them up on the next sync. If
you build your own GPL tree, put it under `native-libs/gpl/<target>/` and select it with
`-Pkitecodec.ffmpeg.license=gpl`. Full prerequisites: [docs/troubleshooting.md](docs/troubleshooting.md#vendored-build-prerequisites).

## Running the tests

```bash
# Unit + native tests (pick your host target):
./gradlew :kitecodec-core:macosArm64Test        # or linuxX64Test / mingwX64Test

# End-to-end: build the sample CLI, then transcode a generated clip and ffprobe-assert it:
./gradlew :kitecodec-sample:linkDebugExecutableMacosArm64
scripts/e2e.sh kitecodec-sample/build/bin/macosArm64/debugExecutable/kitecodec-sample.kexe
```

Pure-logic tests (`Rational`, `FrameInfo`) live in `commonTest`; `nativeTest` runs against the actually-linked FFmpeg. CI runs all of this on macOS, Ubuntu, and Windows on every push, a green local `macosArm64Test` + `e2e.sh` is the bar before opening a PR.

## Pull request expectations

- **Keep PRs focused**: one change per PR, with tests where the change is testable.
- **Tests must pass**: the host-target test task and, for anything touching the pipeline, `scripts/e2e.sh`.
- **New public API needs KDoc**: the docs site and the API reference are generated from it, and the KDoc contracts (frame ownership, confinement, timestamps) are part of the API.
- **Docs**: if behavior described under `docs/` changes, update the page in the same PR.
- **Commit messages**: imperative subject line; explain the *why* in the body when it is not obvious.
- CI must be green before review.

## Code style

- Standard Kotlin style (official code style, four-space indent); match the formatting of the file you are editing.
- Public API lives flat in `io.github.yuroyami.kitecodec`, no internal subpackages.
- `commonMain` declares `expect`; `nativeMain` holds the `actual`s. Do not leak `kotlinx.cinterop` types (or the `ffmpeg.*` package) into `commonMain`.
- C bridge helpers in `ffmpeg.def` are prefixed `ffkmp_*`; keep them `static inline` and single-purpose.
- Native resources follow the `AutoCloseable` + `use { }` pattern; anything acquiring native memory must free it in `finally`.
- The public API is compiled in [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors), every public declaration states `public` and its return type.

## Binary compatibility

The build wires [kotlinx binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) in klib mode. `./gradlew apiDump` regenerates the ABI baseline, but it compiles **every** native target's klib, so it needs FFmpeg present for all of them (vendored builds under `native-libs/`), in practice it runs on the release CI machine, not a laptop with only Homebrew FFmpeg. API-breaking changes must be intentional and called out in `CHANGELOG.md`.

## Reporting issues

Use the [issue templates](.github/ISSUE_TEMPLATE/). For anything security-sensitive (malformed-media crashes, memory corruption), see [SECURITY.md](SECURITY.md) instead of opening a public issue.
