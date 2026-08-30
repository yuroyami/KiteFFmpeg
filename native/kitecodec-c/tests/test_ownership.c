/* Ownership and lifetime suite for the extracted FFmpeg helper layer.
 *
 * Register items B1-14 (the allocation interposer is the local leak instrument, because
 * LeakSanitizer is unsupported on macOS arm64) and B1-23 (the per-call SwsContext inside
 * ffkmp_frame_convert_pixfmt is kept as B2's caching baseline, so this suite asserts what the
 * helper does today rather than what it ought to do).
 *
 * What "ownership helper" means here, stated so the coverage claim is checkable. An exported
 * helper belongs in this suite when its body reaches a libav function that allocates a heap
 * object, frees one, or moves a reference. Applied mechanically to the nine src/helpers_*.c units
 * that selects 39 of the 157 exported helpers, listed below by section, and every one of them is
 * called by a case here. Plan section 15.2 says 29 and never enumerates them; 39 is a superset
 * of any 29 that reading could pick, so the plan's requirement is met either way, and the
 * difference is recorded in the run report rather than silently resolved. The two internal
 * helpers ffkmp_graph_finish_ and ffkmp_graph_finish_multi_ also allocate
 * (avfilter_inout_alloc, av_strdup); they are static and unreachable by name, so they are
 * covered through the four graph builders that call them.
 *
 * The set was 43 until B1.4, which deleted four of them as dead exported surface: frame_ref,
 * frame_make_writable, packet_ref and fmt_alloc_output, none of which any Kotlin file imported
 * (register item B1-08). Their cases went with them, except that container inference from the
 * path extension moved to ffkmp_fmt_alloc_output2, which takes the same path with a NULL format.
 *
 *   Frames  (7)  frame_alloc, frame_free, frame_unref, frame_get_buffer, frame_clone,
 *                frame_convert_pixfmt, frame_set_ch_layout_default
 *   Packets (4)  packet_alloc, packet_free, packet_unref, packet_move_ref
 *   Codecs  (8)  codecctx_alloc, codecctx_free, codecctx_open, codecctx_from_par,
 *                codecctx_set_audio, codecctx_set_opt, codecpar_from_context,
 *                codecpar_copy_for_mux
 *   Demux   (4)  fmt_open_input, fmt_close_input, fmt_find_stream_info, fmt_read_frame
 *   Mux     (9)  fmt_alloc_output2, fmt_set_opt, fmt_set_metadata, fmt_new_stream, fmt_io_open,
 *                fmt_write_header, fmt_write_frame, fmt_write_trailer, fmt_free_output
 *   Graphs  (7)  graph_build_video, graph_build_audio, graph_build_video_multi,
 *                graph_build_audio_multi, graph_free, graph_send, graph_receive
 *
 * The three whose pairing rule is not the obvious one each have their own case, as plan section
 * 15.2 requires:
 *
 *   ffkmp_fmt_new_stream        allocates and the parent AVFormatContext owns the result. The
 *                               blocks are still live when the helper returns, and they go away
 *                               when the parent is freed. Both halves are asserted.
 *   ffkmp_frame_convert_pixfmt  allocates and frees an SwsContext on every call and returns a
 *                               caller-owned frame. Asserted by freeing only the frame and
 *                               finding the window balanced, which is what proves no context
 *                               was retained, and by repeating the call so a per-call leak
 *                               would accumulate.
 *   ffkmp_fmt_free_output       closes ctx->pb only when one was opened and the muxer is not
 *                               AVFMT_NOFILE. Both branches have a case.
 *
 * How a case is measured. Every case runs its sequence twice: once as a warm-up whose
 * allocations are not looked at, then once inside a measured window. The warm-up is not
 * decoration. Measured on this machine, the first open, find_stream_info, read and close cycle
 * over the same file leaves 3 blocks live and every later cycle leaves 0, because libavformat
 * builds one-time state on the first pass. Without the warm-up this suite would report a leak
 * that is not one, and a real per-call leak would still be caught, because it repeats.
 *
 * Where the evidence lives per variant. kc_alloc_active() returns 1 under plain and 0 under
 * asan and tsan, since both sanitizer runtimes replace the allocator before dyld reaches the
 * interpose section. So the pairing numbers come from the plain run, and KC_ALLOC_BALANCED
 * records a partial in the other two. The functional assertions in every case run in all three
 * variants, and asan is what makes the use-after-free and out-of-bounds classes visible.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/pixfmt.h>
#include <libavutil/samplefmt.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ---- Inner assertions ----
 *
 * A case that needs to look at the window in the middle uses these rather than KC_ALLOC_LIVE,
 * so that a variant which cannot observe allocation records exactly one partial per case, from
 * the outer KC_ALLOC_BALANCED, instead of one per assertion. The first case of the suite proves
 * that kc_detail itself allocates nothing, which is what makes it safe to call these inside a
 * measured window. */

#define OWN_LIVE_EXACTLY(measure, before, expected, what) \
    do { \
        if ((measure) && kc_alloc_active()) { \
            long long own_live_ = kc_alloc_live_delta(before); \
            kc_detail("%s=%lld", what, own_live_); \
            if (own_live_ != (long long)(expected)) \
                KC_FAIL("%s: %lld blocks live, expected %lld", \
                        what, own_live_, (long long)(expected)); \
        } \
    } while (0)

#define OWN_LIVE_POSITIVE(measure, before, what) \
    do { \
        if ((measure) && kc_alloc_active()) { \
            long long own_live_ = kc_alloc_live_delta(before); \
            kc_detail("%s=%lld", what, own_live_); \
            if (own_live_ <= 0) \
                KC_FAIL("%s: %lld blocks live, expected at least one", what, own_live_); \
        } \
    } while (0)

#define OWN_LIVE_NEGATIVE(measure, before, what) \
    do { \
        if ((measure) && kc_alloc_active()) { \
            long long own_live_ = kc_alloc_live_delta(before); \
            kc_detail("%s=%lld", what, own_live_); \
            if (own_live_ >= 0) \
                KC_FAIL("%s: %lld blocks live, expected a release", what, own_live_); \
        } \
    } while (0)

/* ---- Fixtures ---- */

static char wav_path[1024];
static char out_path[1024];
static char mp4_path[1024];
static char bad_path[1024];

