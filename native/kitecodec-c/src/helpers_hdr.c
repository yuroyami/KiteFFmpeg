/* HDR static metadata: the mastering display (SMPTE ST 2086) and the content light level
 * (CTA-861.3), read from a stream's codec parameters or a frame, and given to an encoder.
 *
 * An encoder takes both as decoded side data before it opens. avcodec_open2 then copies them into
 * its coded side data, which avcodec_parameters_from_context hands to the muxer, and an encoder
 * that embeds them in the bitstream reads them from the same place. */

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mastering_display_metadata.h>

/* An encoder's decoded side data and av_frame_side_data_new arrived in FFmpeg 7. Built against
 * older headers, the two calls that give HDR metadata to an encoder answer AVERROR(ENOSYS). */
#define KC_ENCODER_TAKES_SIDE_DATA (LIBAVCODEC_VERSION_MAJOR >= 61)

/* The ten rationals in the order the header describes, and back. */
static void kc_mastering_to_ints_(const AVMasteringDisplayMetadata *m, int *q, int *flags) {
    const AVRational order[10] = {
        m->display_primaries[0][0], m->display_primaries[0][1],
        m->display_primaries[1][0], m->display_primaries[1][1],
        m->display_primaries[2][0], m->display_primaries[2][1],
        m->white_point[0], m->white_point[1],
        m->min_luminance, m->max_luminance,
    };
    for (int i = 0; i < 10; i++) {
        q[2 * i] = order[i].num;
        q[2 * i + 1] = order[i].den;
    }
    *flags = (m->has_primaries ? KC_HDR_HAS_PRIMARIES : 0) | (m->has_luminance ? KC_HDR_HAS_LUMINANCE : 0);
}

static int kc_mastering_from_ints_(AVMasteringDisplayMetadata *m, const int *q, int flags) {
    AVRational r[10];
    for (int i = 0; i < 10; i++) {
        if (q[2 * i + 1] <= 0) return AVERROR(EINVAL);
        r[i] = av_make_q(q[2 * i], q[2 * i + 1]);
    }
    for (int p = 0; p < 3; p++) {
        m->display_primaries[p][0] = r[2 * p];
        m->display_primaries[p][1] = r[2 * p + 1];
    }
    m->white_point[0] = r[6];
    m->white_point[1] = r[7];
    m->min_luminance = r[8];
    m->max_luminance = r[9];
    m->has_primaries = (flags & KC_HDR_HAS_PRIMARIES) != 0;
    m->has_luminance = (flags & KC_HDR_HAS_LUMINANCE) != 0;
    return 0;
}

KC_API int ffkmp_codecpar_mastering_display(AVCodecParameters *p, int *q, int *flags) {
    if (!p || !q || !flags) return AVERROR(EINVAL);
    const AVPacketSideData *sd = av_packet_side_data_get(p->coded_side_data, p->nb_coded_side_data,
                                                         AV_PKT_DATA_MASTERING_DISPLAY_METADATA);
    if (!sd || sd->size < sizeof(AVMasteringDisplayMetadata)) return 0;
    kc_mastering_to_ints_((const AVMasteringDisplayMetadata *)sd->data, q, flags);
    return 1;
}

KC_API int ffkmp_frame_mastering_display(AVFrame *f, int *q, int *flags) {
    if (!f || !q || !flags) return AVERROR(EINVAL);
    const AVFrameSideData *sd = av_frame_get_side_data(f, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    if (!sd || sd->size < sizeof(AVMasteringDisplayMetadata)) return 0;
    kc_mastering_to_ints_((const AVMasteringDisplayMetadata *)sd->data, q, flags);
    return 1;
}

KC_API int ffkmp_codecpar_content_light(AVCodecParameters *p, int *max_cll, int *max_fall) {
    if (!p || !max_cll || !max_fall) return AVERROR(EINVAL);
    const AVPacketSideData *sd = av_packet_side_data_get(p->coded_side_data, p->nb_coded_side_data,
                                                         AV_PKT_DATA_CONTENT_LIGHT_LEVEL);
    if (!sd || sd->size < sizeof(AVContentLightMetadata)) return 0;
    const AVContentLightMetadata *light = (const AVContentLightMetadata *)sd->data;
    *max_cll = (int)light->MaxCLL;
    *max_fall = (int)light->MaxFALL;
    return 1;
}

KC_API int ffkmp_frame_content_light(AVFrame *f, int *max_cll, int *max_fall) {
    if (!f || !max_cll || !max_fall) return AVERROR(EINVAL);
    const AVFrameSideData *sd = av_frame_get_side_data(f, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    if (!sd || sd->size < sizeof(AVContentLightMetadata)) return 0;
    const AVContentLightMetadata *light = (const AVContentLightMetadata *)sd->data;
    *max_cll = (int)light->MaxCLL;
    *max_fall = (int)light->MaxFALL;
    return 1;
}

KC_API int ffkmp_codecctx_add_mastering_display(AVCodecContext *c, const int *q, int flags) {
    if (!c || !q || !(flags & (KC_HDR_HAS_PRIMARIES | KC_HDR_HAS_LUMINANCE))) return AVERROR(EINVAL);
    AVMasteringDisplayMetadata parsed = { 0 };
    int rc = kc_mastering_from_ints_(&parsed, q, flags);
    if (rc < 0) return rc;
#if KC_ENCODER_TAKES_SIDE_DATA
    AVFrameSideData *sd = av_frame_side_data_new(&c->decoded_side_data, &c->nb_decoded_side_data,
                                                 AV_FRAME_DATA_MASTERING_DISPLAY_METADATA,
                                                 sizeof(AVMasteringDisplayMetadata),
                                                 AV_FRAME_SIDE_DATA_FLAG_REPLACE);
    if (!sd) return AVERROR(ENOMEM);
    *(AVMasteringDisplayMetadata *)sd->data = parsed;
    return 0;
#else
    return AVERROR(ENOSYS);
#endif
}

KC_API int ffkmp_codecctx_add_content_light(AVCodecContext *c, int max_cll, int max_fall) {
    if (!c || max_cll < 0 || max_fall < 0) return AVERROR(EINVAL);
#if KC_ENCODER_TAKES_SIDE_DATA
    AVFrameSideData *sd = av_frame_side_data_new(&c->decoded_side_data, &c->nb_decoded_side_data,
                                                 AV_FRAME_DATA_CONTENT_LIGHT_LEVEL,
                                                 sizeof(AVContentLightMetadata),
                                                 AV_FRAME_SIDE_DATA_FLAG_REPLACE);
    if (!sd) return AVERROR(ENOMEM);
    AVContentLightMetadata *light = (AVContentLightMetadata *)sd->data;
    light->MaxCLL = (unsigned)max_cll;
    light->MaxFALL = (unsigned)max_fall;
    return 0;
#else
    return AVERROR(ENOSYS);
#endif
}
