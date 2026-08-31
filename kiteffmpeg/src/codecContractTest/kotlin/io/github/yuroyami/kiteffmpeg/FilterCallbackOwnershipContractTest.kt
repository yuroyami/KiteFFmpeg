package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A filter callback's frame is on loan, and every backend has to enforce that rather than trust it.
 *
 * The graph lands its output in ONE frame and reuses it, so a callback that keeps the reference
 * would be holding a frame whose contents the next output overwrites. The backends answer this by
 * closing the wrapper the moment the callback returns, which both performs the unref the landing
 * frame needs and makes any retained reference refuse instead of quietly aliasing live storage.
 *
 * ### Why this is here and not only in the native suite
 *
 * `FilterGraphDrainTest` has pinned the native backend for a while. The JVM and Android backend is
 * a separate implementation of the same rule, and on 2026-08-30 nothing covered it: deleting its
 * `finally { callback.close() }` left all 70 JVM tests passing. This suite runs on JVM, macOS
 * native and a real Android device, so the rule is now proven everywhere it is implemented.
 *
 * Audio passthrough, so this needs no media file and no decoder.
 */
class FilterCallbackOwnershipContractTest {

    private companion object {
        const val SAMPLE_RATE = 48_000
        val MICROS = Rational(1, 1_000_000)
    }

    /** s16 mono samples all holding [value], so one frame's bytes cannot be mistaken for another's. */
    private fun samples(count: Int, value: Int) = ByteArray(count * 2).also { bytes ->
        for (s in 0 until count) {
            bytes[s * 2] = (value and 0xFF).toByte()
            bytes[s * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun frame(bytes: ByteArray, ptsMicros: Long) = Frame.ofAudio(
        bytes = bytes,
        sampleCount = bytes.size / 2,
        sampleRate = SAMPLE_RATE,
        channels = 1,
        sampleFormat = SampleFormat.S16,
        ptsMicros = ptsMicros,
    )

    /** Empty description means `anull`, so samples come back out exactly as they went in. */
    private fun passthrough() = FilterGraph.buildAudio(
        description = "",
        sampleRate = SAMPLE_RATE,
        sampleFormat = SampleFormat.S16,
        channels = 1,
        timeBase = MICROS,
    )

    private fun assertRefusesAfterCallback(retained: Frame?, which: String) {
        val frame = requireNotNull(retained) { "$which never delivered a frame to its callback" }
        val refusal = assertFailsWith<IllegalStateException>(
            "$which left the wrapper open, so a callback that kept it is reading storage the graph reuses",
        ) { frame.info }
        assertTrue(
            "Frame is closed" in (refusal.message ?: ""),
            "the refusal must say the frame is closed, said: ${refusal.message}",
        )
    }

    @Test
    fun aFrameKeptPastAFeedCallbackRefusesEveryRead() {
        val fed = listOf(samples(480, 4_000), samples(512, -9_000))
        val seen = mutableListOf<ByteArray>()
        var retained: Frame? = null

        passthrough().use { graph ->
            fed.forEachIndexed { index, bytes ->
                // A callback that takes no ownership at all: no copy(), no close(). Reading the
                // bytes inside the callback is legal, which is what makes the retention the only
                // thing under test here.
                graph.feedInput(0, frame(bytes, index * 20_000L)) { out ->
                    seen += out.copyPlanesToByteArray()
                    retained = out
                }
            }

            assertEquals(2, seen.size, "a passthrough graph emits one frame per fed frame")
            // Each output carries its OWN samples, which is the other half of the same rule: if the
            // landing frame were not released between callbacks the second output could not arrive.
            assertTrue(fed[0].contentEquals(seen[0]), "the first output is not the first frame's samples")
            assertTrue(fed[1].contentEquals(seen[1]), "the second output is not the second frame's samples")

            assertRefusesAfterCallback(retained, "feedInput")
        }
    }

    @Test
    fun aFrameKeptPastAFlushCallbackRefusesEveryRead() {
        // The flush drains through the same loop, and nothing has ever asserted that, so a backend
        // could hold the rule on one path and drop it on the other without anything noticing.
        //
        // A passthrough graph cannot show this: it emits on the feed, so the flush has nothing left
        // to deliver. `amix` fed on one pad only holds its output until every input has samples, so
        // flushing the starved pad is what releases the mix, and the frame reaches the FLUSH
        // callback rather than a feed one.
        var retained: Frame? = null
        FilterGraph.buildAudioMulti(
            description = "[in0][in1]amix=inputs=2[out]",
            inputs = List(2) { AudioInput(SAMPLE_RATE, SampleFormat.FltP, 1, MICROS) },
        ).use { graph ->
            repeat(4) { index -> graph.feedInput(0, silence(1024, index)) { out -> retained = out } }
            graph.flushInput(1) { out -> retained = out }
            graph.flushInput(0) { out -> retained = out }
            assertRefusesAfterCallback(retained, "flushInput")
        }
    }

    /** Planar float silence, which is what `amix` mixes in the native suite's twin of this test. */
    private fun silence(count: Int, index: Int) = Frame.ofAudio(
        bytes = ByteArray(count * 4),
        sampleCount = count,
        sampleRate = SAMPLE_RATE,
        channels = 1,
        sampleFormat = SampleFormat.FltP,
        ptsMicros = index.toLong() * count * 1_000_000L / SAMPLE_RATE,
    )
}
