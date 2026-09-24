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
        startMicros: Long,
        endMicros: Long,
        metadata: Map<String, String>,
        dispatcher: CoroutineDispatcher?,
        onProgress: ((TranscodeProgress) -> Unit)?,
    ): Unit = placeholderBackendUnavailable("Transcoding media")
}
