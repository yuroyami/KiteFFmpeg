/* ffkmp_strerror and the only piece of static storage in the whole helper layer.
 *
 * `static __thread char buf[256]` at ffmpeg.def line 37 is the single static
 * object in the 949 line body. The header now states the contract (see the comment above the
 * declaration of ffkmp_strerror in include/kitecodec_helpers.h); this suite is the other half of
 * the thread affinity contract and proves it.
 *
 * The contract has two halves, and a test that proved only the first would leave a reader thinking
 * the returned pointer is stable:
 *
 *   What thread-local storage DOES guarantee. Concurrent callers on different threads cannot
 *   corrupt each other. Each thread has its own 256 byte object, so a call on thread B never
 *   changes what thread A's pointer sees.
 *
 *   What it does NOT guarantee. The pointer is not stable. The next call on the SAME thread
 *   overwrites the same 256 bytes, so the string has to be copied or consumed before calling
 *   again, and it must never be stored. It is also not shareable: a pointer taken on one thread
 *   names storage another thread cannot reason about, and it does not outlive its thread. That
 *   last one is measured here in the one way that involves no undefined behaviour, by comparing
 *   addresses and never reading through a finished thread's pointer. Eight threads run and are
 *   joined one at a time: in the plain variant all eight are handed the same address, so a kept
 *   pointer would silently read a live message belonging to a different thread, and in the asan
 *   variant each gets a fresh one, so a kept pointer names memory that is gone. The case reports
 *   which happened and asserts only what holds either way, because recycling is a property of the
 *   allocator rather than of the contract.
 *
 * Interleaving discipline. Two facts shape the threaded cases.
 *
 *   pthread_barrier is not implemented on this platform, measured: the macro
 *   PTHREAD_BARRIER_SERIAL_THREAD is undefined in Apple's pthread.h. So the rendezvous is a mutex
 *   and a condition variable, written out below. It is also the synchronisation that gives every
 *   cross-thread read in this file a happens-before edge, which is what keeps the tsan variant
 *   quiet about the test's own bookkeeping rather than about ffkmp_strerror.
 *
 *   The worker threads never call into the harness. kc_case, kc_detail and the KC_ macros write
 *   shared harness state and exit(1) on failure, so calling them from two threads at once would
 *   report a race in the harness and tell us nothing about the helper. Every worker records its
 *   verdict in its own struct and main turns that into an assertion after joining.
 *
 * This suite is one of the two reasons the tsan variant exists. What tsan reported on this machine
 * is in the report for this task: zero warnings, exit 0, over all three variants.
 */

#include "harness.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#include "kitecodec_helpers.h"

#include <libavutil/error.h>

#define KC_MSG_MAX 256
#define KC_WORKERS 4
#define KC_ROUNDS 256

/* ---- A rendezvous, because this platform has no pthread_barrier ---- */

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    unsigned parties;
    unsigned waiting;
    unsigned generation;
} kc_gate;

static void gate_init(kc_gate *gate, unsigned parties)
{
    pthread_mutex_init(&gate->mutex, NULL);
    pthread_cond_init(&gate->cond, NULL);
    gate->parties = parties;
    gate->waiting = 0;
    gate->generation = 0;
}

static void gate_destroy(kc_gate *gate)
{
    pthread_cond_destroy(&gate->cond);
    pthread_mutex_destroy(&gate->mutex);
}

/* Every party blocks until all of them arrive, then all are released. */
static void gate_meet(kc_gate *gate)
{
    unsigned generation;

    pthread_mutex_lock(&gate->mutex);
    generation = gate->generation;
    gate->waiting++;
    if (gate->waiting == gate->parties) {
        gate->waiting = 0;
        gate->generation++;
        pthread_cond_broadcast(&gate->cond);
    } else {
        while (generation == gate->generation)
            pthread_cond_wait(&gate->cond, &gate->mutex);
    }
    pthread_mutex_unlock(&gate->mutex);
}

/* ---- Worker state ---- */

/* Failure codes a worker can report. Nothing here asserts; main does. */
enum {
    KC_W_OK = 0,
    KC_W_FIRST_NULL,
    KC_W_SECOND_NULL,
    KC_W_BUFFER_MOVED,
    KC_W_FIRST_WRONG,
    KC_W_SECOND_WRONG,
    KC_W_SAW_ANOTHER_THREADS_MESSAGE,
    KC_W_NOT_TERMINATED
};

