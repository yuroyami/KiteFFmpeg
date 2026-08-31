package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_pts

public actual object Transcoder {

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

        MediaSource.open(input).use { source ->
            val videoStream = if (spec != null || videoCopy) {
                source.primaryVideo
                    ?: throw FFmpegException(FFmpegError.Internal("No video stream in $input (use spec = null for audio-only)"))
            } else null
            val vinfo = videoStream?.video
            val audioStream = if (audioSpec != null || audioCopy) source.primaryAudio else null
            val ainfo = audioStream?.audio
            val subtitleStreams = if (subtitleCopy) source.streams.filter { it.type == MediaType.Subtitle } else emptyList()

            // The stream whose timestamps drive the end-of-trim stop: video when present, else
            // audio, else the first copied subtitle. Subtitles were left out entirely, so asking
            // for subtitleCopy on its own failed here even though it is a perfectly good output
            // (audit P1-15): extracting the subtitles from a film is exactly that request.
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

            MediaSink.open(output).use { sink ->
                // All encoders + copy mappings + metadata must exist before the header.
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                val venc = if (spec != null) sink.addVideoEncoder(spec) else null
                val aenc = if (audioSpec != null && ainfo != null) sink.addAudioEncoder(audioSpec) else null
                val vcopy = if (videoCopy && videoStream != null) sink.addCopyStream(source, videoStream) else null
                val acopy = if (audioCopy && audioStream != null) sink.addCopyStream(source, audioStream) else null
                val subCopies = subtitleStreams.associate { it.index to sink.addCopyStream(source, it) }

                // Graphs are built LAZILY, from the first decoded frame of each stream, and
                // rebuilt when a frame's format changes mid-stream. Codec parameters lie exactly
                // where it hurts: they can be unknown, differ from what the decoder actually
                // negotiated, and say nothing about mid-file transitions (audit KiteFFmpeg P1-2).
                // The vars live at this scope so the finally below owns them either way.
                var videoGraph: FilterGraph? = null
                var audioGraph: FilterGraph? = null
                try {
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
                                if (onProgress == null) return
                                sinceReport += 1
                                if (!force && sinceReport < progressEvery) return
                                sinceReport = 0
                                val outMicros = primaryCore?.outputMicros
                                    ?: (copiedMicros - startMicros).coerceAtLeast(0)
                                onProgress(
                                    TranscodeProgress(
                                        framesEncoded = venc?.core?.framesEncoded ?: 0,
                                        outputMicros = outMicros,
                                        percent = totalWindowMicros?.let {
                                            (outMicros.toDouble() / it).coerceIn(0.0, 1.0)
                                        },
                                    )
                                )
                            }

                            /**
                             * Frame pts to media-relative micros, read in the frame's own
                             * time-base. Graph output frames carry the graph's time-base and
                             * decoder frames carry the stream's.
                             *
                             * The result is relative, not absolute, because startMicros and
                             * endMicros are measured from the start of the content.
                             *
                             * @see MediaSource.startTimeMicros for the two timelines involved
                             */
                            fun ptsMicros(frame: Frame): Long =
                                if (frame.info.hasPts) {
                                    source.toRelativeMicros(frame.info.pts, frame.streamTimeBase)
                                } else Long.MIN_VALUE  // treat as "always inside the window"

                            // End-bound is re-checked at the encoder door: filter graphs buffer
                            // frames, so their flush can emit frames past the trim end that the
                            // demux-side check never saw.
                            fun encodeVideo(frame: Frame) {
                                val micros = ptsMicros(frame)
                                if (micros != Long.MIN_VALUE && micros > endMicros) { frame.close(); return }
                                venc!!.core.encode(videoPacket, frame)
                                reportMaybe()
                            }
                            fun encodeAudio(frame: Frame) {
                                val micros = ptsMicros(frame)
                                if (micros != Long.MIN_VALUE && micros > endMicros) { frame.close(); return }
                                aenc!!.core.encode(audioPacket, frame)
                                if (venc == null) reportMaybe()
                            }

                            // The lazy graph builders. Keyed on what the DECODER produced; a key
                            // change flushes the old graph into the encoder (its buffered frames
                            // belong to the old format) and builds a fresh one.
                            var videoGraphKey: List<Any>? = null
                            fun videoGraphFor(frame: Frame): FilterGraph? {
                                if (videoFilter == null || videoStream == null) return null
                                val info = frame.info
                                val key = listOf(info.width, info.height, info.pixelFormat.name)
                                videoGraph?.let { existing ->
                                    if (key == videoGraphKey) return existing
                                    existing.flushInto(::encodeVideo)
                                    existing.close()
                                    videoGraph = null
                                }
                                return FilterGraph.buildVideo(
                                    description = videoFilter,
                                    width = info.width,
                                    height = info.height,
                                    pixelFormat = info.pixelFormat,
                                    timeBase = videoStream.timeBase,
                                    frameRate = vinfo?.frameRate ?: Rational(25, 1),
                                    sampleAspectRatio = info.sampleAspectRatio,
                                ).also {
                                    videoGraph = it
                                    videoGraphKey = key
                                }
                            }
                            var audioGraphKey: List<Any>? = null
                            fun audioGraphFor(frame: Frame): FilterGraph {
                                val info = frame.info
                                val key = listOf(info.sampleRate, info.channelCount, info.sampleFormat.name)
                                audioGraph?.let { existing ->
                                    if (key == audioGraphKey) return existing
                                    existing.flushInto(::encodeAudio)
                                    existing.close()
                                    audioGraph = null
                                }
                                // Re-encoded audio always runs through a graph: it resamples and
                                // reformats to what the encoder negotiated and chunks output to
                                // the codec's fixed frame size.
                                return FilterGraph.buildAudio(
                                    description = audioFilter ?: "anull",
                                    sampleRate = info.sampleRate,
                                    sampleFormat = info.sampleFormat,
                                    channels = info.channelCount,
                                    timeBase = audioStream!!.timeBase,
                                    outputSampleRate = aenc!!.sampleRate,
                                    outputSampleFormat = aenc.sampleFormat,
                                    outputChannels = aenc.channels,
                                ).also { graph ->
                                    if (aenc.frameSize > 0) graph.setOutputFrameSize(aenc.frameSize)
                                    audioGraph = graph
                                    audioGraphKey = key
                                }
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
                                    val micros = ptsMicros(frame)
                                    val isLead = frame.streamIndex == leadStream.index
                                    when {
                                        micros != Long.MIN_VALUE && micros > endMicros -> {
                                            frame.close()
                                            if (isLead) throw StopDemux()
                                            // Non-lead frames past the end are just dropped;
                                            // the lead stream decides when to stop demuxing.
                                        }
                                        // Only filter the start when actually trimming. At
                                        // start=0, negative pts (audio priming) must pass.
                                        startMicros > 0 && micros != Long.MIN_VALUE && micros < startMicros ->
                                            frame.close()  // decode-discard up to the exact start
                                        else -> when (frame.streamIndex) {
                                            videoStream?.index -> {
                                                val graph = videoGraphFor(frame)
                                                if (graph != null) graph.feedFrame(frame, ::encodeVideo)
                                                else encodeVideo(frame)
                                            }
                                            audioStream?.index ->
                                                audioGraphFor(frame).feedFrame(frame, ::encodeAudio)
                                            else -> frame.close()
                                        }
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
                                    // Media-relative, same reason as ptsMicros above.
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

                            // Drain filter graphs, then flush encoders.
                            videoGraph?.flushInto(::encodeVideo)
                            audioGraph?.flushInto(::encodeAudio)
                            venc?.core?.finish(videoPacket)
                            aenc?.core?.finish(audioPacket)
                            reportMaybe(force = true)
                        }
                    }
                } finally {
                    videoGraph?.close()
                    audioGraph?.close()
                    venc?.close()
                    aenc?.close()
                }
            }
        }
    }
}
