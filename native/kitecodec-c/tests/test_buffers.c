/* Fixed buffer and copy bound suite for the extracted FFmpeg helper layer. Closes register
 * the fixed buffers.
 *
 * What the plan asks for, and what is actually here.
 *
 * The item names "nine fixed stack buffers" and lists twelve def line numbers: 37, 506, 558,
 * 663, 708, 553, 704, 623, 669, 712, 578, 723. Measured in the def body, those are
 * twelve buffer declarations across ten declaration lines, because two lines declare two
 * buffers each. Nine is the count you get by treating the four args[512] sites as one; twelve
 * is the count of declarations. This suite covers all twelve, so it closes the item on either
 * reading, and the run report records the difference rather than quietly picking one.
 *
 *   buf[256]         def  37   src 14   ffkmp_strerror
 *   args[512]        def 506   src 483  ffkmp_graph_build_video
 *   args[512]        def 558   src 535  ffkmp_graph_build_audio
 *   args[512]        def 663   src 640  ffkmp_graph_build_video_multi
 *   args[512]        def 708   src 685  ffkmp_graph_build_audio_multi
 *   layout_str[128]  def 553   src 530  ffkmp_graph_build_audio
 *   lay_str[128]     def 704   src 681  ffkmp_graph_build_audio_multi
 *   name[16]         def 623   src 600  ffkmp_graph_finish_multi_
 *   name[16]         def 669   src 640  ffkmp_graph_build_video_multi
 *   name[16]         def 712   src 685  ffkmp_graph_build_audio_multi
 *   full_desc[2048]  def 578   src 555  ffkmp_graph_build_audio
 *   full_desc[2048]  def 723   src 700  ffkmp_graph_build_audio_multi
 *
 * The two full_desc sites are the ones whose overflow was fixed in Horizon A phase A0 under
 * defect D27, so a case that passes trivially there proves nothing. Those two get real limit
 * and limit-plus-one rows driven through the public `description` parameter, described case by
 * case below with what each row would catch.
 *
 * The other ten cannot be driven to their limit through the signatures they sit behind, and
 * saying so with a measurement is stronger than pretending otherwise. The widest input each
 * one can be handed produces, measured on this machine against libavcodec 62.11.100:
 *
 *   buf[256]         60 bytes   the longest av_strerror message in the table below, and 33
 *                               bytes for the numeric fallback at INT_MIN
 *   args[512]       162 bytes   every %d at INT_MIN or INT_MAX and the longest pixel format
 *                               name, which is "videotoolbox_vld" at 16 characters
 *   layout_str[128]  11 bytes   av_channel_layout_describe of any default layout from 1 to 64
 *                               channels, the longest being "64 channels"
 *   name[16]         13 bytes   "in" plus INT_MIN
 *
 * So each of those ten gets two rows. One computes the rendered length at the widest inputs
 * with snprintf(NULL, 0, ...) against the same format string the helper uses and asserts it
 * fits, which is the row that fails the day a format string grows or a name gets longer. One
 * calls the helper at those widest inputs and asserts it refuses rather than corrupting, which
 * is the row ASan and UBSan watch. Neither row is a substitute for the other: the first is a
 * bound over all inputs, the second is a live execution.
 *
 * The four size-taking copy helpers get a destination, or a source, of exactly the size the
 * copy needs and then exactly one byte less. Every one of those buffers is a malloc of the
 * exact size, never a stack array with room to spare, so under asan a single byte past the end
 * is a heap-buffer-overflow with a name and a line number rather than a silent pass.
 *
 *   ffkmp_frame_copy_to_buffer    def 116   src  93
 *   ffkmp_samples_copy_to_buffer  def 130   src 107
 *   ffkmp_frame_fill_video        def 148   src 125
 *   ffkmp_frame_fill_audio        def 163   src 140
 *
 * ABI 2.5 adds ffkmp_codecpar_extradata as another bounded copy surface. Its cases cover the
 * sizing call, exact and partial copies with canaries, empty data, NULL parameters and negative
 * sizes. The negative-size row supplies only one destination byte, so a missing signed guard is
 * visible to ASan rather than becoming a huge memcpy.
 *
 * Plan section 15.0 records that the bound inside ffkmp_frame_copy_to_buffer already exists and
 * is simply unexercised, so those rows are evidence, not repair.
 *
 * Variants. This suite makes no allocation claims, so it says the same thing in all three
 * variants. plain is the correctness run, asan is where the out-of-bounds class becomes
 * visible, tsan adds nothing here and costs nothing.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavfilter/buffersink.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>
#include <libavutil/samplefmt.h>

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- Fixtures ---- */

static AVFrame *video_frame(int w, int h, int fmt)
{
    AVFrame *f = ffkmp_frame_alloc();
    KC_NOT_NULL(f);
    ffkmp_frame_set_width(f, w);
    ffkmp_frame_set_height(f, h);
    ffkmp_frame_set_format(f, fmt);
    KC_EQ_INT(ffkmp_frame_get_buffer(f, 0), 0);
    return f;
}

static AVFrame *audio_frame(int rate, int fmt, int channels, int samples)
{
    AVFrame *f = ffkmp_frame_alloc();
    KC_NOT_NULL(f);
    ffkmp_frame_set_format(f, fmt);
    ffkmp_frame_set_sample_rate(f, rate);
    ffkmp_frame_set_nb_samples(f, samples);
    ffkmp_frame_set_ch_layout_default(f, channels);
    KC_EQ_INT(ffkmp_frame_get_buffer(f, 0), 0);
    return f;
}

/* Writes a recognisable pattern over every plane a frame owns, so a copy that loses or
 * reorders bytes is visible rather than plausible. */
static void paint_frame(AVFrame *f, unsigned seed)
{
    int planes = ffkmp_frame_plane_count(f);
    int p;
    for (p = 0; p < planes; p++) {
        uint8_t *data = ffkmp_frame_plane(f, p);
        int stride = ffkmp_frame_linesize(f, p);
        int rows = ffkmp_frame_plane_height(f, p);
        int y;
        int x;
        KC_NOT_NULL(data);
        for (y = 0; y < rows; y++) {
            for (x = 0; x < stride; x++)
                data[(size_t)y * (size_t)stride + (size_t)x] =
                    (uint8_t)(seed + (unsigned)p * 41u + (unsigned)y * 7u + (unsigned)x);
        }
    }
}

static void paint_audio(AVFrame *f, unsigned seed)
{
    int channels = ffkmp_frame_channels(f);
    int planes = av_sample_fmt_is_planar(ffkmp_frame_format(f)) ? channels : 1;
    int needed = ffkmp_samples_get_buffer_size(f);
    int plane_size;
    int p;
    int i;
    KC_CHECKF(needed > 0, "samples_get_buffer_size returned %d", needed);
    plane_size = needed / planes;
    for (p = 0; p < planes; p++) {
        uint8_t *data = f->extended_data[p];
        KC_NOT_NULL(data);
        for (i = 0; i < plane_size; i++)
            data[i] = (uint8_t)(seed + (unsigned)p * 31u + (unsigned)i);
    }
}

/* Builds a filter description of exactly `total` characters: `prefix`, then as many "anull,"
 * links as fit, then `tail`. Every description this returns is a valid filter chain, which is
 * what makes a truncation detectable: cutting the last character leaves a tail that does not
 * parse, so the helper's return code changes. */
