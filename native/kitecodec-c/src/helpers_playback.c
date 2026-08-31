/* Ordinary maintained source since the interlude. Lifted at B1.3 from the def body of
 * kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in PLANNING.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The playback part of the FFmpeg helper layer: the def's 'Playback additions' section(s). */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavutil/channel_layout.h>
#include <libavutil/common.h>
#include <libavutil/display.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>

/* ════════════ Playback additions ════════════
   Everything below exists for KitePlayer. A batch transcoder reads a file once, front to back, and
   needs none of it. A player needs to drive demuxing and decoding as separate stages, to flush a
   decoder after a seek, to know a frame's colour metadata before it can draw it correctly, and to
   reach a frame's pixels without copying them. */

/* --- Decoder control --- */

/* Discards a decoder's internal state. Required after every seek: without it the decoder emits
   frames reconstructed from packets belonging to the position the viewer just left. */
KC_API void ffkmp_codecctx_flush(AVCodecContext *c) { if (c) avcodec_flush_buffers(c); }

/* Frame-level threading for video, and low delay for audio. A player wants the first and not the
   second; a transcoder does not care. 0 lets libavcodec choose. */
KC_API void ffkmp_codecctx_set_threads(AVCodecContext *c, int count, int frame_level) {
    if (!c) return;
    c->thread_count = count;
    c->thread_type = frame_level ? FF_THREAD_FRAME : FF_THREAD_SLICE;
}
KC_API void ffkmp_codecctx_set_low_delay(AVCodecContext *c, int on) {
    if (c) { if (on) c->flags |= AV_CODEC_FLAG_LOW_DELAY; else c->flags &= ~AV_CODEC_FLAG_LOW_DELAY; }
}

/* --- Seeking with the full flag set --- */

KC_API int ffkmp_avseek_flag_backward(void) { return AVSEEK_FLAG_BACKWARD; }
KC_API int ffkmp_avseek_flag_any(void)      { return AVSEEK_FLAG_ANY; }

/* avformat_seek_file, which av_seek_frame cannot express: a bounded window rather than a single
   target. A player uses it to say "land at or before here, but no earlier than there", which is
   what makes a retry ladder cheap instead of a fixed pessimistic backoff. */
/* The cancellation entry poll, mirroring helpers_format.c: every context this layer opens carries an
   int cell as the interrupt opaque, and FFmpeg's own poll sites do not cover buffered reads. */
static int kc_playback_interrupted(AVFormatContext *s) {
    return s && s->interrupt_callback.callback && s->interrupt_callback.opaque
        && *(volatile int *)s->interrupt_callback.opaque;
}

KC_API int ffkmp_fmt_seek_file(AVFormatContext *ctx, int stream_index,
                                     int64_t min_ts, int64_t ts, int64_t max_ts, int flags) {
    if (!ctx) return AVERROR(EINVAL);
    if (kc_playback_interrupted(ctx)) return AVERROR_EXIT;
    return avformat_seek_file(ctx, stream_index, min_ts, ts, max_ts, flags);
}

/* Whether this input can seek at all, read from the input itself instead of assumed. A player
   that assumes yes offers a seek bar that does nothing on a pipe, a capture device or a live
   stream, and hands libavformat a request it answers with an error on every drag.
   Two things must hold. The demuxer must not have declared the input unseekable, which is what
   AVFMTCTX_UNSEEKABLE means, and the byte stream must report AVIO_SEEKABLE_NORMAL. A
   protocol-less input (AVFMT_NOFILE, so a device or a capture) has no byte stream at all and
   answers 0. That last case is conservative: a demuxer may implement its own seek without a
   seekable byte stream, but since FFmpeg 7 the function pointer that would prove it lives in a
   private struct, so it cannot be read from the public headers. Under-promising is the safe
   direction for a capability. */
KC_API int ffkmp_fmt_is_seekable(AVFormatContext *c) {
    if (!c) return 0;
    if (c->ctx_flags & AVFMTCTX_UNSEEKABLE) return 0;
    if (!c->pb) return 0;
    return (c->pb->seekable & AVIO_SEEKABLE_NORMAL) ? 1 : 0;
}

