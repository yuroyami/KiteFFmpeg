/* Dolby Vision: the configuration reader, the per-frame reader, and the composer, which is held to
 * a reference written here in double precision with pow, step by step as the reference decoder
 * describes it. The composer reads the PQ curve from tables and must land within one code of it. */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>

#include <libavcodec/avcodec.h>
#include <libavutil/buffer.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/mem.h>
#include <libavutil/pixfmt.h>

#define KC_TEST_EXT_BLOCKS (LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(59, 12, 100))
#define DENOM 23

static int64_t coef(double v) { return (int64_t)llround(v * (double)(1 << DENOM)); }

/* The RPU of the profile 5 fixture: a three-piece polynomial for I, third-order MMR for P and T,
 * the IPT matrix of a real profile 5 stream, and level 1 and level 6 when FFmpeg carries them. When
 * luma_mmr is set, I's middle piece is an MMR piece instead, which no real stream does and the
 * composer still has to get right. */
static AVDOVIMetadata *make_rpu(size_t *size, int luma_mmr, int residual)
{
    static const int pivots_i[4] = { 0, 256, 640, 1023 };
    static const double poly_i[3][3] = { { 0.0, 1.1, -0.2 }, { 0.02, 0.98, -0.05 }, { -0.04, 1.02, 0.02 } };
    static const double mmr_p[3][7] = {
        { 0.02, 0.95, 0.01, -0.03, 0.02, 0.01, -0.01 },
        { 0.01, 0.04, 0.0, 0.0, 0.01, 0.0, 0.0 },
        { 0.0, -0.02, 0.0, 0.0, 0.0, 0.0, 0.0 },
    };
    static const double mmr_t[3][7] = {
        { -0.02, 0.01, 1.02, 0.02, -0.03, 0.0, 0.01 },
        { 0.0, 0.0, -0.03, 0.01, 0.0, 0.0, 0.0 },
        { 0.0, 0.0, 0.02, 0.0, 0.0, 0.0, 0.0 },
    };
    static const int ycc[9] = { 8192, 799, 1681, 8192, -933, 1091, 8192, 267, -5545 };
    static const int lms[9] = { 17081, -349, -349, -349, 17081, -349, -349, -349, 17081 };
    AVDOVIMetadata *dovi = av_dovi_metadata_alloc(size);
    AVDOVIRpuDataHeader *h;
    AVDOVIDataMapping *m;
    AVDOVIColorMetadata *color;
    if (dovi == NULL) return NULL;
    h = av_dovi_get_header(dovi);
    m = av_dovi_get_mapping(dovi);
    color = av_dovi_get_color(dovi);
    h->rpu_type = 2;
    h->rpu_format = 18;
    h->coef_log2_denom = DENOM;
    h->bl_bit_depth = 10;
    h->el_bit_depth = 10;
    h->vdr_bit_depth = 12;
    h->bl_video_full_range_flag = 1;
    h->disable_residual_flag = residual ? 0 : 1;
    m->curves[0].num_pivots = 4;
    for (int i = 0; i < 4; i++) m->curves[0].pivots[i] = (uint16_t)pivots_i[i];
    for (int i = 0; i < 3; i++) {
        m->curves[0].mapping_idc[i] = AV_DOVI_MAPPING_POLYNOMIAL;
        m->curves[0].poly_order[i] = 2;
        for (int k = 0; k < 3; k++) m->curves[0].poly_coef[i][k] = coef(poly_i[i][k]);
    }
    if (luma_mmr) {
        m->curves[0].mapping_idc[1] = AV_DOVI_MAPPING_MMR;
        m->curves[0].mmr_order[1] = 2;
        m->curves[0].mmr_constant[1] = coef(0.03);
        for (int o = 0; o < 2; o++)
            for (int k = 0; k < 7; k++) m->curves[0].mmr_coef[1][o][k] = coef(k == 0 ? 0.9 - 0.2 * o : 0.01 * (k - 3));
    }
    for (int c = 1; c < 3; c++) {
        const double (*mmr)[7] = c == 1 ? mmr_p : mmr_t;
        m->curves[c].num_pivots = 2;
        m->curves[c].pivots[0] = 0;
        m->curves[c].pivots[1] = 1023;
        m->curves[c].mapping_idc[0] = AV_DOVI_MAPPING_MMR;
        m->curves[c].mmr_order[0] = 3;
        m->curves[c].mmr_constant[0] = coef(c == 1 ? 0.01 : -0.01);
        for (int o = 0; o < 3; o++)
            for (int k = 0; k < 7; k++) m->curves[c].mmr_coef[0][o][k] = coef(mmr[o][k]);
    }
    if (residual) {
        m->nlq_method_idc = AV_DOVI_NLQ_LINEAR_DZ;
        for (int c = 0; c < 3; c++) {
            m->nlq[c].nlq_offset = 512;
            m->nlq[c].vdr_in_max = 1ULL << DENOM;
            m->nlq[c].linear_deadzone_slope = 1234;
        }
    } else {
        m->nlq_method_idc = AV_DOVI_NLQ_NONE;
    }
    for (int i = 0; i < 9; i++) {
        color->ycc_to_rgb_matrix[i] = av_make_q(ycc[i], 8192);
        color->rgb_to_lms_matrix[i] = av_make_q(lms[i], 16384);
    }
    color->ycc_to_rgb_offset[0] = av_make_q(0, 1 << 28);
    color->ycc_to_rgb_offset[1] = av_make_q(1 << 27, 1 << 28);
    color->ycc_to_rgb_offset[2] = av_make_q(1 << 27, 1 << 28);
    color->source_min_pq = 7;
    color->source_max_pq = 3079;
#if KC_TEST_EXT_BLOCKS
    dovi->num_ext_blocks = 2;
    av_dovi_get_ext(dovi, 0)->level = 1;
    av_dovi_get_ext(dovi, 0)->l1.min_pq = 12;
    av_dovi_get_ext(dovi, 0)->l1.avg_pq = 819;
    av_dovi_get_ext(dovi, 0)->l1.max_pq = 2081;
    av_dovi_get_ext(dovi, 1)->level = 6;
    av_dovi_get_ext(dovi, 1)->l6.max_luminance = 1000;
    av_dovi_get_ext(dovi, 1)->l6.min_luminance = 1;
    av_dovi_get_ext(dovi, 1)->l6.max_cll = 943;
    av_dovi_get_ext(dovi, 1)->l6.max_fall = 400;
#endif
    return dovi;
}

