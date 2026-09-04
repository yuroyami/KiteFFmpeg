/* Category unit: format contexts and their borrowed views (methods.def section "format").
 * Streams are parented to their format token; codec parameters and dictionaries are parented to
 * the view that owns them, and dictionary entries to their dictionary. Closing any parent
 * invalidates its complete descendant tree in the handle table before FFmpeg releases memory. */

#include "kj_internal.h"

#include <stdlib.h>
#include <string.h>

JNIEXPORT jlong JNICALL kj_fmt_open_input(JNIEnv *env, jclass cls, jstring path)
{
    char *c = kj_string_dup(env, path);
    kc_fmt_ctx *ctx = NULL;
    jlong token;
    int rc;
    (void)cls;
    if (c == NULL) { kj_throw_handle(env, "open refused: NULL path"); return 0; }
    rc = ffkmp_fmt_open_input(&ctx, c);
    free(c);
    if (rc < 0 || ctx == NULL) { kj_throw_ffmpeg(env, rc, "fmt_open_input"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_FMT_CTX, ctx);
    if (token == 0) ffkmp_fmt_close_input(&ctx);
    return token;
}


/* KD-4 (S4.b window): open with pre-open option pairs. The unused count crosses through a
 * one-slot array because the token is the return value; -1 in that slot means "not counted"
 * and never happens on a successful open. */
JNIEXPORT jlong JNICALL kj_fmt_open_input2(JNIEnv *env, jclass cls, jstring path,
                                           jobjectArray keys, jobjectArray values,
                                           jobjectArray unused_keys_out)
{
    char *c = kj_string_dup(env, path);
    kc_fmt_ctx *ctx = NULL;
    char **ckeys = NULL;
    char **cvalues = NULL;
    jsize n = 0;
    jlong token = 0;
    kc_dict *unused = NULL;
    int rc;
    jsize i;
    (void)cls;
    if (c == NULL) { kj_throw_handle(env, "open refused: NULL path"); return 0; }
    if ((keys == NULL) != (values == NULL)) {
        free(c);
        kj_throw_handle(env, "open refused: keys and values must both exist or both be absent");
        return 0;
    }
    if (keys != NULL) {
        n = (*env)->GetArrayLength(env, keys);
        if ((*env)->GetArrayLength(env, values) != n) {
            free(c);
            kj_throw_handle(env, "open refused: keys and values differ in length");
            return 0;
        }
    }
    if (n > 0) {
        ckeys = (char **)calloc((size_t)n, sizeof(char *));
        cvalues = (char **)calloc((size_t)n, sizeof(char *));
        if (ckeys == NULL || cvalues == NULL) goto oom;
        for (i = 0; i < n; i++) {
            jstring jk = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
            jstring jv = (jstring)(*env)->GetObjectArrayElement(env, values, i);
            ckeys[i] = kj_string_dup(env, jk);
            cvalues[i] = kj_string_dup(env, jv);
            if (jk != NULL) (*env)->DeleteLocalRef(env, jk);
            if (jv != NULL) (*env)->DeleteLocalRef(env, jv);
            if (ckeys[i] == NULL || cvalues[i] == NULL) goto oom;
        }
    }
    rc = ffkmp_fmt_open_input2(&ctx, c,
                               (const char *const *)ckeys, (const char *const *)cvalues,
                               (int)n, &unused);

    goto done;
oom:
    rc = -12; /* the out-of-memory errno shape; the throw below names the phase. */
done:
    if (ckeys != NULL) { for (i = 0; i < n; i++) free(ckeys[i]); free(ckeys); }
    if (cvalues != NULL) { for (i = 0; i < n; i++) free(cvalues[i]); free(cvalues); }
    free(c);
    if (rc < 0 || ctx == NULL) { ffkmp_dict_free(&unused); kj_throw_ffmpeg(env, rc, "fmt_open_input2"); return 0; }
    /* The unused remainder crosses as ONE unit-separated string in slot 0 (empty string when
     * everything was consumed), the same joining the identity report already uses. */
    if (unused_keys_out != NULL && (*env)->GetArrayLength(env, unused_keys_out) >= 1) {
        size_t total = 1;
        kc_dict_entry *e = NULL;
        while ((e = ffkmp_dict_get(unused, e)) != NULL) {
            total += strlen(ffkmp_dict_entry_key(e)) + 1;
        }
        char *joined = (char *)malloc(total);
        if (joined != NULL) {
            size_t at = 0;
            e = NULL;
            while ((e = ffkmp_dict_get(unused, e)) != NULL) {
                const char *key = ffkmp_dict_entry_key(e);
                size_t len = strlen(key);
                if (at > 0) joined[at++] = '\x1f';
                memcpy(joined + at, key, len);
                at += len;
            }
            joined[at] = '\0';
            jstring js = (*env)->NewStringUTF(env, joined);
            free(joined);
            if (js != NULL) {
                (*env)->SetObjectArrayElement(env, unused_keys_out, 0, js);
                (*env)->DeleteLocalRef(env, js);
            }
        }
    }
    ffkmp_dict_free(&unused);
    token = kj_handle_put_checked(env, KJ_KIND_FMT_CTX, ctx);
    if (token == 0) ffkmp_fmt_close_input(&ctx);
    return token;
}

/* KD-5 (S4.b window): the chapter table. */
JNIEXPORT jint JNICALL kj_fmt_chapter_count(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_chapter_count(ctx) : -1;
}

JNIEXPORT jint JNICALL kj_fmt_chapter_get(JNIEnv *env, jclass cls, jlong token, jint index,
                                          jlongArray out_fields)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    int64_t id = 0, start_us = 0, end_us = 0;
    jlong fields[3];
    int rc;
    (void)cls;
    if (ctx == NULL) return -1;
    if (out_fields == NULL || (*env)->GetArrayLength(env, out_fields) < 3) {
        kj_throw_handle(env, "chapter_get needs a three-slot output array");
        return -1;
    }
    rc = ffkmp_fmt_chapter_get(ctx, (int)index, &id, &start_us, &end_us);
    if (rc < 0) return (jint)rc;
    fields[0] = (jlong)id; fields[1] = (jlong)start_us; fields[2] = (jlong)end_us;
    (*env)->SetLongArrayRegion(env, out_fields, 0, 3, fields);
    return 0;
}