/* --- Stream selection at the demuxer --- */

/* AVDISCARD_ALL on an unselected stream makes libavformat skip its packets instead of the caller
   reading and throwing them away. On a file with ten audio tracks that is most of the read work. */
KC_API void ffkmp_stream_discard_all(AVStream *s)  { if (s) s->discard = AVDISCARD_ALL; }
KC_API void ffkmp_stream_discard_none(AVStream *s) { if (s) s->discard = AVDISCARD_DEFAULT; }

/* --- Stream metadata a track menu and a renderer need --- */

KC_API int ffkmp_stream_disposition(AVStream *s) { return s ? s->disposition : 0; }
KC_API int ffkmp_disposition_default(void)           { return AV_DISPOSITION_DEFAULT; }
KC_API int ffkmp_disposition_forced(void)            { return AV_DISPOSITION_FORCED; }
KC_API int ffkmp_disposition_hearing_impaired(void)  { return AV_DISPOSITION_HEARING_IMPAIRED; }
KC_API int ffkmp_disposition_visual_impaired(void)   { return AV_DISPOSITION_VISUAL_IMPAIRED; }
KC_API int ffkmp_disposition_attached_pic(void)      { return AV_DISPOSITION_ATTACHED_PIC; }

/* Rotation, in degrees, from the display matrix a phone writes into its recordings. Without this
   every video shot in portrait plays on its side. av_display_rotation_get returns the angle the
   image must be rotated by counter-clockwise, as a double; the sign is flipped here so the result
   is the clockwise rotation a renderer should apply. */
KC_API int ffkmp_stream_rotation_degrees(AVStream *s) {
    if (!s) return 0;
    const AVPacketSideData *sd = av_packet_side_data_get(s->codecpar->coded_side_data,
                                                         s->codecpar->nb_coded_side_data,
                                                         AV_PKT_DATA_DISPLAYMATRIX);
    if (!sd || sd->size < 9 * 4) return 0;
    double theta = -av_display_rotation_get((const int32_t *)sd->data);
    int deg = (int)(theta < 0 ? theta - 0.5 : theta + 0.5);
    deg %= 360;
    if (deg < 0) deg += 360;
    return deg;
}

/* --- Packet ownership --- */

/* Moves the reference rather than copying the payload, so a player can queue a packet without a
   memcpy. The source is left blank and reusable. */
KC_API void ffkmp_packet_move_ref(AVPacket *dst, AVPacket *src) {
    if (dst && src) av_packet_move_ref(dst, src);
}
KC_API int64_t ffkmp_packet_pos(AVPacket *p) { return p ? p->pos : -1; }

/* --- Frame metadata a renderer cannot be correct without --- */

/* Getting any of these wrong is visible. The matrix decides hue, the range decides whether black
   is black, and the chroma location decides whether colour bleeds at a sharp edge. All four are
   already on the frame; only the accessor was missing. */
KC_API int ffkmp_frame_color_range(AVFrame *f)     { return f ? (int)f->color_range : 0; }
KC_API int ffkmp_frame_colorspace(AVFrame *f)      { return f ? (int)f->colorspace : 2; }
KC_API int ffkmp_frame_color_primaries(AVFrame *f) { return f ? (int)f->color_primaries : 2; }
KC_API int ffkmp_frame_color_trc(AVFrame *f)       { return f ? (int)f->color_trc : 2; }
KC_API int ffkmp_frame_chroma_location(AVFrame *f) { return f ? (int)f->chroma_location : 0; }
KC_API int ffkmp_frame_is_keyframe(AVFrame *f) {
    return (f && (f->flags & AV_FRAME_FLAG_KEY)) ? 1 : 0;
}
KC_API void ffkmp_frame_sample_aspect_ratio(AVFrame *f, int *n, int *d) {
    if (!f || !n || !d) { if (n) *n = 1; if (d) *d = 1; return; }
    *n = f->sample_aspect_ratio.num ? f->sample_aspect_ratio.num : 1;
    *d = f->sample_aspect_ratio.den ? f->sample_aspect_ratio.den : 1;
}

