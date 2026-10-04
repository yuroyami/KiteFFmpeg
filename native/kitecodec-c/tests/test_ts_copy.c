/* A stream copy of MPEG-4 Part 2 video into MPEG-TS carries the headers a decoder needs (#159).
 *
 * MPEG-4 Part 2 starts with a Visual Object Sequence, a Visual Object and a Video Object Layer
 * header, which say the picture size and how the pictures are coded. An encoder writing for MP4 or
 * Matroska puts them in the extradata alone, since those containers carry it beside the stream.
 * MPEG-TS has no such place, and its writer copied the packets as they were, so a copy of such a
 * stream into MPEG-TS held no header at all and nothing could decode a picture of it. The patch
 * 0007-mpegts-carry-the-mpeg4-headers-in-the-stream makes the writer put the extradata in front of
 * each keyframe, as it already did for H.264 and HEVC, and leave alone a keyframe that already
 * begins with it.
 *
 * Each case encodes two seconds of 25 fps MPEG-4 Part 2 with a keyframe every ten pictures and
 * global headers, writes it to MP4, and copies it from there the way the Remuxer does. A linked
 * FFmpeg whose tree does not list the patch in lib/kiteffmpeg/ffmpeg-patches.txt, such as a
 * distribution's, runs only the case that holds without it.
 */

#include "harness.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/frame.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef KC_BUILD_FFMPEG_DIR
#define KC_BUILD_FFMPEG_DIR "unknown"
#endif

#define PATCH "0007-mpegts-carry-the-mpeg4-headers-in-the-stream.patch"

#define FRAMES 50
#define GOP 10
#define MAX_PACKETS 64

static char base[480];
static char mp4_path[512];
static char ts_path[512];
static char second_ts_path[512];

static void remove_fixtures(void)
{
    remove(mp4_path);
    remove(ts_path);
    remove(second_ts_path);
}

/* 1 when the tree of the linked FFmpeg lists patch among the patches it was built with. */
static int linked_tree_carries(const char *patch)
{
    char evidence[1024], line[512];
    int found = 0;
    snprintf(evidence, sizeof(evidence), "%s/kiteffmpeg/ffmpeg-patches.txt", KC_BUILD_FFMPEG_DIR);
    FILE *f = fopen(evidence, "r");
    if (!f) return 0;
    while (!found && fgets(line, sizeof(line), f))
        found = strncmp(line, patch, strlen(patch)) == 0;
    fclose(f);
    return found;
}

/* 1 when data holds a Video Object Layer start code, 00 00 01 20 to 00 00 01 2F. */
static int holds_vol(const uint8_t *data, int size)
{
    for (int i = 0; i + 3 < size; i++)
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0xF0) == 0x20) return 1;
    return 0;
}

/* Writes the fixture to mp4_path. With in_band, each keyframe also begins with the extradata, as a
 * copy through FFmpeg's dump_extra filter would have it. Returns 0, or -1 when this FFmpeg cannot
 * write it. */
static int write_mp4(int in_band)
{
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    AVFormatContext *oc = NULL;
    AVCodecContext *enc = NULL;
    AVFrame *frame = NULL;
    AVPacket *pkt = NULL;
    int ok = -1;
    if (!codec || avformat_alloc_output_context2(&oc, NULL, "mp4", mp4_path) < 0) return -1;
    AVStream *st = avformat_new_stream(oc, NULL);
    enc = avcodec_alloc_context3(codec);
    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!st || !enc || !frame || !pkt) goto done;
    enc->width = 64;
    enc->height = 48;
    enc->pix_fmt = AV_PIX_FMT_YUV420P;
    enc->time_base = (AVRational){ 1, 25 };
    enc->framerate = (AVRational){ 25, 1 };
    enc->gop_size = GOP;
    enc->max_b_frames = 0;
    enc->bit_rate = 400000;
    enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER | AV_CODEC_FLAG_BITEXACT;
    if (avcodec_open2(enc, codec, NULL) < 0) goto done;
    if (avcodec_parameters_from_context(st->codecpar, enc) < 0) goto done;
    st->time_base = enc->time_base;
    if (avio_open(&oc->pb, mp4_path, AVIO_FLAG_WRITE) < 0) goto done;
    if (avformat_write_header(oc, NULL) < 0) goto done;
    frame->format = enc->pix_fmt;
    frame->width = enc->width;
    frame->height = enc->height;
    if (av_frame_get_buffer(frame, 0) < 0) goto done;
    for (int i = 0; i <= FRAMES; i++) {
        if (i < FRAMES) {
            if (av_frame_make_writable(frame) < 0) goto done;
            for (int y = 0; y < frame->height; y++)
                for (int x = 0; x < frame->width; x++)
                    frame->data[0][y * frame->linesize[0] + x] = (uint8_t)(x * 3 + y * 2 + i * 7);
            for (int p = 1; p < 3; p++)
                for (int y = 0; y < frame->height / 2; y++)
                    memset(frame->data[p] + y * frame->linesize[p], 128, (size_t)frame->width / 2);
            frame->pts = i;
        }
        if (avcodec_send_frame(enc, i < FRAMES ? frame : NULL) < 0) goto done;
        while (avcodec_receive_packet(enc, pkt) == 0) {
            if (in_band && (pkt->flags & AV_PKT_FLAG_KEY)) {
                const int size = enc->extradata_size + pkt->size;
                AVPacket *joined = av_packet_alloc();
                if (!joined || av_new_packet(joined, size) < 0 || av_packet_copy_props(joined, pkt) < 0) {
                    av_packet_free(&joined);
                    goto done;
                }
                memcpy(joined->data, enc->extradata, (size_t)enc->extradata_size);
                memcpy(joined->data + enc->extradata_size, pkt->data, (size_t)pkt->size);
                av_packet_unref(pkt);
                av_packet_move_ref(pkt, joined);
                av_packet_free(&joined);
            }
            av_packet_rescale_ts(pkt, enc->time_base, st->time_base);
            pkt->stream_index = st->index;
            if (av_interleaved_write_frame(oc, pkt) < 0) goto done;
        }
    }
    if (av_write_trailer(oc) < 0) goto done;
    ok = 0;
