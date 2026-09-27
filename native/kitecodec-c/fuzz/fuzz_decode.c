/* Fuzz target: the decoders, fed every packet that a demuxer reads from the input.
 *
 * The input is the same as fuzz_demux's: the whole of it is the media, opened through the
 * custom read callback, seekable with the size known. For every audio and video stream, up to
 * eight, the target opens a decoder the way the native backend opens one: the decoder for the
 * stream's codec id, the parameters copied from the stream, one thread, then the open. It then
 * reads packets to the end, sends each one to its stream's decoder, takes every frame out, and
 * at the end flushes every decoder and takes the last frames out.
 *
 * Every frame goes through what a caller uses to read it. A video frame is read plane by plane
 * with the pointer, row pitch and plane height that the helpers hand out, copied whole with the
 * pixel copy, and, when small, converted to RGBA. An audio frame is copied whole with the sample
 * copy. That is this library's own code, working on geometry that the decoder took from the
 * input, and the sanitizer checks every byte it touches. The converter keeps one scaler per
 * thread between calls; FFmpeg reuses it only when every parameter matches, so a reused scaler
 * is the one a fresh input would build.
 *
 * Subtitle streams are not decoded here. fuzz_subtitle.c decodes them. Every video frame's HDR
 * side data is read too, as a player reads it to decide on tone mapping.
 *
 * Each input is decoded twice. The first pass sets max_pixels and max_samples on every decoder,
 * so a decoder refuses a picture or a block of samples past them, and takes at most 4096
 * packets and 1024 frames. If a decoder refuses either option, its budget is gone, so the target
 * aborts instead of running without it. The second pass opens the decoders the way MediaSource
 * and StreamDecoder open them, with neither option, because the library sets no such budget, so
 * an input that fails only above those budgets is still found. It takes at most 256 packets and
 * 64 frames instead. An input that makes a decoder allocate past the fuzzer's memory limit in
 * that pass is a finding about the library. Both passes convert only pictures of at most 65536
 * pixels.
 *
 * A finding is a sanitizer report or a crash inside a decoder or inside the helpers, or one of
 * the aborts below.
 */

#include "kc_fuzz.h"

#include <libavutil/error.h>
#include <libavutil/imgutils.h>

#include <stdint.h>
#include <stdlib.h>

#define KC_FUZZ_MAX_DECODERS 8
#define KC_FUZZ_MAX_PACKETS 4096
#define KC_FUZZ_MAX_FRAMES 1024
#define KC_FUZZ_UNBUDGETED_PACKETS 256
#define KC_FUZZ_UNBUDGETED_FRAMES 64
#define KC_FUZZ_MAX_CONVERT_PIXELS 65536
#define KC_FUZZ_MAX_PIXELS "1048576"
#define KC_FUZZ_MAX_SAMPLES "262144"

/* Reads a picture the way a renderer given the planes reads it: for each plane, the row width
 * in bytes from each row start, one row pitch apart, for as many rows as the plane height says.
 * A pitch can be negative, for a picture stored bottom up. */
static size_t read_planes(kc_frame *frame, int format, int width) {
    size_t total = 0;
    int planes = ffkmp_frame_plane_count(frame);
    if (planes < 1) abort();
    for (int p = 0; p < planes; p++) {
        const uint8_t *base = ffkmp_frame_plane(frame, p);
        int pitch = ffkmp_frame_linesize(frame, p);
        int rows = ffkmp_frame_plane_height(frame, p);
        int row_bytes = av_image_get_linesize((enum AVPixelFormat)format, width, p);
        if (base == NULL || rows <= 0 || row_bytes <= 0) abort();
        for (int y = 0; y < rows; y++) {
            const uint8_t *row = base + (ptrdiff_t)y * pitch;
            for (int x = 0; x < row_bytes; x++) total += row[x];
        }
    }
    /* The plane bound: one past the format's own plane count has no height. */
    if (ffkmp_frame_plane_height(frame, planes) != 0) abort();
    return total;
}

