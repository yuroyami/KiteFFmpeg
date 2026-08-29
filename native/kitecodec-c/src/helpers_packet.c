/* Ordinary maintained source since the interlude (I-12). Lifted at B1.3 from the def body of
 * kiteffmpeg-core/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in KPKMP.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The packet part of the FFmpeg helper layer: the def's 'AVPacket' section(s). */

#include "kitecodec_helpers.h"

#include <libavcodec/packet.h>
#include <libavutil/avutil.h>

/* ════════════ AVPacket ════════════ */

KC_API AVPacket* ffkmp_packet_alloc(void)        { return av_packet_alloc(); }
KC_API void      ffkmp_packet_free(AVPacket *p)  { if (p) { AVPacket *q = p; av_packet_free(&q); } }
KC_API void      ffkmp_packet_unref(AVPacket *p) { if (p) av_packet_unref(p); }
KC_API int64_t   ffkmp_packet_pts(AVPacket *p)           { return p ? p->pts : AV_NOPTS_VALUE; }
KC_API int64_t   ffkmp_packet_dts(AVPacket *p)           { return p ? p->dts : AV_NOPTS_VALUE; }
KC_API int       ffkmp_packet_stream_index(AVPacket *p)  { return p ? p->stream_index : -1; }
KC_API int       ffkmp_packet_size(AVPacket *p)          { return p ? p->size : 0; }
KC_API uint8_t*  ffkmp_packet_data(AVPacket *p)          { return p ? p->data : NULL; }
KC_API int64_t   ffkmp_packet_duration(AVPacket *p)      { return p ? p->duration : 0; }
KC_API int       ffkmp_packet_is_keyframe(AVPacket *p)   { return (p && (p->flags & AV_PKT_FLAG_KEY)) ? 1 : 0; }
KC_API void      ffkmp_packet_set_stream_index(AVPacket *p, int i) { if (p) p->stream_index = i; }
KC_API void      ffkmp_packet_set_pts(AVPacket *p, int64_t v) { if (p) p->pts = v; }
KC_API void      ffkmp_packet_set_dts(AVPacket *p, int64_t v) { if (p) p->dts = v; }
KC_API void      ffkmp_packet_rescale_ts(AVPacket *p, int sn, int sd, int dn, int dd) {
    if (!p) return;
    AVRational s = { sn, sd ? sd : 1 };
    AVRational d = { dn, dd ? dd : 1 };
    av_packet_rescale_ts(p, s, d);
}

KC_API AVPacket* ffkmp_packet_clone(const AVPacket *packet) {
    AVPacket *out;
    if (!packet) return NULL;
    out = av_packet_alloc();
    if (!out) return NULL;
    if (av_packet_ref(out, packet) < 0) {
        av_packet_free(&out);
        return NULL;
    }
    return out;
}
