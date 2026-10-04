/* Tags that change during playback, ffkmp_fmt_take_tag_changes (#135).
 *
 * FFmpeg raises a metadata flag on the context or on a stream when a read applies new tags, and
 * leaves lowering it to the caller. The helper answers which were up for the context and for one
 * stream, lowers exactly those, and leaves every other event bit alone, a stream's "new packets"
 * among them. These cases raise the flags by hand on an open WAV so each rule is checked on its
 * own; the demuxers that raise them for real, chained Ogg, ADTS with ID3 and MPEG-TS timed ID3, are
 * covered by the Kotlin contract suite.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <libavformat/avformat.h>

static char wav_path[1024];

/* A 44-byte canonical WAV header and 4096 bytes of 16-bit stereo silence at 48 kHz. */
static void write_silent_wav(const char *path)
{
    static const unsigned char header[44] = {
        'R', 'I', 'F', 'F', 0x24, 0x10, 0, 0, 'W', 'A', 'V', 'E',
        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 2, 0,
        0x80, 0xBB, 0, 0, 0x00, 0xEE, 0x02, 0, 4, 0, 16, 0,
        'd', 'a', 't', 'a', 0x00, 0x10, 0, 0,
    };
    unsigned char silence[4096];
    FILE *file = fopen(path, "wb");
    KC_CHECKF(file != NULL, "cannot write %s", path);
    memset(silence, 0, sizeof(silence));
    KC_CHECK(fwrite(header, 1, sizeof(header), file) == sizeof(header));
    KC_CHECK(fwrite(silence, 1, sizeof(silence), file) == sizeof(silence));
    KC_CHECK(fclose(file) == 0);
}

static void remove_wav(void) { remove(wav_path); }

/* The WAV's own stream plus a second one, so a case can tell one stream's flag from another's. */
static AVFormatContext *open_two_streams(void)
{
    AVFormatContext *ctx = NULL;
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, wav_path), 0);
    KC_NOT_NULL(ctx);
    KC_NOT_NULL(avformat_new_stream(ctx, NULL));
    KC_EQ_INT((int)ctx->nb_streams, 2);
    ctx->event_flags = 0;
    ctx->streams[0]->event_flags = 0;
    ctx->streams[1]->event_flags = 0;
    return ctx;
}

static void case_null_answers_nothing(void)
{
    kc_case("a NULL context answers 0");
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(NULL, 0), 0);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(NULL, -1), 0);
}

static void case_nothing_raised_answers_nothing(void)
{
    AVFormatContext *ctx = open_two_streams();

    kc_case("with no flag up the answer is 0 for a stream, for every stream and for none");
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 0), 0);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, -1), 0);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 7), 0);
    ffkmp_fmt_close_input(&ctx);
}

static void case_the_container_change_is_taken_once(void)
{
    AVFormatContext *ctx = open_two_streams();

    kc_case("a container change is answered once and lowered, whichever stream is named");
    ctx->event_flags |= AVFMT_EVENT_FLAG_METADATA_UPDATED;
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 1), KC_TAGS_CONTAINER);
    KC_EQ_INT(ctx->event_flags & AVFMT_EVENT_FLAG_METADATA_UPDATED, 0);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 1), 0);
    ffkmp_fmt_close_input(&ctx);
}

static void case_a_stream_change_is_lowered_for_that_stream_only(void)
{
    AVFormatContext *ctx = open_two_streams();

    kc_case("a stream change is answered for its own stream and the other bits stay up");
    ctx->streams[0]->event_flags |= AVSTREAM_EVENT_FLAG_METADATA_UPDATED | AVSTREAM_EVENT_FLAG_NEW_PACKETS;
    ctx->streams[1]->event_flags |= AVSTREAM_EVENT_FLAG_METADATA_UPDATED;
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 0), KC_TAGS_STREAM);
    KC_EQ_INT(ctx->streams[0]->event_flags, AVSTREAM_EVENT_FLAG_NEW_PACKETS);
    KC_EQ_INT(ctx->streams[1]->event_flags, AVSTREAM_EVENT_FLAG_METADATA_UPDATED);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 0), 0);

    kc_case("both changes come back together when both are up");
    ctx->event_flags |= AVFMT_EVENT_FLAG_METADATA_UPDATED;
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 1), KC_TAGS_CONTAINER | KC_TAGS_STREAM);
    KC_EQ_INT(ctx->streams[1]->event_flags, 0);
    ffkmp_fmt_close_input(&ctx);
}

static void case_an_index_out_of_range_answers_for_the_context_alone(void)
{
    AVFormatContext *ctx = open_two_streams();

    kc_case("an index out of range leaves every stream's change up");
    ctx->streams[0]->event_flags |= AVSTREAM_EVENT_FLAG_METADATA_UPDATED;
    ctx->streams[1]->event_flags |= AVSTREAM_EVENT_FLAG_METADATA_UPDATED;
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, 2), 0);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, -2), 0);
    KC_EQ_INT(ctx->streams[0]->event_flags, AVSTREAM_EVENT_FLAG_METADATA_UPDATED);
    KC_EQ_INT(ctx->streams[1]->event_flags, AVSTREAM_EVENT_FLAG_METADATA_UPDATED);
    ffkmp_fmt_close_input(&ctx);
}

static void case_minus_one_lowers_every_stream(void)
{
    AVFormatContext *ctx = open_two_streams();

    kc_case("-1 lowers every stream's change, answers it once and keeps the other bits");
    ctx->event_flags |= AVFMT_EVENT_FLAG_METADATA_UPDATED;
    ctx->streams[0]->event_flags |= AVSTREAM_EVENT_FLAG_METADATA_UPDATED;
    ctx->streams[1]->event_flags |= AVSTREAM_EVENT_FLAG_METADATA_UPDATED | AVSTREAM_EVENT_FLAG_NEW_PACKETS;
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, -1), KC_TAGS_CONTAINER | KC_TAGS_STREAM);
    KC_EQ_INT(ctx->event_flags, 0);
    KC_EQ_INT(ctx->streams[0]->event_flags, 0);
    KC_EQ_INT(ctx->streams[1]->event_flags, AVSTREAM_EVENT_FLAG_NEW_PACKETS);
    KC_EQ_INT(ffkmp_fmt_take_tag_changes(ctx, -1), 0);
    ffkmp_fmt_close_input(&ctx);
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_tag_changes");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    snprintf(wav_path, sizeof(wav_path), "%s%skc_tags_%ld.wav", tmp,
             tmp[strlen(tmp) - 1] == '/' ? "" : "/", (long)getpid());
    write_silent_wav(wav_path);
    atexit(remove_wav);

    case_null_answers_nothing();
    case_nothing_raised_answers_nothing();
    case_the_container_change_is_taken_once();
    case_a_stream_change_is_lowered_for_that_stream_only();
    case_an_index_out_of_range_answers_for_the_context_alone();
    case_minus_one_lowers_every_stream();

    return kc_suite_end();
}
