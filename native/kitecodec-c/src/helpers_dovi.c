/* Dolby Vision: the configuration record of a stream, the RPU a decoder attaches to a frame, and the
 * composition that turns a base layer and its RPU into an HDR10 picture.
 *
 * The composition runs in the reference decoder's order, the one libplacebo runs on the GPU. Each
 * component of the base layer is reshaped by its curve, a polynomial of that component or, for
 * chroma, a multivariate polynomial (MMR) of all three. The RPU's YCC to RGB matrix and offset turn
 * the result into L'M'S', the PQ curve makes it linear, the RPU's RGB to LMS matrix and the
 * Hunt-Pointer-Estevez LMS to RGB matrix give linear BT.2020 RGB, and the PQ curve encodes it
 * again. The picture written is BT.2020 Y'CbCr in limited range, 4:2:0 with chroma sited left.
 *
 * Luma is reshaped per pixel through a table of every code. Chroma is reshaped once per 2x2 block,
 * at the chroma sample's own site: the left column, halfway between the block's two rows, which is
 * where the luma that its MMR reads is taken. */

#include "kitecodec_helpers.h"

#include <math.h>
#include <pthread.h>

#include <libavcodec/avcodec.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>

/* FFmpeg exports the RPU's extension blocks, level 1 among them, from 7.0 (lavu 59.12.100). */
#define KC_DOVI_EXT_BLOCKS (LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(59, 12, 100))

KC_API int ffkmp_codecpar_dovi_config(AVCodecParameters *p, int *out) {
    if (!p || !out) return AVERROR(EINVAL);
    const AVPacketSideData *sd = av_packet_side_data_get(p->coded_side_data, p->nb_coded_side_data,
                                                         AV_PKT_DATA_DOVI_CONF);
    if (!sd || sd->size < offsetof(AVDOVIDecoderConfigurationRecord, dv_bl_signal_compatibility_id) + 1) return 0;
    const AVDOVIDecoderConfigurationRecord *c = (const AVDOVIDecoderConfigurationRecord *)sd->data;
    out[0] = c->dv_version_major;
    out[1] = c->dv_version_minor;
    out[2] = c->dv_profile;
    out[3] = c->dv_level;
    out[4] = c->rpu_present_flag != 0;
    out[5] = c->el_present_flag != 0;
    out[6] = c->bl_present_flag != 0;
    out[7] = c->dv_bl_signal_compatibility_id;
    return 1;
}

static const AVDOVIMetadata *kc_dovi_of_(const AVFrame *f) {
    const AVFrameSideData *sd = av_frame_get_side_data(f, AV_FRAME_DATA_DOVI_METADATA);
    return sd && sd->size >= sizeof(AVDOVIMetadata) ? (const AVDOVIMetadata *)sd->data : NULL;
}

/* A component's inverse quantisation does nothing when it adds no residual: no offset, no slope,
 * no threshold, and a maximum of exactly one. This is libplacebo's test. */
static int kc_dovi_nlq_trivial_(const AVDOVIRpuDataHeader *h, const AVDOVINLQParams *n) {
    return n->nlq_offset == 0 && n->vdr_in_max == (1ULL << h->coef_log2_denom)
        && n->linear_deadzone_slope == 0 && n->linear_deadzone_threshold == 0;
}

static int kc_dovi_uses_residual_(const AVDOVIRpuDataHeader *h, const AVDOVIDataMapping *m) {
    if (h->disable_residual_flag || m->nlq_method_idc != AV_DOVI_NLQ_LINEAR_DZ) return 0;
    return !(kc_dovi_nlq_trivial_(h, &m->nlq[0]) && kc_dovi_nlq_trivial_(h, &m->nlq[1])
             && kc_dovi_nlq_trivial_(h, &m->nlq[2]));
}

