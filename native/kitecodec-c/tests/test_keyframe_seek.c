/* A backward keyframe seek lands on the last keyframe that shows at or before its target (#155).
 *
 * FFmpeg finds the keyframe by when it decodes. In an open group of pictures the B-frames after a
 * keyframe in decode order show before it, so MP4's reader took a keyframe that shows after the
 * target, and MPEG-TS, which seeks by byte position, landed among pictures whose first keyframe
 * showed late. The seek now looks at the keyframe it lands on and holds the packets it read for
 * ffkmp_fmt_read_frame to hand out. Each case writes ten fps MPEG-4 part 2 video with two B-frames
 * and a keyframe every eight pictures, which FFmpeg's encoder writes as an open group of pictures,
 * twice over: stream 1 carries the same packets as stream 0, so that a case can turn one of two
 * streams on or off around a seek and compare what each hands out.
 */

#include "harness.h"

#include "kc_test_seams.h"
#include "kitecodec_helpers.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/frame.h>
#include <libavutil/mathematics.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define FRAMES 60
#define GOP 8

static char mp4_path[512];
static char ts_path[512];

static void remove_fixtures(void)
{
    remove(mp4_path);
    remove(ts_path);
}

/* Writes the fixture into path through the muxer named format. Returns 0, or -1 when this FFmpeg
 * cannot write it. */
static int write_fixture(const char *path, const char *format)
{
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    AVFormatContext *oc = NULL;
    AVCodecContext *enc = NULL;
    AVFrame *frame = NULL;
    AVPacket *pkt = NULL;
    int ok = -1;
    if (!codec || avformat_alloc_output_context2(&oc, NULL, format, path) < 0) return -1;
    AVStream *st = avformat_new_stream(oc, NULL);
    AVStream *twin = avformat_new_stream(oc, NULL);
    enc = avcodec_alloc_context3(codec);
    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!st || !twin || !enc || !frame || !pkt) goto done;
    enc->width = 64;
    enc->height = 48;
    enc->pix_fmt = AV_PIX_FMT_YUV420P;
    enc->time_base = (AVRational){ 1, 10 };
    enc->framerate = (AVRational){ 10, 1 };
    enc->gop_size = GOP;
    enc->max_b_frames = 2;
    enc->bit_rate = 400000;
    if (oc->oformat->flags & AVFMT_GLOBALHEADER) enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if (avcodec_open2(enc, codec, NULL) < 0) goto done;
    if (avcodec_parameters_from_context(st->codecpar, enc) < 0) goto done;
    if (avcodec_parameters_from_context(twin->codecpar, enc) < 0) goto done;
    st->time_base = enc->time_base;
    twin->time_base = enc->time_base;
    if (avio_open(&oc->pb, path, AVIO_FLAG_WRITE) < 0) goto done;
    if (avformat_write_header(oc, NULL) < 0) goto done;
    frame->format = enc->pix_fmt;
    frame->width = enc->width;
    frame->height = enc->height;
    if (av_frame_get_buffer(frame, 0) < 0) goto done;
    for (int i = 0; i <= FRAMES; i++) {
        if (i < FRAMES) {
            if (av_frame_make_writable(frame) < 0) goto done;
            for (int y = 0; y < frame->height; y++)
                for (int x = 0; x < frame->width; x++)
                    frame->data[0][y * frame->linesize[0] + x] = (uint8_t)(x * 3 + y * 2 + i * 7);
            for (int p = 1; p < 3; p++)
                for (int y = 0; y < frame->height / 2; y++)
                    memset(frame->data[p] + y * frame->linesize[p], 128, (size_t)frame->width / 2);
            frame->pts = i;
        }
        if (avcodec_send_frame(enc, i < FRAMES ? frame : NULL) < 0) goto done;
        while (avcodec_receive_packet(enc, pkt) == 0) {
            AVPacket *copy = av_packet_clone(pkt);
            if (!copy) goto done;
            av_packet_rescale_ts(copy, enc->time_base, twin->time_base);
            copy->stream_index = twin->index;
            av_packet_rescale_ts(pkt, enc->time_base, st->time_base);
            pkt->stream_index = st->index;
            int written = av_interleaved_write_frame(oc, pkt);
            if (written >= 0) written = av_interleaved_write_frame(oc, copy);
            av_packet_free(&copy);
            if (written < 0) goto done;
        }
    }
    if (av_write_trailer(oc) < 0) goto done;
    ok = 0;
