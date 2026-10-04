/* The format part of the FFmpeg helper layer: AVFormatContext, input and output. */

#include "kitecodec_helpers.h"

#include <pthread.h>
#include <stddef.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavutil/aes.h>
#include <libavutil/common.h>
#include <libavutil/avstring.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>

/* ════════════ AVFormatContext (input + output) ════════════ */

/* The one interrupt seam for every input open. The opaque is always a plain int
   cell; for the custom-AVIO open it lives inside the bridge and dies with it, for path opens
   it is its own allocation freed by the paired close. FFmpeg polls it at the top of every
   blocking loop and returns AVERROR_EXIT once it reads nonzero. One-way by design: an
   interrupted context is being abandoned, and clearing the flag mid-flight is how a
   cancelled read resumes into freed state.

   A raise comes from any thread while FFmpeg polls on the one inside the open, so every cell
   access is a relaxed atomic: a plain or volatile int there is a data race in C11 (#116). */
static int kc_cell_raised(const int *cell) {
    return __atomic_load_n(cell, __ATOMIC_RELAXED);
}

static void kc_cell_raise(int *cell) {
    __atomic_store_n(cell, 1, __ATOMIC_RELAXED);
}

static int kc_interrupt_check(void *opaque) {
    return opaque ? kc_cell_raised((const int *)opaque) : 0;
}

/* The same poll over a cell the CALLER owns (kc_interrupt). A separate function only so that
   the close can tell the two apart: a borrowed cell is never this layer's to free. */
static int kc_interrupt_check_borrowed(void *opaque) {
    return opaque ? kc_cell_raised((const int *)opaque) : 0;
}

/* The caller-owned cell. `raised` is first, so the cell reads as the plain int both polls
   expect. */
struct kc_interrupt {
    int raised;
};

KC_API kc_interrupt *ffkmp_interrupt_new(void) {
    if (!KC_GATE_OPEN()) return NULL;
    return av_mallocz(sizeof(kc_interrupt));
}
KC_API void ffkmp_interrupt_raise(kc_interrupt *cell) {
    if (cell) kc_cell_raise(&cell->raised);
}
KC_API void ffkmp_interrupt_free(kc_interrupt **cell) {
    if (cell) av_freep(cell);
}

/* True when the context polls one of this layer's cells, owned or borrowed. */
static int kc_ctx_has_cell(const AVFormatContext *s) {
    return s && s->interrupt_callback.opaque &&
        (s->interrupt_callback.callback == kc_interrupt_check ||
         s->interrupt_callback.callback == kc_interrupt_check_borrowed);
}

/* Entry-point poll. FFmpeg itself only polls the seam inside find_stream_info and the URL
   protocol IO loop, so a fully buffered read never sees it; the fail-fast half of the
   contract is therefore checked HERE, at our own entry points. Every context this layer
   opens carries an int cell as the opaque, so the read is safe by construction. */
static int kc_ctx_interrupted(AVFormatContext *s) {
    return kc_ctx_has_cell(s) && kc_cell_raised((const int *)s->interrupt_callback.opaque);
}

KC_API void ffkmp_fmt_interrupt(AVFormatContext *ctx) {
    if (!kc_ctx_has_cell(ctx)) return;
    kc_cell_raise((int *)ctx->interrupt_callback.opaque);
}

/* Installs the interrupt poll on c: over the caller's cell when there is one, else over
   *own, which the caller of this function allocates and frees. */
static void kc_install_interrupt(AVFormatContext *c, kc_interrupt *borrowed, int *own) {
    if (borrowed) {
        c->interrupt_callback.callback = kc_interrupt_check_borrowed;
        c->interrupt_callback.opaque = (void *)&borrowed->raised;
    } else {
        c->interrupt_callback.callback = kc_interrupt_check;
        c->interrupt_callback.opaque = own;
    }
}

KC_API int  ffkmp_fmt_open_input(AVFormatContext **out, const char *path) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (!path) return AVERROR(EINVAL);
    AVFormatContext *c = avformat_alloc_context();
    if (!c) return AVERROR(ENOMEM);
    int *cell = av_mallocz(sizeof(int));
    if (!cell) { avformat_free_context(c); return AVERROR(ENOMEM); }
    c->interrupt_callback.callback = kc_interrupt_check;
    c->interrupt_callback.opaque = cell;
    /* On failure avformat_open_input frees the context it was handed; the cell is ours. */
    int rc = avformat_open_input(&c, path, NULL, NULL);
    if (rc < 0) { av_freep(&cell); return rc; }
    *out = c; return 0;
}
/* Frees the packets a checked seek of the context read and still holds (#155); defined with it. */
static void kc_held_drop(const AVFormatContext *ctx);

KC_API void ffkmp_fmt_close_input(AVFormatContext **ctx) {
    if (!ctx || !*ctx) return;
    AVFormatContext *p = *ctx;
    /* The path opens own their interrupt cell; grab it before the context is freed. A
       custom-io context must never surrender its cell here: that pointer is INTERIOR to the
       bridge, and freeing it would corrupt the heap on top of the AVIO leak this misuse
       already was. The flag is the provenance check. */
    void *cell = (p->interrupt_callback.callback == kc_interrupt_check &&
                  !(p->flags & AVFMT_FLAG_CUSTOM_IO))
        ? p->interrupt_callback.opaque : NULL;
    kc_held_drop(p);
    avformat_close_input(&p);
    *ctx = NULL;
    av_free(cell);
}
/* The option key that names a demuxer to force. It is KiteFFmpeg's own and never reaches FFmpeg,
   which has no option for it: avformat_open_input takes the format as an argument. */
#define KC_FORCED_FORMAT_KEY "kiteffmpeg_input_format"

/* The open's option dictionary from the pairs, with the forced format taken out of it: *forced is
   that demuxer, or NULL when none is named. A name this build does not carry answers
   AVERROR_DEMUXER_NOT_FOUND. On failure nothing is left allocated. */
/* The HLS option the trust_io_open patch adds. Only ffkmp_fmt_open_input_io2 sets it, together with
   the io_open that decides every URL, so kc_open_options refuses it from callers. */
#define KC_TRUST_IO_OPEN_KEY "trust_io_open"

static int kc_open_options(const char *const *keys, const char *const *values, int n,
                           AVDictionary **options, const AVInputFormat **forced) {
    *options = NULL;
    *forced = NULL;
    for (int i = 0; i < n; i++) {
        if (!keys[i] || !values[i]) { av_dict_free(options); return AVERROR(EINVAL); }
        if (strcmp(keys[i], KC_TRUST_IO_OPEN_KEY) == 0) { av_dict_free(options); return AVERROR(EINVAL); }
        if (strcmp(keys[i], KC_FORCED_FORMAT_KEY) == 0) {
            *forced = av_find_input_format(values[i]);
            if (!*forced) { av_dict_free(options); return AVERROR_DEMUXER_NOT_FOUND; }
            continue;
        }
        int rc = av_dict_set(options, keys[i], values[i], 0);
        if (rc < 0) { av_dict_free(options); return rc; }
    }
    return 0;
}

/* True pre-open options. The pairs are applied between allocation and open, which is the
 * only moment probesize, fflags and format forcing can act. Keys FFmpeg does not consume stay
 * in the dictionary afterwards; that remainder is handed to the caller through *unused (owned;
 * release with ffkmp_dict_free), because a silently ignored option is a debugging session. */
KC_API int ffkmp_fmt_open_input2(AVFormatContext **out, const char *path,
                                 const char *const *keys, const char *const *values,
                                 int n, AVDictionary **unused, kc_interrupt *interrupt) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (unused) *unused = NULL;
    if (!path) return AVERROR(EINVAL);
    if (n < 0) return AVERROR(EINVAL);
    if (n > 0 && (!keys || !values)) return AVERROR(EINVAL);
    if (interrupt && kc_cell_raised(&interrupt->raised)) return AVERROR_EXIT;
    AVDictionary *options = NULL;
    const AVInputFormat *forced = NULL;
    int built = kc_open_options(keys, values, n, &options, &forced);
    if (built < 0) return built;
    AVFormatContext *c = avformat_alloc_context();
    if (!c) { av_dict_free(&options); return AVERROR(ENOMEM); }
    int *cell = NULL;
    if (!interrupt) {
        cell = av_mallocz(sizeof(int));
        if (!cell) { avformat_free_context(c); av_dict_free(&options); return AVERROR(ENOMEM); }
    }
    kc_install_interrupt(c, interrupt, cell);
    int rc = avformat_open_input(&c, path, forced, &options);
    if (rc < 0) { av_freep(&cell); av_dict_free(&options); return rc; }
    if (unused) *unused = options;    /* the caller owns the remainder, possibly NULL */
    else av_dict_free(&options);
    *out = c;
    return 0;
}

