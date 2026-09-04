package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Refuses an output that names the input, before either is opened.
 *
 * The sink truncates on open and the source keeps reading from its buffer, so the same path
 * twice wrote a few frames over the caller's media and returned success. Identity is the file
 * key, which sees through symbolic links, hard links and any spelling of the path. An output
 * that does not exist yet cannot be the input, and an input that does not exist is left for the
 * source open to report.
 */
internal fun refuseSameFile(input: String, output: String) {
    val same = try {
        val target = File(output).toPath()
        Files.exists(target) && Files.isSameFile(File(input).toPath(), target)
    } catch (_: IOException) {
        false
    }
    if (same) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(0, "the output names the same file as the input: $output"),
        )
    }
}