static void build_paths(void)
{
    const char *tmp = getenv("TMPDIR");
    const char *sep = "/";
    if (tmp == NULL || tmp[0] == '\0')
        tmp = "/tmp";
    if (tmp[strlen(tmp) - 1] == '/')
        sep = "";
    snprintf(wav_path, sizeof(wav_path), "%s%skc_own_%ld_in.wav", tmp, sep, (long)getpid());
    snprintf(out_path, sizeof(out_path), "%s%skc_own_%ld_out.wav", tmp, sep, (long)getpid());
    snprintf(mp4_path, sizeof(mp4_path), "%s%skc_own_%ld_out.mp4", tmp, sep, (long)getpid());
    snprintf(bad_path, sizeof(bad_path), "%s%skc_own_%ld_absent/nested.wav", tmp, sep,
             (long)getpid());
}

/* Registered with atexit rather than called at the end of main, so a suite that exits on its
 * first failing case still cleans up after itself. */
static void remove_paths(void)
{
    remove(wav_path);
    remove(out_path);
    remove(mp4_path);
}

static AVFrame *video_frame(int w, int h, int fmt)
{
    AVFrame *f = ffkmp_frame_alloc();
    KC_NOT_NULL(f);
    ffkmp_frame_set_width(f, w);
    ffkmp_frame_set_height(f, h);
    ffkmp_frame_set_format(f, fmt);
    KC_EQ_INT(ffkmp_frame_get_buffer(f, 0), 0);
    KC_NOT_NULL(ffkmp_frame_plane(f, 0));
    return f;
}

static AVFrame *audio_frame(int rate, int fmt, int channels, int samples)
{
    AVFrame *f = ffkmp_frame_alloc();
    KC_NOT_NULL(f);
    ffkmp_frame_set_format(f, fmt);
    ffkmp_frame_set_sample_rate(f, rate);
    ffkmp_frame_set_nb_samples(f, samples);
    ffkmp_frame_set_ch_layout_default(f, channels);
    KC_EQ_INT(ffkmp_frame_get_buffer(f, 0), 0);
    ffkmp_frame_set_pts(f, 0);
    return f;
}

/* An AVPacket carrying a real reference counted buffer. av_new_packet is a raw libav call on
 * purpose: no helper allocates packet payload, and a test may use libav directly. */
static AVPacket *filled_packet(int bytes)
{
    AVPacket *p = ffkmp_packet_alloc();
    KC_NOT_NULL(p);
    KC_EQ_INT(av_new_packet(p, bytes), 0);
    memset(ffkmp_packet_data(p), 0, (size_t)bytes);
    KC_EQ_INT(ffkmp_packet_size(p), bytes);
    return p;
}

/* Describes the pcm_s16le stream that every mux and demux case in this suite uses. Written
 * through the codecpar the stream already owns, which is how MediaSink does it too. */
static void describe_pcm_stream(AVStream *st)
{
    AVCodecParameters *par = ffkmp_stream_codecpar(st);
    KC_NOT_NULL(par);
    par->codec_type = AVMEDIA_TYPE_AUDIO;
    par->codec_id = AV_CODEC_ID_PCM_S16LE;
    par->format = AV_SAMPLE_FMT_S16;
    par->sample_rate = 48000;
    par->bit_rate = 48000 * 2 * 16;
    av_channel_layout_default(&par->ch_layout, 2);
    ffkmp_stream_set_time_base(st, 1, 48000);
}

/* Writes a four packet pcm_s16le wav through the mux helpers. Used both as the fixture the
 * demux cases read and as the sequence the mux session case measures. */
static void write_wav(const char *path)
{
    AVFormatContext *ctx = NULL;
    AVStream *st = NULL;
    int i;
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, path, "wav"), 0);
    KC_NOT_NULL(ctx);
    st = ffkmp_fmt_new_stream(ctx, NULL);
    KC_NOT_NULL(st);
    describe_pcm_stream(st);
    KC_EQ_INT(ffkmp_fmt_io_open(ctx, path), 0);
    KC_EQ_INT(ffkmp_fmt_write_header(ctx), 0);
    for (i = 0; i < 4; i++) {
        AVPacket *p = filled_packet(4096);
        ffkmp_packet_set_stream_index(p, 0);
        ffkmp_packet_set_pts(p, (int64_t)i * 1024);
        ffkmp_packet_set_dts(p, (int64_t)i * 1024);
        KC_EQ_INT(ffkmp_fmt_write_frame(ctx, p), 0);
        /* The muxer took the packet's reference. This is the ownership rule of
         * av_interleaved_write_frame and the reason the caller must not unref the payload
         * itself: the packet comes back empty. */
        KC_EQ_INT(ffkmp_packet_size(p), 0);
        KC_NULL(ffkmp_packet_data(p));
        ffkmp_packet_free(p);
    }
    KC_EQ_INT(ffkmp_fmt_write_trailer(ctx), 0);
    ffkmp_fmt_free_output(&ctx);
    KC_NULL(ctx);
}

/* ---- Cases ---- */

/* Instrument check, and the licence for every OWN_LIVE_* call inside a measured window: the
 * harness's own reporting path must not allocate, or every window would carry the harness's
 * noise instead of the helper's behaviour. */
static void case_harness_does_not_allocate(int measure)
{
    kc_alloc_counts before;
    kc_alloc_snapshot(&before);
    /* Only in the measured pass, so the case line carries one probe rather than two. */
    if (measure)
        kc_detail("probe=%d", 1);
    OWN_LIVE_EXACTLY(measure, &before, 0, "kc_detail");
}

static void case_frame_alloc_free(int measure)
{
    kc_alloc_counts before;
    AVFrame *f;
    kc_alloc_snapshot(&before);
    f = ffkmp_frame_alloc();
    KC_NOT_NULL(f);
    /* av_frame_alloc is one av_mallocz of one AVFrame, so the count is structural rather than
     * a measured constant that a future FFmpeg could move. */
    OWN_LIVE_EXACTLY(measure, &before, 1, "after_alloc");
    KC_EQ_INT(ffkmp_frame_width(f), 0);
    ffkmp_frame_free(f);
}

static void case_frame_get_buffer_and_unref(int measure)
{
    kc_alloc_counts before;
    kc_alloc_counts held;
    AVFrame *f = ffkmp_frame_alloc();
    KC_NOT_NULL(f);
    ffkmp_frame_set_width(f, 64);
    ffkmp_frame_set_height(f, 64);
    ffkmp_frame_set_format(f, AV_PIX_FMT_YUV420P);
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_frame_get_buffer(f, 0), 0);
    KC_NOT_NULL(ffkmp_frame_plane(f, 0));
    KC_CHECKF(ffkmp_frame_linesize(f, 0) >= 64, "linesize %d is below the width",
              ffkmp_frame_linesize(f, 0));
    OWN_LIVE_POSITIVE(measure, &before, "buffers");
    kc_alloc_snapshot(&held);
    ffkmp_frame_unref(f);
    /* unref must drop the pixel buffers and reset the descriptive fields, and must leave the
     * AVFrame itself alive so the frame can be reused. */
    OWN_LIVE_NEGATIVE(measure, &held, "after_unref");
    KC_NULL(ffkmp_frame_plane(f, 0));
    KC_EQ_INT(ffkmp_frame_width(f), 0);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
    ffkmp_frame_free(f);
}

