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

/**
 * A small FTP server on the loopback that serves one file in passive mode (#268). It answers the
 * commands that FFmpeg's ftp protocol sends: USER, PASS, TYPE, FEAT, PWD, SIZE, EPSV, REST, RETR
 * and ABOR. [control] takes the control connection and [data] each data connection.
 *
 * The server runs on a thread of its own and serves the first control connection only.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class FtpTestServer(
    private val control: LoopbackListener,
    private val data: LoopbackListener,
    private val file: ByteArray,
) : AutoCloseable {
    /** The address a client opens. */
    val url: String = "ftp://127.0.0.1:${control.port}/$NAME"

    private val thread = newSingleThreadContext("ftp-test-server")
    private val stopping = atomic(false)
    private val logLock = SynchronizedObject()
    private val log = mutableListOf<String>()
    private val starts = mutableListOf<Long>()
    private val job = CoroutineScope(thread).launch { serve() }

    /** The byte offset that each transfer started at, in order. */
    val transfers: List<Long> get() = synchronized(logLock) { starts.toList() }

    /** Every command the server read, for a failure message. */
    val transcript: String get() = synchronized(logLock) { log.joinToString("\n") }

    override fun close() {
        stopping.value = true
        runBlocking { job.join() }
        thread.close()
        data.close()
        control.close()
    }

    private fun serve() {
        var connection: LoopbackConnection? = null
        while (connection == null && !stopping.value) connection = control.accept(100)
        connection?.use { Session(it).run() }
    }

    private inner class Session(private val connection: LoopbackConnection) {
        private var pending = ""
        private var channel: LoopbackConnection? = null
        private var restart = 0L

        fun run() {
            if (!reply("220 KiteFFmpeg test server")) return
            val buffer = ByteArray(1024)
            try {
                while (!stopping.value) {
                    val count = connection.read(buffer, 20)
                    if (count < 0) return
                    if (count == 0) continue
                    pending += buffer.decodeToString(0, count)
                    while (true) {
                        val end = pending.indexOf("\r\n")
                        if (end < 0) break
                        val line = pending.substring(0, end)
                        pending = pending.substring(end + 2)
                        if (!answer(line)) return
                    }
                }
            } finally {
                channel?.close()
            }
        }

        /** Answers one command, and returns false when the connection can take no more. */
        private fun answer(line: String): Boolean {
            synchronized(logLock) { log += line }
            val argument = line.substringAfter(' ', "")
            return when (line.substringBefore(' ').uppercase()) {
                "USER" -> reply("331 Password required")
                "PASS" -> reply("230 Logged in")
                "TYPE" -> reply("200 Binary mode")
                "PWD" -> reply("257 \"/\" is the current directory")
                "SIZE" -> reply(if (argument.endsWith(NAME)) "213 ${file.size}" else "550 No such file")
                "EPSV" -> reply("229 Entering extended passive mode (|||${data.port}|)") && accept()
                "REST" -> argument.toLongOrNull()?.let { offset ->
                    restart = offset
                    reply("350 Restarting at $offset")
                } ?: reply("501 Bad offset")
                "RETR" -> retrieve(argument)
                "ABOR" -> {
                    channel?.close()
                    channel = null
                    reply("226 Abort successful")
                }
                "QUIT" -> {
                    reply("221 Goodbye")
                    false
                }
                else -> reply("502 Not implemented")
            }
        }

        /** Takes the data connection that the client opens after EPSV. */
        private fun accept(): Boolean {
            channel?.close()
            channel = data.accept(5_000)
            return true
        }

        /** Sends the file from the REST offset over the data connection, then closes it. */
        private fun retrieve(path: String): Boolean {
            val open = channel ?: return reply("425 No data connection")
            if (!path.endsWith(NAME)) return reply("550 No such file")
            val from = restart.coerceIn(0L, file.size.toLong()).toInt()
            restart = 0
            channel = null
            synchronized(logLock) { starts += from.toLong() }
            if (!reply("150 Opening binary mode data connection")) return false
            // A seek closes the data connection while the file is on its way, so the write fails.
            val sent = try {
                open.write(file.copyOfRange(from, file.size))
                true
            } catch (_: Throwable) {
                false
            } finally {
                open.close()
            }
            return reply(if (sent) "226 Transfer complete" else "426 Transfer aborted")
        }

        private fun reply(text: String): Boolean = try {
            connection.write("$text\r\n".encodeToByteArray())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private companion object {
        const val NAME = "clip.mp4"
    }
}
