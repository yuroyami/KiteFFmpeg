# kitecodec-c fuzz targets

Six fuzz targets, one per C entry point that parses a caller's string. Plan sub-phase B1.5 in
`KitePlayer/PLANNING.md`.

## What runs where, and what each result is worth

There are two drivers over one body, and the difference between them decides what a green run
means. Nothing here should be read as "the library was fuzzed" unless the Linux job says so.

| Driver | Where | Instruments | Evidence |
|---|---|---|---|
| libFuzzer, `LLVMFuzzerTestOneInput` | `ubuntu-24.04`, the `fuzz-linux` job of `.github/workflows/ci.yml`, five minutes per target | ASan, UBSan, LeakSanitizer, coverage guidance | Level 8: a declared configuration. The job is written and has never executed, so no search for unknown inputs has happened yet; this row becomes level 2 on the day a run exists and not before. |
| Corpus replay, `replay_main.c` | this machine, `scripts/replay-corpus.sh`, every later gate | ASan, UBSan | Level 2 for the inputs in the corpus and nothing more. A regression test, and today the only fuzz-shaped evidence that exists. |

**libFuzzer does not exist on this host.** Measured while writing this directory, not quoted from
the plan:

```
/usr/bin/clang -fsanitize=fuzzer          Apple clang 17.0.0 (clang-1700.3.19.1)
  ld: library '.../lib/clang/17/lib/darwin/libclang_rt.fuzzer_osx.a' not found     exit 1
~/.konan/.../llvm-21-aarch64-macos-essentials-97/bin/clang -fsanitize=fuzzer
  clang version 21.1.6
  ld: library '.../lib/clang/21/lib/darwin/libclang_rt.fuzzer_osx.a' not found     exit 1
/opt/homebrew/opt/llvm/bin/clang          not installed
```

Only the header directory `lib/clang/17/include/fuzzer` is present. `-fsanitize=fuzzer-no-link`
does compile, exit 0, but that is coverage instrumentation with no driver and it fuzzes nothing.
That is register item B1-13, and `scripts/run-fuzz.sh` detects it by compiling a two line program
and exits 3 with one sentence rather than dumping a linker error.

So the local gate replays a committed corpus. It discovers nothing. It refuses to forget.

## The six targets

| Target | Entry points | Why it is a fuzz target |
|---|---|---|
| `fuzz_filter_video.c` | `ffkmp_graph_build_video`, `ffkmp_graph_build_video_multi` | The description goes to `avfilter_graph_parse_ptr` unvalidated, straight from the public Kotlin `FilterGraph` API. |
| `fuzz_filter_audio.c` | `ffkmp_graph_build_audio`, `ffkmp_graph_build_audio_multi` | The same, plus the D27 site: these two COMPOSE the description into a fixed `char full_desc[2048]` with repeated `n += snprintf(...)`. |
| `fuzz_codec_option.c` | `ffkmp_codecctx_set_opt` | `av_opt_set` picks a value parser by looking the key up, so one caller string decides how the other is parsed. |
| `fuzz_format_option.c` | `ffkmp_fmt_set_opt` | The same, over the muxer's private option table, which is a different set of parsers. |
| `fuzz_metadata.c` | `ffkmp_fmt_set_metadata` | `av_dict_set` stores rather than looks up: growth, replacement and the NULL-value delete path. |
| `fuzz_format_name.c` | `ffkmp_pix_fmt_from_name`, `ffkmp_sample_fmt_from_name` | A name looked up in a descriptor table, and the round trip back to a name. |

Each target's own file header says what it does, what matrix it runs and what a finding would look
like. Read the file, not this table.

## The input contract

Written once in `kc_fuzz.h` and repeated here because the corpus depends on it. A corpus that
disagrees with the split silently stops testing what its file names claim.

* **Graph targets.** The whole input is the filter description. The target then runs a fixed matrix
  over it (single and multi input, output pins off and on), so one seed reaches every composition
  path rather than one of them.
* **Key and value targets.** The input splits at the **first newline**. Before it is the key, after
  it is the value. No newline anywhere means a NULL value, which all three entry points accept.
* **Name target.** The whole input is one format name.

Every string handed to a helper is a **NUL terminated heap copy**, never a pointer into the
driver's buffer. libFuzzer's data is not NUL terminated, so passing it directly would read past the
end on every call and every finding would be the harness's own; and ASan can put a redzone around a
heap block but not around the middle of the fuzzer's buffer, so a helper reading one byte past a key
is caught with the copy and invisible without it.

Embedded NUL bytes are kept rather than rejected. A key carrying one becomes a shorter C string,
which is exactly what happens when the same bytes arrive from Kotlin, and the corpus has seeds for
it in every family.

