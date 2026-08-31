package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Damaged data is skipped OR refused, and either way it is counted.
 *
 * The defect was not the skipping. Skipping is right for a player: one broken frame in a film
 * should not end playback, and every seek into a stream carrying its parameter sets in band lands
 * before the next one. The defect was that skipping was invisible. A caller verifying a recording,
 * or transcoding an archive and needing to know whether the result was whole, had no way at all to
 * tell a clean decode from a damaged one.
 *
 * The fixture is built rather than committed: a real file is encoded, then bytes deep inside its
 * sample payload are overwritten, which leaves the container parseable and the frames broken. That
 * is exactly the shape of a partially rotted file or a bad transfer.
 */
class CorruptDataPolicyTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun tmp(name: String): File =
        File.createTempFile("kiteffmpeg-corrupt-$name", ".mp4").also { files += it }

    private fun writeVideo(file: File, frames: Int = 60) {
        MediaSink.open(file.absolutePath).use { sink ->
            val encoder = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 128, height = 128,
                    frameRate = Rational(25, 1),
                    bitrateBps = 300_000,
                ),
            )
            runBlocking {
                encoder.drive(
                    kotlinx.coroutines.flow.flow {
                        repeat(frames) { i ->
                            val w = 128
                            val h = 128
                            val y = ByteArray(w * h) { ((it / 7 + i * 5) % 255).toByte() }
                            val u = ByteArray(w * h / 4) { ((it + i) % 255).toByte() }
                            val v = ByteArray(w * h / 4) { ((it * 3 + i) % 255).toByte() }
                            emit(Frame.ofVideo(y + u + v, w, h, PixelFormat.Yuv420p, i * 40_000L))
                        }
                    },
                )
            }
        }
    }

    /**
     * Overwrites a long run inside the sample payload, leaving the container structure alone.
     *
     * The `mdat` box is where an MP4 keeps its coded frames. Corrupting inside it gives the DECODER
     * damaged input, which is the case under test; corrupting the boxes around it would give the
     * DEMUXER a broken file, which is a different failure with a different answer.
     */
    private fun corruptSamplePayload(file: File) {
        val bytes = file.readBytes()
        val mdat = indexOfAscii(bytes, "mdat")
        assertTrue(mdat > 0, "the fixture must be an MP4 with an mdat box to damage")
        val from = mdat + 4 + (bytes.size - mdat) / 4
        val until = minOf(bytes.size, from + (bytes.size - mdat) / 3)
        assertTrue(until > from, "the payload must be long enough to damage meaningfully")
        for (i in from until until) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        file.writeBytes(bytes)
    }

    private fun indexOfAscii(bytes: ByteArray, needle: String): Int {
        val target = needle.encodeToByteArray()
        outer@ for (start in 0..bytes.size - target.size) {
            for (i in target.indices) if (bytes[start + i] != target[i]) continue@outer
            return start
        }
        return -1
    }

    @Test
    fun `a healthy file decodes with nothing counted as damaged`() {
        val file = tmp("healthy")
        writeVideo(file)
        MediaSource.open(file.absolutePath).use { source ->
            val video = source.streams.first { it.type == MediaType.Video }
            val decoded = runBlocking { source.decodedFrames(video).onEach { it.close() }.count() }

            assertTrue(decoded > 0, "the fixture must decode")
            assertEquals(
                0L,
                source.corruptDataSkipped,
                "a clean file must report no damage, or the counter means nothing",
            )
        }
    }

    /**
     * Red by returning from the INVALIDDATA branches without calling `noteCorruptData`, which is
     * exactly what every backend did: the frames vanish and the counter stays at zero, so the
     * caller cannot tell this file from the healthy one above.
     */
    @Test
    fun `damaged data is skipped by default and the loss is counted`() {
        val file = tmp("skip")
        writeVideo(file)
        corruptSamplePayload(file)

        MediaSource.open(file.absolutePath).use { source ->
            val video = source.streams.first { it.type == MediaType.Video }
            val decoded = runBlocking { source.decodedFrames(video).onEach { it.close() }.count() }

            assertTrue(
                source.corruptDataSkipped > 0,
                "the payload was overwritten and $decoded frames came out, yet nothing was " +
                    "recorded as damaged: that is the silence this fixes",
            )
        }
    }

    /** And the caller who would rather fail than produce a quietly incomplete result can. */
    @Test
    fun `the failing policy refuses the same file typed`() {
        val file = tmp("fail")
        writeVideo(file)
        corruptSamplePayload(file)

        MediaSource.open(file.absolutePath).use { source ->
            source.corruptData = CorruptData.Fail
            val video = source.streams.first { it.type == MediaType.Video }
            val failure = runCatching {
                runBlocking { source.decodedFrames(video).onEach { it.close() }.count() }
            }.exceptionOrNull()

            assertTrue(
                failure is FFmpegException,
                "damaged data under the failing policy must throw typed, got $failure",
            )
        }
    }
}
