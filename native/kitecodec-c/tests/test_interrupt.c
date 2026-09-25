/* The caller-owned interrupt cell, kc_interrupt.
 *
 * A caller creates the cell before an open so another thread can stop the open while it waits.
 * Both opens poll it instead of a cell of their own, the context keeps polling it, and no close
 * frees it. The cases below pin each half of that: a raised cell refuses an open before anything
 * is read, an interrupt through the context raises the caller's cell, and closing a context never
 * frees the borrowed cell, which the asan variant would report as a double free when the test
 * then frees it itself.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <libavformat/avformat.h>
#include <libavutil/error.h>

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
    fclose(file);
}

static void remove_wav(void) { remove(wav_path); }

/* The custom-io source: serves the WAV file's bytes from memory. */
typedef struct {
    unsigned char *bytes;
    int64_t size;
    int64_t position;
} memory_source;

static int memory_read(void *opaque, unsigned char *buf, int len)
{
    memory_source *m = (memory_source *)opaque;
    int64_t left = m->size - m->position;
    int n = left < len ? (int)left : len;
    if (n <= 0) return KC_IO_EOF;
    memcpy(buf, m->bytes + m->position, (size_t)n);
    m->position += n;
    return n;
}

static int64_t memory_seek(void *opaque, int64_t offset, int whence)
{
    memory_source *m = (memory_source *)opaque;
    int64_t target = whence == SEEK_SET ? offset : whence == SEEK_CUR ? m->position + offset : m->size + offset;
    if (target < 0 || target > m->size) return KC_IO_ERR;
    m->position = target;
    return target;
}

static void load_wav(memory_source *m)
{
    FILE *file = fopen(wav_path, "rb");
    KC_CHECKF(file != NULL, "cannot read %s", wav_path);
    m->size = 44 + 4096;
    m->bytes = malloc((size_t)m->size);
    KC_NOT_NULL(m->bytes);
    KC_CHECK(fread(m->bytes, 1, (size_t)m->size, file) == (size_t)m->size);
    fclose(file);
    m->position = 0;
}

static void case_cell_lifecycle(void)
{
    kc_interrupt *cell;
    kc_interrupt *none = NULL;

    kc_case("a new cell exists, raising NULL is a no-op, and free clears the pointer");
    cell = ffkmp_interrupt_new();
    KC_NOT_NULL(cell);
    ffkmp_interrupt_raise(NULL);
    ffkmp_interrupt_raise(cell);
    ffkmp_interrupt_free(&cell);
    KC_NULL(cell);
    ffkmp_interrupt_free(&none);
    ffkmp_interrupt_free(NULL);
}

static void case_raised_cell_refuses_the_path_open(void)
{
    kc_interrupt *cell = ffkmp_interrupt_new();
    kc_fmt_ctx *ctx = (kc_fmt_ctx *)0x1;
    kc_dict *unused = (kc_dict *)0x1;

    kc_case("a raised cell refuses the path open with AVERROR_EXIT and hands nothing back");
    KC_NOT_NULL(cell);
    ffkmp_interrupt_raise(cell);
    KC_EQ_INT(ffkmp_fmt_open_input2(&ctx, wav_path, NULL, NULL, 0, &unused, cell), AVERROR_EXIT);
    KC_NULL(ctx);
    KC_NULL(unused);
    ffkmp_interrupt_free(&cell);
}

static void case_interrupting_the_context_raises_the_borrowed_cell(void)
{
    kc_interrupt *cell = ffkmp_interrupt_new();
    kc_fmt_ctx *ctx = NULL;
    kc_fmt_ctx *second = NULL;

    kc_case("the context polls the borrowed cell, and closing it leaves the cell to its owner");
    KC_NOT_NULL(cell);
    KC_EQ_INT(ffkmp_fmt_open_input2(&ctx, wav_path, NULL, NULL, 0, NULL, cell), 0);
    KC_NOT_NULL(ctx);
    ffkmp_fmt_interrupt(ctx);
    /* The interrupt reached the caller's cell: a second open over it is refused. */
    KC_EQ_INT(ffkmp_fmt_open_input2(&second, wav_path, NULL, NULL, 0, NULL, cell), AVERROR_EXIT);
    KC_NULL(second);
    KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, 0), AVERROR_EXIT);
    ffkmp_fmt_close_input(&ctx);
    KC_NULL(ctx);
    /* A close that freed the borrowed cell makes this a double free under asan. */
    ffkmp_interrupt_free(&cell);
}

static void case_the_custom_io_open_polls_the_borrowed_cell(void)
{
    memory_source source;
    kc_interrupt *cell = ffkmp_interrupt_new();
    kc_fmt_ctx *ctx = NULL;

    kc_case("the custom-io open refuses a raised cell and polls an unraised one");
    KC_NOT_NULL(cell);
    load_wav(&source);
    ffkmp_interrupt_raise(cell);
    KC_EQ_INT(ffkmp_fmt_open_input_io(&ctx, &source, memory_read, memory_seek, source.size,
                                      NULL, NULL, 0, NULL, cell), AVERROR_EXIT);
    KC_NULL(ctx);
    ffkmp_interrupt_free(&cell);

    cell = ffkmp_interrupt_new();
    KC_NOT_NULL(cell);
    source.position = 0;
    KC_EQ_INT(ffkmp_fmt_open_input_io(&ctx, &source, memory_read, memory_seek, source.size,
                                      NULL, NULL, 0, NULL, cell), 0);
    KC_NOT_NULL(ctx);
    ffkmp_interrupt_raise(cell);
    KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, 0), AVERROR_EXIT);
    ffkmp_fmt_close_input_io(&ctx);
    KC_NULL(ctx);
    ffkmp_interrupt_free(&cell);
    free(source.bytes);
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_interrupt");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    snprintf(wav_path, sizeof(wav_path), "%s%skc_interrupt_%ld.wav", tmp,
             tmp[strlen(tmp) - 1] == '/' ? "" : "/", (long)getpid());
    write_silent_wav(wav_path);
    atexit(remove_wav);

    case_cell_lifecycle();
    case_raised_cell_refuses_the_path_open();
    case_interrupting_the_context_raises_the_borrowed_cell();
    case_the_custom_io_open_polls_the_borrowed_cell();

    return kc_suite_end();
}