/* The one owned-dictionary release, for ffkmp_fmt_open_input2's remainder. Safe on NULL and on
 * a pointer whose dictionary is already NULL; writes NULL through the pointer either way. It
 * must never be used on the BORROWED dictionaries the metadata accessors return. */
KC_API void ffkmp_dict_free(AVDictionary **dict) {
    if (dict) av_dict_free(dict);
}
/* The chapter table. Times are rescaled onto
 * microseconds here, because every timestamp this ABI hands over speaks AV_TIME_BASE. */
KC_API int ffkmp_fmt_chapter_count(const AVFormatContext *ctx) {
    return ctx ? (int)ctx->nb_chapters : AVERROR(EINVAL);
}
KC_API int ffkmp_fmt_chapter_get(const AVFormatContext *ctx, int index,
                                 int64_t *out_id, int64_t *out_start_us, int64_t *out_end_us) {
    if (!ctx || index < 0 || (unsigned)index >= ctx->nb_chapters) return AVERROR(EINVAL);
    if (!out_id || !out_start_us || !out_end_us) return AVERROR(EINVAL);
    const AVChapter *ch = ctx->chapters[index];
    *out_id = ch->id;
    *out_start_us = av_rescale_q(ch->start, ch->time_base, AV_TIME_BASE_Q);
    *out_end_us = av_rescale_q(ch->end, ch->time_base, AV_TIME_BASE_Q);
    return 0;
}
/* The chapter's own metadata dictionary (title lives here), reusing the standing dict walk. */
KC_API AVDictionary *ffkmp_fmt_chapter_metadata(const AVFormatContext *ctx, int index) {
    if (!ctx || index < 0 || (unsigned)index >= ctx->nb_chapters) return NULL;
    return ctx->chapters[index]->metadata;
}
/* The programme table (#148): the channels of a transport stream multiplex, the variants of an HLS
 * master playlist, or the one programme of a DASH presentation. program_num is 0 where the
 * container states no number. In a transport stream the programme association table states it,
 * so a channel that only the service description table names has 0 there, and no streams. */
KC_API int ffkmp_fmt_program_count(const AVFormatContext *ctx) {
    return ctx ? (int)ctx->nb_programs : AVERROR(EINVAL);
}
KC_API int ffkmp_fmt_program_get(const AVFormatContext *ctx, int index,
                                 int *out_id, int *out_number, int *out_stream_count) {
    if (!ctx || index < 0 || (unsigned)index >= ctx->nb_programs) return AVERROR(EINVAL);
    if (!out_id || !out_number || !out_stream_count) return AVERROR(EINVAL);
    const AVProgram *program = ctx->programs[index];
    *out_id = program->id;
    *out_number = program->program_num;
    *out_stream_count = (int)program->nb_stream_indexes;
    return 0;
}
KC_API int ffkmp_fmt_program_stream(const AVFormatContext *ctx, int index, int position) {
    if (!ctx || index < 0 || (unsigned)index >= ctx->nb_programs) return AVERROR(EINVAL);
    const AVProgram *program = ctx->programs[index];
    if (position < 0 || (unsigned)position >= program->nb_stream_indexes) return AVERROR(EINVAL);
    return (int)program->stream_index[position];
}
/* The programme's own tags, where a transport stream's service_name and service_provider live. */
KC_API AVDictionary *ffkmp_fmt_program_metadata(const AVFormatContext *ctx, int index) {
    if (!ctx || index < 0 || (unsigned)index >= ctx->nb_programs) return NULL;
    return ctx->programs[index]->metadata;
}
KC_API int  ffkmp_fmt_find_stream_info(AVFormatContext *c) {
    return c ? avformat_find_stream_info(c, NULL) : AVERROR(EINVAL);
}

/* ════════════ Keyframe seeks that land where they promise (#155) ════════════

   A backward seek promises the last keyframe that shows at or before its target, and FFmpeg finds
   that keyframe by when it DECODES. Once B-frames reorder the pictures the two differ. MP4's reader
   turns the target into a decode time by one constant and checks the result only for HEVC, so in an
   open group of pictures it takes a keyframe that shows up to the reorder depth after the target.
   FLV seeks by decode time outright. MPEG-TS searches byte positions and lands on whatever packet
   sits there, so the first keyframe after it shows late on nearly every seek.

   So a backward seek of a video stream reads on to that stream's first keyframe and looks at when
   it shows. Shown in time, the packets read on the way are held, and ffkmp_fmt_read_frame hands
   them out before it reads anything new, so a seek FFmpeg already got right costs no second read
   and no second seek. Shown late, the seek aims before that keyframe, by its own reorder delay or
   by how late it showed, whichever is more, and four times as far again on each later try; on an
   indexed container the first try lands on the keyframe before. The stream's own packets before
   its keyframe, which a byte-position seek lands among, are dropped: nothing can show them
   without what came before.

   The packets held are those of the streams that were on for the seek, and kc_held_take answers
   a selection changed before they are all handed out. */

/* How far a check reads before it gives up on finding the keyframe: ten seconds between keyframes
   at 25 Mbit/s. The seek then stands as FFmpeg made it. */
#define KC_SEEK_LOOK_BYTES ((int64_t)32 << 20)
/* How many times a seek aims earlier before it settles for where it landed. */
#define KC_SEEK_TRIES 12

/* A checked seek as its caller asked for it: stream si, or the default stream in AV_TIME_BASE when
   si is -1, back to target and no earlier than floor, through avformat_seek_file when windowed. */
typedef struct kc_seek_args {
    int si;
    int windowed;
    int flags;
    int64_t floor;
    int64_t target;
} kc_seek_args;

/* The packets a checked seek read and still owes the reader of one context. */
typedef struct kc_held {
    const AVFormatContext *ctx;
    AVPacket **packets;
    int count;
    int next;
    /* The seek that read them, and which of the context's streams were on for it, one byte for
       each stream the context had. */
    kc_seek_args seek;
    uint8_t *on;
    unsigned on_count;
    /* Whether the reader has been handed one of them yet. */
    int handed;
    struct kc_held *link;
} kc_held;

static pthread_mutex_t kc_held_lock = PTHREAD_MUTEX_INITIALIZER;
static kc_held *kc_held_list;
/* How many contexts hold packets, read without the lock so that a read with nothing held, which is
   nearly every read, never takes it. A context's packets are held by its own seek, which its caller
   orders before its reads, so a count that includes them is always seen. */
static int kc_held_contexts;

static void kc_packets_free(AVPacket **packets, int from, int count) {
    for (int i = from; i < count; i++) av_packet_free(&packets[i]);
    av_free(packets);
}

static void kc_held_free(kc_held *h) {
    kc_packets_free(h->packets, h->next, h->count);
    av_free(h->on);
    av_free(h);
}

/* Unlinks ctx's held packets, or returns NULL when it holds none. Called with the lock held. */
static kc_held *kc_held_unlink(const AVFormatContext *ctx) {
    for (kc_held **at = &kc_held_list; *at; at = &(*at)->link) {
        kc_held *h = *at;
        if (h->ctx != ctx) continue;
        *at = h->link;
        __atomic_sub_fetch(&kc_held_contexts, 1, __ATOMIC_RELEASE);
        return h;
    }
    return NULL;
}

/* Frees what ctx still holds: a seek moves past it, and a close takes the context away. */
static void kc_held_drop(const AVFormatContext *ctx) {
    if (!ctx || !__atomic_load_n(&kc_held_contexts, __ATOMIC_ACQUIRE)) return;
    pthread_mutex_lock(&kc_held_lock);
    kc_held *h = kc_held_unlink(ctx);
    pthread_mutex_unlock(&kc_held_lock);
    if (h) kc_held_free(h);
}

