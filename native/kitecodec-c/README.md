# kitecodec-c

The FFmpeg helper layer: the C that every KiteFFmpeg binding calls. Kotlin/Native reaches it
through cinterop, the JVM and Android through the JNI adapter in `native/kitecodec-jni`, and the
web through the codec module that `linkKiteFFmpegWasmModule` links with emscripten. The layer has
its own build, its own tests and its own sanitizer runs, all driven by the scripts in this
directory.

## The boundary

- The public headers include no FFmpeg header. Every FFmpeg type crosses as one of the opaque
  aliases in `include/kitecodec_handles.h`, so no raw libav function, constant or struct layout
  reaches Kotlin.
- Helper names start with `ffkmp_`. The identity gate and the ABI calls start with `kc_`.
- `include/kitecodec_abi.h` holds the C ABI version, `KITECODEC_C_ABI_MAJOR` and
  `KITECODEC_C_ABI_MINOR`, and `kc_abi_version()` reports it at run time. The major moves when a
  declaration changes shape, the minor when something is added compatibly.
- Three committed baselines hold the surface: `exported-symbols-baseline.txt` (the exported names),
  `signature-baseline.txt` (the declaration shapes) and `klib-metadata-baseline.txt` (what cinterop
  binds). `scripts/symbol-audit.sh` refuses any change to the first two unless the ABI version
  rises in the same commit.

## Layout

| Path | What it is |
|---|---|
| `include/kitecodec_helpers.h` | The helper declarations, each with the contract its signature cannot say. |
| `include/kitecodec_handles.h` | The opaque aliases for FFmpeg's types. No FFmpeg include. |
| `include/kitecodec_abi.h` | The FFmpeg identity gate and the C ABI version. No FFmpeg include. |
| `include/kitecodec_ffmpeg_versions.h` | Private. The only place the gate reads FFmpeg's version macros, and the one file the identity test replaces. |
| `src/helpers_*.c` | One translation unit per subsystem: build, codec, codecpar, display, dovi, error, filter, format, frame, hdr, hwaccel, log, packet, playback, stream, subtitle and swr. |
| `src/kitecodec_abi.c` | The gate: the frozen header versions, the runtime comparison, the report and the diagnostic bypass. |
| `scripts/build-host.sh` | Builds the host test binaries for one variant. |
| `scripts/run-c-tests.sh` | Runs every suite for one variant, or the plain binaries in the `interpose` mode, where allocation counting is required. |
| `scripts/symbol-audit.sh` | Checks what the compiled archive needs, exports and keeps private, and the declaration shapes. |
| `scripts/klib-metadata-diff.sh` | Checks the cinterop klib against its baseline and rejects any raw libav binding. |
| `scripts/check-deleted-surface.sh` | Checks that nothing in this repository, or in a KitePlayer checkout beside it, refers to a helper whose status in `deleted-surface.txt` is deleted. |
| `scripts/replay-corpus.sh` | Replays every committed fuzz seed through its target under ASan and UBSan. |
| `scripts/run-fuzz.sh` | Runs the libFuzzer targets. It refuses on a host whose clang has no fuzzer runtime. |
| `fuzz/` | The fuzz targets, their committed corpus and a README on what is fuzzed and what is not. |
| `tests/harness.h`, `tests/harness.c` | The assertion and reporting API every suite uses. |
| `tests/interpose_alloc.c` | The allocation interposer, the local leak instrument. |
| `tests/test_*.c` | The suites, one binary each. Both scripts find them in this directory, so adding a file adds a suite. |
| `tests/fake_headers/` | Doctored copies of the private versions header for the identity test, and a stand-in `jni.h` for `test_jni_bridge`, which compiles the JNI units without a JDK. |
| `coupling-baseline.txt` | The ceilings on direct FFmpeg use from Kotlin, both zero. |
| `probe/` | `report_offsets.c` and `opener_offsets.c`, which `../../scripts/wasm-report-offsets.sh` uses to state the byte layouts the web binding reads and writes; `wasm_link_probe.c`, a link check of the web archive run by `../../scripts/wasm-link-probe.sh`; and a browser demo page. None is part of a gate. |
| `build/` | Output. Gitignored. |

