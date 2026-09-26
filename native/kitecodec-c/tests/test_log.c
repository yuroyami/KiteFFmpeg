/* ffkmp_log_set_sink: where FFmpeg's own log lines go.
 *
 * FFmpeg's default callback prints every line to the process's error stream. The helper replaces it
 * with a forwarder. With no sink nothing is printed at all, and with a sink each line at the sink's
 * level or more severe reaches it once, with the name of what logged and without its newline.
 *
 * The first case captures file descriptor 2 through a pipe, and proves the capture works by letting
 * FFmpeg's own callback print one line into it before the forwarder is installed. */

#include "harness.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "kitecodec_helpers.h"

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

int main(void)
{
    ssize_t printed;

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

    return kc_suite_end();
}
