/* The Java bridge under native/kitecodec-jni, on paths that no JVM test can reach.
 *
 * WHY A C SUITE. Every call into the bridge comes from Java, so a JVM test can only drive what a
 * working JVM lets happen. A handle minted while a Java exception is pending is one such path: in
 * the open calls, the only JNI calls that could raise that exception fail only when the JVM is out
 * of memory. This suite compiles the bridge units into itself against the stand-in
 * tests/fake_headers/jni/jni.h and drives them with a fake JNIEnv, so it can raise the exception
 * on purpose.
 *
 * What is compiled: kj_format.c, kj_handles.c and the handle table kc_handles.c, unchanged. kj_util.c
 * is not: it builds Java strings and exceptions, and the stubs below stand in for it.
 *
 * What it cannot prove is how a particular JVM behaves. The fakes follow the JNI specification: an
 * exception stays pending on its thread until something clears it, and a native method's return
 * value is dropped when the method returns with an exception pending.
 */

#include "harness.h"

/* The bridge units, statics and all. Nothing else in this binary defines their symbols. */
#include "kc_handles.c"
#include "kj_handles.c"
#include "kj_format.c"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ---- Fake Java objects ---- */

enum fake_kind { FAKE_STRING = 1, FAKE_ARRAY };

/* One Java object. Strings own their text and arrays own their element slots, not the elements. */
struct _jobject {
    enum fake_kind kind;
    char *text;
    jobject *elements;
    jsize length;
};

static char *copy_text(const char *text)
{
    size_t bytes = strlen(text) + 1u;
    char *copy = (char *)malloc(bytes);
    KC_NOT_NULL(copy);
    memcpy(copy, text, bytes);
    return copy;
}

static jobject fake_string(const char *text)
{
    jobject object = (jobject)calloc(1, sizeof(struct _jobject));
    KC_NOT_NULL(object);
    object->kind = FAKE_STRING;
    object->text = copy_text(text);
    return object;
}

static jobject fake_array(jsize length)
{
    jobject object = (jobject)calloc(1, sizeof(struct _jobject));
    KC_NOT_NULL(object);
    object->kind = FAKE_ARRAY;
    object->elements = (jobject *)calloc((size_t)length, sizeof(jobject));
    KC_NOT_NULL(object->elements);
    object->length = length;
    return object;
}

static void fake_free(jobject object)
{
    if (object == NULL) return;
    free(object->text);
    free(object->elements);
    free(object);
}

/* ---- The pending Java exception, per thread as in JNI ---- */

static _Thread_local int pending;
static _Thread_local const char *pending_reason;

/* The first exception wins, as it does for the bridge's own throws. */
static void raise_pending(const char *reason)
{
    if (pending) return;
    pending = 1;
    pending_reason = reason;
}

static void clear_pending(void)
{
    pending = 0;
    pending_reason = NULL;
}

/* ---- The fake JNIEnv ---- */

static jboolean env_exception_check(JNIEnv *env)
{
    (void)env;
    return pending ? JNI_TRUE : JNI_FALSE;
}

static void env_exception_clear(JNIEnv *env)
{
    (void)env;
    clear_pending();
}

static jsize env_array_length(JNIEnv *env, jarray array)
{
    (void)env;
    KC_CHECK(array != NULL && array->kind == FAKE_ARRAY);
    return array->length;
}

static jobject env_array_get(JNIEnv *env, jobjectArray array, jsize index)
{
    (void)env;
    KC_CHECK(array != NULL && array->kind == FAKE_ARRAY);
    KC_CHECK(index >= 0 && index < array->length);
    return array->elements[index];
}

static void env_array_set(JNIEnv *env, jobjectArray array, jsize index, jobject value)
{
    (void)env;
    KC_CHECK(array != NULL && array->kind == FAKE_ARRAY);
    KC_CHECK(index >= 0 && index < array->length);
    array->elements[index] = value;
}

/* Local references are not modelled: every fake lives until its case frees it. */
static void env_delete_local(JNIEnv *env, jobject object)
{
    (void)env;
    (void)object;
}

/* The members no case in this suite reaches. Each one fails loudly rather than guessing. */
static jint env_unexpected_get_vm(JNIEnv *env, JavaVM **vm)
{
    (void)env; (void)vm;
    KC_FAIL("the bridge called GetJavaVM, which no case here expects");
}

static jclass env_unexpected_object_class(JNIEnv *env, jobject object)
{
    (void)env; (void)object;
    KC_FAIL("the bridge called GetObjectClass, which no case here expects");
}

static jmethodID env_unexpected_method_id(JNIEnv *env, jclass cls, const char *name, const char *sig)
{
    (void)env; (void)cls; (void)name; (void)sig;
    KC_FAIL("the bridge called GetMethodID, which no case here expects");
}

static jint env_unexpected_call_int(JNIEnv *env, jobject object, jmethodID method, ...)
{
    (void)env; (void)object; (void)method;
    KC_FAIL("the bridge called CallIntMethod, which no case here expects");
}

