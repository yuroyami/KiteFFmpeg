/* Category unit: codecs and decoder contexts (methods.def section "codec").
 * A kc_codec is FFmpeg-owned static data. Its table token is nevertheless explicitly released
 * once its wrapper is finished, so the live-handle ledger reaches zero without freeing FFmpeg's
 * static object. A kc_codec_ctx is caller-owned and freed exactly once. */

#include "kj_internal.h"

#include <stdlib.h>

JNIEXPORT jlong JNICALL kj_find_decoder_by_id(JNIEnv *env, jclass cls, jint id)
{
    const kc_codec *c = ffkmp_find_decoder_by_id((int)id);
    (void)env; (void)cls;
    if (c == NULL) return 0; /* legitimate "not found": Kotlin decides whether to throw */
    return kj_handle_put_checked(env, KJ_KIND_CODEC, (void *)c);
}

JNIEXPORT jlong JNICALL kj_find_decoder_by_name(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    const kc_codec *codec;
    (void)cls;
    if (c == NULL) return 0;
    codec = ffkmp_find_decoder_by_name(c);
    free(c);
    if (codec == NULL) return 0;
    return kj_handle_put_checked(env, KJ_KIND_CODEC, (void *)codec);
}

JNIEXPORT jlong JNICALL kj_find_encoder_by_name(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name); const kc_codec *codec; (void)cls;
    if (c == NULL) return 0; codec = ffkmp_find_encoder_by_name(c); free(c);
    return codec ? kj_handle_put_checked(env, KJ_KIND_CODEC, (void *)codec) : 0;
}

JNIEXPORT jint JNICALL kj_codec_id(JNIEnv *env, jclass cls, jlong token)
{
    const kc_codec *codec = (const kc_codec *)kj_handle_get(env, token, KJ_KIND_CODEC);
    (void)cls;
    return codec ? (jint)ffkmp_codec_id(codec) : 0;
}

JNIEXPORT jstring JNICALL kj_codec_id_name(JNIEnv *env, jclass cls, jint id)
{
    (void)cls;
    return kj_string_new(env, ffkmp_codec_id_name((int)id));
}

JNIEXPORT jint JNICALL kj_codec_id_by_name(JNIEnv *env, jclass cls, jstring name)
{
    char *c = kj_string_dup(env, name);
    int id;
    (void)cls;
    if (c == NULL) return 0;
    id = ffkmp_codec_id_by_name(c);
    free(c);
    return (jint)id;
}

JNIEXPORT jlong JNICALL kj_find_encoder_by_id(JNIEnv *env, jclass cls, jint id)
{
    const kc_codec *c = ffkmp_find_encoder_by_id((int)id);
    (void)cls;
    return c ? kj_handle_put_checked(env, KJ_KIND_CODEC, (void *)c) : 0;
}

JNIEXPORT jstring JNICALL kj_codec_name(JNIEnv *env, jclass cls, jlong token)
{
    const kc_codec *codec = (const kc_codec *)kj_handle_get(env, token, KJ_KIND_CODEC);
    (void)cls;
    return codec ? kj_string_new(env, ffkmp_codec_name(codec)) : NULL;
}

JNIEXPORT void JNICALL kj_codec_release(JNIEnv *env, jclass cls, jlong token)
{ (void)env; (void)cls; kj_handle_release(token, KJ_KIND_CODEC); }

JNIEXPORT jlong JNICALL kj_codecctx_alloc(JNIEnv *env, jclass cls, jlong codec_token)
{
    const kc_codec *codec = (const kc_codec *)kj_handle_get(env, codec_token, KJ_KIND_CODEC);
    kc_codec_ctx *ctx;
    jlong token;
    (void)cls;
    if (codec == NULL) return 0;
    ctx = ffkmp_codecctx_alloc(codec);
    if (ctx == NULL) { kj_throw_handle(env, "codec context allocation failed"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_CODEC_CTX, ctx);
    if (token == 0) ffkmp_codecctx_free(ctx);
    return token;
}

JNIEXPORT void JNICALL kj_codecctx_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_close(token, KJ_KIND_CODEC_CTX);
    (void)env; (void)cls;
    ffkmp_codecctx_free(ctx);
}

JNIEXPORT jint JNICALL kj_codecctx_from_par(JNIEnv *env, jclass cls, jlong ctx_token, jlong par_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_codec_par *par;
    (void)cls;
    if (ctx == NULL) return -1;
    par = (kc_codec_par *)kj_handle_get(env, par_token, KJ_KIND_CODEC_PAR);
    if (par == NULL) return -1;
    return (jint)ffkmp_codecctx_from_par(ctx, par);
}

