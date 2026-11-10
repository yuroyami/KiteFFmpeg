/* Fuzz target: an HLS playlist whose segments and keys come through the nested opener (#229).
 *
 * The input is a playlist and the files it may name, cut apart by the eight bytes KC_PART below:
 *
 *   <playlist> KC_PART <file 0> KC_PART <file 1> ...
 *
 * The playlist is opened through ffkmp_fmt_open_input_io2 as `https://kite.test/index.m3u8`, with
 * an opener that serves `https://kite.test/f0` ... `f7` from the parts after it, and the last part
 * also as `https://kite.test/key`, so a playlist that names `#EXT-X-KEY:METHOD=AES-128,URI="key"`
 * gets its key from the input. Everything else the opener refuses. That reaches what no other
 * target does: KiteFFmpeg's own AES-128 reader (the key and IV parsing, the block reads and the
 * padding strip), the nested open and close paths, and the redirect location, because `f0` answers
 * a location of its own. The opener is the door a Kotlin byte source's openRelated walks through.
 *
 * After the open the target reads packets to the end, at most KC_FUZZ_MAX_PACKETS, and every byte
 * of each, then closes. The opener counts its opens and closes, and a close that does not release
 * every source it opened is a finding: the Kotlin side frees a reader only when FFmpeg closes it.
 *
 * It needs a tree with the trust_io_open patch, which the fuzz job's prebuilt tree carries. With
 * an FFmpeg that lacks it, the open answers ENOSYS and the target tests nothing, so it says so
 * once rather than passing quietly.
 */

#include "kc_fuzz.h"

#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define KC_FUZZ_MAX_PACKETS 4096
#define KC_FUZZ_MAX_FILES 8
#define KC_BASE "https://kite.test/"

/* The eight bytes that cut the input into its parts. Rare in media, and plain in a seed file. */
static const uint8_t KC_PART[8] = { '\n', '#', '#', 'K', 'C', 'F', '#', '\n' };

typedef struct {
    const uint8_t *data;
    int64_t size;
} part;

typedef struct {
    const uint8_t *bytes;
    int64_t size;
    int64_t position;
} cursor;

typedef struct {
    part files[KC_FUZZ_MAX_FILES];
    int count;
    int opened;
    int closed;
} network;

/* Served sources: a cursor first, so the shared read and seek take one, then its location. */
typedef struct {
    cursor at;
    const char *location;
} served;

static int cursor_read(void *opaque, unsigned char *buf, int len) {
    cursor *c = (cursor *)opaque;
    int64_t left = c->size - c->position;
    int n = left < len ? (int)left : len;
    if (n <= 0) return KC_IO_EOF;
    memcpy(buf, c->bytes + c->position, (size_t)n);
    c->position += n;
    return n;
}

static int64_t cursor_seek(void *opaque, int64_t offset, int whence) {
    cursor *c = (cursor *)opaque;
    if (whence == AVSEEK_SIZE) return c->size;
    int64_t target = whence == SEEK_SET ? offset : whence == SEEK_CUR ? c->position + offset : c->size + offset;
    if (target < 0 || target > c->size) return KC_IO_ERR;
    c->position = target;
    return target;
}

/* The part a nested URL names, or NULL: `f<digit>` for a file, `key` for the last part. */
static const part *named(const network *net, const char *url) {
    size_t base = strlen(KC_BASE);
    if (strncmp(url, KC_BASE, base) != 0) return NULL;
    const char *name = url + base;
    if (strcmp(name, "key") == 0) return net->count > 0 ? &net->files[net->count - 1] : NULL;
    if (name[0] == 'f' && name[1] >= '0' && name[1] <= '9' && name[2] == '\0') {
        int index = name[1] - '0';
        return index < net->count ? &net->files[index] : NULL;
    }
    return NULL;
}

