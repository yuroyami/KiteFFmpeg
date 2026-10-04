/* A stream's language, through the writers whose field holds a three-letter ISO 639-2 code (#156),
 * and out of Matroska's LanguageBCP47 (#150).
 *
 * FFmpeg's "language" tag holds a BCP 47 tag whenever the source gave one: the HLS and DASH
 * demuxers copy a rendition's tag, and Matroska's LanguageBCP47 says "en" or "pt-BR". MP4, MOV,
 * MPEG-TS and Matroska took such a tag as if it were a three-letter code, so MP4, MOV and MPEG-TS
 * dropped it and Matroska wrote it into an element that holds a code. The patch
 * 0005-write-a-bcp47-language-as-its-iso639-code makes each of them write the code of the language
 * the tag's first subtag names, and Matroska also write the whole tag as LanguageBCP47.
 * FFmpeg's Matroska reader skipped LanguageBCP47, so zh-Hant and zh-Hans both read as chi; the
 * patch 0006-matroska-read-the-bcp47-language makes it report that element, as the specification
 * asks, and the old Language only when the track has none.
 *
 * Each case writes one stream of packets that are not real media, since a writer copies them:
 * audio, or for MPEG-TS's own subtitle and teletext descriptors a stream of either. It reads the
 * language back with FFmpeg, or for Matroska reads the elements themselves. The Matroska reading
 * cases rewrite the elements of a file the writer made, since it writes Language on every track. A
 * linked FFmpeg whose tree does not list a patch in lib/kiteffmpeg/ffmpeg-patches.txt, such as a
 * distribution's, runs only the cases that hold without it.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef KC_BUILD_FFMPEG_DIR
#define KC_BUILD_FFMPEG_DIR "unknown"
#endif

#define WRITER_PATCH "0005-write-a-bcp47-language-as-its-iso639-code.patch"
#define READER_PATCH "0006-matroska-read-the-bcp47-language.patch"

#define MATROSKA_ID_LANGUAGE 0x22B59C
#define MATROSKA_ID_LANGUAGE_BCP47 0x22B59D

static const char *const extensions[] = { "mp4", "mov", "ts", "mkv", "webm" };
static char base[480];
static char path[512];

static void remove_fixtures(void)
{
    for (size_t i = 0; i < sizeof(extensions) / sizeof(extensions[0]); i++) {
        snprintf(path, sizeof(path), "%s.%s", base, extensions[i]);
        remove(path);
    }
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

static int put_packet(AVFormatContext *oc, AVStream *st, int index, int ts)
{
    /* An ADTS header for AAC LC at 48 kHz in stereo, which MPEG-TS needs to carry AAC with no
     * extradata, then a payload no decoder is ever asked to read. */
    static const uint8_t payload[9] = { 0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c, 0x01, 0x02, 0x03 };
    const int opus = st->codecpar->codec_id == AV_CODEC_ID_OPUS;
    const int size = (ts ? 7 : 0) + (int)sizeof(payload);
    AVPacket *pkt = av_packet_alloc();
    int rc;
    if (!pkt || av_new_packet(pkt, size) < 0) {
        av_packet_free(&pkt);
        return -1;
    }
    if (ts) {
        const uint8_t adts[7] = { 0xff, 0xf1, 0x4c, (uint8_t)(0x80 | ((size >> 11) & 3)),
                                  (uint8_t)(size >> 3), (uint8_t)(((size & 7) << 5) | 0x1f), 0xfc };
        memcpy(pkt->data, adts, sizeof(adts));
    }
    memcpy(pkt->data + (ts ? 7 : 0), payload, sizeof(payload));
    if (opus) pkt->data[0] = 0xfc; /* one 20 ms CELT frame */
    pkt->duration = opus ? 960 : 1024;
    pkt->pts = pkt->dts = (int64_t)index * pkt->duration;
    pkt->stream_index = st->index;
    av_packet_rescale_ts(pkt, (AVRational){ 1, 48000 }, st->time_base);
    pkt->flags |= AV_PKT_FLAG_KEY;
    rc = av_interleaved_write_frame(oc, pkt);
    av_packet_free(&pkt);
    return rc;
}