static void attach_rpu(AVFrame *frame, int luma_mmr, int residual)
{
    size_t size;
    AVDOVIMetadata *dovi = make_rpu(&size, luma_mmr, residual);
    AVBufferRef *buf;
    KC_NOT_NULL(dovi);
    buf = av_buffer_create((uint8_t *)dovi, size, NULL, NULL, 0);
    KC_NOT_NULL(buf);
    KC_NOT_NULL(av_frame_new_side_data_from_buf(frame, AV_FRAME_DATA_DOVI_METADATA, buf));
}

/* Deterministic noise, so a failure reproduces. */
static uint32_t rng_state = 12345u;
static uint32_t next_random(void)
{
    rng_state = rng_state * 1664525u + 1013904223u;
    return rng_state >> 8;
}

/* A w x h base layer in format, filled with codes drawn from the full range, with the RPU. */
static AVFrame *base_layer(enum AVPixelFormat format, int w, int h, int luma_mmr)
{
    AVFrame *frame = av_frame_alloc();
    KC_NOT_NULL(frame);
    frame->format = format;
    frame->width = w;
    frame->height = h;
    frame->pts = 4242;
    KC_EQ_INT(av_frame_get_buffer(frame, 0), 0);
    rng_state = 12345u;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            unsigned code = next_random() & 1023;
            if (format == AV_PIX_FMT_YUV420P) {
                frame->data[0][y * frame->linesize[0] + x] = (uint8_t)(code >> 2);
            } else {
                uint16_t *row = (uint16_t *)(frame->data[0] + y * frame->linesize[0]);
                row[x] = (uint16_t)(format == AV_PIX_FMT_P010LE ? code << 6 : code);
            }
        }
    }
    for (int y = 0; y < (h + 1) / 2; y++) {
        for (int x = 0; x < (w + 1) / 2; x++) {
            unsigned u = 256 + (next_random() & 511), v = 256 + (next_random() & 511);
            if (format == AV_PIX_FMT_YUV420P) {
                frame->data[1][y * frame->linesize[1] + x] = (uint8_t)(u >> 2);
                frame->data[2][y * frame->linesize[2] + x] = (uint8_t)(v >> 2);
            } else if (format == AV_PIX_FMT_P010LE) {
                uint16_t *row = (uint16_t *)(frame->data[1] + y * frame->linesize[1]);
                row[2 * x] = (uint16_t)(u << 6);
                row[2 * x + 1] = (uint16_t)(v << 6);
            } else {
                ((uint16_t *)(frame->data[1] + y * frame->linesize[1]))[x] = (uint16_t)u;
                ((uint16_t *)(frame->data[2] + y * frame->linesize[2]))[x] = (uint16_t)v;
            }
        }
    }
    attach_rpu(frame, luma_mmr, 0);
    return frame;
}

