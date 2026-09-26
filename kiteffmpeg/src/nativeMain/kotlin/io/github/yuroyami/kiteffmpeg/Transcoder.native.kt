package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_pts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.cancellation.CancellationException

public actual object Transcoder {

    @Throws(FFmpegException::class, CancellationException::class)
    public actual suspend fun transcode(
        input: String,
        output: String,
        spec: VideoEncoderSpec?,
        videoFilter: String?,
        videoCopy: Boolean,
        audioSpec: AudioEncoderSpec?,
        audioFilter: String?,
        audioCopy: Boolean,
        subtitleCopy: Boolean,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ) {
        // The FFmpeg identity gate. First statement of the entry point.
        requireCompatibleFFmpeg()
        require(!(videoCopy && (spec != null || videoFilter != null))) {
            "videoCopy is mutually exclusive with spec/videoFilter: copied packets never touch a decoder, so they can't be filtered or re-encoded"
        }
        require(!(audioCopy && (audioSpec != null || audioFilter != null))) {
            "audioCopy is mutually exclusive with audioSpec/audioFilter: copied packets never touch a decoder, so they can't be filtered or re-encoded"
        }
        require(spec != null || videoFilter == null) { "videoFilter requires a video encoder spec" }
        require(spec != null || videoCopy || audioSpec != null || audioCopy || subtitleCopy) {
            "Nothing to output: no video spec or copy, no audio, no subtitle copy"
        }
        require(startMicros >= 0 && endMicros > startMicros) { "Invalid trim window [$startMicros, $endMicros]" }
        runTranscode(dispatcher ?: Dispatchers.IO, onProgress) { publish ->
            transcodeHere(
                input, output, spec, videoFilter, videoCopy, audioSpec, audioFilter, audioCopy,
                subtitleCopy, startMicros, endMicros, metadata, publish,
            )
        }
    }

    /** The whole transcode, on the calling thread, handing its progress reports to [publish]. */
    private suspend fun transcodeHere(
        input: String,
        output: String,
        spec: VideoEncoderSpec?,
        videoFilter: String?,
        videoCopy: Boolean,
        audioSpec: AudioEncoderSpec?,
        audioFilter: String?,
        audioCopy: Boolean,
        subtitleCopy: Boolean,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        publish: ((TranscodeProgress) -> Unit)?,
    ) {
        refuseSameFile(input, output)

        MediaSource.open(input).use { source ->
            val videoStream = if (spec != null || videoCopy) {
                source.primaryVideo
                    ?: throw FFmpegException(FFmpegError.Internal("No video stream in $input (use spec = null for audio-only)"))
            } else null
            val audioStream = if (audioSpec != null || audioCopy) source.primaryAudio else null
            val ainfo = audioStream?.audio
            val subtitleStreams = if (subtitleCopy) source.streams.filter { it.type == MediaType.Subtitle } else emptyList()

            // The stream whose timestamps drive the end-of-trim stop: video when present, else
            // audio, else the first copied subtitle. Subtitles were left out entirely, so asking
            // for subtitleCopy on its own failed here even though it is a perfectly good output:
            // extracting the subtitles from a film is exactly that request.
            val leadStream = videoStream ?: audioStream ?: subtitleStreams.firstOrNull()
                ?: throw FFmpegException(
                    FFmpegError.Internal("Input has none of the requested streams"),
                )

            // Progress denominator: trim window clamped by what the container declares.
            val totalWindowMicros: Long? = run {
                val dur = source.durationMicros
                val end = if (endMicros == Long.MAX_VALUE) dur else minOf(endMicros, dur ?: endMicros)
                end?.let { (it - startMicros).coerceAtLeast(1) }
            }

            if (startMicros > 0) {
                if (videoCopy) {
                    // A copied video stream deliberately keeps EVERY packet from the landing
                    // keyframe onwards, so seeking extra-early would put that pre-roll in the
                    // output as real content. Take the exact keyframe seek and its documented
                    // keyframe snap.
                    source.seekMicros(startMicros)
                } else {
                    // Decoding paths discard forward to the exact start, so landing early is
                    // free. Landing late (which indexless containers do) would silently cut
                    // content the caller asked for. Copied audio and subtitles alongside a
                    // decoded video stream are unaffected: the beforeStart gate drops their
                    // pre-roll.
                    source.seekForDecode(startMicros)
                }
            }

            // What the source declares and the specs leave open: colour, pixel shape and HDR
            // metadata from the first frame the encoder will receive, the channel layout from the
            // audio stream.
            val videoSpec = spec?.let { requested ->
                if (!requested.inheritsAnything) requested
                else requested.inheriting(firstEncodedFrameInfo(input, videoStream!!, videoFilter, startMicros), videoStream)
            }
            val audioEncoderSpec = audioSpec?.inheriting(audioStream)

            MediaSink.open(output).use { sink ->
                // All encoders + copy mappings + metadata must exist before the header.
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                sink.setChapters(
                    chaptersForOutput(
                        source.chapters,
                        originMicros = source.startTimeMicros + startMicros,
                        lengthMicros = if (endMicros == Long.MAX_VALUE) Long.MAX_VALUE else endMicros - startMicros,
                    ),
                )
                val venc = if (videoSpec != null) sink.addVideoEncoder(videoSpec) else null
                val aenc = if (audioEncoderSpec != null && ainfo != null) sink.addAudioEncoder(audioEncoderSpec) else null
                val vcopy = if (videoCopy && videoStream != null) sink.addCopyStream(source, videoStream) else null
                val acopy = if (audioCopy && audioStream != null) sink.addCopyStream(source, audioStream) else null
                val subCopies = subtitleStreams.associate { it.index to sink.addCopyStream(source, it) }

                // Built from what each stream declares and rebuilt whenever a frame's shape differs
                // (see ShapedGraph). The vars live at this scope so the finally below owns them.
                var videoGraph: ShapedGraph<VideoShape>? = null
                var audioGraph: ShapedGraph<AudioShape>? = null
                // The video is written at the spec's constant rate: frames are dropped or repeated
                // against the input timeline, so a rate change keeps the duration. A decoder's frame
                // durations hold; a filter's may not.
                val videoRate = spec?.let { ConstantFrameRate(it.frameRate, durationsHold = videoFilter == null) }
                try {
                    if (videoFilter != null && videoStream != null) {
                        videoGraph = transcodeVideoGraph(videoFilter, videoStream)
                    }
                    if (aenc != null && audioStream != null) {
                        // Re-encoded audio always runs through a graph: it converts to what the
                        // encoder negotiated and chunks the output to the codec's frame size.
                        audioGraph = transcodeAudioGraph(audioFilter, audioStream, aenc)
                    }
                    // Write the header eagerly, like Remuxer does. Without this a source that
                    // yields no frames at all never reaches the drain loop that would trigger it,
                    // so avio_open never runs and the call returns "successfully" having created
                    // no file whatsoever. An empty but valid container is the honest result.
                    sink.ensureHeaderWritten()

                    withPacket { videoPacket ->
                        withPacket { audioPacket ->
                            val progressEvery = if (venc != null) 30L else 100L
                            var sinceReport = 0L
                            val primaryCore = venc?.core ?: aenc?.core

                            /**
                             * How far a COPY-only output has got, in microseconds.
                             *
                             * Progress was read from the encoders alone, so a transcode with
                             * nothing to encode, `-c copy` on every stream, reported zero percent
                             * from beginning to end while doing real work at full speed. Copied
                             * packets carry the timeline just as well; they just have no encoder
                             * to ask.
                             */
                            var copiedMicros = 0L
                            fun noteCopied(micros: Long) {
                                if (micros > copiedMicros) copiedMicros = micros
                            }

                            fun reportMaybe(force: Boolean = false) {
                                if (publish == null) return
                                sinceReport += 1
                                if (!force && sinceReport < progressEvery) return
                                sinceReport = 0
                                val outMicros = primaryCore?.outputMicros
                                    ?: (copiedMicros - startMicros).coerceAtLeast(0)
                                publish(
                                    TranscodeProgress(
                                        framesEncoded = venc?.core?.framesEncoded ?: 0,
                                        outputMicros = outMicros,
                                        percent = totalWindowMicros?.let {
                                            (outMicros.toDouble() / it).coerceIn(0.0, 1.0)
                                        },
                                    )
                                )
                            }

                            // Both bounds are relative to the start of the content (see
                            // MediaSource.startTimeMicros).
                            val trim = TrimWindow(startMicros, endMicros, source.startTimeMicros)

                            // One output frame per tick of the spec's rate, each its own copy.
                            fun encodeVideoTick(frame: Frame, tick: Long) {
                                venc!!.core.encode(videoPacket, frame.copyAt(tick, videoRate!!.tickBase))
                                reportMaybe()
                            }

                            // No trim check here. The trim applies once, to the decoded input
                            // below; a filter that moves time may put its frames past endMicros,
                            // and they still belong to the selection.
                            fun encodeVideo(frame: Frame) {
                                frame.use { videoRate!!.push(it, ::encodeVideoTick) }
                            }
                            fun encodeAudio(frame: Frame) {
                                aenc!!.core.encode(audioPacket, frame)
                                if (venc == null) reportMaybe()
                            }

                            val decodeList = listOfNotNull(
                                videoStream?.takeIf { venc != null },
                                audioStream?.takeIf { aenc != null },
                            )
                            val copyList = listOfNotNull(
                                videoStream?.takeIf { vcopy != null },
                                audioStream?.takeIf { acopy != null },
                            ) + subtitleStreams

                            source.demuxRouted(
                                decode = decodeList,
                                copy = copyList,
                                onFrame = { frame ->
                                    when {
                                        trim.isPastEnd(frame) -> {
                                            val isLead = frame.streamIndex == leadStream.index
                                            frame.close()
                                            // Non-lead frames past the end are just dropped;
                                            // the lead stream decides when to stop demuxing.
                                            if (isLead) throw StopDemux()
                                        }
                                        frame.streamIndex == videoStream?.index -> when {
                                            // Decode-discard up to the exact start.
                                            trim.startsBeforeStart(frame) -> frame.close()
                                            else -> videoGraph?.feed(frame, ::encodeVideo) ?: encodeVideo(frame)
                                        }
                                        frame.streamIndex == audioStream?.index -> {
                                            // Cut to the sample: a block that straddles a bound
                                            // keeps the part inside it. The decoded block picks
                                            // the graph (see ShapedGraph.feed).
                                            val decoded = frame.info
                                            val kept = trim.keptAudio(frame)
                                            if (kept !== frame) frame.close()
                                            if (kept != null) audioGraph!!.feed(kept, ::encodeAudio, shapeFrom = decoded)
                                        }
                                        else -> frame.close()
                                    }
                                },
                                onPacket = { packet, info ->
                                    // Copied packets: keyframe-snapped at start (a copied video
                                    // stream keeps everything from the seek keyframe onwards,
                                    // because dropping "before start" packets would break decode
                                    // until the next keyframe), pts-filtered for audio and
                                    // subtitles, end-bounded.
                                    // End detection gates on dts (monotonic in demux order); pts
                                    // reorders around B-frames and would stop the demux early.
                                    val pktDts = ffkmp_packet_dts(packet)
                                    val pktPts = ffkmp_packet_pts(packet)
                                    val gateTs = if (pktDts != FrameInfo.NOPTS) pktDts else pktPts
                                    // Media-relative, like the trim window's bounds.
                                    val gateMicros = if (gateTs != FrameInfo.NOPTS) {
                                        source.toRelativeMicros(gateTs, info.timeBase)
                                    } else Long.MIN_VALUE
                                    val ptsMs = if (pktPts != FrameInfo.NOPTS) {
                                        source.toRelativeMicros(pktPts, info.timeBase)
                                    } else Long.MIN_VALUE
                                    val pastEnd = gateMicros != Long.MIN_VALUE && gateMicros > endMicros
                                    val isVideoCopy = info.index == vcopy?.sourceIndex
                                    val beforeStart = !isVideoCopy && startMicros > 0 &&
                                        ptsMs != Long.MIN_VALUE && ptsMs < startMicros
                                    when {
                                        pastEnd -> if (info.index == leadStream.index) throw StopDemux()
                                        beforeStart -> {}  // drop
                                        isVideoCopy -> {
                                            vcopy!!.writeCopyPacket(packet)
                                            if (ptsMs != Long.MIN_VALUE) noteCopied(ptsMs)
                                        }
                                        info.index == acopy?.sourceIndex -> {
                                            acopy.writeCopyPacket(packet)
                                            if (ptsMs != Long.MIN_VALUE) noteCopied(ptsMs)
                                        }
                                        else -> subCopies[info.index]?.let { copy ->
                                            copy.writeCopyPacket(packet)
                                            if (ptsMs != Long.MIN_VALUE) noteCopied(ptsMs)
                                        }
                                    }
                                    // A copy-only run has no encoder to drive the report, so the
                                    // packets themselves do it.
                                    if (primaryCore == null) reportMaybe()
                                },
                            )

                            // Drain filter graphs and the held video frame, then flush encoders.
                            videoGraph?.flush(::encodeVideo)
                            audioGraph?.flush(::encodeAudio)
                            videoRate?.finish(::encodeVideoTick)
                            venc?.core?.finish(videoPacket)
                            aenc?.core?.finish(audioPacket)
                            reportMaybe(force = true)
                        }
                    }
                } finally {
                    videoGraph?.close()
                    audioGraph?.close()
                    videoRate?.close()
                    venc?.close()
                    aenc?.close()
                }
            }
        }
    }
}