/* Writes one audio stream tagged lang through the muxer named format into path, which gets the
 * format's extension, since a probe takes a tiny MPEG-TS file without one for MPEG-PS: Opus for
 * WebM, AAC otherwise. Returns 0, or -1 when this FFmpeg cannot write it. */
static int write_tagged(const char *format, const char *lang)
{
    static const uint8_t opus_head[19] = { 'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 2,
                                           0x38, 0x01, 0x80, 0xbb, 0, 0, 0, 0, 0 };
    static const uint8_t aac_config[2] = { 0x11, 0x90 };
    const int webm = strcmp(format, "webm") == 0;
    const int ts = strcmp(format, "mpegts") == 0;
    AVFormatContext *oc = NULL;
    int ok = -1;
    snprintf(path, sizeof(path), "%s.%s", base,
             ts ? "ts" : strcmp(format, "matroska") == 0 ? "mkv" : format);
    if (avformat_alloc_output_context2(&oc, NULL, format, path) < 0) return -1;
    AVStream *st = avformat_new_stream(oc, NULL);
    if (!st) goto done;
    AVCodecParameters *par = st->codecpar;
    par->codec_type = AVMEDIA_TYPE_AUDIO;
    par->codec_id = webm ? AV_CODEC_ID_OPUS : AV_CODEC_ID_AAC;
    par->sample_rate = 48000;
    av_channel_layout_default(&par->ch_layout, 2);
    if (!ts) {
        const uint8_t *config = webm ? opus_head : aac_config;
        const int size = webm ? (int)sizeof(opus_head) : (int)sizeof(aac_config);
        par->extradata = av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!par->extradata) goto done;
        memcpy(par->extradata, config, (size_t)size);
        par->extradata_size = size;
    }
    st->time_base = (AVRational){ 1, 48000 };
    if (lang && av_dict_set(&st->metadata, "language", lang, 0) < 0) goto done;
    if (avio_open(&oc->pb, path, AVIO_FLAG_WRITE) < 0) goto done;
    if (avformat_write_header(oc, NULL) < 0) goto done;
    /* Enough packets for a probe to tell MPEG-TS from MPEG-PS. */
    for (int i = 0; i < 40; i++)
        if (put_packet(oc, st, i, ts) < 0) goto done;
    if (av_write_trailer(oc) < 0) goto done;
    ok = 0;
done:
    if (oc && oc->pb) avio_closep(&oc->pb);
    avformat_free_context(oc);
    return ok;
}

/* Writes one stream of DVB subtitles or teletext, codec, tagged lang into an MPEG-TS file at path.
 * The muxer copies the payload without reading it and carries the language in the stream's own
 * descriptor, apart from the one audio uses. Returns 0, or -1 when this FFmpeg cannot write it. */
static int write_ts_subtitles(enum AVCodecID codec, const char *lang)
{
    AVFormatContext *oc = NULL;
    int ok = -1;
    snprintf(path, sizeof(path), "%s.ts", base);
    if (avformat_alloc_output_context2(&oc, NULL, "mpegts", path) < 0) return -1;
    AVStream *st = avformat_new_stream(oc, NULL);
    if (!st) goto done;
    st->codecpar->codec_type = AVMEDIA_TYPE_SUBTITLE;
    st->codecpar->codec_id = codec;
    st->time_base = (AVRational){ 1, 90000 };
    if (av_dict_set(&st->metadata, "language", lang, 0) < 0) goto done;
    if (avio_open(&oc->pb, path, AVIO_FLAG_WRITE) < 0) goto done;
    if (avformat_write_header(oc, NULL) < 0) goto done;
    for (int i = 0; i < 40; i++) {
        AVPacket *pkt = av_packet_alloc();
        if (!pkt || av_new_packet(pkt, 64) < 0) {
            av_packet_free(&pkt);
            goto done;
        }
        memset(pkt->data, 0x20, 64);
        pkt->pts = pkt->dts = (int64_t)i * 9000;
        pkt->stream_index = st->index;
        pkt->flags |= AV_PKT_FLAG_KEY;
        int rc = av_interleaved_write_frame(oc, pkt);
        av_packet_free(&pkt);
        if (rc < 0) goto done;
    }
    if (av_write_trailer(oc) < 0) goto done;
    ok = 0;
done:
    if (oc && oc->pb) avio_closep(&oc->pb);
    avformat_free_context(oc);
    return ok;
}

