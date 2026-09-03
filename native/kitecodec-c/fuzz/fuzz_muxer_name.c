/* Fuzz target: ffkmp_fmt_alloc_output2's `format` argument.
 *
 * Entry point, and why. fuzz_format_option and fuzz_metadata both call this helper, and both pass a
 * FIXED muxer name from a table of three: what they fuzz is the option pair and the metadata that
 * follow, and the muxer name is scenery. That left the format argument itself unfuzzed, which the
 * fuzz README recorded as a deliberate gap. It is not a small surface: the string is handed to
 * av_guess_format, which walks the muxer registry comparing names, and it is PUBLIC through
 * MediaSink.open(path, format, options), where a caller chooses the container by name.
 *
 * The whole input is one muxer name. The path is fixed, because the path is not the subject and a
 * fuzzed one would only re-test the option system's own copy.
 *
 * The properties are ownership, not resolution. Whether a name resolves is libav's business; what
 * this library promises is that the two outcomes are the only two:
 *
 *   success -> *out is non-NULL and ffkmp_fmt_free_output brings it back to NULL
 *   failure -> *out is NULL, and there is nothing to free
 *
 * The failure half is the one worth a target. A helper that returned an error while leaving a
 * half-built context in *out would leak it on every rejected name, and a caller has no way to tell
 * that from the documented contract. The success half asserts the free actually clears the pointer,
 * which is what makes a double free impossible for a caller that follows the contract.
 *
 * The name is a heap copy from kc_fuzz_dup, never a pointer into the driver's buffer, so an
 * over-read inside the registry walk lands in an ASan redzone.
 */

#include "kc_fuzz.h"

#include <stdlib.h>

/* Never opened: alloc_output2 only allocates and names, so nothing reaches the filesystem. The name
 * is distinctive so a file appearing beside the fuzzer is traceable to this target. */
#define FUZZ_OUTPUT_PATH "kc_fuzz_never_opened.out"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *format = kc_fuzz_dup(data, size);
    if (format == NULL) return 0;

    kc_fmt_ctx *ctx = NULL;
    int rc = ffkmp_fmt_alloc_output2(&ctx, FUZZ_OUTPUT_PATH, format);
    if (rc == 0) {
        /* Success must hand back a context. */
        if (ctx == NULL) abort();
        ffkmp_fmt_free_output(&ctx);
        /* And the free must clear the caller's pointer, which is what makes a double free
         * impossible for a caller following the documented contract. */
        if (ctx != NULL) abort();
    } else {
        /* Failure must leave nothing behind. A half-built context left in *out here would leak on
         * every rejected name and no caller could see it. */
        if (ctx != NULL) abort();
    }

    kc_fuzz_free(format);
    return 0;
}
