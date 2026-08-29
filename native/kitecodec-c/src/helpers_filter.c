/* Ordinary maintained source since the interlude (I-12). Lifted at B1.3 from the def body of
 * kiteffmpeg-core/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in KPKMP.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The filter part of the FFmpeg helper layer: the def's 'Filter graphs (single-input video / audio)' section(s). */

#include "kitecodec_helpers.h"

#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>

/* ════════════ Filter graphs (single-input video / audio) ════════════ */

KC_API int ffkmp_filter_exists(const char *name) {
    return (name && avfilter_get_by_name(name)) ? 1 : 0;
}

/* Shared tail of graph construction: wire a configured src/sink pair around the parsed
   `description` chain, then config the whole graph. Frees the graph on any failure. */
static int ffkmp_graph_finish_(
    AVFilterGraph *graph, AVFilterContext *src_ctx, AVFilterContext *sink_ctx,
    const char *description
) {
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs  = avfilter_inout_alloc();
    if (!outputs || !inputs) {
        avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
        return AVERROR(ENOMEM);
    }
    outputs->name = av_strdup("in");  outputs->filter_ctx = src_ctx;  outputs->pad_idx = 0; outputs->next = NULL;
    inputs->name  = av_strdup("out"); inputs->filter_ctx  = sink_ctx; inputs->pad_idx  = 0; inputs->next  = NULL;

    int rc = avfilter_graph_parse_ptr(graph, description, &inputs, &outputs, NULL);
    avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
    if (rc < 0) return rc;
    return avfilter_graph_config(graph, NULL);
}