JNIEXPORT jint JNICALL kj_codecctx_open(JNIEnv *env, jclass cls, jlong ctx_token, jlong codec_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    const kc_codec *codec;
    (void)cls;
    if (ctx == NULL) return -1;
    codec = (const kc_codec *)kj_handle_get(env, codec_token, KJ_KIND_CODEC);
    if (codec == NULL) return -1;
    return (jint)ffkmp_codecctx_open(ctx, codec);
}

JNIEXPORT jint JNICALL kj_codecctx_set_opt(JNIEnv *env, jclass cls, jlong token, jstring key, jstring value)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    char *k, *v;
    int rc;
    (void)cls;
    if (ctx == NULL) return -1;
    k = kj_string_dup(env, key);
    if (k == NULL) { kj_throw_handle(env, "set_opt refused: NULL key"); return -1; }
    v = kj_string_dup(env, value);
    if (value != NULL && v == NULL) {
        free(k);
        return -1; /* preserve the conversion/OOM exception already pending */
    }
    rc = ffkmp_codecctx_set_opt(ctx, k, v);
    free(k);
    free(v);
    return (jint)rc;
}

JNIEXPORT jint JNICALL kj_codecctx_use_videotoolbox(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    (void)cls;
    if (ctx == NULL) return -1;
    /* On macOS this works: the C archive links VideoToolbox there. Elsewhere it answers the
       function-not-implemented error code, the capability truth the Kotlin side forwards typed. */
    return (jint)ffkmp_codecctx_use_videotoolbox(ctx);
}

JNIEXPORT jint JNICALL kj_codecctx_use_d3d11va(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    (void)cls;
    if (ctx == NULL) return -1;
    /* On Windows this attaches a Direct3D 11 device. Elsewhere it answers the
       function-not-implemented error code, the capability truth the Kotlin side forwards typed. */
    return (jint)ffkmp_codecctx_use_d3d11va(ctx);
}

JNIEXPORT jint JNICALL kj_codecctx_send_packet(JNIEnv *env, jclass cls, jlong ctx_token, jlong packet_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_packet *p;
    (void)cls;
    if (ctx == NULL) return -1;
    /* A zero packet token is the documented drain packet: FFmpeg's own NULL-send convention. */
    if (packet_token == 0) return (jint)ffkmp_codecctx_send_packet(ctx, NULL);
    p = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET);
    if (p == NULL) return -1;
    return (jint)ffkmp_codecctx_send_packet(ctx, p);
}

JNIEXPORT jint JNICALL kj_codecctx_receive_frame(JNIEnv *env, jclass cls, jlong ctx_token, jlong frame_token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_frame *f;
    (void)cls;
    if (ctx == NULL) return -1;
    f = (kc_frame *)kj_handle_get(env, frame_token, KJ_KIND_FRAME);
    if (f == NULL) return -1;
    return (jint)ffkmp_codecctx_receive_frame(ctx, f);
}

JNIEXPORT void JNICALL kj_codecctx_flush(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_ctx *ctx = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    (void)cls;
    if (ctx != NULL) ffkmp_codecctx_flush(ctx);
}

