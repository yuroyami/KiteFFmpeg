package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cut stops reading once every stream with a timeline has passed its end (#233). A song's cover
 * picture, a video stream with one packet at the start, was the lead of the cut and never passed
 * the end, so trimming the first second of a song read the whole file.
 */
internal class CutEndContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** [bytes] read through, counting what was read. */
    private class CountingBytes(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        var read = 0L
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            read += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }

    private class DiscardingSink : MediaByteSink {
        override val seekable: Boolean = false
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        override fun seek(position: Long) = error("this sink cannot seek")
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Test
    fun aCutOfASongWithACoverStopsReadingAtItsEnd() {
        // Sixty seconds of AAC in an M4A with a cover picture, made by the host's ffmpeg, which reads
        // the cover back as an attached picture.
        val song = path("m4a")
        val sound = path("m4a")
        val cover = path("jpg")
        // In three steps, because the ffmpeg command line ends the whole output with a one-frame cover.
        val written = MediaOracle.generate(
            listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=60", "-c:a", "aac", "-b:a", "128k"),
            sound,
        ) && MediaOracle.generate(listOf("-f", "lavfi", "-i", "color=c=red:s=64x64:d=1", "-frames:v", "1"), cover) &&
            MediaOracle.generate(
                listOf(
                    "-i", sound, "-i", cover, "-map", "0", "-map", "1", "-c", "copy",
                    "-disposition:v", "attached_pic", "-movflags", "+faststart",
                ),
                song,
            )
        if (!written) return println("cut end contract degraded: no ffmpeg")
        val bytes = readContractBytes(song)
        val probe = MediaSource.open(song).use { source -> source.streams.map { it.type to it.disposition.attachedPicture } }
        assertTrue((MediaType.Video to true) in probe, "the song carries no cover, so this proves nothing: $probe")
        val input = CountingBytes(bytes)
        runBlocking {
            Remuxer.remux(input = { input }, output = DiscardingSink(), format = "matroska", endMicros = 1_000_000L)
        }
        assertTrue(input.read < bytes.size / 4, "a one second cut read ${input.read} of ${bytes.size} bytes")
    }

    @Test
    fun eachStreamStopsAtItsOwnEndAndACoverIsNotWaitedFor() {
        val streams = listOf(
            StreamInfo(0, MediaType.Video, CodecId("mjpeg"), Rational(1, 90_000), null, null, disposition = Disposition(attachedPicture = true)),
            StreamInfo(1, MediaType.Video, CodecId("h264"), Rational(1, 90_000), null, null),
            StreamInfo(2, MediaType.Audio, CodecId("aac"), Rational(1, 48_000), null, null),
            StreamInfo(3, MediaType.Subtitle, CodecId("subrip"), Rational(1, 1000), null, null),
        )
        assertEquals(listOf(1, 2), CutEnd.waitingFor(streams))
        val cut = CutEnd(listOf(1, 2), endMicros = 10_000_000L)
        assertEquals(false, cut.passed(1, 10_100_000L), "the sound still owes packets before the end")
        assertEquals(true, cut.passed(2, 10_050_000L), "both have passed")
        assertEquals(true, CutEnd(listOf(1, 2), 10_000_000L).passed(1, 11_100_000L), "a window past the end stops the read")
    }
}
