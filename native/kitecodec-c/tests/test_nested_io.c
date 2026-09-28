/* The nested opener of the custom-io open, from the C side.
 *
 * An HLS playlist arrives through the caller's read_fn, and every URL it names comes back to the
 * caller's opener: the segment, and for an encrypted segment the key too. The segment is a small
 * WAV, which FFmpeg's HLS demuxer takes as a segment format, so every byte that comes out of the
 * demuxer can be compared with the bytes that went in. The asan variant also checks every memory
 * access on the way, which matters most for the AES-128 reader.
 *
 * A linked FFmpeg without the trust_io_open patch, such as a distribution's, runs only the cases
 * that do not need it: the open with an opener must then fail with AVERROR(ENOSYS).
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavutil/aes.h>
#include <libavutil/base64.h>

#define KC_BASE "https://media.example/live/"
/* One second of 48 kHz stereo 16-bit PCM: more than three reads of the AES reader's buffer. */
#define KC_PCM_BYTES 192000
#define KC_WAV_BYTES (44 + KC_PCM_BYTES)
#define KC_MAX_ASKED 8

static const unsigned char kc_key[16] = {
    0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6, 0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c,
};
static const unsigned char kc_iv[16] = {
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
};
#define KC_IV_HEX "0x000102030405060708090A0B0C0D0E0F"

/* ---- A fake network: resources by URL, and a record of what the opener was asked ---- */

typedef struct {
    const char *url;
    const unsigned char *bytes;
    int64_t size;
} resource;

typedef struct {
    const resource *resources;
    int count;
    const char *refuse;         /* a URL the opener declines, or NULL */
    kc_interrupt *raise_on_open; /* a cell the opener raises before it serves, or NULL */
    char asked[KC_MAX_ASKED][512];
    int asked_count;
    int opened;
    int closed;
} network;

typedef struct {
    const unsigned char *bytes;
    int64_t size;
    int64_t position;
} cursor;

static int cursor_read(void *opaque, unsigned char *buf, int len)
{
    cursor *c = (cursor *)opaque;
    int64_t left = c->size - c->position;
    int n = left < len ? (int)left : len;
    if (n <= 0) return KC_IO_EOF;
    memcpy(buf, c->bytes + c->position, (size_t)n);
    c->position += n;
    return n;
}

static int64_t cursor_seek(void *opaque, int64_t offset, int whence)
{
    cursor *c = (cursor *)opaque;
    int64_t target = whence == SEEK_SET ? offset : whence == SEEK_CUR ? c->position + offset : c->size + offset;
    if (target < 0 || target > c->size) return KC_IO_ERR;
    c->position = target;
    return target;
}

static int network_open(void *opaque, const char *url, void **source, int64_t *size, int *seekable)
{
    network *net = (network *)opaque;
    if (net->asked_count < KC_MAX_ASKED) snprintf(net->asked[net->asked_count++], 512, "%s", url);
    if (net->refuse && strcmp(url, net->refuse) == 0) return KC_IO_REFUSED;
    for (int i = 0; i < net->count; i++) {
        if (strcmp(url, net->resources[i].url) != 0) continue;
        cursor *c = calloc(1, sizeof(cursor));
        if (!c) return KC_IO_ERR;
        c->bytes = net->resources[i].bytes;
        c->size = net->resources[i].size;
        if (net->raise_on_open) ffkmp_interrupt_raise(net->raise_on_open);
        *source = c;
        *size = c->size;
        *seekable = 1;
        net->opened++;
        return 0;
    }
    return KC_IO_REFUSED;
}

static void network_close(void *opaque, void *source)
{
    network *net = (network *)opaque;
    net->closed++;
    free(source);
}

static kc_io_opener opener_for(network *net)
{
    kc_io_opener opener = { net, network_open, cursor_read, cursor_seek, network_close };
    return opener;
}

static int was_asked(const network *net, const char *url)
{
    for (int i = 0; i < net->asked_count; i++) {
        if (strcmp(net->asked[i], url) == 0) return 1;
    }
    return 0;
}

/* ---- The media ---- */

/* A 48 kHz stereo 16-bit WAV whose PCM bytes follow a pattern the reads are compared with. */
static unsigned char *make_wav(void)
{
    static const unsigned char header[44] = {
        'R', 'I', 'F', 'F', 0x24, 0xEE, 0x02, 0, 'W', 'A', 'V', 'E',
        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 2, 0,
        0x80, 0xBB, 0, 0, 0x00, 0xEE, 0x02, 0, 4, 0, 16, 0,
        'd', 'a', 't', 'a', 0x00, 0xEE, 0x02, 0,
    };
    unsigned char *wav = malloc(KC_WAV_BYTES);
    KC_NOT_NULL(wav);
    memcpy(wav, header, sizeof(header));
    for (int i = 0; i < KC_PCM_BYTES; i++) wav[44 + i] = (unsigned char)(i * 7 + 3);
    return wav;
}

