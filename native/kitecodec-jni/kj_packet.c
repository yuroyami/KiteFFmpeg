/* Category unit: packets (methods.def section "packet"). Follows kj_abi.c's canonical pattern. */

#include "kj_internal.h"

JNIEXPORT jlong JNICALL kj_packet_alloc(JNIEnv *env, jclass cls)
{
    kc_packet *p = ffkmp_packet_alloc();
    jlong token;
    (void)cls;
    if (p == NULL) { kj_throw_handle(env, "packet allocation failed"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_PACKET, p);
    if (token == 0) ffkmp_packet_free(p);
    return token;
}

JNIEXPORT void JNICALL kj_packet_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_close(token, KJ_KIND_PACKET);
    (void)env; (void)cls;
    ffkmp_packet_free(p); /* NULL-safe; double close resolved to NULL by the table */
}

JNIEXPORT jlong JNICALL kj_packet_clone(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *src = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    kc_packet *out;
    jlong out_token;
    (void)cls;
    if (src == NULL) return 0;
    out = ffkmp_packet_clone(src);
    if (out == NULL) { kj_throw_handle(env, "packet clone failed"); return 0; }
    out_token = kj_handle_put_checked(env, KJ_KIND_PACKET, out);
    if (out_token == 0) ffkmp_packet_free(out);
    return out_token;
}

JNIEXPORT jlong JNICALL kj_packet_pts(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_pts(p) : 0;
}

JNIEXPORT jlong JNICALL kj_packet_dts(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_dts(p) : 0;
}

JNIEXPORT jlong JNICALL kj_packet_duration(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_duration(p) : 0;
}

JNIEXPORT jint JNICALL kj_packet_stream_index(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jint)ffkmp_packet_stream_index(p) : -1;
}

JNIEXPORT jint JNICALL kj_packet_size(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jint)ffkmp_packet_size(p) : 0;
}

JNIEXPORT jboolean JNICALL kj_packet_is_keyframe(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return (p && ffkmp_packet_is_keyframe(p)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL kj_packet_is_discard(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return (p && ffkmp_packet_is_discard(p)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL kj_packet_skip_start(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls;
    return p ? (jlong)ffkmp_packet_skip_start(p) : 0;
}

JNIEXPORT jlong JNICALL kj_packet_position(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; return p ? (jlong)ffkmp_packet_pos(p) : -1;
}

JNIEXPORT void JNICALL kj_packet_unref(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; if (p != NULL) ffkmp_packet_unref(p);
}

JNIEXPORT void JNICALL kj_packet_set_stream_index(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; if (p != NULL) ffkmp_packet_set_stream_index(p, (int)value);
}

JNIEXPORT void JNICALL kj_packet_set_pts(JNIEnv *env, jclass cls, jlong token, jlong value)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; if (p != NULL) ffkmp_packet_set_pts(p, (int64_t)value);
}

JNIEXPORT void JNICALL kj_packet_set_dts(JNIEnv *env, jclass cls, jlong token, jlong value)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; if (p != NULL) ffkmp_packet_set_dts(p, (int64_t)value);
}

JNIEXPORT void JNICALL kj_packet_set_duration(JNIEnv *env, jclass cls, jlong token, jlong value)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; if (p != NULL) ffkmp_packet_set_duration(p, (int64_t)value);
}

JNIEXPORT void JNICALL kj_packet_rescale(JNIEnv *env, jclass cls, jlong token,
                                         jint sn, jint sd, jint dn, jint dd)
{
    kc_packet *p = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    (void)cls; if (p != NULL) ffkmp_packet_rescale_ts(p, sn, sd, dn, dd);
}

JNIEXPORT void JNICALL kj_packet_move_ref(JNIEnv *env, jclass cls, jlong dst_token,
                                          jlong src_token)
{
    kc_packet *dst = (kc_packet *)kj_handle_get(env, dst_token, KJ_KIND_PACKET);
    kc_packet *src;
    (void)cls;
    if (dst == NULL) return;
    src = (kc_packet *)kj_handle_get(env, src_token, KJ_KIND_PACKET);
    if (src != NULL) ffkmp_packet_move_ref(dst, src);
}

JNIEXPORT jbyteArray JNICALL kj_packet_bytes(JNIEnv *env, jclass cls, jlong token)
{
    kc_packet *packet = (kc_packet *)kj_handle_get(env, token, KJ_KIND_PACKET);
    int size;
    (void)cls;
    if (packet == NULL) return NULL;
    size = ffkmp_packet_size(packet);
    if (size == 0) {
        static const uint8_t empty = 0;
        return kj_bytes_new(env, &empty, 0);
    }
    return kj_bytes_new(env, ffkmp_packet_data(packet), size);
}
