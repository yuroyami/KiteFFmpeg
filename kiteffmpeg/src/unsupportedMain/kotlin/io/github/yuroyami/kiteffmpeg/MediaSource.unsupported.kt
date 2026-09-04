package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions
import kotlinx.coroutines.flow.Flow

public actual class MediaSource private constructor() : AutoCloseable {
    public actual val streams: List<StreamInfo>
        get() = placeholderBackendUnavailable("Reading media streams")
    public actual val durationMicros: Long?
        get() = placeholderBackendUnavailable("Reading media duration")
    public actual val formatName: String
        get() = placeholderBackendUnavailable("Reading the media format")
    public actual val metadata: Map<String, String>
        get() = placeholderBackendUnavailable("Reading media metadata")
    public actual val chapters: List<Chapter>
        get() = placeholderBackendUnavailable("Reading media chapters")
    public actual val unusedOpenOptions: List<String>
        get() = placeholderBackendUnavailable("Reading media open options")
    public actual val streamDivergences: List<StreamDivergence>
        // Nothing decodes here, so nothing was ever compared. Empty rather than a refusal: this is
        // "no disagreement observed", which is exactly true, and it matches corruptDataSkipped's
        // 0L on this backend rather than unusedOpenOptions' throw.
        get() = emptyList()
    public actual val startTimeMicros: Long
        get() = placeholderBackendUnavailable("Reading media start time")
    public actual val bitrateBps: Long?
        get() = placeholderBackendUnavailable("Reading the container bit rate")
    public actual val isSeekable: Boolean
        get() = placeholderBackendUnavailable("Reading media seekability")
    public actual val primaryVideo: StreamInfo?
        get() = placeholderBackendUnavailable("Reading the primary video stream")
    public actual val primaryAudio: StreamInfo?
        get() = placeholderBackendUnavailable("Reading the primary audio stream")

    public actual fun decodedFrames(stream: StreamInfo): Flow<Frame> =
        placeholderBackendUnavailable("Decoding media frames")

    public actual fun decodeStreams(streams: List<StreamInfo>): Flow<Frame> =
        placeholderBackendUnavailable("Decoding media streams")

    public actual suspend fun seekMicros(micros: Long): Unit =
        placeholderBackendUnavailable("Seeking media")

    public actual suspend fun extractFrame(atMicros: Long, stream: StreamInfo?): Frame =
        placeholderBackendUnavailable("Extracting a media frame")

    public actual fun openPacketReader(streams: List<StreamInfo>): PacketReader =
        placeholderBackendUnavailable("Opening a packet reader")

    @Throws(FFmpegException::class)
    public actual fun openDecoder(
        stream: StreamInfo,
        threadCount: Int,
        lowDelay: Boolean,
        decoder: CodecId?,
        options: DecoderOptions?,
        hardware: HardwareAccel?,
        corruptData: CorruptData,
    ): StreamDecoder = placeholderBackendUnavailable("Opening a stream decoder")

    public actual var corruptData: CorruptData = CorruptData.Skip

    public actual var corruptDataSkipped: Long = 0L
        private set

    public actual fun interrupt(): Unit = Unit

    actual override fun close(): Unit = Unit

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(path: String): MediaSource =
            placeholderBackendUnavailable("Opening media")

        @Throws(FFmpegException::class)
        public actual fun open(path: String, options: Map<String, String>): MediaSource =
            placeholderBackendUnavailable("Opening media")

        @Throws(FFmpegException::class)
        public actual fun open(io: MediaByteSource, options: Map<String, String>): MediaSource =
            placeholderBackendUnavailable("Opening media")
    }
}
