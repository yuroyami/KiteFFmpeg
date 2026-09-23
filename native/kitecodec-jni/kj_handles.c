/* The three handle entry points that need a JNIEnv, and nothing else.
 *
 * The table itself moved to native/kitecodec-handles so the
 * web binding runs the SAME code rather than a second copy. What stays here is exactly what could
 * not go: these three throw, and throwing needs a JNIEnv. Every one of them is a thin wrapper whose
 * only job is turning the table's "0" or "NULL" into a typed JVM exception, which is the contract
 * kj_internal.h describes.
 */

#include "kj_internal.h"

#include <stdio.h>

jlong kj_handle_put_checked(JNIEnv *env, int kind, void *ptr)
{
    jlong token;
    /* A token minted while an exception is pending never reaches Kotlin: the JVM throws and drops
     * the return value, so the object the token names would leak. Refusing sends the caller down
     * its "no token" path, which frees that object. */
    if ((*env)->ExceptionCheck(env)) return 0;
    token = kj_handle_put(kind, ptr);
    if (ptr != NULL && token == 0) {
        kj_throw_handle(env, "native handle table is full");
    }
    return token;
}

jlong kj_handle_put_borrowed(JNIEnv *env, int kind, void *ptr, jlong parent_token)
{
    jlong token;
    /* The same refusal: a borrowed token nobody receives holds a table slot until its parent
     * closes. */
    if ((*env)->ExceptionCheck(env)) return 0;
    if (ptr == NULL || parent_token == 0) {
        kj_throw_handle(env, "cannot mint a borrowed handle without an object and a live parent");
        return 0;
    }
    token = kj_handle_put_borrowed_raw(kind, ptr, parent_token);
    if (token == 0) kj_throw_handle(env, "cannot mint borrowed handle: parent is closed/stale or table is full");
    return token;
}

void *kj_handle_get(JNIEnv *env, jlong token, int kind)
{
    void *ptr = kj_handle_peek(token, kind);
    if (ptr == NULL) {
        char msg[96];
        snprintf(msg, sizeof msg,
                 "invalid native handle (token=%lld, expected kind=%d): zero, closed, stale or wrong kind",
                 (long long)token, kind);
        kj_throw_handle(env, msg);
    }
    return ptr;
}