JNIEXPORT jlong JNICALL kj_fmt_chapter_metadata(JNIEnv *env, jclass cls, jlong token, jint index)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_dict *dict;
    (void)cls;
    if (ctx == NULL) return 0;
    dict = ffkmp_fmt_chapter_metadata(ctx, (int)index);
    return dict ? kj_handle_put_borrowed(env, KJ_KIND_DICT, dict, token) : 0;
}

JNIEXPORT void JNICALL kj_fmt_close_input(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_close(token, KJ_KIND_FMT_CTX);
    (void)env; (void)cls;
    if (ctx != NULL) ffkmp_fmt_close_input(&ctx);
}

/* The one entry point callable from another thread while a read or seek is blocked
   on the same context. The handle table resolves or throws as usual; the C seam is a single
   volatile write. */
JNIEXPORT void JNICALL kj_fmt_interrupt(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    if (ctx != NULL) ffkmp_fmt_interrupt(ctx);
}

/* Returns the CLOSE result so the caller can fail a write that only failed at the very end, such
   as a full disk discovered while the final buffer was flushed. Zero when there was
   nothing to close, which is also what success looks like. */
JNIEXPORT jint JNICALL kj_fmt_free_output(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_close(token, KJ_KIND_FMT_CTX);
    (void)env; (void)cls;
    return ctx != NULL ? (jint)ffkmp_fmt_free_output(&ctx) : 0;
}

JNIEXPORT jint JNICALL kj_fmt_find_stream_info(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_find_stream_info(ctx) : -1;
}

JNIEXPORT jint JNICALL kj_fmt_nb_streams(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_nb_streams(ctx) : 0;
}

