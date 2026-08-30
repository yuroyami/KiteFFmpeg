/* Arithmetic and by-value-struct helpers: the macro crossings, the 128 bit rescales, the
 * AVRational out parameters and AV_CEIL_RSHIFT plane heights.
 *
 * Plan section 15.2 B1.2 asks this suite to cover "the ten macro, 128 bit and by-value-struct
 * helpers with the D9 overflow vectors", and section 15.3 adds "AV_CEIL_RSHIFT plane heights for
 * subsampled formats". The set was enumerated here rather than taken on trust, and it measures 15
 * helpers, not ten. The count is recorded on the suite's first case so a reader sees it without
 * reading this comment:
 *
 *   2 macro crossings          ffkmp_averror_eagain, ffkmp_averror_eof
 *   4 with 128 bit intermediates
 *                              ffkmp_rescale_q, ffkmp_packet_rescale_ts,
 *                              ffkmp_stream_duration_micros, ffkmp_fmt_seek_micros
 *   6 by-value AVRational returned through out parameters
 *                              ffkmp_codecctx_time_base, ffkmp_codecpar_sample_aspect_ratio,
 *                              ffkmp_stream_time_base, ffkmp_stream_avg_frame_rate,
 *                              ffkmp_buffersink_time_base, ffkmp_frame_sample_aspect_ratio
 *   1 AV_CEIL_RSHIFT           ffkmp_frame_plane_height
 *
 * ffkmp_stream_set_time_base is the same family in the other direction, an AVRational written from
 * an int pair, and it is exercised below as the way the stream cases set up their fixture.
 *
 * The out-parameter family was found by grep, and the first grep was wrong: searching for the
 * spelling `int *n, int *d` finds five of the six and misses
 * ffkmp_codecpar_sample_aspect_ratio, which spells its parameters `int *num, int *den`. Anyone
 * re-deriving this set should search for `int \*[a-z]+` instead. That miss is the reason the count
 * here is 13 and not the 12 a narrower search reports.
 *
 * The count was 15 until B1.4 deleted ffkmp_averror_einval and ffkmp_nopts_value as dead exported
 * surface, along with 13 other helpers no Kotlin file imported. The two
 * cases that asserted their values went with them; every other use of them in this file was
 * incidental, a convenient source of an error code or of a timestamp sentinel, and those uses now
 * spell the libav macro the deleted helper wrapped.
 *
 * Where a value below is a number rather than a formula it was measured on this machine against
 * Apple clang 17.0.0 and the pkg-config FFmpeg (libavcodec 62.11.100, libavutil 60.8.100), and the
 * measurement is what the case asserts. Two expectations are deliberately rebuilt from arithmetic
 * rather than named: the AVERROR_EOF tag, because a case asserting AVERROR_EOF == AVERROR_EOF
 * proves nothing about the value crossing a function boundary, and the overflowing product that
 * the D9 defect used to compute, because the point of that case is what the wrong arithmetic
 * produced.
 *
 * What this suite does not do. It does not exercise ffkmp_fmt_seek_micros past its NULL guard: the
 * rescale inside it feeds av_seek_frame, which needs a real demuxer, and a container fixture
 * belongs to the suite that owns demuxing. Two hazards were measured in a child process while
 * writing this file and are recorded in kc_note() lines below rather than asserted, because a test
 * must not enshrine a crash as correct behaviour.
 */

#include "harness.h"

#include <errno.h>

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>

/* ---- The two macro crossings ---- */

/* FFmpeg spells its own error tags FFERRTAG(a,b,c,d) == -(int)MKTAG(a,b,c,d), and MKTAG packs the
 * four bytes little end first with the last byte taken as unsigned. Recomputing that here is the
 * only way to assert the value rather than the macro name, and it is the same arithmetic Errors.kt
 * reimplements in Kotlin (plan section 15.5, deferral 3), so a drift between the two shows up as a
 * failure of this case rather than as a wrong error tag in a consumer. */
static int recomputed_errtag(char a, char b, char c, char d)
{
    unsigned packed = (unsigned)(unsigned char)a
        | ((unsigned)(unsigned char)b << 8)
        | ((unsigned)(unsigned char)c << 16)
        | ((unsigned)(unsigned char)d << 24);
    return -(int)packed;
}

