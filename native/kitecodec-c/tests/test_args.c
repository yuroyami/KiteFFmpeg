/* Argument-boundary cases for the exported helper layer.
 *
 * S1.a.7 adds one leading refusal to each of sixteen helpers that currently lets a required
 * NULL pointer reach FFmpeg or an immediate dereference. Each invalid call runs in a child so
 * the unguarded reproduction records its signal without killing the driver. Pass one row id as
 * argv[1] to reproduce a single vector; the gate invokes the binary without an id and runs all
 * thirty-one cases (sixteen S1.a.7 refusals, four S4.b arms, four S2.a arms, the controls).
 *
 * The final eight cases are load-bearing controls. Six prevent the new refusals from
 * rejecting positions whose existing contracts deliberately use NULL: the default audio filter,
 * graph EOF, mux flush, output-format inference, a context-retained codec and pathless output
 * allocation with an explicit format; the seventh pins selected-codec identity and its NULL rule.
 */

#include "harness.h"
#include "kitecodec_helpers.h"
#include "kitecodec_handles.h"

#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

typedef int (*invalid_call)(void);
typedef void (*control_call)(void);

typedef struct {
    const char *id;
    const char *label;
    invalid_call call;
} invalid_case;

typedef struct {
    const char *id;
    const char *label;
    control_call call;
} control_case;

static const char *signal_class(int signal_number)
{
    switch (signal_number) {
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGSEGV: return "SIGSEGV";
        case SIGTRAP: return "SIGTRAP";
        default:      return "other";
    }
}

static void expect_einval_in_child(const char *id, invalid_call call)
{
    int result_pipe[2];
    pid_t child;
    int status;
    int rc = 0;
    ssize_t received;

    if (pipe(result_pipe) != 0)
        KC_FAIL("%s: pipe failed with errno %d", id, errno);

    child = fork();
    if (child < 0) {
        int saved_errno = errno;
        close(result_pipe[0]);
        close(result_pipe[1]);
        KC_FAIL("%s: fork failed with errno %d", id, saved_errno);
    }

    if (child == 0) {
        ssize_t written;
        close(result_pipe[0]);
        rc = call();
        written = write(result_pipe[1], &rc, sizeof(rc));
        close(result_pipe[1]);
        _exit(written == (ssize_t)sizeof(rc) ? 0 : 120);
    }

    close(result_pipe[1]);
    do {
        received = read(result_pipe[0], &rc, sizeof(rc));
    } while (received < 0 && errno == EINTR);
    close(result_pipe[0]);

    do {
        status = 0;
    } while (waitpid(child, &status, 0) < 0 && errno == EINTR);

    if (WIFSIGNALED(status)) {
        int signal_number = WTERMSIG(status);
        KC_FAIL(
            "%s reached the unguarded call and terminated with signal %d (%s)",
            id,
            signal_number,
            signal_class(signal_number)
        );
    }
    KC_CHECKF(WIFEXITED(status), "%s child ended without an exit status", id);
    KC_EQ_INT(WEXITSTATUS(status), 0);
    KC_EQ_SIZE(received, sizeof(rc));
    KC_EQ_INT(rc, -EINVAL);
    kc_detail("rc=%d", rc);
}

static int invalid_frame_get_buffer(void)
{
    return ffkmp_frame_get_buffer(NULL, 0);
}

static int invalid_codecpar_from_context(void)
{
    return ffkmp_codecpar_from_context(NULL, NULL);
}

static int invalid_codecpar_copy_for_mux(void)
{
    return ffkmp_codecpar_copy_for_mux(NULL, NULL);
}

static int invalid_fmt_open_input(void)
{
    return ffkmp_fmt_open_input(NULL, "/definitely/not/a/kiteffmpeg-input");
}

static int invalid_fmt_find_stream_info(void)
{
    return ffkmp_fmt_find_stream_info(NULL);
}

static int invalid_fmt_open_input2(void)
{
    return ffkmp_fmt_open_input2(NULL, "/definitely/not/a/kiteffmpeg-input", NULL, NULL, 0, NULL);
}