/* ---- The reference, in double precision. ---- */

static double pq_decode(double e)
{
    double p, num;
    if (e <= 0.0) return 0.0;
    if (e > 1.0) e = 1.0;
    p = pow(e, 1.0 / 78.84375);
    num = p - 0.8359375;
    return pow((num > 0.0 ? num : 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}

static double pq_encode(double y)
{
    double p = pow(y > 0.0 ? (y < 1.0 ? y : 1.0) : 0.0, 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p), 78.84375);
}

static double reference_reshape(const AVDOVIMetadata *dovi, int comp, const double s[3])
{
    const AVDOVIRpuDataHeader *h = av_dovi_get_header(dovi);
    const AVDOVIReshapingCurve *c = &av_dovi_get_mapping(dovi)->curves[comp];
    const double pivot = 1.0 / ((1 << h->bl_bit_depth) - 1), scale = 1.0 / (double)(1 << h->coef_log2_denom);
    double x = s[comp], r;
    int i = 0;
    while (i < c->num_pivots - 2 && x >= c->pivots[i + 1] * pivot) i++;
    if (c->mapping_idc[i] == AV_DOVI_MAPPING_POLYNOMIAL) {
        r = 0.0;
        for (int k = c->poly_order[i]; k >= 0; k--) r = r * x + c->poly_coef[i][k] * scale;
    } else {
        const double t[7] = { s[0], s[1], s[2], s[0] * s[1], s[0] * s[2], s[1] * s[2], s[0] * s[1] * s[2] };
        r = c->mmr_constant[i] * scale;
        for (int o = 0; o < c->mmr_order[i]; o++)
            for (int k = 0; k < 7; k++) r += c->mmr_coef[i][o][k] * scale * pow(t[k], o + 1);
    }
    if (r < c->pivots[0] * pivot) r = c->pivots[0] * pivot;
    if (r > c->pivots[c->num_pivots - 1] * pivot) r = c->pivots[c->num_pivots - 1] * pivot;
    return r;
}

static double sample_at(const AVFrame *src, int comp, int x, int y);

/* What one chroma site carries: its reshaped P and T, minus their offsets, and its base layer
 * chroma, which an MMR luma piece reads. */
typedef struct { double p, t, cb, cr; } site_value;

static site_value reference_site(const AVFrame *src, const AVDOVIMetadata *dovi, int ci, int cy)
{
    const AVDOVIColorMetadata *color = av_dovi_get_color(dovi);
    const int h = src->height;
    double luma = 0.5 * (sample_at(src, 0, 2 * ci, 2 * cy) + sample_at(src, 0, 2 * ci, 2 * cy + 1 < h ? 2 * cy + 1 : 2 * cy));
    double site[3] = { luma > 1.0 ? 1.0 : luma, sample_at(src, 1, ci, cy), sample_at(src, 2, ci, cy) };
    site_value v;
    v.p = reference_reshape(dovi, 1, site) - av_q2d(color->ycc_to_rgb_offset[1]);
    v.t = reference_reshape(dovi, 2, site) - av_q2d(color->ycc_to_rgb_offset[2]);
    v.cb = site[1];
    v.cr = site[2];
    return v;
}

static site_value mix(site_value a, double wa, site_value b, double wb)
{
    site_value v = { a.p * wa + b.p * wb, a.t * wa + b.t * wb, a.cb * wa + b.cb * wb, a.cr * wa + b.cr * wb };
    return v;
}

/* The chroma at pixel (x, y): chroma is sited on the even column, halfway between the two rows of
 * its block, so a row takes three quarters of its own site and a quarter of the neighbour on its
 * side, and an odd column takes half of each site beside it. Edges repeat the last site. */
static site_value reference_chroma(const AVFrame *src, const AVDOVIMetadata *dovi, int x, int y)
{
    const int cw = (src->width + 1) / 2, ch = (src->height + 1) / 2;
    const int cy = y / 2, other = (y & 1) ? (cy + 1 < ch ? cy + 1 : cy) : (cy > 0 ? cy - 1 : 0);
    const int left = x / 2, right = (x & 1) ? (left + 1 < cw ? left + 1 : left) : left;
    site_value l = mix(reference_site(src, dovi, left, cy), 0.75, reference_site(src, dovi, left, other), 0.25);
    site_value r = mix(reference_site(src, dovi, right, cy), 0.75, reference_site(src, dovi, right, other), 0.25);
    return mix(l, 0.5, r, 0.5);
}

/* BT.2020 R'G'B' (PQ) of pixel (x, y). */
static void reference_pixel(const AVFrame *src, const AVDOVIMetadata *dovi, int x, int y, double rgb[3])
{
    static const double hpe[9] = {
        3.06441879, -2.16597676, 0.10155818,
        -0.65612108, 1.78554118, -0.12943749,
        0.01736321, -0.04725154, 1.03004253,
    };
    const AVDOVIColorMetadata *color = av_dovi_get_color(dovi);
    const site_value c = reference_chroma(src, dovi, x, y);
    const double pixel[3] = { sample_at(src, 0, x, y), c.cb, c.cr };
    const double ipt[3] = { reference_reshape(dovi, 0, pixel) - av_q2d(color->ycc_to_rgb_offset[0]), c.p, c.t };
    double lms[3], mixed[3];
    for (int r = 0; r < 3; r++) {
        double e = 0.0;
        for (int k = 0; k < 3; k++) e += av_q2d(color->ycc_to_rgb_matrix[r * 3 + k]) * ipt[k];
        lms[r] = pq_decode(e);
    }
    for (int r = 0; r < 3; r++) {
        mixed[r] = 0.0;
        for (int k = 0; k < 3; k++) mixed[r] += av_q2d(color->rgb_to_lms_matrix[r * 3 + k]) * lms[k];
    }
    for (int r = 0; r < 3; r++) {
        double v = 0.0;
        for (int k = 0; k < 3; k++) v += hpe[r * 3 + k] * mixed[k];
        rgb[r] = pq_encode(v);
    }
}

static int code_of(double v, int lo, int hi)
{
    long c = lround(v);
    return (int)(c < lo ? lo : c > hi ? hi : c);
}

/* Component comp of src at (x, y) of its plane, in 0 to 1 at the frame's own depth, for the three
 * layouts the cases use. */
static double sample_at(const AVFrame *src, int comp, int x, int y)
{
    switch (src->format) {
    case AV_PIX_FMT_YUV420P:
        return src->data[comp][y * src->linesize[comp] + x] / 255.0;
    case AV_PIX_FMT_P010LE:
        if (comp == 0) return (((const uint16_t *)(src->data[0] + y * src->linesize[0]))[x] >> 6) / 1023.0;
        return (((const uint16_t *)(src->data[1] + y * src->linesize[1]))[2 * x + comp - 1] >> 6) / 1023.0;
    default:
        return ((const uint16_t *)(src->data[comp] + y * src->linesize[comp]))[x] / 1023.0;
    }
}

/* The reference picture as 10-bit planes: luma from each pixel, and chroma sited on the left column,
 * a quarter of each neighbouring column and half of its own, each column averaged over its block's
 * two rows. */
static void reference_compose(const AVFrame *src, uint16_t *out_y, uint16_t *out_cb, uint16_t *out_cr)
{
    const AVDOVIMetadata *dovi = (const AVDOVIMetadata *)av_frame_get_side_data(src, AV_FRAME_DATA_DOVI_METADATA)->data;
    const int w = src->width, h = src->height, cw = (w + 1) / 2;
    for (int by = 0; by < h; by += 2) {
        int rows = by + 1 < h ? 2 : 1;
        double left_cb = 0.0, left_cr = 0.0;
        for (int bx = 0; bx < w; bx += 2) {
            int cols = bx + 1 < w ? 2 : 1, ci = bx / 2;
            double col_cb[2] = { 0, 0 }, col_cr[2] = { 0, 0 };
            for (int dx = 0; dx < cols; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    int row = by + (dy < rows ? dy : 0);
                    double rgb[3], luma;
                    reference_pixel(src, dovi, bx + dx, row, rgb);
                    luma = 0.2627 * rgb[0] + 0.6780 * rgb[1] + 0.0593 * rgb[2];
                    if (dy < rows) out_y[(by + dy) * w + bx + dx] = (uint16_t)code_of(64.0 + 876.0 * luma, 64, 940);
                    col_cb[dx] += 0.5 * (rgb[2] - luma) / 1.8814;
                    col_cr[dx] += 0.5 * (rgb[0] - luma) / 1.4746;
                }
            }
            if (cols == 1) { col_cb[1] = col_cb[0]; col_cr[1] = col_cr[0]; }
            if (bx == 0) { left_cb = col_cb[1]; left_cr = col_cr[1]; }
            out_cb[(by / 2) * cw + ci] = (uint16_t)code_of(512.0 + 896.0 * (0.25 * left_cb + 0.5 * col_cb[0] + 0.25 * col_cb[1]), 64, 960);
            out_cr[(by / 2) * cw + ci] = (uint16_t)code_of(512.0 + 896.0 * (0.25 * left_cr + 0.5 * col_cr[0] + 0.25 * col_cr[1]), 64, 960);
            left_cb = col_cb[1];
            left_cr = col_cr[1];
        }
    }
}

