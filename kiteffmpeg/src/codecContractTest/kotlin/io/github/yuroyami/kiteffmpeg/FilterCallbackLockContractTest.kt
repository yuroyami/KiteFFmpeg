package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A filter graph's callback runs outside the graph's lock. A callback that waits on a second
 * thread, which itself needs the same graph, used to stop both threads for good.
 */
internal class FilterCallbackLockContractTest {

    private fun silence(index: Int) = Frame.ofAudio(ByteArray(1024 * 2 * 4), 1024, 48_000, 2, SampleFormat.FltP, index * 21_333L)

    @Test
    fun aCallbackThatWaitsOnAThreadNeedingTheGraphDoesNotStopEitherThread() = runBlocking {
        val graph = FilterGraph.buildAudio("anull", 48_000, SampleFormat.FltP, 2, Rational(1, 48_000))
        var delivered = 0
        // Outside this test's scope, so a deadlocked feed cannot hold the test open: the deadline
        // below reports it, and only the stuck threads are left behind.
        val feeding = CoroutineScope(Dispatchers.Default).launch {
            graph.feedInput(0, silence(0)) {
                // Another thread takes the graph's lock while this callback waits for it.
                val other = CoroutineScope(Dispatchers.Default).async { graph.setOutputFrameSize(1024) }
                runBlocking { other.await() }
                delivered++
            }
        }
        // A deadline instead of a join: a deadlocked thread cannot be cancelled, only reported.
        val finished = withTimeoutOrNull(10_000) { feeding.join() }
        assertNotNull(finished, "the feed and the thread it waited on stopped each other")
        assertEquals(1, delivered)
        graph.close()
    }

    @Test
    fun aCallbackMayCloseItsOwnGraphAndStillReadItsFrame() {
        val graph = FilterGraph.buildAudio("anull", 48_000, SampleFormat.FltP, 2, Rational(1, 48_000))
        var samples = 0
        graph.feedInput(0, silence(0)) { frame ->
            graph.close()
            samples = frame.info.sampleCount
        }
        assertEquals(1024, samples, "the frame handed over is its own reference and outlives the graph")
    }
}
