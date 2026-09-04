/* The generation-tagged handle table, shared by every non-cinterop binding.
 *
 * WHY THIS IS ITS OWN DIRECTORY. The table was written for JNI and lived in
 * `native/kitecodec-jni/kj_handles.c`. The web binding needs exactly the same guarantee for the
 * same reason, and the owner chose one implementation over two copies: this
 * table is what turns a stale, zero, double-closed or wrong-kind token into a typed error instead
 * of memory corruption, and two copies of that are how a fixed bug survives in the one nobody
 * edited.
 *
 * It is NOT in `native/kitecodec-c/src`, which compiles into every target's `libkitecodec.a`.
 * Nothing on the cinterop path calls this table, so putting it there would move the symbol-audit
 * baseline for targets that have no use for it. This directory is compiled into exactly two
 * things: the JNI library and the wasm archive.
 *
 * THE `kj_` PREFIX IS HISTORICAL and is kept on purpose. Renaming would touch 155 call sites in
 * code that Android and the desktop JVM already ship, for no behavioural gain, and the point of
 * this change is to not destabilise them. Read it as "KiteFFmpeg handle".
 *
 * No JNI here, and none is possible: the token is an `int64_t`, which is what `jlong` already is.
 * The three JNI entry points that THROW on a bad token stay in `kj_handles.c` beside their
 * `JNIEnv`.
 */

#ifndef KC_HANDLES_H
#define KC_HANDLES_H

#include <stdint.h>

/* The object kinds a token can name. Kind travels inside the token as well as in the table, so a
 * wrong-kind lookup is distinguishable from a stale one in error text. */
enum {
    KJ_KIND_NONE = 0,
    KJ_KIND_CODEC,
    KJ_KIND_CODEC_CTX,
    KJ_KIND_CODEC_PAR,
    KJ_KIND_DICT,
    KJ_KIND_DICT_ENTRY,
    KJ_KIND_FILTER_CTX,
    KJ_KIND_FILTER_GRAPH,
    KJ_KIND_FMT_CTX,
    KJ_KIND_FRAME,
    KJ_KIND_PACKET,
    KJ_KIND_STREAM,
    KJ_KIND_COUNT
};

/* Token layout, restated here because a binding author needs it: { generation:31 | kind:5 |
 * slot:20 }, bit 63 unused so the value stays positive in Kotlin and JavaScript alike. A live
 * generation is always odd and never zero, so token 0 can never collide with a legal token and
 * safely means "no handle". */
#define KJ_SLOT_BITS 20
#define KJ_KIND_BITS 5
#define KJ_GEN_BITS  31
#define KJ_MAX_SLOTS (1 << KJ_SLOT_BITS)

/** Mints a token for [ptr]. Returns 0 when ptr is NULL, the kind is illegal or the table is full. */
int64_t kj_handle_put(int kind, void *ptr);

/** Mints a token whose lifetime is bounded by a live parent's. 0 if the parent is closed or stale. */
int64_t kj_handle_put_borrowed_raw(int kind, void *ptr, int64_t parent_token);

/** Resolves without closing. NULL when the token is zero, closed, stale or of the wrong kind. */
void *kj_handle_peek(int64_t token, int kind);

/** Resolves and closes in one step, invalidating every descendant. NULL if it was not live. */
void *kj_handle_close(int64_t token, int kind);

/** Closes and discards the pointer. Idempotent. */
void kj_handle_release(int64_t token, int kind);

/** Live handle count, for leak assertions in tests. */
int64_t kj_handle_live_count(void);

#endif /* KC_HANDLES_H */
