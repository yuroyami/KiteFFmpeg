package io.github.yuroyami.kiteffmpeg

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
    ): Unit = placeholderBackendUnavailable("Transcoding media")
}