## The corpus

103 files, 38077 bytes, all committed, all textual. Per plan section 15.3: small and textual. The
full replay of all six targets under ASan and UBSan takes 4.4 seconds on this machine.

| Directory | Files | What is in it |
|---|---|---|
| `corpus/filter_video/` | 19 | Valid chains taken from `FFmpegNativeTest` and `FilterGraphDrainTest` (`scale=160:90,format=yuv420p`, `null`, `[in0][in1]overlay=W-w-10:H-h-10[out]`), a three input `hstack`, unknown filters, unbalanced labels, an embedded NUL, an empty input, and the 2047, 2048 and 4096 byte length vectors once plain and once behind `[in0]`. |
| `corpus/filter_audio/` | 16 | The same shape for audio (`volume=0.5`, `anull`, `aformat=...`, `[in0][in1]amix=inputs=2:duration=longest[out]`), plus the six committed D27 length vectors. |
| `corpus/codec_option/` | 19 | Option keys whose values reach different parsers: `threads`, `b` with a `128k` suffix, `time_base` as a rational, `pixel_format`, `ch_layout`, `video_size`, a `+global_header` flag set, a private `preset`. Plus embedded NULs in key and in value, `:` and `=` separators inside both, an empty key, an empty value, no value at all, an unknown key, a 24 digit number and INT64_MIN. |
| `corpus/format_option/` | 15 | Muxer options: `movflags` with flag lists, `brand`, `frag_duration`, `avoid_negative_ts` as a named constant, and the same NUL, separator and emptiness edges. |
| `corpus/metadata/` | 14 | Metadata keys and values, including one that collides with the tag the target pre-sets, invalid UTF-8 in a key, multibyte UTF-8 in a value, and the no-newline seeds that reach `av_dict_set`'s delete path. |
| `corpus/format_name/` | 20 | Valid pixel and sample format names, the `rgb32` and `bgr32` aliases, a truncated name, uppercase, a leading space, a name with an embedded NUL, and a 4096 byte name. |

**The D27 length vectors.** Plan step 2 names descriptions of length 0, 2047, 2048, 4096 and
1048576. The first four are committed for both filter targets, once plain and once with an `[in0]`
prefix, so single-input and multi-input builders both see them. Length 0 is `edge_empty`.

The 1048576 vector is **generated** into `build/<variant>/fuzz/generated/filter_audio/` by
`scripts/replay-corpus.sh`, and it is replayed through the same driver over the same code path. Two
measured reasons rather than tidiness: one megabyte of padding would be 27 times the whole rest of
the corpus and the plan asks for two of them, against a requirement that says small; and libFuzzer
derives `-max_len` from the largest seed when the flag is absent, so a megabyte seed would spend the
five minute budget on length rather than on shape. `run-fuzz.sh` passes an explicit `-max_len` for
the same reason.

It is generated for `filter_audio` only, and the asymmetry is worth reading rather than skipping.

## The video builders apply no length limit at all

D27 is a defect in the two **audio** builders by name, because they are the ones that compose the
description into `char full_desc[2048]`. A 1048576 byte description is refused by their first length
check in microseconds, so the vector is free there.

The video builders have no composition buffer, and therefore no length check of any kind. The
description goes whole to `avfilter_graph_parse_ptr`. Measured on this machine with the same
`filter_video_replay` binary over the same 1048576 byte `scale=1.000...` description:

| `ASAN_OPTIONS` | Wall clock |
|---|---|
| `detect_leaks=0:abort_on_error=1` | 0.25 s |
| the same plus `strict_string_checks=1` | did not finish inside 120 s |

`strict_string_checks=1` is what `run-c-tests.sh` sets and what `replay-corpus.sh` sets to match, so
the second row is the gate's configuration. The cost belongs to ASan's string interceptors, which
validate a whole buffer per call, against a parser that makes many string calls over the whole
description. It is not a defect in the library and it is not a finding.

What IS worth recording is the property underneath it: **an unbounded caller string reaches the
avfilter parser through `ffkmp_graph_build_video` and `ffkmp_graph_build_video_multi` with no length
policy anywhere in KiteFFmpeg.** Nothing here says that is wrong. It says it is unbounded, that the
audio path is bounded and the video path is not, and that a length or time policy for
caller-supplied filter text is B8's, alongside the resource classification that container fuzzing
needs anyway. The committed video corpus therefore stops at 4096 bytes, which is half of
`run-fuzz.sh`'s `-max_len` and replays in 1.0 second for the whole 19 file directory, and the gate
stays fast.

