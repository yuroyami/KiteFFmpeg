package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.POLLIN
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

internal actual fun listenOnLoopback(): LoopbackListener? = PosixLoopbackListener()

@OptIn(ExperimentalForeignApi::class)
private class PosixLoopbackListener : LoopbackListener {
    private val fd: Int = socket(AF_INET, SOCK_STREAM, 0)
    override val port: Int

    init {
        check(fd >= 0) { "socket() failed with errno $errno" }
        port = memScoped {
            val address = alloc<sockaddr_in>()
            memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            // 127.0.0.1 in network byte order, read as a little-endian word.
            address.sin_addr.s_addr = 0x0100007Fu
            check(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed with errno $errno" }
            check(listen(fd, 1) == 0) { "listen() failed with errno $errno" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(fd, address.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname() failed with errno $errno" }
            val networkOrder = address.sin_port.toInt()
            ((networkOrder and 0xFF) shl 8) or (networkOrder shr 8)
        }
    }

    override fun accept(timeoutMillis: Int): LoopbackConnection? {
        if (!readable(fd, timeoutMillis)) return null
        val client = platform.posix.accept(fd, null, null)
        check(client >= 0) { "accept() failed with errno $errno" }
        return PosixLoopbackConnection(client)
    }

    override fun close() {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixLoopbackConnection(private val fd: Int) : LoopbackConnection {
    init {
        // A write to a client that already closed fails with EPIPE instead of killing the process.
        memScoped {
            val on = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<IntVar>().convert())
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        if (!readable(fd, timeoutMillis)) return 0
        val count = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }.toInt()
        return if (count <= 0) -1 else count
    }

    override fun write(bytes: ByteArray) {
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val sent = send(fd, pinned.addressOf(offset), (bytes.size - offset).convert(), 0).toInt()
                check(sent > 0) { "send() failed with errno $errno" }
                offset += sent
            }
        }
    }

    override fun close() {
        shutdown(fd, SHUT_RDWR)
        close(fd)
    }
}

/** True when [fd] has something to read, or a connection to accept, within [timeoutMillis]. */
@OptIn(ExperimentalForeignApi::class)
private fun readable(fd: Int, timeoutMillis: Int): Boolean = memScoped {
    val entry = alloc<pollfd>()
    entry.fd = fd
    entry.events = POLLIN.convert()
    entry.revents = 0
    poll(entry.ptr, 1.convert(), timeoutMillis) > 0
}