/* --- Which speaker each channel belongs to --- */

/* A channel count is not a layout. Six channels are 5.1 with side surrounds or 5.1 with back
   surrounds, and a downmix that guesses wrong sends the surround content to the wrong speakers.
   The mask carries one bit per speaker, so it answers the question a count cannot.
   0 means there is no mask to report: the layout is unspecified (the container never said), a
   custom order (per-channel positions, which only the extended layout describes) or ambisonic.
   A caller that gets 0 must fall back to the count and say that it did. */
static int64_t ffkmp_ch_layout_mask_(const AVChannelLayout *l) {
    if (!l || l->order != AV_CHANNEL_ORDER_NATIVE) return 0;
    return (int64_t)l->u.mask;
}
KC_API int64_t ffkmp_frame_ch_layout_mask(AVFrame *f) {
    return f ? ffkmp_ch_layout_mask_(&f->ch_layout) : 0;
}
KC_API int64_t ffkmp_codecpar_ch_layout_mask(AVCodecParameters *p) {
    return p ? ffkmp_ch_layout_mask_(&p->ch_layout) : 0;
}

/* --- Reaching a frame's pixels without copying them --- */

/* A plane pointer and its row pitch. This is what a renderer uploads from. The alternative, the
   copy that av_image_copy_to_buffer performs, is 3.11 MB for one 1080p frame and 24.9 MB for one
   4K 10-bit frame, which at 60 fps is between 187 MB/s and 1.5 GB/s of pointless work. */
KC_API uint8_t* ffkmp_frame_plane(AVFrame *f, int p) {
    return (f && p >= 0 && p < AV_NUM_DATA_POINTERS) ? f->data[p] : NULL;
}

/* How many planes this frame's format actually has. */
KC_API int ffkmp_frame_plane_count(AVFrame *f) {
    if (!f) return 0;
    if (f->nb_samples > 0) {
        const AVChannelLayout *cl = &f->ch_layout;
        return av_sample_fmt_is_planar((enum AVSampleFormat)f->format) ? cl->nb_channels : 1;
    }
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    return d ? av_pix_fmt_count_planes((enum AVPixelFormat)f->format) : 0;
}

/* The height of plane p in rows, which for a chroma plane of a subsampled format is not the
   frame height. Uploading with the wrong value is the other half of the stride mistake.
   A frame with no width is not a picture: on an audio frame `format` holds a sample format, so
   reading it as a pixel format would answer with the subsampling of whatever pixel format shares
   that ordinal. 0 rows is the only honest answer. */
KC_API int ffkmp_frame_plane_height(AVFrame *f, int p) {
    if (!f || p < 0 || f->width == 0) return 0;
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    if (!d) return 0;
    /* Bounded by the format's OWN plane count, not by AV_NUM_DATA_POINTERS and not at all.
       Unbounded, this answered with the frame height for plane 8 of an rgba frame: a number
       where a refusal belongs, and one a caller can size a copy from. The pointer accessor
       beside it answers NULL for the same index, so the pair used to disagree. */
    if (p >= av_pix_fmt_count_planes((enum AVPixelFormat)f->format)) return 0;
    if (p == 1 || p == 2) return AV_CEIL_RSHIFT(f->height, d->log2_chroma_h);
    return f->height;
}

/* Non-NULL when the frame lives in GPU or hardware memory. For VideoToolbox this is the
   CVPixelBuffer, for MediaCodec the output buffer, for VA-API the surface id. The renderer that
   matches the decoder knows what to do with it; nobody else may touch it. */
KC_API void* ffkmp_frame_hw_surface(AVFrame *f) {
    if (!f || !f->hw_frames_ctx) return NULL;
    return (void *)f->data[3];
}
KC_API int ffkmp_frame_is_hardware(AVFrame *f) {
    return (f && f->hw_frames_ctx) ? 1 : 0;
}
