/* Dynamic registration from the single manifest (S1.c.1 step 4).
 *
 * methods.def is included twice through the KJ_METHOD X-macro: once to forward-declare every C
 * function, once to build one JNINativeMethod table per bridge class. Today every row names one
 * class, so the table is one block; the grouping loop below still keys on the class string so a
 * second bridge class later needs no change here.
 *
 * JNI_OnLoad registers and returns JNI_VERSION_1_6. It deliberately does NOT call kc_init and
 * does NOT call kc_jvm_attach: the Kotlin leaf loader does both AFTER load so an identity
 * rejection arrives as a typed, inspectable Kotlin exception instead of an uninspectable
 * UnsatisfiedLinkError from inside library load (S1.c.2 step 5 records this reasoning).
 */

#include "kj_internal.h"

#include <string.h>

/* Pass 1: forward-declare every manifest row's C function. Only the SYMBOL matters here; the
 * real JNI signatures differ per row and JNINativeMethod's fnPtr holds an opaque pointer. A
 * descriptor that names no Kotlin method fails at RegisterNatives. A C function whose signature
 * differs from its descriptor would build and corrupt the call, so buildSrc's
 * CheckJniRegistrationTask compares the two before every JNI build. The declaration deliberately
 * has an empty parameter list, the one C form that accepts any signature at the definition site. */
typedef void (*kj_fnptr)(void);

#define KJ_METHOD(cls, name, desc, fn) extern void fn();
#include "methods.def"
#undef KJ_METHOD

typedef struct kj_row {
    const char *cls;
    const char *name;
    const char *desc;
    kj_fnptr fn;
} kj_row;

static const kj_row KJ_ROWS[] = {
#define KJ_METHOD(cls, name, desc, fn) { cls, name, desc, (kj_fnptr)fn },
#include "methods.def"
#undef KJ_METHOD
};

enum { KJ_ROW_COUNT = (int)(sizeof KJ_ROWS / sizeof KJ_ROWS[0]) };

int kj_register_all(JNIEnv *env)
{
    int i = 0;
    while (i < KJ_ROW_COUNT) {
        const char *cls = KJ_ROWS[i].cls;
        JNINativeMethod methods[KJ_ROW_COUNT];
        int n = 0, j = i;
        jclass klass;
        while (j < KJ_ROW_COUNT && strcmp(KJ_ROWS[j].cls, cls) == 0) {
            methods[n].name = (char *)KJ_ROWS[j].name;
            methods[n].signature = (char *)KJ_ROWS[j].desc;
            methods[n].fnPtr = (void *)KJ_ROWS[j].fn;
            n++; j++;
        }
        klass = (*env)->FindClass(env, cls);
        if (klass == NULL) return -1;
        if ((*env)->RegisterNatives(env, klass, methods, n) != 0) {
            (*env)->DeleteLocalRef(env, klass);
            return -2;
        }
        (*env)->DeleteLocalRef(env, klass);
        i = j;
    }
    return 0;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    (void)reserved;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    if (kj_register_all(env) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
