package io.github.yuroyami.kiteffmpeg

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A log line from a thread the JVM does not know, such as one of FFmpeg's frame threads, attaches
 * that thread once and keeps it attached for its later lines, instead of attaching and detaching it
 * for every line (#254). Attached once, each such thread is one Java Thread for all its lines.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class JniLogThreadAttachTest {

    private class Bytes(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

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
    fun aForeignLoggingThreadIsAttachedOnceForAllItsLines() {
        val caller = Thread.currentThread()
        val foreignLines = Collections.synchronizedList(ArrayList<Thread>())
        FFmpeg.setLogSink(FFmpegLogLevel.Trace) { _, _, _ ->
            val thread = Thread.currentThread()
            if (thread !== caller) foreignLines += thread
        }
        try {
            MediaSource.open(Bytes(DecodeContractMedia.bytes)).use { source ->
                val video = source.primaryVideo!!
                source.openPacketReader(listOf(video)).use { reader ->
                    source.openDecoder(video, threadCount = 4).use { decoder ->
                        while (true) {
                            val packet = reader.read() ?: break
                            packet.use { while (!decoder.send(it)) while (true) (decoder.receive() ?: break).close() }
                            while (true) (decoder.receive() ?: break).close()
                        }
                        decoder.send(null)
                        while (true) (decoder.receive() ?: break).close()
                    }
                }
            }
        } finally {
            FFmpeg.setLogSink(sink = null)
        }
        val lines = synchronized(foreignLines) { foreignLines.toList() }
        val threads = Collections.newSetFromMap(IdentityHashMap<Thread, Boolean>()).apply { addAll(lines) }
        println("foreign log lines: ${lines.size}, Java threads they arrived on: ${threads.size}")
        assertTrue(lines.size >= 4, "the frame threads logged: ${lines.size} lines")
        assertTrue(threads.size * 2 <= lines.size, "${lines.size} lines arrived on ${threads.size} Java threads")
        assertTrue(threads.all { it.isDaemon }, "a thread attached for logging does not hold the JVM open")
    }
}
