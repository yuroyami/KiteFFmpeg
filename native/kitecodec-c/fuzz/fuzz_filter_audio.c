/* Fuzz target: the two audio filter-graph builders, through avfilter_graph_parse_ptr.
 *
 * Entry points, and why these:
 *
 *   ffkmp_graph_build_audio        src/helpers_filter.c
 *   ffkmp_graph_build_audio_multi  src/helpers_filter.c
 *
 * This is the higher value of the two graph targets, and the reason is the description overflow. The audio
 * builders do not hand the description to the parser directly. They COMPOSE it, appending
 * `,aformat=sample_fmts=...:sample_rates=...:channel_layouts=...` into a fixed `char
 * full_desc[2048]` with repeated `n += snprintf(full_desc + n, sizeof(full_desc) - n, ...)`.
 * snprintf returns the length it WOULD have written, so once the running total passes the array
 * the next destination pointer leaves it and the next size argument wraps to a huge size_t. The fix
 * installed a length check after EVERY append; this target is what keeps them installed.
 *
 * The whole input is the description. The matrix run over every input is four builds, chosen so
 * that both composition sites and both of their branches are reached each time:
 *
 *   single input, pins off   the description goes to the parser whole
 *   single input, pins on    the four-append composition path, where the overflow was
 *   multi input, pins off    the "in%d" label loop with two sources
 *   multi input, pins on     the same composition, behind the strstr("[out]") test that decides
 *                            whether the multi builder appends at all
 *
 * The strstr test is why a seed carrying the literal `[out]` is worth having in the corpus and why
 * one is committed: it selects the branch that skips the append entirely, and a seed set without
 * one would leave that branch untested while looking complete.
 *
 * The pinned values are valid and fixed. A pinned sample format the library cannot name is
 * rejected before any append happens, so fuzzing the pins would mostly buy the early return.
 */

#include "kc_fuzz.h"

#include <libavfilter/avfilter.h>
#include <libavutil/samplefmt.h>

#include <stdlib.h>

/* Pinned output, per plan: a real format, a real rate, a real channel count. `,aformat=` plus all
 * three pins is the longest append the builders can make, which is the case that matters for the
 * running-length checks. */
#define PIN_SAMPLE_FMT   AV_SAMPLE_FMT_FLTP
#define PIN_SAMPLE_RATE  44100
#define PIN_CHANNELS     2
/* Stereo's native mask, so the pinned path also writes the exact-layout form of the pin. */
#define PIN_LAYOUT_MASK  0x3

/* Pins off, as the header documents it: -1 for the format, -1 for the rate, 0 for the channels. */
#define NO_PIN_SAMPLE_FMT   (-1)
#define NO_PIN_SAMPLE_RATE  (-1)
#define NO_PIN_CHANNELS     0
#define NO_PIN_LAYOUT_MASK  0

typedef struct {
    int sample_rate;
    int sample_fmt;
    int channels;
    int tb_num;
    int tb_den;
} audio_input;

static const audio_input INPUTS[2] = {
    { 48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000 },
    { 44100, AV_SAMPLE_FMT_S16,  1, 1, 44100 },
};

static void build_single(const char *description, int pinned) {
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    const audio_input *in = &INPUTS[0];

    int rc = ffkmp_graph_build_audio(
        &graph, &src, &sink, description,
        in->sample_rate, in->sample_fmt, in->channels, in->tb_num, in->tb_den,
        pinned ? PIN_SAMPLE_FMT : NO_PIN_SAMPLE_FMT,
        pinned ? PIN_SAMPLE_RATE : NO_PIN_SAMPLE_RATE,
        pinned ? PIN_CHANNELS : NO_PIN_CHANNELS,
        0, pinned ? PIN_LAYOUT_MASK : NO_PIN_LAYOUT_MASK);

    if (rc == 0) {
        if (graph == NULL || src == NULL || sink == NULL) abort();
        ffkmp_graph_free(&graph);
        if (graph != NULL) abort();
    } else {
        if (graph != NULL || src != NULL || sink != NULL) abort();
    }
}

static void build_multi(const char *description, int n, int pinned) {
    AVFilterGraph *graph = NULL;
    AVFilterContext *sink = NULL;
    AVFilterContext *srcs[2] = { NULL, NULL };
    int rates[2], fmts[2], channels[2], tb_nums[2], tb_dens[2];

    for (int i = 0; i < n; i++) {
        rates[i]    = INPUTS[i].sample_rate;
        fmts[i]     = INPUTS[i].sample_fmt;
        channels[i] = INPUTS[i].channels;
        tb_nums[i]  = INPUTS[i].tb_num;
        tb_dens[i]  = INPUTS[i].tb_den;
    }

    int rc = ffkmp_graph_build_audio_multi(
        &graph, srcs, &sink, description, n,
        rates, fmts, channels, tb_nums, tb_dens,
        pinned ? PIN_SAMPLE_FMT : NO_PIN_SAMPLE_FMT,
        pinned ? PIN_SAMPLE_RATE : NO_PIN_SAMPLE_RATE,
        pinned ? PIN_CHANNELS : NO_PIN_CHANNELS,
        NULL, pinned ? PIN_LAYOUT_MASK : NO_PIN_LAYOUT_MASK);

    if (rc == 0) {
        if (graph == NULL || sink == NULL) abort();
        for (int i = 0; i < n; i++) {
            if (srcs[i] == NULL) abort();
        }
        ffkmp_graph_free(&graph);
        if (graph != NULL) abort();
    } else {
        if (graph != NULL || sink != NULL) abort();
    }
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *description = kc_fuzz_dup(data, size);
    if (description == NULL) return 0;

    build_single(description, 0);
    build_single(description, 1);
    build_multi(description, 2, 0);
    build_multi(description, 2, 1);

    kc_fuzz_free(description);
    return 0;
}
