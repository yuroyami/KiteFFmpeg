package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.remove
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A native [MediaSink] close against work that is already running on it.
 *
 * The encode case is exact: the test parks an encode inside its encoder's lock, before its first
 * codec call, and the close must not finish while it is parked. The copy case is a race, so a red
 * run on code without the lease is likely rather than guaranteed; the copy write holds the mux lock
 * from start to end, which is what makes its pass meaningful.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class SinkCloseLeaseTest {

    private val spec = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = 32,
        height = 32,
        frameRate = Rational(25, 1),
        bitrateBps = 200_000,
    )

    private fun frame(index: Int): Frame {
        val y = ByteArray(32 * 32) { ((it + index * 3) % 200).toByte() }
        val u = ByteArray(32 * 32 / 4) { 100.toByte() }
        val v = ByteArray(32 * 32 / 4) { 140.toByte() }
        return Frame.ofVideo(y + u + v, 32, 32, PixelFormat.Yuv420p, index * 40_000L)
    }

    private fun tmpPath(name: String): String = "${systemTempRoot()}/kiteffmpeg-lease-$name"

    @Test
    fun aCloseWaitsForAnEncodeThatAlreadyStarted() = runBlocking {
        val output = tmpPath("encode-in-flight.mkv")
        val sink = MediaSink.open(output)
        val encoder = sink.addVideoEncoder(spec)
        encoder.core.ensureHeaderWritten()
        val frame = frame(0)
        val state = LeaseState()
        var closer: Job? = null
        // Holding the frame's lease parks the encode below inside its encoder's lock, before any
        // codec call.
        val holder = launch(Dispatchers.IO) {
            frame.withNative {
                state.frameHeld = true
                while (!state.releaseFrame) usleep(100u)
            }
        }
        val encode = try {
            while (!state.frameHeld) usleep(100u)
            val encode = launch(Dispatchers.IO) {
                withPacket { packet -> encoder.core.encode(packet, frame) }
                state.encodeReturned = true
            }
            awaitHeldByAnotherThread(encoder.core.lock)
            closer = launch(Dispatchers.IO) {
                sink.close()
                state.closeReturned = true
            }
            // A close that does not wait finishes in well under a millisecond.
            usleep(50_000u)
            state.closedWhileParked = state.closeReturned
            encode
        } finally {
            state.releaseFrame = true
        }
        holder.join()
        encode.join()
        closer?.join()
        assertFalse(state.closedWhileParked, "the close finished while an encode was inside its encoder")
        assertTrue(state.encodeReturned && state.closeReturned, "both calls return once the encode goes on")
        assertEquals(1, videoPacketCount(output), "the encode that started first is in the file")
        remove(output)
        Unit
    }

    @Test
    fun copyWritesRacingACloseEndTypedAndKeepEveryAcceptedPacket() = runBlocking {
        val input = tmpPath("copy-race-input.mkv")
        MediaSink.open(input).use { sink ->
            val encoder = sink.addVideoEncoder(spec)
            encoder.drive(flow { repeat(25) { emit(frame(it)) } })
        }
        MediaSource.open(input).use { source ->
            val video = checkNotNull(source.primaryVideo)
            val packets = source.openPacketReader(listOf(video)).use { reader ->
                buildList { while (true) add(reader.read() ?: break) }
            }
            try {
                repeat(100) { round ->
                    val output = tmpPath("copy-race-$round.mkv")
                    val sink = MediaSink.open(output)
                    val copy = sink.addCopyStream(source, video)
                    val state = CopyState()
                    val writer = launch(Dispatchers.IO) {
                        try {
                            packets.forEach { packet ->
                                copy.write(packet)
                                state.written++
                            }
                        } catch (_: IllegalStateException) {
                            // The typed refusal of a write that arrives once the close began.
                        }
                    }
                    sink.close()
                    writer.join()
                    // FFmpeg cannot reopen a Matroska file that holds no packet (see
                    // EmptySinkContractTest), so only a round that wrote one is read back.
                    if (state.written > 0) {
                        assertEquals(state.written, videoPacketCount(output), "round $round lost a packet it had accepted")
                    }
                    remove(output)
                }
            } finally {
                packets.forEach(Packet::close)
            }
        }
        remove(input)
        Unit
    }

    /** Returns once another thread holds [lock]; fails after five seconds. */
    private fun awaitHeldByAnotherThread(lock: SynchronizedObject) {
        repeat(50_000) {
            if (!lock.tryLock()) return
            lock.unlock()
            usleep(100u)
        }
        fail("the encode never took its encoder's lock")
    }

    private fun videoPacketCount(path: String): Int = MediaSource.open(path).use { source ->
        source.openPacketReader(listOf(checkNotNull(source.primaryVideo))).use { reader ->
            var count = 0
            while (true) {
                (reader.read() ?: break).close()
                count++
            }
            count
        }
    }
}

/** Packets one writer got into the sink; only that writer counts. */
private class CopyState {
    @Volatile var written: Int = 0
}

/** Plain volatile flags; the test needs visibility across threads, not atomicity. */
private class LeaseState {
    @Volatile var frameHeld: Boolean = false
    @Volatile var releaseFrame: Boolean = false
    @Volatile var encodeReturned: Boolean = false
    @Volatile var closeReturned: Boolean = false
    @Volatile var closedWhileParked: Boolean = false
}