typedef struct {
    int id;
    int code_a;
    int code_b;
    /* Every worker's two expected messages, computed by main before any thread starts. The
     * workers only read it, which is what makes it safe to share unlocked. */
    char (*oracles)[2][KC_MSG_MAX];
    kc_gate *gate;
    /* Results, read by main after the join. */
    const char *buffer_first;
    const char *buffer_last;
    int rounds_done;
    int verdict;
    int verdict_round;
    char observed[KC_MSG_MAX];
} kc_worker;

static int is_another_workers_message(const kc_worker *w, const char *seen)
{
    int other;

    for (other = 0; other < KC_WORKERS; other++) {
        if (other == w->id)
            continue;
        if (strcmp(seen, w->oracles[other][0]) == 0 || strcmp(seen, w->oracles[other][1]) == 0)
            return 1;
    }
    return 0;
}

/* Keeps the first failure only, and never stops the round loop. Every worker must execute exactly
 * the same number of gate_meet calls whatever it observes, or a failing worker would leave its
 * peers blocked in the rendezvous and the suite would hang instead of reporting. Hanging is the
 * one failure mode a gate cannot diagnose, so it is designed out rather than handled. */
static void worker_record(kc_worker *w, int verdict, int round, const char *seen)
{
    if (w->verdict != KC_W_OK)
        return;
    w->verdict = verdict;
    w->verdict_round = round;
    if (seen != NULL)
        snprintf(w->observed, sizeof(w->observed), "%s", seen);
}

static void *worker_main(void *arg)
{
    kc_worker *w = (kc_worker *)arg;
    int round;

    for (round = 0; round < KC_ROUNDS; round++) {
        const char *first;
        const char *second;

        /* Everybody starts the round together. */
        gate_meet(w->gate);

        first = ffkmp_strerror(w->code_a);
        if (round == 0)
            w->buffer_first = first;

        /* Every worker has now called with its own code. If the storage were shared, this is
         * where the message would already have been replaced by somebody else's. */
        gate_meet(w->gate);

        if (first == NULL) {
            worker_record(w, KC_W_FIRST_NULL, round, NULL);
        } else if (strlen(first) >= KC_MSG_MAX) {
            worker_record(w, KC_W_NOT_TERMINATED, round, NULL);
        } else if (strcmp(first, w->oracles[w->id][0]) != 0) {
            worker_record(w, is_another_workers_message(w, first)
                          ? KC_W_SAW_ANOTHER_THREADS_MESSAGE : KC_W_FIRST_WRONG, round, first);
        }

        gate_meet(w->gate);

        second = ffkmp_strerror(w->code_b);

        /* All workers have now called twice. */
        gate_meet(w->gate);

        /* The invalidation half of the contract: the second call reused the same 256 bytes, so
         * the pointer taken before it now reads the second message. */
        if (second == NULL) {
            worker_record(w, KC_W_SECOND_NULL, round, NULL);
        } else if (first != NULL && second != first) {
            worker_record(w, KC_W_BUFFER_MOVED, round, NULL);
        } else if (strlen(second) >= KC_MSG_MAX) {
            worker_record(w, KC_W_NOT_TERMINATED, round, NULL);
        } else if (strcmp(second, w->oracles[w->id][1]) != 0) {
            worker_record(w, is_another_workers_message(w, second)
                          ? KC_W_SAW_ANOTHER_THREADS_MESSAGE : KC_W_SECOND_WRONG, round, second);
        } else {
            w->buffer_last = second;
        }

        w->rounds_done = round + 1;
        gate_meet(w->gate);
    }
    return NULL;
}

static const char *verdict_text(int verdict)
{
    switch (verdict) {
    case KC_W_OK:                            return "ok";
    case KC_W_FIRST_NULL:                    return "the first call returned NULL";
    case KC_W_SECOND_NULL:                   return "the second call returned NULL";
    case KC_W_BUFFER_MOVED:                  return "the second call returned a different address";
    case KC_W_FIRST_WRONG:                   return "the first message was not this thread's";
    case KC_W_SECOND_WRONG:                  return "the second message was not this thread's";
    case KC_W_SAW_ANOTHER_THREADS_MESSAGE:   return "this thread read another thread's message";
    case KC_W_NOT_TERMINATED:                return "the message ran past 256 bytes";
    default:                                 return "unknown";
    }
}