KC_API int ffkmp_frame_dovi_metadata(AVFrame *f, int *out) {
    if (!f || !out) return AVERROR(EINVAL);
    const AVDOVIMetadata *dovi = kc_dovi_of_(f);
    if (!dovi) return 0;
    const AVDOVIRpuDataHeader *h = av_dovi_get_header(dovi);
    const AVDOVIColorMetadata *color = av_dovi_get_color(dovi);
    out[0] = h->bl_bit_depth;
    out[1] = kc_dovi_uses_residual_(h, av_dovi_get_mapping(dovi));
    out[2] = color->source_min_pq;
    out[3] = color->source_max_pq;
    out[4] = out[5] = out[6] = out[7] = 0;
#if KC_DOVI_EXT_BLOCKS
    const AVDOVIDmData *l1 = av_dovi_find_level(dovi, 1);
    if (l1) {
        out[4] = 1;
        out[5] = l1->l1.min_pq;
        out[6] = l1->l1.avg_pq;
        out[7] = l1->l1.max_pq;
    }
#endif
    return 1;
}

/* The PQ curve, both ways, in tables filled once per process.
 *
 * The EOTF reads 4096 even steps of the signal, each a value and the slope to the next. The OETF
 * reads linear light by the bits of its float: 32 octaves from 2^-32 up to 1, 64 even steps in each,
 * so a step is never more than 1.6 percent of the light it starts at. Below 2^-32, which is under a
 * millionth of a nit, it answers the first step's value. A composition through these tables lands
 * within one code of the same arithmetic done with pow in double precision. */
#define KC_DOVI_EOTF_STEPS 4096
#define KC_DOVI_OETF_OCTAVES 32
#define KC_DOVI_OETF_BITS 6
#define KC_DOVI_OETF_STEPS (KC_DOVI_OETF_OCTAVES << KC_DOVI_OETF_BITS)

static float kc_dovi_eotf_[KC_DOVI_EOTF_STEPS][2];
static float kc_dovi_oetf_[KC_DOVI_OETF_STEPS][2];
static pthread_once_t kc_dovi_tables_once = PTHREAD_ONCE_INIT;