static int invalid_fmt_open_input2_pairs(void)
{
    kc_fmt_ctx *out = NULL;
    /* n > 0 with NULL arrays must be refused before anything is allocated. */
    return ffkmp_fmt_open_input2(&out, "/definitely/not/a/kiteffmpeg-input", NULL, NULL, 2, NULL);
}

static int invalid_fmt_chapter_count(void)
{
    return ffkmp_fmt_chapter_count(NULL);
}

static int invalid_fmt_chapter_get(void)
{
    return ffkmp_fmt_chapter_get(NULL, 0, NULL, NULL, NULL);
}

static int invalid_fmt_read_frame(void)
{
    return ffkmp_fmt_read_frame(NULL, NULL);
}

static int invalid_codecctx_use_videotoolbox(void)
{
    return ffkmp_codecctx_use_videotoolbox(NULL);
}

static int invalid_frame_hw_download(void)
{
    return ffkmp_frame_hw_download(NULL, NULL);
}

static int invalid_frame_hw_download_software_src(void)
{
    /* A software frame must be refused, not copied: reaching the download on one means the
       caller's is-hardware bookkeeping is wrong, and copying would hide that. */
    kc_frame *src = ffkmp_frame_alloc();
    kc_frame *dst = ffkmp_frame_alloc();
    int rc;
    if (!src || !dst) {
        ffkmp_frame_free(src);
        ffkmp_frame_free(dst);
        return 0; /* allocation failure would read as a missing refusal; report it as such */
    }
    rc = ffkmp_frame_hw_download(src, dst);
    ffkmp_frame_free(src);
    ffkmp_frame_free(dst);
    return rc;
}

static int invalid_fmt_alloc_output2(void)
{
    return ffkmp_fmt_alloc_output2(NULL, "kiteffmpeg-args.null", "null");
}

static int invalid_fmt_write_frame(void)
{
    return ffkmp_fmt_write_frame(NULL, NULL);
}

static int invalid_codecctx_open(void)
{
    return ffkmp_codecctx_open(NULL, NULL);
}

static int invalid_codecctx_from_par(void)
{
    return ffkmp_codecctx_from_par(NULL, NULL);
}

static int invalid_graph_build_video(void)
{
    return ffkmp_graph_build_video(
        NULL, NULL, NULL, "null", 16, 16, ffkmp_pix_fmt_from_name("yuv420p"),
        1, 30, 30, 1, 1, 1
    );
}

static int invalid_graph_build_audio(void)
{
    return ffkmp_graph_build_audio(
        NULL, NULL, NULL, "anull", 48000, ffkmp_sample_fmt_from_name("fltp"), 2,
        1, 48000, -1, -1, 0
    );
}

static int invalid_graph_build_video_multi(void)
{
    return ffkmp_graph_build_video_multi(
        NULL, NULL, NULL, "[in0]null[out]", 1,
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
    );
}

static int invalid_graph_build_audio_multi(void)
{
    return ffkmp_graph_build_audio_multi(
        NULL, NULL, NULL, "[in0]anull[out]", 1,
        NULL, NULL, NULL, NULL, NULL, -1, -1, 0
    );
}

static int invalid_graph_send(void)
{
    return ffkmp_graph_send(NULL, NULL);
}

static int invalid_graph_receive(void)
{
    return ffkmp_graph_receive(NULL, NULL);
}

