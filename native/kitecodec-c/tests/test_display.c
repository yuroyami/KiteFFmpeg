/* ffkmp_frame_convert_display, the conversion for a picture on a screen, and the FCC row of the
 * matrix table every conversion shares.
 *
 * The tone map is held to an oracle that is not this code: the expected bytes below are what
 * KitePlayer's HdrToneMap.mapInPlace wrote for the same inputs on the JVM, recorded for this file.
 * A source frame in rgba converts to rgba as a plain copy, so those cases measure the tone map
 * alone. The helper's tables can move a channel by one code value next to a rounding boundary,
 * and none of these pixels is next to one, so the bytes are compared exactly. The YCgCo and FCC cases compute their expected colour here in floating point from the
 * ITU-T H.273 definitions, with a tolerance of 1 for fixed-point rounding.
 */

#include "harness.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "kitecodec_helpers.h"

#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>

/* ---- Fixtures ---- */

/* One row of rgba pixels with the given colour tags, alpha 77 so a changed alpha shows. */
static AVFrame *rgba_row(const int (*px)[6], int count, int trc, int primaries)
{
    AVFrame *f = av_frame_alloc();
    int i;
    if (f == NULL) return NULL;
    f->width = count;
    f->height = 1;
    f->format = AV_PIX_FMT_RGBA;
    if (av_frame_get_buffer(f, 0) < 0) {
        av_frame_free(&f);
        return NULL;
    }
    for (i = 0; i < count; i++) {
        f->data[0][i * 4 + 0] = (uint8_t)px[i][0];
        f->data[0][i * 4 + 1] = (uint8_t)px[i][1];
        f->data[0][i * 4 + 2] = (uint8_t)px[i][2];
        f->data[0][i * 4 + 3] = 77;
    }
    f->color_trc = trc;
    f->color_primaries = primaries;
    f->colorspace = AVCOL_SPC_RGB;
    f->color_range = AVCOL_RANGE_JPEG;
    return f;
}

/* A flat YUV frame of one luma and one chroma pair, in yuv444p or yuv420p. */
static AVFrame *flat_yuv(int format, int width, int height, int y, int u, int v, int colorspace, int range)
{
    AVFrame *f = av_frame_alloc();
    int row, chroma_w, chroma_h;
    if (f == NULL) return NULL;
    f->width = width;
    f->height = height;
    f->format = format;
    if (av_frame_get_buffer(f, 0) < 0) {
        av_frame_free(&f);
        return NULL;
    }
    chroma_w = format == AV_PIX_FMT_YUV420P ? (width + 1) / 2 : width;
    chroma_h = format == AV_PIX_FMT_YUV420P ? (height + 1) / 2 : height;
    for (row = 0; row < height; row++)
        memset(f->data[0] + (ptrdiff_t)row * f->linesize[0], y, (size_t)width);
    for (row = 0; row < chroma_h; row++) {
        memset(f->data[1] + (ptrdiff_t)row * f->linesize[1], u, (size_t)chroma_w);
        memset(f->data[2] + (ptrdiff_t)row * f->linesize[2], v, (size_t)chroma_w);
    }
    f->colorspace = colorspace;
    f->color_range = range;
    f->color_trc = AVCOL_TRC_BT709;
    f->color_primaries = AVCOL_PRI_BT709;
    return f;
}

/* Every pixel different from its neighbour, the worst case for the repeated-pixel shortcut. */
static void fill_varied(AVFrame *f)
{
    int x, y;
    for (y = 0; y < f->height; y++)
        for (x = 0; x < f->width; x++)
            f->data[0][(ptrdiff_t)y * f->linesize[0] + x] = (uint8_t)(16 + (x * 7 + y * 3) % 219);
    for (y = 0; y < (f->height + 1) / 2; y++)
        for (x = 0; x < (f->width + 1) / 2; x++) {
            f->data[1][(ptrdiff_t)y * f->linesize[1] + x] = (uint8_t)(16 + (x * 5 + y) % 224);
            f->data[2][(ptrdiff_t)y * f->linesize[2] + x] = (uint8_t)(16 + (x + y * 5) % 224);
        }
}

static void expect_near(int actual, double expected, const char *what)
{
    if (fabs(actual - expected) > 1.0)
        KC_FAIL("%s: actual %d, expected %.2f within 1", what, actual, expected);
}