done:
    av_packet_free(&pkt);
    av_frame_free(&frame);
    avcodec_free_context(&enc);
    if (oc && oc->pb) avio_closep(&oc->pb);
    avformat_free_context(oc);
    return ok;
}

/* Copies the one stream of in into an MPEG-TS file at out, the way the Remuxer does: the input
 * read with its stream information found, the parameters copied, and every packet written through
 * the interleaver. Returns 0 or the failing FFmpeg status. */
static int copy_into_ts(const char *in, const char *out)
{
    AVFormatContext *ic = NULL, *oc = NULL;
    AVPacket *pkt = av_packet_alloc();
    int rc = pkt ? 0 : AVERROR(ENOMEM);
    if (rc == 0) rc = avformat_open_input(&ic, in, NULL, NULL);
    if (rc == 0) rc = avformat_find_stream_info(ic, NULL);
    if (rc >= 0) rc = avformat_alloc_output_context2(&oc, NULL, "mpegts", out);
    AVStream *st = rc >= 0 ? avformat_new_stream(oc, NULL) : NULL;
    if (rc >= 0 && !st) rc = AVERROR(ENOMEM);
    if (rc >= 0) rc = avcodec_parameters_copy(st->codecpar, ic->streams[0]->codecpar);
    if (rc >= 0) {
        st->codecpar->codec_tag = 0;
        st->time_base = ic->streams[0]->time_base;
        rc = avio_open(&oc->pb, out, AVIO_FLAG_WRITE);
    }
    if (rc >= 0) rc = avformat_write_header(oc, NULL);
    while (rc >= 0 && av_read_frame(ic, pkt) >= 0) {
        if (pkt->stream_index == 0) {
            av_packet_rescale_ts(pkt, ic->streams[0]->time_base, st->time_base);
            rc = av_interleaved_write_frame(oc, pkt);
        }
        av_packet_unref(pkt);
    }
    if (rc >= 0) rc = av_write_trailer(oc);
    av_packet_free(&pkt);
    if (oc && oc->pb) avio_closep(&oc->pb);
    avformat_free_context(oc);
    avformat_close_input(&ic);
    return rc < 0 ? rc : 0;
}

/* The packets of path, read with its stream information found, as a player reads them. */
typedef struct {
    AVPacket *packets[MAX_PACKETS];
    int count;
    int extradata_size;
} packet_list;

static void free_packets(packet_list *list)
{
    for (int i = 0; i < list->count; i++) av_packet_free(&list->packets[i]);
    list->count = 0;
}

