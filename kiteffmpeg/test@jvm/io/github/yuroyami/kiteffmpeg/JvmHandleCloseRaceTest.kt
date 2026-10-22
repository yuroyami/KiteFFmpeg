package io.github.yuroyami.kiteffmpeg

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An object closed on one thread while another thread uses it.
 *
 * The bridge's handle table turns a token into a pointer and holds nothing while the pointer is in
 * use, so the table alone cannot stop a close from freeing an object mid-call. Each public wrapper
 * holds its own lock across every native call and across its close, and that lock is what keeps a
 * close from freeing memory in use. These tests race a user against a close and accept exactly two
 * outcomes: the call finished before the close, or it was refused with IllegalStateException
 * because the object was already closed.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class JvmHandleCloseRaceTest {

    @Test
    fun aFrameClosedDuringACopyEitherFinishesTheCopyOrRefusesIt() {
        val width = 640
        val height = 360
        val bytes = ByteArray(width * height * 3 / 2)
        repeat(ROUNDS) {
            val frame = Frame.ofVideo(bytes, width, height, PixelFormat.Yuv420p, 0L)
            val result = race(
                use = { assertEquals(bytes.size, frame.copyPlanesToByteArray().size) },
                close = frame::close,
                yieldBetweenUses = true,
            )
            assertNull(result.unexpected, "a copy racing close failed with something other than a refusal")
            assertTrue(result.refused, "the copy loop ended without the refusal a closed frame owes")
        }
    }

    @Test
    fun aPacketClosedDuringACopyEitherFinishesTheCopyOrRefusesIt() {
        val media = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        try {
            MediaSource.open(media).use { source ->
                source.openPacketReader(source.streams).use { reader ->
                    val original = reader.read() ?: error("the contract fixture yielded no packet to race")
                    original.use {
                        repeat(ROUNDS) {
                            val packet = original.copy()
                            val result = race(use = { packet.copyBytes() }, close = packet::close, yieldBetweenUses = false)
                            assertNull(result.unexpected, "a copy racing close failed with something other than a refusal")
                            assertTrue(result.refused, "the copy loop ended without the refusal a closed packet owes")
                        }
                    }
                }
            }
        } finally {
            deleteContractPath(media)
        }
    }

    private class RaceResult(val refused: Boolean, val unexpected: Throwable?)

    /**
     * Runs [use] in a loop on a second thread while this thread runs [close]. The loop stops at the
     * first refusal, which a closed object owes, or at the first failure of any other kind.
     *
     * [yieldBetweenUses] is for a use that holds the lock a long time, such as a frame copy. A Java
     * monitor is not fair, so such a loop can take the lock back so often that the close waits for
     * seconds. A short use races best without the yield, because more attempts land near the close.
     */
    private fun race(use: () -> Unit, close: () -> Unit, yieldBetweenUses: Boolean): RaceResult {
        val started = CountDownLatch(1)
        var refused = false
        var unexpected: Throwable? = null
        val user = thread(name = "handle-race-user") {
            started.countDown()
            while (true) {
                try {
                    use()
                    if (yieldBetweenUses) Thread.yield()
                } catch (_: IllegalStateException) {
                    refused = true
                    return@thread
                } catch (failure: Throwable) {
                    unexpected = failure
                    return@thread
                }
            }
        }
        started.await()
        close()
        user.join()
        return RaceResult(refused, unexpected)
    }

    private companion object {
        const val ROUNDS = 200
    }
}
