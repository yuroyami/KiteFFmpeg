package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.security.MessageDigest

/**
 * Writes the `ffmpeg` cinterop definition of one Kotlin/Native target.
 *
 * The committed templates (`kiteffmpeg/interop/ffmpeg.def` and `ffmpeg-system.def`) hold the headers,
 * the embedded archives and the platform linker flags. The paths differ per target and per machine, so
 * this adds them: `libraryPaths` (the FFmpeg tree and the helper archive), `compilerOpts` (the header
 * folders) and, for a system FFmpeg, the `-L` and rpath flags of the test binaries.
 */
object FFmpegInterop {

    const val DEFINITION_NAME: String = "ffmpeg.def"

    fun writeDefinition(
        vendoredTemplate: File,
        systemTemplate: File,
        paths: FFmpegPaths,
        helperArchive: File,
        ownInclude: File,
        destination: File,
        singleArchive: Boolean = false,
        extraArchives: List<File> = emptyList(),
    ) {
        if (!helperArchive.isFile) throw BuildFailure("No helper archive at $helperArchive.")
        val template = if (paths.isStaticVendored) vendoredTemplate else systemTemplate
        val embedded = staticLibraries(template.readText())
        val searchDirs = listOf(File(paths.libDir), helperArchive.parentFile)
        val missing = embedded.filter { name -> searchDirs.none { it.resolve(name).isFile } }
        if (missing.isNotEmpty()) {
            throw BuildFailure("The ffmpeg definition embeds ${missing.joinToString()}, which ${searchDirs.joinToString(" and ")} do not hold.")
        }
        val embeddedFiles = embedded.map { name -> searchDirs.map { it.resolve(name) }.first(File::isFile) }
        val templateText = if (singleArchive) {
            val merged = helperArchive.resolveSibling(SINGLE_ARCHIVE_NAME)
            mergeArchives(merged, embeddedFiles + extraArchives)
            replaceStaticLibraries(template.readText(), merged.name)
        } else {
            template.readText()
        }
        val text = buildString {
            append(templateText.trimEnd())
            appendLine()
            appendLine()
            appendLine("# Added by the kiteffmpeg-native plugin for this machine.")
            appendLine("libraryPaths = ${searchDirs.joinToString(" ") { quote(it.absolutePath) }}")
            appendLine("compilerOpts = -I${quote(ownInclude.absolutePath)} -I${quote(paths.includeDir)}")
            if (!paths.isStaticVendored) {
                val lib = quote(paths.libDir)
                appendLine("linkerOpts.osx = -L$lib -Wl,-rpath,$lib")
                appendLine("linkerOpts.linux = -L$lib")
            }
            // cinterop reruns only when this file changes, so the file names the archives it embeds.
            appendLine("# Embedded archives: ${fingerprint(embeddedFiles + extraArchives)}")
        }
        destination.parentFile.mkdirs()
        if (!destination.isFile || destination.readText() != text) destination.writeText(text)
    }

    /**
     * The one archive that replaces the embedded archives on Android. Kotlin/Native gives the embedded
     * archives to the linker in directory order, and the NDK linker (gold) reads each archive once, so
     * an archive that comes before the one it needs leaves undefined symbols. Inside one archive, gold
     * searches again until nothing new resolves.
     */
    const val SINGLE_ARCHIVE_NAME: String = "libkiteffmpeg-all.a"

    /** Writes the members of [inputs], in order, into the archive [output], unless [output] already holds them. */
    fun mergeArchives(output: File, inputs: List<File>) {
        val stamp = output.resolveSibling("${output.name}.inputs")
        val expected = inputs.joinToString("\n") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        if (output.isFile && stamp.isFile && stamp.readText() == expected) return
        val archiver = llvmAr()
        output.delete()
        stamp.delete()
        // `qL` appends the members of each archive. Members with the same name in two archives stay apart.
        runArchiver(listOf(archiver.absolutePath, "qcL", output.absolutePath) + inputs.map { it.absolutePath })
        runArchiver(listOf(archiver.absolutePath, "s", output.absolutePath))
        stamp.writeText(expected)
    }

    /**
     * Writes the outline atomics helpers (`__aarch64_ldadd4_acq_rel` and the others) of the NDK compiler-rt into
     * [output]. The NDK clang that builds FFmpeg calls them on arm64, but Kotlin/Native links with an older
     * toolchain that does not have them.
     */
    fun outlineAtomicsArchive(output: File) {
        val builtins = androidNdkHome().resolve("toolchains/llvm/prebuilt").listFiles().orEmpty()
            .flatMap { host -> host.resolve("lib/clang").listFiles().orEmpty().map { it.resolve("lib/linux/$AARCH64_BUILTINS") } }
            .filter(File::isFile)
            .maxByOrNull { it.parentFile.parentFile.parentFile.name.toIntOrNull() ?: 0 }
            ?: throw BuildFailure("No $AARCH64_BUILTINS in the NDK at ${androidNdkHome()}.")
        val stamp = output.resolveSibling("${output.name}.inputs")
        val expected = "${builtins.absolutePath}:${builtins.length()}:${builtins.lastModified()}"
        if (output.isFile && stamp.isFile && stamp.readText() == expected) return
        val archiver = llvmAr()
        val members = runArchiver(listOf(archiver.absolutePath, "t", builtins.absolutePath)).lines()
            .filter { it == "aarch64.c.o" || it.startsWith("outline_atomic_") }
        if (members.none { it.startsWith("outline_atomic_") }) throw BuildFailure("$builtins has no outline atomics helpers.")
        val objects = output.resolveSibling("objects").apply { deleteRecursively(); mkdirs() }
        runArchiver(listOf(archiver.absolutePath, "x", "--output=${objects.absolutePath}", builtins.absolutePath) + members)
        output.delete()
        runArchiver(listOf(archiver.absolutePath, "crs", output.absolutePath) + members.map { objects.resolve(it).absolutePath })
        stamp.writeText(expected)
    }

    private const val AARCH64_BUILTINS = "libclang_rt.builtins-aarch64-android.a"

    private fun llvmAr(): File {
        val llvmBin = CompileKiteFFmpegCTask.resolveLlvmBinDir(
            konanDataDir().resolve("dependencies"), CompileKiteFFmpegCTask.DEFAULT_LLVM_PACKAGE,
        )
        return CompileKiteFFmpegCTask.resolveTool(llvmBin, "llvm-ar")
            ?: throw BuildFailure("No llvm-ar under $llvmBin, so the Android archives cannot be merged.")
    }

    /** [definition] with its `staticLibraries` line naming only [archive]. */
    fun replaceStaticLibraries(definition: String, archive: String): String =
        definition.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith("staticLibraries") && '=' in line) "staticLibraries = $archive" else line
        }

    private fun runArchiver(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw BuildFailure("${command.joinToString(" ")} failed:\n$output")
        return output
    }

    /** The file names on the `staticLibraries` line of a definition. */
    fun staticLibraries(definition: String): List<String> =
        definition.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("staticLibraries") && '=' in it }
            ?.substringAfter('=')
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** A digest of the name, size and modification time of each file. */
    fun fingerprint(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.name }.forEach { file ->
            digest.update("${file.name}:${file.length()}:${file.lastModified()}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun quote(path: String): String = if (' ' in path) "\"$path\"" else path
}