/* ---- The tone map against KitePlayer's HdrToneMap ---- */

/* Input red, green, blue, then the bytes HdrToneMap.mapInPlace wrote. */
static const int PQ_2020[][6] = {
    {0, 0, 0, 0, 0, 0},           {64, 64, 64, 48, 48, 48},    {128, 128, 128, 180, 180, 180},
    {180, 180, 180, 255, 255, 254}, {230, 230, 230, 255, 255, 255}, {255, 255, 255, 255, 255, 255},
    {200, 100, 50, 255, 0, 0},    {40, 160, 220, 0, 218, 255}, {255, 0, 0, 255, 0, 0},
    {0, 255, 0, 0, 255, 0},       {0, 0, 255, 0, 0, 255},      {150, 200, 90, 0, 255, 0},
};
static const int PQ_709[][6] = {
    {0, 0, 0, 0, 0, 0},           {64, 64, 64, 48, 48, 48},     {128, 128, 128, 180, 180, 180},
    {180, 180, 180, 255, 255, 255}, {230, 230, 230, 255, 255, 255}, {255, 255, 255, 255, 255, 255},
    {200, 100, 50, 255, 84, 26},  {40, 160, 220, 17, 219, 255}, {255, 0, 0, 255, 0, 0},
    {0, 255, 0, 0, 255, 0},       {0, 0, 255, 0, 0, 255},       {150, 200, 90, 127, 255, 42},
};
static const int HLG_2020[][6] = {
    {0, 0, 0, 0, 0, 0},           {64, 64, 64, 64, 64, 64},     {128, 128, 128, 136, 136, 136},
    {180, 180, 180, 214, 214, 214}, {230, 230, 230, 253, 253, 253}, {255, 255, 255, 255, 255, 255},
    {200, 100, 50, 255, 71, 33},  {40, 160, 220, 0, 189, 255},  {255, 0, 0, 255, 0, 0},
    {0, 255, 0, 0, 255, 0},       {0, 0, 255, 0, 0, 255},       {150, 200, 90, 58, 255, 58},
};