done:
    av_packet_free(&pkt);
    av_frame_free(&frame);
    avcodec_free_context(&enc);
    if (oc && oc->pb) avio_closep(&oc->pb);
    avformat_free_context(oc);
    return ok;
}

/* A picture as read: its stream, when it shows, in microseconds from the start of the input, and
 * whether it is a keyframe. */
typedef struct {
    int stream;
    int64_t shows;
    int key;
} picture;

static int open_fixture(const char *path, kc_fmt_ctx **ctx)
{
    *ctx = NULL;
    if (ffkmp_fmt_open_input(ctx, path) < 0) return -1;
    if (ffkmp_fmt_find_stream_info(*ctx) < 0) {
        ffkmp_fmt_close_input(ctx);
        return -1;
    }
    return 0;
}

/* The next picture ctx hands out of stream 0, or of either stream when stream is -1. Returns 0 at
 * the end of the input. */
static int next_picture_of(kc_fmt_ctx *ctx, int stream, picture *out)
{
    AVFormatContext *c = (AVFormatContext *)ctx;
    AVPacket *p = av_packet_alloc();
    int found = 0;
    while (!found && ffkmp_fmt_read_frame(ctx, p) >= 0) {
        AVStream *st = c->streams[p->stream_index];
        if (stream < 0 || p->stream_index == stream) {
            out->stream = p->stream_index;
            out->shows = av_rescale_q(p->pts, st->time_base, AV_TIME_BASE_Q) - c->start_time;
            out->key = (p->flags & AV_PKT_FLAG_KEY) != 0;
            found = 1;
        }
        av_packet_unref(p);
    }
    av_packet_free(&p);
    return found;
}

static int next_picture(kc_fmt_ctx *ctx, picture *out)
{
    return next_picture_of(ctx, 0, out);
}

/* Every picture of stream 0 that ctx hands out from where it stands, into out. Returns how many. */
static int rest_of(kc_fmt_ctx *ctx, picture *out, int capacity)
{
    int n = 0;
    while (n < capacity && next_picture(ctx, &out[n])) n++;
    return n;
}

/* Every picture of either stream that ctx hands out from where it stands, split by stream into
 * zero and one. Returns how many of stream 0's; *ones gets how many of stream 1's. */
static int rest_of_both(kc_fmt_ctx *ctx, picture *zero, picture *one, int capacity, int *ones)
{
    picture got;
    int n = 0;
    *ones = 0;
    while (next_picture_of(ctx, -1, &got)) {
        if (got.stream == 0 && n < capacity) zero[n++] = got;
        if (got.stream == 1 && *ones < capacity) one[(*ones)++] = got;
    }
    return n;
}

/* Whether a and b hold the same pictures in the same order. */
static int same_pictures(const picture *a, int na, const picture *b, int nb)
{
    if (na != nb) return 0;
    for (int i = 0; i < na; i++)
        if (a[i].shows != b[i].shows || a[i].key != b[i].key) return 0;
    return 1;
}

/* The last keyframe in pictures that shows at or before target, or the first keyframe. */
static int64_t keyframe_for(const picture *pictures, int count, int64_t target)
{
    int64_t found = INT64_MIN, first = INT64_MIN;
    for (int i = 0; i < count; i++) {
        if (!pictures[i].key) continue;
        if (first == INT64_MIN || pictures[i].shows < first) first = pictures[i].shows;
        if (pictures[i].shows <= target && pictures[i].shows > found) found = pictures[i].shows;
    }
    return found == INT64_MIN ? first : found;
}

/* Seeks the fixture at path backward to every 50 ms through one entry point, and checks that each
 * seek hands out the right keyframe first and every picture after it once. */