/* The language FFmpeg reads back for the first stream of path, copied into out, or "" when the
 * stream has none. */
static void read_language(char *out, size_t size)
{
    kc_fmt_ctx *ctx = NULL;
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, path), 0);
    /* MPEG-TS adds its streams as it reads, so the read goes on until each one is found. */
    KC_CHECK(ffkmp_fmt_find_stream_info(ctx) >= 0);
    AVFormatContext *c = (AVFormatContext *)ctx;
    KC_CHECK(c->nb_streams >= 1);
    const AVDictionaryEntry *tag = av_dict_get(c->streams[0]->metadata, "language", NULL, 0);
    snprintf(out, size, "%s", tag ? tag->value : "");
    ffkmp_fmt_close_input(&ctx);
}

/* Writes lang into format and reads it back. expected "" means no language at all. */
static void check_round_trip(const char *format, const char *lang, const char *expected)
{
    char read[64];
    KC_EQ_INT(write_tagged(format, lang), 0);
    read_language(read, sizeof(read));
    kc_detail("%s %s->%s", format, lang, read[0] ? read : "(none)");
    KC_EQ_STR(read, expected);
}

/* Writes lang into an MPEG-TS stream of codec and reads it back. */
static void check_subtitle_round_trip(enum AVCodecID codec, const char *lang, const char *expected)
{
    char read[64];
    KC_EQ_INT(write_ts_subtitles(codec, lang), 0);
    read_language(read, sizeof(read));
    kc_detail("mpegts %s %s->%s", avcodec_get_name(codec), lang, read[0] ? read : "(none)");
    KC_EQ_STR(read, expected);
}

/* The value of the first element id in the file at path, a string whose size fits one byte, copied
 * into out, or "" when the file has no such element. */
static void matroska_element(unsigned id, char *out, size_t size)
{
    uint8_t buf[4096];
    out[0] = '\0';
    FILE *f = fopen(path, "rb");
    KC_NOT_NULL(f);
    size_t n = fread(buf, 1, sizeof(buf), f);
    fclose(f);
    for (size_t i = 0; i + 4 <= n; i++) {
        if (buf[i] != (uint8_t)(id >> 16) || buf[i + 1] != (uint8_t)(id >> 8) || buf[i + 2] != (uint8_t)id)
            continue;
        KC_CHECKF(buf[i + 3] & 0x80, "element %06X has a size wider than one byte", id);
        size_t len = buf[i + 3] & 0x7f;
        KC_CHECK(i + 4 + len <= n && len < size);
        memcpy(out, buf + i + 4, len);
        out[len] = '\0';
        return;
    }
}

/* Writes lang into format and checks the Language and LanguageBCP47 elements it wrote. bcp47 ""
 * means no LanguageBCP47 element. */
static void check_elements(const char *format, const char *lang, const char *language, const char *bcp47)
{
    char got_language[64], got_bcp47[64];
    KC_EQ_INT(write_tagged(format, lang), 0);
    matroska_element(MATROSKA_ID_LANGUAGE, got_language, sizeof(got_language));
    matroska_element(MATROSKA_ID_LANGUAGE_BCP47, got_bcp47, sizeof(got_bcp47));
    kc_detail("%s %s->%s/%s", format, lang, got_language, got_bcp47[0] ? got_bcp47 : "(none)");
    KC_EQ_STR(got_language, language);
    KC_EQ_STR(got_bcp47, bcp47);
}

/* Replaces the first element id in the file at path, whose size fits one byte, with the bytes of
 * with, which must be exactly as long as the whole element. */
