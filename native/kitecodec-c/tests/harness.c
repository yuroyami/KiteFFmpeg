/* Implementation of the C test harness declared in harness.h. */

#include "harness.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavutil/log.h>

#define KC_NAME_MAX 200
#define KC_DETAIL_MAX 400

static const char *suite_name = "unnamed";
static char case_name[KC_NAME_MAX];
static char case_detail[KC_DETAIL_MAX];
static int case_open;
static long cases_total;
static long cases_partial;

static void print_case_line(const char *verdict)
{
    if (case_detail[0] != '\0')
        printf("%-6s %3ld  %s  [%s]\n", verdict, cases_total, case_name, case_detail);
    else
        printf("%-6s %3ld  %s\n", verdict, cases_total, case_name);
    fflush(stdout);
}

static void close_case(void)
{
    if (!case_open)
        return;
    case_open = 0;
    print_case_line("ok");
}

void kc_suite_begin(const char *suite)
{
    const char *require;
    suite_name = (suite != NULL && suite[0] != '\0') ? suite : "unnamed";
    /* One line per case is the whole reporting contract, and FFmpeg's own log would drown it
     * the first time a suite drives an error path on purpose. */
    if (getenv("KC_FFMPEG_LOG") == NULL)
        av_log_set_level(AV_LOG_QUIET);
    printf("suite %s\n", suite_name);
    fflush(stdout);
    /* Probe the interposer once here, so its own allocation never lands inside a case window
     * and no suite has to remember to warm it up. */
    (void)kc_alloc_active();
    /* Ported from kiteplayer-rt's harness at the interlude, where this mechanism existed
     * first; the two harnesses are a pair and a fix to either lands in both. Without it, every
     * KC_ALLOC_* assertion degrades to a recorded partial when the interposer is not effective,
     * and the review measured that one word in interpose_alloc.c's section name makes exactly
     * that happen with the whole ownership gate still green: "39 cases, 39 passed, 39 with a
     * property this variant cannot observe". Deferral 2's ownership guarantee is carried by
     * these assertions, so "cannot observe" must be available as a hard failure: the interpose
     * gate step sets KC_REQUIRE_ALLOC_ACCOUNTING=1 and a blind interposer fails the suite
     * instead of hollowing it. */
    require = getenv("KC_REQUIRE_ALLOC_ACCOUNTING");
    if (require != NULL && require[0] == '1' && !kc_alloc_active()) {
        printf("FAIL   %s: KC_REQUIRE_ALLOC_ACCOUNTING=1 but the allocation interposer is not "
               "effective in this build variant\n", suite_name);
        fflush(stdout);
        exit(1);
    }
}

int kc_suite_end(void)
{
    close_case();
    if (cases_total == 0) {
        printf("FAIL   %s ran no cases\n", suite_name);
        fflush(stdout);
        return 1;
    }
    if (cases_partial > 0)
        printf("%s: %ld cases, %ld passed, %ld with a property this variant cannot observe\n",
               suite_name, cases_total, cases_total, cases_partial);
    else
        printf("%s: %ld cases, %ld passed\n", suite_name, cases_total, cases_total);
    fflush(stdout);
    return 0;
}

void kc_case(const char *fmt, ...)
{
    va_list args;
    close_case();
    cases_total++;
    case_open = 1;
    case_detail[0] = '\0';
    va_start(args, fmt);
    vsnprintf(case_name, sizeof(case_name), fmt, args);
    va_end(args);
}

void kc_detail(const char *fmt, ...)
{
    va_list args;
    size_t used = strlen(case_detail);
    if (used + 2 >= sizeof(case_detail))
        return;
    if (used > 0) {
        case_detail[used] = ' ';
        used++;
        case_detail[used] = '\0';
    }
    va_start(args, fmt);
    vsnprintf(case_detail + used, sizeof(case_detail) - used, fmt, args);
    va_end(args);
}