/* AES-128-CBC with PKCS#7 padding, as an HLS packager encrypts a segment (RFC 8216, 5.2). */
static unsigned char *encrypt_segment(const unsigned char *plain, int size, int *out_size)
{
    int pad = 16 - size % 16;
    int total = size + pad;
    unsigned char *padded = malloc((size_t)total);
    unsigned char *cipher = malloc((size_t)total);
    unsigned char iv[16];
    struct AVAES *aes = av_aes_alloc();
    KC_NOT_NULL(padded);
    KC_NOT_NULL(cipher);
    KC_NOT_NULL(aes);
    memcpy(padded, plain, (size_t)size);
    memset(padded + size, pad, (size_t)pad);
    memcpy(iv, kc_iv, sizeof(iv));
    KC_EQ_INT(av_aes_init(aes, kc_key, 128, 0), 0);
    av_aes_crypt(aes, cipher, padded, total / 16, iv, 0);
    av_free(aes);
    free(padded);
    *out_size = total;
    return cipher;
}

static char *make_playlist(const char *key_line)
{
    char *text = malloc(2048);
    KC_NOT_NULL(text);
    snprintf(text, 2048,
             "#EXTM3U\n"
             "#EXT-X-VERSION:3\n"
             "#EXT-X-TARGETDURATION:1\n"
             "#EXT-X-MEDIA-SEQUENCE:0\n"
             "#EXT-X-PLAYLIST-TYPE:VOD\n"
             "%s"
             "#EXTINF:1.0,\n"
             "seg0.wav\n"
             "#EXT-X-ENDLIST\n",
             key_line ? key_line : "");
    return text;
}

/* Every byte the demuxer hands out, in order; the count may exceed capacity. */
static int64_t read_all(kc_fmt_ctx *ctx, unsigned char *out, int64_t capacity)
{
    AVPacket *packet = av_packet_alloc();
    int64_t total = 0;
    KC_NOT_NULL(packet);
    while (av_read_frame(ctx, packet) >= 0) {
        if (total + packet->size <= capacity) memcpy(out + total, packet->data, (size_t)packet->size);
        total += packet->size;
        av_packet_unref(packet);
    }
    av_packet_free(&packet);
    return total;
}

/* Opens the playlist over a cursor, with the given url, MIME type and opener. */
static int open_playlist(kc_fmt_ctx **ctx, cursor *top, const char *playlist, const char *url,
                         const char *mime, const kc_io_opener *opener, kc_interrupt *interrupt,
                         kc_dict **unused)
{
    /* The WAV demuxer declares no file extension, so FFmpeg's segment extension check refuses a
       WAV segment. That check is FFmpeg's policy and not this suite's subject. */
    static const char *const keys[] = { "extension_picky" };
    static const char *const values[] = { "0" };
    top->bytes = (const unsigned char *)playlist;
    top->size = (int64_t)strlen(playlist);
    top->position = 0;
    return ffkmp_fmt_open_input_io2(ctx, top, cursor_read, cursor_seek, top->size, url, mime, opener,
                                    keys, values, 1, unused, interrupt);
}

/* ---- Cases that run on any FFmpeg ---- */

static void case_the_plain_open_still_works(void)
{
    unsigned char *wav = make_wav();
    cursor top = { wav, KC_WAV_BYTES, 0 };
    kc_fmt_ctx *ctx = NULL;

    kc_case("with no url, MIME type or opener, the open reads a WAV as before");
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, top.size, NULL, NULL,
                                       NULL, NULL, NULL, 0, NULL, NULL), 0);
    KC_NOT_NULL(ctx);
    ffkmp_fmt_close_input_io(&ctx);
    KC_NULL(ctx);
    free(wav);
}

static void case_the_url_names_the_input(void)
{
    unsigned char *wav = make_wav();
    cursor top = { wav, KC_WAV_BYTES, 0 };
    kc_fmt_ctx *ctx = NULL;

    kc_case("the url becomes the input's name, and nothing opens it");
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, top.size,
                                       KC_BASE "tone.wav", "audio/wav", NULL, NULL, NULL, 0, NULL,
                                       NULL), 0);
    KC_NOT_NULL(ctx);
    KC_EQ_STR(ctx->url, KC_BASE "tone.wav");
    ffkmp_fmt_close_input_io(&ctx);
    free(wav);
}

