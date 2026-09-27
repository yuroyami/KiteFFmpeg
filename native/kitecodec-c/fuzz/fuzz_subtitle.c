/* Fuzz target: the subtitle decode helpers, fed every packet that a demuxer reads from the input.
 *
 * The input is the same as fuzz_demux's: the whole of it is the media, opened through the custom
 * read callback, seekable with the size known. For every subtitle stream, up to eight, the target
 * opens a decoder with ffkmp_subtitle_decoder_open, which is how the native backend opens one. It
 * then reads packets to the end, decodes each subtitle packet with ffkmp_subtitle_decode, and at
 * the end sends every decoder an empty packet, which flushes a decoder that holds a subtitle back.
 *
 * Every decoded subtitle goes through what a caller uses to read it: its times, the geometry of
 * every rectangle, the RGBA conversion of each image rectangle, and the text of each text or ASS
 * rectangle. The conversion walks an index plane and a palette that the decoder built from the
 * input, so this library's own code works on geometry from the input, and the sanitizer checks
 * every byte it reads and writes. The destination is allocated at exactly the size that the
 * conversion needs, so a write past the last row lands in the redzone.
 *
 * Budgets, so that one input cannot run for minutes or allocate gigabytes: at most 4096 packets
 * and 1024 subtitles per input, and the conversion only for rectangles of at most 1048576 pixels.
 *
 * A finding is a sanitizer report or a crash inside a decoder or inside the helpers, or one of the
 * aborts below.
 */

#include "kc_fuzz.h"

#include <libavutil/error.h>

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define KC_FUZZ_MAX_DECODERS 8
#define KC_FUZZ_MAX_PACKETS 4096
#define KC_FUZZ_MAX_SUBTITLES 1024
#define KC_FUZZ_MAX_CONVERT_PIXELS 1048576

/* The documented refusals of a missing argument, asserted on every input. */
static void check_refusals(void) {
    kc_subtitle *sub = (kc_subtitle *)(uintptr_t)1;
    if (ffkmp_subtitle_decode(NULL, NULL, &sub) != AVERROR(EINVAL) || sub != NULL) abort();
    if (ffkmp_subtitle_decode(NULL, NULL, NULL) != AVERROR(EINVAL)) abort();
    kc_codec_ctx *decoder = (kc_codec_ctx *)(uintptr_t)1;
    if (ffkmp_subtitle_decoder_open(NULL, 0, &decoder) != AVERROR(EINVAL) || decoder != NULL) abort();
    int64_t start = 0, end = 0;
    if (ffkmp_subtitle_times(NULL, &start, &end) != AVERROR(EINVAL)) abort();
    if (ffkmp_subtitle_rect_count(NULL) != 0) abort();
    int type, x, y, w, h, forced;
    if (ffkmp_subtitle_rect(NULL, 0, &type, &x, &y, &w, &h, &forced) != AVERROR(EINVAL)) abort();
    uint8_t pixel[4];
    if (ffkmp_subtitle_rect_rgba(NULL, 0, pixel, (int)sizeof(pixel)) != AVERROR(EINVAL)) abort();
    if (ffkmp_subtitle_rect_text(NULL, 0) != NULL) abort();
    ffkmp_subtitle_free(NULL);
    kc_subtitle *none = NULL;
    ffkmp_subtitle_free(&none);
}

/* Converts image rectangle i and checks the result: every colour channel is premultiplied, so it
 * never exceeds its own alpha. A destination one byte short must be refused. */
static size_t convert(const kc_subtitle *sub, int i, int w, int h) {
    if (w <= 0 || h <= 0 || (int64_t)w * h > KC_FUZZ_MAX_CONVERT_PIXELS) return 0;
    int size = w * h * 4;
    uint8_t *rgba = (uint8_t *)malloc((size_t)size);
    if (rgba == NULL) return 0;
    size_t total = 0;
    if (ffkmp_subtitle_rect_rgba(sub, i, rgba, size) == 0) {
        for (int p = 0; p < size; p += 4) {
            uint8_t alpha = rgba[p + 3];
            if (rgba[p] > alpha || rgba[p + 1] > alpha || rgba[p + 2] > alpha) abort();
            total += alpha;
        }
        if (ffkmp_subtitle_rect_rgba(sub, i, rgba, size - 1) != AVERROR(EINVAL)) abort();
    }
    free(rgba);
    return total;
}

