package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A closed frame answers nothing, including from a cache it filled while it was open.
 *
 * The JVM and native backends both cache [Frame.info], because building it is a dozen native reads
 * and callers ask for it repeatedly. A cache is a way to answer without touching the frame, which
 * is exactly what makes it dangerous here: the obvious way to write it, `cached ?: read()`, answers
 * a CLOSED frame from the cache and hands out a description of buffers that are gone.
 *
 * Both backends avoid that by checking the open state before the cache rather than after, and this
 * pins the ordering. Reading `info` while open first is the whole point: a test that only reads
 * after close never fills the cache and so cannot tell the two orderings apart.
 */
class FrameClosedContractTest {

    private fun audioFrame() = Frame.ofAudio(
        bytes = ByteArray(128),
        sampleCount = 64,
        sampleRate = 48_000,
        channels = 1,
        sampleFormat = SampleFormat.S16,
        ptsMicros = 0L,
    )

    @Test
    fun infoReadWhileOpenIsNotServedFromTheCacheAfterClose() {
        val frame = audioFrame()

        // Fill the cache. This must succeed, or the test proves nothing about caching.
        val whileOpen = frame.info
        assertEquals(64, whileOpen.sampleCount, "the frame must describe itself while it is open")

        frame.close()

        val refusal = assertFailsWith<IllegalStateException>(
            "a closed frame answered from its cache, so callers get a description of freed buffers",
        ) { frame.info }
        assertTrue(
            "Frame is closed" in (refusal.message ?: ""),
            "the refusal must say the frame is closed, said: ${refusal.message}",
        )
    }

    @Test
    fun everyReadOnAClosedFrameRefusesTheSameWay() {
        // info is the cached one, so it is the interesting case; the others are here because a
        // closed frame that refuses one read and serves another is worse than one that refuses
        // nothing, and nothing else held them together.
        val frame = audioFrame()
        frame.close()

        assertFailsWith<IllegalStateException> { frame.info }
        assertFailsWith<IllegalStateException> { frame.copy() }
        assertFailsWith<IllegalStateException> { frame.copyPlanesToByteArray() }
    }

    @Test
    fun closingTwiceIsNotAnError() {
        // Closing is how ownership is handed back, and ownership is handed back on paths that do
        // not know whether an earlier one already did it. A second close that threw would turn
        // correct cleanup into a crash.
        val frame = audioFrame()
        frame.close()
        frame.close()
    }
}
