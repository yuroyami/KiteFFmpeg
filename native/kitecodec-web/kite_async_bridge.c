/*
 * The asynchronous input bridge of the web codec module (#183). It is linked only into the two
 * asynchronous artifacts, kite-jspi and kite-asyncify. The plain kite module does not hold it.
 *
 * A byte source on the web often cannot answer at once: a Fetch response arrives later, and the
 * page has one thread. The imports below are therefore suspending imports. While JavaScript works
 * on one, the C stack that called it is parked, with JSPI by the engine and with Asyncify by the
 * code the linker adds, and it goes on from the same place when the answer is there.
 *
 * Two rules keep one bridge valid for both mechanisms. An export that can park takes no 64-bit
 * parameter, because Asyncify calls it again with no arguments to return to the parked place. A
 * suspending import returns no 64-bit value, because Asyncify returns a plain number while it
 * parks. Every 64-bit value crosses through memory.
 *
 * A module serves one caller at a time, so a source is named by a number that its owner in
 * JavaScript gave it, and nothing here names the owner.
 */
#include <stddef.h>
#include <stdint.h>

#include <libavutil/error.h>

#include "kitecodec_helpers.h"

/* Suspending imports. read answers as a kc_io_read_fn does. seek writes its answer to *result and
 * returns 0, or a negative value to fail. open answers as kc_io_opener.open_fn does. */
extern int  kite_async_read(int source, unsigned char *buf, int len);
extern int  kite_async_seek(int source, const int64_t *offset, int whence, int64_t *result);
extern int  kite_async_open(const char *url, int *source, int64_t *size, int *seekable);
extern void kite_async_close(int source);
extern void kite_async_sleep(unsigned usec);

/* Immediate imports. They only hand over what the suspending import before them already holds. */
extern int  kite_async_tags(int source, kc_io_tags *tags);
extern int  kite_async_location(int source, char *buf, int cap);

#define KITE_ASYNC_BRIDGE_VERSION 1

/* The flags of ffkmp_async_open_input. */
#define KITE_ASYNC_SEEKABLE 1
#define KITE_ASYNC_TAGS     2
#define KITE_ASYNC_NESTED   4

static int id_of(void *opaque) { return (int)(intptr_t)opaque; }

static int bridge_read(void *opaque, unsigned char *buf, int len) {
    return kite_async_read(id_of(opaque), buf, len);
}

static int64_t bridge_seek(void *opaque, int64_t offset, int whence) {
    int64_t result = -1;
    int status = kite_async_seek(id_of(opaque), &offset, whence, &result);
    return status < 0 ? status : result;
}

static int bridge_tags(void *opaque, kc_io_tags *tags) {
    return kite_async_tags(id_of(opaque), tags);
}

static int bridge_open(void *opaque, const char *url, void **source, int64_t *size, int *seekable) {
    int id = 0;
    int status;
    (void)opaque;
    status = kite_async_open(url, &id, size, seekable);
    if (status == 0) *source = (void *)(intptr_t)id;
    return status;
}

static void bridge_close(void *opaque, void *source) {
    (void)opaque;
    kite_async_close(id_of(source));
}

static int bridge_location(void *opaque, void *source, char *buf, int cap) {
    (void)opaque;
    return kite_async_location(id_of(source), buf, cap);
}

/* The version of this bridge, which the loader checks before it uses the module. */
KC_API int ffkmp_async_bridge_version(void) {
    return KITE_ASYNC_BRIDGE_VERSION;
}

/* ffkmp_fmt_open_input_io2 over the imports above. root names the input's bytes and *size is their
 * length, negative when unknown. flags says whether the input can seek, whether it brings tags,
 * and whether nested URLs are opened through the imports. The context closes with
 * ffkmp_fmt_close_input_io, which can park too. */
KC_API int ffkmp_async_open_input(kc_fmt_ctx **out, int root, const int64_t *size,
                                  const char *url, const char *location, const char *mime_type,
                                  int flags,
                                  const char *const *keys, const char *const *values, int n,
                                  kc_dict **unused, kc_interrupt *interrupt) {
    kc_io_opener opener = { NULL, bridge_open, bridge_read, bridge_seek, bridge_close, bridge_location };
    if (!size) return AVERROR(EINVAL);
    return ffkmp_fmt_open_input_io2(out, (void *)(intptr_t)root, bridge_read,
                                    (flags & KITE_ASYNC_SEEKABLE) ? bridge_seek : NULL,
                                    (flags & KITE_ASYNC_TAGS) ? bridge_tags : NULL,
                                    *size, url, location, mime_type,
                                    (flags & KITE_ASYNC_NESTED) ? &opener : NULL,
                                    keys, values, n, unused, interrupt);
}

/* ffkmp_fmt_seek_file with its three times read from range: the least, the target and the most. */
KC_API int ffkmp_async_seek_file(kc_fmt_ctx *ctx, int stream_index, const int64_t *range, int flags) {
    if (!range) return AVERROR(EINVAL);
    return ffkmp_fmt_seek_file(ctx, stream_index, range[0], range[1], range[2], flags);
}

/* ffkmp_fmt_seek_micros with its time read from micros. */
KC_API int ffkmp_async_seek_micros(kc_fmt_ctx *ctx, int stream_index, const int64_t *micros) {
    if (!micros) return AVERROR(EINVAL);
    return ffkmp_fmt_seek_micros(ctx, stream_index, *micros);
}

/* The linker sends every av_usleep call here (--wrap=av_usleep). The one wait inside FFmpeg that
 * a web build reaches is the live HLS reader's, between two loads of a playlist that has not
 * grown. A single-threaded sleep would hold the page's only thread for that long. */
int __wrap_av_usleep(unsigned usec) {
    kite_async_sleep(usec);
    return 0;
}
