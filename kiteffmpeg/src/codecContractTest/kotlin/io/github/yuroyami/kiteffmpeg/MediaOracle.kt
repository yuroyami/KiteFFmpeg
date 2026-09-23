package io.github.yuroyami.kiteffmpeg

/**
 * Runs the host's `ffmpeg` or `ffprobe` with [arguments] and returns what it printed on standard
 * output. The two command-line tools are the independent oracle for durations, frame counts and
 * sample counts: they share no code path with this library's Kotlin layer.
 *
 * Returns null only where a test cannot start a process at all, which is an Android device. On a
 * desktop a missing tool or a nonzero exit throws, so a green run always had its oracle.
 */
internal expect fun runMediaOracle(tool: String, arguments: List<String>): String?

/** The measurements the transcode suites ask the command-line tools for. Null without an oracle. */
internal object MediaOracle {

    /** Decoded video frames in the first video stream, counted by `ffprobe -count_frames`. */
    fun videoFrameCount(path: String): Int? = runMediaOracle(
        "ffprobe",
        listOf(
            "-v", "error", "-count_frames", "-select_streams", "v:0",
            "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", path,
        ),
    )?.trim()?.toInt()

    /** Decoded audio samples per channel in the first audio stream, summed over every frame. */
    fun audioSampleCount(path: String): Long? = runMediaOracle(
        "ffprobe",
        listOf(
            "-v", "error", "-select_streams", "a:0",
            "-show_entries", "frame=nb_samples", "-of", "csv=p=0", path,
        ),
    )?.lineSequence()?.map { it.trim().trimEnd(',') }?.filter { it.isNotEmpty() }?.sumOf { it.toLong() }

    /** The container's duration as `ffprobe` reads it, in microseconds. */
    fun durationMicros(path: String): Long? = runMediaOracle(
        "ffprobe",
        listOf("-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path),
    )?.trim()?.toDouble()?.let { seconds -> (seconds * 1_000_000.0).toLong() }

    /**
     * Runs `ffmpeg -i input <arguments> output` to make a reference file. False where there is
     * no oracle, so the caller skips the comparison instead of reading a file that was never made.
     */
    fun reference(input: String, arguments: List<String>, output: String): Boolean =
        runMediaOracle("ffmpeg", listOf("-v", "error", "-y", "-i", input) + arguments + output) != null
}