static int network_open(void *opaque, const char *url, void **source, int64_t *size, int *seekable) {
    network *net = (network *)opaque;
    const part *file = named(net, url);
    if (file == NULL) return KC_IO_REFUSED;
    served *s = (served *)calloc(1, sizeof(served));
    if (s == NULL) return KC_IO_ERR;
    s->at.bytes = file->data;
    s->at.size = file->size;
    /* The first file answers as a redirect would, so the location path runs. */
    s->location = file == &net->files[0] ? KC_BASE "moved/f0" : NULL;
    *source = s;
    *size = file->size;
    *seekable = 1;
    net->opened++;
    return 0;
}

static int network_location(void *opaque, void *source, char *buf, int cap) {
    (void)opaque;
    const served *s = (const served *)source;
    if (s->location == NULL) return 0;
    int len = (int)strlen(s->location);
    if (len < cap) memcpy(buf, s->location, (size_t)len + 1);
    return len;
}

static void network_close(void *opaque, void *source) {
    network *net = (network *)opaque;
    net->closed++;
    free(source);
}

/* The offset of the next KC_PART at or after `from`, or `size` when there is none. */
static size_t next_part(const uint8_t *data, size_t size, size_t from) {
    for (size_t i = from; i + sizeof(KC_PART) <= size; i++) {
        if (memcmp(data + i, KC_PART, sizeof(KC_PART)) == 0) return i;
    }
    return size;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();
    if (!ffkmp_fmt_nested_io_available()) {
        static int said = 0;
        if (!said) {
            fprintf(stderr, "fuzz_hls_opener: the linked FFmpeg lacks trust_io_open, so this target tests nothing\n");
            said = 1;
        }
        return 0;
    }

    size_t end = next_part(data, size, 0);
    cursor top = { data, (int64_t)end, 0 };
    network net;
    memset(&net, 0, sizeof(net));
    size_t at = end;
    while (at < size && net.count < KC_FUZZ_MAX_FILES) {
        size_t from = at + sizeof(KC_PART);
        size_t to = next_part(data, size, from);
        net.files[net.count].data = data + from;
        net.files[net.count].size = (int64_t)(to - from);
        net.count++;
        at = to;
    }

    kc_io_opener opener = {
        .opaque = &net,
        .open_fn = network_open,
        .read_fn = cursor_read,
        .seek_fn = cursor_seek,
        .close_fn = network_close,
        .location_fn = network_location,
    };
    /* Only the nested opener may serve a URL: no FFmpeg protocol reaches a file or the network. */
    static const char *const keys[] = { "protocol_whitelist", "extension_picky" };
    static const char *const values[] = { "none", "0" };
    kc_fmt_ctx *ctx = NULL;
    kc_dict *unused = NULL;
    int rc = ffkmp_fmt_open_input_io2(&ctx, &top, cursor_read, cursor_seek, NULL, top.size,
                                      KC_BASE "index.m3u8", NULL, "application/vnd.apple.mpegurl",
                                      &opener, keys, values, 2, &unused, NULL);
    ffkmp_dict_free(&unused);
    if (rc < 0) {
        if (ctx != NULL) abort();
        if (net.closed != net.opened) abort();
        return 0;
    }

    AVPacket *packet = av_packet_alloc();
    if (packet != NULL) {
        size_t total = 0;
        int packets = 0;
        for (; packets < KC_FUZZ_MAX_PACKETS && av_read_frame((AVFormatContext *)ctx, packet) >= 0; packets++) {
            for (int i = 0; i < packet->size; i++) total += packet->data[i];
            av_packet_unref(packet);
        }
        av_packet_free(&packet);
        /* KC_FUZZ_REPORT=1 says how far an input got, for checking that a seed reaches the reads. */
        if (getenv("KC_FUZZ_REPORT") != NULL) {
            fprintf(stderr, "fuzz_hls_opener: %d packets, %d sources opened, byte sum %zu\n", packets, net.opened, total);
        }
    }
    ffkmp_fmt_close_input_io(&ctx);
    if (ctx != NULL) abort();
    /* Every source FFmpeg opened through the opener is closed by the time the context is. */
    if (net.closed != net.opened) abort();
    return 0;
}
