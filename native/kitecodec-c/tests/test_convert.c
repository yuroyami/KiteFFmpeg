/* ffkmp_frame_convert_pixfmt, the only swscale use in the whole helper layer.
 *
 * The helper builds and destroys an SwsContext on every call. B2 owns caching
 * that context; B1 changes nothing about it and writes the baseline B2's caching has to match. So
 * every case here asserts the behaviour as it is today, not as it should become, and the numbers
 * are measured rather than chosen.
 *
 * The baseline has four parts.
 *
 *   Correctness, against an oracle that is not the helper. The expected RGBA for a flat yuv420p
 *   frame is computed here from the BT.601 limited-range matrix, in floating point, and compared
 *   with what the helper produced. Measured on this machine, the two agree exactly on all six
 *   colours tried, so the tolerance is stated as 1 rather than as whatever the first run happened
 *   to print. A flat colour is the right fixture: source and destination are the same size, so
 *   SWS_BILINEAR does no filtering and the case measures colour conversion alone.
 *
 *   The metadata the helper carries, and the two tags it deliberately overwrites. Width, height,
 *   format and pts are its own; sample aspect ratio, duration, primaries and transfer travel from
 *   the source through av_frame_copy_props. Colour range and colour space do NOT travel when the
 *   conversion changed them: an RGB destination is written full range with an RGB matrix, so the
 *   result carries those rather than the source's YUV tags. A caller reading
 *   dst->color_range gets what the pixels are, which is the only reading worth trusting.
 *
 *   Allocation cost per call, which is the actual subject here. Measured under the interposer
 *   in the plain variant: an even-height frame costs 9 allocating calls per conversion and an
 *   odd-height frame costs 61, deterministic and repeatable, with the difference coming from
 *   swscale needing full filter tables when the last chroma row is half populated. Every call
 *   frees 5 of those and hands 4 live blocks to the caller, and ffkmp_frame_free brings the window
 *   to exactly zero. A cached context is what makes those numbers fall; this file is what says
 *   what they were.
 *
 *   Leak freedom on the failure paths, which is the half a caching change is most likely to
 *   break. Every refusal returns NULL and leaves no block behind. One fails late: a source frame
 *   with no data pointers gets as far as sws_scale and has to unwind an already allocated
 *   destination frame. Every unusable format now fails EARLY instead, at the descriptor gate in
 *   front of swscale, so pal8, hardware formats and values outside the enum all refuse before a
 *   context or a destination frame exists.
 *
 * One hazard, found while writing this file and FIXED since: a destination format outside the enum,
 * AV_PIX_FMT_NONE included, used to reach libswscale and abort the process at an assertion in
 * swscale_internal.h rather than returning NULL. The helper now validates both formats before
 * swscale sees them. The case named "a destination format outside the enum never
 * yields a frame" still makes that call in a child process, because that is the only way to tell a
 * refusal from a crash: the child must exit, and any signal is now a failure of this suite rather
 * than a documented outcome of it.
 */

#include "harness.h"

#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

#include "kitecodec_helpers.h"

#include <libavutil/frame.h>
#include <libavutil/log.h>
#include <libavutil/pixfmt.h>

/* argv[0] of this process, so the invalid-format case can re-run itself in a child. */
static const char *self_path;

#define KC_CHILD_ARG "kc-child-convert-invalid-format"
#define KC_CHILD_RETURNED_NULL 0
#define KC_CHILD_RETURNED_FRAME 3

/* ---- Fixtures ---- */

/* A flat yuv420p frame: one luma value everywhere, one chroma pair everywhere. */
static AVFrame *flat_yuv420p(int width, int height, int y, int u, int v, int64_t pts)
{
    AVFrame *frame = ffkmp_frame_alloc();
    int row;

    if (frame == NULL)
        return NULL;
    ffkmp_frame_set_width(frame, width);
    ffkmp_frame_set_height(frame, height);
    ffkmp_frame_set_format(frame, AV_PIX_FMT_YUV420P);
    ffkmp_frame_set_pts(frame, pts);
    if (ffkmp_frame_get_buffer(frame, 0) < 0) {
        ffkmp_frame_free(frame);
        return NULL;
    }
    for (row = 0; row < height; row++)
        memset(frame->data[0] + (ptrdiff_t)row * frame->linesize[0], y, (size_t)width);
    for (row = 0; row < (height + 1) / 2; row++) {
        memset(frame->data[1] + (ptrdiff_t)row * frame->linesize[1], u, (size_t)((width + 1) / 2));
        memset(frame->data[2] + (ptrdiff_t)row * frame->linesize[2], v, (size_t)((width + 1) / 2));
    }
    return frame;
}