## What the corpus actually reaches, measured

A corpus is worth what it executes, not what its file names suggest. Measured with
`-fprofile-instr-generate -fcoverage-mapping` and `xcrun llvm-cov` over the six replay binaries
against the whole committed corpus, one profile per target merged into one report. The six entry
points, their two internal graph finishers, and the helpers the targets use for setup:

| Function | Lines | Regions | Branches |
|---|---|---|---|
| `ffkmp_graph_build_video` | 100.00% | 72.22% | 54.17% |
| `ffkmp_graph_build_video_multi` | 100.00% | 73.13% | 57.14% |
| `ffkmp_graph_build_audio` | 100.00% | 79.86% | 58.06% |
| `ffkmp_graph_build_audio_multi` | 100.00% | 79.87% | 60.29% |
| `ffkmp_graph_finish_` (internal) | 78.57% | 85.71% | 66.67% |
| `ffkmp_graph_finish_multi_` (internal) | 100.00% | 84.38% | 80.00% |
| `ffkmp_codecctx_set_opt` | 100.00% | 100.00% | 100.00% |
| `ffkmp_fmt_set_opt` | 100.00% | 100.00% | 100.00% |
| `ffkmp_fmt_set_metadata` | 100.00% | 100.00% | 100.00% |
| `ffkmp_pix_fmt_from_name` | 100.00% | 100.00% | n/a |
| `ffkmp_sample_fmt_from_name` | 100.00% | 100.00% | n/a |
| whole `src/helpers_filter.c` | 92.34% | 75.25% | 55.05% |

Every line of all four graph builders runs. The missing regions and branches are the allocation
failure paths (`avfilter_graph_alloc` returning NULL, `avfilter_inout_alloc` returning NULL) and the
`avfilter_get_by_name` misses, none of which an input can provoke: they need the allocator or the
filter registry to fail, and provoking those needs fault injection rather than a different string.
Three of the missing branches in `ffkmp_graph_finish_` are the same shape.

The other helper units read near zero and that is correct rather than a gap. At B1.5, these six
targets existed for the six string entry points and `tests/test_*.c` covered the other 151 helpers
in that historical 157-helper surface. ABI 1.1 later added twelve compatible, dormant functions;
the B1.5 coverage measurement predates them and makes no claim that the fuzz corpus reaches them.
Pointing a fuzz target at a getter would add coverage numbers and no evidence.

Reproducing it is a one-off measurement and deliberately not a committed script: nothing in the gate
depends on a coverage number, and a coverage script that nobody runs is worse than none. The
invocation is in the Execution log entry for B1.5.

## What B1.5 does not fuzz, and what B8 inherits

**Path entry points get no fuzz target in B1.** Plan sub-phase B1.5 step 3, by name:

* `ffkmp_fmt_open_input`
* `ffkmp_fmt_alloc_output2`
* `ffkmp_fmt_io_open`

The boundary is not squeamishness, it is scope. Fuzzing a path opens the filesystem, and once the
filesystem is open the thing being fuzzed is a protocol handler and then a demuxer, which means
container bytes. Container byte fuzzing is **B8's remit by its own wording**, and a B1 target that
drifted into it would produce findings B1 cannot fix and cannot bound.

Two consequences worth stating so nobody has to rediscover them:

1. `ffkmp_fmt_alloc_output2` **is** called by `fuzz_format_option.c` and `fuzz_metadata.c`, because
   an `AVFormatContext` is needed before an option or a tag can be set on one. It is called with a
   **fixed path constant** and a fixed muxer short name, and the path is never fuzzed.
   `avformat_alloc_output_context2` guesses the muxer and copies the path into `ctx->url`; it opens
   nothing, and `ffkmp_fmt_free_output` closes `ctx->pb` only when `pb` is non-NULL, which it never
   is here. So no file is created and no protocol runs. The constant is named
   `kc_fuzz_never_opened.out` so that a file by that name appearing anywhere would be traceable to
   this directory instead of mysterious.
2. Nothing here reads or writes media. There is no `AVPacket` and no `AVFrame` carrying data in any
   of the six targets, deliberately, so no finding from this directory can be about a bitstream.

**What B8 inherits from here.** The two drivers over one body, the corpus layout, `kc_fuzz.h`'s
input contract, `run-fuzz.sh`'s budget flags and the `--prove-power` check below. What B8 has to add
is what B1 refused: path and protocol entry points, container byte corpora, a seed corpus of real
media, and a resource policy, because a demuxer fed random bytes will hit out-of-memory and timeout
before it hits memory corruption and those two need classifying before they can be gated on.

## Proving the harness has power

