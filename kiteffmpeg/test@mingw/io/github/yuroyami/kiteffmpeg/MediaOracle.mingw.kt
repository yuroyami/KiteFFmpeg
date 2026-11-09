// Windows' oracle runner for the codec contract suite (#279): the tool comes from PATH, where the
// CI's package manager puts it, and runs through cmd's _popen.
package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix._pclose
import platform.posix._popen
import platform.posix.fgets

/** One cmd word: quoted, with inner quotes doubled. */
private fun cmdWord(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

/** cmd strips one pair of quotes around the whole line, so the line is quoted once more. */
private fun commandLine(tool: String, arguments: List<String>, redirect: String): String =
    "\"" + (listOf(tool) + arguments).joinToString(" ", transform = ::cmdWord) + " $redirect\""

internal actual fun runMediaOracle(tool: String, arguments: List<String>): String? {
    val command = commandLine(tool, arguments, "2>NUL")
    val pipe = _popen(command, "r") ?: error("Could not start $tool")
    val output = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        while (fgets(buffer, 4096, pipe) != null) output.append(buffer.toKString())
    }
    val status = _pclose(pipe)
    check(status == 0) { "$tool exited with $status: $command" }
    return output.toString()
}

internal actual fun startMediaOracle(tool: String, arguments: List<String>): RunningOracle? {
    val pipe = _popen(commandLine(tool, arguments, ">NUL 2>&1"), "r") ?: error("Could not start $tool")
    return object : RunningOracle {
        override fun await(): Int = _pclose(pipe)
    }
}
