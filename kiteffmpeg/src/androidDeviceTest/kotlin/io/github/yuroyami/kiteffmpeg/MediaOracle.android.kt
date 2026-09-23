package io.github.yuroyami.kiteffmpeg

/** A device has no `ffmpeg` or `ffprobe`, so the transcode suites keep only their own checks there. */
internal actual fun runMediaOracle(tool: String, arguments: List<String>): String? = null
