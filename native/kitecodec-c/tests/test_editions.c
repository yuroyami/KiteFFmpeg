/* A Matroska file's editions (#172).
 *
 * A Matroska file can carry several editions, each with its own chapters, such as a theatrical and
 * an extended cut, and RFC 9559 makes one of them the default: the first flagged default, or the
 * first when none is. A hidden chapter is not shown, the content of a disabled chapter is skipped,
 * and in an ordered edition a chapter with a ChapterSegmentUUID plays a range of another segment;
 * in any other edition that link is only information. FFmpeg's Matroska reader put every edition's
 * chapters into one list and kept each that started later than the last it kept, hidden, disabled
 * and linked ones included. The patch 0013-matroska-take-the-chapters-of-the-default-edition makes
 * the chapter list the default edition's, without its hidden and disabled chapters and without an
 * ordered edition's links.
 *
 * Each case writes its own file with the small EBML writer below: an EBML header, and a segment
 * with an Info, one subtitle track, the case's Chapters and Tags, and one cluster holding one block.
 * A linked FFmpeg whose tree does not list the patch in lib/kiteffmpeg/ffmpeg-patches.txt, such as
 * a distribution's, runs only the case that holds without it.
 *
 * FFmpeg reads one edition and none of the links between segments, so a caller that plays an ordered
 * edition reads the rest itself (#173). The patch 0014-matroska-export-the-info-and-chapters-payloads
 * keeps the payloads of the segment's Info and Chapters elements as the reader parses them, and
 * exports them as the demuxer's read-only binary options "info_payload" and "chapters_payload",
 * which ffkmp_fmt_exported_bytes copies out. The export cases compare those bytes with the ones the
 * writer below wrote, through a file, through an input that cannot seek back, and with the Chapters
 * element after the clusters, where only the SeekHead reaches it.
 */

#include "harness.h"
#include "kitecodec_helpers.h"

#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef KC_BUILD_FFMPEG_DIR
#define KC_BUILD_FFMPEG_DIR "unknown"
#endif

#define EDITIONS_PATCH "0013-matroska-take-the-chapters-of-the-default-edition.patch"
#define EXPORT_PATCH "0014-matroska-export-the-info-and-chapters-payloads.patch"

static char path[512];

