package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.remove
import platform.posix.rename

private fun privateTemporaryPath(): String = memScoped {
    val root = sequenceOf("TMPDIR", "TEMP", "TMP")
        .mapNotNull { getenv(it)?.toKString() }
        .firstOrNull { it.isNotBlank() }
        ?: error("No private temporary directory")
    val template = "${root.trimEnd('/')}/kiteffmpeg-contract-XXXXXX".cstr.getPointer(this)
    val descriptor = mkstemp(template)
    check(descriptor >= 0) { "mkstemp failed" }
    check(close(descriptor) == 0) { "close failed for contract temporary file" }
    template.toKString()
}

private fun writePrivateBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("Cannot open contract path for writing")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
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
    val reserved = privateTemporaryPath()
    check(remove(reserved) == 0) { "Cannot release reserved contract path" }
    return "$reserved.$extension"
}

internal actual fun readContractBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("Cannot open contract path for reading")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Cannot seek contract path" }
        val size = ftell(file)
        check(size >= 0) { "Cannot size contract path" }
        check(fseek(file, 0, SEEK_SET) == 0) { "Cannot rewind contract path" }
        return ByteArray(size.toInt()).also { bytes ->
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    val read = fread(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
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
    val temporary = "$destination.tmp"
    writePrivateBytes(temporary, text.encodeToByteArray())
    check(rename(temporary, destination) == 0) { "Cannot publish contract transcript" }
}


internal actual fun contractLiveHandleCount(): Long = 0L
