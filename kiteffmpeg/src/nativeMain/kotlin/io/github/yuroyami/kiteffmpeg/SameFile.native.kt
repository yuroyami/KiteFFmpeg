package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat

/**
 * Refuses an output that names the input, before either is opened. The JVM twin says why.
 *
 * Identity is device and inode, which sees through symbolic links, hard links and any spelling
 * of the path. Windows reports no inode, so there the two spellings are compared as text and a
 * link to the input is not caught. An output that does not exist yet cannot be the input, and an
 * input that does not exist is left for the source open to report.
 */
internal fun refuseSameFile(input: String, output: String) {
    val same = memScoped {
        val a = alloc<stat>()
        val b = alloc<stat>()
        when {
            stat(input, a.ptr) != 0 || stat(output, b.ptr) != 0 -> false
            a.st_ino.toLong() == 0L && b.st_ino.toLong() == 0L -> input == output
            else -> a.st_dev == b.st_dev && a.st_ino == b.st_ino
        }
    }
    if (same) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(0, "the output names the same file as the input: $output"),
        )
    }
}
