/* The error part of the FFmpeg helper layer: errors. */

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