## The FFmpeg identity gate

**Why it exists.** Code built against older FFmpeg headers links cleanly against a newer FFmpeg
runtime. Every symbol resolves, the static archive has no SONAME to object, and 38 measured struct
field offsets are wrong. 48 of the helpers read or write through one of them, so the process reads
wrong values and then dies inside `av_frame_free`, with AddressSanitizer naming a four byte read 36
bytes past a 416 byte region. In the nondeterministic case it is silent.

**How the expectations are frozen.** `src/kitecodec_abi.c` initializes a file-scope `static const`
array from the six `LIB*_VERSION_INT` macros. It is compiled by the same task, against the same
include tree, as every helper unit, so if the compiler baked a struct offset it also read these
macros. Nothing else can recover the header versions after the build, and this construction
cannot get them wrong.

**The policy.**

- The major version must match exactly. A mismatch is rejected with no override, because a major
  bump lets FFmpeg reorder struct fields.
- The runtime minor version must be at or above the header minor version. FFmpeg only guarantees
  backward compatibility, so a lower runtime minor is rejected.
- The micro version is compared and reported, but never rejected.
- The six `*_configuration()` strings must agree with each other. A disagreement means a mixed
  install, which matching version numbers cannot reveal.

**Once per process.** `kc_init` is guarded by `pthread_once`. A function-local static in a header
would give one flag per translation unit, so the gate would run once per consumer of the header
instead of once per process.

**A report cinterop can read.** The report is flat plain data with fixed char arrays and no
pointers, and no two-dimensional arrays: cinterop flattens `char names[6][16]` into one byte array,
so `names[i]` would be byte `i` and not row `i`. The per-library names come from
`kc_ffmpeg_library_name(index)` instead.

**The diagnostic bypass**, `KITECODEC_FFMPEG_ABI_BYPASS=1`. It is opt-in, takes only that exact
value, and is never quiet. It turns a rejection into a warning, written once per process, that
names both identities, and it records in the report that it was used. It exists because an unbypassable gate turns one
false rejection into an outage inside a consumer's product that the consumer cannot patch. It is not
a supported configuration. This is the only unit in the layer that writes to a stream, and
`symbol-audit.sh` pins that permission to this one file.

**How the test reaches the rejecting path.** `tests/fake_headers/<case>/kitecodec_ffmpeg_versions.h`
includes the real private header and then redefines the `LIB*_VERSION_*` macros, so only the frozen
expectation array changes. Each shim also renames that copy's exported symbols through
`tests/fake_headers/kc_rename.h`, so several verdict copies of the same source link into one binary.
The shim directory must come before `-I include` on the command line. If it comes after, the real
header wins, no copy is renamed, and the link fails on an undefined `kc_<case>_init`.

## Documented contracts

A helper whose contract its signature cannot express carries that contract as the comment directly
above its declaration in `include/kitecodec_helpers.h`: who owns a returned object, which calls
take a reference rather than copy, which leave a frame or packet blank, and what a graph frees on
failure. Every helper that allocates, frees or moves a reference has a case in
`tests/test_ownership.c` that asserts the pairing, so the words and the tests cover the same set.

## Building and running

There is no make, no cmake and no ninja here. A checkout path can contain `#`, and GNU make starts
a comment at an unescaped `#`, so the scripts drive clang directly.

```bash
./scripts/check-deleted-surface.sh
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
./scripts/run-c-tests.sh interpose       # plain binaries, allocation counting required
./scripts/symbol-audit.sh --host         # or with no argument, for the shipped archive
./scripts/replay-corpus.sh
```

`run-c-tests.sh` never builds anything. It tests whatever binaries are there, so run the build
script first, every time. The runner takes suite names after the variant, which gives a fast loop
while you write a suite:

```bash
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain test_buffers
```

FFmpeg flags come from `pkg-config` for the six libraries, or from `KC_FFMPEG_PREFIX` when that is
set, which may point at a vendored static tree. `KC_CC` and `KC_AR` override the compiler and the
archiver, which default to `/usr/bin/clang` and `/usr/bin/ar`.

The cinterop API has its own check, which needs a klib instead of a host binary. The cinterop
definition parses only the three public headers. The libav headers are private to the C archive:

