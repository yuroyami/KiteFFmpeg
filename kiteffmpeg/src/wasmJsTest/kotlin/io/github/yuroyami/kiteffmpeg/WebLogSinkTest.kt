package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** FFmpeg's own log lines reach the sink on the web too, through the linked codec module. */
class WebLogSinkTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() {
        FFmpeg.setLogSink(sink = null)
        forgetCodecModule()
    }

    /** An MP4 with its `ftyp` box and the start of an `mdat` box but no `moov`, which the demuxer refuses. */
    private val truncatedMp4: ByteArray =
        ("00000018" + "66747970" + "69736f6d" + "00000200" + "69736f6d" + "6d703431" + "00000010" + "6d646174" + "0000000000000000")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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

    @Test
    fun aWarningSinkHearsTheMissingMoovAtom() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        val lines = mutableListOf<String>()
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { level, component, message -> lines += "$level $component: $message" }
        assertFailsWith<FFmpegException> { MediaSource.open(BytesSource(truncatedMp4)).close() }
        assertTrue(lines.any { it.startsWith("Error mov") && "moov atom not found" in it }, "heard: $lines")
    }

    @Test
    fun aSinkSetBeforeTheModuleAttachesHearsItsLines() = runTest {
        val lines = mutableListOf<String>()
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { _, _, message -> lines += message }
        if (!useLinkedCodecModule()) return@runTest
        assertFailsWith<FFmpegException> { MediaSource.open(BytesSource(truncatedMp4)).close() }
        assertTrue(lines.any { "moov atom not found" in it }, "heard: $lines")
    }
}
