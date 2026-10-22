# Contributing to KiteFFmpeg

KiteFFmpeg is a Kotlin Multiplatform binding to FFmpeg's libav\* libraries, with Kotlin/Native, JVM, Android and wasmJs backends, so contributing means having an FFmpeg to link against. The repository builds with the Kotlin Toolchain: the `./kotlin` script in the root (`kotlin.bat` on Windows) downloads the toolchain, a JDK and Kotlin/Native on first use.

## Ground rules

- **Red first, always.** Write the failing test, run it, watch it fail at the line you
  predicted, then fix it, then break the fix and watch it go red again. A test that was never
  seen red proves nothing.
- **A claim carries the strength of its evidence and no more.** Compilation is not support. A
  source-set declaration is not support. A laptop green is not a device green. A simulator
  green is not a device green. A cached up-to-date build proves only that the cache is not
  red. When code, artifacts, docs and measurements disagree, the weakest result is the truth.
- **No em dashes in any file**: code, comments, Markdown or commit messages. The gate scans for
  them.
- **No new dependency without asking first**: not a library, not a plugin, not a toolchain bump. C or shader source we author ourselves is fine.
- **A design act is its own commit.** Deciding a public API shape and executing it never happen
  in the same commit.
- **Public API changes run `./kotlin do updateAbi` and `./kotlin do updateKlibAbi` after a full
  `./kotlin build -m kiteffmpeg`, in the same commit**, and every new public declaration carries KDoc. Explicit API mode is on.
  The dump covers every klib target, so the run needs all eleven native FFmpeg trees; see
  [Binary compatibility](#binary-compatibility).
- **When the tree contradicts an issue or a document, stop and say so.** Do not improvise the
  document back into truth.
- Before you file a finding, search the trackers of both KiteFFmpeg and KitePlayer: open and
  closed issues, with their comments. File it only when no issue covers it yet.

### What we may and may not copy

- This library ships under a permissive license. Any GPL or LGPL player checked out under
  `vendor/` is **study only**. Designs, algorithms and thresholds are facts and may be
  restated. Source text is expression, and a Kotlin transliteration inherits its license.
  Never transliterate, and never name a study-only source in a comment as the origin of an
  implementation.
- The `ffmpeg` and `ffprobe` binaries as test oracles are always fine. Differential testing
  compares outputs, never source.

## Build prerequisites

- **No JDK and no Gradle.** `./kotlin` downloads JDK 21 itself. The Android platforms need the Android SDK in `ANDROID_HOME`, and the Android JNI libraries need the NDK in `ANDROID_NDK_HOME`.
- **FFmpeg**: the fastest path on a dev machine is a system install:
  - macOS: `brew install ffmpeg`
  - Linux: the apt packages do not link (see [Building from source](docs/building-from-source.md#where-the-build-finds-ffmpeg)). Unzip this repository's `ffmpeg-<version>-lgpl-linux-x64.zip` release asset (or the `linux-arm64` one) under `native-libs/lgpl/linux-x64/`, which is what CI does.
  - Windows has no system lookup. Unzip this repository's `ffmpeg-<version>-lgpl-mingw-x64.zip` release asset under `native-libs/lgpl/mingw-x64/`, which is what CI does (see [Building from source](docs/building-from-source.md#windows)).
- If Homebrew lives in a non-standard prefix, set `homebrewPrefix` of the `kiteffmpeg-native` plugin in `kiteffmpeg/module.yaml`.
- The `ffmpeg` and `ffprobe` CLIs on `PATH` (used by the e2e script only).

macOS arm64 is the reference development machine.

### Vendored FFmpeg (optional)

To work on the static-linking path or the FFmpeg build tasks themselves:

```bash
git clone --depth 1 --branch n9.0.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
git clone --depth 1 --branch 1.5.4 https://code.videolan.org/videolan/dav1d.git vendor/dav1d
brew install nasm meson ninja                             # nasm for x86_64 asm, meson/ninja for dav1d
./kotlin do buildFFmpegForMacosArm64                     # LGPL, and the only flavor built here
```

A build checks each checkout before it compiles anything. It must hold the commit its tag names,
which the build recipe pins beside the tag, with nothing changed, added or ignored in it, and the
build stops and names whatever differs. A change to FFmpeg belongs in a patch under
`native/patches/ffmpeg`, which every build applies to its own copy of the source and records.
A change to libass belongs under `native/patches/libass`, applied and recorded the same way, and
`scripts/package-ass-chain.sh` refuses a chain whose record differs from that folder. dav1d,
FreeType, HarfBuzz and FriBidi carry no patches.

No third-party media library is needed on any target, and there is no GPL build command.

Outputs land in `native-libs/lgpl/<target>/`, and the cinterop picks them up on the next build. If
you build your own GPL tree, put it under `native-libs/gpl/<target>/` and select it with the
environment variable `KITEFFMPEG_FFMPEG_LICENSE=gpl`. Full prerequisites: [docs/troubleshooting.md](docs/troubleshooting.md#vendored-build-prerequisites).

## Running the tests

```bash
# Unit + native tests (pick your host platform):
./kotlin test -m kiteffmpeg -p macosArm64   # or -p linuxX64 / -p mingwX64
./kotlin test -m kiteffmpeg -p jvm          # the JVM backend, with the JNI boundary harness

# The web tests, which `./kotlin test` cannot run:
python3 scripts/web-tests/run.py            # js and wasmJs in Node; see docs/building-from-source.md

# End-to-end: build the sample CLI, then transcode a generated clip and ffprobe-assert it:
./kotlin build -m kiteffmpeg-sample-macos -p macosArm64
scripts/e2e.sh "$(find build/tasks -name 'kiteffmpeg-sample-macos.kexe' -type f | head -1)"

# The tests of the build plugins:
./kotlin test -m native-build

# The JNI library on the one running emulator (start it first). ANDROID_SERIAL picks another device:
./scripts/android-device-check.sh
```

Pure-logic tests (`Rational`, `FrameInfo`) live in `kiteffmpeg/test`, and `kiteffmpeg/test@native` runs against the linked FFmpeg. CI runs all of this on macOS, Ubuntu and Windows on every push. A green local `./kotlin test -m kiteffmpeg -p macosArm64` and `e2e.sh` is the bar before you open a PR.

The JVM and macOS arm64 tests can each write a transcript of the shared codec contract, and the two
must agree byte for byte:

```bash
KITECODEC_CONTRACT_TRANSCRIPT="$PWD/build/contract-transcripts/macosArm64.txt" ./kotlin test -m kiteffmpeg -p macosArm64
./kotlin test -m kiteffmpeg -p jvm
./kotlin do compareCodecContract
```

### The sample tool

`kiteffmpeg-sample` is a command-line program over the whole API. Its app modules
`kiteffmpeg-sample-macos`, `kiteffmpeg-sample-linux` and `kiteffmpeg-sample-windows` build it for
macOS arm64, Linux x64, Linux arm64 and Windows x64. It probes the linked FFmpeg for its encoders, so
it works against the embedded build and against your own FFmpeg alike.

```bash
./kotlin build -m kiteffmpeg-sample-macos -p macosArm64
KEXE="$(find build/tasks -name 'kiteffmpeg-sample-macos.kexe' -type f | head -1)"

$KEXE info                                    # versions and encoders
$KEXE probe path/to/clip.mp4                  # streams, duration and metadata
$KEXE transcode input.mp4 output.mp4 "scale=1280:720,format=yuv420p"
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -an       # no audio
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -acopy    # copy the audio
$KEXE transcode input.mp4 output.mp4 "scale=1280:720" -vt       # hardware H.264
$KEXE transcode input.mp4 clip.mp4 "scale=1280:720" --ss 12.3 --to 45.6 --title "My clip"
$KEXE thumbnail input.mp4 frame.jpg 90.0
$KEXE remux input.mp4 output.mkv
```

### The C layer

The C layer under the Kotlin API builds and tests on its own, outside the Kotlin build:

```bash
cd native/kitecodec-c
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain   # also asan, tsan
```

[native/kitecodec-c/README.md](native/kitecodec-c/README.md) says what each C check covers.

## The gate before every commit

Pick the tier by which paths changed, never by how confident you feel. Say which tier you ran
and which rule selected it. `scripts/check-gate.sh` runs either tier on a macOS arm64 machine,
prints each command before it runs it, and stops at the first failure. `--from=STEP` resumes a
tier at the step named in its output, and `--dry-run` prints the commands only.

**Tier 1, every change without exception, a few minutes:**

```bash
./scripts/check-gate.sh tier1
```

It runs `./kotlin check cinteropCoupling` and `./kotlin do checkFFmpegRecipes`, the deleted-surface check,
the JNI source-discipline check, the plain C build and its suites, and the em dash scan.

Tier 1 cannot catch data races, wrong-architecture archives, cinterop surface changes,
real-media regressions, or anything about a target it did not build. It does catch a vendored
FFmpeg tree baked from a different recipe than the checkout describes.

**Tier 2, tens of minutes.** Selected by any of: files under `native/` or `build-plugins/`, any `.def`
file, any `project.yaml`, `module.yaml` or module template, the version catalog, or any Kotlin under a
platform folder (`src@<platform>` or `test@<platform>`).

```bash
./scripts/check-gate.sh tier2
```

Tier 2 is Tier 1 plus a build of every platform and the `abi` and `klibAbi` checks (see
[Binary compatibility](#binary-compatibility)), the wasm binding and release target mirrors, the
JNI registration and FFmpeg release pin checks, the dependency hygiene script, host cinterop and the klib metadata diff,
the build logic tests, the interpose, AddressSanitizer and ThreadSanitizer C runs, the symbol
audit, the corpus replay, the macOS arm64 tests, the JVM tests with the JNI boundary harness, the
comparison of the JVM and macOS codec contract transcripts, the end-to-end transcode, and
`./scripts/linux-tests.sh`, which needs Docker. The full build needs the Android SDK and NDK and an
FFmpeg tree for each of the eleven native targets.

`run-c-tests.sh` never builds anything. When you run it by hand, run `build-host.sh <variant>`
first, or you are testing yesterday's binaries.

## Pull request expectations

- **Keep PRs focused**: one change per PR, with tests where the change is testable.
- **Tests must pass**: the tests of the host platform and, for anything touching the pipeline, `scripts/e2e.sh`.
- **New public API needs KDoc**: the docs site and the API reference are generated from it, and the KDoc contracts (frame ownership, confinement, timestamps) are part of the API.
- **Docs**: if behavior described under `docs/` changes, update the page in the same PR.
- **Commit messages**: imperative subject line; explain the *why* in the body when it is not obvious.
- CI must be green before review.

## Code style

- Standard Kotlin style (official code style, four-space indent); match the formatting of the file you are editing.
- Public API lives in `io.github.yuroyami.kiteffmpeg`, except the option and filter builders, which live in `io.github.yuroyami.kiteffmpeg.dsl`.
- `kiteffmpeg/src` (the common code) declares `expect`. The `actual`s live in `src@native` and the platform folders under it, `src@jvmAndAndroid`, `src@wasmJs`, `src@web` and `src@js`. Do not leak `kotlinx.cinterop` types (or the `ffmpeg.*` package) into the common code.
- C helpers live in `native/kitecodec-c/src`, are prefixed `ffkmp_*`, and are exported with `KC_API`. Keep each one single-purpose.
- Native resources follow the `AutoCloseable` + `use { }` pattern; anything acquiring native memory must free it in `finally`.
- The public API is compiled in [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors), every public declaration states `public` and its return type.

## Binary compatibility

The local build plugin `build-plugins/abi-check` keeps two committed dumps in the format of the Kotlin Gradle plugin's ABI validation. `kiteffmpeg/api/jvm/kiteffmpeg.api` holds the JVM API. `kiteffmpeg/api/kiteffmpeg.klib.api` covers all thirteen klib platforms: the eleven native targets, `js` and `wasmJs`. The `klibPlatforms` list of the plugin in `kiteffmpeg/module.yaml` names them. The ratchets job in `.github/workflows/ci.yml` checks both.

The klib checks read the klib of every platform in that list, so build all of them first. Check and dump like this:

```bash
./kotlin build -m kiteffmpeg
./kotlin check abi klibAbi
./kotlin do updateAbi        # after a deliberate API change
./kotlin do updateKlibAbi
```

The build compiles the klib of every native target, so it needs an FFmpeg tree in `native-libs/lgpl/<triple>/` for each of the eleven triples. A macOS host can compile all of them. The prebuilt trees are assets of the release that `FFMPEG_ASSET_TAG` names in the workflows, and the ratchets job shows how to fetch and verify them. When a tree is missing, the build of that platform stops and names the target. Only the host's own target can use a system FFmpeg instead.

API-breaking changes must be intentional and called out in `CHANGELOG.md`.

A default parameter added to a public function still breaks compiled callers, because the
compiled signature changes. When a public function gains a parameter, keep the old signature as a
bridge marked `@Deprecated(level = DeprecationLevel.HIDDEN)`. A data class that gains a field
cannot keep its old constructor that way, so the release notes say that the release is not binary
compatible and name the classes. Diff the ABI dumps between the two release tags before a release,
and name every removed `fun` line in the notes.

## Reporting issues

GitHub Issues is the only tracker for this repository: open work, plans and findings all live there, and
there is no private planning file. Use the [issue templates](.github/ISSUE_TEMPLATE/). For anything security-sensitive (malformed-media crashes, memory corruption), see [SECURITY.md](SECURITY.md) instead of opening a public issue.
