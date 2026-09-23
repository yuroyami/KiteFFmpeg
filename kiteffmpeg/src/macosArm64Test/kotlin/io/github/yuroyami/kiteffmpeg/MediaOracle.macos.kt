package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

/** Homebrew's prefixes first, where CONTRIBUTING puts the tools, then the system's. */
private fun oracleExecutable(tool: String): String =
    listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        .map { "$it/$tool" }
        .firstOrNull { access(it, X_OK) == 0 }
        ?: error("The $tool oracle is not installed (brew install ffmpeg)")

/** One shell word, whatever it contains. */
private fun shellWord(text: String): String = "'" + text.replace("'", "'\\''") + "'"

internal actual fun runMediaOracle(tool: String, arguments: List<String>): String? {
    val command = (listOf(oracleExecutable(tool)) + arguments).joinToString(" ", transform = ::shellWord)
    val pipe = popen("$command 2>/dev/null", "r") ?: error("Could not start $tool")
    val output = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        while (fgets(buffer, 4096, pipe) != null) output.append(buffer.toKString())
    }
    // pclose returns the wait status; the exit code is its second byte.
    val status = pclose(pipe)
    check(status == 0) { "$tool exited with ${status shr 8}: $command" }
    return output.toString()
}
