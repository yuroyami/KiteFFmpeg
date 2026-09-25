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
 * Where a transcode runs, and how soon it stops.
 *
 * A transcode is long, blocking work. It used to run on the caller's own dispatcher, so a caller
 * on a single thread was frozen until the whole file was written, and a cancel sent from that
 * thread could not run before the end either. Each test here calls from one single-threaded
 * dispatcher, the way a UI thread would.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class TranscodeDispatchTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        const val FRAMES = 600
        val RATE = Rational(30, 1)
    }

    /** Twenty seconds of 30 fps video, so the transcode is still running when the test looks. */
    private fun longInput(): String = path("mkv").also {
        TranscodeFixtures.writeConstantRateVideo(it, RATE, frames = FRAMES)
    }

    /** Runs [block] on a dispatcher with exactly one thread, as a UI caller would. */
    private fun onOneThread(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val caller = newSingleThreadContext("transcode-caller")
        try {
            runBlocking(caller, block)
        } finally {
            caller.close()
        }
    }

    /**
     * The issue's first half. Red when the transcode runs on its caller's dispatcher: the launch
     * then runs to the end of the file on the only thread before this test can look.
     */
    @Test
    fun aTranscodeLeavesItsCallersDispatcherFree() {
        val input = longInput()
        val output = path("mkv")
        onOneThread {
            val transcode = launch {
                Transcoder.transcode(input = input, output = output, spec = TranscodeFixtures.videoSpec(RATE))
            }
            // One turn of the only thread.
            yield()
            assertTrue(transcode.isActive, "the transcode finished before anything else on its dispatcher could run")
            transcode.join()
        }
        assertEquals(FRAMES, TranscodeFixtures.decodedFrameIndices(output).size, "the transcode still wrote every frame")
    }

    /**
     * The issue's second half. The caller cancels from its own thread as soon as the first report
     * arrives. Red when the transcode runs on that thread: the cancel runs only after the whole
     * file is written. A cancelled sink still writes its trailer, so the output shows how far the
     * work got.
     */
    @Test
    fun cancellingFromTheCallersThreadStopsTheTranscodeEarly() {
        val input = longInput()
        val output = path("mkv")
        val baseline = contractLiveHandleCount()
        onOneThread {
            val firstReport = CompletableDeferred<TranscodeProgress>()
            val transcode = launch {
                Transcoder.transcode(
                    input = input,
                    output = output,
                    spec = TranscodeFixtures.videoSpec(RATE),
                    onProgress = { firstReport.complete(it) },
                )
            }
            withTimeout(60_000L) { firstReport.await() }
            transcode.cancelAndJoin()
        }
        val written = TranscodeFixtures.decodedFrameIndices(output).size
        // The bug writes every frame before the cancel runs. A busy machine delays the cancel too,
        // so the bound is the end of the file, not a fraction of it.
        assertTrue(written < FRAMES, "the cancelled transcode went on to write $written of $FRAMES frames")
        assertEquals(baseline, contractLiveHandleCount(), "a cancelled transcode left a native object open")
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
            Transcoder.transcode(
                input = longInput(),
                output = output,
                spec = TranscodeFixtures.videoSpec(RATE),
                dispatcher = counting,
            )
        }
        assertTrue(dispatched.value > 0, "the transcode never ran on the dispatcher it was given")
        assertEquals(FRAMES, TranscodeFixtures.decodedFrameIndices(output).size, "the transcode wrote every frame")
    }
}
