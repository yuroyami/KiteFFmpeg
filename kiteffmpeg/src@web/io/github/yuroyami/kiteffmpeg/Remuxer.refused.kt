package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher

public actual object Remuxer {
    public actual suspend fun remux(
        input: String,
        output: String,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((packetsWritten: Long) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Remuxing media")

    public actual suspend fun remux(
        input: String,
        inputOptions: Map<String, String>,
        output: String,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((packetsWritten: Long) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Remuxing media")

    public actual suspend fun remux(
        input: () -> MediaByteSource,
        output: MediaByteSink,
        format: String,
        outputOptions: Map<String, String>,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((packetsWritten: Long) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Remuxing media")
}