static void remove_fixture(void)
{
    remove(path);
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

/* ---- A small EBML writer ---- */

typedef struct {
    uint8_t data[8192];
    size_t size;
} Ebml;

static void put_bytes(Ebml *b, const void *p, size_t n)
{
    KC_CHECKF(b->size + n <= sizeof(b->data), "a fixture outgrew its %zu byte buffer", sizeof(b->data));
    memcpy(b->data + b->size, p, n);
    b->size += n;
}

/* An element id as it is stored, its marker bit included, in as many bytes as it takes. */
static void put_id(Ebml *b, uint32_t id)
{
    uint8_t out[4];
    int n = id > 0xffffff ? 4 : id > 0xffff ? 3 : id > 0xff ? 2 : 1;
    for (int i = 0; i < n; i++)
        out[i] = (uint8_t)(id >> (8 * (n - 1 - i)));
    put_bytes(b, out, (size_t)n);
}

/* Every size in eight bytes, which EBML allows for any value and which keeps the writer simple. */
static void put_size(Ebml *b, uint64_t size)
{
    uint8_t out[8] = { 0x01 };
    for (int i = 1; i < 8; i++)
        out[i] = (uint8_t)(size >> (8 * (7 - i)));
    put_bytes(b, out, sizeof(out));
}

static void put_uint(Ebml *b, uint32_t id, uint64_t value)
{
    uint8_t out[8];
    int n = 1;
    while (n < 8 && value >> (8 * n)) n++;
    for (int i = 0; i < n; i++)
        out[i] = (uint8_t)(value >> (8 * (n - 1 - i)));
    put_id(b, id);
    put_size(b, (uint64_t)n);
    put_bytes(b, out, (size_t)n);
}

/* A uint in eight bytes whatever its value, so that an element holding it keeps its size when the
 * value changes. EBML allows the leading zeros. */
static void put_uint8(Ebml *b, uint32_t id, uint64_t value)
{
    uint8_t out[8];
    for (int i = 0; i < 8; i++)
        out[i] = (uint8_t)(value >> (8 * (7 - i)));
    put_id(b, id);
    put_size(b, sizeof(out));
    put_bytes(b, out, sizeof(out));
}

static void put_binary(Ebml *b, uint32_t id, const void *p, size_t n)
{
    put_id(b, id);
    put_size(b, n);
    put_bytes(b, p, n);
}

static void put_string(Ebml *b, uint32_t id, const char *s)
{
    put_binary(b, id, s, strlen(s));
}

static void put_float(Ebml *b, uint32_t id, double value)
{
    uint64_t bits;
    uint8_t out[8];
    memcpy(&bits, &value, sizeof(bits));
    for (int i = 0; i < 8; i++)
        out[i] = (uint8_t)(bits >> (8 * (7 - i)));
    put_binary(b, id, out, sizeof(out));
}

static void put_master(Ebml *b, uint32_t id, const Ebml *child)
{
    put_binary(b, id, child->data, child->size);
}

/* ---- Matroska ---- */

#define ID_EBML                 0x1A45DFA3
#define ID_EBMLVERSION          0x4286
#define ID_EBMLREADVERSION      0x42F7
#define ID_EBMLMAXIDLENGTH      0x42F2
#define ID_EBMLMAXSIZELENGTH    0x42F3
#define ID_DOCTYPE              0x4282
#define ID_DOCTYPEVERSION       0x4287
#define ID_DOCTYPEREADVERSION   0x4285
#define ID_SEGMENT              0x18538067
#define ID_SEEKHEAD             0x114D9B74
#define ID_SEEK                 0x4DBB
#define ID_SEEKID               0x53AB
#define ID_SEEKPOSITION         0x53AC
#define ID_INFO                 0x1549A966
#define ID_TIMESTAMPSCALE       0x2AD7B1
#define ID_DURATION             0x4489
#define ID_MUXINGAPP            0x4D80
#define ID_WRITINGAPP           0x5741
#define ID_SEGMENTUUID          0x73A4
#define ID_TRACKS               0x1654AE6B
#define ID_TRACKENTRY           0xAE
#define ID_TRACKNUMBER          0xD7
#define ID_TRACKUID             0x73C5
#define ID_TRACKTYPE            0x83
#define ID_CODECID              0x86
#define ID_CHAPTERS             0x1043A770
#define ID_EDITIONENTRY         0x45B9
#define ID_EDITIONUID           0x45BC
#define ID_EDITIONFLAGDEFAULT   0x45DB
#define ID_EDITIONFLAGORDERED   0x45DD
#define ID_CHAPTERATOM          0xB6
#define ID_CHAPTERUID           0x73C4
#define ID_CHAPTERTIMESTART     0x91
#define ID_CHAPTERTIMEEND       0x92
#define ID_CHAPTERFLAGHIDDEN    0x98
#define ID_CHAPTERFLAGENABLED   0x4598
#define ID_CHAPTERSEGMENTUUID   0x6E67
#define ID_CHAPTERDISPLAY       0x80
#define ID_CHAPSTRING           0x85
#define ID_TAGS                 0x1254C367
#define ID_TAG                  0x7373
#define ID_TARGETS              0x63C0
#define ID_TAGCHAPTERUID        0x63C4
#define ID_SIMPLETAG            0x67C8
#define ID_TAGNAME              0x45A3
#define ID_TAGSTRING            0x4487
#define ID_CLUSTER              0x1F43B675
#define ID_TIMESTAMP            0xE7
#define ID_BLOCKGROUP           0xA0
#define ID_BLOCK                0xA1
#define ID_BLOCKDURATION        0x9B

#define NS_PER_MS 1000000ULL

/* One chapter. enabled is 1 or 0 to write ChapterFlagEnabled, or -1 to leave it to its default. */
typedef struct {
    uint64_t uid;
    uint64_t start_ms;
    int hidden;
    int enabled;
    const uint8_t *segment_uid;
    const char *title;
} Atom;

static const uint8_t own_uid[16] = {
    0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf,
};
static const uint8_t other_uid[16] = {
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
};

/* Appends an EditionEntry to chapters. flag_default is 1 or 0 to write EditionFlagDefault, or -1 to
 * leave it to its default, which is 0, and ordered writes EditionFlagOrdered 1 when it is set. */
static void put_edition(Ebml *chapters, uint64_t uid, int flag_default, int ordered, const Atom *atoms, int n)
{
    Ebml edition = { .size = 0 };
    put_uint(&edition, ID_EDITIONUID, uid);
    if (flag_default >= 0) put_uint(&edition, ID_EDITIONFLAGDEFAULT, (uint64_t)flag_default);
    if (ordered) put_uint(&edition, ID_EDITIONFLAGORDERED, 1);
    for (int i = 0; i < n; i++) {
        Ebml atom = { .size = 0 }, display = { .size = 0 };
        put_uint(&atom, ID_CHAPTERUID, atoms[i].uid);
        put_uint(&atom, ID_CHAPTERTIMESTART, atoms[i].start_ms * NS_PER_MS);
        if (atoms[i].segment_uid) {
            /* A linked chapter must say where it ends. */
            put_uint(&atom, ID_CHAPTERTIMEEND, (atoms[i].start_ms + 500) * NS_PER_MS);
            put_binary(&atom, ID_CHAPTERSEGMENTUUID, atoms[i].segment_uid, 16);
        }
        if (atoms[i].hidden) put_uint(&atom, ID_CHAPTERFLAGHIDDEN, 1);
        if (atoms[i].enabled >= 0) put_uint(&atom, ID_CHAPTERFLAGENABLED, (uint64_t)atoms[i].enabled);
        put_string(&display, ID_CHAPSTRING, atoms[i].title);
        put_master(&atom, ID_CHAPTERDISPLAY, &display);
        put_master(&edition, ID_CHAPTERATOM, &atom);
    }
    put_master(chapters, ID_EDITIONENTRY, &edition);
}

/* Where write_matroska puts the Chapters element: before the clusters, after them with a SeekHead
 * at the start of the segment pointing at it, or twice before the clusters, the second time with
 * the payload in second_chapters. */
enum { CHAPTERS_FIRST, CHAPTERS_LAST, CHAPTERS_TWICE };

/* The fixture's bytes, and the Info payload it wrote, for the export cases to compare with. */
static Ebml file, written_info;

/* Writes the fixture: a six second segment whose Info carries segment_uid unless it is NULL, one
 * subtitle track with one block, and chapters and tags as the payloads of a Chapters and a Tags
 * element, each left out when empty, with the Chapters placed as layout says. */
static void write_matroska_as(const uint8_t *segment_uid, const Ebml *chapters, const Ebml *tags, int layout,
                              const Ebml *second_chapters)
{
    static Ebml segment, part, child, seekhead;

    file.size = 0;
    part.size = 0;
    put_uint(&part, ID_EBMLVERSION, 1);
    put_uint(&part, ID_EBMLREADVERSION, 1);
    put_uint(&part, ID_EBMLMAXIDLENGTH, 4);
    put_uint(&part, ID_EBMLMAXSIZELENGTH, 8);
    put_string(&part, ID_DOCTYPE, "matroska");
    put_uint(&part, ID_DOCTYPEVERSION, 4);
    put_uint(&part, ID_DOCTYPEREADVERSION, 2);
    put_master(&file, ID_EBML, &part);

    segment.size = 0;
    written_info.size = 0;
    put_uint(&written_info, ID_TIMESTAMPSCALE, 1000000);
    put_float(&written_info, ID_DURATION, 6000.0);
    put_string(&written_info, ID_MUXINGAPP, "test_editions");
    put_string(&written_info, ID_WRITINGAPP, "test_editions");
    if (segment_uid) put_binary(&written_info, ID_SEGMENTUUID, segment_uid, 16);
    put_master(&segment, ID_INFO, &written_info);

    part.size = 0;
    child.size = 0;
    put_uint(&child, ID_TRACKNUMBER, 1);
    put_uint(&child, ID_TRACKUID, 1);
    put_uint(&child, ID_TRACKTYPE, 0x11);
    put_string(&child, ID_CODECID, "S_TEXT/UTF8");
    put_master(&part, ID_TRACKENTRY, &child);
    put_master(&segment, ID_TRACKS, &part);

    if (chapters->size && layout != CHAPTERS_LAST) put_master(&segment, ID_CHAPTERS, chapters);
    if (layout == CHAPTERS_TWICE) put_master(&segment, ID_CHAPTERS, second_chapters);
    if (tags->size) put_master(&segment, ID_TAGS, tags);

    /* One block on track 1 at 0 ms, lasting a second. */
    static const uint8_t block[] = { 0x81, 0x00, 0x00, 0x00, 'h', 'i' };
    part.size = 0;
    child.size = 0;
    put_uint(&part, ID_TIMESTAMP, 0);
    put_binary(&child, ID_BLOCK, block, sizeof(block));
    put_uint(&child, ID_BLOCKDURATION, 1000);
    put_master(&part, ID_BLOCKGROUP, &child);
    put_master(&segment, ID_CLUSTER, &part);

    if (layout == CHAPTERS_LAST) {
        /* A SeekHead first in the segment, pointing at the Chapters after the cluster. Its position
         * is written in eight bytes, so the SeekHead is as long as it will be before the position
         * that depends on its length is known. */
        static const uint8_t chapters_id[4] = { 0x10, 0x43, 0xA7, 0x70 };
        Ebml seek = { .size = 0 }, ahead = { .size = 0 };
        put_binary(&seek, ID_SEEKID, chapters_id, sizeof(chapters_id));
        put_uint8(&seek, ID_SEEKPOSITION, 0);
        seekhead.size = 0;
        put_master(&seekhead, ID_SEEK, &seek);
        put_master(&ahead, ID_SEEKHEAD, &seekhead);
        uint64_t position = ahead.size + segment.size;
        seek.size = 0;
        put_binary(&seek, ID_SEEKID, chapters_id, sizeof(chapters_id));
        put_uint8(&seek, ID_SEEKPOSITION, position);
        seekhead.size = 0;
        put_master(&seekhead, ID_SEEK, &seek);
        ahead.size = 0;
        put_master(&ahead, ID_SEEKHEAD, &seekhead);
        put_bytes(&ahead, segment.data, segment.size);
        KC_EQ_SIZE(ahead.size, (size_t)position);
        put_master(&ahead, ID_CHAPTERS, chapters);
        segment = ahead;
    }

    put_master(&file, ID_SEGMENT, &segment);

    FILE *f = fopen(path, "wb");
    KC_NOT_NULL(f);
    KC_EQ_SIZE(fwrite(file.data, 1, file.size, f), file.size);
    fclose(f);
}

static void write_matroska(const uint8_t *segment_uid, const Ebml *chapters, const Ebml *tags)
{
    write_matroska_as(segment_uid, chapters, tags, CHAPTERS_FIRST, NULL);
}

/* The chapters FFmpeg lists for the fixture, as "uid@ms" joined by spaces. */
static void read_chapters(char *out, size_t size)
{
    AVFormatContext *fmt = NULL;
    KC_EQ_INT(avformat_open_input(&fmt, path, NULL, NULL), 0);
    out[0] = '\0';
    for (unsigned i = 0; i < fmt->nb_chapters; i++) {
        const AVChapter *c = fmt->chapters[i];
        size_t used = strlen(out);
        snprintf(out + used, size - used, "%s%lld@%lld", i ? " " : "", (long long)c->id,
                 (long long)av_rescale_q(c->start, c->time_base, (AVRational){ 1, 1000 }));
    }
    avformat_close_input(&fmt);
}

static void check_chapters(const uint8_t *segment_uid, const Ebml *chapters, const char *expected)
{
    char got[512];
    Ebml no_tags = { .size = 0 };
    write_matroska(segment_uid, chapters, &no_tags);
    read_chapters(got, sizeof(got));
    kc_detail("%s", got[0] ? got : "(none)");
    KC_EQ_STR(got, expected);
}

/* A theatrical and an extended cut, as two editions. The theatrical one's last chapter is hidden. */
static const Atom theatrical[] = {
    { 11, 0, 0, -1, NULL, "Theatrical one" },
    { 12, 2000, 0, -1, NULL, "Theatrical two" },
    { 13, 4000, 1, -1, NULL, "Theatrical hidden" },
};
static const Atom extended[] = {
    { 21, 0, 0, -1, NULL, "Extended one" },
    { 22, 1000, 0, -1, NULL, "Extended two" },
    { 23, 5000, 0, -1, NULL, "Extended three" },
};

static void case_one_plain_edition_reads_as_it_always_did(void)
{
    kc_case("one edition with no flags lists every chapter");
    static const Atom plain[] = {
        { 1, 0, 0, -1, NULL, "First" },
        { 2, 3000, 0, -1, NULL, "Second" },
    };
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 100, -1, 0, plain, 2);
    check_chapters(NULL, &chapters, "1@0 2@3000");
}

