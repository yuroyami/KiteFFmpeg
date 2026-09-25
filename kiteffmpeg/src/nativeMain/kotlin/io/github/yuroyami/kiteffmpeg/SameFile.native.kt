package io.github.yuroyami.kiteffmpeg

/** A file's device and inode. Windows reports an inode of zero for every file. */
internal data class FileIdentity(val device: Long, val inode: Long)

/**
 * [path]'s device and inode, or null when it does not exist. There is one actual per platform
 * family, because `stat`'s field types differ between them and the shared native code cannot read
 * those fields.
 */
internal expect fun fileIdentity(path: String): FileIdentity?

/**
 * Refuses an output that names the input, before either is opened. The JVM twin says why.
 *
 * Identity is device and inode, which sees through symbolic links, hard links and any spelling
 * of the path. Windows reports no inode, so there the two spellings are compared as text and a
 * link to the input is not caught. An output that does not exist yet cannot be the input, and an
 * input that does not exist is left for the source open to report.
 */
internal fun refuseSameFile(input: String, output: String) {
    val a = fileIdentity(input)
    val b = fileIdentity(output)
    val same = when {
        a == null || b == null -> false
        a.inode == 0L && b.inode == 0L -> input == output
        else -> a == b
    }
    if (same) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(0, "the output names the same file as the input: $output"),
        )
    }
}