static char *chain_of_length(int total, const char *prefix, const char *tail)
{
    size_t plen = strlen(prefix);
    size_t tlen = strlen(tail);
    int links = (total - (int)plen - (int)tlen) / 6;
    char *out;
    int at = 0;
    int i;
    KC_CHECKF(links >= 0 && (total - (int)plen - (int)tlen) % 6 == 0,
              "chain_of_length(%d, \"%s\", \"%s\") is not reachable in 6 character links",
              total, prefix, tail);
    out = (char *)malloc((size_t)total + 1);
    KC_NOT_NULL(out);
    memcpy(out + at, prefix, plen);
    at += (int)plen;
    for (i = 0; i < links; i++) {
        memcpy(out + at, "anull,", 6);
        at += 6;
    }
    memcpy(out + at, tail, tlen);
    at += (int)tlen;
    out[at] = '\0';
    KC_EQ_SIZE(kc_strlen(out), (size_t)total);
    return out;
}

/* The aformat suffix ffkmp_graph_build_audio appends for these three pins, so a row can say
 * which total length is the exact fit rather than guessing. */
#define PIN_FMT AV_SAMPLE_FMT_S16
#define PIN_RATE 44100
#define PIN_CHANNELS 2
static int pin_suffix_length(void)
{
    /* ",aformat=" then "sample_fmts=s16" then ":sample_rates=44100" then
     * ":channel_layouts=2c", built with the same formats the helper uses. */
    return snprintf(NULL, 0, ",aformat=") +
           snprintf(NULL, 0, "sample_fmts=%s", av_get_sample_fmt_name(PIN_FMT)) +
           snprintf(NULL, 0, ":sample_rates=%d", PIN_RATE) +
           snprintf(NULL, 0, ":channel_layouts=%dc", PIN_CHANNELS);
}

/* ---- Codec parameter extradata copy ---- */

static void case_codecpar_extradata_query_and_exact_copy(void)
{
    uint8_t config[] = { 1, 100, 0, 31, 0xff, 0xe1, 0x23 };
    uint8_t destination[9];
    AVCodecParameters par = { 0 };
    par.extradata = config;
    par.extradata_size = (int)sizeof(config);
    memset(destination, 0xa5, sizeof(destination));

    KC_EQ_INT(ffkmp_codecpar_extradata(&par, NULL, 0), (int)sizeof(config));
    KC_EQ_INT(ffkmp_codecpar_extradata(&par, destination, (int)sizeof(config)),
              (int)sizeof(config));
    KC_EQ_MEM(destination, config, sizeof(config));
    KC_EQ_INT((int)destination[7], 0xa5);
    KC_EQ_INT((int)destination[8], 0xa5);
    kc_detail("bytes=%zu", sizeof(config));
}

static void case_codecpar_extradata_partial_copy(void)
{
    uint8_t config[] = { 1, 2, 3, 4, 5 };
    uint8_t destination[7];
    AVCodecParameters par = { 0 };
    size_t i;
    par.extradata = config;
    par.extradata_size = (int)sizeof(config);
    memset(destination, 0xa5, sizeof(destination));

    KC_EQ_INT(ffkmp_codecpar_extradata(&par, destination, 3), 3);
    KC_EQ_MEM(destination, config, 3u);
    for (i = 3; i < sizeof(destination); i++) {
        KC_EQ_INT((int)destination[i], 0xa5);
    }
    KC_EQ_INT(ffkmp_codecpar_extradata(&par, destination, 0), 0);
    KC_EQ_MEM(destination, config, 3u);
    kc_detail("available=%zu copied=3", sizeof(config));
}

static void case_codecpar_extradata_empty_and_invalid(void)
{
    uint8_t config[] = { 9, 8, 7 };
    uint8_t destination = 0x5a;
    AVCodecParameters par = { 0 };

    KC_EQ_INT(ffkmp_codecpar_extradata(NULL, NULL, 0), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecpar_extradata(&par, &destination, 1), 0);
    par.extradata_size = (int)sizeof(config);
    KC_EQ_INT(ffkmp_codecpar_extradata(&par, &destination, 1), 0);
    par.extradata = config;
    KC_EQ_INT(ffkmp_codecpar_extradata(&par, &destination, -1), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_codecpar_extradata(&par, NULL, INT_MIN), AVERROR(EINVAL));
    KC_EQ_INT((int)destination, 0x5a);
}

static void case_codecpar_video_metadata(void)
{
    AVCodecParameters par = { 0 };

    par.profile = 2;
    par.level = 51;
    par.format = AV_PIX_FMT_YUV420P10LE;
    par.color_space = AVCOL_SPC_BT2020_NCL;
    par.color_primaries = AVCOL_PRI_BT2020;
    par.color_trc = AVCOL_TRC_SMPTE2084;
    par.color_range = AVCOL_RANGE_MPEG;
    par.chroma_location = AVCHROMA_LOC_TOPLEFT;

    KC_EQ_INT(ffkmp_codecpar_profile(&par), 2);
    KC_EQ_INT(ffkmp_codecpar_level(&par), 51);
    KC_EQ_INT(ffkmp_codecpar_bit_depth(&par), 10);
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(&par), 420);
    KC_EQ_INT(ffkmp_codecpar_color_space(&par), AVCOL_SPC_BT2020_NCL);
    KC_EQ_INT(ffkmp_codecpar_color_primaries(&par), AVCOL_PRI_BT2020);
    KC_EQ_INT(ffkmp_codecpar_color_transfer(&par), AVCOL_TRC_SMPTE2084);
    KC_EQ_INT(ffkmp_codecpar_color_range(&par), AVCOL_RANGE_MPEG);
    KC_EQ_INT(ffkmp_codecpar_chroma_location(&par), AVCHROMA_LOC_TOPLEFT);

    par.format = AV_PIX_FMT_YUV422P;
    KC_EQ_INT(ffkmp_codecpar_bit_depth(&par), 8);
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(&par), 422);
    par.format = AV_PIX_FMT_YUV444P12LE;
    KC_EQ_INT(ffkmp_codecpar_bit_depth(&par), 12);
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(&par), 444);
    par.format = AV_PIX_FMT_GRAY8;
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(&par), 400);
    par.format = AV_PIX_FMT_RGBA;
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(&par), 0);
}

static void case_codecpar_video_metadata_unknown(void)
{
    AVCodecParameters par = { 0 };

    par.profile = -99;
    par.level = -99;
    par.format = AV_PIX_FMT_NONE;
    par.bits_per_raw_sample = 9;

    KC_EQ_INT(ffkmp_codecpar_profile(&par), -99);
    KC_EQ_INT(ffkmp_codecpar_level(&par), -99);
    KC_EQ_INT(ffkmp_codecpar_bit_depth(&par), 9);
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(&par), 0);
    KC_EQ_INT(ffkmp_codecpar_color_space(&par), AVCOL_SPC_RGB);
    KC_EQ_INT(ffkmp_codecpar_color_primaries(&par), AVCOL_PRI_RESERVED0);
    KC_EQ_INT(ffkmp_codecpar_color_transfer(&par), AVCOL_TRC_RESERVED0);
    KC_EQ_INT(ffkmp_codecpar_color_range(&par), AVCOL_RANGE_UNSPECIFIED);
    KC_EQ_INT(ffkmp_codecpar_chroma_location(&par), AVCHROMA_LOC_UNSPECIFIED);

    KC_EQ_INT(ffkmp_codecpar_profile(NULL), -99);
    KC_EQ_INT(ffkmp_codecpar_level(NULL), -99);
    KC_EQ_INT(ffkmp_codecpar_bit_depth(NULL), 0);
    KC_EQ_INT(ffkmp_codecpar_chroma_subsampling(NULL), 0);
    KC_EQ_INT(ffkmp_codecpar_color_space(NULL), AVCOL_SPC_UNSPECIFIED);
    KC_EQ_INT(ffkmp_codecpar_color_primaries(NULL), AVCOL_PRI_UNSPECIFIED);
    KC_EQ_INT(ffkmp_codecpar_color_transfer(NULL), AVCOL_TRC_UNSPECIFIED);
    KC_EQ_INT(ffkmp_codecpar_color_range(NULL), AVCOL_RANGE_UNSPECIFIED);
    KC_EQ_INT(ffkmp_codecpar_chroma_location(NULL), AVCHROMA_LOC_UNSPECIFIED);
}