static void check_every_seek(const char *path, const char *name, int windowed)
{
    kc_fmt_ctx *ctx = NULL;
    picture whole[FRAMES + 8], after[FRAMES + 8];
    kc_case("a backward seek of %s through %s lands on the last keyframe that shows at or before its target",
            name, windowed ? "ffkmp_fmt_seek_file" : "ffkmp_fmt_seek_micros");
    KC_EQ_INT(open_fixture(path, &ctx), 0);
    if (!ctx) return;
    AVFormatContext *c = (AVFormatContext *)ctx;
    int count = rest_of(ctx, whole, FRAMES + 8);
    KC_EQ_INT(count, FRAMES);
    int late_before = 0;
    for (int i = 0; i + 1 < count; i++) late_before += whole[i].key && whole[i + 1].shows < whole[i].shows;
    KC_CHECKF(late_before > 0, "%s has no keyframe whose B-frames follow it, so it proves nothing", name);
    for (int64_t target = 0; target < (FRAMES - 1) * 100000; target += 50000) {
        int64_t at = c->start_time + target;
        int rc = windowed ? ffkmp_fmt_seek_file(ctx, -1, INT64_MIN, at, at, ffkmp_avseek_flag_backward())
                          : ffkmp_fmt_seek_micros(ctx, -1, at);
        KC_EQ_INT(rc >= 0, 1);
        int n = rest_of(ctx, after, FRAMES + 8);
        KC_CHECKF(n > 0, "a seek to %lld us handed out no picture", (long long)target);
        if (n == 0) break;
        int64_t wanted = keyframe_for(whole, count, target);
        KC_CHECKF(after[0].key && after[0].shows == wanted,
                  "a seek to %lld us landed on %s at %lld us, not the keyframe at %lld us",
                  (long long)target, after[0].key ? "a keyframe" : "a picture", (long long)after[0].shows,
                  (long long)wanted);
        int from = 0;
        while (from < count && !(whole[from].key && whole[from].shows == after[0].shows)) from++;
        KC_CHECKF(n == count - from, "a seek to %lld us handed out %d pictures of %d", (long long)target, n,
                  count - from);
        for (int i = 0; i < n && from + i < count; i++) {
            if (after[i].shows != whole[from + i].shows) {
                KC_FAIL("a seek to %lld us handed out the picture at %lld us where %lld us comes",
                        (long long)target, (long long)after[i].shows, (long long)whole[from + i].shows);
                break;
            }
        }
    }
    KC_EQ_INT(kc_test_held_contexts(), 0);
    ffkmp_fmt_close_input(&ctx);
}

static void case_every_seek_lands_in_time(void)
{
    check_every_seek(mp4_path, "MP4", 0);
    check_every_seek(mp4_path, "MP4", 1);
    check_every_seek(ts_path, "MPEG-TS", 0);
    check_every_seek(ts_path, "MPEG-TS", 1);
}

/* The packets a seek holds belong to that seek. A second one before any read hands out its own. */
static void case_a_second_seek_replaces_what_the_first_held(void)
{
    kc_fmt_ctx *ctx = NULL;
    picture whole[FRAMES + 8], after[FRAMES + 8];
    kc_case("a seek after a seek, with nothing read between, hands out what the second one landed on");
    KC_EQ_INT(open_fixture(mp4_path, &ctx), 0);
    if (!ctx) return;
    AVFormatContext *c = (AVFormatContext *)ctx;
    int count = rest_of(ctx, whole, FRAMES + 8);
    KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, c->start_time + 4500000) >= 0, 1);
    KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, c->start_time + 1500000) >= 0, 1);
    KC_EQ_INT(kc_test_held_contexts(), 1);
    int n = rest_of(ctx, after, FRAMES + 8);
    KC_CHECK(n > 0);
    if (n > 0) KC_EQ_I64(after[0].shows, keyframe_for(whole, count, 1500000));
    int from = 0;
    while (from < count && whole[from].shows != after[0].shows) from++;
    KC_EQ_INT(n, count - from);
    ffkmp_fmt_close_input(&ctx);
}

