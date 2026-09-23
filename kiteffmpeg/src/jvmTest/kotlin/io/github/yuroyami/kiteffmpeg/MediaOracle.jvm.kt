package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.util.concurrent.TimeUnit

/** Homebrew's prefixes first, where CONTRIBUTING puts the tools, then the system and the PATH. */
private fun oracleExecutable(tool: String): String =
    listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        .map { File(it, tool) }
        .firstOrNull { it.canExecute() }
        ?.path
        ?: tool

internal actual fun runMediaOracle(tool: String, arguments: List<String>): String? {
    val command = listOf(oracleExecutable(tool)) + arguments
    val process = try {
        ProcessBuilder(command).start()
    } catch (missing: java.io.IOException) {
        throw IllegalStateException("The $tool oracle is not installed (brew install ffmpeg)", missing)
    }
    val output = process.inputStream.bufferedReader().readText()
    val errors = process.errorStream.bufferedReader().readText()
    check(process.waitFor(120, TimeUnit.SECONDS)) { "$tool did not finish: $command" }
    check(process.exitValue() == 0) { "$tool exited with ${process.exitValue()}: $command\n$errors" }
    return output
}
