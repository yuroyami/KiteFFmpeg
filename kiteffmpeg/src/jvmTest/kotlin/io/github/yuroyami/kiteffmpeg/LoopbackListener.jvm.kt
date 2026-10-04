package io.github.yuroyami.kiteffmpeg

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

internal actual fun listenOnLoopback(): LoopbackListener? = JvmLoopbackListener()

private class JvmLoopbackListener : LoopbackListener {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    override val port: Int = server.localPort

    override fun accept(timeoutMillis: Int): LoopbackConnection? {
        server.soTimeout = timeoutMillis
        return try {
            JvmLoopbackConnection(server.accept())
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = server.close()
}

private class JvmLoopbackConnection(private val socket: Socket) : LoopbackConnection {
    init {
        socket.tcpNoDelay = true
    }

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        socket.soTimeout = timeoutMillis
        return try {
            socket.getInputStream().read(buffer)
        } catch (_: SocketTimeoutException) {
            0
        }
    }

    override fun write(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    override fun close() = socket.close()
}
