package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cut that copies streams starts at the keyframe at or before the trim start, and that keyframe
 * is the output's zero, so the output's chapters move by as much as its media (#144). They moved
 * by the requested start instead, so each chapter came out early by however far the cut reached
 * back: a chapter on a frame three seconds into the output said one and a half.
 *
 * The source is six seconds at 10 fps with a keyframe every two seconds, a chapter that starts on
 * frame 50, at five seconds, and one before it. A cut from 3.5 s lands on the keyframe at two
 * seconds, which puts frame 50 three seconds in. Each output is read back by this library and by
 * `ffprobe`, which decides where the chapter is without this library's help.
 */
internal class TrimmedChapterContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /**
     * The source, in Matroska. [bFrames] gives the video two B-frames between references,
     * [audio] adds a PCM track beside it, and [startsAtMicros] moves its whole timeline later, as
     * a capture or a cut from a broadcast would be.
     */
    private fun source(bFrames: Boolean = false, audio: Boolean = false, startsAtMicros: Long = 0L): String =
        path("mkv").also { file ->
            val options = if (startsAtMicros == 0L) emptyMap() else mapOf("output_ts_offset" to "${startsAtMicros}us")
            MediaSink.open(file, options = options).use { sink ->
                // On the file's own timeline: the muxer moves packets by its offset, not chapters.
                sink.setChapters(
                    listOf(
                        Chapter(1, startsAtMicros, startsAtMicros + MARKER_MICROS, mapOf("title" to BEFORE)),
                        Chapter(2, startsAtMicros + MARKER_MICROS, startsAtMicros + 6_000_000L, mapOf("title" to MARKER)),
                    ),
                )
                val spec = TranscodeFixtures.videoSpec(Rational(10, 1))
                val video = sink.addVideoEncoder(if (bFrames) spec.copy(options = mapOf("bf" to "2")) else spec)
                val sound = if (audio) sink.addAudioEncoder(TranscodeFixtures.pcmSpec(SAMPLE_RATE, 1)) else null
                runBlocking {
                    video.drive((0 until 60).asFlow().map { i -> TranscodeFixtures.texturedPicture(i, ptsMicros = i * 100_000L) })
                    sound?.drive(
                        (0 until 6 * SAMPLE_RATE step BLOCK).asFlow().map { first ->
                            Frame.ofAudio(
                                bytes = ByteArray(BLOCK * 2),
                                sampleCount = BLOCK,
                                sampleRate = SAMPLE_RATE,
                                channels = 1,
                                sampleFormat = SampleFormat.S16,
                                ptsMicros = first * 1_000_000L / SAMPLE_RATE,
                            )
                        },
                    )
                }
            }
        }

    /**
     * Asserts the marker chapter of [output] starts where frame 50 shows and runs for
     * [markerMicros], and the chapter before it ends there, by this library and by ffprobe.
     */
    private fun assertChapterOnItsFrame(output: String, markerMicros: Long) {
        val indices = TranscodeFixtures.decodedFrameIndices(output)
        val times = TranscodeFixtures.decodedFrameTimes(output)
        val shown = times[indices.indexOf(MARKER_FRAME).also { assertTrue(it >= 0, "frame 50 is not in the output: $indices") }]
        val (before, marker) = MediaSource.open(output).use { source ->
            val chapters = source.chapters.associateBy { it.title }
            fun relative(title: String) = checkNotNull(chapters[title]) { "no $title chapter: ${source.chapters}" }
                .let { it.copy(startMicros = it.startMicros - source.startTimeMicros, endMicros = it.endMicros - source.startTimeMicros) }
            relative(BEFORE) to relative(MARKER)
        }
        assertTrue(abs(marker.startMicros - shown) <= 1_000L, "the chapter starts at ${marker.startMicros} us and its frame shows at $shown us")
        assertTrue(abs(marker.endMicros - marker.startMicros - markerMicros) <= 1_000L, "the chapter runs for ${marker.endMicros - marker.startMicros} us")
        assertEquals(marker.startMicros, before.endMicros, "the chapter before it ends where it starts")
        MediaOracle.chapterStarts(output)?.let { starts ->
            val probed = checkNotNull(starts[MARKER]) { "ffprobe reads no $MARKER chapter: $starts" }
            assertTrue(abs(probed - shown) <= 1_000L, "ffprobe reads the chapter at $probed us and its frame shows at $shown us")
        }
    }

    /** The fixture itself: its chapter is on its frame before anything cuts it. */
    @Test
    fun theSourceHasItsChapterOnItsFrame() {
        assertChapterOnItsFrame(source(), markerMicros = 1_000_000L)
        assertChapterOnItsFrame(source(bFrames = true, audio = true, startsAtMicros = 10_000_000L), markerMicros = 1_000_000L)
    }

    /** The issue's reproduction: red when the chapter starts at 1.5 s and its frame shows at 3 s. */
    @Test
    fun aMatroskaRemuxCutBetweenKeyframesKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking { Remuxer.remux(source(), output, startMicros = 3_500_000L, endMicros = 5_800_000L) }
        assertEquals(20, TranscodeFixtures.decodedFrameIndices(output).first(), "the cut lands on the keyframe at two seconds")
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    @Test
    fun anMp4RemuxCutBetweenKeyframesKeepsAChapterOnItsFrame() {
        val output = path("mp4")
        runBlocking { Remuxer.remux(source(), output, startMicros = 3_500_000L, endMicros = 5_800_000L) }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    @Test
    fun aRemuxOfVideoWithBFramesKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking { Remuxer.remux(source(bFrames = true), output, startMicros = 3_500_000L, endMicros = 5_800_000L) }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    @Test
    fun anMp4RemuxOfVideoWithBFramesKeepsAChapterOnItsFrame() {
        val output = path("mp4")
        runBlocking { Remuxer.remux(source(bFrames = true), output, startMicros = 3_500_000L, endMicros = 5_800_000L) }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    @Test
    fun aRemuxWithAudioKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking { Remuxer.remux(source(audio = true), output, startMicros = 3_500_000L, endMicros = 5_800_000L) }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    /** Chapters are absolute and the cut is relative, so a source that starts late moves both. */
    @Test
    fun aRemuxOfASourceThatStartsLateKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking {
            Remuxer.remux(source(startsAtMicros = 10_000_000L), output, startMicros = 3_500_000L, endMicros = 5_800_000L)
        }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    @Test
    fun aCopyingTranscodeCutBetweenKeyframesKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(input = source(), output = output, videoCopy = true, startMicros = 3_500_000L, endMicros = 5_800_000L)
        }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    /** The copied video claims the origin at its keyframe and the encoded audio starts at the cut. */
    @Test
    fun aTranscodeCopyingVideoAndEncodingAudioKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(
                input = source(audio = true),
                output = output,
                videoCopy = true,
                audioSpec = TranscodeFixtures.pcmSpec(SAMPLE_RATE, 1),
                startMicros = 3_500_000L,
                endMicros = 5_800_000L,
            )
        }
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    /** A transcode that encodes its video starts on the frame at the cut, and its chapters with it. */
    @Test
    fun anEncodingTranscodeKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(
                input = source(audio = true),
                output = output,
                spec = TranscodeFixtures.videoSpec(Rational(10, 1)),
                audioCopy = true,
                startMicros = 3_500_000L,
                endMicros = 5_800_000L,
            )
        }
        assertEquals(35, TranscodeFixtures.decodedFrameIndices(output).first(), "an encoded cut starts on the frame at the cut")
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    /** A cut on a keyframe moves both by the same amount already, and still does. */
    @Test
    fun aRemuxCutOnAKeyframeKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking { Remuxer.remux(source(), output, startMicros = 4_000_000L, endMicros = 5_800_000L) }
        assertEquals(40, TranscodeFixtures.decodedFrameIndices(output).first())
        assertChapterOnItsFrame(output, markerMicros = 800_000L)
    }

    /** An untrimmed remux keeps the chapter where it was. */
    @Test
    fun aWholeRemuxKeepsAChapterOnItsFrame() {
        val output = path("mkv")
        runBlocking { Remuxer.remux(source(), output) }
        assertChapterOnItsFrame(output, markerMicros = 1_000_000L)
    }

    private companion object {
        const val BEFORE = "Before"
        const val MARKER = "Marker"
        const val MARKER_FRAME = 50
        const val MARKER_MICROS = 5_000_000L
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 960
    }
}
