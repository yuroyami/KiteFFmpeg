package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A TCP socket listening on the loopback, which a test serves one connection from. */
internal interface LoopbackListener : AutoCloseable {
    /** The port it listens on. */
    val port: Int

    /** Waits up to [timeoutMillis] for a connection, and answers null when none arrived. */
    fun accept(timeoutMillis: Int): LoopbackConnection?
}

/** One accepted connection. */
internal interface LoopbackConnection : AutoCloseable {
    /**
     * Waits up to [timeoutMillis] for bytes and reads what arrived into [buffer]. Answers the
     * count, 0 when nothing arrived in time, or -1 once the other side has closed.
     */
    fun read(buffer: ByteArray, timeoutMillis: Int): Int

    /** Sends all of [bytes]. Throws once the other side has gone. */
    fun write(bytes: ByteArray)
}

/** Listens on the loopback, or answers null where a test cannot listen, which is an Android device. */
internal expect fun listenOnLoopback(): LoopbackListener?

/**
 * A small RTSP server on the loopback that holds one session the way a camera does (#136). It
 * describes one PCMU audio stream, delivers it interleaved over the RTSP connection once played,
 * and announces a session timeout of [timeoutSeconds]. A session that hears no request for longer
 * than that really ends: the server stops its media and answers 454 Session Not Found to anything
 * that names it afterwards, which is what a client that paused and then fell silent meets.
 *
 * The server runs on a thread of its own and serves the first connection only.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class RtspTestServer(private val listener: LoopbackListener, private val timeoutSeconds: Int) : AutoCloseable {
    /** The address a client opens. */
    val url: String = "rtsp://127.0.0.1:${listener.port}/live"

    private val thread = newSingleThreadContext("rtsp-test-server")
    private val stopping = atomic(false)
    private val silent = atomic(false)
    private val plays = atomic(0)
    private val pauses = atomic(0)
    private val keepalives = atomic(0)
    private val ended = atomic(false)
    private val logLock = SynchronizedObject()
    private val log = mutableListOf<String>()
    private val start = TimeSource.Monotonic.markNow()
    private val job = CoroutineScope(thread).launch { serve() }

    /** PLAY requests the session accepted. */
    val playCount: Int get() = plays.value

    /** PAUSE requests the session accepted. */
    val pauseCount: Int get() = pauses.value

    /** GET_PARAMETER and OPTIONS requests that named the live session, which keep it alive. */
    val keepaliveCount: Int get() = keepalives.value

    /** True once the session ended because it heard nothing for longer than its timeout. */
    val sessionEnded: Boolean get() = ended.value

    /** Every request line with the time it arrived, for a failure message. */
    val transcript: String get() = synchronized(logLock) { log.joinToString("\n") }

    /**
     * Stops every write, so a client that closes next meets no media and no reply racing the
     * close. The server keeps reading until the connection ends.
     */
    fun silence() {
        silent.value = true
    }

    override fun close() {
        silence()
        stopping.value = true
        runBlocking { job.join() }
        thread.close()
        listener.close()
    }

    private fun serve() {
        var connection: LoopbackConnection? = null
        while (connection == null && !stopping.value) connection = listener.accept(100)
        connection?.use { Session(it).run() }
    }

    private inner class Session(private val connection: LoopbackConnection) {
        private var pending = ByteArray(0)
        private var sessionId: String? = null
        private var lastRequest = TimeSource.Monotonic.markNow()
        private var playing = false
        private var nextPacket = TimeSource.Monotonic.markNow()
        private var sequence = 0
        private var timestamp = 0

        fun run() {
            val buffer = ByteArray(4096)
            while (!stopping.value) {
                val count = connection.read(buffer, 5)
                if (count < 0) return
                if (count > 0) {
                    pending += buffer.copyOf(count)
                    while (true) {
                        val request = takeRequest() ?: break
                        if (!answer(request)) return
                    }
                }
                expireWhenSilent()
                if (playing && nextPacket.hasPassedNow() && !send(packet())) return
            }
        }

        /** Ends the session when no request named it for longer than the timeout. */
        private fun expireWhenSilent() {
            if (sessionId == null || ended.value) return
            if (lastRequest.elapsedNow() <= timeoutSeconds.seconds) return
            ended.value = true
            playing = false
            note("session ended after ${lastRequest.elapsedNow().inWholeMilliseconds} ms without a request")
        }

        /** Answers [request] and returns false when the connection can take no more. */
        private fun answer(request: Request): Boolean {
            note(request.line)
            expireWhenSilent()
            val cseq = request.headers["cseq"] ?: "0"
            val named = request.headers["session"]?.substringBefore(';')?.trim()
            if (named != null && (named != sessionId || ended.value)) return reply(cseq, "454 Session Not Found")
            if (named != null) lastRequest = TimeSource.Monotonic.markNow()
            val session = "Session: $sessionId;timeout=$timeoutSeconds"
            return when (request.method) {
                "OPTIONS" -> {
                    if (named != null) keepalives.incrementAndGet()
                    reply(cseq, "200 OK", listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, GET_PARAMETER, TEARDOWN"))
                }
                "DESCRIBE" -> {
                    val sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=KiteFFmpeg\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n" +
                        "m=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=control:track0\r\n"
                    reply(cseq, "200 OK", listOf("Content-Base: $url/", "Content-Type: application/sdp"), sdp)
                }
                "SETUP" -> {
                    if (sessionId == null) sessionId = "13579"
                    lastRequest = TimeSource.Monotonic.markNow()
                    reply(cseq, "200 OK", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1", "Session: $sessionId;timeout=$timeoutSeconds"))
                }
                "PLAY" -> {
                    plays.incrementAndGet()
                    playing = true
                    nextPacket = TimeSource.Monotonic.markNow()
                    reply(cseq, "200 OK", listOf(session))
                }
                "PAUSE" -> {
                    pauses.incrementAndGet()
                    playing = false
                    reply(cseq, "200 OK", listOf(session))
                }
                "GET_PARAMETER" -> {
                    keepalives.incrementAndGet()
                    reply(cseq, "200 OK", listOf(session))
                }
                // A client sends TEARDOWN as it closes and does not wait for the answer.
                "TEARDOWN" -> {
                    playing = false
                    true
                }
                else -> reply(cseq, "501 Not Implemented")
            }
        }

        private fun reply(cseq: String, status: String, headers: List<String> = emptyList(), body: String = ""): Boolean {
            val text = buildString {
                append("RTSP/1.0 ").append(status).append("\r\n")
                append("CSeq: ").append(cseq).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                if (body.isNotEmpty()) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
                append("\r\n").append(body)
            }
            return send(text.encodeToByteArray())
        }

        /** Twenty milliseconds of PCMU silence in one RTP packet, framed for channel 0. */
        private fun packet(): ByteArray {
            val payload = 160
            val rtp = ByteArray(4 + 12 + payload) { 0xFF.toByte() }
            val length = 12 + payload
            rtp[0] = '$'.code.toByte(); rtp[1] = 0
            rtp[2] = (length shr 8).toByte(); rtp[3] = length.toByte()
            rtp[4] = 0x80.toByte(); rtp[5] = 0
            rtp[6] = (sequence shr 8).toByte(); rtp[7] = sequence.toByte()
            for (index in 0 until 4) rtp[8 + index] = (timestamp shr (24 - 8 * index)).toByte()
            for (index in 0 until 4) rtp[12 + index] = (0x4B495445 shr (24 - 8 * index)).toByte()
            sequence = (sequence + 1) and 0xFFFF
            timestamp += payload
            nextPacket += 20.milliseconds
            return rtp
        }

        private fun send(bytes: ByteArray): Boolean {
            if (silent.value) return true
            return try {
                connection.write(bytes)
                true
            } catch (_: Exception) {
                false
            }
        }

        /** Takes one whole request from what has arrived, skipping interleaved RTCP. */
        private fun takeRequest(): Request? {
            while (pending.isNotEmpty() && pending[0] == '$'.code.toByte()) {
                if (pending.size < 4) return null
                val frame = 4 + (((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF))
                if (pending.size < frame) return null
                pending = pending.copyOfRange(frame, pending.size)
            }
            val text = pending.decodeToString()
            val end = text.indexOf("\r\n\r\n")
            if (end < 0) return null
            val lines = text.substring(0, end).split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
            // The head is ASCII, so its length in characters is its length in bytes.
            val total = end + 4 + bodyLength
            if (pending.size < total) return null
            pending = pending.copyOfRange(total, pending.size)
            return Request(lines[0], lines[0].substringBefore(' '), headers)
        }

        private fun note(line: String) {
            synchronized(logLock) { log += "${start.elapsedNow().inWholeMilliseconds} ms: $line" }
        }
    }

    private class Request(val line: String, val method: String, val headers: Map<String, String>)
}