JNIEXPORT jlong JNICALL kj_fmt_stream(JNIEnv *env, jclass cls, jlong token, jint index)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_stream *s;
    (void)cls;
    if (ctx == NULL) return 0;
    if (index < 0 || (unsigned)index >= ffkmp_fmt_nb_streams(ctx)) {
        kj_throw_handle(env, "stream index out of range");
        return 0;
    }
    s = ffkmp_fmt_stream(ctx, (unsigned)index);
    if (s == NULL) { kj_throw_handle(env, "stream lookup failed"); return 0; }
    return kj_handle_put_borrowed(env, KJ_KIND_STREAM, s, token);
}

JNIEXPORT jlong JNICALL kj_fmt_duration_micros(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jlong)ffkmp_fmt_duration(ctx) : 0;
}

JNIEXPORT jint JNICALL kj_fmt_read_frame(JNIEnv *env, jclass cls, jlong token, jlong packet_token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_packet *p;
    (void)cls;
    if (ctx == NULL) return -1;
    p = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET);
    if (p == NULL) return -1;
    return (jint)ffkmp_fmt_read_frame(ctx, p);
}

JNIEXPORT jint JNICALL kj_fmt_seek_micros(JNIEnv *env, jclass cls, jlong token, jint stream_index, jlong micros)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls;
    return ctx ? (jint)ffkmp_fmt_seek_micros(ctx, (int)stream_index, (int64_t)micros) : -1;
}

JNIEXPORT jint JNICALL kj_fmt_set_opt(JNIEnv *env, jclass cls, jlong token, jstring key, jstring value)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
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
    rc = ffkmp_fmt_set_opt(ctx, k, v);
    free(k);
    free(v);
    return (jint)rc;
}

JNIEXPORT jlong JNICALL kj_fmt_start_time(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return ctx ? (jlong)ffkmp_fmt_start_time(ctx) : 0;
}

JNIEXPORT jstring JNICALL kj_fmt_input_name(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return ctx ? kj_string_new(env, ffkmp_fmt_iformat_name(ctx)) : NULL;
}

JNIEXPORT jboolean JNICALL kj_fmt_is_seekable(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return (ctx && ffkmp_fmt_is_seekable(ctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL kj_fmt_seek_file(JNIEnv *env, jclass cls, jlong token, jint index,
                                        jlong min, jlong target, jlong max, jint flags)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    (void)cls; return ctx ? (jint)ffkmp_fmt_seek_file(ctx, index, min, target, max, flags) : -1;
}

JNIEXPORT jlong JNICALL kj_fmt_metadata(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    kc_dict *dict; (void)cls; if (ctx == NULL) return 0;
    dict = ffkmp_fmt_metadata(ctx); return dict ? kj_handle_put_borrowed(env, KJ_KIND_DICT, dict, token) : 0;
}

JNIEXPORT jlong JNICALL kj_fmt_alloc_output(JNIEnv *env, jclass cls, jstring path, jstring format)
{
    char *p = NULL; char *f = NULL;
    kc_fmt_ctx *ctx = NULL; jlong token; int rc; (void)cls;
    p = kj_string_dup(env, path);
    if (path != NULL && p == NULL) return 0;
    f = kj_string_dup(env, format);
    if (format != NULL && f == NULL) { free(p); return 0; }
    rc = ffkmp_fmt_alloc_output2(&ctx, p, f); free(p); free(f);
    if (rc < 0 || ctx == NULL) { kj_throw_ffmpeg(env, rc, "fmt_alloc_output"); return 0; }
    token = kj_handle_put_checked(env, KJ_KIND_FMT_CTX, ctx);
    if (token == 0) ffkmp_fmt_free_output(&ctx);
    return token;
}

JNIEXPORT jlong JNICALL kj_fmt_new_stream(JNIEnv *env, jclass cls, jlong fmt_token, jlong codec_token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, fmt_token, KJ_KIND_FMT_CTX);
    const kc_codec *codec = NULL; kc_stream *stream; (void)cls;
    if (ctx == NULL) return 0;
    if (codec_token != 0) {
        codec = (const kc_codec *)kj_handle_get(env, codec_token, KJ_KIND_CODEC);
        if (codec == NULL) return 0;
    }
    stream = ffkmp_fmt_new_stream(ctx, codec);
    if (stream == NULL) { kj_throw_handle(env, "new output stream failed"); return 0; }
    return kj_handle_put_borrowed(env, KJ_KIND_STREAM, stream, fmt_token);
}

JNIEXPORT jint JNICALL kj_fmt_io_open(JNIEnv *env, jclass cls, jlong token, jstring path)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX);
    char *p; int rc; (void)cls; if (ctx == NULL) return -1;
    p = kj_string_dup(env, path); if (p == NULL) return -1;
    rc = ffkmp_fmt_io_open(ctx, p); free(p); return (jint)rc;
}

