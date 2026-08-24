@file:OptIn(KiteCodecLowLevelApi::class)

package io.github.yuroyami.kitecodec

import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_set_dts
import ffmpeg.ffkmp_packet_set_pts
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface a real-time player drives: the packet reader, the decoders it feeds, the timestamps
 * they carry, and what the source says about itself. Media is synthesized through this library's
 * own sink, like the rest of the native tests, so no fixture files are needed.
 *
 * Every case here is a defect that used to be invisible from the outside: a reader that left the
 * demuxer skipping streams after it closed, timestamps that overflowed or were never converted at
 * all, a decoder that could not say whether it was drained or merely hungry, a seek that moved a
 * cursor another object owned, a channel layout reduced to a count, picture geometry computed for
 * an audio frame, and a seekability flag that was simply asserted.
 */
class PlayerSurfaceTest {

    private companion object {
        /** FL, FR, FC, LFE, SL, SR. What `5.1(side)` means, and what a channel count cannot say. */
        const val MASK_5POINT1_SIDE = 0x60FL

        /** FL, FR, FC, LFE, BL, BR. The layout a count of six is guessed into: different speakers. */
        const val MASK_5POINT1_BACK = 0x3FL
    }

    private val tmpFiles = mutableListOf<String>()

    private fun tmp(name: String): String {
        val root = systemTempRoot()
        return "$root/kitecodec-test-$name".also { tmpFiles += it }
    }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    /** One tightly-packed yuv420p frame with per-frame varying luma (not a solid color). */
    private fun yuvFrame(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { i -> ((i + index * 7) % 200 + 20).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private fun videoFrames(count: Int, width: Int = 64, height: Int = 64) =
        (0 until count).asFlow().map { i ->
            Frame.ofVideo(
                bytes = yuvFrame(width, height, i),
                width = width, height = height,
                pixelFormat = PixelFormat.Yuv420p,
                ptsMicros = i * 1_000_000L / 30,
            )
        }

    /** Silent s16 audio, [channels] interleaved, in chunks of [samplesPerFrame]. */
    private fun audioFrames(count: Int, channels: Int, sampleRate: Int, samplesPerFrame: Int = 1024) =
        (0 until count).asFlow().map { i ->
            Frame.ofAudio(
                bytes = ByteArray(samplesPerFrame * channels * 2),
                sampleCount = samplesPerFrame,
                sampleRate = sampleRate,
                channels = channels,
                sampleFormat = SampleFormat.S16,
                ptsMicros = i.toLong() * samplesPerFrame * 1_000_000L / sampleRate,
            )
        }

    private fun videoSpec(width: Int = 64, height: Int = 64) = VideoEncoderSpec(
        codec = CodecId("mpeg4"),  // built into every FFmpeg, no external encoder needed
        width = width, height = height,
        frameRate = Rational(30, 1),
        bitrateBps = 500_000,
    )

    /** A two-stream clip: mpeg4 video plus uncompressed audio, so both streams decode anywhere. */
    private fun writeVideoAndAudio(path: String, frames: Int = 30) {
        MediaSink.open(path).use { sink ->
            val video = sink.addVideoEncoder(videoSpec())
            val audio = sink.addAudioEncoder(
                AudioEncoderSpec(
                    codec = CodecId.PcmS16,
                    sampleRate = 48_000,
                    channels = 1,
                    sampleFormat = SampleFormat.S16,
                )
            )
            runBlocking {
                video.drive(videoFrames(frames))
                audio.drive(audioFrames(frames, channels = 1, sampleRate = 48_000))
            }
        }
    }

    /** MPEG-TS, whose stream time base is always 1/90000: the classic broadcast timescale. */
    private fun writeTransportStream(path: String, frames: Int = 30) {
        MediaSink.open(path, format = "mpegts").use { sink ->
            val video = sink.addVideoEncoder(videoSpec())
            runBlocking { video.drive(videoFrames(frames)) }
        }
    }

    /**
     * Six channels of silence declared as `5.1(side)`, in a container that stores the layout.
     *
     * The layout is set through the encoder's own `ch_layout` option, because the default for a
     * count of six is `5.1` with BACK surrounds. That difference is the whole point of the mask:
     * both are six channels, and only one of them is what this file contains. WAV carries the mask
     * in its header; Matroska stores the count alone and would lose it.
     */
    private fun writeSurroundWav(path: String, frames: Int = 10) {
        MediaSink.open(path).use { sink ->
            val audio = sink.addAudioEncoder(
                AudioEncoderSpec(
                    codec = CodecId.PcmS16,
                    sampleRate = 48_000,
                    channels = 6,
                    sampleFormat = SampleFormat.S16,
                    options = mapOf("ch_layout" to "5.1(side)"),
                )
            )
            runBlocking { audio.drive(audioFrames(frames, channels = 6, sampleRate = 48_000)) }
        }
    }

    /**
     * `av_rescale_q` rounds to nearest and breaks ties away from zero. Recomputed here, without the
     * library, so the assertions have an independent oracle instead of the code under test.
     */
    private fun ticksToMicrosNearest(ticks: Long, den: Long): Long {
        val scaled = ticks * 1_000_000L
        return if (scaled >= 0) (scaled + den / 2) / den else -((-scaled + den / 2) / den)
    }

    /** Decodes [stream] to the end of the container and returns how many samples arrived. */
    private fun totalSamples(src: MediaSource, stream: StreamInfo): Int {
        val frames = runBlocking { src.decodedFrames(stream).toList() }
        try {
            return frames.sumOf { it.info.sampleCount }
        } finally {
            frames.forEach { it.close() }
        }
    }

    /**
     * D8. Opening a reader marks the streams it did not select AVDISCARD_ALL, which is what makes
     * reading cheap on a file with many tracks. Those flags live on the demuxer, not on the reader,
     * so a close that did not restore them left the source permanently unable to see those streams:
     * libavformat skipped their packets, the batch decode API returned almost nothing, and no error
     * explained it, because a skipped stream looks exactly like a short one.
     *
     * The assertion is the same source before and after the reader, because "frames arrive" is too
     * weak: a discarded matroska audio track still delivers its first packet, so a count of zero is
     * not what the defect looks like. Equal sample totals are.
     */
    @Test
    fun closingAReaderLetsTheBatchApiReadTheStreamsItSkipped() {
        val path = tmp("discard-restore.mkv")
        writeVideoAndAudio(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            val audio = src.primaryAudio ?: error("no audio stream written")

            // Before any reader exists: what this audio stream really contains.
            val reference = totalSamples(src, audio)
            assertTrue(reference > 0, "the audio stream decoded to nothing before any reader ran")

            runBlocking { src.seekMicros(0) }
            src.openPacketReader(listOf(video)).use { reader ->
                var read = 0
                while (read < 3) {
                    val packet = reader.read() ?: break
                    packet.use { assertEquals(video.index, it.streamIndex) }
                    read++
                }
                assertTrue(read > 0, "the reader produced no packets at all")
            }

            runBlocking { src.seekMicros(0) }
            assertEquals(
                reference,
                totalSamples(src, audio),
                "the audio stream lost samples after a reader that had skipped it was closed",
            )
        }
    }

    /**
     * D9. The microsecond helpers convert through `av_rescale_q`, which uses a 128 bit
     * intermediate. The naive form, `ticks * 1_000_000 * num / den` in Long arithmetic, overflows
     * on a fine time base: this test's value is a nanosecond timescale about two and a half hours
     * in, which every player reaches on a long recording.
     */
    @Test
    fun microsecondHelpersSurviveATimeBaseThatOverflowsANaiveMultiply() {
        val nanoTimeBase = Rational(1, 1_000_000_000)
        val ticks = 10_000_000_000_000L  // 10^13 ns, so 10^4 seconds

        assertTrue(
            ticks * 1_000_000L < 0L,
            "the naive multiply must overflow, otherwise this test proves nothing",
        )

        val packet = Packet(
            ffkmp_packet_alloc() ?: error("av_packet_alloc returned NULL"),
            nanoTimeBase,
        )
        packet.use {
            ffkmp_packet_set_pts(it.native, ticks)
            ffkmp_packet_set_dts(it.native, ticks)
            assertEquals(10_000_000_000L, it.ptsMicros)
            assertEquals(10_000_000_000L, it.dtsMicros)
        }

        FrameOps.acquire(streamIndex = 0, streamType = MediaType.Video, timeBase = nanoTimeBase).use { frame ->
            ffkmp_frame_set_pts(frame.nativeFrame, ticks)
            assertEquals(10_000_000_000L, frame.ptsMicros)
        }
    }

    /** D9. Absence stays absence: no timestamp and no duration read as null, never as zero. */
    @Test
    fun microsecondHelpersReportAbsenceAsNull() {
        Packet(ffkmp_packet_alloc() ?: error("av_packet_alloc returned NULL"), Rational(1, 90_000)).use {
            // A freshly allocated packet carries AV_NOPTS_VALUE and a duration of 0.
            assertNull(it.ptsMicros, "a packet with no pts must not report a time")
            assertNull(it.dtsMicros, "a packet with no dts must not report a time")
            assertNull(it.durationMicros, "an unknown duration must not report as 0")
        }
        FrameOps.acquire(streamIndex = 0, streamType = MediaType.Video, timeBase = Rational(1, 90_000)).use {
            assertNull(it.durationMicros, "an unknown frame duration must not report as 0")
        }
    }

    /**
     * D9. Packet DTS used to be handed out as raw ticks wrapped in a microsecond type, with no
     * rescale at all. On the 90 kHz timescale of every transport stream that is wrong by a factor
     * of about eleven.
     */
    @Test
    fun dtsAndDurationMicrosAreRescaledOnA90kHzStream() {
        val path = tmp("ts90k.ts")
        writeTransportStream(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            assertEquals(Rational(1, 90_000), video.timeBase, "MPEG-TS must report the 90 kHz timescale")

            src.openPacketReader(listOf(video)).use { reader ->
                var checked = 0
                while (checked < 5) {
                    val packet = reader.read() ?: break
                    packet.use {
                        val dts = it.dts
                        if (dts != FrameInfo.NOPTS) {
                            assertEquals(ticksToMicrosNearest(dts, 90_000L), it.dtsMicros)
                            // The old behaviour: ticks presented as microseconds. Only a dts of 0
                            // rescales to itself, and that one proves nothing either way.
                            if (dts != 0L) {
                                assertNotEquals(dts, it.dtsMicros, "dts $dts was not rescaled at all")
                            }
                            checked++
                        }
                        if (it.duration > 0) {
                            assertEquals(ticksToMicrosNearest(it.duration, 90_000L), it.durationMicros)
                        } else {
                            assertNull(it.durationMicros)
                        }
                    }
                }
                assertTrue(checked > 0, "no packet carried a dts to check")
            }

            // The reader left the cursor part way in; rewind so the decode below is deterministic.
            runBlocking { src.seekMicros(0) }
            val frames = runBlocking { src.decodedFrames(video).toList() }
            try {
                val first = frames.firstOrNull { it.info.hasPts } ?: error("no decoded frame carried a pts")
                assertEquals(ticksToMicrosNearest(first.info.pts, 90_000L), first.ptsMicros)
            } finally {
                frames.forEach { it.close() }
            }
        }
    }

    /**
     * D17. `receive()` returns null both when the decoder wants more input and when the stream is
     * over, and a player must tell them apart: treating the first as the second cuts the tail off
     * every file, and treating the second as the first spins forever.
     */
    @Test
    fun isDrainedSeparatesEndOfStreamFromNeedingInput() {
        val path = tmp("drained.mkv")
        writeVideoAndAudio(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            src.openDecoder(video).use { decoder ->
                var received = 0
                src.openPacketReader(listOf(video)).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use {
                            while (!decoder.send(it)) {
                                decoder.receive()?.close()
                                    ?: error("the decoder refused a packet and produced nothing")
                                received++
                            }
                        }
                        while (true) {
                            val frame = decoder.receive() ?: break
                            frame.close()
                            received++
                        }
                        assertFalse(decoder.isDrained, "a decoder still being fed is not drained")
                    }
                }

                decoder.send(null)  // begin the end-of-stream drain
                while (true) {
                    val frame = decoder.receive() ?: break
                    frame.close()
                    received++
                }
                assertTrue(received > 0, "the decoder produced no frames")
                assertTrue(decoder.isDrained, "end of stream did not set isDrained")

                decoder.flush()
                assertFalse(decoder.isDrained, "flush must make the decoder ready for input again")
            }
        }
    }

