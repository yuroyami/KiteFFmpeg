/* Doctored shim, case 3 of tests/test_identity.c: libavformat's HEADER micro one above the runtime's.
 *
 * The one case that is reported and never rejected. A micro bump inside one minor is a bug fix release
 * by FFmpeg's own versioning rules, so a runtime with a lower micro is missing fixes and not missing
 * declarations or fields. Rejecting it would refuse to start against a runtime that is fine, which is
 * exactly the false positive that becomes an outage inside a consumer's product.
 * Policy: verdict recorded, status accepting.
 *
 * On the proving machine today that is header 62.3.101 against runtime 62.3.100.
 */

#ifndef KC_FAKE_MICRO_OLDER_H
#define KC_FAKE_MICRO_OLDER_H

#define KC_CASE kc_micro_older
#include "../kc_rename.h"

#include_next "kitecodec_ffmpeg_versions.h"

enum { KC_FAKE_REAL_AVFORMAT_MICRO = LIBAVFORMAT_VERSION_MICRO };

#undef LIBAVFORMAT_VERSION_MICRO
#define LIBAVFORMAT_VERSION_MICRO (KC_FAKE_REAL_AVFORMAT_MICRO + 1)

#endif /* KC_FAKE_MICRO_OLDER_H */
