/* The stream part of the FFmpeg helper layer: AVStream. */

#include "kitecodec_helpers.h"

#include <libavutil/dict.h>

#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/mathematics.h>

/* ════════════ AVStream ════════════ */

KC_API int                  ffkmp_stream_index(AVStream *s)    { return s ? s->index : -1; }
KC_API AVCodecParameters*   ffkmp_stream_codecpar(AVStream *s) { return s ? s->codecpar : NULL; }
/* Stream duration converted from the stream's own time-base into microseconds.
   Returns -1 when the container doesn't declare it (AV_NOPTS_VALUE / non-positive). */
KC_API int64_t              ffkmp_stream_duration_micros(AVStream *s) {
    if (!s || s->duration == AV_NOPTS_VALUE || s->duration <= 0) return -1;
    return av_rescale_q(s->duration, s->time_base, AV_TIME_BASE_Q);
}
KC_API int64_t              ffkmp_stream_start_time(AVStream *s){return s ? s->start_time : 0; }
KC_API AVDictionary*        ffkmp_stream_metadata(AVStream *s) { return s ? s->metadata : NULL; }
KC_API void ffkmp_stream_time_base(AVStream *s, int *n, int *d) {
    if (!s || !n || !d) { if (n) *n = 0; if (d) *d = 1; return; }
    *n = s->time_base.num; *d = s->time_base.den ? s->time_base.den : 1;
}
KC_API void ffkmp_stream_avg_frame_rate(AVStream *s, int *n, int *d) {
    if (!s || !n || !d) { if (n) *n = 0; if (d) *d = 1; return; }
    *n = s->avg_frame_rate.num; *d = s->avg_frame_rate.den ? s->avg_frame_rate.den : 1;
}
KC_API void ffkmp_stream_set_time_base(AVStream *s, int n, int d) {
    if (s) { s->time_base.num = n; s->time_base.den = d ? d : 1; }
}
KC_API int ffkmp_stream_set_sample_aspect_ratio(AVStream *s, int num, int den) {
    if (!s || num < 0 || den <= 0) return AVERROR(EINVAL);
    s->sample_aspect_ratio = av_make_q(num, den);
    return 0;
}

/* Copies what names src into dst: every tag, language and title among them, and the disposition
   flags. Also the stream-level pixel shape, which a Matroska demuxer reads from the container and
   never stores in the codec parameters. The side data a renderer needs, such as the display matrix,
   already travels with the codec parameters. Run it before the output header is written. */
KC_API int ffkmp_stream_copy_identity(AVStream *dst, const AVStream *src) {
    if (!dst || !src) return AVERROR(EINVAL);
    int rc = av_dict_copy(&dst->metadata, src->metadata, 0);
    if (rc < 0) return rc;
    dst->disposition = src->disposition;
    dst->sample_aspect_ratio = src->sample_aspect_ratio;
    return 0;
}