/* The largest difference between dst's planes and the reference, and how many samples differ. */
static int worst_difference(const AVFrame *dst, const AVFrame *src, int *differing)
{
    const int w = dst->width, h = dst->height, cw = (w + 1) / 2, ch = (h + 1) / 2;
    uint16_t *ry = malloc(sizeof(uint16_t) * (size_t)(w * h));
    uint16_t *rcb = malloc(sizeof(uint16_t) * (size_t)(cw * ch));
    uint16_t *rcr = malloc(sizeof(uint16_t) * (size_t)(cw * ch));
    int worst = 0;
    KC_NOT_NULL(ry);
    KC_NOT_NULL(rcb);
    KC_NOT_NULL(rcr);
    reference_compose(src, ry, rcb, rcr);
    *differing = 0;
    for (int p = 0; p < 3; p++) {
        int pw = p ? cw : w, ph = p ? ch : h;
        const uint16_t *ref = p == 0 ? ry : p == 1 ? rcb : rcr;
        for (int y = 0; y < ph; y++)
            for (int x = 0; x < pw; x++) {
                int got = ((const uint16_t *)(dst->data[p] + y * dst->linesize[p]))[x];
                int d = abs(got - ref[y * pw + x]);
                if (d > worst) worst = d;
                if (d) (*differing)++;
            }
    }
    free(ry);
    free(rcb);
    free(rcr);
    return worst;
}

