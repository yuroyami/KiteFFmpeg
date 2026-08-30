/* Category unit: identity, capability and error plumbing (methods.def section "abi").
 *
 * THE CANONICAL PATTERN used by every implemented category unit:
 *   - one function per manifest row, named exactly as the row names it;
 *   - resolve every incoming token with kj_handle_get and return immediately on NULL (the typed
 *     exception is already pending);
 *   - operate only through kc_ or ffkmp_ helpers; bounded compositions are allowed where a row
 *     assembles one identity report, graph, or copied Java value;
 *   - mint outgoing objects with kj_handle_put; convert text with kj_util; NEVER include a libav
 *     header, NEVER spell a direct FFmpeg call (scripts/source-discipline.sh fails the build).
 */

#include "kj_internal.h"
#include "kj_append.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jint JNICALL kj_abi_version(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)kc_abi_version();
}

JNIEXPORT jint JNICALL kj_abi_init(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)kc_init();
}

JNIEXPORT jint JNICALL kj_abi_attach_current_vm(JNIEnv *env, jclass cls)
{
    JavaVM *vm = NULL;
    (void)cls;
    if ((*env)->GetJavaVM(env, &vm) != 0 || vm == NULL) {
        return KC_JVM_BAD_ARGUMENT;
    }
    return (jint)kc_jvm_attach(vm);
}

/* The full identity report as one parseable string. Kotlin (Internals.jvm.kt) splits on '\x1f'
 * (unit separator, cannot appear in any report text) and rebuilds the same typed report native
 * consumers read. Field order, fixed: status, bypassed, abi_major, abi_minor, then per library
 * i in 0..5: header M.m.p, runtime M.m.p, verdict; then configuration_agrees,
 * configuration_disagreed_count, configuration_disagreed, build_ffmpeg_ref,
 * build_license_flavour, build_provisioning_dir, runtime_version_info, runtime_license,
 * provisioning. 4 + 6*3 + 9 = 31 fields. */
JNIEXPORT jstring JNICALL kj_abi_identity_report(JNIEnv *env, jclass cls)
{
    kc_ffmpeg_report r;
    char buf[4096];
    int off = 0, i;
    (void)cls;
    kc_ffmpeg_report_get(&r);
    /* Every append is checked. Seven of these fields are strings of unbounded length, and
       the old `off += snprintf(...)` chain would have walked `buf + off` out of the array on the
       first one that did not fit. A report that does not fit is refused, never truncated: the
       Kotlin side splits it into a fixed 31 fields, so a short one parses into wrong values
       instead of failing. */
    if (kj_append(buf, sizeof buf, &off, "%d\x1f%d\x1f%d\x1f%d",
                  r.status, r.bypassed, r.abi_major, r.abi_minor) != 0) {
        kj_throw_handle(env, "the FFmpeg identity report does not fit its buffer");
        return NULL;
    }
    for (i = 0; i < KC_FFMPEG_LIBRARY_COUNT; i++) {
        if (kj_append(buf, sizeof buf, &off,
                      "\x1f%d.%d.%d\x1f%d.%d.%d\x1f%d",
                      r.header_major[i], r.header_minor[i], r.header_micro[i],
                      r.runtime_major[i], r.runtime_minor[i], r.runtime_micro[i],
                      r.verdict[i]) != 0) {
            kj_throw_handle(env, "the FFmpeg identity report does not fit its buffer");
            return NULL;
        }
    }
    if (kj_append(buf, sizeof buf, &off,
                  "\x1f%d\x1f%d\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s",
                  r.configuration_agrees, r.configuration_disagreed_count,
                  r.configuration_disagreed, r.build_ffmpeg_ref, r.build_license_flavour,
                  r.build_provisioning_dir, r.runtime_version_info, r.runtime_license,
                  r.provisioning) != 0) {
        kj_throw_handle(env, "the FFmpeg identity report does not fit its buffer");
        return NULL;
    }
    return kj_string_new(env, buf);
}