    /**
     * D18. A reader owns the demuxer cursor. Seeking the source behind its back moved the cursor
     * while the reader's caller believed it was reading forward, and nothing told that caller the
     * packets afterwards came from somewhere else.
     */
    @Test
    fun seekingASourceWithAnOpenReaderIsRejected() {
        val path = tmp("seek-guard.mkv")
        writeVideoAndAudio(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            val reader = src.openPacketReader(listOf(video))
            val failure = assertFailsWith<IllegalStateException> { runBlocking { src.seekMicros(0) } }
            assertTrue(
                failure.message!!.contains("PacketReader.seek"),
                "the rejection must name the call to use instead, got: ${failure.message}",
            )
            reader.close()
            // With the reader closed the cursor is the source's again.
            runBlocking { src.seekMicros(0) }
        }
    }

    /**
     * D30. Six channels are 5.1 with side surrounds or 5.1 with back surrounds. A downmix keyed on
     * the count alone sends surround content to the wrong speakers, so the layout has to survive
     * the trip from the container to the caller.
     */
    @Test
    fun audioStreamAndFramesReportTheRealChannelLayoutMask() {
        val path = tmp("surround51side.wav")
        writeSurroundWav(path)
        MediaSource.open(path).use { src ->
            val audio = src.primaryAudio ?: error("no audio stream written")
            val info = audio.audio ?: error("audio stream carries no AudioStreamInfo")
            assertEquals(6, info.channels)
            assertEquals(MASK_5POINT1_SIDE, info.channelLayoutMask, "the side layout did not survive")
            assertNotEquals(
                MASK_5POINT1_BACK,
                info.channelLayoutMask,
                "the mask is the layout a count of six is guessed into, so nothing was read",
            )

            val frames = runBlocking { src.decodedFrames(audio).toList() }
            try {
                val first = frames.firstOrNull() ?: error("no audio frames decoded")
                assertEquals(MASK_5POINT1_SIDE, first.info.channelLayoutMask)
                assertEquals(6, first.info.channelCount)
            } finally {
                frames.forEach { it.close() }
            }
        }
    }

