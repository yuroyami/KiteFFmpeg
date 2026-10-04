/* The frame part of the FFmpeg helper layer: AVFrame, pixel and sample format names, and
 * dictionary iteration. */

#include "kitecodec_helpers.h"
#include "kc_convert.h"

#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>

/* ════════════ AVFrame ════════════ */

KC_API AVFrame* ffkmp_frame_alloc(void)        { return KC_GATE_OPEN() ? av_frame_alloc() : NULL; }
KC_API void     ffkmp_frame_free(AVFrame *f)   { if (f) { AVFrame *p = f; av_frame_free(&p); } }
KC_API void     ffkmp_frame_unref(AVFrame *f)  { if (f) av_frame_unref(f); }
KC_API int64_t  ffkmp_frame_pts(AVFrame *f)         { return f ? f->pts : AV_NOPTS_VALUE; }
KC_API int64_t  ffkmp_frame_duration(AVFrame *f)    { return f ? f->duration : 0; }
KC_API int      ffkmp_frame_format(AVFrame *f)      { return f ? f->format : -1; }
KC_API int      ffkmp_frame_width(AVFrame *f)       { return f ? f->width : 0; }
KC_API int      ffkmp_frame_height(AVFrame *f)      { return f ? f->height : 0; }
KC_API int      ffkmp_frame_nb_samples(AVFrame *f)  { return f ? f->nb_samples : 0; }
KC_API int      ffkmp_frame_sample_rate(AVFrame *f) { return f ? f->sample_rate : 0; }
KC_API int      ffkmp_frame_channels(AVFrame *f)    { return f ? f->ch_layout.nb_channels : 0; }
KC_API int      ffkmp_frame_linesize(AVFrame *f, int p) { return (f && p>=0 && p<AV_NUM_DATA_POINTERS) ? f->linesize[p] : 0; }
KC_API void     ffkmp_frame_set_pts(AVFrame *f, int64_t pts)     { if (f) f->pts = pts; }
KC_API void     ffkmp_frame_set_format(AVFrame *f, int v)        { if (f) f->format = v; }
KC_API void     ffkmp_frame_set_width(AVFrame *f, int v)         { if (f) f->width = v; }
KC_API void     ffkmp_frame_set_height(AVFrame *f, int v)        { if (f) f->height = v; }
KC_API void     ffkmp_frame_set_sample_rate(AVFrame *f, int v)   { if (f) f->sample_rate = v; }
KC_API void     ffkmp_frame_set_nb_samples(AVFrame *f, int v)    { if (f) f->nb_samples = v; }
KC_API int      ffkmp_frame_get_buffer(AVFrame *f, int align)    {
    return f ? av_frame_get_buffer(f, align) : AVERROR(EINVAL);
}
KC_API void     ffkmp_frame_set_ch_layout_default(AVFrame *f, int ch) {
    if (!f) return;
    av_channel_layout_uninit(&f->ch_layout);
    av_channel_layout_default(&f->ch_layout, ch);
}
/* Decoders fill best_effort_timestamp even when pts is missing (e.g. AVI without pts);
   promoting it to pts is what ffmpeg.c itself does before filtering/encoding. */
KC_API void     ffkmp_frame_use_best_effort_ts(AVFrame *f) {
    if (f) f->pts = f->best_effort_timestamp;
}
/* Deep-copy via new references to the same (refcounted) buffers, so O(1), no pixel copy.
   The clone owns its references: safe to hold after the source frame is reused/unref'd. */
KC_API AVFrame* ffkmp_frame_clone(const AVFrame *f) { return (f && KC_GATE_OPEN()) ? av_frame_clone(f) : NULL; }

KC_API int ffkmp_frame_a53_cc(AVFrame *f, uint8_t *dst, int dst_size) {
    const AVFrameSideData *sd;
    if (!f) return AVERROR(EINVAL);
    sd = av_frame_get_side_data(f, AV_FRAME_DATA_A53_CC);
    if (!sd || sd->size == 0) return 0;
    if (sd->size > 0x7fffffff) return AVERROR(EINVAL);
    if (dst) {
        if (dst_size < (int)sd->size) return AVERROR(EINVAL);
        memcpy(dst, sd->data, sd->size);
    }
    return (int)sd->size;
}

/* Exact on every target (#164). Every caller in this library keeps what this returns, as an image
   or as an encoder's input, so the bytes matter more than the time. A picture drawn once per frame
   takes ffkmp_frame_convert_display instead. */
KC_API AVFrame* ffkmp_frame_convert_pixfmt(const AVFrame *src, int dst_fmt) {
    return kc_sws_convert(src, dst_fmt, KC_SWS_EXACT);
}

KC_API int ffkmp_image_get_buffer_size(int fmt, int w, int h, int align) {
    return av_image_get_buffer_size(fmt, w, h, align);
}
KC_API int ffkmp_frame_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size) {
    if (!f || !dst) return AVERROR(EINVAL);
    return av_image_copy_to_buffer(
        dst, dst_size,
        (const uint8_t * const *)f->data, f->linesize,
        f->format, f->width, f->height, 1);
}