static double kc_dovi_pq_decode_(double e) {
    if (e <= 0.0) return 0.0;
    double p = pow(e, 1.0 / 78.84375);
    double num = p - 0.8359375;
    return pow((num > 0.0 ? num : 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}

static double kc_dovi_pq_encode_(double y) {
    double p = pow(y > 0.0 ? y : 0.0, 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p), 78.84375);
}

static void kc_dovi_tables_init_(void) {
    for (int i = 0; i < KC_DOVI_EOTF_STEPS; i++) {
        double a = kc_dovi_pq_decode_((double)i / (KC_DOVI_EOTF_STEPS - 1));
        double b = kc_dovi_pq_decode_((double)(i + 1) / (KC_DOVI_EOTF_STEPS - 1));
        kc_dovi_eotf_[i][0] = (float)a;
        kc_dovi_eotf_[i][1] = (float)(b - a);
    }
    /* The octave's scale doubles from 2^-32, which is exact; pow(2, n) here becomes a call to
     * ldexp, a dependency of its own. */
    double octave = 1.0 / 4294967296.0;
    for (int o = 0; o < KC_DOVI_OETF_OCTAVES; o++, octave *= 2.0) {
        for (int step = 0; step < 1 << KC_DOVI_OETF_BITS; step++) {
            double a = kc_dovi_pq_encode_(octave * (1.0 + (double)step / (1 << KC_DOVI_OETF_BITS)));
            double b = kc_dovi_pq_encode_(octave * (1.0 + (double)(step + 1) / (1 << KC_DOVI_OETF_BITS)));
            kc_dovi_oetf_[(o << KC_DOVI_OETF_BITS) + step][0] = (float)a;
            kc_dovi_oetf_[(o << KC_DOVI_OETF_BITS) + step][1] = (float)(b - a);
        }
    }
}

static inline float kc_dovi_clampf_(float v, float lo, float hi) { return fminf(fmaxf(v, lo), hi); }

static inline float kc_dovi_eotf_at_(float e) {
    e = kc_dovi_clampf_(e, 0.0f, 1.0f) * (KC_DOVI_EOTF_STEPS - 1);
    int i = (int)e;
    if (i > KC_DOVI_EOTF_STEPS - 2) i = KC_DOVI_EOTF_STEPS - 2;
    return kc_dovi_eotf_[i][0] + kc_dovi_eotf_[i][1] * (e - (float)i);
}

static inline float kc_dovi_oetf_at_(float y) {
    union { float f; uint32_t u; } bits;
    bits.f = kc_dovi_clampf_(y, 0x1p-32f, 0x1.fffffep-1f);
    int i = (int)(bits.u >> (23 - KC_DOVI_OETF_BITS)) - ((127 - KC_DOVI_OETF_OCTAVES) << KC_DOVI_OETF_BITS);
    float frac = (float)(bits.u & ((1u << (23 - KC_DOVI_OETF_BITS)) - 1)) * (1.0f / (1u << (23 - KC_DOVI_OETF_BITS)));
    return kc_dovi_oetf_[i][0] + kc_dovi_oetf_[i][1] * frac;
}

/* One component's reshaping curve, normalised: pivots in the base layer's 0 to 1, coefficients as
 * reals. A polynomial piece leaves its MMR fields zero, and the other way round. */
typedef struct {
    int num_pivots;
    float pivots[AV_DOVI_MAX_PIECES + 1];
    int mmr[AV_DOVI_MAX_PIECES];
    float poly[AV_DOVI_MAX_PIECES][3];
    int mmr_order[AV_DOVI_MAX_PIECES];
    float mmr_constant[AV_DOVI_MAX_PIECES];
    float mmr_coef[AV_DOVI_MAX_PIECES][3][7];
} kc_dovi_curve;

typedef struct {
    kc_dovi_curve curve[3];
    int luma_mmr;          /* the luma curve has an MMR piece, so luma depends on chroma too */
    float nonlinear[9];    /* reshaped I P T to L'M'S', row-major */
    float offset[3];       /* subtracted from I P T before the matrix */
    float linear[9];       /* linear LMS to linear BT.2020 RGB */
} kc_dovi_params;

/* The BT.2020 LMS to RGB matrix of the Hunt-Pointer-Estevez transform, without crosstalk, which
 * FFmpeg says the RPU's RGB to LMS matrix feeds. */
static const double kc_dovi_hpe_lms_to_rgb_[9] = {
     3.06441879, -2.16597676,  0.10155818,
    -0.65612108,  1.78554118, -0.12943749,
     0.01736321, -0.04725154,  1.03004253,
};

static int kc_dovi_params_of_(const AVDOVIMetadata *dovi, kc_dovi_params *out) {
    const AVDOVIRpuDataHeader *h = av_dovi_get_header(dovi);
    const AVDOVIDataMapping *m = av_dovi_get_mapping(dovi);
    const AVDOVIColorMetadata *color = av_dovi_get_color(dovi);
    if (h->bl_bit_depth < 8 || h->bl_bit_depth > 16 || h->coef_log2_denom > 32) return AVERROR(EINVAL);
    const double pivot_scale = 1.0 / ((1 << h->bl_bit_depth) - 1);
    const double coef_scale = 1.0 / (double)(1ULL << h->coef_log2_denom);
    memset(out, 0, sizeof *out);
    for (int c = 0; c < 3; c++) {
        const AVDOVIReshapingCurve *src = &m->curves[c];
        kc_dovi_curve *dst = &out->curve[c];
        if (src->num_pivots < 2 || src->num_pivots > AV_DOVI_MAX_PIECES + 1) return AVERROR(EINVAL);
        dst->num_pivots = src->num_pivots;
        for (int i = 0; i < src->num_pivots; i++) dst->pivots[i] = (float)(src->pivots[i] * pivot_scale);
        for (int i = 0; i < src->num_pivots - 1; i++) {
            if (src->mapping_idc[i] == AV_DOVI_MAPPING_MMR) {
                if (src->mmr_order[i] < 1 || src->mmr_order[i] > 3) return AVERROR(EINVAL);
                dst->mmr[i] = 1;
                dst->mmr_order[i] = src->mmr_order[i];
                dst->mmr_constant[i] = (float)(src->mmr_constant[i] * coef_scale);
                for (int o = 0; o < src->mmr_order[i]; o++)
                    for (int k = 0; k < 7; k++) dst->mmr_coef[i][o][k] = (float)(src->mmr_coef[i][o][k] * coef_scale);
                if (c == 0) out->luma_mmr = 1;
            } else if (src->mapping_idc[i] == AV_DOVI_MAPPING_POLYNOMIAL) {
                for (int k = 0; k <= src->poly_order[i] && k < 3; k++)
                    dst->poly[i][k] = (float)(src->poly_coef[i][k] * coef_scale);
            } else {
                return AVERROR(EINVAL);
            }
        }
    }
    for (int i = 0; i < 9; i++) {
        if (color->ycc_to_rgb_matrix[i].den == 0 || color->rgb_to_lms_matrix[i].den == 0) return AVERROR(EINVAL);
        out->nonlinear[i] = (float)av_q2d(color->ycc_to_rgb_matrix[i]);
    }
    for (int i = 0; i < 3; i++) {
        if (color->ycc_to_rgb_offset[i].den == 0) return AVERROR(EINVAL);
        out->offset[i] = (float)av_q2d(color->ycc_to_rgb_offset[i]);
    }
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) {
            double sum = 0.0;
            for (int k = 0; k < 3; k++)
                sum += kc_dovi_hpe_lms_to_rgb_[r * 3 + k] * av_q2d(color->rgb_to_lms_matrix[k * 3 + c]);
            out->linear[r * 3 + c] = (float)sum;
        }
    return 0;
}

