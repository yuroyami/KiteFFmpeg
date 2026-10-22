package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * What one handle costs the JVM bridge when many are already live: the table finds a free slot for
 * each new handle, and while a filter graph is open, whose filter contexts are borrowed handles, it
 * checks every slot on each close. The cost is printed and never asserted, because a busy machine
 * moves it.
 */
class HandleTableCostTest {
    private val samples = ByteArray(32)

    private fun smallFrame() = Frame.ofAudio(samples, 16, 48_000, 1, SampleFormat.S16, ptsMicros = 0L)

    /** Nanoseconds to make and close one frame, with [live] other frames held open meanwhile. */
    private fun churn(live: Int): Long {
        val held = List(live) { smallFrame() }
        try {
            repeat(1_000) { smallFrame().close() }
            val rounds = 5_000
            val elapsed = measureTime { repeat(rounds) { smallFrame().close() } }
            return elapsed.inWholeNanoseconds / rounds
        } finally {
            held.forEach(Frame::close)
        }
    }

    @Test
    fun aHandleCostsTheSameWithManyLiveOrReportsHowMuchMore() {
        val baseline = contractLiveHandleCount()
        for (live in listOf(0, 1_000, 10_000)) {
            val alone = churn(live)
            val withGraph = FilterGraph.buildAudio("anull", 48_000, SampleFormat.S16, 1, Rational(1, 48_000)).use { churn(live) }
            println("handle table cost: $live live, $alone ns per frame alone, $withGraph ns with a filter graph open")
        }
        assertEquals(baseline, contractLiveHandleCount(), "the measurement leaked a handle")
    }
}