/* ---- Single thread: the invalidation rule ---- */

static void case_single_thread_invalidation(void)
{
    const char *first;
    const char *second;
    char kept[KC_MSG_MAX];
    char oracle_einval[KC_MSG_MAX];
    char oracle_eof[KC_MSG_MAX];

    /* av_strerror into the caller's own buffer is the oracle. It is the same FFmpeg function
     * ffkmp_strerror wraps, so this compares the wrapper's storage discipline rather than
     * FFmpeg's message text. */
    KC_EQ_INT(av_strerror(AVERROR(EINVAL), oracle_einval, sizeof(oracle_einval)), 0);
    KC_EQ_INT(av_strerror(ffkmp_averror_eof(), oracle_eof, sizeof(oracle_eof)), 0);

    kc_case("ffkmp_strerror answers with the message FFmpeg itself would write");
    first = ffkmp_strerror(AVERROR(EINVAL));
    KC_NOT_NULL(first);
    KC_EQ_STR(first, oracle_einval);
    kc_detail("\"%s\"", first);

    kc_case("a second call on the same thread returns the same address");
    snprintf(kept, sizeof(kept), "%s", first);
    second = ffkmp_strerror(ffkmp_averror_eof());
    KC_NOT_NULL(second);
    KC_EQ_PTR(second, first);
    kc_detail("buffer=%p", (const void *)first);

    kc_case("and it overwrote the first message, so the first pointer is stale");
    KC_EQ_STR(second, oracle_eof);
    KC_EQ_STR(first, oracle_eof);
    KC_CHECKF(strcmp(first, kept) != 0,
              "the buffer still holds \"%s\" after a call with a different code, which would make "
              "the pointer stable and the documented contract wrong", kept);
    kc_detail("was \"%s\", now \"%s\"", kept, first);
    kc_note("this is why Internals.kt must consume the string before calling again, and why the");
    kc_note("header says it must never be stored");

    kc_case("repeated calls with the same code keep answering from the same address");
    KC_EQ_PTR(ffkmp_strerror(AVERROR(EINVAL)), first);
    KC_EQ_PTR(ffkmp_strerror(AVERROR(EINVAL)), first);
    KC_EQ_STR(first, oracle_einval);
}

/* ---- Single thread: the message always fits and is always terminated ---- */

static void case_message_bounds(void)
{
    /* buf[256] is one of the nine fixed buffers, and driving buffers to
     * their limit is tests/test_buffers.c's job, not this suite's. What belongs here is the part
     * of the storage contract a caller depends on: whatever code is passed, the result is a NUL
     * terminated C string inside the 256 bytes, so reading it can never run off the end. */
    int codes[] = {
        0, 1, -1, 12345678, INT32_MIN, INT32_MAX,
        AVERROR_EOF, AVERROR_UNKNOWN, AVERROR_INVALIDDATA, AVERROR_PATCHWELCOME, AVERROR_BUG,
        AVERROR_BUFFER_TOO_SMALL, AVERROR_DECODER_NOT_FOUND, AVERROR_EXIT
    };
    size_t i;
    size_t longest = 0;

    for (i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        const char *message;
        char oracle[KC_MSG_MAX];
        size_t length;

        kc_case("error code %d yields a terminated string inside 256 bytes", codes[i]);
        message = ffkmp_strerror(codes[i]);
        KC_NOT_NULL(message);
        length = strlen(message);
        KC_CHECKF(length < KC_MSG_MAX, "message length %zu does not fit in 256 bytes", length);
        KC_CHECKF(length > 0, "the message for %d is empty", codes[i]);
        /* av_strerror answers 0 for a code it knows and AVERROR(EINVAL) for one it does not; in
         * both cases it still writes a usable string, which is the property being asserted. */
        (void)av_strerror(codes[i], oracle, sizeof(oracle));
        KC_EQ_STR(message, oracle);
        if (length > longest)
            longest = length;
        kc_detail("len=%zu \"%s\"", length, message);
    }

    kc_case("no message on this FFmpeg comes close to the 256 byte buffer");
    KC_CHECKF(longest < KC_MSG_MAX, "longest message was %zu bytes", longest);
    kc_detail("longest=%zu of 255 usable bytes", longest);
    kc_note("so the 256 byte bound is not exercised by any real error code here; the limit and");
    kc_note("limit plus one rows for this buffer belong to tests/test_buffers.c");
}

