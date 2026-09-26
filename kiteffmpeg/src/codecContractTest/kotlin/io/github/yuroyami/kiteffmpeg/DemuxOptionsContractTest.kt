package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DemuxOptions
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** [bytes] as a seekable input. */
    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }

    // Headerless input has nothing to probe. Naming the format is the only way in, as ffmpeg -f is.
    @Test
    fun aNamedFormatOpensHeaderlessAudio() {
        // One second of a 1 kHz tone, 48 kHz mono signed 16-bit little-endian, with no header at all.
        val tone = ByteArray(48_000 * 2)
        for (i in 0 until 48_000) {
            val sample = (sin(2 * PI * 1_000 * i / 48_000) * 12_000).roundToInt()
            tone[i * 2] = (sample and 0xFF).toByte()
            tone[i * 2 + 1] = (sample shr 8).toByte()
        }
        val options = DemuxOptions(format = "s16le", options = mapOf("sample_rate" to "48000", "ch_layout" to "mono"))
        MediaSource.open(BytesSource(tone), options).use { source ->
            assertEquals(48_000, source.streams.single { it.type == MediaType.Audio }.audio?.sampleRate)
            assertTrue("kiteffmpeg_input_format" !in source.unusedOpenOptions, "the key reached FFmpeg: ${source.unusedOpenOptions}")
        }
    }

    @Test
    fun aNamedFormatOpensHeaderlessVideo() {
        // Ten 64 by 48 yuv420p frames of flat grey.
        val frames = ByteArray(64 * 48 * 3 / 2 * 10) { 128.toByte() }
        val options = DemuxOptions(
            format = "rawvideo",
            options = mapOf("video_size" to "64x48", "pixel_format" to "yuv420p", "framerate" to "25"),
        )
        MediaSource.open(BytesSource(frames), options).use { source ->
            assertEquals(64, source.streams.single { it.type == MediaType.Video }.video?.width)
        }
    }

    @Test
    fun aFormatThisBuildDoesNotCarryIsATypedRefusal() {
        val failure = assertFailsWith<FFmpegException> {
            MediaSource.open(mediaPath(), DemuxOptions(format = "no_such_demuxer")).close()
        }
        assertIs<FFmpegError.DemuxerNotFound>(failure.error, "was ${failure.error}")
    }
}