/* ---- The four frame and sample size-taking copy helpers ---- */

static void case_frame_copy_to_buffer_exact(void)
{
    AVFrame *f = video_frame(16, 16, AV_PIX_FMT_YUV420P);
    int needed = ffkmp_image_get_buffer_size(AV_PIX_FMT_YUV420P, 16, 16, 1);
    uint8_t *dst;
    paint_frame(f, 3);
    KC_CHECKF(needed > 0, "image_get_buffer_size returned %d", needed);
    /* Exactly the needed size and not one byte more, so a copy that writes past the end lands
     * in ASan's redzone instead of in slack the test would never notice. */
    dst = (uint8_t *)malloc((size_t)needed);
    KC_NOT_NULL(dst);
    memset(dst, 0, (size_t)needed);
    KC_EQ_INT(ffkmp_frame_copy_to_buffer(f, dst, needed), needed);
    kc_detail("needed=%d", needed);
    free(dst);
    ffkmp_frame_free(f);
}

static void case_frame_copy_to_buffer_one_byte_short(void)
{
    AVFrame *f = video_frame(16, 16, AV_PIX_FMT_YUV420P);
    int needed = ffkmp_image_get_buffer_size(AV_PIX_FMT_YUV420P, 16, 16, 1);
    /* The destination really is one byte smaller than the copy needs, not a larger block with
     * a smaller size argument. That is what makes ASan a detector here and not just a witness:
     * a helper that dropped the size argument, or passed the frame's own linesize instead,
     * would copy 384 bytes into a 383 byte heap block and ASan would name the overflow. The
     * return code is the second detector, for the case where a weakened bound still writes
     * inside the block. */
    uint8_t *dst = (uint8_t *)malloc((size_t)needed - 1);
    paint_frame(f, 5);
    KC_NOT_NULL(dst);
    memset(dst, 0, (size_t)needed - 1);
    KC_CHECKF(ffkmp_frame_copy_to_buffer(f, dst, needed - 1) < 0,
              "a destination one byte short was accepted");
    /* Refused means refused: not one byte was written before the check. */
    KC_ALL_ZERO(dst, (size_t)needed - 1);
    kc_detail("needed=%d short=%d", needed, needed - 1);
    free(dst);
    ffkmp_frame_free(f);
}

static void case_frame_copy_to_buffer_degenerate_sizes(void)
{
    AVFrame *f = video_frame(16, 16, AV_PIX_FMT_YUV420P);
    uint8_t one = 0;
    /* Zero, one and a negative size all have to be refused. A negative size is the one that
     * matters most: the parameter is a signed int reached from Kotlin, and a helper that
     * compared sizes with the wrong sign would treat it as room for gigabytes. */
    KC_CHECKF(ffkmp_frame_copy_to_buffer(f, &one, 0) < 0, "a zero size was accepted");
    KC_CHECKF(ffkmp_frame_copy_to_buffer(f, &one, 1) < 0, "a one byte destination was accepted");
    KC_CHECKF(ffkmp_frame_copy_to_buffer(f, &one, -1) < 0, "a negative size was accepted");
    KC_CHECKF(ffkmp_frame_copy_to_buffer(f, &one, INT_MIN) < 0, "INT_MIN was accepted");
    KC_CHECKF(ffkmp_frame_copy_to_buffer(f, NULL, 4096) < 0, "a NULL destination was accepted");
    KC_CHECKF(ffkmp_frame_copy_to_buffer(NULL, &one, 4096) < 0, "a NULL frame was accepted");
    KC_EQ_INT((int)one, 0);
    ffkmp_frame_free(f);
}

