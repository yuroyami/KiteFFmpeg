package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Rows the execution log calls DONE that were only ever done on Native (register item KC-NOTDONE).
 *
 * Each case below has a passing twin in `nativeTest`. That is the whole point: a guard living on
 * one backend is not a guard, it is a coin flip decided by which target the caller compiled for.
 */
class JvmBackendParityTest {

    private val files = mutableListOf<File>()

    private fun tmp(name: String, extension: String = ".mkv"): File =
        File.createTempFile("kiteffmpeg-parity-$name", extension).also { files += it }

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun yuv(w: Int, h: Int, i: Int): ByteArray {
        val y = ByteArray(w * h) { ((it / 5 + i * 3) % 255).toByte() }
        val u = ByteArray(w * h / 4) { ((it + i) % 255).toByte() }
        val v = ByteArray(w * h / 4) { ((it * 3 + i) % 255).toByte() }
        return y + u + v
    }

    /** A real playable file, whose frame rate decides its video stream's time base. */
    private fun writeVideo(file: File, fps: Int, frames: Int = 20) {
        MediaSink.open(file.absolutePath).use { sink ->
            val encoder = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64, height = 64,
                    frameRate = Rational(fps, 1),
                    bitrateBps = 200_000,
                ),
            )
            runBlocking {
                encoder.drive(
                    flow {
                        repeat(frames) { i -> emit(Frame.ofVideo(yuv(64, 64, i), 64, 64, PixelFormat.Yuv420p, i * (1_000_000L / fps))) }
                    },
                )
            }
        }
    }

    /**
     * P1-11. `StreamInfo` is a public data class and therefore forgeable, so every entry point
     * taking one has to canonicalize it against the source that is supposed to own it. Native does
     * this in `codecparOf`; the JVM's `withCodecParameters` did not, so `addCopyStream` accepted a
     * stream belonging to a DIFFERENT file and wrote this file's codec parameters under the other
     * file's time base. That is silent corruption, not a crash: the remux succeeds and plays at the
     * wrong speed.
     *
     * Both files carry one video stream at index 0, so the index resolves on either source. Only
     * the identity check can tell them apart.
     */
    @Test
    fun addCopyStreamRefusesAStreamBelongingToAnotherSource() {
        val slow = tmp("slow").also { writeVideo(it, fps = 25) }
        val fast = tmp("fast").also { writeVideo(it, fps = 50) }
        val output = tmp("copied")

        MediaSource.open(slow.absolutePath).use { slowSource ->
            MediaSource.open(fast.absolutePath).use { fastSource ->
                val foreign = fastSource.streams.first { it.type == MediaType.Video }
                MediaSink.open(output.absolutePath).use { sink ->
                    val failure = assertFailsWith<IllegalArgumentException> {
                        sink.addCopyStream(slowSource, foreign)
                    }
                    assertTrue(
                        "does not belong to this MediaSource" in (failure.message ?: ""),
                        "the refusal must name the reason, got: ${failure.message}",
                    )
                }
            }
        }
    }

    /**
     * P0-08. An audio encoder handed a video frame used to reach FFmpeg on the JVM, where the
     * picture bytes were read as samples: a wrong answer rather than a refusal. Native guards this
     * in its own `encode`, and the row was logged done on the strength of that half.
     */
    @Test
    fun anAudioEncoderRefusesAVideoFrame() {
        val output = tmp("mistyped", ".m4a")
        MediaSink.open(output.absolutePath).use { sink ->
            val encoder = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId("aac")))
            val video = Frame.ofVideo(yuv(64, 64, 0), 64, 64, PixelFormat.Yuv420p, 0L)
            val failure = assertFailsWith<IllegalArgumentException> {
                runBlocking { encoder.drive(flowOf(video)) }
            }
            assertTrue(
                "Video frame" in (failure.message ?: "") || "Audio" in (failure.message ?: ""),
                "the refusal must name both the encoder's type and the frame's, got: ${failure.message}",
            )
        }
    }
}