static int kc_stream_on(const AVFormatContext *ctx, unsigned index) {
    return index < ctx->nb_streams && ctx->streams[index]->discard < AVDISCARD_ALL;
}

/* Whether a stream that was off for h's seek is on now. */
static int kc_held_widened(const AVFormatContext *ctx, const kc_held *h) {
    unsigned known = FFMIN(h->on_count, ctx->nb_streams);
    for (unsigned i = 0; i < known; i++)
        if (!h->on[i] && kc_stream_on(ctx, i)) return 1;
    return 0;
}

static int kc_seek_keyframe(AVFormatContext *ctx, int si, int windowed, int64_t floor, int64_t target, int flags);

/* Moves the next packet ctx holds into p. Returns 1 when it did, 0 when ctx holds none, or a
   negative AVERROR from a seek made again.

   The held packets are of the streams that were on when the seek read them, while the demuxer
   already stands after them, so a selection changed since then is answered here. A stream turned
   off is passed over, as the demuxer would pass it over. A stream turned on before the reader took
   anything makes the seek again, so that it lands as a seek with that stream on lands. A stream
   turned on after that joins where the demuxer stands, after these packets, which is where a
   stream turned on in the middle of reading joins. */
static int kc_held_take(AVFormatContext *ctx, AVPacket *p) {
    for (;;) {
        if (!__atomic_load_n(&kc_held_contexts, __ATOMIC_ACQUIRE)) return 0;
        kc_held *spent = NULL, *stale = NULL;
        int took = 0;
        pthread_mutex_lock(&kc_held_lock);
        kc_held *h = kc_held_list;
        while (h && h->ctx != ctx) h = h->link;
        if (h && !h->handed && kc_held_widened(ctx, h)) {
            stale = kc_held_unlink(ctx);
        } else if (h) {
            while (!took && h->next < h->count) {
                AVPacket *q = h->packets[h->next];
                h->packets[h->next++] = NULL;
                if (kc_stream_on(ctx, (unsigned)q->stream_index)) {
                    av_packet_unref(p);
                    av_packet_move_ref(p, q);
                    h->handed = took = 1;
                }
                av_packet_free(&q);
            }
            if (h->next == h->count) spent = kc_held_unlink(ctx);
        }
        pthread_mutex_unlock(&kc_held_lock);
        if (spent) kc_held_free(spent);
        if (!stale) return took;
        kc_seek_args seek = stale->seek;
        kc_held_free(stale);
        int rc = kc_seek_keyframe(ctx, seek.si, seek.windowed, seek.floor, seek.target, seek.flags);
        if (rc < 0) return rc;
    }
}

#ifdef KC_TESTING
/* How many contexts hold packets, for the host suites (tests/kc_test_seams.h). */
int kc_test_held_contexts(void);
int kc_test_held_contexts(void) {
    return __atomic_load_n(&kc_held_contexts, __ATOMIC_ACQUIRE);
}
#endif

/* The packets one check read, in the order it read them, owned. */
typedef struct kc_run {
    AVPacket **packets;
    int count;
    int capacity;
    int64_t bytes;
} kc_run;

static void kc_run_free(kc_run *run) {
    kc_packets_free(run->packets, 0, run->count);
    memset(run, 0, sizeof(*run));
}

/* Hands run's packets, read by seek, to ctx's next reads, or frees an empty run. */
static int kc_held_put(const AVFormatContext *ctx, kc_run *run, const kc_seek_args *seek) {
    if (!run->count) { kc_run_free(run); return 0; }
    kc_held *h = av_mallocz(sizeof(*h));
    uint8_t *on = av_malloc(FFMAX(ctx->nb_streams, 1u));
    if (!h || !on) {
        av_free(h);
        av_free(on);
        kc_run_free(run);
        return AVERROR(ENOMEM);
    }
    for (unsigned i = 0; i < ctx->nb_streams; i++) on[i] = (uint8_t)kc_stream_on(ctx, i);
    h->ctx = ctx;
    h->packets = run->packets;
    h->count = run->count;
    h->seek = *seek;
    h->on = on;
    h->on_count = ctx->nb_streams;
    memset(run, 0, sizeof(*run));
    pthread_mutex_lock(&kc_held_lock);
    h->link = kc_held_list;
    kc_held_list = h;
    __atomic_add_fetch(&kc_held_contexts, 1, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&kc_held_lock);
    return 0;
}

enum { KC_LOOK_LATE, KC_LOOK_GOOD, KC_LOOK_ENDED, KC_LOOK_TOO_FAR };

/* Frees run's packets before place `from`, so that the packet there comes first. */
static void kc_run_start_at(kc_run *run, int from) {
    if (from <= 0) return;
    for (int i = 0; i < from; i++) av_packet_free(&run->packets[i]);
    memmove(run->packets, run->packets + from, (size_t)(run->count - from) * sizeof(*run->packets));
    run->count -= from;
    run->bytes = 0;
    for (int i = 0; i < run->count; i++) run->bytes += run->packets[i]->size;
}

/* When packet p shows, or when it decodes if it states no showing. */
static int64_t kc_shows(const AVPacket *p) {
    return p->pts != AV_NOPTS_VALUE ? p->pts : p->dts;
}

/* Reads ctx on from where it stands into run, looking at stream st's keyframes against target, in
   time base tb. A keyframe that shows at or before the target becomes *best, its place in run;
   without onward the first one ends the look as KC_LOOK_GOOD, and with onward the look goes on for
   a later one, dropping what came before each. The first keyframe that shows after the target ends
   the look as KC_LOOK_LATE, kept in run after *best's pictures, with its place in *late. Otherwise
   the look answers KC_LOOK_ENDED at the end of the input, KC_LOOK_TOO_FAR past KC_SEEK_LOOK_BYTES,
   or a read's negative AVERROR. */
static int kc_look(AVFormatContext *ctx, const AVStream *st, int64_t target, AVRational tb, int onward,
                   kc_run *run, int *best, int *late) {
    *best = -1;
    *late = -1;
    for (;;) {
        if (run->bytes > KC_SEEK_LOOK_BYTES) return KC_LOOK_TOO_FAR;
        AVPacket *p = av_packet_alloc();
        if (!p) return AVERROR(ENOMEM);
        int rc = av_read_frame(ctx, p);
        if (rc < 0) {
            av_packet_free(&p);
            return rc == AVERROR_EOF ? KC_LOOK_ENDED : rc;
        }
        if (run->count == run->capacity) {
            int capacity = run->capacity ? run->capacity * 2 : 16;
            AVPacket **grown = av_realloc_array(run->packets, (size_t)capacity, sizeof(*grown));
            if (!grown) { av_packet_free(&p); return AVERROR(ENOMEM); }
            run->packets = grown;
            run->capacity = capacity;
        }
        run->packets[run->count++] = p;
        run->bytes += p->size;
        if (p->stream_index != st->index || !(p->flags & AV_PKT_FLAG_KEY)) continue;
        int64_t shows = kc_shows(p);
        if (shows != AV_NOPTS_VALUE && av_compare_ts(shows, st->time_base, target, tb) > 0) {
            *late = run->count - 1;
            return KC_LOOK_LATE;
        }
        if (*best >= 0) kc_run_start_at(run, run->count - 1);
        *best = run->count - 1;
        if (!onward) return KC_LOOK_GOOD;
    }
}

/* Drops stream ref's packets before run's keyframe at place key: nothing shows them without the
   pictures before them, which a byte-position seek lands among. */
static void kc_run_drop_before_key(kc_run *run, int ref, int key) {
    int kept = 0;
    for (int i = 0; i < run->count; i++) {
        if (i < key && run->packets[i]->stream_index == ref) {
            av_packet_free(&run->packets[i]);
            continue;
        }
        run->packets[kept++] = run->packets[i];
    }
    run->count = kept;
}

/* One seek of stream si, or of the default stream in AV_TIME_BASE when si is -1, to aim: through
   avformat_seek_file over the window from floor when windowed, else through av_seek_frame. */
static int kc_seek_once(AVFormatContext *ctx, int si, int windowed, int64_t floor, int64_t aim, int flags) {
    return windowed ? avformat_seek_file(ctx, si, floor, aim, aim, flags)
                    : av_seek_frame(ctx, si, aim, AVSEEK_FLAG_BACKWARD);
}