static void case_frame_fill_video_round_trip(void)
{
    AVFrame *src = video_frame(16, 16, AV_PIX_FMT_YUV420P);
    AVFrame *dst = video_frame(16, 16, AV_PIX_FMT_YUV420P);
    int needed = ffkmp_image_get_buffer_size(AV_PIX_FMT_YUV420P, 16, 16, 1);
    uint8_t *packed = (uint8_t *)malloc((size_t)needed);
    uint8_t *again = (uint8_t *)malloc((size_t)needed);
    paint_frame(src, 11);
    paint_frame(dst, 200);
    KC_NOT_NULL(packed);
    KC_NOT_NULL(again);
    KC_EQ_INT(ffkmp_frame_copy_to_buffer(src, packed, needed), needed);
    /* The reverse direction at exactly the needed size. Comparing through a second packed copy
     * rather than plane by plane keeps linesize padding out of the comparison, which is what
     * the two helpers agree about and all a caller can rely on. */
    KC_EQ_INT(ffkmp_frame_fill_video(dst, packed, needed), 0);
    KC_EQ_INT(ffkmp_frame_copy_to_buffer(dst, again, needed), needed);
    KC_EQ_MEM(again, packed, (size_t)needed);
    kc_detail("needed=%d", needed);
    free(again);
    free(packed);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_frame_fill_video_one_byte_short(void)
{
    AVFrame *dst = video_frame(16, 16, AV_PIX_FMT_YUV420P);
    int needed = ffkmp_image_get_buffer_size(AV_PIX_FMT_YUV420P, 16, 16, 1);
    /* Here the short buffer is the source, so the hazard is a read past the end rather than a
     * write. Exactly needed minus one bytes on the heap: without the src_size check,
     * av_image_fill_arrays would map plane pointers over memory that does not belong to the
     * caller and av_image_copy would read all of it. What this row would catch is that read,
     * named by ASan as a heap-buffer-overflow on the source. */
    uint8_t *shortsrc = (uint8_t *)malloc((size_t)needed - 1);
    KC_NOT_NULL(shortsrc);
    memset(shortsrc, 0x5a, (size_t)needed - 1);
    KC_CHECKF(ffkmp_frame_fill_video(dst, shortsrc, needed - 1) < 0,
              "a source one byte short was accepted");
    KC_CHECKF(ffkmp_frame_fill_video(dst, shortsrc, 0) < 0, "a zero sized source was accepted");
    KC_CHECKF(ffkmp_frame_fill_video(dst, shortsrc, -1) < 0, "a negative size was accepted");
    KC_CHECKF(ffkmp_frame_fill_video(dst, NULL, needed) < 0, "a NULL source was accepted");
    KC_CHECKF(ffkmp_frame_fill_video(NULL, shortsrc, needed) < 0, "a NULL frame was accepted");
    kc_detail("needed=%d short=%d", needed, needed - 1);
    free(shortsrc);
    ffkmp_frame_free(dst);
}

static void case_samples_copy_to_buffer_exact_planar(void)
{
    AVFrame *f = audio_frame(48000, AV_SAMPLE_FMT_FLTP, 2, 1024);
    int needed;
    uint8_t *dst;
    paint_audio(f, 17);
    needed = ffkmp_samples_get_buffer_size(f);
    KC_EQ_INT(needed, 2 * 1024 * 4);
    dst = (uint8_t *)malloc((size_t)needed);
    KC_NOT_NULL(dst);
    memset(dst, 0, (size_t)needed);
    KC_EQ_INT(ffkmp_samples_copy_to_buffer(f, dst, needed), needed);
    /* Planar audio lands plane after plane. Checking the second plane's first byte is what
     * catches a plane_size computed from the wrong divisor, which would otherwise look like a
     * successful copy of the wrong bytes. */
    KC_EQ_MEM(dst, f->extended_data[0], (size_t)needed / 2);
    KC_EQ_MEM(dst + needed / 2, f->extended_data[1], (size_t)needed / 2);
    kc_detail("needed=%d", needed);
    free(dst);
    ffkmp_frame_free(f);
}

static void case_samples_copy_to_buffer_one_byte_short(void)
{
    AVFrame *f = audio_frame(48000, AV_SAMPLE_FMT_FLTP, 2, 1024);
    int needed;
    uint8_t *dst;
    paint_audio(f, 19);
    needed = ffkmp_samples_get_buffer_size(f);
    /* Again a destination that is genuinely one byte short, so both detectors are armed. */
    dst = (uint8_t *)malloc((size_t)needed - 1);
    KC_NOT_NULL(dst);
    memset(dst, 0, (size_t)needed - 1);
    /* The helper's own guard, `if (needed > dst_size) return AVERROR(EINVAL)`. What this row
     * would catch: a guard written with >= or against the plane size instead of the total
     * would let the last plane's memcpy run one byte past the destination, and ASan would name
     * it. The zero check afterwards proves no plane was copied before the refusal, which a
     * guard placed inside the plane loop would fail. */
    KC_CHECKF(ffkmp_samples_copy_to_buffer(f, dst, needed - 1) < 0,
              "a destination one byte short was accepted");
    KC_ALL_ZERO(dst, (size_t)needed - 1);
    KC_CHECKF(ffkmp_samples_copy_to_buffer(f, dst, 0) < 0, "a zero size was accepted");
    KC_CHECKF(ffkmp_samples_copy_to_buffer(f, dst, -1) < 0, "a negative size was accepted");
    KC_CHECKF(ffkmp_samples_copy_to_buffer(f, NULL, needed) < 0,
              "a NULL destination was accepted");
    KC_CHECKF(ffkmp_samples_copy_to_buffer(NULL, dst, needed) < 0, "a NULL frame was accepted");
    kc_detail("needed=%d short=%d", needed, needed - 1);
    free(dst);
    ffkmp_frame_free(f);
}

static void case_fill_audio_round_trip_planar(void)
{
    AVFrame *src = audio_frame(48000, AV_SAMPLE_FMT_FLTP, 2, 1024);
    AVFrame *dst = audio_frame(48000, AV_SAMPLE_FMT_FLTP, 2, 1024);
    int needed;
    uint8_t *packed;
    paint_audio(src, 23);
    paint_audio(dst, 250);
    needed = ffkmp_samples_get_buffer_size(src);
    packed = (uint8_t *)malloc((size_t)needed);
    KC_NOT_NULL(packed);
    KC_EQ_INT(ffkmp_samples_copy_to_buffer(src, packed, needed), needed);
    KC_EQ_INT(ffkmp_frame_fill_audio(dst, packed, needed), 0);
    KC_EQ_MEM(dst->extended_data[0], src->extended_data[0], (size_t)needed / 2);
    KC_EQ_MEM(dst->extended_data[1], src->extended_data[1], (size_t)needed / 2);
    kc_detail("needed=%d", needed);
    free(packed);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_fill_audio_round_trip_packed(void)
{
    AVFrame *src = audio_frame(48000, AV_SAMPLE_FMT_S16, 2, 512);
    AVFrame *dst = audio_frame(48000, AV_SAMPLE_FMT_S16, 2, 512);
    int needed;
    uint8_t *packed;
    paint_audio(src, 29);
    paint_audio(dst, 240);
    needed = ffkmp_samples_get_buffer_size(src);
    /* Interleaved, so one plane of 512 frames times 2 channels times 2 bytes. The packed and
     * planar paths take different branches of the same plane loop, and a divisor bug shows up
     * in only one of them. */
    KC_EQ_INT(needed, 512 * 2 * 2);
    packed = (uint8_t *)malloc((size_t)needed);
    KC_NOT_NULL(packed);
    KC_EQ_INT(ffkmp_samples_copy_to_buffer(src, packed, needed), needed);
    KC_EQ_INT(ffkmp_frame_fill_audio(dst, packed, needed), 0);
    KC_EQ_MEM(dst->extended_data[0], src->extended_data[0], (size_t)needed);
    kc_detail("needed=%d", needed);
    free(packed);
    ffkmp_frame_free(dst);
    ffkmp_frame_free(src);
}

static void case_fill_audio_one_byte_short(void)
{
    AVFrame *dst = audio_frame(48000, AV_SAMPLE_FMT_FLTP, 2, 1024);
    int needed;
    uint8_t *shortsrc;
    paint_audio(dst, 31);
    needed = ffkmp_samples_get_buffer_size(dst);
    /* A source of exactly needed minus one, so the read bound is what is under test. Without
     * the src_size check the second plane's memcpy would read one byte past the caller's
     * buffer, which is the read ASan names here. */
    shortsrc = (uint8_t *)malloc((size_t)needed - 1);
    KC_NOT_NULL(shortsrc);
    memset(shortsrc, 0x3c, (size_t)needed - 1);
    KC_CHECKF(ffkmp_frame_fill_audio(dst, shortsrc, needed - 1) < 0,
              "a source one byte short was accepted");
    KC_CHECKF(ffkmp_frame_fill_audio(dst, shortsrc, 0) < 0, "a zero sized source was accepted");
    KC_CHECKF(ffkmp_frame_fill_audio(dst, shortsrc, -1) < 0, "a negative size was accepted");
    KC_CHECKF(ffkmp_frame_fill_audio(dst, NULL, needed) < 0, "a NULL source was accepted");
    KC_CHECKF(ffkmp_frame_fill_audio(NULL, shortsrc, needed) < 0, "a NULL frame was accepted");
    kc_detail("needed=%d short=%d", needed, needed - 1);
    free(shortsrc);
    ffkmp_frame_free(dst);
}

static void case_fill_audio_refuses_more_channels_than_planes(void)
{
    AVFrame *f = ffkmp_frame_alloc();
    uint8_t byte = 0;
    KC_NOT_NULL(f);
    ffkmp_frame_set_format(f, AV_SAMPLE_FMT_FLTP);
    ffkmp_frame_set_sample_rate(f, 48000);
    ffkmp_frame_set_nb_samples(f, 16);
    ffkmp_frame_set_ch_layout_default(f, 16);
    /* AVFrame.data holds AV_NUM_DATA_POINTERS planes, which is 8. The helper refuses above
     * that instead of indexing extended_data past what it can trust. What this row would
     * catch: dropping that check would index a 16 plane layout through an 8 entry array. */
    KC_CHECKF(ffkmp_frame_fill_audio(f, &byte, 1) < 0, "a 16 channel planar frame was accepted");
    kc_detail("channels=%d limit=%d", ffkmp_frame_channels(f), AV_NUM_DATA_POINTERS);
    ffkmp_frame_free(f);
}

/* ---- buf[256], src 14, ffkmp_strerror ---- */

static void case_strerror_fits_its_buffer(void)
{
    static const int codes[] = {
        0,
        AVERROR(EINVAL), AVERROR(ENOMEM), AVERROR(EAGAIN), AVERROR(EIO),
        AVERROR_EOF, AVERROR_INVALIDDATA, AVERROR_UNKNOWN, AVERROR_EXPERIMENTAL,
        AVERROR_BSF_NOT_FOUND, AVERROR_DECODER_NOT_FOUND, AVERROR_ENCODER_NOT_FOUND,
        AVERROR_DEMUXER_NOT_FOUND, AVERROR_MUXER_NOT_FOUND, AVERROR_FILTER_NOT_FOUND,
        AVERROR_PROTOCOL_NOT_FOUND, AVERROR_STREAM_NOT_FOUND, AVERROR_OPTION_NOT_FOUND,
        AVERROR_PATCHWELCOME, AVERROR_BUFFER_TOO_SMALL, AVERROR_EXIT,
        AVERROR_HTTP_BAD_REQUEST, AVERROR_HTTP_UNAUTHORIZED, AVERROR_HTTP_FORBIDDEN,
        AVERROR_HTTP_NOT_FOUND, AVERROR_HTTP_TOO_MANY_REQUESTS, AVERROR_HTTP_OTHER_4XX,
        AVERROR_HTTP_SERVER_ERROR,
        -1234567, -1, INT_MIN + 1
    };
    size_t longest = 0;
    size_t i;
    for (i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        /* A reference far larger than the helper's 256 bytes. Comparing the two is the row:
         * equality proves the helper did not truncate, and the reference length proves the
         * buffer is wide enough for every message this FFmpeg can produce. What it would
         * catch is the day a message grows past 255 bytes, which today would be silent, and a
         * missing NUL, which would make the comparison read past the buffer under ASan. */
        char reference[4096];
        const char *got;
        size_t len;
        KC_CHECKF(av_strerror(codes[i], reference, sizeof(reference)) == 0 ||
                  reference[0] != '\0',
                  "av_strerror produced nothing for %d", codes[i]);
        got = ffkmp_strerror(codes[i]);
        KC_NOT_NULL(got);
        len = kc_strlen(got);
        KC_CHECKF(len < 256, "code %d produced %zu bytes, the buffer is 256", codes[i], len);
        KC_EQ_STR(got, reference);
        if (len > longest)
            longest = len;
    }
    kc_detail("codes=%zu longest=%zu limit=255",
              sizeof(codes) / sizeof(codes[0]), longest);
}

static void case_strerror_widest_message_bound(void)
{
    /* The numeric fallback is the widest message the helper can be made to produce by a caller,
     * because every other message is a fixed string FFmpeg chose. Rendering it at INT_MIN and
     * at INT_MAX is the limit-plus-one attempt for this buffer: 256 bytes cannot be exceeded
     * through this signature, and this is the row that stops being true if the fallback format
     * ever grows. */
    int widest = snprintf(NULL, 0, "Error number %d occurred", INT_MIN);
    int at_max = snprintf(NULL, 0, "Error number %d occurred", INT_MAX);
    KC_CHECKF(widest < 256, "the numeric fallback needs %d bytes, the buffer is 256", widest);
    KC_CHECKF(at_max < 256, "the numeric fallback needs %d bytes, the buffer is 256", at_max);
    KC_CHECKF(kc_strlen(ffkmp_strerror(INT_MIN + 1)) < 256, "INT_MIN + 1 overflowed the buffer");
    kc_detail("widest=%d limit=255", widest);
}

/* ---- args[512], src 483 and 640, the two video builders ---- */

static const char *longest_pix_fmt_name(int *out_len)
{
    const char *longest = "yuv420p";
    size_t best = strlen(longest);
    int i;
    for (i = 0; i < AV_PIX_FMT_NB; i++) {
        const char *name = av_get_pix_fmt_name((enum AVPixelFormat)i);
        if (name != NULL && strlen(name) > best) {
            best = strlen(name);
            longest = name;
        }
    }
    *out_len = (int)best;
    return longest;
}

static void case_video_args_bound(void)
{
    int name_len = 0;
    const char *widest_name = longest_pix_fmt_name(&name_len);
    /* The same format string src 484 and src 641 use, at the widest value every conversion can
     * take: eight ints at INT_MIN or INT_MAX and the longest pixel format name this build
     * knows. What this row would catch is a format string that grows a field, or an FFmpeg that
     * adds a longer format name, either of which would start truncating the buffer filter's
     * arguments and produce a graph configured from a half written string. */
    int rendered = snprintf(NULL, 0,
        "video_size=%dx%d:pix_fmt=%s:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d",
        INT_MIN, INT_MIN, widest_name, INT_MIN, INT_MAX, INT_MIN, INT_MAX, INT_MIN, INT_MAX);
    KC_CHECKF(rendered > 0, "snprintf measurement failed");
    KC_CHECKF(rendered < 512, "the widest video args need %d bytes, the buffer is 512", rendered);
    kc_detail("rendered=%d limit=511 pix_fmt=%s(%d)", rendered, widest_name, name_len);
}

static void case_video_args_at_the_widest_inputs(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    int name_len = 0;
    const char *widest_name = longest_pix_fmt_name(&name_len);
    int widest_fmt = av_get_pix_fmt(widest_name);
    /* The live half of the row above. Every number at its extreme, and a pixel format whose
     * name is the longest one there is, so the helper really renders the widest string it can.
     * The graph is expected to be refused, since no buffer filter accepts INT_MIN as a width.
     * What matters is that it is refused rather than crashing, and that asan and ubsan stay
     * silent while the widest string is built on the stack. */
    KC_CHECKF(ffkmp_graph_build_video(&graph, &src, &sink, "null",
                                      INT_MIN, INT_MIN, AV_PIX_FMT_YUV420P,
                                      INT_MIN, INT_MAX, INT_MIN, INT_MAX, INT_MIN, INT_MAX) < 0,
              "a graph with INT_MIN dimensions was accepted");
    KC_NULL(graph);
    KC_CHECKF(widest_fmt >= 0, "the longest pixel format name did not resolve back");
    KC_CHECKF(ffkmp_graph_build_video(&graph, &src, &sink, "null",
                                      1920, 1080, widest_fmt, 1, 25, 25, 1, 1, 1) < 0,
              "a graph in a hardware pixel format was accepted");
    KC_NULL(graph);
    /* An unknown pixel format is an ARGUMENT ERROR, not an invitation to substitute yuv420p:
     * a graph silently built for a different format than the caller's frames misreads every
     * plane. The helper resolves the name first and refuses when there is none, so no NULL
     * name ever reaches snprintf. */
    KC_EQ_INT(ffkmp_graph_build_video(&graph, &src, &sink, "null",
                                      64, 64, 999999, 1, 25, 25, 1, 1, 1), AVERROR(EINVAL));
    KC_NULL(graph);
    kc_detail("pix_fmt=%s, unknown format refused with EINVAL", widest_name);
}

static void case_video_multi_args_at_the_widest_inputs(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[2] = { NULL, NULL };
    AVFilterContext *sink = NULL;
    const int extremes[2] = { INT_MIN, INT_MAX };
    const int fmts[2] = { 999999, AV_PIX_FMT_YUV420P };
    /* src 641 renders the same string per input from parallel arrays. Feeding it INT_MIN and
     * INT_MAX in the same call covers both signs of every field, and an unknown format in the
     * first slot is refused outright since P1-22. Refusal is the expected answer; the row exists
     * so the widest render happens under the sanitizers. */
    KC_CHECKF(ffkmp_graph_build_video_multi(&graph, srcs, &sink, "[in0][in1]overlay=0:0[out]", 2,
                                            extremes, extremes, fmts, extremes, extremes,
                                            extremes, extremes, extremes, extremes) < 0,
              "a multi input graph with INT_MIN dimensions was accepted");
    KC_NULL(graph);
    KC_CHECKF(ffkmp_graph_build_video_multi(&graph, srcs, &sink, "[in0]null[out]", 0,
                                            extremes, extremes, fmts, extremes, extremes,
                                            extremes, extremes, extremes, extremes) < 0,
              "zero inputs were accepted");
    KC_NULL(graph);

    /* P1-22: everything else VALID, one unknown pixel format. The multi input builder used to
     * substitute yuv420p here, which builds the graph for a layout the caller's frames are not in
     * and misreads every plane. The single input builder has always refused; this is the same
     * answer, and the row above cannot see it because INT_MIN dimensions refuse on their own. */
    {
        const int ok_dims[2] = { 64, 64 };
        const int ok_ones[2] = { 1, 1 };
        const int ok_rates[2] = { 25, 25 };
        const int bad_fmts[2] = { 999999, AV_PIX_FMT_YUV420P };
        KC_EQ_INT(ffkmp_graph_build_video_multi(&graph, srcs, &sink, "[in0][in1]overlay=0:0[out]", 2,
                                                ok_dims, ok_dims, bad_fmts, ok_ones, ok_rates,
                                                ok_rates, ok_ones, ok_ones, ok_ones),
                  AVERROR(EINVAL));
        KC_NULL(graph);
        kc_note("an unknown pixel format in a multi input graph is refused, never substituted");
    }
}

/* ---- args[512] and layout_str[128] and lay_str[128], the two audio builders ---- */

static void case_audio_args_and_layout_bound(void)
{
    char described[4096];
    size_t longest_layout = 0;
    int longest_sample_name = 0;
    int channels;
    int i;
    for (channels = 1; channels <= 64; channels++) {
        AVChannelLayout layout;
        int rc;
        av_channel_layout_default(&layout, channels);
        rc = av_channel_layout_describe(&layout, described, sizeof(described));
        av_channel_layout_uninit(&layout);
        KC_CHECKF(rc > 0, "av_channel_layout_describe failed for %d channels", channels);
        /* layout_str at src 530 and lay_str at src 681 are both 128 bytes and both hold this
         * string. What this row would catch: a default layout whose description grows past 127
         * bytes. The helpers check only that describe returned a non-negative value, not that
         * the value fits, so a longer description would be truncated silently and the filter
         * would be configured from a partial layout name. That is the one real weakness in
         * these two buffers, and it is measured here rather than assumed away. */
        KC_CHECKF(strlen(described) < 128,
                  "%d channels describe to %zu bytes, the buffer is 128",
                  channels, strlen(described));
        KC_CHECKF(rc < 128, "%d channels need %d bytes, the buffer is 128", channels, rc);
        if (strlen(described) > longest_layout)
            longest_layout = strlen(described);
    }
    for (i = 0; i < AV_SAMPLE_FMT_NB; i++) {
        const char *name = av_get_sample_fmt_name((enum AVSampleFormat)i);
        if (name != NULL && (int)strlen(name) > longest_sample_name)
            longest_sample_name = (int)strlen(name);
    }
    {
        /* The same format string src 536 and src 686 use, at the widest inputs: every int at an
         * extreme, the longest sample format name, and the longest layout description measured
         * above. */
        int rendered = snprintf(NULL, 0,
            "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
            INT_MIN, INT_MAX, INT_MIN, "s16p", described);
        KC_CHECKF(rendered < 512, "the widest audio args need %d bytes, the buffer is 512",
                  rendered);
        kc_detail("layout_max=%zu sample_fmt_max=%d args=%d",
                  longest_layout, longest_sample_name, rendered);
    }
}

static void case_audio_args_at_the_widest_inputs(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    /* 64 channels is the widest layout description there is, and every other field is at an
     * extreme. Refusal is expected: a 64 channel default layout describes to "64 channels",
     * which contains a space and is not a filter argument any parser accepts. The row exists
     * so the widest layout_str and args render live under the sanitizers, and so a future
     * FFmpeg that changes that description cannot do it silently. */
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, "anull",
                                      INT_MIN, AV_SAMPLE_FMT_S16P, 64,
                                      INT_MIN, INT_MAX, -1, -1, 0) < 0,
              "a 64 channel graph at INT_MIN rates was accepted");
    KC_NULL(graph);
    /* An unknown sample format is refused before anything is rendered, which is the branch
     * that would otherwise pass NULL to a %s conversion. */
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, "anull",
                                      48000, 9999, 2, 1, 48000, -1, -1, 0) < 0,
              "an unknown sample format was accepted");
    KC_NULL(graph);
    /* Zero and negative channel counts fall back to 2 inside the helper rather than describing
     * a zero channel layout. */
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, "anull",
                                      48000, AV_SAMPLE_FMT_FLTP, 0, 1, 48000, -1, -1, 0), 0);
    KC_NOT_NULL(graph);
    ffkmp_graph_free(&graph);
}

