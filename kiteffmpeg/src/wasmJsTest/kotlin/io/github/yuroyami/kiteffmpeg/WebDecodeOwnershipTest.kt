package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The three web-backend fixes that had no test able to fail if they were reverted.
 *
 * All three are about what happens on the way OUT of a decode: a counter the caller is supposed to
 * be able to read while the decode is still running, and two objects that must be released when the
 * step after them throws. None of that is visible from a decode that succeeds, which is why a
 * passing happy path never covered any of it.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebDecodeOwnershipTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private fun openSource(module: JsAny, script: String): MediaSource {
        useCodecModule(module)
        setFakeDecodeScript(module, script)
        return MediaSource.open(OneByteSource(), emptyMap())
    }

    @Test
    fun corruptDataSkippedIsReadableWhileTheDecodeIsStillRunning() = runTest {
        val module = fakeDecodeCodecModule()
        // Damaged, good, damaged, good: the count must have moved BEFORE each frame arrives, so a
        // collector sees 1 at the first frame and 2 at the second.
        val source = openSource(module, "xgxg")
        try {
            val seenAtEachFrame = mutableListOf<Long>()
            val stream = source.streams[0]
            source.decodeStreams(listOf(stream)).collect { frame ->
                frame.use { seenAtEachFrame.add(source.corruptDataSkipped) }
            }

            assertEquals(
                listOf(1L, 2L),
                seenAtEachFrame,
                "the count must be published per packet. Reading 0 at both frames is the defect: " +
                    "it means the total is only written once the flow has finished, so a caller " +
                    "watching a long decode cannot learn it is losing data while it can still act",
            )
            assertEquals(2L, source.corruptDataSkipped, "the finished total must agree with the live one")

            // Lifetime, not per pass: a second decode ADDS to the first rather than erasing it,
            // which is what the shared KDoc promises and what zeroing at flow start broke.
            setFakeDecodeScript(module, "xgxg")
            source.decodeStreams(listOf(stream)).toList().forEach { it.close() }
            assertEquals(4L, source.corruptDataSkipped, "a second pass must accumulate, not reset")

            assertEquals(0, fakeLiveDecoders(module), "every decoder the flow built must be freed")
            assertEquals(0, fakeFrameBalance(module), "every frame allocated or cloned must be freed")
        } finally {
            source.close()
        }
    }

    @Test
    fun decodersAreFreedWhenOpeningThePacketReaderThrows() = runTest {
        val module = fakeDecodeCodecModule()
        val source = openSource(module, "gg")
        try {
            // A container has one demux cursor, so holding a reader open makes the flow's own
            // openPacketReader refuse. That refusal lands AFTER the decoders have been built, which
            // is the exact ordering the leak lived in.
            val held = source.openPacketReader(listOf(source.streams[0]))
            try {
                assertFailsWith<IllegalStateException> {
                    source.decodeStreams(source.streams).toList()
                }
            } finally {
                held.close()
            }

            assertEquals(
                source.streams.size,
                fakeDecoderOpens(module),
                "the decoders must have been built before the refusal, or this test proves nothing",
            )
            assertEquals(
                fakeDecoderOpens(module),
                fakeDecoderFrees(module),
                "every decoder built before the throw must be freed. Leaving them open is the " +
                    "defect: one codec context leaks per stream, and nothing reports it",
            )
            assertEquals(0, fakeLiveDecoders(module))
        } finally {
            source.close()
        }
    }

    @Test
    fun extractFrameReturnsTheCursorLeaseWhenOpeningTheDecoderThrows() = runTest {
        val module = fakeDecodeCodecModule()
        val source = openSource(module, "gg")
        try {
            val stream = source.streams[0]
            setFakeDecoderOpenFails(module, true)

            assertFailsWith<FFmpegException> { source.extractFrame(0L, stream) }

            assertEquals(0, fakeLiveDecoders(module), "a decoder that failed to open must free itself")
            // The real damage was never the throw. It was that the reader opened one line earlier
            // stayed open and kept the demux-cursor lease for ever, so this source could never open
            // another reader for the rest of its life.
            setFakeDecoderOpenFails(module, false)
            val after = source.openPacketReader(listOf(stream))
            after.close()

            // And the source still works: the lease was returned rather than merely not checked.
            setFakeDecodeScript(module, "gg")
            val frames = source.decodeStreams(listOf(stream)).toList()
            frames.forEach { it.close() }
            assertTrue(frames.isNotEmpty(), "the source must still decode after the failed extract")
        } finally {
            source.close()
        }
    }

    /** The smallest byte source `MediaSource.open` accepts; the fake demuxer ignores its content. */
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

    @Test
    fun theDivergenceReportIsFilledFromTheDecodeAndNotLeftEmpty() = runTest {
        // The web backend has no shared drain loop: its decode drives StreamDecoders inline through
        // four separate emit sites, and every frame now leaves through one wrapper. This pins that
        // the wrapper did not break the decode and that an honest source reports nothing.
        //
        // What it does NOT prove, said plainly: that the comparison itself runs here. This fake
        // decodes SUBTITLE streams, which declare no width and no sample rate, so the recorder
        // returns before it ever looks at a frame. Proving the web comparison needs a fake that
        // decodes video, which means a video codec type and the frame accessors to go with it, and
        // changing this fake's stream type would move a dozen unrelated tests. Native and JVM are
        // proven by falsification; the web comparison is wired and compiled and not yet exercised.
        val module = fakeDecodeCodecModule()
        val source = openSource(module, "gggg")
        try {
            val stream = source.streams[0]
            assertTrue(source.streamDivergences.isEmpty(), "nothing has decoded yet")

            source.decodeStreams(listOf(stream)).toList().forEach { it.close() }

            // The fake's frames agree with the fake's declared stream, so the honest answer is
            // still empty. What this proves is that the comparison RAN: the falsification for it
            // makes divergencesOf always disagree, and this case fails when it does.
            assertEquals(emptyList(), source.streamDivergences)
        } finally {
            source.close()
        }
    }

}