static void case_the_edition_flagged_default_is_read_alone(void)
{
    kc_case("the first edition flagged default is read without the other one or its hidden chapter");
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1001, 1, 0, theatrical, 3);
    put_edition(&chapters, 1002, -1, 0, extended, 3);
    check_chapters(NULL, &chapters, "11@0 12@2000");
}

static void case_a_later_edition_flagged_default_wins(void)
{
    kc_case("the second edition flagged default is read even though it comes second");
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1001, 0, 0, theatrical, 3);
    put_edition(&chapters, 1002, 1, 0, extended, 3);
    check_chapters(NULL, &chapters, "21@0 22@1000 23@5000");
}

static void case_with_no_default_the_first_edition_is_read(void)
{
    kc_case("with no edition flagged default the first one is read");
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1001, -1, 0, theatrical, 3);
    put_edition(&chapters, 1002, -1, 0, extended, 3);
    check_chapters(NULL, &chapters, "11@0 12@2000");
}

static void case_disabled_and_linked_chapters_are_left_out(void)
{
    kc_case("a disabled chapter and one an ordered edition links to another segment are left out");
    /* The chapter that names this segment's own UID breaks the specification, and names this one. */
    static const Atom flagged[] = {
        { 31, 0, 0, -1, NULL, "Own one" },
        { 32, 1500, 0, 0, NULL, "Disabled" },
        { 33, 2500, 0, -1, other_uid, "Linked" },
        { 34, 3500, 0, -1, own_uid, "Linked to itself" },
        { 35, 4500, 0, 1, NULL, "Own two" },
    };
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1003, -1, 1, flagged, 5);
    check_chapters(own_uid, &chapters, "31@0 34@3500 35@4500");
}