JNIEXPORT void JNICALL kj_fmt_avoid_negative_ts(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; if (ctx) ffkmp_fmt_avoid_negative_ts(ctx); }
JNIEXPORT jint JNICALL kj_fmt_write_header(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; return ctx ? ffkmp_fmt_write_header(ctx) : -1; }
JNIEXPORT jint JNICALL kj_fmt_write_frame(JNIEnv *env, jclass cls, jlong token, jlong packet_token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); kc_packet *packet = NULL; (void)cls;
    if (!ctx) return -1; if (packet_token != 0) { packet = (kc_packet *)kj_handle_get(env, packet_token, KJ_KIND_PACKET); if (!packet) return -1; }
    return ffkmp_fmt_write_frame(ctx, packet);
}
JNIEXPORT jint JNICALL kj_fmt_write_trailer(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; return ctx ? ffkmp_fmt_write_trailer(ctx) : -1; }
JNIEXPORT jboolean JNICALL kj_fmt_global_header(JNIEnv *env, jclass cls, jlong token)
{ kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); (void)cls; return (ctx && ffkmp_oformat_global_header(ctx)) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL kj_fmt_set_metadata(JNIEnv *env, jclass cls, jlong token, jstring key, jstring value)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_get(env, token, KJ_KIND_FMT_CTX); char *k, *v; int rc; (void)cls;
    if (!ctx) return -1;
    k = kj_string_dup(env, key);
    if (k == NULL) return -1;
    v = kj_string_dup(env, value);
    if (value != NULL && v == NULL) { free(k); return -1; }
    rc = ffkmp_fmt_set_metadata(ctx, k, v); free(k); free(v); return rc;
}

JNIEXPORT void JNICALL kj_borrowed_release(JNIEnv *env, jclass cls, jlong token, jint kind)
{ (void)env; (void)cls; kj_handle_release(token, (int)kind); }

JNIEXPORT jint JNICALL kj_stream_index(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s = (kc_stream *)kj_handle_get(env, token, KJ_KIND_STREAM); (void)cls; return s ? ffkmp_stream_index(s) : -1; }
JNIEXPORT jlong JNICALL kj_stream_codecpar(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s = (kc_stream *)kj_handle_get(env, token, KJ_KIND_STREAM); kc_codec_par *p; (void)cls; if (!s) return 0; p=ffkmp_stream_codecpar(s); return p ? kj_handle_put_borrowed(env,KJ_KIND_CODEC_PAR,p,token):0; }
JNIEXPORT jlong JNICALL kj_stream_duration(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s=(kc_stream *)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_duration_micros(s):0; }
JNIEXPORT jlong JNICALL kj_stream_start_time(JNIEnv *env, jclass cls, jlong token)
{ kc_stream *s=(kc_stream *)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_start_time(s):0; }
JNIEXPORT jlong JNICALL kj_stream_metadata(JNIEnv *env,jclass cls,jlong token)
{ kc_stream *s=(kc_stream *)kj_handle_get(env,token,KJ_KIND_STREAM);kc_dict*d;(void)cls;if(!s)return 0;d=ffkmp_stream_metadata(s);return d?kj_handle_put_borrowed(env,KJ_KIND_DICT,d,token):0; }
JNIEXPORT jlong JNICALL kj_stream_time_base(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);int n=0,d=1;(void)cls;if(s)ffkmp_stream_time_base(s,&n,&d);return ((jlong)(uint32_t)n<<32)|(uint32_t)d; }
JNIEXPORT jlong JNICALL kj_stream_frame_rate(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);int n=0,d=1;(void)cls;if(s)ffkmp_stream_avg_frame_rate(s,&n,&d);return ((jlong)(uint32_t)n<<32)|(uint32_t)d; }
JNIEXPORT void JNICALL kj_stream_set_time_base(JNIEnv *env,jclass cls,jlong token,jint n,jint d)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;if(s)ffkmp_stream_set_time_base(s,n,d); }
JNIEXPORT void JNICALL kj_stream_discard(JNIEnv *env,jclass cls,jlong token,jboolean discard)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;if(s){if(discard)ffkmp_stream_discard_all(s);else ffkmp_stream_discard_none(s);} }
JNIEXPORT jint JNICALL kj_stream_disposition(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_disposition(s):0; }
JNIEXPORT jint JNICALL kj_stream_rotation(JNIEnv *env,jclass cls,jlong token)
{ kc_stream*s=(kc_stream*)kj_handle_get(env,token,KJ_KIND_STREAM);(void)cls;return s?ffkmp_stream_rotation_degrees(s):0; }

