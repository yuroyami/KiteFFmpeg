/* Allocation interposer for the KiteFFmpeg C tests.
 *
 * Why this file exists. LeakSanitizer is not supported on macOS arm64: an ASan and UBSan
 * binary built by Apple clang 17 answers ASAN_OPTIONS=detect_leaks=1 with "AddressSanitizer:
 * detect_leaks is not supported on this platform". The 29
 * ownership helpers still need leak evidence on the proving machine, so this file is the local
 * instrument, and LSan in the Linux CI job is the corroboration.
 *
 * How it works, and the two traps it avoids.
 *
 * 1. It uses the Mach-O __DATA,__interpose section. The obvious alternative, inserting a
 *    library that simply defines its own malloc, silently counts zero: the two-level namespace
 *    binds every call to the definition in libSystem, so the shadowing definition is never
 *    reached. That trap is worth knowing rather than rediscovering.
 * 2. dyld does not apply an interpose section to the image that carries it. That is what lets
 *    the wrappers below call the real malloc and free with no recursion and no dlsym dance. It
 *    is also why the "is the interposer effective" probe lives in harness.c instead: a probe
 *    compiled into this file could never see its own counters move.
 *
 * The section is honoured both when this library is linked into the test binary and when it
 * arrives through DYLD_INSERT_LIBRARIES. build-host.sh links it, because that needs no
 * environment variable and survives a binary being run by hand.
 *
 * Measured scope. In the plain variant the counters are live. Under asan and tsan they read
 * zero, because each sanitizer runtime replaces the allocator before dyld reaches this
 * section, so kc_alloc_active() returns 0 there and a suite records the gap with kc_partial().
 *
 * posix_memalign is not decoration. FFmpeg's av_malloc goes through posix_memalign on this
 * platform, and av_free goes through free: an av_frame_alloc and av_frame_free pair measured
 * as 1 posix_memalign against 1 free. An interposer that watched only malloc and free would
 * have reported zero allocations against one free for every FFmpeg object in the suite, which
 * is worse than no instrument at all because it looks like a finding.
 *
 * calloc, realloc, posix_memalign, aligned_alloc and valloc are separate entry points in
 * libmalloc rather than wrappers over malloc, so counting all of them does not double count.
 * strdup is deliberately not interposed: it does call malloc internally, and interposing both
 * would count one allocation twice.
 */

#include "harness.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/types.h>

static _Atomic long long n_malloc;
static _Atomic long long n_calloc;
static _Atomic long long n_realloc;
static _Atomic long long n_posix_memalign;
static _Atomic long long n_aligned_alloc;
static _Atomic long long n_valloc;
static _Atomic long long n_free;
static _Atomic long long n_mmap;
static _Atomic long long n_munmap;
static _Atomic long long n_live;

static void bump(_Atomic long long *counter, long long by)
{
    atomic_fetch_add_explicit(counter, by, memory_order_relaxed);
}

static long long read_counter(const _Atomic long long *counter)
{
    return atomic_load_explicit(counter, memory_order_relaxed);
}

void kc_alloc_snapshot(kc_alloc_counts *out)
{
    if (out == NULL)
        return;
    out->malloc_calls = read_counter(&n_malloc);
    out->calloc_calls = read_counter(&n_calloc);
    out->realloc_calls = read_counter(&n_realloc);
    out->posix_memalign_calls = read_counter(&n_posix_memalign);
    out->aligned_alloc_calls = read_counter(&n_aligned_alloc);
    out->valloc_calls = read_counter(&n_valloc);
    out->free_calls = read_counter(&n_free);
    out->mmap_calls = read_counter(&n_mmap);
    out->munmap_calls = read_counter(&n_munmap);
    out->live_blocks = read_counter(&n_live);
}

static void *kc_malloc(size_t size)
{
    void *block = malloc(size);
    bump(&n_malloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void *kc_calloc(size_t count, size_t size)
{
    void *block = calloc(count, size);
    bump(&n_calloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void *kc_realloc(void *old, size_t size)
{
    void *block = realloc(old, size);
    bump(&n_realloc, 1);
    /* Net effect on live blocks, case by case. A grow in place, and a move to a fresh block,
     * are both net zero: one block goes away and one appears. */
    if (old == NULL) {
        if (block != NULL)
            bump(&n_live, 1);
    } else if (block == NULL && size == 0) {
        bump(&n_live, -1);
    }
    return block;
}

static int kc_posix_memalign(void **out, size_t alignment, size_t size)
{
    int rc = posix_memalign(out, alignment, size);
    bump(&n_posix_memalign, 1);
    if (rc == 0 && out != NULL && *out != NULL)
        bump(&n_live, 1);
    return rc;
}

static void *kc_aligned_alloc(size_t alignment, size_t size)
{
    void *block = aligned_alloc(alignment, size);
    bump(&n_aligned_alloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void *kc_valloc(size_t size)
{
    void *block = valloc(size);
    bump(&n_valloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void kc_free(void *block)
{
    /* free(NULL) is legal and allocates nothing, so it must not move either counter. */
    if (block != NULL) {
        bump(&n_free, 1);
        bump(&n_live, -1);
    }
    free(block);
}

static void *kc_mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    bump(&n_mmap, 1);
    return mmap(addr, length, prot, flags, fd, offset);
}

static int kc_munmap(void *addr, size_t length)
{
    bump(&n_munmap, 1);
    return munmap(addr, length);
}

typedef struct {
    const void *replacement;
    const void *original;
} kc_interpose_entry;

__attribute__((used)) static const kc_interpose_entry
kc_interposers[] __attribute__((section("__DATA,__interpose"))) = {
    { (const void *)&kc_malloc,          (const void *)&malloc },
    { (const void *)&kc_calloc,          (const void *)&calloc },
    { (const void *)&kc_realloc,         (const void *)&realloc },
    { (const void *)&kc_posix_memalign,  (const void *)&posix_memalign },
    { (const void *)&kc_aligned_alloc,   (const void *)&aligned_alloc },
    { (const void *)&kc_valloc,          (const void *)&valloc },
    { (const void *)&kc_free,            (const void *)&free },
    { (const void *)&kc_mmap,            (const void *)&mmap },
    { (const void *)&kc_munmap,          (const void *)&munmap },
};