static void case_frame_clone(int measure)
{
    AVFrame *src = video_frame(48, 32, AV_PIX_FMT_YUV420P);
    kc_alloc_counts before;
    AVFrame *clone;
    ffkmp_frame_set_pts(src, 4242);
    kc_alloc_snapshot(&before);
    clone = ffkmp_frame_clone(src);
    KC_NOT_NULL(clone);
    /* The clone is caller owned and shares the pixels. Both halves matter: the first is why
     * the caller must free it, the second is why cloning is O(1). */
    KC_EQ_PTR(ffkmp_frame_plane(clone, 0), ffkmp_frame_plane(src, 0));
    KC_EQ_I64(ffkmp_frame_pts(clone), 4242);
    OWN_LIVE_POSITIVE(measure, &before, "clone");
    ffkmp_frame_free(clone);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
    ffkmp_frame_free(src);
}

static void case_frame_clone_refuses_null(int measure)
{
    kc_alloc_counts before;
    kc_alloc_snapshot(&before);
    KC_NULL(ffkmp_frame_clone(NULL));
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

/* B1-23, first of the three awkward helpers by plan order but kept next to the frame cases
 * because that is what it returns. */
static void case_frame_convert_pixfmt_is_caller_owned(int measure)
{
    AVFrame *src = video_frame(32, 32, AV_PIX_FMT_YUV420P);
    kc_alloc_counts before;
    AVFrame *dst;
    ffkmp_frame_set_pts(src, 77);
    kc_alloc_snapshot(&before);
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGB24);
    KC_NOT_NULL(dst);
    KC_EQ_INT(ffkmp_frame_format(dst), AV_PIX_FMT_RGB24);
    KC_EQ_INT(ffkmp_frame_width(dst), 32);
    KC_EQ_INT(ffkmp_frame_height(dst), 32);
    KC_EQ_I64(ffkmp_frame_pts(dst), 77);
    KC_NOT_NULL(ffkmp_frame_plane(dst, 0));
    OWN_LIVE_POSITIVE(measure, &before, "returned");
    ffkmp_frame_free(dst);
    /* Freeing the frame alone brings the window back to zero. That is the assertion that the
     * per-call SwsContext was freed inside the helper: if it were retained, this would be
     * positive and no caller could ever release it. B2 will cache the context, and this row is
     * the baseline it has to match. */
    OWN_LIVE_EXACTLY(measure, &before, 0, "after_free");
    ffkmp_frame_free(src);
}

static void case_frame_convert_pixfmt_repeated_does_not_accumulate(int measure)
{
    AVFrame *src = video_frame(32, 32, AV_PIX_FMT_YUV420P);
    kc_alloc_counts before;
    int i;
    kc_alloc_snapshot(&before);
    for (i = 0; i < 8; i++) {
        AVFrame *dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGB24);
        KC_NOT_NULL(dst);
        ffkmp_frame_free(dst);
    }
    /* Eight conversions, eight contexts allocated and freed. A context kept per call would
     * show up here as eight retained blocks rather than as one, which is what makes this row
     * stronger than the single call above. */
    OWN_LIVE_EXACTLY(measure, &before, 0, "after_eight");
    ffkmp_frame_free(src);
}

static void case_frame_convert_pixfmt_refuses_bad_input(int measure)
{
    kc_alloc_counts before;
    AVFrame *empty = ffkmp_frame_alloc();
    KC_NOT_NULL(empty);
    kc_alloc_snapshot(&before);
    KC_NULL(ffkmp_frame_convert_pixfmt(NULL, AV_PIX_FMT_RGB24));
    /* Zero sized source: refused before any context or frame is allocated. The error path is
     * where a per-call allocator usually leaks. */
    KC_NULL(ffkmp_frame_convert_pixfmt(empty, AV_PIX_FMT_RGB24));
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
    ffkmp_frame_free(empty);
}

static void case_frame_set_ch_layout_default(int measure)
{
    AVFrame *f = ffkmp_frame_alloc();
    kc_alloc_counts before;
    KC_NOT_NULL(f);
    kc_alloc_snapshot(&before);
    ffkmp_frame_set_ch_layout_default(f, 6);
    KC_EQ_INT(ffkmp_frame_channels(f), 6);
    /* Called twice on purpose. The second call runs the av_channel_layout_uninit branch over a
     * layout that is already set, which is the only path in this helper that can release
     * anything. */
    ffkmp_frame_set_ch_layout_default(f, 2);
    KC_EQ_INT(ffkmp_frame_channels(f), 2);
    ffkmp_frame_set_ch_layout_default(NULL, 2);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
    ffkmp_frame_free(f);
}

static void case_packet_alloc_free(int measure)
{
    kc_alloc_counts before;
    AVPacket *p;
    kc_alloc_snapshot(&before);
    p = ffkmp_packet_alloc();
    KC_NOT_NULL(p);
    OWN_LIVE_EXACTLY(measure, &before, 1, "after_alloc");
    KC_EQ_INT(ffkmp_packet_size(p), 0);
    ffkmp_packet_free(p);
}

static void case_packet_unref_releases_payload(int measure)
{
    kc_alloc_counts before;
    AVPacket *p;
    kc_alloc_snapshot(&before);
    p = filled_packet(4096);
    OWN_LIVE_POSITIVE(measure, &before, "with_payload");
    ffkmp_packet_unref(p);
    KC_EQ_INT(ffkmp_packet_size(p), 0);
    KC_NULL(ffkmp_packet_data(p));
    OWN_LIVE_EXACTLY(measure, &before, 1, "packet_only");
    ffkmp_packet_free(p);
}

static void case_packet_move_ref_transfers_payload(int measure)
{
    AVPacket *src = filled_packet(1024);
    AVPacket *dst = ffkmp_packet_alloc();
    kc_alloc_counts before;
    const uint8_t *payload = ffkmp_packet_data(src);
    KC_NOT_NULL(dst);
    kc_alloc_snapshot(&before);
    ffkmp_packet_move_ref(dst, src);
    /* A move, not a copy: the same payload, and the source left empty. The one nobody may
     * unref twice. */
    KC_EQ_PTR(ffkmp_packet_data(dst), payload);
    KC_EQ_INT(ffkmp_packet_size(dst), 1024);
    KC_EQ_INT(ffkmp_packet_size(src), 0);
    KC_NULL(ffkmp_packet_data(src));
    OWN_LIVE_EXACTLY(measure, &before, 0, "no_copy");
    ffkmp_packet_free(dst);
    ffkmp_packet_free(src);
}

