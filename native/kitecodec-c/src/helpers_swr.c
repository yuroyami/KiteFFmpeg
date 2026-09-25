/* The audio resampler: libswresample behind one opaque handle.
 *
 * Every KiteFFmpeg tree links libswresample, and until this file nothing called it: the audio
 * encoder refused a frame whose sample format did not match, and a caller who wanted another rate
 * had to build a filter graph around the aresample filter. This is the four-call path instead.
 * Channel layouts are FFmpeg's default for each channel count, the same layouts the rest of this
 * layer gives an audio frame it builds. */

#include "kitecodec_helpers.h"

#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

struct kc_swr {
    SwrContext *ctx;
    /* What every output frame is stamped with before swr_convert_frame fills it. */
    int out_rate;
    int out_format;
    AVChannelLayout out_layout;
};

static int kc_swr_format_valid(int format) {
    return format >= 0 && format < AV_SAMPLE_FMT_NB;
}

KC_API int ffkmp_swr_create(kc_swr **out,
                            int in_rate, int in_channels, int in_format,
                            int out_rate, int out_channels, int out_format) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (in_rate <= 0 || out_rate <= 0 || in_channels <= 0 || out_channels <= 0 ||
        !kc_swr_format_valid(in_format) || !kc_swr_format_valid(out_format)) {
        return AVERROR(EINVAL);
    }
    kc_swr *s = av_mallocz(sizeof(*s));
    if (!s) return AVERROR(ENOMEM);
    AVChannelLayout in_layout;
    av_channel_layout_default(&in_layout, in_channels);
    av_channel_layout_default(&s->out_layout, out_channels);
    int rc = swr_alloc_set_opts2(&s->ctx, &s->out_layout, (enum AVSampleFormat)out_format, out_rate,
                                 &in_layout, (enum AVSampleFormat)in_format, in_rate, 0, NULL);
    av_channel_layout_uninit(&in_layout);
    if (rc >= 0) rc = swr_init(s->ctx);
    if (rc < 0) {
        swr_free(&s->ctx);
        av_channel_layout_uninit(&s->out_layout);
        av_free(s);
        return rc;
    }
    s->out_rate = out_rate;
    s->out_format = out_format;
    *out = s;
    return 0;
}

KC_API int ffkmp_swr_convert_frame(kc_swr *s, kc_frame *out, const kc_frame *in) {
    if (!s || !out) return AVERROR(EINVAL);
    av_frame_unref(out);
    out->sample_rate = s->out_rate;
    out->format = s->out_format;
    int rc = av_channel_layout_copy(&out->ch_layout, &s->out_layout);
    if (rc >= 0) rc = swr_convert_frame(s->ctx, out, in);
    if (rc < 0) av_frame_unref(out);
    return rc;
}

KC_API int64_t ffkmp_swr_delay(kc_swr *s, int64_t base) {
    return s ? swr_get_delay(s->ctx, base) : 0;
}

KC_API void ffkmp_swr_free(kc_swr **s) {
    if (!s || !*s) return;
    swr_free(&(*s)->ctx);
    av_channel_layout_uninit(&(*s)->out_layout);
    av_freep(s);
}
