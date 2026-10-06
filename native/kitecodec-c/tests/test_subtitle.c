/* The subtitle decoder behind ffkmp_subtitle_*: a Blu-ray (PGS) file written byte by byte decodes
 * to the image it describes, at the position, time and colours it names, and then to the clear
 * that ends it. The file is built here rather than shipped, so every expected value is visible. */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static unsigned char sup[256];
static size_t sup_len;

static void put8(unsigned v) { sup[sup_len++] = (unsigned char)v; }
static void put16(unsigned v) { put8(v >> 8); put8(v & 0xFF); }
static void put24(unsigned v) { put8(v >> 16); put16(v & 0xFFFF); }
static void put32(unsigned v) { put16(v >> 16); put16(v & 0xFFFF); }

/* A PGS segment header: "PG", the 90 kHz timestamps, the type and the payload size. */
static void segment(unsigned pts, unsigned type, unsigned size)
{
    put8('P'); put8('G');
    put32(pts);
    put32(0);
    put8(type);
    put16(size);
}

/*
 * Display set one, at one second: a 1920x1080 composition with one forced object at (100, 200),
 * a palette of opaque white (1) and half-transparent black (2), and a 4x2 object whose rows are
 * 1 1 2 2 and 2 2 1 1. Display set two, at two seconds, has no object, which clears the screen.
 */
static void build_sup(void)
{
    static const unsigned char rle[] = {
        0x01, 0x01, 0x02, 0x02, 0x00, 0x00,
        0x02, 0x02, 0x01, 0x01, 0x00, 0x00,
    };
    size_t i;

    sup_len = 0;
    segment(90000, 0x16, 19);              /* presentation composition */
    put16(1920); put16(1080); put8(0x10);
    put16(0); put8(0x80); put8(0x00); put8(0);
    put8(1);
    put16(0); put8(0); put8(0x40); put16(100); put16(200);

    segment(90000, 0x17, 10);              /* window */
    put8(1); put8(0); put16(100); put16(200); put16(4); put16(2);

    segment(90000, 0x14, 12);              /* palette: id, version, two entries */
    put8(0); put8(0);
    put8(1); put8(235); put8(128); put8(128); put8(255);
    put8(2); put8(16); put8(128); put8(128); put8(128);

    segment(90000, 0x15, (unsigned)(11 + sizeof(rle)));  /* object */
    put16(0); put8(0); put8(0xC0);
    put24((unsigned)(4 + sizeof(rle)));
    put16(4); put16(2);
    for (i = 0; i < sizeof(rle); i++) put8(rle[i]);

    segment(90000, 0x80, 0);               /* end of display set */

    segment(180000, 0x16, 11);             /* the clear: a composition with no object */
    put16(1920); put16(1080); put8(0x10);
    put16(1); put8(0x00); put8(0x00); put8(0);
    put8(0);
    segment(180000, 0x80, 0);
}

static char sup_path[1024];

static void remove_sup(void)
{
    remove(sup_path);
}

static void write_sup(void)
{
    const char *tmp = getenv("TMPDIR");
    const char *sep = "/";
    FILE *file;

    build_sup();
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    if (tmp[strlen(tmp) - 1] == '/') sep = "";
    snprintf(sup_path, sizeof(sup_path), "%s%skc_subtitle_%ld.sup", tmp, sep, (long)getpid());
    file = fopen(sup_path, "wb");
    KC_NOT_NULL(file);
    atexit(remove_sup);
    KC_EQ_SIZE(fwrite(sup, 1, sup_len, file), sup_len);
    KC_EQ_INT(fclose(file), 0);
}