/* Reads one decoded subtitle the way a caller reads it. */
static size_t inspect(const kc_subtitle *sub) {
    size_t total = 0;
    int64_t start = 0, end = 0;
    if (ffkmp_subtitle_times(sub, &start, &end) != 0) abort();
    /* An end is known only after a start, and only when it is later. */
    if (end != INT64_MIN && (start == INT64_MIN || end <= start)) abort();
    total += (size_t)start + (size_t)end;

    int count = ffkmp_subtitle_rect_count(sub);
    if (count < 0) abort();
    for (int i = 0; i < count; i++) {
        int type, x, y, w, h, forced;
        if (ffkmp_subtitle_rect(sub, i, &type, &x, &y, &w, &h, &forced) != 0) abort();
        if (forced != 0 && forced != 1) abort();
        total += (size_t)type + (size_t)x + (size_t)y;
        if (type == KC_SUBTITLE_BITMAP) {
            total += convert(sub, i, w, h);
            if (ffkmp_subtitle_rect_text(sub, i) != NULL) abort();
        } else {
            const char *text = ffkmp_subtitle_rect_text(sub, i);
            if (text != NULL) total += strlen(text);
        }
    }

    /* The index bound: one past the last rectangle is refused by every accessor. */
    int type, x, y, w, h, forced;
    if (ffkmp_subtitle_rect(sub, count, &type, &x, &y, &w, &h, &forced) != AVERROR(EINVAL)) abort();
    if (ffkmp_subtitle_rect_text(sub, count) != NULL) abort();
    uint8_t pixel[4];
    if (ffkmp_subtitle_rect_rgba(sub, count, pixel, (int)sizeof(pixel)) != AVERROR(EINVAL)) abort();
    return total;
}

/* Decodes one packet and reads what it completes. Returns 1 when a subtitle came out. */
static int decode(kc_codec_ctx *decoder, const kc_packet *packet, volatile size_t *total) {
    kc_subtitle *sub = NULL;
    /* A refusal or a decoding error means the same here: go on with the next packet. */
    (void)ffkmp_subtitle_decode(decoder, packet, &sub);
    if (sub == NULL) return 0;
    *total += inspect(sub);
    ffkmp_subtitle_free(&sub);
    if (sub != NULL) abort();
    return 1;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();
    check_refusals();

    kc_fuzz_media media = { data, size, 0 };
    kc_fmt_ctx *ctx = NULL;
    if (kc_fuzz_open_media(&ctx, &media, 1) < 0) return 0;
    if (ffkmp_fmt_find_stream_info(ctx) < 0) {
        ffkmp_fmt_close_input_io(&ctx);
        return 0;
    }

    /* Decoders are indexed by stream. Streams that appear later carry no decoder. */
    unsigned streams = ffkmp_fmt_nb_streams(ctx);
    kc_codec_ctx **decoders = (kc_codec_ctx **)calloc(streams > 0 ? streams : 1, sizeof(*decoders));
    kc_packet *packet = ffkmp_packet_alloc();
    volatile size_t total = 0;

    if (decoders != NULL && packet != NULL) {
        int subtitle = ffkmp_media_type_subtitle();
        int opened = 0;
        for (unsigned i = 0; i < streams && opened < KC_FUZZ_MAX_DECODERS; i++) {
            kc_codec_par *par = ffkmp_stream_codecpar(ffkmp_fmt_stream(ctx, i));
            if (ffkmp_codecpar_codec_type(par) != subtitle) continue;
            kc_codec_ctx *decoder = NULL;
            int rc = ffkmp_subtitle_decoder_open(ctx, (int)i, &decoder);
            if ((rc == 0) != (decoder != NULL)) abort();
            decoders[i] = decoder;
            if (decoder != NULL) opened++;
        }

        int subtitles_left = KC_FUZZ_MAX_SUBTITLES;
        for (int n = 0; n < KC_FUZZ_MAX_PACKETS && subtitles_left > 0; n++) {
            if (ffkmp_fmt_read_frame(ctx, packet) < 0) break;
            int index = ffkmp_packet_stream_index(packet);
            if (index >= 0 && (unsigned)index < streams && decoders[index] != NULL) {
                subtitles_left -= decode(decoders[index], packet, &total);
            }
            ffkmp_packet_unref(packet);
        }

        /* The end of the input: an empty packet flushes a decoder that holds a subtitle back. */
        for (unsigned i = 0; i < streams && subtitles_left > 0; i++) {
            if (decoders[i] != NULL) subtitles_left -= decode(decoders[i], packet, &total);
        }
    }

    if (decoders != NULL) {
        for (unsigned i = 0; i < streams; i++) ffkmp_codecctx_free(decoders[i]);
    }
    free(decoders);
    ffkmp_packet_free(packet);
    ffkmp_fmt_close_input_io(&ctx);
    if (ctx != NULL) abort();
    (void)total;
    return 0;
}
