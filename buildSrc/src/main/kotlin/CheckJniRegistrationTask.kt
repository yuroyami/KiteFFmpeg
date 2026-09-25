package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a C function that `methods.def` registers does not take and return what
 * its JNI descriptor says.
 *
 * `RegisterNatives` stores each function as an untyped pointer, so the C compiler never compares
 * a function with its descriptor. A wrong argument count or type then builds cleanly and corrupts
 * the call at run time. This reads every `KJ_METHOD` row and the `JNIEXPORT` definition of its
 * function, maps the descriptor to JNI C types, and compares the two.
 */
abstract class CheckJniRegistrationTask : DefaultTask() {

    /** `native/kitecodec-jni/methods.def`, the one registration manifest. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifest: RegularFileProperty

    /** The adapter's C files, which hold every registered function's definition. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val definitions = sources.files.filter { it.name.endsWith(".c") }.map { it.readText() }
        val rows = JniRegistration.rows(manifest.get().asFile.readText())
        if (rows.isEmpty()) throw GradleException("no KJ_METHOD rows parsed from ${manifest.get().asFile.path}")
        val problems = JniRegistration.mismatches(rows, definitions)
        if (problems.isEmpty()) {
            logger.lifecycle("[KiteFFmpeg jni] ${rows.size} registered functions match their descriptors")
            return
        }
        throw GradleException(buildString {
            appendLine("${problems.size} registered JNI function(s) do not match their descriptor in methods.def:")
            problems.forEach { appendLine("  $it") }
        })
    }
}

/** Parsing and comparison for [CheckJniRegistrationTask], kept apart so a test can drive it with text. */
object JniRegistration {

    /** One `KJ_METHOD` row: the Kotlin method name, its JNI descriptor and the C function registered for it. */
    data class Row(val name: String, val descriptor: String, val function: String)

    /** A C function's return type and parameter types, pointers written without spaces (`JNIEnv*`). */
    data class Signature(val returns: String, val parameters: List<String>)

    private val ROW = Regex("""^\s*KJ_METHOD\(\s*"[^"]*"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*(\w+)\s*\)""", RegexOption.MULTILINE)
    private val DEFINITION = Regex("""JNIEXPORT\s+(\w+)\s+JNICALL\s+(\w+)\s*\(([^)]*)\)\s*\{""")

    fun rows(manifest: String): List<Row> =
        ROW.findAll(manifest).map { Row(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()

    /** Every `JNIEXPORT` function defined in [source], by name. */
    fun definitions(source: String): Map<String, Signature> =
        DEFINITION.findAll(source).associate { match ->
            val parameters = match.groupValues[3].split(',').map(::parameterType).filter { it.isNotEmpty() && it != "void" }
            match.groupValues[2] to Signature(match.groupValues[1], parameters)
        }

    /** A parameter declaration without its name: `JNIEnv *env` is `JNIEnv*`, `jlong token` is `jlong`. */
    private fun parameterType(declaration: String): String {
        val words = declaration.replace("*", " * ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "const" }
        if (words.isEmpty()) return ""
        val typeWords = if (words.size > 1 && words.last() != "*") words.dropLast(1) else words
        return typeWords.joinToString("")
    }

    /**
     * The C signature a JNI [descriptor] requires: the environment, the receiver, then one JNI type
     * per parameter. The receiver is written `jclass`, which jni.h defines as `jobject`.
     */
    fun expected(descriptor: String): Signature {
        require(descriptor.startsWith("(") && ')' in descriptor) { "not a method descriptor: $descriptor" }
        val parameters = descriptor.substring(1, descriptor.indexOf(')'))
        val returns = descriptor.substring(descriptor.indexOf(')') + 1)
        val types = mutableListOf("JNIEnv*", "jclass")
        var i = 0
        while (i < parameters.length) {
            val end = typeEnd(parameters, i)
            types += cType(parameters.substring(i, end))
            i = end
        }
        return Signature(cType(returns), types)
    }

    /** The index just past the one field type that starts at [start] in [text]. */
    private fun typeEnd(text: String, start: Int): Int {
        var i = start
        while (text[i] == '[') i++
        return if (text[i] == 'L') text.indexOf(';', i) + 1 else i + 1
    }

    private fun cType(field: String): String = when {
        field == "V" -> "void"
        field == "Ljava/lang/String;" -> "jstring"
        field == "Ljava/lang/Class;" -> "jclass"
        field == "Ljava/lang/Throwable;" -> "jthrowable"
        field.startsWith("L") -> "jobject"
        field.startsWith("[") && (field.length == 2 && field[1] != 'L') -> primitive(field[1]) + "Array"
        field.startsWith("[") -> "jobjectArray"
        field.length == 1 -> primitive(field[0])
        else -> throw IllegalArgumentException("not a JNI field type: $field")
    }

    private fun primitive(code: Char): String = when (code) {
        'Z' -> "jboolean"
        'B' -> "jbyte"
        'C' -> "jchar"
        'S' -> "jshort"
        'I' -> "jint"
        'J' -> "jlong"
        'F' -> "jfloat"
        'D' -> "jdouble"
        else -> throw IllegalArgumentException("not a JNI primitive: $code")
    }

    /** One line per row whose function is missing or whose C signature differs from its descriptor. */
    fun mismatches(rows: List<Row>, sources: List<String>): List<String> {
        val defined = sources.fold(emptyMap<String, Signature>()) { all, source -> all + definitions(source) }
        return rows.mapNotNull { row ->
            val actual = defined[row.function] ?: return@mapNotNull "${row.function}: no JNIEXPORT definition found"
            val wanted = expected(row.descriptor)
            // jclass is jobject in jni.h, so the receiver may be written either way.
            val receiverAgrees = actual.parameters.getOrNull(1) in setOf("jclass", "jobject")
            val agrees = actual.returns == wanted.returns && actual.parameters.size == wanted.parameters.size &&
                actual.parameters[0] == wanted.parameters[0] && receiverAgrees &&
                actual.parameters.drop(2) == wanted.parameters.drop(2)
            if (agrees) null
            else "${row.function} (${row.name} ${row.descriptor}): C is ${actual.render()}, the descriptor needs ${wanted.render()}"
        }
    }

    private fun Signature.render(): String = "$returns(${parameters.joinToString(", ")})"
}
