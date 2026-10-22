package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A filter graph hands each frame over as it comes out, before it asks the graph for the next one
 * (#141). It used to take every frame the graph had ready first, so a short input that a filter
 * expands held every expanded frame before the first was seen, and `tpad=stop=-1`, which pads
 * without end once its input ends, never let a flush reach its callback at all: nothing could stop
 * it, and it filled memory.
 *
 * The real graph runs under a backend that counts what it hands out and stops handing out at a
 * ceiling, so a graph that still drains eagerly fails here instead of filling memory.
 */
internal class FilterOutputPaceContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private class CountingBackend(private val real: FilterBackend) : FilterBackend by real {
        var received = 0
        var freed = false

        override fun receive(): Frame? {
            if (received >= CEILING) return null
            return real.receive()?.also { received++ }
        }

        override fun free() {
            freed = true
            real.free()
        }
    }

    private class Stop : RuntimeException()

    private fun graph(description: String) = CountingBackend(
        buildVideoBackend(description, SIZE, SIZE, PixelFormat.Yuv420p, MICROS, Rational(25, 1), Rational(1, 1)),
    )

    private fun picture() = Frame.ofVideo(ByteArray(SIZE * SIZE * 3 / 2), SIZE, SIZE, PixelFormat.Yuv420p, ptsMicros = 0L)

    @Test
    fun aFlushHandsOverEachPaddedFrameBeforeItTakesTheNext() {
        val baseline = contractLiveHandleCount()
        val backend = graph("tpad=stop=63")
        val ahead = mutableListOf<Int>()
        var delivered = 0
        FilterGraph(backend).use { graph ->
            val count: (Frame) -> Unit = {
                delivered++
                ahead += backend.received - delivered
            }
            graph.feedInput(0, picture(), count)
            graph.flushInput(0, count)
        }
        assertEquals(64, delivered, "the picture and the 63 frames padded after it")
        assertEquals(List(64) { 0 }, ahead, "frames taken from the graph and not yet handed over, at each callback")
        assertEquals(baseline, contractLiveHandleCount(), "a frame was left open")
    }

    @Test
    fun anEndlessPadStopsWhenItsCallbackThrows() {
        val baseline = contractLiveHandleCount()
        val backend = graph("tpad=stop=-1")
        var delivered = 0
        FilterGraph(backend).use { graph ->
            graph.feedInput(0, picture()) { delivered++ }
            assertFailsWith<Stop> {
                graph.flushInput(0) { if (++delivered == 50) throw Stop() }
            }
        }
        assertEquals(50, backend.received, "frames taken from the graph")
        assertTrue(backend.freed)
        assertEquals(baseline, contractLiveHandleCount(), "a frame was left open")
    }

    @Test
    fun anEndlessPadStopsWhenItsCallbackClosesTheGraph() {
        val baseline = contractLiveHandleCount()
        val backend = graph("tpad=stop=-1")
        val graph = FilterGraph(backend)
        graph.feedInput(0, picture()) {}
        var delivered = 0
        val result = graph.flushInput(0) { frame ->
            if (++delivered == 50) graph.close()
            // Still readable: the frame handed over is its own reference and outlives the graph.
            assertEquals(SIZE, frame.info.width)
        }
        assertEquals(FeedResult.Ready(50), result)
        assertEquals(51, backend.received, "the picture and the 50 padded frames")
        assertTrue(backend.freed, "the graph closed from its callback was never freed")
        assertEquals(baseline, contractLiveHandleCount(), "a frame was left open")
    }

    @Test
    fun aProcessedEndlessPadEndsWhereItsCollectorStops() = runBlocking {
        val baseline = contractLiveHandleCount()
        val backend = graph("tpad=stop=-1")
        val frames = FilterGraph(backend).process(flowOf(picture())).take(50).toList()
        frames.forEach(Frame::close)
        assertEquals(50, frames.size)
        assertEquals(50, backend.received, "frames taken from the graph")
        assertTrue(backend.freed)
        assertEquals(baseline, contractLiveHandleCount(), "a frame was left open")
    }

    @Test
    fun aProcessedEndlessPadEndsWhenItsCollectorIsCancelled() = runBlocking {
        val baseline = contractLiveHandleCount()
        val backend = graph("tpad=stop=-1")
        var delivered = 0
        launch {
            FilterGraph(backend).process(flowOf(picture())).collect { frame ->
                frame.close()
                // Cancelled without a suspension, so only the graph's own check can see it.
                if (++delivered == 20) cancel()
            }
        }.join()
        assertEquals(20, delivered)
        assertTrue(backend.received <= 21, "the graph took ${backend.received} frames for a collector cancelled at 20")
        assertTrue(backend.freed)
        assertEquals(baseline, contractLiveHandleCount(), "a frame was left open")
    }

    /**
     * The transcoder's video filter takes the same path: its flush used to drain an endless pad
     * before its first frame reached the encoder, where cancellation is checked, so a transcode
     * given up on by its caller never ended.
     */
    @Test
    fun aTranscodeThroughAnEndlessPadEndsWhenItsCallerGivesUp() = runBlocking {
        val input = path("mkv").also { TranscodeFixtures.writeConstantRateVideo(it, Rational(25, 1), frames = 10) }
        val output = path("mkv")
        // Outside this test's scope, so a transcode that never ends cannot hold the test open:
        // the deadline below reports it, and only the stuck work is left behind.
        val work = CoroutineScope(Dispatchers.Default).async {
            withTimeoutOrNull(1_000) {
                Transcoder.transcode(
                    input = input,
                    output = output,
                    spec = TranscodeFixtures.videoSpec(Rational(25, 1)),
                    videoFilter = "tpad=stop=-1",
                )
            }
        }
        val ended = withTimeoutOrNull(10_000) { work.await() ?: "given up" }
        assertNotNull(ended, "the transcode through an endless pad did not stop when its caller gave up")
        assertEquals("given up", ended, "the transcode ended by itself, so the pad was not endless")
    }

    private companion object {
        const val SIZE = 16

        /** Above anything a test here hands over, and small enough to fit in memory at once. */
        const val CEILING = 10_000

        val MICROS = Rational(1, 1_000_000)
    }
}
