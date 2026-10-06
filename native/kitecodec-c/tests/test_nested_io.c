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
 *
 * A source can say where its bytes came from after a redirect (#167), and the HLS demuxer then
 * resolves the addresses inside it against that location, as it does after FFmpeg's own http
 * follows a redirect. Those cases need the trust_io_open patch too.
 *
 * Playlist variables (#166) need the patch that substitutes them, so their cases run only against
 * a tree that lists it among the patches it was built with.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavutil/aes.h>
#include <libavutil/base64.h>
#include <libavutil/log.h>
#include <stdarg.h>

#define KC_BASE "https://media.example/live/"
/* One second of 48 kHz stereo 16-bit PCM: more than three reads of the AES reader's buffer. */
#define KC_PCM_BYTES 192000
#define KC_WAV_BYTES (44 + KC_PCM_BYTES)
#define KC_MAX_ASKED 96

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

/* A URL whose bytes say they came from somewhere else, as after an HTTP redirect. */
typedef struct {
    const char *url;
    const char *location;
} redirect;

typedef struct {
    const resource *resources;
    int count;
    const redirect *redirects;  /* the URLs whose sources name a location */
    int redirect_count;
    int location_fails;         /* location_fn answers with a failure */
    int location_calls;
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
    /* FFmpeg's size probe reaches the reader and moves nothing (#177). */
    if (whence == AVSEEK_SIZE) return c->size;
    int64_t target = whence == SEEK_SET ? offset : whence == SEEK_CUR ? c->position + offset : c->size + offset;
    if (target < 0 || target > c->size) return KC_IO_ERR;
    c->position = target;
    return target;
}

/* What the opener serves: a cursor, first so that cursor_read and cursor_seek take it, and the
   location its bytes name. */
typedef struct {
    cursor at;
    const char *location;
} served;

static int network_open(void *opaque, const char *url, void **source, int64_t *size, int *seekable)
{
    network *net = (network *)opaque;
    if (net->asked_count < KC_MAX_ASKED) snprintf(net->asked[net->asked_count++], 512, "%s", url);
    if (net->refuse && strcmp(url, net->refuse) == 0) return KC_IO_REFUSED;
    for (int i = 0; i < net->count; i++) {
        if (strcmp(url, net->resources[i].url) != 0) continue;
        served *c = calloc(1, sizeof(served));
        if (!c) return KC_IO_ERR;
        c->at.bytes = net->resources[i].bytes;
        c->at.size = net->resources[i].size;
        for (int r = 0; r < net->redirect_count; r++) {
            if (strcmp(url, net->redirects[r].url) == 0) c->location = net->redirects[r].location;
        }
        if (net->raise_on_open) ffkmp_interrupt_raise(net->raise_on_open);
        *source = c;
        *size = c->at.size;
        *seekable = 1;
        net->opened++;
        return 0;
    }
    return KC_IO_REFUSED;
}

static int network_location(void *opaque, void *source, char *buf, int cap)
{
    network *net = (network *)opaque;
    const served *c = (const served *)source;
    int len;
    net->location_calls++;
    if (net->location_fails) return KC_IO_ERR;
    if (!c->location) return 0;
    len = (int)strlen(c->location);
    if (len < cap) memcpy(buf, c->location, (size_t)len + 1);
    return len;
}

static void network_close(void *opaque, void *source)
{
    network *net = (network *)opaque;
    net->closed++;
    free(source);
}

static kc_io_opener opener_for(network *net)
{
    kc_io_opener opener = {
        .opaque = net,
        .open_fn = network_open,
        .read_fn = cursor_read,
        .seek_fn = cursor_seek,
        .close_fn = network_close,
        .location_fn = network_location,
    };
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

/* Opens the playlist over a cursor, with the given url, location, MIME type and opener. */
static int open_playlist_at(kc_fmt_ctx **ctx, cursor *top, const char *playlist, const char *url,
                            const char *location, const char *mime, const kc_io_opener *opener,
                            kc_interrupt *interrupt, kc_dict **unused)
{
    /* The WAV demuxer declares no file extension, so FFmpeg's segment extension check refuses a
       WAV segment. That check is FFmpeg's policy and not this suite's subject. */
    static const char *const keys[] = { "extension_picky" };
    static const char *const values[] = { "0" };
    top->bytes = (const unsigned char *)playlist;
    top->size = (int64_t)strlen(playlist);
    top->position = 0;
    return ffkmp_fmt_open_input_io2(ctx, top, cursor_read, cursor_seek, NULL, top->size, url, location,
                                    mime, opener, keys, values, 1, unused, interrupt);
}

/* Opens the playlist over a cursor, with the given url, MIME type and opener, and no location. */
static int open_playlist(kc_fmt_ctx **ctx, cursor *top, const char *playlist, const char *url,
                         const char *mime, const kc_io_opener *opener, kc_interrupt *interrupt,
                         kc_dict **unused)
{
    return open_playlist_at(ctx, top, playlist, url, NULL, mime, opener, interrupt, unused);
}

/* ---- Cases that run on any FFmpeg ---- */

static void case_the_plain_open_still_works(void)
{
    unsigned char *wav = make_wav();
    cursor top = { wav, KC_WAV_BYTES, 0 };
    kc_fmt_ctx *ctx = NULL;

    kc_case("with no url, MIME type or opener, the open reads a WAV as before");
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, NULL, top.size, NULL, NULL,
                                       NULL, NULL, NULL, NULL, 0, NULL, NULL), 0);
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
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, NULL, top.size,
                                       KC_BASE "tone.wav", NULL, "audio/wav", NULL, NULL, NULL, 0,
                                       NULL, NULL), 0);
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
    KC_EQ_INT(ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, NULL, top.size, NULL, NULL,
                                       NULL, NULL, keys, values, 1, NULL, NULL), AVERROR(EINVAL));
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

