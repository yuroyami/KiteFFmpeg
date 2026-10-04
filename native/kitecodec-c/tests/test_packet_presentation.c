/* What a packet shows (#153), and how long copied audio lasts (#154).
 *
 * A copy starts its output where its media starts to show, so it reads two marks a demuxer leaves
 * on a packet: the samples a decoder drops from the start of what it decodes to, which hide an
 * encoder's priming, and the discard flag on the samples a demuxer reads before an MP4 edit list
 * starts. Each case builds a packet by hand and reads the mark back. Copied audio then counts its
 * place in samples, so it asks how many samples a packet holds and sets the duration that follows.
 */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <libavcodec/codec_par.h>
#include <libavcodec/packet.h>
#include <libavutil/channel_layout.h>
#include <libavutil/intreadwrite.h>

static AVPacket *with_skip(uint32_t start, uint32_t end, size_t size)
{
    AVPacket *p = ffkmp_packet_alloc();
    uint8_t *side;
    KC_NOT_NULL(p);
    side = av_packet_new_side_data(p, AV_PKT_DATA_SKIP_SAMPLES, size);
    KC_NOT_NULL(side);
    if (size >= 4) AV_WL32(side, start);
    if (size >= 8) AV_WL32(side + 4, end);
    return p;
}

static void case_the_skip_at_the_start_is_read(void)
{
    AVPacket *p = with_skip(1024, 0, 10);
    kc_case("a packet marked to skip 1024 samples at its start reads 1024");
    KC_EQ_I64(ffkmp_packet_skip_start(p), 1024);
    ffkmp_packet_free(p);
}

static void case_the_skip_at_the_end_is_not_the_start(void)
{
    AVPacket *p = with_skip(0, 512, 10);
    kc_case("a packet marked to skip only at its end reads no skip at its start");
    KC_EQ_I64(ffkmp_packet_skip_start(p), 0);
    ffkmp_packet_free(p);
}

static void case_a_count_past_a_signed_int_stays_positive(void)
{
    AVPacket *p = with_skip(0x80000001u, 0, 10);
    kc_case("a skip count above INT32_MAX reads as the unsigned count it is");
    KC_EQ_I64(ffkmp_packet_skip_start(p), (int64_t)0x80000001u);
    ffkmp_packet_free(p);
}

static void case_no_side_data_and_no_packet_skip_nothing(void)
{
    AVPacket *p = ffkmp_packet_alloc();
    AVPacket *truncated = with_skip(7, 0, 3);
    kc_case("no side data, side data too short to hold a count, and a NULL packet read no skip");
    KC_NOT_NULL(p);
    KC_EQ_I64(ffkmp_packet_skip_start(p), 0);
    KC_EQ_I64(ffkmp_packet_skip_start(truncated), 0);
    KC_EQ_I64(ffkmp_packet_skip_start(NULL), 0);
    ffkmp_packet_free(truncated);
    ffkmp_packet_free(p);
}

static void case_the_discard_flag_is_read(void)
{
    AVPacket *p = ffkmp_packet_alloc();
    kc_case("the discard flag reads 1 only while it is set, and a NULL packet reads 0");
    KC_NOT_NULL(p);
    p->flags = AV_PKT_FLAG_KEY;
    KC_EQ_INT(ffkmp_packet_is_discard(p), 0);
    p->flags |= AV_PKT_FLAG_DISCARD;
    KC_EQ_INT(ffkmp_packet_is_discard(p), 1);
    KC_EQ_INT(ffkmp_packet_is_keyframe(p), 1);
    p->flags = AV_PKT_FLAG_DISCARD;
    KC_EQ_INT(ffkmp_packet_is_discard(p), 1);
    KC_EQ_INT(ffkmp_packet_is_discard(NULL), 0);
    ffkmp_packet_free(p);
}

static AVCodecParameters *audio(enum AVCodecID id, int channels, int frame_size)
{
    AVCodecParameters *par = avcodec_parameters_alloc();
    KC_NOT_NULL(par);
    par->codec_type = AVMEDIA_TYPE_AUDIO;
    par->codec_id = id;
    par->sample_rate = 48000;
    par->frame_size = frame_size;
    av_channel_layout_default(&par->ch_layout, channels);
    return par;
}

static void case_the_samples_a_packet_holds_are_counted(void)
{
    AVCodecParameters *aac = audio(AV_CODEC_ID_AAC, 1, 1024);
    AVCodecParameters *pcm = audio(AV_CODEC_ID_PCM_S16LE, 2, 0);
    AVCodecParameters *mp2 = audio(AV_CODEC_ID_MP2, 2, 0);
    kc_case("AAC holds its frame size, PCM what its bytes carry, and MP2 its fixed 1152");
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(aac, 371), 1024);
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(pcm, 4096), 1024);
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(pcm, 400), 100);
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(mp2, 576), 1152);
    avcodec_parameters_free(&mp2);
    avcodec_parameters_free(&pcm);
    avcodec_parameters_free(&aac);
}

static void case_what_ffmpeg_cannot_count_is_zero(void)
{
    AVCodecParameters *vorbis = audio(AV_CODEC_ID_VORBIS, 2, 0);
    AVCodecParameters *video = avcodec_parameters_alloc();
    kc_case("Vorbis, whose packets vary, a video stream and a NULL argument count no samples");
    KC_NOT_NULL(video);
    video->codec_type = AVMEDIA_TYPE_VIDEO;
    video->codec_id = AV_CODEC_ID_H264;
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(vorbis, 300), 0);
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(video, 300), 0);
    KC_EQ_INT(ffkmp_codecpar_audio_frame_samples(NULL, 300), 0);
    avcodec_parameters_free(&video);
    avcodec_parameters_free(&vorbis);
}

static void case_the_duration_is_set(void)
{
    AVPacket *p = ffkmp_packet_alloc();
    kc_case("a packet's duration reads back as it was set, and a NULL packet is left alone");
    KC_NOT_NULL(p);
    ffkmp_packet_set_duration(p, 1024);
    KC_EQ_I64(ffkmp_packet_duration(p), 1024);
    ffkmp_packet_set_duration(p, 0);
    KC_EQ_I64(ffkmp_packet_duration(p), 0);
    ffkmp_packet_set_duration(NULL, 7);
    ffkmp_packet_free(p);
}

int main(void)
{
    kc_suite_begin("test_packet_presentation");

    case_the_skip_at_the_start_is_read();
    case_the_skip_at_the_end_is_not_the_start();
    case_a_count_past_a_signed_int_stays_positive();
    case_no_side_data_and_no_packet_skip_nothing();
    case_the_discard_flag_is_read();
    case_the_samples_a_packet_holds_are_counted();
    case_what_ffmpeg_cannot_count_is_zero();
    case_the_duration_is_set();

    return kc_suite_end();
}
