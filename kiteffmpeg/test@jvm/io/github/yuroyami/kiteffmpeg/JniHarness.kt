package io.github.yuroyami.kiteffmpeg

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The two broken JNI libraries of the boundary tests. The kiteffmpeg-native plugin links them on an
 * arm64 Mac and writes their paths to the test resource `kiteffmpeg-jni-harness.properties`.
 */
internal object JniHarness {
    private val properties = Properties().apply {
        val stream = checkNotNull(JniHarness::class.java.getResourceAsStream("/kiteffmpeg-jni-harness.properties")) {
            "The build wrote no kiteffmpeg-jni-harness.properties test resource."
        }
        stream.use { load(it) }
    }

    /** Why this host has no harness libraries, or null when it has them. */
    val unavailable: String? = properties.getProperty("unavailable")

    /** The absolute path of the library that [name] names. A system property of that name wins. */
    fun library(name: String): String {
        val value = requireNotNull(System.getProperty(name) ?: properties.getProperty(name)) { "No $name for the JNI harness." }
        val path = Path.of(value).toAbsolutePath()
        require(Files.isRegularFile(path)) { "$name is not a file: $path" }
        return path.toString()
    }

    /** The classpath of this test JVM, for the probe JVMs the tests start. */
    val classpath: String
        get() = (System.getProperty("kiteffmpeg.jni.probe.classpath") ?: System.getProperty("java.class.path"))
            .also { require(it.isNotBlank()) { "The test classpath is blank." } }
}
