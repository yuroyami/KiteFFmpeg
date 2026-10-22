package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A call that opens or sets something up and fails carries the lines FFmpeg logged for that failure
 * (#170), with or without a log sink, and the code still types the failure as it always did.
 */
class LoggedReasonContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        FFmpeg.setLogSink(sink = null)
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** An MP4 with its `ftyp` box and the start of an `mdat` box but no `moov`, which the demuxer refuses. */
    private fun truncatedMp4(): ByteArray {
        val ftyp = box("ftyp", "isom".encodeToByteArray() + byteArrayOf(0, 0, 2, 0) + "isommp41".encodeToByteArray())
        val mdat = box("mdat", ByteArray(8))
        return ftyp + mdat
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.encodeToByteArray() + body
    }

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

    private fun cutMp4Failure(): FFmpegException =
        assertFailsWith<FFmpegException> { MediaSource.open(BytesSource(truncatedMp4())).close() }

    private fun FFmpegException.moovLine(): FFmpegLogLine = assertNotNull(
        logged.firstOrNull { "moov atom not found" in it.message },
        "the exception did not carry the refusal, it carried $logged",
    )

    @Test
    fun aCutMp4CarriesTheMissingMoovLineWithNoSinkInstalled() {
        val failure = cutMp4Failure()
        assertIs<FFmpegError.InvalidData>(failure.error, "the code still types the failure")
        val moov = failure.moovLine()
        assertEquals(FFmpegLogLevel.Error, moov.level)
        assertTrue("mov" in moov.component, "component was '${moov.component}'")
        assertTrue(!moov.message.endsWith("\n"), "the newline was not trimmed")
        assertTrue(failure.logged.all { it.level <= FFmpegLogLevel.Error }, "a milder line was kept: ${failure.logged}")
        // The message says it too, so a log of the exception alone names the reason.
        assertTrue("FFmpeg logged: $moov" in failure.message, failure.message)
        assertEquals(failure.message, failure.error.message, "the error and the exception tell the same story")
    }

    @Test
    fun aSinkStillHearsTheLineTheExceptionCarries() {
        val heard = atomic(emptyList<String>())
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { _, _, message -> heard.update { it + message } }
        val failure = cutMp4Failure()
        failure.moovLine()
        assertTrue(heard.value.any { "moov atom not found" in it }, "the sink lost the line: ${heard.value}")
    }

    @Test
    fun aSinkThatHearsOnlyFatalLinesDoesNotHideTheLineFromTheException() {
        FFmpeg.setLogSink(FFmpegLogLevel.Fatal) { _, _, _ -> }
        cutMp4Failure().moovLine()
    }

    @Test
    fun eachFailureCarriesOnlyItsOwnLines() {
        cutMp4Failure()
        val failure = assertFailsWith<FFmpegException> {
            FilterGraph.buildAudio(
                description = "volume=bogus=1",
                sampleRate = 48_000,
                sampleFormat = SampleFormat.S16,
                channels = 1,
                timeBase = Rational(1, 1_000_000),
            ).close()
        }
        assertTrue(failure.logged.none { "moov" in it.message }, "an earlier failure's line rode along: ${failure.logged}")
    }

    @Test
    fun aFilterGraphRefusingAnOptionNamesIt() {
        val failure = assertFailsWith<FFmpegException> {
            FilterGraph.buildAudio(
                description = "volume=bogus=1",
                sampleRate = 48_000,
                sampleFormat = SampleFormat.S16,
                channels = 1,
                timeBase = Rational(1, 1_000_000),
            ).close()
        }
        assertTrue(
            failure.logged.any { "bogus" in it.message && it.level <= FFmpegLogLevel.Error },
            "the graph's refusal did not name the option: ${failure.logged}",
        )
        assertTrue("bogus" in failure.message, failure.message)
    }

    @Test
    fun anEncoderRefusingASampleRateSaysWhichOne() {
        val path = contractOutputPath("mkv").also(paths::add)
        MediaSink.open(path).use { sink ->
            val failure = assertFailsWith<FFmpegException> {
                sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId("aac"), sampleRate = 12_345, channels = 2))
            }
            assertTrue(
                failure.logged.any { "12345" in it.message },
                "the encoder's refusal did not name the rate: ${failure.logged}",
            )
        }
    }

    @Test
    fun aSucceedingOpenLeavesNothingBehindForTheNextFailure() {
        MediaSource.open(BytesSource(ContractMedia.bytes)).close()
        val failure = cutMp4Failure()
        assertEquals(1, failure.logged.count { "moov atom not found" in it.message }, "logged: ${failure.logged}")
    }
}