static void rewrite_element(unsigned id, const uint8_t *with, size_t len)
{
    uint8_t buf[4096];
    FILE *f = fopen(path, "r+b");
    KC_NOT_NULL(f);
    if (!f) return;
    size_t n = fread(buf, 1, sizeof(buf), f);
    for (size_t i = 0; i + 4 <= n; i++) {
        if (buf[i] != (uint8_t)(id >> 16) || buf[i + 1] != (uint8_t)(id >> 8) || buf[i + 2] != (uint8_t)id)
            continue;
        KC_EQ_INT((int)(4 + (buf[i + 3] & 0x7f)), (int)len);
        KC_EQ_INT(fseek(f, (long)i, SEEK_SET), 0);
        KC_EQ_INT((int)fwrite(with, 1, len, f), (int)len);
        fclose(f);
        return;
    }
    fclose(f);
    KC_FAIL("the file has no element %06X", id);
}

/* Writes lang into Matroska, lets edit rewrite the file, and reads the language back. */
static void check_matroska_reading(const char *lang, void (*edit)(void), const char *expected)
{
    char read[64], language[64], bcp47[64];
    KC_EQ_INT(write_tagged("matroska", lang), 0);
    if (edit) edit();
    matroska_element(MATROSKA_ID_LANGUAGE, language, sizeof(language));
    matroska_element(MATROSKA_ID_LANGUAGE_BCP47, bcp47, sizeof(bcp47));
    read_language(read, sizeof(read));
    kc_detail("%s/%s->%s", language[0] ? language : "(none)", bcp47[0] ? bcp47 : "(none)",
              read[0] ? read : "(none)");
    KC_EQ_STR(read, expected);
}

/* Turns the Language element "eng" into a Void element of the same length. */
static void drop_language(void)
{
    static const uint8_t void_of_five[7] = { 0xec, 0x85, 0, 0, 0, 0, 0 };
    rewrite_element(MATROSKA_ID_LANGUAGE, void_of_five, sizeof(void_of_five));
}

/* Turns the LanguageBCP47 element "en-GB" into "und" and an empty Void element. */
static void make_bcp47_und(void)
{
    static const uint8_t und[9] = { 0x22, 0xb5, 0x9d, 0x83, 'u', 'n', 'd', 0xec, 0x80 };
    rewrite_element(MATROSKA_ID_LANGUAGE_BCP47, und, sizeof(und));
}

/* Turns the LanguageBCP47 element "en" into an empty one and an empty Void element. */
static void empty_bcp47(void)
{
    static const uint8_t empty[6] = { 0x22, 0xb5, 0x9d, 0x80, 0xec, 0x80 };
    rewrite_element(MATROSKA_ID_LANGUAGE_BCP47, empty, sizeof(empty));
}

static void case_a_three_letter_code_is_written_as_it_is(void)
{
    kc_case("a three-letter code reaches every writer as it is");
    check_round_trip("mp4", "eng", "eng");
    check_round_trip("mp4", "ger", "ger");
    check_round_trip("mov", "eng", "eng");
    check_round_trip("mpegts", "eng", "eng");
    check_round_trip("mpegts", "eng,fre", "eng,fre");
    check_subtitle_round_trip(AV_CODEC_ID_DVB_SUBTITLE, "eng", "eng");
    check_subtitle_round_trip(AV_CODEC_ID_DVB_TELETEXT, "eng,fre", "eng,fre");
    check_round_trip("matroska", "eng", "eng");
}

static void case_mp4_takes_the_terminological_code(void)
{
    kc_case("MP4 writes the terminological code of a BCP 47 tag's language");
    check_round_trip("mp4", "en", "eng");
    check_round_trip("mp4", "EN-gb", "eng");
    check_round_trip("mp4", "pt-BR", "por");
    check_round_trip("mp4", "zh-Hant", "zho");
    check_round_trip("mp4", "de-CH", "deu");
    check_round_trip("mp4", "yue-HK", "yue");
    check_round_trip("mp4", "x-klingon", "");
}

static void case_mov_finds_the_language_in_its_table(void)
{
    kc_case("MOV finds a BCP 47 tag's language in its Macintosh table under either code");
    check_round_trip("mov", "en", "eng");
    check_round_trip("mov", "fr-CA", "fra");
    check_round_trip("mov", "de", "ger");
    check_round_trip("mov", "pt-BR", "por");
}

