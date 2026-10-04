/* ffkmp_log_set_sink: where FFmpeg's own log lines go.
 *
 * FFmpeg's default callback prints every line to the process's error stream. The helper replaces it
 * with a forwarder. With no sink nothing is printed at all, and with a sink each line at the sink's
 * level or more severe reaches it once, with the name of what logged and without its newline.
 *
 * The first case captures file descriptor 2 through a pipe, and proves the capture works by letting
 * FFmpeg's own callback print one line into it before the forwarder is installed.
 *
 * The later cases cover kc_log_capture (#170): the error lines one thread logs while one call runs,
 * kept whether or not a sink is installed, so a failed open can say why it failed. */

#include "harness.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kitecodec_helpers.h"

#include <libavutil/error.h>
#include <libavutil/log.h>

static int kc_lines;
static int kc_last_level;
static char kc_last_component[64];
static char kc_last_message[256];

static void kc_record(int level, const char *component, const char *message)
{
    kc_lines++;
    kc_last_level = level;
    snprintf(kc_last_component, sizeof kc_last_component, "%s", component);
    snprintf(kc_last_message, sizeof kc_last_message, "%s", message);
}

/* Something for FFmpeg to name, the way it names a decoder or a demuxer. */
static const AVClass kc_test_class = {
    .class_name = "kc_test",
    .item_name = av_default_item_name,
    .version = LIBAVUTIL_VERSION_INT,
};
static const AVClass *kc_test_object = &kc_test_class;

/* What FFmpeg printed to descriptor 2 while `emit` ran, as a byte count. */
static ssize_t kc_printed_during(void (*emit)(void))
{
    int pipe_ends[2];
    int saved;
    char buffer[512];
    ssize_t total = 0;
    ssize_t n;

    KC_EQ_INT(pipe(pipe_ends), 0);
    fflush(stderr);
    saved = dup(2);
    KC_CHECK(saved >= 0);
    KC_CHECK(dup2(pipe_ends[1], 2) >= 0);
    emit();
    fflush(stderr);
    KC_CHECK(dup2(saved, 2) >= 0);
    close(saved);
    close(pipe_ends[1]);
    while ((n = read(pipe_ends[0], buffer, sizeof buffer)) > 0) total += n;
    close(pipe_ends[0]);
    return total;
}

static void kc_emit_error(void)
{
    av_log(NULL, AV_LOG_ERROR, "an error line for the capture\n");
}

/* Logs one error line from a thread of its own, for the case that proves a capture is per thread. */
static void *kc_log_elsewhere(void *unused)
{
    (void)unused;
    av_log(&kc_test_object, AV_LOG_ERROR, "from another thread\n");
    return NULL;
}

/* A file holding nothing but an MP4 ftyp box, so the MP4 reader takes it and finds no moov. */
static int kc_write_cut_mp4(char *path, size_t size)
{
    static const unsigned char ftyp[] = {
        0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0x00, 0x00, 0x02, 0x00, 'i', 's', 'o', 'm', 'i', 's', 'o', '2',
    };
    int fd;
    snprintf(path, size, "/tmp/kc_log_cut_XXXXXX");
    fd = mkstemp(path);
    if (fd < 0) return -1;
    if (write(fd, ftyp, sizeof ftyp) != (ssize_t)sizeof ftyp) {
        close(fd);
        return -1;
    }
    return close(fd);
}

