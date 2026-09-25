/* Fuzz target: the demuxers, fed through the custom read callback of ffkmp_fmt_open_input_io.
 *
 * The whole input is the media. kc_fuzz_open_media serves it from memory through the read
 * callback, which is the door every caller-supplied byte source walks through:
 * MediaSource.open(MediaByteSource) on the native and JVM backends, and every open on the web
 * backend. FFmpeg probes the bytes, picks a demuxer and parses the container, so a malformed
 * container reaches a demuxer here the way it does in a consumer's process.
 *
 * Every input is opened twice. The first open is seekable with the size known, as a file
 * opens. The second has no seek callback and no size, as a live stream opens. Demuxers take
 * different paths for the two, because in the second a seek works only inside what is already
 * buffered: the CAF seed, for example, opens only in the first.
 *
 * After a successful open the target does what MediaSource.open does next. It reads the stream
 * information, every property the Kotlin side reads for the container and for each stream, the
 * metadata and the chapters. Then it reads packets to the end and reads every byte of every
 * packet, so a packet whose size disagrees with its buffer is caught. The seekable open then
 * seeks to the middle and reads a few more packets, because a player seeks, and some containers
 * parse their seek index only then.
 *
 * One read loop takes at most 4096 packets. A demuxer that never reaches the end then costs one
 * input a bounded time instead of a timeout.
 *
 * A finding is a sanitizer report or a crash inside a demuxer or inside the bridge, or one of
 * the aborts below. Each abort is a property that the Kotlin side relies on and does not check.
 */

#include "kc_fuzz.h"

#include <libavutil/error.h>

#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define KC_FUZZ_MAX_PACKETS 4096
#define KC_FUZZ_PACKETS_AFTER_SEEK 64

/* Walks a borrowed dictionary the way the Kotlin side does, reading every key and value to its
 * terminator. Returns a sum of the lengths so the reads cannot be optimised away. */
static size_t read_dictionary(kc_dict *dict) {
    size_t total = 0;
    kc_dict_entry *entry = NULL;
    while ((entry = ffkmp_dict_get(dict, entry)) != NULL) {
        const char *key = ffkmp_dict_entry_key(entry);
        const char *value = ffkmp_dict_entry_value(entry);
        if (key == NULL || value == NULL) abort();
        total += strlen(key) + strlen(value);
    }
    return total;
}

/* The codec's configuration bytes, first asked for by size and then copied, as the Kotlin side
 * does. A second copy into a buffer of half the size must give exactly that many bytes: the
 * helper bounds the copy by the caller's buffer, and the sanitizer checks the bound on a heap
 * block of exactly that size. */
static void read_extradata(kc_codec_par *par) {
    int size = ffkmp_codecpar_extradata(par, NULL, 0);
    if (size < 0) abort();
    if (size == 0) return;

    uint8_t *whole = (uint8_t *)malloc((size_t)size);
    if (whole == NULL) return;
    if (ffkmp_codecpar_extradata(par, whole, size) != size) abort();

    int half = size / 2;
    if (half > 0) {
        uint8_t *prefix = (uint8_t *)malloc((size_t)half);
        if (prefix != NULL) {
            if (ffkmp_codecpar_extradata(par, prefix, half) != half) abort();
            if (memcmp(prefix, whole, (size_t)half) != 0) abort();
            free(prefix);
        }
    }
    free(whole);
}

static size_t read_streams(kc_fmt_ctx *ctx) {
    size_t total = 0;
    unsigned count = ffkmp_fmt_nb_streams(ctx);
    for (unsigned i = 0; i < count; i++) {
        kc_stream *stream = ffkmp_fmt_stream(ctx, i);
        if (stream == NULL) abort();
        if (ffkmp_stream_index(stream) != (int)i) abort();
        kc_codec_par *par = ffkmp_stream_codecpar(stream);
        if (par == NULL) abort();

        int num = 0;
        int den = 0;
        total += (size_t)ffkmp_codecpar_codec_type(par);
        /* avcodec_get_name answers "unknown_codec" rather than NULL, and the Kotlin side reads
         * the answer as a string without checking. */
        if (ffkmp_codec_id_name(ffkmp_codecpar_codec_id(par)) == NULL) abort();
        total += (size_t)ffkmp_codecpar_bit_rate(par);
        total += (size_t)ffkmp_codecpar_field_order(par);
        total += (size_t)ffkmp_codecpar_width(par) + (size_t)ffkmp_codecpar_height(par);
        total += (size_t)ffkmp_codecpar_format(par);
        total += (size_t)ffkmp_codecpar_profile(par) + (size_t)ffkmp_codecpar_level(par);
        total += (size_t)ffkmp_codecpar_color_space(par) + (size_t)ffkmp_codecpar_color_primaries(par);
        total += (size_t)ffkmp_codecpar_color_transfer(par) + (size_t)ffkmp_codecpar_color_range(par);
        total += (size_t)ffkmp_codecpar_chroma_location(par);
        total += (size_t)ffkmp_codecpar_bit_depth(par) + (size_t)ffkmp_codecpar_chroma_subsampling(par);
        total += (size_t)ffkmp_codecpar_sample_rate(par) + (size_t)ffkmp_codecpar_channels(par);
        total += (size_t)ffkmp_codecpar_ch_layout_mask(par);
        ffkmp_codecpar_sample_aspect_ratio(par, &num, &den);
        if (den == 0) abort();
        read_extradata(par);

        ffkmp_stream_time_base(stream, &num, &den);
        if (den == 0) abort();
        ffkmp_stream_avg_frame_rate(stream, &num, &den);
        total += (size_t)ffkmp_stream_duration_micros(stream) + (size_t)ffkmp_stream_start_time(stream);
        total += (size_t)ffkmp_stream_disposition(stream);
        int rotation = ffkmp_stream_rotation_degrees(stream);
        if (rotation < 0 || rotation >= 360) abort();
        total += read_dictionary(ffkmp_stream_metadata(stream));
    }
    /* The index guard: one past the last stream is refused, never read. */
    if (ffkmp_fmt_stream(ctx, count) != NULL) abort();
    return total;
}

