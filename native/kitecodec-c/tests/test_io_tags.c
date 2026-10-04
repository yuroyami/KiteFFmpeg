/* Tags a caller's bytes bring, through the input bridge's tags_fn (#168).
 *
 * A source that reads a station itself takes the title blocks out of the bytes and hands each title
 * to FFmpeg, which reads it as the input's "metadata" option after the next packet, as it reads the
 * titles of its own http. Each case serves a WAV from memory without a size or a seek, as a radio
 * comes, and stops its reads at the byte where its next tags belong, as FFmpeg's http stops at every
 * title block. The Kotlin contract suite covers the same door through MediaByteSource.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavutil/opt.h>

#define KC_WAV_HEADER 44
#define KC_WAV_DATA (4 * 1024 * 1024)
/* Where the second song starts: inside the data, well past the open, whose probe reads about five
   seconds of audio, which is 864 KiB of this one. */
#define KC_SONG_BYTE (KC_WAV_HEADER + 2 * 1024 * 1024 + 1000)

typedef struct {
    unsigned char *bytes;
    int64_t size;
    int64_t position;
    /* Where the last read started, which is where the tags it brought belong. */
    int64_t read_start;
    int tags_asked;
    int fail_at_song;
} radio;

static void make_radio(radio *r)
{
    static const unsigned char header[KC_WAV_HEADER] = {
        'R', 'I', 'F', 'F', 0x24, 0x00, 0x40, 0, 'W', 'A', 'V', 'E',
        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 2, 0,
        0x80, 0xBB, 0, 0, 0x00, 0xEE, 0x02, 0, 4, 0, 16, 0,
        'd', 'a', 't', 'a', 0x00, 0x00, 0x40, 0,
    };
    memset(r, 0, sizeof(*r));
    r->size = KC_WAV_HEADER + KC_WAV_DATA;
    r->bytes = calloc(1, (size_t)r->size);
    KC_NOT_NULL(r->bytes);
    memcpy(r->bytes, header, sizeof(header));
}

/* Stops a read at the second song's byte, so the read after it starts there. */
static int radio_read(void *opaque, unsigned char *buf, int len)
{
    radio *r = (radio *)opaque;
    int64_t end = r->position < KC_SONG_BYTE ? KC_SONG_BYTE : r->size;
    int64_t left = end - r->position;
    int n = left < len ? (int)left : len;
    if (n <= 0) return KC_IO_EOF;
    memcpy(buf, r->bytes + r->position, (size_t)n);
    r->read_start = r->position;
    r->position += n;
    return n;
}

/* The station's name with the first bytes, and the second song's title with its first byte. */
static int radio_tags(void *opaque, kc_io_tags *tags)
{
    radio *r = (radio *)opaque;
    r->tags_asked++;
    if (r->read_start == 0) return ffkmp_io_tag(tags, "icy-name", "Test radio");
    if (r->read_start != KC_SONG_BYTE) return 0;
    if (r->fail_at_song) return KC_IO_ERR;
    /* A key given twice in one read keeps the later value. */
    if (ffkmp_io_tag(tags, "StreamTitle", "Not this one") < 0) return KC_IO_ERR;
    return ffkmp_io_tag(tags, "StreamTitle", "Second song");
}

static kc_fmt_ctx *open_radio(radio *r, kc_io_tags_fn tags_fn, const char *mime_type)
{
    kc_fmt_ctx *ctx = NULL;
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, r, radio_read, NULL, tags_fn, -1, NULL, NULL, mime_type,
                                       NULL, NULL, NULL, 0, NULL, NULL), 0);
    KC_NOT_NULL(ctx);
    return ctx;
}

static const char *tag(kc_fmt_ctx *ctx, const char *key)
{
    const AVDictionaryEntry *e = av_dict_get(((AVFormatContext *)ctx)->metadata, key, NULL, 0);
    return e ? e->value : NULL;
}

static void case_the_first_reads_tags_are_in_the_open(void)
{
    radio r;
    make_radio(&r);
    kc_fmt_ctx *ctx = open_radio(&r, radio_tags, NULL);

    kc_case("the tags the header's reads brought are the open's, before any packet");
    KC_CHECK(r.tags_asked > 0);
    KC_EQ_STR(tag(ctx, "icy-name"), "Test radio");
    KC_NULL(tag(ctx, "StreamTitle"));
    ffkmp_fmt_close_input_io(&ctx);
    free(r.bytes);
}