/* Reshapes component comp of s, the base layer's three components in 0 to 1. */
static float kc_dovi_reshape_(const kc_dovi_curve *c, const float s[3], int comp) {
    const float x = s[comp];
    int i = 0;
    while (i < c->num_pivots - 2 && x >= c->pivots[i + 1]) i++;
    float r;
    if (!c->mmr[i]) {
        r = (c->poly[i][2] * x + c->poly[i][1]) * x + c->poly[i][0];
    } else {
        const float t[7] = { s[0], s[1], s[2], s[0] * s[1], s[0] * s[2], s[1] * s[2], s[0] * s[1] * s[2] };
        float p[7];
        memcpy(p, t, sizeof p);
        r = c->mmr_constant[i];
        for (int o = 0; o < c->mmr_order[i]; o++) {
            for (int k = 0; k < 7; k++) r += c->mmr_coef[i][o][k] * p[k];
            for (int k = 0; k < 7; k++) p[k] *= t[k];
        }
    }
    return kc_dovi_clampf_(r, c->pivots[0], c->pivots[c->num_pivots - 1]);
}

/* What compose can read: three components, 4:2:0, little-endian integer samples of 8 to 16 bits
 * in one or two bytes, luma in plane 0. That is yuv420p at every depth, nv12, nv21 and p010 to
 * p016. Fills the depth and the luma table's size. */
static int kc_dovi_readable_(const AVFrame *f, int *depth) {
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    if (!d || d->nb_components != 3 || d->log2_chroma_w != 1 || d->log2_chroma_h != 1) return 0;
    if (d->flags & (AV_PIX_FMT_FLAG_BE | AV_PIX_FMT_FLAG_HWACCEL | AV_PIX_FMT_FLAG_PAL | AV_PIX_FMT_FLAG_RGB
                    | AV_PIX_FMT_FLAG_FLOAT | AV_PIX_FMT_FLAG_BITSTREAM | AV_PIX_FMT_FLAG_ALPHA)) return 0;
    if (d->comp[0].plane != 0) return 0;
    for (int c = 0; c < 3; c++) {
        if (d->comp[c].depth != d->comp[0].depth) return 0;
        if (d->comp[c].depth + d->comp[c].shift > 16) return 0;
    }
    if (d->comp[0].depth < 8) return 0;
    *depth = d->comp[0].depth;
    return 1;
}