static void case_tone_map(const char *name, const int (*rows)[6], int count, int trc, int primaries)
{
    AVFrame *src, *dst;
    int i;
    kc_case("%s maps to the bytes of KitePlayer's HdrToneMap", name);
    src = rgba_row(rows, count, trc, primaries);
    KC_NOT_NULL(src);
    dst = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    for (i = 0; i < count; i++) {
        const uint8_t *p = dst->data[0] + i * 4;
        if (p[0] != rows[i][3] || p[1] != rows[i][4] || p[2] != rows[i][5] || p[3] != 77)
            KC_FAIL("input (%d, %d, %d): actual (%d, %d, %d, %d), expected (%d, %d, %d, 77)",
                    rows[i][0], rows[i][1], rows[i][2], p[0], p[1], p[2], p[3],
                    rows[i][3], rows[i][4], rows[i][5]);
    }
    KC_EQ_INT(dst->color_trc, AVCOL_TRC_GAMMA22);
    KC_EQ_INT(dst->color_primaries, primaries == AVCOL_PRI_BT2020 ? AVCOL_PRI_BT709 : primaries);
    KC_EQ_INT(dst->colorspace, AVCOL_SPC_RGB);
    KC_EQ_INT(dst->color_range, AVCOL_RANGE_JPEG);
    kc_detail("%d pixels", count);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_other_byte_orders(void)
{
    static const int one[][6] = {{200, 100, 50, 255, 84, 26}};
    AVFrame *src, *dst;
    kc_case("bgra and rgb24 carry each tone mapped channel in its own byte");
    src = rgba_row(one, 1, AVCOL_TRC_SMPTE2084, AVCOL_PRI_BT709);
    KC_NOT_NULL(src);
    dst = ffkmp_frame_convert_display(src, AV_PIX_FMT_BGRA);
    KC_NOT_NULL(dst);
    KC_EQ_INT(dst->data[0][0], 26);
    KC_EQ_INT(dst->data[0][1], 84);
    KC_EQ_INT(dst->data[0][2], 255);
    KC_EQ_INT(dst->data[0][3], 77);
    ffkmp_frame_free(dst);
    dst = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGB24);
    KC_NOT_NULL(dst);
    KC_EQ_INT(dst->data[0][0], 255);
    KC_EQ_INT(dst->data[0][1], 84);
    KC_EQ_INT(dst->data[0][2], 26);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

/* swscale's fast conversion of a BT.709 limited range picture to rgba, called directly. */
static AVFrame *fast_rgba(const AVFrame *src)
{
    struct SwsContext *sws;
    const int *coeffs = sws_getCoefficients(SWS_CS_ITU709);
    AVFrame *dst = av_frame_alloc();
    if (dst == NULL) return NULL;
    dst->width = src->width;
    dst->height = src->height;
    dst->format = AV_PIX_FMT_RGBA;
    sws = sws_getContext(src->width, src->height, (enum AVPixelFormat)src->format,
                         src->width, src->height, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
    if (sws == NULL || av_frame_get_buffer(dst, 0) < 0 ||
        sws_setColorspaceDetails(sws, coeffs, 0, coeffs, 1, 0, 1 << 16, 1 << 16) < 0 ||
        sws_scale(sws, (const uint8_t * const *)src->data, src->linesize, 0, src->height,
                  dst->data, dst->linesize) < 0)
        av_frame_free(&dst);
    sws_freeContext(sws);
    return dst;
}

/* A screen takes swscale's fast conversion, which ffkmp_frame_convert_pixfmt made too until #164.
 * The exact one lands within 1 of the matrix and interpolates chroma; the fast one lands up to 3
 * off and repeats each chroma sample across a pair of pixels, for about a seventh of the time on
 * a 1080p frame, as the cost case prints. A
 * picture whose every pixel differs from its neighbour pins the bytes to swscale's, and a flat one,
 * where repeating chroma changes nothing, holds the two conversions within 3 of each other. */
static void case_sdr_is_the_fast_conversion(void)
{
    AVFrame *src, *plain, *display, *direct;
    int row, i, worst = 0;
    kc_case("an SDR picture gives swscale's fast conversion, with the tags of ffkmp_frame_convert_pixfmt");
    src = flat_yuv(AV_PIX_FMT_YUV420P, 64, 48, 120, 90, 180, AVCOL_SPC_BT709, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(src);
    fill_varied(src);
    display = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGBA);
    direct = fast_rgba(src);
    KC_NOT_NULL(display);
    KC_NOT_NULL(direct);
    for (row = 0; row < 48; row++)
        KC_EQ_MEM(display->data[0] + (ptrdiff_t)row * display->linesize[0],
                  direct->data[0] + (ptrdiff_t)row * direct->linesize[0], 64 * 4);
    ffkmp_frame_free(direct);
    ffkmp_frame_free(display);
    ffkmp_frame_free(src);

    src = flat_yuv(AV_PIX_FMT_YUV420P, 64, 48, 120, 90, 180, AVCOL_SPC_BT709, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(src);
    plain = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGBA);
    display = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(plain);
    KC_NOT_NULL(display);
    for (row = 0; row < 48; row++)
        for (i = 0; i < 64 * 4; i++) {
            int d = abs(display->data[0][(ptrdiff_t)row * display->linesize[0] + i] -
                        plain->data[0][(ptrdiff_t)row * plain->linesize[0] + i]);
            if (d > worst) worst = d;
        }
    if (worst > 3) KC_FAIL("the fast conversion is %d off the exact one, more than 3", worst);
    KC_EQ_INT(display->color_trc, plain->color_trc);
    KC_EQ_INT(display->color_primaries, plain->color_primaries);
    KC_EQ_INT(display->colorspace, AVCOL_SPC_RGB);
    KC_EQ_INT(display->color_range, AVCOL_RANGE_JPEG);
    kc_detail("flat colour fast (%d, %d, %d), exact (%d, %d, %d)",
              display->data[0][0], display->data[0][1], display->data[0][2],
              plain->data[0][0], plain->data[0][1], plain->data[0][2]);
    ffkmp_frame_free(display);
    ffkmp_frame_free(plain);
    ffkmp_frame_free(src);
}

/* ---- YCgCo ---- */

static void check_ycgco(int format, int y, int cg, int co, int range)
{
    AVFrame *src, *dst;
    double yf, cgf, cof;
    int full = range == AVCOL_RANGE_JPEG;
    const uint8_t *p;
    src = flat_yuv(format, 16, 8, y, cg, co, AVCOL_SPC_YCGCO, range);
    KC_NOT_NULL(src);
    dst = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    yf = full ? y : (y - 16) * 255.0 / 219.0;
    cgf = full ? cg - 128 : (cg - 128) * 255.0 / 224.0;
    cof = full ? co - 128 : (co - 128) * 255.0 / 224.0;
    /* The last pixel of the last row, so the whole frame was written. */
    p = dst->data[0] + (ptrdiff_t)7 * dst->linesize[0] + 15 * 4;
    expect_near(p[0], fmin(255, fmax(0, yf - cgf + cof)), "red");
    expect_near(p[1], fmin(255, fmax(0, yf + cgf)), "green");
    expect_near(p[2], fmin(255, fmax(0, yf - cgf - cof)), "blue");
    KC_EQ_INT(p[3], 255);
    KC_EQ_INT(dst->colorspace, AVCOL_SPC_RGB);
    KC_EQ_INT(dst->color_range, AVCOL_RANGE_JPEG);
    kc_detail("(%d, %d, %d) to (%d, %d, %d)", y, cg, co, p[0], p[1], p[2]);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_ycgco(void)
{
    kc_case("a full range YCgCo picture uses the YCgCo matrix");
    check_ycgco(AV_PIX_FMT_YUV444P, 128, 160, 128, AVCOL_RANGE_JPEG);
    check_ycgco(AV_PIX_FMT_YUV444P, 128, 128, 160, AVCOL_RANGE_JPEG);
    check_ycgco(AV_PIX_FMT_YUV444P, 128, 128, 128, AVCOL_RANGE_JPEG);
    check_ycgco(AV_PIX_FMT_YUV444P, 240, 20, 250, AVCOL_RANGE_JPEG);
    kc_case("a studio range YCgCo picture scales before the matrix");
    check_ycgco(AV_PIX_FMT_YUV444P, 126, 150, 110, AVCOL_RANGE_MPEG);
    check_ycgco(AV_PIX_FMT_YUV444P, 16, 128, 128, AVCOL_RANGE_MPEG);
    check_ycgco(AV_PIX_FMT_YUV444P, 235, 128, 128, AVCOL_RANGE_MPEG);
    kc_case("a subsampled YCgCo picture is upsampled first");
    check_ycgco(AV_PIX_FMT_YUV420P, 128, 160, 128, AVCOL_RANGE_JPEG);
    check_ycgco(AV_PIX_FMT_YUV420P, 126, 150, 110, AVCOL_RANGE_MPEG);
}

/* ---- FCC, on the plain conversion every backend's Frame.convert uses ---- */

static void case_fcc(void)
{
    const int y = 120, u = 90, v = 180;
    const double kr = 0.30, kb = 0.11, kg = 1.0 - kr - kb;
    double yf = (y - 16) * 255.0 / 219.0, uf = (u - 128) * 255.0 / 224.0, vf = (v - 128) * 255.0 / 224.0;
    AVFrame *src, *dst;
    const uint8_t *p;
    kc_case("an FCC picture converts with the FCC matrix, not the HD guess");
    /* 720 rows, where the undeclared-matrix rule would have picked BT.709. */
    src = flat_yuv(AV_PIX_FMT_YUV420P, 64, 720, y, u, v, AVCOL_SPC_FCC, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(src);
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    p = dst->data[0] + (ptrdiff_t)719 * dst->linesize[0] + 63 * 4;
    expect_near(p[0], yf + 2 * (1 - kr) * vf, "red");
    expect_near(p[1], yf - 2 * kb * (1 - kb) / kg * uf - 2 * kr * (1 - kr) / kg * vf, "green");
    expect_near(p[2], yf + 2 * (1 - kb) * uf, "blue");
    kc_detail("(%d, %d, %d)", p[0], p[1], p[2]);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

/* ---- Cost, printed and never asserted, because a busy machine moves it ---- */

/* The thread's own processor time, so other processes on a busy machine move the number less. */
static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

static double per_frame_ms(const AVFrame *src, AVFrame *(*convert)(const AVFrame *, int))
{
    double start;
    int i;
    ffkmp_frame_free(convert(src, AV_PIX_FMT_RGBA));
    start = now_ms();
    for (i = 0; i < 5; i++) {
        AVFrame *dst = convert(src, AV_PIX_FMT_RGBA);
        KC_NOT_NULL(dst);
        ffkmp_frame_free(dst);
    }
    return (now_ms() - start) / 5;
}

static void case_cost(void)
{
    AVFrame *frame;
    double sdr, exact, pq_varied, pq_flat;
    kc_case("the tone map's cost on a 1080p frame, beside the plain conversion and the exact one");
    frame = flat_yuv(AV_PIX_FMT_YUV420P, 1920, 1080, 120, 90, 180, AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(frame);
    fill_varied(frame);
    sdr = per_frame_ms(frame, ffkmp_frame_convert_display);
    exact = per_frame_ms(frame, ffkmp_frame_convert_pixfmt);
    frame->color_trc = AVCOL_TRC_SMPTE2084;
    frame->color_primaries = AVCOL_PRI_BT2020;
    pq_varied = per_frame_ms(frame, ffkmp_frame_convert_display);
    ffkmp_frame_free(frame);
    frame = flat_yuv(AV_PIX_FMT_YUV420P, 1920, 1080, 120, 90, 180, AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(frame);
    frame->color_trc = AVCOL_TRC_SMPTE2084;
    frame->color_primaries = AVCOL_PRI_BT2020;
    pq_flat = per_frame_ms(frame, ffkmp_frame_convert_display);
    kc_detail("SDR %.2f ms, exact %.2f ms, PQ %.2f ms varied and %.2f ms flat, per frame", sdr, exact, pq_varied, pq_flat);
    ffkmp_frame_free(frame);
}

/* ---- Refusals and ownership ---- */

static void case_refusals(void)
{
    static const int formats[] = {
        AV_PIX_FMT_YUV420P, AV_PIX_FMT_GBRP, AV_PIX_FMT_RGB48LE, AV_PIX_FMT_PAL8,
        AV_PIX_FMT_RGB565LE, AV_PIX_FMT_NONE, 1 << 20,
    };
    kc_alloc_counts before;
    AVFrame *src;
    size_t i;
    kc_case("a destination that is not packed 8-bit RGB is refused before anything allocates");
    src = flat_yuv(AV_PIX_FMT_YUV420P, 16, 16, 120, 90, 180, AVCOL_SPC_BT709, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(src);
    kc_alloc_snapshot(&before);
    for (i = 0; i < sizeof(formats) / sizeof(formats[0]); i++)
        KC_NULL(ffkmp_frame_convert_display(src, formats[i]));
    KC_NULL(ffkmp_frame_convert_display(NULL, AV_PIX_FMT_RGBA));
    KC_ALLOC_BALANCED(&before);
    ffkmp_frame_free(src);
}

static void case_no_leak(void)
{
    kc_alloc_counts before;
    AVFrame *src, *dst;
    kc_case("the YCgCo step frees its intermediate frame, and the caller frees the result");
    src = flat_yuv(AV_PIX_FMT_YUV420P, 64, 64, 126, 150, 110, AVCOL_SPC_YCGCO, AVCOL_RANGE_MPEG);
    KC_NOT_NULL(src);
    src->color_trc = AVCOL_TRC_SMPTE2084;
    /* The first call builds this thread's scaler; the window measures the steady state. */
    dst = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    ffkmp_frame_free(dst);
    kc_alloc_snapshot(&before);
    dst = ffkmp_frame_convert_display(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    ffkmp_frame_free(dst);
    KC_ALLOC_BALANCED(&before);
    ffkmp_frame_free(src);
}

int main(void)
{
    kc_suite_begin("test_display");

    case_tone_map("PQ with BT.2020 primaries", PQ_2020, 12, AVCOL_TRC_SMPTE2084, AVCOL_PRI_BT2020);
    case_tone_map("PQ with BT.709 primaries", PQ_709, 12, AVCOL_TRC_SMPTE2084, AVCOL_PRI_BT709);
    case_tone_map("HLG with BT.2020 primaries", HLG_2020, 12, AVCOL_TRC_ARIB_STD_B67, AVCOL_PRI_BT2020);
    case_other_byte_orders();
    case_sdr_is_the_fast_conversion();
    case_ycgco();
    case_fcc();
    case_refusals();
    case_no_leak();
    case_cost();

    return kc_suite_end();
}
