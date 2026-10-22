package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transcode works on its own dispatcher, and its progress still reaches the caller's thread.
 *
 * A UI caller updates its views from `onProgress`, and a view may only be touched on the thread
 * that made it. Moving the work off the caller must not move the callback with it.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class TranscodeProgressThreadTest {
    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun tmp(suffix: String): File = File.createTempFile("kiteffmpeg-progress-thread", suffix).also { files += it }

    /** A guard: calling `onProgress` from the dispatcher that does the work fails it. */
    @Test
    fun progressReachesTheCallersThreadWhileTheWorkRunsElsewhere() {
        val input = tmp(".mkv").absolutePath
        TranscodeFixtures.writeConstantRateVideo(input, Rational(30, 1), frames = 300)
        val output = tmp(".mkv").absolutePath
        val reportThreads = mutableListOf<Thread>()
        var lastReport: TranscodeProgress? = null
        val caller = newSingleThreadContext("transcode-caller")
        val callerThread = try {
            runBlocking(caller) {
                Transcoder.transcode(
                    input = input,
                    output = output,
                    spec = TranscodeFixtures.videoSpec(Rational(30, 1)),
                    onProgress = {
                        reportThreads += Thread.currentThread()
                        lastReport = it
                    },
                )
                Thread.currentThread()
            }
        } finally {
            caller.close()
        }
        assertTrue(reportThreads.isNotEmpty(), "the transcode reported no progress")
        assertTrue(reportThreads.all { it === callerThread }, "a report ran on ${reportThreads.map { it.name }.distinct()}")
        assertEquals(300L, lastReport?.framesEncoded, "the last report arrives before transcode returns")
    }
}
