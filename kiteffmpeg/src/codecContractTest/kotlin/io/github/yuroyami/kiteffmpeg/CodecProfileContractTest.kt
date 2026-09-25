package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A stream's codec profile reaches [StreamInfo.codecProfile], as FFmpeg's own number. */
internal class CodecProfileContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun anAacLcTrackReportsItsProfile() {
        val input = path("wav")
        TranscodeFixtures.writePcm(input, sampleCount = 48_000, sampleRate = 48_000, channels = 2) { index, _ ->
            (index % 200 * 100).toShort()
        }
        val output = path("m4a")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                audioSpec = AudioEncoderSpec(codec = CodecId("aac"), sampleRate = 48_000, channels = 2),
            )
        }
        MediaSource.open(output).use { source ->
            // AV_PROFILE_AAC_LOW: FFmpeg's aac encoder writes AAC LC by default.
            assertEquals(1, source.primaryAudio?.codecProfile)
        }
    }

    @Test
    fun aPcmTrackHasNoProfile() {
        val input = path("wav")
        TranscodeFixtures.writePcm(input, sampleCount = 4_800, sampleRate = 48_000, channels = 1) { _, _ -> 0 }
        MediaSource.open(input).use { source ->
            assertEquals(null, source.primaryAudio?.codecProfile)
        }
    }
}
