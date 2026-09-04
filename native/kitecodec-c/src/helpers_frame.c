/* Ordinary maintained source since the interlude. Lifted at B1.3 from the def body of
 * kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in that commit, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The frame part of the FFmpeg helper layer: the def's 'AVFrame', 'Pixel/sample format names', 'AVDictionary iteration' section(s). */

#include "kitecodec_helpers.h"

#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>
#include <libswscale/swscale.h>

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

/* Maps an AVColorSpace onto the SWS_CS_* table sws_getCoefficients understands. */
static int kc_sws_cs_for(enum AVColorSpace spc, int height) {
    switch (spc) {
        case AVCOL_SPC_BT709:            return SWS_CS_ITU709;
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_BT470BG:          return SWS_CS_ITU601;
        case AVCOL_SPC_SMPTE240M:        return SWS_CS_SMPTE240M;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:        return SWS_CS_BT2020;
        default:
            /* Undeclared colour is normal; guess by the rule every player uses: SD is 601,
               HD and above is 709. */
            return height >= 720 ? SWS_CS_ITU709 : SWS_CS_ITU601;
    }
}

/* Is this format one swscale can actually take on the named side?

   libswscale ASSERTS on a format outside the enum rather than returning an error, so an arbitrary
   integer arriving through the exported C ABI took the whole process down. Three
   gates, in this order: av_pix_fmt_desc_get answers NULL for anything outside the enum, the
   HWACCEL flag marks a format whose planes are opaque handles rather than pixels, and the
   sws_isSupported pair is swscale's own verdict on everything that survives. The order matters:
   the sws predicates take an enum, so nothing reaches them until the descriptor proves the value
   names a real one. */
static int kc_pixfmt_convertible(int fmt, int as_output) {
    const AVPixFmtDescriptor *desc;
    if (fmt == AV_PIX_FMT_NONE) return 0;
    desc = av_pix_fmt_desc_get((enum AVPixelFormat)fmt);
    if (!desc) return 0;
    if (desc->flags & AV_PIX_FMT_FLAG_HWACCEL) return 0;
    return as_output ? sws_isSupportedOutput((enum AVPixelFormat)fmt)
                     : sws_isSupportedInput((enum AVPixelFormat)fmt);
}

/* Pixel format conversion (e.g. yuv420p → rgb24). Returns a freshly allocated frame the caller
   must av_frame_free, or NULL on failure.

   The converter is CACHED per thread through sws_getCachedContext, which reuses the existing
   context while the geometry and formats match and rebuilds it when they change: the repository's
   own allocation baseline measured 9 to 61 allocations per call for the create-and-free shape
   this replaces (audit KiteFFmpeg P1-4). One context per calling thread is deliberate: swscale
   contexts are not thread-safe, and decode/encode paths are thread-confined already.

   Colour is configured, not assumed: the source's matrix and range feed sws_setColorspaceDetails
   (swscale otherwise assumes 601/limited for everything, visibly wrong for HD), and RGB output is
   produced full-range, which is what every RGB consumer expects. Frame properties (SAR, colour
   tags, timestamps, duration) are carried over with av_frame_copy_props instead of losing
   everything but pts. Hardware frames are refused: their data pointers are opaque handles, not
   planes; download first. */
KC_API AVFrame* ffkmp_frame_convert_pixfmt(const AVFrame *src, int dst_fmt) {
    static _Thread_local struct SwsContext *kc_sws_cache = NULL;
    if (!src || src->width <= 0 || src->height <= 0) return NULL;
    if (src->hw_frames_ctx) return NULL;
    /* Both sides, before anything allocates: an unusable source format asserts inside swscale
       exactly as an unusable destination one does. */
    if (!kc_pixfmt_convertible(src->format, 0)) return NULL;
    if (!kc_pixfmt_convertible(dst_fmt, 1)) return NULL;
    const AVPixFmtDescriptor *dst_desc = av_pix_fmt_desc_get((enum AVPixelFormat)dst_fmt);
    int src_full = src->color_range == AVCOL_RANGE_JPEG;
    int dst_rgb = (dst_desc->flags & AV_PIX_FMT_FLAG_RGB) != 0;
    int dst_full = dst_rgb ? 1 : src_full;
    kc_sws_cache = sws_getCachedContext(kc_sws_cache,
        src->width, src->height, (enum AVPixelFormat)src->format,
        src->width, src->height, (enum AVPixelFormat)dst_fmt,
        SWS_BILINEAR, NULL, NULL, NULL);
    if (!kc_sws_cache) return NULL;
    {
        const int *coeffs = sws_getCoefficients(kc_sws_cs_for(src->colorspace, src->height));
        /* Refuses (-1) for pure RGB<->RGB conversions, where there is nothing to configure. */
        (void)sws_setColorspaceDetails(kc_sws_cache, coeffs, src_full, coeffs, dst_full,
                                       0, 1 << 16, 1 << 16);
    }
    AVFrame *dst = av_frame_alloc();
    if (!dst) return NULL;
    dst->width = src->width; dst->height = src->height; dst->format = dst_fmt;
    if (av_frame_get_buffer(dst, 0) < 0 ||
        sws_scale(kc_sws_cache, (const uint8_t * const *)src->data, src->linesize,
                  0, src->height, dst->data, dst->linesize) < 0) {
        av_frame_free(&dst);
        return NULL;
    }
    /* SAR, colour tags, pts, duration and the rest travel with the picture. copy_props does not
       touch width/height/format/data, so the conversion's own fields stand. */
    if (av_frame_copy_props(dst, src) < 0) { av_frame_free(&dst); return NULL; }
    /* Then the two tags copy_props gets WRONG for a converted frame, because they describe the
       source's encoding rather than this output's. The pixels above were produced
       full range for an RGB destination, and their matrix is RGB, not the source's YUV one; a
       consumer trusting the copied tags would convert a second time and crush the range.
       Primaries and transfer describe the light itself, not the encoding, so they stay. */
    dst->color_range = dst_full ? AVCOL_RANGE_JPEG : src->color_range;
    if (dst_rgb) dst->colorspace = AVCOL_SPC_RGB;
    return dst;
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
