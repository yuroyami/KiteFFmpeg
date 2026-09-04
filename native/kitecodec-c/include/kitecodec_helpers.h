/* Ordinary maintained source since the interlude. Lifted at B1.3 from the def body of
 * kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def as it stood at revision 5364329, and
 * proved byte for byte faithful to it one last time at 2b4287f; the full verify-lift.sh output
 * with all eleven digests is recorded in that commit, and the proof
 * script itself is retired because an anchor no revision can replace forbids every future edit.
 * Edit this file like any other C file. Its shape is held by the C suites in every variant, the
 * sanitizers, symbol-audit.sh and the export baseline, not by an extraction proof.
 *
 * Declarations for the exported FFmpeg helper layer. */

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
 * either way. Both pixel formats are validated before swscale sees them: a value outside the
 * enum, a hardware format, or one swscale cannot read or write returns NULL rather than
 * asserting inside libswscale. The colour tags on the result describe the OUTPUT,
 * not the source: an RGB destination is full range with an RGB matrix.
 */
KC_API kc_frame* ffkmp_frame_convert_pixfmt(const kc_frame *src, int dst_fmt);
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
KC_API int64_t   ffkmp_packet_duration(kc_packet *p);
KC_API int       ffkmp_packet_is_keyframe(kc_packet *p);
KC_API void      ffkmp_packet_set_stream_index(kc_packet *p, int i);
KC_API void      ffkmp_packet_set_pts(kc_packet *p, int64_t v);
KC_API void      ffkmp_packet_set_dts(kc_packet *p, int64_t v);
KC_API void      ffkmp_packet_rescale_ts(kc_packet *p, int sn, int sd, int dn, int dd);

/* Ownership. Returns a new kc_packet the caller owns, carrying one more reference to the same
 * compressed payload as the input (av_packet_ref), so it is O(1) over the payload size. The two
 * packets close independently, in either order, each with ffkmp_packet_free. A NULL input is
 * refused with NULL. Allocation or ref failure frees everything this call created and returns
 * NULL. S1.c.1 adds this for the retained-GOP fallback and Packet.copy(); the typed outcome model
 * for packets remains later work, and this wrapper exists because the JVM bridge needs an owned
 * clone it can hand across the boundary. */
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

/* KD-4. Like ffkmp_fmt_open_input, with n option pairs applied between allocation and open,
 * which is the only moment pre-open options (probesize, fflags, format forcing) can act.
 * Ownership of *out matches ffkmp_fmt_open_input exactly. keys/values must each hold n
 * non-NULL strings; n may be 0 with NULL arrays. When unused is non-NULL it receives the
 * dictionary of pairs FFmpeg did NOT consume (possibly NULL when all were), which the caller
 * OWNS and releases with ffkmp_dict_free after walking it with ffkmp_dict_get, so an ignored
 * option is a named key, never a mystery. NULL out or path, negative n, or a NULL entry
 * inside the arrays is refused with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_open_input2(kc_fmt_ctx **out, const char *path,
                                  const char *const *keys, const char *const *values,
                                  int n, kc_dict **unused);

/* Ownership. Releases a dictionary ffkmp_fmt_open_input2 handed over, and only such a
 * dictionary: the metadata accessors return BORROWED dictionaries this must never touch.
 * Safe on NULL and on an already-released pointer; writes NULL through dict either way.
 */
KC_API void ffkmp_dict_free(kc_dict **dict);

/* M1, the custom AVIO bridge: demuxing whose BYTES come from the
 * caller instead of a path. This is the door KiteTorrent, HTTP clients with their own auth,
 * encrypted stores and caches walk through.
 *
 * read_fn contract: fill buf with at most len bytes and return how many (> 0). It must BLOCK
 * until at least one byte exists. At end of stream return KC_IO_EOF; on any failure return
 * KC_IO_ERR. It is called from whatever thread drives the demuxer, one call at a time.
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
 * live stream). Option pairs behave exactly like ffkmp_fmt_open_input2, unused included.
 * NULL out or read_fn, negative n, or a NULL entry inside the arrays is refused with
 * AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_open_input_io(kc_fmt_ctx **out,
                                    void *opaque, kc_io_read_fn read_fn, kc_io_seek_fn seek_fn,
                                    int64_t size,
                                    const char *const *keys, const char *const *values,
                                    int n, kc_dict **unused);

/* Ownership. The one close for ffkmp_fmt_open_input_io contexts: closes the demuxer, then
 * frees the custom AVIOContext, its buffer and the bridge state, and writes NULL through
 * ctx. Safe on NULL and on an already-NULL pointer. Using ffkmp_fmt_close_input on a
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

/* The opaque the caller gave ffkmp_fmt_open_input_io, or NULL when ctx is NULL, was not
 * opened by that call, or the bridge marker is absent. Callers that park per-open state
 * behind opaque (the JNI adapter's callback refs) recover it here BEFORE the close frees
 * the bridge. Borrowed; never freed by the caller through this.
 */
