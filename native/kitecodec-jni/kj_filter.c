/* Category unit: filter graphs (methods.def section "filter"). The graph owns every source and
 * sink context. They cross JNI as borrowed children, so freeing the graph invalidates every view
 * before FFmpeg releases their pointers. */

#include "kj_internal.h"

#include <stdlib.h>

static jlongArray kj_graph_result(JNIEnv *env, kc_filter_graph *graph,
                                  kc_filter_ctx **sources, int count, kc_filter_ctx *sink)
{
    jlong graph_token = 0;
    jlong *tokens;
    jlongArray result = NULL;
    int i;

    tokens = (jlong *)calloc((size_t)count + 2u, sizeof(jlong));
    if (tokens == NULL) {
        kj_throw_handle(env, "out of memory building filter handle result");
        goto fail;
    }
    graph_token = kj_handle_put_checked(env, KJ_KIND_FILTER_GRAPH, graph);
    if (graph_token == 0) goto fail;
    tokens[0] = graph_token;
    for (i = 0; i < count; i++) {
        tokens[i + 1] = kj_handle_put_borrowed(env, KJ_KIND_FILTER_CTX, sources[i], graph_token);
        if (tokens[i + 1] == 0) goto fail;
    }
    tokens[count + 1] = kj_handle_put_borrowed(env, KJ_KIND_FILTER_CTX, sink, graph_token);
    if (tokens[count + 1] == 0) goto fail;
    result = kj_longs_new(env, tokens, count + 2);
    if (result != NULL) {
        free(tokens);
        return result;
    }

fail:
    if (graph_token != 0) {
        kc_filter_graph *owned = (kc_filter_graph *)kj_handle_close(graph_token, KJ_KIND_FILTER_GRAPH);
        if (owned != NULL) ffkmp_graph_free(&owned);
    } else if (graph != NULL) {
        ffkmp_graph_free(&graph);
    }
    free(tokens);
    return NULL;
}

JNIEXPORT jlongArray JNICALL kj_graph_build_video(
    JNIEnv *env, jclass cls, jstring description,
    jint width, jint height, jint format,
    jint tbn, jint tbd, jint frn, jint frd, jint sarn, jint sard)
{
    char *desc = kj_string_dup(env, description);
    kc_filter_graph *graph = NULL;
    kc_filter_ctx *source = NULL, *sink = NULL;
    kc_filter_ctx *sources[1];
    int rc;
    (void)cls;
    if (desc == NULL) {
        kj_throw_handle(env, "video filter description is null");
        return NULL;
    }
    rc = ffkmp_graph_build_video(&graph, &source, &sink, desc, width, height, format,
                                 tbn, tbd, frn, frd, sarn, sard);
    free(desc);
    if (rc < 0) {
        kj_throw_ffmpeg(env, rc, "graph_build_video");
        return NULL;
    }
    sources[0] = source;
    return kj_graph_result(env, graph, sources, 1, sink);
}

JNIEXPORT jlongArray JNICALL kj_graph_build_audio(
    JNIEnv *env, jclass cls, jstring description,
    jint rate, jint format, jint channels, jint tbn, jint tbd,
    jint out_format, jint out_rate, jint out_channels, jlong layout_mask, jlong out_layout_mask)
{
    char *desc = kj_string_dup(env, description);
    kc_filter_graph *graph = NULL;
    kc_filter_ctx *source = NULL, *sink = NULL;
    kc_filter_ctx *sources[1];
    int rc;
    (void)cls;
    if ((*env)->ExceptionCheck(env)) return NULL;
    rc = ffkmp_graph_build_audio(&graph, &source, &sink, desc, rate, format, channels,
                                 tbn, tbd, out_format, out_rate, out_channels,
                                 (int64_t)layout_mask, (int64_t)out_layout_mask);
    free(desc);
    if (rc < 0) {
        kj_throw_ffmpeg(env, rc, "graph_build_audio");
        return NULL;
    }
    sources[0] = source;
    return kj_graph_result(env, graph, sources, 1, sink);
}

