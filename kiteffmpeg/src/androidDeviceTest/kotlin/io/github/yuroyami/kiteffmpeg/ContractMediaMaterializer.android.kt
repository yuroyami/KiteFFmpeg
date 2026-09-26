package io.github.yuroyami.kiteffmpeg

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

private fun contractCacheFile(prefix: String, suffix: String): File {
    val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    return File.createTempFile(prefix, suffix, cache)
}

internal actual fun materializeContractMedia(bytes: ByteArray, sha256: String): String {
    check(sha256Hex(bytes) == sha256) { "Contract fixture digest mismatch before materialization" }
    val file = contractCacheFile("kiteffmpeg-contract-", ".mp4")
    file.writeBytes(bytes)
    check(sha256Hex(file.readBytes()) == sha256) { "Contract fixture digest mismatch after materialization" }
    return file.absolutePath
}

internal actual fun contractOutputPath(extension: String): String {
    require(extension.matches(Regex("[a-z0-9]+")))
    return contractCacheFile("kiteffmpeg-contract-output-", ".$extension").also { it.delete() }.absolutePath
}

internal actual fun readContractBytes(path: String): ByteArray = File(path).readBytes()

internal actual fun deleteContractPath(path: String) {
    File(path).delete()
}

internal actual fun writeContractTranscript(text: String) {
    // The device contract asserts the same scalars in-process; only JVM/native are byte-compared.
}

internal actual fun contractLiveHandleCount(): Long = Internals.liveHandles()

internal actual fun contractPausedInput(bytes: ByteArray, pauseAt: Int): ContractPausedInput {
    val path = contractOutputPath("pipe")
    android.system.Os.mkfifo(path, 0x180)
    val gate = java.util.concurrent.CountDownLatch(1)
    kotlin.concurrent.thread(isDaemon = true, name = "contract-pipe-writer") {
        // A reader that stops early breaks the pipe. That ends this writer, and nothing else.
        runCatching {
            java.io.FileOutputStream(path).use { out ->
                out.write(bytes, 0, pauseAt)
                out.flush()
                gate.await(PAUSE_LIMIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                out.write(bytes, pauseAt, bytes.size - pauseAt)
            }
        }
    }
    return object : ContractPausedInput {
        override val path: String = path
        override fun resume() = gate.countDown()
    }
}
