/* Category unit: frames (methods.def section "frame"). Follows kj_abi.c's canonical pattern. */

#include "kj_internal.h"

#include <stdlib.h>

JNIEXPORT jlong JNICALL kj_frame_alloc(JNIEnv *env, jclass cls)
{
    kc_frame *f = ffkmp_frame_alloc();
    jlong token;
    (void)cls;
    if (f == NULL) { kj_throw_handle(env, "frame allocation failed"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_FRAME, f);
    if (token == 0) ffkmp_frame_free(f);
    return token;
}

JNIEXPORT void JNICALL kj_frame_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_close(token, KJ_KIND_FRAME);
    (void)env; (void)cls;
    ffkmp_frame_free(f);
}

JNIEXPORT jlong JNICALL kj_frame_pts(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jlong)ffkmp_frame_pts(f) : 0;
}

JNIEXPORT jint JNICALL kj_frame_width(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jint)ffkmp_frame_width(f) : 0;
}

JNIEXPORT jint JNICALL kj_frame_height(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jint)ffkmp_frame_height(f) : 0;
}

JNIEXPORT jint JNICALL kj_frame_format(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return f ? (jint)ffkmp_frame_format(f) : -1;
}

JNIEXPORT jboolean JNICALL kj_frame_is_keyframe(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *f = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return (f && ffkmp_frame_is_keyframe(f)) ? JNI_TRUE : JNI_FALSE;
}

/* The safe copied-plane surface a JVM software converter is built on: one exact copy of
 * the frame's tightly packed planes as a byte array. Video frames size through
 * ffkmp_image_get_buffer_size at align 1 (tightly packed is the documented Frame.kt layout);
 * audio frames size through ffkmp_samples_get_buffer_size. */
/* Which frame a byte copy reads or writes, and whether it holds a picture or samples. */
typedef struct kj_frame_bytes {
    kc_frame *frame;
    int video;
} kj_frame_bytes;

static int kj_frame_bytes_out(void *ctx, uint8_t *dst, int32_t len)
{
    const kj_frame_bytes *fb = (const kj_frame_bytes *)ctx;
    return fb->video ? ffkmp_frame_copy_to_buffer(fb->frame, dst, len)
                     : ffkmp_samples_copy_to_buffer(fb->frame, dst, len);
}

static int kj_frame_bytes_in(void *ctx, const uint8_t *src, int32_t len)
{
    const kj_frame_bytes *fb = (const kj_frame_bytes *)ctx;
    return fb->video ? ffkmp_frame_fill_video(fb->frame, src, len)
                     : ffkmp_frame_fill_audio(fb->frame, src, len);
}

/* One copy, straight into the Java array, where a scratch buffer and SetByteArrayRegion made two. */
JNIEXPORT jbyteArray JNICALL kj_frame_copy_planes(JNIEnv *env, jclass cls, jlong token)
{
    kj_frame_bytes fb;
    int size, rc = 0;
    jbyteArray out;
    (void)cls;
    fb.frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    if (fb.frame == NULL) return NULL;
    fb.video = ffkmp_frame_width(fb.frame) > 0;
    size = fb.video ? ffkmp_image_get_buffer_size(ffkmp_frame_format(fb.frame), ffkmp_frame_width(fb.frame),
                                                  ffkmp_frame_height(fb.frame), 1)
                    : ffkmp_samples_get_buffer_size(fb.frame);
    if (size < 0) { kj_throw_ffmpeg(env, size, "frame_copy_planes size"); return NULL; }
    out = kj_bytes_filled_in_place(env, size, kj_frame_bytes_out, &fb, &rc);
    if (out == NULL && rc < 0) kj_throw_ffmpeg(env, rc, "frame_copy_planes copy");
    return out;
}

JNIEXPORT void JNICALL kj_frame_unref(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_unref(frame);
}

