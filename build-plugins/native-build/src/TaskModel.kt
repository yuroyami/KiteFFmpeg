package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/*
 * The build steps in this module came from Gradle task classes. These small types keep the names of
 * the Gradle provider API that the steps use (Property, DirectoryProperty, ExecOperations and so on),
 * so the step bodies stay as they were. Nothing here depends on Gradle.
 */

/** A build step failed. The message says what to do about it. */
class BuildFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** One configured value. [get] fails when the value was never set. */
open class Property<T : Any>(private val label: String = "value") {
    private var value: T? = null
    private var fallback: T? = null

    fun set(newValue: T?) {
        value = newValue
    }

    fun convention(newValue: T): Property<T> = apply { fallback = newValue }

    fun get(): T = value ?: fallback ?: throw BuildFailure("The $label of this build step was never set.")

    val orNull: T? get() = value ?: fallback

    val isPresent: Boolean get() = orNull != null

    fun getOrElse(defaultValue: T): T = orNull ?: defaultValue
}

class Directory(val asFile: File) {
    fun file(path: String): RegularFile = RegularFile(asFile.resolve(path))
    fun dir(path: String): Directory = Directory(asFile.resolve(path))
    override fun toString(): String = asFile.path
}

class RegularFile(val asFile: File) {
    override fun toString(): String = asFile.path
}

class DirectoryProperty : Property<Directory>("directory") {
    fun set(file: File) = set(Directory(file))
    fun file(path: String): RegularFile = get().file(path)
    fun dir(path: String): Directory = get().dir(path)
}

class RegularFileProperty : Property<RegularFile>("file") {
    fun set(file: File) = set(RegularFile(file))
}

class ListProperty<T : Any> : Property<List<T>>("list") {
    init {
        convention(emptyList())
    }

    fun add(element: T) = set(get() + element)
    fun addAll(elements: Iterable<T>) = set(get() + elements)
}

class MapProperty<K : Any, V : Any> : Property<Map<K, V>>("map") {
    init {
        convention(emptyMap())
    }

    fun put(key: K, value: V) = set(get() + (key to value))
}

/** A set of files. [from] takes files, directories (all files below them) and other collections. */
class ConfigurableFileCollection {
    private val roots = mutableListOf<Any>()

    fun from(vararg paths: Any): ConfigurableFileCollection = apply { roots.addAll(paths) }

    fun setFrom(vararg paths: Any) {
        roots.clear()
        roots.addAll(paths)
    }

    val files: Set<File>
        get() = buildSet { roots.forEach { addRoot(it) } }

    /** The paths as given, with folders kept as folders. [files] lists the files inside a folder instead. */
    val elements: Set<File>
        get() = buildSet {
            fun visit(root: Any?) {
                when (root) {
                    null -> {}
                    is Iterable<*> -> root.forEach(::visit)
                    is Array<*> -> root.forEach(::visit)
                    is ConfigurableFileCollection -> addAll(root.elements)
                    is File -> add(root)
                    is java.nio.file.Path -> add(root.toFile())
                    is String -> add(File(root))
                    is RegularFile -> add(root.asFile)
                    is Directory -> add(root.asFile)
                    else -> throw BuildFailure("Cannot use $root (${root::class.simpleName}) as a file.")
                }
            }
            roots.forEach(::visit)
        }

    val isEmpty: Boolean get() = files.isEmpty()

    val asPath: String get() = files.joinToString(File.pathSeparator) { it.absolutePath }

    private fun MutableSet<File>.addRoot(root: Any) {
        when (root) {
            is File -> if (root.isDirectory) addAll(root.walkTopDown().filter { it.isFile }.sorted()) else add(root)
            is RegularFile -> add(root.asFile)
            is Directory -> addRoot(root.asFile)
            is String -> addRoot(File(root))
            is java.nio.file.Path -> addRoot(root.toFile())
            is ConfigurableFileCollection -> addAll(root.files)
            is Iterable<*> -> root.forEach { it?.let { item -> addRoot(item) } }
            is Array<*> -> root.forEach { it?.let { item -> addRoot(item) } }
            else -> throw BuildFailure("Cannot use $root (${root::class.simpleName}) as a file.")
        }
    }
}