/* The HDR static metadata a frame carries. 1 when it has it, 0 when it has none. */
static size_t read_frame_hdr(kc_frame *frame) {
    size_t total = 0;
    int mastering[KC_HDR_MASTERING_INTS];
    int flags = 0;
    int has = ffkmp_frame_mastering_display(frame, mastering, &flags);
    if (has != 0 && has != 1) abort();
    if (has == 1) {
        if ((flags & ~(KC_HDR_HAS_PRIMARIES | KC_HDR_HAS_LUMINANCE)) != 0) abort();
        for (int i = 0; i < KC_HDR_MASTERING_INTS; i++) total += (size_t)mastering[i];
    }
    int max_cll = 0;
    int max_fall = 0;
    has = ffkmp_frame_content_light(frame, &max_cll, &max_fall);
    if (has != 0 && has != 1) abort();
    if (has == 1) total += (size_t)max_cll + (size_t)max_fall;
    return total;
}

static size_t inspect_video(kc_frame *frame, int rgba) {
    size_t total = 0;
    int width = ffkmp_frame_width(frame);
    int height = ffkmp_frame_height(frame);
    int format = ffkmp_frame_format(frame);
    if (width <= 0 || height <= 0 || format < 0) return 0;
    total += read_frame_hdr(frame);
    /* No hardware device is attached, so every frame is in memory. */
    if (ffkmp_frame_is_hardware(frame) || ffkmp_frame_hw_surface(frame) != NULL) abort();

    total += read_planes(frame, format, width);

    int needed = ffkmp_image_get_buffer_size(format, width, height, 1);
    if (needed > 0) {
        uint8_t *copy = (uint8_t *)malloc((size_t)needed);
        if (copy != NULL) {
            if (ffkmp_frame_copy_to_buffer(frame, copy, needed) != needed) abort();
            total += copy[needed - 1];
            free(copy);
        }
    }

    if ((int64_t)width * height <= KC_FUZZ_MAX_CONVERT_PIXELS && rgba >= 0) {
        kc_frame *converted = ffkmp_frame_convert_pixfmt(frame, rgba);
        if (converted != NULL) {
            if (ffkmp_frame_width(converted) != width || ffkmp_frame_height(converted) != height
                || ffkmp_frame_format(converted) != rgba) {
                abort();
            }
            total += read_planes(converted, rgba, width);
            ffkmp_frame_free(converted);
        }
    }
    return total;
}

static size_t inspect_audio(kc_frame *frame) {
    size_t total = 0;
    if (ffkmp_frame_nb_samples(frame) <= 0) return 0;
    int needed = ffkmp_samples_get_buffer_size(frame);
    if (needed > 0) {
        uint8_t *copy = (uint8_t *)malloc((size_t)needed);
        if (copy != NULL) {
            if (ffkmp_samples_copy_to_buffer(frame, copy, needed) != needed) abort();
            total += copy[needed - 1];
            free(copy);
        }
    }
    total += (size_t)ffkmp_frame_sample_rate(frame) + (size_t)ffkmp_frame_ch_layout_mask(frame);
    return total;
}

/* Takes every frame the decoder has ready. Returns when the decoder wants more input, has
 * finished, fails, or the input's frame budget is spent. */
static size_t drain(kc_codec_ctx *decoder, int is_video, kc_frame *frame, int rgba, int *frames_left) {
    size_t total = 0;
    while (*frames_left > 0 && ffkmp_codecctx_receive_frame(decoder, frame) >= 0) {
        (*frames_left)--;
        total += (size_t)ffkmp_frame_pts(frame) + (size_t)ffkmp_frame_duration(frame);
        total += is_video ? inspect_video(frame, rgba) : inspect_audio(frame);
        ffkmp_frame_unref(frame);
    }
    return total;
}

