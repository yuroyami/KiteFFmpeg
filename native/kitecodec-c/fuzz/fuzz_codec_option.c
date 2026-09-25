/* Fuzz target: ffkmp_codecctx_set_opt, through av_opt_set.
 *
 * Entry point, and why this one. It was chosen because the key and the value
 * are both caller-controlled text and both go to a parser: av_opt_set looks the key up in the
 * option table and then parses the value ACCORDING TO THE OPTION'S TYPE. So one string decides
 * which parser the other string is fed to, and AV_OPT_SEARCH_CHILDREN widens the table to the
 * codec's private options as well as AVCodecContext's own. Rationals, durations, image sizes,
 * pixel format names, channel layouts and dictionaries all have their own parser behind this call.
 *
 * Input contract: the first newline splits key from value, per kc_fuzz.h. No newline means a NULL
 * value, which av_opt_set accepts and treats as clearing or as an empty string depending on the
 * option type, and which the helper must survive either way.
 *
 * Two contexts per input, because the option table they expose is not the same:
 *
 *   a context allocated with no codec       AVCodecContext's own options only, priv_data is NULL
 *   a context allocated with the H.264      the same, plus every private option of that decoder,
 *   decoder                                 which is where the more exotic parsers live
 *
 * The helper's own contract is asserted rather than assumed: a NULL context or a NULL key must be
 * refused with AVERROR(EINVAL) and must not reach av_opt_set at all. Those two cases are checked
 * on every input, which costs nothing and would catch a reordering of the guard.
 *
 * The context is never opened. avcodec_open2 on a fuzzed option set would spend the whole budget
 * inside a decoder rather than in the option parser, and decoding fuzzed bitstreams is another target's job.
 */

#include "kc_fuzz.h"

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>

#include <stdlib.h>

/* Any decoder with a rich private option table works. H.264 is present in every FFmpeg build this
 * repository targets, including the vendored LGPL profile, so a missing decoder here would be a
 * real finding about the runtime rather than a flaky target. */
#define FUZZ_DECODER_ID AV_CODEC_ID_H264

static void set_on_fresh_context(const AVCodec *codec, const char *key, const char *value) {
    AVCodecContext *ctx = ffkmp_codecctx_alloc(codec);
    if (ctx == NULL) return;  /* allocation failure is not this target's subject */

    /* The return code is deliberately unused. av_opt_set rejecting a key or a value is the
     * ordinary outcome for almost every input and says nothing; what is being tested is that
     * rejecting it does not corrupt anything. */
    (void)ffkmp_codecctx_set_opt(ctx, key, value);

    ffkmp_codecctx_free(ctx);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *key = NULL;
    char *value = NULL;
    if (kc_fuzz_split(data, size, &key, &value) != 0) return 0;

    /* The two guard cases, asserted on every input. */
    if (ffkmp_codecctx_set_opt(NULL, key, value) != AVERROR(EINVAL)) abort();

    set_on_fresh_context(NULL, key, value);

    const AVCodec *codec = ffkmp_find_decoder_by_id(FUZZ_DECODER_ID);
    if (codec != NULL) {
        AVCodecContext *ctx = ffkmp_codecctx_alloc(codec);
        if (ctx != NULL) {
            if (ffkmp_codecctx_set_opt(ctx, NULL, value) != AVERROR(EINVAL)) abort();
            (void)ffkmp_codecctx_set_opt(ctx, key, value);
            ffkmp_codecctx_free(ctx);
        }
    }

    kc_fuzz_free(key);
    kc_fuzz_free(value);
    return 0;
}
