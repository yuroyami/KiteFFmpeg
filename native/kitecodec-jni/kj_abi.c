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

/* The log bridge. The Kotlin side keeps the sink, so C forwards every line to one static method,
 * Internals.onNativeLog, resolved when the first level is set. FFmpeg logs on its own worker
 * threads too, so a thread the JVM does not know is attached for the one call and detached after
 * it, as the byte-source bridge in kj_format.c does. A pending exception on the logging thread is
 * set aside for the call and restored after it, because calling Java with one pending is illegal. */
static JavaVM *kj_log_vm = NULL;
static jclass kj_log_class = NULL;
static jmethodID kj_log_method = NULL;

static void kj_log_forward(int level, const char *component, const char *message)
{
    JNIEnv *env = NULL;
    int attached = 0;
    jthrowable pending;
    jstring jcomponent;
    jstring jmessage;

    if (kj_log_vm == NULL) return;
    if ((*kj_log_vm)->GetEnv(kj_log_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
#ifdef __ANDROID__
        if ((*kj_log_vm)->AttachCurrentThread(kj_log_vm, &env, NULL) != JNI_OK) return;
#else
        if ((*kj_log_vm)->AttachCurrentThread(kj_log_vm, (void **)&env, NULL) != JNI_OK) return;
#endif
        attached = 1;
    }
    pending = (*env)->ExceptionOccurred(env);
    if (pending != NULL) (*env)->ExceptionClear(env);
    jcomponent = kj_string_new(env, component);
    jmessage = jcomponent != NULL ? kj_string_new(env, message) : NULL;
    if (jcomponent != NULL && jmessage != NULL) {
        (*env)->CallStaticVoidMethod(env, kj_log_class, kj_log_method, (jint)level, jcomponent, jmessage);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jmessage != NULL) (*env)->DeleteLocalRef(env, jmessage);
    if (jcomponent != NULL) (*env)->DeleteLocalRef(env, jcomponent);
    if (pending != NULL) {
        (*env)->Throw(env, pending);
        (*env)->DeleteLocalRef(env, pending);
    }
    if (attached) (*kj_log_vm)->DetachCurrentThread(kj_log_vm);
}

/* A negative level drops every line. The Kotlin caller serialises these calls, so the one-time
 * resolution below never races itself, and the atomic store inside ffkmp_log_set_sink publishes it
 * to the threads that log. The externals are members of the Internals object, so the second
 * argument is that object and its class is looked up from it. */
JNIEXPORT void JNICALL kj_abi_set_log_level(JNIEnv *env, jclass cls, jint level)
{
    if (kj_log_class == NULL) {
        jclass klass = (*env)->GetObjectClass(env, (jobject)cls);
        jmethodID method;
        jclass global;
        if (klass == NULL) return;
        method = (*env)->GetStaticMethodID(env, klass, "onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
        if (method == NULL) {
            (*env)->DeleteLocalRef(env, klass);
            return;
        }
        if ((*env)->GetJavaVM(env, &kj_log_vm) != 0) {
            (*env)->DeleteLocalRef(env, klass);
            kj_throw_handle(env, "log sink: GetJavaVM failed");
            return;
        }
        global = (jclass)(*env)->NewGlobalRef(env, klass);
        (*env)->DeleteLocalRef(env, klass);
        if (global == NULL) return;
        kj_log_method = method;
        kj_log_class = global;
    }
    ffkmp_log_set_sink(level >= 0 ? kj_log_forward : NULL, (int)level);
}

/* Begins a capture of the error lines FFmpeg logs on this thread (#170). 0, or a negative AVERROR when
 * none could begin, in which case nativeLogCaptureEnd must not be called for it. */
JNIEXPORT jint JNICALL kj_abi_log_capture_begin(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)ffkmp_log_capture_begin();
}

/* Ends this thread's innermost capture and hands its lines over as one flat array, three entries a
 * line: the level in decimal, the component and the message. The capture is freed here whatever
 * happens, so a failed conversion loses the lines and never the memory. NULL with an exception
 * pending when the array or a string cannot be made, and NULL with none when no capture was open. */
JNIEXPORT jobjectArray JNICALL kj_abi_log_capture_end(JNIEnv *env, jclass cls)
{
    kc_log_capture *lines = ffkmp_log_capture_end();
    jobjectArray out = NULL;
    jclass string_class;
    int count;
    int i;
    (void)cls;
    if (lines == NULL) return NULL;
    count = ffkmp_log_capture_count(lines);
    string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class != NULL) out = (*env)->NewObjectArray(env, (jsize)count * 3, string_class, NULL);
    for (i = 0; out != NULL && i < count; i++) {
        char level[16];
        const char *fields[3];
        int f;
        snprintf(level, sizeof level, "%d", ffkmp_log_capture_level(lines, i));
        fields[0] = level;
        fields[1] = ffkmp_log_capture_component(lines, i);
        fields[2] = ffkmp_log_capture_message(lines, i);
        for (f = 0; f < 3; f++) {
            jstring field = kj_string_new(env, fields[f]);
            if (field == NULL) {
                (*env)->DeleteLocalRef(env, out);
                out = NULL;
                break;
            }
            (*env)->SetObjectArrayElement(env, out, (jsize)(i * 3 + f), field);
            (*env)->DeleteLocalRef(env, field);
        }
    }
    if (string_class != NULL) (*env)->DeleteLocalRef(env, string_class);
    ffkmp_log_capture_free(&lines);
    return out;
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
JNIEXPORT jint JNICALL kj_abi_disposition_descriptions(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_descriptions(); }
JNIEXPORT jint JNICALL kj_abi_disposition_comment(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return (jint)ffkmp_disposition_comment(); }

/* Every component of one KC_COMPONENT_* kind the linked FFmpeg contains, newline separated. */
JNIEXPORT jstring JNICALL kj_component_names(JNIEnv *env, jclass cls, jint kind)
{
    int needed = ffkmp_component_names(kind, NULL, 0);
    char *buf;
    jstring names;
    (void)cls;
    if (needed < 0) { kj_throw_ffmpeg(env, needed, "component_names"); return NULL; }
    buf = (char *)malloc((size_t)needed + 1);
    if (buf == NULL) { kj_throw_handle(env, "component_names: out of memory"); return NULL; }
    ffkmp_component_names(kind, buf, needed + 1);
    names = kj_string_new(env, buf);
    free(buf);
    return names;
}
