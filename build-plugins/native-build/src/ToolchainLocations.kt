package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the Kotlin Toolchain keeps its downloads on this host. */
fun kotlinToolchainCache(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Mac") -> home.resolve("Library/Caches/JetBrains/Kotlin")
        os.startsWith("Windows") -> File(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").path, "JetBrains/Kotlin")
        else -> home.resolve(".cache/JetBrains/Kotlin")
    }
}

/** `~/.konan`, or the folder that `KONAN_DATA_DIR` names. Kotlin/Native keeps its LLVM and sysroots there. */
fun konanDataDir(): File =
    System.getenv("KONAN_DATA_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")

/**
 * The `konanc` of Kotlin/Native [kotlinVersion]: first the one the Kotlin Toolchain downloaded, then
 * `~/.konan`. A copy in `~/.konan` counts only when it carries [COMPLETE_MARK], which only an
 * unpack that finished writes, so a folder another task is still filling is never taken (#236).
 */
fun findKonanc(kotlinVersion: String): File? {
    val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "konanc.bat" else "konanc"
    val toolchainCopies = kotlinToolchainCache().resolve("extract.cache").listFiles().orEmpty()
        .filter { it.isDirectory && "kotlin-native-prebuilt-$kotlinVersion-" in it.name }
        .map { it.resolve("bin/$executable") }
    val konanCopies = konanDataDir().listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("kotlin-native-prebuilt-") && it.name.endsWith("-$kotlinVersion") }
        .filter { it.resolve(COMPLETE_MARK).isFile }
        .map { it.resolve("bin/$executable") }
    return (toolchainCopies + konanCopies).firstOrNull { it.canExecute() }
}

/** Written into an unpacked Kotlin/Native folder once every file of it is in place. */
private const val COMPLETE_MARK = ".kiteffmpeg-complete"

/**
 * The `konanc` of [kotlinVersion]. On a fresh machine a plugin task can run before the Kotlin Toolchain
 * has downloaded Kotlin/Native, so this then downloads it from Maven Central into `~/.konan`. Several
 * tasks start at once on a cold machine, so the download runs under [konanSetup] and checks again
 * once it holds it.
 */
fun requireKonanc(kotlinVersion: String): File =
    findKonanc(kotlinVersion) ?: konanSetup { findKonanc(kotlinVersion) ?: downloadKonanc(kotlinVersion) }

/** Downloads and unpacks Kotlin/Native [kotlinVersion]. The caller holds [konanSetup]. */
private fun downloadKonanc(kotlinVersion: String): File {
    val os = System.getProperty("os.name").orEmpty()
    val arm = System.getProperty("os.arch").orEmpty() in setOf("aarch64", "arm64")
    val host = when {
        os.startsWith("Mac") -> if (arm) "macos-aarch64" else "macos-x86_64"
        os.startsWith("Windows") -> "windows-x86_64"
        else -> if (arm) "linux-aarch64" else "linux-x86_64"
    }
    val extension = if (host.startsWith("windows")) "zip" else "tar.gz"
    val url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-native-prebuilt/$kotlinVersion/" +
        "kotlin-native-prebuilt-$kotlinVersion-$host.$extension"
    val target = konanDataDir().apply { mkdirs() }
    BuildLog.lifecycle("[KiteFFmpeg] no Kotlin/Native $kotlinVersion found. Downloading $url")
    val archive = Files.createTempFile("kotlin-native-prebuilt", ".$extension").toFile()
    // Unpacked beside its final place, so the move into place is a rename on one file system.
    val staging = Files.createTempDirectory(target.toPath(), ".kotlin-native-staging-").toFile()
    try {
        URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        // bsdtar on macOS and Windows reads the zip too.
        ExecOperations().exec { commandLine("tar", "-xf", archive.absolutePath, "-C", staging.absolutePath) }
        val unpacked = staging.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("kotlin-native-prebuilt-") }
        val root = unpacked.singleOrNull()
            ?: throw BuildFailure("The Kotlin/Native $kotlinVersion download from $url has no single kotlin-native-prebuilt folder.")
        root.resolve(COMPLETE_MARK).writeText("$url\n")
        val destination = target.resolve(root.name)
        // A folder without the mark is an unpack that never finished, from before this lock existed.
        if (destination.exists()) destination.deleteRecursively()
        Files.move(root.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        archive.delete()
        staging.deleteRecursively()
    }
    return findKonanc(kotlinVersion)
        ?: throw BuildFailure("The Kotlin/Native $kotlinVersion download from $url has no konanc under $target.")
}

/**
 * Runs [block] while no other download of Kotlin/Native or sysroot warmup runs, in this process or
 * another, since both write into the one konan folder. On a cold CI machine three tasks downloaded
 * and unpacked the same distribution into it at once, and one then ran a compiler whose jars were
 * not all written (#236). Not reentrant: [block] calls only the unlocked helpers.
 */
private fun <T> konanSetup(block: () -> T): T = synchronized(konanSetupMonitor()) {
    val dataDir = konanDataDir().also { it.mkdirs() }
    RandomAccessFile(dataDir.resolve(".kiteffmpeg-setup.lock"), "rw").use { file ->
        lockFully(file.channel).use { block() }
    }
}

