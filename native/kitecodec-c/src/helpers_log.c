/* The log part of the FFmpeg helper layer: where FFmpeg's own log lines go.
 *
 * FFmpeg prints through one process-wide callback, and its default one prints every line. This unit
 * replaces it with a forwarder that hands each line to the caller's sink, or drops it when there is
 * none. The sink and its level are two atomics, so a line logged on one of FFmpeg's worker threads
 * while another thread changes them sees either the old pair or the new one, never a torn pointer.
 *
 * The forwarder also fills the calling thread's open captures (kc_log_capture), which are the
 * thread's own and need no lock: only the thread that began a capture ever reaches it. */

#include "kitecodec_helpers.h"

#include <libavutil/error.h>
#include <libavutil/log.h>
#include <libavutil/mem.h>
#include <stdatomic.h>
#include <string.h>

/* ════════════ Logging ════════════ */

static _Atomic(ffkmp_log_sink) kc_log_target = NULL;
static atomic_int kc_log_threshold = AV_LOG_QUIET;

/* One capture: the lines it kept, oldest first, each component and message one allocation. */
struct kc_log_capture {
    kc_log_capture *outer;
    int count;
    int levels[KC_LOG_CAPTURE_LINES];
    char *components[KC_LOG_CAPTURE_LINES];
    char *messages[KC_LOG_CAPTURE_LINES];
};

/* The innermost capture open on this thread, each one linking to the capture it is nested in. */
static __thread kc_log_capture *kc_log_captures = NULL;

/* Keeps one line in c, unless c is full. A line that cannot be copied is dropped rather than kept
 * in part, and nothing here logs, so FFmpeg's logger is never entered again from inside it. */
static void kc_log_capture_keep(kc_log_capture *c, int level, const char *component, const char *message)
{
    int i = c->count;
    if (i >= KC_LOG_CAPTURE_LINES) return;
    c->components[i] = av_strdup(component);
    c->messages[i] = av_strdup(message);
    if (c->components[i] == NULL || c->messages[i] == NULL) {
        av_freep(&c->components[i]);
        av_freep(&c->messages[i]);
        return;
    }
    c->levels[i] = level;
    c->count = i + 1;
}

/* FFmpeg's callback. av_log_format_line2 with print_prefix at 0 formats the message alone, without
 * the "[h264 @ 0x...]" prefix, because the component travels as its own argument. */
static void kc_log_forward(void *avcl, int level, const char *fmt, va_list vl)
{
    char message[1024];
    int print_prefix = 0;
    const char *component = NULL;
    ffkmp_log_sink sink;
    kc_log_capture *capture;
    size_t length;

    /* The high byte carries a colour hint, not a level. */
    level &= 0xff;
    /* An error line is formatted for this thread's captures even when no sink takes it. */
    capture = level <= AV_LOG_ERROR ? kc_log_captures : NULL;
    sink = level <= atomic_load(&kc_log_threshold) ? atomic_load(&kc_log_target) : NULL;
    if (sink == NULL && capture == NULL) return;
    if (av_log_format_line2(avcl, level, fmt, vl, message, (int)sizeof message, &print_prefix) < 0) return;
    if (avcl != NULL) {
        const AVClass *avc = *(const AVClass **)avcl;
        if (avc != NULL) component = avc->item_name != NULL ? avc->item_name(avcl) : avc->class_name;
    }
    length = strlen(message);
    while (length > 0 && (message[length - 1] == '\n' || message[length - 1] == '\r')) message[--length] = '\0';
    if (length == 0) return;
    if (component == NULL) component = "";
    for (; capture != NULL; capture = capture->outer) kc_log_capture_keep(capture, level, component, message);
    if (sink != NULL) sink(level, component, message);
}

KC_API void ffkmp_log_set_sink(ffkmp_log_sink sink, int level)
{
    atomic_store(&kc_log_threshold, sink != NULL ? level : AV_LOG_QUIET);
    atomic_store(&kc_log_target, sink);
    av_log_set_callback(kc_log_forward);
}

KC_API int ffkmp_log_capture_begin(void)
{
    kc_log_capture *c = av_mallocz(sizeof *c);
    if (c == NULL) return AVERROR(ENOMEM);
    c->outer = kc_log_captures;
    kc_log_captures = c;
    return 0;
}

KC_API kc_log_capture *ffkmp_log_capture_end(void)
{
    kc_log_capture *c = kc_log_captures;
    if (c == NULL) return NULL;
    kc_log_captures = c->outer;
    c->outer = NULL;
    return c;
}

KC_API int ffkmp_log_capture_count(const kc_log_capture *c)
{
    return c != NULL ? c->count : 0;
}

KC_API int ffkmp_log_capture_level(const kc_log_capture *c, int i)
{
    return c != NULL && i >= 0 && i < c->count ? c->levels[i] : -1;
}

KC_API const char *ffkmp_log_capture_component(const kc_log_capture *c, int i)
{
    return c != NULL && i >= 0 && i < c->count ? c->components[i] : NULL;
}

KC_API const char *ffkmp_log_capture_message(const kc_log_capture *c, int i)
{
    return c != NULL && i >= 0 && i < c->count ? c->messages[i] : NULL;
}

KC_API void ffkmp_log_capture_free(kc_log_capture **c)
{
    int i;
    if (c == NULL || *c == NULL) return;
    for (i = 0; i < (*c)->count; i++) {
        av_freep(&(*c)->components[i]);
        av_freep(&(*c)->messages[i]);
    }
    av_freep(c);
}