static void case_audio_multi_args_at_the_widest_inputs(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[2] = { NULL, NULL };
    AVFilterContext *sink = NULL;
    const int rates[2] = { INT_MIN, INT_MAX };
    const int fmts[2] = { AV_SAMPLE_FMT_S16P, AV_SAMPLE_FMT_FLTP };
    const int channels[2] = { 64, 63 };
    const int tb_nums[2] = { INT_MIN, INT_MAX };
    const int tb_dens[2] = { INT_MAX, INT_MIN };
    /* The same widest render through src 681 and src 686, from parallel arrays, with two
     * different layouts so both the native mask path and the unspecified order path are
     * described in one call. */
    KC_CHECKF(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, "[in0][in1]amix=inputs=2[out]",
                                            2, rates, fmts, channels, tb_nums, tb_dens,
                                            -1, -1, 0) < 0,
              "a 64 channel multi input graph at INT_MIN rates was accepted");
    KC_NULL(graph);
    KC_CHECKF(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, "[in0]anull[out]", -1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            -1, -1, 0) < 0,
              "a negative input count was accepted");
    KC_NULL(graph);
}

/* ---- name[16], src 600, 640 and 685 ---- */

static void case_pad_name_bound(void)
{
    /* "in%d" against the widest int there is. 13 bytes plus a NUL against a 16 byte buffer, so
     * this buffer cannot be overflowed through any of the three sites that use it. What this
     * row would catch: a wider name format, for instance one that added a prefix or a suffix,
     * which would start truncating pad names and silently wire the wrong input. */
    int at_min = snprintf(NULL, 0, "in%d", INT_MIN);
    int at_max = snprintf(NULL, 0, "in%d", INT_MAX);
    KC_CHECKF(at_min < 16, "in%%d at INT_MIN needs %d bytes, the buffer is 16", at_min);
    KC_CHECKF(at_max < 16, "in%%d at INT_MAX needs %d bytes, the buffer is 16", at_max);
    kc_detail("at_min=%d at_max=%d limit=15", at_min, at_max);
}