static void case_macro_crossings(void)
{
    struct row {
        const char *name;
        int actual;
        int expected;
    };
    struct row rows[] = {
        { "ffkmp_averror_eagain", ffkmp_averror_eagain(), -EAGAIN },
        { "ffkmp_averror_eof",    ffkmp_averror_eof(),    recomputed_errtag('E', 'O', 'F', ' ') },
    };
    size_t i;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        kc_case("%s crosses as its macro value", rows[i].name);
        KC_EQ_INT(rows[i].actual, rows[i].expected);
        KC_CHECKF(rows[i].actual < 0, "%s returned %d, which is not negative; every AVERROR is",
                  rows[i].name, rows[i].actual);
        kc_detail("value=%d", rows[i].actual);
    }

    kc_case("the two error codes are distinct");
    KC_CHECK(ffkmp_averror_eagain() != ffkmp_averror_eof());
    kc_detail("eagain=%d eof=%d", ffkmp_averror_eagain(), ffkmp_averror_eof());
}

/* ---- ffkmp_rescale_q, the helper D9's whole fix rests on ---- */

static void case_rescale_q_vectors(void)
{
    struct row {
        const char *name;
        int64_t v;
        int sn, sd, dn, dd;
        int64_t expected;
    };
    /* The first two rows are D9's own test line: a timestamp of 10^13 in a time base of 1/10^9,
     * converted to microseconds. 10^13 nanoseconds is 10^4 seconds, so 10^10 microseconds. */
    struct row rows[] = {
        { "D9: 1e13 ticks at 1/1e9 to us",   10000000000000LL,  1, 1000000000, 1, 1000000,
          10000000000LL },
        { "D9 negated: -1e13 at 1/1e9",     -10000000000000LL,  1, 1000000000, 1, 1000000,
          -10000000000LL },
        { "zero stays zero",                                0,  1, 1000000000, 1, 1000000, 0 },
        { "90 kHz: 90000 ticks is one second",          90000LL,  1, 90000, 1, 1000000, 1000000LL },
        { "90 kHz: one tick truncates to 11 us",           1LL,  1, 90000, 1, 1000000, 11LL },
        { "identity leaves INT64_MAX alone",         INT64_MAX,  1, 1, 1, 1, INT64_MAX },
        { "INT64_MAX at 1/1e9 to us",                INT64_MAX,  1, 1000000000, 1, 1000000,
          9223372036854776LL },
        { "INT64_MIN at 1/1e9 to us",                INT64_MIN,  1, 1000000000, 1, 1000000,
          -9223372036854776LL },
        { "INT64_MIN + 1 at 1/1e9 to us",        INT64_MIN + 1,  1, 1000000000, 1, 1000000,
          -9223372036854776LL },
        { "tie +0.5 rounds away from zero",              1LL,  1, 2, 1, 1, 1LL },
        { "tie -0.5 rounds away from zero",             -1LL,  1, 2, 1, 1, -1LL },
        { "tie +1.5 rounds away from zero",              3LL,  1, 2, 1, 1, 2LL },
        /* A zero denominator is substituted with 1 by the helper itself, on either side. These
         * four rows assert that substitution rather than assuming it. */
        { "both denominators zero become 1/1",        1000LL,  1, 0, 1, 0, 1000LL },
        { "zero destination denominator",             1000LL,  1, 1000, 1, 0, 1LL },
        { "zero source denominator",                  1000LL,  1, 0, 1, 1000, 1000000LL },
        /* A zero or negative numerator is NOT substituted, and the two sides behave differently:
         * a zero source numerator scales everything to nothing, while a zero or negative
         * destination numerator makes av_rescale_rnd refuse and return INT64_MIN. */
        { "zero source numerator gives zero",         1000LL,  0, 1000, 1, 1000000, 0LL },
        { "zero destination numerator refuses",       1000LL,  1, 1000, 0, 1000000, INT64_MIN },
        { "negative source numerator refuses",        1000LL, -1, 1000, 1, 1000000, INT64_MIN },
        { "negative destination numerator refuses",   1000LL,  1, 1000, -1, 1000000, INT64_MIN },
        { "negative source denominator refuses",      1000LL,  1, -1000, 1, 1000000, INT64_MIN },
        { "negative destination denominator refuses", 1000LL,  1, 1000, 1, -1000000, INT64_MIN },
    };
    size_t i;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        int64_t got;
        kc_case("rescale_q %s", rows[i].name);
        got = ffkmp_rescale_q(rows[i].v, rows[i].sn, rows[i].sd, rows[i].dn, rows[i].dd);
        KC_EQ_I64(got, rows[i].expected);
        kc_detail("%lld in %d/%d becomes %lld in %d/%d", (long long)rows[i].v, rows[i].sn,
                  rows[i].sd, (long long)got, rows[i].dn, rows[i].dd);
    }
}

