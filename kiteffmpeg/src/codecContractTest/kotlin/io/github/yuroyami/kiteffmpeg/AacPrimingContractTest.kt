package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An AAC file written here decodes to the samples that were written, not to the encoder's
 * priming as well.
 *
 * The AAC encoder starts every stream with 1024 priming samples at a negative timestamp, and the
 * container tells a decoder to drop them: an MP4 edit list, or a Matroska codec delay. The muxer
 * used to shift every timestamp up to zero, which threw that marker away, so a 960-sample clip
 * decoded to 2048 samples.
 */
internal class AacPrimingContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        const val RATE = 48_000

        /** 20 ms, less than one AAC frame, so any priming that survives shows as a whole frame. */
        const val WRITTEN = 960

        /** Samples in one AAC frame. The last frame is padded, so the tail may keep up to one. */
        const val AAC_FRAME = 1024
    }

    /** Transcodes [WRITTEN] samples of a 1 kHz mono tone to AAC in a file with [extension]. */
    private fun aacTone(extension: String): String {
        val input = path("wav")
        TranscodeFixtures.writePcm(input, sampleCount = WRITTEN, sampleRate = RATE, channels = 1) { index, _ ->
            (sin(2.0 * PI * 1000.0 * index / RATE) * 12_000.0).roundToInt().toShort()
        }
        val output = path(extension)
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                audioSpec = AudioEncoderSpec(codec = CodecId("aac"), sampleRate = RATE, channels = 1),
            )
        }
        return output
    }

    private fun assertPrimingDropped(output: String, askTheOracle: Boolean) {
        val allowed = WRITTEN.toLong()..AAC_FRAME.toLong()
        val decoded = TranscodeFixtures.decodedSampleCount(output)
        assertTrue(
            decoded in allowed,
            "$WRITTEN samples were written and $decoded decoded. More than $AAC_FRAME means the " +
                "encoder's $AAC_FRAME priming samples are played as audio.",
        )
        if (!askTheOracle) return
        MediaOracle.audioSampleCount(output)?.let { probed ->
            assertTrue(probed in allowed, "ffprobe decodes $probed samples from $output")
        }
    }

    @Test
    fun anM4aFileDecodesToTheSamplesThatWereWritten() = assertPrimingDropped(aacTone("m4a"), askTheOracle = true)

    /**
     * Matroska carries the delay as a codec delay element. The FFmpeg this library embeds turns it
     * into skipped samples on decode. The host's `ffprobe` may be an older FFmpeg that ignores the
     * element for AAC (Homebrew's 8.0 decodes 2048 samples from the same file), so this case asks
     * only this library's decoder.
     */
    @Test
    fun aMatroskaFileDecodesToTheSamplesThatWereWritten() = assertPrimingDropped(aacTone("mkv"), askTheOracle = false)
}