JNIEXPORT jlongArray JNICALL kj_graph_build_video_multi(
    JNIEnv *env, jclass cls, jstring description, jint count,
    jintArray widths, jintArray heights, jintArray formats,
    jintArray tbns, jintArray tbds, jintArray frns, jintArray frds,
    jintArray sarns, jintArray sards)
{
    char *desc = NULL;
    int *w = NULL, *h = NULL, *fmt = NULL, *tn = NULL, *td = NULL;
    int *fn = NULL, *fd = NULL, *sn = NULL, *sd = NULL;
    int32_t nw, nh, nfmt, ntn, ntd, nfn, nfd, nsn, nsd;
    kc_filter_graph *graph = NULL;
    kc_filter_ctx **sources = NULL, *sink = NULL;
    jlongArray result = NULL;
    int rc = -1;
    (void)cls;
    if (count <= 0) {
        kj_throw_handle(env, "video multi graph requires at least one input");
        return NULL;
    }
    desc = kj_string_dup(env, description);
    sources = (kc_filter_ctx **)calloc((size_t)count, sizeof(kc_filter_ctx *));
    if ((*env)->ExceptionCheck(env)) goto done;
    if (sources == NULL) {
        kj_throw_handle(env, "out of memory allocating video filter-source views");
        goto done;
    }
    if (kj_ints_dup(env, widths, &w, &nw) != 0 || kj_ints_dup(env, heights, &h, &nh) != 0
        || kj_ints_dup(env, formats, &fmt, &nfmt) != 0 || kj_ints_dup(env, tbns, &tn, &ntn) != 0
        || kj_ints_dup(env, tbds, &td, &ntd) != 0 || kj_ints_dup(env, frns, &fn, &nfn) != 0
        || kj_ints_dup(env, frds, &fd, &nfd) != 0 || kj_ints_dup(env, sarns, &sn, &nsn) != 0
        || kj_ints_dup(env, sards, &sd, &nsd) != 0) goto done;
    if (nw != count || nh != count || nfmt != count || ntn != count || ntd != count
        || nfn != count || nfd != count || nsn != count || nsd != count) {
        kj_throw_handle(env, "video multi graph argument-array length mismatch");
        goto done;
    }
    rc = ffkmp_graph_build_video_multi(&graph, sources, &sink, desc, count,
                                       w, h, fmt, tn, td, fn, fd, sn, sd);
    if (rc < 0) {
        kj_throw_ffmpeg(env, rc, "graph_build_video_multi");
        goto done;
    }
    result = kj_graph_result(env, graph, sources, count, sink);
    graph = NULL;
done:
    if (graph != NULL) ffkmp_graph_free(&graph);
    free(desc); free(sources); free(w); free(h); free(fmt); free(tn); free(td);
    free(fn); free(fd); free(sn); free(sd);
    return result;
}

