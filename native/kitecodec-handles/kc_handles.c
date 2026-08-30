/* The generation-tagged handle table. See kc_handles.h for why this lives outside the JNI tree.
 *
 * Moved verbatim from native/kitecodec-jni/kj_handles.c on 2026-08-17 (PLANNING.md), with
 * `jlong` spelled `int64_t`, which is the same type, and the three throwing wrappers left behind
 * with their JNIEnv. The table grows in fixed-size chunks and never shrinks or moves entries, so a
 * slot index stays valid for the process lifetime. One mutex guards mint/close; resolve reads under
 * the same mutex because a resolve racing a close must observe either the live pointer or the
 * closed slot, never a torn pair.
 */

#include "kc_handles.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define KJ_CHUNK     1024

typedef struct kj_slot {
    void    *ptr;      /* NULL when free or closed */
    uint32_t gen;      /* odd while live, even while free; starts at 0 */
    uint8_t  kind;
    int32_t  parent;   /* slot index of a live parent, or -1 for an owned/root token */
    uint32_t parent_gen;
} kj_slot;

static pthread_mutex_t kj_lock = PTHREAD_MUTEX_INITIALIZER;
static kj_slot *kj_slots = NULL;
static int32_t  kj_capacity = 0;
static int32_t  kj_next_free_scan = 0;
static int64_t  kj_live = 0;
/* How many live handles record a parent. Zero means a close has nothing to orphan, which is the
 * ordinary case and is why the sweep below is skipped rather than run over the whole table. */
static int64_t  kj_borrowed_live = 0;

/* The generation as the TOKEN can carry it.
 *
 * The token has KJ_GEN_BITS for it and the slot's counter is a full uint32_t, so past
 * 2^KJ_GEN_BITS the two stop agreeing: the token holds the low bits and the comparison in
 * kj_resolve was against the whole counter, so the slot resolved nothing ever again and every
 * token minted from it was dead on arrival. Masking at the point the counter MOVES keeps the two
 * identical for the process lifetime, and it preserves the odd-is-live rule because the
 * truncation drops a bit whose weight is even. */
#define KJ_GEN_MASK ((1u << KJ_GEN_BITS) - 1u)

static int64_t kj_encode(uint32_t gen, int kind, int32_t slot)
{
    return ((int64_t)(gen & ((1u << KJ_GEN_BITS) - 1)) << (KJ_SLOT_BITS + KJ_KIND_BITS))
         | ((int64_t)(kind & ((1 << KJ_KIND_BITS) - 1)) << KJ_SLOT_BITS)
         | (int64_t)(slot & (KJ_MAX_SLOTS - 1));
}

static void kj_slot_reset(kj_slot *slot)
{
    if (slot->parent >= 0) kj_borrowed_live -= 1;
    slot->ptr = NULL;
    slot->gen = (slot->gen + 1u) & KJ_GEN_MASK; /* live odd -> free even, inside the token's bits */
    slot->kind = KJ_KIND_NONE;
    slot->parent = -1;
    slot->parent_gen = 0;
    kj_live -= 1;
}

/* True while this slot holds a handle. Live is "has a pointer and an odd generation", and both
 * halves matter: a freed slot keeps its even counter and a zeroed one has neither. */
static int kj_slot_live(const kj_slot *slot)
{
    return slot->ptr != NULL && (slot->gen & 1u) != 0u;
}

/* Closes every live handle whose recorded parent is no longer live at the generation it recorded.
 *
 * This used to RECURSE, one frame per level of borrowing, and the depth is the caller's: nothing
 * in the table stops an application from borrowing a handle from a borrowed handle for as long as
 * it likes. A close is not a place to find out how deep the stack goes.
 *
 * Iterating to a fixed point does the same work with a constant frame. An orphan is a purely local
 * property, so a pass that closes one may create another, and the loop simply repeats until a pass
 * changes nothing. Slots are handed out in ascending order and never move, so a child normally
 * sits above its parent and one pass unwinds a whole chain; the second pass is the one that
 * confirms there is nothing left. */
static void kj_invalidate_orphans_locked(void)
{
    int changed = 1;
    while (changed) {
        int32_t i;
        changed = 0;
        for (i = 0; i < kj_capacity; i++) {
            int32_t parent = kj_slots[i].parent;
            if (!kj_slot_live(&kj_slots[i]) || parent < 0) continue;
            if (parent < kj_capacity && kj_slot_live(&kj_slots[parent])
                && kj_slots[parent].gen == kj_slots[i].parent_gen) {
                continue;
            }
            kj_slot_reset(&kj_slots[i]);
            changed = 1;
        }
    }
}

