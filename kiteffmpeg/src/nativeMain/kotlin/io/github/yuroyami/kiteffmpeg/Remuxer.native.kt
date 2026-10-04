package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_pts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
        // The FFmpeg identity gate. First statement of the entry point.
        requireCompatibleFFmpeg()
        require(startMicros >= 0 && endMicros > startMicros) { "Invalid trim window [$startMicros, $endMicros]" }
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
                        ?: throw FFmpegException(FFmpegError.Internal("No stream with index $wanted in ${input.name}"))
                }
            }
            if (selected.isEmpty()) {
                throw FFmpegException(FFmpegError.Internal("Nothing to remux from ${input.name}"))
            }
            // Validated BEFORE the sink exists, exactly as the JVM actual does. The demuxer refuses
            // a duplicated index too, but only after a stream has been created in the output for
            // every entry, so a caller who asked for the same stream twice got a half built
            // container and then the refusal.
            if (selected.distinctBy { it.index }.size != selected.size) {
                throw FFmpegException(
                    FFmpegError.InvalidArgument(
                        0,
                        "the same stream index was asked for more than once: " +
                            selected.map { it.index }.sorted().joinToString(),
                    ),
                )
            }
            // Whoever carries video decides the stop point (sparse subtitle pts would stop late
            // or never); otherwise the first selected stream does.
            val leadIndex = (selected.firstOrNull { it.type == MediaType.Video } ?: selected.first()).index

            if (startMicros > 0) source.seekMicros(startMicros)

            output.open().use { sink ->
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                // Placed when the header is written, against the origin the copied media takes.
                sink.setSourceChapters(SourceChapters.of(source, startMicros, endMicros))
                val copies = selected.associate { it.index to sink.addCopyStream(source, it) }
                var written = 0L
                source.demuxRouted(
                    decode = emptyList(),
                    copy = selected,
                    onFrame = { it.close() },  // unreachable: nothing decodes
                    onPacket = { packet, info ->
                        // Gate the end bound on dts (monotonic in demux order); pts reorders
                        // around B-frames and would stop the demux a GOP early.
                        val dts = ffkmp_packet_dts(packet)
                        val ts = if (dts != FrameInfo.NOPTS) dts else ffkmp_packet_pts(packet)
                        // Media-relative: [endMicros] means "n microseconds into the content", but
                        // packet timestamps include the container's start offset (~1.4s on MPEG-TS),
                        // so comparing the raw value would cut the clip in the wrong place.
                        val micros = if (ts != FrameInfo.NOPTS) {
                            source.toRelativeMicros(ts, info.timeBase)
                        } else Long.MIN_VALUE
                        if (micros != Long.MIN_VALUE && micros > endMicros) {
                            if (info.index == leadIndex) throw StopDemux()
                            // other streams: drop and wait for the lead to finish the window
                        } else {
                            copies.getValue(info.index).writeCopyPacket(packet)
                            written += 1
                            if (publish != null && written % 100 == 0L) publish(written)
                        }
                    },
                )
                // The first packet wrote the header, once it had claimed the output's origin, which
                // the chapters need. A source with none still gets a valid, empty container rather
                // than no file at all.
                sink.ensureHeaderWritten()
                publish?.invoke(written)
            }
        }
    }
}