static kc_codec_ctx *open_decoder(kc_codec_par *par, int budgeted) {
    const kc_codec *codec = ffkmp_find_decoder_by_id(ffkmp_codecpar_codec_id(par));
    if (codec == NULL) return NULL;
    kc_codec_ctx *decoder = ffkmp_codecctx_alloc(codec);
    if (decoder == NULL) return NULL;
    if (ffkmp_codecctx_from_par(decoder, par) < 0) {
        ffkmp_codecctx_free(decoder);
        return NULL;
    }
    ffkmp_codecctx_set_threads(decoder, 1, 0);
    if (budgeted) {
        if (ffkmp_codecctx_set_opt(decoder, "max_pixels", KC_FUZZ_MAX_PIXELS) != 0) abort();
        if (ffkmp_codecctx_set_opt(decoder, "max_samples", KC_FUZZ_MAX_SAMPLES) != 0) abort();
    }
    if (ffkmp_codecctx_open(decoder, codec) < 0) {
        ffkmp_codecctx_free(decoder);
        return NULL;
    }
    return decoder;
}

/* One decode of the whole input, with or without the decoder budgets, within these counts. */
static size_t decode_all(const uint8_t *data, size_t size, int budgeted, int max_packets, int max_frames) {
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
    int *is_video = (int *)calloc(streams > 0 ? streams : 1, sizeof(*is_video));
    kc_packet *packet = ffkmp_packet_alloc();
    kc_frame *frame = ffkmp_frame_alloc();
    volatile size_t total = 0;

    if (decoders != NULL && is_video != NULL && packet != NULL && frame != NULL) {
        int video = ffkmp_media_type_video();
        int audio = ffkmp_media_type_audio();
        int opened = 0;
        for (unsigned i = 0; i < streams && opened < KC_FUZZ_MAX_DECODERS; i++) {
            kc_codec_par *par = ffkmp_stream_codecpar(ffkmp_fmt_stream(ctx, i));
            int type = ffkmp_codecpar_codec_type(par);
            if (type != video && type != audio) continue;
            decoders[i] = open_decoder(par, budgeted);
            is_video[i] = (type == video);
            if (decoders[i] != NULL) opened++;
        }

        int rgba = ffkmp_pix_fmt_from_name("rgba");
        int frames_left = max_frames;
        for (int n = 0; n < max_packets && frames_left > 0; n++) {
            if (ffkmp_fmt_read_frame(ctx, packet) < 0) break;
            int index = ffkmp_packet_stream_index(packet);
            if (index >= 0 && (unsigned)index < streams && decoders[index] != NULL) {
                /* A refusal, a decoding error or a full decoder all mean the same here: take
                 * out what is ready and go on with the next packet. */
                (void)ffkmp_codecctx_send_packet(decoders[index], packet);
                total += drain(decoders[index], is_video[index], frame, rgba, &frames_left);
            }
            ffkmp_packet_unref(packet);
        }

        /* The end of the input: every decoder gives up the frames it still holds. */
        for (unsigned i = 0; i < streams; i++) {
            if (decoders[i] == NULL) continue;
            if (ffkmp_codecctx_send_packet(decoders[i], NULL) >= 0) {
                total += drain(decoders[i], is_video[i], frame, rgba, &frames_left);
            }
        }
    }

    if (decoders != NULL) {
        for (unsigned i = 0; i < streams; i++) ffkmp_codecctx_free(decoders[i]);
    }
    free(decoders);
    free(is_video);
    ffkmp_frame_free(frame);
    ffkmp_packet_free(packet);
    ffkmp_fmt_close_input_io(&ctx);
    if (ctx != NULL) abort();
    return total;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    /* The documented refusals of a missing context or frame, asserted on every input. */
    if (ffkmp_codecctx_send_packet(NULL, NULL) != AVERROR(EINVAL)) abort();
    if (ffkmp_codecctx_receive_frame(NULL, NULL) != AVERROR(EINVAL)) abort();
    int q[KC_HDR_MASTERING_INTS];
    int flags = 0;
    int light = 0;
    if (ffkmp_frame_mastering_display(NULL, q, &flags) != AVERROR(EINVAL)) abort();
    if (ffkmp_frame_content_light(NULL, &light, &light) != AVERROR(EINVAL)) abort();

    volatile size_t total = 0;
    total += decode_all(data, size, 1, KC_FUZZ_MAX_PACKETS, KC_FUZZ_MAX_FRAMES);
    total += decode_all(data, size, 0, KC_FUZZ_UNBUDGETED_PACKETS, KC_FUZZ_UNBUDGETED_FRAMES);
    (void)total;
    return 0;
}
