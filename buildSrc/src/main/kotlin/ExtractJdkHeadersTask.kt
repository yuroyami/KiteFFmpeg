package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Copies a JDK's `include` tree out of a container, for cross-compiling JNI to another platform.
 *
 * A macOS JDK ships `include/darwin`, so it cannot supply the `linux/jni_md.h` a Linux JNI library
 * needs. The header is TAKEN FROM a JDK image at build time and never committed: OpenJDK's headers
 * carry their own licence, and vendoring one into this repository is a decision the owner has not
 * been asked for and does not need to be.
 */
abstract class ExtractJdkHeadersTask : DefaultTask() {

    /** A JDK image that has the headers, for example `eclipse-temurin:21-jdk`. */
    @get:Input
    abstract val image: Property<String>

    /** The container platform, for example `linux/arm64`. Decides which `jni_md.h` arrives. */
    @get:Input
    abstract val platform: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteffmpeg"
        description = "Extracts a JDK include tree from a container image for cross-compiled JNI."
    }

    @TaskAction
    fun extract() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        // Docker Desktop's credential helper blocks on the login keychain in a headless session and
        // hangs every pull, so an empty config is supplied; these are public images.
        val config = temporaryDir.resolve("docker-config").apply {
            mkdirs()
            resolve("config.json").writeText("{}")
        }
        val command = listOf(
            "docker", "run", "--rm", "--platform", platform.get(),
            "--entrypoint", "sh",
            "-v", "${out.absolutePath}:/out",
            image.get(),
            "-c", "cp -r \$JAVA_HOME/include/. /out/",
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { it.environment()["DOCKER_CONFIG"] = config.absolutePath }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException(
                "could not extract JDK headers from ${image.get()}: $output\n" +
                    "This needs a running Docker daemon. Build without " +
                    "-Pkiteffmpeg.jni.linux=true to skip the Linux JNI library entirely.",
            )
        }
        if (!out.resolve("jni.h").isFile) {
            throw GradleException("${image.get()} produced no jni.h in ${out.absolutePath}")
        }
        logger.lifecycle("[KiteFFmpeg] JDK headers extracted from ${image.get()} (${platform.get()})")
    }
}
