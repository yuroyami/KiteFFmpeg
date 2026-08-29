/* The hardware decode funnels of KiteFFmpeg window 3 (KPKMP 17.4.8, S2.a).
 *
 * VideoToolbox is an HWACCEL behind FFmpeg's ordinary `h264`/`hevc` decoders, not a named
 * decoder the way `h264_mediacodec` is. That difference decides this file's shape: there is no
 * decoder name to select, only a device context to attach to a codec context between allocation
 * and open, and a format negotiation to answer when the decoder offers hardware output. Both
 * live here as portable C. FFmpeg's headers declare every hwdevice type unconditionally and
 * `av_hwdevice_ctx_create` answers AVERROR(ENOSYS) on a build that does not carry the type, so
 * capability is FFmpeg's runtime answer rather than this file's preprocessor guess, exactly the
 * capability honesty rule (D-5) the Kotlin surface already follows. */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixfmt.h>

/* The format negotiation. The decoder calls this with every output format it can produce, and
   the answer is VideoToolbox when offered. When it is NOT offered, which happens mid-stream when
   the hardware refuses a profile or a resolution change, the default negotiation takes over and
   decoding continues in software; the Kotlin side notices the downgrade on the frames themselves
   (ffkmp_frame_is_hardware turns false), which is what D-2's fallback reporting reads. */
static enum AVPixelFormat ffkmp_pick_videotoolbox_format_(
        AVCodecContext *ctx, const enum AVPixelFormat *formats) {
    const enum AVPixelFormat *candidate;
    for (candidate = formats; *candidate != AV_PIX_FMT_NONE; candidate++) {
        if (*candidate == AV_PIX_FMT_VIDEOTOOLBOX) return *candidate;
    }
    return avcodec_default_get_format(ctx, formats);
}

/* Attaches a VideoToolbox device context to an allocated, not yet opened codec context and
   installs the negotiation above. Call between ffkmp_codecctx_alloc and ffkmp_codecctx_open,
   the same window the pre-open option funnel uses. Returns 0 or FFmpeg's own error: a build
   without VideoToolbox answers AVERROR(ENOSYS) here and the caller keeps its typed refusal.
   A repeated call replaces the previous device context rather than leaking it. */
KC_API int ffkmp_codecctx_use_videotoolbox(AVCodecContext *c) {
    AVBufferRef *device = NULL;
    int rc;
    if (!c) return AVERROR(EINVAL);
    rc = av_hwdevice_ctx_create(&device, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, NULL, NULL, 0);
    if (rc < 0) return rc;
    av_buffer_unref(&c->hw_device_ctx);
    c->hw_device_ctx = device;
    c->get_format = ffkmp_pick_videotoolbox_format_;
    return 0;
}

/* The measured software download of D-2's fallback path. Copies a hardware frame's pixels into
   dst, which must be a blank allocated frame, and carries the presentation properties (pts,
   colour, rotation side data) with them, because a downloaded frame that forgot its timestamp
   would be worse than no frame. dst is left blank again when the copy fails, so ownership stays
   single: the caller frees both frames exactly as it allocated them. A src that is not a
   hardware frame is refused rather than copied, because the caller reaching this function on a
   software frame means its is-hardware bookkeeping is wrong and copying would hide that. */
KC_API int ffkmp_frame_hw_download(AVFrame *src, AVFrame *dst) {
    int rc;
    if (!src || !dst) return AVERROR(EINVAL);
    if (!src->hw_frames_ctx) return AVERROR(EINVAL);
    rc = av_hwframe_transfer_data(dst, src, 0);
    if (rc < 0) return rc;
    rc = av_frame_copy_props(dst, src);
    if (rc < 0) {
        av_frame_unref(dst);
        return rc;
    }
    return 0;
}
