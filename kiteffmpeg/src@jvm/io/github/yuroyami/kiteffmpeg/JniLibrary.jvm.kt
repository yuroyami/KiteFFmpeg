package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.nio.file.Files

/**
 * Finds and loads `libkitecodec_jni` for a desktop JVM.
 *
 * Three sources, tried in this order:
 * 1. `-Dkiteffmpeg.jni.path=/abs/path` wins outright. The JNI boundary tests use it to load a
 *    deliberately broken library, so it must beat everything else.
 * 2. `System.loadLibrary`, which reads `java.library.path`. This is how a packager (jpackage, a
 *    distro package, a Gradle run task) supplies its own copy without unpacking anything.
 * 3. The copy bundled in this jar under `native/<os>-<arch>/`, extracted into a temporary folder
 *    named after the bundle's hash, which every later launch reuses ([JniExtraction]).
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
        val platformDirectory = platformDirectory ?: throw UnsatisfiedLinkError(
            "kitecodec_jni is not on java.library.path, and this build of kiteffmpeg carries no " +
                "native library for os.name=${System.getProperty("os.name")}, " +
                "os.arch=${System.getProperty("os.arch")}; supply one with -Dkiteffmpeg.jni.path " +
                "or -Djava.library.path.",
        )
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

        val bundle = manifest.map { name ->
            val stream = JniLibrary::class.java.getResourceAsStream("$directoryResource/$name")
                ?: throw UnsatisfiedLinkError(
                    "the bundle manifest names $name but $directoryResource/$name is not in the jar.",
                )
            JniExtraction.BundleFile(name, stream.use { it.readBytes() })
        }
        // One folder per bundle, reused by every later launch, so Windows, which cannot delete
        // a loaded DLL, no longer keeps a copy per launch (#208). A folder this process cannot
        // own falls back to a private one for this launch only.
        val shared = runCatching {
            JniExtraction.extract(File(System.getProperty("java.io.tmpdir")), bundle)
        }.getOrNull()
        val directory = shared ?: Files.createTempDirectory(JniExtraction.LEGACY_PREFIX).toFile().apply {
            deleteOnExit()
            bundle.forEach { file ->
                File(this, file.name).apply { writeBytes(file.bytes); deleteOnExit() }
            }
        }
        val library: File? = manifest.lastOrNull { it == fileName }?.let { File(directory, it) }
        val loadable = library ?: throw UnsatisfiedLinkError(
            "the bundle at $directoryResource carries no $fileName.",
        )
        System.load(loadable.absolutePath)
    }

    /** This JVM's bundle directory, or null when it runs on a processor or system no bundle names. */
    private val platformDirectory: String? by lazy {
        platformDirectoryFor(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
    }

    private val fileName: String by lazy {
        val directory = platformDirectory.orEmpty()
        when {
            directory.startsWith("macos") -> "lib$LIBRARY_NAME.dylib"
            directory.startsWith("windows") -> "$LIBRARY_NAME.dll"
            else -> "lib$LIBRARY_NAME.so"
        }
    }

    /**
     * `macos-arm64`, `linux-x64`, `windows-x64`: the same names the vendored FFmpeg trees use, or
     * null for a processor or system that is not one of them. Only known names map, so a JVM on
     * 32-bit ARM, RISC-V or 32-bit x86 gets the clear refusal instead of the x64 library failing
     * inside `System.load` (#266).
     */
    internal fun platformDirectoryFor(osName: String, osArch: String): String? {
        val name = osName.lowercase()
        val os = when {
            "mac" in name || "darwin" in name -> "macos"
            "win" in name -> "windows"
            "linux" in name -> "linux"
            else -> return null
        }
        val cpu = when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return "$os-$cpu"
    }

    private const val LIBRARY_NAME = "kitecodec_jni"
    private const val RESOURCE_ROOT = "kiteffmpeg-native"
    private const val MANIFEST_NAME = "manifest.txt"
}
