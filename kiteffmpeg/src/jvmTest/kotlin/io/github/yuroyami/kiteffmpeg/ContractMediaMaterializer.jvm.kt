package io.github.yuroyami.kiteffmpeg

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal actual fun materializeContractMedia(bytes: ByteArray, sha256: String): String {
    check(sha256Hex(bytes) == sha256) { "Contract fixture digest mismatch before materialization" }
    val path = Files.createTempFile("kiteffmpeg-contract-", ".mp4")
    Files.write(path, bytes)
    check(sha256Hex(Files.readAllBytes(path)) == sha256) { "Contract fixture digest mismatch after materialization" }
    return path.toAbsolutePath().toString()
}

internal actual fun contractOutputPath(extension: String): String {
    require(extension.matches(Regex("[a-z0-9]+")))
    val path = Files.createTempFile("kiteffmpeg-contract-output-", ".$extension")
    Files.delete(path)
    return path.toAbsolutePath().toString()
}

internal actual fun readContractBytes(path: String): ByteArray = Files.readAllBytes(Path.of(path))

internal actual fun deleteContractPath(path: String) {
    Files.deleteIfExists(Path.of(path))
}

internal actual fun writeContractTranscript(text: String) {
    val configured = System.getProperty("kiteffmpeg.contract.transcript")?.takeIf { it.isNotBlank() } ?: return
    val destination = Path.of(configured).toAbsolutePath()
    Files.createDirectories(destination.parent)
    val temporary = Files.createTempFile(destination.parent, destination.fileName.toString(), ".tmp")
    Files.writeString(temporary, text)
    try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal actual fun contractLiveHandleCount(): Long = Internals.liveHandles()