JNIEXPORT jlong JNICALL kj_frame_clone(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    kc_frame *copy;
    jlong copy_token;
    (void)cls;
    if (frame == NULL) return 0;
    copy = ffkmp_frame_clone(frame);
    if (copy == NULL) {
        kj_throw_handle(env, "frame clone failed");
        return 0;
    }
    copy_token = kj_handle_put_checked(env, KJ_KIND_FRAME, copy);
    if (copy_token == 0) ffkmp_frame_free(copy);
    return copy_token;
}

JNIEXPORT jlong JNICALL kj_frame_convert(JNIEnv *env, jclass cls, jlong token, jint format)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    kc_frame *converted;
    jlong converted_token;
    (void)cls;
    if (frame == NULL) return 0;
    converted = ffkmp_frame_convert_pixfmt(frame, (int)format);
    if (converted == NULL) {
        kj_throw_handle(env, "frame pixel-format conversion failed");
        return 0;
    }
    converted_token = kj_handle_put_checked(env, KJ_KIND_FRAME, converted);
    if (converted_token == 0) ffkmp_frame_free(converted);
    return converted_token;
}

JNIEXPORT jlong JNICALL kj_frame_duration(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jlong)ffkmp_frame_duration(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_sample_count(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_nb_samples(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_sample_rate(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_sample_rate(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_channels(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_channels(frame) : 0;
}

JNIEXPORT jlong JNICALL kj_frame_channel_layout(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jlong)ffkmp_frame_ch_layout_mask(frame) : 0;
}

JNIEXPORT jintArray JNICALL kj_frame_hdr(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    int q[KC_HDR_MASTERING_INTS] = { 0 }, flags = 0, cll = 0, fall = 0;
    int display_rc, light_rc;
    (void)cls;
    if (frame == NULL) return NULL;
    display_rc = ffkmp_frame_mastering_display(frame, q, &flags);
    light_rc = ffkmp_frame_content_light(frame, &cll, &fall);
    return kj_hdr_new(env, display_rc, q, flags, light_rc, cll, fall);
}

JNIEXPORT jint JNICALL kj_frame_color_range(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_color_range(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_color_space(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_colorspace(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_color_primaries(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_color_primaries(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_color_transfer(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_color_trc(frame) : 0;
}

JNIEXPORT jint JNICALL kj_frame_chroma_location(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_chroma_location(frame) : 0;
}

JNIEXPORT jlong JNICALL kj_frame_sample_aspect_ratio(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    int n = 0, d = 1;
    (void)cls;
    if (frame != NULL) ffkmp_frame_sample_aspect_ratio(frame, &n, &d);
    return ((jlong)(uint32_t)n << 32) | (uint32_t)d;
}

JNIEXPORT jboolean JNICALL kj_frame_is_hardware(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return (frame && ffkmp_frame_is_hardware(frame)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL kj_frame_hw_download(JNIEnv *env, jclass cls, jlong src_token, jlong dst_token)
{
    kc_frame *src = (kc_frame *)kj_handle_get(env, src_token, KJ_KIND_FRAME);
    kc_frame *dst;
    (void)cls;
    if (src == NULL) return -1;
    dst = (kc_frame *)kj_handle_get(env, dst_token, KJ_KIND_FRAME);
    if (dst == NULL) return -1;
    return (jint)ffkmp_frame_hw_download(src, dst);
}

JNIEXPORT void JNICALL kj_frame_use_best_effort(JNIEnv *env, jclass cls, jlong token)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_use_best_effort_ts(frame);
}

JNIEXPORT void JNICALL kj_frame_set_pts(JNIEnv *env, jclass cls, jlong token, jlong value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_pts(frame, (int64_t)value);
}

JNIEXPORT void JNICALL kj_frame_set_format(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_format(frame, (int)value);
}

JNIEXPORT void JNICALL kj_frame_set_width(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_width(frame, (int)value);
}

JNIEXPORT void JNICALL kj_frame_set_height(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_height(frame, (int)value);
}

JNIEXPORT void JNICALL kj_frame_set_sample_rate(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_sample_rate(frame, (int)value);
}

JNIEXPORT void JNICALL kj_frame_set_sample_count(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_nb_samples(frame, (int)value);
}

JNIEXPORT void JNICALL kj_frame_set_channels(JNIEnv *env, jclass cls, jlong token, jint value)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    if (frame != NULL) ffkmp_frame_set_ch_layout_default(frame, (int)value);
}

JNIEXPORT jint JNICALL kj_frame_get_buffer(JNIEnv *env, jclass cls, jlong token, jint align)
{
    kc_frame *frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    (void)cls;
    return frame ? (jint)ffkmp_frame_get_buffer(frame, (int)align) : -1;
}

/* One copy, from the Java array straight into the frame's buffers, where a Kotlin copyOf and a
   malloc'd duplicate made three. */
static jint kj_frame_fill(JNIEnv *env, jlong token, jbyteArray bytes, int audio)
{
    kj_frame_bytes fb;
    fb.frame = (kc_frame *)kj_handle_get(env, token, KJ_KIND_FRAME);
    if (fb.frame == NULL) return -1;
    fb.video = !audio;
    return (jint)kj_bytes_read_in_place(env, bytes, kj_frame_bytes_in, &fb);
}

JNIEXPORT jint JNICALL kj_frame_fill_video(JNIEnv *env, jclass cls, jlong token, jbyteArray bytes)
{
    (void)cls;
    return kj_frame_fill(env, token, bytes, 0);
}

JNIEXPORT jint JNICALL kj_frame_fill_audio(JNIEnv *env, jclass cls, jlong token, jbyteArray bytes)
{
    (void)cls;
    return kj_frame_fill(env, token, bytes, 1);
}

/* The resampler behind Resampler: a handle-table token of kind KJ_KIND_SWR. */
JNIEXPORT jlong JNICALL kj_swr_create(JNIEnv *env, jclass cls,
                                      jint in_rate, jint in_channels, jint in_format,
                                      jint out_rate, jint out_channels, jint out_format,
                                      jlong in_mask, jlong out_mask)
{
    kc_swr *s = NULL;
    jlong token;
    int rc = ffkmp_swr_create(&s, in_rate, in_channels, in_format, out_rate, out_channels, out_format,
                              (int64_t)in_mask, (int64_t)out_mask);
    (void)cls;
    if (rc < 0 || s == NULL) { kj_throw_ffmpeg(env, rc < 0 ? rc : -12, "swr_create"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_SWR, s);
    if (token == 0) ffkmp_swr_free(&s);
    return token;
}
/* Converts the frame behind in_token (0 drains) into the frame behind out_token; returns the
 * FFmpeg status, which the Kotlin side maps. */
JNIEXPORT jint JNICALL kj_swr_convert_frame(JNIEnv *env, jclass cls, jlong swr_token,
                                            jlong out_token, jlong in_token)
{
    kc_swr *s = (kc_swr *)kj_handle_get(env, swr_token, KJ_KIND_SWR);
    kc_frame *out;
    kc_frame *in = NULL;
    (void)cls;
    if (s == NULL) return -22;
    out = (kc_frame *)kj_handle_get(env, out_token, KJ_KIND_FRAME);
    if (out == NULL) return -22;
    if (in_token != 0) {
        in = (kc_frame *)kj_handle_get(env, in_token, KJ_KIND_FRAME);
        if (in == NULL) return -22;
    }
    return (jint)ffkmp_swr_convert_frame(s, out, in);
}
JNIEXPORT void JNICALL kj_swr_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_swr *s = (kc_swr *)kj_handle_close(token, KJ_KIND_SWR);
    (void)env; (void)cls;
    ffkmp_swr_free(&s); /* NULL-safe; double close resolved to NULL by the table */
}