static size_t read_container(kc_fmt_ctx *ctx) {
    size_t total = 0;
    total += (size_t)ffkmp_fmt_duration(ctx) + (size_t)ffkmp_fmt_start_time(ctx);
    total += (size_t)ffkmp_fmt_bit_rate(ctx) + (size_t)ffkmp_fmt_is_seekable(ctx);
    /* An open input always has the demuxer that opened it. */
    if (ffkmp_fmt_iformat_name(ctx) == NULL) abort();
    total += read_dictionary(ffkmp_fmt_metadata(ctx));

    int chapters = ffkmp_fmt_chapter_count(ctx);
    if (chapters < 0) abort();
    int64_t id = 0;
    int64_t start = 0;
    int64_t end = 0;
    for (int i = 0; i < chapters; i++) {
        if (ffkmp_fmt_chapter_get(ctx, i, &id, &start, &end) != 0) abort();
        total += (size_t)id + read_dictionary(ffkmp_fmt_chapter_metadata(ctx, i));
    }
    /* The index guards: one past the last chapter is refused. */
    if (ffkmp_fmt_chapter_get(ctx, chapters, &id, &start, &end) != AVERROR(EINVAL)) abort();
    if (ffkmp_fmt_chapter_metadata(ctx, chapters) != NULL) abort();
    return total;
}

/* Reads up to `limit` packets and every byte in them. Stops at the first failed read, which is
 * how the end of the input or an unreadable packet shows. */
static size_t read_packets(kc_fmt_ctx *ctx, kc_packet *packet, int limit) {
    size_t total = 0;
    for (int n = 0; n < limit; n++) {
        if (ffkmp_fmt_read_frame(ctx, packet) < 0) break;

        /* Streams can appear while packets are read, so the bound is the count right now. */
        int index = ffkmp_packet_stream_index(packet);
        if (index < 0 || (unsigned)index >= ffkmp_fmt_nb_streams(ctx)) abort();
        int size = ffkmp_packet_size(packet);
        const uint8_t *data = ffkmp_packet_data(packet);
        if (size < 0 || (size > 0 && data == NULL)) abort();
        for (int i = 0; i < size; i++) total += data[i];

        total += (size_t)ffkmp_packet_pts(packet) + (size_t)ffkmp_packet_dts(packet);
        total += (size_t)ffkmp_packet_duration(packet) + (size_t)ffkmp_packet_pos(packet);
        total += (size_t)ffkmp_packet_is_keyframe(packet);
        ffkmp_packet_unref(packet);
    }
    return total;
}

static size_t seek_to_middle(kc_fmt_ctx *ctx, kc_packet *packet) {
    /* The index guard of the seek: a stream index one past the last is refused. */
    unsigned count = ffkmp_fmt_nb_streams(ctx);
    if (count < INT_MAX && ffkmp_fmt_seek_micros(ctx, (int)count, 0) != AVERROR(EINVAL)) abort();

    int64_t duration = ffkmp_fmt_duration(ctx);
    if (duration <= 0) return 0;
    int64_t start = ffkmp_fmt_start_time(ctx);
    int64_t half = duration / 2;
    /* Both numbers come from the container. Skip a sum that would overflow. */
    if (start > 0 && half > INT64_MAX - start) return 0;
    (void)ffkmp_fmt_seek_micros(ctx, -1, start + half);
    return read_packets(ctx, packet, KC_FUZZ_PACKETS_AFTER_SEEK);
}

static size_t demux(const uint8_t *data, size_t size, int seekable, kc_packet *packet) {
    size_t total = 0;
    kc_fuzz_media media = { data, size, 0 };
    kc_fmt_ctx *ctx = NULL;
    if (kc_fuzz_open_media(&ctx, &media, seekable) < 0) return 0;
    if (ffkmp_fmt_io_opaque(ctx) != &media) abort();

    /* MediaSource.open closes the input when the stream information cannot be read, and reads
     * nothing more. The target does the same. */
    if (ffkmp_fmt_find_stream_info(ctx) >= 0) {
        total += read_container(ctx);
        total += read_streams(ctx);
        total += read_packets(ctx, packet, KC_FUZZ_MAX_PACKETS);
        if (seekable) total += seek_to_middle(ctx, packet);
    }

    ffkmp_fmt_close_input_io(&ctx);
    if (ctx != NULL) abort();
    return total;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    /* The documented refusal of a missing read callback, asserted on every input. */
    kc_fmt_ctx *refused = NULL;
    if (ffkmp_fmt_open_input_io(&refused, NULL, NULL, NULL, -1, NULL, NULL, 0, NULL, NULL) != AVERROR(EINVAL)) {
        abort();
    }
    if (refused != NULL) abort();

    kc_packet *packet = ffkmp_packet_alloc();
    if (packet == NULL) return 0;
    volatile size_t total = 0;
    total += demux(data, size, 1, packet);
    total += demux(data, size, 0, packet);
    (void)total;
    ffkmp_packet_free(packet);
    return 0;
}