/* ---- Where a redirected source's bytes came from (#167) ---- */

#define KC_CDN "https://cdn.example/x/"

/* A master playlist with one variant, video.m3u8, beside it. */
static const char kc_master[] =
    "#EXTM3U\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=1000000\n"
    "video.m3u8\n";

static void case_a_redirected_variant_resolves_against_its_location(int with_location_fn)
{
    char *media = make_playlist(NULL);
    unsigned char *wav = make_wav();
    unsigned char *out = malloc(KC_WAV_BYTES);
    resource resources[] = {
        { KC_BASE "video.m3u8", (const unsigned char *)media, (int64_t)strlen(media) },
        { KC_CDN "seg0.wav", wav, KC_WAV_BYTES },
    };
    redirect moved[] = { { KC_BASE "video.m3u8", KC_CDN "video.m3u8" } };
    network net = { .resources = resources, .count = 2, .redirects = moved, .redirect_count = 1 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    int rc;

    if (!with_location_fn) opener.location_fn = NULL;
    kc_case("a variant playlist that came from another address %s",
            with_location_fn ? "has its segment asked for beside that address"
                             : "has its segment asked for beside the address asked for, without a location_fn");
    KC_NOT_NULL(out);
    rc = open_playlist(&ctx, &top, kc_master, KC_BASE "master.m3u8", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d asked=%d", rc, net.asked_count);
    for (int i = 0; i < net.asked_count; i++) kc_detail("asked %s", net.asked[i]);
    KC_CHECK(was_asked(&net, KC_BASE "video.m3u8"));
    if (with_location_fn) {
        KC_EQ_INT(rc, 0);
        KC_NOT_NULL(ctx);
        KC_CHECK(was_asked(&net, KC_CDN "seg0.wav"));
        KC_CHECK(!was_asked(&net, KC_BASE "seg0.wav"));
        /* Once for the variant, once for the segment, which came from where it was asked. */
        KC_EQ_INT(net.location_calls, 2);
        KC_EQ_I64(read_all(ctx, out, KC_WAV_BYTES), KC_PCM_BYTES);
        KC_EQ_MEM(out, wav + 44, KC_PCM_BYTES);
        ffkmp_fmt_close_input_io(&ctx);
    } else {
        KC_CHECK(rc < 0);
        KC_NULL(ctx);
        KC_CHECK(was_asked(&net, KC_BASE "seg0.wav"));
        KC_EQ_INT(net.location_calls, 0);
    }
    KC_EQ_INT(net.closed, net.opened);
    free(out);
    free(wav);
    free(media);
}

static void case_the_input_location_rebases_its_variants(const char *location)
{
    char *media = make_playlist(NULL);
    unsigned char *wav = make_wav();
    int located = location && *location;
    const char *base = located ? KC_CDN : KC_BASE;
    char variant[128], segment[128];
    resource resources[2];
    network net = { .resources = resources, .count = 2 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    int rc;

    snprintf(variant, sizeof(variant), "%svideo.m3u8", base);
    snprintf(segment, sizeof(segment), "%sseg0.wav", base);
    resources[0] = (resource){ variant, (const unsigned char *)media, (int64_t)strlen(media) };
    resources[1] = (resource){ segment, wav, KC_WAV_BYTES };
    kc_case("a master playlist with %s location resolves its variant against %s, and keeps its url "
            "as its name", located ? "a" : location ? "an empty" : "no",
            located ? "the location" : "the url");
    rc = open_playlist_at(&ctx, &top, kc_master, KC_BASE "master.m3u8", location, NULL, &opener,
                          NULL, NULL);
    kc_detail("rc=%d", rc);
    for (int i = 0; i < net.asked_count; i++) kc_detail("asked %s", net.asked[i]);
    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(ctx);
    KC_EQ_STR(ctx->url, KC_BASE "master.m3u8");
    KC_CHECK(was_asked(&net, variant));
    KC_CHECK(was_asked(&net, segment));
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(wav);
    free(media);
}

static void case_a_long_location_takes_a_second_call(void)
{
    /* Longer than the first buffer the layer offers, and shorter than hls.c's MAX_URL_SIZE. */
    enum { KC_LONG_DIR = 3000 };
    char *media = make_playlist(NULL);
    unsigned char *wav = make_wav();
    char *location = malloc(64 + KC_LONG_DIR);
    char *segment = malloc(64 + KC_LONG_DIR);
    char dir[KC_LONG_DIR + 1];
    int rc;

    KC_NOT_NULL(location);
    KC_NOT_NULL(segment);
    memset(dir, 'd', KC_LONG_DIR);
    dir[KC_LONG_DIR] = 0;
    snprintf(location, 64 + KC_LONG_DIR, KC_CDN "%s/video.m3u8", dir);
    snprintf(segment, 64 + KC_LONG_DIR, KC_CDN "%s/seg0.wav", dir);
    resource resources[] = {
        { KC_BASE "video.m3u8", (const unsigned char *)media, (int64_t)strlen(media) },
        { segment, wav, KC_WAV_BYTES },
    };
    redirect moved[] = { { KC_BASE "video.m3u8", location } };
    network net = { .resources = resources, .count = 2, .redirects = moved, .redirect_count = 1 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;

    kc_case("a location of %d bytes, longer than the first buffer, is asked for again and used whole",
            (int)strlen(location));
    rc = open_playlist(&ctx, &top, kc_master, KC_BASE "master.m3u8", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d", rc);
    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(ctx);
    /* The opener serves the segment only at its full address, so the open proves it arrived whole. */
    KC_EQ_INT(net.opened, 2);
    KC_EQ_INT(net.location_calls, 3);
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(segment);
    free(location);
    free(wav);
    free(media);
}

static void case_a_failed_location_fails_that_address(void)
{
    char *media = make_playlist(NULL);
    resource resources[] = {
        { KC_BASE "video.m3u8", (const unsigned char *)media, (int64_t)strlen(media) },
    };
    network net = { .resources = resources, .count = 1, .location_fails = 1 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    int rc;

    kc_case("a location_fn that fails fails the address it was asked about, and its source closes");
    rc = open_playlist(&ctx, &top, kc_master, KC_BASE "master.m3u8", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d", rc);
    KC_CHECK(rc < 0);
    KC_NULL(ctx);
    KC_EQ_INT(net.opened, 1);
    KC_EQ_INT(net.closed, 1);
    KC_CHECK(!was_asked(&net, KC_BASE "seg0.wav"));
    free(media);
}

/* ---- Playlist variables, which need the patch that substitutes them ---- */

/* FFmpeg's patch that defines and substitutes playlist variables. */
#define KC_VARIABLES_PATCH "0011-hls-substitute-playlist-variables.patch"

/* 1 when the linked FFmpeg's tree lists [patch] among the patches it was built with. */
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

/* The error lines FFmpeg logged while a capture was on. */
static char kc_logged[8192];

static void capture_errors(void *avcl, int level, const char *fmt, va_list vl)
{
    char line[1024];
    (void)avcl;
    if ((level & 0xff) > AV_LOG_ERROR) return;
    vsnprintf(line, sizeof(line), fmt, vl);
    size_t used = strlen(kc_logged);
    snprintf(kc_logged + used, sizeof(kc_logged) - used, "%s", line);
}

static void case_playlist_variables_reach_every_address(void)
{
    static const char master[] =
        "#EXTM3U\n"
        "#EXT-X-VERSION:8\n"
        "#EXT-X-DEFINE:QUERYPARAM=\"token\"\n"
        "#EXT-X-DEFINE:NAME=\"dir\",VALUE=\"v\"\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=1000000\n"
        "{$dir}ideo.m3u8?token={$token}\n";
    static const char media[] =
        "#EXTM3U\n"
        "#EXT-X-VERSION:8\n"
        "#EXT-X-DEFINE:IMPORT=\"token\"\n"
        "#EXT-X-DEFINE:NAME=\"name\",VALUE=\"seg0\"\n"
        "#EXT-X-DEFINE:NAME=\"iv\",VALUE=\"000102030405060708090A0B0C0D0E0F\"\n"
        "#EXT-X-TARGETDURATION:1\n"
        "#EXT-X-PLAYLIST-TYPE:VOD\n"
        "#EXT-X-KEY:METHOD=AES-128,URI=\"{$name}.key?token={$token}\",IV=0x{$iv}\n"
        "#EXTINF:1.0,\n"
        "{$name}.wav?token={$token}\n"
        "#EXT-X-ENDLIST\n";
    unsigned char *wav = make_wav();
    unsigned char *out = malloc(KC_WAV_BYTES);
    int cipher_size = 0;
    unsigned char *cipher = encrypt_segment(wav, KC_WAV_BYTES, &cipher_size);
    resource resources[] = {
        { KC_BASE "video.m3u8?token=t=1", (const unsigned char *)media, (int64_t)strlen(media) },
        { KC_BASE "seg0.wav?token=t=1", cipher, cipher_size },
        { KC_BASE "seg0.key?token=t=1", kc_key, sizeof(kc_key) },
    };
    network net = { .resources = resources, .count = 3 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    int rc;

    kc_case("a token the master's own address carries reaches the media playlist, the key and the "
            "segment, percent-decoded, and the segment decrypts to the exact bytes");
    KC_NOT_NULL(out);
    rc = open_playlist(&ctx, &top, master, KC_BASE "master.m3u8?token=t%3D1", NULL, &opener, NULL,
                       NULL);
    kc_detail("rc=%d asked=%d", rc, net.asked_count);
    for (int i = 0; i < net.asked_count; i++) kc_detail("asked %s", net.asked[i]);
    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(ctx);
    KC_CHECK(was_asked(&net, KC_BASE "video.m3u8?token=t=1"));
    KC_CHECK(was_asked(&net, KC_BASE "seg0.key?token=t=1"));
    KC_CHECK(was_asked(&net, KC_BASE "seg0.wav?token=t=1"));
    for (int i = 0; i < net.asked_count; i++) KC_CHECK(strstr(net.asked[i], "{$") == NULL);
    KC_EQ_I64(read_all(ctx, out, KC_WAV_BYTES), KC_PCM_BYTES);
    KC_EQ_MEM(out, wav + 44, KC_PCM_BYTES);
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(cipher);
    free(out);
    free(wav);
}

static void case_a_value_is_substituted_once_and_names_keep_their_case(void)
{
    static const char media[] =
        "#EXTM3U\n"
        "#EXT-X-VERSION:8\n"
        "#EXT-X-DEFINE:NAME=\"b\",VALUE=\"x\"\n"
        "#EXT-X-DEFINE:NAME=\"a\",VALUE=\"{$b}\"\n"
        "#EXT-X-DEFINE:NAME=\"Case\",VALUE=\"upper\"\n"
        "#EXT-X-DEFINE:NAME=\"case\",VALUE=\"lower\"\n"
        "#EXT-X-TARGETDURATION:1\n"
        "#EXT-X-PLAYLIST-TYPE:VOD\n"
        "#EXTINF:1.0,\n"
        "seg{$a}-{$Case}-{$case}-{$}-{$no space}.wav\n"
        "#EXT-X-ENDLIST\n";
    unsigned char *wav = make_wav();
    resource resources[] = { { KC_BASE "seg{$b}-upper-lower-{$}-{$no space}.wav", wav, KC_WAV_BYTES } };
    network net = { .resources = resources, .count = 1 };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    int rc;

    kc_case("a value holding a reference is used as it is, two names that differ in case are two "
            "variables, and a {$ that names no variable stays as written");
    rc = open_playlist(&ctx, &top, media, KC_BASE "index.m3u8", NULL, &opener, NULL, NULL);
    kc_detail("rc=%d", rc);
    for (int i = 0; i < net.asked_count; i++) kc_detail("asked %s", net.asked[i]);
    KC_EQ_INT(rc, 0);
    KC_CHECK(was_asked(&net, KC_BASE "seg{$b}-upper-lower-{$}-{$no space}.wav"));
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    free(wav);
}

typedef struct {
    const char *why;
    const char *url;        /* the top-level playlist's address */
    const char *top;        /* the top-level playlist */
    const char *media;      /* the media playlist a master names as media.m3u8, or NULL */
    const char *named;      /* what the logged error must name */
    const char *says;       /* what else it must say, or NULL */
} failing_playlist;

#define KC_MEDIA_HEAD "#EXTM3U\n#EXT-X-VERSION:8\n#EXT-X-TARGETDURATION:1\n#EXT-X-PLAYLIST-TYPE:VOD\n"
#define KC_MEDIA_TAIL "#EXTINF:1.0,\nseg0.wav\n#EXT-X-ENDLIST\n"

static const failing_playlist kc_failing[] = {
    { "a reference to a variable that nothing defined", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXTINF:1.0,\nseg0.wav?token={$missing}\n#EXT-X-ENDLIST\n", NULL, "'missing'", NULL },
    { "a reference before the definition that gives it", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXTINF:1.0,\nseg0.wav?v={$late}\n#EXT-X-DEFINE:NAME=\"late\",VALUE=\"1\"\n"
      "#EXT-X-ENDLIST\n", NULL, "'late'", NULL },
    { "an IMPORT in a playlist that no master loaded", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:IMPORT=\"token\"\n" KC_MEDIA_TAIL, NULL, "'token'",
      "which no master playlist loaded" },
    { "an IMPORT in the master playlist itself", KC_BASE "master.m3u8",
      "#EXTM3U\n#EXT-X-DEFINE:NAME=\"token\",VALUE=\"1\"\n#EXT-X-DEFINE:IMPORT=\"other\"\n"
      "#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n", KC_MEDIA_HEAD KC_MEDIA_TAIL, "'other'",
      "which no master playlist loaded" },
    { "a QUERYPARAM that the address does not carry", KC_BASE "index.m3u8?other=1",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:QUERYPARAM=\"token\"\n" KC_MEDIA_TAIL, NULL, "'token'", NULL },
    { "a QUERYPARAM whose parameter has no value", KC_BASE "index.m3u8?token",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:QUERYPARAM=\"token\"\n" KC_MEDIA_TAIL, NULL, "'token'", NULL },
    { "a QUERYPARAM whose value holds a quote", KC_BASE "index.m3u8?token=a%22b",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:QUERYPARAM=\"token\"\n" KC_MEDIA_TAIL, NULL, "'token'", NULL },
    { "a NAME without a VALUE", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:NAME=\"lonely\"\n" KC_MEDIA_TAIL, NULL, "'lonely'", NULL },
    { "a name defined twice", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:NAME=\"twice\",VALUE=\"1\"\n#EXT-X-DEFINE:NAME=\"twice\",VALUE=\"2\"\n"
      KC_MEDIA_TAIL, NULL, "'twice'", NULL },
    { "a name with a character outside the set", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:NAME=\"bad.name\",VALUE=\"1\"\n" KC_MEDIA_TAIL, NULL, "'bad.name'", NULL },
    { "a tag with both NAME and IMPORT", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:NAME=\"both\",VALUE=\"1\",IMPORT=\"both\"\n" KC_MEDIA_TAIL, NULL,
      "exactly one of NAME, IMPORT and QUERYPARAM", NULL },
    { "an undefined variable in an EXT-X-MAP address", KC_BASE "index.m3u8",
      KC_MEDIA_HEAD "#EXT-X-MAP:URI=\"{$init}.mp4\"\n" KC_MEDIA_TAIL, NULL, "'init'", NULL },
    { "an undefined variable in an EXT-X-MEDIA address", KC_BASE "master.m3u8",
      "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"x\",URI=\"{$audio}.m3u8\"\n"
      "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nmedia.m3u8\n", KC_MEDIA_HEAD KC_MEDIA_TAIL, "'audio'", NULL },
    { "an undefined variable in an EXT-X-STREAM-INF attribute", KC_BASE "master.m3u8",
      "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"{$group}\"\nmedia.m3u8\n",
      KC_MEDIA_HEAD KC_MEDIA_TAIL, "'group'", NULL },
    { "an IMPORT of a name the master does not define", KC_BASE "master.m3u8",
      "#EXTM3U\n#EXT-X-DEFINE:NAME=\"other\",VALUE=\"1\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n",
      KC_MEDIA_HEAD "#EXT-X-DEFINE:IMPORT=\"token\"\n" KC_MEDIA_TAIL, "'token'",
      "the master playlist does not define it" },
};

static void case_a_playlist_the_specification_refuses_fails_the_open(const failing_playlist *f)
{
    unsigned char *wav = make_wav();
    resource resources[] = {
        { KC_BASE "media.m3u8", (const unsigned char *)(f->media ? f->media : ""),
          f->media ? (int64_t)strlen(f->media) : 0 },
        { KC_BASE "seg0.wav", wav, KC_WAV_BYTES },
    };
    network net = { .resources = resources, .count = f->media ? 2 : 1 };
    if (!f->media) {
        resources[0] = resources[1];
        net.count = 1;
    }
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    int rc;

    kc_case("%s fails the open with invalid data, and the error names it", f->why);
    kc_logged[0] = 0;
    av_log_set_callback(capture_errors);
    rc = open_playlist(&ctx, &top, f->top, f->url, NULL, &opener, NULL, NULL);
    av_log_set_callback(av_log_default_callback);
    kc_detail("rc=%d logged: %s", rc, kc_logged);
    KC_EQ_INT(rc, AVERROR_INVALIDDATA);
    KC_NULL(ctx);
    KC_CHECKF(strstr(kc_logged, f->named) != NULL, "the error does not name %s: %s", f->named, kc_logged);
    if (f->says) KC_CHECKF(strstr(kc_logged, f->says) != NULL, "the error does not say %s: %s", f->says, kc_logged);
    KC_CHECK(!was_asked(&net, KC_BASE "seg0.wav"));
    KC_EQ_INT(net.closed, net.opened);
    free(wav);
}

/* ---- A rendition the reads leave (#178) ---- */

#define KC_RENDITION_SEGMENTS 8
#define KC_PIECE_PCM 48000 /* a quarter second of the pattern's PCM */

/* The quarter second at [index] of a WAV that never says where it ends, as a live encoder writes
   one: the first piece carries the header, and every later one is the PCM that follows. */
static unsigned char *make_piece(int index, int *size)
{
    static const unsigned char header[44] = {
        'R', 'I', 'F', 'F', 0xFF, 0xFF, 0xFF, 0xFF, 'W', 'A', 'V', 'E',
        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 2, 0,
        0x80, 0xBB, 0, 0, 0x00, 0xEE, 0x02, 0, 4, 0, 16, 0,
        'd', 'a', 't', 'a', 0xFF, 0xFF, 0xFF, 0xFF,
    };
    int head = index == 0 ? 44 : 0;
    unsigned char *piece = malloc((size_t)(head + KC_PIECE_PCM));
    KC_NOT_NULL(piece);
    if (head) memcpy(piece, header, sizeof(header));
    for (int i = 0; i < KC_PIECE_PCM; i++) piece[head + i] = (unsigned char)((index * KC_PIECE_PCM + i) * 7 + 3);
    *size = head + KC_PIECE_PCM;
    return piece;
}

static char *make_rendition_playlist(const char *name)
{
    char *text = malloc(4096);
    int at;
    KC_NOT_NULL(text);
    at = snprintf(text, 4096,
                  "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n"
                  "#EXT-X-PLAYLIST-TYPE:VOD\n");
    for (int i = 0; i < KC_RENDITION_SEGMENTS; i++) at += snprintf(text + at, (size_t)(4096 - at), "#EXTINF:0.25,\n%s/%d.wav\n", name, i);
    snprintf(text + at, (size_t)(4096 - at), "#EXT-X-ENDLIST\n");
    return text;
}

/* How many of [net]'s requests from [from] on asked for a segment of the rendition [name]. */
static int asked_for(const network *net, int from, const char *name)
{
    char prefix[128];
    int count = 0;
    snprintf(prefix, sizeof(prefix), KC_BASE "%s/", name);
    for (int i = from; i < net->asked_count; i++) {
        if (strncmp(net->asked[i], prefix, strlen(prefix)) == 0) count++;
    }
    return count;
}

static void case_a_rendition_the_reads_leave_stops_downloading(void)
{
    static const char *const names[] = { "main", "en", "fr" };
    resource resources[3 + 3 * KC_RENDITION_SEGMENTS];
    unsigned char *pieces[3 * KC_RENDITION_SEGMENTS];
    char urls[3 + 3 * KC_RENDITION_SEGMENTS][128];
    char *playlists[3];
    int count = 0;
    for (int n = 0; n < 3; n++) {
        playlists[n] = make_rendition_playlist(names[n]);
        snprintf(urls[count], sizeof(urls[count]), KC_BASE "%s.m3u8", names[n]);
        resources[count] = (resource){ urls[count], (const unsigned char *)playlists[n], (int64_t)strlen(playlists[n]) };
        count++;
        for (int i = 0; i < KC_RENDITION_SEGMENTS; i++) {
            int size;
            pieces[n * KC_RENDITION_SEGMENTS + i] = make_piece(i, &size);
            snprintf(urls[count], sizeof(urls[count]), KC_BASE "%s/%d.wav", names[n], i);
            resources[count] = (resource){ urls[count], pieces[n * KC_RENDITION_SEGMENTS + i], size };
            count++;
        }
    }
    const char *master =
        "#EXTM3U\n"
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,URI=\"en.m3u8\"\n"
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",NAME=\"French\",LANGUAGE=\"fr\",URI=\"fr.m3u8\"\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=1600000,AUDIO=\"sound\"\n"
        "main.m3u8\n";
    network net = { .resources = resources, .count = count };
    kc_io_opener opener = opener_for(&net);
    cursor top;
    kc_fmt_ctx *ctx = NULL;
    AVPacket *packet = av_packet_alloc();
    int stream_of[3] = { -1, -1, -1 };
    int read_after = 0, switched_at, fr_after = 0;
    int64_t en_bytes_after = 0;

    kc_case("a rendition whose streams the reads leave stops downloading, and the one they take up starts");
    KC_NOT_NULL(packet);
    KC_EQ_INT(open_playlist(&ctx, &top, master, KC_BASE "master.m3u8", NULL, &opener, NULL, NULL), 0);
    KC_EQ_INT((int)ctx->nb_streams, 3);
    /* Each stream's playlist, by its language: the variant's own sound has none. */
    for (unsigned i = 0; i < ctx->nb_streams; i++) {
        const AVDictionaryEntry *language = av_dict_get(ctx->streams[i]->metadata, "language", NULL, 0);
        int which = language == NULL ? 0 : strcmp(language->value, "en") == 0 ? 1 : 2;
        stream_of[which] = (int)i;
    }
    kc_detail("main=%d en=%d fr=%d", stream_of[0], stream_of[1], stream_of[2]);
    KC_CHECK(stream_of[0] >= 0 && stream_of[1] >= 0 && stream_of[2] >= 0);
    /* The picture's stream and the English sound are read, as a player reads the sound heard. */
    ctx->streams[stream_of[2]]->discard = AVDISCARD_ALL;
    for (int i = 0; i < 40 && av_read_frame(ctx, packet) >= 0; i++) av_packet_unref(packet);
    KC_CHECKF(asked_for(&net, 0, "en") >= 2, "English was read before the switch: %d", asked_for(&net, 0, "en"));
    /* The listener switches to French. */
    ctx->streams[stream_of[1]]->discard = AVDISCARD_ALL;
    ctx->streams[stream_of[2]]->discard = AVDISCARD_DEFAULT;
    switched_at = net.asked_count;
    while (av_read_frame(ctx, packet) >= 0) {
        if (packet->stream_index == stream_of[1]) en_bytes_after += packet->size;
        if (packet->stream_index == stream_of[2]) fr_after++;
        read_after++;
        av_packet_unref(packet);
    }
    kc_detail("after the switch: en segments asked=%d, fr segments asked=%d, en bytes=%lld, fr packets=%d of %d",
              asked_for(&net, switched_at, "en"), asked_for(&net, switched_at, "fr"), (long long)en_bytes_after,
              fr_after, read_after);
    /* No segment of the sound left behind is asked for again, because FFmpeg checks before each
       segment that some stream of its playlist is read. What was already downloaded still comes
       out, and nothing more. */
    KC_EQ_INT(asked_for(&net, switched_at, "en"), 0);
    KC_CHECKF(asked_for(&net, switched_at, "fr") >= 2, "French was not taken up: %d", asked_for(&net, switched_at, "fr"));
    KC_CHECK(fr_after > 0);
    av_packet_free(&packet);
    ffkmp_fmt_close_input_io(&ctx);
    KC_EQ_INT(net.closed, net.opened);
    for (int n = 0; n < 3; n++) free(playlists[n]);
    for (int i = 0; i < 3 * KC_RENDITION_SEGMENTS; i++) free(pieces[i]);
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
    case_a_redirected_variant_resolves_against_its_location(1);
    case_a_redirected_variant_resolves_against_its_location(0);
    case_the_input_location_rebases_its_variants(KC_CDN "master.m3u8");
    case_the_input_location_rebases_its_variants("");
    case_the_input_location_rebases_its_variants(NULL);
    case_a_long_location_takes_a_second_call();
    case_a_failed_location_fails_that_address();
    case_a_rendition_the_reads_leave_stops_downloading();

    if (!linked_tree_carries(KC_VARIABLES_PATCH)) {
        kc_note("the linked FFmpeg's tree lacks %s, so the variable cases did not run", KC_VARIABLES_PATCH);
        return kc_suite_end();
    }
    case_playlist_variables_reach_every_address();
    case_a_value_is_substituted_once_and_names_keep_their_case();
    for (size_t i = 0; i < sizeof(kc_failing) / sizeof(kc_failing[0]); i++)
        case_a_playlist_the_specification_refuses_fails_the_open(&kc_failing[i]);

    return kc_suite_end();
}