KC_API int ffkmp_graph_build_video(
    AVFilterGraph **out_graph, AVFilterContext **out_src, AVFilterContext **out_sink,
    const char *description,
    int width, int height, int pix_fmt,
    int tb_num, int tb_den, int fr_num, int fr_den, int sar_num, int sar_den
) {
    if (!out_graph || !out_src || !out_sink) return AVERROR(EINVAL);
    *out_graph = NULL; *out_src = NULL; *out_sink = NULL;
    if (!description) return AVERROR(EINVAL);
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("buffer");
    const AVFilter *sink = avfilter_get_by_name("buffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    /* FFmpeg 8: the `buffer` filter wants pix_fmt as a NAME, not an int. An unknown format is an
       argument error, NOT an invitation to substitute yuv420p: a graph silently built for a
       different format than the caller's frames misreads every plane. */
    const char *pix_fmt_name = av_get_pix_fmt_name((enum AVPixelFormat)pix_fmt);
    if (!pix_fmt_name) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }

    char args[512];
    snprintf(args, sizeof(args),
        "video_size=%dx%d:pix_fmt=%s:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d",
        width, height, pix_fmt_name, tb_num, tb_den ? tb_den : 1,
        sar_num ? sar_num : 1, sar_den ? sar_den : 1,
        fr_num ? fr_num : 30, fr_den ? fr_den : 1);

    AVFilterContext *src_ctx = NULL, *sink_ctx = NULL;
    int rc = avfilter_graph_create_filter(&src_ctx, src, "in", args, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    /* Don't constrain the buffersink output format, let the graph auto-negotiate. Converting
       whatever it emits into the encoder's pixel format is EncoderCore's job (see conversionFor
       in MediaSink.native.kt), which is what makes an unpinned graph safe here. Pinning is still
       cheaper: put `format=yuv420p` last in the description and the conversion becomes a no-op. */

    rc = ffkmp_graph_finish_(graph, src_ctx, sink_ctx, description);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    *out_graph = graph; *out_src = src_ctx; *out_sink = sink_ctx;
    return 0;
}

KC_API int ffkmp_graph_build_audio(
    AVFilterGraph **out_graph, AVFilterContext **out_src, AVFilterContext **out_sink,
    const char *description,
    int sample_rate, int sample_fmt, int channels,
    int tb_num, int tb_den,
    /* Pin the graph's output so frames arrive encoder-ready. Pass -1/-1/0 to leave free.
       Implemented by appending an `aformat` filter rather than buffersink options, because the
       option names were renamed across FFmpeg 7→8, the filter-string syntax never changes. */
    int out_sample_fmt, int out_sample_rate, int out_channels
) {
    if (!out_graph || !out_src || !out_sink) return AVERROR(EINVAL);
    *out_graph = NULL; *out_src = NULL; *out_sink = NULL;
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("abuffer");
    const AVFilter *sink = avfilter_get_by_name("abuffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    const char *fmt_name = av_get_sample_fmt_name((enum AVSampleFormat)sample_fmt);
    if (!fmt_name) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }

    AVChannelLayout in_layout;
    av_channel_layout_default(&in_layout, channels > 0 ? channels : 2);
    char layout_str[128];
    int rc = av_channel_layout_describe(&in_layout, layout_str, sizeof(layout_str));
    av_channel_layout_uninit(&in_layout);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    char args[512];
    snprintf(args, sizeof(args),
        "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
        tb_num, tb_den ? tb_den : 1, sample_rate, fmt_name, layout_str);

    AVFilterContext *src_ctx = NULL, *sink_ctx = NULL;
    rc = avfilter_graph_create_filter(&src_ctx, src, "in", args, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    /* Compose `<description>,aformat=…` with only the requested pins. `Nc` is the
       layout-from-channel-count syntax, which avoids names like "5.1(side)" whose parens
       would fight the filter-string parser.

       The description is public API input of any length, and snprintf returns the length it
       WOULD have written, not what it wrote. So the running total must be checked against the
       buffer after EVERY append and before the next one computes `full_desc + n`: past the end
       that pointer leaves the array and `sizeof(full_desc) - n` wraps to a huge size_t. A
       description that does not leave room for the pins is refused, never truncated. */
    char full_desc[2048];
    int n = snprintf(full_desc, sizeof(full_desc), "%s",
                     (description && description[0]) ? description : "anull");
    if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
    if (out_sample_fmt >= 0 || out_sample_rate > 0 || out_channels > 0) {
        n += snprintf(full_desc + n, sizeof(full_desc) - n, ",aformat=");
        if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        int first = 1;
        if (out_sample_fmt >= 0) {
            const char *ofmt = av_get_sample_fmt_name((enum AVSampleFormat)out_sample_fmt);
            if (!ofmt) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            n += snprintf(full_desc + n, sizeof(full_desc) - n, "sample_fmts=%s", ofmt);
            if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            first = 0;
        }
        if (out_sample_rate > 0) {
            n += snprintf(full_desc + n, sizeof(full_desc) - n, "%ssample_rates=%d", first ? "" : ":", out_sample_rate);
            if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            first = 0;
        }
        if (out_channels > 0) {
            n += snprintf(full_desc + n, sizeof(full_desc) - n, "%schannel_layouts=%dc", first ? "" : ":", out_channels);
            if (n < 0 || n >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        }
    }

    rc = ffkmp_graph_finish_(graph, src_ctx, sink_ctx, full_desc);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    *out_graph = graph; *out_src = src_ctx; *out_sink = sink_ctx;
    return 0;
}

/* ── Multi-input variants ──
   The description references inputs as [in0]…[inN-1] and the output as [out], e.g.
   "[in0][in1]overlay=10:10[out]" or "[in0][in1]amix=inputs=2[out]". Caller passes parallel
   arrays of per-input parameters and a caller-allocated out_srcs array of length n. */

static int ffkmp_graph_finish_multi_(
    AVFilterGraph *graph, AVFilterContext **src_ctxs, int n, AVFilterContext *sink_ctx,
    const char *description
) {
    AVFilterInOut *outputs = NULL, *outputs_tail = NULL;
    for (int i = 0; i < n; i++) {
        AVFilterInOut *io = avfilter_inout_alloc();
        char name[16];
        snprintf(name, sizeof(name), "in%d", i);
        if (!io) { avfilter_inout_free(&outputs); return AVERROR(ENOMEM); }
        io->name = av_strdup(name);
        io->filter_ctx = src_ctxs[i];
        io->pad_idx = 0; io->next = NULL;
        if (!outputs) outputs = io; else outputs_tail->next = io;
        outputs_tail = io;
    }
    AVFilterInOut *inputs = avfilter_inout_alloc();
    if (!inputs) { avfilter_inout_free(&outputs); return AVERROR(ENOMEM); }
    inputs->name = av_strdup("out");
    inputs->filter_ctx = sink_ctx;
    inputs->pad_idx = 0; inputs->next = NULL;

    int rc = avfilter_graph_parse_ptr(graph, description, &inputs, &outputs, NULL);
    avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
    if (rc < 0) return rc;
    return avfilter_graph_config(graph, NULL);
}

KC_API int ffkmp_graph_build_video_multi(
    AVFilterGraph **out_graph, AVFilterContext **out_srcs, AVFilterContext **out_sink,
    const char *description, int n,
    const int *widths, const int *heights, const int *pix_fmts,
    const int *tb_nums, const int *tb_dens,
    const int *fr_nums, const int *fr_dens,
    const int *sar_nums, const int *sar_dens
) {
    if (!out_graph || !out_srcs || !out_sink) return AVERROR(EINVAL);
    *out_graph = NULL; *out_sink = NULL;
    if (!description || n <= 0 || !widths || !heights || !pix_fmts ||
        !tb_nums || !tb_dens || !fr_nums || !fr_dens || !sar_nums || !sar_dens) {
        return AVERROR(EINVAL);
    }
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("buffer");
    const AVFilter *sink = avfilter_get_by_name("buffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    for (int i = 0; i < n; i++) {
        /* The same refusal the single-input builder makes, for the same reason (audit P1-22):
           substituting yuv420p for a format FFmpeg does not know builds a graph for a layout the
           caller's frames are not in, and every plane is then read at the wrong stride and depth.
           An unknown format is an argument error. */
        const char *pf = av_get_pix_fmt_name((enum AVPixelFormat)pix_fmts[i]);
        if (!pf) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        char args[512], name[16];
        snprintf(args, sizeof(args),
            "video_size=%dx%d:pix_fmt=%s:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d",
            widths[i], heights[i], pf, tb_nums[i], tb_dens[i] ? tb_dens[i] : 1,
            sar_nums[i] ? sar_nums[i] : 1, sar_dens[i] ? sar_dens[i] : 1,
            fr_nums[i] ? fr_nums[i] : 30, fr_dens[i] ? fr_dens[i] : 1);
        snprintf(name, sizeof(name), "in%d", i);
        out_srcs[i] = NULL;
        int rc = avfilter_graph_create_filter(&out_srcs[i], src, name, args, NULL, graph);
        if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    }
    AVFilterContext *sink_ctx = NULL;
    int rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    rc = ffkmp_graph_finish_multi_(graph, out_srcs, n, sink_ctx, description);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    *out_graph = graph; *out_sink = sink_ctx;
    return 0;
}

KC_API int ffkmp_graph_build_audio_multi(
    AVFilterGraph **out_graph, AVFilterContext **out_srcs, AVFilterContext **out_sink,
    const char *description, int n,
    const int *sample_rates, const int *sample_fmts, const int *channels,
    const int *tb_nums, const int *tb_dens,
    int out_sample_fmt, int out_sample_rate, int out_channels
) {
    if (!out_graph || !out_srcs || !out_sink) return AVERROR(EINVAL);
    *out_graph = NULL; *out_sink = NULL;
    if (n <= 0 || !sample_rates || !sample_fmts || !channels || !tb_nums || !tb_dens) {
        return AVERROR(EINVAL);
    }
    if ((!description || !description[0]) && n != 1) return AVERROR(EINVAL);
    AVFilterGraph *graph = avfilter_graph_alloc();
    if (!graph) return AVERROR(ENOMEM);
    const AVFilter *src = avfilter_get_by_name("abuffer");
    const AVFilter *sink = avfilter_get_by_name("abuffersink");
    if (!src || !sink) { avfilter_graph_free(&graph); return AVERROR_FILTER_NOT_FOUND; }

    for (int i = 0; i < n; i++) {
        const char *fmt = av_get_sample_fmt_name((enum AVSampleFormat)sample_fmts[i]);
        if (!fmt) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
        AVChannelLayout lay;
        av_channel_layout_default(&lay, channels[i] > 0 ? channels[i] : 2);
        char lay_str[128];
        int rc = av_channel_layout_describe(&lay, lay_str, sizeof(lay_str));
        av_channel_layout_uninit(&lay);
        if (rc < 0) { avfilter_graph_free(&graph); return rc; }
        char args[512], name[16];
        snprintf(args, sizeof(args),
            "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
            tb_nums[i], tb_dens[i] ? tb_dens[i] : 1, sample_rates[i], fmt, lay_str);
        snprintf(name, sizeof(name), "in%d", i);
        out_srcs[i] = NULL;
        rc = avfilter_graph_create_filter(&out_srcs[i], src, name, args, NULL, graph);
        if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    }
    AVFilterContext *sink_ctx = NULL;
    int rc = avfilter_graph_create_filter(&sink_ctx, sink, "out", NULL, NULL, graph);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }

    /* Same aformat pinning trick as the single-input audio builder, and the same rule about
       checking the running length after every append. See that builder for why. */
    int generated_default = !description || !description[0];
    char full_desc[2048];
    int len = snprintf(full_desc, sizeof(full_desc), "%s",
                       generated_default ? "[in0]anull" : description);
    if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
    if (out_sample_fmt >= 0 || out_sample_rate > 0 || out_channels > 0) {
        /* The pinned chain must hang off [out]'s producer. Renaming a caller's terminal label
           would be intrusive, so an explicit [out] means the caller controls formats; Transcoder
           always appends its own aformat before [out]. The generated default deliberately has no
           [out] yet, allowing requested pins to be appended before the label is closed below. */
        if (!strstr(full_desc, "[out]")) {
            int first = 1;
            len += snprintf(full_desc + len, sizeof(full_desc) - len, ",aformat=");
            if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            if (out_sample_fmt >= 0) {
                const char *of = av_get_sample_fmt_name((enum AVSampleFormat)out_sample_fmt);
                if (!of) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
                len += snprintf(full_desc + len, sizeof(full_desc) - len, "sample_fmts=%s", of);
                if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
                first = 0;
            }
            if (out_sample_rate > 0) {
                len += snprintf(full_desc + len, sizeof(full_desc) - len, "%ssample_rates=%d", first ? "" : ":", out_sample_rate);
                if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
                first = 0;
            }
            if (out_channels > 0) {
                len += snprintf(full_desc + len, sizeof(full_desc) - len, "%schannel_layouts=%dc", first ? "" : ":", out_channels);
                if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
            }
        }
    }
    if (generated_default) {
        len += snprintf(full_desc + len, sizeof(full_desc) - len, "[out]");
        if (len < 0 || len >= (int)sizeof(full_desc)) { avfilter_graph_free(&graph); return AVERROR(EINVAL); }
    }

    rc = ffkmp_graph_finish_multi_(graph, out_srcs, n, sink_ctx, full_desc);
    if (rc < 0) { avfilter_graph_free(&graph); return rc; }
    *out_graph = graph; *out_sink = sink_ctx;
    return 0;
}

KC_API void ffkmp_graph_free(AVFilterGraph **g) { if (g && *g) avfilter_graph_free(g); }
KC_API int  ffkmp_graph_send(AVFilterContext *src, AVFrame *frame) {
    return src ? av_buffersrc_add_frame_flags(src, frame, AV_BUFFERSRC_FLAG_KEEP_REF)
               : AVERROR(EINVAL);
}
KC_API int  ffkmp_graph_receive(AVFilterContext *sink, AVFrame *frame) {
    return (sink && frame) ? av_buffersink_get_frame(sink, frame) : AVERROR(EINVAL);
}
/* Fixed-frame-size pull: AAC & friends require exactly frame_size samples per encode call.
   Setting this makes the buffersink chunk its output accordingly (last frame may be short). */
KC_API void ffkmp_buffersink_set_frame_size(AVFilterContext *sink, unsigned n) {
    if (sink && n > 0) av_buffersink_set_frame_size(sink, n);
}
/* The time-base frames carry when they leave the graph. Filters like fps/atempo change it,
   so the consumer must read it from the sink rather than assume the input time-base. */
KC_API void ffkmp_buffersink_time_base(AVFilterContext *sink, int *n, int *d) {
    if (!sink || !n || !d) { if (n) *n = 0; if (d) *d = 1; return; }
    AVRational tb = av_buffersink_get_time_base(sink);
    *n = tb.num; *d = tb.den ? tb.den : 1;
}
