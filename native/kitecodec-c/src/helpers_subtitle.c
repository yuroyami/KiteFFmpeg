/* Subtitle decoding: avcodec_decode_subtitle2 behind an opaque AVSubtitle, and the conversion of
 * its palette images to premultiplied RGBA. Blu-ray, DVB and DVD subtitles are palette images, so
 * the conversion is the part every image format shares. The converter at the end encodes decoded
 * text subtitles again with another text codec, which is how a transcode changes their format. */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>

#include <stdint.h>
#include <string.h>

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
    if (!c) return AVERROR(EINVAL);
    /* A NULL packet is the drain: an empty packet, which a decoder with AV_CODEC_CAP_DELAY, such
     * as the CEA-608 caption decoder, answers with the subtitle it still holds, and which every
     * other decoder is not even handed. */
    AVPacket *empty = NULL;
    if (!p) {
        empty = av_packet_alloc();
        if (!empty) return AVERROR(ENOMEM);
        p = empty;
    }
    AVSubtitle *sub = av_mallocz(sizeof(*sub));
    if (!sub) {
        av_packet_free(&empty);
        return AVERROR(ENOMEM);
    }
    int got = 0;
    int rc = avcodec_decode_subtitle2(c, sub, &got, p);
    av_packet_free(&empty);
    if (rc < 0 || !got) {
        avsubtitle_free(sub);
        av_free(sub);
        return rc < 0 ? rc : 0;
    }
    *out = sub;
    return 0;
}

KC_API int ffkmp_caption_decoder_open(AVCodecContext **out, int real_time) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_EIA_608);
    if (!codec) return AVERROR_DECODER_NOT_FOUND;
    AVCodecContext *c = avcodec_alloc_context3(codec);
    if (!c) return AVERROR(ENOMEM);
    /* The caller's times are microseconds, and without a packet time base every decoded time is unset. */
    c->time_base = AV_TIME_BASE_Q;
    c->pkt_timebase = AV_TIME_BASE_Q;
    /* Field 1, which carries CC1 and CC2. Left to choose, the decoder takes the field of the first
       byte it is given before it checks that byte, so one damaged or CEA-708 byte at the start
       locks it onto a field nothing is captioned in. */
    AVDictionary *options = NULL;
    int rc = av_dict_set(&options, "data_field", "first", 0);
    /* Real time answers as the screen changes, which a player needs (#180). */
    if (rc >= 0) rc = av_dict_set(&options, "real_time", real_time ? "1" : "0", 0);
    if (rc >= 0) rc = avcodec_open2(c, codec, &options);
    av_dict_free(&options);
    if (rc < 0) {
        avcodec_free_context(&c);
        return rc;
    }
    *out = c;
    return 0;
}