static void case_rescale_q_beats_the_naive_multiply(void)
{
    const int64_t pts = 10000000000000LL;      /* 10^13 ticks */
    const int64_t exact = 10000000000LL;       /* 10^10 microseconds */
    int64_t got;
    uint64_t product;
    int64_t wrapped;
    int64_t naive;

    kc_case("the naive 64 bit multiply overflows where rescale_q is exact");
    got = ffkmp_rescale_q(pts, 1, 1000000000, 1, 1000000);
    KC_EQ_I64(got, exact);
    /* The same value computed the way KitePlayer used to compute it: pts * 1000000 first, then
     * divide by the time base. The product is 10^19, past INT64_MAX at 9.22e18, so the Long
     * arithmetic wrapped and every timestamp after that point was wrong.
     *
     * The product is formed in uint64_t and then converted, rather than written as the signed
     * multiply it was. Signed overflow is undefined behaviour and the asan variant carries UBSan,
     * so writing the bug literally would abort this suite instead of demonstrating it. The
     * conversion of an out-of-range unsigned value is implementation defined rather than
     * undefined, and on this target it is the two's complement wrap the original bug produced. */
    product = (uint64_t)pts * 1000000u;
    wrapped = (int64_t)product;
    naive = wrapped / 1000000000LL;
    KC_CHECKF(wrapped < 0, "the product did not wrap negative: %lld", (long long)wrapped);
    KC_EQ_I64(wrapped, -8446744073709551616LL);
    KC_EQ_I64(naive, -8446744073LL);
    KC_CHECKF(naive != exact, "the naive computation agreed with the helper, so this vector is "
                              "not an overflow vector any more");
    /* And the correct answer, reached by dividing before multiplying, agrees with the helper. */
    KC_EQ_I64(got, pts / 1000);
    kc_detail("exact=%lld naive=%lld", (long long)got, (long long)naive);

    kc_case("rescale_q does not recognise AV_NOPTS_VALUE, it converts it");
    got = ffkmp_rescale_q(AV_NOPTS_VALUE, 1, 1000000000, 1, 1000000);
    KC_EQ_I64(got, -9223372036854776LL);
    KC_CHECKF(got != AV_NOPTS_VALUE,
              "AV_NOPTS_VALUE survived the rescale as itself, which would make the guard optional");
    kc_note("a caller that forgets to test for AV_NOPTS_VALUE gets a plausible timestamp, not an");
    kc_note("error, which is why the D9 fix returns null on NOPTS before calling this helper");
    kc_detail("nopts rescaled to %lld", (long long)got);
}

/* ---- ffkmp_packet_rescale_ts ---- */

