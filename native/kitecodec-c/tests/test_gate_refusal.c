/* Every constructor helper refuses when the identity gate rejects the linked FFmpeg.
 *
 * Until this suite the gate was enforced by the Kotlin call sites alone: seventeen of them call
 * requireCompatibleFFmpeg before a factory runs. The C entry points are exported (KC_API is
 * default visibility), so a pure C or JNI consumer reached FFmpeg with no gate at all, and the
 * header's own claim that "every KiteFFmpeg entry point calls this FIRST" was false for all of
 * them. Each constructor asks now, and this suite is what says so.
 *
 * The verdict is computed once per process from the FFmpeg this binary linked, and a host build
 * links a compatible one by construction, so there is no way to reach the refusal without a seam.
 * kc_test_force_gate_status is that seam, KC_TESTING only.
 *
 * What is asserted is the REFUSAL, in each helper's own failure vocabulary: a pointer-returning
 * constructor answers NULL, an int-returning one answers AVERROR_EXTERNAL. Not AVERROR(EINVAL),
 * which already means "you passed something wrong", and not a kc_status, whose values collide with
 * the errno-based AVERRORs (KC_STATUS_MAJOR_MISMATCH is -1, which is also AVERROR(EPERM)). The
 * reason for the refusal is not in the return code by design; it is in kc_ffmpeg_report_get, which
 * a caller reads once rather than decoding from every call.
 */

#include "harness.h"

#include "kitecodec_helpers.h"
#include "kitecodec_abi.h"
#include "kc_test_seams.h"

#include <libavutil/error.h>

#include <stdlib.h>
#include <string.h>

int main(void)
{
    kc_suite_begin("test_gate_refusal");

    kc_case("the gate accepts this build, so the seam is what makes a refusal reachable");
    KC_EQ_INT(kc_init(), KC_STATUS_OK);

    kc_case("a rejected gate refuses every pointer-returning constructor");
    {
        kc_test_force_gate_status(1, KC_STATUS_MAJOR_MISMATCH);
        KC_NULL(ffkmp_frame_alloc());
        KC_NULL(ffkmp_packet_alloc());
        KC_NULL(ffkmp_codecctx_alloc(NULL));
        KC_NULL(ffkmp_find_encoder_by_name("aac"));
        KC_NULL(ffkmp_find_decoder_by_name("h264"));
        KC_NULL(ffkmp_find_decoder_by_id(27));
        KC_NULL(ffkmp_fmt_new_stream(NULL, NULL));
        kc_test_force_gate_status(0, KC_STATUS_OK);
        kc_detail("frame, packet, codec context, three lookups, stream: all NULL");
    }

    kc_case("a rejected gate refuses every int-returning constructor with AVERROR_EXTERNAL");
    {
        kc_fmt_ctx *ctx = NULL;
        kc_test_force_gate_status(1, KC_STATUS_CONFIGURATION_MISMATCH);
        KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, "kc_gate_refusal.out", "mp4"), AVERROR_EXTERNAL);
        KC_NULL(ctx);
        KC_EQ_INT(ffkmp_fmt_open_input(&ctx, "kc_gate_refusal.in"), AVERROR_EXTERNAL);
        KC_NULL(ctx);
        KC_EQ_INT(ffkmp_codecctx_open(NULL, NULL), AVERROR_EXTERNAL);
        KC_EQ_INT(ffkmp_fmt_io_open(NULL, "kc_gate_refusal.out"), AVERROR_EXTERNAL);
        kc_test_force_gate_status(0, KC_STATUS_OK);
        kc_detail("output alloc, input open, codec open, io open: all AVERROR_EXTERNAL");
    }

    kc_case("a refusal is distinguishable from a bad argument");
    {
        /* The whole reason the code is not a kc_status: a caller must be able to tell "this
         * library will not run against your FFmpeg" from "you passed me a null". */
        kc_fmt_ctx *ctx = NULL;
        KC_EQ_INT(ffkmp_fmt_alloc_output2(NULL, "x", "mp4"), AVERROR(EINVAL));
        kc_test_force_gate_status(1, KC_STATUS_RUNTIME_OLDER);
        KC_EQ_INT(ffkmp_fmt_alloc_output2(NULL, "x", "mp4"), AVERROR_EXTERNAL);
        kc_test_force_gate_status(0, KC_STATUS_OK);
        KC_EQ_INT(ffkmp_fmt_alloc_output2(&ctx, "kc_gate_refusal.out", "mp4"), 0);
        KC_NOT_NULL(ctx);
        ffkmp_fmt_free_output(&ctx);
        KC_NULL(ctx);
        kc_detail("EINVAL for a null out, EXTERNAL for a shut gate, 0 when both are fine");
    }

    kc_case("clearing the force restores the real verdict and the helpers work again");
    {
        kc_frame *frame = ffkmp_frame_alloc();
        kc_packet *packet = ffkmp_packet_alloc();
        KC_NOT_NULL(frame);
        KC_NOT_NULL(packet);
        ffkmp_frame_free(frame);
        ffkmp_packet_free(packet);
        KC_EQ_INT(kc_init(), KC_STATUS_OK);
        kc_detail("force cleared, allocations succeed");
    }

    return kc_suite_end();
}