/* The backward keyframe seek both entry points share. target and floor are in stream si's time
   base, or AV_TIME_BASE when si is -1.

   The first look takes the first keyframe it meets when that one shows in time: FFmpeg chose it as
   the last one that decodes before the target, so no later keyframe shows in time either. A look
   after the seek aimed earlier reads on to the first keyframe that shows late and keeps the last
   one before it, so a byte-position seek, which cannot aim at a keyframe, still lands on the right
   one. */
static int kc_seek_keyframe(AVFormatContext *ctx, int si, int windowed, int64_t floor, int64_t target, int flags) {
    kc_held_drop(ctx);
    int rc = kc_seek_once(ctx, si, windowed, floor, target, flags);
    if (rc < 0) return rc;
    int ref = si >= 0 ? si : av_find_default_stream_index(ctx);
    if (ref < 0 || (unsigned)ref >= ctx->nb_streams) return rc;
    AVStream *st = ctx->streams[ref];
    /* Only pictures reorder, a cover picture is not a stream of them, and a stream the reader has
       turned off delivers nothing to look at. */
    if (st->codecpar->codec_type != AVMEDIA_TYPE_VIDEO || (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ||
        st->discard >= AVDISCARD_ALL) return rc;
    AVRational tb = si >= 0 ? st->time_base : AV_TIME_BASE_Q;
    const kc_seek_args seek = { si, windowed, flags, floor, target };
    int64_t aim = target;
    int64_t step = 0;
    for (int tries = 1;; tries++) {
        kc_run run = { 0 };
        int best = -1, late = -1;
        int looked = kc_look(ctx, st, target, tb, tries > 1, &run, &best, &late);
        if (looked < 0) { kc_run_free(&run); return looked; }
        if (best >= 0) {
            kc_run_drop_before_key(&run, ref, best);
            return kc_held_put(ctx, &run, &seek);
        }
        if (looked == KC_LOOK_TOO_FAR) {
            /* Too far apart to look at: the seek stands where it last landed. */
            kc_run_free(&run);
            return kc_seek_once(ctx, si, windowed, floor, aim, flags);
        }
        if (tries == KC_SEEK_TRIES || aim <= floor) return kc_held_put(ctx, &run, &seek);
        /* Nothing in time: aim before the keyframe that showed late, by its reorder delay or by how
           late it showed, whichever is more, and four times as far as the last try after that. With
           no keyframe before the end of the input, the first step is half a second. */
        int64_t from = aim;
        int64_t first_step = av_rescale_q(AV_TIME_BASE / 2, AV_TIME_BASE_Q, tb);
        if (looked == KC_LOOK_LATE) {
            const AVPacket *k = run.packets[late];
            int64_t shows = kc_shows(k);
            int64_t decodes = k->dts != AV_NOPTS_VALUE ? k->dts : shows;
            int64_t decodes_at = av_rescale_q_rnd(decodes, st->time_base, tb, AV_ROUND_DOWN | AV_ROUND_PASS_MINMAX);
            int64_t shows_at = av_rescale_q_rnd(shows, st->time_base, tb, AV_ROUND_UP | AV_ROUND_PASS_MINMAX);
            first_step = FFMAX(shows_at - decodes_at, shows_at - target);
            from = FFMIN(aim, decodes_at);
        }
        kc_run_free(&run);
        step = step ? (step > INT64_MAX / 4 ? INT64_MAX : step * 4) : FFMAX(first_step, 1);
        aim = from <= floor || (uint64_t)from - (uint64_t)floor <= (uint64_t)step ? floor : from - step;
        rc = kc_seek_once(ctx, si, windowed, floor, aim, flags);
        if (rc < 0 && st->start_time != AV_NOPTS_VALUE) {
            /* This demuxer cannot go that early, and the stream's own start is as early as it gets. */
            int64_t start = FFMAX(av_rescale_q(st->start_time, st->time_base, tb), floor);
            if (start > aim) {
                aim = start;
                rc = kc_seek_once(ctx, si, windowed, floor, aim, flags);
            }
        }
        if (rc < 0) return kc_seek_once(ctx, si, windowed, floor, target, flags);
        if (kc_ctx_interrupted(ctx)) return AVERROR_EXIT;
    }
}

KC_API int  ffkmp_fmt_seek_micros(AVFormatContext *ctx, int stream_index, int64_t micros) {
    if (kc_ctx_interrupted(ctx)) return AVERROR_EXIT;
    if (!ctx) return AVERROR(EINVAL);
    /* An index at or past nb_streams used to index ctx->streams[]
     * unchecked, reproduced as signal 11 through this exported entry point. -1 keeps its
     * documented meaning, any stream; every other out of range index is refused. */
    if (stream_index < -1 || (stream_index >= 0 && (unsigned)stream_index >= ctx->nb_streams)) return AVERROR(EINVAL);
    int64_t target = stream_index < 0 ? micros : av_rescale_q(micros, AV_TIME_BASE_Q, ctx->streams[stream_index]->time_base);
    return kc_seek_keyframe(ctx, stream_index, 0, INT64_MIN, target, AVSEEK_FLAG_BACKWARD);
}

/* avformat_seek_file, which av_seek_frame cannot express: a bounded window rather than a single
   target. A player uses it to say "land at or before here, but no earlier than there", which is
   what makes a retry ladder cheap instead of a fixed pessimistic backoff. A window that ends at its
   target asks for a keyframe at or before it, and gets one as kc_seek_keyframe makes sure of. */
KC_API int ffkmp_fmt_seek_file(AVFormatContext *ctx, int stream_index,
                                     int64_t min_ts, int64_t ts, int64_t max_ts, int flags) {
    if (!ctx) return AVERROR(EINVAL);
    if (kc_ctx_interrupted(ctx)) return AVERROR_EXIT;
    if (stream_index < -1 || (stream_index >= 0 && (unsigned)stream_index >= ctx->nb_streams)) return AVERROR(EINVAL);
    if (max_ts == ts && min_ts <= ts && !(flags & (AVSEEK_FLAG_BYTE | AVSEEK_FLAG_ANY | AVSEEK_FLAG_FRAME)))
        return kc_seek_keyframe(ctx, stream_index, 1, min_ts, ts, flags);
    kc_held_drop(ctx);
    return avformat_seek_file(ctx, stream_index, min_ts, ts, max_ts, flags);
}

/* Hands out what a checked seek read first, then reads on, passing over the packets of streams
   turned off. Some demuxers still hand those out, MPEG-TS among them for a packet it began while
   the stream was on, which a seek's look can make it do. */
KC_API int  ffkmp_fmt_read_frame(AVFormatContext *c, AVPacket *p) {
    if (kc_ctx_interrupted(c)) return AVERROR_EXIT;
    if (!c || !p) return AVERROR(EINVAL);
    int rc = kc_held_take(c, p);
    if (rc) return rc < 0 ? rc : 0;
    for (;;) {
        rc = av_read_frame(c, p);
        if (rc < 0 || kc_stream_on(c, (unsigned)p->stream_index)) return rc;
        av_packet_unref(p);
        if (kc_ctx_interrupted(c)) return AVERROR_EXIT;
    }
}

KC_API int64_t       ffkmp_fmt_duration(AVFormatContext *c)   { return c ? c->duration : 0; }
KC_API int           ffkmp_fmt_duration_origin(AVFormatContext *c) {
    return c ? (int)c->duration_estimation_method : -1;
}
/* Where the media's timeline BEGINS, in microseconds (AV_TIME_BASE units), i.e. the earliest
   start_time across streams. MPEG-TS commonly reports ~1.4s; mp4 usually 0. Every timestamp the
   demuxer hands out is absolute (includes this), while KiteFFmpeg's public API, meaning seeks, trim
   bounds and extractFrame, is media-RELATIVE, so this is the offset between the two. Returns 0
   when the container doesn't declare one. */
KC_API int64_t       ffkmp_fmt_start_time(AVFormatContext *c) {
    /* Only AV_NOPTS_VALUE means "not declared". A NEGATIVE start is a real position: edit lists
       and encoder priming legitimately put the first timestamp below zero, and flattening those
       to 0 shifted every relative timestamp of such a file. */
    if (!c || c->start_time == AV_NOPTS_VALUE) return 0;
    return c->start_time;
}
KC_API unsigned      ffkmp_fmt_nb_streams(AVFormatContext *c) { return c ? c->nb_streams : 0; }
KC_API AVStream*     ffkmp_fmt_stream(AVFormatContext *c, unsigned i) {
    return (c && i < c->nb_streams) ? c->streams[i] : NULL;
}
KC_API const char*   ffkmp_fmt_iformat_name(AVFormatContext *c) { return (c && c->iformat) ? c->iformat->name : NULL; }
KC_API AVDictionary* ffkmp_fmt_metadata(AVFormatContext *c)     { return c ? c->metadata : NULL; }

/* Output */
/* Allocates an output context with an explicit container short name ("mp4", "matroska");
   NULL/empty format falls back to extension inference from the path. */
KC_API int  ffkmp_fmt_alloc_output2(AVFormatContext **out, const char *path, const char *format) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if ((!format || !format[0]) && (!path || !path[0])) return AVERROR(EINVAL);
    AVFormatContext *c = NULL;
    int rc = avformat_alloc_output_context2(&c, NULL, (format && format[0]) ? format : NULL, path);
    if (rc < 0 || !c) return rc < 0 ? rc : AVERROR_UNKNOWN;
    *out = c; return 0;
}
KC_API int64_t ffkmp_fmt_bit_rate(const AVFormatContext *ctx) {
    return ctx ? ctx->bit_rate : 0;
}
/* Muxer private options (movflags, …): AV_OPT_SEARCH_CHILDREN reaches oformat priv_data. */
KC_API int  ffkmp_fmt_set_opt(AVFormatContext *c, const char *k, const char *v) {
    /* A NULL key used to reach av_opt_set's name comparison and crash,
     * reproduced as signal 11 through this exported entry point. Refused like a NULL context. */
    if (!c || !k) return AVERROR(EINVAL);
    return av_opt_set(c, k, v, AV_OPT_SEARCH_CHILDREN);
}
KC_API int ffkmp_fmt_free_output(AVFormatContext **ctx) {
    int rc = 0;
    if (ctx && *ctx) {
        /* The close result is the LAST thing that can fail about an output file, and it is where a
           full disk, a broken pipe or a failed final flush announces itself. Discarding it reported
           a truncated file as a written one. The context is still freed on every
           path: the caller gets the error, not a leak. */
        if (!((*ctx)->oformat && ((*ctx)->oformat->flags & AVFMT_NOFILE)) && (*ctx)->pb) {
            rc = avio_closep(&(*ctx)->pb);
        }
        avformat_free_context(*ctx);
        *ctx = NULL;
    }
    return rc;
}
KC_API AVStream* ffkmp_fmt_new_stream(AVFormatContext *ctx, const AVCodec *codec) {
    if (!KC_GATE_OPEN()) return NULL;
    return ctx ? avformat_new_stream(ctx, codec) : NULL;
}
KC_API int ffkmp_fmt_io_open(AVFormatContext *ctx, const char *path) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!ctx) return AVERROR(EINVAL);
    if (ctx->oformat && (ctx->oformat->flags & AVFMT_NOFILE)) return 0;
    return avio_open(&ctx->pb, path, AVIO_FLAG_WRITE);
}
/* Timestamps handed to the muxer are rebased against a base SHARED by every stream of the sink
   (MediaSink.claimBaseMicros), which keeps the relative A/V offset intact but lets a stream that
   starts earlier than the claiming one go negative. AAC priming samples are the common case, and
   their negative start is exactly what tells a decoder to drop them. So the policy is AUTO, each
   muxer's own: MP4 and Ogg keep the negative start (an edit list, a pre-skip), Matroska keeps it
   down to the codec delay it writes, and every other muxer shifts all streams by one common
   amount so nothing is negative and the offset survives. MAKE_ZERO used to shift the priming up
   to zero, so every AAC file played 1024 extra samples at its start. */
