/* Fuzz target: ffkmp_fmt_set_opt, through av_opt_set on a muxer context.
 *
 * Entry point, and why this one. Same shape as the codec option target
 * and a different table: AV_OPT_SEARCH_CHILDREN here reaches the OUTPUT FORMAT's private options,
 * which is how `movflags`, `brand`, `frag_duration` and the rest are set. The mov muxer's option
 * table alone carries flag sets, durations and dictionaries, each with its own value parser, and
 * the key selects which one runs.
 *
 * No filesystem is touched. avformat_alloc_output_context2 guesses the muxer from the format short
 * name and copies the path into ctx->url; it never opens anything, and ffkmp_fmt_free_output closes
 * ctx->pb only when pb is non-NULL, which it never is here because nothing opened it. That is what
 * keeps this target on the right side of its one rule: the path is a fixed constant and is never
 * fuzzed (see fuzz/README.md), so the parser under test is the option parser and not a protocol.
 *
 * Three muxers per input, because their private option tables are the interesting part and they
 * differ: mp4 has the largest table of the three, matroska has a different one, and null has almost
 * none, which is the case that leaves only AVFormatContext's own options in play.
 *
 * ── The NULL key ──
 *
 * All three option helpers refuse a NULL key the same way:
 *
 *   ffkmp_codecctx_set_opt   if (!c || !key) return AVERROR(EINVAL);   src/helpers_codec.c
 *   ffkmp_fmt_set_metadata   if (!c || !key) return AVERROR(EINVAL);   src/helpers_format.c
 *   ffkmp_fmt_set_opt        if (!c || !k)   return AVERROR(EINVAL);   src/helpers_format.c
 *
 * A NULL key used to reach av_opt_set through ffkmp_fmt_set_opt, and av_opt_set does not tolerate
 * it: it walks the option table with strcmp(o->name, name) and never tests `name`, which under
 * -fsanitize=address,undefined was a SEGV in strcmp called from av_opt_find2. The helper now
 * refuses a NULL key like its two siblings, and this target asserts that refusal on every input.
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

    /* Both guards of the helper, asserted on every input. */
    if (ffkmp_fmt_set_opt(NULL, key, value) != AVERROR(EINVAL)) abort();

    for (size_t i = 0; i < sizeof(MUXERS) / sizeof(MUXERS[0]); i++) {
        AVFormatContext *ctx = NULL;
        if (ffkmp_fmt_alloc_output2(&ctx, FUZZ_OUTPUT_PATH, MUXERS[i]) != 0) continue;
        if (ctx == NULL) abort();
        if (ffkmp_fmt_set_opt(ctx, NULL, value) != AVERROR(EINVAL)) abort();

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
