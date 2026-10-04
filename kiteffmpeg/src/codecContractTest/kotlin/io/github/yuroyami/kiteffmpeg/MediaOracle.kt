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

    /**
     * Each chapter's title and start as `ffprobe` reads them, the start in microseconds from the
     * start of the content, which `ffprobe` also decides.
     */
    fun chapterStarts(path: String): Map<String, Long>? {
        val origin = runMediaOracle(
            "ffprobe",
            listOf("-v", "error", "-show_entries", "format=start_time", "-of", "csv=p=0", path),
        )?.trim()?.toDouble() ?: return null
        return runMediaOracle(
            "ffprobe",
            listOf("-v", "error", "-show_entries", "chapter=start_time:chapter_tags=title", "-of", "csv=p=0", path),
        )?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.associate { line ->
            val (start, title) = line.split(',', limit = 2)
            title to ((start.toDouble() - origin) * 1_000_000.0).let { kotlin.math.round(it).toLong() }
        }
    }

    /** The container's duration as `ffprobe` reads it, in microseconds. */
    fun durationMicros(path: String): Long? = runMediaOracle(
        "ffprobe",
        listOf("-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path),
    )?.trim()?.toDouble()?.let { seconds -> (seconds * 1_000_000.0).toLong() }

    /**
     * True when the host's `ffmpeg` is release [major].[minor] or later, read from the first line
     * of `ffmpeg -version`, which names the release first, as in `6.1.1-3ubuntu5`, `7.1.1` or
     * `n7.1`. A build of FFmpeg's main branch, which names a commit count as `N-` and a number,
     * counts as later than every release. False without an oracle, and for a line that names
     * neither, so a test compares against an `ffmpeg` only when it knows the release.
     */
    fun ffmpegAtLeast(major: Int, minor: Int): Boolean {
        val line = runMediaOracle("ffmpeg", listOf("-version"))?.lineSequence()?.firstOrNull() ?: return false
        if (Regex("^ffmpeg version N-\\d").containsMatchIn(line)) return true
        val release = Regex("^ffmpeg version n?(\\d+)\\.(\\d+)").find(line) ?: return false
        val (foundMajor, foundMinor) = release.destructured
        return foundMajor.toInt() > major || (foundMajor.toInt() == major && foundMinor.toInt() >= minor)
    }

    /**
     * Runs `ffmpeg -i input <arguments> output` to make a reference file. False where there is
     * no oracle, so the caller skips the comparison instead of reading a file that was never made.
     */
    fun reference(input: String, arguments: List<String>, output: String): Boolean =
        generate(listOf("-i", input) + arguments, output)

    /** Runs `ffmpeg <arguments> output`, for an input only `ffmpeg` can make. False without an oracle. */
    fun generate(arguments: List<String>, output: String): Boolean =
        runMediaOracle("ffmpeg", listOf("-v", "error", "-y") + arguments + output) != null
}

/**
 * Starts the host's `ffmpeg` with [arguments] in the background and throws its output away, so a
 * test can use it as a live sender or listener on the loopback. Null only where a test cannot start
 * a process at all, which is an Android device; on a desktop a tool that cannot start throws.
 */
internal expect fun startMediaOracle(tool: String, arguments: List<String>): RunningOracle?

/** A command-line tool running in the background. */
internal interface RunningOracle {
    /** Waits for the tool to exit and returns its exit code. */
    fun await(): Int
}