KC_API void *ffkmp_fmt_io_opaque(kc_fmt_ctx *ctx);

/* KD-5. The chapter table. count answers AVERROR(EINVAL) on NULL; get writes the chapter's id
 * and its bounds rescaled to microseconds, refusing NULL outputs and out-of-range indices.
 * The metadata accessor returns a borrowed dictionary owned by the context (NULL on any bad
 * argument), for the standing ffkmp_dict_get iteration; the caller frees nothing.
 */
KC_API int      ffkmp_fmt_chapter_count(const kc_fmt_ctx *ctx);
KC_API int      ffkmp_fmt_chapter_get(const kc_fmt_ctx *ctx, int index,
                                      int64_t *out_id, int64_t *out_start_us, int64_t *out_end_us);
KC_API kc_dict* ffkmp_fmt_chapter_metadata(const kc_fmt_ctx *ctx, int index);

/* Ownership. Allocates per stream parsing state, and may probe and buffer packets. All of
 * it belongs to the context and is released when the context is closed. Nothing becomes
 * the caller's. A NULL context is refused with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_find_stream_info(kc_fmt_ctx *c);

/* Arguments. A NULL context is refused with AVERROR(EINVAL). stream_index -1 means any
 * stream; 0 to nb_streams-1 seeks in that stream's time base; every other value is refused
 * with AVERROR(EINVAL) instead of indexing streams[] out of range (interlude guard, I-12).
 */
KC_API int  ffkmp_fmt_seek_micros(kc_fmt_ctx *ctx, int stream_index, int64_t micros);

/* Ownership. On success the packet holds a new reference the caller owns. The packet must be
 * blank on entry, and must be unreferenced before it is filled again, or the reference
 * leaks. On failure the packet is left blank. A NULL context or packet is refused with
 * AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_read_frame(kc_fmt_ctx *c, kc_packet *p);
KC_API int64_t       ffkmp_fmt_duration(kc_fmt_ctx *c);
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

/* The container's own bit rate estimate in bits per second, or 0 when it has none. Resurrected for
   plan item K2: the always-null containerBitrate stat is what it is for. */
KC_API int64_t ffkmp_fmt_bit_rate(const kc_fmt_ctx *ctx);

/* Ownership. The option system copies key and value, so neither string is retained and both
 * may be freed immediately. A NULL context is refused with AVERROR(EINVAL), and so is a NULL
 * key, which used to crash inside the option lookup (interlude guard, I-12). A NULL value is
 * passed through and av_opt_set itself answers AVERROR(EINVAL) without crashing, measured at
 * the interlude for a flags option and an int option alike.
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
    int out_sample_fmt, int out_sample_rate, int out_channels
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
    int out_sample_fmt, int out_sample_rate, int out_channels
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
KC_API void ffkmp_buffersink_set_frame_size(kc_filter_ctx *sink, unsigned n);
KC_API void ffkmp_buffersink_time_base(kc_filter_ctx *sink, int *n, int *d);

/* Playback additions */
KC_API void ffkmp_codecctx_flush(kc_codec_ctx *c);
KC_API void ffkmp_codecctx_set_threads(kc_codec_ctx *c, int count, int frame_level);
KC_API void ffkmp_codecctx_set_low_delay(kc_codec_ctx *c, int on);
KC_API int ffkmp_avseek_flag_backward(void);
KC_API int ffkmp_avseek_flag_any(void);
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
KC_API int ffkmp_stream_rotation_degrees(kc_stream *s);

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
KC_API uint8_t* ffkmp_frame_plane(kc_frame *f, int p);
KC_API int ffkmp_frame_plane_count(kc_frame *f);
KC_API int ffkmp_frame_plane_height(kc_frame *f, int p);
KC_API void* ffkmp_frame_hw_surface(kc_frame *f);
KC_API int ffkmp_frame_is_hardware(kc_frame *f);

/* Hardware decode, KiteFFmpeg window 3 (S2.a). VideoToolbox is an hwaccel behind the ordinary
 * decoders, so there is no decoder name to select: ffkmp_codecctx_use_videotoolbox attaches a
 * device context between alloc and open (the pre-open window) and installs the format
 * negotiation that prefers hardware output and falls back to the default negotiation when the
 * offer is withdrawn. A build without VideoToolbox answers AVERROR(ENOSYS) at attach time,
 * FFmpeg's own capability answer. ffkmp_frame_hw_download copies a hardware frame's pixels and
 * presentation properties into a blank allocated dst and leaves dst blank on failure; a
 * software src is refused, because reaching the download on one means the caller's bookkeeping
 * is wrong. */
KC_API int ffkmp_codecctx_use_videotoolbox(kc_codec_ctx *c);
KC_API int ffkmp_frame_hw_download(kc_frame *src, kc_frame *dst);

#endif /* KITECODEC_HELPERS_H */