/* Two inputs seeking at once each hand out their own packets. */
static void case_two_inputs_hold_their_own_packets(void)
{
    kc_fmt_ctx *a = NULL, *b = NULL;
    picture whole[FRAMES + 8], from_a, from_b;
    kc_case("two inputs that seek before either reads each hand out their own landing");
    KC_EQ_INT(open_fixture(mp4_path, &a), 0);
    KC_EQ_INT(open_fixture(mp4_path, &b), 0);
    if (!a || !b) { ffkmp_fmt_close_input(&a); ffkmp_fmt_close_input(&b); return; }
    int count = rest_of(a, whole, FRAMES + 8);
    KC_EQ_INT(ffkmp_fmt_seek_micros(a, -1, ((AVFormatContext *)a)->start_time + 2500000) >= 0, 1);
    KC_EQ_INT(ffkmp_fmt_seek_micros(b, -1, ((AVFormatContext *)b)->start_time + 4500000) >= 0, 1);
    KC_EQ_INT(next_picture(b, &from_b), 1);
    KC_EQ_INT(next_picture(a, &from_a), 1);
    KC_EQ_I64(from_a.shows, keyframe_for(whole, count, 2500000));
    KC_EQ_I64(from_b.shows, keyframe_for(whole, count, 4500000));
    ffkmp_fmt_close_input(&a);
    ffkmp_fmt_close_input(&b);
}

/* A close takes the packets a seek still holds with it. */
static void case_a_close_frees_what_a_seek_holds(void)
{
    kc_alloc_counts before;
    kc_fmt_ctx *ctx = NULL;
    kc_case("closing an input that holds the packets of a seek frees them");
    KC_EQ_INT(kc_test_held_contexts(), 0);
    /* Once first, so FFmpeg's one-time tables are not counted against the close. */
    for (int round = 0; round < 2; round++) {
        if (round == 1) kc_alloc_snapshot(&before);
        KC_EQ_INT(open_fixture(ts_path, &ctx), 0);
        if (!ctx) return;
        KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, ((AVFormatContext *)ctx)->start_time + 3500000) >= 0, 1);
        KC_EQ_INT(kc_test_held_contexts(), 1);
        ffkmp_fmt_close_input(&ctx);
        KC_NULL(ctx);
        KC_EQ_INT(kc_test_held_contexts(), 0);
    }
    KC_ALLOC_BALANCED(&before);
}

/* The selection a seek's held packets were read with can change before they are handed out. Each
 * check seeks to every 50 ms, once with both streams on throughout, which is what the other seek
 * is held to, and once changing stream 1 at one point around the seek. FFmpeg lands stream 1 where
 * it lands it, which for MP4 is a group of pictures before stream 0, so stream 1 is compared with
 * itself rather than with stream 0. */
enum { TURN_ON_BEFORE_READING, TURN_OFF_BEFORE_READING, TURN_ON_AFTER_A_READ };