JNIEXPORT jlong JNICALL kj_dict_next(JNIEnv *env,jclass cls,jlong dict_token,jlong previous_token)
{
    kc_dict*d=(kc_dict*)kj_handle_get(env,dict_token,KJ_KIND_DICT);kc_dict_entry*prev=NULL,*next;(void)cls;
    if(!d)return 0;if(previous_token){prev=(kc_dict_entry*)kj_handle_get(env,previous_token,KJ_KIND_DICT_ENTRY);if(!prev)return 0;}
    next=ffkmp_dict_get(d,prev);
    if (previous_token) kj_handle_release(previous_token, KJ_KIND_DICT_ENTRY);
    return next?kj_handle_put_borrowed(env,KJ_KIND_DICT_ENTRY,next,dict_token):0;
}
JNIEXPORT jstring JNICALL kj_dict_key(JNIEnv *env,jclass cls,jlong token)
{kc_dict_entry*e=(kc_dict_entry*)kj_handle_get(env,token,KJ_KIND_DICT_ENTRY);(void)cls;return e?kj_string_new(env,ffkmp_dict_entry_key(e)):NULL;}
JNIEXPORT jstring JNICALL kj_dict_value(JNIEnv *env,jclass cls,jlong token)
{kc_dict_entry*e=(kc_dict_entry*)kj_handle_get(env,token,KJ_KIND_DICT_ENTRY);(void)cls;return e?kj_string_new(env,ffkmp_dict_entry_value(e)):NULL;}