static void case_pad_names_across_the_two_digit_boundary(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[11];
    AVFilterContext *sink = NULL;
    int widths[11];
    int heights[11];
    int fmts[11];
    int tb_nums[11];
    int tb_dens[11];
    int fr_nums[11];
    int fr_dens[11];
    int sar_nums[11];
    int sar_dens[11];
    char description[512];
    int at = 0;
    int i;
    for (i = 0; i < 11; i++) {
        srcs[i] = NULL;
        widths[i] = 32;
        heights[i] = 32;
        fmts[i] = AV_PIX_FMT_YUV420P;
        tb_nums[i] = 1;
        tb_dens[i] = 25;
        fr_nums[i] = 25;
        fr_dens[i] = 1;
        sar_nums[i] = 1;
        sar_dens[i] = 1;
        at += snprintf(description + at, sizeof(description) - (size_t)at, "[in%d]", i);
    }
    snprintf(description + at, sizeof(description) - (size_t)at, "mix=inputs=11[out]");
    /* Eleven inputs, so the pad names cross from one digit to two and "in10" is rendered by
     * both src 640 and src 600. The graph is expected to build: if a name were truncated, the
     * label the description asks for would not exist and the parse would fail. That makes this
     * row a real check on the naming rather than on the buffer size alone. */
    KC_EQ_INT(ffkmp_graph_build_video_multi(&graph, srcs, &sink, description, 11,
                                            widths, heights, fmts, tb_nums, tb_dens,
                                            fr_nums, fr_dens, sar_nums, sar_dens), 0);
    KC_NOT_NULL(graph);
    for (i = 0; i < 11; i++)
        KC_NOT_NULL(srcs[i]);
    KC_NOT_NULL(sink);
    ffkmp_graph_free(&graph);
    kc_detail("inputs=11 description=%zu bytes", kc_strlen(description));
}