static void case_a_link_without_a_segment_uid_of_its_own_is_another_segment(void)
{
    kc_case("a segment with no UID of its own reads every linked chapter as another segment's");
    static const Atom linked[] = {
        { 41, 0, 0, -1, NULL, "Own" },
        { 42, 1000, 0, -1, own_uid, "Linked" },
    };
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1004, -1, 1, linked, 2);
    check_chapters(NULL, &chapters, "41@0");
}

static void case_a_link_in_an_edition_that_is_not_ordered_marks_this_segment(void)
{
    kc_case("a chapter linked to another segment in an edition that is not ordered is listed");
    /* RFC 9559 section 20.1.3: with simple chapters the link is only information. */
    static const Atom linked[] = {
        { 51, 0, 0, -1, NULL, "Own" },
        { 52, 1000, 0, -1, other_uid, "Linked" },
    };
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1005, -1, 0, linked, 2);
    check_chapters(own_uid, &chapters, "51@0 52@1000");
}

static void case_a_tag_finds_its_chapter_in_the_default_edition(void)
{
    kc_case("a tag that targets a chapter of the default edition reaches it");
    Ebml chapters = { .size = 0 }, tags = { .size = 0 }, tag = { .size = 0 }, part = { .size = 0 };
    put_edition(&chapters, 1001, -1, 0, extended, 3);
    put_edition(&chapters, 1002, 1, 0, theatrical, 3);
    put_uint(&part, ID_TAGCHAPTERUID, 12);
    put_master(&tag, ID_TARGETS, &part);
    part.size = 0;
    put_string(&part, ID_TAGNAME, "ARTIST");
    put_string(&part, ID_TAGSTRING, "Someone");
    put_master(&tag, ID_SIMPLETAG, &part);
    put_master(&tags, ID_TAG, &tag);
    write_matroska(NULL, &chapters, &tags);

    AVFormatContext *fmt = NULL;
    KC_EQ_INT(avformat_open_input(&fmt, path, NULL, NULL), 0);
    const AVDictionaryEntry *artist = NULL;
    for (unsigned i = 0; i < fmt->nb_chapters; i++)
        if (fmt->chapters[i]->id == 12)
            artist = av_dict_get(fmt->chapters[i]->metadata, "ARTIST", NULL, 0);
    kc_detail("%u chapters, ARTIST %s", fmt->nb_chapters, artist ? artist->value : "(none)");
    KC_EQ_INT((int)fmt->nb_chapters, 2);
    KC_NOT_NULL(artist);
    KC_EQ_STR(artist->value, "Someone");
    avformat_close_input(&fmt);
}