/** Build output. `lifecycle`, `warn` and `error` always print; `info` prints with KITEFFMPEG_BUILD_VERBOSE=1. */
object BuildLog {
    private val verbose = System.getenv("KITEFFMPEG_BUILD_VERBOSE") == "1"
    fun lifecycle(message: String) = println(message)
    fun info(message: String) {
        if (verbose) println(message)
    }
    fun warn(message: String) = System.err.println("warning: $message")
    fun error(message: String) = System.err.println("error: $message")
}

/** The base of every build step: a name for messages, a log and a scratch directory. */
abstract class BuildStep {
    var name: String = this::class.simpleName ?: "build step"
    var group: String? = null
    var description: String? = null
    val logger: BuildLog get() = BuildLog

    private var scratch: File? = null

    /** A scratch directory for this step. Callers may point it into the build directory. */
    var temporaryDir: File
        get() = scratch ?: Files.createTempDirectory("kiteffmpeg-$name").toFile().also { scratch = it }
        set(value) {
            value.mkdirs()
            scratch = value
        }
}

/** Runs external processes. The output goes to the build log unless a step captures it. */
class ExecOperations {
    fun exec(configure: ExecSpec.() -> Unit): ExecResult {
        val spec = ExecSpec().apply(configure)
        if (spec.command.isEmpty()) throw BuildFailure("No command to run.")
        val builder = ProcessBuilder(spec.command)
        spec.workingDir?.let { builder.directory(it) }
        builder.environment().putAll(spec.environment)
        val separateErrors = spec.errorOutput != null
        builder.redirectErrorStream(!separateErrors)
        val process = try {
            builder.start()
        } catch (failure: java.io.IOException) {
            throw BuildFailure("Cannot start ${spec.command.first()}: ${failure.message}", failure)
        }
        val errors = if (separateErrors) {
            Thread { process.errorStream.copyTo(spec.errorOutput!!) }.apply { start() }
        } else {
            null
        }
        val stdout = spec.standardOutput
        if (stdout != null) {
            process.inputStream.copyTo(stdout)
        } else {
            process.inputStream.bufferedReader().forEachLine { println(it) }
        }
        val exit = process.waitFor()
        errors?.join()
        val result = ExecResult(exit, spec.command)
        if (!spec.isIgnoreExitValue) result.assertNormalExitValue()
        return result
    }
}

class ExecSpec {
    var command: List<String> = emptyList()
    var workingDir: File? = null
    val environment: MutableMap<String, String> = mutableMapOf()
    var standardOutput: OutputStream? = null
    var errorOutput: OutputStream? = null
    var isIgnoreExitValue: Boolean = false

    fun commandLine(vararg arguments: Any) {
        command = arguments.flatMap { if (it is Iterable<*>) it.map(Any?::toString) else listOf(it.toString()) }
    }

    fun commandLine(arguments: Iterable<*>) {
        command = arguments.map { it.toString() }
    }

    fun workingDir(directory: Any) {
        workingDir = if (directory is File) directory else File(directory.toString())
    }

    fun environment(name: String, value: Any) {
        environment[name] = value.toString()
    }

    fun environment(values: Map<String, *>) {
        values.forEach { (name, value) -> environment[name] = value.toString() }
    }
}

class ExecResult(val exitValue: Int, private val command: List<String>) {
    fun assertNormalExitValue(): ExecResult {
        if (exitValue != 0) {
            throw BuildFailure("${command.joinToString(" ")} finished with exit value $exitValue.")
        }
        return this
    }
}

/** Runs [command] and returns what it printed. Fails on a non-zero exit. */
fun captureOutput(command: List<String>, workingDir: File? = null): String {
    val stdout = ByteArrayOutputStream()
    ExecOperations().exec {
        commandLine(command)
        workingDir?.let { workingDir(it) }
        standardOutput = stdout
    }
    return stdout.toString()
}

/** Copies a directory, replacing what the destination held. */
class FileSystemOperations {
    fun sync(configure: CopySpec.() -> Unit) {
        val spec = CopySpec().apply(configure)
        val destination = spec.destination ?: throw BuildFailure("A sync has no destination.")
        destination.deleteRecursively()
        destination.mkdirs()
        spec.sources.forEach { source -> source.copyRecursively(destination, overwrite = true) }
    }
}

class CopySpec {
    val sources = mutableListOf<File>()
    var destination: File? = null

    fun from(source: Any) {
        sources += if (source is File) source else File(source.toString())
    }

    fun into(target: Any) {
        destination = if (target is File) target else File(target.toString())
    }
}
