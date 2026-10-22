package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.FORCED_FORMAT_KEY
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The one-call APIs read caller bytes, write caller bytes, and pass pre-open options to the input. */
class MediaEndsContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
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

    private class MemorySink(override val seekable: Boolean) : MediaByteSink {
        var bytes = ByteArray(0)
        private var position = 0
        var closes = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (position + length > this.bytes.size) this.bytes = this.bytes.copyOf(position + length)
            bytes.copyInto(this.bytes, position, offset, offset + length)
            position += length
        }

        override fun seek(position: Long) {
            check(seekable) { "seek on a sink that said it cannot" }
            this.position = position.toInt()
        }

        override fun close() {
            closes++
        }
    }

    /** Ten mpeg4 frames at 25 fps in Matroska, as bytes. */
    private fun videoBytes(): ByteArray {
        val file = path("mkv")
        TranscodeFixtures.writeConstantRateVideo(file, Rational(25, 1), frames = 10)
        return readContractBytes(file)
    }

    private fun decodedVideoFrames(bytes: ByteArray): Int = MediaSource.open(BytesSource(bytes)).use { source ->
        val video = checkNotNull(source.primaryVideo) { "the output has no video stream" }
        var count = 0
        runBlocking { source.decodedFrames(video).collect { frame -> frame.close().also { count++ } } }
        count
    }

    @Test
    fun bytesTranscodeIntoAFragmentedMp4ThatOpensAgain() {
        val input = videoBytes()
        var opens = 0
        val sink = MemorySink(seekable = false)
        runBlocking {
            Transcoder.transcode(
                input = { opens++; BytesSource(input) },
                output = sink,
                format = "mp4",
                outputOptions = mapOf("movflags" to "frag_keyframe+empty_moov"),
                spec = TranscodeFixtures.videoSpec(Rational(25, 1)),
            )
        }
        // The spec leaves the colour to the input, so the first frame is read from a second open.
        assertEquals(2, opens, "the input factory was not called once per open")
        assertEquals(1, sink.closes, "the transcode closes the sink it was given")
        assertEquals(10, decodedVideoFrames(sink.bytes))
    }

    @Test
    fun bytesRemuxIntoBytes() {
        val input = videoBytes()
        val sink = MemorySink(seekable = true)
        runBlocking { Remuxer.remux(input = { BytesSource(input) }, output = sink, format = "matroska") }
        assertEquals(1, sink.closes, "the remux closes the sink it was given")
        assertEquals(10, decodedVideoFrames(sink.bytes))
    }

    @Test
    fun inputOptionsReachTheOpen() {
        val wav = path("wav")
        TranscodeFixtures.writePcm(wav, sampleCount = 4_800, sampleRate = 48_000, channels = 1) { index, _ -> (index % 1_000).toShort() }
        val spec = TranscodeFixtures.pcmSpec(48_000, 1)

        // A forced demuxer this build does not have fails the open, which proves the option got there.
        assertFailsWith<FFmpegException> {
            runBlocking {
                Transcoder.transcode(wav, mapOf(FORCED_FORMAT_KEY to "no_such_demuxer"), path("wav"), audioSpec = spec)
            }
        }
        assertFailsWith<FFmpegException> {
            runBlocking { Remuxer.remux(wav, mapOf(FORCED_FORMAT_KEY to "no_such_demuxer"), path("wav")) }
        }

        val transcoded = path("wav")
        runBlocking { Transcoder.transcode(wav, mapOf(FORCED_FORMAT_KEY to "wav"), transcoded, audioSpec = spec) }
        assertEquals(4_800L, TranscodeFixtures.decodedSampleCount(transcoded))
        val remuxed = path("wav")
        runBlocking { Remuxer.remux(wav, mapOf(FORCED_FORMAT_KEY to "wav"), remuxed) }
        assertEquals(4_800L, TranscodeFixtures.decodedSampleCount(remuxed))
    }
}
