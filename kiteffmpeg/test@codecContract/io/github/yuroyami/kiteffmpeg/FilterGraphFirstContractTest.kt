package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a collector receives when it ends the flow early.
 *
 * `take` and `first` end a flow by throwing OUT of `emit`, and they do it AFTER the value has
 * reached the collector. The JVM backend read that throw as "the emission failed" and closed the
 * frame, so `process(input).first()` handed back a frame the library had already closed and every
 * read on it refused. The native backend had no catch at all, so a genuinely cancelled emission
 * stranded its clone instead.
 *
 * Neither side can tell the two apart, so neither decides: an undelivered frame is held by the
 * graph and closed when the graph closes. Frame close is idempotent, so a collector that did
 * receive the frame is unaffected by that.
 */
class FilterGraphFirstContractTest {

    private companion object {
        const val SAMPLE_RATE = 48_000
        val MICROS = Rational(1, 1_000_000)
    }

    private fun s16Frame(samples: Int, value: Int, ptsMicros: Long) = Frame.ofAudio(
        bytes = ByteArray(samples * 2) { i -> if (i % 2 == 0) (value and 0xFF).toByte() else 0 },
        sampleCount = samples,
        sampleRate = SAMPLE_RATE,
        channels = 1,
        sampleFormat = SampleFormat.S16,
        ptsMicros = ptsMicros,
    )

    private fun passthroughGraph() = FilterGraph.buildAudio(
        description = "",
        sampleRate = SAMPLE_RATE,
        sampleFormat = SampleFormat.S16,
        channels = 1,
        timeBase = MICROS,
    )

    @Test
    fun theFrameFirstHandsBackIsStillOpen() = runBlocking {
        passthroughGraph().use { graph ->
            val input = flowOf(s16Frame(256, 7, 0), s16Frame(256, 9, 5_333))
            val frame = graph.process(input).first()
            frame.use {
                // The whole point: readable. A closed frame refuses every one of these.
                assertEquals(256, it.info.sampleCount, "the frame first() returned was closed under the caller")
                assertTrue(it.info.sampleRate == SAMPLE_RATE)
            }
        }
    }

    @Test
    fun takeEndsTheFlowAndLeavesWhatItTookUsable() = runBlocking {
        passthroughGraph().use { graph ->
            val input = flowOf(s16Frame(256, 1, 0), s16Frame(256, 2, 5_333), s16Frame(256, 3, 10_666))
            val frames = graph.process(input).take(2).toList()
            assertEquals(2, frames.size)
            frames.forEach { frame ->
                frame.use { assertEquals(256, it.info.sampleCount, "take closed a frame it handed over") }
            }
        }
    }

    @Test
    fun closingTheGraphAfterAnEarlyEndIsSafe() = runBlocking {
        // Closing twice, after an early end, is safe: close is idempotent on both sides and the
        // flow already closed the graph when it ended.
        val graph = passthroughGraph()
        val input = flowOf(s16Frame(256, 4, 0), s16Frame(256, 5, 5_333))
        val frame = graph.process(input).first()
        frame.close()
        graph.close()
        graph.close()
    }
}