int main(void)
{
    ssize_t printed;
    kc_log_capture *outer;
    kc_log_capture *inner;
    kc_log_capture *lines;
    pthread_t thread;
    char path[64];
    kc_fmt_ctx *ctx = NULL;
    int rc;
    int i;
    int found;

    kc_suite_begin("log");

    kc_case("with no sink FFmpeg prints nothing, where its own callback prints the line");
    av_log_set_level(AV_LOG_INFO);
    printed = kc_printed_during(kc_emit_error);
    kc_detail("default callback printed %zd bytes", printed);
    KC_CHECKF(printed > 0, "the capture saw nothing from FFmpeg's own callback, so it proves nothing");
    ffkmp_log_set_sink(NULL, AV_LOG_TRACE);
    printed = kc_printed_during(kc_emit_error);
    kc_detail("forwarder printed %zd bytes", printed);
    KC_EQ_INT((int)printed, 0);
    av_log_set_level(AV_LOG_QUIET);

    kc_case("a warning sink hears warnings and errors with their component, and no info");
    ffkmp_log_set_sink(kc_record, AV_LOG_WARNING);
    av_log(&kc_test_object, AV_LOG_WARNING, "careful: %d\n", 7);
    KC_EQ_INT(kc_lines, 1);
    KC_EQ_INT(kc_last_level, AV_LOG_WARNING);
    KC_EQ_STR(kc_last_component, "kc_test");
    KC_EQ_STR(kc_last_message, "careful: 7");
    av_log(&kc_test_object, AV_LOG_INFO, "chatter\n");
    KC_EQ_INT(kc_lines, 1);
    av_log(NULL, AV_LOG_ERROR, "no object\n");
    KC_EQ_INT(kc_lines, 2);
    KC_EQ_STR(kc_last_component, "");
    KC_EQ_STR(kc_last_message, "no object");

    kc_case("a colour hint in the level's high byte does not change the level");
    av_log(&kc_test_object, AV_LOG_WARNING | AV_LOG_C(134), "tinted\n");
    KC_EQ_INT(kc_lines, 3);
    KC_EQ_INT(kc_last_level, AV_LOG_WARNING);

    kc_case("an empty line reaches no sink");
    av_log(&kc_test_object, AV_LOG_ERROR, "\n");
    KC_EQ_INT(kc_lines, 3);

    kc_case("a NULL sink stops delivery at every level");
    ffkmp_log_set_sink(NULL, AV_LOG_TRACE);
    av_log(&kc_test_object, AV_LOG_PANIC, "gone\n");
    KC_EQ_INT(kc_lines, 3);

    kc_case("with no sink a capture keeps the error lines its thread logs and nothing milder");
    ffkmp_log_set_sink(NULL, AV_LOG_QUIET);
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    av_log(&kc_test_object, AV_LOG_ERROR, "first %d\n", 1);
    av_log(&kc_test_object, AV_LOG_WARNING, "only a warning\n");
    av_log(NULL, AV_LOG_FATAL | AV_LOG_C(134), "second\n");
    av_log(&kc_test_object, AV_LOG_ERROR, "\n");
    lines = ffkmp_log_capture_end();
    KC_CHECK(lines != NULL);
    KC_EQ_INT(ffkmp_log_capture_count(lines), 2);
    KC_EQ_INT(ffkmp_log_capture_level(lines, 0), AV_LOG_ERROR);
    KC_EQ_STR(ffkmp_log_capture_component(lines, 0), "kc_test");
    KC_EQ_STR(ffkmp_log_capture_message(lines, 0), "first 1");
    KC_EQ_INT(ffkmp_log_capture_level(lines, 1), AV_LOG_FATAL);
    KC_EQ_STR(ffkmp_log_capture_component(lines, 1), "");
    KC_EQ_STR(ffkmp_log_capture_message(lines, 1), "second");
    KC_EQ_INT(ffkmp_log_capture_level(lines, 2), -1);
    KC_CHECK(ffkmp_log_capture_message(lines, -1) == NULL);
    KC_CHECK(ffkmp_log_capture_component(lines, 2) == NULL);
    ffkmp_log_capture_free(&lines);
    KC_CHECK(lines == NULL);
    av_log(&kc_test_object, AV_LOG_ERROR, "after the capture\n");
    KC_CHECK(ffkmp_log_capture_end() == NULL);

    kc_case("a capture keeps the first eight lines");
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    for (i = 0; i < 10; i++) av_log(&kc_test_object, AV_LOG_ERROR, "line %d\n", i);
    lines = ffkmp_log_capture_end();
    KC_EQ_INT(ffkmp_log_capture_count(lines), KC_LOG_CAPTURE_LINES);
    KC_EQ_STR(ffkmp_log_capture_message(lines, 0), "line 0");
    KC_EQ_STR(ffkmp_log_capture_message(lines, KC_LOG_CAPTURE_LINES - 1), "line 7");
    ffkmp_log_capture_free(&lines);

    kc_case("a nested capture keeps its own lines and the outer one keeps them too");
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    av_log(&kc_test_object, AV_LOG_ERROR, "outer before\n");
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    av_log(&kc_test_object, AV_LOG_ERROR, "inner\n");
    inner = ffkmp_log_capture_end();
    av_log(&kc_test_object, AV_LOG_ERROR, "outer after\n");
    outer = ffkmp_log_capture_end();
    KC_EQ_INT(ffkmp_log_capture_count(inner), 1);
    KC_EQ_STR(ffkmp_log_capture_message(inner, 0), "inner");
    KC_EQ_INT(ffkmp_log_capture_count(outer), 3);
    KC_EQ_STR(ffkmp_log_capture_message(outer, 0), "outer before");
    KC_EQ_STR(ffkmp_log_capture_message(outer, 1), "inner");
    KC_EQ_STR(ffkmp_log_capture_message(outer, 2), "outer after");
    ffkmp_log_capture_free(&inner);
    ffkmp_log_capture_free(&outer);
    KC_CHECK(ffkmp_log_capture_end() == NULL);

    kc_case("a line another thread logs does not land in this thread's capture");
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    KC_EQ_INT(pthread_create(&thread, NULL, kc_log_elsewhere, NULL), 0);
    KC_EQ_INT(pthread_join(thread, NULL), 0);
    av_log(&kc_test_object, AV_LOG_ERROR, "from this thread\n");
    lines = ffkmp_log_capture_end();
    KC_EQ_INT(ffkmp_log_capture_count(lines), 1);
    KC_EQ_STR(ffkmp_log_capture_message(lines, 0), "from this thread");
    ffkmp_log_capture_free(&lines);

    kc_case("a sink still hears each line a capture keeps");
    kc_lines = 0;
    ffkmp_log_set_sink(kc_record, AV_LOG_WARNING);
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    av_log(&kc_test_object, AV_LOG_WARNING, "heard, not kept\n");
    av_log(&kc_test_object, AV_LOG_ERROR, "heard and kept\n");
    lines = ffkmp_log_capture_end();
    KC_EQ_INT(kc_lines, 2);
    KC_EQ_STR(kc_last_message, "heard and kept");
    KC_EQ_INT(ffkmp_log_capture_count(lines), 1);
    KC_EQ_STR(ffkmp_log_capture_message(lines, 0), "heard and kept");
    ffkmp_log_capture_free(&lines);
    ffkmp_log_set_sink(NULL, AV_LOG_QUIET);

    kc_case("NULL is accepted everywhere a capture is read or freed");
    KC_EQ_INT(ffkmp_log_capture_count(NULL), 0);
    KC_EQ_INT(ffkmp_log_capture_level(NULL, 0), -1);
    KC_CHECK(ffkmp_log_capture_component(NULL, 0) == NULL);
    KC_CHECK(ffkmp_log_capture_message(NULL, 0) == NULL);
    ffkmp_log_capture_free(NULL);
    lines = NULL;
    ffkmp_log_capture_free(&lines);

    kc_case("an MP4 cut before its moov fails its open, and the capture holds why");
    KC_EQ_INT(kc_write_cut_mp4(path, sizeof path), 0);
    KC_EQ_INT(ffkmp_log_capture_begin(), 0);
    rc = ffkmp_fmt_open_input(&ctx, path);
    lines = ffkmp_log_capture_end();
    unlink(path);
    kc_detail("open returned %d with %d lines", rc, ffkmp_log_capture_count(lines));
    KC_EQ_INT(rc, AVERROR_INVALIDDATA);
    KC_CHECK(ctx == NULL);
    found = 0;
    for (i = 0; i < ffkmp_log_capture_count(lines); i++) {
        kc_detail("line %d: [%s] %s", i, ffkmp_log_capture_component(lines, i), ffkmp_log_capture_message(lines, i));
        if (strcmp(ffkmp_log_capture_message(lines, i), "moov atom not found") == 0 &&
            strncmp(ffkmp_log_capture_component(lines, i), "mov,mp4", 7) == 0) found = 1;
    }
    KC_CHECKF(found, "no line said 'moov atom not found' from the MP4 reader");
    ffkmp_log_capture_free(&lines);

    return kc_suite_end();
}
