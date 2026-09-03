/* Fuzz target: ffkmp_find_encoder_by_name, ffkmp_find_decoder_by_name and ffkmp_filter_exists.
 *
 * Entry points, and why these. They are the three name lookups left without a target after B1.5.
 * fuzz_format_name covers the pixel and sample format tables; these three walk libav's codec and
 * filter registries instead, and each is PUBLIC through a one-line Kotlin function that hands the
 * caller's string straight down: FFmpeg.hasEncoder, FFmpeg.hasDecoder and FFmpeg.hasFilter. Each
 * takes `const char *name` with no length limit and makes no copy of its own, which is the same
 * property fuzz_format_name exists for: the heap copy kc_fuzz_dup makes is what gives ASan a
 * redzone for an over-read to land in.
 *
 * The whole input is one name. There is nothing to split.
 *
 * The interesting property is not "does it find something". It is that a FOUND codec carries an id
 * the id table can name:
 *
 *   ffkmp_find_encoder_by_name(n) -> c, then ffkmp_codec_id(c) -> id, then ffkmp_codec_id_name(id)
 *
 * That is an assertion over two different libav tables, and it is the one this surface can make. It
 * is deliberately NOT stated as a name round trip: several encoders implement one codec id, so
 * `libx264` and `h264` share an id whose canonical name resolves to neither encoder in particular,
 * and asserting otherwise would fail on a correct registry.
 *
 * The name is checked against "none" rather than against NULL, and that is the difference between
 * an assertion and a tautology here. ffkmp_codec_id_name wraps avcodec_get_name, whose own contract
 * is that it never returns NULL and answers "none" for an id it does not know. So NULL is
 * unreachable and testing for it proves nothing; "none" is what an id outside the table actually
 * looks like, and that is the state nothing downstream checks for.
 *
 * The encoder and decoder registries are asked separately on purpose: a name can be one and not
 * the other, and a target that only asked one would miss a lookup that walks the wrong table.
 * ffkmp_filter_exists answers a plain 0 or 1 and has nothing to round trip, so what it contributes
 * is the walk itself under a sanitizer.
 */

#include "kc_fuzz.h"

#include <stdlib.h>
#include <string.h>

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *name = kc_fuzz_dup(data, size);
    if (name == NULL) return 0;

    const kc_codec *encoder = ffkmp_find_encoder_by_name(name);
    if (encoder != NULL) {
        /* A codec the registry resolved but whose id the name table does not know is a descriptor
         * outside that table. The Kotlin side reports the id to callers without checking it. */
        int id = ffkmp_codec_id(encoder);
        if (id == 0) abort();
        if (strcmp(ffkmp_codec_id_name(id), "none") == 0) abort();
    }

    const kc_codec *decoder = ffkmp_find_decoder_by_name(name);
    if (decoder != NULL) {
        int id = ffkmp_codec_id(decoder);
        if (id == 0) abort();
        if (strcmp(ffkmp_codec_id_name(id), "none") == 0) abort();
    }

    /* No round trip to make: the answer is a plain yes or no. What this contributes is the registry
     * walk itself, over a name the caller controls, under the sanitizer. */
    int exists = ffkmp_filter_exists(name);
    if (exists != 0 && exists != 1) abort();

    kc_fuzz_free(name);
    return 0;
}