static jlong env_unexpected_call_long(JNIEnv *env, jobject object, jmethodID method, ...)
{
    (void)env; (void)object; (void)method;
    KC_FAIL("the bridge called CallLongMethod, which no case here expects");
}

static jobject env_unexpected_new_global(JNIEnv *env, jobject object)
{
    (void)env; (void)object;
    KC_FAIL("the bridge called NewGlobalRef, which no case here expects");
}

static void env_unexpected_delete_global(JNIEnv *env, jobject object)
{
    (void)env; (void)object;
    KC_FAIL("the bridge called DeleteGlobalRef, which no case here expects");
}

static jbyteArray env_unexpected_new_bytes(JNIEnv *env, jsize length)
{
    (void)env; (void)length;
    KC_FAIL("the bridge called NewByteArray, which no case here expects");
}

static void env_unexpected_get_bytes(JNIEnv *env, jbyteArray array, jsize start, jsize length,
                                     jbyte *buf)
{
    (void)env; (void)array; (void)start; (void)length; (void)buf;
    KC_FAIL("the bridge called GetByteArrayRegion, which no case here expects");
}

static void env_unexpected_set_longs(JNIEnv *env, jlongArray array, jsize start, jsize length,
                                     const jlong *buf)
{
    (void)env; (void)array; (void)start; (void)length; (void)buf;
    KC_FAIL("the bridge called SetLongArrayRegion, which no case here expects");
}

static const struct JNINativeInterface_ fake_env_functions = {
    .GetJavaVM = env_unexpected_get_vm,
    .ExceptionCheck = env_exception_check,
    .ExceptionClear = env_exception_clear,
    .GetObjectClass = env_unexpected_object_class,
    .GetMethodID = env_unexpected_method_id,
    .CallIntMethod = env_unexpected_call_int,
    .CallLongMethod = env_unexpected_call_long,
    .NewGlobalRef = env_unexpected_new_global,
    .DeleteGlobalRef = env_unexpected_delete_global,
    .DeleteLocalRef = env_delete_local,
    .GetArrayLength = env_array_length,
    .GetObjectArrayElement = env_array_get,
    .SetObjectArrayElement = env_array_set,
    .NewByteArray = env_unexpected_new_bytes,
    .GetByteArrayRegion = env_unexpected_get_bytes,
    .SetLongArrayRegion = env_unexpected_set_longs,
};

static JNIEnv fake_env = &fake_env_functions;
static JNIEnv *const env = &fake_env;

/* ---- Stand-ins for kj_util.c ---- */

/* When set, the next kj_string_new fails the way it does when the JVM is out of memory. */
static int fail_next_string;

void kj_throw_handle(JNIEnv *thrower, const char *msg)
{
    (void)thrower;
    raise_pending(msg);
}

void kj_throw_ffmpeg(JNIEnv *thrower, int averror, const char *context)
{
    (void)thrower;
    (void)averror;
    raise_pending(context);
}

char *kj_string_dup(JNIEnv *caller, jstring s)
{
    (void)caller;
    if (s == NULL) return NULL;
    KC_CHECK(s->kind == FAKE_STRING);
    return copy_text(s->text);
}

jstring kj_string_new(JNIEnv *caller, const char *c)
{
    (void)caller;
    if (c == NULL) return NULL;
    if (fail_next_string) {
        fail_next_string = 0;
        raise_pending("the JVM ran out of memory building a string");
        return NULL;
    }
    return fake_string(c);
}

jbyteArray kj_bytes_new(JNIEnv *caller, const void *data, int32_t len)
{
    (void)caller; (void)data; (void)len;
    KC_FAIL("the bridge copied bytes into a Java array, which no case here expects");
}

/* ---- Fixture ---- */

static char wav_path[1024];

static void remove_wav(void)
{
    remove(wav_path);
}

/* A tenth of a second of 8 kHz mono 16-bit silence, which FFmpeg's WAV demuxer opens. */
static void write_wav(void)
{
    static const unsigned char header[44] = {
        'R', 'I', 'F', 'F', 0x64, 0x06, 0, 0, 'W', 'A', 'V', 'E',
        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0,
        0x40, 0x1f, 0, 0, 0x80, 0x3e, 0, 0, 2, 0, 16, 0,
        'd', 'a', 't', 'a', 0x40, 0x06, 0, 0,
    };
    static const unsigned char silence[1600];
    const char *tmp = getenv("TMPDIR");
    const char *sep = "/";
    FILE *file;

    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    if (tmp[strlen(tmp) - 1] == '/') sep = "";
    snprintf(wav_path, sizeof(wav_path), "%s%skc_jni_bridge_%ld.wav", tmp, sep, (long)getpid());
    file = fopen(wav_path, "wb");
    KC_NOT_NULL(file);
    atexit(remove_wav);
    KC_EQ_SIZE(fwrite(header, 1, sizeof(header), file), sizeof(header));
    KC_EQ_SIZE(fwrite(silence, 1, sizeof(silence), file), sizeof(silence));
    KC_EQ_INT(fclose(file), 0);
}