/* ---- The exported payloads (#173) ---- */

/* How a case reads the fixture: from the file, from memory through an input that cannot seek, or
 * from memory through one that can seek but fails to read again what it handed out more than a
 * buffer ago, as a network input would answer a second request it cannot serve. The memory inputs
 * have a buffer far smaller than the Info and Chapters payloads, so the export holds only if the
 * reader kept the payload's bytes rather than asking the input for them again. */
enum { FROM_FILE, UNSEEKABLE, FAILS_REREADS };
enum { SMALL = 64 };

typedef struct {
    size_t at;
    size_t furthest;
    int mode;
} Cursor;

static int read_fixture(void *opaque, uint8_t *buf, int size)
{
    Cursor *c = opaque;
    size_t left = file.size - c->at;
    size_t n = (size_t)size < left ? (size_t)size : left;
    /* The probe rewinds to the start once, and that read is allowed. */
    if (c->mode == FAILS_REREADS && c->at > 0 && c->at + SMALL < c->furthest) return AVERROR(EIO);
    if (n == 0) return AVERROR_EOF;
    memcpy(buf, file.data + c->at, n);
    c->at += n;
    if (c->at > c->furthest) c->furthest = c->at;
    return (int)n;
}

static int64_t seek_fixture(void *opaque, int64_t offset, int whence)
{
    Cursor *c = opaque;
    if (whence == AVSEEK_SIZE) return (int64_t)file.size;
    if (whence != SEEK_SET || offset < 0 || (size_t)offset > file.size) return AVERROR(EINVAL);
    c->at = (size_t)offset;
    return offset;
}

