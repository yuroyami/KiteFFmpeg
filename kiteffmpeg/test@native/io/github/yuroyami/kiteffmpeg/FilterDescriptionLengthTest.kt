package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Filter descriptions are public API input, and the audio graph builders compose them into a
 * fixed-size native buffer. A description that reaches or passes that size used to leave the
 * running length past the end of the buffer, so the next append addressed memory outside it.
 * Every length here must either build a usable graph or fail with [FFmpegException]; none may
 * corrupt memory, and the test binary surviving the run is part of the assertion.
 */
class FilterDescriptionLengthTest {

    private companion object {
        /** Size of the buffer the builders compose into, so the lengths below straddle it. */
        const val DESC_BUFFER_BYTES = 2048

        /** Empty, exactly filling the buffer, exactly overflowing it, and far past it. */
        val LENGTHS = listOf(0, DESC_BUFFER_BYTES - 1, DESC_BUFFER_BYTES, 4096, 1_048_576)
    }

    /**
     * A syntactically valid filter chain of exactly [length] ASCII bytes: one `volume` filter
     * whose numeric argument carries the padding, optionally behind an input label.
     */
    private fun description(length: Int, prefix: String = ""): String {
        if (length == 0) return ""
        val head = prefix + "volume=1."
        check(length >= head.length) { "length $length is too short to pad" }
        return head + "0".repeat(length - head.length)
    }

    private fun buildAudio(description: String, pinOutput: Boolean) = FilterGraph.buildAudio(
        description = description,
        sampleRate = 48_000,
        sampleFormat = SampleFormat.FltP,
        channels = 2,
        timeBase = Rational(1, 48_000),
        outputSampleRate = if (pinOutput) 44_100 else 0,
        outputSampleFormat = if (pinOutput) SampleFormat.FltP else SampleFormat.None,
        outputChannels = if (pinOutput) 2 else 0,
    )

    private fun buildAudioMulti(description: String, pinOutput: Boolean) = FilterGraph.buildAudioMulti(
        description = description,
        inputs = listOf(AudioInput(48_000, SampleFormat.FltP, 2, Rational(1, 48_000))),
        outputSampleRate = if (pinOutput) 44_100 else 0,
        outputSampleFormat = if (pinOutput) SampleFormat.FltP else SampleFormat.None,
        outputChannels = if (pinOutput) 2 else 0,
    )

    /** Asserts the only two legal outcomes of a build: a usable graph, or a typed failure. */
    private fun buildsOrFailsCleanly(what: String, build: () -> FilterGraph) {
        val graph = try {
            build()
        } catch (e: FFmpegException) {
            assertTrue(e.code != 0, "$what failed without an error code")
            return
        }
        graph.use { assertTrue(it.inputCount >= 1, "$what built a graph with no inputs") }
    }

    @Test
    fun singleInputAudioSurvivesEveryDescriptionLength() {
        for (length in LENGTHS) {
            for (pinOutput in listOf(false, true)) {
                buildsOrFailsCleanly("buildAudio(length=$length, pinOutput=$pinOutput)") {
                    buildAudio(description(length), pinOutput)
                }
            }
        }
    }

    @Test
    fun multiInputAudioSurvivesEveryDescriptionLength() {
        for (length in LENGTHS) {
            for (pinOutput in listOf(false, true)) {
                buildsOrFailsCleanly("buildAudioMulti(length=$length, pinOutput=$pinOutput)") {
                    buildAudioMulti(description(length, prefix = "[in0]"), pinOutput)
                }
            }
        }
    }

    @Test
    fun descriptionFillingTheBufferBuilds() {
        // Length 2047 plus the terminator is exactly the buffer: nothing more is appended, so
        // the description is used whole rather than rejected.
        buildAudio(description(DESC_BUFFER_BYTES - 1), pinOutput = false).close()
    }

    @Test
    fun descriptionPastTheBufferIsRejectedInsteadOfTruncated() {
        for (length in listOf(DESC_BUFFER_BYTES, 4096, 1_048_576)) {
            assertFailsWith<FFmpegException>("length $length must be refused") {
                buildAudio(description(length), pinOutput = false)
            }
            assertFailsWith<FFmpegException>("length $length must be refused (multi-input)") {
                buildAudioMulti(description(length, prefix = "[in0]"), pinOutput = false)
            }
        }
    }

    @Test
    fun pinnedOutputThatCannotFitIsRejected() {
        // The pins append `,aformat=...` after the description. A description that already fills
        // the buffer leaves no room for it, and the append must not write past the buffer to
        // find that out.
        assertFailsWith<FFmpegException> {
            buildAudio(description(DESC_BUFFER_BYTES - 1), pinOutput = true)
        }
        assertFailsWith<FFmpegException> {
            buildAudioMulti(description(DESC_BUFFER_BYTES - 1, prefix = "[in0]"), pinOutput = true)
        }
    }
}
