/* Shared plumbing for the six fuzz targets of plan sub-phase B1.5.
 *
 * Each target is one `LLVMFuzzerTestOneInput` in one source file, and that one body serves two
 * drivers:
 *
 *   libFuzzer     -fsanitize=fuzzer,address,undefined, on ubuntu-24.04 in CI. The real fuzzer.
 *                 Nothing links it on this machine: the runtime archive libclang_rt.fuzzer_osx.a
 *                 is absent from Apple clang 17 and from konan's LLVM 21, and Homebrew LLVM is
 *                 not installed. scripts/run-fuzz.sh says so before it tries.
 *   corpus replay replay_main.c supplies main(), reads every file named on the command line and
 *                 hands its bytes to the same function under ASan and UBSan. That is what runs
 *                 here and in every later gate, and it is a regression test, not a fuzz run.
 *
 * A target must therefore never assume which driver called it: no global state that survives a
 * call, no reliance on being called once, no exit() on rejected input. Returning 0 for "this
 * input was uninteresting" is the only contract. A finding is a crash, a sanitizer report or a
 * hang, never a return value.
 *
 * The input contract, per target family. It is written here rather than in each target because
 * the committed corpus depends on it, and a corpus that disagrees with the split silently stops
 * testing what its file names claim.
 *
 *   Graph targets, fuzz_filter_video and fuzz_filter_audio
 *     The whole input is the filter description, nothing else. The target then runs a fixed
 *     matrix of parameter sets over it: single input and multi input, output pins off and on. So
 *     one seed exercises every composition path in the builders rather than one of them, and the
 *     fuzzer spends its budget on the description, which is the part a caller controls.
 *
 *   Key and value targets, fuzz_codec_option, fuzz_format_option and fuzz_metadata
 *     The input splits at the FIRST newline (0x0a). The bytes before it are the key, the bytes
 *     after it are the value. With no newline anywhere the whole input is the key and the value
 *     is NULL, which is a case all three entry points accept and must survive.
 *
 *   Name target, fuzz_format_name
 *     The whole input is one format name, handed to both from-name lookups.
 *
 * Every string a target passes to a helper is a heap copy with a NUL appended, made by
 * kc_fuzz_dup below, and never a pointer into the driver's own buffer. Two reasons, and both are
 * about what the instrument can see:
 *
 *   1. libFuzzer's data is not NUL terminated. Passing it straight to a `const char *` parameter
 *      would read past the end on every call and every finding would be the harness's.
 *   2. ASan puts a redzone around a heap block and cannot put one around the middle of the
 *      fuzzer's buffer. A helper that reads one byte past the end of a key is caught with the
 *      heap copy and invisible without it.
 *
 * Embedded NUL bytes are deliberately kept in the copy rather than rejected. A key that carries
 * one becomes a shorter C string, which is exactly what happens when the same bytes arrive from
 * Kotlin, and the corpus has seeds for it.
 */

#ifndef KITECODEC_FUZZ_H
#define KITECODEC_FUZZ_H

#include <stddef.h>
#include <stdint.h>

#include "kitecodec_helpers.h"

/* The one function every target defines. Declared here so replay_main.c can call it without
 * knowing which target it was linked against, and so a target that misspells the signature
 * fails to compile instead of being silently unreachable. */
int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

/* Silences the FFmpeg log the first time any target runs. A fuzz target drives parser error
 * paths on purpose, and libav's own diagnostics would be several lines per call: under libFuzzer
 * that is the whole output budget, and under the replay driver it buries the one line per corpus
 * file. Set KC_FFMPEG_LOG=1 in the environment to keep them while investigating a finding.
 *
 * Idempotent and safe to call on every input, which is what the targets do, because a target
 * cannot know whether it is the first. */
void kc_fuzz_quiet(void);

/* A NUL terminated heap copy of `size` bytes from `data`. Returns NULL only when the allocation
 * fails, so a target may treat NULL as "give up on this input" rather than as an error. `data`
 * may be NULL when `size` is 0. Release with kc_fuzz_free. */
char *kc_fuzz_dup(const uint8_t *data, size_t size);

/* Splits the input at the first newline into two NUL terminated heap copies. `*out_key` is the
 * bytes before it and is never NULL on success. `*out_value` is the bytes after it, or NULL when
 * the input holds no newline at all, which is the case that exercises a NULL value.
 *
 * Returns 0 on success and -1 when an allocation failed, in which case both out parameters are
 * left NULL and nothing needs releasing. */
int kc_fuzz_split(const uint8_t *data, size_t size, char **out_key, char **out_value);

/* Releases what kc_fuzz_dup or kc_fuzz_split returned. A NULL pointer is accepted. */
void kc_fuzz_free(char *s);

#endif /* KITECODEC_FUZZ_H */
