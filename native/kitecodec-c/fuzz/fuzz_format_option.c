/* Fuzz target: ffkmp_fmt_set_opt, through av_opt_set on a muxer context.
 *
 * Entry point, and why this one. Plan sub-phase B1.5 step 1. Same shape as the codec option target
 * and a different table: AV_OPT_SEARCH_CHILDREN here reaches the OUTPUT FORMAT's private options,
 * which is how `movflags`, `brand`, `frag_duration` and the rest are set. The mov muxer's option
 * table alone carries flag sets, durations and dictionaries, each with its own value parser, and
 * the key selects which one runs.
 *
 * No filesystem is touched. avformat_alloc_output_context2 guesses the muxer from the format short
 * name and copies the path into ctx->url; it never opens anything, and ffkmp_fmt_free_output closes
 * ctx->pb only when pb is non-NULL, which it never is here because nothing opened it. That is what
 * keeps this target on the right side of B1.5 step 3: the path is a fixed constant and is never
 * fuzzed (see fuzz/README.md), so the parser under test is the option parser and not a protocol.
 *
 * Three muxers per input, because their private option tables are the interesting part and they
 * differ: mp4 has the largest table of the three, matroska has a different one, and null has almost
 * none, which is the case that leaves only AVFormatContext's own options in play.
 *
 * ── One thing this target deliberately does NOT do, and the measurement behind that ──
 *
 * A NULL key is not passed to ffkmp_fmt_set_opt, and the reason is not squeamishness. The two
 * sibling helpers do not guard the same way:
 *
 *   ffkmp_codecctx_set_opt   if (!c || !key) return AVERROR(EINVAL);   src/helpers_codec.c
 *   ffkmp_fmt_set_metadata   if (!c || !key) return AVERROR(EINVAL);   src/helpers_format.c
 *   ffkmp_fmt_set_opt        if (!c)        return AVERROR(EINVAL);   src/helpers_format.c
 *
 * So a NULL key reaches av_opt_set only through ffkmp_fmt_set_opt, and av_opt_set does not tolerate
 * it. Measured on this machine against FFmpeg 8.0 (libavutil 60.8.100), a five line program under
 * -fsanitize=address,undefined:
 *
 *   AddressSanitizer: SEGV on unknown address 0x000000000000
 *   The signal is caused by a READ memory access
 *   #0 strcmp
 *   #1 av_opt_find2
 *
 * av_opt_find2 walks the option table with strcmp(o->name, name) and never tests `name`.
 *
 * That is a real hole in one exported helper of a versioned library, and it is not reachable from
 * KiteFFmpeg's own Kotlin today: MediaSink passes the keys of a Map<String, String>, which cannot
 * hold a null key. It becomes reachable the moment any other C consumer calls the exported symbol.
 *
 * It is not fixed here and it is not asserted here. Fixing it means editing src/helpers_format.c,
 * which B1.5 does not own. Asserting it means writing a target that reliably crashes on every
 * input, which would replace a search for unknown defects with a monument to a known one. So the
 * measurement is recorded, here and in fuzz/README.md, and the fix belongs to whoever next edits
 * that unit. When the guard lands, the assertion to add is one line next to the context alloc:
 *
 *   if (ffkmp_fmt_set_opt(ctx, NULL, value) != AVERROR(EINVAL)) abort();
 */

#include "kc_fuzz.h"

#include <libavformat/avformat.h>
#include <libavutil/error.h>

#include <stdlib.h>

/* Never opened. Named so that a stray file in the working directory would be traceable to this
 * target rather than mysterious, in the event that some future libav does open it. */
#define FUZZ_OUTPUT_PATH "kc_fuzz_never_opened.out"

static const char *const MUXERS[3] = { "mp4", "matroska", "null" };

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *key = NULL;
    char *value = NULL;
    if (kc_fuzz_split(data, size, &key, &value) != 0) return 0;

    /* The one guard the helper does have, asserted on every input. */
    if (ffkmp_fmt_set_opt(NULL, key, value) != AVERROR(EINVAL)) abort();

    for (size_t i = 0; i < sizeof(MUXERS) / sizeof(MUXERS[0]); i++) {
        AVFormatContext *ctx = NULL;
        if (ffkmp_fmt_alloc_output2(&ctx, FUZZ_OUTPUT_PATH, MUXERS[i]) != 0) continue;
        if (ctx == NULL) abort();

        /* The subject. The return code says only whether this key exists in this muxer's table,
         * which is not what is being tested; surviving the parse is. */
        (void)ffkmp_fmt_set_opt(ctx, key, value);

        ffkmp_fmt_free_output(&ctx);
        if (ctx != NULL) abort();
    }

    kc_fuzz_free(key);
    kc_fuzz_free(value);
    return 0;
}
