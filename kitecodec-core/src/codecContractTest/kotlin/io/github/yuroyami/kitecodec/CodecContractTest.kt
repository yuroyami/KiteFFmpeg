package io.github.yuroyami.kitecodec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(KiteCodecLowLevelApi::class)
internal class CodecContractTest {
    private val paths = mutableListOf<String>()

    private fun mediaPath(): String = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        .also(paths::add)

    private fun outputPath(extension: String): String = contractOutputPath(extension).also(paths::add)

    private fun nonBmpOutputPath(extension: String): String {
        val reserved = outputPath(extension)
        val suffix = ".$extension"
        return "${reserved.removeSuffix(suffix)}-\uD83E\uDE81$suffix".also(paths::add)
    }

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
        assertEquals(0L, contractLiveHandleCount(), "native/JNI owner leaked across a contract arm")
    }

    @Test
    fun publicContractProducesTheSameStableTranscript() {
        assertEquals(0L, contractLiveHandleCount())
        val transcript = CodecContractTranscript()
        val identity = FFmpeg.identity
        assertTrue(identity.isAcceptable, identity.describe())
        assertEquals(6, identity.libraries.size)
        transcript.put("identity.acceptable", identity.isAcceptable)
        transcript.put("identity.c_abi", identity.cAbiVersion)
        transcript.put("identity.library_count", identity.libraries.size)
        transcript.put("capability.decoder.mpeg4", FFmpeg.hasDecoder("mpeg4"))
        transcript.put("capability.decoder.missing", FFmpeg.hasDecoder("kitecodec_missing_decoder"))
        transcript.put("capability.filter.null", FFmpeg.hasFilter("null"))

        val missing = assertFailsWith<FFmpegException> {
            MediaSource.open("/definitely/not/a/kitecodec-contract-file.mp4")
        }
        assertIs<FFmpegError.FileNotFound>(missing.error)
        transcript.put("error.missing_file", "FileNotFound")

        val input = mediaPath()
        MediaSource.open(input).useOwner { source ->
            val video = assertNotNull(source.primaryVideo)
            val audio = assertNotNull(source.primaryAudio)
            assertEquals(MediaType.Video, video.type)
            assertEquals(MediaType.Audio, audio.type)
            transcript.put("source.stream_count", source.streams.size)
            transcript.put("source.video.codec", video.codec.name)
            transcript.put("source.video.width", video.video?.width)
            transcript.put("source.video.height", video.video?.height)
            val videoExtradata = assertNotNull(video.codecExtradata)
            transcript.put("source.video.extradata.size", videoExtradata.size)
            transcript.put("source.video.extradata.sha256", sha256Hex(videoExtradata))
            transcript.put("source.audio.codec", audio.codec.name)
            transcript.put("source.audio.rate", audio.audio?.sampleRate)
            val audioExtradata = assertNotNull(audio.codecExtradata)
            transcript.put("source.audio.extradata.size", audioExtradata.size)
            transcript.put("source.audio.extradata.sha256", sha256Hex(audioExtradata))
            transcript.put("source.seekable", source.isSeekable)
            transcript.put("source.duration.positive", (source.durationMicros ?: 0L) > 0L)

            source.openPacketReader(listOf(video)).useOwner { reader ->
                val packet = assertNotNull(reader.read())
                val packetCopy = packet.copy()
                val expectedStream = packet.streamIndex
                val expectedPts = packet.pts
                val expectedSize = packet.sizeBytes
                packet.close()
                assertEquals(expectedStream, packetCopy.streamIndex)
                assertEquals(expectedPts, packetCopy.pts)
                assertEquals(expectedSize, packetCopy.sizeBytes)
                assertTrue(packetCopy.sizeBytes > 0)
                transcript.put("packet.copy_independent", true)
                transcript.put("packet.stream_index", packetCopy.streamIndex)
                transcript.put("packet.size.positive", packetCopy.sizeBytes > 0)
                packetCopy.close()
                assertFailsWith<IllegalStateException> { packetCopy.copy() }
                reader.seek(0L)
                assertNotNull(reader.read()).close()
            }

            source.seekMicrosBlocking(0L)
            val decoded = decodeOne(source, video, video.codec)
            try {
                assertEquals(MediaType.Video, decoded.info.type)
                assertEquals(16, decoded.info.width)
                assertEquals(16, decoded.info.height)
                val planes = decoded.copyPlanesToByteArray()
                assertTrue(planes.isNotEmpty())
                val copy = decoded.copy()
                decoded.close()
                assertContentEquals(planes, copy.copyPlanesToByteArray())
                transcript.put("decode.named_exact", video.codec.name)
                transcript.put("frame.width", copy.info.width)
                transcript.put("frame.height", copy.info.height)
                transcript.put("frame.plane_size", planes.size)
                transcript.put("frame.plane_sha256", sha256Hex(planes))
                copy.close()
            } finally {
                decoded.close()
            }

            exerciseDecoderDrainFlushAndWrongState(source, video, transcript)

            assertFailsWith<FFmpegException> {
                source.openDecoder(video, decoder = CodecId.PcmS16)
            }
            transcript.put("decode.incompatible_named_refused", true)
        }

        exerciseFramesAndFilters(transcript)
        exerciseMuxRemuxAndTranscode(input, transcript)
        writeContractTranscript(transcript.render())
    }

    @Test
    fun streamCodecExtradataIsAnOwnedSnapshot() {
        val extradata = MediaSource.open(mediaPath()).useOwner { source ->
            assertNotNull(source.primaryVideo?.codecExtradata)
        }

        assertTrue(extradata.isNotEmpty())
    }

    @Test
    fun cancellationAndWrongStateReleaseEveryOwner() {
        assertEquals(0L, contractLiveHandleCount())
        val sourcePath = outputPath("mp4")
        writeSyntheticVideo(sourcePath, frames = 60)
        val cancelledOutput = outputPath("mp4")
        assertFailsWith<CancellationException> {
            runBlocking {
                Transcoder.transcode(
                    input = sourcePath,
                    output = cancelledOutput,
                    spec = VideoEncoderSpec(
                        codec = CodecId("mpeg4"),
                        width = 16,
                        height = 16,
                        frameRate = Rational(30, 1),
                        bitrateBps = 100_000L,
                    ),
                    onProgress = { throw CancellationException("contract cancellation") },
                )
            }
        }

        val recovery = outputPath("mkv")
        runBlocking { Remuxer.remux(sourcePath, recovery) }
        MediaSource.open(recovery).useOwner { source ->
            assertNotNull(source.primaryVideo)
        }

        val frame = Frame.ofVideo(yuv420(16, 16, 0), 16, 16, PixelFormat.Yuv420p)
        frame.close()
        assertFailsWith<IllegalStateException> { frame.copyPlanesToByteArray() }

        val source = MediaSource.open(sourcePath)
        val video = assertNotNull(source.primaryVideo)
        val sourceBaseline = contractLiveHandleCount()
        // A forged StreamInfo is now refused by canonicalization BEFORE any decoder opens
        // (audit P1-8): the refusal is a typed argument error, and nothing was allocated to leak.
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                source.decodeStreams(
                    listOf(video, video.copy(index = Int.MAX_VALUE)),
                ).toList()
            }
        }
        assertEquals(
            sourceBaseline,
            contractLiveHandleCount(),
            "a refused stream set must allocate no decoder owner",
        )
        val reader = source.openPacketReader(listOf(video))
        reader.close()
        assertFailsWith<IllegalStateException> { reader.read() }
        source.close()
        assertFailsWith<IllegalStateException> {
            source.openPacketReader(listOf(video))
        }
    }

    private fun exerciseFramesAndFilters(transcript: CodecContractTranscript) {
        val videoBytes = yuv420(16, 16, 3)
        Frame.ofVideo(videoBytes, 16, 16, PixelFormat.Yuv420p, ptsMicros = 123_456L).useOwner { frame ->
            assertContentEquals(videoBytes, frame.copyPlanesToByteArray())
            val image = frame.encodeImage(CodecId.Mjpeg)
            assertTrue(image.size > 100)
            assertEquals(0xff.toByte(), image[0])
            assertEquals(0xd8.toByte(), image[1])
            transcript.put("raw.video.roundtrip", true)
            transcript.put("raw.video.pts", frame.info.pts)
            transcript.put("image.jpeg_markers", true)

            FilterGraph.buildVideo(
                description = "null@kite\uD83E\uDE81",
                width = 16,
                height = 16,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 1_000_000),
                frameRate = Rational(30, 1),
            ).useOwner { graph ->
                var filtered: Frame? = null
                graph.feedInput(0, frame.copy()) { callbackFrame -> filtered = callbackFrame.copy() }
                graph.flushInput(0) { callbackFrame ->
                    if (filtered == null) filtered = callbackFrame.copy()
                }
                val output = assertNotNull(filtered)
                assertContentEquals(videoBytes, output.copyPlanesToByteArray())
                transcript.put("filter.video.sha256", sha256Hex(output.copyPlanesToByteArray()))
                output.close()
            }

            val rejectedFrame = frame.copy()
            FilterGraph.buildVideo(
                description = "null",
                width = 16,
                height = 16,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 1_000_000),
                frameRate = Rational(30, 1),
            ).useOwner { graph ->
                assertFailsWith<IllegalArgumentException> {
                    graph.feedInput(1, rejectedFrame) { }
                }
                assertFailsWith<IllegalStateException> { rejectedFrame.copy() }
            }

            val closedGraphFrame = frame.copy()
            val closedGraph = FilterGraph.buildVideo(
                description = "null",
                width = 16,
                height = 16,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 1_000_000),
                frameRate = Rational(30, 1),
            )
            closedGraph.close()
            assertFailsWith<IllegalStateException> {
                closedGraph.feedInput(0, closedGraphFrame) { }
            }
            assertFailsWith<IllegalStateException> { closedGraphFrame.copy() }
        }

        val audioBytes = ByteArray(64) { index -> (index * 3).toByte() }
        Frame.ofAudio(
            bytes = audioBytes,
            sampleCount = 32,
            sampleRate = 8_000,
            channels = 1,
            sampleFormat = SampleFormat.S16,
            ptsMicros = 250_000L,
        ).useOwner { frame ->
            assertContentEquals(audioBytes, frame.copyPlanesToByteArray())
            transcript.put("raw.audio.roundtrip", true)
            FilterGraph.buildAudio(
                description = "anull",
                sampleRate = 8_000,
                sampleFormat = SampleFormat.S16,
                channels = 1,
                timeBase = Rational(1, 8_000),
            ).useOwner { graph ->
                var outputCount = 0
                graph.feedInput(0, frame.copy()) { output ->
                    assertTrue(output.copyPlanesToByteArray().isNotEmpty())
                    outputCount++
                }
                graph.flushInput(0) { outputCount++ }
                assertTrue(outputCount > 0)
                transcript.put("filter.audio.output", outputCount > 0)
            }
        }
    }

    private fun exerciseMuxRemuxAndTranscode(input: String, transcript: CodecContractTranscript) {
        val encoded = nonBmpOutputPath("mp4")
        val title = "Kite \uD83E\uDE81 café"
        writeSyntheticVideo(encoded, frames = 4, title = title, proveHeaderState = true)
        MediaSource.open(encoded).useOwner { source ->
            assertEquals(title, source.metadata["title"] ?: source.metadata["TITLE"])
            transcript.put("sink.video.present", source.primaryVideo != null)
            transcript.put("sink.metadata", "standard-utf8-non-bmp")
            transcript.put("sink.path.non_bmp", true)
            transcript.put("sink.video.header", true)
            transcript.put("sink.video.write", true)
            transcript.put("sink.video.trailer", true)
        }

        exerciseAudioSink(transcript)
        exerciseDirectCopyStreamDeclaration(input, transcript)
        exercisePoisonedCopyStreamDeclaration(input, transcript)
        exerciseFaultSeamIsInertWhenUnarmed(input, transcript)
        exerciseHeaderFailureAndOpenRollback(transcript)

        val remuxed = outputPath("mkv")
        var packets = 0L
        runBlocking {
            Remuxer.remux(input, remuxed, metadata = mapOf("title" to "remuxed")) { packets = it }
        }
        MediaSource.open(remuxed).useOwner { source ->
            assertTrue(source.streams.isNotEmpty())
            transcript.put("remux.stream_count", source.streams.size)
            transcript.put("remux.readable", true)
            transcript.put("sink.copy.write", true)
        }
        transcript.put("remux.progress.nonnegative", packets >= 0L)

        val transcoded = outputPath("mkv")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = transcoded,
                videoCopy = true,
                audioCopy = true,
                metadata = mapOf("title" to "transcoded"),
            )
        }
        MediaSource.open(transcoded).useOwner { source ->
            assertNotNull(source.primaryVideo)
            transcript.put("transcode.video.present", true)
            transcript.put("transcode.audio.present", source.primaryAudio != null)
        }
        val invalid = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                Transcoder.transcode(
                    input = input,
                    output = outputPath("mp4"),
                    spec = VideoEncoderSpec(
                        codec = CodecId("mpeg4"),
                        width = 16,
                        height = 16,
                        frameRate = Rational(30, 1),
                    ),
                    videoCopy = true,
                )
            }
        }
        assertTrue(invalid.message?.contains("videoCopy") == true)
        transcript.put("transcode.invalid_combination_refused", true)
    }

    private fun exerciseAudioSink(transcript: CodecContractTranscript) {
        assertTrue(FFmpeg.hasEncoder("pcm_s16le"))
        val audioOutput = outputPath("wav")
        val sink = MediaSink.open(audioOutput)
        try {
            val encoder = sink.addAudioEncoder(
                AudioEncoderSpec(
                    codec = CodecId.PcmS16,
                    sampleRate = 8_000,
                    channels = 1,
                    sampleFormat = SampleFormat.S16,
                    bitrateBps = 128_000L,
                ),
            )
            try {
                val sampleCount = 800
                val samples = ByteArray(sampleCount * 2) { index -> (index * 5).toByte() }
                runBlocking {
                    encoder.drive(
                        flowOf(
                            Frame.ofAudio(
                                bytes = samples,
                                sampleCount = sampleCount,
                                sampleRate = 8_000,
                                channels = 1,
                                sampleFormat = SampleFormat.S16,
                                ptsMicros = 0L,
                            ),
                        ),
                    )
                }
            } finally {
                encoder.close()
            }
        } finally {
            sink.close()
        }
        sink.close()

        MediaSource.open(audioOutput).useOwner { source ->
            val audio = assertNotNull(source.primaryAudio)
            assertEquals(MediaType.Audio, audio.type)
            assertEquals(8_000, audio.audio?.sampleRate)
        }
        assertTrue(readContractBytes(audioOutput).size > 44)
        transcript.put("sink.audio.encoder", "pcm_s16le")
        transcript.put("sink.audio.write", true)
        transcript.put("sink.audio.trailer", true)
    }

    private fun exerciseDirectCopyStreamDeclaration(input: String, transcript: CodecContractTranscript) {
        val unwrittenOutput = outputPath("mkv")
        MediaSource.open(input).useOwner { source ->
            val video = assertNotNull(source.primaryVideo)
            MediaSink.open(unwrittenOutput).useOwner { sink ->
                assertNotNull(sink.addCopyStream(source, video))
                sink.setMetadata(mapOf("title" to "copy-declaration"))
            }
        }
        transcript.put("sink.copy.declared_directly", true)
    }

    /**
     * KC-EVIDENCE-MUX. The poison in `addCopyStream` becomes falsifiable.
     *
     * `avformat_new_stream` mutates the format context and FFmpeg cannot take a stream back, so a
     * failure after it leaves a half-configured stream in the muxer. The sink poisons itself for
     * exactly that reason, and until now nothing could prove it: the only steps left in the guarded
     * block are a parameter copy and a time-base write, and no caller can make either fail. The
     * `MuxFaults` seam supplies the failure this path cannot otherwise have.
     *
     * The assertion is deliberately about the SINK and not about the throw. Anyone can make a
     * function throw; what P1-10 fixed is that the sink must not still look usable afterwards.
     */
    private fun exercisePoisonedCopyStreamDeclaration(input: String, transcript: CodecContractTranscript) {
        val poisonedOutput = outputPath("mkv")
        MediaSource.open(input).useOwner { source ->
            val video = assertNotNull(source.primaryVideo)
            MediaSink.open(poisonedOutput).useOwner { sink ->
                MuxFaults.armOnce()
                try {
                    assertFailsWith<FFmpegException> { sink.addCopyStream(source, video) }
                } finally {
                    // Self-disarming already, but a test that leaves state armed on an unexpected
                    // path would poison whichever test ran next, which is worse than the bug.
                    MuxFaults.disarm()
                }
                // THE POINT: a sink that swallowed a mid-mutation failure and carried on is the
                // defect. A later declaration must refuse rather than write against a muxer holding
                // a stream that was never configured.
                assertFailsWith<Throwable> { sink.addCopyStream(source, video) }
                // `setMetadata` is deliberately NOT asserted here, and the reason is a finding.
                // The first run of this seam showed the backends DISAGREE: the JVM's setMetadata
                // goes through checkOpen and refuses a poisoned sink, while the native one checks
                // only headerWritten and closed and accepts it. Asserting either behaviour would
                // pin a divergence as if it were the contract. Recorded as `KC-POISON-SCOPE`.
            }
        }
        transcript.put("sink.copy.poisoned_on_mid_mutation_failure", true)
    }

    /** The seam is INERT unless armed, which is the only state a shipped consumer can reach. */
    private fun exerciseFaultSeamIsInertWhenUnarmed(input: String, transcript: CodecContractTranscript) {
        val output = outputPath("mkv")
        MuxFaults.disarm()
        MediaSource.open(input).useOwner { source ->
            val video = assertNotNull(source.primaryVideo)
            MediaSink.open(output).useOwner { sink ->
                assertNotNull(sink.addCopyStream(source, video))
                sink.setMetadata(mapOf("title" to "unarmed"))
            }
        }
        transcript.put("sink.copy.fault_seam_inert_when_unarmed", true)
    }

    private fun exerciseHeaderFailureAndOpenRollback(transcript: CodecContractTranscript) {
        val baseline = contractLiveHandleCount()
        assertFailsWith<FFmpegException> {
            MediaSink.open(
                outputPath("mp4"),
                options = mapOf("kitecodec_missing_muxer_option" to "1"),
            )
        }
        assertEquals(baseline, contractLiveHandleCount())

        val absentParent = outputPath("dir")
        val sink = MediaSink.open("$absentParent/output.mp4", format = "mp4")
        try {
            val encoder = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 16,
                    height = 16,
                    frameRate = Rational(30, 1),
                    bitrateBps = 100_000L,
                ),
            )
            try {
                assertFailsWith<FFmpegException> {
                    runBlocking { encoder.drive(flowOf()) }
                }
                val repeatedFailure = assertFailsWith<FFmpegException> {
                    runBlocking { encoder.drive(flowOf()) }
                }
                assertTrue(repeatedFailure.message?.contains("header failed") == true)
            } finally {
                encoder.close()
            }
        } finally {
            sink.close()
        }
        sink.close()
        assertEquals(baseline, contractLiveHandleCount())
        transcript.put("sink.open.rollback", true)
        transcript.put("sink.header.failure_sticky", true)
    }

    private fun writeSyntheticVideo(
        path: String,
        frames: Int,
        title: String? = null,
        proveHeaderState: Boolean = false,
    ) {
        MediaSink.open(path).useOwner { sink ->
            if (title != null) sink.setMetadata(mapOf("title" to title))
            val encoder = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 16,
                    height = 16,
                    frameRate = Rational(30, 1),
                    bitrateBps = 100_000L,
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                runBlocking { encoder.drive(flowOf(), progressEveryNFrames = 0) }
            }
            runBlocking {
                encoder.drive(
                    (0 until frames).asFlow().map { index ->
                        Frame.ofVideo(
                            bytes = yuv420(16, 16, index),
                            width = 16,
                            height = 16,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = index * 1_000_000L / 30L,
                        )
                    },
                    progressEveryNFrames = 1,
                )
            }
            if (proveHeaderState) {
                assertFailsWith<IllegalStateException> {
                    sink.setMetadata(mapOf("late" to "must-be-refused"))
                }
            }
        }
    }

    private fun decodeOne(source: MediaSource, stream: StreamInfo, decoderName: CodecId): Frame {
        return source.openDecoder(stream, decoder = decoderName).useOwner { decoder ->
            source.openPacketReader(listOf(stream)).useOwner readerOwner@ { reader ->
                var atEof = false
                var drainSent = false
                var pending: Packet? = null
                try {
                    repeat(10_000) {
                        decoder.receive()?.let { return@readerOwner it }
                        if (decoder.isDrained) error("Decoder drained without producing a frame")
                        if (atEof) {
                            if (!drainSent && decoder.send(null)) drainSent = true
                        } else {
                            if (pending == null) pending = reader.read()
                            val packet = pending
                            if (packet == null) {
                                atEof = true
                            } else if (decoder.send(packet)) {
                                packet.close()
                                pending = null
                            }
                        }
                    }
                } finally {
                    pending?.close()
                }
                error("Decoder made no progress")
            }
        }
    }

    private fun exerciseDecoderDrainFlushAndWrongState(
        source: MediaSource,
        stream: StreamInfo,
        transcript: CodecContractTranscript,
    ) {
        source.openDecoder(stream, decoder = stream.codec).useOwner { decoder ->
            source.openPacketReader(listOf(stream)).useOwner { reader ->
                reader.seek(0L)
                var pending: Packet? = null
                var inputEnded = false
                var drainSent = false
                var frameCount = 0
                var attempts = 0
                try {
                    while (!decoder.isDrained && attempts++ < 10_000) {
                        val frame = decoder.receive()
                        if (frame != null) {
                            frameCount++
                            frame.close()
                            continue
                        }
                        if (decoder.isDrained) break

                        val packet = pending
                        if (packet != null) {
                            if (decoder.send(packet)) {
                                packet.close()
                                pending = null
                            }
                            continue
                        }

                        if (!inputEnded) {
                            pending = reader.read()
                            if (pending == null) inputEnded = true
                            continue
                        }

                        if (!drainSent && decoder.send(null)) drainSent = true
                    }
                } finally {
                    pending?.close()
                }
                assertTrue(decoder.isDrained, "decoder did not report EOF after the full drain")
                assertTrue(frameCount > 0)
                assertTrue(drainSent)

                decoder.flush()
                assertTrue(!decoder.isDrained)
                reader.seek(0L)
                val closedPacket = assertNotNull(reader.read())
                closedPacket.close()
                assertFailsWith<IllegalStateException> { decoder.send(closedPacket) }

                reader.seek(0L)
                val freshPacket = assertNotNull(reader.read())
                var acceptedAfterFlush = false
                var sendAttempts = 0
                try {
                    while (!acceptedAfterFlush && sendAttempts++ < 1_000) {
                        if (decoder.send(freshPacket)) {
                            acceptedAfterFlush = true
                        } else {
                            decoder.receive()?.close()
                        }
                    }
                } finally {
                    freshPacket.close()
                }
                assertTrue(acceptedAfterFlush)

                decoder.close()
                decoder.close()
                assertFailsWith<IllegalStateException> { decoder.send(null) }
                assertFailsWith<IllegalStateException> { decoder.receive() }
                assertFailsWith<IllegalStateException> { decoder.flush() }

                transcript.put("decode.full_drain", true)
                transcript.put("decode.flush.clears_drained", true)
                transcript.put("decode.flush.accepts_new_input", true)
                transcript.put("decode.closed.guards", true)
            }
        }
    }

    private fun MediaSource.seekMicrosBlocking(micros: Long) {
        runBlocking { seekMicros(micros) }
    }

    private fun yuv420(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { offset -> ((offset + index * 7) % 200 + 20).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private inline fun <T : AutoCloseable, R> T.useOwner(block: (T) -> R): R {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