KC_API int ffkmp_caption_decode(AVCodecContext *c, const uint8_t *data, int size, int64_t pts_us, AVSubtitle **out) {
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (!c || !data || size < 1) return AVERROR(EINVAL);
    AVPacket *p = av_packet_alloc();
    if (!p) return AVERROR(ENOMEM);
    int rc = av_new_packet(p, size);
    if (rc < 0) {
        av_packet_free(&p);
        return rc;
    }
    memcpy(p->data, data, (size_t)size);
    p->pts = pts_us;
    p->dts = pts_us;
    rc = ffkmp_subtitle_decode(c, p, out);
    av_packet_free(&p);
    /* In real time FFmpeg's decoder dates a changed screen at the change before it, the start its
       buffered mode gives a caption. The screen as it now stands shows from this frame (#180). */
    int64_t real_time = 0;
    if (rc >= 0 && *out && av_opt_get_int(c->priv_data, "real_time", 0, &real_time) >= 0 && real_time) {
        (*out)->pts = pts_us;
        (*out)->start_display_time = 0;
    }
    return rc;
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

/* ---- Subtitle conversion ---- */

struct kc_subtitle_converter {
    AVCodecContext *dec;
    AVCodecContext *enc;
    /* The input stream's time base, which every converted packet is stamped on. */
    AVRational stream_tb;
    /* The encoder's output, reused for every subtitle. */
    uint8_t *buf;
};

/* 1 MiB, the buffer FFmpeg's own tool gives a subtitle encoder. */
#define KC_SUBTITLE_OUT_MAX (1 << 20)

KC_API void ffkmp_subtitle_converter_free(kc_subtitle_converter **c) {
    if (!c || !*c) return;
    avcodec_free_context(&(*c)->dec);
    avcodec_free_context(&(*c)->enc);
    av_freep(&(*c)->buf);
    av_freep(c);
}

KC_API int ffkmp_subtitle_converter_open(AVFormatContext *ctx, int stream_index, const char *codec,
                                         AVCodecParameters *out_par, kc_subtitle_converter **out) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (!ctx || !codec || !out_par || stream_index < 0 || (unsigned)stream_index >= ctx->nb_streams) {
        return AVERROR(EINVAL);
    }
    const AVStream *st = ctx->streams[stream_index];
    if (st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) return AVERROR(EINVAL);
    const AVCodecDescriptor *in_desc = avcodec_descriptor_get(st->codecpar->codec_id);
    if (!in_desc || !(in_desc->props & AV_CODEC_PROP_TEXT_SUB)) return AVERROR_PATCHWELCOME;
    const AVCodecDescriptor *out_desc = avcodec_descriptor_get_by_name(codec);
    if (!out_desc || out_desc->type != AVMEDIA_TYPE_SUBTITLE || !(out_desc->props & AV_CODEC_PROP_TEXT_SUB)) {
        return AVERROR(EINVAL);
    }
    const AVCodec *encoder = avcodec_find_encoder(out_desc->id);
    if (!encoder) return AVERROR_ENCODER_NOT_FOUND;

    kc_subtitle_converter *c = av_mallocz(sizeof(*c));
    if (!c) return AVERROR(ENOMEM);
    int rc = ffkmp_subtitle_decoder_open(ctx, stream_index, &c->dec);
    if (rc < 0) goto fail;
    c->enc = avcodec_alloc_context3(encoder);
    c->buf = av_malloc(KC_SUBTITLE_OUT_MAX);
    if (!c->enc || !c->buf) {
        rc = AVERROR(ENOMEM);
        goto fail;
    }
    c->enc->time_base = AV_TIME_BASE_Q;
    if (c->dec->subtitle_header && c->dec->subtitle_header_size > 0) {
        c->enc->subtitle_header = av_mallocz((size_t)c->dec->subtitle_header_size + 1);
        if (!c->enc->subtitle_header) {
            rc = AVERROR(ENOMEM);
            goto fail;
        }
        memcpy(c->enc->subtitle_header, c->dec->subtitle_header, (size_t)c->dec->subtitle_header_size);
        c->enc->subtitle_header_size = c->dec->subtitle_header_size;
    }
    rc = avcodec_open2(c->enc, encoder, NULL);
    if (rc >= 0) rc = avcodec_parameters_from_context(out_par, c->enc);
    if (rc < 0) goto fail;
    c->stream_tb = st->time_base;
    *out = c;
    return 0;
fail:
    ffkmp_subtitle_converter_free(&c);
    return rc;
}

KC_API int ffkmp_subtitle_converter_convert(kc_subtitle_converter *c, const AVPacket *in, AVPacket *out) {
    if (!c || !in || !out) return AVERROR(EINVAL);
    AVSubtitle sub;
    memset(&sub, 0, sizeof(sub));
    int got = 0;
    int rc = avcodec_decode_subtitle2(c->dec, &sub, &got, in);
    if (rc < 0) return rc;
    if (!got) return 0;
    if (sub.num_rects == 0 || sub.pts == AV_NOPTS_VALUE) {
        avsubtitle_free(&sub);
        return 0;
    }
    /* The encoder takes the start in pts and a start_display_time of zero, as FFmpeg's own tool
     * hands it over. UINT32_MAX says the stream gives no end, which stays unset. */
    int has_end = sub.end_display_time != UINT32_MAX && sub.end_display_time > sub.start_display_time;
    int64_t duration_ms = has_end ? (int64_t)sub.end_display_time - sub.start_display_time : 0;
    sub.pts += av_rescale_q(sub.start_display_time, (AVRational){ 1, 1000 }, AV_TIME_BASE_Q);
    sub.end_display_time = has_end ? (uint32_t)duration_ms : UINT32_MAX;
    sub.start_display_time = 0;
    int size = avcodec_encode_subtitle(c->enc, c->buf, KC_SUBTITLE_OUT_MAX, &sub);
    int64_t pts = sub.pts;
    avsubtitle_free(&sub);
    if (size < 0) return size;
    if (size == 0) return 0;
    av_packet_unref(out);
    rc = av_new_packet(out, size);
    if (rc < 0) return rc;
    memcpy(out->data, c->buf, (size_t)size);
    out->pts = av_rescale_q(pts, AV_TIME_BASE_Q, c->stream_tb);
    out->dts = out->pts;
    if (has_end) out->duration = av_rescale_q(duration_ms, (AVRational){ 1, 1000 }, c->stream_tb);
    out->flags |= AV_PKT_FLAG_KEY;
    return 1;
}