/* Opens the fixture through the bridge with one option FFmpeg does not use, so the open reports
 * that key back through kj_string_new. [fail_report] makes that report fail. */
static jlong open_with_unused_option(jobjectArray unused_out, int fail_report)
{
    jobject path = fake_string(wav_path);
    jobject keys = fake_array(1);
    jobject values = fake_array(1);
    jlong token;

    keys->elements[0] = fake_string("kc_test_unused_option");
    values->elements[0] = fake_string("1");
    fail_next_string = fail_report;
    token = kj_fmt_open_input2(env, NULL, path, keys, values, unused_out);
    fail_next_string = 0;
    fake_free(keys->elements[0]);
    fake_free(values->elements[0]);
    fake_free(keys);
    fake_free(values);
    fake_free(path);
    return token;
}

/* ---- Cases: nothing is minted while a Java exception is pending ---- */

static int object_a;
static int object_b;

static void case_owned_mint_refused_while_pending(void)
{
    int64_t before = kj_handle_live_count();
    jlong token;

    kc_case("no owned handle is minted while a Java exception is pending");
    raise_pending("an exception the caller has not seen yet");
    token = kj_handle_put_checked(env, KJ_KIND_FRAME, &object_a);
    KC_EQ_I64(token, 0);
    KC_EQ_I64(kj_handle_live_count(), before);
    KC_EQ_STR(pending_reason, "an exception the caller has not seen yet");
    kc_note("the JVM would drop the token with the return value, and the frame it names with it");
    clear_pending();

    kc_case("with nothing pending, the same call mints");
    token = kj_handle_put_checked(env, KJ_KIND_FRAME, &object_a);
    KC_CHECK(token != 0);
    KC_EQ_I64(kj_handle_live_count(), before + 1);
    kj_handle_release(token, KJ_KIND_FRAME);
    KC_EQ_I64(kj_handle_live_count(), before);
}

static void case_borrowed_mint_refused_while_pending(void)
{
    int64_t before = kj_handle_live_count();
    int64_t parent = kj_handle_put(KJ_KIND_FMT_CTX, &object_a);
    jlong child;

    kc_case("no borrowed handle is minted while a Java exception is pending");
    KC_CHECK(parent != 0);
    raise_pending("an exception the caller has not seen yet");
    child = kj_handle_put_borrowed(env, KJ_KIND_STREAM, &object_b, parent);
    KC_EQ_I64(child, 0);
    KC_EQ_I64(kj_handle_live_count(), before + 1);
    clear_pending();

    kc_case("with nothing pending, the same call mints");
    child = kj_handle_put_borrowed(env, KJ_KIND_STREAM, &object_b, parent);
    KC_CHECK(child != 0);
    KC_EQ_I64(kj_handle_live_count(), before + 2);
    kj_handle_release(parent, KJ_KIND_FMT_CTX);
    KC_EQ_I64(kj_handle_live_count(), before);
}

static void case_open_reports_its_unused_option(void)
{
    int64_t before = kj_handle_live_count();
    jobject unused_out = fake_array(1);
    jlong token;

    kc_case("the fixture opens, and its unused option comes back");
    token = open_with_unused_option(unused_out, 0);
    KC_CHECK(token != 0);
    KC_CHECK(!pending);
    KC_NOT_NULL(unused_out->elements[0]);
    KC_EQ_STR(unused_out->elements[0]->text, "kc_test_unused_option");
    kj_fmt_close_input(env, NULL, token);
    KC_EQ_I64(kj_handle_live_count(), before);
    fake_free(unused_out->elements[0]);
    fake_free(unused_out);
}

/* The report of the unused key fails after FFmpeg opened the file. Before the fix the open went
 * on to mint a handle for the context, which the JVM then dropped with the exception. */
static void run_open_whose_report_fails(int measure)
{
    kc_alloc_counts before_allocs;
    int64_t before = kj_handle_live_count();
    jobject unused_out;
    jlong token;

    kc_alloc_snapshot(&before_allocs);
    unused_out = fake_array(1);
    token = open_with_unused_option(unused_out, 1);
    KC_EQ_I64(token, 0);
    KC_EQ_I64(kj_handle_live_count(), before);
    KC_CHECK(pending);
    KC_EQ_STR(pending_reason, "the JVM ran out of memory building a string");
    KC_NULL(unused_out->elements[0]);
    clear_pending();
    fake_free(unused_out);
    if (measure) KC_ALLOC_BALANCED(&before_allocs);
}

static void case_open_whose_report_fails_keeps_nothing(void)
{
    kc_case("an open whose unused-option report fails mints nothing and closes what it opened");
    run_open_whose_report_fails(0); /* warm-up: FFmpeg builds one-time state on its first open */
    run_open_whose_report_fails(1);
}

int main(void)
{
    kc_suite_begin("test_jni_bridge");
    write_wav();
    case_owned_mint_refused_while_pending();
    case_borrowed_mint_refused_while_pending();
    case_open_reports_its_unused_option();
    case_open_whose_report_fails_keeps_nothing();
    return kc_suite_end();
}
