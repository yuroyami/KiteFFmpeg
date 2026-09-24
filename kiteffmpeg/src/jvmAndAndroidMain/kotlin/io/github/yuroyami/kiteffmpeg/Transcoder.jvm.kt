package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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
        dispatcher: CoroutineDispatcher?,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ) {
        Internals.requireCompatible()
        require(!(videoCopy && (spec != null || videoFilter != null))) {
            "videoCopy is mutually exclusive with spec/videoFilter"
        }
        require(!(audioCopy && (audioSpec != null || audioFilter != null))) {
            "audioCopy is mutually exclusive with audioSpec/audioFilter"
        }
        require(spec != null || videoFilter == null) { "videoFilter requires a video encoder spec" }
        require(spec != null || videoCopy || audioSpec != null || audioCopy || subtitleCopy) {
            "Nothing to output: no video spec or copy, no audio, no subtitle copy"
        }
        require(startMicros >= 0L && endMicros > startMicros) {
            "Invalid trim window [$startMicros, $endMicros]"
        }
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
                    ?: throw FFmpegException(FFmpegError.Internal("No video stream in $input"))
            } else null
            val audioStream = if (audioSpec != null || audioCopy) source.primaryAudio else null
            val subtitles = if (subtitleCopy) {
                source.streams.filter { it.type == MediaType.Subtitle }
            } else emptyList()
            // Video, else audio, else the first copied subtitle. Subtitles were left out, so
            // asking for subtitleCopy on its own failed here even though extracting the subtitles
            // from a film is exactly that request.
            val lead = videoStream ?: audioStream ?: subtitles.firstOrNull()
                ?: throw FFmpegException(
                    FFmpegError.Internal("Input has none of the requested streams"),
                )
            val totalWindowMicros = run {
                val duration = source.durationMicros
                val end = if (endMicros == Long.MAX_VALUE) duration else minOf(endMicros, duration ?: endMicros)
                end?.let { (it - startMicros).coerceAtLeast(1L) }
            }

            if (startMicros > 0L) {
                if (videoCopy) source.seekMicros(startMicros) else source.seekForDecode(startMicros)
            }

            MediaSink.open(output).use { sink ->
                if (metadata.isNotEmpty()) sink.setMetadata(metadata)
                val videoEncoder = spec?.let(sink::addVideoEncoder)
                val audioEncoder = if (audioSpec != null && audioStream != null) {
                    sink.addAudioEncoder(audioSpec)
                } else null
                val videoCopyStream = if (videoCopy && videoStream != null) {
                    sink.addCopyStream(source, videoStream)
                } else null
                val audioCopyStream = if (audioCopy && audioStream != null) {
                    sink.addCopyStream(source, audioStream)
                } else null
                val subtitleCopies = subtitles.associate { it.index to sink.addCopyStream(source, it) }

                var videoGraph: FilterGraph? = null
                var audioGraph: FilterGraph? = null
                // The video is written at the spec's constant rate: frames are dropped or repeated
                // against the input timeline, so a rate change keeps the duration. A decoder's frame
                // durations hold; a filter's may not.
                val videoRate = spec?.let { ConstantFrameRate(it.frameRate, durationsHold = videoFilter == null) }
                try {
                    val videoInfo = videoStream?.video
                    if (videoFilter != null && videoInfo != null) {
                        videoGraph = FilterGraph.buildVideo(
                            videoFilter,
                            videoInfo.width,
                            videoInfo.height,
                            videoInfo.pixelFormat,
                            videoStream.timeBase,
                            videoInfo.frameRate,
                            videoInfo.sampleAspectRatio,
                        )
                    }
                    val audioInfo = audioStream?.audio
                    if (audioEncoder != null && audioStream != null && audioInfo != null) {
                        audioGraph = FilterGraph.buildAudio(
                            audioFilter ?: "anull",
                            audioInfo.sampleRate,
                            audioInfo.sampleFormat,
                            audioInfo.channels,
                            audioStream.timeBase,
                            audioEncoder.sampleRate,
                            audioEncoder.sampleFormat,
                            audioEncoder.channels,
                        ).also { graph ->
                            if (audioEncoder.frameSize > 0) graph.setOutputFrameSize(audioEncoder.frameSize)
                        }
                    }

                    sink.ensureHeaderWritten()
                    withPacket { videoPacket ->
                        withPacket { audioPacket ->
                            val progressEvery = if (videoEncoder != null) 30L else 100L
                            var sinceReport = 0L
                            val primaryCore = videoEncoder?.core ?: audioEncoder?.core

                            /**
                             * How far a COPY-only output has got.
                             *
                             * Progress was read from the encoders alone, so a `-c copy` transcode
                             * reported zero percent from beginning to end while doing real work at
                             * full speed. Copied packets carry the timeline just as well.
                             */
                            var copiedMicros = 0L
                            fun noteCopied(micros: Long) {
                                if (micros > copiedMicros) copiedMicros = micros
                            }

                            fun report(force: Boolean = false) {
                                if (publish == null) return
                                sinceReport += 1L
                                if (!force && sinceReport < progressEvery) return
                                sinceReport = 0L
                                val micros = primaryCore?.outputMicros
                                    ?: (copiedMicros - startMicros).coerceAtLeast(0L)
                                publish(
                                    TranscodeProgress(
                                        videoEncoder?.core?.framesEncoded ?: 0L,
                                        micros,
                                        totalWindowMicros?.let {
                                            (micros.toDouble() / it).coerceIn(0.0, 1.0)
                                        },
                                    ),
                                )
                            }

                            val trim = TrimWindow(startMicros, endMicros, source.startTimeMicros)

                            // One output frame per tick of the spec's rate, each its own copy.
                            fun encodeVideoTick(frame: Frame, tick: Long) {
                                videoEncoder!!.core.encode(videoPacket, frame.copyAt(tick, videoRate!!.tickBase))
                                report()
                            }

                            // No trim check here. The trim applies once, to the decoded input
                            // below; a filter that moves time may put its frames past endMicros,
                            // and they still belong to the selection.
                            fun encodeVideo(frame: Frame) {
                                frame.use { videoRate!!.push(it, ::encodeVideoTick) }
                            }

                            fun encodeAudio(frame: Frame) {
                                audioEncoder!!.core.encode(audioPacket, frame)
                                if (videoEncoder == null) report()
                            }

                            val decode = listOfNotNull(
                                videoStream?.takeIf { videoEncoder != null },
                                audioStream?.takeIf { audioGraph != null },
                            )
                            val copy = listOfNotNull(
                                videoStream?.takeIf { videoCopyStream != null },
                                audioStream?.takeIf { audioCopyStream != null },
                            ) + subtitles

                            source.demuxRouted(
                                decode,
                                copy,
                                onFrame = { frame ->
                                    when {
                                        trim.isPastEnd(frame) -> {
                                            val isLead = frame.streamIndex == lead.index
                                            frame.close()
                                            if (isLead) throw StopDemux()
                                        }
                                        frame.streamIndex == videoStream?.index -> when {
                                            trim.startsBeforeStart(frame) -> frame.close()
                                            else -> videoGraph?.feedFrame(frame, ::encodeVideo) ?: encodeVideo(frame)
                                        }
                                        frame.streamIndex == audioStream?.index -> {
                                            // Cut to the sample: a block that straddles a bound
                                            // keeps the part inside it.
                                            val kept = trim.keptAudio(frame)
                                            if (kept !== frame) frame.close()
                                            if (kept != null) audioGraph!!.feedFrame(kept, ::encodeAudio)
                                        }
                                        else -> frame.close()
                                    }
                                },
                                onPacket = { packet, info ->
                                    val dts = Internals.packetDts(packet)
                                    val pts = Internals.packetPts(packet)
                                    val gateTimestamp = if (dts != FrameInfo.NOPTS) dts else pts
                                    val gateMicros = if (gateTimestamp != FrameInfo.NOPTS) {
                                        source.toRelativeMicros(gateTimestamp, info.timeBase)
                                    } else Long.MIN_VALUE
                                    val ptsMicros = if (pts != FrameInfo.NOPTS) {
                                        source.toRelativeMicros(pts, info.timeBase)
                                    } else Long.MIN_VALUE
                                    val pastEnd = gateMicros != Long.MIN_VALUE && gateMicros > endMicros
                                    val isVideoCopy = info.index == videoCopyStream?.sourceIndex
                                    val beforeStart = !isVideoCopy && startMicros > 0L &&
                                        ptsMicros != Long.MIN_VALUE && ptsMicros < startMicros
                                    when {
                                        pastEnd -> if (info.index == lead.index) throw StopDemux()
                                        beforeStart -> Unit
                                        isVideoCopy -> {
                                            videoCopyStream.writeCopyPacket(packet)
                                            if (ptsMicros != Long.MIN_VALUE) noteCopied(ptsMicros)
                                        }
                                        info.index == audioCopyStream?.sourceIndex -> {
                                            audioCopyStream.writeCopyPacket(packet)
                                            if (ptsMicros != Long.MIN_VALUE) noteCopied(ptsMicros)
                                        }
                                        else -> subtitleCopies[info.index]?.let { copy ->
                                            copy.writeCopyPacket(packet)
                                            if (ptsMicros != Long.MIN_VALUE) noteCopied(ptsMicros)
                                        }
                                    }
                                    // A copy-only run has no encoder to drive the report.
                                    if (primaryCore == null) report()
                                },
                            )

                            videoGraph?.flushInto(::encodeVideo)
                            audioGraph?.flushInto(::encodeAudio)
                            videoRate?.finish(::encodeVideoTick)
                            videoEncoder?.core?.finish(videoPacket)
                            audioEncoder?.core?.finish(audioPacket)
                            report(force = true)
                        }
                    }
                } finally {
                    videoGraph?.close()
                    audioGraph?.close()
                    videoRate?.close()
                    videoEncoder?.close()
                    audioEncoder?.close()
                }
            }
        }
    }
}