KC_API void ffkmp_fmt_avoid_negative_ts(AVFormatContext *ctx) {
    if (ctx) ctx->avoid_negative_ts = AVFMT_AVOID_NEG_TS_AUTO;
}
KC_API int ffkmp_fmt_write_header(AVFormatContext *ctx)         { return ctx ? avformat_write_header(ctx, NULL) : AVERROR(EINVAL); }
KC_API int ffkmp_fmt_write_frame(AVFormatContext *ctx, AVPacket *p) {
    return ctx ? av_interleaved_write_frame(ctx, p) : AVERROR(EINVAL);
}
KC_API int ffkmp_fmt_write_trailer(AVFormatContext *ctx)         { return ctx ? av_write_trailer(ctx) : AVERROR(EINVAL); }
KC_API int ffkmp_oformat_global_header(AVFormatContext *c) {
    return (c && c->oformat && (c->oformat->flags & AVFMT_GLOBALHEADER)) ? 1 : 0;
}
/* Container-level metadata (title, artist, …). Must run before avformat_write_header. */
KC_API int ffkmp_fmt_set_metadata(AVFormatContext *c, const char *key, const char *value) {
    if (!c || !key) return AVERROR(EINVAL);
    return av_dict_set(&c->metadata, key, value, 0);
}

/* Appends one chapter to an output context, bounds in microseconds on the output timeline and n
   tags beside them. The context owns the chapter from here and avformat_free_context frees it,
   metadata included; the allocation is the public form FFmpeg's own tools use, because the
   helper that does it inside libavformat is private. */
KC_API int ffkmp_fmt_add_chapter(AVFormatContext *ctx, int64_t id, int64_t start_us, int64_t end_us,
                                 const char *const *keys, const char *const *values, int n) {
    if (!ctx || end_us < start_us || n < 0 || (n > 0 && (!keys || !values))) return AVERROR(EINVAL);
    AVChapter *chapter = av_mallocz(sizeof(*chapter));
    if (!chapter) return AVERROR(ENOMEM);
    chapter->id = id;
    chapter->time_base = AV_TIME_BASE_Q;
    chapter->start = start_us;
    chapter->end = end_us;
    for (int i = 0; i < n; i++) {
        int rc = (keys[i] && values[i]) ? av_dict_set(&chapter->metadata, keys[i], values[i], 0) : AVERROR(EINVAL);
        if (rc < 0) {
            av_dict_free(&chapter->metadata);
            av_free(chapter);
            return rc;
        }
    }
    AVChapter **grown = av_realloc_array(ctx->chapters, ctx->nb_chapters + 1, sizeof(*grown));
    if (!grown) {
        av_dict_free(&chapter->metadata);
        av_free(chapter);
        return AVERROR(ENOMEM);
    }
    ctx->chapters = grown;
    ctx->chapters[ctx->nb_chapters++] = chapter;
    return 0;
}

/* ════════════ Custom AVIO ════════════ */

/* 64 KiB: avio's own default probe/read granularity; large enough that a network-backed
   read_fn is not called per demuxer nibble. */
#define KC_IO_BUFFER_SIZE (64 * 1024)

typedef struct kc_io_nested kc_io_nested;

/* The bridge the AVIOContext's opaque points at. The magic pins provenance so the paired
   close can refuse to free state it did not create. av_class is first, so that the probe can
   read mime_type from the bridge (see kc_io_context_class); it stays NULL without a MIME type. */
#define KC_IO_BRIDGE_MAGIC 0x4B43494Fu /* "KCIO" */
typedef struct kc_io_bridge {
    const AVClass *av_class;
    uint32_t      magic;
    void         *opaque;
    kc_io_read_fn read_fn;
    kc_io_seek_fn seek_fn;
    int64_t       size;
    /* The bridge's own interrupt cell, freed with the bridge. */
    int           interrupted;
    /* The cell every read and seek checks and the context's interrupt_callback polls: the
       caller's kc_interrupt when the open was given one, else &interrupted. Nested sources
       check it too. */
    int          *cell;
    /* The MIME type the probe reads, owned; NULL when the caller gave none. */
    char         *mime_type;
    /* The caller's nested opener, copied; open_fn is NULL when the open has none. */
    kc_io_opener  opener;
    /* FFmpeg's own io_open and io_close2, for the data: URLs the opener never sees. */
    int         (*default_io_open)(AVFormatContext *s, AVIOContext **pb, const char *url,
                                   int flags, AVDictionary **options);
    int         (*default_io_close2)(AVFormatContext *s, AVIOContext *pb);
    /* Every nested source still open, so the close can release what FFmpeg left behind. */
    kc_io_nested *nested;
} kc_io_bridge;

