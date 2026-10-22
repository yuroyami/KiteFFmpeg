package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.net.URI
import java.nio.file.Files

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

/** The `konanc` of Kotlin/Native [kotlinVersion]: first the one the Kotlin Toolchain downloaded, then `~/.konan`. */
fun findKonanc(kotlinVersion: String): File? {
    val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "konanc.bat" else "konanc"
    val toolchainCopies = kotlinToolchainCache().resolve("extract.cache").listFiles().orEmpty()
        .filter { it.isDirectory && "kotlin-native-prebuilt-$kotlinVersion-" in it.name }
        .map { it.resolve("bin/$executable") }
    val konanCopies = konanDataDir().listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("kotlin-native-prebuilt-") && it.name.endsWith("-$kotlinVersion") }
        .map { it.resolve("bin/$executable") }
    return (toolchainCopies + konanCopies).firstOrNull { it.canExecute() }
}

/**
 * The `konanc` of [kotlinVersion]. On a fresh machine a plugin task can run before the Kotlin Toolchain
 * has downloaded Kotlin/Native, so this then downloads it from Maven Central into `~/.konan`.
 */
fun requireKonanc(kotlinVersion: String): File {
    findKonanc(kotlinVersion)?.let { return it }
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
    try {
        URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        // bsdtar on macOS and Windows reads the zip too.
        ExecOperations().exec { commandLine("tar", "-xf", archive.absolutePath, "-C", target.absolutePath) }
    } finally {
        archive.delete()
    }
    return findKonanc(kotlinVersion)
        ?: throw BuildFailure("The Kotlin/Native $kotlinVersion download from $url has no konanc under $target.")
}

/**
 * Makes Kotlin/Native download the sysroot of [konanTarget] when it is missing.
 *
 * The C helper layer and the JNI links compile against konan's sysroots, but Kotlin/Native downloads a
 * sysroot only when it first compiles for that target. On a fresh machine the C compile runs first, so
 * this compiles an empty library for the target, which downloads what the target needs.
 */
fun ensureKonanSysroot(konanTarget: String, kotlinVersion: String) {
    val sysroot = CompileKiteFFmpegCTask.specFor(konanTarget).konanSysroot ?: return
    if (konanDataDir().resolve("dependencies").resolve(sysroot).isDirectory) return
    val konanc = requireKonanc(kotlinVersion)
    BuildLog.lifecycle("[KiteFFmpeg] downloading the Kotlin/Native dependencies for $konanTarget")
    val work = Files.createTempDirectory("kiteffmpeg-konan-sysroot").toFile()
    try {
        val source = work.resolve("warmup.kt").apply { writeText("package warmup\n") }
        ExecOperations().exec {
            commandLine(
                konanc.absolutePath, "-target", konanTarget, "-produce", "library",
                "-o", work.resolve("warmup").absolutePath, source.absolutePath,
            )
            environment("JAVA_HOME", System.getProperty("java.home"))
        }
    } finally {
        work.deleteRecursively()
    }
    if (!konanDataDir().resolve("dependencies").resolve(sysroot).isDirectory) {
        throw BuildFailure("Kotlin/Native did not download $sysroot for $konanTarget into ${konanDataDir()}.")
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
