/* Pause and play on an open input, ffkmp_fmt_read_pause and ffkmp_fmt_read_play (#136).
 *
 * The helpers answer 1 when the input paused or played, 0 when it has no notion of pausing, and a
 * negative AVERROR when it refused. A file is the common case of no notion at all: FFmpeg answers
 * ENOSYS, which must come back as 0 and leave the input reading exactly as before, so a caller can
 * pause every source without first asking what it is. The RTSP behaviour needs a server and is
 * covered by the Kotlin contract suite.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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
    KC_CHECK(fclose(file) == 0);
}

static void remove_wav(void) { remove(wav_path); }

static void case_null_is_refused(void)
{
    kc_case("a NULL context is refused with EINVAL by both helpers");
    KC_EQ_INT(ffkmp_fmt_read_pause(NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_fmt_read_play(NULL), AVERROR(EINVAL));
}

static void case_a_file_has_no_notion_of_pausing(void)
{
    kc_fmt_ctx *ctx = NULL;
    kc_packet *packet = ffkmp_packet_alloc();

    kc_case("a file answers 0 to both and reads on unchanged");
    KC_NOT_NULL(packet);
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, wav_path), 0);
    KC_NOT_NULL(ctx);
    KC_EQ_INT(ffkmp_fmt_read_pause(ctx), 0);
    KC_EQ_INT(ffkmp_fmt_read_pause(ctx), 0);
    KC_EQ_INT(ffkmp_fmt_read_play(ctx), 0);
    KC_EQ_INT(ffkmp_fmt_read_frame(ctx, packet), 0);
    ffkmp_packet_unref(packet);
    ffkmp_packet_free(packet);
    ffkmp_fmt_close_input(&ctx);
    KC_NULL(ctx);
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_pause");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    snprintf(wav_path, sizeof(wav_path), "%s%skc_pause_%ld.wav", tmp,
             tmp[strlen(tmp) - 1] == '/' ? "" : "/", (long)getpid());
    write_silent_wav(wav_path);
    atexit(remove_wav);

    case_null_is_refused();
    case_a_file_has_no_notion_of_pausing();

    return kc_suite_end();
}
