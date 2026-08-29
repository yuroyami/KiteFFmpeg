/* Opaque names for the FFmpeg objects carried across KiteFFmpeg's C boundary.
 *
 * This header deliberately includes no FFmpeg header. Each alias names the same C struct tag
 * that FFmpeg later typedefs, so the aliases can coexist with the source-compatible AV* surface
 * while giving cinterop a KiteFFmpeg-owned vocabulary for the wrappers added beside it.
 */

#ifndef KITECODEC_HANDLES_H
#define KITECODEC_HANDLES_H

struct AVCodec;
typedef struct AVCodec kc_codec;

struct AVCodecContext;
typedef struct AVCodecContext kc_codec_ctx;

struct AVCodecParameters;
typedef struct AVCodecParameters kc_codec_par;

struct AVDictionary;
typedef struct AVDictionary kc_dict;

struct AVDictionaryEntry;
typedef struct AVDictionaryEntry kc_dict_entry;

struct AVFilterContext;
typedef struct AVFilterContext kc_filter_ctx;

struct AVFilterGraph;
typedef struct AVFilterGraph kc_filter_graph;

struct AVFormatContext;
typedef struct AVFormatContext kc_fmt_ctx;

struct AVFrame;
typedef struct AVFrame kc_frame;

struct AVPacket;
typedef struct AVPacket kc_packet;

struct AVStream;
typedef struct AVStream kc_stream;

#endif
