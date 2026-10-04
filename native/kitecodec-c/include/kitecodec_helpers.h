/* Declarations for the exported FFmpeg helper layer. */

#ifndef KITECODEC_HELPERS_H
#define KITECODEC_HELPERS_H

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "kitecodec_handles.h"
/* For KC_GATE_OPEN: every constructor helper below asks the identity gate before it builds
   anything, so the gate holds for a pure C or JNI consumer and not only for the Kotlin callers. */
#include "kitecodec_abi.h"

/* KC_API marks the helpers the Kotlin side imports as deliberately exported.
 *
 * The archive is compiled with -fvisibility=hidden, which governs the DYNAMIC symbol table
 * and not static linking: an unmarked helper still resolves inside the link that embeds the
 * archive. So this macro is not what makes the cinterop work; it is what makes the exported
 * set a decision rather than an accident, and scripts/symbol-audit.sh checks the decision.
 * The four trailing-underscore helpers are `static` and never carry it.
 */
#if defined(_WIN32)
#define KC_API __declspec(dllexport)
#else
#define KC_API __attribute__((visibility("default")))
#endif

/* Errors & macros */

/* Thread affinity. The returned pointer is into
 * `static __thread char buf[256]` at def line 37, which is the only static storage in
 * the whole helper layer. Two consequences, and both are contract rather than accident:
 * the storage is per thread, so a pointer must never be shared between threads; and the
 * next ffkmp_strerror call on the same thread overwrites it, so the string must be
 * copied or consumed before calling again. It must never be stored.
 * Proved by tests/test_strerror_thread.c.
 */
KC_API const char* ffkmp_strerror(int errnum);
KC_API int ffkmp_averror_eagain(void);
KC_API int ffkmp_averror_eof(void);
KC_API int64_t ffkmp_rescale_q(int64_t v, int sn, int sd, int dn, int dd);

/* Logging */

/* Receives FFmpeg's own log lines. level is FFmpeg's, where a lower number is more severe:
 * AV_LOG_ERROR is 16 and AV_LOG_WARNING is 24. component names the object that logged, such as
 * "h264" or "mov,mp4,m4a,3gp,3g2,mj2", and is "" when FFmpeg names none. message is the line without
 * its trailing newline. Both strings are the bytes FFmpeg wrote and live only for the call.
 */
typedef void (*ffkmp_log_sink)(int level, const char *component, const char *message);

/* Routes FFmpeg's log lines at level and more severe to sink. A NULL sink drops every line, so
 * nothing reaches stderr. Until the first call, FFmpeg's default callback still prints; the Kotlin
 * library makes this call with NULL once, when it first accepts the FFmpeg runtime.
 *
 * FFmpeg calls the sink on whichever thread logs, including its own worker threads, so the sink must
 * be safe on any thread and must not call back into this library. Safe to call from any thread. A
 * line already being delivered when this returns may still reach the previous sink.
 */
KC_API void ffkmp_log_set_sink(ffkmp_log_sink sink, int level);

/* kc_frame */

/* Ownership. Returns a new kc_frame the caller owns, or NULL when allocation fails.
 * Release it with ffkmp_frame_free and never with free.
 */
KC_API kc_frame* ffkmp_frame_alloc(void);

/* Ownership. Frees the frame and drops every reference it holds. The pointer arrives by
 * value, so the caller's own variable is not cleared and must be cleared by the caller.
 * A NULL frame is accepted and does nothing.
 */
KC_API void     ffkmp_frame_free(kc_frame *f);

/* Ownership. Drops the frame's data references and resets its fields. The kc_frame itself
 * stays allocated and stays the caller's. A NULL frame is accepted and does nothing.
 */
KC_API void     ffkmp_frame_unref(kc_frame *f);
KC_API int64_t  ffkmp_frame_pts(kc_frame *f);
KC_API int64_t  ffkmp_frame_duration(kc_frame *f);
KC_API int      ffkmp_frame_format(kc_frame *f);
KC_API int      ffkmp_frame_width(kc_frame *f);
KC_API int      ffkmp_frame_height(kc_frame *f);
KC_API int      ffkmp_frame_nb_samples(kc_frame *f);
KC_API int      ffkmp_frame_sample_rate(kc_frame *f);
KC_API int      ffkmp_frame_channels(kc_frame *f);
KC_API int      ffkmp_frame_linesize(kc_frame *f, int p);
KC_API void     ffkmp_frame_set_pts(kc_frame *f, int64_t pts);
KC_API void     ffkmp_frame_set_format(kc_frame *f, int v);
KC_API void     ffkmp_frame_set_width(kc_frame *f, int v);
KC_API void     ffkmp_frame_set_height(kc_frame *f, int v);
KC_API void     ffkmp_frame_set_sample_rate(kc_frame *f, int v);
KC_API void     ffkmp_frame_set_nb_samples(kc_frame *f, int v);

/* Ownership. Allocates data buffers from the frame's width, height, format and, for audio,
 * nb_samples and ch_layout, all of which must be set first. The frame owns the buffers and
 * ffkmp_frame_unref or ffkmp_frame_free releases them. A NULL frame is refused with
 * AVERROR(EINVAL).
 */
KC_API int      ffkmp_frame_get_buffer(kc_frame *f, int align);

/* Ownership. Uninitialises the frame's existing channel layout before writing the default
 * for `ch`, so calling it repeatedly does not leak a layout allocation. The frame keeps
 * ownership of the result.
 */
KC_API void     ffkmp_frame_set_ch_layout_default(kc_frame *f, int ch);
KC_API void     ffkmp_frame_use_best_effort_ts(kc_frame *f);

/* Ownership. Returns a new caller-owned kc_frame, or NULL. The data is shared with the
 * source through a new reference and is not copied. Release it with ffkmp_frame_free.
 */
KC_API kc_frame* ffkmp_frame_clone(const kc_frame *f);

/* Ownership. Returns a new caller-owned kc_frame with its own buffers, or NULL. Release it
 * with ffkmp_frame_free. The SwsContext is CACHED per calling thread and reused while the
 * geometry and formats match, so it outlives the call and nothing about it reaches the caller
 * either way. The cache is freed when its thread ends. Both pixel formats are validated before swscale sees them: a value outside the
 * enum, a hardware format, or one swscale cannot read or write returns NULL rather than
 * asserting inside libswscale. The colour tags on the result describe the OUTPUT,
 * not the source: an RGB destination is full range with an RGB matrix. The conversion is exact:
 * every 8-bit result is within 1 of what the source's colour matrix gives, chroma is interpolated
 * rather than repeated, and the bytes are the same on every architecture (#164).
 */
KC_API kc_frame* ffkmp_frame_convert_pixfmt(const kc_frame *src, int dst_fmt);

/* Ownership, caching and refusals as ffkmp_frame_convert_pixfmt, with a cache of its own. A
 * picture for a screen, converted once per frame drawn, into dst_fmt, which must be a packed 8-bit
 * RGB format of 3 or 4 bytes per pixel, rgba among them; any other format returns NULL. The
 * conversion is swscale's fast one rather than the exact one: up to 3 off the colour matrix, with
 * each chroma sample repeated across a pair of pixels in a row rather than interpolated, for
 * about a seventh of the time on a 1080p frame (#164). Two steps are added. A YCgCo picture uses
 * the YCgCo matrix. A PQ or HLG picture is tone mapped to SDR: BT.2020 primaries fold to BT.709,
 * and luminance rolls off from a 1000 nit peak to 203 nit reference white, encoded as gamma 2.2.
 * The result's tags say so. Every other picture gives the fast conversion's bytes, with the tags
 * ffkmp_frame_convert_pixfmt gives.
 */
KC_API kc_frame* ffkmp_frame_convert_display(const kc_frame *src, int dst_fmt);
KC_API int ffkmp_image_get_buffer_size(int fmt, int w, int h, int align);
KC_API int ffkmp_frame_copy_to_buffer(kc_frame *f, uint8_t *dst, int dst_size);
KC_API int ffkmp_samples_get_buffer_size(kc_frame *f);
KC_API int ffkmp_samples_copy_to_buffer(kc_frame *f, uint8_t *dst, int dst_size);
KC_API int ffkmp_frame_fill_video(kc_frame *f, const uint8_t *src, int src_size);
KC_API int ffkmp_frame_fill_audio(kc_frame *f, const uint8_t *src, int src_size);

/* Pixel/sample format names */
KC_API const char* ffkmp_pix_fmt_name(int fmt);
KC_API int         ffkmp_pix_fmt_from_name(const char *n);
KC_API const char* ffkmp_sample_fmt_name(int fmt);
KC_API int         ffkmp_sample_fmt_from_name(const char *n);

/* kc_dict iteration */
KC_API kc_dict_entry* ffkmp_dict_get(kc_dict *d, kc_dict_entry *prev);
KC_API const char* ffkmp_dict_entry_key(kc_dict_entry *e);
KC_API const char* ffkmp_dict_entry_value(kc_dict_entry *e);

/* kc_packet */

/* Ownership. Returns a new kc_packet the caller owns, or NULL when allocation fails.
 * Release it with ffkmp_packet_free and never with free.
 */
KC_API kc_packet* ffkmp_packet_alloc(void);

/* Ownership. Frees the packet and drops every reference it holds. The pointer arrives by
 * value, so the caller's own variable is not cleared and must be cleared by the caller.
 * A NULL packet is accepted and does nothing.
 */
KC_API void      ffkmp_packet_free(kc_packet *p);

/* Ownership. Drops the packet's data reference and resets its fields. The kc_packet itself
 * stays allocated and stays the caller's. A NULL packet is accepted and does nothing.
 */
