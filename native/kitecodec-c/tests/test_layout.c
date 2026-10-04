/* The stream count and the programme table as one number (#151).
 *
 * A live transport stream adds a stream, or moves one into or out of a programme, while it reads, and
 * FFmpeg raises no flag for either, so a reader compares ffkmp_fmt_layout_stamp after every read and
 * reads the tables again only when it moved. These cases build the tables in memory, the way the
 * transport stream demuxer changes them, and check that every change a reader must see moves the
 * number and that the same tables always answer the same one. The Kotlin suites read a real stream
 * whose sound starts after its picture.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavformat/avformat.h>
#include <libavutil/dict.h>

static AVFormatContext *with_streams(int count)
{
    AVFormatContext *ctx = avformat_alloc_context();
    KC_NOT_NULL(ctx);
    for (int i = 0; i < count; i++) KC_NOT_NULL(avformat_new_stream(ctx, NULL));
    return ctx;
}

/* A programme as a transport stream's tables make one: an id, the number its association table
   states, its streams, and the channel name its service table gives. */
static AVProgram *with_program(AVFormatContext *ctx, int id, int number, const char *name, int first, int count)
{
    AVProgram *program = av_new_program(ctx, id);
    KC_NOT_NULL(program);
    program->program_num = number;
    for (int i = first; i < first + count; i++) av_program_add_stream_index(ctx, id, (unsigned)i);
    if (name) KC_EQ_INT(av_dict_set(&program->metadata, "service_name", name, 0), 0);
    return program;
}

static kc_fmt_ctx *as_kc(AVFormatContext *ctx) { return (kc_fmt_ctx *)ctx; }

static void case_no_context_answers_zero(void)
{
    kc_case("a NULL context answers 0");
    KC_EQ_I64(ffkmp_fmt_layout_stamp(NULL), 0);
}

static void case_the_same_tables_answer_the_same_number(void)
{
    AVFormatContext *a = with_streams(2);
    AVFormatContext *b = with_streams(2);
    with_program(a, 1, 1, "ChannelA", 0, 2);
    with_program(b, 1, 1, "ChannelA", 0, 2);

    kc_case("the same tables answer the same number, asked again or in another context");
    KC_EQ_I64(ffkmp_fmt_layout_stamp(as_kc(a)), ffkmp_fmt_layout_stamp(as_kc(a)));
    KC_EQ_I64(ffkmp_fmt_layout_stamp(as_kc(a)), ffkmp_fmt_layout_stamp(as_kc(b)));
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(a)) != 0);

    kc_case("a stream's own parameters are not part of it, because a reader reads those itself");
    int64_t before = ffkmp_fmt_layout_stamp(as_kc(a));
    a->streams[1]->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
    a->streams[1]->codecpar->sample_rate = 48000;
    KC_EQ_I64(ffkmp_fmt_layout_stamp(as_kc(a)), before);

    avformat_free_context(a);
    avformat_free_context(b);
}

static void case_a_stream_added_moves_it(void)
{
    AVFormatContext *ctx = with_streams(1);
    with_program(ctx, 1, 1, NULL, 0, 1);
    int64_t before = ffkmp_fmt_layout_stamp(as_kc(ctx));

    kc_case("a stream FFmpeg adds moves it, before any programme names the stream");
    KC_NOT_NULL(avformat_new_stream(ctx, NULL));
    int64_t added = ffkmp_fmt_layout_stamp(as_kc(ctx));
    KC_CHECK(added != before);

    kc_case("the stream joining its programme moves it again");
    av_program_add_stream_index(ctx, 1, 1);
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(ctx)) != added);

    kc_case("the stream leaving its programme, as a new table that no longer lists it makes it, moves it back");
    ctx->programs[0]->nb_stream_indexes = 1;
    KC_EQ_I64(ffkmp_fmt_layout_stamp(as_kc(ctx)), added);
    avformat_free_context(ctx);
}

static void case_a_programme_changed_moves_it(void)
{
    AVFormatContext *ctx = with_streams(4);
    AVProgram *first = with_program(ctx, 1, 1, "ChannelA", 0, 2);
    int64_t one = ffkmp_fmt_layout_stamp(as_kc(ctx));

    kc_case("a programme added moves it");
    AVProgram *second = with_program(ctx, 2, 2, "ChannelB", 2, 2);
    int64_t two = ffkmp_fmt_layout_stamp(as_kc(ctx));
    KC_CHECK(two != one);

    kc_case("a programme renamed moves it, and the old name moves it back");
    KC_EQ_INT(av_dict_set(&second->metadata, "service_name", "ChannelC", 0), 0);
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(ctx)) != two);
    KC_EQ_INT(av_dict_set(&second->metadata, "service_name", "ChannelB", 0), 0);
    KC_EQ_I64(ffkmp_fmt_layout_stamp(as_kc(ctx)), two);

    kc_case("a programme given a new number moves it");
    first->program_num = 7;
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(ctx)) != two);
    first->program_num = 1;

    kc_case("the same streams in another order move it");
    unsigned swap = second->stream_index[0];
    second->stream_index[0] = second->stream_index[1];
    second->stream_index[1] = swap;
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(ctx)) != two);
    avformat_free_context(ctx);
}

static void case_a_tag_cannot_slide_to_the_next_programme(void)
{
    AVFormatContext *a = with_streams(2);
    AVFormatContext *b = with_streams(2);
    with_program(a, 1, 1, "Channel", 0, 1);
    with_program(a, 2, 2, NULL, 1, 1);
    with_program(b, 1, 1, NULL, 0, 1);
    with_program(b, 2, 2, "Channel", 1, 1);

    kc_case("a name moved from one programme to the next moves it");
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(a)) != ffkmp_fmt_layout_stamp(as_kc(b)));

    kc_case("a key and a value split differently move it");
    AVFormatContext *c = with_streams(1);
    AVFormatContext *d = with_streams(1);
    AVProgram *pc = with_program(c, 1, 1, NULL, 0, 1);
    AVProgram *pd = with_program(d, 1, 1, NULL, 0, 1);
    KC_EQ_INT(av_dict_set(&pc->metadata, "ab", "c", 0), 0);
    KC_EQ_INT(av_dict_set(&pd->metadata, "a", "bc", 0), 0);
    KC_CHECK(ffkmp_fmt_layout_stamp(as_kc(c)) != ffkmp_fmt_layout_stamp(as_kc(d)));

    avformat_free_context(a);
    avformat_free_context(b);
    avformat_free_context(c);
    avformat_free_context(d);
}

int main(void)
{
    kc_suite_begin("test_layout");

    case_no_context_answers_zero();
    case_the_same_tables_answer_the_same_number();
    case_a_stream_added_moves_it();
    case_a_programme_changed_moves_it();
    case_a_tag_cannot_slide_to_the_next_programme();

    return kc_suite_end();
}
