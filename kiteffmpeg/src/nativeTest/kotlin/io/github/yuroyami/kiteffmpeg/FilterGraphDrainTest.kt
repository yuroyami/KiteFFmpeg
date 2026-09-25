package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The push-style filter API, around the single frame the buffersink lands its output in. Two rules
 * are covered: that frame is released after every callback, and a send the graph cannot take is
 * never retried forever. A graph with two inputs reports the input it waits for, and a graph with
 * one input fails.
 */
class FilterGraphDrainTest {

    private companion object {
        const val SAMPLE_RATE = 48_000

        /** Micro-second time-base, so a frame's pts and the graph's units are the same thing. */
        val MICROS = Rational(1, 1_000_000)
    }

    /** s16 mono samples all holding [value], so one frame's bytes cannot be mistaken for another's. */
    private fun s16Samples(samples: Int, value: Int) = ByteArray(samples * 2).also { bytes ->
        for (s in 0 until samples) {
            bytes[s * 2] = (value and 0xFF).toByte()
            bytes[s * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun s16Frame(bytes: ByteArray, ptsMicros: Long) = Frame.ofAudio(
        bytes = bytes,
        sampleCount = bytes.size / 2,
        sampleRate = SAMPLE_RATE,
        channels = 1,
        sampleFormat = SampleFormat.S16,
        ptsMicros = ptsMicros,
    )

    /** Empty description means `anull`, so the samples come back out exactly as they went in. */
    private fun passthroughGraph() = FilterGraph.buildAudio(
        description = "",
        sampleRate = SAMPLE_RATE,
        sampleFormat = SampleFormat.S16,
        channels = 1,
        timeBase = MICROS,
    )

    private fun mixGraph() = FilterGraph.buildAudioMulti(
        description = "[in0][in1]amix=inputs=2[out]",
        inputs = List(2) { AudioInput(SAMPLE_RATE, SampleFormat.FltP, 1, MICROS) },
    )

    private fun fltpSilence(samples: Int, index: Int) = Frame.ofAudio(
        bytes = ByteArray(samples * 4),
        sampleCount = samples,
        sampleRate = SAMPLE_RATE,
        channels = 1,
        sampleFormat = SampleFormat.FltP,
        ptsMicros = index.toLong() * samples * 1_000_000L / SAMPLE_RATE,
    )

    /**
     * The drain used to hand the landing frame to the callback and move straight on. A callback that
     * neither cloned nor closed it left it populated, and `av_buffersink_get_frame` MOVES its next
     * result into that same frame and requires it to be empty.
     *
     * The release is done by closing the wrapper the callback was given, which the drain states is
     * deliberate: "The wrapper itself must be closed, not just the landing frame unreffed: a
     * callback that retains the wrapper would otherwise hold an 'open' Frame whose pointer aliases
     * storage this loop reuses and the graph eventually frees." So two outputs in a row through a
     * callback that takes no ownership prove both halves: each output carries its own samples, and
     * the wrapper the callback kept is closed the moment it returns, which is the unref.
     */
    @Test
    fun theLandingFrameIsReleasedAfterEveryCallback() {
        val fed = listOf(s16Samples(960, 4_000), s16Samples(1024, -9_000))
        val seen = mutableListOf<ByteArray>()
        var lastSeen: Frame? = null

        passthroughGraph().use { graph ->
            fed.forEachIndexed { i, bytes ->
                graph.feedInput(0, s16Frame(bytes, i * 20_000L)) { out ->
                    // A callback that takes no ownership at all: no copy(), no close(). Reading the
                    // bytes here is legal because the wrapper is still open inside the callback.
                    seen += out.copyPlanesToByteArray()
                    lastSeen = out
                }
            }

            assertEquals(2, seen.size, "a passthrough graph emits one frame per fed frame")
            assertTrue(fed[0].contentEquals(seen[0]), "the first output is not the first frame's samples")
            assertTrue(fed[1].contentEquals(seen[1]), "the second output is not the second frame's samples")

            // Retaining it bought nothing: every read now refuses instead of aliasing the storage
            // the next receive lands in.
            val retained = assertFailsWith<IllegalStateException> { lastSeen!!.info }
            assertTrue(
                "Frame is closed" in (retained.message ?: ""),
                "the drain left the wrapper it gave the callback open: ${retained.message}",
            )
        }
    }

    /**
     * A two-input graph fed on one pad only: `amix` holds its output until every input has samples,
     * so each feed returns at once, with nothing produced and the other input named. Flushing that
     * input is what releases the mix.
     */
    @Test
    fun aTwoInputGraphFedOnOnePadStaysBoundedAndFinishesOnFlush() {
        var outputs = 0
        mixGraph().use { graph ->
            repeat(4) { i ->
                assertEquals(FeedResult.NeedsInput(1, 0), graph.feedInput(0, fltpSilence(1024, i)) { outputs++ })
            }
            assertEquals(0, outputs, "amix cannot emit anything before its second input has samples")

            graph.flushInput(1) { outputs++ }
            graph.flushInput(0) { outputs++ }
            assertTrue(outputs > 0, "flushing the waiting input must release the mixed frames")
        }
    }

    /**
     * The refusal rule. EAGAIN from a buffer source means the frame was not taken, so the send is
     * made again after a drain that produced output. With no output, a graph with two inputs waits
     * for the other one, which the feed reports, and a graph with one input can never take the
     * frame, which is an error instead of a retry that never ends.
     *
     * The send is injected: the FFmpeg this binds to answers a buffer source write with 0 or a hard
     * error and never with EAGAIN, so the branch is unreachable through [FilterGraph.feedInput].
     */
    @Test
    fun aRefusedSendWaitsInATwoInputGraphAndFailsInASingleInputOne() {
        val drained = mutableListOf<Frame>()
        mixGraph().use { graph ->
            assertFalse(graph.offer(index = 1, outputs = drained) { FFErrors.EAGAIN }, "a two-input graph waits")
        }
        passthroughGraph().use { graph ->
            val ex = assertFailsWith<FFmpegException> { graph.offer(index = 0, outputs = drained) { FFErrors.EAGAIN } }
            assertIs<FFmpegError.Internal>(ex.error)
            assertTrue("input 0" in ex.message.orEmpty(), "the error names the input: ${ex.message}")
        }
        assertEquals(0, drained.size, "a graph that refused cannot have produced output")
    }
}
