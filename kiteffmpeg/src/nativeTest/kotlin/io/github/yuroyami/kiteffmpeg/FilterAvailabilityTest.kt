@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.Deinterlacer
import io.github.yuroyami.kiteffmpeg.dsl.audioFilters
import io.github.yuroyami.kiteffmpeg.dsl.videoFilters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A chain can say which of its filters this build lacks, before FFmpeg is asked to parse it.
 *
 * The recipes compile a chosen set of filters, and a chain built from the DSL can name one the
 * build does not carry. Until this, finding that out meant handing the description to FFmpeg and
 * reading whatever it said, which is a parse error about a string rather than an answer about a
 * filter. The chain knows its own filter names, and [FFmpeg.hasFilter] is one call each.
 *
 * `eq` stands in for a missing filter: FFmpeg's own build marks it GPL-only, so no recipe this
 * permissive library builds can carry it.
 */
class FilterAvailabilityTest {

    @Test
    fun `a chain names every filter this build lacks not just the first`() {
        val chain = videoFilters {
            eq(brightness = 0.1)
            scale(320, 240)
            drawBox(0, 0, 8, 8)
            eq(contrast = 1.2)
        }
        val missing = chain.missingFilters()
        // scale is in every recipe this project builds; eq and drawbox are not.
        assertTrue("scale" !in missing, "scale is compiled in, so it cannot be missing: $missing")
        assertEquals(
            missing.distinct(),
            missing,
            "a chain naming one filter twice must report it once: $missing",
        )
        for (name in missing) {
            assertTrue(!FFmpeg.hasFilter(name), "$name was reported missing but the build has it")
        }
    }

    @Test
    fun `a chain of filters this build has reports nothing missing`() {
        val chain = videoFilters { scale(320, 240) }
        assertEquals(emptyList(), chain.missingFilters())
        chain.requireAvailable()
    }

    @Test
    fun `requireAvailable refuses typed and names every missing filter at once`() {
        val chain = videoFilters {
            eq(brightness = 0.1)
            scale(320, 240)
        }
        val missing = chain.missingFilters()
        if (missing.isEmpty()) return // this build carries them; nothing to refuse

        val failure = assertFailsWith<FFmpegException> { chain.requireAvailable() }
        assertTrue(
            failure.error is FFmpegError.FilterNotFound,
            "a missing filter is FilterNotFound, not ${failure.error::class.simpleName}",
        )
        for (name in missing) {
            assertTrue(
                name in failure.message.orEmpty(),
                "the refusal must name $name; it said ${failure.message}",
            )
        }
    }

