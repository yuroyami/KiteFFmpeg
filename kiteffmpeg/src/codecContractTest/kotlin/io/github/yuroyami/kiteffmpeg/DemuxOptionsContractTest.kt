package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DemuxOptions
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [DemuxOptions] reach the demuxer, and the two seek-breaking keys never do. */
class DemuxOptionsContractTest {

    private fun mediaPath(): String = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)

    @Test
    fun aProtocolWhitelistWithoutFileRefusesAFile() {
        val refusal = assertFailsWith<FFmpegException> {
            MediaSource.open(mediaPath(), DemuxOptions(protocolWhitelist = setOf("http"))).close()
        }
        println("protocol whitelist refusal: ${refusal.error::class.simpleName}: ${refusal.message}")
    }

    @Test
    fun aFormatWhitelistWithoutTheFilesFormatRefusesIt() {
        assertFailsWith<FFmpegException> {
            MediaSource.open(mediaPath(), DemuxOptions(formatWhitelist = setOf("wav"))).close()
        }
        // The same whitelist with the file's own format opens it.
        val format = MediaSource.open(mediaPath()).use { it.formatName }
        MediaSource.open(mediaPath(), DemuxOptions(formatWhitelist = setOf(format))).close()
    }

    @Test
    fun aKeyNobodyConsumedIsReportedNotDropped() {
        MediaSource.open(mediaPath(), DemuxOptions(probeSizeBytes = 1_000_000, options = mapOf("kc_unknown" to "1"))).use { source ->
            assertTrue("kc_unknown" in source.unusedOpenOptions, "unused: ${source.unusedOpenOptions}")
            assertTrue("probesize" !in source.unusedOpenOptions, "probesize reached the demuxer: ${source.unusedOpenOptions}")
        }
    }

    @Test
    fun theSeekBreakingKeysAreRefusedOnTheTypedAndTheRawRoute() {
        val typed = assertFailsWith<FFmpegException> {
            MediaSource.open(mediaPath(), DemuxOptions(options = mapOf("usetoc" to "1")))
        }
        assertIs<FFmpegError.InvalidArgument>(typed.error)
        val raw = assertFailsWith<FFmpegException> {
            MediaSource.open(mediaPath(), mapOf("fflags" to "+fastseek"))
        }
        assertIs<FFmpegError.InvalidArgument>(raw.error)
    }
}