```bash
(cd ../.. && ./kotlin build -m kiteffmpeg -p macosArm64)
./scripts/klib-metadata-diff.sh --check  # exits non-zero on any difference
./scripts/klib-metadata-diff.sh --update # re-baseline after reading and accepting a change
```

Editing a `.c` body reaches the klib because the `ffmpegInterop<Platform>` task of
`build-plugins/kiteffmpeg-native` takes the C sources as an input. It rebuilds the archive and writes
a fingerprint of the archive into the generated definition file, so the cinterop step runs again.
If a local change to a helper body seems to have no effect, check that fingerprint before suspecting
the compiler.

## The three variants

ASan and TSan cannot be combined, which is why there are three variants rather than one.

| Variant | Flags on top of `-std=c11 -Wall -Wextra -Werror -Werror=vla -g` | What it is for |
|---|---|---|
| `plain` | `-O2` | Compile fidelity, correctness, and allocation pairing. |
| `asan` | `-fsanitize=address,undefined -fno-omit-frame-pointer -O1` | The out of bounds and undefined behavior class. |
| `tsan` | `-fsanitize=thread -O1` | The threaded cases, starting with `ffkmp_strerror`. |

The helper units also get `-fvisibility=hidden`, matching the shipped compile in
`build-plugins/native-build/src/CompileKiteFFmpegCTask.kt`, so the host archive exports the same set as the shipped one.

`-Werror` matters here. Every unit includes the public header, so this compile checks that all
the public declarations agree with their definitions. Separate compilation adds a second proof: a
`static` helper cannot be called from another unit, because that would be an implicit declaration,
and cannot sit in a unit that never calls it, because that would be an unused function.

## The allocation interposer

LeakSanitizer is not supported on macOS arm64, so `tests/interpose_alloc.c` counts allocations
through the Mach-O `__DATA,__interpose` section, and it is the local leak check. Three measured
facts about it can each mislead you if you do not know them:

* A library that simply defines its own `malloc` counts exactly zero. The two-level namespace binds
  every call to libSystem's definition, so the shadowing one is never reached. The interpose section
  is the mechanism that works.
* dyld does not apply an interpose section to the image that carries it. That lets the wrappers call
  the real `malloc` with no recursion, and it is why the "is the interposer effective" probe lives in
  `harness.c`, in the executable.
* FFmpeg's `av_malloc` goes through `posix_memalign` on this platform and `av_free` through `free`. An
  interposer that watched only `malloc` and `free` would report zero allocations against one free for
  every FFmpeg object, which looks like a finding and is not.

The counters are live in the `plain` variant and read zero under `asan` and `tsan`, because each
sanitizer runtime replaces the allocator first. `KC_ALLOC_BALANCED` and `KC_ALLOC_LIVE` record that
gap with `kc_partial()` when `kc_alloc_active()` is 0, and the suite summary counts the partial
cases, so no result claims something that the variant could not observe. `run-c-tests.sh interpose`
makes an inactive interposer fail the run instead.

## Writing a suite

`tests/harness.h` is the API and carries the details. Every suite is table driven, prints one line
per case, and returns non-zero on the first failure:

```c
#include "harness.h"
#include "kitecodec_helpers.h"

int main(void) {
    kc_suite_begin("test_something");
    for (size_t i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        kc_case("%s at %d", rows[i].name, rows[i].size);
        int rc = ffkmp_something(rows[i].size);
        KC_EQ_INT(rc, rows[i].expected);
        kc_detail("rc=%d", rc);
    }
    return kc_suite_end();
}
```

* Reporting: `kc_suite_begin`, `kc_case`, `kc_detail`, `kc_partial`, `kc_note`, `kc_suite_end`.
  `kc_suite_end` returns the process exit code, and non-zero when the suite ran no cases, so an empty
  suite cannot pass by accident.
* Assertions: `KC_CHECK`, `KC_CHECKF`, `KC_EQ_INT`, `KC_EQ_I64`, `KC_EQ_SIZE`, `KC_EQ_PTR`,
  `KC_NOT_NULL`, `KC_NULL`, `KC_EQ_STR`, `KC_EQ_STRLEN`, `KC_EQ_MEM`, `KC_ALL_ZERO`. Each prints the
  case line, the source location and the actual against the expected value, then exits. On
  purpose, there is no mode that continues after a failure. The first failure is the one whose
  surrounding state is still intact.