/* Unpacks n samples of component comp of row y into dst, as codes of the frame's depth. */
static void kc_dovi_unpack_(const AVFrame *f, const AVPixFmtDescriptor *d, int comp, int y, int n, uint16_t *dst) {
    const AVComponentDescriptor *c = &d->comp[comp];
    const uint8_t *row = f->data[c->plane] + (ptrdiff_t)y * f->linesize[c->plane] + c->offset;
    const unsigned mask = (1u << c->depth) - 1;
    if (c->depth + c->shift > 8) {
        for (int x = 0; x < n; x++) {
            const uint8_t *p = row + (ptrdiff_t)x * c->step;
            dst[x] = (uint16_t)(((unsigned)p[0] | ((unsigned)p[1] << 8)) >> c->shift & mask);
        }
    } else {
        for (int x = 0; x < n; x++) dst[x] = (uint16_t)((row[(ptrdiff_t)x * c->step] >> c->shift) & mask);
    }
}

static int kc_dovi_blank_(const AVFrame *f) {
    return f->buf[0] == NULL && f->data[0] == NULL && f->hw_frames_ctx == NULL;
}

/* A luminance in candelas per square metre, to a ten-thousandth, as ST 2086 counts the darkest. */
static AVRational kc_dovi_nits_q_(int pq) {
    return av_make_q((int)(kc_dovi_pq_decode_(pq / 4095.0) * 10000.0 * 10000.0 + 0.5), 10000);
}

