package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The native half of the damaged-data policy (audit P1-05).
 *
 * Same subject as the JVM test next to it, and it has to exist separately because the decode loop
 * is a different implementation on each backend: the whole finding was that all three behaved
 * differently and none of them said anything.
 */
@OptIn(ExperimentalForeignApi::class)
class CorruptDataPolicyTest {

    private fun tmpPath(name: String): String {
        val root = systemTempRoot()
        return "$root/kiteffmpeg-corrupt-$name.mp4"
    }

    private fun writeVideo(path: String, frames: Int = 60) {
        MediaSink.open(path).use { sink ->
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
                    flow {
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

    private fun readAll(path: String): ByteArray {
        val handle = platform.posix.fopen(path, "rb") ?: error("cannot read $path")
        try {
            platform.posix.fseek(handle, 0, platform.posix.SEEK_END)
            val size = platform.posix.ftell(handle).toInt()
            platform.posix.fseek(handle, 0, platform.posix.SEEK_SET)
            val bytes = ByteArray(size)
            bytes.usePinned { platform.posix.fread(it.addressOf(0), 1u, size.toULong(), handle) }
            return bytes
        } finally {
            platform.posix.fclose(handle)
        }
    }

    private fun writeAll(path: String, bytes: ByteArray) {
        val handle = platform.posix.fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { platform.posix.fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
        } finally {
            platform.posix.fclose(handle)
        }
    }

    /** Damages the coded frames and leaves the container structure intact; see the JVM twin. */
    private fun corruptSamplePayload(path: String) {
        val bytes = readAll(path)
        val mdat = indexOfAscii(bytes, "mdat")
        assertTrue(mdat > 0, "the fixture must be an MP4 with an mdat box to damage")
        val from = mdat + 4 + (bytes.size - mdat) / 4
        val until = minOf(bytes.size, from + (bytes.size - mdat) / 3)
        assertTrue(until > from, "the payload must be long enough to damage meaningfully")
        for (i in from until until) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        writeAll(path, bytes)
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
    fun aHealthyFileDecodesWithNothingCountedAsDamaged() {
        val path = tmpPath("healthy")
        writeVideo(path)
        try {
            MediaSource.open(path).use { source ->
                val video = source.streams.first { it.type == MediaType.Video }
                val decoded = runBlocking { source.decodedFrames(video).onEach { it.close() }.count() }
                assertTrue(decoded > 0, "the fixture must decode")
                assertEquals(0L, source.corruptDataSkipped, "a clean file must report no damage")
            }
        } finally {
            platform.posix.remove(path)
        }
    }

    /** Red by dropping `noteCorruptData` from the INVALIDDATA branches, which is what it did. */
    @Test
    fun damagedDataIsSkippedByDefaultAndTheLossIsCounted() {
        val path = tmpPath("skip")
        writeVideo(path)
        corruptSamplePayload(path)
        try {
            MediaSource.open(path).use { source ->
                val video = source.streams.first { it.type == MediaType.Video }
                val decoded = runBlocking { source.decodedFrames(video).onEach { it.close() }.count() }
                assertTrue(
                    source.corruptDataSkipped > 0,
                    "the payload was overwritten and $decoded frames came out, yet nothing was " +
                        "recorded as damaged",
                )
            }
        } finally {
            platform.posix.remove(path)
        }
    }

    @Test
    fun theFailingPolicyRefusesTheSameFileTyped() {
        val path = tmpPath("fail")
        writeVideo(path)
        corruptSamplePayload(path)
        try {
            MediaSource.open(path).use { source ->
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
        } finally {
            platform.posix.remove(path)
        }
    }
}
