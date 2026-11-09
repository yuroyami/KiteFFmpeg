// Windows' helpers for the codec contract suite (#279). The same as the POSIX ones but for what
// MinGW lacks: no mkstemp, no mkfifo, and a mkdir that takes no mode.
package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.launch
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.remove
import platform.posix.rename
import platform.windows.CloseHandle
import platform.windows.ConnectNamedPipe
import platform.windows.CreateNamedPipeW
import platform.windows.DWORDVar
import platform.windows.DisconnectNamedPipe
import platform.windows.FlushFileBuffers
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.PIPE_ACCESS_OUTBOUND
import platform.windows.PIPE_TYPE_BYTE
import platform.windows.PIPE_WAIT
import platform.windows.WriteFile
import kotlin.random.Random

/** A new path under the user's temporary folder, whose random name nobody else can guess. */
private fun privateTemporaryPath(): String {
    val root = sequenceOf("TEMP", "TMP")
        .mapNotNull { getenv(it)?.toKString() }
        .firstOrNull { it.isNotBlank() }
        ?: error("No temporary directory: TEMP and TMP are unset")
    val name = "kiteffmpeg-contract-" + Random.nextLong().toULong().toString(16)
    return "${root.trimEnd('\\', '/')}\\$name"
}

private fun writePrivateBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("Cannot open contract path for writing")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1u, bytes.size.convert(), file)
                check(written.toInt() == bytes.size) { "Short contract write" }
            }
        }
    } finally {
        check(fclose(file) == 0) { "Cannot close contract path" }
    }
}

internal actual fun materializeContractMedia(bytes: ByteArray, sha256: String): String {
    check(sha256Hex(bytes) == sha256) { "Contract fixture digest mismatch before materialization" }
    val path = privateTemporaryPath()
    writePrivateBytes(path, bytes)
    check(sha256Hex(readContractBytes(path)) == sha256) {
        "Contract fixture digest mismatch after materialization"
    }
    return path
}

internal actual fun contractOutputPath(extension: String): String {
    require(extension.all { it in 'a'..'z' || it in '0'..'9' })
    return "${privateTemporaryPath()}.$extension"
}

internal actual fun readContractBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("Cannot open contract path for reading")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Cannot seek contract path" }
        val size = ftell(file).toLong()
        check(size >= 0) { "Cannot size contract path" }
        check(fseek(file, 0, SEEK_SET) == 0) { "Cannot rewind contract path" }
        return ByteArray(size.toInt()).also { bytes ->
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    val read = fread(pinned.addressOf(0), 1u, bytes.size.convert(), file)
                    check(read.toInt() == bytes.size) { "Short contract read" }
                }
            }
        }
    } finally {
        check(fclose(file) == 0) { "Cannot close contract path" }
    }
}

internal actual fun deleteContractPath(path: String) {
    remove(path)
}

internal actual fun writeContractTranscript(text: String) {
    val destination = getenv("KITECODEC_CONTRACT_TRANSCRIPT")?.toKString()?.takeIf { it.isNotBlank() }
        ?: return
    destination.split('/', '\\').dropLast(1).runningReduce { parent, name -> "$parent\\$name" }
        .filter { it.isNotEmpty() && !it.endsWith(':') }.forEach { platform.posix.mkdir(it) }
    val temporary = "$destination.tmp"
    writePrivateBytes(temporary, text.encodeToByteArray())
    remove(destination)
    check(rename(temporary, destination) == 0) { "Cannot publish contract transcript" }
}

internal actual fun contractLiveHandleCount(): Long = 0L

/**
 * A Windows named pipe stands in for the FIFO: FFmpeg's file protocol opens its `\\.\pipe\` path
 * as it opens a file, and reads it as a stream it cannot seek.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
internal actual fun contractPausedInput(bytes: ByteArray, pauseAt: Int): ContractPausedInput {
    val path = "\\\\.\\pipe\\kiteffmpeg-contract-" + Random.nextLong().toULong().toString(16)
    val pipe = CreateNamedPipeW(
        path, PIPE_ACCESS_OUTBOUND.convert(), (PIPE_TYPE_BYTE or PIPE_WAIT).convert(), 1u, 65_536u, 65_536u, 0u, null,
    )
    check(pipe != null && pipe != INVALID_HANDLE_VALUE) { "CreateNamedPipe failed for $path" }
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            // Waits for the reader to open the pipe; a reader already there answers an error, which is fine.
            ConnectNamedPipe(pipe, null)
            if (writeAll(pipe, bytes, 0, pauseAt)) {
                kotlinx.coroutines.withTimeoutOrNull(PAUSE_LIMIT_MILLIS) { gate.await() }
                writeAll(pipe, bytes, pauseAt, bytes.size - pauseAt)
            }
            FlushFileBuffers(pipe)
            DisconnectNamedPipe(pipe)
        } finally {
            CloseHandle(pipe)
        }
    }
    return object : ContractPausedInput {
        override val path: String = path
        override fun resume() {
            gate.complete(Unit)
        }
    }
}

/** Writes [length] bytes from [offset], and answers false when the reader went away. */
@OptIn(ExperimentalForeignApi::class)
private fun writeAll(pipe: HANDLE?, bytes: ByteArray, offset: Int, length: Int): Boolean = memScoped {
    val written = alloc<DWORDVar>()
    var done = 0
    bytes.usePinned { pinned ->
        while (done < length) {
            val ok = WriteFile(pipe, pinned.addressOf(offset + done), (length - done).convert(), written.ptr, null)
            if (ok == 0 || written.value.toInt() <= 0) return@memScoped false
            done += written.value.toInt()
        }
    }
    true
}
