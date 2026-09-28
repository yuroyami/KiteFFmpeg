/* ffkmp_subtitle_converter_*, which decodes the packets of a text subtitle stream and encodes each
 * subtitle again with another text codec. */

#include "harness.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

static char srt_path[512];

static const char SRT[] =
    "1\n00:00:01,000 --> 00:00:02,500\nHello there\n\n"
    "2\n00:00:03,000 --> 00:00:04,000\nSecond line\n\n";

static void remove_srt(void) { remove(srt_path); }

static void write_srt(void)
{
    FILE *file = fopen(srt_path, "wb");
    KC_NOT_NULL(file);
    if (file == NULL) return;
    KC_CHECK(fwrite(SRT, 1, sizeof SRT - 1, file) == sizeof SRT - 1);
    fclose(file);
}

static int64_t in_ms(int64_t value, AVRational tb) { return av_rescale_q(value, tb, (AVRational){ 1, 1000 }); }

static void case_srt_to_mov_text(void)
{
    kc_fmt_ctx *ctx = NULL;
    AVCodecParameters *par = avcodec_parameters_alloc();
    kc_subtitle_converter *conv = NULL;
    AVPacket *in = av_packet_alloc();
    AVPacket *out = av_packet_alloc();
    int64_t pts[2] = { 0, 0 };
    int64_t duration[2] = { 0, 0 };
    int converted = 0;
    AVRational tb;

    kc_case("a SubRip stream converts to mov_text cue by cue, on the stream's own time base");
    KC_NOT_NULL(par);
    KC_NOT_NULL(in);
    KC_NOT_NULL(out);
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, srt_path), 0);
    KC_NOT_NULL(ctx);
    if (ctx == NULL || par == NULL || in == NULL || out == NULL) goto done;
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 0, "mov_text", par, &conv), 0);
    KC_NOT_NULL(conv);
    if (conv == NULL) goto done;
    KC_EQ_INT((int)par->codec_id, (int)AV_CODEC_ID_MOV_TEXT);
    KC_EQ_INT((int)par->codec_type, (int)AVMEDIA_TYPE_SUBTITLE);

    while (av_read_frame(ctx, in) >= 0) {
        int rc = ffkmp_subtitle_converter_convert(conv, in, out);
        KC_CHECKF(rc >= 0, "convert failed with %d", rc);
        if (rc == 1) {
            if (converted == 0) {
                /* mov_text is a 16-bit big-endian length and then the text. */
                KC_CHECK(out->size >= 2 + 11);
                KC_EQ_INT((out->data[0] << 8) | out->data[1], 11);
                KC_EQ_MEM(out->data + 2, "Hello there", 11);
            }
            if (converted < 2) {
                pts[converted] = out->pts;
                duration[converted] = out->duration;
            }
            converted++;
        }
        av_packet_unref(in);
    }
    KC_EQ_INT(converted, 2);
    tb = ctx->streams[0]->time_base;
    KC_EQ_I64(in_ms(pts[0], tb), 1000);
    KC_EQ_I64(in_ms(duration[0], tb), 1500);
    KC_EQ_I64(in_ms(pts[1], tb), 3000);
    KC_EQ_I64(in_ms(duration[1], tb), 1000);

done:
    ffkmp_subtitle_converter_free(&conv);
    KC_NULL(conv);
    av_packet_free(&in);
    av_packet_free(&out);
    avcodec_parameters_free(&par);
    ffkmp_fmt_close_input(&ctx);
}

static void case_refusals(void)
{
    kc_fmt_ctx *ctx = NULL;
    AVCodecParameters *par = avcodec_parameters_alloc();
    kc_subtitle_converter *conv = (kc_subtitle_converter *)(uintptr_t)1;
    AVPacket *pkt = av_packet_alloc();

    kc_case("a codec that is not a text subtitle codec, a stray index and NULL arguments are refused");
    KC_NOT_NULL(par);
    KC_NOT_NULL(pkt);
    KC_EQ_INT(ffkmp_fmt_open_input(&ctx, srt_path), 0);
    if (ctx == NULL || par == NULL || pkt == NULL) goto done;
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 0, "h264", par, &conv), AVERROR(EINVAL));
    KC_NULL(conv);
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 0, "no such codec", par, &conv), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 5, "mov_text", par, &conv), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_converter_open(NULL, 0, "mov_text", par, &conv), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 0, NULL, par, &conv), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 0, "mov_text", NULL, &conv), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_converter_open(ctx, 0, "mov_text", par, NULL), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_subtitle_converter_convert(NULL, pkt, pkt), AVERROR(EINVAL));
    ffkmp_subtitle_converter_free(NULL);
    ffkmp_subtitle_converter_free(&conv);
    KC_NULL(conv);

done:
    av_packet_free(&pkt);
    avcodec_parameters_free(&par);
    ffkmp_fmt_close_input(&ctx);
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_subtitle_convert");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    snprintf(srt_path, sizeof(srt_path), "%s%skc_subtitle_convert_%ld.srt", tmp,
             tmp[strlen(tmp) - 1] == '/' ? "" : "/", (long)getpid());
    write_srt();
    atexit(remove_srt);

    case_srt_to_mov_text();
    case_refusals();

    return kc_suite_end();
}