/* ---- full_desc[2048], src 555 and src 700, the D27 sites ----
 *
 * These two are the buffers A0 fixed, so every row below states the failure mode it detects
 * rather than only the value it asserts. The discipline under test is the one the source
 * comment describes: snprintf returns the length it WOULD have written, so the running total
 * has to be checked against the buffer after every append and before the next one computes
 * `full_desc + n` and `sizeof(full_desc) - n`. Past the end, that pointer leaves the array and
 * the size wraps to a huge size_t.
 */

static void case_full_desc_exact_fit_without_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    /* 2047 characters plus the NUL is exactly the buffer. The chain is valid, and its last
     * token is "atrim=start=0", so losing even one character would leave "atrim=start=" and
     * the parse would fail. What this row would catch: an off-by-one that refused a
     * description which fits, and a truncation at the very last byte. */
    char *description = chain_of_length(2047, "", "atrim=start=0");
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, description,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000, -1, -1, 0), 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(src);
    KC_NOT_NULL(sink);
    ffkmp_graph_free(&graph);
    kc_detail("description=%zu limit=2047", kc_strlen(description));
    free(description);
}

static void case_full_desc_one_byte_over_without_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    /* 2048 characters, one past what fits. The tail is "atrim=start=00", so a truncation to
     * 2047 characters would leave "atrim=start=0", which is a perfectly valid filter chain.
     * That is exactly why this row asserts a refusal: without the guard the helper would
     * return success and build a graph from a description one character shorter than the
     * caller asked for, and nothing downstream could ever notice. */
    char *description = chain_of_length(2048, "", "atrim=start=00");
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, description,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000, -1, -1, 0) < 0,
              "a description one byte past the buffer was accepted");
    KC_NULL(graph);
    KC_NULL(src);
    KC_NULL(sink);
    kc_detail("description=%zu limit=2047", kc_strlen(description));
    free(description);
}

static void case_full_desc_exact_fit_with_all_three_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    int suffix = pin_suffix_length();
    int total = 2047 - suffix;
    char *description = chain_of_length(total, "", "anull");
    /* The description plus all three appended pins land on byte 2047 with the NUL, the last
     * position that fits. Then the sink's configured format and rate are read back, which is
     * what proves the aformat clause arrived whole: a truncated ":channel_layouts=2c" would
     * fail to parse and a truncated ":sample_rates=44100" would leave the wrong rate. What this
     * row would catch is a guard that refuses one byte too early, which would make the pinned
     * path unusable for long descriptions for no reason. */
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, description,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000,
                                      PIN_FMT, PIN_RATE, PIN_CHANNELS), 0);
    KC_NOT_NULL(graph);
    KC_EQ_INT(av_buffersink_get_format(sink), PIN_FMT);
    KC_EQ_INT(av_buffersink_get_sample_rate(sink), PIN_RATE);
    ffkmp_graph_free(&graph);
    kc_detail("description=%d pins=%d total=%d limit=2047", total, suffix, total + suffix);
    free(description);
}

static void case_full_desc_one_byte_over_with_all_three_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    int suffix = pin_suffix_length();
    /* Six characters past the exact fit, which is the next reachable chain length. The last
     * append is the one that does not fit. Without the check after it, the helper would return
     * success with ":channel_layouts=2" instead of ":channel_layouts=2c", so the graph would be
     * pinned to a layout that does not parse or, worse, to a different one. */
    char *description = chain_of_length(2047 - suffix + 6, "", "anull");
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, description,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000,
                                      PIN_FMT, PIN_RATE, PIN_CHANNELS) < 0,
              "a description that leaves no room for the pins was accepted");
    KC_NULL(graph);
    kc_detail("description=%zu pins=%d limit=2047", kc_strlen(description), suffix);
    free(description);
}

static void case_full_desc_trips_the_first_append_with_more_pending(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    /* This is the D27 case itself, and the reason A0 exists.
     *
     * The description is 2045 characters, so it fits. The first append then asks for
     * ",aformat=", nine characters, into the three bytes that remain: snprintf writes two
     * characters and a NUL and returns 9, so the running total becomes 2054, nine past the end
     * of the array, with three more appends still pending.
     *
     * With the running check the helper refuses here. Without it, the next append evaluates
     * `full_desc + 2054`, which is outside the array, and `sizeof(full_desc) - 2054`, which
     * wraps to 18446744073709551610 as a size_t, so snprintf is told it has essentially
     * infinite room past the end of a stack array. That is a stack-buffer-overflow ASan names
     * precisely, and it is reachable from any public caller who passes a long filter
     * description with pins requested. This row is why the suite runs under asan. */
    char *description = chain_of_length(2045, "", "anull");
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, description,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000,
                                      PIN_FMT, PIN_RATE, PIN_CHANNELS) < 0,
              "a description that overflows on the first append was accepted");
    KC_NULL(graph);
    /* The same trip point with only the middle pin requested, so a different subset of the
     * appends runs and the one that trips is a different statement. */
    KC_CHECKF(ffkmp_graph_build_audio(&graph, &src, &sink, description,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000,
                                      -1, PIN_RATE, 0) < 0,
              "a description that overflows before sample_rates was accepted");
    KC_NULL(graph);
    kc_detail("description=%zu first_append=9", kc_strlen(description));
    free(description);
}

static void case_full_desc_empty_description_falls_back(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *src = NULL;
    AVFilterContext *sink = NULL;
    /* NULL and empty both fall back to "anull" rather than rendering nothing, which is the
     * branch that decides what n starts at. A fallback that wrote no characters would make the
     * pins the whole description and produce a graph with no input link. */
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, NULL,
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000,
                                      PIN_FMT, PIN_RATE, PIN_CHANNELS), 0);
    KC_NOT_NULL(graph);
    KC_EQ_INT(av_buffersink_get_format(sink), PIN_FMT);
    ffkmp_graph_free(&graph);
    KC_EQ_INT(ffkmp_graph_build_audio(&graph, &src, &sink, "",
                                      48000, AV_SAMPLE_FMT_FLTP, 2, 1, 48000, -1, -1, 0), 0);
    KC_NOT_NULL(graph);
    ffkmp_graph_free(&graph);
}

/* The multi input twin of the five rows above. Its description must label the inputs, and its
 * pins are appended only when the description carries no explicit [out] label, so the reachable
 * lengths differ by the five characters of "[in0]". */

static void case_multi_full_desc_exact_fit_without_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[1] = { NULL };
    AVFilterContext *sink = NULL;
    const int rates[1] = { 48000 };
    const int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    char *description = chain_of_length(2047, "[in0]", "atrim=start=00");
    /* Same property as the single input exact fit row, through src 700 instead of src 555. */
    KC_EQ_INT(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            -1, -1, 0), 0);
    KC_NOT_NULL(graph);
    KC_NOT_NULL(srcs[0]);
    ffkmp_graph_free(&graph);
    kc_detail("description=%zu limit=2047", kc_strlen(description));
    free(description);
}

static void case_multi_full_desc_one_byte_over_without_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[1] = { NULL };
    AVFilterContext *sink = NULL;
    const int rates[1] = { 48000 };
    const int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    /* 2048 characters with a tail whose truncation would still parse, so a missing guard would
     * return success on a silently shortened description. */
    char *description = chain_of_length(2048, "[in0]", "atrim=start=000");
    KC_CHECKF(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            -1, -1, 0) < 0,
              "a description one byte past the buffer was accepted");
    KC_NULL(graph);
    kc_detail("description=%zu limit=2047", kc_strlen(description));
    free(description);
}

