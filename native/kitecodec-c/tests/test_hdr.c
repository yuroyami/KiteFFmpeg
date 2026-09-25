/* What an encode keeps about the picture: the colour, the pixel shape and the HDR static metadata
 * an encoder is given before it opens, read back from the codec parameters it hands the muxer, and
 * the same readers on a frame and on a stream. */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/pixfmt.h>

/* The mastering display of the Kotlin contract test: BT.2020 primaries, D65, 0.0001 to 1000 nits. */
static const int MASTERING[KC_HDR_MASTERING_INTS] = {
    34000, 50000, 16000, 50000,   /* red x, y */
    13250, 50000, 34500, 50000,   /* green x, y */
    7500, 50000, 3000, 50000,     /* blue x, y */
    15635, 50000, 16450, 50000,   /* white x, y */
    1, 10000, 10000000, 10000,    /* min, max luminance */
};

static AVCodecContext *mpeg4_context(void)
{
    const kc_codec *codec = ffkmp_find_encoder_by_name("mpeg4");
    AVCodecContext *c;
    KC_NOT_NULL(codec);
    c = ffkmp_codecctx_alloc(codec);
    KC_NOT_NULL(c);
    ffkmp_codecctx_set_video(c, 64, 48, AV_PIX_FMT_YUV420P, 25, 1, 1, 25, 400000, 12);
    return c;
}

