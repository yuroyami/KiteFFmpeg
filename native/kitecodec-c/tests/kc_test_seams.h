/* Declarations for the KC_TESTING-only seams the host suites drive.
 *
 * Deliberately NOT in include/kitecodec_abi.h. That header is the library's public boundary and is
 * read by scripts/symbol-audit.sh, which counts every KC_API record in it against a signature
 * baseline; a testing seam there would ask the audit to bless a symbol no consumer may call. The
 * definitions live in src/kitecodec_abi.c behind KC_TESTING, which only scripts/build-host.sh
 * defines, so nothing here exists in a shipped archive.
 */

#ifndef KC_TEST_SEAMS_H
#define KC_TEST_SEAMS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Forces what kc_init answers, so a suite can drive the refusal every constructor helper owes.
 * There is no other way to reach it: the verdict is computed once from the FFmpeg this process
 * linked, and a host build links a compatible one by construction. `active` zero restores the real
 * verdict. */
void kc_test_force_gate_status(int32_t active, int32_t status);

#ifdef __cplusplus
}
#endif

#endif