/* S1.c.1. The clone the JVM bridge hands across the boundary. Metadata equality, shared payload
 * (the O(1) property), and independent close in BOTH orders, because the bridge cannot promise
 * which side dies first. */
static void case_packet_clone(int measure)
{
    AVPacket *src = filled_packet(96);
    kc_alloc_counts before;
    AVPacket *clone;
    ffkmp_packet_set_pts(src, 7001);
    ffkmp_packet_set_dts(src, 6999);
    ffkmp_packet_set_stream_index(src, 3);
    kc_alloc_snapshot(&before);
    clone = ffkmp_packet_clone(src);
    KC_NOT_NULL(clone);
    KC_EQ_PTR(ffkmp_packet_data(clone), ffkmp_packet_data(src));
    KC_EQ_INT(ffkmp_packet_size(clone), 96);
    KC_EQ_I64(ffkmp_packet_pts(clone), 7001);
    KC_EQ_I64(ffkmp_packet_dts(clone), 6999);
    KC_EQ_INT(ffkmp_packet_stream_index(clone), 3);
    OWN_LIVE_POSITIVE(measure, &before, "clone");
    ffkmp_packet_free(clone);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net_clone_first");
    ffkmp_packet_free(src);
}

static void case_packet_clone_source_closes_first(int measure)
{
    AVPacket *src = filled_packet(64);
    AVPacket *clone = ffkmp_packet_clone(src);
    kc_alloc_counts before;
    (void)measure;
    KC_NOT_NULL(clone);
    kc_alloc_snapshot(&before);
    ffkmp_packet_free(src);
    /* The payload survives the source: the clone still reads its own reference. */
    KC_EQ_INT(ffkmp_packet_size(clone), 64);
    KC_NOT_NULL(ffkmp_packet_data(clone));
    ffkmp_packet_free(clone);
}

static void case_packet_clone_refuses_null(int measure)
{
    kc_alloc_counts before;
    kc_alloc_snapshot(&before);
    KC_EQ_PTR(ffkmp_packet_clone(NULL), NULL);
    OWN_LIVE_EXACTLY(measure, &before, 0, "refusal");
}

