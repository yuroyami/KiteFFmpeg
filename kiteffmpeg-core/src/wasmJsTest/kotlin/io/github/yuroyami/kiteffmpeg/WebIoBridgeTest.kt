package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * KC-WEB-IO (spec 17.22.B). The staged web reader against its own written contract.
 *
 * `MediaByteSource`'s KDoc promises two things this backend did not keep: close runs exactly once,
 * and seek is never called on a source that says it is not seekable. JVM honours both, Native
 * honours seekable, and this one honoured neither and said nothing. Those are contract breaks
 * rather than quality bars, which is why they are the arms that come first.
 */
class WebIoBridgeTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    /**
     * Records what the bridge did to it.
     *
     * [seekable] false must mean [seek] is never called at all, not that its argument is ignored:
     * a source that cannot rewind may still be mid-stream, and rewinding it is what corrupts it.
     */
    private class FakeByteSource(
        private val content: ByteArray,
        override val seekable: Boolean = true,
        override val size: Long? = content.size.toLong(),
        private val failAfterBytes: Int = -1,
    ) : MediaByteSource {
        var closeCount: Int = 0
            private set
        val seekCalls: MutableList<Long> = mutableListOf()
        private var position = 0
        private var served = 0

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (failAfterBytes >= 0 && served >= failAfterBytes) {
                throw IllegalStateException("scripted read failure at $served bytes")
            }
            if (position >= content.size) return -1
            val n = minOf(length, content.size - position)
            content.copyInto(into, offset, position, position + n)
            position += n
            served += n
            return n
        }

        override fun seek(position: Long) {
            seekCalls += position
            this.position = position.toInt()
        }

        override fun close() {
            closeCount++
        }
    }

    private fun attachFake(): JsAnyHolder {
        val module = fakeCodecModule()
        useCodecModule(module)
        return JsAnyHolder(module)
    }

    private class JsAnyHolder(val module: kotlin.js.JsAny)

    @Test
    fun theSourceIsClosedExactlyOnceAfterStaging() {
        attachFake()
        val source = FakeByteSource(ByteArray(1000) { it.toByte() })
        WebIoBridge.install(source)
        assertEquals(1, source.closeCount, "staging consumes the source whole; it must then close it")
    }

    /** The path that actually leaks in practice: the source is gone and the handle is not. */
    @Test
    fun theSourceIsClosedWhenStagingFailsPartWay() {
        attachFake()
        val source = FakeByteSource(ByteArray(200_000) { it.toByte() }, failAfterBytes = 65_536)
        assertFailsWith<IllegalStateException> { WebIoBridge.install(source) }
        assertEquals(1, source.closeCount, "a source that threw mid-stage must still be closed once")
    }

    @Test
    fun aNonSeekableSourceIsNeverSeeked() {
        attachFake()
        val source = FakeByteSource(ByteArray(1000) { it.toByte() }, seekable = false)
        WebIoBridge.install(source)
        assertTrue(
            source.seekCalls.isEmpty(),
            "MediaByteSource promises seek is never called when seekable is false, was ${source.seekCalls}",
        )
    }

    @Test
    fun aSeekableSourceIsRewoundExactlyOnce() {
        attachFake()
        val source = FakeByteSource(ByteArray(1000) { it.toByte() })
        WebIoBridge.install(source)
        assertEquals(listOf(0L), source.seekCalls, "a seekable source is staged from the start, once")
    }

    /**
     * Every byte value, across a chunk boundary, byte-identical in codec memory.
     *
     * This is the arm that guards the crossing-count fix. Packing a chunk into one JS call is only
     * correct while every value 0..255 survives it; anything that treats the payload as text
     * mangles the high half and leaves the low half looking perfect, which is the failure a ramp
     * catches and an ASCII fixture does not.
     */
    @Test
    fun everyByteValueSurvivesStagingAcrossAChunkBoundary() {
        val holder = attachFake()
        val content = ByteArray(70_000) { (it % 256).toByte() }
        val source = FakeByteSource(content)
        WebIoBridge.install(source)
        val staged = readBytes(holder.module, lastMallocPointer(holder.module), content.size)
        assertContentEquals(content, staged, "the staged bytes must equal the source's, value for value")
    }

    @Test
    fun anOversizeSourceRefusesBeforeAllocatingAndStillCloses() {
        val holder = attachFake()
        val before = mallocCount(holder.module)
        val source = FakeByteSource(ByteArray(16), size = 513L * 1024 * 1024)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Unsupported, "an oversize source is a typed refusal")
        assertEquals(before, mallocCount(holder.module), "the refusal must not have staged anything")
        assertEquals(1, source.closeCount, "a refused source is still the bridge's to close")
    }

    @Test
    fun aSourceOfUnknownSizeRefusesBeforeAllocatingAndStillCloses() {
        val holder = attachFake()
        val before = mallocCount(holder.module)
        val source = FakeByteSource(ByteArray(16), size = null)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Unsupported, "a live stream is a typed refusal")
        assertEquals(before, mallocCount(holder.module), "the refusal must not have staged anything")
        assertEquals(1, source.closeCount, "a refused source is still the bridge's to close")
    }
}