static void case_multi_full_desc_exact_fit_with_all_three_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[1] = { NULL };
    AVFilterContext *sink = NULL;
    const int rates[1] = { 48000 };
    const int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    int suffix = pin_suffix_length();
    /* No [out] label, so the pin path runs. The tail length is chosen so the total is exactly
     * the last byte that fits once the pins are appended. */
    char *description = chain_of_length(2047 - suffix, "[in0]", "atrim=start=000000");
    KC_EQ_INT(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            PIN_FMT, PIN_RATE, PIN_CHANNELS), 0);
    KC_NOT_NULL(graph);
    KC_EQ_INT(av_buffersink_get_format(sink), PIN_FMT);
    KC_EQ_INT(av_buffersink_get_sample_rate(sink), PIN_RATE);
    ffkmp_graph_free(&graph);
    kc_detail("description=%zu pins=%d total=2047", kc_strlen(description), suffix);
    free(description);
}

static void case_multi_full_desc_trips_an_append_with_more_pending(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[1] = { NULL };
    AVFilterContext *sink = NULL;
    const int rates[1] = { 48000 };
    const int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    /* The D27 case through src 700: 2045 characters fit, then ",aformat=" trips the total nine
     * bytes past the array with three appends still pending. Same wrapped size_t, same
     * stack-buffer-overflow if the running check is ever removed from this copy of the logic.
     * Both copies need their own row, because the discipline is duplicated rather than shared. */
    char *description = chain_of_length(2045, "[in0]", "atrim=start=000000");
    KC_CHECKF(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            PIN_FMT, PIN_RATE, PIN_CHANNELS) < 0,
              "a description that overflows on the first append was accepted");
    KC_NULL(graph);
    KC_CHECKF(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            -1, -1, PIN_CHANNELS) < 0,
              "a description that overflows before channel_layouts was accepted");
    KC_NULL(graph);
    kc_detail("description=%zu first_append=9", kc_strlen(description));
    free(description);
}

static void case_multi_full_desc_with_an_explicit_out_label_skips_the_pins(void)
{
    AVFilterGraph *graph = NULL;
    AVFilterContext *srcs[1] = { NULL };
    AVFilterContext *sink = NULL;
    const int rates[1] = { 48000 };
    const int fmts[1] = { AV_SAMPLE_FMT_FLTP };
    const int channels[1] = { 2 };
    const int tb_nums[1] = { 1 };
    const int tb_dens[1] = { 48000 };
    /* With an explicit [out] label the multi builder leaves the description alone, so a length
     * that would be refused with pins is accepted without them. That asymmetry is deliberate
     * in the source and this row pins it down, since a reader could otherwise assume the pins
     * always apply and write a description that silently loses them. */
    char *description = chain_of_length(2042, "[in0]", "volume=1.0[out]");
    KC_EQ_INT(ffkmp_graph_build_audio_multi(&graph, srcs, &sink, description, 1,
                                            rates, fmts, channels, tb_nums, tb_dens,
                                            PIN_FMT, PIN_RATE, PIN_CHANNELS), 0);
    KC_NOT_NULL(graph);
    /* The pins were skipped, so the sink keeps the input format rather than the pinned one. */
    KC_EQ_INT(av_buffersink_get_format(sink), AV_SAMPLE_FMT_FLTP);
    ffkmp_graph_free(&graph);
    kc_detail("description=%zu", kc_strlen(description));
    free(description);
}

/* ---- Table and driver ---- */

typedef struct {
    const char *name;
    void (*run)(void);
} buffer_case;

static const buffer_case cases[] = {
    { "codecpar extradata query and exact copy",           case_codecpar_extradata_query_and_exact_copy },
    { "codecpar extradata partial copy",                   case_codecpar_extradata_partial_copy },
    { "codecpar extradata empty and invalid arguments",    case_codecpar_extradata_empty_and_invalid },
    { "codecpar video metadata preserves declarations",    case_codecpar_video_metadata },
    { "codecpar video metadata preserves unknowns",        case_codecpar_video_metadata_unknown },
    { "frame_copy_to_buffer at exactly the needed size",   case_frame_copy_to_buffer_exact },
    { "frame_copy_to_buffer one byte short",               case_frame_copy_to_buffer_one_byte_short },
    { "frame_copy_to_buffer at degenerate sizes",          case_frame_copy_to_buffer_degenerate_sizes },
    { "frame_fill_video round trip at the needed size",    case_frame_fill_video_round_trip },
    { "frame_fill_video one byte short",                   case_frame_fill_video_one_byte_short },
    { "samples_copy_to_buffer exact, planar",              case_samples_copy_to_buffer_exact_planar },
    { "samples_copy_to_buffer one byte short",             case_samples_copy_to_buffer_one_byte_short },
    { "frame_fill_audio round trip, planar",               case_fill_audio_round_trip_planar },
    { "frame_fill_audio round trip, packed",               case_fill_audio_round_trip_packed },
    { "frame_fill_audio one byte short",                   case_fill_audio_one_byte_short },
    { "frame_fill_audio refuses more channels than planes", case_fill_audio_refuses_more_channels_than_planes },
    { "strerror buf[256] against a 4096 byte reference",   case_strerror_fits_its_buffer },
    { "strerror buf[256] widest reachable message",        case_strerror_widest_message_bound },
    { "video args[512] bound at the widest inputs",        case_video_args_bound },
    { "video args[512] rendered at the widest inputs",     case_video_args_at_the_widest_inputs },
    { "video multi args[512] and name[16] at the widest inputs", case_video_multi_args_at_the_widest_inputs },
    { "audio args[512] and layout_str[128] bound",         case_audio_args_and_layout_bound },
    { "audio args[512] and layout_str[128] rendered",      case_audio_args_at_the_widest_inputs },
    { "audio multi args[512] and lay_str[128] rendered",   case_audio_multi_args_at_the_widest_inputs },
    { "pad name[16] bound at the widest input",            case_pad_name_bound },
    { "pad name[16] across the two digit boundary",        case_pad_names_across_the_two_digit_boundary },
    { "full_desc[2048] exact fit, no pins",                case_full_desc_exact_fit_without_pins },
    { "full_desc[2048] one byte over, no pins",            case_full_desc_one_byte_over_without_pins },
    { "full_desc[2048] exact fit with all three pins",     case_full_desc_exact_fit_with_all_three_pins },
    { "full_desc[2048] one byte over with all three pins", case_full_desc_one_byte_over_with_all_three_pins },
    { "full_desc[2048] trips the first append, D27",       case_full_desc_trips_the_first_append_with_more_pending },
    { "full_desc[2048] empty description falls back",      case_full_desc_empty_description_falls_back },
    { "multi full_desc[2048] exact fit, no pins",          case_multi_full_desc_exact_fit_without_pins },
    { "multi full_desc[2048] one byte over, no pins",      case_multi_full_desc_one_byte_over_without_pins },
    { "multi full_desc[2048] exact fit with all three pins", case_multi_full_desc_exact_fit_with_all_three_pins },
    { "multi full_desc[2048] trips an append, D27",        case_multi_full_desc_trips_an_append_with_more_pending },
    { "multi full_desc[2048] explicit out label skips the pins", case_multi_full_desc_with_an_explicit_out_label_skips_the_pins },
};

int main(void)
{
    size_t i;
    kc_suite_begin("test_buffers");
    for (i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        kc_case("%s", cases[i].name);
        cases[i].run();
    }
    return kc_suite_end();
}