static void case_packet_rescale_ts(void)
{
    AVPacket *p = ffkmp_packet_alloc();

    KC_NOT_NULL(p);

    kc_case("packet_rescale_ts moves pts, dts and duration together, 1/1e9 to us");
    ffkmp_packet_set_pts(p, 10000000000000LL);
    ffkmp_packet_set_dts(p, 9999000000000LL);
    p->duration = 1000000000LL;
    ffkmp_packet_rescale_ts(p, 1, 1000000000, 1, 1000000);
    KC_EQ_I64(ffkmp_packet_pts(p), 10000000000LL);
    KC_EQ_I64(ffkmp_packet_dts(p), 9999000000LL);
    KC_EQ_I64(ffkmp_packet_duration(p), 1000000LL);
    kc_detail("pts=%lld dts=%lld dur=%lld", (long long)ffkmp_packet_pts(p),
              (long long)ffkmp_packet_dts(p), (long long)ffkmp_packet_duration(p));

    kc_case("packet_rescale_ts leaves AV_NOPTS_VALUE alone");
    ffkmp_packet_set_pts(p, AV_NOPTS_VALUE);
    ffkmp_packet_set_dts(p, AV_NOPTS_VALUE);
    p->duration = 0;
    ffkmp_packet_rescale_ts(p, 1, 1000000000, 1, 1000000);
    KC_EQ_I64(ffkmp_packet_pts(p), AV_NOPTS_VALUE);
    KC_EQ_I64(ffkmp_packet_dts(p), AV_NOPTS_VALUE);
    KC_EQ_I64(ffkmp_packet_duration(p), 0);
    kc_note("this is the difference from ffkmp_rescale_q, which converts NOPTS into a number");

    kc_case("packet_rescale_ts substitutes 1 for a zero denominator on both sides");
    ffkmp_packet_set_pts(p, 1000);
    ffkmp_packet_set_dts(p, 1000);
    p->duration = 1000;
    ffkmp_packet_rescale_ts(p, 1, 0, 1, 0);
    KC_EQ_I64(ffkmp_packet_pts(p), 1000);
    KC_EQ_I64(ffkmp_packet_dts(p), 1000);
    KC_EQ_I64(ffkmp_packet_duration(p), 1000);

    kc_case("packet_rescale_ts on a negative timestamp keeps the sign");
    ffkmp_packet_set_pts(p, -10000000000000LL);
    ffkmp_packet_set_dts(p, -10000000000000LL);
    p->duration = 0;
    ffkmp_packet_rescale_ts(p, 1, 1000000000, 1, 1000000);
    KC_EQ_I64(ffkmp_packet_pts(p), -10000000000LL);
    KC_EQ_I64(ffkmp_packet_dts(p), -10000000000LL);

    kc_case("packet_rescale_ts on a NULL packet does nothing and returns");
    ffkmp_packet_rescale_ts(NULL, 1, 1000000000, 1, 1000000);
    kc_detail("survived");

    ffkmp_packet_free(p);
}

/* ---- The six AVRational out-parameter helpers ---- */

/* All six hand an AVRational back through an int pair, and all six substitute 1 for a zero
 * denominator. Their NULL behaviour is not uniform, and that is worth pinning down rather than
 * discovering later. Measured here, there are three shapes: ffkmp_codecctx_time_base and
 * ffkmp_codecpar_sample_aspect_ratio leave the out parameters exactly as the caller left them, the
 * three time-base and frame-rate helpers write 0/1, and ffkmp_frame_sample_aspect_ratio writes
 * 1/1. A caller that does not initialise its pair reads uninitialised memory from the first
 * shape. */
static void case_rational_null_behaviour(void)
{
    int n;
    int d;

    kc_case("codecctx_time_base(NULL) leaves the out parameters untouched");
    n = -7;
    d = -7;
    ffkmp_codecctx_time_base(NULL, &n, &d);
    KC_EQ_INT(n, -7);
    KC_EQ_INT(d, -7);
    kc_note("the caller must initialise n and d before calling this one and the codecpar one,");
    kc_note("because neither writes anything when it refuses");

    kc_case("codecpar_sample_aspect_ratio(NULL) leaves the out parameters untouched");
    n = -7;
    d = -7;
    ffkmp_codecpar_sample_aspect_ratio(NULL, &n, &d);
    KC_EQ_INT(n, -7);
    KC_EQ_INT(d, -7);

    kc_case("stream_time_base(NULL) writes 0/1");
    n = -7;
    d = -7;
    ffkmp_stream_time_base(NULL, &n, &d);
    KC_EQ_INT(n, 0);
    KC_EQ_INT(d, 1);

    kc_case("stream_avg_frame_rate(NULL) writes 0/1");
    n = -7;
    d = -7;
    ffkmp_stream_avg_frame_rate(NULL, &n, &d);
    KC_EQ_INT(n, 0);
    KC_EQ_INT(d, 1);

    kc_case("buffersink_time_base(NULL) writes 0/1");
    n = -7;
    d = -7;
    ffkmp_buffersink_time_base(NULL, &n, &d);
    KC_EQ_INT(n, 0);
    KC_EQ_INT(d, 1);

    kc_case("frame_sample_aspect_ratio(NULL) writes 1/1, not 0/1");
    n = -7;
    d = -7;
    ffkmp_frame_sample_aspect_ratio(NULL, &n, &d);
    KC_EQ_INT(n, 1);
    KC_EQ_INT(d, 1);
    kc_note("1/1 is the only sane square-pixel default, so this asymmetry is intended, not a bug");
}