KC_API void      ffkmp_packet_unref(kc_packet *p);
KC_API int64_t   ffkmp_packet_pts(kc_packet *p);
KC_API int64_t   ffkmp_packet_dts(kc_packet *p);
KC_API int       ffkmp_packet_stream_index(kc_packet *p);
KC_API int       ffkmp_packet_size(kc_packet *p);
KC_API uint8_t*  ffkmp_packet_data(kc_packet *p);

/* Copies n bytes from src to dst, which must not overlap. For Kotlin code shared by every native
 * target, which cannot call memcpy because its size_t width differs between them. Nothing is
 * copied when n <= 0 or either pointer is NULL.
 */
KC_API void      ffkmp_copy_bytes(void *dst, const void *src, int n);

KC_API int64_t   ffkmp_packet_duration(kc_packet *p);
KC_API int       ffkmp_packet_is_keyframe(kc_packet *p);

/* 1 when the packet carries AV_PKT_FLAG_DISCARD, 0 otherwise or for a NULL packet. A decoder needs
 * such a packet, but nothing it decodes to is shown: a demuxer flags the samples it reads before an
 * MP4 edit list starts this way, so that a cut made by an editor still decodes from its keyframe. */
KC_API int       ffkmp_packet_is_discard(const kc_packet *p);

/* The samples a decoder drops from the start of what this packet decodes to, read from its
 * AV_PKT_DATA_SKIP_SAMPLES side data: an encoder's priming at the start of an AAC or Opus stream,
 * which the container marks to be hidden. The count can reach past this packet into the next ones.
 * 0 when the packet carries no such data, and for a NULL packet. */
KC_API int64_t   ffkmp_packet_skip_start(const kc_packet *p);

KC_API void      ffkmp_packet_set_stream_index(kc_packet *p, int i);
KC_API void      ffkmp_packet_set_pts(kc_packet *p, int64_t v);
KC_API void      ffkmp_packet_set_dts(kc_packet *p, int64_t v);
/* Sets how long the packet lasts, in its stream's time base. Nothing happens for a NULL packet. */
KC_API void      ffkmp_packet_set_duration(kc_packet *p, int64_t v);
KC_API void      ffkmp_packet_rescale_ts(kc_packet *p, int sn, int sd, int dn, int dd);

/* Ownership. Returns a new kc_packet the caller owns, carrying one more reference to the same
 * compressed payload as the input (av_packet_ref), so it is O(1) over the payload size. The two
 * packets close independently, in either order, each with ffkmp_packet_free. A NULL input is
 * refused with NULL. Allocation or ref failure frees everything this call created and returns
 * NULL. It backs Packet.copy(), and the JVM bridge needs an owned clone it can hand across the
 * boundary. */
KC_API kc_packet* ffkmp_packet_clone(const kc_packet *packet);

/* kc_codec_par */
KC_API int     ffkmp_codecpar_codec_type(kc_codec_par *p);
KC_API int     ffkmp_media_type_video(void);
KC_API int     ffkmp_media_type_audio(void);
KC_API int     ffkmp_media_type_subtitle(void);
KC_API int     ffkmp_media_type_data(void);
KC_API int     ffkmp_media_type_attachment(void);
KC_API int     ffkmp_codecpar_codec_id(kc_codec_par *p);
KC_API int64_t ffkmp_codecpar_bit_rate(kc_codec_par *p);
/* The stream's field order, as the display order rather than the coded one:
   0 unknown, 1 progressive, 2 top field first, 3 bottom field first. FFmpeg's coded-order variants
   (TT/BB/TB/BT) collapse onto the display order they present in, because a deinterlacer needs to
   know which field to show first and nothing here needs to know how they were stored. */
KC_API int32_t ffkmp_codecpar_field_order(const kc_codec_par *p);
KC_API int     ffkmp_codecpar_width(kc_codec_par *p);
KC_API int     ffkmp_codecpar_height(kc_codec_par *p);
KC_API int     ffkmp_codecpar_format(kc_codec_par *p);
KC_API int     ffkmp_codecpar_profile(kc_codec_par *p);
KC_API int     ffkmp_codecpar_level(kc_codec_par *p);
KC_API int     ffkmp_codecpar_color_space(kc_codec_par *p);
KC_API int     ffkmp_codecpar_color_primaries(kc_codec_par *p);
KC_API int     ffkmp_codecpar_color_transfer(kc_codec_par *p);
KC_API int     ffkmp_codecpar_color_range(kc_codec_par *p);
KC_API int     ffkmp_codecpar_chroma_location(kc_codec_par *p);
/** Luma/component depth from the declared pixel format, or bits_per_raw_sample as a fallback. */
KC_API int     ffkmp_codecpar_bit_depth(kc_codec_par *p);
/** Returns 400, 420, 422 or 444 for a representable YUV layout; zero when unknown or not YUV. */
KC_API int     ffkmp_codecpar_chroma_subsampling(kc_codec_par *p);
KC_API int     ffkmp_codecpar_sample_rate(kc_codec_par *p);
KC_API int     ffkmp_codecpar_channels(kc_codec_par *p);
/* Copies the codec configuration record or other raw extradata. A NULL destination queries the
 * full byte count. A non-NULL destination receives at most dst_size bytes and the copied count is
 * returned. No extradata returns zero. A NULL parameters object or negative size is refused with
 * AVERROR(EINVAL).
 */
KC_API int     ffkmp_codecpar_extradata(kc_codec_par *p, uint8_t *dst, int dst_size);
KC_API void    ffkmp_codecpar_sample_aspect_ratio(kc_codec_par *p, int *num, int *den);

/* Ownership. Fills the parameters from the context, freeing and replacing any extradata the
 * parameters already held. The parameters stay owned by whoever holds them. A NULL parameters
 * object or context is refused with AVERROR(EINVAL).
 */
KC_API int ffkmp_codecpar_from_context(kc_codec_par *par, kc_codec_ctx *ctx);

/* Ownership. Replaces the destination's contents with a copy of the source, freeing what the
 * destination held, then clears codec_tag so the muxer picks its own. The destination stays
 * owned by its stream. A NULL destination or source is refused with AVERROR(EINVAL).
 */
KC_API int ffkmp_codecpar_copy_for_mux(kc_codec_par *dst, const kc_codec_par *src);

/* kc_codec / kc_codec_ctx */

/* Ownership. Returns a new kc_codec_ctx the caller owns, or NULL. Release it with
 * ffkmp_codecctx_free whether or not it was ever opened.
 */
KC_API kc_codec_ctx* ffkmp_codecctx_alloc(const kc_codec *c);

/* Ownership. Frees the context together with everything it holds, including buffered
 * frames and, when it was opened, the codec's private state. The pointer arrives by value,
 * so the caller's own variable is not cleared.
 */
KC_API void  ffkmp_codecctx_free(kc_codec_ctx *c);

/* Ownership. Allocates the codec's internal state onto the context. Failure leaves nothing
 * extra to undo, because ffkmp_codecctx_free releases the context either way. Never call
 * it twice on one context. A NULL context is refused with AVERROR(EINVAL), while a NULL codec
 * is passed through so a context that remembers its codec can be opened.
 */
KC_API int   ffkmp_codecctx_open(kc_codec_ctx *c, const kc_codec *codec);

/* Ownership. Copies the parameters into the context, taking a private copy of extradata.
 * The parameters stay owned by whoever holds them, normally an kc_stream. A NULL context or
 * parameters object is refused with AVERROR(EINVAL).
 */
KC_API int   ffkmp_codecctx_from_par(kc_codec_ctx *c, kc_codec_par *p);

/* The def's header removal forces this raw-int wrapper before the typed outcome model lands
 * later; a NULL packet is passed through to flush, while a NULL context is refused.
 */
KC_API int ffkmp_codecctx_send_packet(kc_codec_ctx *ctx, const kc_packet *packet);

/* The def's header removal forces this raw-int wrapper before the typed outcome model lands
 * later; a NULL context or output frame is refused.
 */
KC_API int ffkmp_codecctx_receive_frame(kc_codec_ctx *ctx, kc_frame *frame);

/* The def's header removal forces this raw-int wrapper before the typed outcome model lands
 * later; a NULL frame is passed through to flush, while a NULL context is refused.
 */
KC_API int ffkmp_codecctx_send_frame(kc_codec_ctx *ctx, const kc_frame *frame);

/* The def's header removal forces this raw-int wrapper before the typed outcome model lands
 * later; a NULL context or output packet is refused.
 */
KC_API int ffkmp_codecctx_receive_packet(kc_codec_ctx *ctx, kc_packet *packet);
KC_API void  ffkmp_codecctx_set_video(
    kc_codec_ctx *c, int width, int height, int pix_fmt,
    int fr_num, int fr_den, int tb_num, int tb_den, int64_t bit_rate, int gop_size
);

/* Ownership. Uninitialises the context's existing channel layout before writing the default
 * for `channels`, so calling it repeatedly does not leak a layout allocation.
 */
KC_API void  ffkmp_codecctx_set_audio(
    kc_codec_ctx *c, int sample_rate, int sample_fmt, int channels, int64_t bit_rate
);
KC_API int   ffkmp_codec_first_sample_fmt(const kc_codec *codec);
KC_API int ffkmp_codec_first_pix_fmt(const kc_codec *codec);
KC_API int ffkmp_codec_supports_pix_fmt(const kc_codec *codec, int fmt);
KC_API int   ffkmp_codecctx_frame_size(kc_codec_ctx *c);
KC_API int   ffkmp_codecctx_sample_rate(kc_codec_ctx *c);
KC_API int   ffkmp_codecctx_channels(kc_codec_ctx *c);
KC_API void  ffkmp_codecctx_time_base(kc_codec_ctx *c, int *n, int *d);
KC_API void  ffkmp_codecctx_set_global_header(kc_codec_ctx *c);

/* Ownership. The option system copies key and value, so neither string is retained and both
 * may be freed immediately. A NULL context or key is refused with AVERROR(EINVAL).
 */
