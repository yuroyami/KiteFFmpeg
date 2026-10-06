/* The custom-io read contract, from the C side.
 *
 * A read callback may return at most the len it was given, and the bridge refuses a larger count
 * as an I/O error. Each case below serves a WAV from memory and answers its reads with a wrong
 * count; the open must fail and hand nothing back. The asan variant also checks every access FFmpeg
 * makes on the way. The first case is the control: an honest source opens.
 *
 * FFmpeg's size probe reaches the reader each time FFmpeg asks, so a source whose size moves
 * after the open, as a file still being written does, answers with what it holds now (#177).
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>

/* A canonical 16-bit stereo 48 kHz WAV header over 256 KiB of silence: large enough that FFmpeg's
   own reads fill its whole buffer. */
#define KC_WAV_DATA (256 * 1024)

typedef enum { ANSWER_HONEST, ANSWER_PLUS_16, ANSWER_FULL_PLUS_1, ANSWER_INT_MAX } answer_kind;

typedef struct {
    unsigned char *bytes;
    int64_t size;
    int64_t position;
    answer_kind answer;
    /* True when the size probe goes unanswered, as from a reader that cannot tell. */
    int size_unknown;
} memory_source;

static void make_wav(memory_source *m, answer_kind answer)
{
    static const unsigned char header[44] = {
        'R', 'I', 'F', 'F', 0x24, 0x00, 0x04, 0, 'W', 'A', 'V', 'E',
        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 2, 0,
        0x80, 0xBB, 0, 0, 0x00, 0xEE, 0x02, 0, 4, 0, 16, 0,
        'd', 'a', 't', 'a', 0x00, 0x00, 0x04, 0,
    };
    m->size = (int64_t)sizeof(header) + KC_WAV_DATA;
    m->bytes = calloc(1, (size_t)m->size);
    KC_NOT_NULL(m->bytes);
    memcpy(m->bytes, header, sizeof(header));
    m->position = 0;
    m->answer = answer;
    m->size_unknown = 0;
}

/* Fills what it honestly can, then answers with the count its kind names. */
static int memory_read(void *opaque, unsigned char *buf, int len)
{
    memory_source *m = (memory_source *)opaque;
    int64_t left = m->size - m->position;
    int n = left < len ? (int)left : len;
    if (n <= 0) return KC_IO_EOF;
    memcpy(buf, m->bytes + m->position, (size_t)n);
    m->position += n;
    switch (m->answer) {
    case ANSWER_PLUS_16: return n + 16;
    case ANSWER_FULL_PLUS_1: return len + 1;
    case ANSWER_INT_MAX: return INT_MAX;
    case ANSWER_HONEST: break;
    }
    return n;
}

static int64_t memory_seek(void *opaque, int64_t offset, int whence)
{
    memory_source *m = (memory_source *)opaque;
    /* FFmpeg's size probe reaches the reader and moves nothing (#177). */
    if (whence == AVSEEK_SIZE) return m->size_unknown ? KC_IO_ERR : m->size;
    int64_t target = whence == SEEK_SET ? offset : whence == SEEK_CUR ? m->position + offset : m->size + offset;
    if (target < 0 || target > m->size) return KC_IO_ERR;
    m->position = target;
    return target;
}

static void case_an_honest_source_opens(void)
{
    memory_source source;
    kc_fmt_ctx *ctx = NULL;

    kc_case("a source that answers with what it read opens");
    make_wav(&source, ANSWER_HONEST);
    KC_EQ_INT(ffkmp_fmt_open_input_io(&ctx, &source, memory_read, memory_seek, source.size,
                                      NULL, NULL, 0, NULL, NULL), 0);
    KC_NOT_NULL(ctx);
    ffkmp_fmt_close_input_io(&ctx);
    KC_NULL(ctx);
    free(source.bytes);
}

static void case_an_over_count_fails_the_open(answer_kind answer, const char *what)
{
    memory_source source;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    int rc;

    kc_case("a source that answers %s fails the open and hands nothing back", what);
    make_wav(&source, answer);
    rc = ffkmp_fmt_open_input_io(&ctx, &source, memory_read, memory_seek, source.size,
                                 NULL, NULL, 0, NULL, NULL);
    kc_detail("rc=%d", rc);
    KC_CHECK(rc < 0);
    KC_NULL(ctx);
    free(source.bytes);
}

static void case_a_source_that_grows_reports_its_current_size(void)
{
    memory_source source;
    kc_fmt_ctx *ctx = NULL;
    int64_t at_open;

    kc_case("a source whose size moves after the open answers FFmpeg with what it holds now");
    make_wav(&source, ANSWER_HONEST);
    at_open = source.size;
    KC_EQ_INT(ffkmp_fmt_open_input_io(&ctx, &source, memory_read, memory_seek, source.size,
                                      NULL, NULL, 0, NULL, NULL), 0);
    KC_NOT_NULL(ctx);
    KC_EQ_INT(avio_size(ctx->pb), at_open);
    /* The bytes stay where they are; only the size the reader states moves, which is all the probe sees. */
    source.size = at_open - 4096;
    kc_detail("size at open %lld, now %lld", (long long)at_open, (long long)avio_size(ctx->pb));
    KC_EQ_INT(avio_size(ctx->pb), at_open - 4096);
    /* A reader that cannot tell keeps the size it gave at open. */
    source.size_unknown = 1;
    KC_EQ_INT(avio_size(ctx->pb), at_open);
    ffkmp_fmt_close_input_io(&ctx);
    free(source.bytes);
}

int main(void)
{
    kc_suite_begin("test_input_io");

    case_an_honest_source_opens();
    case_a_source_that_grows_reports_its_current_size();
    case_an_over_count_fails_the_open(ANSWER_PLUS_16, "16 bytes more than it read");
    case_an_over_count_fails_the_open(ANSWER_FULL_PLUS_1, "one byte more than the whole buffer");
    case_an_over_count_fails_the_open(ANSWER_INT_MAX, "INT_MAX");

    return kc_suite_end();
}