static void case_codecctx_time_base(void)
{
    AVCodecContext *c = ffkmp_codecctx_alloc(NULL);
    int n = -7;
    int d = -7;

    KC_NOT_NULL(c);

    kc_case("codecctx_time_base reads 1/48000 back as 1/48000");
    c->time_base.num = 1;
    c->time_base.den = 48000;
    ffkmp_codecctx_time_base(c, &n, &d);
    KC_EQ_INT(n, 1);
    KC_EQ_INT(d, 48000);

    kc_case("codecctx_time_base substitutes 1 for a zero denominator");
    c->time_base.num = 1;
    c->time_base.den = 0;
    ffkmp_codecctx_time_base(c, &n, &d);
    KC_EQ_INT(n, 1);
    KC_EQ_INT(d, 1);
    kc_detail("1/0 read back as %d/%d", n, d);

    ffkmp_codecctx_free(c);
}

static void case_frame_sample_aspect_ratio(void)
{
    AVFrame *f = ffkmp_frame_alloc();
    int n = -7;
    int d = -7;

    KC_NOT_NULL(f);

    kc_case("frame_sample_aspect_ratio reads 4/3 back as 4/3");
    f->sample_aspect_ratio.num = 4;
    f->sample_aspect_ratio.den = 3;
    ffkmp_frame_sample_aspect_ratio(f, &n, &d);
    KC_EQ_INT(n, 4);
    KC_EQ_INT(d, 3);

    kc_case("frame_sample_aspect_ratio turns an undeclared 0/0 into 1/1");
    f->sample_aspect_ratio.num = 0;
    f->sample_aspect_ratio.den = 0;
    ffkmp_frame_sample_aspect_ratio(f, &n, &d);
    KC_EQ_INT(n, 1);
    KC_EQ_INT(d, 1);
    kc_note("this helper substitutes 1 for a zero NUMERATOR too, which the others do not");

    ffkmp_frame_free(f);
}

/* ---- The stream family, and the D9 vector applied to a real helper ---- */

