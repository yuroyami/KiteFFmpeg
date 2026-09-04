/* Ordinary maintained source since the interlude. Lifted at B1.3 from the def body of
 * kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in that commit, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The stream part of the FFmpeg helper layer: the def's 'AVStream' section(s). */

#include "kitecodec_helpers.h"

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