static AVFrame *compose_whole(AVFrame *src)
{
    AVFrame *dst = av_frame_alloc();
    KC_NOT_NULL(dst);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, dst), 1);
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, dst, 0, src->height), 0);
    return dst;
}

/* ---- Cases ---- */

static void case_config(void)
{
    AVCodecParameters *par = avcodec_parameters_alloc();
    size_t size;
    AVDOVIDecoderConfigurationRecord *record = av_dovi_alloc(&size);
    int out[8] = { -1, -1, -1, -1, -1, -1, -1, -1 };
    kc_case("a stream's configuration record reads as eight ints, and its absence as 0");
    KC_NOT_NULL(par);
    KC_NOT_NULL(record);
    KC_EQ_INT(ffkmp_codecpar_dovi_config(par, out), 0);
    record->dv_version_major = 1;
    record->dv_profile = 5;
    record->dv_level = 6;
    record->rpu_present_flag = 1;
    record->bl_present_flag = 1;
    record->dv_bl_signal_compatibility_id = 0;
    KC_NOT_NULL(av_packet_side_data_add(&par->coded_side_data, &par->nb_coded_side_data,
                                        AV_PKT_DATA_DOVI_CONF, record, size, 0));
    KC_EQ_INT(ffkmp_codecpar_dovi_config(par, out), 1);
    KC_EQ_INT(out[0], 1);
    KC_EQ_INT(out[1], 0);
    KC_EQ_INT(out[2], 5);
    KC_EQ_INT(out[3], 6);
    KC_EQ_INT(out[4], 1);
    KC_EQ_INT(out[5], 0);
    KC_EQ_INT(out[6], 1);
    KC_EQ_INT(out[7], 0);
    KC_EQ_INT(ffkmp_codecpar_dovi_config(NULL, out), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecpar_dovi_config(par, NULL), AVERROR(EINVAL));
    avcodec_parameters_free(&par);
}