/* The probe asks the input's AVIOContext for "mime_type" through AV_OPT_SEARCH_CHILDREN, and only
   when that context has a class. A custom AVIOContext has none, so a caller's MIME type never
   reached the probe. With a MIME type, the context gets kc_io_context_class, whose one child is
   the bridge, whose class answers mime_type. */
static const AVOption kc_io_bridge_options[] = {
    { .name = "mime_type", .help = "the MIME type the caller's bytes arrived with",
      .offset = offsetof(kc_io_bridge, mime_type), .type = AV_OPT_TYPE_STRING,
      .default_val = { .str = NULL }, .flags = AV_OPT_FLAG_DECODING_PARAM | AV_OPT_FLAG_READONLY },
    { .name = NULL },
};

static const AVClass kc_io_bridge_class = {
    .class_name = "kc_io_bridge",
    .item_name  = av_default_item_name,
    .option     = kc_io_bridge_options,
    .version    = LIBAVUTIL_VERSION_INT,
};

static void *kc_io_context_child_next(void *obj, void *prev) {
    AVIOContext *pb = (AVIOContext *)obj;
    return prev ? NULL : pb->opaque;
}

static const AVClass kc_io_context_class = {
    .class_name = "AVIOContext",
    .item_name  = av_default_item_name,
    .version    = LIBAVUTIL_VERSION_INT,
    .child_next = kc_io_context_child_next,
};

/* One caller read on FFmpeg's read_packet contract: >0 bytes, AVERROR_EOF at end, never 0
   since n7. The caller contract (KC_IO_EOF / KC_IO_ERR) maps here so the Kotlin side never
   needs an FFmpeg constant. */
static int kc_io_read_through(kc_io_read_fn read_fn, void *source, const int *cell,
                              uint8_t *buf, int len) {
    /* FFmpeg's own poll sites never see a custom AVIO, so an interrupted long scan
       is broken here, between caller reads, which is exactly where a network stall spins. */
    if (kc_cell_raised(cell)) return AVERROR_EXIT;
    int r = read_fn(source, buf, len);
    /* FFmpeg's buffer holds len bytes, so a larger count is refused. */
    if (r > len) return AVERROR(EIO);
    if (r > 0) return r;
    if (r == KC_IO_EOF) return AVERROR_EOF;
    return AVERROR(EIO);
}

static int64_t kc_io_seek_through(kc_io_seek_fn seek_fn, void *source, const int *cell,
                                  int64_t size, int64_t offset, int whence) {
    if (kc_cell_raised(cell)) return AVERROR_EXIT;
    if (whence & AVSEEK_SIZE) return size >= 0 ? size : AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    if (!seek_fn) return AVERROR(ENOSYS);
    int64_t r = seek_fn(source, offset, whence);
    return r < 0 ? AVERROR(EIO) : r;
}

static int kc_io_read_packet(void *opaque, uint8_t *buf, int len) {
    kc_io_bridge *b = (kc_io_bridge *)opaque;
    return kc_io_read_through(b->read_fn, b->opaque, b->cell, buf, len);
}

static int64_t kc_io_seek(void *opaque, int64_t offset, int whence) {
    kc_io_bridge *b = (kc_io_bridge *)opaque;
    return kc_io_seek_through(b->seek_fn, b->opaque, b->cell, b->size, offset, whence);
}

/* ── Nested sources: the other URLs a demuxer opens, served by the caller's opener ── */

/* One source the nested opener produced. Its AVIOContext reads through the opener's read_fn and
   seek_fn with source as their opaque. An AES-128 source decrypts on the way, as RFC 8216,
   section 5.2 describes it: CBC with the playlist's key and IV, and PKCS#7 padding at the end. */
#define KC_IO_NESTED_MAGIC 0x4B434E53u /* "KCNS" */
#define KC_AES_BLOCK 16
/* The encrypted bytes one step reads and decrypts; a whole number of blocks. */
#define KC_CRYPT_BUFFER (64 * 1024)
struct kc_io_nested {
    uint32_t      magic;
    kc_io_bridge *parent;
    void         *source;       /* the caller's, released through parent->opener.close_fn */
    int64_t       size;
    AVIOContext  *pb;           /* the context FFmpeg reads */
    kc_io_nested *next;         /* the parent's list of open sources */
    struct AVAES *aes;          /* NULL for a plain source */
    uint8_t       iv[KC_AES_BLOCK];
    uint8_t      *cipher;       /* encrypted input not decrypted yet */
    int           cipher_len;
    uint8_t      *plain;        /* decrypted bytes not handed to FFmpeg yet */
    int           plain_pos;
    int           plain_len;
    int           ended;        /* the source has no more bytes */
};

static int kc_io_nested_read(void *opaque, uint8_t *buf, int len) {
    kc_io_nested *n = (kc_io_nested *)opaque;
    return kc_io_read_through(n->parent->opener.read_fn, n->source, n->parent->cell, buf, len);
}

static int64_t kc_io_nested_seek(void *opaque, int64_t offset, int whence) {
    kc_io_nested *n = (kc_io_nested *)opaque;
    return kc_io_seek_through(n->parent->opener.seek_fn, n->source, n->parent->cell, n->size,
                              offset, whence);
}

/* Hands FFmpeg the next decrypted bytes. The last whole block stays back until the source ends,
   because it may carry the padding, which must not reach FFmpeg. */
static int kc_io_crypt_read(void *opaque, uint8_t *buf, int len) {
    kc_io_nested *n = (kc_io_nested *)opaque;
    kc_io_bridge *p = n->parent;
    while (n->plain_pos == n->plain_len) {
        if (kc_cell_raised(p->cell)) return AVERROR_EXIT;
        while (!n->ended && n->cipher_len < 2 * KC_AES_BLOCK) {
            int room = KC_CRYPT_BUFFER - n->cipher_len;
            int r = p->opener.read_fn(n->source, n->cipher + n->cipher_len, room);
            if (r > room) return AVERROR(EIO);
            if (r > 0) n->cipher_len += r;
            else if (r == KC_IO_EOF) n->ended = 1;
            else return AVERROR(EIO);
        }
        /* Ciphertext always comes in whole blocks. */
        if (n->ended && n->cipher_len % KC_AES_BLOCK) return AVERROR_INVALIDDATA;
        int blocks = n->cipher_len / KC_AES_BLOCK - (n->ended ? 0 : 1);
        if (blocks <= 0) return AVERROR_EOF;
        av_aes_crypt(n->aes, n->plain, n->cipher, blocks, n->iv, 1);
        int used = blocks * KC_AES_BLOCK;
        memmove(n->cipher, n->cipher + used, (size_t)(n->cipher_len - used));
        n->cipher_len -= used;
        n->plain_pos = 0;
        n->plain_len = used;
        if (n->ended && n->cipher_len == 0) {
            /* The final block: its last byte says how many padding bytes to drop. */
            int pad = n->plain[used - 1];
            if (pad >= 1 && pad <= KC_AES_BLOCK) n->plain_len -= pad;
        }
    }
    int take = FFMIN(len, n->plain_len - n->plain_pos);
    memcpy(buf, n->plain + n->plain_pos, (size_t)take);
    n->plain_pos += take;
    return take;
}

/* Releases one nested source: the caller's state through close_fn, then this layer's. */
static void kc_io_nested_free(kc_io_nested *n) {
    kc_io_bridge *p = n->parent;
    if (n->source) p->opener.close_fn(p->opener.opaque, n->source);
    if (n->pb) {
        av_freep(&n->pb->buffer);
        avio_context_free(&n->pb);
    }
    av_free(n->aes);
    av_free(n->cipher);
    av_free(n->plain);
    av_free(n);
}

static void kc_io_nested_unlink(kc_io_bridge *p, kc_io_nested *n) {
    for (kc_io_nested **at = &p->nested; *at; at = &(*at)->next) {
        if (*at == n) {
            *at = n->next;
            return;
        }
    }
}

