/* Doctored shim, case 1 of tests/test_identity.c: libavutil's HEADER major one below the runtime's.
 *
 * This is the direction that matters and the one reconnaissance reproduced as a live crash: older
 * headers against a newer runtime link cleanly, every symbol resolves, 38 struct field offsets are
 * wrong, and the process dies inside av_frame_free with AddressSanitizer naming a four byte read 36
 * bytes past a 416 byte region. Policy: hard reject, no override.
 *
 * The doctoring happens AFTER include_next has read the real headers, so FFmpeg's own deprecation
 * guards saw their true values and the set of declarations this translation unit has is the real one.
 * Only src/kitecodec_abi.c's frozen expectation array is affected, which is the single variable this
 * experiment wants to change.
 *
 * The real major is captured into an enum constant first rather than written as a literal, so the case
 * keeps meaning "one major behind" when FFmpeg moves. On the proving machine today that is header 59
 * against runtime 60.
 */

#ifndef KC_FAKE_MAJOR_MISMATCH_H
#define KC_FAKE_MAJOR_MISMATCH_H

#define KC_CASE kc_major_mismatch
#include "../kc_rename.h"

#include_next "kitecodec_ffmpeg_versions.h"

enum { KC_FAKE_REAL_AVUTIL_MAJOR = LIBAVUTIL_VERSION_MAJOR };

#undef LIBAVUTIL_VERSION_MAJOR
#define LIBAVUTIL_VERSION_MAJOR (KC_FAKE_REAL_AVUTIL_MAJOR - 1)

#endif /* KC_FAKE_MAJOR_MISMATCH_H */