/* Audio twin of the above: how many bytes the frame's samples occupy, and a flat copy.
   Planar formats land plane-after-plane (ch0 samples, ch1 samples, …), packed stay packed. */
KC_API int ffkmp_samples_get_buffer_size(AVFrame *f) {
    if (!f || f->nb_samples <= 0) return AVERROR(EINVAL);
    return av_samples_get_buffer_size(NULL, f->ch_layout.nb_channels, f->nb_samples, f->format, 1);
}
KC_API int ffkmp_samples_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size) {
    if (!f || !dst || f->nb_samples <= 0) return AVERROR(EINVAL);
    int ch = f->ch_layout.nb_channels;
    int needed = av_samples_get_buffer_size(NULL, ch, f->nb_samples, f->format, 1);
    if (needed < 0) return needed;
    if (needed > dst_size) return AVERROR(EINVAL);
    int planes = av_sample_fmt_is_planar(f->format) ? ch : 1;
    int plane_size = needed / planes;
    for (int p = 0; p < planes; p++) {
        if (!f->extended_data[p]) return AVERROR(EINVAL);
        memcpy(dst + (size_t)p * plane_size, f->extended_data[p], plane_size);
    }
    return needed;
}

/* Reverse of ffkmp_frame_copy_to_buffer: fill an allocated video frame's planes from a
   tightly-packed (align=1) buffer. Frame must already carry width/height/format and have
   buffers (av_frame_get_buffer). */
KC_API int ffkmp_frame_fill_video(AVFrame *f, const uint8_t *src, int src_size) {
    if (!f || !src || f->width <= 0 || f->height <= 0) return AVERROR(EINVAL);
    int needed = av_image_get_buffer_size(f->format, f->width, f->height, 1);
    if (needed < 0) return needed;
    if (src_size < needed) return AVERROR(EINVAL);
    uint8_t *tmp_data[4]; int tmp_linesize[4];
    int rc = av_image_fill_arrays(tmp_data, tmp_linesize, src, f->format, f->width, f->height, 1);
    if (rc < 0) return rc;
    av_image_copy(f->data, f->linesize, (const uint8_t **)tmp_data, tmp_linesize,
                  f->format, f->width, f->height);
    return 0;
}

/* Reverse of ffkmp_samples_copy_to_buffer: fill an allocated audio frame's planes from a
   flat buffer (planar: plane-after-plane; packed: interleaved).

   No channel cap. This used to refuse above AV_NUM_DATA_POINTERS while its READING twin took any
   count, so a 10-channel planar frame could be copied out of the library and not back in. Both
   walk `extended_data`, which is the field that exists precisely because `data` stops at eight,
   and the per-plane NULL check below is the real guard: a frame whose planes were never
   allocated refuses whatever its channel count says. */
KC_API int ffkmp_frame_fill_audio(AVFrame *f, const uint8_t *src, int src_size) {
    if (!f || !src || f->nb_samples <= 0) return AVERROR(EINVAL);
    int ch = f->ch_layout.nb_channels;
    if (ch <= 0) return AVERROR(EINVAL);
    int needed = av_samples_get_buffer_size(NULL, ch, f->nb_samples, f->format, 1);
    if (needed < 0) return needed;
    if (src_size < needed) return AVERROR(EINVAL);
    int planes = av_sample_fmt_is_planar(f->format) ? ch : 1;
    int plane_size = needed / planes;
    for (int p = 0; p < planes; p++) {
        if (!f->extended_data[p]) return AVERROR(EINVAL);
        memcpy(f->extended_data[p], src + (size_t)p * plane_size, plane_size);
    }
    return 0;
}

/* ════════════ Pixel/sample format names ════════════ */

KC_API const char* ffkmp_pix_fmt_name(int fmt)              { return av_get_pix_fmt_name(fmt); }
KC_API int         ffkmp_pix_fmt_from_name(const char *n)   { return av_get_pix_fmt(n); }
KC_API const char* ffkmp_sample_fmt_name(int fmt)           { return av_get_sample_fmt_name(fmt); }
KC_API int         ffkmp_sample_fmt_from_name(const char *n){ return av_get_sample_fmt(n); }

/* ════════════ AVDictionary iteration ════════════ */

KC_API AVDictionaryEntry* ffkmp_dict_get(AVDictionary *d, AVDictionaryEntry *prev) {
    return av_dict_get(d, "", prev, AV_DICT_IGNORE_SUFFIX);
}
KC_API const char* ffkmp_dict_entry_key(AVDictionaryEntry *e)   { return e ? e->key   : NULL; }
KC_API const char* ffkmp_dict_entry_value(AVDictionaryEntry *e) { return e ? e->value : NULL; }