/* A frame with dimensions and a format but no buffer, for the guard rows. */
static AVFrame *bare_frame(int width, int height, int format)
{
    AVFrame *frame = ffkmp_frame_alloc();

    if (frame == NULL)
        return NULL;
    ffkmp_frame_set_width(frame, width);
    ffkmp_frame_set_height(frame, height);
    ffkmp_frame_set_format(frame, format);
    return frame;
}

/* ---- The oracle ---- */

/* BT.601 limited range, the conversion swscale applies to a yuv420p frame that declares neither a
 * colour space nor a range. Written from the coefficients rather than from swscale's tables, so
 * this is an independent answer and not a restatement of the code under test. */
static void bt601_limited_to_rgb(int y, int u, int v, int *r, int *g, int *b)
{
    const double kr = 0.299;
    const double kg = 0.587;
    const double kb = 0.114;
    double luma = 255.0 / 219.0 * (y - 16);
    double cb = u - 128;
    double cr = v - 128;
    double red = luma + 255.0 / 112.0 * (1.0 - kr) * cr;
    double green = luma
        - 255.0 / 112.0 * (1.0 - kb) * (kb / kg) * cb
        - 255.0 / 112.0 * (1.0 - kr) * (kr / kg) * cr;
    double blue = luma + 255.0 / 112.0 * (1.0 - kb) * cb;

    *r = (int)lround(red < 0.0 ? 0.0 : red > 255.0 ? 255.0 : red);
    *g = (int)lround(green < 0.0 ? 0.0 : green > 255.0 ? 255.0 : green);
    *b = (int)lround(blue < 0.0 ? 0.0 : blue > 255.0 ? 255.0 : blue);
}

/* The largest absolute difference between a converted RGBA frame and one expected pixel, over
 * every pixel in the frame. A flat source must convert to a flat destination, so one expected
 * pixel is the right shape for the assertion. */
static int max_rgba_deviation(const AVFrame *frame, int r, int g, int b, int a)
{
    int worst = 0;
    int row;
    int col;

    for (row = 0; row < frame->height; row++) {
        const uint8_t *line = frame->data[0] + (ptrdiff_t)row * frame->linesize[0];
        for (col = 0; col < frame->width; col++) {
            int expected[4];
            int channel;
            expected[0] = r;
            expected[1] = g;
            expected[2] = b;
            expected[3] = a;
            for (channel = 0; channel < 4; channel++) {
                int difference = (int)line[col * 4 + channel] - expected[channel];
                if (difference < 0)
                    difference = -difference;
                if (difference > worst)
                    worst = difference;
            }
        }
    }
    return worst;
}

/* ---- Correctness ---- */

