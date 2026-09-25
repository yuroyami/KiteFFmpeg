/* Subtitle decoding: avcodec_decode_subtitle2 behind an opaque AVSubtitle, and the conversion of
 * its palette images to premultiplied RGBA. Blu-ray, DVB and DVD subtitles are palette images, so
 * the conversion is the part every image format shares. */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>

#include <stdint.h>

KC_API int ffkmp_subtitle_decoder_open(AVFormatContext *ctx, int stream_index, AVCodecContext **out) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (!ctx || stream_index < 0 || (unsigned)stream_index >= ctx->nb_streams) return AVERROR(EINVAL);
    const AVStream *st = ctx->streams[stream_index];
    if (st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) return AVERROR(EINVAL);
    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) return AVERROR_DECODER_NOT_FOUND;
    AVCodecContext *c = avcodec_alloc_context3(codec);
    if (!c) return AVERROR(ENOMEM);
    int rc = avcodec_parameters_to_context(c, st->codecpar);
    if (rc >= 0) {
        /* Without it avcodec_decode_subtitle2 leaves every decoded time unset. */
        c->pkt_timebase = st->time_base;
        rc = avcodec_open2(c, codec, NULL);
    }
    if (rc < 0) {
        avcodec_free_context(&c);
        return rc;
    }
    *out = c;
    return 0;
}

KC_API int ffkmp_subtitle_decode(AVCodecContext *c, const AVPacket *p, AVSubtitle **out) {
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (!c || !p) return AVERROR(EINVAL);
    AVSubtitle *sub = av_mallocz(sizeof(*sub));
    if (!sub) return AVERROR(ENOMEM);
    int got = 0;
    int rc = avcodec_decode_subtitle2(c, sub, &got, p);
    if (rc < 0 || !got) {
        avsubtitle_free(sub);
        av_free(sub);
        return rc < 0 ? rc : 0;
    }
    *out = sub;
    return 0;
}

KC_API int ffkmp_subtitle_times(const AVSubtitle *s, int64_t *start_us, int64_t *end_us) {
    if (!s || !start_us || !end_us) return AVERROR(EINVAL);
    *start_us = INT64_MIN;
    *end_us = INT64_MIN;
    if (s->pts == AV_NOPTS_VALUE) return 0;
    *start_us = s->pts + (int64_t)s->start_display_time * 1000;
    /* UINT32_MAX says the stream has no end; an end at or before the start says nothing either. */
    if (s->end_display_time != UINT32_MAX && s->end_display_time > s->start_display_time) {
        *end_us = s->pts + (int64_t)s->end_display_time * 1000;
    }
    return 0;
}

KC_API int ffkmp_subtitle_rect_count(const AVSubtitle *s) {
    return s ? (int)s->num_rects : 0;
}

static const AVSubtitleRect *kc_rect_at_(const AVSubtitle *s, int i) {
    if (!s || i < 0 || (unsigned)i >= s->num_rects || !s->rects) return NULL;
    return s->rects[i];
}

KC_API int ffkmp_subtitle_rect(const AVSubtitle *s, int i, int *type, int *x, int *y, int *w, int *h,
                               int *forced) {
    const AVSubtitleRect *r = kc_rect_at_(s, i);
    if (!r || !type || !x || !y || !w || !h || !forced) return AVERROR(EINVAL);
    *type = (int)r->type;
    *x = r->x;
    *y = r->y;
    *w = r->w;
    *h = r->h;
    *forced = (r->flags & AV_SUBTITLE_FLAG_FORCED) ? 1 : 0;
    return 0;
}

KC_API int ffkmp_subtitle_rect_rgba(const AVSubtitle *s, int i, uint8_t *dst, int dst_size) {
    const AVSubtitleRect *r = kc_rect_at_(s, i);
    if (!r || !dst || r->type != SUBTITLE_BITMAP || r->w <= 0 || r->h <= 0 || !r->data[0] || !r->data[1]) {
        return AVERROR(EINVAL);
    }
    /* w * h * 4 in 64 bits, so a hostile size cannot wrap past the check. */
    if ((int64_t)r->w * r->h * 4 > (int64_t)dst_size) return AVERROR(EINVAL);
    const uint32_t *palette = (const uint32_t *)r->data[1];
    for (int row = 0; row < r->h; row++) {
        const uint8_t *indices = r->data[0] + (ptrdiff_t)row * r->linesize[0];
        uint8_t *out = dst + (ptrdiff_t)row * r->w * 4;
        for (int col = 0; col < r->w; col++) {
            uint32_t argb = indices[col] < r->nb_colors ? palette[indices[col]] : 0;
            uint32_t a = argb >> 24;
            /* Premultiplied, rounded to nearest: what every consumer of these pixels uploads. */
            out[4 * col + 0] = (uint8_t)((((argb >> 16) & 0xFF) * a + 127) / 255);
            out[4 * col + 1] = (uint8_t)((((argb >> 8) & 0xFF) * a + 127) / 255);
            out[4 * col + 2] = (uint8_t)(((argb & 0xFF) * a + 127) / 255);
            out[4 * col + 3] = (uint8_t)a;
        }
    }
    return 0;
}

KC_API const char *ffkmp_subtitle_rect_text(const AVSubtitle *s, int i) {
    const AVSubtitleRect *r = kc_rect_at_(s, i);
    if (!r) return NULL;
    if (r->type == SUBTITLE_ASS) return r->ass;
    if (r->type == SUBTITLE_TEXT) return r->text;
    return NULL;
}

KC_API void ffkmp_subtitle_free(AVSubtitle **s) {
    if (!s || !*s) return;
    avsubtitle_free(*s);
    av_freep(s);
}