* Allocation: `kc_alloc_active`, `kc_alloc_snapshot`, `kc_alloc_live_delta`, `kc_alloc_new_delta`,
  `kc_alloc_free_delta`, and the `KC_ALLOC_BALANCED` and `KC_ALLOC_LIVE` macros.
* `kc_suite_begin` silences the FFmpeg log, because a suite that drives error paths on purpose would
  otherwise bury its own output. Set `KC_FFMPEG_LOG=1` to keep FFmpeg's diagnostics while debugging.

Add a suite by adding `tests/test_<name>.c`. Both scripts pick it up.

## The suites

The `tests/` directory holds 40 suites, and the header of each one says what it establishes. These
cover the core contracts:

| Suite | What it establishes |
|---|---|
| `test_ownership.c` | Exact allocation pairing for every ownership helper under the interposer, including the parent-owned stream, the cached `SwsContext`, the conditional `pb` close and the packet clone. |
| `test_buffers.c` | Every fixed buffer the helpers write into, the four frame and sample copy helpers and the extradata copy, at the limit and one past it. |
| `test_rescale.c` | The arithmetic helpers at the rescale overflow vectors, and `AV_CEIL_RSHIFT` plane heights. |
| `test_strerror_thread.c` | Both halves of `ffkmp_strerror`'s thread affinity contract, clean under TSan. |
| `test_convert.c` | Pixel format conversion against an independently computed oracle. |
| `test_identity.c` | One case per identity verdict against the doctored header trees, the true build, the bypass conditions and the JVM attach. |
| `test_gate_refusal.c` | Every constructor helper refuses when the gate rejects the linked FFmpeg. |
| `test_args.c` | One invalid vector for each guarded entry point, and controls for arguments whose NULL meaning is part of FFmpeg's contract. |
| `test_filter.c` | The filter graph builders' boundaries, the channel layout they take, and the count of requests a source could not serve. |
| `test_build.c` | The component lists a build reports, and the codec lookups by format. |
| `test_handles.c` | The generation-tagged handle table, past the wrap its token layout implies, and its borrowed children. |
| `test_jni_bridge.c` | The JNI units on paths no JVM test can reach, against a stand-in `jni.h`. |
| `test_interrupt.c` | The caller-owned interrupt cell. |
| `test_output_io.c` | The output bridge that writes a container into application-owned bytes. |
| `test_subtitle.c` | The subtitle decoder, on a Blu-ray file written byte by byte. |
| `test_swr.c` | The resampler's refusals and sample counts. |
| `test_hdr.c` | The color, pixel shape and HDR metadata an encode keeps. |
| `test_rotation.c` | The rotation read from a display matrix, including an all-zero one. |
| `test_field_order.c` | The field order mapping and the container bit rate. |
| `test_append.c` | The bounded string append the JNI identity report uses. |

## What each check shows, and what it cannot

| Check | Shows | Cannot show |
|---|---|---|
| The allocation interposer | Exact allocation and free pairing per ownership helper, and that nothing is left live at the end of a case. | Anything under `asan` or `tsan`, where the sanitizer owns the allocator; those cases report a partial. |
| ASan with UBSan | An out of bounds read or write, and a signed overflow, named at the byte and the line. | A leak, because LeakSanitizer is unsupported here. A race, because it cannot be combined with TSan. |
| TSan | A real data race between two threads. | Memory ordering strength: a release store downgraded to relaxed is still atomic. |

Two limits of a macOS arm64 host shape all of the above:

* **No libFuzzer.** Apple clang and Kotlin/Native's LLVM both lack `libclang_rt.fuzzer_osx.a`, so
  `run-fuzz.sh` refuses to run here. The fuzzer only runs in the Linux CI job. Locally, only the
  corpus replay runs, which is a regression test over the committed seeds and nothing more.
* **No LeakSanitizer**, which is why the interposer exists.

This directory has no lock-free audio ring and no real-time device callback. Those belong to a
player. Putting them here would make a player's real-time core depend on a codec binding.