    @Test
    fun `building from a chain refuses before FFmpeg is asked to parse anything`() {
        val chain = videoFilters {
            eq(brightness = 0.1)
            scale(320, 240)
        }
        if (chain.missingFilters().isEmpty()) return

        val failure = assertFailsWith<FFmpegException> {
            FilterGraph.buildVideo(
                chain,
                width = 320,
                height = 240,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 25),
                frameRate = Rational(25, 1),
            )
        }
        assertTrue(
            failure.error is FFmpegError.FilterNotFound,
            "the chain overload must refuse on availability, got ${failure.error::class.simpleName}",
        )
    }

    @Test
    fun `a buildable chain still builds through the chain overload`() {
        val graph = FilterGraph.buildVideo(
            videoFilters { scale(320, 240) },
            width = 640,
            height = 480,
            pixelFormat = PixelFormat.Yuv420p,
            timeBase = Rational(1, 25),
            frameRate = Rational(25, 1),
        )
        graph.close()
    }

    /** Deinterlacing and loudness control are ordinary player features, and every recipe carries them. */
    @Test
    fun theDeinterlacersAndTheLoudnessFiltersAreCompiledIn() {
        for (name in listOf("yadif", "bwdif", "loudnorm", "ebur128", "alimiter")) {
            assertTrue(FFmpeg.hasFilter(name), "this build does not carry $name")
        }
        assertEquals(emptyList(), videoFilters { deinterlace() }.missingFilters())
        assertEquals(emptyList(), videoFilters { deinterlace(Deinterlacer.Yadif) }.missingFilters())
        assertEquals(emptyList(), audioFilters { loudnorm() }.missingFilters())
    }

    /**
     * An interlaced picture in motion: every frame puts its bright lines on the other field, which
     * is what two fields captured at different instants look like. A deinterlacer rebuilds the
     * missing lines of each output from the field it keeps, so the comb between neighbouring lines
     * collapses. A filter that passed the frames through would hand the full comb back.
     */
    @Test
    fun bothDeinterlacersTurnACombedStreamIntoFramesWithoutTheComb() {
        for (deinterlacer in Deinterlacer.entries) {
            val combs = mutableListOf<Double>()
            FilterGraph.buildVideo(
                videoFilters { deinterlace(deinterlacer) },
                width = SIZE,
                height = SIZE,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = MICROS,
                frameRate = Rational(25, 1),
            ).use { graph ->
                repeat(FRAMES) { index ->
                    graph.feedInput(0, combedFrame(index)) { out -> combs += combOf(out) }
                }
                graph.flushInput(0) { out -> combs += combOf(out) }
            }
            assertTrue(combs.size >= FRAMES, "$deinterlacer made ${combs.size} frames from $FRAMES")
            val mean = combs.average()
            println("$deinterlacer: ${combs.size} frames from $FRAMES, mean comb $mean against $INPUT_COMB")
            assertTrue(
                mean < INPUT_COMB / 2,
                "$deinterlacer left a mean comb of $mean against $INPUT_COMB in its input",
            )
        }
    }

    @Test
    fun loudnormNormalisesAToneIntoFrames() {
        var samplesOut = 0
        FilterGraph.buildAudio(
            audioFilters { loudnorm() },
            sampleRate = SAMPLE_RATE,
            sampleFormat = SampleFormat.S16,
            channels = 1,
            timeBase = MICROS,
            outputSampleRate = SAMPLE_RATE,
        ).use { graph ->
            repeat(TONE_FRAMES) { index ->
                graph.feedInput(0, toneFrame(index)) { out -> samplesOut += out.info.sampleCount }
            }
            graph.flushInput(0) { out -> samplesOut += out.info.sampleCount }
        }
        assertTrue(samplesOut > 0, "loudnorm produced no samples from $TONE_FRAMES frames of tone")
    }

    private companion object {
        const val SIZE = 64
        const val FRAMES = 6
        const val DARK = 40
        const val BRIGHT = 200
        const val INPUT_COMB = (BRIGHT - DARK).toDouble()
        const val SAMPLE_RATE = 48_000
        const val TONE_SAMPLES = 1024
        const val TONE_FRAMES = 60

        /** Micro-second time-base, so a frame's pts and the graph's units are the same thing. */
        val MICROS = Rational(1, 1_000_000)

        /** Luma lines alternate dark and bright, and the bright lines change field every frame. */
        fun combedFrame(index: Int): Frame {
            val y = ByteArray(SIZE * SIZE) { offset ->
                val line = offset / SIZE
                (if ((line + index) % 2 == 0) BRIGHT else DARK).toByte()
            }
            val chroma = ByteArray(SIZE * SIZE / 4) { 128.toByte() }
            return Frame.ofVideo(y + chroma + chroma, SIZE, SIZE, PixelFormat.Yuv420p, index * 40_000L)
        }

        /** The mean step between vertically neighbouring luma lines, away from the top and bottom edge. */
        fun combOf(frame: Frame): Double {
            val info = frame.info
            val luma = frame.copyPlanesToByteArray()
            var total = 0L
            var count = 0
            for (line in 2 until info.height - 3) {
                for (x in 0 until info.width) {
                    val here = luma[line * info.width + x].toInt() and 0xFF
                    val below = luma[(line + 1) * info.width + x].toInt() and 0xFF
                    total += abs(here - below)
                    count++
                }
            }
            return total.toDouble() / count
        }

        /** A 1 kHz tone at about -20 dBFS, s16 mono. */
        fun toneFrame(index: Int): Frame {
            val bytes = ByteArray(TONE_SAMPLES * 2)
            for (s in 0 until TONE_SAMPLES) {
                val t = (index * TONE_SAMPLES + s).toDouble() / SAMPLE_RATE
                val value = (3_277 * sin(2 * PI * 1_000 * t)).roundToInt()
                bytes[s * 2] = (value and 0xFF).toByte()
                bytes[s * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            return Frame.ofAudio(
                bytes = bytes,
                sampleCount = TONE_SAMPLES,
                sampleRate = SAMPLE_RATE,
                channels = 1,
                sampleFormat = SampleFormat.S16,
                ptsMicros = index.toLong() * TONE_SAMPLES * 1_000_000L / SAMPLE_RATE,
            )
        }
    }
}
