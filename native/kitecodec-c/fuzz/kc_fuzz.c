/* The shared plumbing declared in kc_fuzz.h. Compiled into both drivers.
 *
 * Deliberately tiny. Everything here runs on every single input, so a bug in this file would be
 * charged to whichever helper the target happened to call next, which is the one failure mode a
 * fuzz harness must not have.
 */

#include "kc_fuzz.h"

#include <libavutil/log.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void kc_fuzz_quiet(void) {
    static int done = 0;
    /* Not thread safe and does not need to be: both drivers are single threaded, and libFuzzer
     * only forks workers as separate processes. A word-sized flag is the whole guard. */
    if (done) return;
    done = 1;
    if (getenv("KC_FFMPEG_LOG") == NULL) av_log_set_level(AV_LOG_QUIET);
}

char *kc_fuzz_dup(const uint8_t *data, size_t size) {
    char *copy = (char *)malloc(size + 1);
    if (copy == NULL) return NULL;
    if (size > 0) memcpy(copy, data, size);
    copy[size] = '\0';
    return copy;
}

int kc_fuzz_split(const uint8_t *data, size_t size, char **out_key, char **out_value) {
    *out_key = NULL;
    *out_value = NULL;

    const uint8_t *newline = (size > 0) ? (const uint8_t *)memchr(data, '\n', size) : NULL;
    size_t key_size = (newline != NULL) ? (size_t)(newline - data) : size;

    char *key = kc_fuzz_dup(data, key_size);
    if (key == NULL) return -1;

    char *value = NULL;
    if (newline != NULL) {
        /* Everything after the newline, which may be zero bytes: an input ending in a newline
         * means an empty value, which is a different case from no value at all. */
        size_t value_offset = key_size + 1;
        value = kc_fuzz_dup(data + value_offset, size - value_offset);
        if (value == NULL) {
            free(key);
            return -1;
        }
    }

    *out_key = key;
    *out_value = value;
    return 0;
}

void kc_fuzz_free(char *s) {
    free(s);
}

/* The read callback. At most `len` bytes, never zero: the contract allows more than zero bytes,
 * KC_IO_EOF or KC_IO_ERR, and the Kotlin callbacks answer a request they cannot fill with
 * KC_IO_ERR too. */
static int kc_fuzz_media_read(void *opaque, unsigned char *buf, int len) {
    kc_fuzz_media *media = (kc_fuzz_media *)opaque;
    if (len <= 0) return KC_IO_ERR;
    if (media->position < 0 || (uint64_t)media->position >= (uint64_t)media->size) return KC_IO_EOF;
    size_t left = media->size - (size_t)media->position;
    size_t count = ((size_t)len < left) ? (size_t)len : left;
    memcpy(buf, media->data + media->position, count);
    media->position += (int64_t)count;
    return (int)count;
}

/* The seek callback. Any position from zero up is accepted, past the end included, as a file
 * accepts it. The offset comes from the container, so a sum that would overflow is refused
 * rather than computed: computing it would be undefined behaviour in this harness, and the
 * sanitizer would charge it to the library. */
static int64_t kc_fuzz_media_seek(void *opaque, int64_t offset, int whence) {
    kc_fuzz_media *media = (kc_fuzz_media *)opaque;
    int64_t base;
    switch (whence) {
        case SEEK_SET: base = 0; break;
        case SEEK_CUR: base = media->position; break;
        case SEEK_END: base = (int64_t)media->size; break;
        default: return KC_IO_ERR;
    }
    if ((offset > 0 && base > INT64_MAX - offset) || (offset < 0 && base < INT64_MIN - offset)) {
        return KC_IO_ERR;
    }
    int64_t target = base + offset;
    if (target < 0) return KC_IO_ERR;
    media->position = target;
    return target;
}

int kc_fuzz_open_media(kc_fmt_ctx **out, kc_fuzz_media *media, int seekable) {
    static const char *const keys[1] = { "protocol_whitelist" };
    static const char *const values[1] = { "none" };
    kc_dict *unused = NULL;

    media->position = 0;
    int rc = ffkmp_fmt_open_input_io(out, media, kc_fuzz_media_read,
                                     seekable ? kc_fuzz_media_seek : NULL,
                                     seekable ? (int64_t)media->size : -1,
                                     keys, values, 1, &unused);
    if (rc < 0) {
        /* A failed open hands nothing over: no context and no dictionary. */
        if (*out != NULL || unused != NULL) abort();
        return rc;
    }
    if (*out == NULL) abort();
    if (unused != NULL) abort();
    return rc;
}