static void case_refusals(void)
{
    kc_codec_ctx *c = (kc_codec_ctx *)0x1;
    kc_subtitle *s = (kc_subtitle *)0x1;
    int type, x, y, w, h, forced;
    int64_t start, end;
    uint8_t px[4];

    kc_case("NULL arguments and a missing stream are refused and leave the outputs NULL");
    KC_EQ_INT(ffkmp_subtitle_decoder_open(NULL, 0, &c), AVERROR(EINVAL));
    KC_NULL(c);
    KC_EQ_INT(ffkmp_subtitle_decoder_open(NULL, 0, NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_decode(NULL, NULL, &s), AVERROR(EINVAL));
    KC_NULL(s);
    KC_EQ_INT(ffkmp_subtitle_times(NULL, &start, &end), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_rect_count(NULL), 0);
    KC_EQ_INT(ffkmp_subtitle_rect(NULL, 0, &type, &x, &y, &w, &h, &forced), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_rect_rgba(NULL, 0, px, sizeof(px)), AVERROR(EINVAL));
    KC_NULL(ffkmp_subtitle_rect_text(NULL, 0));
    ffkmp_subtitle_free(NULL);
    s = NULL;
    ffkmp_subtitle_free(&s);
}

static void case_a_blu_ray_subtitle_decodes_to_its_image(void)
{
    kc_fmt_ctx *ctx = NULL;
    kc_codec_ctx *c = NULL;
    kc_packet *pkt = ffkmp_packet_alloc();
    kc_subtitle *shown = NULL, *cleared = NULL;
    int decoded = 0;
    int type, x, y, w, h, forced;
    int64_t start, end;
    uint8_t rgba[4 * 2 * 4];
    uint8_t small[4];
    /* Opaque white and half-transparent black, premultiplied: black stays 0 at any alpha. */
    static const uint8_t expected[4 * 2 * 4] = {
        255, 255, 255, 255,  255, 255, 255, 255,  0, 0, 0, 128,  0, 0, 0, 128,
        0, 0, 0, 128,  0, 0, 0, 128,  255, 255, 255, 255,  255, 255, 255, 255,
    };

    kc_case("a PGS display set decodes to one forced 4x2 image at (100, 200) from 1 s, then a clear at 2 s");
    write_sup();
    KC_NOT_NULL(pkt);
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, sup_path), 0);
    KC_EQ_INT(ffkmp_subtitle_decoder_open(ctx, 1, &c), AVERROR(EINVAL));
    KC_NULL(c);
    KC_EQ_INT(ffkmp_subtitle_decoder_open(ctx, 0, &c), 0);
    KC_NOT_NULL(c);
    while (ffkmp_fmt_read_frame(ctx, pkt) >= 0) {
        kc_subtitle *s = NULL;
        KC_EQ_INT(ffkmp_subtitle_decode(c, pkt, &s), 0);
        ffkmp_packet_unref(pkt);
        if (s == NULL) continue;
        decoded++;
        if (shown == NULL) shown = s;
        else if (cleared == NULL) cleared = s;
        else ffkmp_subtitle_free(&s);
    }
    KC_EQ_INT(decoded, 2);
    KC_NOT_NULL(shown);
    KC_NOT_NULL(cleared);

    KC_EQ_INT(ffkmp_subtitle_times(shown, &start, &end), 0);
    KC_EQ_I64(start, 1000000);
    KC_EQ_I64(end, INT64_MIN);
    KC_EQ_INT(ffkmp_codecctx_width(c), 1920);
    KC_EQ_INT(ffkmp_codecctx_height(c), 1080);
    KC_EQ_INT(ffkmp_subtitle_rect_count(shown), 1);
    KC_EQ_INT(ffkmp_subtitle_rect(shown, 0, &type, &x, &y, &w, &h, &forced), 0);
    KC_EQ_INT(type, KC_SUBTITLE_BITMAP);
    KC_EQ_INT(x, 100);
    KC_EQ_INT(y, 200);
    KC_EQ_INT(w, 4);
    KC_EQ_INT(h, 2);
    KC_EQ_INT(forced, 1);
    KC_EQ_INT(ffkmp_subtitle_rect(shown, 1, &type, &x, &y, &w, &h, &forced), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_rect_rgba(shown, 0, small, sizeof(small)), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_rect_rgba(shown, 0, rgba, sizeof(rgba)), 0);
    KC_CHECKF(memcmp(rgba, expected, sizeof(expected)) == 0, "the pixels differ from the palette the file names");
    KC_NULL(ffkmp_subtitle_rect_text(shown, 0));

    KC_EQ_INT(ffkmp_subtitle_times(cleared, &start, &end), 0);
    KC_EQ_I64(start, 2000000);
    KC_EQ_INT(ffkmp_subtitle_rect_count(cleared), 0);

    ffkmp_subtitle_free(&shown);
    KC_NULL(shown);
    ffkmp_subtitle_free(&cleared);
    ffkmp_codecctx_free(c);
    ffkmp_packet_free(pkt);
    ffkmp_fmt_close_input(&ctx);
}

