package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow

public actual class MediaSink private constructor() : AutoCloseable {
    @Throws(FFmpegException::class)
    public actual fun addVideoEncoder(spec: VideoEncoderSpec): VideoEncoder =
        placeholderBackendUnavailable("Adding a video encoder")

    @Throws(FFmpegException::class)
    public actual fun addAudioEncoder(spec: AudioEncoderSpec): AudioEncoder =
        placeholderBackendUnavailable("Adding an audio encoder")

    @Throws(FFmpegException::class)
    public actual fun addCopyStream(source: MediaSource, stream: StreamInfo): CopyStream =
        placeholderBackendUnavailable("Adding a copy stream")

    @Throws(FFmpegException::class)
    public actual fun setMetadata(metadata: Map<String, String>): Unit =
        placeholderBackendUnavailable("Setting output metadata")

    actual override fun close(): Unit = Unit

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(path: String, format: String?, options: Map<String, String>): MediaSink =
            placeholderBackendUnavailable("Opening a media sink")
    }
}

public actual class CopyStream private constructor() {
    @KiteFFmpegLowLevelApi
    public actual fun write(packet: Packet): Unit = placeholderBackendUnavailable("Writing a packet")
}

public actual class VideoEncoder private constructor() : AutoCloseable {
    public actual suspend fun drive(
        input: Flow<Frame>,
        onProgress: ((framesEncoded: Long) -> Unit)?,
        progressEveryNFrames: Int,
    ): Unit = placeholderBackendUnavailable("Encoding video")

    actual override fun close(): Unit = Unit
}

public actual class AudioEncoder private constructor() : AutoCloseable {
    public actual val frameSize: Int
        get() = placeholderBackendUnavailable("Reading an audio encoder frame size")
    public actual val sampleFormat: SampleFormat
        get() = placeholderBackendUnavailable("Reading an audio encoder sample format")
    public actual val sampleRate: Int
        get() = placeholderBackendUnavailable("Reading an audio encoder sample rate")
    public actual val channels: Int
        get() = placeholderBackendUnavailable("Reading an audio encoder channel count")

    public actual suspend fun drive(input: Flow<Frame>): Unit =
        placeholderBackendUnavailable("Encoding audio")

    actual override fun close(): Unit = Unit
}