    /**
     * D31. `withPlanes` reports a row count per plane, computed from the frame's format read as a
     * pixel format. An audio frame's format is a SAMPLE format, so the same ordinal named an
     * unrelated pixel format and the heights were nonsense the caller could not detect.
     */
    @Test
    fun withPlanesRefusesAnAudioFrameAndStillMeasuresAVideoOne() {
        Frame.ofAudio(
            bytes = ByteArray(1024 * 2 * 2),
            sampleCount = 1024,
            sampleRate = 48_000,
            channels = 2,
            sampleFormat = SampleFormat.S16,
        ).use { frame ->
            val failure = assertFailsWith<IllegalStateException> { frame.withPlanes { _, _, _ -> } }
            assertTrue(
                failure.message!!.contains("copyPlanesToByteArray"),
                "the refusal must name what to use for audio, got: ${failure.message}",
            )
        }

        // The positive control: yuv420p subsamples chroma vertically, so the two chroma planes are
        // half height. A wrong answer here would mean the guard broke the video path.
        Frame.ofVideo(yuvFrame(64, 64, 0), 64, 64, PixelFormat.Yuv420p).use { frame ->
            frame.withPlanes { planes, strides, heights ->
                assertEquals(3, planes.size)
                assertEquals(listOf(64, 32, 32), heights)
                assertTrue(strides.all { it >= 32 }, "implausible row pitches: $strides")
            }
        }
    }

