/* The custom output bridge behind ffkmp_fmt_alloc_output_io: a muxer writes a whole Matroska file
 * into caller memory, a muxer that has to seek refuses an unseekable sink, and a sink that refuses
 * a write fails the operation that wrote. */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* A growable memory file: bytes, a size and a position that seeks can move backwards. */
typedef struct {
    unsigned char *data;
    int64_t size;
    int64_t position;
    int writes;
    int seeks;
    int fail;
} memory_sink;

static int memory_write(void *opaque, const unsigned char *buf, int len)
{
    memory_sink *m = (memory_sink *)opaque;
    if (m->fail) return KC_IO_ERR;
    if (m->position + len > m->size) {
        unsigned char *grown = realloc(m->data, (size_t)(m->position + len));
        if (!grown) return KC_IO_ERR;
        m->data = grown;
        m->size = m->position + len;
    }
    memcpy(m->data + m->position, buf, (size_t)len);
    m->position += len;
    m->writes++;
    return 0;
}

static int64_t memory_seek(void *opaque, int64_t offset, int whence)
{
    memory_sink *m = (memory_sink *)opaque;
    if (whence != SEEK_SET || offset < 0 || offset > m->size) return KC_IO_ERR;
    m->position = offset;
    m->seeks++;
    return offset;
}

/* One mono 8 kHz PCM stream, which every container here accepts. */
static void add_pcm_stream(kc_fmt_ctx *ctx)
{
    AVStream *st = (AVStream *)ffkmp_fmt_new_stream(ctx, NULL);
    KC_NOT_NULL(st);
    st->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
    st->codecpar->codec_id = AV_CODEC_ID_PCM_S16LE;
    st->codecpar->sample_rate = 8000;
    st->codecpar->format = AV_SAMPLE_FMT_S16;
    st->codecpar->bits_per_coded_sample = 16;
    av_channel_layout_default(&st->codecpar->ch_layout, 1);
    st->time_base = (AVRational){ 1, 8000 };
}

static int write_packets(kc_fmt_ctx *ctx, int count)
{
    int i, rc = 0;
    for (i = 0; i < count && rc >= 0; i++) {
        AVPacket *p = av_packet_alloc();
        KC_NOT_NULL(p);
        KC_EQ_INT(av_new_packet(p, 320), 0);
        memset(p->data, i, 320);
        p->pts = p->dts = (int64_t)i * 160;
        p->duration = 160;
        p->stream_index = 0;
        rc = ffkmp_fmt_write_frame(ctx, p);
        av_packet_free(&p);
    }
    return rc;
}

static void case_refusals(void)
{
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    memory_sink m = { 0 };

    kc_case("NULL arguments and an empty format are refused and leave the output NULL");
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(NULL, "matroska", &m, memory_write, NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, "matroska", &m, NULL, NULL), AVERROR(EINVAL));
    KC_NULL(ctx);
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, "", &m, memory_write, NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, NULL, &m, memory_write, NULL), AVERROR(EINVAL));
    KC_CHECK(ffkmp_fmt_alloc_output_io(&ctx, "no_such_format", &m, memory_write, NULL) < 0);
    KC_NULL(ctx);
    KC_EQ_INT(ffkmp_fmt_free_output_io(NULL), 0);
    KC_NULL(ffkmp_fmt_output_io_opaque(NULL));
}

static void case_a_matroska_file_lands_in_memory(void)
{
    kc_fmt_ctx *ctx = NULL;
    memory_sink m = { 0 };
    static const unsigned char ebml[4] = { 0x1A, 0x45, 0xDF, 0xA3 };

    kc_case("a seekable memory sink receives a whole Matroska file, header to trailer");
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, "matroska", &m, memory_write, memory_seek), 0);
    KC_EQ_PTR(ffkmp_fmt_output_io_opaque(ctx), &m);
    add_pcm_stream(ctx);
    KC_EQ_INT(ffkmp_fmt_write_header(ctx), 0);
    KC_EQ_INT(write_packets(ctx, 5), 0);
    KC_EQ_INT(ffkmp_fmt_write_trailer(ctx), 0);
    KC_EQ_INT(ffkmp_fmt_free_output_io(&ctx), 0);
    KC_NULL(ctx);
    kc_detail("size=%lld writes=%d seeks=%d", (long long)m.size, m.writes, m.seeks);
    KC_CHECK(m.size > 5 * 320);
    KC_CHECK(memcmp(m.data, ebml, sizeof(ebml)) == 0);
    KC_CHECK(m.seeks > 0);
    free(m.data);
}

static void case_a_muxer_that_seeks_refuses_an_unseekable_sink(void)
{
    kc_fmt_ctx *ctx = NULL;
    memory_sink m = { 0 };

    kc_case("MP4 without fragments refuses a sink with no seek; fragmented MP4 writes");
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, "mp4", &m, memory_write, NULL), 0);
    add_pcm_stream(ctx);
    KC_CHECK(ffkmp_fmt_write_header(ctx) < 0);
    ffkmp_fmt_free_output_io(&ctx);
    free(m.data);

    memset(&m, 0, sizeof(m));
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, "mp4", &m, memory_write, NULL), 0);
    KC_EQ_INT(ffkmp_fmt_set_opt(ctx, "movflags", "frag_keyframe+empty_moov"), 0);
    add_pcm_stream(ctx);
    KC_EQ_INT(ffkmp_fmt_write_header(ctx), 0);
    KC_EQ_INT(write_packets(ctx, 5), 0);
    /* The fragmented MP4 trailer answers with a positive byte count, which is success. */
    KC_CHECK(ffkmp_fmt_write_trailer(ctx) >= 0);
    KC_EQ_INT(ffkmp_fmt_free_output_io(&ctx), 0);
    KC_EQ_INT(m.seeks, 0);
    KC_CHECK(m.size > 5 * 320);
    free(m.data);
}

static void case_a_refused_write_fails_the_operation(void)
{
    kc_fmt_ctx *ctx = NULL;
    memory_sink m = { 0 };

    kc_case("a sink that refuses a write fails the write that reached it, and the free reports it");
    KC_EQ_INT(ffkmp_fmt_alloc_output_io(&ctx, "matroska", &m, memory_write, memory_seek), 0);
    add_pcm_stream(ctx);
    KC_EQ_INT(ffkmp_fmt_write_header(ctx), 0);
    m.fail = 1;
    /* The bridge buffers 64 KiB, so enough packets go in to force a write through it. */
    KC_CHECK(write_packets(ctx, 400) < 0 || ffkmp_fmt_write_trailer(ctx) < 0);
    KC_EQ_INT(ffkmp_fmt_free_output_io(&ctx), AVERROR(EIO));
    KC_NULL(ctx);
    free(m.data);
}

int main(void)
{
    kc_suite_begin("test_output_io");

    case_refusals();
    case_a_matroska_file_lands_in_memory();
    case_a_muxer_that_seeks_refuses_an_unseekable_sink();
    case_a_refused_write_fails_the_operation();

    return kc_suite_end();
}