KC_API int   ffkmp_codecctx_set_opt(kc_codec_ctx *c, const char *key, const char *value);
KC_API void  ffkmp_codecctx_set_full_range(kc_codec_ctx *c);

/* The colour an encoder declares, as FFmpeg's own enum values: primaries, transfer, matrix, range
 * and chroma location, each written as given, the unspecified values included. Run it before
 * ffkmp_codecctx_open. A NULL context or a value outside its enum is refused with
 * AVERROR(EINVAL) and changes nothing. */
KC_API int ffkmp_codecctx_set_color(kc_codec_ctx *c, int primaries, int transfer, int matrix,
                                    int range, int chroma_location);

/* The shape of one pixel. 0/1 means unknown, which players read as square. A NULL context, a
 * negative numerator or a denominator that is not positive is refused with AVERROR(EINVAL). */
KC_API int ffkmp_codecctx_set_sample_aspect_ratio(kc_codec_ctx *c, int num, int den);

/* The exact channel layout of an audio encoder, as a native channel mask; it replaces the
 * default layout that ffkmp_codecctx_set_audio wrote. A NULL context or a mask that is not
 * positive is refused with AVERROR(EINVAL). */
KC_API int ffkmp_codecctx_set_ch_layout_mask(kc_codec_ctx *c, int64_t mask);

/* The context's channel layout as a native channel mask; 0 for NULL or a layout with no native
 * order. */
KC_API int64_t ffkmp_codecctx_ch_layout_mask(kc_codec_ctx *c);

/* HDR static metadata crosses this layer as ints. A mastering display is KC_HDR_MASTERING_INTS
 * ints, ten num/den pairs in this order: red x, red y, green x, green y, blue x, blue y, white x,
 * white y, minimum luminance, maximum luminance. The flags say which halves are present:
 * KC_HDR_HAS_PRIMARIES for the first eight pairs, KC_HDR_HAS_LUMINANCE for the last two. */
#define KC_HDR_MASTERING_INTS 20
#define KC_HDR_HAS_PRIMARIES 1
#define KC_HDR_HAS_LUMINANCE 2

/* Gives an encoder a mastering display (SMPTE ST 2086) before ffkmp_codecctx_open. The encoder
 * writes it into the stream's side data when it opens, and an encoder that embeds it in the
 * bitstream does that too. A second call replaces the first. A NULL context or array, flags with
 * neither half, or a pair with a denominator that is not positive is refused with
 * AVERROR(EINVAL). Built against FFmpeg older than 7, which has no way to do this, it answers
 * AVERROR(ENOSYS) after those checks. */
KC_API int ffkmp_codecctx_add_mastering_display(kc_codec_ctx *c, const int *q, int flags);

/* Gives an encoder a content light level (CTA-861.3), in candelas per square metre, the same way.
 * A NULL context or a negative level is refused with AVERROR(EINVAL), and FFmpeg older than 7
 * answers AVERROR(ENOSYS) the same way. */
KC_API int ffkmp_codecctx_add_content_light(kc_codec_ctx *c, int max_cll, int max_fall);
KC_API const kc_codec* ffkmp_find_decoder_by_id(int id);

/* The def's header removal forces this pointer wrapper before the typed outcome model lands
 * later; a NULL name returns NULL.
 */
KC_API const kc_codec* ffkmp_find_encoder_by_name(const char *name);

/* The def's header removal forces this pointer wrapper before the typed outcome model lands
 * later; a NULL name returns NULL.
 */
KC_API const kc_codec* ffkmp_find_decoder_by_name(const char *name);
/* The codec id implemented by a selected static codec descriptor. NULL returns zero. This lets
 * callers reject a named decoder that cannot decode the stream before allocating/opening a
 * context, instead of using avcodec_open2 as a compatibility probe. */
KC_API int ffkmp_codec_id(const kc_codec *codec);
KC_API const char* ffkmp_codec_id_name(int id);

/* The codec id of a format by FFmpeg's codec name ("h264", "aac"): the inverse of
 * ffkmp_codec_id_name. 0, AV_CODEC_ID_NONE, for NULL or a name that is not a format, such as an
 * encoder's name ("libx264"). */
KC_API int ffkmp_codec_id_by_name(const char *name);

/* The encoder FFmpeg picks by default for codec id, as avcodec_find_encoder does; NULL when this
 * build has none. The decoder twin is ffkmp_find_decoder_by_id. */
KC_API const kc_codec* ffkmp_find_encoder_by_id(int id);

/* An implementation's own name ("libx264", "aac"), which may differ from the name of the format
 * it implements; NULL for NULL. */
KC_API const char* ffkmp_codec_name(const kc_codec *codec);
KC_API int ffkmp_codecctx_pix_fmt(kc_codec_ctx *c);
KC_API int ffkmp_codecctx_width(kc_codec_ctx *c);
KC_API int ffkmp_codecctx_height(kc_codec_ctx *c);

/* kc_fmt_ctx (input + output) */

/* Ownership. On success *out is a new kc_fmt_ctx the caller owns and must release with
 * ffkmp_fmt_close_input, never with ffkmp_fmt_free_output. On failure *out is set to NULL
 * and nothing is left allocated. A NULL out pointer or path is refused with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_open_input(kc_fmt_ctx **out, const char *path);

/* Ownership. Closes the demuxer, frees the context with every stream in it, and writes NULL
 * through ctx. Safe on a pointer that is already NULL. It must not be used on a context
 * from ffkmp_fmt_alloc_output2.
 */
KC_API void ffkmp_fmt_close_input(kc_fmt_ctx **ctx);

/* Copies every tag and the disposition flags of src onto dst, two streams of any contexts. The
 * display matrix and other coded side data travel with the codec parameters, not here. Run it
 * before the output header is written. NULL either side is refused with AVERROR(EINVAL). */
KC_API int ffkmp_stream_copy_identity(kc_stream *dst, const kc_stream *src);

/* Appends one chapter to an output context: bounds in microseconds on the output timeline and n
 * tag pairs. Run it before the header is written. The context owns the chapter. A NULL context,
 * an end before the start, a negative n, or a NULL entry in the arrays is refused with
 * AVERROR(EINVAL). */
KC_API int ffkmp_fmt_add_chapter(kc_fmt_ctx *ctx, int64_t id, int64_t start_us, int64_t end_us,
                                 const char *const *keys, const char *const *values, int n);

/* The component families ffkmp_component_names lists. */
#define KC_COMPONENT_DECODERS 0
#define KC_COMPONENT_ENCODERS 1
#define KC_COMPONENT_DEMUXERS 2
#define KC_COMPONENT_MUXERS 3
#define KC_COMPONENT_FILTERS 4
#define KC_COMPONENT_INPUT_PROTOCOLS 5
#define KC_COMPONENT_BITSTREAM_FILTERS 6

/* The names of every component of kind (a KC_COMPONENT_* value) the linked FFmpeg contains, in
 * FFmpeg's registry order, separated by newlines and ended by a NUL, written into buf when it
 * holds cap bytes. Returns the length the whole list needs without the NUL, so a caller whose
 * buffer was too short calls again with that length plus one; the truncated list is still
 * NUL-ended. cap may be 0 with a NULL buf to ask for the length alone. An unknown kind, a negative
 * cap, or a NULL buf with a positive cap is refused with AVERROR(EINVAL). */
KC_API int ffkmp_component_names(int kind, char *buf, int cap);

/* The audio resampler, libswresample behind one opaque handle. Channel layouts are FFmpeg's
 * default for each channel count; sample formats are AVSampleFormat values, as
 * ffkmp_sample_fmt_from_name answers them.
 */
typedef struct kc_swr kc_swr;

/* Ownership. On success *out is a resampler the caller owns and releases with ffkmp_swr_free.
 * in_mask and out_mask name each side's channel layout as a native channel mask; 0 takes
 * FFmpeg's default layout for the channel count. A non-positive rate or channel count, an unknown
 * sample format, a negative mask or a mask whose channel count differs is refused with
 * AVERROR(EINVAL) and leaves *out NULL. */
KC_API int ffkmp_swr_create(kc_swr **out,
                            int in_rate, int in_channels, int in_format,
                            int out_rate, int out_channels, int out_format,
                            int64_t in_mask, int64_t out_mask);

/* Converts in into out. out is a frame the caller allocated; it is unreferenced first, stamped
 * with the output rate, layout and format, and given buffers for the converted samples, so its
 * nb_samples says how many came out (0 when the resampler buffered them all). NULL in drains the
 * samples the resampler still holds. A frame that names no layout is read as the input layout. A
 * frame whose rate, layout or format differs from the ones the resampler was created for is
 * refused with AVERROR_INPUT_CHANGED. */
KC_API int ffkmp_swr_convert_frame(kc_swr *s, kc_frame *out, const kc_frame *in);

/* Ownership. Frees *s and writes NULL through the pointer; safe on NULL either way. */
KC_API void ffkmp_swr_free(kc_swr **s);

/* FFmpeg's default layout for `channels` channels as a native channel mask: the layout
 * av_channel_layout_default picks, which is the first native layout with that count and the one
 * ffkmp_swr_create takes for a mask of 0. Returns 0 when the linked FFmpeg has no native layout
 * with that count, and for a count that is not positive. */
KC_API int64_t ffkmp_ch_layout_default_mask(int channels);

/* Subtitle decoding. A decoded subtitle is FFmpeg's AVSubtitle behind kc_subtitle: its times, and
 * rectangles that are images or text. */
#define KC_SUBTITLE_BITMAP 1
#define KC_SUBTITLE_TEXT 2
#define KC_SUBTITLE_ASS 3

/* Ownership. Opens a decoder for subtitle stream `stream_index` of ctx into *out, a context the
 * caller frees with ffkmp_codecctx_free. Its packet time base is the stream's, so decoded times
 * are exact. A NULL out or context, an index outside the context or a stream that is not a
 * subtitle stream is refused with AVERROR(EINVAL); a codec this build cannot decode with
 * AVERROR_DECODER_NOT_FOUND. *out is NULL on every failure. */
