package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a graph with several inputs does with a frame one input will not take yet: it keeps the
 * frame, answers which input it waits for, and sends the frame first on the next call for its
 * input. The FFmpeg this binds to never refuses a buffer source write, so the backend here does:
 * its input 0 refuses everything until input 1 has had a frame.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class FilterGraphWaitingContractTest {

    private class GatedBackend : FilterBackend {
        /** Every send that went in: the input, and the frame's timestamp or null for a flush. */
        val taken = mutableListOf<Pair<Int, Long?>>()
        var freed = false
        private var inputOneFed = false

        override val inputCount: Int = 2
        override val outputTimeBase: Rational = Rational(1, 1_000_000)

        override fun setOutputFrameSize(samples: Int) = Unit

        override fun send(index: Int, frame: Frame?): Int {
            if (index == 0 && !inputOneFed) return AGAIN
            if (index == 1) inputOneFed = true
            taken += index to frame?.ptsMicros
            return 0
        }

        override fun receive(): Frame? = null

        override fun failedRequests(index: Int): Int = if (index == 1 && !inputOneFed) 3 else 0

        override fun isAgain(rc: Int): Boolean = rc == AGAIN

        override fun isEof(rc: Int): Boolean = false

        override fun error(rc: Int): FFmpegError = FFmpegError.Internal("send failed with $rc")

        override fun free() {
            freed = true
        }

        companion object {
            const val AGAIN = -11
        }
    }

    private fun frame(ptsMicros: Long) = Frame.ofAudio(
        bytes = ByteArray(2 * 480),
        sampleCount = 480,
        sampleRate = 48_000,
        channels = 1,
        sampleFormat = SampleFormat.S16,
        ptsMicros = ptsMicros,
    )

    private val ignore: (Frame) -> Unit = {}

    @Test
    fun aRefusedFrameWaitsAndGoesInFirstOnTheNextCallForItsInput() {
        val backend = GatedBackend()
        FilterGraph(backend).use { graph ->
            assertEquals(FeedResult.NeedsInput(1, 0), graph.feedInput(0, frame(0L), ignore))
            assertEquals(FeedResult.NeedsInput(1, 0), graph.feedInput(0, frame(10_000L), ignore))
            assertEquals(emptyList(), backend.taken, "input 0 took nothing yet")

            assertEquals(FeedResult.Ready(0), graph.feedInput(1, frame(0L), ignore))
            graph.feedInput(0, frame(20_000L), ignore)
            graph.flushInput(0, ignore)
            assertEquals(
                listOf(1 to 0L, 0 to 0L, 0 to 10_000L, 0 to 20_000L, 0 to null),
                backend.taken,
                "the waiting frames go in first, in order, and the flush last",
            )
            val late = assertFailsWith<IllegalStateException> { graph.feedInput(0, frame(30_000L), ignore) }
            assertTrue("flushed" in late.message.orEmpty(), "a flushed input says so: ${late.message}")
        }
        assertTrue(backend.freed)
    }

    @Test
    fun aFlushWaitsBehindTheFramesOfItsInput() {
        val backend = GatedBackend()
        FilterGraph(backend).use { graph ->
            graph.feedInput(0, frame(0L), ignore)
            assertEquals(FeedResult.NeedsInput(1, 0), graph.flushInput(0, ignore))
            graph.feedInput(1, frame(0L), ignore)
            // A second flush adds nothing, but sends what waits.
            graph.flushInput(0, ignore)
            assertEquals(listOf(1 to 0L, 0 to 0L, 0 to null), backend.taken)
        }
    }

    @Test
    fun closingAGraphClosesTheFramesThatStillWait() {
        val before = contractLiveHandleCount()
        val backend = GatedBackend()
        FilterGraph(backend).use { graph ->
            graph.feedInput(0, frame(0L), ignore)
            graph.feedInput(0, frame(10_000L), ignore)
        }
        assertTrue(backend.freed)
        assertEquals(before, contractLiveHandleCount(), "a frame that waited was never closed")
    }
}