static void case_refusals(void)
{
    AVCodecContext *c = mpeg4_context();
    int q[KC_HDR_MASTERING_INTS];
    int i;

    kc_case("values outside their range are refused and change nothing");
    KC_EQ_INT(ffkmp_codecctx_set_color(c, AVCOL_PRI_NB, 2, 2, 0, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecctx_set_color(c, 2, -1, 2, 0, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecctx_set_color(c, 2, 2, 2, AVCOL_RANGE_NB, 0), AVERROR(EINVAL));
    KC_EQ_INT(c->color_primaries, AVCOL_PRI_UNSPECIFIED);
    KC_EQ_INT(ffkmp_codecctx_set_sample_aspect_ratio(c, -1, 3), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecctx_set_sample_aspect_ratio(c, 4, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecctx_set_ch_layout_mask(c, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecctx_add_mastering_display(c, MASTERING, 0), AVERROR(EINVAL));
    for (i = 0; i < KC_HDR_MASTERING_INTS; i++) q[i] = MASTERING[i];
    q[3] = 0;
    KC_EQ_INT(ffkmp_codecctx_add_mastering_display(c, q, KC_HDR_HAS_PRIMARIES), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecctx_add_content_light(c, -1, 400), AVERROR(EINVAL));
    KC_EQ_INT(c->nb_decoded_side_data, 0);
    KC_EQ_I64(ffkmp_codecctx_ch_layout_mask(NULL), 0);
    ffkmp_codecctx_free(c);
}

static void case_an_encoder_hands_the_muxer_what_it_was_given(void)
{
    const kc_codec *codec = ffkmp_find_encoder_by_name("mpeg4");
    AVCodecContext *c = mpeg4_context();
    AVCodecParameters *p = avcodec_parameters_alloc();
    int q[KC_HDR_MASTERING_INTS] = { 0 };
    int flags = 0, cll = 0, fall = 0;
    int i;

    kc_case("colour, pixel shape, mastering display and light level survive avcodec_open2");
    KC_NOT_NULL(p);
    KC_EQ_INT(ffkmp_codecctx_set_color(c, AVCOL_PRI_BT2020, AVCOL_TRC_SMPTE2084, AVCOL_SPC_BT2020_NCL,
                                       AVCOL_RANGE_MPEG, AVCHROMA_LOC_LEFT), 0);
    KC_EQ_INT(ffkmp_codecctx_set_sample_aspect_ratio(c, 4, 3), 0);
    KC_EQ_INT(ffkmp_codecctx_add_mastering_display(c, MASTERING, KC_HDR_HAS_PRIMARIES | KC_HDR_HAS_LUMINANCE), 0);
    KC_EQ_INT(ffkmp_codecctx_add_content_light(c, 1000, 400), 0);
    KC_EQ_INT(ffkmp_codecctx_open(c, codec), 0);
    KC_EQ_INT(ffkmp_codecpar_from_context(p, c), 0);

    KC_EQ_INT(p->color_primaries, AVCOL_PRI_BT2020);
    KC_EQ_INT(p->color_trc, AVCOL_TRC_SMPTE2084);
    KC_EQ_INT(p->color_space, AVCOL_SPC_BT2020_NCL);
    KC_EQ_INT(p->color_range, AVCOL_RANGE_MPEG);
    KC_EQ_INT(p->chroma_location, AVCHROMA_LOC_LEFT);
    KC_EQ_INT(p->sample_aspect_ratio.num, 4);
    KC_EQ_INT(p->sample_aspect_ratio.den, 3);

    KC_EQ_INT(ffkmp_codecpar_mastering_display(p, q, &flags), 1);
    KC_EQ_INT(flags, KC_HDR_HAS_PRIMARIES | KC_HDR_HAS_LUMINANCE);
    for (i = 0; i < KC_HDR_MASTERING_INTS; i++) {
        KC_CHECKF(q[i] == MASTERING[i], "int %d read back as %d, written as %d", i, q[i], MASTERING[i]);
    }
    KC_EQ_INT(ffkmp_codecpar_content_light(p, &cll, &fall), 1);
    KC_EQ_INT(cll, 1000);
    KC_EQ_INT(fall, 400);

    avcodec_parameters_free(&p);
    ffkmp_codecctx_free(c);
}

static void case_a_frame_reports_each_half_it_carries(void)
{
    AVFrame *f = av_frame_alloc();
    AVMasteringDisplayMetadata *m;
    AVContentLightMetadata *light;
    int q[KC_HDR_MASTERING_INTS] = { 0 };
    int flags = -1, cll = -1, fall = -1;

    kc_case("a frame without HDR side data answers 0; with it, each half and its flag");
    KC_NOT_NULL(f);
    KC_EQ_INT(ffkmp_frame_mastering_display(f, q, &flags), 0);
    KC_EQ_INT(ffkmp_frame_content_light(f, &cll, &fall), 0);

    m = av_mastering_display_metadata_create_side_data(f);
    KC_NOT_NULL(m);
    m->max_luminance = av_make_q(1000, 1);
    m->min_luminance = av_make_q(1, 10000);
    m->has_luminance = 1;
    KC_EQ_INT(ffkmp_frame_mastering_display(f, q, &flags), 1);
    KC_EQ_INT(flags, KC_HDR_HAS_LUMINANCE);
    KC_EQ_INT(q[18], 1000);
    KC_EQ_INT(q[19], 1);

    light = av_content_light_metadata_create_side_data(f);
    KC_NOT_NULL(light);
    light->MaxCLL = 4000;
    light->MaxFALL = 250;
    KC_EQ_INT(ffkmp_frame_content_light(f, &cll, &fall), 1);
    KC_EQ_INT(cll, 4000);
    KC_EQ_INT(fall, 250);
    av_frame_free(&f);
}

static void case_a_stream_takes_and_copies_its_pixel_shape(void)
{
    kc_fmt_ctx *ctx = NULL;
    kc_stream *src, *dst;

    kc_case("the stream-level pixel shape is set, refused when invalid, and copied with the identity");
    KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, "kiteffmpeg-hdr.mkv", "matroska"), 0);
    src = ffkmp_fmt_new_stream(ctx, NULL);
    dst = ffkmp_fmt_new_stream(ctx, NULL);
    KC_NOT_NULL(src);
    KC_NOT_NULL(dst);
    KC_EQ_INT(ffkmp_stream_set_sample_aspect_ratio(src, 4, 3), 0);
    KC_EQ_INT(ffkmp_stream_set_sample_aspect_ratio(src, -4, 3), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_stream_set_sample_aspect_ratio(src, 4, 0), AVERROR(EINVAL));
    KC_EQ_INT(((AVStream *)src)->sample_aspect_ratio.num, 4);
    KC_EQ_INT(ffkmp_stream_copy_identity(dst, src), 0);
    KC_EQ_INT(((AVStream *)dst)->sample_aspect_ratio.num, 4);
    KC_EQ_INT(((AVStream *)dst)->sample_aspect_ratio.den, 3);
    ffkmp_fmt_free_output(&ctx);
}

static void case_an_encoder_takes_an_exact_channel_layout(void)
{
    AVCodecContext *c = ffkmp_codecctx_alloc(ffkmp_find_encoder_by_name("aac"));

    kc_case("a side-surround mask replaces the default back-surround layout for six channels");
    KC_NOT_NULL(c);
    ffkmp_codecctx_set_audio(c, 48000, AV_SAMPLE_FMT_FLTP, 6, 192000);
    KC_EQ_I64(ffkmp_codecctx_ch_layout_mask(c), 0x3F);
    KC_EQ_INT(ffkmp_codecctx_set_ch_layout_mask(c, 0x60F), 0);
    KC_EQ_I64(ffkmp_codecctx_ch_layout_mask(c), 0x60F);
    KC_EQ_INT(ffkmp_codecctx_channels(c), 6);
    ffkmp_codecctx_free(c);
}

int main(void)
{
    kc_suite_begin("test_hdr");

    case_refusals();
    case_an_encoder_hands_the_muxer_what_it_was_given();
    case_a_frame_reports_each_half_it_carries();
    case_a_stream_takes_and_copies_its_pixel_shape();
    case_an_encoder_takes_an_exact_channel_layout();

    return kc_suite_end();
}
