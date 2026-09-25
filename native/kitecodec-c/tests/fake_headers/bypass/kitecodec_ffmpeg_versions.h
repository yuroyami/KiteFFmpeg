/* Doctored shim for the bypass cases of tests/test_identity.c.
 *
 * Doctored exactly like tests/fake_headers/major_mismatch/, and separate from it for one reason: the
 * gate runs once per process per copy, so the copy that must run with KITECODEC_FFMPEG_ABI_BYPASS set
 * cannot be the copy that proves the bypass is not a silent default. Case 1 calls the major_mismatch
 * copy while the variable is unset and asserts the rejection stands; the bypass cases set the variable
 * and then touch THIS copy for the first time.
 *
 * That pair of assertions is the point: the escape hatch must be proved not to exist as a silent
 * default, and proved to exist when set.
 */

#ifndef KC_FAKE_BYPASS_H
#define KC_FAKE_BYPASS_H

#define KC_CASE kc_bypass
#include "../kc_rename.h"

#include_next "kitecodec_ffmpeg_versions.h"

enum { KC_FAKE_BYPASS_REAL_AVUTIL_MAJOR = LIBAVUTIL_VERSION_MAJOR };

#undef LIBAVUTIL_VERSION_MAJOR
#define LIBAVUTIL_VERSION_MAJOR (KC_FAKE_BYPASS_REAL_AVUTIL_MAJOR - 1)

#endif /* KC_FAKE_BYPASS_H */
