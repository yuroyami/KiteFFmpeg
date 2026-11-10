/* The display part of the FFmpeg helper layer: a decoded picture converted to 8-bit RGB for a
 * screen. The conversion is swscale's fast one, KC_SWS_FAST, rather than the exact one
 * ffkmp_frame_convert_pixfmt makes, because a player calls this for every frame it draws, and on
 * x86-64 the exact one takes about 11 ms of a 1080p frame where the fast one takes about 1.5, for
 * a difference of 3 levels at most (#164). Like that one, it changes only the encoding. This file
 * adds the two steps a screen needs on top of it and swscale cannot do in that call:
 *
 *   YCgCo, whose inverse matrix feeds both chroma planes into red and blue. swscale's coefficient
 *   table holds four numbers, one chroma plane per colour, so it cannot express it.
 *   PQ and HLG, which are tone mapped to SDR: linear light per channel, BT.2020 primaries folded
 *   to BT.709, luminance rolled off through the ITU-R BT.2390 EETF from the source's peak to 203
 *   nit reference white, then gamma 2.2. The source's peak is the frame's MaxCLL when it lies
 *   between 100 and 10000 nits, else its mastering display's peak in that range, else 1000 nits,
 *   as BT.2390 suggests for an unknown master. The law reads a 16-bit RGB intermediate and packs
 *   to 8 bits only after it: one 8-bit PQ step in the mid tones is several percent of brightness,
 *   which the EETF stretches into visible stairs (#231). */

#include "kitecodec_helpers.h"
#include "kc_convert.h"

#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>

#include <math.h>
#include <pthread.h>
#include <string.h>

/* Byte offsets of red, green, blue and alpha (-1 for none) inside one pixel of `step` bytes. */
typedef struct kc_rgb8 {
    int r, g, b, a, step;
} kc_rgb8;

/* Accepts exactly the packed 8-bit RGB formats of 3 or 4 bytes per pixel, rgba and bgr0 among
 * them, and fills in where each component sits. */
static int kc_rgb8_layout(int fmt, kc_rgb8 *out) {
    const AVPixFmtDescriptor *d;
    int i;
    if (fmt == AV_PIX_FMT_NONE) return 0;
    d = av_pix_fmt_desc_get((enum AVPixelFormat)fmt);
    if (!d || !(d->flags & AV_PIX_FMT_FLAG_RGB)) return 0;
    if (d->flags & (AV_PIX_FMT_FLAG_PLANAR | AV_PIX_FMT_FLAG_BITSTREAM | AV_PIX_FMT_FLAG_PAL |
                    AV_PIX_FMT_FLAG_HWACCEL | AV_PIX_FMT_FLAG_FLOAT)) return 0;
    if (d->nb_components < 3 || d->nb_components > 4) return 0;
    for (i = 0; i < d->nb_components; i++) {
        if (d->comp[i].plane != 0 || d->comp[i].depth != 8 || d->comp[i].shift != 0) return 0;
        if (d->comp[i].step != d->comp[0].step) return 0;
    }
    if (d->comp[0].step != 3 && d->comp[0].step != 4) return 0;
    out->r = d->comp[0].offset;
    out->g = d->comp[1].offset;
    out->b = d->comp[2].offset;
    out->a = d->nb_components == 4 ? d->comp[3].offset : -1;
    out->step = d->comp[0].step;
    return 1;
}

static uint8_t kc_clamp_byte(int v) { return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v); }

/* ════════════ YCgCo ════════════ */

/* swscale only upsamples the chroma and reduces the depth here, into yuv444p with the source's
 * range, and the matrix runs below: R = Y - Cg + Co, G = Y + Cg, B = Y - Cg - Co, with Cg in the
 * first chroma plane and Co in the second (ITU-T H.273, matrix 8). Studio range scales luma by
 * 255/219 and chroma by 255/224 first, in 16.16 fixed point. */