static void case_flat_colours(void)
{
    struct row {
        const char *name;
        int y;
        int u;
        int v;
    };
    struct row rows[] = {
        { "limited black",  16, 128, 128 },
        { "limited white", 235, 128, 128 },
        { "601 red",        81,  90, 240 },
        { "601 green",     145,  54,  34 },
        { "601 blue",       41, 240, 110 },
        { "mid grey",      126, 128, 128 }
    };
    /* Measured deviation from the oracle on this machine: 0 on every one of the six. The tolerance
     * is 1 so that a future FFmpeg rounding its last bit differently does not fail the gate, and
     * the measured deviation is reported on each case line so a drift is still visible. */
    const int tolerance = 1;
    size_t i;
    int worst_overall = 0;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        AVFrame *src;
        AVFrame *dst;
        int r;
        int g;
        int b;
        int deviation;

        kc_case("%s (y=%d u=%d v=%d) converts to the BT.601 limited-range answer", rows[i].name,
                rows[i].y, rows[i].u, rows[i].v);
        src = flat_yuv420p(64, 64, rows[i].y, rows[i].u, rows[i].v, 12345);
        KC_NOT_NULL(src);
        dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGBA);
        KC_NOT_NULL(dst);
        bt601_limited_to_rgb(rows[i].y, rows[i].u, rows[i].v, &r, &g, &b);
        deviation = max_rgba_deviation(dst, r, g, b, 255);
        KC_CHECKF(deviation <= tolerance,
                  "worst channel deviation %d exceeds the stated tolerance of %d; the oracle says "
                  "rgba(%d,%d,%d,255)", deviation, tolerance, r, g, b);
        if (deviation > worst_overall)
            worst_overall = deviation;
        kc_detail("oracle rgba(%d,%d,%d,255) worst deviation %d of %d allowed", r, g, b, deviation,
                  tolerance);
        ffkmp_frame_free(dst);
        ffkmp_frame_free(src);
    }

    kc_case("the worst deviation across all six colours");
    KC_CHECKF(worst_overall <= tolerance, "worst overall deviation %d", worst_overall);
    kc_detail("worst=%d, measured 0 when this baseline was written", worst_overall);
}