JNIEXPORT jstring JNICALL kj_abi_configuration(JNIEnv *env, jclass cls)
{
    (void)cls;
    return kj_string_new(env, kc_ffmpeg_configuration());
}

JNIEXPORT jboolean JNICALL kj_abi_has_decoder(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    jboolean found;
    (void)cls;
    if (c == NULL) return JNI_FALSE;
    found = ffkmp_find_decoder_by_name(c) != NULL ? JNI_TRUE : JNI_FALSE;
    free(c);
    return found;
}

JNIEXPORT jboolean JNICALL kj_abi_has_encoder(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    jboolean found;
    (void)cls;
    if (c == NULL) return JNI_FALSE;
    found = ffkmp_find_encoder_by_name(c) != NULL ? JNI_TRUE : JNI_FALSE;
    free(c);
    return found;
}

JNIEXPORT jboolean JNICALL kj_abi_has_filter(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    jboolean found;
    (void)cls;
    if (c == NULL) return JNI_FALSE;
    found = ffkmp_filter_exists(c) != 0 ? JNI_TRUE : JNI_FALSE;
    free(c);
    return found;
}

JNIEXPORT jint JNICALL kj_abi_error_eagain(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)ffkmp_averror_eagain();
}

JNIEXPORT jint JNICALL kj_abi_error_eof(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)ffkmp_averror_eof();
}

JNIEXPORT jstring JNICALL kj_abi_strerror(JNIEnv *env, jclass cls, jint errnum)
{
    (void)cls;
    return kj_string_new(env, ffkmp_strerror((int)errnum));
}

JNIEXPORT jint JNICALL kj_abi_media_type_video(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_video(); }

JNIEXPORT jint JNICALL kj_abi_media_type_audio(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_audio(); }

JNIEXPORT jint JNICALL kj_abi_media_type_subtitle(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_subtitle(); }

JNIEXPORT jint JNICALL kj_abi_media_type_data(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_data(); }

JNIEXPORT jint JNICALL kj_abi_media_type_attachment(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_media_type_attachment(); }

JNIEXPORT jlong JNICALL kj_abi_live_handles(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jlong)kj_handle_live_count();
}

JNIEXPORT jlong JNICALL kj_abi_rescale(JNIEnv *env, jclass cls, jlong value,
                                       jint sn, jint sd, jint dn, jint dd)
{
    (void)env; (void)cls;
    return (jlong)ffkmp_rescale_q((int64_t)value, (int)sn, (int)sd, (int)dn, (int)dd);
}

JNIEXPORT jstring JNICALL kj_abi_pixel_format_name(JNIEnv *env, jclass cls, jint value)
{ (void)cls; return kj_string_new(env, ffkmp_pix_fmt_name((int)value)); }

JNIEXPORT jint JNICALL kj_abi_pixel_format_value(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name); int out;
    (void)cls; if (c == NULL) return -1; out = ffkmp_pix_fmt_from_name(c); free(c); return (jint)out;
}

JNIEXPORT jstring JNICALL kj_abi_sample_format_name(JNIEnv *env, jclass cls, jint value)
{ (void)cls; return kj_string_new(env, ffkmp_sample_fmt_name((int)value)); }

JNIEXPORT jint JNICALL kj_abi_sample_format_value(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name); int out;
    (void)cls; if (c == NULL) return -1; out = ffkmp_sample_fmt_from_name(c); free(c); return (jint)out;
}

JNIEXPORT jint JNICALL kj_abi_seek_flag_backward(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_avseek_flag_backward(); }
JNIEXPORT jint JNICALL kj_abi_seek_flag_any(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_avseek_flag_any(); }
JNIEXPORT jint JNICALL kj_abi_disposition_default(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_default(); }
JNIEXPORT jint JNICALL kj_abi_disposition_forced(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_forced(); }
JNIEXPORT jint JNICALL kj_abi_disposition_hearing(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_hearing_impaired(); }
JNIEXPORT jint JNICALL kj_abi_disposition_visual(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_visual_impaired(); }
JNIEXPORT jint JNICALL kj_abi_disposition_attached(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_attached_pic(); }
