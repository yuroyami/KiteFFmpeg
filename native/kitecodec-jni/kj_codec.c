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
JNIEXPORT void JNICALL kj_codecctx_full_range(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;if(c)ffkmp_codecctx_set_full_range(c);}
JNIEXPORT jint JNICALL kj_codecctx_pixel_format(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_pix_fmt(c):-1;}
JNIEXPORT jint JNICALL kj_codecctx_width(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_width(c):0;}
JNIEXPORT jint JNICALL kj_codecctx_height(JNIEnv *env,jclass cls,jlong token)
{kc_codec_ctx*c=(kc_codec_ctx*)kj_handle_get(env,token,KJ_KIND_CODEC_CTX);(void)cls;return c?ffkmp_codecctx_height(c):0;}
