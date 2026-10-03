package io.github.yuroyami.kiteffmpeg

/**
 * Where a container's length came from, as FFmpeg records it in
 * `AVFormatContext.duration_estimation_method`. See [MediaSource.durationOrigin].
 */
public enum class DurationOrigin {
    /**
     * Read from the streams' own timestamps at the start and the end of the input, which FFmpeg
     * does for MPEG transport and program streams. As exact as the timestamps.
     */
    Timestamps,

    /**
     * Declared by the container or by one of its streams, as MP4, Matroska and most other formats
     * do. As exact as the file that wrote it.
     */
    Header,

    /**
     * Estimated from the input's size and the bit rate of its first frames, because the input
     * declares no length, as an ADTS AAC file or an MP3 without its Xing header does. Close for a
     * constant bit rate and wrong by minutes, in either direction, for a variable one. A hint, never
     * the end of the media.
     */
    Bitrate;

    internal companion object {
        /** The origin for FFmpeg's `AVDurationEstimationMethod` [code], or null for one this does not know. */
        fun ofCode(code: Int): DurationOrigin? = when (code) {
            0 -> Timestamps
            1 -> Header
            2 -> Bitrate
            else -> null
        }
    }
}