static void case_stream_helpers(void)
{
    /* A container-less AVFormatContext is enough for every stream field this section reads, and
     * it avoids dragging a media fixture into an arithmetic suite. AVStream has setters for the
     * time base only, so duration and the frame rate are written through the struct, which is
     * legitimate in a test even though library code must not do it. */
    AVFormatContext *fc = avformat_alloc_context();
    AVStream *st;
    int n = -7;
    int d = -7;

    KC_NOT_NULL(fc);
    st = ffkmp_fmt_new_stream(fc, NULL);
    KC_NOT_NULL(st);

    kc_case("stream_time_base reads 1/1000000000 back exactly");
    ffkmp_stream_set_time_base(st, 1, 1000000000);
    ffkmp_stream_time_base(st, &n, &d);
    KC_EQ_INT(n, 1);
    KC_EQ_INT(d, 1000000000);

    kc_case("stream_duration_micros: D9's 1e13 ticks at 1/1e9 is 1e10 us");
    st->duration = 10000000000000LL;
    KC_EQ_I64(ffkmp_stream_duration_micros(st), 10000000000LL);
    kc_detail("micros=%lld", (long long)ffkmp_stream_duration_micros(st));

    kc_case("stream_duration_micros: 90 kHz, 90000 ticks is one second");
    ffkmp_stream_set_time_base(st, 1, 90000);
    st->duration = 90000;
    KC_EQ_I64(ffkmp_stream_duration_micros(st), 1000000LL);

    {
        struct row {
            const char *name;
            int64_t duration;
        };
        struct row rows[] = {
            { "AV_NOPTS_VALUE", INT64_MIN },
            { "zero",           0 },
            { "negative",       -5 },
        };
        size_t i;
        for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
            kc_case("stream_duration_micros reports -1 for a duration of %s", rows[i].name);
            st->duration = rows[i].duration;
            KC_EQ_I64(ffkmp_stream_duration_micros(st), -1);
        }
    }

    kc_case("stream_duration_micros reports -1 for a NULL stream");
    KC_EQ_I64(ffkmp_stream_duration_micros(NULL), -1);

    kc_case("stream_time_base substitutes 1 for a zero denominator");
    ffkmp_stream_set_time_base(st, 1, 0);
    ffkmp_stream_time_base(st, &n, &d);
    KC_EQ_INT(n, 1);
    KC_EQ_INT(d, 1);

    kc_case("stream_avg_frame_rate reads 30000/1001 back exactly");
    st->avg_frame_rate.num = 30000;
    st->avg_frame_rate.den = 1001;
    ffkmp_stream_avg_frame_rate(st, &n, &d);
    KC_EQ_INT(n, 30000);
    KC_EQ_INT(d, 1001);

    kc_case("stream_avg_frame_rate substitutes 1 for a zero denominator");
    st->avg_frame_rate.num = 25;
    st->avg_frame_rate.den = 0;
    ffkmp_stream_avg_frame_rate(st, &n, &d);
    KC_EQ_INT(n, 25);
    KC_EQ_INT(d, 1);

    /* The sixth member of the out-parameter family, reached through the stream's own parameters so
     * the case needs no extra fixture. */
    {
        AVCodecParameters *par = ffkmp_stream_codecpar(st);

        KC_NOT_NULL(par);

        kc_case("codecpar_sample_aspect_ratio reads 40/33 back as 40/33");
        par->sample_aspect_ratio.num = 40;
        par->sample_aspect_ratio.den = 33;
        n = -7;
        d = -7;
        ffkmp_codecpar_sample_aspect_ratio(par, &n, &d);
        KC_EQ_INT(n, 40);
        KC_EQ_INT(d, 33);

        kc_case("codecpar_sample_aspect_ratio substitutes 1 for a zero denominator only");
        par->sample_aspect_ratio.num = 0;
        par->sample_aspect_ratio.den = 0;
        ffkmp_codecpar_sample_aspect_ratio(par, &n, &d);
        KC_EQ_INT(n, 0);
        KC_EQ_INT(d, 1);
        kc_note("0/1 here where ffkmp_frame_sample_aspect_ratio answers 1/1 for the same input.");
        kc_note("Two helpers for the same quantity, two different undeclared values, so a caller");
        kc_note("comparing one against the other has to normalise first.");
    }

    avformat_free_context(fc);
}

static void case_fmt_seek_micros_guard(void)
{
    kc_case("fmt_seek_micros refuses a NULL context with AVERROR(EINVAL)");
    KC_EQ_INT(ffkmp_fmt_seek_micros(NULL, 0, 1000000), AVERROR(EINVAL));
    kc_detail("rc=%d", ffkmp_fmt_seek_micros(NULL, 0, 1000000));
    kc_note("its 128 bit rescale of micros into the stream time base is not observable from here:");
    kc_note("the value feeds av_seek_frame, so proving it needs a demuxable container, which");
    kc_note("belongs to the suite that owns demuxing. Two hazards measured in a child process");
    kc_note("while writing this file, recorded and not asserted: a stream_index at or past");
    kc_note("nb_streams indexes ctx->streams out of bounds with no check, and a hand built");
    kc_note("context with no iformat faults inside av_seek_frame. Both are caller discipline");
    kc_note("today rather than helper guarantees.");
}

/* ---- ffkmp_buffersink_time_base, read from a real graph ---- */