KC_API int ffkmp_subtitle_decoder_open(kc_fmt_ctx *ctx, int stream_index, kc_codec_ctx **out);

/* Ownership. Decodes packet p into *out, a subtitle the caller frees with ffkmp_subtitle_free.
 * *out is NULL when the packet completed no subtitle, which is not an error. A NULL p is the drain,
 * since 3.19: *out is then what the decoder still holds at the end of the stream, which only a
 * decoder that delays its output, such as CEA-608 closed captions, can hold, and the decoder needs
 * ffkmp_codecctx_flush before it decodes again. A NULL c or out is refused with AVERROR(EINVAL). */
KC_API int ffkmp_subtitle_decode(kc_codec_ctx *c, const kc_packet *p, kc_subtitle **out);

/* When the subtitle starts and ends, in microseconds on the stream's own timeline. Either is
 * INT64_MIN when it is not known: the start when the packet had no timestamp, the end when the
 * stream does not say, as with Blu-ray subtitles. NULL arguments are refused with AVERROR(EINVAL). */
KC_API int ffkmp_subtitle_times(const kc_subtitle *s, int64_t *start_us, int64_t *end_us);

/* How many rectangles the subtitle has; 0 for NULL or a subtitle that clears the screen. */
KC_API int ffkmp_subtitle_rect_count(const kc_subtitle *s);

/* Rectangle i: its KC_SUBTITLE_* type, its position and size in canvas pixels, and 1 in forced
 * when the stream marks it forced. A NULL argument or an index outside the subtitle is refused
 * with AVERROR(EINVAL). */
KC_API int ffkmp_subtitle_rect(const kc_subtitle *s, int i, int *type, int *x, int *y, int *w, int *h,
                               int *forced);

/* Converts image rectangle i to premultiplied RGBA in dst, which holds dst_size bytes and needs
 * w * h * 4 of them, rows top to bottom with no padding. A palette index outside the palette is
 * transparent. A NULL argument, an index outside the subtitle, a rectangle that is not an image or
 * a dst that is too small is refused with AVERROR(EINVAL). */
KC_API int ffkmp_subtitle_rect_rgba(const kc_subtitle *s, int i, uint8_t *dst, int dst_size);

/* The text of text or ASS rectangle i, owned by the subtitle; NULL for an image rectangle, an
 * index outside the subtitle or NULL. */
KC_API const char *ffkmp_subtitle_rect_text(const kc_subtitle *s, int i);

/* Ownership. Frees *s with every rectangle in it and writes NULL through the pointer; safe on
 * NULL either way. */
KC_API void ffkmp_subtitle_free(kc_subtitle **s);

/* Subtitle conversion: the packets of one text subtitle stream, decoded and encoded again with
 * another text codec, such as SubRip to mov_text for an MP4. */
typedef struct kc_subtitle_converter kc_subtitle_converter;

/* Ownership. Opens a converter for subtitle stream `stream_index` of ctx into *out, which the
 * caller frees with ffkmp_subtitle_converter_free. `codec` is the output codec's name, such as
 * "mov_text". The encoder gets the decoder's ASS header, as FFmpeg's own tool gives it, and its
 * parameters are written into out_par, the output stream's. An image subtitle stream has no text
 * to encode and is refused with AVERROR_PATCHWELCOME. A codec that is not a text subtitle codec is
 * refused with AVERROR(EINVAL), and one this build cannot encode with AVERROR_ENCODER_NOT_FOUND.
 * NULL arguments and an index outside ctx are refused with AVERROR(EINVAL). *out is NULL on every
 * failure. */
KC_API int ffkmp_subtitle_converter_open(kc_fmt_ctx *ctx, int stream_index, const char *codec,
                                         kc_codec_par *out_par, kc_subtitle_converter **out);

/* Decodes packet `in` and encodes the subtitle it completes into `out`, replacing what out held.
 * out's timestamps and duration are on the input stream's time base, so it writes like a copied
 * packet of that stream. Returns 1 when out holds a packet, 0 when `in` completed no subtitle or one
 * with no text, and a negative AVERROR on failure. NULL arguments are refused with AVERROR(EINVAL). */
KC_API int ffkmp_subtitle_converter_convert(kc_subtitle_converter *c, const kc_packet *in, kc_packet *out);

/* Ownership. Frees *c and writes NULL through the pointer; safe on NULL either way. */
KC_API void ffkmp_subtitle_converter_free(kc_subtitle_converter **c);

/* An interrupt cell a caller creates BEFORE an open, so another thread can stop the open while
 * it runs: ffkmp_fmt_open_input2 and ffkmp_fmt_open_input_io poll it instead of allocating a
 * cell of their own, and the context they return keeps polling it. No close ever frees it. The
 * caller frees it with ffkmp_interrupt_free once every context using it is closed, or after an
 * open that failed.
 */
typedef struct kc_interrupt kc_interrupt;

/* Ownership. A new cell, not raised, that the caller owns; NULL when the identity gate refused
 * this build or allocation failed. */
KC_API kc_interrupt *ffkmp_interrupt_new(void);

/* Raises cell, one-way. Safe from any thread while cell is live; no-op on NULL. */
KC_API void ffkmp_interrupt_raise(kc_interrupt *cell);

/* Ownership. Frees *cell and writes NULL through the pointer; safe on NULL either way. */
KC_API void ffkmp_interrupt_free(kc_interrupt **cell);

/* Like ffkmp_fmt_open_input, with n option pairs applied between allocation and open, which
 * is the only moment pre-open options (probesize, fflags, format forcing) can act. Ownership
 * of *out matches ffkmp_fmt_open_input exactly. keys/values must each hold n non-NULL strings;
 * n may be 0 with NULL arrays. When unused is non-NULL it receives the dictionary of pairs
 * FFmpeg did NOT consume (possibly NULL when all were), which the caller OWNS and releases
 * with ffkmp_dict_free after walking it with ffkmp_dict_get, so an ignored option is a named
 * key, never a mystery. interrupt may be NULL; when it is not, the open and the context poll
 * that cell (see kc_interrupt), and an already raised cell fails the open with AVERROR_EXIT
 * before anything is read. NULL out or path, negative n, or a NULL entry inside the arrays is
 * refused with AVERROR(EINVAL).
 *
 * One key is KiteFFmpeg's own and never reaches FFmpeg: "kiteffmpeg_input_format" names the
 * demuxer to use, as the command line's -f does, so the open does not probe. That is what opens
 * headerless input such as s16le or rawvideo. A name this build does not carry fails the open
 * with AVERROR_DEMUXER_NOT_FOUND. ffkmp_fmt_open_input_io honours the same key.
 *
 * One key is refused with AVERROR(EINVAL): "trust_io_open", the HLS option the trust_io_open
 * patch adds. Only ffkmp_fmt_open_input_io2 sets it, together with the io_open that decides every
 * URL. Set anywhere else, it would let a playlist open any URL through FFmpeg's own protocols.
 */
KC_API int  ffkmp_fmt_open_input2(kc_fmt_ctx **out, const char *path,
                                  const char *const *keys, const char *const *values,
                                  int n, kc_dict **unused, kc_interrupt *interrupt);

/* Ownership. Releases a dictionary ffkmp_fmt_open_input2 handed over, and only such a
 * dictionary: the metadata accessors return BORROWED dictionaries this must never touch.
 * Safe on NULL and on an already-released pointer; writes NULL through dict either way.
 */
KC_API void ffkmp_dict_free(kc_dict **dict);

/* The custom AVIO bridge: demuxing whose BYTES come from the
 * caller instead of a path. This is the door torrent clients, HTTP clients with their own auth,
 * encrypted stores and caches walk through.
 *
 * read_fn contract: fill buf with at most len bytes and return how many (> 0). It must BLOCK
 * until at least one byte exists. At end of stream return KC_IO_EOF; on any failure return
 * KC_IO_ERR. A count above len is refused as an I/O error. It is called from whatever thread
 * drives the demuxer, one call at a time.
 *
 * seek_fn contract: move the cursor to offset (whence is SEEK_SET/SEEK_CUR/SEEK_END) and
 * return the NEW absolute position, or KC_IO_ERR. A NULL seek_fn declares the stream
 * unseekable; AVSEEK_SIZE never reaches it because size below answers that probe.
 */
#define KC_IO_EOF (-1)
#define KC_IO_ERR (-2)
typedef int     (*kc_io_read_fn)(void *opaque, unsigned char *buf, int len);
typedef int64_t (*kc_io_seek_fn)(void *opaque, int64_t offset, int whence);

/* Ownership. On success *out is a new kc_fmt_ctx the caller owns and must release with
 * ffkmp_fmt_close_input_io and NOTHING ELSE: this open installs a custom AVIOContext whose
 * buffer and bridge state only that close knows how to free. opaque must stay valid until
 * that close returns. size is the total byte length, or a negative value when unknown (a
 * live stream). Option pairs and interrupt behave exactly like ffkmp_fmt_open_input2, unused
 * included; the bridge checks the interrupt cell before every read and seek. NULL out or
 * read_fn, negative n, or a NULL entry inside the arrays is refused with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_open_input_io(kc_fmt_ctx **out,
                                    void *opaque, kc_io_read_fn read_fn, kc_io_seek_fn seek_fn,
                                    int64_t size,
                                    const char *const *keys, const char *const *values,
                                    int n, kc_dict **unused, kc_interrupt *interrupt);

/* What an open_fn returns when the caller declines a URL. It becomes AVERROR(EACCES). */
#define KC_IO_REFUSED (-3)

