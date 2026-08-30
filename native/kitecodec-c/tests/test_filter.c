/* The filter-graph builders' own boundaries, all four of them found by reading the code.
 *
 * Every case here is about what the builder hands BACK rather than about whether a graph plays.
 * A graph that configures is proved by the real-media suites; what those cannot see is a caller
 * left holding freed pointers, a terminal label that was never appended, or a channel count one
 * direction accepts and the other refuses.
 */

#include "harness.h"
#include "kitecodec_helpers.h"

#include <libavfilter/buffersink.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <string.h>

/* ── the source array a failed multi-input build hands back ────────────────────────────── */

/* Builds an audio graph over `n` inputs whose LAST input carries an unusable sample format, so
 * the loop creates some sources and then fails. Returns the builder's own return code. */
static int build_failing_multi(kc_filter_ctx **srcs, int n)
{
    kc_filter_graph *graph = (kc_filter_graph *)0xdead;
    kc_filter_ctx *sink = (kc_filter_ctx *)0xdead;
    int rates[4], fmts[4], chans[4], tbn[4], tbd[4];
    int i;

    for (i = 0; i < n; i++) {
        rates[i] = 48000;
        /* The last one is not a sample format at all, which is the refusal the loop makes after
         * it has already created and published every earlier source. */
        fmts[i] = (i == n - 1) ? 9999 : AV_SAMPLE_FMT_FLTP;
        chans[i] = 2;
        tbn[i] = 1;
        tbd[i] = 48000;
    }
    return ffkmp_graph_build_audio_multi(&graph, srcs, &sink,
                                         "[in0][in1]amix=inputs=2[out]", n,
                                         rates, fmts, chans, tbn, tbd, -1, 0, 0);
}

static void case_failed_multi_build_leaves_no_dangling_sources(void)
{
    kc_filter_ctx *srcs[2];
    int rc;

    srcs[0] = (kc_filter_ctx *)0xdead;
    srcs[1] = (kc_filter_ctx *)0xdead;

    kc_case("a multi-input build that fails halfway clears every source it published");
    rc = build_failing_multi(srcs, 2);
    KC_CHECK(rc < 0);
    /* Source 0 was created and written into the caller's array before source 1's format was
     * refused, and the failure path frees the whole graph. Without the clear the caller is left
     * holding a pointer into a freed filter context that looks exactly like a good one. */
    KC_CHECK(srcs[0] == NULL);
    KC_CHECK(srcs[1] == NULL);
    kc_note("the array is the only thing the caller can inspect: the graph out-pointer is NULL");
    kc_note("on failure either way, so a dangling source is invisible until it is used");
}

/* ── the terminal label, found as a label and not as a substring ───────────────────────── */

/* Builds a ONE-input multi graph with format pins over the given description.
 *
 * The multi builders are where the label logic lives: they take a description that names its own
 * endpoints ([in0] … [out]), and they append an aformat chain only when the description has not
 * already closed itself. The single-input builder takes a bare chain with no labels at all and
 * appends unconditionally, so it has no such decision to make. */
static int build_pinned_multi(const char *description, int *out_rate)
{
    kc_filter_graph *graph = NULL;
    kc_filter_ctx *srcs[1] = { NULL };
    kc_filter_ctx *sink = NULL;
    int rates[1] = { 48000 };
    int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    int chans[1] = { 2 };
    int tbn[1] = { 1 };
    int tbd[1] = { 48000 };
    int rc;

    if (out_rate) *out_rate = 0;
    rc = ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                       rates, fmts, chans, tbn, tbd,
                                       AV_SAMPLE_FMT_S16, 44100, 2);
    if (rc == 0) {
        /* The sink's OWN answer, which is the only place the pins become observable. An
         * unlabelled chain still connects to the sink, so a graph that dropped its aformat
         * builds perfectly well and simply delivers the input rate. */
        if (out_rate) *out_rate = av_buffersink_get_sample_rate(sink);
        ffkmp_graph_free(&graph);
    }
    return rc;
}

static void case_out_label_is_a_label_not_a_substring(void)
{
    int rate = 0;

    /* A caller that closed its own chain keeps control of the output format, which is the
     * documented bargain: the builder appends nothing and the input rate comes out. */
    kc_case("a description that closes itself with [out] keeps its own format");
    KC_EQ_INT(build_pinned_multi("[in0]anull[out]", &rate), 0);
    KC_EQ_INT(rate, 48000);

    kc_case("a description with no [out] gets the pins the caller asked for");
    KC_EQ_INT(build_pinned_multi("[in0]anull", &rate), 0);
    KC_EQ_INT(rate, 44100);

    /* The whole point, and the reason it is not visible as a build failure. `[out]` inside a
     * QUOTED option value is not the graph's terminal label, and a substring search cannot tell
     * the two apart. Read as a label it made the builder skip its aformat pins, and an
     * unlabelled chain still connects to the sink, so the graph BUILT and quietly delivered the
     * input rate instead of the requested one. Nothing failed; the format was just wrong. */
    kc_case("[out] inside a quoted option value is not the terminal label");
    KC_EQ_INT(build_pinned_multi("[in0]ametadata=mode=add:key=k:value='[out]'", &rate), 0);
    KC_EQ_INT(rate, 44100);
    kc_note("with a substring search this built too, at 48000: the pins were silently dropped");

    kc_case("an escaped [out] is not the terminal label either");
    KC_EQ_INT(build_pinned_multi("[in0]ametadata=mode=add:key=k:value=x\\[out\\]", &rate), 0);
    KC_EQ_INT(rate, 44100);
}

/* ── the channel cap that only one direction had ───────────────────────────────────────── */

static void case_audio_fill_matches_its_reading_twin(void)
{
    kc_frame *f = ffkmp_frame_alloc();
    int channels = 10;
    int samples = 64;
    int needed;
    unsigned char *flat;
    int rc;

    KC_NOT_NULL(f);
    ffkmp_frame_set_format(f, AV_SAMPLE_FMT_FLTP);
    ffkmp_frame_set_nb_samples(f, samples);
    ffkmp_frame_set_ch_layout_default(f, channels);
    ffkmp_frame_set_sample_rate(f, 48000);
    KC_EQ_INT(ffkmp_frame_get_buffer(f, 0), 0);

    kc_case("ten planar channels: reading answers a size, so writing must accept the same one");
    needed = ffkmp_samples_get_buffer_size(f);
    KC_CHECK(needed > 0);
    flat = (unsigned char *)av_mallocz((size_t)needed);
    KC_NOT_NULL(flat);

    /* Both directions walk extended_data, which is the field that exists because `data` stops
     * at eight. The writer used to refuse above eight while the reader took any count, so a
     * 10-channel frame could be copied out of the library and never back in. */
    KC_EQ_INT(ffkmp_samples_copy_to_buffer(f, flat, needed), needed);
    rc = ffkmp_frame_fill_audio(f, flat, needed);
    KC_EQ_INT(rc, 0);
    kc_note("channels=%d, planes=%d, bytes=%d", channels, channels, needed);

    kc_case("a zero channel count is still refused, in both directions");
    ffkmp_frame_set_ch_layout_default(f, 0);
    KC_CHECK(ffkmp_frame_fill_audio(f, flat, needed) < 0);

    av_free(flat);
    ffkmp_frame_free(f);
}

int main(void)
{
    kc_suite_begin("test_filter");

    case_failed_multi_build_leaves_no_dangling_sources();
    case_out_label_is_a_label_not_a_substring();
    case_audio_fill_matches_its_reading_twin();

    return kc_suite_end();
}