static void case_a_title_lands_on_the_packet_holding_its_byte(void)
{
    radio r;
    make_radio(&r);
    kc_fmt_ctx *ctx = open_radio(&r, radio_tags, NULL);
    kc_packet *packet = ffkmp_packet_alloc();
    int64_t changed_at = -1, changed_size = 0, packets = 0;
    int rc;

    kc_case("the title rides the first packet that holds its byte, and no packet before it");
    KC_NOT_NULL(packet);
    KC_EQ_INT(ffkmp_fmt_find_stream_info(ctx), 0);
    ffkmp_fmt_take_tag_changes(ctx, -1);
    while ((rc = ffkmp_fmt_read_frame(ctx, packet)) >= 0) {
        AVPacket *p = (AVPacket *)packet;
        int changes = ffkmp_fmt_take_tag_changes(ctx, -1);
        packets++;
        if (changes) {
            KC_EQ_INT(changes, KC_TAGS_CONTAINER);
            KC_EQ_I64(changed_at, -1);
            changed_at = p->pos;
            changed_size = p->size;
            KC_EQ_STR(tag(ctx, "StreamTitle"), "Second song");
        } else if (changed_at < 0) {
            KC_NULL(tag(ctx, "StreamTitle"));
        }
        ffkmp_packet_unref(packet);
    }
    KC_EQ_INT(rc, AVERROR_EOF);
    KC_CHECKF(packets > 10, "read %lld packets", (long long)packets);
    KC_CHECKF(changed_at >= 0 && changed_at <= KC_SONG_BYTE && changed_at + changed_size > KC_SONG_BYTE,
              "the change came on the packet at %lld of %lld bytes, and the song starts at %d",
              (long long)changed_at, (long long)changed_size, KC_SONG_BYTE);

    kc_case("the title merges into the tags the open read, and the later value of a key wins");
    KC_EQ_STR(tag(ctx, "icy-name"), "Test radio");
    KC_EQ_STR(tag(ctx, "StreamTitle"), "Second song");

    ffkmp_packet_free(packet);
    ffkmp_fmt_close_input_io(&ctx);
    free(r.bytes);
}

static void case_only_a_bridge_with_tags_answers_metadata(void)
{
    radio r;
    AVDictionary *metadata = NULL;
    make_radio(&r);

    kc_case("an input without a tags_fn answers no metadata option, so FFmpeg stops asking");
    kc_fmt_ctx *ctx = open_radio(&r, NULL, "audio/wav");
    KC_EQ_INT(av_opt_get_dict_val(ctx, "metadata", AV_OPT_SEARCH_CHILDREN, &metadata),
              AVERROR_OPTION_NOT_FOUND);
    KC_EQ_INT(r.tags_asked, 0);
    ffkmp_fmt_close_input_io(&ctx);

    kc_case("an input with one answers it, empty once the open took what it held");
    r.position = 0;
    ctx = open_radio(&r, radio_tags, NULL);
    KC_EQ_INT(av_opt_get_dict_val(ctx, "metadata", AV_OPT_SEARCH_CHILDREN, &metadata), 0);
    KC_NULL(metadata);
    ffkmp_fmt_close_input_io(&ctx);
    free(r.bytes);
}

static void case_a_failing_tags_fn_fails_its_read(void)
{
    radio r;
    make_radio(&r);
    r.fail_at_song = 1;
    kc_fmt_ctx *ctx = open_radio(&r, radio_tags, NULL);
    kc_packet *packet = ffkmp_packet_alloc();
    int rc;

    KC_NOT_NULL(packet);
    while ((rc = ffkmp_fmt_read_frame(ctx, packet)) >= 0) ffkmp_packet_unref(packet);

    kc_case("a tags_fn that fails fails the read it followed, and the reading stops there");
    KC_CHECKF(rc < 0 && rc != AVERROR_EOF, "the last read answered %d", rc);
    KC_EQ_I64(r.read_start, KC_SONG_BYTE);
    ffkmp_packet_free(packet);
    ffkmp_fmt_close_input_io(&ctx);
    free(r.bytes);
}

static void case_a_tag_needs_a_tagged_bridge_a_key_and_a_value(void)
{
    struct { const void *av_class; uint32_t magic; } stranger = { NULL, 0 };

    kc_case("ffkmp_io_tag refuses what is not a bridge's tags, and an empty or missing key or value");
    KC_EQ_INT(ffkmp_io_tag(NULL, "title", "x"), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_io_tag((kc_io_tags *)&stranger, "title", "x"), AVERROR(EINVAL));
}

static radio *checked_radio;

static int checking_tags(void *opaque, kc_io_tags *tags)
{
    (void)opaque;
    if (checked_radio->tags_asked++ > 0) return 0;
    KC_EQ_INT(ffkmp_io_tag(tags, NULL, "x"), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_io_tag(tags, "", "x"), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_io_tag(tags, "title", NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_io_tag(tags, "title", ""), 0);
    return 0;
}

static void case_a_tag_inside_a_tags_fn_is_checked(void)
{
    radio r;
    make_radio(&r);
    checked_radio = &r;

    kc_case("inside a tags_fn a NULL or empty key and a NULL value are refused, and an empty value is kept");
    kc_fmt_ctx *ctx = open_radio(&r, checking_tags, NULL);
    KC_CHECK(r.tags_asked > 0);
    KC_EQ_STR(tag(ctx, "title"), "");
    ffkmp_fmt_close_input_io(&ctx);
    free(r.bytes);
}

int main(void)
{
    kc_suite_begin("test_io_tags");

    case_the_first_reads_tags_are_in_the_open();
    case_a_title_lands_on_the_packet_holding_its_byte();
    case_only_a_bridge_with_tags_answers_metadata();
    case_a_failing_tags_fn_fails_its_read();
    case_a_tag_needs_a_tagged_bridge_a_key_and_a_value();
    case_a_tag_inside_a_tags_fn_is_checked();

    return kc_suite_end();
}
