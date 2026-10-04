/* The swscale step of the FFmpeg helper layer, which two units share without a symbol between them.
 *
 * ffkmp_frame_convert_pixfmt in helpers_frame.c converts exactly, and ffkmp_frame_convert_display in
 * helpers_display.c converts fast (#164). Both need the same steps around swscale: the format gates,
 * the colour setup, a cached context per thread and the frame's properties. symbol-audit.sh holds
 * the archive's external symbols to the KC_API declarations, so the steps live here as static
 * functions and each unit that includes this file compiles its own copy, with its own cache. A
 * thread that both draws and encodes therefore keeps one context for each flag set, rather than
 * rebuilding one context every time it moves between them. */

#ifndef KC_CONVERT_H
#define KC_CONVERT_H

#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>

#include <pthread.h>

/* Every 8-bit result within 1 of what the source's colour matrix gives, and the same bytes on every
   architecture. From YUV to RGB swscale has an output that reads lookup tables and lands up to 3
   off, in its C code and its SIMD alike, and one that computes from full chroma. Full chroma
   interpolation selects the second, but for a frame of even height swscale takes a fast path of
   tables only that ignores it, which is also why one colour used to convert to different bytes in
   a frame with an odd number of rows. Accurate rounding keeps every frame off that fast path. Full
   chroma input is the same care from RGB to YUV, and bit-exact keeps SIMD from rounding its own
   way, as swscale's header recommends beside accurate rounding. Accurate rounding and the two full
   chroma flags are mpv's default quality flags. On x86-64 swscale takes about 11 ms for a 1080p
   yuv420p frame to rgba, where the fast path takes under 1 ms, and from RGB to YUV about an eighth
   longer than the fast path. */
#define KC_SWS_EXACT (SWS_BILINEAR | SWS_ACCURATE_RND | SWS_BITEXACT | SWS_FULL_CHR_H_INT | SWS_FULL_CHR_H_INP)

/* swscale's fast paths: up to 3 off the colour matrix from YUV to RGB, with each chroma sample
   repeated across a pair of pixels in a row rather than interpolated. For an 8-bit 4:2:0 frame of
   even height, the common case, that is about a tenth of the exact path's time, and for other
   frames about a third. For a picture drawn once per frame, where the frame's time budget is what
   matters and the result is not kept. */
#define KC_SWS_FAST SWS_BILINEAR

/* Maps an AVColorSpace onto the SWS_CS_* table sws_getCoefficients understands. */
static int kc_sws_cs_for(enum AVColorSpace spc, int height) {
    switch (spc) {
        case AVCOL_SPC_BT709:            return SWS_CS_ITU709;
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_BT470BG:          return SWS_CS_ITU601;
        case AVCOL_SPC_SMPTE240M:        return SWS_CS_SMPTE240M;
        case AVCOL_SPC_FCC:              return SWS_CS_FCC;
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

/* Pixel format conversion (e.g. yuv420p → rgb24) with the swscale flags given, KC_SWS_EXACT or
   KC_SWS_FAST. Returns a freshly allocated frame the caller must av_frame_free, or NULL on failure.

   The converter is CACHED per thread through sws_getCachedContext, which reuses the existing
   context while the geometry and formats match and rebuilds it when they change: the repository's
   own allocation baseline measured 9 to 61 allocations per call for the create-and-free shape
   this replaces. One context per calling thread is deliberate: swscale
   contexts are not thread-safe, and decode/encode paths are thread-confined already.

   Colour is configured, not assumed: the source's matrix and range feed sws_setColorspaceDetails
   (swscale otherwise assumes 601/limited for everything, visibly wrong for HD), and RGB output is
   produced full-range, which is what every RGB consumer expects. Frame properties (SAR, colour
   tags, timestamps, duration) are carried over with av_frame_copy_props instead of losing
   everything but pts. Hardware frames are refused: their data pointers are opaque handles, not
   planes; download first. */
/* Each thread's cached converter lives in a thread key whose destructor frees it when the thread
   ends. A _Thread_local pointer had no such hook, so every thread that converted and then ended
   took the only pointer to its context with it (#105). The key is created once per process. */
static pthread_key_t kc_sws_key;
static pthread_once_t kc_sws_key_once = PTHREAD_ONCE_INIT;
static int kc_sws_key_ready = 0;

static void kc_sws_free(void *context) {
    sws_freeContext((struct SwsContext *)context);
}

static void kc_sws_key_create(void) {
    kc_sws_key_ready = pthread_key_create(&kc_sws_key, kc_sws_free) == 0;
}

static AVFrame *kc_sws_convert(const AVFrame *src, int dst_fmt, int flags) {
    struct SwsContext *kc_sws_cache;
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
    (void)pthread_once(&kc_sws_key_once, kc_sws_key_create);
    {
        /* Without a key there is nowhere to keep a context, so this call builds its own and the
           end of the function frees it. */
        struct SwsContext *cached = kc_sws_key_ready ? (struct SwsContext *)pthread_getspecific(kc_sws_key) : NULL;
        kc_sws_cache = sws_getCachedContext(cached,
            src->width, src->height, (enum AVPixelFormat)src->format,
            src->width, src->height, (enum AVPixelFormat)dst_fmt,
            flags, NULL, NULL, NULL);
        /* sws_getCachedContext frees `cached` when it builds another, and on failure too. */
        if (kc_sws_key_ready && kc_sws_cache != cached) (void)pthread_setspecific(kc_sws_key, kc_sws_cache);
    }
    if (!kc_sws_cache) return NULL;
    {
        const int *coeffs = sws_getCoefficients(kc_sws_cs_for(src->colorspace, src->height));
        /* Refuses (-1) for pure RGB<->RGB conversions, where there is nothing to configure. */
        (void)sws_setColorspaceDetails(kc_sws_cache, coeffs, src_full, coeffs, dst_full,
                                       0, 1 << 16, 1 << 16);
    }
    AVFrame *dst = av_frame_alloc();
    if (!dst) goto done;
    dst->width = src->width; dst->height = src->height; dst->format = dst_fmt;
    if (av_frame_get_buffer(dst, 0) < 0 ||
        sws_scale(kc_sws_cache, (const uint8_t * const *)src->data, src->linesize,
                  0, src->height, dst->data, dst->linesize) < 0) {
        av_frame_free(&dst);
        goto done;
    }
    /* SAR, colour tags, pts, duration and the rest travel with the picture. copy_props does not
       touch width/height/format/data, so the conversion's own fields stand. */
    if (av_frame_copy_props(dst, src) < 0) { av_frame_free(&dst); goto done; }
    /* Then the two tags copy_props gets WRONG for a converted frame, because they describe the
       source's encoding rather than this output's. The pixels above were produced
       full range for an RGB destination, and their matrix is RGB, not the source's YUV one; a
       consumer trusting the copied tags would convert a second time and crush the range.
       Primaries and transfer describe the light itself, not the encoding, so they stay. */
    dst->color_range = dst_full ? AVCOL_RANGE_JPEG : src->color_range;
    if (dst_rgb) dst->colorspace = AVCOL_SPC_RGB;
done:
    if (!kc_sws_key_ready) sws_freeContext(kc_sws_cache);
    return dst;
}

#endif