/* The nested opener: it serves the other URLs a demuxer opens while it reads, such as the variant
 * playlists, segments and keys that an HLS playlist names.
 *
 * open_fn gets an absolute URL. To serve it, it sets *source to the caller's state for those
 * bytes, *size to their length (negative when unknown) and *seekable to 1 or 0, and returns 0.
 * To decline it, it returns KC_IO_REFUSED; any other negative value is a failure (AVERROR(EIO)).
 * The bytes are then read through read_fn and seek_fn, with *source as their opaque and the same
 * contracts as the custom AVIO bridge above. A NULL seek_fn makes every nested source unseekable.
 * close_fn releases a *source that open_fn produced, exactly once, either when FFmpeg is done with
 * it or at the paired close. Every call runs on the thread that drives the demuxer.
 *
 * location_fn, which may be NULL, says where a source's bytes came from when that is not the URL
 * open_fn was given, as after an HTTP redirect. It is called once, right after open_fn serves a
 * URL. It writes the address as UTF-8 into buf, NUL-terminated, when it fits in cap bytes, and
 * returns the address's length without the NUL, or 0 when the bytes came from the URL asked for.
 * A return of cap or more is called again with a buffer that fits, and a negative return fails
 * that URL (AVERROR(EIO)). FFmpeg reads the address as the source's "location" option, as it reads
 * the location of its own http protocol, so the HLS demuxer, and the DASH one in a build that has
 * it, resolve the addresses inside a redirected playlist or manifest against it. */
typedef struct kc_io_opener {
    void *opaque;
    int  (*open_fn)(void *opaque, const char *url, void **source, int64_t *size, int *seekable);
    kc_io_read_fn read_fn;
    kc_io_seek_fn seek_fn;
    void (*close_fn)(void *opaque, void *source);
    int  (*location_fn)(void *opaque, void *source, char *buf, int cap);
} kc_io_opener;

/* Tags a caller's bytes brought, such as the song an internet radio station names in a title block
 * between its audio bytes (#168). A tags_fn is asked after every read_fn call that returned bytes,
 * with the same opaque, before FFmpeg reads any of them. It hands each tag those bytes brought to
 * ffkmp_io_tag with the tags it was given, and returns 0, or a negative value to fail that read
 * (AVERROR(EIO)). The tags belong at the first byte of that read: FFmpeg's demuxer reads them as the
 * input's "metadata" option after the next packet, merges them into the context's tags and raises
 * AVFMT_EVENT_FLAG_METADATA_UPDATED, exactly as it does with the titles its own http reads, so a
 * caller that stops each read at the byte where its next tags belong places them there. Tags that
 * the header's reads brought are already in the context's tags when the open returns. */
typedef struct kc_io_tags kc_io_tags;
typedef int (*kc_io_tags_fn)(void *opaque, kc_io_tags *tags);

/* Adds one tag to those the current read brought; a later one with the same key replaces it. Valid
 * only inside a tags_fn, with the tags it was given. 0, or AVERROR(EINVAL) for NULL tags, a NULL or
 * empty key or a NULL value, or AVERROR(ENOMEM). */
KC_API int  ffkmp_io_tag(kc_io_tags *tags, const char *key, const char *value);

/* Ownership as ffkmp_fmt_open_input_io, whose paired close this shares. Five additions, each of
 * which may be NULL:
 *
 * tags_fn hands FFmpeg the tags the bytes brought, as kc_io_tags_fn describes. Only the input asks
 * it: FFmpeg reads no tags from a nested source.
 *
 * url names the bytes. The probe matches it as it matches a file name, and relative URLs inside
 * the media resolve against it. It is never opened to read the input itself.
 *
 * location is where the bytes came from when that is not url, as after an HTTP redirect. The
 * input answers it as its "location" option, as a nested source answers its location_fn, so the
 * HLS demuxer resolves relative URLs against it while url still names the input. An empty
 * location is the same as NULL.
 *
 * mime_type is the type the bytes arrived with. The probe weighs it as it weighs an HTTP
 * Content-Type, so an HLS playlist whose url has no .m3u8 name opens with an HLS MIME type.
 *
 * opener serves the nested URLs, as kc_io_opener describes. The struct is copied, so it may live
 * on the caller's stack, but its opaque must stay valid until the paired close returns. With an
 * opener, the HLS demuxer hands every URL to it unchecked (the trust_io_open patch), except a
 * data: URL, which FFmpeg's data protocol still reads. An AES-128 segment reaches the opener as its
 * plain URL, and this layer decrypts it. The segment extension check still runs. When the linked
 * FFmpeg lacks the trust_io_open patch, the open is refused with AVERROR(ENOSYS); see
 * ffkmp_fmt_nested_io_available. An opener with a NULL open_fn, read_fn or close_fn is refused
 * with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_open_input_io2(kc_fmt_ctx **out,
                                     void *opaque, kc_io_read_fn read_fn, kc_io_seek_fn seek_fn,
                                     kc_io_tags_fn tags_fn,
                                     int64_t size, const char *url, const char *location,
                                     const char *mime_type, const kc_io_opener *opener,
                                     const char *const *keys, const char *const *values,
                                     int n, kc_dict **unused, kc_interrupt *interrupt);

/* 1 when ffkmp_fmt_open_input_io2 can take an opener: the linked FFmpeg's HLS demuxer carries the
 * trust_io_open patch, or the build has no HLS demuxer at all. 0 for a tree built without the
 * patch, and when the identity gate refused this build. */
KC_API int  ffkmp_fmt_nested_io_available(void);

/* Ownership. The one close for ffkmp_fmt_open_input_io and ffkmp_fmt_open_input_io2 contexts:
 * closes the demuxer, then frees the custom AVIOContext, its buffer and the bridge state, and
 * writes NULL through ctx. A nested source that FFmpeg left open is released here through the
 * opener's close_fn. Safe on NULL and on an already-NULL pointer. Using ffkmp_fmt_close_input on a
 * custom-io context leaks the AVIO state; using this on a path-opened context is refused
 * by the absence of the bridge marker and falls back to the plain close.
 */
KC_API void ffkmp_fmt_close_input_io(kc_fmt_ctx **ctx);

/* Requests that every current and future blocking call on this input context
 * return AVERROR_EXIT: FFmpeg polls the interrupt seam at the top of its blocking loops, so
 * a read or seek already in flight returns promptly and later calls fail fast. One-way by
 * design; there is no clear. The context stays owned and its paired close remains both legal
 * and required. This is the ONE call that may run from another thread while a read or seek
 * is blocked on the same context; it must still never run concurrently with, or after, the
 * close. No-op on NULL and on a context this layer did not open. */
KC_API void ffkmp_fmt_interrupt(kc_fmt_ctx *ctx);

/* The opaque the caller gave ffkmp_fmt_open_input_io or ffkmp_fmt_open_input_io2, or NULL when
 * ctx is NULL, was not opened by either call, or the bridge marker is absent. Callers that park per-open state
 * behind opaque (the JNI adapter's callback refs) recover it here BEFORE the close frees
 * the bridge. Borrowed; never freed by the caller through this.
 */
KC_API void *ffkmp_fmt_io_opaque(kc_fmt_ctx *ctx);

/* The custom output bridge: muxing whose bytes go to the caller instead of a path.
 *
 * write_fn contract: take all len bytes at the current position and return 0, or KC_IO_ERR on
 * any failure, which fails the muxer operation that wrote them. It is called from whatever
 * thread drives the muxer, one call at a time, and blocking in it holds the muxer.
 *
 * seek_fn contract: move the position to offset, which is always absolute (whence SEEK_SET),
 * and return it, or KC_IO_ERR. A NULL seek_fn declares the output unseekable, and a muxer that
 * has to go back then refuses to write its header.
 */
typedef int (*kc_io_write_fn)(void *opaque, const unsigned char *buf, int len);

/* Ownership. On success *out is a new output context the caller owns and must release with
 * ffkmp_fmt_free_output_io and NOTHING ELSE: its AVIOContext is the bridge's, so no path is ever
 * opened for it and ffkmp_fmt_io_open must not be called. opaque must stay valid until that free
 * returns. A NULL out, format or write_fn, or an empty format, is refused with AVERROR(EINVAL);
 * an unknown format with FFmpeg's own error. *out is NULL on every failure. */
KC_API int ffkmp_fmt_alloc_output_io(kc_fmt_ctx **out, const char *format,
                                     void *opaque, kc_io_write_fn write_fn, kc_io_seek_fn seek_fn);

/* Ownership. The one free for ffkmp_fmt_alloc_output_io contexts: hands over the bytes still
 * buffered, frees the bridge and the context, and writes NULL through ctx. Returns the first
 * write error the bridge met, or 0. Safe on NULL and on an already-NULL pointer. */
KC_API int ffkmp_fmt_free_output_io(kc_fmt_ctx **ctx);

/* The opaque the caller gave ffkmp_fmt_alloc_output_io, or NULL for any other context. Borrowed;
 * the JNI adapter recovers its callback state here before the free. */
KC_API void *ffkmp_fmt_output_io_opaque(kc_fmt_ctx *ctx);

/* The chapter table. count answers AVERROR(EINVAL) on NULL; get writes the chapter's id
 * and its bounds rescaled to microseconds, refusing NULL outputs and out-of-range indices.
 * The metadata accessor returns a borrowed dictionary owned by the context (NULL on any bad
 * argument), for the standing ffkmp_dict_get iteration; the caller frees nothing.
 */
KC_API int      ffkmp_fmt_chapter_count(const kc_fmt_ctx *ctx);
KC_API int      ffkmp_fmt_chapter_get(const kc_fmt_ctx *ctx, int index,
                                      int64_t *out_id, int64_t *out_start_us, int64_t *out_end_us);
KC_API kc_dict* ffkmp_fmt_chapter_metadata(const kc_fmt_ctx *ctx, int index);

