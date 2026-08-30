/* Ordinary maintained source since the interlude. Lifted at B1.3 from the def body of
 * kiteffmpeg-core/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in KPKMP.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The codec part of the FFmpeg helper layer: the def's 'AVCodec / AVCodecContext' section(s). */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>

/* ════════════ AVCodec / AVCodecContext ════════════ */

KC_API AVCodecContext* ffkmp_codecctx_alloc(const AVCodec *c) { return avcodec_alloc_context3(c); }
KC_API void  ffkmp_codecctx_free(AVCodecContext *c) { if (c) { AVCodecContext *q = c; avcodec_free_context(&q); } }
KC_API int   ffkmp_codecctx_open(AVCodecContext *c, const AVCodec *codec) {
    return c ? avcodec_open2(c, codec, NULL) : AVERROR(EINVAL);
}
KC_API int   ffkmp_codecctx_from_par(AVCodecContext *c, AVCodecParameters *p) {
    return (c && p) ? avcodec_parameters_to_context(c, p) : AVERROR(EINVAL);
}
KC_API int ffkmp_codecctx_send_packet(kc_codec_ctx *ctx, const kc_packet *packet) {
    return ctx ? avcodec_send_packet(ctx, packet) : AVERROR(EINVAL);
}
KC_API int ffkmp_codecctx_receive_frame(kc_codec_ctx *ctx, kc_frame *frame) {
    return (ctx && frame) ? avcodec_receive_frame(ctx, frame) : AVERROR(EINVAL);
}
KC_API int ffkmp_codecctx_send_frame(kc_codec_ctx *ctx, const kc_frame *frame) {
    return ctx ? avcodec_send_frame(ctx, frame) : AVERROR(EINVAL);
}
KC_API int ffkmp_codecctx_receive_packet(kc_codec_ctx *ctx, kc_packet *packet) {
    return (ctx && packet) ? avcodec_receive_packet(ctx, packet) : AVERROR(EINVAL);
}
KC_API void  ffkmp_codecctx_set_video(
    AVCodecContext *c, int width, int height, int pix_fmt,
    int fr_num, int fr_den, int tb_num, int tb_den, int64_t bit_rate, int gop_size
) {
    if (!c) return;
    c->width = width; c->height = height;
    c->pix_fmt = (enum AVPixelFormat)pix_fmt;
    c->framerate.num = fr_num; c->framerate.den = fr_den ? fr_den : 1;
    c->time_base.num = tb_num; c->time_base.den = tb_den ? tb_den : 1;
    c->bit_rate = bit_rate; c->gop_size = gop_size;
}
KC_API void  ffkmp_codecctx_set_audio(
    AVCodecContext *c, int sample_rate, int sample_fmt, int channels, int64_t bit_rate
) {
    if (!c) return;
    c->sample_rate = sample_rate;
    c->sample_fmt = (enum AVSampleFormat)sample_fmt;
    av_channel_layout_uninit(&c->ch_layout);
    av_channel_layout_default(&c->ch_layout, channels);
    c->time_base.num = 1; c->time_base.den = sample_rate ? sample_rate : 1;
    c->bit_rate = bit_rate;
}
/* First sample format the encoder supports, used to pick a sane default (e.g. aac → fltp).
   avcodec_get_supported_config landed in FFmpeg 7.1 (lavc 61.13); distro FFmpeg 6.x still
   exposes the deprecated AVCodec.sample_fmts array, so version-gate to support both. */
KC_API int   ffkmp_codec_first_sample_fmt(const AVCodec *codec) {
    if (!codec) return -1;
#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 13, 100)
    const enum AVSampleFormat *fmts = NULL;
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0,
                                     (const void **)&fmts, NULL) < 0 || !fmts) return -1;
    return fmts[0] == AV_SAMPLE_FMT_NONE ? -1 : (int)fmts[0];
#else
    if (!codec->sample_fmts || codec->sample_fmts[0] == AV_SAMPLE_FMT_NONE) return -1;
    return (int)codec->sample_fmts[0];