static int kc_hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* The 16 bytes behind the 32 hex digits that hls.c passes as the "key" or "iv" option. */
static int kc_hex_block(const AVDictionary *options, const char *name, uint8_t out[KC_AES_BLOCK]) {
    const AVDictionaryEntry *e = av_dict_get(options, name, NULL, 0);
    if (!e || strlen(e->value) != 2 * KC_AES_BLOCK) return AVERROR(EINVAL);
    for (int i = 0; i < KC_AES_BLOCK; i++) {
        int hi = kc_hex_digit(e->value[2 * i]);
        int lo = kc_hex_digit(e->value[2 * i + 1]);
        if (hi < 0 || lo < 0) return AVERROR(EINVAL);
        out[i] = (uint8_t)(hi << 4 | lo);
    }
    return 0;
}

/* The context's io_open while it has an opener. With the trust_io_open patch, the HLS demuxer
   sends every playlist, segment and key URL here without checking its protocol first. */
static int kc_io_open_nested(AVFormatContext *s, AVIOContext **pb, const char *url, int flags,
                             AVDictionary **options) {
    kc_io_bridge *p = s ? (kc_io_bridge *)s->opaque : NULL;
    if (!pb || !url || !p || p->magic != KC_IO_BRIDGE_MAGIC || !p->opener.open_fn)
        return AVERROR(EINVAL);
    *pb = NULL;
    if (kc_cell_raised(p->cell)) return AVERROR_EXIT;
    /* A data: URL carries its bytes inline, and FFmpeg's data protocol reads them. */
    if (av_strstart(url, "data:", NULL)) return p->default_io_open(s, pb, url, flags, options);
    if (flags & AVIO_FLAG_WRITE) return AVERROR(EACCES);

    /* An AES-128 segment arrives as crypto+URL, or crypto:URL, with its key and IV as options.
       The opener gets the plain URL, and this layer decrypts. */
    const char *inner = url;
    int encrypted = av_strstart(url, "crypto+", &inner) || av_strstart(url, "crypto:", &inner);
    uint8_t key[KC_AES_BLOCK];
    uint8_t iv[KC_AES_BLOCK];
    if (encrypted) {
        const AVDictionary *given = options ? *options : NULL;
        if (kc_hex_block(given, "key", key) < 0 || kc_hex_block(given, "iv", iv) < 0)
            return AVERROR(EINVAL);
    }

    void *source = NULL;
    int64_t size = -1;
    int seekable = 0;
    int rc = p->opener.open_fn(p->opener.opaque, inner, &source, &size, &seekable);
    if (rc == KC_IO_REFUSED) return AVERROR(EACCES);
    if (rc < 0 || !source) return AVERROR(EIO);

    kc_io_nested *n = av_mallocz(sizeof(*n));
    if (!n) {
        p->opener.close_fn(p->opener.opaque, source);
        return AVERROR(ENOMEM);
    }
    n->magic = KC_IO_NESTED_MAGIC;
    n->parent = p;
    n->source = source;
    n->size = size;
    int can_seek = !encrypted && seekable && p->opener.seek_fn;
    unsigned char *buffer = av_malloc(KC_IO_BUFFER_SIZE);
    if (encrypted) {
        n->aes = av_aes_alloc();
        n->cipher = av_malloc(KC_CRYPT_BUFFER);
        n->plain = av_malloc(KC_CRYPT_BUFFER);
    }
    if (!buffer || (encrypted && (!n->aes || !n->cipher || !n->plain ||
                                  av_aes_init(n->aes, key, 128, 1) < 0))) {
        av_free(buffer);
        kc_io_nested_free(n);
        return AVERROR(ENOMEM);
    }
    if (encrypted) memcpy(n->iv, iv, KC_AES_BLOCK);
    n->pb = avio_alloc_context(buffer, KC_IO_BUFFER_SIZE, 0, n,
                               encrypted ? kc_io_crypt_read : kc_io_nested_read, NULL,
                               can_seek ? kc_io_nested_seek : NULL);
    if (!n->pb) {
        av_free(buffer);
        kc_io_nested_free(n);
        return AVERROR(ENOMEM);
    }
    n->pb->seekable = can_seek ? AVIO_SEEKABLE_NORMAL : 0;
    n->next = p->nested;
    p->nested = n;
    *pb = n->pb;
    return 0;
}

/* The context's io_close2 while it has an opener: a nested source goes back to the caller, and
   anything else is a data: URL that FFmpeg's own io_open opened. */
static int kc_io_close_nested(AVFormatContext *s, AVIOContext *pb) {
    kc_io_bridge *p = s ? (kc_io_bridge *)s->opaque : NULL;
    if (!pb) return 0;
    if (pb->read_packet != kc_io_nested_read && pb->read_packet != kc_io_crypt_read)
        return (p && p->default_io_close2) ? p->default_io_close2(s, pb) : 0;
    kc_io_nested *n = (kc_io_nested *)pb->opaque;
    if (!p || !n || n->magic != KC_IO_NESTED_MAGIC || n->parent != p) return AVERROR(EINVAL);
    kc_io_nested_unlink(p, n);
    kc_io_nested_free(n);
    return 0;
}

/* Frees the bridge and every nested source FFmpeg left open, once the demuxer is gone. */
static void kc_io_bridge_free(kc_io_bridge *bridge) {
    while (bridge->nested) {
        kc_io_nested *n = bridge->nested;
        bridge->nested = n->next;
        kc_io_nested_free(n);
    }
    av_freep(&bridge->mime_type);
    av_free(bridge);
}

/* Frees a custom input's AVIOContext, its buffer and its bridge, once the demuxer is gone. */
static void kc_io_input_free(AVIOContext *pb) {
    kc_io_bridge *bridge = (kc_io_bridge *)pb->opaque;
    if (bridge && bridge->magic == KC_IO_BRIDGE_MAGIC) kc_io_bridge_free(bridge);
    av_freep(&pb->buffer);
    avio_context_free(&pb);
}

KC_API int ffkmp_fmt_nested_io_available(void) {
    if (!KC_GATE_OPEN()) return 0;
    const AVInputFormat *hls = av_find_input_format("hls");
    if (!hls) return 1;
    const AVClass *cls = hls->priv_class;
    return cls && av_opt_find(&cls, KC_TRUST_IO_OPEN_KEY, NULL, 0, AV_OPT_SEARCH_FAKE_OBJ) ? 1 : 0;
}

