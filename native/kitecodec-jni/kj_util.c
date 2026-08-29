/* JNI string, array and exception conversion (S1.c.1 step 5). This is the ONLY unit that may
 * construct JVM objects or throw; category units call these and return. Both exception classes
 * are Kotlin classes owned by the bridge (see methods.def's header block): keeping their binary
 * names in one place here and in the consumer keep rule is what lets R8 shrink everything else. */

#include "kj_internal.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define KJ_HANDLE_EXCEPTION "io/github/yuroyami/kiteffmpeg/JniHandleException"
#define KJ_FFMPEG_EXCEPTION "io/github/yuroyami/kiteffmpeg/JniNativeException"

/* JNI's *UTF entry points use modified UTF-8: supplementary code points are encoded as two
 * surrogate-shaped three-byte sequences and U+0000 is encoded as C0 80. KiteFFmpeg's C boundary,
 * FFmpeg and the host filesystem use standard, NUL-terminated UTF-8 instead. Keep that impedance
 * match here, in the one JNI conversion unit, rather than leaking either encoding into a category
 * unit. The raw C-to-Java helper reports validation failures without throwing so exception
 * construction can use it without recursing through kj_throw_handle. */
static jstring kj_utf8_to_jstring(JNIEnv *env, const char *c, const char **failure)
{
    const unsigned char *bytes = (const unsigned char *)c;
    size_t byte_len;
    size_t offset = 0;
    size_t units = 0;
    jchar local_utf16[512];
    jchar *utf16;
    int heap_utf16 = 0;
    jstring result;

    *failure = NULL;
    byte_len = strlen(c);
    while (offset < byte_len) {
        uint32_t code_point;
        unsigned char first = bytes[offset];
        size_t width;

        if (first <= 0x7f) {
            code_point = first;
            width = 1;
        } else if (first >= 0xc2 && first <= 0xdf &&
                   offset + 1 < byte_len &&
                   (bytes[offset + 1] & 0xc0) == 0x80) {
            code_point = ((uint32_t)(first & 0x1f) << 6) |
                         (uint32_t)(bytes[offset + 1] & 0x3f);
            width = 2;
        } else if (first >= 0xe0 && first <= 0xef &&
                   offset + 2 < byte_len &&
                   (bytes[offset + 1] & 0xc0) == 0x80 &&
                   (bytes[offset + 2] & 0xc0) == 0x80 &&
                   !(first == 0xe0 && bytes[offset + 1] < 0xa0) &&
                   !(first == 0xed && bytes[offset + 1] >= 0xa0)) {
            code_point = ((uint32_t)(first & 0x0f) << 12) |
                         ((uint32_t)(bytes[offset + 1] & 0x3f) << 6) |
                         (uint32_t)(bytes[offset + 2] & 0x3f);
            width = 3;
        } else if (first >= 0xf0 && first <= 0xf4 &&
                   offset + 3 < byte_len &&
                   (bytes[offset + 1] & 0xc0) == 0x80 &&
                   (bytes[offset + 2] & 0xc0) == 0x80 &&
                   (bytes[offset + 3] & 0xc0) == 0x80 &&
                   !(first == 0xf0 && bytes[offset + 1] < 0x90) &&
                   !(first == 0xf4 && bytes[offset + 1] >= 0x90)) {
            code_point = ((uint32_t)(first & 0x07) << 18) |
                         ((uint32_t)(bytes[offset + 1] & 0x3f) << 12) |
                         ((uint32_t)(bytes[offset + 2] & 0x3f) << 6) |
                         (uint32_t)(bytes[offset + 3] & 0x3f);
            width = 4;
        } else {
            *failure = "native string conversion refused: malformed standard UTF-8";
            return NULL;
        }
        if (units > (size_t)INT32_MAX - (code_point > 0xffffu ? 2u : 1u)) {
            *failure = "native string conversion refused: result exceeds the JNI string limit";
            return NULL;
        }
        units += code_point > 0xffffu ? 2u : 1u;
        offset += width;
    }

    if (units <= sizeof local_utf16 / sizeof local_utf16[0]) {
        utf16 = local_utf16;
    } else {
        utf16 = (jchar *)malloc(units * sizeof(jchar));
        if (utf16 == NULL) {
            *failure = "out of memory converting a native UTF-8 string";
            return NULL;
        }
        heap_utf16 = 1;
    }
    offset = 0;
    units = 0;
    while (offset < byte_len) {
        uint32_t code_point;
        unsigned char first = bytes[offset];
        size_t width;

        if (first <= 0x7f) {
            code_point = first;
            width = 1;
        } else if (first <= 0xdf) {
            code_point = ((uint32_t)(first & 0x1f) << 6) |
                         (uint32_t)(bytes[offset + 1] & 0x3f);
            width = 2;
        } else if (first <= 0xef) {
            code_point = ((uint32_t)(first & 0x0f) << 12) |
                         ((uint32_t)(bytes[offset + 1] & 0x3f) << 6) |
                         (uint32_t)(bytes[offset + 2] & 0x3f);
            width = 3;
        } else {
            code_point = ((uint32_t)(first & 0x07) << 18) |
                         ((uint32_t)(bytes[offset + 1] & 0x3f) << 12) |
                         ((uint32_t)(bytes[offset + 2] & 0x3f) << 6) |
                         (uint32_t)(bytes[offset + 3] & 0x3f);
            width = 4;
        }
        if (code_point <= 0xffffu) {
            utf16[units++] = (jchar)code_point;
        } else {
            code_point -= 0x10000u;
            utf16[units++] = (jchar)(0xd800u + (code_point >> 10));
            utf16[units++] = (jchar)(0xdc00u + (code_point & 0x3ffu));
        }
        offset += width;
    }
    result = (*env)->NewString(env, utf16, (jsize)units);
    if (heap_utf16) free(utf16);
    return result;
}