static void case_buffersink_time_base(void)
{
    struct row {
        const char *description;
        int expect_n;
        int expect_d;
        const char *why;
    };
    /* The helper exists because a filter can change the time base on the way through, so the
     * consumer must read it from the sink instead of assuming the input one. The fps row is that
     * claim under test: the graph is fed 1/90000 and the sink reports 1/25. */
    struct row rows[] = {
        { "null",        1, 90000, "a pass-through graph keeps the input time base" },
        { "setpts=PTS",  1, 90000, "setpts rewrites timestamps but not the time base" },
        { "fps=25",      1, 25,    "fps really does change it, which is why this helper exists" },
    };
    size_t i;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        AVFilterGraph *graph = NULL;
        AVFilterContext *src = NULL;
        AVFilterContext *sink = NULL;
        int n = -7;
        int d = -7;
        int rc;

        kc_case("buffersink_time_base after '%s': %s", rows[i].description, rows[i].why);
        rc = ffkmp_graph_build_video(&graph, &src, &sink, rows[i].description,
                                     320, 240, AV_PIX_FMT_YUV420P,
                                     1, 90000, 25, 1, 1, 1);
        KC_EQ_INT(rc, 0);
        KC_NOT_NULL(sink);
        ffkmp_buffersink_time_base(sink, &n, &d);
        KC_EQ_INT(n, rows[i].expect_n);
        KC_EQ_INT(d, rows[i].expect_d);
        kc_detail("tb=%d/%d", n, d);
        ffkmp_graph_free(&graph);
        KC_NULL(graph);
    }
}

/* ---- ffkmp_frame_plane_height and AV_CEIL_RSHIFT ---- */

/* Ceiling division by a power of two, written out so the case has an oracle that does not come
 * from the header being tested. AV_CEIL_RSHIFT(a, b) is ((a) + (1 << (b)) - 1) >> (b). */
static int ceil_rshift(int value, int shift)
{
    return (value + (1 << shift) - 1) >> shift;
}