static void case_callers_cannot_set_trust_io_open(void)
{
    static const char *const keys[] = { "trust_io_open" };
    static const char *const values[] = { "1" };
    unsigned char *wav = make_wav();
    cursor top = { wav, KC_WAV_BYTES, 0 };
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;

    kc_case("a caller's trust_io_open option is refused on the byte-source and the path open");
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, top.size, NULL, NULL,
                                       NULL, keys, values, 1, NULL, NULL), AVERROR(EINVAL));
    KC_NULL(ctx);
    ctx = (kc_fmt_ctx *)0x1;
    KC_EQ_INT(ffkmp_fmt_open_input2(&ctx, "unused.wav", keys, values, 1, NULL, NULL), AVERROR(EINVAL));
    KC_NULL(ctx);
    free(wav);
}

static void case_an_incomplete_opener_is_refused(void)
{
    network net = { 0 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    char *playlist = make_playlist(NULL);

    kc_case("an opener without a close_fn is refused before anything is read");
    opener.close_fn = NULL;
    KC_EQ_INT(open_playlist(&ctx, &top, playlist, KC_BASE "index.m3u8", NULL, &opener, NULL, NULL),
              AVERROR(EINVAL));
    KC_NULL(ctx);
    KC_EQ_INT(net.asked_count, 0);
    free(playlist);
}

static void case_without_the_patch_an_opener_is_refused(void)
{
    network net = { 0 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    char *playlist = make_playlist(NULL);

    kc_case("a linked FFmpeg without the trust_io_open patch refuses an opener with ENOSYS");
    KC_EQ_INT(open_playlist(&ctx, &top, playlist, KC_BASE "index.m3u8", NULL, &opener, NULL, NULL),
              AVERROR(ENOSYS));
    KC_NULL(ctx);
    KC_EQ_INT(net.asked_count, 0);
    free(playlist);
}

/* ---- Cases that need the trust_io_open patch ---- */

static void case_hls_over_https_reads_every_byte(const char *url, const char *mime)
{
    unsigned char *wav = make_wav();
    unsigned char *out = malloc(KC_WAV_BYTES);
    resource resources[] = { { KC_BASE "seg0.wav", wav, KC_WAV_BYTES } };
    network net = { .resources = resources, .count = 1 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    kc_dict *unused = NULL;
    char *playlist = make_playlist(NULL);
    int rc;

    kc_case("an https playlist at %s with MIME type %s reads its segment through the opener",
            url, mime ? mime : "none");
    KC_NOT_NULL(out);
    rc = open_playlist(&ctx, &top, playlist, url, mime, &opener, NULL, &unused);
    kc_detail("rc=%d", rc);
    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(ctx);
    KC_EQ_STR(ctx->iformat->name, "hls");
    /* trust_io_open is this layer's key, never the caller's unused option. */
    KC_NULL(unused);
    KC_CHECK(was_asked(&net, KC_BASE "seg0.wav"));
    KC_EQ_I64(read_all(ctx, out, KC_WAV_BYTES), KC_PCM_BYTES);
    KC_EQ_MEM(out, wav + 44, KC_PCM_BYTES);
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(playlist);
    free(out);
    free(wav);
}

static void case_the_probe_needs_a_name_or_a_mime_type(void)
{
    network net = { 0 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    char *playlist = make_playlist(NULL);
    int rc;

    kc_case("a playlist whose url has no .m3u8 name and that has no MIME type does not open");
    rc = open_playlist(&ctx, &top, playlist, KC_BASE "index", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d", rc);
    KC_CHECK(rc < 0);
    KC_NULL(ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(playlist);
}

static void case_an_aes128_segment_decrypts(int key_as_data_url)
{
    unsigned char *wav = make_wav();
    unsigned char *out = malloc(KC_WAV_BYTES);
    int cipher_size = 0;
    unsigned char *cipher = encrypt_segment(wav, KC_WAV_BYTES, &cipher_size);
    resource resources[] = {
        { KC_BASE "seg0.wav", cipher, cipher_size },
        { KC_BASE "key.bin", kc_key, sizeof(kc_key) },
    };
    network net = { .resources = resources, .count = 2 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    char key_line[512];
    char *playlist;
    int rc;

    if (key_as_data_url) {
        char encoded[64];
        KC_NOT_NULL(av_base64_encode(encoded, sizeof(encoded), kc_key, sizeof(kc_key)));
        snprintf(key_line, sizeof(key_line),
                 "#EXT-X-KEY:METHOD=AES-128,URI=\"data:application/octet-stream;base64,%s\",IV=" KC_IV_HEX "\n",
                 encoded);
    } else {
        snprintf(key_line, sizeof(key_line), "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=" KC_IV_HEX "\n");
    }
    playlist = make_playlist(key_line);

    kc_case("an AES-128 segment with its key %s decrypts to the exact bytes",
            key_as_data_url ? "in a data: URL" : "behind the opener");
    KC_NOT_NULL(out);
    rc = open_playlist(&ctx, &top, playlist, KC_BASE "index.m3u8", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d", rc);
    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(ctx);
    KC_CHECK(was_asked(&net, KC_BASE "seg0.wav"));
    /* The data: key never reaches the opener; FFmpeg's data protocol reads it. */
    KC_EQ_INT(was_asked(&net, KC_BASE "key.bin"), key_as_data_url ? 0 : 1);
    for (int i = 0; i < net.asked_count; i++) KC_CHECK(strncmp(net.asked[i], "crypto", 6) != 0);
    KC_EQ_I64(read_all(ctx, out, KC_WAV_BYTES), KC_PCM_BYTES);
    KC_EQ_MEM(out, wav + 44, KC_PCM_BYTES);
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(playlist);
    free(cipher);
    free(out);
    free(wav);
}

static void case_a_refused_segment_opens_nothing(void)
{
    unsigned char *wav = make_wav();
    resource resources[] = { { KC_BASE "seg0.wav", wav, KC_WAV_BYTES } };
    network net = { .resources = resources, .count = 1, .refuse = KC_BASE "seg0.wav" };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    char *playlist = make_playlist(NULL);
    int rc;

    kc_case("a playlist whose only segment the opener refuses fails the open and leaks nothing");
    rc = open_playlist(&ctx, &top, playlist, KC_BASE "index.m3u8", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d", rc);
    KC_CHECK(rc < 0);
    KC_NULL(ctx);
    KC_CHECK(was_asked(&net, KC_BASE "seg0.wav"));
    KC_EQ_INT(net.opened, 0);
    KC_EQ_INT(net.closed, 0);
    free(playlist);
    free(wav);
}

static void case_an_interrupt_reaches_the_nested_reads(void)
{
    unsigned char *wav = make_wav();
    resource resources[] = { { KC_BASE "seg0.wav", wav, KC_WAV_BYTES } };
    network net = { .resources = resources, .count = 1 };
    kc_io_opener opener = opener_for(&net);
    kc_interrupt *cell = ffkmp_interrupt_new();
    cursor top;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    char *playlist = make_playlist(NULL);
    int rc;

    kc_case("an interrupt raised while a segment opens stops its reads, and every source closes");
    KC_NOT_NULL(cell);
    net.raise_on_open = cell;
    rc = open_playlist(&ctx, &top, playlist, KC_BASE "index.m3u8", NULL, &opener, cell, NULL);
    kc_detail("rc=%d", rc);
    KC_CHECK(rc < 0);
    KC_NULL(ctx);
    KC_EQ_INT(net.opened, 1);
    KC_EQ_INT(net.closed, 1);
    ffkmp_interrupt_free(&cell);
    free(playlist);
    free(wav);
}

int main(void)
{
    kc_suite_begin("test_nested_io");

    case_the_plain_open_still_works();
    case_the_url_names_the_input();
    case_callers_cannot_set_trust_io_open();
    case_an_incomplete_opener_is_refused();

    if (!ffkmp_fmt_nested_io_available()) {
        kc_note("the linked FFmpeg lacks the trust_io_open patch, so the HLS cases did not run");
        case_without_the_patch_an_opener_is_refused();
        return kc_suite_end();
    }

    case_hls_over_https_reads_every_byte(KC_BASE "index", "application/vnd.apple.mpegurl");
    case_hls_over_https_reads_every_byte(KC_BASE "index.m3u8", NULL);
    case_hls_over_https_reads_every_byte(KC_BASE "index.m3u8?token=abc", NULL);
    case_the_probe_needs_a_name_or_a_mime_type();
    case_an_aes128_segment_decrypts(0);
    case_an_aes128_segment_decrypts(1);
    case_a_refused_segment_opens_nothing();
    case_an_interrupt_reaches_the_nested_reads();

    return kc_suite_end();
}
