/* The hardware decode funnels.
 *
 * VideoToolbox and D3D11VA are HWACCELs behind FFmpeg's ordinary `h264`/`hevc` decoders, not
 * named decoders the way `h264_mediacodec` is. That difference decides this file's shape: there
 * is no decoder name to select, only a device context to attach to a codec context between
 * allocation and open, and a format negotiation to answer when the decoder offers hardware
 * output. Both live here as portable C. FFmpeg's headers declare every hwdevice type
 * unconditionally and FFmpeg lists the types a build carries at run time, so capability is
 * FFmpeg's runtime answer rather than this file's preprocessor guess, exactly the capability
 * honesty rule the Kotlin surface already follows. */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixfmt.h>

/* The format negotiation. The decoder calls this with every output format it can produce, and
   the answer is the device's own format when offered. When it is NOT offered, which happens
   mid-stream when the hardware refuses a profile or a resolution change, or from the start for a
   codec the device has no hwaccel for, the default negotiation takes over and decoding continues
   in software; the Kotlin side notices the downgrade on the frames themselves
   (ffkmp_frame_is_hardware turns false), which is what a player's fallback report reads. */
static enum AVPixelFormat ffkmp_pick_format_(
        AVCodecContext *ctx, const enum AVPixelFormat *formats, enum AVPixelFormat wanted) {
    const enum AVPixelFormat *candidate;
    for (candidate = formats; *candidate != AV_PIX_FMT_NONE; candidate++) {
        if (*candidate == wanted) return *candidate;
    }
    return avcodec_default_get_format(ctx, formats);
}

static enum AVPixelFormat ffkmp_pick_videotoolbox_format_(
        AVCodecContext *ctx, const enum AVPixelFormat *formats) {
    return ffkmp_pick_format_(ctx, formats, AV_PIX_FMT_VIDEOTOOLBOX);
}

/* AV_PIX_FMT_D3D11 is the frame format of FFmpeg's d3d11va2 hwaccels, the ones that allocate
   their own surfaces from the attached device. The older d3d11va variants want a caller-built
   decoder context and are never picked here. */
static enum AVPixelFormat ffkmp_pick_d3d11_format_(
        AVCodecContext *ctx, const enum AVPixelFormat *formats) {
    return ffkmp_pick_format_(ctx, formats, AV_PIX_FMT_D3D11);
}

/* Whether this FFmpeg build carries the device type at all. */
static int ffkmp_device_type_built_(enum AVHWDeviceType type) {
    enum AVHWDeviceType built = AV_HWDEVICE_TYPE_NONE;
    while ((built = av_hwdevice_iterate_types(built)) != AV_HWDEVICE_TYPE_NONE) {
        if (built == type) return 1;
    }
    return 0;
}

/* Attaches a device context of [type] to an allocated, not yet opened codec context and installs
   [pick] as its format negotiation. A repeated call replaces the previous device context rather
   than leaking it. A build without the device type answers AVERROR(ENOSYS). The check is ours:
   av_hwdevice_ctx_create answers AVERROR(ENOMEM) for a type the build does not carry, because
   its allocator returns NULL for that and for a failed allocation alike. */
static int ffkmp_attach_device_(
        AVCodecContext *c, enum AVHWDeviceType type,
        enum AVPixelFormat (*pick)(AVCodecContext *, const enum AVPixelFormat *)) {
    AVBufferRef *device = NULL;
    int rc;
    if (!c) return AVERROR(EINVAL);
    if (!ffkmp_device_type_built_(type)) return AVERROR(ENOSYS);
    rc = av_hwdevice_ctx_create(&device, type, NULL, NULL, 0);
    if (rc < 0) return rc;
    av_buffer_unref(&c->hw_device_ctx);
    c->hw_device_ctx = device;
    c->get_format = pick;
    return 0;
}

/* Attaches a VideoToolbox device context. Call between ffkmp_codecctx_alloc and
   ffkmp_codecctx_open, the same window the pre-open option funnel uses. Returns 0 or FFmpeg's
   own error: a build without VideoToolbox answers AVERROR(ENOSYS) here and the caller keeps its
   typed refusal. */
KC_API int ffkmp_codecctx_use_videotoolbox(AVCodecContext *c) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    return ffkmp_attach_device_(c, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, ffkmp_pick_videotoolbox_format_);
}

/* The Windows twin of ffkmp_codecctx_use_videotoolbox: a Direct3D 11 device on the default
   adapter, in the same pre-open window. Every build but Windows answers AVERROR(ENOSYS), and a
   Windows machine whose adapter offers no video decoding answers with the device error. */
KC_API int ffkmp_codecctx_use_d3d11va(AVCodecContext *c) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    return ffkmp_attach_device_(c, AV_HWDEVICE_TYPE_D3D11VA, ffkmp_pick_d3d11_format_);
}

/* The software download a fallback path pays for. Copies a hardware frame's pixels into
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