JNIEXPORT jlongArray JNICALL kj_graph_build_audio_multi(
    JNIEnv *env, jclass cls, jstring description, jint count,
    jintArray rates, jintArray formats, jintArray channels,
    jintArray tbns, jintArray tbds, jint out_format, jint out_rate, jint out_channels,
    jlongArray layout_masks, jlong out_layout_mask)
{
    char *desc = NULL;
    int *rate = NULL, *fmt = NULL, *ch = NULL, *tn = NULL, *td = NULL;
    jlong *masks = NULL;
    int32_t nr, nf, nc, ntn, ntd, nm;
    kc_filter_graph *graph = NULL;
    kc_filter_ctx **sources = NULL, *sink = NULL;
    jlongArray result = NULL;
    int rc = -1;
    (void)cls;
    if (count <= 0) {
        kj_throw_handle(env, "audio multi graph requires at least one input");
        return NULL;
    }
    desc = kj_string_dup(env, description);
    sources = (kc_filter_ctx **)calloc((size_t)count, sizeof(kc_filter_ctx *));
    if ((*env)->ExceptionCheck(env)) goto done;
    if (sources == NULL) {
        kj_throw_handle(env, "out of memory allocating audio filter-source views");
        goto done;
    }
    if (kj_ints_dup(env, rates, &rate, &nr) != 0 || kj_ints_dup(env, formats, &fmt, &nf) != 0
        || kj_ints_dup(env, channels, &ch, &nc) != 0 || kj_ints_dup(env, tbns, &tn, &ntn) != 0
        || kj_ints_dup(env, tbds, &td, &ntd) != 0
        || kj_longs_dup(env, layout_masks, &masks, &nm) != 0) goto done;
    if (nr != count || nf != count || nc != count || ntn != count || ntd != count || nm != count) {
        kj_throw_handle(env, "audio multi graph argument-array length mismatch");
        goto done;
    }
    rc = ffkmp_graph_build_audio_multi(&graph, sources, &sink, desc, count,
                                       rate, fmt, ch, tn, td,
                                       out_format, out_rate, out_channels,
                                       (const int64_t *)masks, (int64_t)out_layout_mask);
    if (rc < 0) {
        kj_throw_ffmpeg(env, rc, "graph_build_audio_multi");
        goto done;
    }
    result = kj_graph_result(env, graph, sources, count, sink);
    graph = NULL;
done:
    if (graph != NULL) ffkmp_graph_free(&graph);
    free(desc); free(sources); free(rate); free(fmt); free(ch); free(tn); free(td); free(masks);
    return result;
}

JNIEXPORT void JNICALL kj_graph_free(JNIEnv *env, jclass cls, jlong token)
{
    kc_filter_graph *graph = (kc_filter_graph *)kj_handle_close(token, KJ_KIND_FILTER_GRAPH);
    (void)env; (void)cls;
    if (graph != NULL) ffkmp_graph_free(&graph);
}

JNIEXPORT jint JNICALL kj_graph_send(JNIEnv *env, jclass cls, jlong source_token, jlong frame_token)
{
    kc_filter_ctx *source = (kc_filter_ctx *)kj_handle_get(env, source_token, KJ_KIND_FILTER_CTX);
    kc_frame *frame = NULL;
    (void)cls;
    if (source == NULL) return -1;
    if (frame_token != 0) {
        frame = (kc_frame *)kj_handle_get(env, frame_token, KJ_KIND_FRAME);
        if (frame == NULL) return -1;
    }
    return (jint)ffkmp_graph_send(source, frame);
}

JNIEXPORT jint JNICALL kj_graph_receive(JNIEnv *env, jclass cls, jlong sink_token, jlong frame_token)
{
    kc_filter_ctx *sink = (kc_filter_ctx *)kj_handle_get(env, sink_token, KJ_KIND_FILTER_CTX);
    kc_frame *frame;
    (void)cls;
    if (sink == NULL) return -1;
    frame = (kc_frame *)kj_handle_get(env, frame_token, KJ_KIND_FRAME);
    return frame ? (jint)ffkmp_graph_receive(sink, frame) : -1;
}

JNIEXPORT void JNICALL kj_graph_set_frame_size(JNIEnv *env, jclass cls, jlong sink_token, jint size)
{
    kc_filter_ctx *sink = (kc_filter_ctx *)kj_handle_get(env, sink_token, KJ_KIND_FILTER_CTX);
    (void)cls;
    if (sink != NULL) ffkmp_buffersink_set_frame_size(sink, (unsigned)size);
}

JNIEXPORT jlong JNICALL kj_graph_time_base(JNIEnv *env, jclass cls, jlong sink_token)
{
    kc_filter_ctx *sink = (kc_filter_ctx *)kj_handle_get(env, sink_token, KJ_KIND_FILTER_CTX);
    int n = 0, d = 1;
    (void)cls;
    if (sink != NULL) ffkmp_buffersink_time_base(sink, &n, &d);
    return ((jlong)(uint32_t)n << 32) | (uint32_t)d;
}