static void case_metadata_carried_and_dropped(void)
{
    AVFrame *src = flat_yuv420p(48, 32, 81, 90, 240, 987654321);
    AVFrame *dst;

    KC_NOT_NULL(src);
    src->sample_aspect_ratio.num = 4;
    src->sample_aspect_ratio.den = 3;
    src->color_range = AVCOL_RANGE_MPEG;
    src->colorspace = AVCOL_SPC_BT470BG;
    src->duration = 4242;

    kc_case("the helper carries width, height, format and pts");
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    KC_EQ_INT(ffkmp_frame_width(dst), 48);
    KC_EQ_INT(ffkmp_frame_height(dst), 32);
    KC_EQ_INT(ffkmp_frame_format(dst), AV_PIX_FMT_RGBA);
    KC_EQ_I64(ffkmp_frame_pts(dst), 987654321);
    kc_detail("w=%d h=%d fmt=%d pts=%lld", ffkmp_frame_width(dst), ffkmp_frame_height(dst),
              ffkmp_frame_format(dst), (long long)ffkmp_frame_pts(dst));

    kc_case("sar and duration travel with the picture");
    KC_EQ_INT(dst->sample_aspect_ratio.num, 4);
    KC_EQ_INT(dst->sample_aspect_ratio.den, 3);
    KC_EQ_I64(dst->duration, 4242);
    kc_note("the source declared 4/3 and a duration of 4242, and both reach the result: the helper");
    kc_note("runs av_frame_copy_props, so nothing about the picture's timing or shape is lost.");

    kc_case("but the colour tags describe the OUTPUT, not the source it was converted from");
    KC_EQ_INT((int)dst->color_range, (int)AVCOL_RANGE_JPEG);
    KC_EQ_INT((int)dst->colorspace, (int)AVCOL_SPC_RGB);
    kc_note("the source declared MPEG range and BT470BG, and copying those onto an RGBA result");
    kc_note("would be a lie about the pixels: the conversion above produces RGB full range through");
    kc_note("sws_setColorspaceDetails, and RGB has no YUV matrix. A consumer trusting the copied");
    kc_note("tags would convert a second time and crush the range. Primaries and");
    kc_note("transfer are NOT overwritten: they describe the light, not the encoding.");
    KC_EQ_INT((int)dst->color_primaries, (int)src->color_primaries);
    KC_EQ_INT((int)dst->color_trc, (int)src->color_trc);
    kc_detail("sar=%d/%d range=%d space=%d duration=%lld",
              dst->sample_aspect_ratio.num, dst->sample_aspect_ratio.den,
              (int)dst->color_range, (int)dst->colorspace, (long long)dst->duration);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_destination_is_a_new_frame(void)
{
    AVFrame *src = flat_yuv420p(16, 16, 81, 90, 240, 7);
    AVFrame *dst;

    KC_NOT_NULL(src);

    kc_case("converting to the source format still produces a separate frame, not an alias");
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_YUV420P);
    KC_NOT_NULL(dst);
    KC_CHECKF(dst != src, "the helper returned its own argument");
    KC_CHECKF(dst->data[0] != src->data[0], "the helper shared the source's pixel buffer");
    KC_EQ_INT(dst->data[0][0], src->data[0][0]);
    KC_EQ_INT(dst->data[1][0], src->data[1][0]);
    KC_EQ_INT(dst->data[2][0], src->data[2][0]);
    kc_detail("src plane0 at %p, dst plane0 at %p, same y byte %u", (const void *)src->data[0],
              (const void *)dst->data[0], dst->data[0][0]);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_ten_bit_destination(void)
{
    AVFrame *src = flat_yuv420p(16, 16, 235, 128, 128, 5);
    AVFrame *dst;
    unsigned luma;

    KC_NOT_NULL(src);

    kc_case("a 10 bit destination puts the sample in the high bits, P010 style");
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_P010LE);
    KC_NOT_NULL(dst);
    KC_EQ_INT(ffkmp_frame_format(dst), AV_PIX_FMT_P010LE);
    luma = (unsigned)dst->data[0][0] | ((unsigned)dst->data[0][1] << 8);
    /* 235 in 8 bits becomes 235 << 8 in P010's 16 bit little-endian container, which is the
     * alignment the plan is about. Asserting it here means a conversion that started
     * shifting by 6 instead of 8 would fail this case rather than a renderer. */
    KC_EQ_INT((int)luma, 235 << 8);
    kc_detail("luma word %u, expected %u", luma, 235u << 8);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_odd_dimensions(void)
{
    AVFrame *src = flat_yuv420p(17, 9, 81, 90, 240, 3);
    AVFrame *dst;
    int r;
    int g;
    int b;

    KC_NOT_NULL(src);

    kc_case("an odd 17x9 frame converts, and the colour survives the half chroma row");
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    KC_EQ_INT(ffkmp_frame_width(dst), 17);
    KC_EQ_INT(ffkmp_frame_height(dst), 9);
    bt601_limited_to_rgb(81, 90, 240, &r, &g, &b);
    KC_CHECKF(max_rgba_deviation(dst, r, g, b, 255) <= 1, "odd geometry changed the colour");
    kc_detail("deviation %d", max_rgba_deviation(dst, r, g, b, 255));
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_round_trip(void)
{
    AVFrame *rgba = ffkmp_frame_alloc();
    AVFrame *yuv;
    AVFrame *back;
    int row;
    int col;

    KC_NOT_NULL(rgba);
    ffkmp_frame_set_width(rgba, 32);
    ffkmp_frame_set_height(rgba, 32);
    ffkmp_frame_set_format(rgba, AV_PIX_FMT_RGBA);
    KC_CHECK(ffkmp_frame_get_buffer(rgba, 0) >= 0);
    for (row = 0; row < 32; row++) {
        uint8_t *line = rgba->data[0] + (ptrdiff_t)row * rgba->linesize[0];
        for (col = 0; col < 32; col++) {
            line[col * 4 + 0] = 200;
            line[col * 4 + 1] = 30;
            line[col * 4 + 2] = 60;
            line[col * 4 + 3] = 255;
        }
    }

    kc_case("rgba to yuv420p and back lands within 1 of where it started");
    yuv = ffkmp_frame_convert_pixfmt(rgba, AV_PIX_FMT_YUV420P);
    KC_NOT_NULL(yuv);
    back = ffkmp_frame_convert_pixfmt(yuv, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(back);
    /* Chroma subsampling loses information, but a flat colour has nothing to lose: every chroma
     * sample averages identical neighbours. So the round trip is exact up to the two rounding
     * steps, which measured 1 on the green and blue channels. */
    KC_CHECKF(max_rgba_deviation(back, 200, 30, 60, 255) <= 1,
              "round trip deviation %d exceeds 1", max_rgba_deviation(back, 200, 30, 60, 255));
    kc_detail("yuv (%u,%u,%u), back deviation %d", yuv->data[0][0], yuv->data[1][0],
              yuv->data[2][0], max_rgba_deviation(back, 200, 30, 60, 255));
    ffkmp_frame_free(back);
    ffkmp_frame_free(yuv);
    ffkmp_frame_free(rgba);
}

/* ---- Allocation, the actual baseline ---- */

static void case_allocation_baseline(void)
{
    AVFrame *even = flat_yuv420p(64, 64, 81, 90, 240, 1);
    AVFrame *odd = flat_yuv420p(64, 63, 81, 90, 240, 1);
    kc_alloc_counts before;
    AVFrame *dst;
    long long held_live;
    long long per_call_even = 0;
    long long per_call_odd = 0;
    int repeat;

    KC_NOT_NULL(even);
    KC_NOT_NULL(odd);

    kc_case("one conversion, held: the caller owns the frame and its buffer");
    kc_alloc_snapshot(&before);
    dst = ffkmp_frame_convert_pixfmt(even, AV_PIX_FMT_RGBA);
    KC_NOT_NULL(dst);
    if (kc_alloc_active()) {
        held_live = kc_alloc_live_delta(&before);
        kc_detail("new=%lld freed=%lld live=%lld", kc_alloc_new_delta(&before),
                  kc_alloc_free_delta(&before), held_live);
        KC_CHECKF(held_live == 4, "expected 4 live blocks after a conversion, measured %lld",
                  held_live);
        per_call_even = kc_alloc_new_delta(&before);
    } else {
        kc_partial("allocation pairing not observable in this variant");
    }

    kc_case("and freeing it brings the whole window to zero, SwsContext included");
    ffkmp_frame_free(dst);
    KC_ALLOC_BALANCED(&before);

    kc_case("repeats on the SAME shape hit the thread-local cache and cost strictly less");
    if (kc_alloc_active()) {
        long long steady = -1;
        for (repeat = 0; repeat < 8; repeat++) {
            long long cost;
            kc_alloc_snapshot(&before);
            dst = ffkmp_frame_convert_pixfmt(even, AV_PIX_FMT_RGBA);
            KC_NOT_NULL(dst);
            ffkmp_frame_free(dst);
            cost = kc_alloc_new_delta(&before);
            if (steady < 0) steady = cost;
            KC_CHECKF(cost == steady,
                      "repeat %d cost %lld allocating calls, the first repeat cost %lld; every "
                      "cache hit is supposed to cost the same", repeat, cost, steady);
            KC_CHECKF(kc_alloc_live_delta(&before) == 0, "repeat %d leaked", repeat);
        }
        /* The cached-context baseline. per_call_even is the cost of a call that had to REBUILD
         * the context (the case above ran after a differently shaped conversion); steady is the
         * cost once the context matches. Both are asserted, so a regression that reintroduces
         * per-call context construction fails here with numbers in it. */
        KC_CHECKF(per_call_even == 9,
                  "a shape-changing conversion cost %lld allocating calls, the recorded baseline "
                  "is 9", per_call_even);
        KC_CHECKF(steady == 4,
                  "a cache-hit conversion cost %lld allocating calls, the recorded baseline is 4",
                  steady);
        KC_CHECKF(steady < per_call_even, "the cache did not make a repeat cheaper");
        per_call_even = steady;
        kc_detail("rebuild=9, cache hit=4 allocating calls, 8 repeats, all identical");
        kc_note("sws_getCachedContext keeps one context per thread. Only a shape change (size or");
        kc_note("either pixel format) pays construction again, which is what perf blocker 3 asked");
        kc_note("for; the four remaining calls are the destination frame and its buffer.");
    } else {
        kc_partial("allocation pairing not observable in this variant");
    }

    kc_case("an odd height changes the shape, so it pays a rebuild AND swscale's slow path");
    if (kc_alloc_active()) {
        long long held_by_cache;
        kc_alloc_snapshot(&before);
        dst = ffkmp_frame_convert_pixfmt(odd, AV_PIX_FMT_RGBA);
        KC_NOT_NULL(dst);
        ffkmp_frame_free(dst);
        per_call_odd = kc_alloc_new_delta(&before);
        held_by_cache = kc_alloc_live_delta(&before);
        KC_CHECKF(per_call_odd == 61,
                  "a 64x63 conversion cost %lld allocating calls, the recorded baseline is 61",
                  per_call_odd);
        KC_CHECKF(per_call_odd > per_call_even, "the odd case did not cost more");
        /* The frame is gone but the NEW context is not: the cache holds exactly one, so what
         * stays live here is the odd context minus the even one it replaced. */
        KC_CHECKF(held_by_cache > 0, "the odd rebuild left nothing cached, so nothing was cached");
        kc_detail("cache hit=%lld odd rebuild=%lld, cached blocks held=%lld", per_call_even,
                  per_call_odd, held_by_cache);
        kc_note("the odd number is a rebuild, not a steady-state cost: alternating two shapes on");
        kc_note("one thread defeats the cache by construction, which is why decode paths convert");
        kc_note("one shape at a time.");

        kc_case("and the cache holds exactly one context: the next shape frees the odd one");
        dst = ffkmp_frame_convert_pixfmt(even, AV_PIX_FMT_RGBA);
        KC_NOT_NULL(dst);
        ffkmp_frame_free(dst);
        KC_CHECKF(kc_alloc_live_delta(&before) == 0,
                  "converting the even shape again left %lld blocks live; the cache is supposed "
                  "to hold one context, not accumulate them", kc_alloc_live_delta(&before));
        kc_detail("the odd context was released when the even shape came back, window balanced");
    } else {
        kc_partial("allocation pairing not observable in this variant");
        kc_case("and the cache holds exactly one context: the next shape frees the odd one");
        kc_partial("allocation pairing not observable in this variant");
    }

    ffkmp_frame_free(odd);
    ffkmp_frame_free(even);
}

/* ---- Refusals, all of which must allocate nothing net ---- */

static void case_guard_paths(void)
{
    struct row {
        const char *name;
        int width;
        int height;
        int src_format;
        int with_buffer;
        int dst_format;
        const char *why;
    };
    struct row rows[] = {
        { "a NULL source", 0, 0, 0, 0, AV_PIX_FMT_RGBA,
          "the first guard in the helper" },
        { "zero width", 0, 16, AV_PIX_FMT_YUV420P, 0, AV_PIX_FMT_RGBA,
          "refused before swscale is touched" },
        { "zero height", 16, 0, AV_PIX_FMT_YUV420P, 0, AV_PIX_FMT_RGBA,
          "refused before swscale is touched" },
        { "a negative height", 16, -2, AV_PIX_FMT_YUV420P, 0, AV_PIX_FMT_RGBA,
          "refused before swscale is touched" },
        { "a source with no pixel buffer", 16, 16, AV_PIX_FMT_YUV420P, 0, AV_PIX_FMT_RGBA,
          "fails inside sws_scale, so the destination frame has to be unwound" },
        { "pal8 as the destination", 16, 16, AV_PIX_FMT_YUV420P, 1, AV_PIX_FMT_PAL8,
          "a real format swscale cannot write" },
        { "videotoolbox as the destination", 16, 16, AV_PIX_FMT_YUV420P, 1,
          AV_PIX_FMT_VIDEOTOOLBOX, "a hardware format, refused by the descriptor gate" }
    };
    size_t i;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        AVFrame *src = NULL;
        AVFrame *dst;
        kc_alloc_counts before;

        kc_case("%s is refused with NULL and keeps nothing: %s", rows[i].name, rows[i].why);
        /* The first row is the NULL-source row, so it is the only one with no frame to build. */
        if (i > 0) {
            src = rows[i].with_buffer
                ? flat_yuv420p(rows[i].width, rows[i].height, 81, 90, 240, 1)
                : bare_frame(rows[i].width, rows[i].height, rows[i].src_format);
            KC_NOT_NULL(src);
        }
        kc_alloc_snapshot(&before);
        dst = ffkmp_frame_convert_pixfmt(src, rows[i].dst_format);
        KC_NULL(dst);
        /* Not BALANCED: a refusal that reached sws_getCachedContext RELEASES the context the
         * cache was holding, so the window legitimately ends NEGATIVE. What no refusal may do is
         * leave a block behind, which is what a positive delta would mean. */
        KC_CHECKF(kc_alloc_live_delta(&before) <= 0,
                  "the refusal left %lld blocks live", kc_alloc_live_delta(&before));
        kc_detail("new=%lld freed=%lld live=%lld", kc_alloc_new_delta(&before),
                  kc_alloc_free_delta(&before), kc_alloc_live_delta(&before));
        if (src != NULL)
            ffkmp_frame_free(src);
    }
}

/* ---- The refusal that used to be a crash ---- */

/* Run in a child process. A destination format outside the pixel format enum is refused by the
 * helper's own descriptor gate now, so the call returns NULL and the child exits normally. The
 * child exits 0 if the call came back with NULL and 3 if it came back with a frame; if it dies
 * with a signal, libswscale asserted, which means the gate is gone. All three outcomes other than
 * a clean NULL are failures. The fork stays because a returned NULL and an aborted process are
 * indistinguishable in-process, and only the fork can tell them apart. */
static int child_convert_invalid_format(void)
{
    AVFrame *src = flat_yuv420p(16, 16, 81, 90, 240, 1);
    AVFrame *dst;

    av_log_set_level(AV_LOG_QUIET);
    if (src == NULL)
        return KC_CHILD_RETURNED_NULL;
    dst = ffkmp_frame_convert_pixfmt(src, AV_PIX_FMT_NONE);
    ffkmp_frame_free(src);
    if (dst != NULL) {
        ffkmp_frame_free(dst);
        return KC_CHILD_RETURNED_FRAME;
    }
    return KC_CHILD_RETURNED_NULL;
}

static void case_invalid_destination_format(void)
{
    pid_t child;
    int status = 0;

    kc_case("a destination format outside the enum is refused, without a signal");
    KC_NOT_NULL(self_path);
    KC_CHECKF(access(self_path, X_OK) == 0,
              "cannot re-run this binary as a child: %s is not executable", self_path);

    child = fork();
    KC_CHECKF(child >= 0, "fork failed");
    if (child == 0) {
        char *argv[3];
        argv[0] = (char *)self_path;
        argv[1] = (char *)KC_CHILD_ARG;
        argv[2] = NULL;
        /* Silenced because a REGRESSION here aborts, and libswscale plus any sanitizer runtime
         * would print pages of it into the middle of this suite's one-line-per-case output. */
        if (freopen("/dev/null", "w", stderr) == NULL)
            _exit(126);
        if (freopen("/dev/null", "w", stdout) == NULL)
            _exit(126);
        execv(self_path, argv);
        _exit(127);
    }
    KC_CHECKF(waitpid(child, &status, 0) == child, "waitpid failed");

    if (WIFSIGNALED(status)) {
        /* No signal is acceptable here. This case exists to prove the format gate stands in front
         * of libswscale's assertion, and a signal is that gate being gone. */
        KC_FAIL("the child died with signal %d: the destination format reached libswscale and it "
                "asserted, so ffkmp_frame_convert_pixfmt is no longer validating its formats",
                WTERMSIG(status));
    } else if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        kc_detail("the child exited %d", code);
        KC_CHECKF(code != KC_CHILD_RETURNED_FRAME,
                  "the helper built a frame from a destination format that does not exist");
        KC_CHECKF(code == KC_CHILD_RETURNED_NULL,
                  "the child could not run the case, exit %d", code);
        kc_note("the helper's own descriptor gate refused the format and returned NULL, so the");
        kc_note("process survived a call that used to abort it from inside libswscale");
    } else {
        KC_FAIL("the child neither exited nor was signalled, status %d", status);
    }
}

int main(int argc, char **argv)
{
    self_path = argv[0];

    if (argc == 2 && strcmp(argv[1], KC_CHILD_ARG) == 0)
        return child_convert_invalid_format();

    kc_suite_begin("test_convert");

    case_flat_colours();
    case_metadata_carried_and_dropped();
    case_destination_is_a_new_frame();
    case_ten_bit_destination();
    case_odd_dimensions();
    case_round_trip();
    case_allocation_baseline();
    case_guard_paths();
    case_invalid_destination_format();

    return kc_suite_end();
}