static void case_metadata(void)
{
    AVFrame *frame = av_frame_alloc(), *bare = av_frame_alloc();
    int out[8];
    kc_case("a frame's RPU reads as eight ints, a residual counts, and a bare frame answers 0");
    KC_NOT_NULL(frame);
    KC_NOT_NULL(bare);
    attach_rpu(frame, 0, 0);
    KC_EQ_INT(ffkmp_frame_dovi_metadata(frame, out), 1);
    KC_EQ_INT(out[0], 10);
    KC_EQ_INT(out[1], 0);
    KC_EQ_INT(out[2], 7);
    KC_EQ_INT(out[3], 3079);
#if KC_TEST_EXT_BLOCKS
    KC_EQ_INT(out[4], 1);
    KC_EQ_INT(out[5], 12);
    KC_EQ_INT(out[6], 819);
    KC_EQ_INT(out[7], 2081);
    kc_detail("level 1 read");
#else
    KC_EQ_INT(out[4], 0);
    kc_detail("FFmpeg before 7.0 exports no level 1");
#endif
    av_frame_remove_side_data(frame, AV_FRAME_DATA_DOVI_METADATA);
    attach_rpu(frame, 0, 1);
    KC_EQ_INT(ffkmp_frame_dovi_metadata(frame, out), 1);
    KC_EQ_INT(out[1], 1);
    KC_EQ_INT(ffkmp_frame_dovi_metadata(bare, out), 0);
    KC_EQ_INT(ffkmp_frame_dovi_metadata(NULL, out), AVERROR(EINVAL));
    av_frame_free(&frame);
    av_frame_free(&bare);
}

