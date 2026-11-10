package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A custom output whose flush and close both fail reports both (#112). The close ran in a finally
 * and its failure replaced the flush's, so the error that said the bytes never arrived was gone
 * from the exception, its cause and its suppressed list.
 */
internal class SinkFinishFailuresContractTest {

    private class FailingFinishSink : MediaByteSink {
        var flushes = 0
        var closes = 0
        override val seekable: Boolean = false
        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        override fun seek(position: Long) = throw UnsupportedOperationException("not seekable")
        override fun flush() {
            flushes++
            throw IllegalStateException("flush lost bytes")
        }
        override fun close() {
            closes++
            throw IllegalStateException("close also failed")
        }
    }

    @Test
    fun aFailingFlushStaysTheErrorWhenTheCloseFailsToo() {
        val bytes = FailingFinishSink()
        val sink = MediaSink.open(bytes, "matroska")
        val encoder = sink.addVideoEncoder(
            VideoEncoderSpec(codec = CodecId("mpeg4"), width = 32, height = 32, frameRate = Rational(25, 1), bitrateBps = 200_000),
        )
        val y = ByteArray(32 * 32) { (it % 200).toByte() }
        val chroma = ByteArray(32 * 32 / 4) { 128.toByte() }
        runBlocking { encoder.drive(flowOf(Frame.ofVideo(y + chroma + chroma, 32, 32, PixelFormat.Yuv420p, 0))) }
        val failure = assertFailsWith<IllegalStateException> { sink.close() }
        assertEquals("flush lost bytes", failure.message, "the flush's failure is the error")
        assertTrue(
            failure.suppressedExceptions.any { it.message == "close also failed" },
            "the close's failure is kept beside it: ${failure.suppressedExceptions}",
        )
        sink.close()
        assertEquals(1, bytes.flushes, "the byte sink is flushed once")
        assertEquals(1, bytes.closes, "the byte sink is closed once")
    }
}
