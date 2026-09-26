/* The log part of the FFmpeg helper layer: where FFmpeg's own log lines go.
 *
 * FFmpeg prints through one process-wide callback, and its default one prints every line. This unit
 * replaces it with a forwarder that hands each line to the caller's sink, or drops it when there is
 * none. The sink and its level are two atomics, so a line logged on one of FFmpeg's worker threads
 * while another thread changes them sees either the old pair or the new one, never a torn pointer. */

#include "kitecodec_helpers.h"

#include <libavutil/log.h>
#include <stdatomic.h>

/* ════════════ Logging ════════════ */

static _Atomic(ffkmp_log_sink) kc_log_target = NULL;
static atomic_int kc_log_threshold = AV_LOG_QUIET;

/* FFmpeg's callback. av_log_format_line2 with print_prefix at 0 formats the message alone, without
 * the "[h264 @ 0x...]" prefix, because the component travels as its own argument. */
static void kc_log_forward(void *avcl, int level, const char *fmt, va_list vl)
{
    char message[1024];
    int print_prefix = 0;
    const char *component = NULL;
    ffkmp_log_sink sink;
    size_t length;

    /* The high byte carries a colour hint, not a level. */
    level &= 0xff;
    if (level > atomic_load(&kc_log_threshold)) return;
    sink = atomic_load(&kc_log_target);
    if (sink == NULL) return;
    if (av_log_format_line2(avcl, level, fmt, vl, message, (int)sizeof message, &print_prefix) < 0) return;
    if (avcl != NULL) {
        const AVClass *avc = *(const AVClass **)avcl;
        if (avc != NULL) component = avc->item_name != NULL ? avc->item_name(avcl) : avc->class_name;
    }
    length = strlen(message);
    while (length > 0 && (message[length - 1] == '\n' || message[length - 1] == '\r')) message[--length] = '\0';
    if (length == 0) return;
    sink(level, component != NULL ? component : "", message);
}

KC_API void ffkmp_log_set_sink(ffkmp_log_sink sink, int level)
{
    atomic_store(&kc_log_threshold, sink != NULL ? level : AV_LOG_QUIET);
    atomic_store(&kc_log_target, sink);
    av_log_set_callback(kc_log_forward);
}
