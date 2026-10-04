/* A Matroska file's editions (#172).
 *
 * A Matroska file can carry several editions, each with its own chapters, such as a theatrical and
 * an extended cut, and RFC 9559 makes one of them the default: the first flagged default, or the
 * first when none is. A hidden chapter is not shown, the content of a disabled chapter is skipped,
 * and a chapter with a ChapterSegmentUUID plays a range of another segment. FFmpeg's Matroska
 * reader put every edition's chapters into one list and kept each that started later than the last
 * it kept, hidden, disabled and linked ones included. The patch
 * 0013-matroska-take-the-chapters-of-the-default-edition makes the chapter list the default
 * edition's, without those three kinds.
 *
 * Each case writes its own file with the small EBML writer below: an EBML header, and a segment
 * with an Info, one subtitle track, the case's Chapters and Tags, and one cluster holding one block.
 * A linked FFmpeg whose tree does not list the patch in lib/kiteffmpeg/ffmpeg-patches.txt, such as
 * a distribution's, runs only the case that holds without it.
 */

#include "harness.h"

#include <libavformat/avformat.h>
#include <libavutil/dict.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef KC_BUILD_FFMPEG_DIR
#define KC_BUILD_FFMPEG_DIR "unknown"
#endif

#define EDITIONS_PATCH "0013-matroska-take-the-chapters-of-the-default-edition.patch"

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
 * leave it to its default, which is 0. */
static void put_edition(Ebml *chapters, uint64_t uid, int flag_default, const Atom *atoms, int n)
{
    Ebml edition = { .size = 0 };
    put_uint(&edition, ID_EDITIONUID, uid);
    if (flag_default >= 0) put_uint(&edition, ID_EDITIONFLAGDEFAULT, (uint64_t)flag_default);
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

/* Writes the fixture: a six second segment whose Info carries segment_uid unless it is NULL, one
 * subtitle track with one block, and chapters and tags as the payloads of a Chapters and a Tags
 * element, each left out when empty. */
static void write_matroska(const uint8_t *segment_uid, const Ebml *chapters, const Ebml *tags)
{
    static Ebml file, segment, part, child;

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
    part.size = 0;
    put_uint(&part, ID_TIMESTAMPSCALE, 1000000);
    put_float(&part, ID_DURATION, 6000.0);
    put_string(&part, ID_MUXINGAPP, "test_editions");
    put_string(&part, ID_WRITINGAPP, "test_editions");
    if (segment_uid) put_binary(&part, ID_SEGMENTUUID, segment_uid, 16);
    put_master(&segment, ID_INFO, &part);

    part.size = 0;
    child.size = 0;
    put_uint(&child, ID_TRACKNUMBER, 1);
    put_uint(&child, ID_TRACKUID, 1);
    put_uint(&child, ID_TRACKTYPE, 0x11);
    put_string(&child, ID_CODECID, "S_TEXT/UTF8");
    put_master(&part, ID_TRACKENTRY, &child);
    put_master(&segment, ID_TRACKS, &part);

    if (chapters->size) put_master(&segment, ID_CHAPTERS, chapters);
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

    put_master(&file, ID_SEGMENT, &segment);

    FILE *f = fopen(path, "wb");
    KC_NOT_NULL(f);
    KC_EQ_SIZE(fwrite(file.data, 1, file.size, f), file.size);
    fclose(f);
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
    put_edition(&chapters, 100, -1, plain, 2);
    check_chapters(NULL, &chapters, "1@0 2@3000");
}

static void case_the_edition_flagged_default_is_read_alone(void)
{
    kc_case("the first edition flagged default is read without the other one or its hidden chapter");
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1001, 1, theatrical, 3);
    put_edition(&chapters, 1002, -1, extended, 3);
    check_chapters(NULL, &chapters, "11@0 12@2000");
}

static void case_a_later_edition_flagged_default_wins(void)
{
    kc_case("the second edition flagged default is read even though it comes second");
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1001, 0, theatrical, 3);
    put_edition(&chapters, 1002, 1, extended, 3);
    check_chapters(NULL, &chapters, "21@0 22@1000 23@5000");
}

static void case_with_no_default_the_first_edition_is_read(void)
{
    kc_case("with no edition flagged default the first one is read");
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1001, -1, theatrical, 3);
    put_edition(&chapters, 1002, -1, extended, 3);
    check_chapters(NULL, &chapters, "11@0 12@2000");
}

static void case_disabled_and_linked_chapters_are_left_out(void)
{
    kc_case("a disabled chapter and one linked to another segment are left out");
    /* The chapter that names this segment's own UID breaks the specification, and names this one. */
    static const Atom flagged[] = {
        { 31, 0, 0, -1, NULL, "Own one" },
        { 32, 1500, 0, 0, NULL, "Disabled" },
        { 33, 2500, 0, -1, other_uid, "Linked" },
        { 34, 3500, 0, -1, own_uid, "Linked to itself" },
        { 35, 4500, 0, 1, NULL, "Own two" },
    };
    Ebml chapters = { .size = 0 };
    put_edition(&chapters, 1003, -1, flagged, 5);
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
    put_edition(&chapters, 1004, -1, linked, 2);
    check_chapters(NULL, &chapters, "41@0");
}

static void case_a_tag_finds_its_chapter_in_the_default_edition(void)
{
    kc_case("a tag that targets a chapter of the default edition reaches it");
    Ebml chapters = { .size = 0 }, tags = { .size = 0 }, tag = { .size = 0 }, part = { .size = 0 };
    put_edition(&chapters, 1001, -1, extended, 3);
    put_edition(&chapters, 1002, 1, theatrical, 3);
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

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_editions");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    const char *slash = tmp[strlen(tmp) - 1] == '/' ? "" : "/";
    snprintf(path, sizeof(path), "%s%skc_editions_%ld.mkv", tmp, slash, (long)getpid());
    atexit(remove_fixture);

    case_one_plain_edition_reads_as_it_always_did();

    if (!linked_tree_carries(EDITIONS_PATCH)) {
        kc_note("the linked FFmpeg's tree does not list %s, so the edition cases did not run", EDITIONS_PATCH);
        return kc_suite_end();
    }
    case_the_edition_flagged_default_is_read_alone();
    case_a_later_edition_flagged_default_wins();
    case_with_no_default_the_first_edition_is_read();
    case_disabled_and_linked_chapters_are_left_out();
    case_a_link_without_a_segment_uid_of_its_own_is_another_segment();
    case_a_tag_finds_its_chapter_in_the_default_edition();

    return kc_suite_end();
}