/* ---- Several threads, interleaved on purpose ---- */

static void case_threaded_isolation(void)
{
    static char oracles[KC_WORKERS][2][KC_MSG_MAX];
    kc_worker workers[KC_WORKERS];
    pthread_t threads[KC_WORKERS];
    kc_gate gate;
    /* Two distinct codes per worker, and no code is shared between workers, so a message that
     * crosses threads is recognisable as somebody else's. */
    int codes[KC_WORKERS][2] = {
        { AVERROR(EINVAL),      AVERROR_EOF },
        { AVERROR(EAGAIN),      AVERROR_INVALIDDATA },
        { AVERROR(ENOMEM),      AVERROR_PATCHWELCOME },
        { AVERROR(ERANGE),      AVERROR_BUG }
    };
    int i;
    int j;

    kc_case("the %d workers have %d distinct expected messages between them", KC_WORKERS,
            KC_WORKERS * 2);
    for (i = 0; i < KC_WORKERS; i++) {
        for (j = 0; j < 2; j++) {
            (void)av_strerror(codes[i][j], oracles[i][j], KC_MSG_MAX);
            KC_CHECKF(strlen(oracles[i][j]) > 0, "worker %d has an empty oracle for code %d", i,
                      codes[i][j]);
        }
    }
    for (i = 0; i < KC_WORKERS * 2; i++) {
        for (j = i + 1; j < KC_WORKERS * 2; j++) {
            const char *left = oracles[i / 2][i % 2];
            const char *right = oracles[j / 2][j % 2];
            KC_CHECKF(strcmp(left, right) != 0,
                      "two of the chosen error codes share the message \"%s\", so a leak between "
                      "threads would be invisible", left);
        }
    }

    gate_init(&gate, KC_WORKERS);
    for (i = 0; i < KC_WORKERS; i++) {
        workers[i].id = i;
        workers[i].code_a = codes[i][0];
        workers[i].code_b = codes[i][1];
        workers[i].oracles = oracles;
        workers[i].gate = &gate;
        workers[i].buffer_first = NULL;
        workers[i].buffer_last = NULL;
        workers[i].rounds_done = 0;
        workers[i].verdict = KC_W_OK;
        workers[i].verdict_round = -1;
        workers[i].observed[0] = '\0';
    }

    kc_case("%d threads, %d interleaved rounds each, every one seeing only its own message",
            KC_WORKERS, KC_ROUNDS);
    for (i = 0; i < KC_WORKERS; i++)
        KC_EQ_INT(pthread_create(&threads[i], NULL, worker_main, &workers[i]), 0);
    for (i = 0; i < KC_WORKERS; i++)
        KC_EQ_INT(pthread_join(threads[i], NULL), 0);
    for (i = 0; i < KC_WORKERS; i++) {
        KC_CHECKF(workers[i].verdict == KC_W_OK, "worker %d failed in round %d: %s%s%s", i,
                  workers[i].verdict_round, verdict_text(workers[i].verdict),
                  workers[i].observed[0] != '\0' ? ", it read " : "", workers[i].observed);
    }
    kc_detail("%d rounds x %d workers, 0 crossings", KC_ROUNDS, KC_WORKERS);

    kc_case("each thread had its own 256 bytes, at a different address from every other");
    for (i = 0; i < KC_WORKERS; i++) {
        KC_NOT_NULL(workers[i].buffer_first);
        KC_EQ_PTR(workers[i].buffer_last, workers[i].buffer_first);
    }
    for (i = 0; i < KC_WORKERS; i++) {
        for (j = i + 1; j < KC_WORKERS; j++) {
            KC_CHECKF(workers[i].buffer_first != workers[j].buffer_first,
                      "workers %d and %d were handed the same address %p, so the storage is not "
                      "per thread", i, j, (const void *)workers[i].buffer_first);
        }
    }
    KC_CHECKF(workers[0].buffer_first != ffkmp_strerror(AVERROR(EINVAL)),
              "a worker and the main thread shared an address");
    kc_detail("addresses %p %p %p %p", (const void *)workers[0].buffer_first,
              (const void *)workers[1].buffer_first, (const void *)workers[2].buffer_first,
              (const void *)workers[3].buffer_first);
    kc_note("only the addresses were compared across threads. Nothing here dereferences another");
    kc_note("thread's pointer: that storage belongs to that thread and the contract says so.");

    gate_destroy(&gate);
}