void kc_partial(const char *fmt, ...)
{
    va_list args;
    char reason[KC_DETAIL_MAX];
    va_start(args, fmt);
    vsnprintf(reason, sizeof(reason), fmt, args);
    va_end(args);
    cases_partial++;
    kc_detail("partial: %s", reason);
}

void kc_note(const char *fmt, ...)
{
    va_list args;
    printf("       |  ");
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    printf("\n");
    fflush(stdout);
}

_Noreturn void kc_fail_at(const char *file, int line, const char *fmt, ...)
{
    va_list args;
    if (!case_open) {
        cases_total++;
        snprintf(case_name, sizeof(case_name), "%s", "(outside any case)");
    }
    case_open = 0;
    print_case_line("FAIL");
    printf("       |  at %s:%d\n", file, line);
    printf("       |  ");
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    printf("\n");
    printf("FAIL   %s failed at case %ld\n", suite_name, cases_total);
    fflush(stdout);
    exit(1);
}

int kc_strcmp(const char *a, const char *b)
{
    return strcmp(a, b);
}

size_t kc_strlen(const char *s)
{
    return strlen(s);
}

long kc_memdiff(const void *a, const void *b, size_t bytes)
{
    const unsigned char *left = (const unsigned char *)a;
    const unsigned char *right = (const unsigned char *)b;
    size_t i;
    if (left == NULL || right == NULL)
        return (bytes == 0) ? -1 : 0;
    for (i = 0; i < bytes; i++) {
        if (left[i] != right[i])
            return (long)i;
    }
    return -1;
}

long kc_firstnonzero(const void *start, size_t bytes)
{
    const unsigned char *bytes_at = (const unsigned char *)start;
    size_t i;
    if (bytes_at == NULL)
        return (bytes == 0) ? -1 : 0;
    for (i = 0; i < bytes; i++) {
        if (bytes_at[i] != 0)
            return (long)i;
    }
    return -1;
}

/* ---- Allocation accounting ----
 *
 * The probe has to live here rather than in interpose_alloc.c. dyld does not apply an
 * interpose section to the image that carries it, which is exactly why the interposer's own
 * wrappers can call the real malloc without recursing, and exactly why a probe compiled into
 * the interposer would always report zero. This translation unit is part of the test
 * executable, so its allocations do go through the interposed entry points. */

static int alloc_probe_done;
static int alloc_probe_result;
static void *volatile probe_sink;

__attribute__((noinline)) static int probe_interposer(void)
{
    kc_alloc_counts before;
    kc_alloc_counts after;
    volatile size_t size = 32;
    void *block;
    kc_alloc_snapshot(&before);
    block = malloc(size);
    if (block == NULL)
        return 0;
    memset(block, 0, size);
    probe_sink = block;
    free(block);
    probe_sink = NULL;
    kc_alloc_snapshot(&after);
    return (after.malloc_calls > before.malloc_calls)
        && (after.free_calls > before.free_calls);
}

int kc_alloc_active(void)
{
    if (!alloc_probe_done) {
        alloc_probe_result = probe_interposer();
        alloc_probe_done = 1;
    }
    return alloc_probe_result;
}

static long long new_calls(const kc_alloc_counts *counts)
{
    return counts->malloc_calls + counts->calloc_calls + counts->posix_memalign_calls
        + counts->aligned_alloc_calls + counts->valloc_calls;
}

long long kc_alloc_live_delta(const kc_alloc_counts *before)
{
    kc_alloc_counts now;
    kc_alloc_snapshot(&now);
    return now.live_blocks - before->live_blocks;
}

long long kc_alloc_new_delta(const kc_alloc_counts *before)
{
    kc_alloc_counts now;
    kc_alloc_snapshot(&now);
    return new_calls(&now) - new_calls(before);
}

long long kc_alloc_free_delta(const kc_alloc_counts *before)
{
    kc_alloc_counts now;
    kc_alloc_snapshot(&now);
    return now.free_calls - before->free_calls;
}
