package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [bufferFrames] releases what the collector never receives; `buffer()` does not.
 *
 * The fake codec module counts every frame allocated, cloned and freed, so "nothing was stranded"
 * is a number here rather than an argument. That matters because the leak this operator exists for
 * is invisible by construction: the frames go missing inside a channel nobody can reach, and no
 * exception is thrown and no counter in the library moves.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class BufferFramesTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    /** Eight good packets, so a `take(1)` leaves plenty behind to strand. */
    private fun openSource(module: JsAnyAlias): MediaSource {
        useCodecModule(module)
        setFakeDecodeScript(module, "gggggggg")
        return MediaSource.open(OneByteSource(), emptyMap())
    }

    @Test
    fun bufferFramesClosesEveryFrameTheCollectorNeverReceives() = runTest {
        val module = fakeDecodeCodecModule()
        val source = openSource(module)
        try {
            val stream = source.streams[0]
            val received = source.decodeStreams(listOf(stream))
                .bufferFrames()
                .take(1)
                .toList()

            assertEquals(1, received.size, "take(1) must deliver exactly one frame")
            // The delivered frame is still the collector's, so close it the way any collector must.
            received.forEach { it.close() }

            assertEquals(
                0,
                fakeFrameBalance(module),
                "frames were left allocated after a cancelled buffered decode: that is the leak " +
                    "this operator exists to prevent, and it is silent, so only this count sees it",
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun theStandardBufferIsTheHazardThisReplaces() = runTest {
        // Not a defect in the standard library: `buffer()` has no idea its elements own anything.
        // Pinned so the operator above has a stated reason to exist, and so anyone who proposes
        // deleting it can see what goes back to happening.
        val module = fakeDecodeCodecModule()
        val source = openSource(module)
        try {
            val stream = source.streams[0]
            val received = source.decodeStreams(listOf(stream))
                .buffer()
                .take(1)
                .toList()
            received.forEach { it.close() }

            assertTrue(
                fakeFrameBalance(module) > 0,
                "plain buffer() is expected to strand frames here; if this ever reads zero the " +
                    "standard library gained a hook it did not have, and bufferFrames can be " +
                    "reconsidered rather than assumed still necessary",
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun aFullyCollectedBufferedFlowStrandsNothingEither() = runTest {
        // The happy path still has to balance: an operator that closed frames it also delivered
        // would pass the first test and break every real caller.
        val module = fakeDecodeCodecModule()
        val source = openSource(module)
        try {
            val stream = source.streams[0]
            val received = source.decodeStreams(listOf(stream)).bufferFrames().toList()
            assertTrue(received.size > 1, "the fake script must produce several frames")
            received.forEach { it.close() }
            assertEquals(0, fakeFrameBalance(module), "a fully collected flow must balance")
        } finally {
            source.close()
        }
    }

    private class OneByteSource : MediaByteSource {
        override val size: Long = 1L
        override val seekable: Boolean = true
        private var consumed = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (consumed) return -1
            into[offset] = 0
            consumed = true
            return 1
        }

        override fun seek(position: Long) {
            consumed = position != 0L
        }

        override fun close(): Unit = Unit
    }
}

private typealias JsAnyAlias = kotlin.js.JsAny