static AVFormatContext *open_fixture(int mode, Cursor *cursor)
{
    AVFormatContext *fmt = NULL;
    if (mode == FROM_FILE) {
        KC_EQ_INT(avformat_open_input(&fmt, path, NULL, NULL), 0);
        return fmt;
    }
    uint8_t *buffer = av_malloc(SMALL);
    KC_NOT_NULL(buffer);
    cursor->at = 0;
    cursor->furthest = 0;
    cursor->mode = mode;
    AVIOContext *io = avio_alloc_context(buffer, SMALL, 0, cursor, read_fixture,
                                         NULL, mode == FAILS_REREADS ? seek_fixture : NULL);
    KC_NOT_NULL(io);
    fmt = avformat_alloc_context();
    KC_NOT_NULL(fmt);
    fmt->pb = io;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    KC_EQ_INT(avformat_open_input(&fmt, NULL, av_find_input_format("matroska"), NULL), 0);
    return fmt;
}

static void close_fixture(AVFormatContext *fmt, int mode)
{
    AVIOContext *io = mode == FROM_FILE ? NULL : fmt->pb;
    avformat_close_input(&fmt);
    if (io) {
        av_freep(&io->buffer);
        avio_context_free(&io);
    }
}

/* Checks that the export called name holds exactly the bytes in want: its size asked with no
 * buffer, the whole copy, and a copy cut short by a smaller buffer. */
