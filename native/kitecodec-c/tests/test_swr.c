/* The resampler behind ffkmp_swr_*: argument refusals, the sample count a rate change must come
 * to after the drain, and the refusal of a frame that does not match what the resampler was
 * created for. */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <math.h>

#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/samplefmt.h>

#define PI 3.14159265358979323846

/* A planar float stereo frame of samples 1 kHz sine, starting at sample first. */
static AVFrame *sine_frame(int rate, int samples, int first)
{
    AVFrame *f = av_frame_alloc();
    int c, i;
    KC_NOT_NULL(f);
    f->nb_samples = samples;
    f->sample_rate = rate;
    f->format = AV_SAMPLE_FMT_FLTP;
    av_channel_layout_default(&f->ch_layout, 2);
    KC_EQ_INT(av_frame_get_buffer(f, 0), 0);
    for (c = 0; c < 2; c++) {
        float *plane = (float *)f->data[c];
        for (i = 0; i < samples; i++) plane[i] = (float)(0.5 * sin(2.0 * PI * 1000.0 * (first + i) / rate));
    }
    return f;
}

static void case_refusals(void)
{
    kc_swr *s = (kc_swr *)0x1;
    kc_swr *none = NULL;

    kc_case("bad rates, channel counts and formats are refused and leave the handle NULL");
    KC_EQ_INT(ffkmp_swr_create(NULL, 48000, 2, AV_SAMPLE_FMT_FLTP, 44100, 2, AV_SAMPLE_FMT_S16, 0, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_swr_create(&s, 0, 2, AV_SAMPLE_FMT_FLTP, 44100, 2, AV_SAMPLE_FMT_S16, 0, 0), AVERROR(EINVAL));
    KC_NULL(s);
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 0, AV_SAMPLE_FMT_FLTP, 44100, 2, AV_SAMPLE_FMT_S16, 0, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 2, AV_SAMPLE_FMT_NB, 44100, 2, AV_SAMPLE_FMT_S16, 0, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 2, AV_SAMPLE_FMT_FLTP, 44100, 2, -1, 0, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_swr_convert_frame(NULL, NULL, NULL), AVERROR(EINVAL));
    ffkmp_swr_free(&none);
    ffkmp_swr_free(NULL);
}

static void case_a_rate_change_keeps_the_sample_count_after_the_drain(void)
{
    kc_swr *s = NULL;
    AVFrame *out = av_frame_alloc();
    int64_t produced = 0;
    int i;

    kc_case("48 kHz planar float to 44.1 kHz s16 comes to 10240 * 44100 / 48000 samples after the drain");
    KC_NOT_NULL(out);
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 2, AV_SAMPLE_FMT_FLTP, 44100, 2, AV_SAMPLE_FMT_S16, 0, 0), 0);
    KC_NOT_NULL(s);
    for (i = 0; i < 10; i++) {
        AVFrame *in = sine_frame(48000, 1024, i * 1024);
        KC_EQ_INT(ffkmp_swr_convert_frame(s, out, in), 0);
        KC_EQ_INT(out->sample_rate, 44100);
        KC_EQ_INT(out->format, AV_SAMPLE_FMT_S16);
        KC_EQ_INT(out->ch_layout.nb_channels, 2);
        produced += out->nb_samples;
        av_frame_free(&in);
    }
    do {
        KC_EQ_INT(ffkmp_swr_convert_frame(s, out, NULL), 0);
        produced += out->nb_samples;
    } while (out->nb_samples > 0);
    kc_detail("produced=%lld expected=9408", (long long)produced);
    KC_CHECKF(llabs(produced - 9408) <= 2, "produced %lld samples, expected 9408 within 2", (long long)produced);
    av_frame_free(&out);
    ffkmp_swr_free(&s);
    KC_NULL(s);
}

static void case_a_frame_that_does_not_match_is_refused(void)
{
    kc_swr *s = NULL;
    AVFrame *out = av_frame_alloc();
    AVFrame *in = sine_frame(44100, 256, 0);

    kc_case("a frame at another rate than the resampler was created for is refused with AVERROR_INPUT_CHANGED");
    KC_NOT_NULL(out);
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 2, AV_SAMPLE_FMT_FLTP, 48000, 1, AV_SAMPLE_FMT_S16, 0, 0), 0);
    KC_EQ_INT(ffkmp_swr_convert_frame(s, out, in), AVERROR_INPUT_CHANGED);
    KC_EQ_INT(out->nb_samples, 0);
    av_frame_free(&in);
    av_frame_free(&out);
    ffkmp_swr_free(&s);
}

/* A planar float frame of six silent channels in the layout `mask`, or unordered when mask is 0. */
static AVFrame *six_channel_frame(uint64_t mask)
{
    AVFrame *f = av_frame_alloc();
    KC_NOT_NULL(f);
    f->nb_samples = 256;
    f->sample_rate = 48000;
    f->format = AV_SAMPLE_FMT_FLTP;
    if (mask) {
        KC_EQ_INT(av_channel_layout_from_mask(&f->ch_layout, mask), 0);
    } else {
        f->ch_layout.order = AV_CHANNEL_ORDER_UNSPEC;
        f->ch_layout.nb_channels = 6;
    }
    KC_EQ_INT(av_frame_get_buffer(f, 0), 0);
    for (int c = 0; c < 6; c++) {
        float *plane = (float *)f->data[c];
        for (int i = 0; i < f->nb_samples; i++) plane[i] = 0.0f;
    }
    return f;
}

static void case_exact_layouts(void)
{
    kc_swr *s = NULL;
    AVFrame *out = av_frame_alloc();
    AVFrame *side = six_channel_frame(0x60F);
    AVFrame *back = six_channel_frame(0x3F);
    AVFrame *unordered = six_channel_frame(0);

    kc_case("side surrounds convert to back surrounds; an unordered frame reads as the input layout");
    KC_NOT_NULL(out);
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 2, AV_SAMPLE_FMT_FLTP, 48000, 2, AV_SAMPLE_FMT_FLTP, 0x60F, 0), AVERROR(EINVAL));
    KC_NULL(s);
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 6, AV_SAMPLE_FMT_FLTP, 48000, 6, AV_SAMPLE_FMT_FLTP, -1, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_swr_create(&s, 48000, 6, AV_SAMPLE_FMT_FLTP, 48000, 6, AV_SAMPLE_FMT_FLTP, 0x60F, 0x3F), 0);
    KC_EQ_INT(ffkmp_swr_convert_frame(s, out, side), 0);
    KC_EQ_INT(out->ch_layout.order, AV_CHANNEL_ORDER_NATIVE);
    KC_EQ_I64((int64_t)out->ch_layout.u.mask, 0x3F);
    KC_EQ_INT(ffkmp_swr_convert_frame(s, out, unordered), 0);
    KC_EQ_INT(unordered->ch_layout.order, AV_CHANNEL_ORDER_UNSPEC);
    KC_EQ_INT(ffkmp_swr_convert_frame(s, out, back), AVERROR_INPUT_CHANGED);
    ffkmp_swr_free(&s);
    av_frame_free(&side);
    av_frame_free(&back);
    av_frame_free(&unordered);
    av_frame_free(&out);
}

int main(void)
{
    kc_suite_begin("test_swr");

    case_refusals();
    case_a_rate_change_keeps_the_sample_count_after_the_drain();
    case_a_frame_that_does_not_match_is_refused();
    case_exact_layouts();

    return kc_suite_end();
}
