package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Makes the desktop JNI library self-contained, then lays it out for the jvm artifact.
 *
 * The macOS link pulls libraries from Homebrew that a consumer's machine has no reason to own, and
 * two of them (SvtAv1Enc, graphite2) ship no static archive there. Publishing the dylib alone would
 * work on the machine that built it and fail everywhere else, which is the worst kind of green.
 *
 * So every non-system dependency travels beside it: copied in, its own id rewritten to
 * `@loader_path`, and the referring library's load command rewritten to match. The walk is breadth
 * first because a bundled library can pull in another one. `manifest.txt` lists what came along, so
 * the loader knows what to unpack before it calls `System.load`.
 *
 * KPKMP.md 17.13, register item W-02.
 */
abstract class BundleHostJniTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jniLibrary: RegularFileProperty

    /** `macos-arm64`, `linux-x64`, `windows-x64`: the same names the vendored FFmpeg trees use. */
    @get:Input
    abstract val platformDirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteffmpeg"
        description = "Bundles the desktop JNI library and its non-system dependencies for the jvm jar."
    }

    /**
     * Mach-O only. An ELF library built here links FFmpeg statically and needs nothing but the
     * platform's own libc, so there is no dependency to bundle, no load command to rewrite and no
     * signature to repair. Doing the Mach-O dance on it would just fail.
     */
    private val isMachO: Boolean
        get() = platformDirectory.get().startsWith("macos")

    @TaskAction
    fun bundle() {
        val destination = outputDir.get().asFile
            .resolve("$RESOURCE_ROOT/${platformDirectory.get()}")
        destination.deleteRecursively()
        destination.mkdirs()

        val source = jniLibrary.get().asFile
        val jni = destination.resolve(source.name)
        source.copyTo(jni, overwrite = true)
        jni.setWritable(true)
        // A library loaded by absolute path does not need its own id, but leaving the build tree's
        // path in there makes every `otool -L` of a shipped artifact read like a mistake.
        if (isMachO) rewriteId(jni)

        val bundled = linkedSetOf<String>()
        if (!isMachO) {
            destination.resolve(MANIFEST_NAME).writeText(jni.name + "\n")
            logger.lifecycle("[KiteFFmpeg] ${jni.name} staged for ${platformDirectory.get()}, no dependencies to bundle")
            return
        }
        val pending = ArrayDeque(listOf(jni))
        while (pending.isNotEmpty()) {
            val library = pending.removeFirst()
            nonSystemDependenciesOf(library).forEach { path ->
                val leaf = File(path).name
                if (bundled.add(leaf)) {
                    val copy = destination.resolve(leaf)
                    val origin = File(path)
                    if (!origin.isFile) {
                        throw GradleException(
                            "$library depends on $path, which does not exist. The JNI library " +
                                "cannot be made self-contained without it.",
                        )
                    }
                    origin.copyTo(copy, overwrite = true)
                    copy.setWritable(true)
                    rewriteId(copy)
                    pending.addLast(copy)
                }
                run(listOf(INSTALL_NAME_TOOL, "-change", path, "@loader_path/$leaf", library.absolutePath))
            }
        }

        // install_name_tool invalidates a Mach-O signature, and Apple silicon REFUSES to load an
        // unsigned or wrongly signed library: the JVM dies with SIGKILL and no Java exception,
        // which reads as an out-of-memory kill. Ad-hoc signing is what makes the rewritten copies
        // loadable, and it must run after the last rewrite of each file.
        (bundled.map { destination.resolve(it) } + jni).forEach(::signAdHoc)

        destination.resolve(MANIFEST_NAME)
            .writeText((bundled + jni.name).joinToString("\n", postfix = "\n"))
        logger.lifecycle(
            "[KiteFFmpeg] ${jni.name} bundled with ${bundled.size} dependencies " +
                "(${bundled.joinToString(", ").ifEmpty { "none" }})",
        )
    }

    private fun signAdHoc(library: File) {
        run(listOf(CODESIGN, "--force", "--sign", "-", "--timestamp=none", library.absolutePath))
    }

    private fun rewriteId(library: File) {
        run(listOf(INSTALL_NAME_TOOL, "-id", "@loader_path/${library.name}", library.absolutePath))
    }

    /** Everything the library loads from a package manager prefix rather than from the OS. */
    private fun nonSystemDependenciesOf(library: File): List<String> =
        capture(listOf(OTOOL, "-L", library.absolutePath))
            .lineSequence()
            .drop(1)
            .map { it.trim().substringBefore(" (") }
            .filter { it.startsWith("/opt/homebrew/") || it.startsWith("/usr/local/") }
            .toList()

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("${command.first()} failed: $output")
        }
    }

    private fun capture(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("${command.first()} failed: $output")
        }
        return output
    }

    companion object {
        /** Read back by `JniLibrary.jvm.kt`; changing it changes the loader too. */
        const val RESOURCE_ROOT: String = "kiteffmpeg-native"
        const val MANIFEST_NAME: String = "manifest.txt"
        private const val OTOOL = "/usr/bin/otool"
        private const val INSTALL_NAME_TOOL = "/usr/bin/install_name_tool"
        private const val CODESIGN = "/usr/bin/codesign"
    }
}