JNIEXPORT void JNICALL kj_codecctx_set_threads(JNIEnv *env, jclass cls, jlong token, jint count, jboolean frame_level)
{ kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_threads(c,count,frame_level?1:0); }
JNIEXPORT void JNICALL kj_codecctx_set_low_delay(JNIEnv *env, jclass cls, jlong token, jboolean on)
{ kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_low_delay(c,on?1:0); }
JNIEXPORT jint JNICALL kj_codecctx_send_frame(JNIEnv *env,jclass cls,jlong ctx_token,jlong frame_token)
{
    kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,ctx_token,KJ_KIND_CODEC_CTX);kc_frame*f=NULL;(void)cls;if(!c)return-1;
    if(frame_token){f=(kc_frame*)kj_handle_get(env,frame_token,KJ_KIND_FRAME);if(!f)return-1;}return ffkmp_codecctx_send_frame(c,f);
}
JNIEXPORT jint JNICALL kj_codecctx_receive_packet(JNIEnv *env,jclass cls,jlong ctx_token,jlong packet_token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,ctx_token,KJ_KIND_CODEC_CTX);kc_packet*p;(void)cls;if(!c)return-1;p=(kc_packet*)kj_handle_get(env,packet_token,KJ_KIND_PACKET);return p?ffkmp_codecctx_receive_packet(c,p):-1;}
JNIEXPORT void JNICALL kj_codecctx_set_video(JNIEnv *env,jclass cls,jlong token,jint width,jint height,jint format,jint frn,jint frd,jint tbn,jint tbd,jlong bitrate,jint gop)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_video(c,width,height,format,frn,frd,tbn,tbd,bitrate,gop);}
JNIEXPORT void JNICALL kj_codecctx_set_audio(JNIEnv *env,jclass cls,jlong token,jint rate,jint format,jint channels,jlong bitrate)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_audio(c,rate,format,channels,bitrate);}
JNIEXPORT jint JNICALL kj_codec_first_sample_format(JNIEnv *env,jclass cls,jlong token)
{const kc_codec*c=(const kc_codec*)kj_handle_get(env,token,KJ_KIND_CODEC);(void)cls;return c?ffkmp_codec_first_sample_fmt(c):-1;}
JNIEXPORT jint JNICALL kj_codec_first_pixel_format(JNIEnv *env,jclass cls,jlong token)
{const kc_codec*c=(const kc_codec*)kj_handle_get(env,token,KJ_KIND_CODEC);(void)cls;return c?ffkmp_codec_first_pix_fmt(c):-1;}
JNIEXPORT jboolean JNICALL kj_codec_supports_pixel_format(JNIEnv *env,jclass cls,jlong token,jint format)
{const kc_codec*c=(const kc_codec*)kj_handle_get(env,token,KJ_KIND_CODEC);(void)cls;return(c&&ffkmp_codec_supports_pix_fmt(c,format))?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jint JNICALL kj_codecctx_frame_size(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_frame_size(c):0;}
JNIEXPORT jint JNICALL kj_codecctx_sample_rate(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_sample_rate(c):0;}
JNIEXPORT jint JNICALL kj_codecctx_channels(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_channels(c):0;}
JNIEXPORT jlong JNICALL kj_codecctx_time_base(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);int n=0,d=1;(void)cls;if(c)ffkmp_codecctx_time_base(c,&n,&d);return((jlong)(uint32_t)n<<32)|(uint32_t)d;}
JNIEXPORT void JNICALL kj_codecctx_global_header(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_global_header(c);}
JNIEXPORT jint JNICALL kj_codecctx_set_color(JNIEnv *env,jclass cls,jlong token,jint primaries,jint transfer,jint matrix,jint range,jint chroma)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_set_color(c,primaries,transfer,matrix,range,chroma):-22;}
JNIEXPORT jint JNICALL kj_codecctx_set_sar(JNIEnv *env,jclass cls,jlong token,jint num,jint den)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_set_sample_aspect_ratio(c,num,den):-22;}
JNIEXPORT jint JNICALL kj_codecctx_set_layout(JNIEnv *env,jclass cls,jlong token,jlong mask)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_set_ch_layout_mask(c,(int64_t)mask):-22;}
JNIEXPORT jlong JNICALL kj_codecctx_layout(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?(jlong)ffkmp_codecctx_ch_layout_mask(c):0;}
JNIEXPORT jint JNICALL kj_codecctx_add_light(JNIEnv *env,jclass cls,jlong token,jint max_cll,jint max_fall)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_add_content_light(c,max_cll,max_fall):-22;}

/* The mastering display as KC_HDR_MASTERING_INTS ints and the halves present. */
JNIEXPORT jint JNICALL kj_codecctx_add_mastering(JNIEnv *env, jclass cls, jlong token, jintArray values, jint flags)
{
    kc_codec_ctx *c = (kc_codec_ctx *)kj_handle_get(env, token, KJ_KIND_CODEC_CTX);
    int *q = NULL;
    int32_t n = 0;
    int rc;
    (void)cls;
    if (c == NULL) return -22;
    if (kj_ints_dup(env, values, &q, &n) != 0) return -22;
    rc = n == KC_HDR_MASTERING_INTS ? ffkmp_codecctx_add_mastering_display(c, q, flags) : -22;
    free(q);
    return rc;
}
JNIEXPORT void JNICALL kj_codecctx_full_range(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_full_range(c);}
JNIEXPORT jint JNICALL kj_codecctx_pixel_format(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_pix_fmt(c):-1;}
JNIEXPORT jint JNICALL kj_codecctx_width(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_width(c):0;}
JNIEXPORT jint JNICALL kj_codecctx_height(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_height(c):0;}

/* ── Subtitle decoding ── */

/* A decoder for subtitle stream stream_index of the source behind fmt_token: an owned context,
 * freed with kj_codecctx_free like any other. */
JNIEXPORT jlong JNICALL kj_subtitle_decoder_open(JNIEnv *env, jclass cls, jlong fmt_token, jint stream_index)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, fmt_token, KJ_KIND_FMT_CTX);
    kc_codec_ctx *c = NULL;
    jlong token;
    int rc;
    (void)cls;
    if (ctx == NULL) return 0;
    rc = ffkmp_subtitle_decoder_open(ctx, stream_index, &c);
    if (rc < 0 || c == NULL) {
        kj_throw_ffmpeg(env, rc < 0 ? rc : -12, "subtitle_decoder_open");
        return 0;
    }
    token = kj_handle_put_checked(env, KJ_KIND_CODEC_CTX, c);
    if (token == 0) ffkmp_codecctx_free(c);
    return token;
}