/* A NULL packet drains: nothing from a decoder that holds nothing, such as PGS. */
static void case_a_decoder_that_holds_nothing_drains_to_nothing(void)
{
    kc_fmt_ctx *ctx = NULL;
    kc_codec_ctx *c = NULL;
    kc_subtitle *s = (kc_subtitle *)0x1;

    kc_case("a PGS decoder drains to no subtitle, and a NULL context is still refused");
    write_sup();
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, sup_path), 0);
    KC_EQ_INT(ffkmp_subtitle_decoder_open(ctx, 0, &c), 0);
    KC_EQ_INT(ffkmp_subtitle_decode(c, NULL, &s), 0);
    KC_NULL(s);
    s = (kc_subtitle *)0x1;
    KC_EQ_INT(ffkmp_subtitle_decode(NULL, NULL, &s), AVERROR(EINVAL));
    KC_NULL(s);
    ffkmp_codecctx_free(c);
    ffkmp_fmt_close_input(&ctx);
}

/*
 * One CEA-608 pop-on caption, HELLO at one second, that nothing erases. The caption decoder gives
 * a caption only when the screen next changes, so no packet completes it and only the drain does.
 * The SCC is the Scenarist text form: resume caption loading, erase non-displayed memory, a row,
 * the letters with odd parity, end of caption, each control code doubled as broadcasters send it.
 */
static const char held_caption[] =
    "Scenarist_SCC V1.0\n\n"
    "00:00:01:00\t9420 9420 94ae 94ae 9440 9440 c845 4c4c 4f80 942f 942f\n\n";

static char scc_path[1024];

static void remove_scc(void)
{
    remove(scc_path);
}

static void case_a_held_caption_comes_out_of_the_drain(void)
{
    const char *tmp = getenv("TMPDIR");
    const char *sep = "/";
    FILE *file;
    kc_fmt_ctx *ctx = NULL;
    kc_codec_ctx *c = NULL;
    kc_packet *pkt = ffkmp_packet_alloc();
    kc_subtitle *s = NULL;
    int decoded = 0;
    int64_t start, end;
    const char *text;

    kc_case("a closed caption nothing erases is completed by no packet and given by the drain, once");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    if (tmp[strlen(tmp) - 1] == '/') sep = "";
    snprintf(scc_path, sizeof(scc_path), "%s%skc_subtitle_%ld.scc", tmp, sep, (long)getpid());
    file = fopen(scc_path, "wb");
    KC_NOT_NULL(file);
    atexit(remove_scc);
    KC_EQ_SIZE(fwrite(held_caption, 1, sizeof(held_caption) - 1, file), sizeof(held_caption) - 1);
    KC_EQ_INT(fclose(file), 0);

    KC_NOT_NULL(pkt);
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, scc_path), 0);
    KC_EQ_INT(ffkmp_subtitle_decoder_open(ctx, 0, &c), 0);
    while (ffkmp_fmt_read_frame(ctx, pkt) >= 0) {
        KC_EQ_INT(ffkmp_subtitle_decode(c, pkt, &s), 0);
        ffkmp_packet_unref(pkt);
        if (s != NULL) {
            decoded++;
            ffkmp_subtitle_free(&s);
        }
    }
    KC_EQ_INT(decoded, 0);

    KC_EQ_INT(ffkmp_subtitle_decode(c, NULL, &s), 0);
    KC_NOT_NULL(s);
    KC_EQ_INT(ffkmp_subtitle_times(s, &start, &end), 0);
    KC_EQ_I64(start, 1000000);
    KC_EQ_INT(ffkmp_subtitle_rect_count(s), 1);
    text = ffkmp_subtitle_rect_text(s, 0);
    KC_NOT_NULL(text);
    KC_CHECKF(strstr(text, "HELLO") != NULL, "the drained caption reads %s", text);
    ffkmp_subtitle_free(&s);

    /* It was given once: a second drain finds nothing left. */
    KC_EQ_INT(ffkmp_subtitle_decode(c, NULL, &s), 0);
    KC_NULL(s);

    ffkmp_codecctx_free(c);
    ffkmp_packet_free(pkt);
    ffkmp_fmt_close_input(&ctx);
}