static void control_audio_description_null(void)
{
    kc_filter_graph *graph = NULL;
    kc_filter_ctx *source = NULL;
    kc_filter_ctx *sources[1] = { NULL };
    kc_filter_ctx *sink = NULL;
    const int sample_rates[1] = { 48000 };
    const int sample_fmts[1] = { ffkmp_sample_fmt_from_name("fltp") };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    kc_frame *pushed;
    kc_frame *pulled;
    int rc = ffkmp_graph_build_audio(
        &graph, &source, &sink, NULL, 48000, ffkmp_sample_fmt_from_name("fltp"), 2,
        1, 48000, -1, -1, 0
    );

    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(source);
    KC_NOT_NULL(sink);
    ffkmp_graph_free(&graph);
    KC_NULL(graph);

    sink = NULL;
    rc = ffkmp_graph_build_audio_multi(
        &graph, sources, &sink, NULL, 1,
        sample_rates, sample_fmts, channels, tb_nums, tb_dens,
        ffkmp_sample_fmt_from_name("s16"), 44100, 1
    );
    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(sources[0]);
    KC_NOT_NULL(sink);

    pushed = ffkmp_frame_alloc();
    KC_NOT_NULL(pushed);
    ffkmp_frame_set_format(pushed, ffkmp_sample_fmt_from_name("fltp"));
    ffkmp_frame_set_sample_rate(pushed, 48000);
    ffkmp_frame_set_nb_samples(pushed, 1024);
    ffkmp_frame_set_ch_layout_default(pushed, 2);
    KC_EQ_INT(ffkmp_frame_get_buffer(pushed, 0), 0);
    KC_EQ_INT(ffkmp_graph_send(sources[0], pushed), 0);
    ffkmp_frame_free(pushed);
    KC_EQ_INT(ffkmp_graph_send(sources[0], NULL), 0);

    pulled = ffkmp_frame_alloc();
    KC_NOT_NULL(pulled);
    KC_EQ_INT(ffkmp_graph_receive(sink, pulled), 0);
    KC_EQ_INT(ffkmp_frame_format(pulled), ffkmp_sample_fmt_from_name("s16"));
    KC_EQ_INT(ffkmp_frame_sample_rate(pulled), 44100);
    KC_EQ_INT(ffkmp_frame_channels(pulled), 1);
    ffkmp_frame_free(pulled);
    ffkmp_graph_free(&graph);
    KC_NULL(graph);
    kc_detail("rc=%d multi=s16/44100/1", rc);
}

static void control_graph_send_null_frame(void)
{
    kc_filter_graph *graph = NULL;
    kc_filter_ctx *source = NULL;
    kc_filter_ctx *sink = NULL;
    int rc = ffkmp_graph_build_audio(
        &graph, &source, &sink, "anull", 48000, ffkmp_sample_fmt_from_name("fltp"), 2,
        1, 48000, -1, -1, 0
    );

    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(source);
    rc = ffkmp_graph_send(source, NULL);
    KC_EQ_INT(rc, 0);
    ffkmp_graph_free(&graph);
    KC_NULL(graph);
    kc_detail("rc=%d", rc);
}

static void control_mux_packet_null(void)
{
    kc_fmt_ctx *context = NULL;
    const kc_codec *codec;
    kc_codec_ctx *codec_context;
    kc_stream *stream;
    int rc = ffkmp_fmt_alloc_output2(&context, "kiteffmpeg-args.null", "null");

    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(context);

    codec = ffkmp_find_encoder_by_name("pcm_s16le");
    KC_NOT_NULL(codec);
    codec_context = ffkmp_codecctx_alloc(codec);
    KC_NOT_NULL(codec_context);
    ffkmp_codecctx_set_audio(
        codec_context,
        48000,
        ffkmp_codec_first_sample_fmt(codec),
        2,
        48000 * 2 * 16
    );
    stream = ffkmp_fmt_new_stream(context, codec);
    KC_NOT_NULL(stream);
    KC_EQ_INT(
        ffkmp_codecpar_from_context(ffkmp_stream_codecpar(stream), codec_context),
        0
    );
    ffkmp_stream_set_time_base(stream, 1, 48000);
    ffkmp_codecctx_free(codec_context);

    KC_EQ_INT(ffkmp_fmt_write_header(context), 0);
    rc = ffkmp_fmt_write_frame(context, NULL);
    KC_EQ_INT(rc, 0);
    KC_EQ_INT(ffkmp_fmt_write_trailer(context), 0);
    ffkmp_fmt_free_output(&context);
    KC_NULL(context);
    kc_detail("rc=%d", rc);
}

static void control_output_format_null(void)
{
    kc_fmt_ctx *context = NULL;
    int rc = ffkmp_fmt_alloc_output2(&context, "kiteffmpeg-args.mp4", NULL);

    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(context);
    ffkmp_fmt_free_output(&context);
    KC_NULL(context);
    kc_detail("rc=%d", rc);
}

