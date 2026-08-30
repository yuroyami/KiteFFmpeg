/* The generation-tagged handle table, driven past the wrap its token layout implies.
 *
 * This suite compiles native/kitecodec-handles/kc_handles.c INTO itself rather than linking it.
 * That is deliberate and it is the only way the interesting case is reachable: the defect it
 * covers needs a slot whose generation counter has passed 2^31, which is about two billion
 * mint/close pairs on one slot. Nothing that runs in a test's lifetime gets there, so the counter
 * is set by hand, which needs the file's statics.
 *
 * The table has no other C suite. Every other binding reaches it through a JVM or a browser, and
 * neither is in this build.
 */

#include "harness.h"

/* The table itself, statics and all. Nothing else links it, so there is no duplicate symbol. */
#include "kc_handles.c"

#include <stdint.h>

/* Two distinct addresses to hand out. Their contents are never read; the table stores pointers. */
static int object_a;
static int object_b;

/* The slot a token names, which is the only thing this suite needs to reach back into. */
static int32_t slot_of(int64_t token)
{
    return (int32_t)(token & (KJ_MAX_SLOTS - 1));
}

static void case_ordinary_round_trip(void)
{
    int64_t token;

    kc_case("a minted token resolves to what was put in, and closing it is final");
    token = kj_handle_put(KJ_KIND_FRAME, &object_a);
    KC_CHECK(token != 0);
    KC_EQ_PTR(kj_handle_peek(token, KJ_KIND_FRAME), &object_a);
    KC_NULL(kj_handle_peek(token, KJ_KIND_PACKET));
    KC_EQ_PTR(kj_handle_close(token, KJ_KIND_FRAME), &object_a);
    KC_NULL(kj_handle_peek(token, KJ_KIND_FRAME));
    kc_note("the wrong-kind read is the other half: a token is not just a slot number");
}

static void case_generation_past_the_token_width(void)
{
    int64_t token;
    int32_t slot;

    /* Mint once to own a slot, then wind that slot's counter to the last value the token can
     * carry. The next mint crosses the boundary. */
    token = kj_handle_put(KJ_KIND_FRAME, &object_a);
    KC_CHECK(token != 0);
    slot = slot_of(token);
    kj_handle_release(token, KJ_KIND_FRAME);

    kc_case("a slot whose generation reached the token's last value still mints and resolves");
    /* Even, because the slot is free. One below the largest even value the token can hold. */
    kj_slots[slot].gen = ((1u << KJ_GEN_BITS) - 1u) - 1u;
    kj_next_free_scan = slot;
    token = kj_handle_put(KJ_KIND_FRAME, &object_a);
    KC_CHECK(token != 0);
    KC_EQ_INT(slot_of(token), slot);
    KC_EQ_PTR(kj_handle_peek(token, KJ_KIND_FRAME), &object_a);
    kj_handle_release(token, KJ_KIND_FRAME);
    kc_note("gen=%u, the last odd value KJ_GEN_BITS can hold", (unsigned)((1u << KJ_GEN_BITS) - 1u));

    kc_case("and the slot survives the wrap: the next mint resolves too");
    /* The counter is now at the mask boundary. Winding it one more time is what used to kill the
     * slot for good: the token carried the truncated value and the comparison used the whole
     * counter, so they could never be equal again. */
    kj_next_free_scan = slot;
    token = kj_handle_put(KJ_KIND_FRAME, &object_b);
    KC_CHECK(token != 0);
    KC_EQ_INT(slot_of(token), slot);
    KC_EQ_PTR(kj_handle_peek(token, KJ_KIND_FRAME), &object_b);
    kc_note("stored gen=%u, which is inside the token's %d bits", (unsigned)kj_slots[slot].gen,
            KJ_GEN_BITS);

    kc_case("a live generation is still odd after the wrap, because free means even");
    KC_EQ_INT((int)(kj_slots[slot].gen & 1u), 1);
    kj_handle_release(token, KJ_KIND_FRAME);
    KC_EQ_INT((int)(kj_slots[slot].gen & 1u), 0);
}

static void case_stale_token_after_the_wrap(void)
{
    int64_t first, second;
    int32_t slot;

    first = kj_handle_put(KJ_KIND_FRAME, &object_a);
    KC_CHECK(first != 0);
    slot = slot_of(first);
    kj_handle_release(first, KJ_KIND_FRAME);

    kc_case("a token from before the wrap does not resolve against the slot after it");
    kj_slots[slot].gen = ((1u << KJ_GEN_BITS) - 1u) - 1u;
    kj_next_free_scan = slot;
    first = kj_handle_put(KJ_KIND_FRAME, &object_a);
    KC_CHECK(first != 0);
    kj_handle_release(first, KJ_KIND_FRAME);
    kj_next_free_scan = slot;
    second = kj_handle_put(KJ_KIND_FRAME, &object_b);
    KC_CHECK(second != 0);
    KC_EQ_INT(slot_of(second), slot);
    KC_CHECK(first != second);
    KC_NULL(kj_handle_peek(first, KJ_KIND_FRAME));
    KC_EQ_PTR(kj_handle_peek(second, KJ_KIND_FRAME), &object_b);
    kc_note("the point of the generation is that a reused slot refuses the old token, and that");
    kc_note("has to keep working across the wrap or the guard quietly stops guarding");
    kj_handle_release(second, KJ_KIND_FRAME);
}