static void kj_throw_named(JNIEnv *env, const char *class_name, const char *msg)
{
    jclass cls;
    jmethodID constructor;
    jstring message;
    jobject exception;
    const char *failure;

    if ((*env)->ExceptionCheck(env)) return; /* first throw wins */
    cls = (*env)->FindClass(env, class_name);
    if (cls == NULL) {
        /* The bridge class set is broken; fall back to something that always exists so the
         * caller still sees a typed failure rather than a silent NULL. */
        (*env)->ExceptionClear(env);
        cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (cls == NULL) return;
    }
    constructor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V");
    if (constructor == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    message = kj_utf8_to_jstring(env, msg, &failure);
    if (message == NULL && !(*env)->ExceptionCheck(env)) {
        message = kj_utf8_to_jstring(
            env,
            failure != NULL ? failure : "native bridge exception",
            &failure
        );
    }
    if (message == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    exception = (*env)->NewObject(env, cls, constructor, message);
    (*env)->DeleteLocalRef(env, message);
    if (exception != NULL) {
        (*env)->Throw(env, (jthrowable)exception);
        (*env)->DeleteLocalRef(env, exception);
    }
    (*env)->DeleteLocalRef(env, cls);
}

void kj_throw_handle(JNIEnv *env, const char *msg)
{
    kj_throw_named(env, KJ_HANDLE_EXCEPTION, msg);
}

void kj_throw_ffmpeg(JNIEnv *env, int averror, const char *context)
{
    /* ffkmp_strerror returns a thread-local buffer the next call overwrites; snprintf copies it
     * into msg before anything else can run on this thread, which is the documented discipline. */
    const char *text = ffkmp_strerror(averror);
    char msg[384];
    snprintf(msg, sizeof msg, "%d|%s|%s",
             averror, context ? context : "", text ? text : "unknown FFmpeg error");
    kj_throw_named(env, KJ_FFMPEG_EXCEPTION, msg);
}

char *kj_string_dup(JNIEnv *env, jstring s)
{
    const jchar *utf16;
    char *copy;
    jsize units;
    jsize index;
    size_t bytes = 0;
    size_t offset = 0;
    const char *failure = NULL;

    if (s == NULL) return NULL;
    units = (*env)->GetStringLength(env, s);
    utf16 = (*env)->GetStringChars(env, s, NULL);
    if (utf16 == NULL) return NULL; /* OOM already thrown */

    for (index = 0; index < units; index++) {
        uint32_t code_point = utf16[index];
        size_t width;

        if (code_point == 0) {
            failure = "string conversion refused: embedded NUL cannot cross a C string boundary";
            break;
        }
        if (code_point >= 0xd800u && code_point <= 0xdbffu) {
            uint32_t low;
            if (index + 1 >= units || utf16[index + 1] < 0xdc00u || utf16[index + 1] > 0xdfffu) {
                failure = "string conversion refused: unpaired UTF-16 surrogate";
                break;
            }
            low = utf16[++index];
            code_point = 0x10000u + ((code_point - 0xd800u) << 10) + (low - 0xdc00u);
        } else if (code_point >= 0xdc00u && code_point <= 0xdfffu) {
            failure = "string conversion refused: unpaired UTF-16 surrogate";
            break;
        }
        width = code_point <= 0x7fu ? 1u :
                code_point <= 0x7ffu ? 2u :
                code_point <= 0xffffu ? 3u : 4u;
        if (bytes > SIZE_MAX - width - 1u) {
            failure = "string conversion refused: UTF-8 result is too large";
            break;
        }
        bytes += width;
    }
    if (failure != NULL) {
        (*env)->ReleaseStringChars(env, s, utf16);
        kj_throw_handle(env, failure);
        return NULL;
    }
    copy = (char *)malloc(bytes + 1u);
    if (copy == NULL) {
        (*env)->ReleaseStringChars(env, s, utf16);
        kj_throw_handle(env, "out of memory copying a string across the JNI boundary");
        return NULL;
    }
    for (index = 0; index < units; index++) {
        uint32_t code_point = utf16[index];
        if (code_point >= 0xd800u && code_point <= 0xdbffu) {
            uint32_t low = utf16[++index];
            code_point = 0x10000u + ((code_point - 0xd800u) << 10) + (low - 0xdc00u);
        }
        if (code_point <= 0x7fu) {
            copy[offset++] = (char)code_point;
        } else if (code_point <= 0x7ffu) {
            copy[offset++] = (char)(0xc0u | (code_point >> 6));
            copy[offset++] = (char)(0x80u | (code_point & 0x3fu));
        } else if (code_point <= 0xffffu) {
            copy[offset++] = (char)(0xe0u | (code_point >> 12));
            copy[offset++] = (char)(0x80u | ((code_point >> 6) & 0x3fu));
            copy[offset++] = (char)(0x80u | (code_point & 0x3fu));
        } else {
            copy[offset++] = (char)(0xf0u | (code_point >> 18));
            copy[offset++] = (char)(0x80u | ((code_point >> 12) & 0x3fu));
            copy[offset++] = (char)(0x80u | ((code_point >> 6) & 0x3fu));
            copy[offset++] = (char)(0x80u | (code_point & 0x3fu));
        }
    }
    copy[offset] = '\0';
    (*env)->ReleaseStringChars(env, s, utf16);
    return copy;
}

jstring kj_string_new(JNIEnv *env, const char *c)
{
    const char *failure;
    jstring result;

    if (c == NULL) return NULL;
    result = kj_utf8_to_jstring(env, c, &failure);
    if (result == NULL && !(*env)->ExceptionCheck(env)) {
        kj_throw_handle(env, failure != NULL ? failure : "native UTF-8 conversion failed");
    }
    return result;
}

jbyteArray kj_bytes_new(JNIEnv *env, const void *data, int32_t len)
{
    jbyteArray out;
    if (data == NULL || len < 0) {
        kj_throw_handle(env, "byte copy across the JNI boundary refused: NULL data or negative length");
        return NULL;
    }
    out = (*env)->NewByteArray(env, (jsize)len);
    if (out == NULL) return NULL; /* OOM already thrown */
    if (len > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)data);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    return out;
}

jlongArray kj_longs_new(JNIEnv *env, const jlong *values, int32_t count)
{
    jlongArray out;
    if (values == NULL || count < 0) {
        kj_throw_handle(env, "long copy across the JNI boundary refused: NULL data or negative length");
        return NULL;
    }
    out = (*env)->NewLongArray(env, (jsize)count);
    if (out == NULL) return NULL;
    if (count > 0) {
        (*env)->SetLongArrayRegion(env, out, 0, (jsize)count, values);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    return out;
}

int kj_bytes_dup(JNIEnv *env, jbyteArray bytes, uint8_t **out, int32_t *out_len)
{
    jsize len;
    uint8_t *copy;
    if (out == NULL || out_len == NULL || bytes == NULL) {
        kj_throw_handle(env, "byte copy into native memory refused: NULL argument");
        return -1;
    }
    *out = NULL;
    *out_len = 0;
    len = (*env)->GetArrayLength(env, bytes);
    copy = (uint8_t *)malloc(len > 0 ? (size_t)len : 1u);
    if (copy == NULL) {
        kj_throw_handle(env, "out of memory copying a Java byte array");
        return -1;
    }
    if (len > 0) {
        (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte *)copy);
        if ((*env)->ExceptionCheck(env)) { free(copy); return -1; }
    }
    *out = copy;
    *out_len = (int32_t)len;
    return 0;
}

int kj_longs_dup(JNIEnv *env, jlongArray values, jlong **out, int32_t *out_len)
{
    jsize len;
    jlong *copy;
    if (out == NULL || out_len == NULL || values == NULL) {
        kj_throw_handle(env, "long-array copy refused: NULL argument"); return -1;
    }
    *out = NULL; *out_len = 0;
    len = (*env)->GetArrayLength(env, values);
    copy = (jlong *)malloc(len > 0 ? (size_t)len * sizeof(jlong) : sizeof(jlong));
    if (copy == NULL) { kj_throw_handle(env, "out of memory copying a Java long array"); return -1; }
    if (len > 0) {
        (*env)->GetLongArrayRegion(env, values, 0, len, copy);
        if ((*env)->ExceptionCheck(env)) { free(copy); return -1; }
    }
    *out = copy; *out_len = (int32_t)len; return 0;
}

int kj_ints_dup(JNIEnv *env, jintArray values, int **out, int32_t *out_len)
{
    jsize len;
    int *copy;
    if (out == NULL || out_len == NULL || values == NULL) {
        kj_throw_handle(env, "int-array copy refused: NULL argument"); return -1;
    }
    *out = NULL; *out_len = 0;
    len = (*env)->GetArrayLength(env, values);
    copy = (int *)malloc(len > 0 ? (size_t)len * sizeof(int) : sizeof(int));
    if (copy == NULL) { kj_throw_handle(env, "out of memory copying a Java int array"); return -1; }
    if (len > 0) {
        (*env)->GetIntArrayRegion(env, values, 0, len, (jint *)copy);
        if ((*env)->ExceptionCheck(env)) { free(copy); return -1; }
    }
    *out = copy; *out_len = (int32_t)len; return 0;
}
