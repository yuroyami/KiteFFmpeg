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
    ) {
        Internals.requireCompatible()
        require(startMicros >= 0L && endMicros > startMicros) {
            "Invalid trim window [$startMicros, $endMicros]"
        }
        refuseSameFile(input, output)
        MediaSource.open(input).use { source ->
            val selected = if (streamIndices == null) {
                source.streams.filter { it.type != MediaType.Unknown }
            } else {
                streamIndices.map { wanted ->
                    source.streams.firstOrNull { it.index == wanted }
                        ?: throw FFmpegException(
                            FFmpegError.Internal("No stream with index $wanted in $input"),
                        )
                }
            }
            if (selected.isEmpty()) {
                throw FFmpegException(FFmpegError.Internal("Nothing to remux from $input"))
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

            MediaSink.open(output).use { sink ->
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                val copies = selected.associate { it.index to sink.addCopyStream(source, it) }
                sink.ensureHeaderWritten()
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
                            if (onProgress != null && written % 100L == 0L) onProgress(written)
                        }
                    },
                )
                onProgress?.invoke(written)
            }
        }
    }
}
