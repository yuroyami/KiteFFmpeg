package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** FFmpeg's own log lines reach the sink the caller installs, at its level and no other. */
class LogSinkContractTest {

    private class Line(val level: FFmpegLogLevel, val component: String, val message: String)

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

    /** Every line the sink heard while [block] ran with a sink at [level] installed. */
    private fun heardDuring(level: FFmpegLogLevel, block: () -> Unit): List<Line> {
        val lines = atomic(emptyList<Line>())
        FFmpeg.setLogSink(level) { lineLevel, component, message ->
            lines.update { it + Line(lineLevel, component, message) }
        }
        try {
            block()
        } finally {
            FFmpeg.setLogSink(sink = null)
        }
        return lines.value
    }

    @Test
    fun aWarningSinkHearsTheMissingMoovAtom() {
        val lines = heardDuring(FFmpegLogLevel.Warning) {
            assertFailsWith<FFmpegException> { MediaSource.open(BytesSource(truncatedMp4())).close() }
        }
        val moov = assertNotNull(
            lines.firstOrNull { "moov atom not found" in it.message },
            "the sink did not hear the refusal: ${lines.map { "${it.level} ${it.component}: ${it.message}" }}",
        )
        assertEquals(FFmpegLogLevel.Error, moov.level)
        assertTrue("mov" in moov.component, "component was '${moov.component}'")
        assertTrue(!moov.message.endsWith("\n"), "the newline was not trimmed")
    }

    @Test
    fun aFatalSinkDoesNotHearAnError() {
        val lines = heardDuring(FFmpegLogLevel.Fatal) {
            assertFailsWith<FFmpegException> { MediaSource.open(BytesSource(truncatedMp4())).close() }
        }
        assertTrue(lines.none { "moov atom not found" in it.message }, "an error line passed a fatal sink")
    }

    @Test
    fun aSinkThatThrowsDoesNotBreakTheCall() {
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { _, _, _ -> error("a sink that throws") }
        try {
            // The refusal is still the typed one, and the thrown exception went nowhere.
            assertFailsWith<FFmpegException> { MediaSource.open(BytesSource(truncatedMp4())).close() }
        } finally {
            FFmpeg.setLogSink(sink = null)
        }
    }

    @Test
    fun levelsBetweenTwoNamesTakeTheMoreSevereName() {
        assertEquals(FFmpegLogLevel.Warning, FFmpegLogLevel.of(24))
        assertEquals(FFmpegLogLevel.Warning, FFmpegLogLevel.of(31))
        assertEquals(FFmpegLogLevel.Trace, FFmpegLogLevel.of(99))
        assertEquals(FFmpegLogLevel.Panic, FFmpegLogLevel.of(-1))
    }
}