static void check_export(AVFormatContext *fmt, const char *name, const Ebml *want)
{
    static uint8_t got[sizeof(want->data)];
    int size = ffkmp_fmt_exported_bytes(fmt, name, NULL, 0);
    kc_detail("%s: %d bytes, %zu written", name, size, want->size);
    KC_EQ_INT(size, (int)want->size);
    memset(got, 0, sizeof(got));
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, name, got, (int)sizeof(got)), (int)want->size);
    KC_CHECKF(memcmp(got, want->data, want->size) == 0, "%s differs from the bytes written", name);
    memset(got, 0, sizeof(got));
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, name, got, 5), 5);
    KC_CHECKF(memcmp(got, want->data, 5) == 0 && got[5] == 0, "a short copy of %s is not its first 5 bytes", name);
}

/* Writes a file with two editions, its Chapters placed as layout says, reads it as mode says, and
 * checks the exports, and that the reader went on to read the chapters and the block after them. */
static void check_exports(int mode, int layout)
{
    Ebml chapters = { .size = 0 }, no_tags = { .size = 0 };
    put_edition(&chapters, 1001, -1, 1, theatrical, 3);
    put_edition(&chapters, 1002, 1, 0, extended, 3);
    write_matroska_as(own_uid, &chapters, &no_tags, layout, NULL);
    KC_CHECKF(written_info.size > 64 && chapters.size > 64, "the payloads must outgrow the 64 byte buffer");

    Cursor cursor;
    AVFormatContext *fmt = open_fixture(mode, &cursor);
    check_export(fmt, "info_payload", &written_info);
    check_export(fmt, "chapters_payload", &chapters);
    KC_EQ_INT((int)fmt->nb_chapters, 3);
    AVPacket *pkt = av_packet_alloc();
    KC_NOT_NULL(pkt);
    KC_EQ_INT(av_read_frame(fmt, pkt), 0);
    KC_EQ_INT(pkt->size, 2);
    KC_CHECKF(memcmp(pkt->data, "hi", 2) == 0, "the block after the payloads did not read");
    av_packet_free(&pkt);
    close_fixture(fmt, mode);
}

static void case_an_option_the_demuxer_does_not_export_reads_as_not_found(void)
{
    kc_case("the export helper answers not found for a name the demuxer does not export");
    Ebml chapters = { .size = 0 }, no_tags = { .size = 0 };
    put_edition(&chapters, 100, -1, 0, theatrical, 2);
    write_matroska(NULL, &chapters, &no_tags);
    AVFormatContext *fmt = NULL;
    uint8_t byte;
    KC_EQ_INT(avformat_open_input(&fmt, path, NULL, NULL), 0);
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, "no_such_payload", NULL, 0), AVERROR_OPTION_NOT_FOUND);
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, "no_such_payload", &byte, 1), AVERROR_OPTION_NOT_FOUND);
    KC_EQ_INT(ffkmp_fmt_exported_bytes(NULL, "info_payload", NULL, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, NULL, NULL, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, "info_payload", &byte, -1), AVERROR(EINVAL));
    avformat_close_input(&fmt);
}

static void case_the_info_and_chapters_payloads_are_exported_as_written(void)
{
    kc_case("a file's Info and Chapters payloads are exported byte for byte");
    check_exports(FROM_FILE, CHAPTERS_FIRST);
}