static int64_t kj_handle_put_locked(int kind, void *ptr, int32_t parent, uint32_t parent_gen)
{
    int32_t i, slot = -1;
    int64_t token = 0;
    if (ptr == NULL || kind <= KJ_KIND_NONE || kind >= KJ_KIND_COUNT) return 0;
    for (i = 0; i < kj_capacity; i++) {
        int32_t probe = (kj_next_free_scan + i) % (kj_capacity ? kj_capacity : 1);
        if (kj_slots[probe].ptr == NULL && (kj_slots[probe].gen & 1u) == 0u) { slot = probe; break; }
    }
    if (slot < 0) {
        if (kj_capacity >= KJ_MAX_SLOTS) return 0;
        {
            int32_t grown = kj_capacity + KJ_CHUNK;
            kj_slot *bigger = (kj_slot *)realloc(kj_slots, (size_t)grown * sizeof(kj_slot));
            if (bigger == NULL) return 0;
            memset(bigger + kj_capacity, 0, (size_t)KJ_CHUNK * sizeof(kj_slot));
            for (i = kj_capacity; i < grown; i++) bigger[i].parent = -1;
            kj_slots = bigger;
            slot = kj_capacity;
            kj_capacity = grown;
        }
    }
    kj_slots[slot].gen = (kj_slots[slot].gen + 1u) & KJ_GEN_MASK;  /* even -> odd: live */
    /* Wrap guard: the mask can land on an even value, and even means free. */
    if ((kj_slots[slot].gen & 1u) == 0u) kj_slots[slot].gen = (kj_slots[slot].gen + 1u) & KJ_GEN_MASK;
    kj_slots[slot].ptr = ptr;
    kj_slots[slot].kind = (uint8_t)kind;
    kj_slots[slot].parent = parent;
    kj_slots[slot].parent_gen = parent_gen;
    if (parent >= 0) kj_borrowed_live += 1;
    kj_next_free_scan = slot + 1;
    kj_live += 1;
    token = kj_encode(kj_slots[slot].gen, kind, slot);
    return token;
}

int64_t kj_handle_put(int kind, void *ptr)
{
    int64_t token;
    pthread_mutex_lock(&kj_lock);
    token = kj_handle_put_locked(kind, ptr, -1, 0);
    pthread_mutex_unlock(&kj_lock);
    return token;
}




int64_t kj_handle_put_borrowed_raw(int kind, void *ptr, int64_t parent_token)
{
    int32_t parent = (int32_t)(parent_token & (KJ_MAX_SLOTS - 1));
    uint32_t parent_gen = (uint32_t)((uint64_t)parent_token >> (KJ_SLOT_BITS + KJ_KIND_BITS));
    int64_t token = 0;
    if (ptr == NULL || parent_token == 0) return 0;
    pthread_mutex_lock(&kj_lock);
    if (parent < kj_capacity && kj_slots[parent].ptr != NULL
        && kj_slots[parent].gen == parent_gen
        && kj_slots[parent].kind == (uint8_t)((parent_token >> KJ_SLOT_BITS)
                                             & ((1 << KJ_KIND_BITS) - 1))) {
        token = kj_handle_put_locked(kind, ptr, parent, parent_gen);
    }
    pthread_mutex_unlock(&kj_lock);
    return token;
}

static void *kj_resolve(int64_t token, int kind, int close_it)
{
    int32_t slot = (int32_t)(token & (KJ_MAX_SLOTS - 1));
    uint32_t gen = (uint32_t)((uint64_t)token >> (KJ_SLOT_BITS + KJ_KIND_BITS));
    int token_kind = (int)((token >> KJ_SLOT_BITS) & ((1 << KJ_KIND_BITS) - 1));
    void *ptr = NULL;
    if (token == 0) return NULL;
    /* The token's OWN kind field has to agree with what the caller is asking for. It used to be
       written at mint and then never read, so two tokens differing only in those bits resolved
       the same object and the field was decoration. The slot's kind is still checked below;
       this is what makes a token name exactly one handle. */
    if (token_kind != kind) return NULL;
    pthread_mutex_lock(&kj_lock);
    if (slot < kj_capacity
        && kj_slots[slot].ptr != NULL
        && kj_slots[slot].gen == gen
        && kj_slots[slot].kind == (uint8_t)kind) {
        ptr = kj_slots[slot].ptr;
        if (close_it) {
            kj_slot_reset(&kj_slots[slot]);
            /* Nothing can be orphaned when nothing was borrowed, which is most closes. */
            if (kj_borrowed_live > 0) kj_invalidate_orphans_locked();
        }
    }
    pthread_mutex_unlock(&kj_lock);
    return ptr;
}

void *kj_handle_peek(int64_t token, int kind)
{
    return kj_resolve(token, kind, 0);
}


void *kj_handle_close(int64_t token, int kind)
{
    return kj_resolve(token, kind, 1);
}

void kj_handle_release(int64_t token, int kind)
{
    (void)kj_resolve(token, kind, 1);
}

int64_t kj_handle_live_count(void)
{
    int64_t n;
    pthread_mutex_lock(&kj_lock);
    n = kj_live;
    pthread_mutex_unlock(&kj_lock);
    return n;
}
