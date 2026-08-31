package io.github.yuroyami.kiteffmpeg

public actual object Remuxer {
    public actual suspend fun remux(
        input: String,
        output: String,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        onProgress: ((packetsWritten: Long) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Remuxing media")
}
