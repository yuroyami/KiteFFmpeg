package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher

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
        subtitleCodec: CodecId?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Transcoding media")

    public actual suspend fun transcode(
        input: String,
        inputOptions: Map<String, String>,
        output: String,
        spec: VideoEncoderSpec?,
        videoFilter: String?,
        videoCopy: Boolean,
        audioSpec: AudioEncoderSpec?,
        audioFilter: String?,
        audioCopy: Boolean,
        subtitleCopy: Boolean,
        subtitleCodec: CodecId?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Transcoding media")

    public actual suspend fun transcode(
        input: () -> MediaByteSource,
        output: MediaByteSink,
        format: String,
        outputOptions: Map<String, String>,
        spec: VideoEncoderSpec?,
        videoFilter: String?,
        videoCopy: Boolean,
        audioSpec: AudioEncoderSpec?,
        audioFilter: String?,
        audioCopy: Boolean,
        subtitleCopy: Boolean,
        subtitleCodec: CodecId?,
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Transcoding media")
}
