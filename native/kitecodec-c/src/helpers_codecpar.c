/* Ordinary maintained source since the interlude. Lifted at B1.3 from the def body of
 * kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in PLANNING.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The codecpar part of the FFmpeg helper layer: the def's 'AVCodecParameters' section(s). */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavutil/pixdesc.h>

#include <string.h>

/* ════════════ AVCodecParameters ════════════ */

KC_API int     ffkmp_codecpar_codec_type(AVCodecParameters *p) { return p ? (int)p->codec_type : -1; }
KC_API int     ffkmp_media_type_video(void)      { return (int)AVMEDIA_TYPE_VIDEO; }
KC_API int     ffkmp_media_type_audio(void)      { return (int)AVMEDIA_TYPE_AUDIO; }
KC_API int     ffkmp_media_type_subtitle(void)   { return (int)AVMEDIA_TYPE_SUBTITLE; }
KC_API int     ffkmp_media_type_data(void)       { return (int)AVMEDIA_TYPE_DATA; }
KC_API int     ffkmp_media_type_attachment(void) { return (int)AVMEDIA_TYPE_ATTACHMENT; }
KC_API int     ffkmp_codecpar_codec_id(AVCodecParameters *p)   { return p ? (int)p->codec_id : 0; }
KC_API int64_t ffkmp_codecpar_bit_rate(AVCodecParameters *p)   { return p ? p->bit_rate : 0; }
KC_API int32_t ffkmp_codecpar_field_order(const AVCodecParameters *p) {
    if (!p) return 0;
    switch (p->field_order) {
        case AV_FIELD_PROGRESSIVE: return 1;
        /* TT and TB both present the TOP field first; BB and BT present the bottom one first. The
           second letter is the CODED order, which no caller here acts on. */
        case AV_FIELD_TT:
        case AV_FIELD_TB: return 2;
        case AV_FIELD_BB:
        case AV_FIELD_BT: return 3;
        default: return 0;
    }
}
KC_API int     ffkmp_codecpar_width(AVCodecParameters *p)      { return p ? p->width : 0; }
KC_API int     ffkmp_codecpar_height(AVCodecParameters *p)     { return p ? p->height : 0; }
KC_API int     ffkmp_codecpar_format(AVCodecParameters *p)     { return p ? p->format : -1; }
KC_API int     ffkmp_codecpar_profile(AVCodecParameters *p)    { return p ? p->profile : -99; }
KC_API int     ffkmp_codecpar_level(AVCodecParameters *p)      { return p ? p->level : -99; }
KC_API int     ffkmp_codecpar_color_space(AVCodecParameters *p){ return p ? (int)p->color_space : AVCOL_SPC_UNSPECIFIED; }
KC_API int     ffkmp_codecpar_color_primaries(AVCodecParameters *p) { return p ? (int)p->color_primaries : AVCOL_PRI_UNSPECIFIED; }
KC_API int     ffkmp_codecpar_color_transfer(AVCodecParameters *p) { return p ? (int)p->color_trc : AVCOL_TRC_UNSPECIFIED; }
KC_API int     ffkmp_codecpar_color_range(AVCodecParameters *p){ return p ? (int)p->color_range : AVCOL_RANGE_UNSPECIFIED; }
KC_API int     ffkmp_codecpar_chroma_location(AVCodecParameters *p) { return p ? (int)p->chroma_location : AVCHROMA_LOC_UNSPECIFIED; }
KC_API int     ffkmp_codecpar_bit_depth(AVCodecParameters *p) {
    const AVPixFmtDescriptor *descriptor;
    if (!p) return 0;
    descriptor = av_pix_fmt_desc_get((enum AVPixelFormat)p->format);
    if (descriptor && descriptor->nb_components > 0) return descriptor->comp[0].depth;
    return p->bits_per_raw_sample > 0 ? p->bits_per_raw_sample : 0;
}
KC_API int     ffkmp_codecpar_chroma_subsampling(AVCodecParameters *p) {
    const AVPixFmtDescriptor *descriptor;
    if (!p) return 0;
    descriptor = av_pix_fmt_desc_get((enum AVPixelFormat)p->format);
    if (!descriptor || descriptor->flags & (AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_PAL | AV_PIX_FMT_FLAG_HWACCEL)) {
        return 0;
    }
    if (descriptor->nb_components < 3) return 400;
    if (descriptor->log2_chroma_w == 1 && descriptor->log2_chroma_h == 1) return 420;
    if (descriptor->log2_chroma_w == 1 && descriptor->log2_chroma_h == 0) return 422;
    if (descriptor->log2_chroma_w == 0 && descriptor->log2_chroma_h == 0) return 444;
    return 0;
}
KC_API int     ffkmp_codecpar_sample_rate(AVCodecParameters *p){ return p ? p->sample_rate : 0; }
KC_API int     ffkmp_codecpar_channels(AVCodecParameters *p)   { return p ? p->ch_layout.nb_channels : 0; }
KC_API int     ffkmp_codecpar_extradata(AVCodecParameters *p, uint8_t *dst, int dst_size) {
    int copied;
    if (!p || dst_size < 0) return AVERROR(EINVAL);
    if (!p->extradata || p->extradata_size <= 0) return 0;
    if (!dst) return p->extradata_size;
    copied = p->extradata_size < dst_size ? p->extradata_size : dst_size;
    if (copied > 0) memcpy(dst, p->extradata, (size_t)copied);
    return copied;
}
KC_API void    ffkmp_codecpar_sample_aspect_ratio(AVCodecParameters *p, int *num, int *den) {
    if (!p || !num || !den) return;
    *num = p->sample_aspect_ratio.num;
    *den = p->sample_aspect_ratio.den ? p->sample_aspect_ratio.den : 1;
}
KC_API int ffkmp_codecpar_from_context(AVCodecParameters *par, AVCodecContext *ctx) {
    if (!par || !ctx) return AVERROR(EINVAL);
    return avcodec_parameters_from_context(par, ctx);
}
/* Stream-copy parameter clone. codec_tag is container-specific (mp4 'avc1' means nothing
   to mkv), and zeroing it lets the destination muxer pick its own tag; ffmpeg.c does the same. */
KC_API int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src) {
    if (!dst || !src) return AVERROR(EINVAL);
    int rc = avcodec_parameters_copy(dst, src);
    if (rc >= 0) dst->codec_tag = 0;
    return rc;
}