KC_API int ffkmp_fmt_open_input_io2(AVFormatContext **out,
                                    void *opaque, kc_io_read_fn read_fn, kc_io_seek_fn seek_fn,
                                    int64_t size, const char *url, const char *mime_type,
                                    const kc_io_opener *opener,
                                    const char *const *keys, const char *const *values,
                                    int n, AVDictionary **unused, kc_interrupt *interrupt) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (unused) *unused = NULL;
    if (!read_fn) return AVERROR(EINVAL);
    if (n < 0) return AVERROR(EINVAL);
    if (n > 0 && (!keys || !values)) return AVERROR(EINVAL);
    if (opener && (!opener->open_fn || !opener->read_fn || !opener->close_fn))
        return AVERROR(EINVAL);
    /* A tree built without the trust_io_open patch would refuse every https URL quietly. */
    if (opener && !ffkmp_fmt_nested_io_available()) return AVERROR(ENOSYS);
    if (interrupt && kc_cell_raised(&interrupt->raised)) return AVERROR_EXIT;

    kc_io_bridge *bridge = av_mallocz(sizeof(kc_io_bridge));
    if (!bridge) return AVERROR(ENOMEM);
    bridge->magic = KC_IO_BRIDGE_MAGIC;
    bridge->opaque = opaque;
    bridge->read_fn = read_fn;
    bridge->seek_fn = seek_fn;
    bridge->size = size;
    bridge->cell = interrupt ? &interrupt->raised : &bridge->interrupted;
    if (opener) bridge->opener = *opener;
    if (mime_type) {
        bridge->mime_type = av_strdup(mime_type);
        if (!bridge->mime_type) { kc_io_bridge_free(bridge); return AVERROR(ENOMEM); }
        bridge->av_class = &kc_io_bridge_class;
    }

    unsigned char *buffer = av_malloc(KC_IO_BUFFER_SIZE);
    if (!buffer) { kc_io_bridge_free(bridge); return AVERROR(ENOMEM); }

    AVIOContext *pb = avio_alloc_context(buffer, KC_IO_BUFFER_SIZE, 0, bridge,
                                         kc_io_read_packet, NULL,
                                         seek_fn ? kc_io_seek : NULL);
    if (!pb) { av_freep(&buffer); kc_io_bridge_free(bridge); return AVERROR(ENOMEM); }
    /* Seekability truth for demuxers that ask the pb instead of probing a seek. */
    pb->seekable = seek_fn ? AVIO_SEEKABLE_NORMAL : 0;
    if (mime_type) pb->av_class = &kc_io_context_class;

    AVFormatContext *c = avformat_alloc_context();
    if (!c) { kc_io_input_free(pb); return AVERROR(ENOMEM); }
    c->pb = pb;
    c->flags |= AVFMT_FLAG_CUSTOM_IO;
    /* The bridge's own cell is interior to the bridge, freed with it by the IO close; the
       path close never frees a custom-io context's cell, whichever poll it carries. */
    c->interrupt_callback.callback = interrupt ? kc_interrupt_check_borrowed : kc_interrupt_check;
    c->interrupt_callback.opaque = (void *)bridge->cell;
    if (opener) {
        bridge->default_io_open = c->io_open;
        bridge->default_io_close2 = c->io_close2;
        c->opaque = bridge;
        c->io_open = kc_io_open_nested;
        c->io_close2 = kc_io_close_nested;
    }

    AVDictionary *options = NULL;
    const AVInputFormat *forced = NULL;
    int built = kc_open_options(keys, values, n, &options, &forced);
    if (built >= 0 && opener) built = av_dict_set(&options, KC_TRUST_IO_OPEN_KEY, "1", 0);
    if (built < 0) {
        av_dict_free(&options);
        avformat_free_context(c);
        kc_io_input_free(pb);
        return built;
    }

    /* On failure avformat_open_input frees the context but, per AVFMT_FLAG_CUSTOM_IO, never the
       caller's pb; the bridge, the AVIO state and any nested source are this function's to unwind. */
    int rc = avformat_open_input(&c, url, forced, &options);
    if (rc < 0) {
        av_dict_free(&options);
        kc_io_input_free(pb);
        return rc;
    }
    /* The key this layer added is not the caller's, so it never shows as unused. */
    if (opener) av_dict_set(&options, KC_TRUST_IO_OPEN_KEY, NULL, 0);
    if (unused) *unused = options;
    else av_dict_free(&options);
    *out = c;
    return 0;
}

KC_API int ffkmp_fmt_open_input_io(AVFormatContext **out,
                                   void *opaque, kc_io_read_fn read_fn, kc_io_seek_fn seek_fn,
                                   int64_t size,
                                   const char *const *keys, const char *const *values,
                                   int n, AVDictionary **unused, kc_interrupt *interrupt) {
    return ffkmp_fmt_open_input_io2(out, opaque, read_fn, seek_fn, size, NULL, NULL, NULL,
                                    keys, values, n, unused, interrupt);
}

KC_API void ffkmp_fmt_close_input_io(AVFormatContext **ctx) {
    if (!ctx || !*ctx) return;
    AVFormatContext *c = *ctx;
    AVIOContext *pb = (c->flags & AVFMT_FLAG_CUSTOM_IO) ? c->pb : NULL;
    kc_held_drop(c);
    avformat_close_input(&c);
    *ctx = NULL;
    if (pb) kc_io_input_free(pb);
}

KC_API void *ffkmp_fmt_io_opaque(AVFormatContext *ctx) {
    if (!ctx || !(ctx->flags & AVFMT_FLAG_CUSTOM_IO) || !ctx->pb) return NULL;
    kc_io_bridge *bridge = (kc_io_bridge *)ctx->pb->opaque;
    if (!bridge || bridge->magic != KC_IO_BRIDGE_MAGIC) return NULL;
    return bridge->opaque;
}

/* ── The custom output bridge: the bytes a muxer writes go to the caller instead of a path. ── */

#define KC_IO_WRITER_MAGIC 0x4B43494Eu /* "KCIN", distinct from the input bridge magic */
typedef struct kc_io_writer {
    uint32_t       magic;
    void          *opaque;
    kc_io_write_fn write_fn;
    kc_io_seek_fn  seek_fn;
} kc_io_writer;

/* FFmpeg 7 made the write callback's buffer const; FFmpeg 6.1 still passes it mutable. */
#if LIBAVFORMAT_VERSION_MAJOR >= 61
#define KC_AVIO_WRITE_BUFFER const uint8_t *
#else
#define KC_AVIO_WRITE_BUFFER uint8_t *
#endif

static int kc_io_write_packet(void *opaque, KC_AVIO_WRITE_BUFFER buf, int len) {
    kc_io_writer *w = (kc_io_writer *)opaque;
    return w->write_fn(w->opaque, buf, len) < 0 ? AVERROR(EIO) : len;
}

/* avio turns a relative seek into an absolute one before it calls this, so SEEK_SET is the only
   request a caller's seek_fn has to answer. The size probe has no answer: the sink is still being
   written. */
static int64_t kc_io_write_seek(void *opaque, int64_t offset, int whence) {
    kc_io_writer *w = (kc_io_writer *)opaque;
    whence &= ~AVSEEK_FORCE;
    if (whence != SEEK_SET || !w->seek_fn) return AVERROR(ENOSYS);
    int64_t r = w->seek_fn(w->opaque, offset, SEEK_SET);
    return r < 0 ? AVERROR(EIO) : r;
}

KC_API int ffkmp_fmt_alloc_output_io(AVFormatContext **out, const char *format,
                                     void *opaque, kc_io_write_fn write_fn, kc_io_seek_fn seek_fn) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (!out) return AVERROR(EINVAL);
    *out = NULL;
    if (!format || !format[0] || !write_fn) return AVERROR(EINVAL);

    AVFormatContext *c = NULL;
    int rc = avformat_alloc_output_context2(&c, NULL, format, NULL);
    if (rc < 0 || !c) return rc < 0 ? rc : AVERROR_UNKNOWN;
    kc_io_writer *writer = av_mallocz(sizeof(kc_io_writer));
    unsigned char *buffer = av_malloc(KC_IO_BUFFER_SIZE);
    AVIOContext *pb = (writer && buffer)
        ? avio_alloc_context(buffer, KC_IO_BUFFER_SIZE, 1, writer, NULL, kc_io_write_packet,
                             seek_fn ? kc_io_write_seek : NULL)
        : NULL;
    if (!pb) {
        av_free(buffer);
        av_free(writer);
        avformat_free_context(c);
        return AVERROR(ENOMEM);
    }
    writer->magic = KC_IO_WRITER_MAGIC;
    writer->opaque = opaque;
    writer->write_fn = write_fn;
    writer->seek_fn = seek_fn;
    /* What a muxer that has to go back reads to decide whether it can. */
    pb->seekable = seek_fn ? AVIO_SEEKABLE_NORMAL : 0;
    c->pb = pb;
    c->flags |= AVFMT_FLAG_CUSTOM_IO;
    *out = c;
    return 0;
}

KC_API int ffkmp_fmt_free_output_io(AVFormatContext **ctx) {
    if (!ctx || !*ctx) return 0;
    AVFormatContext *c = *ctx;
    AVIOContext *pb = (c->flags & AVFMT_FLAG_CUSTOM_IO) ? c->pb : NULL;
    int rc = 0;
    if (pb) {
        /* The last bytes leave here, and a write that fails now is the last chance to say so. */
        avio_flush(pb);
        rc = pb->error;
        kc_io_writer *writer = (kc_io_writer *)pb->opaque;
        if (writer && writer->magic == KC_IO_WRITER_MAGIC) av_freep(&pb->opaque);
        av_freep(&pb->buffer);
        avio_context_free(&pb);
        c->pb = NULL;
    }
    avformat_free_context(c);
    *ctx = NULL;
    return rc;
}

KC_API void *ffkmp_fmt_output_io_opaque(AVFormatContext *ctx) {
    if (!ctx || !(ctx->flags & AVFMT_FLAG_CUSTOM_IO) || !ctx->pb) return NULL;
    kc_io_writer *writer = (kc_io_writer *)ctx->pb->opaque;
    if (!writer || writer->magic != KC_IO_WRITER_MAGIC) return NULL;
    return writer->opaque;
}