JNIEXPORT jint JNICALL kj_codecpar_type(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_codec_type(p):-1;}
JNIEXPORT jint JNICALL kj_codecpar_id(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_codec_id(p):-1;}
JNIEXPORT jlong JNICALL kj_codecpar_bitrate(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_bit_rate(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_field_order(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_field_order(p):0;}
JNIEXPORT jlong JNICALL kj_fmt_bitrate(JNIEnv *env,jclass cls,jlong token)
{kc_fmt_ctx*c=(kc_fmt_ctx*)kj_handle_get(env,token,KJ_KIND_FMT_CTX);(void)cls;return c?ffkmp_fmt_bit_rate(c):0;}
JNIEXPORT jint JNICALL kj_codecpar_width(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_width(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_height(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_height(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_format(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_format(p):-1;}
JNIEXPORT jint JNICALL kj_codecpar_profile(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_profile(p):-99;}
JNIEXPORT jint JNICALL kj_codecpar_level(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_level(p):-99;}
JNIEXPORT jint JNICALL kj_codecpar_color_space(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_color_space(p):2;}
JNIEXPORT jint JNICALL kj_codecpar_color_primaries(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_color_primaries(p):2;}
JNIEXPORT jint JNICALL kj_codecpar_color_transfer(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_color_transfer(p):2;}
JNIEXPORT jint JNICALL kj_codecpar_color_range(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_color_range(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_chroma_location(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_chroma_location(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_bit_depth(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_bit_depth(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_chroma_subsampling(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_chroma_subsampling(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_sample_rate(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_sample_rate(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_channels(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_channels(p):0;}
JNIEXPORT jbyteArray JNICALL kj_codecpar_extradata(JNIEnv *env, jclass cls, jlong token)
{
    kc_codec_par *p = (kc_codec_par *)kj_handle_get(env, token, KJ_KIND_CODEC_PAR);
    uint8_t *bytes;
    jbyteArray result;
    int size, copied;
    (void)cls;
    if (p == NULL) return NULL;
    size = ffkmp_codecpar_extradata(p, NULL, 0);
    if (size < 0) {
        kj_throw_ffmpeg(env, size, "codecpar_extradata size");
        return NULL;
    }
    if (size == 0) return NULL;
    bytes = (uint8_t *)malloc((size_t)size);
    if (bytes == NULL) {
        kj_throw_handle(env, "out of memory copying codec parameter extradata");
        return NULL;
    }
    copied = ffkmp_codecpar_extradata(p, bytes, size);
    if (copied != size) {
        free(bytes);
        if (copied < 0) kj_throw_ffmpeg(env, copied, "codecpar_extradata copy");
        else kj_throw_handle(env, "codec parameter extradata changed while copying");
        return NULL;
    }
    result = kj_bytes_new(env, bytes, size);
    free(bytes);
    return result;
}
JNIEXPORT jlong JNICALL kj_codecpar_sar(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);int n=0,d=1;(void)cls;if(p)ffkmp_codecpar_sample_aspect_ratio(p,&n,&d);return((jlong)(uint32_t)n<<32)|(uint32_t)d;}
JNIEXPORT jlong JNICALL kj_codecpar_layout(JNIEnv *env,jclass cls,jlong token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,token,KJ_KIND_CODEC_PAR);(void)cls;return p?ffkmp_codecpar_ch_layout_mask(p):0;}
JNIEXPORT jint JNICALL kj_codecpar_from_context(JNIEnv *env,jclass cls,jlong par_token,jlong ctx_token)
{kc_codec_par*p=(kc_codec_par*)kj_handle_get(env,par_token,KJ_KIND_CODEC_PAR);kc_codec_ctx*c;(void)cls;if(!p)return-1;c=(kc_codec_ctx*)kj_handle_get(env,ctx_token,KJ_KIND_CODEC_CTX);return c?ffkmp_codecpar_from_context(p,c):-1;}
JNIEXPORT jint JNICALL kj_codecpar_copy(JNIEnv *env,jclass cls,jlong dst_token,jlong src_token)
{kc_codec_par*d=(kc_codec_par*)kj_handle_get(env,dst_token,KJ_KIND_CODEC_PAR);kc_codec_par*s;(void)cls;if(!d)return-1;s=(kc_codec_par*)kj_handle_get(env,src_token,KJ_KIND_CODEC_PAR);return s?ffkmp_codecpar_copy_for_mux(d,s):-1;}

/* ── M1: the custom AVIO bridge ──────────────────────────────────────────────────────────────
 * The bytes come from a Kotlin JniByteIo instead of a path. This unit parks the VM pointer,
 * the callback's global refs and the reusable transfer array behind the C bridge's opaque, and
 * recovers them at close through ffkmp_fmt_io_opaque. Method names and signatures here are the
 * other half of JniByteIo.kt's pinned contract. */

#define KJ_IO_BUFFER_SIZE (64 * 1024)

typedef struct kj_io_state {
    JavaVM   *vm;
    jobject   cb;      /* global ref: the JniByteIo */
    jobject   buffer;  /* global ref: the reusable jbyteArray */
    jmethodID read;    /* ([BI)I */
    jmethodID seek;    /* (JI)J */
} kj_io_state;

static JNIEnv *kj_io_env(kj_io_state *st)
{
    JNIEnv *env = NULL;
    if ((*st->vm)->GetEnv(st->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK && env != NULL) return env;
#ifdef __ANDROID__
    if ((*st->vm)->AttachCurrentThread(st->vm, &env, NULL) == JNI_OK) return env;
#else
    if ((*st->vm)->AttachCurrentThread(st->vm, (void **)&env, NULL) == JNI_OK) return env;
#endif
    return NULL;
}

static int kj_io_read(void *opaque, unsigned char *buf, int len)
{
    kj_io_state *st = (kj_io_state *)opaque;
    JNIEnv *env = kj_io_env(st);
    jint want, r;
    if (env == NULL || len <= 0) return KC_IO_ERR;
    want = len < KJ_IO_BUFFER_SIZE ? (jint)len : (jint)KJ_IO_BUFFER_SIZE;
    r = (*env)->CallIntMethod(env, st->cb, st->read, st->buffer, want);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return KC_IO_ERR; }
    if (r > 0) {
        if (r > want) return KC_IO_ERR;
        (*env)->GetByteArrayRegion(env, (jbyteArray)st->buffer, 0, r, (jbyte *)buf);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return KC_IO_ERR; }
        return (int)r;
    }
    return r == -1 ? KC_IO_EOF : KC_IO_ERR;
}

static int64_t kj_io_seek_cb(void *opaque, int64_t offset, int whence)
{
    kj_io_state *st = (kj_io_state *)opaque;
    JNIEnv *env = kj_io_env(st);
    jlong r;
    if (env == NULL) return KC_IO_ERR;
    r = (*env)->CallLongMethod(env, st->cb, st->seek, (jlong)offset, (jint)whence);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return KC_IO_ERR; }
    return r < 0 ? KC_IO_ERR : (int64_t)r;
}

static void kj_io_state_free(JNIEnv *env, kj_io_state *st)
{
    if (st == NULL) return;
    if (st->cb != NULL) (*env)->DeleteGlobalRef(env, st->cb);
    if (st->buffer != NULL) (*env)->DeleteGlobalRef(env, st->buffer);
    free(st);
}

JNIEXPORT jlong JNICALL kj_fmt_open_input_io(JNIEnv *env, jclass cls, jobject cb,
                                             jboolean seekable, jlong size,
                                             jobjectArray keys, jobjectArray values,
                                             jobjectArray unused_keys_out)
{
    kj_io_state *st = NULL;
    kc_fmt_ctx *ctx = NULL;
    char **ckeys = NULL;
    char **cvalues = NULL;
    jsize n = 0;
    jlong token = 0;
    kc_dict *unused = NULL;
    jclass cb_class;
    jbyteArray local_buffer;
    int rc;
    jsize i;
    (void)cls;
    if (cb == NULL) { kj_throw_handle(env, "custom io open refused: NULL callback"); return 0; }
    if ((keys == NULL) != (values == NULL)) {
        kj_throw_handle(env, "custom io open refused: keys and values must both exist or both be absent");
        return 0;
    }
    if (keys != NULL) {
        n = (*env)->GetArrayLength(env, keys);
        if ((*env)->GetArrayLength(env, values) != n) {
            kj_throw_handle(env, "custom io open refused: keys and values differ in length");
            return 0;
        }
    }

    st = (kj_io_state *)calloc(1, sizeof(kj_io_state));
    if (st == NULL) { kj_throw_handle(env, "custom io open: out of memory"); return 0; }
    if ((*env)->GetJavaVM(env, &st->vm) != 0) {
        free(st);
        kj_throw_handle(env, "custom io open: GetJavaVM failed");
        return 0;
    }
    cb_class = (*env)->GetObjectClass(env, cb);
    st->read = (*env)->GetMethodID(env, cb_class, "read", "([BI)I");
    st->seek = (*env)->GetMethodID(env, cb_class, "seek", "(JI)J");
    (*env)->DeleteLocalRef(env, cb_class);
    if (st->read == NULL || st->seek == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        free(st);
        kj_throw_handle(env, "custom io open: JniByteIo methods not found (keep rules?)");
        return 0;
    }
    st->cb = (*env)->NewGlobalRef(env, cb);
    local_buffer = (*env)->NewByteArray(env, KJ_IO_BUFFER_SIZE);
    st->buffer = local_buffer != NULL ? (*env)->NewGlobalRef(env, local_buffer) : NULL;
    if (local_buffer != NULL) (*env)->DeleteLocalRef(env, local_buffer);
    if (st->cb == NULL || st->buffer == NULL) {
        kj_io_state_free(env, st);
        kj_throw_handle(env, "custom io open: global ref allocation failed");
        return 0;
    }

    if (n > 0) {
        ckeys = (char **)calloc((size_t)n, sizeof(char *));
        cvalues = (char **)calloc((size_t)n, sizeof(char *));
        if (ckeys == NULL || cvalues == NULL) goto oom;
        for (i = 0; i < n; i++) {
            jstring jk = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
            jstring jv = (jstring)(*env)->GetObjectArrayElement(env, values, i);
            ckeys[i] = kj_string_dup(env, jk);
            cvalues[i] = kj_string_dup(env, jv);
            if (jk != NULL) (*env)->DeleteLocalRef(env, jk);
            if (jv != NULL) (*env)->DeleteLocalRef(env, jv);
            if (ckeys[i] == NULL || cvalues[i] == NULL) goto oom;
        }
    }

    rc = ffkmp_fmt_open_input_io(&ctx, st, kj_io_read,
                                 seekable == JNI_TRUE ? kj_io_seek_cb : NULL,
                                 (int64_t)size,
                                 (const char *const *)ckeys, (const char *const *)cvalues,
                                 (int)n, &unused);
    goto done;
oom:
    rc = -12;
done:
    if (ckeys != NULL) { for (i = 0; i < n; i++) free(ckeys[i]); free(ckeys); }
    if (cvalues != NULL) { for (i = 0; i < n; i++) free(cvalues[i]); free(cvalues); }
    if (rc < 0 || ctx == NULL) {
        ffkmp_dict_free(&unused);
        kj_io_state_free(env, st);
        kj_throw_ffmpeg(env, rc, "fmt_open_input_io");
        return 0;
    }
    /* The unused remainder crosses exactly like fmt_open_input2's. */
    if (unused_keys_out != NULL && (*env)->GetArrayLength(env, unused_keys_out) >= 1) {
        size_t total = 1;
        kc_dict_entry *e = NULL;
        while ((e = ffkmp_dict_get(unused, e)) != NULL) {
            total += strlen(ffkmp_dict_entry_key(e)) + 1;
        }
        char *joined = (char *)malloc(total);
        if (joined != NULL) {
            size_t at = 0;
            e = NULL;
            while ((e = ffkmp_dict_get(unused, e)) != NULL) {
                const char *key = ffkmp_dict_entry_key(e);
                size_t len = strlen(key);
                if (at > 0) joined[at++] = '\x1f';
                memcpy(joined + at, key, len);
                at += len;
            }
            joined[at] = '\0';
            jstring js = (*env)->NewStringUTF(env, joined);
            free(joined);
            if (js != NULL) {
                (*env)->SetObjectArrayElement(env, unused_keys_out, 0, js);
                (*env)->DeleteLocalRef(env, js);
            }
        }
    }
    ffkmp_dict_free(&unused);
    token = kj_handle_put_checked(env, KJ_KIND_FMT_CTX, ctx);
    if (token == 0) {
        ffkmp_fmt_close_input_io(&ctx);
        kj_io_state_free(env, st);
    }
    return token;
}

JNIEXPORT void JNICALL kj_fmt_close_input_io(JNIEnv *env, jclass cls, jlong token)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)kj_handle_close(token, KJ_KIND_FMT_CTX);
    kj_io_state *st;
    (void)cls;
    if (ctx == NULL) return;
    st = (kj_io_state *)ffkmp_fmt_io_opaque(ctx);
    ffkmp_fmt_close_input_io(&ctx);
    kj_io_state_free(env, st);
}
