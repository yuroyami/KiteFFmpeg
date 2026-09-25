/* The JNI identity report once accumulated with `off += snprintf(...)` and no bound at all.
 *
 * snprintf returns the length it WOULD have written. Once `off` passes the buffer size,
 * `buf + off` leaves the array and `sizeof buf - (size_t)off` wraps to a huge `size_t`, so the
 * next append writes past the end with a length that disables every bound the call had. Today the
 * reachable maximum is about 2.3 KB against a 4 KB buffer, so it is latent, not live; seven of the
 * report's fields are strings of unbounded length, so latent is the only word for it.
 *
 * This suite compiles the SHIPPED helper out of `kitecodec-jni/kj_append.h`. That header carries
 * no jni.h on purpose, which is what lets a host test cover the JNI layer's arithmetic when there
 * is no JVM in the build and no C test of that layer anywhere else.
 *
 * The wrap is shown, not assumed: `theOldFormWalksOffTheEnd` computes what the un-guarded chain
 * would have produced and asserts it is out of range. Without that row the suite would pass on a
 * buffer that was simply never filled.
 */

#include "harness.h"
#include "kj_append.h"

#include <string.h>

int main(void)
{
    kc_suite_begin("test_append");

    {
        char buf[32];
        int off = 0;
        kc_case("appends in order and keeps the offset truthful");
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%d", 42), 0);
        KC_EQ_INT(off, 2);
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "\x1f%s", "ok"), 0);
        KC_EQ_INT(off, 5);
        KC_EQ_STR(buf, "42\x1f" "ok");
        kc_detail("off=%d", off);
    }

    {
        char buf[8];
        int off = 0;
        kc_case("a value that does not fit is refused, and the offset does not move");
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "1234567890"), -1);
        KC_EQ_INT(off, 0);
    }

    {
        char buf[8];
        int off = 0;
        kc_case("exactly filling the buffer is refused, because the NUL would be dropped");
        /* 7 characters plus the NUL is the largest that fits in 8 bytes. */
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "1234567"), 0);
        KC_EQ_INT(off, 7);
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "x"), -1);
        KC_EQ_INT(off, 7);
        KC_EQ_STR(buf, "1234567");
    }

    {
        char buf[16];
        int off = 0;
        kc_case("a refused append leaves every earlier byte intact");
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "keep"), 0);
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "far too long to fit in here"), -1);
        KC_EQ_STR(buf, "keep");
        KC_EQ_INT(off, 4);
    }

    {
        char buf[16];
        int off = 99;
        kc_case("an offset already outside the buffer is refused rather than trusted");
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "x"), -1);
        KC_EQ_INT(off, 99);
        off = -1;
        KC_EQ_INT(kj_append(buf, sizeof buf, &off, "%s", "x"), -1);
    }

    {
        /* The defect itself. `off += snprintf(...)` with a 12 byte want into a 8 byte buffer
           leaves off at 12, which is PAST the end; the next call would compute `buf + 12` and
           `8 - 12`, an enormous size_t. The guarded form never lets off leave the array. */
        char buf[8];
        int naive = 0;
        int guarded = 0;
        kc_case("the old form walks the offset off the end, and the new one cannot");
        naive += snprintf(buf + naive, sizeof buf - (size_t)naive, "%s", "123456789012");
        KC_CHECKF(naive > (int)sizeof buf,
                  "the row claims the naive form overshoots, but off is %d for an %zu byte buffer",
                  naive, sizeof buf);
        kc_note("unguarded off landed at %d in an %zu byte buffer", naive, sizeof buf);
        KC_EQ_INT(kj_append(buf, sizeof buf, &guarded, "%s", "123456789012"), -1);
        KC_CHECK(guarded >= 0 && (size_t)guarded < sizeof buf);
    }

    return kc_suite_end();
}