static void control_codecctx_open_null_codec(void)
{
    const kc_codec *codec = ffkmp_find_decoder_by_name("pcm_s16le");
    kc_codec_ctx *context;
    int rc;

    KC_NOT_NULL(codec);
    context = ffkmp_codecctx_alloc(codec);
    KC_NOT_NULL(context);
    ffkmp_codecctx_set_audio(
        context,
        48000,
        ffkmp_codec_first_sample_fmt(codec),
        2,
        48000 * 2 * 16
    );
    rc = ffkmp_codecctx_open(context, NULL);
    KC_EQ_INT(rc, 0);
    ffkmp_codecctx_free(context);
    kc_detail("rc=%d", rc);
}

static void control_fmt_alloc_output2_null_path(void)
{
    kc_fmt_ctx *context = NULL;
    int rc = ffkmp_fmt_alloc_output2(&context, NULL, "null");

    KC_EQ_INT(rc, 0);
    KC_NOT_NULL(context);
    ffkmp_fmt_free_output(&context);
    KC_NULL(context);
    kc_detail("rc=%d", rc);
}

static void control_codec_id(void)
{
    const kc_codec *codec = ffkmp_find_decoder_by_name("pcm_s16le");
    int id;

    KC_EQ_INT(ffkmp_codec_id(NULL), 0);
    KC_NOT_NULL(codec);
    id = ffkmp_codec_id(codec);
    KC_EQ_INT(id != 0, 1);
    KC_EQ_STR(ffkmp_codec_id_name(id), "pcm_s16le");
    kc_detail("id=%d name=%s", id, ffkmp_codec_id_name(id));
}

static const invalid_case invalid_cases[] = {
    { "invalid_frame_get_buffer", "ffkmp_frame_get_buffer refuses a NULL frame", invalid_frame_get_buffer },
    { "invalid_codecpar_from_context", "ffkmp_codecpar_from_context refuses NULL arguments", invalid_codecpar_from_context },
    { "invalid_codecpar_copy_for_mux", "ffkmp_codecpar_copy_for_mux refuses NULL arguments", invalid_codecpar_copy_for_mux },
    { "invalid_fmt_open_input", "ffkmp_fmt_open_input refuses a NULL output", invalid_fmt_open_input },
    { "invalid_fmt_find_stream_info", "ffkmp_fmt_find_stream_info refuses a NULL context", invalid_fmt_find_stream_info },
    { "invalid_fmt_open_input2", "ffkmp_fmt_open_input2 refuses a NULL output", invalid_fmt_open_input2 },
    { "invalid_fmt_open_input2_pairs", "ffkmp_fmt_open_input2 refuses pairs without arrays", invalid_fmt_open_input2_pairs },
    { "invalid_fmt_chapter_count", "ffkmp_fmt_chapter_count refuses a NULL context", invalid_fmt_chapter_count },
    { "invalid_fmt_chapter_get", "ffkmp_fmt_chapter_get refuses NULL arguments", invalid_fmt_chapter_get },
    { "invalid_fmt_read_frame", "ffkmp_fmt_read_frame refuses NULL arguments", invalid_fmt_read_frame },
    { "invalid_codecctx_use_videotoolbox", "ffkmp_codecctx_use_videotoolbox refuses a NULL context", invalid_codecctx_use_videotoolbox },
    { "invalid_frame_hw_download", "ffkmp_frame_hw_download refuses NULL frames", invalid_frame_hw_download },
    { "invalid_frame_hw_download_software_src", "ffkmp_frame_hw_download refuses a software source", invalid_frame_hw_download_software_src },
    { "invalid_fmt_alloc_output2", "ffkmp_fmt_alloc_output2 refuses a NULL output", invalid_fmt_alloc_output2 },
    { "invalid_fmt_write_frame", "ffkmp_fmt_write_frame refuses a NULL context", invalid_fmt_write_frame },
    { "invalid_codecctx_open", "ffkmp_codecctx_open refuses a NULL context", invalid_codecctx_open },
    { "invalid_codecctx_from_par", "ffkmp_codecctx_from_par refuses NULL arguments", invalid_codecctx_from_par },
    { "invalid_graph_build_video", "ffkmp_graph_build_video refuses NULL outputs", invalid_graph_build_video },
    { "invalid_graph_build_audio", "ffkmp_graph_build_audio refuses NULL outputs", invalid_graph_build_audio },
    { "invalid_graph_build_video_multi", "ffkmp_graph_build_video_multi refuses NULL required arrays", invalid_graph_build_video_multi },
    { "invalid_graph_build_audio_multi", "ffkmp_graph_build_audio_multi refuses NULL required arrays", invalid_graph_build_audio_multi },
    { "invalid_graph_send", "ffkmp_graph_send refuses a NULL source", invalid_graph_send },
    { "invalid_graph_receive", "ffkmp_graph_receive refuses NULL arguments", invalid_graph_receive },
};