KC_API int ffkmp_frame_dovi_compose_prepare(AVFrame *src, AVFrame *dst) {
    if (!src || !dst) return AVERROR(EINVAL);
    if (!kc_dovi_blank_(dst)) return AVERROR(EINVAL);
    const AVDOVIMetadata *dovi = kc_dovi_of_(src);
    if (!dovi) return 0;
    if (src->hw_frames_ctx) return AVERROR(EINVAL);
    int depth;
    if (!kc_dovi_readable_(src, &depth)) return AVERROR_PATCHWELCOME;
    if (src->width <= 0 || src->height <= 0) return AVERROR(EINVAL);
    kc_dovi_params params;
    int rc = kc_dovi_params_of_(dovi, &params);
    if (rc < 0) return rc;
    const AVDOVIColorMetadata *color = av_dovi_get_color(dovi);
    const int source_min_pq = color->source_min_pq, source_max_pq = color->source_max_pq;
#if KC_DOVI_EXT_BLOCKS
    const AVDOVIDmData *l6 = av_dovi_find_level(dovi, 6);
    const int max_cll = l6 ? l6->l6.max_cll : 0, max_fall = l6 ? l6->l6.max_fall : 0;
#else
    const int max_cll = 0, max_fall = 0;
#endif

    dst->format = AV_PIX_FMT_YUV420P10LE;
    dst->width = src->width;
    dst->height = src->height;
    rc = av_frame_get_buffer(dst, 0);
    if (rc < 0) goto fail;
    rc = av_frame_copy_props(dst, src);
    if (rc < 0) goto fail;
    av_frame_remove_side_data(dst, AV_FRAME_DATA_DOVI_METADATA);
    av_frame_remove_side_data(dst, AV_FRAME_DATA_DOVI_RPU_BUFFER);
    av_frame_remove_side_data(dst, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    av_frame_remove_side_data(dst, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    dst->color_primaries = AVCOL_PRI_BT2020;
    dst->color_trc = AVCOL_TRC_SMPTE2084;
    dst->colorspace = AVCOL_SPC_BT2020_NCL;
    dst->color_range = AVCOL_RANGE_MPEG;
    dst->chroma_location = AVCHROMA_LOC_LEFT;
    if (source_max_pq > 0) {
        AVMasteringDisplayMetadata *display = av_mastering_display_metadata_create_side_data(dst);
        if (!display) { rc = AVERROR(ENOMEM); goto fail; }
        display->min_luminance = kc_dovi_nits_q_(source_min_pq);
        display->max_luminance = kc_dovi_nits_q_(source_max_pq);
        display->has_luminance = 1;
    }
    if (max_cll > 0) {
        AVContentLightMetadata *light = av_content_light_metadata_create_side_data(dst);
        if (!light) { rc = AVERROR(ENOMEM); goto fail; }
        light->MaxCLL = (unsigned)max_cll;
        light->MaxFALL = (unsigned)max_fall;
    }
    return 1;
fail:
    av_frame_unref(dst);
    return rc;
}

/* The limited-range 10-bit code of a value from lo to hi, rounded. */
static inline uint16_t kc_dovi_code_(float v, float lo, float hi) { return (uint16_t)kc_dovi_clampf_(v + 0.5f, lo, hi); }

/* What a chroma site carries into the pixels around it: its share of L'M'S', which is the second
 * and third columns of the nonlinear matrix times its reshaped P and T, and its base layer chroma,
 * which an MMR luma piece reads. */
#define KC_DOVI_SITE 5

/* Reshapes chroma row cy into out, one KC_DOVI_SITE group per site. The MMR luma of a site is the
 * left column's, averaged over the two rows the chroma sits between. */
static void kc_dovi_chroma_row_(const AVFrame *src, const AVPixFmtDescriptor *d, const kc_dovi_params *params,
                                int cy, float norm, uint16_t *ya, uint16_t *yb, uint16_t *cb, uint16_t *cr,
                                float *out) {
    const int w = src->width, h = src->height, cw = (w + 1) / 2;
    const float *nl = params->nonlinear;
    kc_dovi_unpack_(src, d, 0, 2 * cy, w, ya);
    kc_dovi_unpack_(src, d, 0, 2 * cy + 1 < h ? 2 * cy + 1 : 2 * cy, w, yb);
    kc_dovi_unpack_(src, d, 1, cy, cw, cb);
    kc_dovi_unpack_(src, d, 2, cy, cw, cr);
    for (int ci = 0; ci < cw; ci++) {
        const float site[3] = {
            kc_dovi_clampf_(0.5f * (float)(ya[2 * ci] + yb[2 * ci]) * norm, 0.0f, 1.0f),
            kc_dovi_clampf_((float)cb[ci] * norm, 0.0f, 1.0f),
            kc_dovi_clampf_((float)cr[ci] * norm, 0.0f, 1.0f),
        };
        const float p = kc_dovi_reshape_(&params->curve[1], site, 1) - params->offset[1];
        const float t = kc_dovi_reshape_(&params->curve[2], site, 2) - params->offset[2];
        float *o = out + (size_t)ci * KC_DOVI_SITE;
        o[0] = nl[1] * p + nl[2] * t;
        o[1] = nl[4] * p + nl[5] * t;
        o[2] = nl[7] * p + nl[8] * t;
        o[3] = site[1];
        o[4] = site[2];
    }
}

KC_API int ffkmp_frame_dovi_compose_rows(AVFrame *src, AVFrame *dst, int row_start, int row_end) {
    if (!src || !dst) return AVERROR(EINVAL);
    const AVDOVIMetadata *dovi = kc_dovi_of_(src);
    int depth;
    if (!dovi || src->hw_frames_ctx || !kc_dovi_readable_(src, &depth)) return AVERROR(EINVAL);
    if (dst->format != AV_PIX_FMT_YUV420P10LE || dst->width != src->width || dst->height != src->height
        || !dst->data[0] || !dst->data[1] || !dst->data[2]) return AVERROR(EINVAL);
    const int w = src->width, h = src->height;
    if (row_start < 0 || row_end > h || row_start > row_end || (row_start & 1) || ((row_end & 1) && row_end != h))
        return AVERROR(EINVAL);
    if (row_start == row_end) return 0;
    kc_dovi_params params;
    int rc = kc_dovi_params_of_(dovi, &params);
    if (rc < 0) return AVERROR(EINVAL);
    pthread_once(&kc_dovi_tables_once, kc_dovi_tables_init_);

    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)src->format);
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    const int lut_bits = depth < 12 ? depth : 12;
    const int lut_size = 1 << lut_bits, lut_shift = depth - lut_bits;
    const float norm = 1.0f / (float)((1 << depth) - 1);
    const float *nl = params.nonlinear, *lin = params.linear;

    /* Two luma rows and one row of each chroma, twice: once for the pixels and once for reshaping a
     * chroma row. Three reshaped chroma rows, the one between the two pixel rows and its neighbours
     * above and below, and the two blends of them that the pixel rows read. And the luma table: the
     * first column of the nonlinear matrix times the reshaped I of every code, minus its offset. */
    const size_t samples = 4 * (size_t)w + 2 * (size_t)cw;
    const size_t sites = (size_t)cw * KC_DOVI_SITE;
    uint16_t *scratch = av_malloc(sizeof(uint16_t) * samples);
    float *rows = av_malloc(sizeof(float) * 5 * sites);
    float (*luma)[3] = params.luma_mmr ? NULL : av_malloc(sizeof(float[3]) * (size_t)lut_size);
    if (!scratch || !rows || (!params.luma_mmr && !luma)) {
        av_free(scratch);
        av_free(rows);
        av_free(luma);
        return AVERROR(ENOMEM);
    }
    uint16_t *y0 = scratch, *y1 = y0 + w, *ta = y1 + w, *tb = ta + w, *tcb = tb + w, *tcr = tcb + cw;
    float *above = rows, *middle = above + sites, *below = middle + sites, *blend[2] = { below + sites, below + 2 * sites };
    if (luma) {
        for (int code = 0; code < lut_size; code++) {
            const float s[3] = { (float)(code << lut_shift) * norm, 0.0f, 0.0f };
            const float i = kc_dovi_reshape_(&params.curve[0], s, 0) - params.offset[0];
            for (int r = 0; r < 3; r++) luma[code][r] = nl[r * 3] * i;
        }
    }

    const int cy_first = row_start / 2;
    kc_dovi_chroma_row_(src, d, &params, cy_first > 0 ? cy_first - 1 : 0, norm, ta, tb, tcb, tcr, above);
    kc_dovi_chroma_row_(src, d, &params, cy_first, norm, ta, tb, tcb, tcr, middle);
    for (int by = row_start; by < row_end; by += 2) {
        const int cy = by / 2, pair = by + 1 < h ? 2 : 1;
        kc_dovi_chroma_row_(src, d, &params, cy + 1 < ch ? cy + 1 : cy, norm, ta, tb, tcb, tcr, below);
        /* Chroma sits halfway between the two rows, so each row is a quarter of a row from it and
         * three quarters of a row from the neighbour on its side. */
        for (size_t i = 0; i < sites; i++) {
            blend[0][i] = 0.75f * middle[i] + 0.25f * above[i];
            blend[1][i] = 0.75f * middle[i] + 0.25f * below[i];
        }
        kc_dovi_unpack_(src, d, 0, by, w, y0);
        kc_dovi_unpack_(src, d, 0, by + pair - 1, w, y1);
        uint16_t *out_y[2] = {
            (uint16_t *)(dst->data[0] + (ptrdiff_t)by * dst->linesize[0]),
            (uint16_t *)(dst->data[0] + (ptrdiff_t)(by + pair - 1) * dst->linesize[0]),
        };
        uint16_t *out_cb = (uint16_t *)(dst->data[1] + (ptrdiff_t)cy * dst->linesize[1]);
        uint16_t *out_cr = (uint16_t *)(dst->data[2] + (ptrdiff_t)cy * dst->linesize[2]);
        /* The chroma of the column left of the block, for the left-sited filter. The first block
         * has none, so it mirrors the column on its right. */
        float left_cb = 0.0f, left_cr = 0.0f;
        for (int bx = 0, ci = 0; bx < w; bx += 2, ci++) {
            const int cols = bx + 1 < w ? 2 : 1;
            const int next = ci + 1 < cw ? ci + 1 : ci;
            float col_cb[2] = { 0.0f, 0.0f }, col_cr[2] = { 0.0f, 0.0f };
            for (int dy = 0; dy < 2; dy++) {
                /* The last block of an odd height has one row, which stands in for both. */
                const int row = dy < pair ? dy : 0;
                const uint16_t *codes = row ? y1 : y0;
                const float *own = blend[row] + (size_t)ci * KC_DOVI_SITE, *right = blend[row] + (size_t)next * KC_DOVI_SITE;
                for (int dx = 0; dx < cols; dx++) {
                    /* Chroma is sited on the even column, so the odd one takes half of each side. */
                    float c[KC_DOVI_SITE];
                    for (int k = 0; k < KC_DOVI_SITE; k++) c[k] = dx ? 0.5f * (own[k] + right[k]) : own[k];
                    const uint16_t code = codes[bx + dx];
                    float lms_in[3];
                    if (luma) {
                        const float *l = luma[code >> lut_shift];
                        for (int r = 0; r < 3; r++) lms_in[r] = l[r] + c[r];
                    } else {
                        const float s[3] = { (float)code * norm, c[3], c[4] };
                        const float i = kc_dovi_reshape_(&params.curve[0], s, 0) - params.offset[0];
                        for (int r = 0; r < 3; r++) lms_in[r] = nl[r * 3] * i + c[r];
                    }
                    const float l = kc_dovi_eotf_at_(lms_in[0]), m = kc_dovi_eotf_at_(lms_in[1]), s = kc_dovi_eotf_at_(lms_in[2]);
                    const float red = kc_dovi_oetf_at_(lin[0] * l + lin[1] * m + lin[2] * s);
                    const float green = kc_dovi_oetf_at_(lin[3] * l + lin[4] * m + lin[5] * s);
                    const float blue = kc_dovi_oetf_at_(lin[6] * l + lin[7] * m + lin[8] * s);
                    const float luma_out = 0.2627f * red + 0.6780f * green + 0.0593f * blue;
                    if (dy < pair) out_y[dy][bx + dx] = kc_dovi_code_(64.0f + 876.0f * luma_out, 64.0f, 940.0f);
                    col_cb[dx] += 0.5f * (blue - luma_out) * (1.0f / 1.8814f);
                    col_cr[dx] += 0.5f * (red - luma_out) * (1.0f / 1.4746f);
                }
            }
            if (cols == 1) { col_cb[1] = col_cb[0]; col_cr[1] = col_cr[0]; }
            if (bx == 0) { left_cb = col_cb[1]; left_cr = col_cr[1]; }
            /* Sited on the left column: a quarter of each neighbour and half of its own. */
            out_cb[ci] = kc_dovi_code_(512.0f + 896.0f * (0.25f * left_cb + 0.5f * col_cb[0] + 0.25f * col_cb[1]), 64.0f, 960.0f);
            out_cr[ci] = kc_dovi_code_(512.0f + 896.0f * (0.25f * left_cr + 0.5f * col_cr[0] + 0.25f * col_cr[1]), 64.0f, 960.0f);
            left_cb = col_cb[1];
            left_cr = col_cr[1];
        }
        float *spare = above;
        above = middle;
        middle = below;
        below = spare;
    }
    av_free(scratch);
    av_free(rows);
    av_free(luma);
    return 0;
}
