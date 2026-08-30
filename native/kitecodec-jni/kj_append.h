/* Bounded string building for the JNI category units.
 *
 * THE TRAP THIS EXISTS TO CLOSE. `off += snprintf(buf + off, sizeof buf - (size_t)off, ...)` is
 * wrong, and wrong in a way that gets worse the moment it starts failing. snprintf returns the
 * length it WOULD have written, not what it wrote. Once `off` passes the buffer size, `buf + off`
 * points outside the array and `sizeof buf - (size_t)off` wraps to an enormous `size_t`, so the
 * NEXT append writes past the end with a bound that no longer bounds anything.
 *
 * `helpers_filter.c` already guards exactly this, by hand, after every append. This header is that
 * same guard in one place, so the next unit that composes a string gets it for free.
 *
 * REFUSE, NEVER TRUNCATE. A half-written identity report is worse than no report: the Kotlin side
 * splits it into a fixed field count, so a truncated one parses into wrong values rather than
 * failing. Callers turn a non-zero return into a typed exception.
 *
 * Deliberately header-only and free of jni.h, so a host test can compile the arithmetic without a
 * JVM present.
 */

#ifndef KJ_APPEND_H
#define KJ_APPEND_H

#include <stdarg.h>
#include <stdio.h>
#include <stddef.h>

/**
 * Appends a formatted string at `*off` in `buf`, which holds `cap` bytes.
 *
 * Returns 0 and advances `*off` past the text on success. Returns -1 and leaves `*off` alone when
 * the text does not fit, when the format fails, or when `*off` is already out of range.
 */
static inline int kj_append(char *buf, size_t cap, int *off, const char *fmt, ...)
    __attribute__((format(printf, 4, 5)));

static inline int kj_append(char *buf, size_t cap, int *off, const char *fmt, ...)
{
    va_list args;
    int written;

    if (buf == NULL || off == NULL || *off < 0 || (size_t)*off >= cap) {
        return -1;
    }
    va_start(args, fmt);
    written = vsnprintf(buf + *off, cap - (size_t)*off, fmt, args);
    va_end(args);
    /* `written` is what vsnprintf WANTED to write. Equal to the remaining space means the NUL was
       dropped, which is already a truncation.

       The NUL is put back at the old offset on refusal because vsnprintf has ALREADY written the
       part that fit. Without this the buffer keeps a truncated tail that no offset points at, and
       a caller that logs the accumulated string on the error path prints half a field. */
    if (written < 0 || (size_t)written >= cap - (size_t)*off) {
        buf[*off] = '\0';
        return -1;
    }
    *off += written;
    return 0;
}

#endif /* KJ_APPEND_H */
