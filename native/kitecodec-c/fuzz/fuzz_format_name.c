/* Fuzz target: ffkmp_pix_fmt_from_name and ffkmp_sample_fmt_from_name.
 *
 * Entry points, and why these. They are named together because they are one
 * surface: a caller-supplied name goes to a libav lookup that walks a table of descriptors and
 * compares strings, and the answer is an enum the rest of the library then trusts. They are the
 * smallest of the six targets and they cover the shape the other five do not have, a name that is
 * looked up rather than parsed.
 *
 * The whole input is one name. There is nothing to split.
 *
 * The interesting property is not the lookup. It is the ROUND TRIP. When a name resolves, the enum
 * it produced is handed back to the matching name function, and the two names must agree:
 *
 *   ffkmp_pix_fmt_from_name(n) -> fmt, then ffkmp_pix_fmt_name(fmt) -> n2, and n2 must equal n
 *
 * That is a real assertion and not a tautology, because the two directions are different libav code
 * over different tables. It is stated in the direction that holds for aliases too: av_get_pix_fmt
 * maps `rgb32` and `bgr32` onto endianness-dependent names and retries an unknown name with `le` or
 * `be` appended, so a caller's name need not come back unchanged. What must hold is that libav can
 * name whatever it just resolved, and that the CANONICAL name resolves back to the same enum. A
 * resolved format whose name comes back NULL means from-name produced an enum outside the name
 * table's range, and nothing downstream checks that. This is where it has to be caught.
 *
 * Both from-name helpers take the name straight from the caller with no length limit and no copy, so
 * the heap copy that kc_fuzz_dup makes is what gives ASan a redzone to catch an over-read against.
 * An unterminated buffer here would be the classic way a name lookup runs off the end.
 */

#include "kc_fuzz.h"

#include <libavutil/pixfmt.h>
#include <libavutil/samplefmt.h>

#include <stdlib.h>

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *name = kc_fuzz_dup(data, size);
    if (name == NULL) return 0;

    /* Pixel formats. AV_PIX_FMT_NONE is -1 and is the "not found" answer. */
    int pix = ffkmp_pix_fmt_from_name(name);
    if (pix != AV_PIX_FMT_NONE) {
        const char *pix_name = ffkmp_pix_fmt_name(pix);
        /* A resolved format the name table cannot name means from-name answered outside the table's
         * range. Nothing downstream checks that, so this is where it has to be caught. */
        if (pix_name == NULL) abort();
        /* And the name must round trip to the same enum, which catches an alias table that resolves
         * one way and not the other. */
        if (ffkmp_pix_fmt_from_name(pix_name) != pix) abort();
    }

    /* Sample formats. AV_SAMPLE_FMT_NONE is -1, same convention. */
    int sample = ffkmp_sample_fmt_from_name(name);
    if (sample != AV_SAMPLE_FMT_NONE) {
        const char *sample_name = ffkmp_sample_fmt_name(sample);
        if (sample_name == NULL) abort();
        if (ffkmp_sample_fmt_from_name(sample_name) != sample) abort();
    }

    kc_fuzz_free(name);
    return 0;
}