static void case_an_input_that_cannot_seek_exports_the_same_bytes(void)
{
    kc_case("an input that cannot seek, read through a 64 byte buffer, exports the same bytes");
    check_exports(UNSEEKABLE, CHAPTERS_FIRST);
}

static void case_chapters_after_the_clusters_are_exported_through_the_seekhead(void)
{
    kc_case("a Chapters element after the clusters, reached through the SeekHead, is exported");
    check_exports(FROM_FILE, CHAPTERS_LAST);
}

static void case_an_input_that_can_seek_is_not_asked_for_a_payload_twice(void)
{
    kc_case("an input that can seek but fails to serve a byte twice exports the same bytes");
    check_exports(FAILS_REREADS, CHAPTERS_FIRST);
}

static void case_a_file_without_chapters_exports_only_its_info(void)
{
    kc_case("a file with no Chapters element exports its Info and no chapters");
    Ebml no_chapters = { .size = 0 }, no_tags = { .size = 0 };
    write_matroska(NULL, &no_chapters, &no_tags);
    AVFormatContext *fmt = NULL;
    KC_EQ_INT(avformat_open_input(&fmt, path, NULL, NULL), 0);
    check_export(fmt, "info_payload", &written_info);
    KC_EQ_INT(ffkmp_fmt_exported_bytes(fmt, "chapters_payload", NULL, 0), AVERROR_OPTION_NOT_FOUND);
    avformat_close_input(&fmt);
}

static void case_a_second_chapters_element_does_not_replace_the_first(void)
{
    kc_case("of two Chapters elements the first is exported");
    /* RFC 9559 allows one; FFmpeg reads the second into the same list, and the export keeps the first. */
    Ebml first = { .size = 0 }, second = { .size = 0 }, no_tags = { .size = 0 };
    put_edition(&first, 1001, -1, 0, theatrical, 3);
    put_edition(&second, 1002, -1, 0, extended, 3);
    write_matroska_as(NULL, &first, &no_tags, CHAPTERS_TWICE, &second);
    AVFormatContext *fmt = NULL;
    KC_EQ_INT(avformat_open_input(&fmt, path, NULL, NULL), 0);
    check_export(fmt, "chapters_payload", &first);
    avformat_close_input(&fmt);
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_editions");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    const char *slash = tmp[strlen(tmp) - 1] == '/' ? "" : "/";
    snprintf(path, sizeof(path), "%s%skc_editions_%ld.mkv", tmp, slash, (long)getpid());
    atexit(remove_fixture);

    case_one_plain_edition_reads_as_it_always_did();
    case_an_option_the_demuxer_does_not_export_reads_as_not_found();

    if (linked_tree_carries(EXPORT_PATCH)) {
        case_the_info_and_chapters_payloads_are_exported_as_written();
        case_an_input_that_cannot_seek_exports_the_same_bytes();
        case_chapters_after_the_clusters_are_exported_through_the_seekhead();
        case_an_input_that_can_seek_is_not_asked_for_a_payload_twice();
        case_a_file_without_chapters_exports_only_its_info();
        case_a_second_chapters_element_does_not_replace_the_first();
    } else {
        kc_note("the linked FFmpeg's tree does not list %s, so the export cases did not run", EXPORT_PATCH);
    }

    if (!linked_tree_carries(EDITIONS_PATCH)) {
        kc_note("the linked FFmpeg's tree does not list %s, so the edition cases did not run", EDITIONS_PATCH);
        return kc_suite_end();
    }
    case_the_edition_flagged_default_is_read_alone();
    case_a_later_edition_flagged_default_wins();
    case_with_no_default_the_first_edition_is_read();
    case_disabled_and_linked_chapters_are_left_out();
    case_a_link_without_a_segment_uid_of_its_own_is_another_segment();
    case_a_link_in_an_edition_that_is_not_ordered_marks_this_segment();
    case_a_tag_finds_its_chapter_in_the_default_edition();

    return kc_suite_end();
}
