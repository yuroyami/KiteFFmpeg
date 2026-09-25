package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a remux runs, and how soon it stops.
 *
 * A remux used to run its whole copy loop on the caller's own dispatcher, so a remux started from
 * an app's main thread blocked that thread until the file was written, and a cancel sent from it
 * could not run before the end. Each test here calls from one single-threaded dispatcher, the
 * way a UI thread would.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class RemuxDispatchTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        /** A copy is fast, so the input is long enough that a cancel lands well before the end. */
        const val FRAMES = 3_000
        val RATE = Rational(30, 1)
    }

    private fun longInput(): String = path("mkv").also {
        TranscodeFixtures.writeConstantRateVideo(it, RATE, frames = FRAMES)
    }

    /** Runs [block] on a dispatcher with exactly one thread, as a UI caller would. */
    private fun onOneThread(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val caller = newSingleThreadContext("remux-caller")
        try {
            runBlocking(caller, block)
        } finally {
            caller.close()
        }
    }

    /**
     * Red when the remux runs on its caller's dispatcher: the launch then copies the whole file on
     * the only thread during the yield, and is finished when the test looks.
     */
    @Test
    fun aRemuxLeavesItsCallersDispatcherFree() {
        val input = longInput()
        val output = path("mkv")
        onOneThread {
            val remux = launch { Remuxer.remux(input = input, output = output) }
            // One turn of the only thread.
            yield()
            assertTrue(remux.isActive, "the remux finished before anything else on its dispatcher could run")
            remux.join()
        }
        assertEquals(FRAMES, TranscodeFixtures.decodedFrameIndices(output).size, "the remux still copied every frame")
    }

    /**
     * The caller cancels from its own thread as soon as the first report arrives. Red when the
     * remux runs on that thread: the cancel runs only after the whole file is copied. A cancelled
     * sink still writes its trailer, so the output shows how far the copy got.
     */
    @Test
    fun cancellingFromTheCallersThreadStopsTheRemuxEarly() {
        val input = longInput()
        val output = path("mkv")
        val baseline = contractLiveHandleCount()
        onOneThread {
            val firstReport = CompletableDeferred<Long>()
            val remux = launch {
                Remuxer.remux(input = input, output = output, onProgress = { firstReport.complete(it) })
            }
            withTimeout(60_000L) { firstReport.await() }
            remux.cancelAndJoin()
        }
        val written = TranscodeFixtures.decodedFrameIndices(output).size
        assertTrue(written < FRAMES / 2, "the cancelled remux went on to copy $written of $FRAMES frames")
        assertEquals(baseline, contractLiveHandleCount(), "a cancelled remux left a native object open")
    }

    /** Red when the dispatcher argument is ignored: nothing is ever dispatched to it. */
    @Test
    fun theWorkRunsOnTheDispatcherTheCallerPasses() {
        val dispatched = atomic(0)
        val counting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val output = path("mkv")
        onOneThread {
            Remuxer.remux(input = longInput(), output = output, dispatcher = counting)
        }
        assertTrue(dispatched.value > 0, "the remux never ran on the dispatcher it was given")
        assertEquals(FRAMES, TranscodeFixtures.decodedFrameIndices(output).size, "the remux copied every frame")
    }
}
