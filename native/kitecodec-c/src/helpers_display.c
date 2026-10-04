/* The display part of the FFmpeg helper layer: a decoded picture converted to 8-bit RGB for a
 * screen. The conversion is swscale's fast one, KC_SWS_FAST, rather than the exact one
 * ffkmp_frame_convert_pixfmt makes, because a player calls this for every frame it draws, and on
 * x86-64 the exact one takes about 11 ms of a 1080p frame where the fast one takes about 1.5, for
 * a difference of 3 levels at most (#164). Like that one, it changes only the encoding. This file
 * adds the two steps a screen needs on top of it and swscale cannot do in that call:
 *
 *   YCgCo, whose inverse matrix feeds both chroma planes into red and blue. swscale's coefficient
 *   table holds four numbers, one chroma plane per colour, so it cannot express it.
 *   PQ and HLG, which are tone mapped to SDR with the law KitePlayer's software converters use:
 *   linear light per channel, BT.2020 primaries folded to BT.709, luminance rolled off through the
 *   BT.2390 EETF from a 1000 nit peak to 203 nit reference white, then gamma 2.2. The law runs on
 *   the 8-bit RGB bytes, as the Kotlin one does, so the two paths give the same picture. */

#include "kitecodec_helpers.h"
#include "kc_convert.h"

#include <libavutil/frame.h>
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

#define KC_SRC_PEAK 1000.0
#define KC_REFERENCE_WHITE 203.0

/* The reference law samples two curves through square-root-warped tables, which costs four
   square roots and three divisions per pixel. Here both curves are tables indexed by the bits of
   a float, its exponent and top mantissa bits, filled once from the reference tables. A pixel then
   costs table reads and multiplications. On a 1080p frame of random pixels this loop ran six
   times faster than the reference loop, and 0.65 percent of the channels came out one code value
   apart, where a value fell within one table step of a rounding boundary. None differed by more. */

/* The EETF ratio over luminance in nits, 2^-14 to 2^14 (PQ ends at 10000), eight mantissa bits
   per octave. */
#define KC_RATIO_BITS 8
#define KC_RATIO_BASE ((127 - 14) << KC_RATIO_BITS)
#define KC_RATIO_SIZE (28 << KC_RATIO_BITS)

/* The gamma 2.2 encode over SDR linear light, 2^-24 to 1, eleven mantissa bits per octave. */
#define KC_ENCODE_BITS 11
#define KC_ENCODE_BASE ((127 - 24) << KC_ENCODE_BITS)
#define KC_ENCODE_SIZE (24 << KC_ENCODE_BITS)

typedef struct kc_tone_tables {
    double pq_eotf[256];           /* PQ electrical byte to nits */
    double hlg_eotf[256];          /* HLG electrical byte to scene light 0..1, before the OOTF */
    double hlg_ootf[1024];         /* sqrt-warped scene luminance to the HLG OOTF's Ys^0.2 */
    double ratio[KC_RATIO_SIZE];   /* luminance to the factor that maps it to SDR linear light */
    uint8_t encode[KC_ENCODE_SIZE];
    double eetf_ratio[1024];       /* the reference table the ratio table is filled from */
    uint8_t encode_ref[4096];      /* the reference table the encode table is filled from */
} kc_tone_tables;

static kc_tone_tables kc_tone;
static pthread_once_t kc_tone_once = PTHREAD_ONCE_INIT;

static double kc_pq_encode(double y) {
    double p = pow(y > 0.0 ? y : 0.0, 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p), 78.84375);
}

