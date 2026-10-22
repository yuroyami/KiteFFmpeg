package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An output written into application-owned bytes: the same bytes a path gets, a streamable
 * container into a sink that cannot seek, a clear refusal where a container has to seek, and a
 * sink's own failure surfacing as the cause of the error it causes.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class MediaByteSinkContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Bytes in memory, with an optional seek, a throw after [failAfter] bytes and a call log. */
    private class MemorySink(
        override val seekable: Boolean,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : MediaByteSink {
        var bytes = ByteArray(0)
        var position = 0
        var largestWrite = 0
        var flushes = 0
        var closes = 0
        var written = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (written + length > failAfter) throw IllegalStateException("the upload stream went away")
            written += length
            largestWrite = maxOf(largestWrite, length)
            if (position + length > this.bytes.size) this.bytes = this.bytes.copyOf(position + length)
            bytes.copyInto(this.bytes, position, offset, offset + length)
            position += length
        }

        override fun seek(position: Long) {
            check(seekable) { "seek on a sink that said it cannot" }
            this.position = position.toInt()
        }

        override fun flush() {
            flushes++
        }

        override fun close() {
            closes++
        }
    }

    /** Two seconds of mpeg4 video and AAC audio in Matroska. */
    private fun fixture(): String? {
        val output = path("mkv")
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:v", "mpeg4", "-c:a", "aac",
            ),
            output,
        )
        return output.takeIf { written }
    }

    /** Copies every stream of [input] into [sink], then closes it. */
    private fun remux(input: String, sink: MediaSink) {
        sink.use {
            MediaSource.open(input).use { source ->
                val copies = source.streams.associate { it.index to sink.addCopyStream(source, it) }
                source.openPacketReader(source.streams).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use { copies.getValue(it.streamIndex).write(it) }
                    }
                }
            }
        }
    }

    @Test
    fun aRemuxIntoMemoryIsByteIdenticalToTheSameRemuxIntoAFile() {
        val input = fixture() ?: return println("byte sink contract degraded: no ffmpeg")
        // Bit exact, so neither output carries a random segment id or the writing library's version.
        val options = mapOf("fflags" to "+bitexact")
        for (format in listOf("matroska", "mp4")) {
            val file = path(if (format == "mp4") "mp4" else "mkv")
            remux(input, MediaSink.open(file, format, options))
            val memory = MemorySink(seekable = true)
            remux(input, MediaSink.open(memory, format, options))
            assertContentEquals(readContractBytes(file), memory.bytes, "$format differs between a path and a byte sink")
            assertEquals(1, memory.flushes, "$format: one flush after the last byte")
            assertEquals(1, memory.closes, "$format: one close")
        }
    }

    @Test
    fun fragmentedMp4GoesIntoASinkThatCannotSeek() {
        val input = fixture() ?: return println("byte sink contract degraded: no ffmpeg")
        val memory = MemorySink(seekable = false)
        remux(input, MediaSink.open(memory, "mp4", mapOf("movflags" to "frag_keyframe+empty_moov")))
        // Read back through the input door, so the bytes never touch a file.
        MediaSource.open(BytesSource(memory.bytes), emptyMap()).use { source ->
            assertEquals(2, source.streams.size)
            val duration = assertNotNullDuration(source)
            assertTrue(duration in 1_900_000L..2_100_000L, "the fragments hold two seconds, not $duration")
        }
    }

    private fun assertNotNullDuration(source: MediaSource): Long = source.durationMicros ?: fail("no duration")

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

    @Test
    fun aContainerThatHasToSeekRefusesASinkThatCannotAndSaysWhatToDo() {
        val input = fixture() ?: return println("byte sink contract degraded: no ffmpeg")
        val memory = MemorySink(seekable = false)
        val failure = assertFailsWith<FFmpegException> { remux(input, MediaSink.open(memory, "mp4")) }
        assertIs<FFmpegError.InvalidArgument>(failure.error, "was ${failure.error}")
        assertTrue("movflags" in (failure.message ?: ""), "the refusal names the way out: ${failure.message}")
        assertEquals(1, memory.closes, "the sink is still closed once")
    }

    @Test
    fun aSinkThatThrowsFailsTheWriteWithItsOwnException() {
        val input = fixture() ?: return println("byte sink contract degraded: no ffmpeg")
        val memory = MemorySink(seekable = true, failAfter = 1_000)
        val failure = assertFailsWith<FFmpegException> { remux(input, MediaSink.open(memory, "matroska")) }
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertTrue(
            causes.any { it is IllegalStateException && it.message == "the upload stream went away" },
            "the sink's exception is lost: $causes",
        )
        assertEquals(1, memory.closes, "the sink is closed once, after the failure")
    }

    @Test
    fun theMuxerNeverHoldsMoreThanItsBufferAheadOfTheSink() {
        val input = fixture() ?: return println("byte sink contract degraded: no ffmpeg")
        val memory = MemorySink(seekable = true)
        remux(input, MediaSink.open(memory, "matroska"))
        assertTrue(memory.largestWrite in 1..64 * 1024, "one write carried ${memory.largestWrite} bytes")
    }
}