static void case_prepare_refusals(void)
{
    AVFrame *src = base_layer(AV_PIX_FMT_YUV420P10LE, 8, 8, 0), *dst = av_frame_alloc(), *bare = av_frame_alloc();
    AVFrame *rgb = av_frame_alloc();
    kc_case("prepare answers 0 without an RPU and refuses hardware, a used dst and a picture not 4:2:0");
    KC_NOT_NULL(dst);
    KC_NOT_NULL(bare);
    KC_NOT_NULL(rgb);
    bare->format = AV_PIX_FMT_YUV420P10LE;
    bare->width = bare->height = 8;
    KC_EQ_INT(av_frame_get_buffer(bare, 0), 0);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(bare, dst), 0);
    KC_NULL(dst->data[0]);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(NULL, dst), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, bare), AVERROR(EINVAL));

    rgb->format = AV_PIX_FMT_RGB48LE;
    rgb->width = rgb->height = 8;
    KC_EQ_INT(av_frame_get_buffer(rgb, 0), 0);
    attach_rpu(rgb, 0, 0);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(rgb, dst), AVERROR_PATCHWELCOME);
    KC_NULL(dst->data[0]);

    /* Only the presence of a frames context is read, so any buffer stands in for one. */
    src->hw_frames_ctx = av_buffer_alloc(1);
    KC_NOT_NULL(src->hw_frames_ctx);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, dst), AVERROR(EINVAL));
    KC_NULL(dst->data[0]);
    av_buffer_unref(&src->hw_frames_ctx);
    av_frame_free(&src);
    av_frame_free(&dst);
    av_frame_free(&bare);
    av_frame_free(&rgb);
}

static void case_prepare_picture(void)
{
    AVFrame *src = base_layer(AV_PIX_FMT_YUV420P10LE, 10, 6, 0), *dst = av_frame_alloc();
    const AVFrameSideData *sd;
    const AVMasteringDisplayMetadata *display;
    kc_case("the composed picture is 10-bit 4:2:0 HDR10 with the source range and no Dolby Vision");
    KC_NOT_NULL(dst);
    src->color_range = AVCOL_RANGE_JPEG;
    KC_NOT_NULL(av_frame_new_side_data(src, AV_FRAME_DATA_DOVI_RPU_BUFFER, 4));
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, dst), 1);
    KC_EQ_INT(dst->format, AV_PIX_FMT_YUV420P10LE);
    KC_EQ_INT(dst->width, 10);
    KC_EQ_INT(dst->height, 6);
    KC_EQ_I64(dst->pts, 4242);
    KC_EQ_INT(dst->color_primaries, AVCOL_PRI_BT2020);
    KC_EQ_INT(dst->color_trc, AVCOL_TRC_SMPTE2084);
    KC_EQ_INT(dst->colorspace, AVCOL_SPC_BT2020_NCL);
    KC_EQ_INT(dst->color_range, AVCOL_RANGE_MPEG);
    KC_EQ_INT(dst->chroma_location, AVCHROMA_LOC_LEFT);
    KC_NULL(av_frame_get_side_data(dst, AV_FRAME_DATA_DOVI_METADATA));
    KC_NULL(av_frame_get_side_data(dst, AV_FRAME_DATA_DOVI_RPU_BUFFER));
    sd = av_frame_get_side_data(dst, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    KC_NOT_NULL(sd);
    display = (const AVMasteringDisplayMetadata *)sd->data;
    KC_EQ_INT(display->has_luminance, 1);
    KC_EQ_INT(display->has_primaries, 0);
    /* PQ code 3079 of 4095 is 1000 nits to within a nit, and 7 is about 0.0002. */
    KC_CHECK(fabs(av_q2d(display->max_luminance) - 1000.0) < 1.5);
    KC_CHECK(av_q2d(display->min_luminance) < 0.001);
    kc_detail("max %.2f min %.5f nits", av_q2d(display->max_luminance), av_q2d(display->min_luminance));
#if KC_TEST_EXT_BLOCKS
    sd = av_frame_get_side_data(dst, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    KC_NOT_NULL(sd);
    KC_EQ_INT((int)((const AVContentLightMetadata *)sd->data)->MaxCLL, 943);
    KC_EQ_INT((int)((const AVContentLightMetadata *)sd->data)->MaxFALL, 400);
#else
    KC_NULL(av_frame_get_side_data(dst, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL));
#endif
    av_frame_free(&src);
    av_frame_free(&dst);
}

static void case_matches_reference(enum AVPixelFormat format, const char *name, int luma_mmr)
{
    AVFrame *src, *dst;
    int differing, worst;
    kc_case("%s%s at 37x23 lands within one code of the double-precision reference", name,
            luma_mmr ? " with an MMR luma piece" : "");
    src = base_layer(format, 37, 23, luma_mmr);
    dst = compose_whole(src);
    worst = worst_difference(dst, src, &differing);
    kc_detail("worst %d, %d samples differ", worst, differing);
    KC_CHECKF(worst <= 1, "worst difference %d codes", worst);
    av_frame_free(&src);
    av_frame_free(&dst);
}

static void case_bands(void)
{
    AVFrame *src = base_layer(AV_PIX_FMT_YUV420P10LE, 21, 15, 0), *whole = compose_whole(src), *banded = av_frame_alloc();
    kc_case("bands compose the same picture as one call, and an odd or outside band is refused");
    KC_NOT_NULL(banded);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, banded), 1);
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 1, 4), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 0, 3), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 14, 16), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, -2, 2), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 6, 4), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 4, 4), 0);
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 8, 15), 0);
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 0, 2), 0);
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 2, 8), 0);
    for (int p = 0; p < 3; p++) {
        int pw = p ? 11 : 21, ph = p ? 8 : 15;
        for (int y = 0; y < ph; y++)
            KC_EQ_INT(memcmp(whole->data[p] + y * whole->linesize[p], banded->data[p] + y * banded->linesize[p],
                             (size_t)pw * 2), 0);
    }
    /* A dst made for another size, or a src without its RPU, is refused. */
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 0, 2), 0);
    av_frame_remove_side_data(src, AV_FRAME_DATA_DOVI_METADATA);
    KC_EQ_INT(ffkmp_frame_dovi_compose_rows(src, banded, 0, 2), AVERROR(EINVAL));
    av_frame_free(&src);
    av_frame_free(&whole);
    av_frame_free(&banded);
}