static double kc_pq_decode(double e) {
    double p = pow(e > 0.0 ? e : 0.0, 1.0 / 78.84375);
    double num = p - 0.8359375;
    return pow((num > 0.0 ? num : 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}

/* BT.2390 EETF from [0, KC_SRC_PEAK] into [0, KC_REFERENCE_WHITE] nits, in normalized PQ. */
static double kc_eetf_nits(double nits) {
    double src_pq = kc_pq_encode(KC_SRC_PEAK / 10000.0);
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

/* The reference law's factor from luminance in nits to SDR linear light. */
static double kc_ratio_ref(double luma) {
    double clamped = luma > KC_SRC_PEAK ? KC_SRC_PEAK : luma;
    double mapped = kc_tone.eetf_ratio[kc_warp1023(clamped, KC_SRC_PEAK)] * clamped;
    return luma > 1e-4 ? mapped / luma / KC_REFERENCE_WHITE : 1.0 / KC_REFERENCE_WHITE;
}

static void kc_tone_init(void) {
    kc_tone_tables *t = &kc_tone;
    int i;
    for (i = 0; i < 256; i++) {
        double e = i / 255.0;
        t->pq_eotf[i] = kc_pq_decode(e) * 10000.0;
        t->hlg_eotf[i] = e <= 0.5 ? e * e / 3.0 : (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    }
    for (i = 0; i < 1024; i++) {
        double w = i / 1023.0;
        double nits = w * w * KC_SRC_PEAK;
        t->hlg_ootf[i] = w <= 0.0 ? 0.0 : pow(w * w, 0.2);
        t->eetf_ratio[i] = nits <= 1e-4 ? 1.0 : kc_eetf_nits(nits) / nits;
    }
    for (i = 0; i < 4096; i++) {
        double w = i / 4095.0;
        t->encode_ref[i] = kc_clamp_byte((int)(pow(w * w, 1.0 / 2.2) * 255.0 + 0.5));
    }
    /* Each entry takes the reference value at the middle of the float range it stands for. */
    for (i = 0; i < KC_RATIO_SIZE; i++) {
        uint32_t bits = ((uint32_t)(i + KC_RATIO_BASE) << (23 - KC_RATIO_BITS)) | (1u << (22 - KC_RATIO_BITS));
        t->ratio[i] = kc_ratio_ref(kc_float_at(bits));
    }
    for (i = 0; i < KC_ENCODE_SIZE; i++) {
        uint32_t bits = ((uint32_t)(i + KC_ENCODE_BASE) << (23 - KC_ENCODE_BITS)) | (1u << (22 - KC_ENCODE_BITS));
        t->encode[i] = t->encode_ref[kc_warp4095(kc_float_at(bits))];
    }
}

/* Both lookups clamp rather than branch, because a branch per channel on busy video costs more
   than the rest of the pixel. Below the first entry the reference answers what that entry holds,
   and PQ's 10000 nit ceiling sits inside the ratio table, so the clamps change no answer. */
static double kc_ratio_at(double luma) {
    float v = fminf(fmaxf((float)luma, 0x1p-14f), 0x1.fffffep13f);
    return kc_tone.ratio[(kc_float_bits(v) >> (23 - KC_RATIO_BITS)) - KC_RATIO_BASE];
}

static uint8_t kc_encode_at(double linear) {
    float v = fminf(fmaxf((float)linear, 0x1p-24f), 0x1.fffffep-1f);
    return kc_tone.encode[(kc_float_bits(v) >> (23 - KC_ENCODE_BITS)) - KC_ENCODE_BASE];
}

static void kc_tone_map(int hlg, int gamut2020, AVFrame *f, const kc_rgb8 *lay) {
    const double *eotf = hlg ? kc_tone.hlg_eotf : kc_tone.pq_eotf;
    /* A pixel equal to the one before it takes that pixel's answer, so letterbox bars and flat
       areas cost one comparison. */
    int last_r = -1, last_g = -1, last_b = -1;
    uint8_t out_r = 0, out_g = 0, out_b = 0;
    int x, y;
    for (y = 0; y < f->height; y++) {
        uint8_t *p = f->data[0] + (ptrdiff_t)y * f->linesize[0];
        for (x = 0; x < f->width; x++, p += lay->step) {
            double r, g, b, ratio;
            if (p[lay->r] == last_r && p[lay->g] == last_g && p[lay->b] == last_b) {
                p[lay->r] = out_r;
                p[lay->g] = out_g;
                p[lay->b] = out_b;
                continue;
            }
            last_r = p[lay->r];
            last_g = p[lay->g];
            last_b = p[lay->b];
            r = eotf[last_r];
            g = eotf[last_g];
            b = eotf[last_b];
            if (hlg) {
                /* BT.2100 OOTF at Lw = 1000: display = 1000 * Ys^0.2 * scene. */
                double ys = 0.2627 * r + 0.6780 * g + 0.0593 * b;
                double factor = 1000.0 * kc_tone.hlg_ootf[kc_warp1023(ys, 1.0)];
                r *= factor; g *= factor; b *= factor;
            }
            if (gamut2020) {
                double r709 = 1.6605 * r - 0.5876 * g - 0.0728 * b;
                double g709 = -0.1246 * r + 1.1329 * g - 0.0083 * b;
                double b709 = -0.0182 * r - 0.1006 * g + 1.1187 * b;
                r = r709 > 0.0 ? r709 : 0.0;
                g = g709 > 0.0 ? g709 : 0.0;
                b = b709 > 0.0 ? b709 : 0.0;
            }
            ratio = kc_ratio_at(0.2126 * r + 0.7152 * g + 0.0722 * b);
            out_r = kc_encode_at(r * ratio);
            out_g = kc_encode_at(g * ratio);
            out_b = kc_encode_at(b * ratio);
            p[lay->r] = out_r;
            p[lay->g] = out_g;
            p[lay->b] = out_b;
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

KC_API AVFrame* ffkmp_frame_convert_display(const AVFrame *src, int dst_fmt) {
    kc_rgb8 lay;
    AVFrame *dst;
    if (!src || !kc_rgb8_layout(dst_fmt, &lay)) return NULL;
    dst = kc_is_ycgco(src) ? kc_convert_ycgco(src, dst_fmt, &lay) : kc_sws_convert(src, dst_fmt, KC_SWS_FAST);
    if (!dst) return NULL;
    if (src->color_trc == AVCOL_TRC_SMPTE2084 || src->color_trc == AVCOL_TRC_ARIB_STD_B67) {
        int gamut2020 = src->color_primaries == AVCOL_PRI_BT2020;
        (void)pthread_once(&kc_tone_once, kc_tone_init);
        kc_tone_map(src->color_trc == AVCOL_TRC_ARIB_STD_B67, gamut2020, dst, &lay);
        dst->color_trc = AVCOL_TRC_GAMMA22;
        if (gamut2020) dst->color_primaries = AVCOL_PRI_BT709;
    }
    return dst;
}