/* Decodes the packet behind packet_token: 0 when it completed no subtitle, else a subtitle token
 * the caller frees with kj_subtitle_free. */
JNIEXPORT jlong JNICALL kj_subtitle_decode(JNIEnv *env, jclass cls, jlong ctx_token, jlong packet_token)
{
    kc_codec_ctx *c = (kc_codec_ctx *)kj_handle_get(env, ctx_token, KJ_KIND_CODEC_CTX);
    kc_packet *p;
    kc_subtitle *s = NULL;
    jlong token;
    int rc;
    (void)cls;
    if (c == NULL) return 0;
    p = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET);
    if (p == NULL) return 0;
    rc = ffkmp_subtitle_decode(c, p, &s);
    if (rc < 0) {
        kj_throw_ffmpeg(env, rc, "subtitle_decode");
        return 0;
    }
    if (s == NULL) return 0;
    token = kj_handle_put_checked(env, KJ_KIND_SUBTITLE, s);
    if (token == 0) ffkmp_subtitle_free(&s);
    return token;
}

/* { start, end, rectangle count }, with INT64_MIN for a time that is not known. */
JNIEXPORT jlongArray JNICALL kj_subtitle_info(JNIEnv *env, jclass cls, jlong token)
{
    kc_subtitle *s = (kc_subtitle *)kj_handle_get(env, token, KJ_KIND_SUBTITLE);
    jlong info[3];
    int64_t start = 0, end = 0;
    (void)cls;
    if (s == NULL) return NULL;
    ffkmp_subtitle_times(s, &start, &end);
    info[0] = (jlong)start;
    info[1] = (jlong)end;
    info[2] = (jlong)ffkmp_subtitle_rect_count(s);
    return kj_longs_new(env, info, 3);
}

/* { type, x, y, width, height, forced } of rectangle i. */
JNIEXPORT jintArray JNICALL kj_subtitle_rect(JNIEnv *env, jclass cls, jlong token, jint i)
{
    kc_subtitle *s = (kc_subtitle *)kj_handle_get(env, token, KJ_KIND_SUBTITLE);
    int v[6] = { 0 };
    jint out[6];
    int rc, k;
    (void)cls;
    if (s == NULL) return NULL;
    rc = ffkmp_subtitle_rect(s, i, &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]);
    if (rc < 0) {
        kj_throw_ffmpeg(env, rc, "subtitle_rect");
        return NULL;
    }
    for (k = 0; k < 6; k++) out[k] = (jint)v[k];
    return kj_ints_new(env, out, 6);
}

/* The premultiplied RGBA of image rectangle i. */
JNIEXPORT jbyteArray JNICALL kj_subtitle_rect_rgba(JNIEnv *env, jclass cls, jlong token, jint i)
{
    kc_subtitle *s = (kc_subtitle *)kj_handle_get(env, token, KJ_KIND_SUBTITLE);
    int type, x, y, w, h, forced;
    int64_t size;
    uint8_t *pixels;
    jbyteArray result;
    int rc;
    (void)cls;
    if (s == NULL) return NULL;
    rc = ffkmp_subtitle_rect(s, i, &type, &x, &y, &w, &h, &forced);
    size = (int64_t)w * h * 4;
    if (rc < 0 || type != KC_SUBTITLE_BITMAP || w <= 0 || h <= 0 || size > INT32_MAX) {
        kj_throw_ffmpeg(env, rc < 0 ? rc : -22, "subtitle_rect_rgba");
        return NULL;
    }
    pixels = (uint8_t *)malloc((size_t)size);
    if (pixels == NULL) {
        kj_throw_handle(env, "out of memory converting a subtitle image");
        return NULL;
    }
    rc = ffkmp_subtitle_rect_rgba(s, i, pixels, (int)size);
    result = rc < 0 ? NULL : kj_bytes_new(env, pixels, (int32_t)size);
    free(pixels);
    if (rc < 0) kj_throw_ffmpeg(env, rc, "subtitle_rect_rgba");
    return result;
}

/* The text of text or ASS rectangle i, or null for an image. */
JNIEXPORT jstring JNICALL kj_subtitle_rect_text(JNIEnv *env, jclass cls, jlong token, jint i)
{
    kc_subtitle *s = (kc_subtitle *)kj_handle_get(env, token, KJ_KIND_SUBTITLE);
    (void)cls;
    if (s == NULL) return NULL;
    return kj_string_new(env, ffkmp_subtitle_rect_text(s, i));
}

JNIEXPORT void JNICALL kj_subtitle_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_subtitle *s = (kc_subtitle *)kj_handle_close(token, KJ_KIND_SUBTITLE);
    (void)env; (void)cls;
    if (s != NULL) ffkmp_subtitle_free(&s);
}
