package io.github.yuroyami.kiteffmpeg

/** Materialize verified private test bytes and return an absolute path accepted by [MediaSource.open]. */
internal expect fun materializeContractMedia(bytes: ByteArray, sha256: String): String

/** Reserve an absolute private output path with the requested extension. */
internal expect fun contractOutputPath(extension: String): String

/** Read a contract output back for stable hashing. */
internal expect fun readContractBytes(path: String): ByteArray

/** Best-effort cleanup for paths returned by the two functions above. */
internal expect fun deleteContractPath(path: String)

/** Write the stable transcript when this platform's gate configured an output path. */
internal expect fun writeContractTranscript(text: String)

/** JNI arms expose their exact token ledger; the pointer-owning native arm has no token table. */
internal expect fun contractLiveHandleCount(): Long

/** A named pipe the test feeds itself, so it decides when an input's reads can progress. */
internal interface ContractPausedInput {
    /** The pipe's path, which [MediaSource.open] reads like a file. */
    val path: String

    /** Sends the rest of the bytes. */
    fun resume()
}

/**
 * Serves [bytes] through a named pipe and stops after the first [pauseAt] of them until
 * [ContractPausedInput.resume], or until [PAUSE_LIMIT_MILLIS] pass, so a test that never resumes
 * still ends. Delete [ContractPausedInput.path] with [deleteContractPath].
 */
internal expect fun contractPausedInput(bytes: ByteArray, pauseAt: Int): ContractPausedInput

/** How long a paused input waits for [ContractPausedInput.resume] before it sends the rest anyway. */
internal const val PAUSE_LIMIT_MILLIS: Long = 20_000L
