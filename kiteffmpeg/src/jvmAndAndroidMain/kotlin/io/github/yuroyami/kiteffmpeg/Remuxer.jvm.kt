package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.cancellation.CancellationException

public actual object Remuxer {
    @Throws(FFmpegException::class, CancellationException::class)
    public actual suspend fun remux(
        input: String,
        output: String,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((packetsWritten: Long) -> Unit)?,
    ): Unit = remuxEnds(inputAt(input, emptyMap()), outputAt(output), streamIndices, startMicros, endMicros, metadata, dispatcher, onProgress)

    @Throws(FFmpegException::class, CancellationException::class)
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
    ): Unit = remuxEnds(inputAt(input, inputOptions), outputAt(output), streamIndices, startMicros, endMicros, metadata, dispatcher, onProgress)

    @Throws(FFmpegException::class, CancellationException::class)
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
    ): Unit = remuxEnds(inputFrom(input), outputInto(output, format, outputOptions), streamIndices, startMicros, endMicros, metadata, dispatcher, onProgress)

    /** The three overloads, once they have said where the input and the output are. */
    private suspend fun remuxEnds(
        input: InputEnd,
        output: OutputEnd,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((packetsWritten: Long) -> Unit)?,
    ) {
        Internals.requireCompatible()
        require(startMicros >= 0L && endMicros > startMicros) {
            "Invalid trim window [$startMicros, $endMicros]"
        }
        if (input.path != null && output.path != null) refuseSameFile(input.path, output.path)
        runTranscode(dispatcher ?: Dispatchers.IO, onProgress) { publish ->
            remuxHere(input, output, streamIndices, startMicros, endMicros, metadata, publish)
        }
    }

    /** The whole remux, on the calling thread, handing its packet counts to [publish]. */
    private suspend fun remuxHere(
        input: InputEnd,
        output: OutputEnd,
        streamIndices: List<Int>?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        publish: ((packetsWritten: Long) -> Unit)?,
    ) {
        input.open().use { source ->
            val selected = if (streamIndices == null) {
                source.streams.filter { it.type != MediaType.Unknown }
            } else {
                streamIndices.map { wanted ->
                    source.streams.firstOrNull { it.index == wanted }
                        ?: throw FFmpegException(
                            FFmpegError.Internal("No stream with index $wanted in ${input.name}"),
                        )
                }
            }
            if (selected.isEmpty()) {
                throw FFmpegException(FFmpegError.Internal("Nothing to remux from ${input.name}"))
            }
            // Validated BEFORE the sink exists. The demuxer refuses a duplicated index too, but it
            // only sees the mapping after a stream has been created in the output for every entry,
            // so a caller who asked for the same stream twice got a half built container and then
            // the refusal. The complete mapping is checked here, where nothing has
            // been mutated yet.
            if (selected.distinctBy { it.index }.size != selected.size) {
                throw FFmpegException(
                    FFmpegError.InvalidArgument(
                        0,
                        "the same stream index was asked for more than once: " +
                            selected.map { it.index }.sorted().joinToString(),
                    ),
                )
            }
            val leadIndex = (selected.firstOrNull { it.type == MediaType.Video } ?: selected.first()).index
            if (startMicros > 0L) source.seekMicros(startMicros)

            output.open().use { sink ->
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                // Placed when the header is written, against the origin the copied media takes.
                sink.setSourceChapters(SourceChapters.of(source, startMicros, endMicros))
                val copies = selected.associate { it.index to sink.addCopyStream(source, it) }
                var written = 0L
                source.demuxRouted(
                    decode = emptyList(),
                    copy = selected,
                    onFrame = { it.close() },
                    onPacket = { packet, info ->
                        val dts = Internals.packetDts(packet)
                        val timestamp = if (dts != FrameInfo.NOPTS) dts else Internals.packetPts(packet)
                        val micros = if (timestamp != FrameInfo.NOPTS) {
                            source.toRelativeMicros(timestamp, info.timeBase)
                        } else Long.MIN_VALUE
                        if (micros != Long.MIN_VALUE && micros > endMicros) {
                            if (info.index == leadIndex) throw StopDemux()
                        } else {
                            copies.getValue(info.index).writeCopyPacket(packet)
                            written += 1L
                            if (publish != null && written % 100L == 0L) publish(written)
                        }
                    },
                )
                // The first packet wrote the header. A source with none still gets a valid,
                // empty container rather than no file at all.
                sink.ensureHeaderWritten()
                publish?.invoke(written)
            }
        }
    }
}
