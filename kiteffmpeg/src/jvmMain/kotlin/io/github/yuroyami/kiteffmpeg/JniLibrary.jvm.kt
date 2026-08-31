package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Finds and loads `libkitecodec_jni` for a desktop JVM.
 *
 * Three sources, tried in this order:
 * 1. `-Dkiteffmpeg.jni.path=/abs/path` wins outright. The JNI boundary tests use it to load a
 *    deliberately broken library, so it must beat everything else.
 * 2. `System.loadLibrary`, which reads `java.library.path`. This is how a packager (jpackage, a
 *    distro package, a Gradle run task) supplies its own copy without unpacking anything.
 * 3. The copy bundled in this jar under `native/<os>-<arch>/`, extracted once to a temp file.
 *    This is what makes `implementation("...:kiteffmpeg")` enough for a desktop app.
 */
internal actual object JniLibrary {
    actual val isAndroid: Boolean = false

    @Volatile private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val override = System.getProperty("kiteffmpeg.jni.path")
            if (!override.isNullOrBlank()) System.load(override) else loadBundledOrInstalled()
            loaded = true
        }
    }

    private fun loadBundledOrInstalled() {
        val installed = runCatching { System.loadLibrary(LIBRARY_NAME) }
        if (installed.isSuccess) return

        // manifest.txt lists every file in the bundle, the JNI library LAST. The others are
        // dependencies it loads through @loader_path, so they must all sit in one directory
        // before the load; extracting only the library would fail at the first missing symbol.
        val directoryResource = "/$RESOURCE_ROOT/$platformDirectory"
        val manifest = JniLibrary::class.java.getResourceAsStream("$directoryResource/$MANIFEST_NAME")
            ?.bufferedReader()?.use { it.readLines() }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: throw UnsatisfiedLinkError(
                "kitecodec_jni is neither on java.library.path nor bundled at $directoryResource. " +
                    "This build of kiteffmpeg carries no native library for $platformDirectory; " +
                    "supply one with -Dkiteffmpeg.jni.path or -Djava.library.path.",
            )

        val directory = Files.createTempDirectory("kitecodec-jni").toFile().apply { deleteOnExit() }
        var library: File? = null
        for (name in manifest) {
            val stream = JniLibrary::class.java.getResourceAsStream("$directoryResource/$name")
                ?: throw UnsatisfiedLinkError(
                    "the bundle manifest names $name but $directoryResource/$name is not in the jar.",
                )
            val target = File(directory, name)
            stream.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            target.deleteOnExit()
            if (name == fileName) library = target
        }
        val loadable = library ?: throw UnsatisfiedLinkError(
            "the bundle at $directoryResource carries no $fileName.",
        )
        System.load(loadable.absolutePath)
    }

    /** `macos-arm64`, `linux-x64`, `windows-x64`: the same names the vendored FFmpeg trees use. */
    private val platformDirectory: String by lazy {
        val name = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val os = when {
            "mac" in name || "darwin" in name -> "macos"
            "win" in name -> "windows"
            else -> "linux"
        }
        val cpu = if (arch in setOf("aarch64", "arm64")) "arm64" else "x64"
        "$os-$cpu"
    }

    private val fileName: String by lazy {
        when {
            platformDirectory.startsWith("macos") -> "lib$LIBRARY_NAME.dylib"
            platformDirectory.startsWith("windows") -> "$LIBRARY_NAME.dll"
            else -> "lib$LIBRARY_NAME.so"
        }
    }

    private const val LIBRARY_NAME = "kitecodec_jni"
    private const val RESOURCE_ROOT = "kiteffmpeg-native"
    private const val MANIFEST_NAME = "manifest.txt"
}
