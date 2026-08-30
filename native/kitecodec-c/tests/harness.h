/* Assertion and reporting API for the KiteFFmpeg C test suites.
 *
 * Contract, from plan section 15.3: every suite is table driven, prints one line per case, and
 * returns non-zero on the first failure. This header is what makes that contract cheap to
 * honour, so no suite has to invent its own reporting.
 *
 * Shape of a suite:
 *
 *     #include "harness.h"
 *     #include "kitecodec_helpers.h"
 *
 *     int main(void) {
 *         kc_suite_begin("test_something");
 *         for (size_t i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
 *             kc_case("%s at %d", rows[i].name, rows[i].size);
 *             int rc = ffkmp_something(rows[i].size);
 *             KC_EQ_INT(rc, rows[i].expected);
 *             kc_detail("rc=%d", rc);
 *         }
 *         return kc_suite_end();
 *     }
 *
 * Output, one line per case, verdict first so a failing gate is greppable:
 *
 *     suite test_something
 *     ok      1  frame buffer at 256  [rc=0]
 *     FAIL    2  frame buffer at 257
 *         at tests/test_something.c:41
 *         KC_EQ_INT(rc, -22): actual -1, expected -22
 *
 * A failing assertion prints and calls exit(1) straight away. There is no "continue after
 * failure" mode on purpose: the first failure is the one with the intact state around it.
 *
 * Everything here is compiled with -std=c11 -Wall -Wextra -Werror -Werror=vla, so a suite that
 * warns does not build. Unused parameters, sign comparisons and shortened formats all have to
 * be dealt with rather than tolerated.
 */

#ifndef KITECODEC_TEST_HARNESS_H
#define KITECODEC_TEST_HARNESS_H

#include <stddef.h>
#include <stdint.h>

/* ---- Suite lifecycle ---- */

/* Opens the suite. Also silences the FFmpeg log, because a suite that drives error paths
 * would otherwise bury its own one-line-per-case output. Set KC_FFMPEG_LOG=1 in the
 * environment to keep FFmpeg's own diagnostics while debugging a case. */
void kc_suite_begin(const char *suite);

/* Closes the open case, prints the summary and returns the process exit code. Returns
 * non-zero when the suite ran no cases at all, so an empty suite cannot pass by accident. */
int kc_suite_end(void);

