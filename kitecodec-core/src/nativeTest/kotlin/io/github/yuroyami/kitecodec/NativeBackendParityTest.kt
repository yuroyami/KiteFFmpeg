package io.github.yuroyami.kitecodec

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.remove
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Rows the execution log calls DONE that were only ever done on the JVM, plus one done in most of
 * Native but not all of it (register item KC-NOTDONE).
 *
 * The JVM twins of the first case already pass. That is the point: a typed error a caller can catch
 * on one backend and cannot on another is not a typed error, it is a platform lottery.
 */
@OptIn(KiteCodecLowLevelApi::class)
class NativeBackendParityTest {

    private val tmpFiles = mutableListOf<String>()

    private fun tmp(name: String): String {
        val root = sequenceOf("TMPDIR", "TEMP", "TMP")
            .mapNotNull { getenv(it)?.toKString() }
            .firstOrNull { it.isNotBlank() }
            ?: error("No temporary directory: TMPDIR, TEMP, and TMP are all missing or blank")
        return "${root.trimEnd('/', '\\')}/kitecodec-parity-$name".also { tmpFiles += it }
    }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    private fun smallFrame(): Frame {
        val w = 32
        val h = 32
        val y = ByteArray(w * h) { (it % 200).toByte() }
        val u = ByteArray(w * h / 4) { 100.toByte() }
        val v = ByteArray(w * h / 4) { 140.toByte() }
        return Frame.ofVideo(y + u + v, w, h, PixelFormat.Yuv420p, 0)
    }

    /**
     * P1-04. A missing encoder is a condition a caller handles: fall back to software, pick another
     * codec, tell the user which build they installed. The JVM has thrown a catchable
     * [FFmpegError.EncoderNotFound] since the same commit that was supposed to convert both; Native
     * still threw the untyped [FFmpegError.Internal], so `when (error)` fell to the else branch and
     * a recoverable condition looked like a bug in the library.
     */
    @Test
    fun aMissingVideoEncoderIsTypedAndNotInternal() {
        MediaSink.open(tmp("missing-video.mkv")).use { sink ->
            val failure = assertFailsWith<FFmpegException> {
                sink.addVideoEncoder(
                    VideoEncoderSpec(
                        codec = CodecId("no_such_encoder_exists"),
                        width = 64, height = 64,
                        frameRate = Rational(25, 1),
                        bitrateBps = 200_000,
                    ),
                )
            }
            assertIs<FFmpegError.EncoderNotFound>(
                failure.error,
                "a missing encoder must be catchable by kind, got ${failure.error::class.simpleName}",
            )
        }
    }

    /** The audio half of the same call, which shares the lookup and therefore the defect. */
    @Test
    fun aMissingAudioEncoderIsTypedAndNotInternal() {
        MediaSink.open(tmp("missing-audio.mkv")).use { sink ->
            val failure = assertFailsWith<FFmpegException> {
                sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId("no_such_encoder_exists")))
            }
            assertIs<FFmpegError.EncoderNotFound>(
                failure.error,
                "a missing encoder must be catchable by kind, got ${failure.error::class.simpleName}",
            )
        }
    }

    /**
     * P0-07 inside Native. `withPlanes` read the pointer through `checkedNative`, whose own KDoc
     * says it is not a lease: the pointer escapes the lock the instant it is returned, so the plane
     * addresses handed to the caller's block were only ever checked, never held. A concurrent close
     * during the block frees the AVFrame under those addresses, which is the render path.
     *
     * This is asserted without relying on a crash. Under a real lease a close cannot COMPLETE while
     * the block runs, because it parks on the frame's lock; without one it completes in
     * microseconds. Fifty milliseconds is several orders of magnitude more than a close takes, so a
     * close that has not finished by then is a close that is blocked.
     */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun withPlanesHoldsTheFrameForTheWholeBlock() = runBlocking {
        val frame = smallFrame()
        val state = RaceState()

        val closer = launch(Dispatchers.Default) {
            while (!state.insideBlock) usleep(100u)
            state.closeAttempted = true
            frame.close()
            state.closeCompleted = true
        }

        var completedWhileInside = false
        frame.withPlanes { planes, _, _ ->
            state.insideBlock = true
            while (!state.closeAttempted) usleep(100u)
            usleep(50_000u)
            completedWhileInside = state.closeCompleted
            planes.size
        }
        closer.join()

        assertFalse(
            completedWhileInside,
            "close() completed while withPlanes was still running, so the plane pointers it handed " +
                "out were dangling. withPlanes must take the frame's lease, not merely check it.",
        )
    }

}

/** Plain volatile flags; the test needs visibility across threads, not atomicity. */
private class RaceState {
    @Volatile var insideBlock: Boolean = false
    @Volatile var closeAttempted: Boolean = false
    @Volatile var closeCompleted: Boolean = false
}