#endif
}
/* Pixel-format twin of the sample-format query above, same 7.1 version gate. */
static const enum AVPixelFormat* ffkmp_codec_pix_fmts_(const AVCodec *codec) {
    if (!codec) return NULL;
#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 13, 100)
    const enum AVPixelFormat *fmts = NULL;
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_PIX_FORMAT, 0,
                                     (const void **)&fmts, NULL) < 0) return NULL;
    return fmts;
#else
    return codec->pix_fmts;
#endif
}
KC_API int ffkmp_codec_first_pix_fmt(const AVCodec *codec) {
    const enum AVPixelFormat *fmts = ffkmp_codec_pix_fmts_(codec);
    if (!fmts || fmts[0] == AV_PIX_FMT_NONE) return -1;
    return (int)fmts[0];
}
KC_API int ffkmp_codec_supports_pix_fmt(const AVCodec *codec, int fmt) {
    const enum AVPixelFormat *fmts = ffkmp_codec_pix_fmts_(codec);
    if (!fmts) return 0;
    for (int i = 0; fmts[i] != AV_PIX_FMT_NONE; i++) {
        if ((int)fmts[i] == fmt) return 1;
    }
    return 0;
}
KC_API int   ffkmp_codecctx_frame_size(AVCodecContext *c)  { return c ? c->frame_size : 0; }
KC_API int   ffkmp_codecctx_sample_rate(AVCodecContext *c) { return c ? c->sample_rate : 0; }
KC_API int   ffkmp_codecctx_channels(AVCodecContext *c)    { return c ? c->ch_layout.nb_channels : 0; }
KC_API void  ffkmp_codecctx_time_base(AVCodecContext *c, int *n, int *d) {
    if (!c || !n || !d) return;
    *n = c->time_base.num; *d = c->time_base.den ? c->time_base.den : 1;
}
KC_API void  ffkmp_codecctx_set_global_header(AVCodecContext *c) {
    if (c) c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
}
/* Codec-private + generic option setter ("preset"=veryfast, "crf"=23, "allow_sw"=1 …).
   SEARCH_CHILDREN reaches priv_data, so both generic and per-encoder options resolve. */
KC_API int   ffkmp_codecctx_set_opt(AVCodecContext *c, const char *key, const char *value) {
    if (!c || !key) return AVERROR(EINVAL);
    return av_opt_set(c, key, value, AV_OPT_SEARCH_CHILDREN);
}
/* FFmpeg ≥7 dropped the yuvj* formats: the mjpeg encoder takes plain yuv420p but refuses to
   open unless the context declares full (JPEG) color range. */
KC_API void  ffkmp_codecctx_set_full_range(AVCodecContext *c) {
    if (c) c->color_range = AVCOL_RANGE_JPEG;
}
KC_API const AVCodec* ffkmp_find_decoder_by_id(int id) { return avcodec_find_decoder((enum AVCodecID)id); }
KC_API const kc_codec* ffkmp_find_encoder_by_name(const char *name) {
    return name ? avcodec_find_encoder_by_name(name) : NULL;
}
KC_API const kc_codec* ffkmp_find_decoder_by_name(const char *name) {
    return name ? avcodec_find_decoder_by_name(name) : NULL;
}
KC_API int ffkmp_codec_id(const kc_codec *codec) {
    return codec ? (int)codec->id : 0;
}
/* The CODEC's canonical name ("av1", "opus", "mov_text"), independent of which decoder
   implementation this build happens to register for it; avcodec_find_decoder would answer
   "libdav1d"/"libopus" and would answer NOTHING at all for streams with no decoder compiled
   in (subtitles, attachments, data). Never returns NULL; unknown ids yield "none". */
KC_API const char* ffkmp_codec_id_name(int id) { return avcodec_get_name((enum AVCodecID)id); }
KC_API int ffkmp_codecctx_pix_fmt(AVCodecContext *c) { return c ? (int)c->pix_fmt : -1; }
KC_API int ffkmp_codecctx_width(AVCodecContext *c)   { return c ? c->width : 0; }
KC_API int ffkmp_codecctx_height(AVCodecContext *c)  { return c ? c->height : 0; }