/* The programme table (#148), read the same way. get writes FFmpeg's id for the programme, the
 * programme number the container states, 0 when it states none, and how many streams the
 * programme holds; stream answers the stream index at one position of it. Both refuse a NULL
 * context, NULL outputs and out-of-range indices or positions with AVERROR(EINVAL). The metadata
 * accessor returns a borrowed dictionary owned by the context, NULL on any bad argument.
 */
KC_API int      ffkmp_fmt_program_count(const kc_fmt_ctx *ctx);
KC_API int      ffkmp_fmt_program_get(const kc_fmt_ctx *ctx, int index,
                                      int *out_id, int *out_number, int *out_stream_count);
KC_API int      ffkmp_fmt_program_stream(const kc_fmt_ctx *ctx, int index, int position);
KC_API kc_dict* ffkmp_fmt_program_metadata(const kc_fmt_ctx *ctx, int index);

/* A number that changes when the stream count or the programme table changes (#151): each
 * programme's id, number, stream indexes or tags. A live transport stream changes them while it
 * reads, and FFmpeg raises no flag for it, so a reader compares this after every read and reads the
 * tables again only when it moved. It is a 64-bit FNV-1a fingerprint of those values, so two
 * different tables answer the same number with a chance of one in 2^64. NULL answers 0.
 */
KC_API int64_t  ffkmp_fmt_layout_stamp(const kc_fmt_ctx *ctx);

/* Ownership. Allocates per stream parsing state, and may probe and buffer packets. All of
 * it belongs to the context and is released when the context is closed. Nothing becomes
 * the caller's. A NULL context is refused with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_find_stream_info(kc_fmt_ctx *c);

/* Arguments. A NULL context is refused with AVERROR(EINVAL). stream_index -1 means any
 * stream; 0 to nb_streams-1 seeks in that stream's time base; every other value is refused
 * with AVERROR(EINVAL) instead of indexing streams[] out of range.
 *
 * Landing. A backward seek to the last keyframe that shows at or before micros, in
 * stream_index or, for -1, FFmpeg's default stream, when that is a video stream that is on.
 * FFmpeg finds the keyframe by when it decodes, so the seek reads on to it and aims earlier
 * when it shows too late; ffkmp_fmt_read_frame hands out what it read first (#155).
 */
KC_API int  ffkmp_fmt_seek_micros(kc_fmt_ctx *ctx, int stream_index, int64_t micros);

/* Pause and play (#136). read_pause tells the server of a live input that the caller has stopped
 * reading, through av_read_pause, and read_play tells it to play on, through av_read_play. Each
 * answers 1 when the input has a notion of pausing and FFmpeg carried the call out, 0 when it has
 * none, which FFmpeg answers with AVERROR(ENOSYS) and which is every input but an RTSP stream and
 * an rtmp:// input, and a negative AVERROR when the server or the connection refused. A NULL
 * context is refused with AVERROR(EINVAL).
 *
 * read_pause on an RTSP stream that is not playing sends the server FFmpeg's keepalive once half
 * the session timeout has passed since the last request, and nothing before then, so pausing again
 * every second or so keeps a paused session alive. That needs the FFmpeg patch
 * 0012-rtsp-keep-a-paused-session-alive.patch; without it the call sends nothing and answers 1.
 * read_play on an RTSP stream that is playing asks the server to play again from the last seek
 * target, so call it only after a pause.
 */
KC_API int  ffkmp_fmt_read_pause(kc_fmt_ctx *ctx);
KC_API int  ffkmp_fmt_read_play(kc_fmt_ctx *ctx);

/*
 * Tags that change during playback (#135). A read that applies new container tags raises a flag
 * on the context, and one that applies new tags to a stream raises a flag on that stream, as a
 * station's title, an ID3 tag between ADTS frames, a chained Ogg's next song or a timed ID3 packet
 * do, and FFmpeg leaves lowering them to the caller. This answers which were up, KC_TAGS_CONTAINER
 * for the context and KC_TAGS_STREAM for stream stream_index, and lowers exactly those, leaving
 * every other event bit alone. A stream_index of -1 lowers every stream's flag and answers
 * KC_TAGS_STREAM when any was up, which is how an open forgets the changes it already read. Any
 * other index out of range answers for the context alone. NULL answers 0.
 */
#define KC_TAGS_CONTAINER 1
#define KC_TAGS_STREAM 2
KC_API int  ffkmp_fmt_take_tag_changes(kc_fmt_ctx *ctx, int stream_index);

/* Ownership. On success the packet holds a new reference the caller owns. The packet must be
 * blank on entry, and must be unreferenced before it is filled again, or the reference
 * leaks. On failure the packet is left blank. A NULL context or packet is refused with
 * AVERROR(EINVAL).
 *
 * Order. Hands out the packets a keyframe seek read before it reads anything new, and never a
 * packet of a stream turned off with ffkmp_stream_discard_all, which some demuxers would still
 * hand out. A stream turned on after a seek and before the first read after it makes that seek
 * again, so it starts where the seek lands it.
 */
KC_API int  ffkmp_fmt_read_frame(kc_fmt_ctx *c, kc_packet *p);
KC_API int64_t       ffkmp_fmt_duration(kc_fmt_ctx *c);
/* Where ffkmp_fmt_duration's length came from: FFmpeg's duration_estimation_method, 0 for the
 * streams' timestamps, 1 for a declared duration and 2 for an estimate from the bit rate, which
 * is a guess. Meaningful once stream info was found. -1 for a NULL context. */
KC_API int           ffkmp_fmt_duration_origin(kc_fmt_ctx *c);
KC_API int64_t       ffkmp_fmt_start_time(kc_fmt_ctx *c);
KC_API unsigned      ffkmp_fmt_nb_streams(kc_fmt_ctx *c);
KC_API kc_stream*     ffkmp_fmt_stream(kc_fmt_ctx *c, unsigned i);
KC_API const char*   ffkmp_fmt_iformat_name(kc_fmt_ctx *c);
KC_API kc_dict* ffkmp_fmt_metadata(kc_fmt_ctx *c);

/* Ownership. On success *out is a new muxer context the caller owns and must release with
 * ffkmp_fmt_free_output, never with ffkmp_fmt_close_input. On failure *out is set to NULL. A
 * NULL out pointer, or no nonempty format and no nonempty path, is refused with AVERROR(EINVAL);
 * either selector may be NULL when the other one is present.
 */
KC_API int  ffkmp_fmt_alloc_output2(kc_fmt_ctx **out, const char *path, const char *format);

/* The container's own bit rate estimate in bits per second, or 0 when it has none. */
KC_API int64_t ffkmp_fmt_bit_rate(const kc_fmt_ctx *ctx);

/* Ownership. The option system copies key and value, so neither string is retained and both
 * may be freed immediately. A NULL context is refused with AVERROR(EINVAL), and so is a NULL
 * key, which used to crash inside the option lookup. A NULL value is passed through and
 * av_opt_set itself answers AVERROR(EINVAL) without crashing, measured for a flags option and an
 * int option alike.
 */
KC_API int  ffkmp_fmt_set_opt(kc_fmt_ctx *c, const char *k, const char *v);

/* Ownership. Closes ctx->pb when the format uses a file and pb is open, then frees the
 * context with every stream in it, then writes NULL through ctx. Safe on a pointer that is
 * already NULL. It must not be used on a context from ffkmp_fmt_open_input.
 *
 * Returns the CLOSE result: 0 on success, a negative AVERROR when the final flush or the close
 * of the output file failed. That is where a full disk announces itself, and discarding it
 * reported a truncated file as a written one. The context is freed on every path,
 * so a caller that ignores the result leaks nothing; it only loses the error.
 */
KC_API int ffkmp_fmt_free_output(kc_fmt_ctx **ctx);

/* Ownership. The returned kc_stream belongs to the format context and not to the caller.
 * There is no per stream free, so the pairing rule is different from every other allocating
 * helper here: ffkmp_fmt_free_output releases every stream the context holds. Never free the
 * result, and never use it after the context is gone.
 */
KC_API kc_stream* ffkmp_fmt_new_stream(kc_fmt_ctx *ctx, const kc_codec *codec);

/* Ownership. Opens ctx->pb, which the context then holds. It is a no op, returning 0, for a
 * format carrying AVFMT_NOFILE. There is no separate close: ffkmp_fmt_free_output closes pb
 * exactly when this call opened it, so the pairing is with that free.
 */
KC_API int ffkmp_fmt_io_open(kc_fmt_ctx *ctx, const char *path);
KC_API void ffkmp_fmt_avoid_negative_ts(kc_fmt_ctx *ctx);

/* Ownership. Allocates muxer private state onto the context, released when the context is
 * freed. Once it has succeeded, write the trailer before freeing the context.
 */
KC_API int ffkmp_fmt_write_header(kc_fmt_ctx *ctx);

/* Ownership. Takes over the packet's reference. On success and on failure alike the packet
 * is blank afterwards and must not be unreferenced again. A NULL packet flushes the
 * interleaving queue. A NULL context is refused with AVERROR(EINVAL).
 */
KC_API int ffkmp_fmt_write_frame(kc_fmt_ctx *ctx, kc_packet *p);

/* Ownership. Flushes and releases the packets the muxer had buffered. It does not free the
 * context, so ffkmp_fmt_free_output is still required.
 */
KC_API int ffkmp_fmt_write_trailer(kc_fmt_ctx *ctx);
KC_API int ffkmp_oformat_global_header(kc_fmt_ctx *c);

/* Ownership. Copies key and value into the context's metadata dictionary, which the context
 * owns and its free releases. Neither string is retained.
 */
KC_API int ffkmp_fmt_set_metadata(kc_fmt_ctx *c, const char *key, const char *value);

