/* Ordinary maintained source since the interlude (I-12). Lifted at B1.3 from the def body of
 * kiteffmpeg-core/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in KPKMP.md's I.3 Execution log entry, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * The error part of the FFmpeg helper layer: the def's 'Errors & macros' section(s). */

#include "kitecodec_helpers.h"

#include <libavutil/error.h>
#include <libavutil/mathematics.h>

/* ════════════ Errors & macros ════════════ */

KC_API const char* ffkmp_strerror(int errnum) {
    static __thread char buf[256];
    av_strerror(errnum, buf, sizeof(buf));
    return buf;
}
KC_API int ffkmp_averror_eagain(void) { return AVERROR(EAGAIN); }
KC_API int ffkmp_averror_eof(void)    { return AVERROR_EOF; }

/* av_rescale_q uses a 128-bit intermediate, the only overflow-safe way to convert a
   timestamp between two time-bases. Exposed because Kotlin Long*Long would overflow. */
KC_API int64_t ffkmp_rescale_q(int64_t v, int sn, int sd, int dn, int dd) {
    AVRational s = { sn, sd ? sd : 1 };
    AVRational d = { dn, dd ? dd : 1 };
    return av_rescale_q(v, s, d);
}