static void case_plane_heights(void)
{
    struct fmt_row {
        const char *name;
        int fmt;
        int log2_chroma_h;
        int planes;
    };
    /* log2_chroma_h and the plane count are asserted against the descriptor rather than trusted,
     * so a format whose subsampling changes in a future FFmpeg fails here instead of silently
     * changing what the rest of the row means. */
    struct fmt_row fmts[] = {
        { "yuv420p", AV_PIX_FMT_YUV420P, 1, 3 },
        { "yuv422p", AV_PIX_FMT_YUV422P, 0, 3 },
        { "yuv444p", AV_PIX_FMT_YUV444P, 0, 3 },
        { "yuv410p", AV_PIX_FMT_YUV410P, 2, 3 },
        { "nv12",    AV_PIX_FMT_NV12,    1, 2 },
        { "p010le",  AV_PIX_FMT_P010LE,  1, 2 },
        { "rgba",    AV_PIX_FMT_RGBA,    0, 1 },
    };
    int heights[] = { 1, 2, 3, 5, 1080, 1081 };
    AVFrame *f = ffkmp_frame_alloc();
    size_t i;
    size_t h;

    KC_NOT_NULL(f);
    ffkmp_frame_set_width(f, 1920);

    for (i = 0; i < sizeof(fmts) / sizeof(fmts[0]); i++) {
        const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get((enum AVPixelFormat)fmts[i].fmt);

        kc_case("%s: the descriptor still says log2_chroma_h=%d and %d planes", fmts[i].name,
                fmts[i].log2_chroma_h, fmts[i].planes);
        KC_NOT_NULL(desc);
        KC_EQ_INT(desc->log2_chroma_h, fmts[i].log2_chroma_h);
        KC_EQ_INT(av_pix_fmt_count_planes((enum AVPixelFormat)fmts[i].fmt), fmts[i].planes);

        ffkmp_frame_set_format(f, fmts[i].fmt);
        for (h = 0; h < sizeof(heights) / sizeof(heights[0]); h++) {
            int height = heights[h];
            int expect_chroma = ceil_rshift(height, fmts[i].log2_chroma_h);
            int truncating = height >> fmts[i].log2_chroma_h;

            kc_case("%s at height %d: luma %d, chroma %d", fmts[i].name, height, height,
                    expect_chroma);
            ffkmp_frame_set_height(f, height);
            KC_EQ_INT(ffkmp_frame_plane_height(f, 0), height);
            KC_EQ_INT(ffkmp_frame_plane_height(f, 1), expect_chroma);
            KC_EQ_INT(ffkmp_frame_plane_height(f, 2), expect_chroma);
            if (truncating != expect_chroma) {
                kc_detail("a plain shift would say %d, AV_CEIL_RSHIFT says %d", truncating,
                          expect_chroma);
            } else {
                kc_detail("chroma=%d", expect_chroma);
            }
        }
    }

    /* The rows the plan names: a subsampled format at an odd height is where AV_CEIL_RSHIFT and a
     * plain shift part company, and dropping the last chroma row is a visible corruption. */
    kc_case("yuv420p at 1081 rows gives 541 chroma rows, not the 540 a shift would give");
    ffkmp_frame_set_format(f, AV_PIX_FMT_YUV420P);
    ffkmp_frame_set_height(f, 1081);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 1), 541);
    KC_EQ_INT(1081 >> 1, 540);

    kc_case("yuv410p at 3 rows gives 1 chroma row, where a shift by 2 would give 0");
    ffkmp_frame_set_format(f, AV_PIX_FMT_YUV410P);
    ffkmp_frame_set_height(f, 3);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 1), 1);
    KC_EQ_INT(3 >> 2, 0);
    kc_note("a zero-row plane is the failure this helper exists to prevent");

    kc_case("plane_height answers for planes the format does not have");
    ffkmp_frame_set_format(f, AV_PIX_FMT_RGBA);
    ffkmp_frame_set_height(f, 1081);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 1), 1081);
    ffkmp_frame_set_format(f, AV_PIX_FMT_NV12);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 2), 541);
    kc_note("rgba has one plane and nv12 has two, so both answers are for planes that do not");
    kc_note("exist. The bound is ffkmp_frame_plane_count, and a caller that skips it gets a");
    kc_note("number rather than a refusal.");

    kc_case("plane_height refuses a negative plane index but not a large one");
    ffkmp_frame_set_format(f, AV_PIX_FMT_YUV420P);
    KC_EQ_INT(ffkmp_frame_plane_height(f, -1), 0);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 8), 1081);
    kc_note("index 8 is past AV_NUM_DATA_POINTERS and still answers with the frame height. No");
    kc_note("memory is touched, so this is a misleading answer and not a hazard.");

    kc_case("plane_height reports 0 for an unknown pixel format");
    ffkmp_frame_set_format(f, AV_PIX_FMT_NONE);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 0), 0);

    kc_case("plane_height reports 0 for a frame with no width, which is how audio arrives");
    ffkmp_frame_set_format(f, AV_PIX_FMT_YUV420P);
    ffkmp_frame_set_width(f, 0);
    KC_EQ_INT(ffkmp_frame_plane_height(f, 0), 0);
    kc_note("on an audio frame `format` holds a sample format, so reading it as a pixel format");
    kc_note("would answer with someone else's subsampling");

    kc_case("plane_height reports 0 for a NULL frame");
    KC_EQ_INT(ffkmp_frame_plane_height(NULL, 0), 0);

    ffkmp_frame_free(f);
}

int main(void)
{
    kc_suite_begin("test_rescale");

    kc_case("the helper set was enumerated here: 13 helpers, not the plan's ten");
    kc_detail("2 macro, 4 with 128 bit intermediates, 6 AVRational out params, 1 AV_CEIL_RSHIFT");

    case_macro_crossings();
    case_rescale_q_vectors();
    case_rescale_q_beats_the_naive_multiply();
    case_packet_rescale_ts();
    case_rational_null_behaviour();
    case_codecctx_time_base();
    case_frame_sample_aspect_ratio();
    case_stream_helpers();
    case_fmt_seek_micros_guard();
    case_buffersink_time_base();
    case_plane_heights();

    return kc_suite_end();
}