/* kc_stream */
KC_API int                  ffkmp_stream_index(kc_stream *s);
KC_API kc_codec_par*   ffkmp_stream_codecpar(kc_stream *s);
KC_API int64_t              ffkmp_stream_duration_micros(kc_stream *s);
KC_API int64_t              ffkmp_stream_start_time(kc_stream *s);
KC_API kc_dict*        ffkmp_stream_metadata(kc_stream *s);
KC_API void ffkmp_stream_time_base(kc_stream *s, int *n, int *d);
KC_API void ffkmp_stream_avg_frame_rate(kc_stream *s, int *n, int *d);
KC_API void ffkmp_stream_set_time_base(kc_stream *s, int n, int d);

/* The stream-level shape of one pixel, which Matroska reads instead of the codec parameters'.
 * A NULL stream, a negative numerator or a denominator that is not positive is refused with
 * AVERROR(EINVAL). */
KC_API int ffkmp_stream_set_sample_aspect_ratio(kc_stream *s, int num, int den);

/* The stream's average frame rate, which a muxer writes as the track's frame rate and from which it
 * gives a video packet that carries no duration the length of one frame. An encoder hands back its
 * packets without durations, so an output stream that declares no rate ends on a sample of length
 * zero, which MP4 players drop. A NULL stream or a rate whose terms are not both positive is refused
 * with AVERROR(EINVAL). */
KC_API int ffkmp_stream_set_avg_frame_rate(kc_stream *s, int num, int den);

/* Filter graphs (single-input video / audio) */

/* The def's header removal forces this boolean wrapper before the typed outcome model lands
 * later; a NULL or unknown name returns 0 and a known filter returns 1.
 */
KC_API int ffkmp_filter_exists(const char *name);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the two filter contexts belong to the graph and must never be freed
 * separately. On every failure path the graph is freed inside the call and all three out
 * parameters are left NULL. NULL output slots or a NULL description are refused with
 * AVERROR(EINVAL).
 */
KC_API int ffkmp_graph_build_video(
    kc_filter_graph **out_graph, kc_filter_ctx **out_src, kc_filter_ctx **out_sink,
    const char *description,
    int width, int height, int pix_fmt,
    int tb_num, int tb_den, int fr_num, int fr_den, int sar_num, int sar_den
);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the two filter contexts belong to the graph and must never be freed
 * separately. On every failure path the graph is freed inside the call and all three out
 * parameters are left NULL. NULL output slots are refused with AVERROR(EINVAL), while a NULL
 * description selects `anull`.
 */
KC_API int ffkmp_graph_build_audio(
    kc_filter_graph **out_graph, kc_filter_ctx **out_src, kc_filter_ctx **out_sink,
    const char *description,
    int sample_rate, int sample_fmt, int channels,
    int tb_num, int tb_den,
    /* Pin the graph's output so frames arrive encoder-ready. Pass -1/-1/0 to leave free.
       Implemented by appending an `aformat` filter rather than buffersink options, because the
       option names were renamed across FFmpeg 7→8, the filter-string syntax never changes. */
    int out_sample_fmt, int out_sample_rate, int out_channels,
    /* The input's channel layout and the exact output layout, as native masks. 0 takes the
       default layout for the count on input and pins only the count on output. A negative mask,
       or one whose channel count differs from its count, is refused with AVERROR(EINVAL). */
    int64_t layout_mask, int64_t out_layout_mask
);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the sink and the n source contexts belong to the graph and must never be
 * freed separately. On failure the graph is freed inside the call and *out_graph and
 * *out_sink are NULL, but out_srcs is NOT cleared and its filled entries point into the
 * freed graph. Read out_srcs only when the call returned 0. NULL output slots, a NULL
 * description, nonpositive n or a NULL parameter array are refused with AVERROR(EINVAL).
 */
KC_API int ffkmp_graph_build_video_multi(
    kc_filter_graph **out_graph, kc_filter_ctx **out_srcs, kc_filter_ctx **out_sink,
    const char *description, int n,
    const int *widths, const int *heights, const int *pix_fmts,
    const int *tb_nums, const int *tb_dens,
    const int *fr_nums, const int *fr_dens,
    const int *sar_nums, const int *sar_dens
);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the sink and the n source contexts belong to the graph and must never be
 * freed separately. On failure the graph is freed inside the call and *out_graph and
 * *out_sink are NULL, but out_srcs is NOT cleared and its filled entries point into the
 * freed graph. Read out_srcs only when the call returned 0. NULL output slots, nonpositive n
 * or a NULL parameter array are refused with AVERROR(EINVAL). With one input a NULL or empty
 * description selects `anull`; multiple inputs require an explicit graph.
 */
KC_API int ffkmp_graph_build_audio_multi(
    kc_filter_graph **out_graph, kc_filter_ctx **out_srcs, kc_filter_ctx **out_sink,
    const char *description, int n,
    const int *sample_rates, const int *sample_fmts, const int *channels,
    const int *tb_nums, const int *tb_dens,
    int out_sample_fmt, int out_sample_rate, int out_channels,
    /* Each input's channel layout as a native mask, or NULL; an entry of 0 takes the default
       layout for that input's count. out_layout_mask pins the exact output layout, 0 for none. */
    const int64_t *layout_masks, int64_t out_layout_mask
);

/* Ownership. Frees the graph together with every filter context in it, and writes NULL
 * through g. Every kc_filter_ctx a builder handed out dangles afterwards. Safe on a
 * pointer that is already NULL.
 */
KC_API void ffkmp_graph_free(kc_filter_graph **g);

/* Ownership. Sends the frame with AV_BUFFERSRC_FLAG_KEEP_REF, so the graph takes its own
 * reference and the caller keeps and must still release the frame it passed in. Without
 * that flag the frame would be consumed, which is why the flag is part of the contract. A
 * NULL source is refused with AVERROR(EINVAL), while a NULL frame signals EOF.
 */
KC_API int  ffkmp_graph_send(kc_filter_ctx *src, kc_frame *frame);

/* Ownership. On success the frame holds a new reference the caller owns. The frame must be
 * blank on entry and must be unreferenced before it is filled again. AVERROR(EAGAIN) and
 * AVERROR_EOF leave it blank and are not failures. A NULL sink or frame is refused with
 * AVERROR(EINVAL).
 */
KC_API int  ffkmp_graph_receive(kc_filter_ctx *sink, kc_frame *frame);

/* How often the graph asked the source for a frame it did not have since its last frame. After
 * a receive that produced nothing, the source with the highest count is the input the graph
 * waits for. 0 for a NULL source.
 */
KC_API int  ffkmp_graph_failed_requests(kc_filter_ctx *src);
KC_API void ffkmp_buffersink_set_frame_size(kc_filter_ctx *sink, unsigned n);
KC_API void ffkmp_buffersink_time_base(kc_filter_ctx *sink, int *n, int *d);

/* Playback additions */
KC_API void ffkmp_codecctx_flush(kc_codec_ctx *c);
KC_API void ffkmp_codecctx_set_threads(kc_codec_ctx *c, int count, int frame_level);
KC_API void ffkmp_codecctx_set_low_delay(kc_codec_ctx *c, int on);
KC_API int ffkmp_avseek_flag_backward(void);
KC_API int ffkmp_avseek_flag_any(void);
/* avformat_seek_file. A window that ends at its target, without AVSEEK_FLAG_BYTE, ANY or FRAME,
 * lands as ffkmp_fmt_seek_micros does, no earlier than min_ts. A stream_index outside -1 to
 * nb_streams-1 is refused with AVERROR(EINVAL).
 */
KC_API int ffkmp_fmt_seek_file(kc_fmt_ctx *ctx, int stream_index,
                                     int64_t min_ts, int64_t ts, int64_t max_ts, int flags);
KC_API int ffkmp_fmt_is_seekable(kc_fmt_ctx *c);
KC_API void ffkmp_stream_discard_all(kc_stream *s);
KC_API void ffkmp_stream_discard_none(kc_stream *s);
KC_API int ffkmp_stream_disposition(kc_stream *s);
KC_API int ffkmp_disposition_default(void);
KC_API int ffkmp_disposition_forced(void);
KC_API int ffkmp_disposition_hearing_impaired(void);
KC_API int ffkmp_disposition_visual_impaired(void);
KC_API int ffkmp_disposition_attached_pic(void);
KC_API int ffkmp_disposition_descriptions(void);
KC_API int ffkmp_disposition_comment(void);
/* The display matrix as a mirror and a turn: a renderer mirrors the picture left to right first,
 * when ffkmp_stream_mirrored answers 1, and then turns it clockwise by
 * ffkmp_stream_rotation_degrees, 0 to 359. A stream without a usable matrix answers 0 to both. */
KC_API int ffkmp_stream_rotation_degrees(kc_stream *s);
KC_API int ffkmp_stream_mirrored(kc_stream *s);
/* The container's crop of a stream's pictures, from a Matroska track's PixelCrop elements or an MP4
 * track's clean aperture, which FFmpeg reads and does not apply: out[4] gets the rows at the top,
 * the rows at the bottom, the columns at the left and the columns at the right that are not part
 * of the image. Returns 1 when the stream has one, 0 when it has none or FFmpeg is older than 7.1,
 * which does not read it, and AVERROR(EINVAL) for a NULL argument. */
KC_API int ffkmp_codecpar_frame_cropping(kc_codec_par *p, int *out);
/* How a stream's pictures map onto a sphere, from MP4's spherical video boxes, Apple's video
 * extension box or a Matroska Projection element, which FFmpeg reads and does not apply. out[9]
 * gets FFmpeg's AVSphericalProjection value, the yaw, pitch and roll as 16.16 fixed point, then the
 * bits of four unsigned 0.32 fixed-point bounds, left, top, right and bottom, and of the unsigned
 * cube map padding. Returns 1 when the stream has one, 0 when it has none or its projection is one
 * this layer does not know, and AVERROR(EINVAL) for a NULL argument. */