static void read_packets(const char *path, packet_list *list)
{
    AVFormatContext *ic = NULL;
    memset(list, 0, sizeof(*list));
    KC_EQ_INT(avformat_open_input(&ic, path, NULL, NULL), 0);
    KC_CHECK(avformat_find_stream_info(ic, NULL) >= 0);
    list->extradata_size = ic->streams[0]->codecpar->extradata_size;
    AVPacket *pkt = av_packet_alloc();
    KC_NOT_NULL(pkt);
    while (av_read_frame(ic, pkt) >= 0) {
        KC_CHECKF(list->count < MAX_PACKETS, "%s holds more than %d packets", path, MAX_PACKETS);
        list->packets[list->count++] = av_packet_clone(pkt);
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    avformat_close_input(&ic);
}

static void expect_same_packets(const packet_list *actual, const packet_list *expected, const char *what)
{
    KC_CHECKF(actual->count == expected->count, "%s holds %d packets, %d expected", what, actual->count,
              expected->count);
    for (int i = 0; i < expected->count; i++) {
        const AVPacket *a = actual->packets[i], *e = expected->packets[i];
        KC_CHECKF(a->size == e->size, "packet %d of %s is %d bytes, %d expected", i, what, a->size, e->size);
        KC_CHECKF(memcmp(a->data, e->data, (size_t)e->size) == 0, "packet %d of %s differs", i, what);
    }
}

/* The number of pictures a decoder opened from the stream information of path decodes from it. */
static int decoded_pictures(const char *path, int *width, int *height)
{
    AVFormatContext *ic = NULL;
    int pictures = 0;
    KC_EQ_INT(avformat_open_input(&ic, path, NULL, NULL), 0);
    KC_CHECK(avformat_find_stream_info(ic, NULL) >= 0);
    const AVCodec *codec = avcodec_find_decoder(ic->streams[0]->codecpar->codec_id);
    KC_NOT_NULL(codec);
    AVCodecContext *dec = avcodec_alloc_context3(codec);
    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    KC_CHECK(dec && pkt && frame);
    KC_CHECK(avcodec_parameters_to_context(dec, ic->streams[0]->codecpar) >= 0);
    KC_EQ_INT(avcodec_open2(dec, codec, NULL), 0);
    for (int more = 1; more;) {
        more = av_read_frame(ic, pkt) >= 0;
        if (more && pkt->stream_index != 0) {
            av_packet_unref(pkt);
            continue;
        }
        avcodec_send_packet(dec, more ? pkt : NULL);
        av_packet_unref(pkt);
        while (avcodec_receive_frame(dec, frame) == 0) {
            pictures++;
            *width = frame->width;
            *height = frame->height;
            av_frame_unref(frame);
        }
    }
    av_frame_free(&frame);
    av_packet_free(&pkt);
    avcodec_free_context(&dec);
    avformat_close_input(&ic);
    return pictures;
}

static void case_headers_already_in_the_stream_are_not_doubled(void)
{
    packet_list source, copy, second;
    kc_case("a keyframe that already begins with the headers reaches MPEG-TS and a second copy unchanged");
    KC_EQ_INT(write_mp4(1), 0);
    read_packets(mp4_path, &source);
    KC_EQ_INT(source.count, FRAMES);
    KC_CHECKF(holds_vol(source.packets[0]->data, source.packets[0]->size),
              "the first keyframe of the fixture carries no header, so the case proves nothing");
    KC_EQ_INT(copy_into_ts(mp4_path, ts_path), 0);
    read_packets(ts_path, &copy);
    expect_same_packets(&copy, &source, "the copy into MPEG-TS");
    /* A copy out of MPEG-TS finds the headers in the stream and makes them the extradata, which is
     * the case the writer must not double. */
    KC_CHECKF(copy.extradata_size > 0, "the MPEG-TS copy yields no extradata, so the second copy proves nothing");
    KC_EQ_INT(copy_into_ts(ts_path, second_ts_path), 0);
    read_packets(second_ts_path, &second);
    expect_same_packets(&second, &copy, "the second copy into MPEG-TS");
    kc_detail("%d packets, extradata %d bytes", copy.count, copy.extradata_size);
    free_packets(&source);
    free_packets(&copy);
    free_packets(&second);
}

static void case_an_mp4_copy_carries_the_headers(void)
{
    packet_list source, copy;
    int width = 0, height = 0;
    kc_case("a copy from MP4 into MPEG-TS carries the headers in front of each keyframe and decodes every picture");
    KC_EQ_INT(write_mp4(0), 0);
    read_packets(mp4_path, &source);
    KC_EQ_INT(source.count, FRAMES);
    KC_CHECKF(source.extradata_size > 0 && !holds_vol(source.packets[0]->data, source.packets[0]->size),
              "the fixture's headers are not in its extradata alone, so the case proves nothing");
    KC_EQ_INT(copy_into_ts(mp4_path, ts_path), 0);
    read_packets(ts_path, &copy);
    KC_EQ_INT(copy.count, FRAMES);
    int keyframes = 0;
    for (int i = 0; i < copy.count; i++) {
        const AVPacket *p = copy.packets[i];
        const int key = (p->flags & AV_PKT_FLAG_KEY) != 0;
        keyframes += key;
        KC_CHECKF(holds_vol(p->data, p->size) == key, "packet %d, %s, %s the headers", i,
                  key ? "a keyframe" : "not a keyframe", key ? "lacks" : "carries");
    }
    KC_EQ_INT(keyframes, FRAMES / GOP);
    KC_EQ_INT(decoded_pictures(ts_path, &width, &height), FRAMES);
    KC_EQ_INT(width, 64);
    KC_EQ_INT(height, 48);
    kc_detail("%d pictures, %d keyframes", FRAMES, keyframes);
    free_packets(&source);
    free_packets(&copy);
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_ts_copy");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    const char *slash = tmp[strlen(tmp) - 1] == '/' ? "" : "/";
    snprintf(base, sizeof(base), "%s%skc_ts_copy_%ld", tmp, slash, (long)getpid());
    snprintf(mp4_path, sizeof(mp4_path), "%s.mp4", base);
    snprintf(ts_path, sizeof(ts_path), "%s.ts", base);
    snprintf(second_ts_path, sizeof(second_ts_path), "%s-2.ts", base);
    atexit(remove_fixtures);

    case_headers_already_in_the_stream_are_not_doubled();

    if (!linked_tree_carries(PATCH)) {
        kc_note("the linked FFmpeg's tree does not list %s, so the MP4 copy case did not run", PATCH);
        return kc_suite_end();
    }
    case_an_mp4_copy_carries_the_headers();

    return kc_suite_end();
}
