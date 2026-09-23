/* A stand-in for the JDK's <jni.h>, for tests/test_jni_bridge.c only.
 *
 * That suite compiles the Java bridge units under native/kitecodec-jni unchanged and runs them
 * against a fake JNIEnv and JavaVM, because some of their paths cannot be reached from a JVM. The
 * units include <jni.h>, and build-host.sh puts this directory on that one suite's include path, so
 * they get this file instead of the JDK's.
 *
 * Only what the compiled units use is declared. Names and signatures follow the JNI specification,
 * so the units compile as they are. The struct layout does not follow the JDK's, and nothing may
 * depend on it, because the suite's fakes are its only implementation. When a bridge unit starts to
 * use another JNIEnv or JavaVM member, add the member here, or test_jni_bridge stops compiling.
 */

#ifndef KC_TEST_FAKE_JNI_H
#define KC_TEST_FAKE_JNI_H

#include <stdint.h>

#define JNIEXPORT
#define JNICALL

#define JNI_FALSE 0
#define JNI_TRUE 1

#define JNI_OK 0
#define JNI_ERR (-1)
#define JNI_EDETACHED (-2)
#define JNI_EVERSION (-3)

#define JNI_VERSION_1_6 0x00010006

typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef uint16_t jchar;
typedef int32_t jint;
typedef int64_t jlong;
typedef jint jsize;

/* The suite defines struct _jobject, so its fake objects are what these handles point at. */
struct _jobject;
typedef struct _jobject *jobject;
typedef jobject jclass;
typedef jobject jthrowable;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray jbyteArray;
typedef jarray jintArray;
typedef jarray jlongArray;
typedef jarray jobjectArray;

struct _jmethodID;
typedef struct _jmethodID *jmethodID;

struct JNINativeInterface_;
struct JNIInvokeInterface_;
typedef const struct JNINativeInterface_ *JNIEnv;
typedef const struct JNIInvokeInterface_ *JavaVM;

struct JNINativeInterface_ {
    jint (*GetJavaVM)(JNIEnv *env, JavaVM **vm);
    jboolean (*ExceptionCheck)(JNIEnv *env);
    void (*ExceptionClear)(JNIEnv *env);
    jclass (*GetObjectClass)(JNIEnv *env, jobject obj);
    jmethodID (*GetMethodID)(JNIEnv *env, jclass clazz, const char *name, const char *sig);
    jint (*CallIntMethod)(JNIEnv *env, jobject obj, jmethodID method, ...);
    jlong (*CallLongMethod)(JNIEnv *env, jobject obj, jmethodID method, ...);
    jobject (*NewGlobalRef)(JNIEnv *env, jobject obj);
    void (*DeleteGlobalRef)(JNIEnv *env, jobject obj);
    void (*DeleteLocalRef)(JNIEnv *env, jobject obj);
    jsize (*GetArrayLength)(JNIEnv *env, jarray array);
    jobject (*GetObjectArrayElement)(JNIEnv *env, jobjectArray array, jsize index);
    void (*SetObjectArrayElement)(JNIEnv *env, jobjectArray array, jsize index, jobject value);
    jbyteArray (*NewByteArray)(JNIEnv *env, jsize length);
    void (*GetByteArrayRegion)(JNIEnv *env, jbyteArray array, jsize start, jsize length, jbyte *buf);
    void (*SetLongArrayRegion)(JNIEnv *env, jlongArray array, jsize start, jsize length,
                               const jlong *buf);
};

struct JNIInvokeInterface_ {
    jint (*GetEnv)(JavaVM *vm, void **env, jint version);
    jint (*AttachCurrentThread)(JavaVM *vm, void **env, void *args);
    jint (*DetachCurrentThread)(JavaVM *vm);
};

#endif /* KC_TEST_FAKE_JNI_H */