KC_API int ffkmp_codecpar_spherical(kc_codec_par *p, int *out);
/* How a stream's pictures hold two eyes' views, from MP4's stereoscopic box, Apple's video
 * extension box or a Matroska StereoMode, which FFmpeg reads and does not apply. out[9] gets
 * FFmpeg's AVStereo3DType, 1 when the views are inverted, the AVStereo3DView, the
 * AVStereo3DPrimaryEye, the bits of the unsigned baseline in micrometres, and the numerator and
 * denominator of the horizontal disparity adjustment and of the horizontal field of view in
 * degrees. Against an FFmpeg older than 7.1, which has none of the last four, they read as none,
 * zero, 0/1 and 0/1. Returns 1 when the stream has one, 0 when it has none or a value this layer
 * does not know, and AVERROR(EINVAL) for a NULL argument. */
KC_API int ffkmp_codecpar_stereo3d(kc_codec_par *p, int *out);
/* The samples per channel an audio packet of frame_bytes bytes holds, as FFmpeg reads it from the
 * codec (av_get_audio_frame_duration2): from the bytes for PCM and the codecs that code each sample
 * in a fixed number of bits, and from the codec's fixed frame size for the rest, 1024 for AAC and
 * 1152 for MP2 and MP3. 0 when FFmpeg cannot tell, as for Vorbis, whose packets vary, and for a
 * NULL argument. */
KC_API int ffkmp_codecpar_audio_frame_samples(kc_codec_par *p, int frame_bytes);

/* Ownership. Moves every reference from src to dst and leaves src blank, so exactly one of
 * the two owns the data afterwards. dst must be blank on entry. Neither packet is freed,
 * and a NULL on either side makes the call do nothing.
 */
KC_API void ffkmp_packet_move_ref(kc_packet *dst, kc_packet *src);
KC_API int64_t ffkmp_packet_pos(kc_packet *p);
KC_API int ffkmp_frame_color_range(kc_frame *f);
KC_API int ffkmp_frame_colorspace(kc_frame *f);
KC_API int ffkmp_frame_color_primaries(kc_frame *f);
KC_API int ffkmp_frame_color_trc(kc_frame *f);
KC_API int ffkmp_frame_chroma_location(kc_frame *f);
KC_API int ffkmp_frame_is_keyframe(kc_frame *f);
KC_API void ffkmp_frame_sample_aspect_ratio(kc_frame *f, int *n, int *d);
KC_API int64_t ffkmp_frame_ch_layout_mask(kc_frame *f);
KC_API int64_t ffkmp_codecpar_ch_layout_mask(kc_codec_par *p);

/* The mastering display a stream or a frame declares, in the layout described at
 * ffkmp_codecctx_add_mastering_display: q receives KC_HDR_MASTERING_INTS ints and flags which
 * halves are present. They return 1 when there is one, 0 when there is none, and AVERROR(EINVAL)
 * for a NULL argument. */
KC_API int ffkmp_codecpar_mastering_display(kc_codec_par *p, int *q, int *flags);
KC_API int ffkmp_frame_mastering_display(kc_frame *f, int *q, int *flags);

/* The content light level a stream or a frame declares, with the same returns. */
KC_API int ffkmp_codecpar_content_light(kc_codec_par *p, int *max_cll, int *max_fall);
KC_API int ffkmp_frame_content_light(kc_frame *f, int *max_cll, int *max_fall);

/* The closed captions a video frame carries: the cc_data of its ATSC A/53 part 4 side data, three
 * bytes per caption pair. Returns their byte count, and 0 when the frame carries none. Copies them
 * into dst when dst is not NULL; a dst_size below the count returns AVERROR(EINVAL) and copies
 * nothing. A NULL frame returns AVERROR(EINVAL). */
KC_API int ffkmp_frame_a53_cc(kc_frame *f, uint8_t *dst, int dst_size);
KC_API uint8_t* ffkmp_frame_plane(kc_frame *f, int p);
KC_API int ffkmp_frame_plane_count(kc_frame *f);
KC_API int ffkmp_frame_plane_height(kc_frame *f, int p);
KC_API void* ffkmp_frame_hw_surface(kc_frame *f);
KC_API int ffkmp_frame_is_hardware(kc_frame *f);

/* Hardware decode. VideoToolbox is an hwaccel behind the ordinary
 * decoders, so there is no decoder name to select: ffkmp_codecctx_use_videotoolbox attaches a
 * device context between alloc and open (the pre-open window) and installs the format
 * negotiation that prefers hardware output and falls back to the default negotiation when the
 * offer is withdrawn. A build without VideoToolbox answers AVERROR(ENOSYS) at attach time,
 * from FFmpeg's own list of the device types it carries. ffkmp_frame_hw_download copies a hardware frame's pixels and
 * presentation properties into a blank allocated dst and leaves dst blank on failure; a
 * software src is refused, because reaching the download on one means the caller's bookkeeping
 * is wrong. */
KC_API int ffkmp_codecctx_use_videotoolbox(kc_codec_ctx *c);
/* ffkmp_codecctx_use_d3d11va is the Windows twin: a Direct3D 11 device attached the same way,
 * preferring the hardware frame format the d3d11va2 hwaccels produce. Every build but Windows
 * answers AVERROR(ENOSYS). */
KC_API int ffkmp_codecctx_use_d3d11va(kc_codec_ctx *c);
KC_API int ffkmp_frame_hw_download(kc_frame *src, kc_frame *dst);

/* Dolby Vision.
 *
 * ffkmp_codecpar_dovi_config reads the configuration record a stream's codec parameters carry into
 * out[8]: the version's major and minor, the profile, the level, whether an RPU, an enhancement
 * layer and a base layer are present (0 or 1), and the base layer's signal compatibility id.
 *
 * ffkmp_frame_dovi_metadata reads the RPU a decoder attached to a frame into out[8]: the base
 * layer's bit depth, whether the composition uses an enhancement layer's residual (0 or 1), the
 * source's lowest and highest level, whether level 1 is present (0 or 1), and level 1's lowest,
 * average and highest level. Levels are 12-bit PQ codes. Built against an FFmpeg older than 7.1,
 * which exports no extension block, level 1 is never present.
 *
 * Both return 1 when there is one, 0 when there is none, and AVERROR(EINVAL) for a NULL argument.
 *
 * ffkmp_frame_dovi_rpu reads the whole RPU a decoder attached to a frame into out, whose capacity
 * must be exactly KC_DOVI_RPU_INTS ints. A 64-bit number takes two ints, the high half first, and a
 * rational two, the numerator first. In order: the fifteen fields of AVDOVIRpuDataHeader from
 * rpu_type to disable_residual_flag; the mapping's vdr_rpu_id, mapping_color_space,
 * mapping_chroma_format_idc, nlq_method_idc, num_x_partitions and num_y_partitions; each of the
 * three curves as num_pivots, nine pivots and eight pieces, each piece as mapping_idc, poly_order,
 * three poly_coef, mmr_order, mmr_constant and three rows of seven mmr_coef; each component's
 * nlq_offset, vdr_in_max, linear_deadzone_slope and linear_deadzone_threshold; whether this FFmpeg
 * exports nlq_pivots (0 or 1), then the two pivots; and the colour's dm_metadata_id,
 * scene_refresh_flag, ycc_to_rgb_matrix, ycc_to_rgb_offset, rgb_to_lms_matrix, signal_eotf, its
 * three parameters (the last as its 32 bits), signal_bit_depth, signal_color_space,
 * signal_chroma_format, signal_full_range_flag, source_min_pq, source_max_pq and source_diagonal.
 * Every int that the counts, the mapping kinds and the orders leave unused is 0, and so are the
 * inverse quantization and its pivots when nlq_method_idc is AV_DOVI_NLQ_NONE. It returns 1, 0 when
 * the frame carries none, AVERROR(EINVAL) for a NULL argument or another capacity, and
 * AVERROR_INVALIDDATA for an RPU outside the bounds FFmpeg's parser keeps: a curve of fewer than two
 * or more than nine pivots, a piece that is neither a polynomial of order 1 or 2 nor an MMR of order
 * 1 to 3, an nlq_method_idc that is neither none nor linear dead zone, or a matrix entry or offset
 * whose denominator is 0.
 *
 * ffkmp_frame_dovi_compose_prepare gives dst, a blank frame, the picture that src's composition
 * fills: 10-bit 4:2:0 at src's size, BT.2020, PQ, limited range, chroma sited left, src's properties,
 * the source's range as a mastering display's luminance, level 6's light levels when present, and
 * no Dolby Vision side data. It returns 1, or 0 when src carries no Dolby Vision metadata and dst is
 * left blank. A NULL, a hardware src, a dst that holds data, or an RPU whose curves are malformed
 * answers AVERROR(EINVAL), and a src that is not 4:2:0 little-endian YUV of 8 to 16 bits answers
 * AVERROR_PATCHWELCOME; dst is left blank on every failure.
 *
 * ffkmp_frame_dovi_compose_rows composes rows row_start up to row_end of src into the dst that
 * prepare gave a picture to. A band starts on an even row and ends on an even row or at the height.
 * Bands that do not overlap may run at the same time on different threads. It returns 0, or
 * AVERROR(EINVAL) for a band out of range, a dst that prepare did not make for src, or a src that
 * lost its metadata. */
KC_API int ffkmp_codecpar_dovi_config(kc_codec_par *p, int *out);
KC_API int ffkmp_frame_dovi_metadata(kc_frame *f, int *out);
#define KC_DOVI_RPU_INTS 1402
KC_API int ffkmp_frame_dovi_rpu(kc_frame *f, int *out, int capacity);
KC_API int ffkmp_frame_dovi_compose_prepare(kc_frame *src, kc_frame *dst);
KC_API int ffkmp_frame_dovi_compose_rows(kc_frame *src, kc_frame *dst, int row_start, int row_end);

#endif /* KITECODEC_HELPERS_H */
