/* The KiteFFmpeg JNI adapter: private declarations shared by the kj_* units.
 *
 * WHAT THIS LIBRARY IS. A narrow, dynamically registered JNI adapter over KiteFFmpeg's opaque C
 * boundary (S1.c.1, register item S1C-01). It includes ONLY <jni.h>, the C runtime and KiteFFmpeg's
 * three opaque headers. It never includes a libav header, never spells an av_* call and never
 * reproduces an FFmpeg struct: scripts/source-discipline.sh enforces all three, and a control that
 * plants a violation must fail it. The category units (kj_abi.c, kj_format.c, kj_packet.c,
 * kj_codec.c, kj_frame.c, kj_filter.c) call kc_* and ffkmp_* helpers only.
 *
 * WHY DYNAMIC REGISTRATION. The shared library exports exactly JNI_OnLoad (exports.map plus
 * -fvisibility=hidden). There is no Java_* symbol to keep in sync with a package name, R8 cannot
 * strip a native method it cannot see a symbol for (the consumer keep rule pins the bridge class
 * instead), and the whole Kotlin-to-C wiring lives in ONE manifest, methods.def, that both
 * kj_registration.c and the Kotlin bridge are written against. No hand-copied second list.
 *
 * HANDLES, NEVER POINTERS. Every C object crossing to the JVM travels as a generation-tagged token
 * minted by kj_handles.c. A jlong encodes a table slot and a generation, so a stale token, a zero
 * token, a double close or a token of the wrong kind is caught at the table and thrown as a typed
 * JVM exception BEFORE any helper runs. A raw pointer in a jlong would turn every one of those
 * caller bugs into memory corruption. Close invalidates the slot exactly once and is idempotent.
 */

#ifndef KJ_INTERNAL_H
#define KJ_INTERNAL_H

#include <jni.h>
#include <stdint.h>

#include "kitecodec_abi.h"
#include "kitecodec_handles.h"
#include "kc_handles.h"
#include "kitecodec_helpers.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── The handle table ─────────────────────────────────────────────────────────────────────── */

/* One kind per opaque typedef the boundary carries (kitecodec_handles.h), in one fixed order.
 * kj_handle_get refuses a token whose kind differs from the caller's expectation. */

/* One long-array element is one handle token; one int-array element is one scalar argument. */
int kj_longs_dup(JNIEnv *env, jlongArray values, jlong **out, int32_t *out_len);
int kj_ints_dup(JNIEnv *env, jintArray values, int **out, int32_t *out_len);

/* The table itself is declared in kc_handles.h and implemented in native/kitecodec-handles, so the
 * web binding runs the same code. Only the three that need a JNIEnv are
 * declared here, because only they can throw.
 *
 * kj_handle_put_checked turns table exhaustion into a typed bridge failure.
 * kj_handle_put_borrowed mints a token whose lifetime is bounded by a live parent; closing the
 *   parent invalidates every descendant atomically before the parent pointer goes back to its owner.
 * kj_handle_get resolves or throws: on a zero, stale, closed or wrong-kind token it throws the
 *   bridge's typed exception on env and returns NULL, and the caller must return immediately
 *   because the pending exception IS the result. Use kj_handle_peek when silence is wanted instead.
 *
 * Both mint functions mint nothing and return 0 while a Java exception is pending, because the JVM
 * drops a native method's return value when it throws, and the token would be lost with it. Every
 * caller already frees the object it was about to hand over when it gets 0.
 */
jlong kj_handle_put_checked(JNIEnv *env, int kind, void *ptr);
jlong kj_handle_put_borrowed(JNIEnv *env, int kind, void *ptr, jlong parent);
void *kj_handle_get(JNIEnv *env, jlong token, int kind);

/* ── JNI utilities (kj_util.c). String, array and exception conversion lives HERE only. ─────── */

/* Throw the bridge's typed handle exception (class named in methods.def's header block). Safe to
 * call with a message only; returns void because the caller returns immediately after. */
void kj_throw_handle(JNIEnv *env, const char *msg);

/* Throw the bridge's typed native exception carrying an FFmpeg error code and a context string.
 * The Kotlin side turns (code, context) into the same FFmpegError taxonomy native uses. */
void kj_throw_ffmpeg(JNIEnv *env, int averror, const char *context);

/* Copy a jstring into a caller-owned malloc'd standard UTF-8 C string. Because FFmpeg's boundary
 * is NUL-terminated, embedded U+0000 and malformed UTF-16 surrogate sequences are refused with a
 * typed bridge failure. NULL jstring gives NULL. The caller frees with free(). */
char *kj_string_dup(JNIEnv *env, jstring s);

/* New jstring from a strictly validated, NUL-terminated standard UTF-8 C string. The explicit
 * decoder supports supplementary code points. NULL c gives NULL without throwing. */
jstring kj_string_new(JNIEnv *env, const char *c);

/* New jbyteArray carrying an exact copy of len bytes. NULL data or negative len throws. */
jbyteArray kj_bytes_new(JNIEnv *env, const void *data, int32_t len);

/* New Java long array carrying an exact copy of count values. */
jlongArray kj_longs_new(JNIEnv *env, const jlong *values, int32_t count);

/* New Java int array carrying an exact copy of count values. */
jintArray kj_ints_new(JNIEnv *env, const jint *values, int32_t count);

/* The HDR metadata of a stream or a frame as one int array of KJ_HDR_INTS, or NULL when it has
 * neither half: [0] the mastering display flags (0 for none), [1..20] its ten num/den pairs,
 * [21] 1 when a content light level follows, [22] MaxCLL, [23] MaxFALL. */
#define KJ_HDR_INTS 24
jintArray kj_hdr_new(JNIEnv *env, int display_rc, const int *q, int flags, int light_rc, int max_cll, int max_fall);

/* Hands use() the Java array's bytes in place, then releases them unchanged, so a frame is copied
 * once. use runs inside a critical region: no JNI call, no lock, nothing that blocks. Returns use's
 * result, or -1 with an exception pending when the array cannot be read. */
int kj_bytes_read_in_place(JNIEnv *env, jbyteArray bytes,
                           int (*use)(void *ctx, const uint8_t *src, int32_t len), void *ctx);

/* A new Java array of len bytes that fill() writes in place, under the same rules as use() above.
 * When fill fails, *rc holds its result and NULL comes back; NULL with *rc == 0 means an exception
 * is pending. */
jbyteArray kj_bytes_filled_in_place(JNIEnv *env, int32_t len,
                                    int (*fill)(void *ctx, uint8_t *dst, int32_t len), void *ctx, int *rc);

/* ── Registration (kj_registration.c) ─────────────────────────────────────────────────────── */

/* Registers every methods.def row on its declared bridge class. Returns 0 on success, negative
 * on a missing class or failed RegisterNatives; JNI_OnLoad converts that into JNI_ERR. */
int kj_register_all(JNIEnv *env);

#ifdef __cplusplus
}
#endif

#endif /* KJ_INTERNAL_H */
