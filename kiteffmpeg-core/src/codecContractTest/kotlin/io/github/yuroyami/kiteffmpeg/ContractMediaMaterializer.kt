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
