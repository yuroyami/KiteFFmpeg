/* What the linked FFmpeg contains, read from FFmpeg's own registries.
 *
 * A measurement of the build rather than of its recipe: a component that failed to compile is not
 * in these registries even when configure was asked for it. */

#include "kitecodec_helpers.h"

#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavutil/error.h>

/* Appends name to buf, after a newline when it is not the first, counting every byte even when
   buf is full, so the final count is the length the whole list needs. */
static void kc_append_name(char *buf, int cap, int *len, const char *name) {
    if (!name) return;
    if (*len > 0) {
        if (*len < cap) buf[*len] = '\n';
        (*len)++;
    }
    for (const char *c = name; *c; c++) {
        if (*len < cap) buf[*len] = *c;
        (*len)++;
    }
}

KC_API int ffkmp_component_names(int kind, char *buf, int cap) {
    if (!KC_GATE_OPEN()) return AVERROR_EXTERNAL;
    if (cap < 0 || (cap > 0 && !buf)) return AVERROR(EINVAL);
    int len = 0;
    void *it = NULL;
    switch (kind) {
    case KC_COMPONENT_DECODERS:
    case KC_COMPONENT_ENCODERS: {
        const AVCodec *codec;
        while ((codec = av_codec_iterate(&it))) {
            int wanted = kind == KC_COMPONENT_DECODERS ? av_codec_is_decoder(codec) : av_codec_is_encoder(codec);
            if (wanted) kc_append_name(buf, cap, &len, codec->name);
        }
        break;
    }
    case KC_COMPONENT_DEMUXERS: {
        const AVInputFormat *format;
        while ((format = av_demuxer_iterate(&it))) kc_append_name(buf, cap, &len, format->name);
        break;
    }
    case KC_COMPONENT_MUXERS: {
        const AVOutputFormat *format;
        while ((format = av_muxer_iterate(&it))) kc_append_name(buf, cap, &len, format->name);
        break;
    }
    case KC_COMPONENT_FILTERS: {
        const AVFilter *filter;
        while ((filter = av_filter_iterate(&it))) kc_append_name(buf, cap, &len, filter->name);
        break;
    }
    case KC_COMPONENT_INPUT_PROTOCOLS: {
        const char *protocol;
        while ((protocol = avio_enum_protocols(&it, 0))) kc_append_name(buf, cap, &len, protocol);
        break;
    }
    case KC_COMPONENT_BITSTREAM_FILTERS: {
        const AVBitStreamFilter *bsf;
        while ((bsf = av_bsf_iterate(&it))) kc_append_name(buf, cap, &len, bsf->name);
        break;
    }
    default:
        return AVERROR(EINVAL);
    }
    if (cap > 0) buf[len < cap ? len : cap - 1] = '\0';
    return len;
}