typedef struct {
    AVFrame *src, *dst;
    int start, end, rc;
} band_job;

static void *run_band(void *arg)
{
    band_job *job = arg;
    job->rc = ffkmp_frame_dovi_compose_rows(job->src, job->dst, job->start, job->end);
    return NULL;
}

static void case_threaded_bands(void)
{
    AVFrame *src = base_layer(AV_PIX_FMT_YUV420P10LE, 64, 40, 0), *whole = compose_whole(src), *banded = av_frame_alloc();
    pthread_t threads[4];
    band_job jobs[4];
    kc_case("four bands on four threads at once compose the same picture as one call");
    KC_NOT_NULL(banded);
    KC_EQ_INT(ffkmp_frame_dovi_compose_prepare(src, banded), 1);
    for (int i = 0; i < 4; i++) {
        jobs[i] = (band_job){ src, banded, i * 10, i * 10 + 10, -1 };
        KC_EQ_INT(pthread_create(&threads[i], NULL, run_band, &jobs[i]), 0);
    }
    for (int i = 0; i < 4; i++) {
        KC_EQ_INT(pthread_join(threads[i], NULL), 0);
        KC_EQ_INT(jobs[i].rc, 0);
    }
    for (int p = 0; p < 3; p++) {
        int pw = p ? 32 : 64, ph = p ? 20 : 40;
        for (int y = 0; y < ph; y++)
            KC_EQ_INT(memcmp(whole->data[p] + y * whole->linesize[p], banded->data[p] + y * banded->linesize[p],
                             (size_t)pw * 2), 0);
    }
    av_frame_free(&src);
    av_frame_free(&whole);
    av_frame_free(&banded);
}

int main(void)
{
    kc_suite_begin("test_dovi");

    case_config();
    case_metadata();
    case_prepare_refusals();
    case_prepare_picture();
    case_matches_reference(AV_PIX_FMT_YUV420P10LE, "yuv420p10le", 0);
    case_matches_reference(AV_PIX_FMT_YUV420P10LE, "yuv420p10le", 1);
    case_matches_reference(AV_PIX_FMT_P010LE, "p010le", 0);
    case_matches_reference(AV_PIX_FMT_YUV420P, "yuv420p", 0);
    case_bands();
    case_threaded_bands();

    return kc_suite_end();
}