static void control_codecctx_use_videotoolbox(void)
{
    /* On this Mac the attach must succeed against an allocated, unopened decoder context; a
       build without VideoToolbox would answer ENOSYS here, which is the typed refusal the
       Kotlin side forwards. The context is freed unopened: the attach's own contract is the
       thing under test, the full hardware decode is the player matrix's proof. */
    const kc_codec *codec = ffkmp_find_decoder_by_name("h264");
    kc_codec_ctx *context;
    int rc;

    KC_NOT_NULL(codec);
    context = ffkmp_codecctx_alloc(codec);
    KC_NOT_NULL(context);
    rc = ffkmp_codecctx_use_videotoolbox(context);
    KC_EQ_INT(rc, 0);
    /* A second attach must replace, not leak; ASan holds this arm to that word. */
    rc = ffkmp_codecctx_use_videotoolbox(context);
    KC_EQ_INT(rc, 0);
    ffkmp_codecctx_free(context);
    kc_detail("rc=%d", rc);
}

static const control_case control_cases[] = {
    { "control_audio_description_null", "audio graph accepts a NULL description as anull", control_audio_description_null },
    { "control_graph_send_null_frame", "graph send accepts a NULL frame as EOF", control_graph_send_null_frame },
    { "control_mux_packet_null", "mux write accepts a NULL packet as flush", control_mux_packet_null },
    { "control_output_format_null", "output allocation accepts a NULL format for inference", control_output_format_null },
    { "control_codecctx_open_null_codec", "codec open accepts NULL when the context remembers its codec", control_codecctx_open_null_codec },
    { "control_fmt_alloc_output2_null_path", "output allocation accepts a NULL path with an explicit format", control_fmt_alloc_output2_null_path },
    { "control_codec_id", "codec id is null-safe and identifies selected pcm_s16le", control_codec_id },
    { "control_codecctx_use_videotoolbox", "the VideoToolbox attach succeeds pre-open and replaces on repeat", control_codecctx_use_videotoolbox },
};

static int selected(const char *focus, const char *id)
{
    return focus == NULL || strcmp(focus, id) == 0;
}

int main(int argc, char **argv)
{
    const char *focus;
    size_t i;
    int matched = 0;

    if (argc > 2) {
        fprintf(stderr, "usage: %s [case-id]\n", argv[0]);
        return 2;
    }
    focus = argc == 2 ? argv[1] : NULL;

    kc_suite_begin("test_args");
    for (i = 0; i < sizeof(invalid_cases) / sizeof(invalid_cases[0]); i++) {
        if (!selected(focus, invalid_cases[i].id))
            continue;
        matched = 1;
        kc_case("%s", invalid_cases[i].label);
        expect_einval_in_child(invalid_cases[i].id, invalid_cases[i].call);
    }
    for (i = 0; i < sizeof(control_cases) / sizeof(control_cases[0]); i++) {
        if (!selected(focus, control_cases[i].id))
            continue;
        matched = 1;
        kc_case("%s", control_cases[i].label);
        control_cases[i].call();
    }
    if (!matched) {
        kc_case("selected case exists");
        KC_FAIL("unknown test_args case id: %s", focus);
    }
    return kc_suite_end();
}