/**
 * One monitor for the whole JVM. Each build plugin loads these classes in a class loader of its own,
 * so an object here would be one per plugin, and two plugins locking at once would reach the file
 * lock together, which the JVM refuses with OverlappingFileLockException. The system properties are
 * the one map every class loader of the JVM shares.
 */
private fun konanSetupMonitor(): Any =
    System.getProperties().computeIfAbsent("io.github.yuroyami.kiteffmpeg.build.konanSetup") { Any() }

/** The file lock of [channel], waiting while another class loader of this JVM still holds it. */
private fun lockFully(channel: FileChannel): FileLock {
    while (true) {
        try {
            return channel.lock()
        } catch (held: OverlappingFileLockException) {
            Thread.sleep(100)
        }
    }
}

/**
 * Makes Kotlin/Native download the LLVM package and the sysroot of [konanTarget] when one is missing.
 *
 * The C helper layer and the JNI links compile with konan's clang against konan's sysroots, but
 * Kotlin/Native downloads both only when it first links for that target. Apple targets take their SDK
 * from Xcode, so they need only the LLVM package. On a fresh machine the C compile runs first, so
 * this compiles a one-line program for the target, which needs the linker and so downloads what the
 * target needs. A library does not: Kotlin/Native 2.4.21 builds a klib without the sysroot (#235).
 * The warmup runs under [konanSetup], as konan unpacks into one shared folder.
 */
fun ensureKonanSysroot(konanTarget: String, kotlinVersion: String) {
    val sysroot = CompileKiteFFmpegCTask.specFor(konanTarget).konanSysroot
    fun present(): Boolean {
        val dependencies = konanDataDir().resolve("dependencies")
        val hasClang = dependencies.listFiles().orEmpty().any {
            it.name.startsWith("llvm-") && CompileKiteFFmpegCTask.resolveTool(it.resolve("bin"), "clang") != null
        }
        return hasClang && (sysroot == null || dependencies.resolve(sysroot).isDirectory)
    }
    // Check only under the lock: while another task extracts the sysroot, its folder exists half full.
    konanSetup {
        if (present()) return@konanSetup
        val konanc = findKonanc(kotlinVersion) ?: downloadKonanc(kotlinVersion)
        BuildLog.lifecycle("[KiteFFmpeg] downloading the Kotlin/Native dependencies for $konanTarget")
        val work = Files.createTempDirectory("kiteffmpeg-konan-sysroot").toFile()
        try {
            val source = work.resolve("warmup.kt").apply { writeText("fun main() {}\n") }
            ExecOperations().exec {
                commandLine(
                    konanc.absolutePath, "-target", konanTarget, "-produce", "program",
                    "-o", work.resolve("warmup").absolutePath, source.absolutePath,
                )
                environment("JAVA_HOME", System.getProperty("java.home"))
            }
        } finally {
            work.deleteRecursively()
        }
        if (!present()) {
            val what = if (sysroot == null) "its LLVM package" else "its LLVM package or $sysroot"
            throw BuildFailure("Kotlin/Native did not download $what for $konanTarget into ${konanDataDir()}.")
        }
    }
}

/**
 * The `include` folder of a JDK, which holds `jni.h`. The Kotlin Toolchain itself runs on a JRE without
 * headers, so this looks at JAVA_HOME, at the JDKs the toolchain downloaded, and at `/usr/libexec/java_home`.
 */
fun jdkIncludeDir(): File {
    val candidates = buildList {
        System.getenv("JAVA_HOME")?.let { add(File(it)) }
        add(File(System.getProperty("java.home")))
        kotlinToolchainCache().resolve("extract.cache").listFiles().orEmpty()
            .filter { it.isDirectory && ("jdk" in it.name.lowercase()) }
            .sortedByDescending { it.name }
            .forEach { root ->
                root.walkTopDown().maxDepth(4).filter { it.name == "include" && it.resolve("jni.h").isFile }
                    .forEach { add(it.parentFile) }
            }
        if (File("/usr/libexec/java_home").canExecute()) {
            runCatching { captureOutput(listOf("/usr/libexec/java_home")).trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }
        }
    }
    return candidates.map { it.resolve("include") }.firstOrNull { it.resolve("jni.h").isFile }
        ?: throw BuildFailure("No JDK with include/jni.h found. Set JAVA_HOME to a JDK.")
}

/** The newest Android NDK: ANDROID_NDK_HOME, ANDROID_NDK_ROOT, ANDROID_NDK_LATEST_HOME, then `<sdk>/ndk/<newest>`. */
fun androidNdkHome(): File {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME").forEach { name ->
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    }
    val sdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        ?.let(::File)
        ?: File(System.getProperty("user.home"), "Library/Android/sdk")
    return newestNdk(sdk.resolve("ndk")) ?: throw BuildFailure(
        "No Android NDK found. Set ANDROID_NDK_HOME, or install an NDK under ${sdk.resolve("ndk")}.",
    )
}

/** Fails when [path] is missing, with the command that builds it. */
fun requireTree(path: File, what: String, command: String) {
    if (!path.exists()) throw BuildFailure("No $what at $path. Run `./kotlin do $command` first.")
}