/* A CEA-608 byte with its odd parity bit set, as a caption pair carries it. */
static unsigned char odd_parity(unsigned char b)
{
    int ones = 0;
    for (int i = 0; i < 7; i++) ones += (b >> i) & 1;
    return (unsigned char)(ones % 2 == 0 ? b | 0x80 : b);
}

/* One frame's cc_data: a single field 1 caption pair, as an A/53 SEI carries it. */
static void caption_pair(unsigned char out[3], unsigned char a, unsigned char b)
{
    out[0] = 0xFC; /* marker bits, cc_valid, cc_type 0: field 1 */
    out[1] = odd_parity(a);
    out[2] = odd_parity(b);
}

static void case_the_captions_frames_carry_decode_without_a_stream(void)
{
    /* Pop-on HELLO on CC1: resume caption loading, the letters, end of caption, then null pairs
       until the erase of displayed memory a second in. Control codes come twice, as broadcast
       sends them. One pair per frame at 30 frames a second. */
    static const unsigned char pairs[][2] = {
        {0x14, 0x20}, {0x14, 0x20}, {'H', 'E'}, {'L', 'L'}, {'O', 0x00}, {0x14, 0x2F}, {0x14, 0x2F},
    };
    const int frames = 31;
    kc_codec_ctx *c = NULL;
    kc_subtitle *s = NULL;
    kc_subtitle *shown = NULL;
    unsigned char cc[3];
    int64_t start, end;
    const char *text;

    kc_case("the captions video frames carry decode to timed text with no container stream behind them");
    KC_EQ_INT(ffkmp_caption_decoder_open(NULL, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_caption_decoder_open(&c, 0), 0);
    KC_NOT_NULL(c);
    caption_pair(cc, 0, 0);
    KC_EQ_INT(ffkmp_caption_decode(NULL, cc, 3, 0, &s), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_caption_decode(c, NULL, 3, 0, &s), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_caption_decode(c, cc, 0, 0, &s), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_caption_decode(c, cc, 3, 0, NULL), AVERROR(EINVAL));
    for (int frame = 0; frame < frames; frame++) {
        int n = (int)(sizeof(pairs) / sizeof(pairs[0]));
        if (frame < n) caption_pair(cc, pairs[frame][0], pairs[frame][1]);
        else if (frame >= frames - 2) caption_pair(cc, 0x14, 0x2C);
        else caption_pair(cc, 0x00, 0x00);
        KC_EQ_INT(ffkmp_caption_decode(c, cc, 3, (int64_t)frame * 1000000 / 30, &s), 0);
        if (s == NULL) continue;
        if (ffkmp_subtitle_rect_count(s) > 0 && shown == NULL) {
            shown = s;
        } else {
            ffkmp_subtitle_free(&s);
        }
        s = NULL;
    }
    if (shown == NULL) KC_EQ_INT(ffkmp_subtitle_decode(c, NULL, &shown), 0);
    KC_NOT_NULL(shown);
    KC_EQ_INT(ffkmp_subtitle_times(shown, &start, &end), 0);
    kc_detail("start=%lld end=%lld", (long long)start, (long long)end);
    KC_CHECKF(start >= 150000 && start <= 250000, "the caption starts at %lld us, at its end of caption", (long long)start);
    KC_CHECKF(end >= 950000 && end <= 1050000, "the caption ends at %lld us, at its erase", (long long)end);
    text = ffkmp_subtitle_rect_text(shown, 0);
    KC_NOT_NULL(text);
    KC_CHECKF(strstr(text, "HELLO") != NULL, "the caption reads %s", text);
    ffkmp_subtitle_free(&shown);
    ffkmp_codecctx_free(c);
}

static void case_captions_in_real_time_show_as_the_screen_changes(void)
{
    /* The same pop-on HELLO, answered in real time (#180): the caption comes at its end of caption
       with no end, and the erase answers with an empty screen. */
    static const unsigned char pairs[][2] = {
        {0x14, 0x20}, {0x14, 0x20}, {'H', 'E'}, {'L', 'L'}, {'O', 0x00}, {0x14, 0x2F}, {0x14, 0x2F},
    };
    const int frames = 31;
    kc_codec_ctx *c = NULL;
    kc_subtitle *s = NULL;
    unsigned char cc[3];
    int64_t shown_at = -1, cleared_at = -1, start, end;

    kc_case("captions in real time answer as the screen changes, with no end, and an empty screen when it clears");
    KC_EQ_INT(ffkmp_caption_decoder_open(&c, 1), 0);
    KC_NOT_NULL(c);
    for (int frame = 0; frame < frames; frame++) {
        int n = (int)(sizeof(pairs) / sizeof(pairs[0]));
        int64_t at = (int64_t)frame * 1000000 / 30;
        if (frame < n) caption_pair(cc, pairs[frame][0], pairs[frame][1]);
        else if (frame >= frames - 2) caption_pair(cc, 0x14, 0x2C);
        else caption_pair(cc, 0x00, 0x00);
        KC_EQ_INT(ffkmp_caption_decode(c, cc, 3, at, &s), 0);
        if (s == NULL) continue;
        KC_EQ_INT(ffkmp_subtitle_times(s, &start, &end), 0);
        KC_CHECKF(end == INT64_MIN, "a real-time answer ends at %lld us, not when the next one comes", (long long)end);
        const char *text = ffkmp_subtitle_rect_count(s) > 0 ? ffkmp_subtitle_rect_text(s, 0) : NULL;
        if (text != NULL && strstr(text, "HELLO") != NULL) {
            if (shown_at < 0) shown_at = start;
        } else if (shown_at >= 0 && cleared_at < 0) {
            cleared_at = start;
        }
        ffkmp_subtitle_free(&s);
    }
    kc_detail("shown=%lld cleared=%lld", (long long)shown_at, (long long)cleared_at);
    KC_CHECKF(shown_at == 5 * 1000000 / 30, "the caption shows at %lld us, its end of caption", (long long)shown_at);
    KC_CHECKF(cleared_at == 29 * 1000000 / 30, "the screen clears at %lld us, its erase", (long long)cleared_at);
    ffkmp_codecctx_free(c);
}

int main(void)
{
    kc_suite_begin("test_subtitle");

    case_refusals();
    case_a_blu_ray_subtitle_decodes_to_its_image();
    case_a_decoder_that_holds_nothing_drains_to_nothing();
    case_a_held_caption_comes_out_of_the_drain();
    case_the_captions_frames_carry_decode_without_a_stream();
    case_captions_in_real_time_show_as_the_screen_changes();

    return kc_suite_end();
}