static void case_borrowed_child_across_the_wrap(void)
{
    int64_t parent, child;
    int32_t slot;

    parent = kj_handle_put(KJ_KIND_FMT_CTX, &object_a);
    KC_CHECK(parent != 0);
    slot = slot_of(parent);
    kj_handle_release(parent, KJ_KIND_FMT_CTX);

    kc_case("a borrowed child of a high-generation parent is minted and dies with it");
    kj_slots[slot].gen = ((1u << KJ_GEN_BITS) - 1u) - 1u;
    kj_next_free_scan = slot;
    parent = kj_handle_put(KJ_KIND_FMT_CTX, &object_a);
    KC_CHECK(parent != 0);
    child = kj_handle_put_borrowed_raw(KJ_KIND_STREAM, &object_b, parent);
    KC_CHECK(child != 0);
    KC_EQ_PTR(kj_handle_peek(child, KJ_KIND_STREAM), &object_b);
    kj_handle_release(parent, KJ_KIND_FMT_CTX);
    KC_NULL(kj_handle_peek(child, KJ_KIND_STREAM));
    kc_note("the parent's generation travels inside the child's record, so the same truncation");
    kc_note("would have orphaned every borrowed handle on that slot");
}

/* ── the token's kind field, which used to be decoration ───────────────────────────────── */

static void case_token_kind_field_is_checked(void)
{
    int64_t token, tampered;

    kc_case("a token whose kind bits were changed does not resolve, even to the right kind");
    token = kj_handle_put(KJ_KIND_FRAME, &object_a);
    KC_CHECK(token != 0);
    KC_EQ_PTR(kj_handle_peek(token, KJ_KIND_FRAME), &object_a);

    /* Same slot, same generation, different kind bits. The slot's kind is checked either way, so
     * this never reached the object; what it did reach was the SAME object through a second
     * token, which is the property a handle is supposed not to have. */
    tampered = token ^ ((int64_t)(KJ_KIND_FRAME ^ KJ_KIND_PACKET) << KJ_SLOT_BITS);
    KC_CHECK(tampered != token);
    KC_NULL(kj_handle_peek(tampered, KJ_KIND_FRAME));
    KC_NULL(kj_handle_peek(tampered, KJ_KIND_PACKET));
    kc_note("both readings refuse: one because the token disagrees with the request, the other");
    kc_note("because the slot disagrees with both");
    kj_handle_release(token, KJ_KIND_FRAME);
}

/* ── closing a deep chain of borrowed handles ──────────────────────────────────────────── */

static void case_deep_borrow_chain_closes_without_recursing(void)
{
    enum { DEPTH = 2000 };
    static int objects[DEPTH];
    int64_t chain[DEPTH];
    int i;

    kc_case("a %d-deep borrow chain is closed by closing its root", DEPTH);
    chain[0] = kj_handle_put(KJ_KIND_FMT_CTX, &objects[0]);
    KC_CHECK(chain[0] != 0);
    for (i = 1; i < DEPTH; i++) {
        /* Nothing in the table stops a caller borrowing from a borrowed handle, so the depth
         * here is the caller's and not the library's. That is the whole point: the old sweep
         * took one stack frame per level. */
        chain[i] = kj_handle_put_borrowed_raw(KJ_KIND_STREAM, &objects[i], chain[i - 1]);
        KC_CHECK(chain[i] != 0);
    }
    KC_EQ_I64(kj_handle_live_count(), (int64_t)DEPTH);
    KC_EQ_I64(kj_borrowed_live, (int64_t)(DEPTH - 1));

    kj_handle_release(chain[0], KJ_KIND_FMT_CTX);
    KC_EQ_I64(kj_handle_live_count(), (int64_t)0);
    KC_EQ_I64(kj_borrowed_live, (int64_t)0);
    for (i = 0; i < DEPTH; i++) {
        KC_NULL(kj_handle_peek(chain[i], i == 0 ? KJ_KIND_FMT_CTX : KJ_KIND_STREAM));
    }
    kc_note("every level is closed and the borrowed counter is back to zero, which is what lets");
    kc_note("an ordinary close skip the sweep entirely");
}

static void case_borrowed_counter_tracks_the_table(void)
{
    int64_t parent, child;

    kc_case("the borrowed counter follows mint and close, in both orders");
    KC_EQ_I64(kj_borrowed_live, (int64_t)0);
    parent = kj_handle_put(KJ_KIND_FMT_CTX, &object_a);
    KC_EQ_I64(kj_borrowed_live, (int64_t)0);
    child = kj_handle_put_borrowed_raw(KJ_KIND_STREAM, &object_b, parent);
    KC_CHECK(child != 0);
    KC_EQ_I64(kj_borrowed_live, (int64_t)1);

    /* Closing the child first is the other order: the parent outlives it and the counter still
     * has to come back, or every later close pays for a sweep that can find nothing. */
    kj_handle_release(child, KJ_KIND_STREAM);
    KC_EQ_I64(kj_borrowed_live, (int64_t)0);
    kj_handle_release(parent, KJ_KIND_FMT_CTX);
    KC_EQ_I64(kj_handle_live_count(), (int64_t)0);
}

static void case_live_count_returns_to_zero(void)
{
    kc_case("every handle this suite minted is closed");
    KC_EQ_I64(kj_handle_live_count(), (int64_t)0);
}

int main(void)
{
    kc_suite_begin("test_handles");

    case_ordinary_round_trip();
    case_generation_past_the_token_width();
    case_stale_token_after_the_wrap();
    case_borrowed_child_across_the_wrap();
    case_token_kind_field_is_checked();
    case_deep_borrow_chain_closes_without_recursing();
    case_borrowed_counter_tracks_the_table();
    case_live_count_returns_to_zero();

    return kc_suite_end();
}
