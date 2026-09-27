/* ffkmp_frame_a53_cc, the reader for the closed captions a decoder attaches to a video frame. */

#include "harness.h"

#include <string.h>

#include "kitecodec_helpers.h"

#include <libavutil/frame.h>

static const uint8_t CC_DATA[] = { 0xFC, 0x94, 0x2C, 0xFD, 0x80, 0x80 };

/* A frame carrying CC_DATA as its A/53 side data, as a decoder attaches it. */
static AVFrame *frame_with_captions(void)
{
    AVFrame *frame = av_frame_alloc();
    AVFrameSideData *sd;
    if (frame == NULL) return NULL;
    sd = av_frame_new_side_data(frame, AV_FRAME_DATA_A53_CC, sizeof CC_DATA);
    if (sd == NULL) {
        av_frame_free(&frame);
        return NULL;
    }
    memcpy(sd->data, CC_DATA, sizeof CC_DATA);
    return frame;
}

static void case_query_and_copy(void)
{
    uint8_t out[16];
    AVFrame *frame;
    kc_case("the byte count comes back without a buffer, and the bytes with one");
    frame = frame_with_captions();
    KC_NOT_NULL(frame);
    KC_EQ_INT(ffkmp_frame_a53_cc(frame, NULL, 0), (int)sizeof CC_DATA);
    memset(out, 0x5A, sizeof out);
    KC_EQ_INT(ffkmp_frame_a53_cc(frame, out, (int)sizeof out), (int)sizeof CC_DATA);
    KC_EQ_MEM(out, CC_DATA, sizeof CC_DATA);
    KC_EQ_INT(out[sizeof CC_DATA], 0x5A);
    av_frame_free(&frame);
}

static void case_short_buffer(void)
{
    uint8_t out[5];
    AVFrame *frame;
    kc_case("a buffer one byte short is refused and left as it was");
    frame = frame_with_captions();
    KC_NOT_NULL(frame);
    memset(out, 0x5A, sizeof out);
    KC_EQ_INT(ffkmp_frame_a53_cc(frame, out, (int)sizeof out), AVERROR(EINVAL));
    KC_EQ_INT(out[0], 0x5A);
    av_frame_free(&frame);
}

static void case_none(void)
{
    uint8_t out[4] = { 0 };
    AVFrame *frame = av_frame_alloc();
    kc_case("a frame without captions answers 0, and a NULL frame is refused");
    KC_NOT_NULL(frame);
    KC_EQ_INT(ffkmp_frame_a53_cc(frame, NULL, 0), 0);
    KC_EQ_INT(ffkmp_frame_a53_cc(frame, out, (int)sizeof out), 0);
    KC_EQ_INT(ffkmp_frame_a53_cc(NULL, NULL, 0), AVERROR(EINVAL));
    av_frame_free(&frame);
}

int main(void)
{
    kc_suite_begin("test_captions");

    case_query_and_copy();
    case_short_buffer();
    case_none();

    return kc_suite_end();
}
