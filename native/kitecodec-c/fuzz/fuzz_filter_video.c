/* Fuzz target: the two video filter-graph builders, through avfilter_graph_parse_ptr.
 *
 * Entry points, and why these. The four graph builders were chosen because
 * each hands caller-controlled text straight to a parser:
 *
 *   ffkmp_graph_build_video        src/helpers_filter.c
 *   ffkmp_graph_build_video_multi  src/helpers_filter.c
 *
 * Both reach avfilter_graph_parse_ptr with the caller's description. The description arrives from
 * the public Kotlin FilterGraph API with no validation anywhere in between, so it is the widest
 * untrusted-text surface the helper layer has.
 *
 * The whole input is the description. Everything else is a fixed matrix, run in full for every
 * input, so one seed covers both builders rather than one of them:
 *
 *   single input, one 320x240 yuv420p source
 *   multi input, two sources at different sizes and pixel formats
 *   multi input, three sources, because ffkmp_graph_finish_multi_ composes the "in%d" labels into
 *     a char name[16] per input and the loop is where a label count would go wrong
 *
 * The parameters are small and fixed on purpose. Fuzzing them too would spend the budget on
 * arithmetic in a snprintf whose format string this target does not control, and would let a
 * description like scale=60000:60000 turn an out-of-memory into a reported finding that is a
 * resource question rather than a memory-safety one. Dimensions and rationals belong to another
 * target, together with the container bytes.
 *
 * What a finding here would look like: a stack write past `char args[512]`, a read past the
 * description's heap block, or a leaked graph on a failure path. The builders promise that every
 * failure path frees the graph and leaves the out parameters NULL, and this target asserts the
 * NULL half on every rejected input, so a builder that started returning a freed graph would be
 * caught by ASan on the next use rather than going unnoticed.
 */

#include "kc_fuzz.h"

#include <libavfilter/avfilter.h>
#include <libavutil/pixfmt.h>

#include <stdlib.h>

/* One source's parameters. Kept together so the multi-input arrays below are readable. */
typedef struct {
    int width;
    int height;
    int pix_fmt;
    int tb_num;
    int tb_den;
    int fr_num;
    int fr_den;
    int sar_num;
    int sar_den;
} video_input;

static const video_input INPUTS[3] = {
    { 320, 240, AV_PIX_FMT_YUV420P, 1, 25, 25, 1, 1, 1 },
    { 176, 144, AV_PIX_FMT_RGB24,   1, 30, 30, 1, 1, 1 },
    {  64,  64, AV_PIX_FMT_GRAY8,   1, 15, 15, 1, 1, 1 },
};

/* Single input. On success the caller owns the graph; on failure all three out parameters must be
 * NULL, which is the contract in the header and is asserted rather than assumed. */
static void build_single(const char *description) {
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    const video_input *in = &INPUTS[0];

    int rc = ffkmp_graph_build_video(
        &graph, &src, &sink, description,
        in->width, in->height, in->pix_fmt,
        in->tb_num, in->tb_den, in->fr_num, in->fr_den, in->sar_num, in->sar_den);

    if (rc == 0) {
        if (graph == NULL || src == NULL || sink == NULL) abort();
        ffkmp_graph_free(&graph);
        if (graph != NULL) abort();
    } else {
        if (graph != NULL || src != NULL || sink != NULL) abort();
    }
}

/* Multi input with `n` sources, 1 <= n <= 3. out_srcs is caller allocated and, per the header, is
 * NOT cleared on failure, so it is only read when the call returned 0. */
static void build_multi(const char *description, int n) {
    AVFilterGraph *graph = NULL;
    AVFilterContext *sink = NULL;
    AVFilterContext *srcs[3] = { NULL, NULL, NULL };
    int widths[3], heights[3], pix_fmts[3];
    int tb_nums[3], tb_dens[3], fr_nums[3], fr_dens[3], sar_nums[3], sar_dens[3];

    for (int i = 0; i < n; i++) {
        widths[i]   = INPUTS[i].width;
        heights[i]  = INPUTS[i].height;
        pix_fmts[i] = INPUTS[i].pix_fmt;
        tb_nums[i]  = INPUTS[i].tb_num;
        tb_dens[i]  = INPUTS[i].tb_den;
        fr_nums[i]  = INPUTS[i].fr_num;
        fr_dens[i]  = INPUTS[i].fr_den;
        sar_nums[i] = INPUTS[i].sar_num;
        sar_dens[i] = INPUTS[i].sar_den;
    }

    int rc = ffkmp_graph_build_video_multi(
        &graph, srcs, &sink, description, n,
        widths, heights, pix_fmts, tb_nums, tb_dens, fr_nums, fr_dens, sar_nums, sar_dens);

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

    build_single(description);
    build_multi(description, 1);
    build_multi(description, 2);
    build_multi(description, 3);

    kc_fuzz_free(description);
    return 0;
}