/* ---- What the contract does not promise: the address outlives nothing ---- */

typedef struct {
    int code;
    const char *buffer;
    char message[KC_MSG_MAX];
} kc_solo;

static void *solo_main(void *arg)
{
    kc_solo *solo = (kc_solo *)arg;
    const char *p = ffkmp_strerror(solo->code);

    solo->buffer = p;
    if (p != NULL)
        snprintf(solo->message, sizeof(solo->message), "%s", p);
    return NULL;
}

#define KC_SOLO_THREADS 8

static void case_address_does_not_outlive_its_thread(void)
{
    kc_solo solos[KC_SOLO_THREADS];
    char oracle[KC_MSG_MAX];
    int distinct = 0;
    int repeats = 0;
    int i;
    int j;

    (void)av_strerror(AVERROR(EINVAL), oracle, sizeof(oracle));

    /* Eight threads, one after another, each created after the previous one was joined. Nothing
     * runs concurrently here, so the only thing being measured is what happens to the storage
     * when a thread ends. */
    kc_case("%d threads run and are joined one at a time, each reading its own message",
            KC_SOLO_THREADS);
    for (i = 0; i < KC_SOLO_THREADS; i++) {
        pthread_t thread;
        solos[i].code = AVERROR(EINVAL);
        solos[i].buffer = NULL;
        solos[i].message[0] = '\0';
        KC_EQ_INT(pthread_create(&thread, NULL, solo_main, &solos[i]), 0);
        KC_EQ_INT(pthread_join(thread, NULL), 0);
        KC_NOT_NULL(solos[i].buffer);
        KC_EQ_STR(solos[i].message, oracle);
    }

    for (i = 0; i < KC_SOLO_THREADS; i++) {
        int seen_before = 0;
        for (j = 0; j < i; j++) {
            if (solos[j].buffer == solos[i].buffer)
                seen_before = 1;
        }
        if (seen_before)
            repeats++;
        else
            distinct++;
    }

    /* Whether the address comes back is a property of the allocator, not of the contract, and it
     * differs by build variant on this machine: the plain variant recycles the slot and hands
     * every one of the eight threads the same address, while the asan variant gives each a fresh
     * one. Both outcomes say the same thing about the pointer, so the case reports which happened
     * and asserts only what is true either way. */
    kc_case("a dead thread's address is not something a caller may keep");
    KC_CHECK(distinct >= 1);
    KC_CHECK(distinct + repeats == KC_SOLO_THREADS);
    kc_detail("%d distinct addresses across %d joined threads, first at %p", distinct,
              KC_SOLO_THREADS, (const void *)solos[0].buffer);
    if (repeats > 0) {
        kc_note("the storage was recycled, measured: a pointer kept past its thread's life would");
        kc_note("read a live message belonging to a different thread instead of failing, which is");
        kc_note("the worst shape a stale pointer can take.");
    } else {
        kc_note("the storage was not recycled in this variant, so a kept pointer would name");
        kc_note("memory that is simply gone. Reading it is undefined either way.");
    }
    kc_note("nothing here reads through a finished thread's pointer; only addresses were");
    kc_note("compared, which is defined behaviour and enough to make the point.");
}

int main(void)
{
    kc_suite_begin("test_strerror_thread");

    case_single_thread_invalidation();
    case_message_bounds();
    case_threaded_isolation();
    case_address_does_not_outlive_its_thread();

    return kc_suite_end();
}