static void check_a_changed_selection(const char *path, const char *name, int change)
{
    kc_fmt_ctx *ctx = NULL;
    picture whole[FRAMES + 8], zero[FRAMES + 8], one[FRAMES + 8], zero_on[FRAMES + 8], one_on[FRAMES + 8];
    if (change == TURN_ON_BEFORE_READING)
        kc_case("a stream of %s turned on after a seek starts where a seek with it on lands it", name);
    else if (change == TURN_OFF_BEFORE_READING)
        kc_case("a stream of %s turned off after a seek hands out none of what the seek read", name);
    else
        kc_case("a stream of %s turned on after a read joins where the input stands and repeats nothing", name);
    KC_EQ_INT(open_fixture(path, &ctx), 0);
    if (!ctx) return;
    AVFormatContext *c = (AVFormatContext *)ctx;
    int count = rest_of(ctx, whole, FRAMES + 8);
    KC_EQ_INT(count, FRAMES);
    int joined = 0;
    for (int64_t target = 0; target < (FRAMES - 1) * 100000; target += 50000) {
        int64_t at = c->start_time + target;
        KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, at) >= 0, 1);
        int ones_on = 0;
        int zeros_on = rest_of_both(ctx, zero_on, one_on, FRAMES + 8, &ones_on);

        if (change == TURN_OFF_BEFORE_READING) ffkmp_stream_discard_none(c->streams[1]);
        else ffkmp_stream_discard_all(c->streams[1]);
        KC_EQ_INT(ffkmp_fmt_seek_micros(ctx, -1, at) >= 0, 1);
        int skipped = change == TURN_ON_AFTER_A_READ ? next_picture(ctx, &zero[0]) : 0;
        if (change == TURN_OFF_BEFORE_READING) ffkmp_stream_discard_all(c->streams[1]);
        else ffkmp_stream_discard_none(c->streams[1]);
        int ones = 0;
        int zeros = rest_of_both(ctx, zero + skipped, one, FRAMES + 8 - skipped, &ones) + skipped;

        int64_t wanted = keyframe_for(whole, count, target);
        KC_CHECKF(zeros > 0 && zero[0].key && zero[0].shows == wanted,
                  "a seek to %lld us landed stream 0 on %lld us, not the keyframe at %lld us", (long long)target,
                  zeros > 0 ? (long long)zero[0].shows : -1LL, (long long)wanted);
        if (!same_pictures(zero, zeros, zero_on, zeros_on)) {
            KC_FAIL("a seek to %lld us handed out %d pictures of stream 0 from %lld us, and %d with stream 1 on from %lld us",
                    (long long)target, zeros, zeros > 0 ? (long long)zero[0].shows : -1LL, zeros_on,
                    zeros_on > 0 ? (long long)zero_on[0].shows : -1LL);
            break;
        }
        if (change == TURN_ON_BEFORE_READING && !same_pictures(one, ones, one_on, ones_on)) {
            KC_FAIL("a seek to %lld us handed out %d pictures of stream 1 from %lld us, and %d with it on from %lld us",
                    (long long)target, ones, ones > 0 ? (long long)one[0].shows : -1LL, ones_on,
                    ones_on > 0 ? (long long)one_on[0].shows : -1LL);
            break;
        }
        if (change == TURN_OFF_BEFORE_READING && ones != 0) {
            KC_FAIL("a seek to %lld us handed out %d pictures of stream 1, turned off before the first read",
                    (long long)target, ones);
            break;
        }
        /* What a seek read past is behind the input, so near the end stream 1 can join with nothing. */
        if (change == TURN_ON_AFTER_A_READ &&
            (ones > ones_on || !same_pictures(one, ones, one_on + ones_on - ones, ones))) {
            KC_FAIL("a seek to %lld us handed out %d pictures of stream 1 after a read, not the last of the %d with it on",
                    (long long)target, ones, ones_on);
            break;
        }
        joined += ones > 0;
    }
    if (change == TURN_ON_AFTER_A_READ) KC_CHECKF(joined > 0, "stream 1 of %s never joined after a read", name);
    ffkmp_stream_discard_none(c->streams[1]);
    KC_EQ_INT(kc_test_held_contexts(), 0);
    ffkmp_fmt_close_input(&ctx);
}

static void case_a_selection_changed_after_a_seek(void)
{
    for (int change = TURN_ON_BEFORE_READING; change <= TURN_ON_AFTER_A_READ; change++) {
        check_a_changed_selection(mp4_path, "MP4", change);
        check_a_changed_selection(ts_path, "MPEG-TS", change);
    }
}

int main(void)
{
    const char *tmp = getenv("TMPDIR");
    kc_suite_begin("test_keyframe_seek");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";
    const char *slash = tmp[strlen(tmp) - 1] == '/' ? "" : "/";
    snprintf(mp4_path, sizeof(mp4_path), "%s%skc_keyframe_seek_%ld.mp4", tmp, slash, (long)getpid());
    snprintf(ts_path, sizeof(ts_path), "%s%skc_keyframe_seek_%ld.ts", tmp, slash, (long)getpid());
    atexit(remove_fixtures);
    if (write_fixture(mp4_path, "mp4") < 0 || write_fixture(ts_path, "mpegts") < 0) {
        kc_case("the fixtures can be written");
        KC_FAIL("this FFmpeg cannot write MPEG-4 part 2 video into MP4 and MPEG-TS");
        return kc_suite_end();
    }

    case_every_seek_lands_in_time();
    case_a_second_seek_replaces_what_the_first_held();
    case_two_inputs_hold_their_own_packets();
    case_a_close_frees_what_a_seek_holds();
    case_a_selection_changed_after_a_seek();

    return kc_suite_end();
}