static void case_mpegts_takes_the_bibliographic_code(void)
{
    kc_case("MPEG-TS writes the bibliographic code of each BCP 47 tag's language");
    check_round_trip("mpegts", "en", "eng");
    check_round_trip("mpegts", "pt-BR", "por");
    check_round_trip("mpegts", "zh-Hant", "chi");
    check_round_trip("mpegts", "de-CH", "ger");
    check_round_trip("mpegts", "pt-BR,en", "por,eng");
    check_round_trip("mpegts", "x-klingon", "");
}

/* The subtitle and teletext descriptors used to copy the tag three characters at a time, so
 * zh-Hant became the two codes zh- and ant. */
static void case_mpegts_subtitles_take_the_bibliographic_code(void)
{
    kc_case("MPEG-TS subtitles and teletext carry the bibliographic code of each BCP 47 tag's language");
    check_subtitle_round_trip(AV_CODEC_ID_DVB_SUBTITLE, "zh-Hant", "chi");
    check_subtitle_round_trip(AV_CODEC_ID_DVB_SUBTITLE, "pt-BR,en", "por,eng");
    check_subtitle_round_trip(AV_CODEC_ID_DVB_TELETEXT, "zh-Hant", "chi");
    check_subtitle_round_trip(AV_CODEC_ID_DVB_TELETEXT, "de-CH,en", "ger,eng");
}

static void case_matroska_writes_the_code_and_the_tag(void)
{
    kc_case("Matroska writes a BCP 47 tag as LanguageBCP47 and its language's code as Language");
    check_elements("matroska", "en", "eng", "en");
    check_elements("matroska", "zh-Hant", "chi", "zh-Hant");
    check_elements("matroska", "pt-BR", "por", "pt-BR");
    check_elements("matroska", "yue-HK", "yue", "yue-HK");
    check_elements("matroska", "eng", "eng", "");
    check_elements("matroska", "fre-ca", "fre-ca", "");
    check_elements("matroska", "eng,fre", "eng,fre", "");
}

static void case_matroska_reads_the_old_language_alone(void)
{
    kc_case("a Matroska track with only Language reads that code");
    check_matroska_reading("chi", NULL, "chi");
    check_matroska_reading("fre-ca", NULL, "fre-ca");
    check_matroska_reading("und", NULL, "");
}

static void case_matroska_reads_the_bcp47_language(void)
{
    kc_case("a Matroska track with LanguageBCP47 reads that tag and ignores Language");
    check_matroska_reading("zh-Hant", NULL, "zh-Hant");
    check_matroska_reading("zh-Hans", NULL, "zh-Hans");
    check_matroska_reading("pt-BR", NULL, "pt-BR");
    check_matroska_reading("en", drop_language, "en");
    check_matroska_reading("en-GB", make_bcp47_und, "");
    check_matroska_reading("en", empty_bcp47, "eng");
}

static void case_webm_writes_only_the_code(void)
{
    kc_case("WebM, which has no LanguageBCP47, writes only the code");
    check_elements("webm", "pt-BR", "por", "");
    check_elements("webm", "en", "eng", "");
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_language");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    const char *slash = tmp[strlen(tmp) - 1] == '/' ? "" : "/";
    snprintf(base, sizeof(base), "%s%skc_language_%ld", tmp, slash, (long)getpid());
    atexit(remove_fixtures);

    case_a_three_letter_code_is_written_as_it_is();
    case_matroska_reads_the_old_language_alone();

    if (!linked_tree_carries(WRITER_PATCH)) {
        kc_note("the linked FFmpeg's tree does not list %s, so the BCP 47 cases did not run", WRITER_PATCH);
        return kc_suite_end();
    }

    case_mp4_takes_the_terminological_code();
    case_mov_finds_the_language_in_its_table();
    case_mpegts_takes_the_bibliographic_code();
    case_mpegts_subtitles_take_the_bibliographic_code();
    case_matroska_writes_the_code_and_the_tag();
    case_webm_writes_only_the_code();

    /* The reading cases make their files with the writer patch, so they need both. */
    if (!linked_tree_carries(READER_PATCH)) {
        kc_note("the linked FFmpeg's tree does not list %s, so the LanguageBCP47 reading case did not run",
                READER_PATCH);
        return kc_suite_end();
    }
    case_matroska_reads_the_bcp47_language();

    return kc_suite_end();
}
