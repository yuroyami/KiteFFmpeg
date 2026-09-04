/* The two accessors K2 added: a codec parameter set's field order, and a container's bit rate.
 *
 * Why a C suite for two one-line readers. The field order one is not a read, it is a MAPPING: five
 * AVFieldOrder values collapse onto four codes, because TT and TB both present the top field first
 * and BB and BT both present the bottom one. A mapping is exactly the kind of code that passes
 * every test written against a real file and is still wrong, because a synthesised fixture only
 * ever exercises one of its rows. Setting the field by hand reaches all five.
 *
 * That matters more than usual here. The Kotlin contract test for this feature could not produce
 * an interlaced fixture at all: the mpeg4 encoder in this build rejects the `top` option outright,
 * and with `+ilme+ildct` alone the order does not survive into the container's codec parameters.
 * So the only positive evidence that an interlaced file reads as interlaced is right here.
 *
 * The bit rate half is a plain read and gets a plain check: what goes in comes out, and a NULL
 * context answers zero instead of dereferencing.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

/* ---- ffkmp_codecpar_field_order ---- */

static void case_field_order_maps_every_av_value(void)
{
    struct row {
        const char *name;
        enum AVFieldOrder input;
        int32_t expected;
    };
    /* All five AVFieldOrder values plus UNKNOWN. The expectation is the DISPLAY order, which is
     * what a deinterlacer needs; the coded order the second letter records is deliberately lost. */
    struct row rows[] = {
        { "AV_FIELD_UNKNOWN",     AV_FIELD_UNKNOWN,     0 },
        { "AV_FIELD_PROGRESSIVE", AV_FIELD_PROGRESSIVE, 1 },
        { "AV_FIELD_TT",          AV_FIELD_TT,          2 },
        { "AV_FIELD_TB",          AV_FIELD_TB,          2 },
        { "AV_FIELD_BB",          AV_FIELD_BB,          3 },
        { "AV_FIELD_BT",          AV_FIELD_BT,          3 },
    };
    AVCodecParameters *par = avcodec_parameters_alloc();
    size_t i;

    KC_CHECKF(par != NULL, "avcodec_parameters_alloc returned NULL");

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        int32_t got;
        par->field_order = rows[i].input;
        got = ffkmp_codecpar_field_order(par);
        kc_case("%s maps to %d", rows[i].name, (int)rows[i].expected);
        KC_EQ_INT((int)got, (int)rows[i].expected);
    }

    /* The pairs collapse, which is the whole point of the mapping and the part a per-value
     * comparison would still pass while getting backwards. */
    kc_case("the top pair and the bottom pair each answer as one");
    par->field_order = AV_FIELD_TT;
    KC_EQ_INT((int)ffkmp_codecpar_field_order(par), 2);
    par->field_order = AV_FIELD_TB;
    KC_EQ_INT((int)ffkmp_codecpar_field_order(par), 2);
    par->field_order = AV_FIELD_BB;
    KC_EQ_INT((int)ffkmp_codecpar_field_order(par), 3);
    par->field_order = AV_FIELD_BT;
    KC_EQ_INT((int)ffkmp_codecpar_field_order(par), 3);

    kc_case("top first and bottom first are not the same answer");
    par->field_order = AV_FIELD_TT;
    {
        int32_t top = ffkmp_codecpar_field_order(par);
        par->field_order = AV_FIELD_BB;
        KC_CHECKF(top != ffkmp_codecpar_field_order(par),
                  "top and bottom field order both answered %d", (int)top);
    }

    avcodec_parameters_free(&par);
}

static void case_field_order_null_is_unknown(void)
{
    kc_case("a NULL parameter set answers unknown rather than dereferencing");
    KC_EQ_INT((int)ffkmp_codecpar_field_order(NULL), 0);
}

/* ---- ffkmp_fmt_bit_rate ---- */

static void case_bit_rate_round_trips(void)
{
    AVFormatContext *ctx = avformat_alloc_context();

    KC_CHECKF(ctx != NULL, "avformat_alloc_context returned NULL");

    kc_case("a fresh context reports no bit rate");
    KC_CHECK(ffkmp_fmt_bit_rate(ctx) == 0);

    kc_case("what the demuxer wrote is what comes back");
    ctx->bit_rate = 1234567;
    KC_CHECK(ffkmp_fmt_bit_rate(ctx) == 1234567);
    kc_detail("bit_rate=%lld", (long long)ffkmp_fmt_bit_rate(ctx));

    /* A container can legitimately exceed 32 bits: a UHD intermediate is tens of megabits and a
     * raw one is well past four billion. The helper returns int64_t, and this is the case that
     * fails if it is ever narrowed. */
    kc_case("a rate past 32 bits survives the crossing");
    ctx->bit_rate = 5000000000LL;
    KC_CHECK(ffkmp_fmt_bit_rate(ctx) == 5000000000LL);
    kc_detail("bit_rate=%lld", (long long)ffkmp_fmt_bit_rate(ctx));

    avformat_free_context(ctx);
}

static void case_bit_rate_null_is_zero(void)
{
    kc_case("a NULL context answers zero rather than dereferencing");
    KC_CHECK(ffkmp_fmt_bit_rate(NULL) == 0);
}

int main(void)
{
    kc_suite_begin("test_field_order");

    case_field_order_maps_every_av_value();
    case_field_order_null_is_unknown();
    case_bit_rate_round_trips();
    case_bit_rate_null_is_zero();

    return kc_suite_end();
}
