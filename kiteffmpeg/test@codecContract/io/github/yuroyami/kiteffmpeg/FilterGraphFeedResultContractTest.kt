package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The typed answers of a real graph: a filter with two inputs names the input it waits for, and a
 * graph that process() spent says so on every later call.
 */
internal class FilterGraphFeedResultContractTest {
    private val micros = Rational(1, 1_000_000)

    /** 1024 samples of mono silence, the [index]th block of a 48 kHz stream. */
    private fun silence(index: Int) = Frame.ofAudio(
        bytes = ByteArray(1024 * 4),
        sampleCount = 1024,
        sampleRate = 48_000,
        channels = 1,
        sampleFormat = SampleFormat.FltP,
        ptsMicros = index * 1024L * 1_000_000L / 48_000,
    )

    /** A 64x64 grey picture, the [index]th frame of a 25 fps stream. */
    private fun picture(index: Int) = Frame.ofVideo(
        bytes = ByteArray(64 * 64 * 3 / 2) { 0x80.toByte() },
        width = 64,
        height = 64,
        pixelFormat = PixelFormat.Yuv420p,
        ptsMicros = index * 40_000L,
    )

    @Test
    fun amixFedOnOneInputNamesTheOther() {
        val inputs = List(2) { AudioInput(48_000, SampleFormat.FltP, 1, micros) }
        FilterGraph.buildAudioMulti("[in0][in1]amix=inputs=2[out]", inputs).use { graph ->
            repeat(3) { i -> assertEquals(FeedResult.NeedsInput(1, 0), graph.feedInput(0, silence(i)) {}) }
            val released = graph.feedInput(1, silence(0)) {}
            assertTrue(released.produced > 0, "a frame on input 1 releases the mix: $released")
        }
    }

    @Test
    fun overlayFedOnItsMainInputNamesTheOverlay() {
        val input = VideoInput(64, 64, PixelFormat.Yuv420p, micros, Rational(25, 1))
        FilterGraph.buildVideoMulti("[in0][in1]overlay[out]", listOf(input, input)).use { graph ->
            repeat(2) { i -> assertEquals(FeedResult.NeedsInput(1, 0), graph.feedInput(0, picture(i)) {}) }
        }
    }

    @Test
    fun aSingleInputGraphAlwaysAnswersReady() {
        FilterGraph.buildAudio("anull", 48_000, SampleFormat.FltP, 1, micros).use { graph ->
            assertEquals(FeedResult.Ready(1), graph.feedInput(0, silence(0)) {})
            assertEquals(FeedResult.Ready(0), graph.flushInput(0) {})
        }
    }

    @Test
    fun aGraphThatProcessSpentSaysSoOnEveryLaterCall() {
        val graph = FilterGraph.buildAudio("anull", 48_000, SampleFormat.FltP, 1, micros)
        try {
            val first = graph.process(flowOf(silence(0)))
            val early = assertFailsWith<IllegalStateException> { graph.process(emptyFlow()) }
            assertTrue("spent" in early.message.orEmpty(), "before the first flow runs: ${early.message}")
            runBlocking { first.collect { it.close() } }
            val late = listOf<() -> Unit>(
                { graph.process(emptyFlow()) },
                { graph.feedInput(0, silence(1)) {} },
                { graph.flushInput(0) {} },
            )
            for (call in late) {
                val failure = assertFailsWith<IllegalStateException> { call() }
                assertTrue("spent" in failure.message.orEmpty(), "after the first flow ran: ${failure.message}")
            }
        } finally {
            graph.close()
        }
    }
}