static void case_codecctx_alloc_free(int measure)
{
    const AVCodec *dec = ffkmp_find_decoder_by_id(AV_CODEC_ID_PCM_S16LE);
    kc_alloc_counts before;
    AVCodecContext *c;
    KC_NOT_NULL(dec);
    kc_alloc_snapshot(&before);
    c = ffkmp_codecctx_alloc(dec);
    KC_NOT_NULL(c);
    OWN_LIVE_POSITIVE(measure, &before, "context");
    ffkmp_codecctx_free(c);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_codecctx_open_then_free(int measure)
{
    const AVCodec *dec = ffkmp_find_decoder_by_id(AV_CODEC_ID_PCM_S16LE);
    kc_alloc_counts before;
    AVCodecContext *c;
    KC_NOT_NULL(dec);
    kc_alloc_snapshot(&before);
    c = ffkmp_codecctx_alloc(dec);
    KC_NOT_NULL(c);
    ffkmp_codecctx_set_audio(c, 48000, AV_SAMPLE_FMT_S16, 2, 0);
    KC_EQ_INT(ffkmp_codecctx_open(c, dec), 0);
    KC_EQ_INT(ffkmp_codecctx_sample_rate(c), 48000);
    KC_EQ_INT(ffkmp_codecctx_channels(c), 2);
    /* Opening allocates the codec's internal state. avcodec_free_context is the only thing
     * that releases it, and it must release all of it. */
    OWN_LIVE_POSITIVE(measure, &before, "opened");
    ffkmp_codecctx_free(c);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_codecctx_set_audio_twice(int measure)
{
    const AVCodec *dec = ffkmp_find_decoder_by_id(AV_CODEC_ID_PCM_S16LE);
    AVCodecContext *c;
    kc_alloc_counts before;
    KC_NOT_NULL(dec);
    c = ffkmp_codecctx_alloc(dec);
    KC_NOT_NULL(c);
    kc_alloc_snapshot(&before);
    ffkmp_codecctx_set_audio(c, 48000, AV_SAMPLE_FMT_S16, 6, 128000);
    KC_EQ_INT(ffkmp_codecctx_channels(c), 6);
    /* The second call runs the uninit branch over a layout that is already set. */
    ffkmp_codecctx_set_audio(c, 44100, AV_SAMPLE_FMT_FLTP, 2, 96000);
    KC_EQ_INT(ffkmp_codecctx_channels(c), 2);
    KC_EQ_INT(ffkmp_codecctx_sample_rate(c), 44100);
    ffkmp_codecctx_set_audio(NULL, 44100, AV_SAMPLE_FMT_FLTP, 2, 0);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
    ffkmp_codecctx_free(c);
}

static void case_codecctx_set_opt_is_context_owned(int measure)
{
    const AVCodec *dec = ffkmp_find_decoder_by_id(AV_CODEC_ID_PCM_S16LE);
    kc_alloc_counts before;
    AVCodecContext *c;
    KC_NOT_NULL(dec);
    kc_alloc_snapshot(&before);
    c = ffkmp_codecctx_alloc(dec);
    KC_NOT_NULL(c);
    /* av_opt_set copies the value. The copy belongs to the context, so the only proof that it
     * is not a leak is that freeing the context brings the window back to zero. */
    KC_EQ_INT(ffkmp_codecctx_set_opt(c, "threads", "2"), 0);
    KC_CHECKF(ffkmp_codecctx_set_opt(c, "no_such_option_at_all", "1") < 0,
              "an unknown option was accepted");
    KC_CHECKF(ffkmp_codecctx_set_opt(NULL, "threads", "2") < 0,
              "a NULL context was accepted");
    ffkmp_codecctx_free(c);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_codecpar_from_context_and_back(int measure)
{
    const AVCodec *dec = ffkmp_find_decoder_by_id(AV_CODEC_ID_PCM_S16LE);
    kc_alloc_counts before;
    AVCodecContext *from;
    AVCodecContext *into;
    AVCodecParameters *par;
    KC_NOT_NULL(dec);
    kc_alloc_snapshot(&before);
    from = ffkmp_codecctx_alloc(dec);
    KC_NOT_NULL(from);
    ffkmp_codecctx_set_audio(from, 48000, AV_SAMPLE_FMT_S16, 2, 128000);
    /* avcodec_parameters_alloc and _free are raw libav calls: no helper allocates a bare
     * AVCodecParameters, because in the library they always come from a stream. */
    par = avcodec_parameters_alloc();
    KC_NOT_NULL(par);
    KC_CHECKF(ffkmp_codecpar_from_context(par, from) >= 0, "codecpar_from_context failed");
    KC_EQ_INT(ffkmp_codecpar_sample_rate(par), 48000);
    KC_EQ_INT(ffkmp_codecpar_channels(par), 2);
    into = ffkmp_codecctx_alloc(dec);
    KC_NOT_NULL(into);
    KC_CHECKF(ffkmp_codecctx_from_par(into, par) >= 0, "codecctx_from_par failed");
    KC_EQ_INT(ffkmp_codecctx_sample_rate(into), 48000);
    KC_EQ_INT(ffkmp_codecctx_channels(into), 2);
    avcodec_parameters_free(&par);
    KC_NULL(par);
    ffkmp_codecctx_free(into);
    ffkmp_codecctx_free(from);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_codecpar_copy_for_mux(int measure)
{
    kc_alloc_counts before;
    AVCodecParameters *src;
    AVCodecParameters *dst;
    kc_alloc_snapshot(&before);
    src = avcodec_parameters_alloc();
    dst = avcodec_parameters_alloc();
    KC_NOT_NULL(src);
    KC_NOT_NULL(dst);
    src->codec_type = AVMEDIA_TYPE_AUDIO;
    src->codec_id = AV_CODEC_ID_PCM_S16LE;
    src->format = AV_SAMPLE_FMT_S16;
    src->sample_rate = 48000;
    src->codec_tag = 0x20776172;
    av_channel_layout_default(&src->ch_layout, 2);
    /* extradata is the part that has to be copied rather than shared, so give it some. */
    src->extradata = (uint8_t *)av_mallocz(16 + AV_INPUT_BUFFER_PADDING_SIZE);
    KC_NOT_NULL(src->extradata);
    src->extradata_size = 16;
    KC_CHECKF(ffkmp_codecpar_copy_for_mux(dst, src) >= 0, "codecpar_copy_for_mux failed");
    KC_EQ_INT(ffkmp_codecpar_sample_rate(dst), 48000);
    KC_EQ_INT(ffkmp_codecpar_channels(dst), 2);
    KC_EQ_INT(dst->extradata_size, 16);
    KC_NOT_NULL(dst->extradata);
    /* A deep copy, not a shared pointer. Sharing would double free the moment either side is
     * released, and the helper's whole purpose is a stream copy across two contexts. */
    KC_CHECKF(dst->extradata != src->extradata, "extradata was shared, not copied");
    /* The container specific tag is dropped on purpose, so the destination muxer picks its
     * own. */
    KC_EQ_INT((int)dst->codec_tag, 0);
    avcodec_parameters_free(&dst);
    avcodec_parameters_free(&src);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_open_input_close_input(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *in = NULL;
    AVPacket *p;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_open_input(&in, wav_path), 0);
    KC_NOT_NULL(in);
    OWN_LIVE_POSITIVE(measure, &before, "opened");
    KC_CHECKF(ffkmp_fmt_nb_streams(in) >= 1u, "no streams after open");
    p = ffkmp_packet_alloc();
    KC_NOT_NULL(p);
    KC_EQ_INT(ffkmp_fmt_read_frame(in, p), 0);
    KC_CHECKF(ffkmp_packet_size(p) > 0, "read_frame produced an empty packet");
    /* read_frame hands the caller a reference. Unreffing it is the caller's job, and the
     * demuxer must not still hold the last one. */
    ffkmp_packet_unref(p);
    ffkmp_packet_free(p);
    ffkmp_fmt_close_input(&in);
    KC_NULL(in);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_find_stream_info(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *in = NULL;
    AVStream *st;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_open_input(&in, wav_path), 0);
    KC_CHECKF(ffkmp_fmt_find_stream_info(in) >= 0, "find_stream_info failed");
    KC_EQ_SIZE(ffkmp_fmt_nb_streams(in), 1u);
    st = ffkmp_fmt_stream(in, 0);
    KC_NOT_NULL(st);
    KC_EQ_INT(ffkmp_codecpar_sample_rate(ffkmp_stream_codecpar(st)), 48000);
    KC_EQ_INT(ffkmp_codecpar_channels(ffkmp_stream_codecpar(st)), 2);
    /* find_stream_info allocates parser and probe state per stream. close_input owes all of
     * it back, and this is the window that says so. */
    ffkmp_fmt_close_input(&in);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

/* Interlude item I-12. The two argument guards the retired byte-equality proof was blocking.
 * Before the guards, both of these were reproduced as signal 11 through the public surface. */
static void case_fmt_set_opt_refuses_a_null_key(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    KC_NOT_NULL(ctx);
    /* A NULL key used to reach av_opt_set and crash inside its strcmp. The contract now matches
     * the context guard beside it: refused with AVERROR(EINVAL), nothing read, nothing stored. */
    KC_EQ_INT(ffkmp_fmt_set_opt(ctx, NULL, "1"), AVERROR(EINVAL));
    /* The guard refuses the key alone; a real option through the same call still works. */
    KC_EQ_INT(ffkmp_fmt_set_opt(ctx, "fflags", "+bitexact"), 0);
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_seek_micros_refuses_an_out_of_range_stream(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *in = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_open_input(&in, wav_path), 0);
    KC_CHECKF(ffkmp_fmt_find_stream_info(in) >= 0, "find_stream_info failed");
    /* An index past nb_streams used to index ctx->streams[] unchecked and crash. -1 keeps its
     * documented meaning, any stream; everything else outside 0..nb_streams-1 is refused. */
    KC_EQ_INT(ffkmp_fmt_seek_micros(in, 99, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_fmt_seek_micros(in, -2, 0), AVERROR(EINVAL));
    KC_CHECKF(ffkmp_fmt_seek_micros(in, -1, 0) >= 0, "-1, any stream, stopped working");
    KC_CHECKF(ffkmp_fmt_seek_micros(in, 0, 0) >= 0, "a valid index stopped working");
    ffkmp_fmt_close_input(&in);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_open_input_refuses_a_missing_file(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *in = NULL;
    kc_alloc_snapshot(&before);
    KC_CHECKF(ffkmp_fmt_open_input(&in, bad_path) < 0, "a missing path opened");
    /* The out parameter is cleared and nothing survives. avformat_open_input frees its own
     * context on failure, and the helper must not have allocated anything around it. */
    KC_NULL(in);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_close_input_tolerates_nothing_to_close(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *in = NULL;
    kc_alloc_snapshot(&before);
    ffkmp_fmt_close_input(NULL);
    ffkmp_fmt_close_input(&in);
    KC_NULL(in);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

/* Container inference from the path extension. This case used to drive ffkmp_fmt_alloc_output,
 * which B1.4 deleted as dead exported surface. The inference path itself is
 * not dead: ffkmp_fmt_alloc_output2 takes it whenever its format argument is NULL or empty, which
 * is exactly what the deleted helper did with no argument at all. So the case keeps its coverage
 * and moves to the surviving helper rather than being dropped with it. */
static void case_fmt_alloc_output2_infers_the_container(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, NULL), 0);
    KC_NOT_NULL(ctx);
    OWN_LIVE_POSITIVE(measure, &before, "context");
    /* Nothing was opened, so free_output must take the branch that does not touch pb. */
    ffkmp_fmt_free_output(&ctx);
    KC_NULL(ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_alloc_output2_with_an_explicit_container(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    KC_NOT_NULL(ctx);
    KC_CHECKF(ffkmp_oformat_global_header(ctx) == 0, "wav asked for a global header");
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net_after_success");
    /* An unknown container name is refused, the out parameter is cleared, and nothing is
     * retained. A helper that left a half built context behind here would leak once per
     * mistyped format string, and the cleared pointer is what lets a caller retry into the
     * same variable without leaking the previous attempt. */
    KC_CHECKF(ffkmp_fmt_alloc_output2(&ctx, out_path, "no_such_muxer") < 0,
              "an unknown container name was accepted");
    KC_NULL(ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net_after_refusal");
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

/* Second of the three awkward helpers, first branch: pb was never opened. */
static void case_fmt_free_output_without_an_open_pb(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    KC_NULL(ctx->pb);
    ffkmp_fmt_free_output(&ctx);
    KC_NULL(ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
    /* Idempotent on nothing, both spellings, because the Kotlin side calls it from a close
     * path that can run twice. */
    ffkmp_fmt_free_output(&ctx);
    ffkmp_fmt_free_output(NULL);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net_after_second_call");
}

/* Second of the three awkward helpers, second branch: pb was opened, so avio_closep runs
 * before avformat_free_context. */
static void case_fmt_free_output_closes_an_open_pb(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    KC_NOT_NULL(ffkmp_fmt_new_stream(ctx, NULL));
    KC_EQ_INT(ffkmp_fmt_io_open(ctx, out_path), 0);
    KC_NOT_NULL(ctx->pb);
    OWN_LIVE_POSITIVE(measure, &before, "with_pb");
    ffkmp_fmt_free_output(&ctx);
    KC_NULL(ctx);
    /* The AVIOContext and its buffer are the blocks this branch exists for. Skipping
     * avio_closep would leave them here, and on a real muxer it would also leave the file
     * unflushed. */
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_io_open_refuses_a_bad_path(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    KC_CHECKF(ffkmp_fmt_io_open(ctx, bad_path) < 0, "io_open accepted a missing directory");
    KC_NULL(ctx->pb);
    KC_CHECKF(ffkmp_fmt_io_open(NULL, out_path) < 0, "io_open accepted a NULL context");
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_set_opt_is_context_owned(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, mp4_path, "mp4"), 0);
    /* A muxer private option, reached through AV_OPT_SEARCH_CHILDREN. Its value is copied into
     * priv_data, which the context frees. */
    KC_CHECKF(ffkmp_fmt_set_opt(ctx, "movflags", "faststart") >= 0, "movflags was refused");
    KC_CHECKF(ffkmp_fmt_set_opt(ctx, "no_such_option_at_all", "1") < 0,
              "an unknown option was accepted");
    KC_CHECKF(ffkmp_fmt_set_opt(NULL, "movflags", "faststart") < 0,
              "a NULL context was accepted");
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_set_metadata_is_context_owned(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    AVDictionary *meta;
    AVDictionaryEntry *entry;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    KC_CHECKF(ffkmp_fmt_set_metadata(ctx, "title", "kite") >= 0, "set_metadata failed");
    meta = ffkmp_fmt_metadata(ctx);
    KC_NOT_NULL(meta);
    entry = ffkmp_dict_get(meta, NULL);
    KC_NOT_NULL(entry);
    KC_EQ_STR(ffkmp_dict_entry_key(entry), "title");
    KC_EQ_STR(ffkmp_dict_entry_value(entry), "kite");
    /* Setting the same key again replaces the value, which frees the old copy. A dictionary
     * that grew a second entry, or kept the old string, would show up in the net count. */
    KC_CHECKF(ffkmp_fmt_set_metadata(ctx, "title", "kite player") >= 0, "replacement failed");
    entry = ffkmp_dict_get(ffkmp_fmt_metadata(ctx), NULL);
    KC_NOT_NULL(entry);
    KC_EQ_STR(ffkmp_dict_entry_value(entry), "kite player");
    KC_NULL(ffkmp_dict_get(ffkmp_fmt_metadata(ctx), entry));
    KC_CHECKF(ffkmp_fmt_set_metadata(NULL, "title", "kite") < 0, "a NULL context was accepted");
    KC_CHECKF(ffkmp_fmt_set_metadata(ctx, NULL, "kite") < 0, "a NULL key was accepted");
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

/* First of the three awkward helpers: the result belongs to the parent. */
static void case_fmt_new_stream_is_parent_owned(int measure)
{
    kc_alloc_counts before;
    kc_alloc_counts at_stream;
    AVFormatContext *ctx = NULL;
    AVStream *first;
    AVStream *second;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "wav"), 0);
    kc_alloc_snapshot(&at_stream);
    first = ffkmp_fmt_new_stream(ctx, NULL);
    KC_NOT_NULL(first);
    KC_EQ_INT(ffkmp_stream_index(first), 0);
    KC_EQ_SIZE(ffkmp_fmt_nb_streams(ctx), 1u);
    KC_EQ_PTR(ffkmp_fmt_stream(ctx, 0), first);
    /* Half one of the rule: the blocks are still live when the helper returns, and there is no
     * ffkmp_stream_free anywhere because freeing them individually would be wrong. */
    OWN_LIVE_POSITIVE(measure, &at_stream, "stream_retained");
    second = ffkmp_fmt_new_stream(ctx, NULL);
    KC_NOT_NULL(second);
    KC_EQ_INT(ffkmp_stream_index(second), 1);
    KC_EQ_SIZE(ffkmp_fmt_nb_streams(ctx), 2u);
    KC_CHECKF(second != first, "the second stream is the first one again");
    KC_NULL(ffkmp_fmt_new_stream(NULL, NULL));
    ffkmp_fmt_free_output(&ctx);
    /* Half two: freeing the parent releases both streams. If it did not, this is where two
     * leaks would appear, and no caller would have any way to reach them. */
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_write_session(int measure)
{
    kc_alloc_counts before;
    kc_alloc_snapshot(&before);
    /* One full mux session through the helpers: alloc, new_stream, io_open, write_header,
     * four write_frame calls, write_trailer, free_output. write_wav asserts that every packet
     * comes back empty, which is the ownership transfer into the muxer. */
    write_wav(out_path);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_fmt_write_header_refuses_a_streamless_context(int measure)
{
    kc_alloc_counts before;
    AVFormatContext *ctx = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, out_path, "mp4"), 0);
    KC_EQ_INT(ffkmp_fmt_io_open(ctx, out_path), 0);
    /* No stream was added, so the muxer refuses. The interesting part is the error path: a
     * refused write_header still allocates inside libavformat, and free_output owes it back. */
    KC_CHECKF(ffkmp_fmt_write_header(ctx) < 0, "write_header accepted a streamless mp4");
    KC_CHECKF(ffkmp_fmt_write_header(NULL) < 0, "write_header accepted a NULL context");
    KC_CHECKF(ffkmp_fmt_write_trailer(NULL) < 0, "write_trailer accepted a NULL context");
    ffkmp_fmt_free_output(&ctx);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_build_video_free(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_graph_build_video(&graph, &src, &sink, "null",
                                      64, 64, AV_PIX_FMT_YUV420P, 1, 25, 25, 1, 1, 1), 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(src);
    KC_NOT_NULL(sink);
    /* The graph owns the two filter contexts and the parsed chain, including the AVFilterInOut
     * list and the av_strdup names that ffkmp_graph_finish_ allocates. */
    OWN_LIVE_POSITIVE(measure, &before, "graph");
    ffkmp_graph_free(&graph);
    KC_NULL(graph);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_build_audio_free(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, "anull",
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000,
                                      AV_SAMPLE_FMT_S16, 44100, 2), 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(src);
    KC_NOT_NULL(sink);
    OWN_LIVE_POSITIVE(measure, &before, "graph");
    ffkmp_graph_free(&graph);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_build_video_multi_free(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[2] = { NULL, NULL };
    AVFilterContext *sink = NULL;
    const int widths[2] = { 64, 64 };
    const int heights[2] = { 64, 64 };
    const int fmts[2] = { AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUV420P };
    const int tb_nums[2] = { 1, 1 };
    const int tb_dens[2] = { 25, 25 };
    const int fr_nums[2] = { 25, 25 };
    const int fr_dens[2] = { 1, 1 };
    const int sar_nums[2] = { 1, 1 };
    const int sar_dens[2] = { 1, 1 };
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_graph_build_video_multi(&graph, srcs, &sink,
                                            "[in0][in1]overlay=0:0[out]", 2,
                                            widths, heights, fmts, tb_nums, tb_dens,
                                            fr_nums, fr_dens, sar_nums, sar_dens), 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(srcs[0]);
    KC_NOT_NULL(srcs[1]);
    KC_NOT_NULL(sink);
    OWN_LIVE_POSITIVE(measure, &before, "graph");
    ffkmp_graph_free(&graph);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_build_audio_multi_free(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[2] = { NULL, NULL };
    AVFilterContext *sink = NULL;
    const int rates[2] = { 48000, 48000 };
    const int fmts[2] = { AV_SAMPLE_FMT_FLTP, AV_SAMPLE_FMT_FLTP };
    const int channels[2] = { 2, 2 };
    const int tb_nums[2] = { 1, 1 };
    const int tb_dens[2] = { 48000, 48000 };
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_graph_build_audio_multi(&graph, srcs, &sink,
                                            "[in0][in1]amix=inputs=2[out]", 2,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            AV_SAMPLE_FMT_S16, 44100, 2), 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(srcs[0]);
    KC_NOT_NULL(srcs[1]);
    KC_NOT_NULL(sink);
    OWN_LIVE_POSITIVE(measure, &before, "graph");
    ffkmp_graph_free(&graph);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_build_refusal_frees_the_partial_graph(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    AVFilterContext *srcs[1] = { NULL };
    const int rates[1] = { 48000 };
    const int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    kc_alloc_snapshot(&before);
    /* Every builder allocates the graph first and parses the description afterwards, so every
     * one of them has an error path that must free a half built graph. This is the row that
     * would catch a missing avfilter_graph_free on one of those returns. */
    KC_CHECKF(ffkmp_graph_build_video(&graph, &src, &sink, "no_such_filter_exists",
                                      64, 64, AV_PIX_FMT_YUV420P, 1, 25, 25, 1, 1, 1) < 0,
              "an unknown video filter was accepted");
    KC_NULL(graph);
    KC_NULL(src);
    KC_NULL(sink);
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, "no_such_filter_exists",
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000, -1, -1, 0) < 0,
              "an unknown audio filter was accepted");
    KC_NULL(graph);
    KC_CHECKF(ffkmp_graph_build_video_multi(&graph, srcs, &sink, "[in0]no_such_filter_exists",
                                            1, rates, rates, fmts, tb_nums, tb_dens,
                                            tb_nums, tb_dens, tb_nums, tb_nums) < 0,
              "an unknown filter was accepted by the multi video builder");
    KC_NULL(graph);
    KC_CHECKF(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, "[in0]no_such_filter_exists",
                                            1, rates, fmts, channels, tb_nums, tb_dens,
                                            -1, -1, 0) < 0,
              "an unknown filter was accepted by the multi audio builder");
    KC_NULL(graph);
    /* Four refusals, nothing retained. */
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_free_tolerates_nothing_to_free(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    kc_alloc_snapshot(&before);
    ffkmp_graph_free(NULL);
    ffkmp_graph_free(&graph);
    KC_NULL(graph);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

static void case_graph_send_keeps_the_callers_frame(int measure)
{
    kc_alloc_counts before;
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    AVFrame *pushed;
    AVFrame *pulled;
    const uint8_t *payload;
    kc_alloc_snapshot(&before);
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, "anull",
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000, -1, -1, 0), 0);
    pushed = audio_frame(48000, AV_SAMPLE_FMT_FLTP, 2, 1024);
    payload = ffkmp_frame_plane(pushed, 0);
    KC_EQ_INT(ffkmp_graph_send(src, pushed), 0);
    /* AV_BUFFERSRC_FLAG_KEEP_REF: the graph takes its own reference and the caller keeps its
     * frame intact. Without the flag the frame would come back empty and every Kotlin caller
     * that reuses a frame after sending it would be reading freed pixels. */
    KC_EQ_PTR(ffkmp_frame_plane(pushed, 0), payload);
    KC_EQ_INT(ffkmp_frame_nb_samples(pushed), 1024);
    pulled = ffkmp_frame_alloc();
    KC_NOT_NULL(pulled);
    KC_EQ_INT(ffkmp_graph_receive(sink, pulled), 0);
    /* receive fills a caller owned frame with references the caller must release. */
    KC_NOT_NULL(ffkmp_frame_plane(pulled, 0));
    KC_EQ_INT(ffkmp_frame_nb_samples(pulled), 1024);
    ffkmp_frame_unref(pulled);
    KC_NULL(ffkmp_frame_plane(pulled, 0));
    ffkmp_frame_free(pulled);
    ffkmp_frame_free(pushed);
    ffkmp_graph_free(&graph);
    OWN_LIVE_EXACTLY(measure, &before, 0, "net");
}

/* ---- Table and driver ---- */

typedef struct {
    const char *name;
    void (*run)(int measure);
} own_case;

static const own_case cases[] = {
    { "harness reporting allocates nothing",              case_harness_does_not_allocate },
    { "frame_alloc then frame_free",                      case_frame_alloc_free },
    { "frame_get_buffer then frame_unref",                case_frame_get_buffer_and_unref },
    { "frame_clone is caller owned and shares pixels",    case_frame_clone },
    { "frame_clone refuses NULL",                         case_frame_clone_refuses_null },
    { "frame_convert_pixfmt returns a caller owned frame", case_frame_convert_pixfmt_is_caller_owned },
    { "frame_convert_pixfmt repeated does not accumulate", case_frame_convert_pixfmt_repeated_does_not_accumulate },
    { "frame_convert_pixfmt refuses bad input",           case_frame_convert_pixfmt_refuses_bad_input },
    { "frame_set_ch_layout_default over an existing layout", case_frame_set_ch_layout_default },
    { "packet_alloc then packet_free",                    case_packet_alloc_free },
    { "packet_unref releases the payload only",           case_packet_unref_releases_payload },
    { "packet_move_ref transfers the payload",            case_packet_move_ref_transfers_payload },
    { "packet_clone shares payload and closes independently", case_packet_clone },
    { "packet_clone survives its source closing first",   case_packet_clone_source_closes_first },
    { "packet_clone refuses NULL",                        case_packet_clone_refuses_null },
    { "codecctx_alloc then codecctx_free",                case_codecctx_alloc_free },
    { "codecctx_open then codecctx_free",                 case_codecctx_open_then_free },
    { "codecctx_set_audio over an existing layout",       case_codecctx_set_audio_twice },
    { "codecctx_set_opt value is context owned",          case_codecctx_set_opt_is_context_owned },
    { "codecpar_from_context then codecctx_from_par",     case_codecpar_from_context_and_back },
    { "codecpar_copy_for_mux deep copies extradata",      case_codecpar_copy_for_mux },
    { "fmt_open_input then fmt_close_input",              case_fmt_open_input_close_input },
    { "fmt_find_stream_info then fmt_close_input",        case_fmt_find_stream_info },
    { "fmt_open_input refuses a missing file",            case_fmt_open_input_refuses_a_missing_file },
    { "fmt_close_input tolerates nothing to close",       case_fmt_close_input_tolerates_nothing_to_close },
    { "fmt_alloc_output2 infers the container",           case_fmt_alloc_output2_infers_the_container },
    { "fmt_alloc_output2 with an explicit container",     case_fmt_alloc_output2_with_an_explicit_container },
    { "fmt_free_output without an open pb",               case_fmt_free_output_without_an_open_pb },
    { "fmt_free_output closes an open pb",                case_fmt_free_output_closes_an_open_pb },
    { "fmt_io_open refuses a bad path",                   case_fmt_io_open_refuses_a_bad_path },
    { "fmt_set_opt value is context owned",               case_fmt_set_opt_is_context_owned },
    { "fmt_set_opt refuses a NULL key",                   case_fmt_set_opt_refuses_a_null_key },
    { "fmt_seek_micros refuses an out of range stream",   case_fmt_seek_micros_refuses_an_out_of_range_stream },
    { "fmt_set_metadata is context owned",                case_fmt_set_metadata_is_context_owned },
    { "fmt_new_stream result is parent owned",            case_fmt_new_stream_is_parent_owned },
    { "fmt_write_header, write_frame and write_trailer",  case_fmt_write_session },
    { "fmt_write_header refuses a streamless context",    case_fmt_write_header_refuses_a_streamless_context },
    { "graph_build_video then graph_free",                case_graph_build_video_free },
    { "graph_build_audio then graph_free",                case_graph_build_audio_free },
    { "graph_build_video_multi then graph_free",          case_graph_build_video_multi_free },
    { "graph_build_audio_multi then graph_free",          case_graph_build_audio_multi_free },
    { "a refused graph build frees the partial graph",    case_graph_build_refusal_frees_the_partial_graph },
    { "graph_free tolerates nothing to free",             case_graph_free_tolerates_nothing_to_free },
    { "graph_send keeps the caller's frame",              case_graph_send_keeps_the_callers_frame },
};

int main(void)
{
    size_t i;
    kc_suite_begin("test_ownership");
    build_paths();
    atexit(remove_paths);

    /* The demux cases need a real file. Writing it through the mux helpers rather than
     * shipping a binary fixture keeps the suite self contained and means the mux path is
     * exercised before anything depends on it. */
    write_wav(wav_path);

    if (!kc_alloc_active()) {
        kc_note("the allocation interposer is not effective in this variant, so every case");
        kc_note("reports its pairing property as partial. The functional assertions still run.");
    }

    for (i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        kc_alloc_counts window;
        /* The case is opened before the warm-up pass, so an assertion that fires during the
         * warm-up is reported under the right name instead of the previous case's. */
        kc_case("%s", cases[i].name);
        /* Warm-up pass. Its allocations are deliberately not looked at: libavformat and
         * libavfilter build one-time state on first use, measured as 3 blocks for the first
         * demux cycle and 0 for every later one. A leak that happens per call still shows up,
         * because the measured pass repeats the same work. */
        cases[i].run(0);
        kc_alloc_snapshot(&window);
        cases[i].run(1);
        KC_ALLOC_BALANCED(&window);
    }

    return kc_suite_end();
}
