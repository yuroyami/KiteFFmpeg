/* The audio resampler: libswresample behind one opaque handle.
 *
 * Every KiteFFmpeg tree links libswresample, and until this file nothing called it: the audio
 * encoder refused a frame whose sample format did not match, and a caller who wanted another rate
 * had to build a filter graph around the aresample filter. This is the four-call path instead.
 * A channel layout is the native mask the caller names, or FFmpeg's default for the channel count,
 * the same layout the rest of this layer gives an audio frame it builds. */

#include "kitecodec_helpers.h"

#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

struct kc_swr {
    SwrContext *ctx;
    /* What an input frame that names no layout is read as. */
    AVChannelLayout in_layout;
    /* What every output frame is stamped with before swr_convert_frame fills it. */
    int out_rate;
    int out_format;
    AVChannelLayout out_layout;
};

static int kc_swr_format_valid(int format) {
    return format >= 0 && format < AV_SAMPLE_FMT_NB;
}

/* The layout of `channels` channels: the native layout `mask` names, or the count's default when
   mask is 0. A mask that names another number of channels is refused. */
static int kc_swr_layout(AVChannelLayout *out, int channels, int64_t mask) {
    if (mask == 0) {
        av_channel_layout_default(out, channels);
        return 0;
    }
    int rc = av_channel_layout_from_mask(out, (uint64_t)mask);
    if (rc < 0) return rc;
    if (out->nb_channels != channels) {
        av_channel_layout_uninit(out);
        return AVERROR(EINVAL);
    }
    return 0;
}

KC_API int ffkmp_swr_create(kc_swr **out,
                            int in_rate, int in_channels, int in_format,
                            int out_rate, int out_channels, int out_format,
                            int64_t in_mask, int64_t out_mask) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (in_rate <= 0 || out_rate <= 0 || in_channels <= 0 || out_channels <= 0 ||
        !kc_swr_format_valid(in_format) || !kc_swr_format_valid(out_format) ||
        in_mask < 0 || out_mask < 0) {
        return AVERROR(EINVAL);
    }
    kc_swr *s = av_mallocz(sizeof(*s));
    if (!s) return AVERROR(ENOMEM);
    int rc = kc_swr_layout(&s->in_layout, in_channels, in_mask);
    if (rc >= 0) rc = kc_swr_layout(&s->out_layout, out_channels, out_mask);
    if (rc >= 0) {
        rc = swr_alloc_set_opts2(&s->ctx, &s->out_layout, (enum AVSampleFormat)out_format, out_rate,
                                 &s->in_layout, (enum AVSampleFormat)in_format, in_rate, 0, NULL);
    }
    if (rc >= 0) rc = swr_init(s->ctx);
    if (rc < 0) {
        swr_free(&s->ctx);
        av_channel_layout_uninit(&s->in_layout);
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
    /* A frame whose demuxer never said which speaker each channel is, such as PCM in Matroska,
       is read as the configured input layout, the way the buffer source filter reads one. A
       reference carries the relabel, so the caller's frame is left as it was. */
    AVFrame *relabelled = NULL;
    if (rc >= 0 && in && in->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC &&
        in->ch_layout.nb_channels == s->in_layout.nb_channels) {
        relabelled = av_frame_alloc();
        rc = relabelled ? av_frame_ref(relabelled, in) : AVERROR(ENOMEM);
        if (rc >= 0) rc = av_channel_layout_copy(&relabelled->ch_layout, &s->in_layout);
    }
    if (rc >= 0) rc = swr_convert_frame(s->ctx, out, relabelled ? relabelled : in);
    av_frame_free(&relabelled);
    if (rc < 0) av_frame_unref(out);
    return rc;
}

KC_API void ffkmp_swr_free(kc_swr **s) {
    if (!s || !*s) return;
    swr_free(&(*s)->ctx);
    av_channel_layout_uninit(&(*s)->in_layout);
    av_channel_layout_uninit(&(*s)->out_layout);
    av_freep(s);
}