A green fuzzer that has never caught anything is not evidence of anything except that it ran. Plan
sub-phase B1.5's tests require one deliberately planted defect, proved caught, then removed.

```bash
./scripts/build-host.sh asan
./scripts/replay-corpus.sh --prove-power
```

What it does:

1. Copies `src/*.c` and `include/*.h` into `build/asan/fuzz/mutant/`. The defect is planted in a
   **copy** and never in the repository, so it is not in any commit at all, which is a stronger
   reading of "removed in the same change" than the plan asked for. It also follows what B1.4
   already established: its suites were proved load bearing by mutation against copies in a scratch
   directory.
2. Deletes exactly one line from the copy: the running-length check that D27 installed after the
   `,aformat=` append in `ffkmp_graph_build_audio`. The mutation is applied by exact text match and
   **refuses to run unless it matches exactly once**, so a helper source that changed shape fails
   loudly instead of turning the whole check into a no-op that reports success.
3. Builds `filter_audio_replay_mutant` against the mutant archive and replays the committed corpus
   through it.
4. Requires a non-zero exit **and** a sanitizer report in the output. A non-zero exit with no report
   is not evidence and is rejected.

The corpus already carries the input that trips it, `corpus/filter_audio/d27_len_2047`, and the
target runs the pinned matrix on every seed, so the proof needs no special input. It needs only the
defect.

## Running

```bash
./scripts/build-host.sh asan                       # the helper archive this links against
./scripts/replay-corpus.sh                         # all six targets, the gate
./scripts/replay-corpus.sh asan filter_audio       # one target, the fast loop
./scripts/replay-corpus.sh --prove-power           # the planted defect
./scripts/run-fuzz.sh                              # exits 3 here, fuzzes on Linux
```

`replay-corpus.sh` never builds the helper layer, so a gate cannot pass against an archive that was
never recompiled. Note that `build-host.sh` deletes `build/<variant>` on every run, so the order
above is the order that works.

`KC_FFMPEG_LOG=1` keeps FFmpeg's own diagnostics, which are silenced by default because a target
that drives parser error paths on purpose would otherwise bury the one line per corpus file.

## Adding a target

1. Write `fuzz/fuzz_<name>.c` with one `LLVMFuzzerTestOneInput` and no `main`. Include `kc_fuzz.h`,
   call `kc_fuzz_quiet()` first, and copy every caller string with `kc_fuzz_dup` or
   `kc_fuzz_split`.
2. Create `fuzz/corpus/<name>/` and commit at least one seed. An empty corpus directory fails the
   replay rather than passing it, and a replay that ran zero files exits non-zero.
3. Add `<name>` to `ALL_TARGETS` in **both** `scripts/replay-corpus.sh` and `scripts/run-fuzz.sh`.
   The two lists must agree.
4. Assert the entry point's documented guards on every input, the way the existing targets assert
   the `AVERROR(EINVAL)` refusals. Those cost nothing per call and catch a reordered guard.
5. Never let a target keep state between calls. libFuzzer saves one file per finding, and a crash
   that needs two inputs in sequence cannot be reproduced from one file.

## One measurement recorded here rather than fixed here

`ffkmp_fmt_set_opt` does not guard a NULL key, and its two siblings do:

```
ffkmp_codecctx_set_opt   if (!c || !key) return AVERROR(EINVAL);   src/helpers_codec.c
ffkmp_fmt_set_metadata   if (!c || !key) return AVERROR(EINVAL);   src/helpers_format.c
ffkmp_fmt_set_opt        if (!c)        return AVERROR(EINVAL);    src/helpers_format.c
```

A NULL key therefore reaches `av_opt_set`, which walks the option table with
`strcmp(o->name, name)` and never tests `name`. Measured on this machine against FFmpeg 8.0
(libavutil 60.8.100), a five line program under `-fsanitize=address,undefined`:

```
AddressSanitizer: SEGV on unknown address 0x000000000000
The signal is caused by a READ memory access
    #0 strcmp
    #1 av_opt_find2
```

It is not reachable from KiteFFmpeg's own Kotlin today: `MediaSink` passes the keys of a
`Map<String, String>`, which cannot hold a null key. It becomes reachable the moment any other C
consumer calls the exported symbol, which is what `KC_API` now makes possible.

`fuzz_format_option.c` deliberately does **not** pass a NULL key, and the reason is in that file's
header: a target that crashes on every input is a monument to a known defect rather than a search
for unknown ones. The fix is one `|| !key` in `src/helpers_format.c`, which B1.5 does not own. When
it lands, the assertion to add next to the context allocation is one line, and that line is written
out in the target's file header.