static AVFrame *kc_convert_ycgco(const AVFrame *src, int dst_fmt, const kc_rgb8 *lay) {
    AVFrame *yuv, *dst;
    int full = src->color_range == AVCOL_RANGE_JPEG;
    int x, y;
    yuv = kc_sws_convert(src, AV_PIX_FMT_YUV444P, KC_SWS_FAST);
    if (!yuv) return NULL;
    dst = av_frame_alloc();
    if (!dst) goto fail;
    dst->width = src->width;
    dst->height = src->height;
    dst->format = dst_fmt;
    if (av_frame_get_buffer(dst, 0) < 0) goto fail;
    for (y = 0; y < src->height; y++) {
        const uint8_t *py = yuv->data[0] + (ptrdiff_t)y * yuv->linesize[0];
        const uint8_t *pg = yuv->data[1] + (ptrdiff_t)y * yuv->linesize[1];
        const uint8_t *po = yuv->data[2] + (ptrdiff_t)y * yuv->linesize[2];
        uint8_t *out = dst->data[0] + (ptrdiff_t)y * dst->linesize[0];
        for (x = 0; x < src->width; x++, out += lay->step) {
            int luma = full ? py[x] * 65536 : (py[x] - 16) * 76310;
            int cg = (pg[x] - 128) * (full ? 65536 : 74607);
            int co = (po[x] - 128) * (full ? 65536 : 74607);
            out[lay->r] = kc_clamp_byte((luma - cg + co + 32768) / 65536);
            out[lay->g] = kc_clamp_byte((luma + cg + 32768) / 65536);
            out[lay->b] = kc_clamp_byte((luma - cg - co + 32768) / 65536);
            if (lay->a >= 0) out[lay->a] = 255;
        }
    }
    if (av_frame_copy_props(dst, src) < 0) goto fail;
    dst->color_range = AVCOL_RANGE_JPEG;
    dst->colorspace = AVCOL_SPC_RGB;
    av_frame_free(&yuv);
    return dst;
fail:
    av_frame_free(&dst);
    av_frame_free(&yuv);
    return NULL;
}

/* ════════════ Tone map ════════════ */

#define KC_DEFAULT_PEAK 1000.0
#define KC_REFERENCE_WHITE 203.0

/* The EETF is a table indexed by the bits of a float, its exponent and top mantissa bits, so a
   pixel costs table reads and multiplications. Each source peak has its own table, built once and
   kept: a stream keeps one peak, so a player builds one or two. */

/* The EETF ratio over luminance in nits, 2^-14 to 2^14 (PQ ends at 10000), eight mantissa bits
   per octave. */
#define KC_RATIO_BITS 8
#define KC_RATIO_BASE ((127 - 14) << KC_RATIO_BITS)
#define KC_RATIO_SIZE (28 << KC_RATIO_BITS)

/* The gamma 2.2 encode over SDR linear light, 2^-24 to 1, eleven mantissa bits per octave. */
#define KC_ENCODE_BITS 11
#define KC_ENCODE_BASE ((127 - 24) << KC_ENCODE_BITS)
#define KC_ENCODE_SIZE (24 << KC_ENCODE_BITS)

/* Full scale of swscale's 16-bit RGB, 255 shifted by eight bits. */
#define KC_WIDE_FULL 65280

/* How many peaks keep a table of their own; a peak past these builds one for the call. */
#define KC_PEAK_TABLES 8

typedef struct kc_tone_tables {
    float pq_eotf[65536];          /* PQ 16-bit code to nits */
    float hlg_eotf[65536];         /* HLG 16-bit code to scene light 0..1, before the OOTF */
    float pq_eotf8[256];           /* the two above at each byte, for the 8-bit path's cache */
    float hlg_eotf8[256];
    double hlg_ootf[1024];         /* sqrt-warped scene luminance to the HLG OOTF's Ys^0.2 */
    uint8_t encode[KC_ENCODE_SIZE];
    uint8_t encode_ref[4096];      /* the reference table the encode table is filled from */
} kc_tone_tables;

typedef struct kc_ratio_table {
    double peak;
    double ratio[KC_RATIO_SIZE];   /* luminance to the factor that maps it to SDR linear light */
} kc_ratio_table;

static kc_tone_tables kc_tone;
static pthread_once_t kc_tone_once = PTHREAD_ONCE_INIT;
static kc_ratio_table *kc_ratio_tables[KC_PEAK_TABLES];
static pthread_mutex_t kc_ratio_lock = PTHREAD_MUTEX_INITIALIZER;

