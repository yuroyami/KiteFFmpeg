package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A copy starts where its media starts to show, so a whole remux is a copy (#153).
 *
 * The output's zero was the decode time of the first packet written. Video with B-frames decodes
 * its first keyframe before it shows it, and AAC in MP4 starts with the encoder's priming, which
 * shows nothing, so a remux moved the whole file later by the B-frame delay or by the priming, and
 * the priming, which the source hides, was then played.
 *
 * The source is six seconds at 10 fps with a keyframe every two seconds, written by this library,
 * optionally with two B-frames between references and an AAC tone beside it. Each output is read
 * back by this library and, where there is one, by `ffprobe`.
 */
internal class CopyOriginContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /**
     * The source. With [audioLeadSamples] the sound starts that many samples before the picture,
     * so an MP4 edit list that starts the file with the picture starts partway into an AAC packet.
     * With [videoDelayMicros] the picture starts that much after the sound.
     */
    private fun source(
        extension: String,
        bFrames: Boolean = false,
        aac: Boolean = false,
        audioLeadSamples: Int = 0,
        videoDelayMicros: Long = 0L,
    ): String = path(extension).also { file ->
        MediaSink.open(file).use { sink ->
            val spec = TranscodeFixtures.videoSpec(Rational(10, 1))
            val video = sink.addVideoEncoder(if (bFrames) spec.copy(options = mapOf("bf" to "2")) else spec)
            val sound = if (aac) sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId.Aac, sampleRate = RATE, channels = 1)) else null
            val pictures = (0 until FRAMES).asFlow().map { i -> TranscodeFixtures.texturedPicture(i, ptsMicros = videoDelayMicros + i * 100_000L) }
            val blocks = (0 until FRAMES * RATE / 10 / AAC_FRAME).asFlow().map { tone(it, audioLeadSamples) }
            runBlocking {
                // The first frame encoded decides the writer's origin, so the stream that starts
                // first is encoded first.
                if (videoDelayMicros > 0L) sound?.drive(blocks)
                video.drive(pictures)
                if (videoDelayMicros == 0L) sound?.drive(blocks)
            }
        }
    }

    /** AAC frame [block] of a 440 Hz tone, planar float as the encoder takes it, [leadSamples] early. */
    private fun tone(block: Int, leadSamples: Int): Frame {
        val bytes = ByteArray(AAC_FRAME * 4)
        for (i in 0 until AAC_FRAME) {
            val bits = (sin(2.0 * PI * 440.0 * (block * AAC_FRAME + i) / RATE) * 0.5).toFloat().toRawBits()
            for (b in 0 until 4) bytes[i * 4 + b] = (bits shr (8 * b)).toByte()
        }
        val ptsMicros = (block * AAC_FRAME - leadSamples) * 1_000_000L / RATE
        return Frame.ofAudio(bytes, AAC_FRAME, RATE, 1, SampleFormat.FltP, ptsMicros = ptsMicros)
    }

    /** The presentation time of each decoded frame of [path]'s first stream of [type], on the file's own timeline. */
    private fun decodedTimes(path: String, type: MediaType): List<Long> = MediaSource.open(path).use { source ->
        val stream = checkNotNull(source.streams.firstOrNull { it.type == type }) { "$path has no $type stream" }
        val frames = runBlocking { source.decodedFrames(stream).toList() }
        try {
            frames.map { rescaleQ(it.info.pts, it.info.timeBase, Rational(1, 1_000_000)) }
        } finally {
            frames.forEach(Frame::close)
        }
    }

    private fun assertSameTimes(expected: List<Long>, actual: List<Long>, what: String) {
        assertEquals(expected.size, actual.size, "$what: ${expected.size} frames went in and ${actual.size} came out")
        expected.zip(actual).forEachIndexed { index, (was, now) ->
            assertTrue(abs(was - now) <= 1_000L, "$what: frame $index showed at $was us and shows at $now us")
        }
    }

    /**
     * Each video stream's start as `ffprobe` reads it, in microseconds, or null without an oracle.
     * Video only, because an older `ffprobe` leaves the samples an audio stream skips out of its
     * start: 6.1 reads AAC that FFmpeg 6.1 itself wrote to Matroska as starting at -21 ms, where
     * the FFmpeg this library carries reads 0.
     */
    private fun probedStarts(path: String): List<Long>? = runMediaOracle(
        "ffprobe",
        listOf("-v", "error", "-select_streams", "v", "-show_entries", "stream=start_time", "-of", "csv=p=0", path),
    )?.lineSequence()?.map { it.trim().trimEnd(',') }?.filter { it.isNotEmpty() }
        ?.map { (it.toDouble() * 1_000_000.0).roundToLong() }?.toList()

    /** Each stream's start as this library reads it, which is FFmpeg's reading. */
    private fun streamStarts(path: String): List<Long> = MediaSource.open(path).use { source -> source.streams.map { it.startTimeMicros } }

    /** A whole remux of [input] into [extension] shows every frame, and starts every stream, where the input does. */
    private fun assertWholeRemuxIsACopy(input: String, extension: String, audio: Boolean) {
        val output = path(extension)
        runBlocking { Remuxer.remux(input, output) }
        assertSameTimes(decodedTimes(input, MediaType.Video), decodedTimes(output, MediaType.Video), "$input to $extension video")
        if (audio) {
            val written = TranscodeFixtures.decodedSampleCount(input)
            val heard = TranscodeFixtures.decodedSampleCount(output)
            assertTrue(heard <= written, "$input to $extension: $heard samples heard of $written, so the priming the source hides is played")
            // Matroska keeps whole milliseconds, which a copy into MP4 does not yet turn back into
            // samples, so that copy can end short by up to a millisecond (#154).
            val slack = if (input.endsWith(".mkv") && extension == "mp4") RATE / 1000 else 0
            assertTrue(written - heard <= slack, "$input to $extension: $heard samples heard of $written")
            // To the millisecond, which is all Matroska keeps.
            val started = decodedTimes(input, MediaType.Audio).first()
            val starts = decodedTimes(output, MediaType.Audio).first()
            assertTrue(abs(started - starts) <= 1_000L, "$input to $extension: the sound started at $started us and starts at $starts us")
        }
        assertSameStarts(streamStarts(input), streamStarts(output), "this library reads the streams of $input to $extension")
        val before = probedStarts(input) ?: return
        assertSameStarts(before, checkNotNull(probedStarts(output)), "ffprobe reads the video of $input to $extension")
    }

    private fun assertSameStarts(before: List<Long>, after: List<Long>, what: String) {
        assertEquals(before.size, after.size, what)
        before.zip(after).forEach { (was, now) ->
            assertTrue(abs(was - now) <= 1_000L, "$what as starting at $before us and then at $after us")
        }
    }

    /** The issue's first row: the output showed every frame 100 ms late. */
    @Test
    fun aWholeRemuxOfVideoWithBFramesIsACopy() {
        val mp4 = source("mp4", bFrames = true)
        val mkv = source("mkv", bFrames = true)
        assertWholeRemuxIsACopy(mp4, "mp4", audio = false)
        assertWholeRemuxIsACopy(mp4, "mkv", audio = false)
        assertWholeRemuxIsACopy(mkv, "mkv", audio = false)
        assertWholeRemuxIsACopy(mkv, "mp4", audio = false)
    }

    /** The issue's second row: the output started late by the priming and played it. */
    @Test
    fun aWholeRemuxOfAacKeepsItsPrimingHidden() {
        val mp4 = source("mp4", aac = true)
        val mkv = source("mkv", aac = true)
        assertWholeRemuxIsACopy(mp4, "mp4", audio = true)
        assertWholeRemuxIsACopy(mp4, "mkv", audio = true)
        assertWholeRemuxIsACopy(mkv, "mkv", audio = true)
        assertWholeRemuxIsACopy(mkv, "mp4", audio = true)
    }

    /**
     * FFmpeg reads an MP4 edit list that starts partway into a packet as discarded packets and a
     * skip on the first one that covers them too, so the sound starts where the first packet plus
     * that skip says, not where the first packet it does not discard does.
     */
    @Test
    fun aWholeRemuxOfSoundCutByAnEditListKeepsItsPlace() {
        val mp4 = source("mp4", aac = true, audioLeadSamples = 476)
        assertWholeRemuxIsACopy(mp4, "mp4", audio = true)
        assertWholeRemuxIsACopy(mp4, "mkv", audio = true)
    }

    @Test
    fun aWholeRemuxKeepsAPictureThatStartsAfterTheSound() {
        for (extension in listOf("mp4", "mkv")) {
            val input = source(extension, bFrames = true, aac = true, videoDelayMicros = 100_000L)
            assertEquals(100_000L, decodedTimes(input, MediaType.Video).first(), "the $extension source's picture starts after its sound")
            assertWholeRemuxIsACopy(input, extension, audio = true)
        }
    }

    /**
     * A caller's own loop may hand over half a second of picture before any sound, and the
     * picture starts 100 ms after the sound. The copy holds the picture until the sound has shown
     * where it starts, so both keep their places.
     */
    @OptIn(KiteFFmpegLowLevelApi::class)
    @Test
    fun aCopyWaitsForEveryStreamToShowWhereItStarts() {
        val input = source("mp4", bFrames = true, aac = true, videoDelayMicros = 100_000L)
        for (extension in listOf("mkv", "mp4")) {
            val output = path(extension)
            MediaSource.open(input).use { pictures ->
                MediaSource.open(input).use { sound ->
                    val video = checkNotNull(pictures.primaryVideo)
                    val audio = checkNotNull(sound.primaryAudio)
                    MediaSink.open(output).use { sink ->
                        val videoOut = sink.addCopyStream(pictures, video)
                        val audioOut = sink.addCopyStream(sound, audio)
                        pictures.openPacketReader(listOf(video)).use { videoIn ->
                            sound.openPacketReader(listOf(audio)).use { audioIn ->
                                repeat(5) { videoIn.read()!!.use(videoOut::write) }
                                while (true) (audioIn.read() ?: break).use(audioOut::write)
                                while (true) (videoIn.read() ?: break).use(videoOut::write)
                            }
                        }
                    }
                }
            }
            assertEquals(100_000L, decodedTimes(output, MediaType.Video).first(), "the $extension copy's picture starts 100 ms in")
            assertTrue(abs(decodedTimes(output, MediaType.Audio).first()) <= 1_000L, "the $extension copy's sound starts at zero")
            assertEquals(TranscodeFixtures.decodedSampleCount(input), TranscodeFixtures.decodedSampleCount(output), "the $extension copy's samples")
        }
    }

    @Test
    fun aWholeRemuxOfBothIsACopy() {
        assertWholeRemuxIsACopy(source("mp4", bFrames = true, aac = true), "mkv", audio = true)
        assertWholeRemuxIsACopy(source("mkv", bFrames = true, aac = true), "mp4", audio = true)
    }

    /**
     * A cut starts on a keyframe, and that keyframe is the output's zero. The pictures that follow
     * it in decode order but show before it lean on a picture the cut leaves behind, so the cut
     * leaves them out rather than starting the output on pictures nobody can see.
     */
    @Test
    fun aCutOfVideoWithBFramesStartsOnItsKeyframe() {
        for (extension in listOf("mp4", "mkv")) {
            val output = path(extension)
            runBlocking { Remuxer.remux(source(extension, bFrames = true), output, startMicros = 3_500_000L) }
            val times = decodedTimes(output, MediaType.Video)
            assertEquals(0L, times.first(), "the $extension cut's first picture shows at its start")
            assertEquals(0L, MediaSource.open(output).use { it.startTimeMicros }, "the $extension cut starts at zero")
            assertEquals(times.size, videoPacketCount(output), "every picture the $extension cut carries can be shown")
            probedStarts(output)?.let { assertEquals(listOf(0L), it, "ffprobe reads the $extension cut starting at zero") }
        }
    }

    /** Counts [path]'s video packets without decoding them. */
    @OptIn(KiteFFmpegLowLevelApi::class)
    private fun videoPacketCount(path: String): Int = MediaSource.open(path).use { source ->
        val video = checkNotNull(source.primaryVideo) { "$path has no video stream" }
        source.openPacketReader(listOf(video)).use { reader ->
            var count = 0
            while (true) {
                (reader.read() ?: break).close()
                count++
            }
            count
        }
    }

    private companion object {
        const val FRAMES = 60
        const val RATE = 48_000
        const val AAC_FRAME = 1024
    }
}