/* Opens a case, closing and reporting the previous one as passed. printf format. */
void kc_case(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Appends measured values to the open case's line, in square brackets. Call it as often as
 * you like; the fragments are joined with a space. printf format. */
void kc_detail(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Records that the open case could not assert one of its properties in this build variant,
 * and why. The case still counts as passed, the reason lands on its line, and the suite
 * summary reports how many cases were partial. Use it for exactly one thing: a property that
 * a build variant cannot observe, such as allocation pairing when the interposer is not
 * effective. Never use it to skip a property the variant can observe. */
void kc_partial(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Prints an extra indented line inside the open case, for context a single line cannot hold.
 * Appears before the case's own verdict line. printf format. */
void kc_note(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Fails the open case and exits non-zero. Not usually called directly; use the macros. */
_Noreturn void kc_fail_at(const char *file, int line, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

/* ---- Assertions ---- */

#define KC_FAIL(...) kc_fail_at(__FILE__, __LINE__, __VA_ARGS__)

#define KC_CHECK(cond) \
    do { if (!(cond)) KC_FAIL("KC_CHECK(%s) is false", #cond); } while (0)

/* Same, with a caller-written explanation. */
#define KC_CHECKF(cond, ...) \
    do { if (!(cond)) KC_FAIL(__VA_ARGS__); } while (0)

#define KC_EQ_INT(actual, expected) \
    do { \
        int kc_a_ = (actual); \
        int kc_e_ = (expected); \
        if (kc_a_ != kc_e_) \
            KC_FAIL("KC_EQ_INT(%s, %s): actual %d, expected %d", \
                    #actual, #expected, kc_a_, kc_e_); \
    } while (0)

#define KC_EQ_I64(actual, expected) \
    do { \
        int64_t kc_a_ = (int64_t)(actual); \
        int64_t kc_e_ = (int64_t)(expected); \
        if (kc_a_ != kc_e_) \
            KC_FAIL("KC_EQ_I64(%s, %s): actual %lld, expected %lld", \
                    #actual, #expected, (long long)kc_a_, (long long)kc_e_); \
    } while (0)

#define KC_EQ_SIZE(actual, expected) \
    do { \
        size_t kc_a_ = (size_t)(actual); \
        size_t kc_e_ = (size_t)(expected); \
        if (kc_a_ != kc_e_) \
            KC_FAIL("KC_EQ_SIZE(%s, %s): actual %zu, expected %zu", \
                    #actual, #expected, kc_a_, kc_e_); \
    } while (0)

#define KC_EQ_PTR(actual, expected) \
    do { \
        const void *kc_a_ = (const void *)(actual); \
        const void *kc_e_ = (const void *)(expected); \
        if (kc_a_ != kc_e_) \
            KC_FAIL("KC_EQ_PTR(%s, %s): actual %p, expected %p", \
                    #actual, #expected, kc_a_, kc_e_); \
    } while (0)

#define KC_NOT_NULL(value) \
    do { \
        if ((const void *)(value) == NULL) \
            KC_FAIL("KC_NOT_NULL(%s): got NULL", #value); \
    } while (0)

#define KC_NULL(value) \
    do { \
        const void *kc_a_ = (const void *)(value); \
        if (kc_a_ != NULL) KC_FAIL("KC_NULL(%s): got %p", #value, kc_a_); \
    } while (0)

/* Both sides must be non-NULL. A NULL against a NULL is a bug in the test, not a pass. */
#define KC_EQ_STR(actual, expected) \
    do { \
        const char *kc_a_ = (actual); \
        const char *kc_e_ = (expected); \
        if (kc_a_ == NULL || kc_e_ == NULL) \
            KC_FAIL("KC_EQ_STR(%s, %s): actual %s, expected %s", #actual, #expected, \
                    kc_a_ ? "non-NULL" : "NULL", kc_e_ ? "non-NULL" : "NULL"); \
        if (kc_strcmp(kc_a_, kc_e_) != 0) \
            KC_FAIL("KC_EQ_STR(%s, %s): actual \"%s\", expected \"%s\"", \
                    #actual, #expected, kc_a_, kc_e_); \
    } while (0)

/* Length of a NUL terminated string, asserted exactly. Useful for the buffer-limit rows of
 * test_buffers, where the interesting property is where truncation happened. */
#define KC_EQ_STRLEN(actual, expected) \
    do { \
        const char *kc_s_ = (actual); \
        KC_NOT_NULL(kc_s_); \
        KC_EQ_SIZE(kc_strlen(kc_s_), (size_t)(expected)); \
    } while (0)

#define KC_EQ_MEM(actual, expected, bytes) \
    do { \
        const void *kc_a_ = (actual); \
        const void *kc_e_ = (expected); \
        size_t kc_n_ = (size_t)(bytes); \
        long kc_at_ = kc_memdiff(kc_a_, kc_e_, kc_n_); \
        if (kc_at_ >= 0) \
            KC_FAIL("KC_EQ_MEM(%s, %s, %zu): first difference at byte %ld", \
                    #actual, #expected, kc_n_, kc_at_); \
    } while (0)

/* Every byte in [start, start + bytes) must be zero. The exact-silence assertion. */
#define KC_ALL_ZERO(start, bytes) \
    do { \
        const void *kc_a_ = (start); \
        size_t kc_n_ = (size_t)(bytes); \
        long kc_at_ = kc_firstnonzero(kc_a_, kc_n_); \
        if (kc_at_ >= 0) \
            KC_FAIL("KC_ALL_ZERO(%s, %zu): byte %ld is not zero", #start, kc_n_, kc_at_); \
    } while (0)

/* Comparison helpers behind the macros, so the macros stay free of <string.h>. */
int kc_strcmp(const char *a, const char *b);
size_t kc_strlen(const char *s);
long kc_memdiff(const void *a, const void *b, size_t bytes);
long kc_firstnonzero(const void *start, size_t bytes);

/* ---- Allocation accounting ---- */

/* Counters maintained by tests/interpose_alloc.c through the Mach-O __DATA,__interpose
 * section. `live_blocks` is the net count of allocations that have not been freed, which is
 * the number an ownership test actually wants: it already accounts for realloc growing a
 * block in place, for realloc(NULL, n) allocating and for realloc(p, 0) freeing.
 *
 * mmap and munmap are counted but deliberately kept out of live_blocks: libmalloc maps its
 * own regions, so those counts carry allocator noise rather than test-owned lifetime. */
typedef struct {
    long long malloc_calls;
    long long calloc_calls;
    long long realloc_calls;
    long long posix_memalign_calls;
    long long aligned_alloc_calls;
    long long valloc_calls;
    long long free_calls;
    long long mmap_calls;
    long long munmap_calls;
    long long live_blocks;
} kc_alloc_counts;

/* 1 when the interposer actually observes this process's allocations.
 *
 * Measured on this machine: 1 in the `plain` variant, 0 under `asan` and `tsan`, because both
 * sanitizer runtimes replace the allocator before dyld gets to our interpose section. So the
 * allocation-pairing evidence comes from the plain run, and the sanitizer runs contribute
 * their own findings instead. LeakSanitizer is not an option here at all: it is unsupported on
 * macOS arm64, which is why this interposer exists.
 *
 * A suite that needs the counters must call this and, when it returns 0, record the gap with
 * kc_partial() rather than quietly asserting nothing. */
int kc_alloc_active(void);

/* Reads the counters. Safe to call from any thread. */
void kc_alloc_snapshot(kc_alloc_counts *out);

/* Net allocations that have not been freed since `before` was taken. Zero means every
 * allocation made in the window was paired with a free. */
long long kc_alloc_live_delta(const kc_alloc_counts *before);

/* Total allocating calls, and total freeing calls, since `before` was taken. */
long long kc_alloc_new_delta(const kc_alloc_counts *before);
long long kc_alloc_free_delta(const kc_alloc_counts *before);

/* Asserts that the window starting at `before` allocated and freed in exact balance, and puts
 * the numbers on the case line. Does nothing but record a kc_partial() when the interposer is
 * not effective in this variant. */
#define KC_ALLOC_BALANCED(before) \
    do { \
        if (!kc_alloc_active()) { \
            kc_partial("allocation pairing not observable in this variant"); \
        } else { \
            long long kc_live_ = kc_alloc_live_delta(before); \
            kc_detail("new=%lld freed=%lld live=%lld", \
                      kc_alloc_new_delta(before), kc_alloc_free_delta(before), kc_live_); \
            if (kc_live_ != 0) \
                KC_FAIL("KC_ALLOC_BALANCED(%s): %lld blocks still live", #before, kc_live_); \
        } \
    } while (0)

/* Asserts the window leaked exactly `blocks` net allocations. The positive form matters for
 * helpers whose contract is to hand ownership to the caller, and for the parent-owned result
 * of ffkmp_fmt_new_stream. */
#define KC_ALLOC_LIVE(before, blocks) \
    do { \
        if (!kc_alloc_active()) { \
            kc_partial("allocation pairing not observable in this variant"); \
        } else { \
            long long kc_live_ = kc_alloc_live_delta(before); \
            kc_detail("live=%lld", kc_live_); \
            if (kc_live_ != (long long)(blocks)) \
                KC_FAIL("KC_ALLOC_LIVE(%s, %s): actual %lld, expected %lld", \
                        #before, #blocks, kc_live_, (long long)(blocks)); \
        } \
    } while (0)

#endif /* KITECODEC_TEST_HARNESS_H */
