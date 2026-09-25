/* The clockwise rotation a renderer applies, read from a stream's display matrix.
 *
 * The case that matters is the all-zero matrix the demux fuzz target found. Its angle is not a
 * number, and converting that to an int is undefined behaviour: arm64 answers 0 and x86-64
 * answers whatever the register held, so one file played upright on one machine and turned on
 * another. On arm64 the unfixed code already answers 0, so the plain variant cannot see the
 * defect. The asan variant can, because its undefined-behaviour checks stop on the conversion.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/display.h>

/* A new stream on ctx whose coded side data carries the given nine-entry display matrix. */
static AVStream *stream_with_matrix(AVFormatContext *ctx, const int32_t matrix[9])
{
    AVStream *st = avformat_new_stream(ctx, NULL);
    AVPacketSideData *sd;

    KC_CHECKF(st != NULL, "avformat_new_stream returned NULL");
    sd = av_packet_side_data_new(&st->codecpar->coded_side_data,
                                 &st->codecpar->nb_coded_side_data,
                                 AV_PKT_DATA_DISPLAYMATRIX, 9 * sizeof(int32_t), 0);
    KC_CHECKF(sd != NULL, "av_packet_side_data_new returned NULL");
    memcpy(sd->data, matrix, 9 * sizeof(int32_t));
    return st;
}

static void case_no_matrix_is_upright(void)
{
    AVFormatContext *ctx = avformat_alloc_context();
    AVStream *st;

    kc_case("a stream without a display matrix answers 0");
    KC_CHECKF(ctx != NULL, "avformat_alloc_context returned NULL");
    st = avformat_new_stream(ctx, NULL);
    KC_CHECKF(st != NULL, "avformat_new_stream returned NULL");
    KC_EQ_INT(ffkmp_stream_rotation_degrees(st), 0);
    avformat_free_context(ctx);
}

static void case_all_zero_matrix_is_upright(void)
{
    AVFormatContext *ctx = avformat_alloc_context();
    const int32_t zeros[9] = { 0 };
    AVStream *st;
    int deg;

    kc_case("an all-zero display matrix answers 0 on every architecture");
    KC_CHECKF(ctx != NULL, "avformat_alloc_context returned NULL");
    st = stream_with_matrix(ctx, zeros);
    deg = ffkmp_stream_rotation_degrees(st);
    kc_detail("deg=%d", deg);
    KC_EQ_INT(deg, 0);
    avformat_free_context(ctx);
}

static void case_quarter_turns(void)
{
    struct row {
        double clockwise;
        int expected;
    };
    /* av_display_rotation_set takes a clockwise angle, and the helper reports clockwise in
     * 0 to 359, so a quarter turn back reads as three quarters forward. */
    const struct row rows[] = {
        { 0.0, 0 },
        { 90.0, 90 },
        { 180.0, 180 },
        { -90.0, 270 },
    };
    size_t i;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        AVFormatContext *ctx = avformat_alloc_context();
        int32_t matrix[9];
        AVStream *st;

        kc_case("a matrix set to %.0f degrees clockwise reads as %d",
                rows[i].clockwise, rows[i].expected);
        KC_CHECKF(ctx != NULL, "avformat_alloc_context returned NULL");
        av_display_rotation_set(matrix, rows[i].clockwise);
        st = stream_with_matrix(ctx, matrix);
        KC_EQ_INT(ffkmp_stream_rotation_degrees(st), rows[i].expected);
        avformat_free_context(ctx);
    }
}

static void case_null_stream_is_upright(void)
{
    kc_case("a NULL stream answers 0 rather than dereferencing");
    KC_EQ_INT(ffkmp_stream_rotation_degrees(NULL), 0);
}

int main(void)
{
    kc_suite_begin("test_rotation");

    case_no_matrix_is_upright();
    case_all_zero_matrix_is_upright();
    case_quarter_turns();
    case_null_stream_is_upright();

    return kc_suite_end();
}