    /**
     * D32. Seekability was asserted rather than read. It is a property of the input: a file can
     * seek, and a pipe carrying the very same bytes cannot, because its bytes only move forward.
     */
    @Test
    fun seekabilityIsReadFromTheInputNotAssumed() {
        val path = tmp("seekability.ts")
        writeTransportStream(path)
        MediaSource.open(path).use { src ->
            assertTrue(src.isSeekable, "a local file must report seekable")
        }

        // `pipe:<fd>` reads the descriptor sequentially and is streamed by definition, so this is
        // the same media through an input that cannot seek.
        val file = fopen(path, "rb") ?: error("could not open $path for the pipe case")
        try {
            MediaSource.open("pipe:${fileno(file)}").use { piped ->
                assertFalse(piped.isSeekable, "a pipe must not report seekable")
                assertTrue(piped.streams.isNotEmpty(), "the pipe case did not open the media at all")
            }
        } finally {
            fclose(file)
        }
    }

    /**
     * D35. A closed packet's `AVPacket` is freed. Reading a property off it, or offering it to a
     * decoder, used to dereference that memory and return or decode whatever the allocator had put
     * there since, which is the kind of bug that reproduces once a week and never in a test.
     */
    @Test
    fun aClosedPacketRefusesToBeReadOrSent() {
        val path = tmp("closed-packet.mkv")
        writeVideoAndAudio(path)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            src.openDecoder(video).use { decoder ->
                src.openPacketReader(listOf(video)).use { reader ->
                    val packet = reader.read() ?: error("the reader produced no packets")

                    // The positive control, on the same packet, before it is closed.
                    assertEquals(video.index, packet.streamIndex)
                    assertTrue(decoder.send(packet), "an open packet must still be accepted")

                    packet.close()

                    val reads = listOf<Pair<String, () -> Any?>>(
                        "streamIndex" to { packet.streamIndex },
                        "pts" to { packet.pts },
                        "dts" to { packet.dts },
                        "duration" to { packet.duration },
                        "isKeyframe" to { packet.isKeyframe },
                        "sizeBytes" to { packet.sizeBytes },
                        "bytePosition" to { packet.bytePosition },
                        "hasPts" to { packet.hasPts },
                        "ptsMicros" to { packet.ptsMicros },
                        "dtsMicros" to { packet.dtsMicros },
                        "durationMicros" to { packet.durationMicros },
                    )
                    reads.forEach { (name, read) ->
                        val failure = assertFailsWith<IllegalStateException>("$name read a closed packet") {
                            read()
                        }
                        assertEquals("Packet is closed", failure.message, "wrong message from $name")
                    }

                    val sendFailure = assertFailsWith<IllegalStateException> { decoder.send(packet) }
                    assertEquals("Packet is closed", sendFailure.message)

                    // The decoder itself is unharmed: the drain still works.
                    assertTrue(decoder.send(null), "the decoder must still accept the end of stream")
                }
            }
        }
    }
}