static double kc_pq_encode(double y) {
    double p = pow(y > 0.0 ? y : 0.0, 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p), 78.84375);
}

static double kc_pq_decode(double e) {
    double p = pow(e > 0.0 ? e : 0.0, 1.0 / 78.84375);
    double num = p - 0.8359375;
    return pow((num > 0.0 ? num : 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}

/* BT.2390 EETF from [0, peak] into [0, KC_REFERENCE_WHITE] nits, in normalized PQ. A peak at or
   below reference white leaves every value as it is. */
static double kc_eetf_nits(double nits, double peak) {
    double src_pq = kc_pq_encode(peak / 10000.0);
    double dst_pq = kc_pq_encode(KC_REFERENCE_WHITE / 10000.0);
    double e1 = kc_pq_encode(nits / 10000.0) / src_pq;
    double max_lum = dst_pq / src_pq;
    double ks = 1.5 * max_lum - 0.5;
    double e2;
    if (e1 < 0.0) e1 = 0.0;
    if (e1 > 1.0) e1 = 1.0;
    if (e1 <= ks) {
        e2 = e1;
    } else {
        double t = (e1 - ks) / (1.0 - ks), t2 = t * t, t3 = t2 * t;
        e2 = (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * max_lum;
    }
    return kc_pq_decode(e2 * src_pq) * 10000.0;
}

static int kc_warp1023(double value, double max) {
    double n = value / max;
    if (n <= 0.0) return 0;
    if (n >= 1.0) return 1023;
    return (int)(sqrt(n) * 1023.0 + 0.5);
}

static int kc_warp4095(double linear) {
    if (linear <= 0.0) return 0;
    if (linear >= 1.0) return 4095;
    return (int)(sqrt(linear) * 4095.0 + 0.5);
}

static uint32_t kc_float_bits(float v) {
    uint32_t u;
    memcpy(&u, &v, sizeof u);
    return u;
}

static double kc_float_at(uint32_t bits) {
    float v;
    memcpy(&v, &bits, sizeof v);
    return v;
}

static void kc_tone_init(void) {
    kc_tone_tables *t = &kc_tone;
    int i;
    /* swscale's 16-bit RGB is its 8-bit scale shifted by eight bits: a studio range 10-bit white
       reads 65283, whatever the flags, and a full range one 65472. A code is therefore read over
       65280, which is within 0.3 percent of both, and the few above it clamp to full scale. */
    for (i = 0; i < 65536; i++) {
        double e = i >= KC_WIDE_FULL ? 1.0 : i / (double)KC_WIDE_FULL;
        t->pq_eotf[i] = (float)(kc_pq_decode(e) * 10000.0);
        t->hlg_eotf[i] = (float)(e <= 0.5 ? e * e / 3.0 : (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0);
    }
    for (i = 0; i < 256; i++) {
        t->pq_eotf8[i] = t->pq_eotf[i << 8];
        t->hlg_eotf8[i] = t->hlg_eotf[i << 8];
    }
    for (i = 0; i < 1024; i++) {
        double w = i / 1023.0;
        t->hlg_ootf[i] = w <= 0.0 ? 0.0 : pow(w * w, 0.2);
    }
    for (i = 0; i < 4096; i++) {
        double w = i / 4095.0;
        t->encode_ref[i] = kc_clamp_byte((int)(pow(w * w, 1.0 / 2.2) * 255.0 + 0.5));
    }
    /* Each entry takes the reference value at the middle of the float range it stands for. */
    for (i = 0; i < KC_ENCODE_SIZE; i++) {
        uint32_t bits = ((uint32_t)(i + KC_ENCODE_BASE) << (23 - KC_ENCODE_BITS)) | (1u << (22 - KC_ENCODE_BITS));
        t->encode[i] = t->encode_ref[kc_warp4095(kc_float_at(bits))];
    }
}

/* Fills the ratio table of one peak, each entry at the middle of the float range it stands for.
   The EETF itself is sampled through a square-root-warped table of 1024 points over [0, peak],
   as the reference law samples it. */
static void kc_ratio_fill(kc_ratio_table *table, double peak) {
    double eetf_ratio[1024];
    int i;
    table->peak = peak;
    for (i = 0; i < 1024; i++) {
        double w = i / 1023.0;
        double nits = w * w * peak;
        eetf_ratio[i] = nits <= 1e-4 ? 1.0 : kc_eetf_nits(nits, peak) / nits;
    }
    for (i = 0; i < KC_RATIO_SIZE; i++) {
        uint32_t bits = ((uint32_t)(i + KC_RATIO_BASE) << (23 - KC_RATIO_BITS)) | (1u << (22 - KC_RATIO_BITS));
        double luma = kc_float_at(bits);
        double clamped = luma > peak ? peak : luma;
        double mapped = eetf_ratio[kc_warp1023(clamped, peak)] * clamped;
        table->ratio[i] = luma > 1e-4 ? mapped / luma / KC_REFERENCE_WHITE : 1.0 / KC_REFERENCE_WHITE;
    }
}

/* The kept table of [peak], built on first use. NULL when every slot holds another peak or the
   allocation failed; the caller then builds its own. A kept table is never written again, so it is
   read without the lock. */
static const kc_ratio_table *kc_ratio_kept(double peak) {
    const kc_ratio_table *found = NULL;
    int i;
    pthread_mutex_lock(&kc_ratio_lock);
    for (i = 0; i < KC_PEAK_TABLES; i++) {
        if (kc_ratio_tables[i] == NULL) {
            kc_ratio_table *fresh = av_malloc(sizeof *fresh);
            if (fresh != NULL) {
                kc_ratio_fill(fresh, peak);
                kc_ratio_tables[i] = fresh;
            }
            found = fresh;
            break;
        }
        if (kc_ratio_tables[i]->peak == peak) {
            found = kc_ratio_tables[i];
            break;
        }
    }
    pthread_mutex_unlock(&kc_ratio_lock);
    return found;
}

/* The peak a PQ picture is mapped from, in whole nits: its MaxCLL, else its mastering display's
   peak, each only when it lies between 100 and 10000 nits, else KC_DEFAULT_PEAK. MaxCLL comes first
   because it measures the content, where the mastering peak describes a display. */
static double kc_source_peak(const AVFrame *src) {
    const AVFrameSideData *sd = av_frame_get_side_data(src, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    if (sd && sd->size >= sizeof(AVContentLightMetadata)) {
        unsigned cll = ((const AVContentLightMetadata *)sd->data)->MaxCLL;
        if (cll >= 100 && cll <= 10000) return cll;
    }
    sd = av_frame_get_side_data(src, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    if (sd && sd->size >= sizeof(AVMasteringDisplayMetadata)) {
        const AVMasteringDisplayMetadata *m = (const AVMasteringDisplayMetadata *)sd->data;
        if (m->has_luminance && m->max_luminance.den > 0) {
            double nits = floor(av_q2d(m->max_luminance) + 0.5);
            if (nits >= 100.0 && nits <= 10000.0) return nits;
        }
    }
    return KC_DEFAULT_PEAK;
}

/* Both lookups clamp rather than branch, because a branch per channel on busy video costs more
   than the rest of the pixel. Below the first entry the reference answers what that entry holds,
   and PQ's 10000 nit ceiling sits inside the ratio table, so the clamps change no answer. */
static double kc_ratio_at(const double *ratio, double luma) {
    float v = fminf(fmaxf((float)luma, 0x1p-14f), 0x1.fffffep13f);
    return ratio[(kc_float_bits(v) >> (23 - KC_RATIO_BITS)) - KC_RATIO_BASE];
}

static uint8_t kc_encode_at(double linear) {
    float v = fminf(fmaxf((float)linear, 0x1p-24f), 0x1.fffffep-1f);
    return kc_tone.encode[(kc_float_bits(v) >> (23 - KC_ENCODE_BITS)) - KC_ENCODE_BASE];
}

typedef struct kc_tone_law {
    const float *eotf;
    const double *ratio;
    int hlg, gamut2020;
} kc_tone_law;

/* One pixel of codes to its three SDR bytes: 16-bit codes, or bytes with an 8-bit law. */
#if defined(__GNUC__) || defined(__clang__)
#define KC_INLINE static inline __attribute__((always_inline))
#else
#define KC_INLINE static inline
#endif

/* Inlined into both loops: a call per pixel costs a quarter of the frame. */
KC_INLINE void kc_tone_pixel(const kc_tone_law *law, unsigned cr, unsigned cg, unsigned cb, uint8_t out[3]) {
    double r = law->eotf[cr], g = law->eotf[cg], b = law->eotf[cb], ratio;
    if (law->hlg) {
        /* BT.2100 OOTF at Lw = 1000: display = 1000 * Ys^0.2 * scene. */
        double ys = 0.2627 * r + 0.6780 * g + 0.0593 * b;
        double factor = 1000.0 * kc_tone.hlg_ootf[kc_warp1023(ys, 1.0)];
        r *= factor; g *= factor; b *= factor;
    }
    if (law->gamut2020) {
        double r709 = 1.6605 * r - 0.5876 * g - 0.0728 * b;
        double g709 = -0.1246 * r + 1.1329 * g - 0.0083 * b;
        double b709 = -0.0182 * r - 0.1006 * g + 1.1187 * b;
        r = r709 > 0.0 ? r709 : 0.0;
        g = g709 > 0.0 ? g709 : 0.0;
        b = b709 > 0.0 ? b709 : 0.0;
    }
    ratio = kc_ratio_at(law->ratio, 0.2126 * r + 0.7152 * g + 0.0722 * b);
    out[0] = kc_encode_at(r * ratio);
    out[1] = kc_encode_at(g * ratio);
    out[2] = kc_encode_at(b * ratio);
}

/* Maps a native-endian gbrp16 or gbrap16 picture into the 8-bit layout of [dst], alpha taking
   its top byte. A pixel equal to the one before it takes that pixel's answer, so letterbox bars and
   flat areas cost one comparison. */
static void kc_tone_map_wide(const kc_tone_law *law, const AVFrame *wide, AVFrame *dst, const kc_rgb8 *lay) {
    unsigned last_r = 65536, last_g = 0, last_b = 0;
    uint8_t out[3] = {0, 0, 0};
    int alpha = lay->a >= 0 && wide->data[3] != NULL;
    int x, y;
    for (y = 0; y < dst->height; y++) {
        const uint16_t *pg = (const uint16_t *)(wide->data[0] + (ptrdiff_t)y * wide->linesize[0]);
        const uint16_t *pb = (const uint16_t *)(wide->data[1] + (ptrdiff_t)y * wide->linesize[1]);
        const uint16_t *pr = (const uint16_t *)(wide->data[2] + (ptrdiff_t)y * wide->linesize[2]);
        const uint16_t *pa = alpha ? (const uint16_t *)(wide->data[3] + (ptrdiff_t)y * wide->linesize[3]) : NULL;
        uint8_t *p = dst->data[0] + (ptrdiff_t)y * dst->linesize[0];
        for (x = 0; x < dst->width; x++, p += lay->step) {
            if (pr[x] != last_r || pg[x] != last_g || pb[x] != last_b) {
                last_r = pr[x];
                last_g = pg[x];
                last_b = pb[x];
                kc_tone_pixel(law, last_r, last_g, last_b, out);
            }
            p[lay->r] = out[0];
            p[lay->g] = out[1];
            p[lay->b] = out[2];
            if (lay->a >= 0) p[lay->a] = pa ? (uint8_t)(pa[x] >> 8) : 255;
        }
    }
}

/* Maps an 8-bit picture in place, each byte read as the 16-bit code it stands for, shifted as
   swscale shifts it, through the law's 256-entry tables. A source of 8 bits a component comes here, since the cut to 8 bits loses
   nothing of it, and so does a YCgCo picture, whose matrix runs on bytes. */
static void kc_tone_map_bytes(const kc_tone_law *law, AVFrame *f, const kc_rgb8 *lay) {
    int last_r = -1, last_g = -1, last_b = -1;
    uint8_t out[3] = {0, 0, 0};
    int x, y;
    for (y = 0; y < f->height; y++) {
        uint8_t *p = f->data[0] + (ptrdiff_t)y * f->linesize[0];
        for (x = 0; x < f->width; x++, p += lay->step) {
            if (p[lay->r] != last_r || p[lay->g] != last_g || p[lay->b] != last_b) {
                last_r = p[lay->r];
                last_g = p[lay->g];
                last_b = p[lay->b];
                kc_tone_pixel(law, (unsigned)last_r, (unsigned)last_g, (unsigned)last_b, out);
            }
            p[lay->r] = out[0];
            p[lay->g] = out[1];
            p[lay->b] = out[2];
        }
    }
}

/* A YCgCo-tagged picture with chroma planes to read. The tag on an RGB or grey format changes
 * nothing, so those take the ordinary conversion. */
static int kc_is_ycgco(const AVFrame *src) {
    const AVPixFmtDescriptor *d;
    if (src->colorspace != AVCOL_SPC_YCGCO || src->format == AV_PIX_FMT_NONE) return 0;
    d = av_pix_fmt_desc_get((enum AVPixelFormat)src->format);
    return d && !(d->flags & AV_PIX_FMT_FLAG_RGB) && d->nb_components >= 3;
}

/* A source with more than 8 bits in a component, whose steps an 8-bit cut would merge. */
static int kc_is_deep(const AVFrame *src) {
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)src->format);
    return d && d->comp[0].depth > 8;
}

KC_API AVFrame* ffkmp_frame_convert_display(const AVFrame *src, int dst_fmt) {
    kc_rgb8 lay;
    AVFrame *dst;
    int hdr;
    if (!src || !kc_rgb8_layout(dst_fmt, &lay)) return NULL;
    hdr = src->color_trc == AVCOL_TRC_SMPTE2084 || src->color_trc == AVCOL_TRC_ARIB_STD_B67;
    if (!hdr) return kc_is_ycgco(src) ? kc_convert_ycgco(src, dst_fmt, &lay) : kc_sws_convert(src, dst_fmt, KC_SWS_FAST);
    {
        kc_tone_law law;
        kc_ratio_table *own = NULL;
        const kc_ratio_table *table;
        /* HLG's OOTF already lands at a 1000 nit display, so only PQ reads the content's peak. */
        double peak = src->color_trc == AVCOL_TRC_SMPTE2084 ? kc_source_peak(src) : KC_DEFAULT_PEAK;
        (void)pthread_once(&kc_tone_once, kc_tone_init);
        table = kc_ratio_kept(peak);
        if (table == NULL) {
            own = av_malloc(sizeof *own);
            if (own == NULL) return NULL;
            kc_ratio_fill(own, peak);
            table = own;
        }
        law.hlg = src->color_trc == AVCOL_TRC_ARIB_STD_B67;
        law.gamut2020 = src->color_primaries == AVCOL_PRI_BT2020;
        law.ratio = table->ratio;
        if (kc_is_ycgco(src) || !kc_is_deep(src)) {
            law.eotf = law.hlg ? kc_tone.hlg_eotf8 : kc_tone.pq_eotf8;
            dst = kc_is_ycgco(src) ? kc_convert_ycgco(src, dst_fmt, &lay) : kc_sws_convert(src, dst_fmt, KC_SWS_FAST);
            if (dst) kc_tone_map_bytes(&law, dst, &lay);
        } else {
            /* Planar 16-bit costs about what packed 8-bit does in swscale; packed rgba64 costs
               twice that on a 1080p frame. */
            AVFrame *wide;
            law.eotf = law.hlg ? kc_tone.hlg_eotf : kc_tone.pq_eotf;
            wide = kc_sws_convert(src, lay.a >= 0 ? AV_PIX_FMT_GBRAP16 : AV_PIX_FMT_GBRP16, KC_SWS_FAST);
            dst = NULL;
            if (wide) {
                dst = av_frame_alloc();
                if (dst) {
                    dst->width = src->width;
                    dst->height = src->height;
                    dst->format = dst_fmt;
                    if (av_frame_get_buffer(dst, 0) < 0 || av_frame_copy_props(dst, wide) < 0) {
                        av_frame_free(&dst);
                    } else {
                        kc_tone_map_wide(&law, wide, dst, &lay);
                    }
                }
                av_frame_free(&wide);
            }
        }
        av_free(own);
        if (!dst) return NULL;
        dst->color_trc = AVCOL_TRC_GAMMA22;
        if (law.gamut2020) dst->color_primaries = AVCOL_PRI_BT709;
    }
    return dst;
}
