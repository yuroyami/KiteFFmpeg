package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkKiteFFmpegJniTaskTest {

    @Test
    fun everyAndroidAbiRecipePinsItsDedicatedHelperProviderAndExactLinkFlags() {
        val arm64 = LinkKiteFFmpegJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteFFmpegJniAndroidArm64",
            helperTaskName = "compileKiteFFmpegCForJniAndroidArm64",
            ffmpegDirName = "android-arm64",
            konanTargetName = "android_arm64",
            ndkTarget = "aarch64-linux-android24",
            abiDirectory = "arm64-v8a",
            outputRelativePath = "kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so",
            sixteenKibPages = true,
        )
        // The streaming sticks and budget television boxes are 32-bit ARM only.
        val arm32 = LinkKiteFFmpegJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteFFmpegJniAndroidArm32",
            helperTaskName = "compileKiteFFmpegCForJniAndroidArm32",
            ffmpegDirName = "android-arm32",
            konanTargetName = "android_arm32",
            ndkTarget = "armv7a-linux-androideabi24",
            abiDirectory = "armeabi-v7a",
            outputRelativePath = "kitecodec-jni/android-arm32/armeabi-v7a/libkitecodec_jni.so",
            sixteenKibPages = false,
        )
        val x64 = LinkKiteFFmpegJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteFFmpegJniAndroidX64",
            helperTaskName = "compileKiteFFmpegCForJniAndroidX64",
            ffmpegDirName = "android-x64",
            konanTargetName = "android_x64",
            ndkTarget = "x86_64-linux-android24",
            abiDirectory = "x86_64",
            outputRelativePath = "kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so",
            sixteenKibPages = true,
        )

        assertEquals(listOf(arm64, arm32, x64), LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES)
        assertEquals(
            expectedAndroidLinkFlags("aarch64-linux-android24", sixteenKibPages = true),
            LinkKiteFFmpegJniTask.androidLinkFlags(arm64),
        )
        // 16 KiB pages are Android's rule for 64-bit libraries only.
        assertEquals(
            expectedAndroidLinkFlags("armv7a-linux-androideabi24", sixteenKibPages = false),
            LinkKiteFFmpegJniTask.androidLinkFlags(arm32),
        )
        assertEquals(
            expectedAndroidLinkFlags("x86_64-linux-android24", sixteenKibPages = true),
            LinkKiteFFmpegJniTask.androidLinkFlags(x64),
        )
    }

    /**
     * An NDK carries one prebuilt toolchain, named after the host it was made for, so the Android
     * link names the build host's own. A path fixed to the Mac failed every link on Linux (#176).
     */
    @Test
    fun theAndroidLinkTakesTheNdkClangOfTheBuildHost() {
        val ndk = "/sdk/ndk/29.0.14206865"
        assertEquals(
            "$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang",
            LinkKiteFFmpegJniTask.ndkClang(ndk, osName = "Mac OS X"),
        )
        assertEquals(
            "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
            LinkKiteFFmpegJniTask.ndkClang(ndk, osName = "Linux"),
        )
        assertEquals(
            "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe",
            LinkKiteFFmpegJniTask.ndkClang(ndk, osName = "Windows 11"),
        )
        val source = repoRoot().resolve("build-plugins/native-build/src/ToolchainLocations.kt").readText()
        assertTrue("prebuilt/darwin-x86_64" !in source, "the NDK lookup names the Mac toolchain itself")
        listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME").forEach { name ->
            assertTrue("\"$name\"" in source, "the Android link does not read $name")
        }
    }

    @Test
    fun desktopRecipesCoverEveryJvmPlatformTheLoaderNamesBesideMacos() {
        val recipes = LinkKiteFFmpegJniTask.DESKTOP_JNI_RECIPES
        assertEquals(listOf("linux-arm64", "linux-x64", "windows-x64"), recipes.map { it.platformDirectory })
        assertEquals(listOf("linux-arm64", "linux-x64", "mingw-x64"), recipes.map { it.ffmpegDirName })
        assertEquals(listOf("linux_arm64", "linux_x64", "mingw_x64"), recipes.map { it.konanTargetName })
        assertEquals(
            listOf("libkitecodec_jni.so", "libkitecodec_jni.so", "kitecodec_jni.dll"),
            recipes.map { it.libraryFileName },
        )
        assertEquals(listOf(false, false, true), recipes.map { it.isWindows })
    }

    @Test
    fun desktopLinkFlagsNameEveryLibraryTheArchivesNeedAndLinkWindowsStatically() {
        val (linux, _, windows) = LinkKiteFFmpegJniTask.DESKTOP_JNI_RECIPES
        val toolchain = listOf("-target", "fake-triple")
        val ffmpeg = listOf("-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample")
        assertEquals(
            toolchain + ffmpeg + "-ldav1d" + listOf("-lz", "-lm", "-ldl", "-lpthread", "-Wl,--no-undefined"),
            LinkKiteFFmpegJniTask.desktopLinkFlags(linux, toolchain, dav1d = true),
        )
        // Every library after -Bstatic comes from its static archive. The MinGW sysroot also
        // carries DLL import libraries for zlib, iconv and winpthreads, and a DLL importing them
        // does not load on a machine without MSYS2.
        assertEquals(
            toolchain + "-Wl,-Bstatic" + ffmpeg + "-ldav1d" + listOf(
                "-liconv", "-lz", "-lm", "-lpthread",
                "-lole32", "-luser32", "-lws2_32", "-lbcrypt",
                "-static-libgcc",
            ),
            LinkKiteFFmpegJniTask.desktopLinkFlags(windows, toolchain, dav1d = true),
        )
        assertTrue("-ldav1d" !in LinkKiteFFmpegJniTask.desktopLinkFlags(linux, toolchain, dav1d = false))
    }

    @Test
    fun theWrittenPlatformHeaderUsesEachPlatformsOwnTypesAndLeavesExportsToTheLink() {
        val (linux, _, windows) = LinkKiteFFmpegJniTask.DESKTOP_JNI_RECIPES
        val unix = LinkKiteFFmpegJniTask.jniPlatformHeader(linux)
        assertTrue("#define JNIEXPORT __attribute__((visibility(\"default\")))" in unix, unix)
        assertTrue("#define JNICALL\n" in unix, unix)
        assertTrue("typedef int jint;" in unix, unix)
        assertTrue("typedef long jlong;" in unix, unix)
        assertTrue("typedef signed char jbyte;" in unix, unix)

        val win = LinkKiteFFmpegJniTask.jniPlatformHeader(windows)
        // Empty, so dllexport cannot export every adapter function past the module-definition file.
        assertTrue("#define JNIEXPORT\n" in win, win)
        assertTrue("#define JNICALL __stdcall" in win, win)
        assertTrue("typedef long jint;" in win, win)
        assertTrue("typedef long long jlong;" in win, win)
        assertTrue("typedef signed char jbyte;" in win, win)
        listOf(unix, win).forEach { header ->
            assertTrue(header.startsWith("/*") && "#ifndef _JAVASOFT_JNI_MD_H_" in header && header.endsWith("#endif\n"))
        }
    }

    @Test
    fun theWindowsModuleDefinitionExportsJniOnLoadAlone() {
        val repoRoot = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root")))
        val definition = repoRoot.resolve("native/kitecodec-jni/exports.def")
        val entries = definition.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith(";") }
        assertEquals(listOf("EXPORTS", "JNI_OnLoad"), entries)
        assertEquals(
            listOf(definition.absolutePath),
            LinkKiteFFmpegJniTask.exportControlArguments(
                LinkKiteFFmpegJniTask.ExportControlKind.PE_MODULE_DEFINITION,
                definition,
            ),
        )
    }

    @Test
    fun theJarStagesEveryDesktopRecipeAndAReleaseCheckDemandsThem() {
        val pluginYaml = repoRoot().resolve("build-plugins/kiteffmpeg-native/plugin.yaml").readText()
        val actions = repoRoot().resolve("build-plugins/kiteffmpeg-native/src/nativeTasks.kt").readText()
        (listOf("macos-arm64") + LinkKiteFFmpegJniTask.DESKTOP_JNI_RECIPES.map { it.platformDirectory }).forEach { platform ->
            assertSourceContains(pluginYaml, "platformDirectory: $platform")
        }
        listOf("MacosArm64", "LinuxX64", "LinuxArm64", "WindowsX64").forEach { suffix ->
            assertSourceContains(pluginYaml, "- directory: ${'$'}{tasks.jni$suffix.action.resourcesDir}\n      fragment: \"@jvm\"")
            assertSourceContains(pluginYaml, "- ${'$'}{tasks.jni$suffix.action.resourcesDir}")
        }
        assertSourceContains(pluginYaml, "- name: checkDesktopJni\n    performedBy: desktopJni")
        assertSourceContains(actions, "jniPlatformHeader.set(LinkKiteFFmpegJniTask.jniPlatformHeader(desktop))")
        assertSourceContains(actions, "exportControlFile.set(jni.resolve(\"exports.def\"))")
        assertTrue("ExtractJdkHeadersTask" !in actions, "the desktop links must not need a container for JDK headers")
    }

    @Test
    fun elfAndMachOExportControlsAreContentTrackedAndConsumedByTheLinkRecipe() {
        val root = Files.createTempDirectory("kitecodec-jni-link-input-test").toFile()
        try {
            val elf = root.resolve("exports.map").apply { writeText("{ global: JNI_OnLoad; local: *; };\n") }
            val macho = root.resolve("exports.macos").apply { writeText("_JNI_OnLoad\n") }
            val elfTask = newLinkTask("linkElf", root, elf, LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
            val machoTask = newLinkTask("linkMacho", root, macho, LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)

            // The export files sit in native/kitecodec-jni, which every link action declares as an @Input.
            listOf("kiteffmpeg-native/src/nativeTasks.kt", "android-jni/src/androidJniTasks.kt").forEach { path ->
                val actions = repoRoot().resolve("build-plugins/$path").readText()
                assertSourceContains(actions, "@Input jniSources: Path")
            }
            assertEquals(
                listOf("-Wl,--version-script=${elf.absolutePath}"),
                LinkKiteFFmpegJniTask.exportControlArguments(elfTask.exportControlKind.get(), elf),
            )
            assertEquals(
                listOf("-Wl,-exported_symbols_list,${macho.absolutePath}"),
                LinkKiteFFmpegJniTask.exportControlArguments(machoTask.exportControlKind.get(), macho),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theOutputLibrarySitsInsideItsDirectoryAndNeverEscapesIt() {
        val root = Files.createTempDirectory("kitecodec-jni-output-model-test").toFile()
        try {
            val exportControl = root.resolve("exports.map").apply {
                writeText("{ global: JNI_OnLoad; local: *; };\n")
            }
            val task = newLinkTask(
                "linkAndroidArm64",
                root,
                exportControl,
                LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT,
            )
            val outputRoot = task.outputDirectory.get().asFile
            val outputLibrary = task.outputLibrary.get().asFile
            LinkKiteFFmpegJniTask.assertOutputLibraryInsideDirectory(outputRoot, outputLibrary)

            val leafFailure = assertFailsWith<BuildFailure> {
                LinkKiteFFmpegJniTask.assertOutputLibraryInsideDirectory(outputRoot, outputRoot)
            }
            assertTrue(leafFailure.message.orEmpty().contains("must be inside output directory"))
            assertFailsWith<BuildFailure> {
                LinkKiteFFmpegJniTask.assertOutputLibraryInsideDirectory(
                    outputRoot,
                    root.resolve("outside/libkitecodec_jni.so"),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun majorMismatchHarnessUsesTheHermeticFakeHeaderWithoutRenamingProductionSymbols() {
        val repoRoot = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root")))
        val fakeHeader = repoRoot.resolve(
            "native/kitecodec-c/tests/fake_headers/major_mismatch/kitecodec_ffmpeg_versions.h",
        ).readText()
        val renameStanza = "#define KC_CASE kc_major_mismatch\n#include \"../kc_rename.h\"\n\n"

        val generated = PrepareKiteFFmpegJniHarnessTask.replaceExactlyOnce(
            fakeHeader,
            renameStanza,
            "",
            "kitecodec_ffmpeg_versions.h",
        )

        assertTrue("#include_next \"kitecodec_ffmpeg_versions.h\"" in generated)
        assertTrue("KC_FAKE_REAL_AVUTIL_MAJOR - 1" in generated)
        assertTrue("kc_rename.h" !in generated)
        assertTrue("KC_CASE kc_major_mismatch" !in generated)
    }

    @Test
    fun harnessMutationRefusesMissingOrRepeatedSourceText() {
        assertFailsWith<BuildFailure> {
            PrepareKiteFFmpegJniHarnessTask.replaceExactlyOnce("alpha", "missing", "beta", "fixture")
        }
        assertFailsWith<BuildFailure> {
            PrepareKiteFFmpegJniHarnessTask.replaceExactlyOnce("alpha alpha", "alpha", "beta", "fixture")
        }
    }

    @Test
    fun theHarnessResourceNamesBothBrokenLibrariesAndTheTestsReadIt() {
        val actions = repoRoot().resolve("build-plugins/kiteffmpeg-native/src/nativeTasks.kt").readText()
        val harness = repoRoot().resolve("kiteffmpeg/test@jvm/io/github/yuroyami/kiteffmpeg/JniHarness.kt").readText()
        val pluginYaml = repoRoot().resolve("build-plugins/kiteffmpeg-native/plugin.yaml").readText()
        assertSourceContains(actions, "internal const val HARNESS_PROPERTIES = \"kiteffmpeg-jni-harness.properties\"")
        assertSourceContains(actions, "\"kiteffmpeg.jni.mismatch.path=${'$'}{mismatchLibrary.absolutePath}\\n\"")
        assertSourceContains(actions, "\"kiteffmpeg.jni.corrupt.path=${'$'}{corruptLibrary.absolutePath}\\n\"")
        assertSourceContains(harness, "getResourceAsStream(\"/kiteffmpeg-jni-harness.properties\")")
        assertSourceContains(harness, "System.getProperty(\"java.class.path\")")
        // The harness resource is a test resource of the JVM platform only.
        assertSourceContains(pluginYaml, "- directory: ${'$'}{tasks.jniHarness.action.resourcesDir}\n      fragment:\n        modifier: \"@jvm\"\n        isTest: true")
    }

    @Test
    fun theLinksCompileTheirOwnHelpersAndUseThePlatformExportControls() {
        val actions = repoRoot().resolve("build-plugins/kiteffmpeg-native/src/nativeTasks.kt").readText()
        val android = repoRoot().resolve("build-plugins/android-jni/src/androidJniTasks.kt").readText()
        val androidYaml = repoRoot().resolve("build-plugins/android-jni/plugin.yaml").readText()

        // Android: one task per ABI, each with its own helper archive, the version script and lib/<abi>/.
        LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES.forEach { recipe ->
            assertSourceContains(androidYaml, "abi: ${recipe.abiDirectory}")
            assertSourceContains(androidYaml, "ffmpegTree: ${'$'}{project.rootDir}/native-libs/lgpl/${recipe.ffmpegDirName}")
        }
        assertSourceContains(android, "val helperDir = work.resolve(\"c/${'$'}{recipe.konanTargetName}\")")
        assertSourceContains(android, "compileHelper(recipe.konanTargetName, cSources.toFile(), cInclude.toFile(), paths, helperDir)")
        assertSourceContains(android, "exportControlFile.set(jni.resolve(\"exports.map\"))")
        assertSourceContains(android, "exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)")
        assertSourceContains(android, "val output = resources.resolve(\"lib/${'$'}abi/libkitecodec_jni.so\")")
        assertSourceContains(android, "checkJniRegistration(jniSources.toFile())")

        // macOS: Mach-O export list. The harness: the hermetic fake header and the corrupt descriptor.
        assertSourceContains(actions, "exportControlFile.set(jni.resolve(\"exports.macos\"))")
        assertSourceContains(actions, "exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)")
        assertSourceContains(actions, "fakeHeaders.resolve(\"major_mismatch/kitecodec_ffmpeg_versions.h\")")
        assertSourceContains(actions, "ffmpegIncludeDirs.set(listOf(opaqueInclude.absolutePath, ffmpeg.includeDir))")
        assertSourceContains(actions, "compileKiteFFmpegCForJniMacosArm64MajorMismatch")
        assertSourceContains(actions, "linkKiteFFmpegJniMacosArm64CorruptDescriptor")
        assertSourceContains(actions, "\"nativeAbiVersion\",       \"()J\",                    kj_abi_version")
        assertSourceContains(actions, "checkJniRegistration(jniSources.toFile())")
    }

    @Test
    fun registrationManifestHasExactlyFourFieldsPerRecord() {
        val repoRoot = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root")))
        val manifest = repoRoot.resolve("native/kitecodec-jni/methods.def")
        val records = manifest.readLines().map(String::trim).filter { it.startsWith("KJ_METHOD(") }
        assertTrue(records.isNotEmpty(), "methods.def contains no KJ_METHOD records")

        records.forEach { record -> assertFourFieldRecord(record) }

        val corrupted = records.first().dropLast(1) + ", packet)"
        assertFailsWith<AssertionError> { assertFourFieldRecord(corrupted) }
    }

    private fun expectedAndroidLinkFlags(ndkTarget: String, sixteenKibPages: Boolean): List<String> = listOf(
        "--target=$ndkTarget",
        "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
        "-lmediandk", "-landroid", "-llog", "-lz", "-ldl", "-lm",
        "-Wl,-z,defs", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
        "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
    ) + if (sixteenKibPages) {
        listOf("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
    } else {
        emptyList()
    }

    private fun newLinkTask(
        name: String,
        root: File,
        exportControl: File,
        kind: LinkKiteFFmpegJniTask.ExportControlKind,
    ): LinkKiteFFmpegJniTask {
        val jniDir = root.resolve("$name-jni").apply { mkdirs() }
        val includeDir = root.resolve("$name-include").apply { mkdirs() }
        val ffmpegLibDir = root.resolve("$name-ffmpeg-lib").apply { mkdirs() }
        val source = jniDir.resolve("kj_probe.c").apply { writeText("int kj_probe(void) { return 0; }\n") }
        val archive = root.resolve("$name-libkitecodec.a").apply { writeText("fixture") }
        return LinkKiteFFmpegJniTask(ExecOperations()).apply {
            this.name = name
            jniSources.from(source)
            opaqueIncludeDir.set(includeDir)
            helperArchive.from(archive)
            this.ffmpegLibDir.set(ffmpegLibDir)
            compiler.set("clang")
            extraIncludeDirs.set(emptyList())
            linkFlags.set(emptyList())
            libSearchDirs.set(emptyList())
            exportControlFile.set(exportControl)
            exportControlKind.set(kind)
            outputDirectory.set(root.resolve("$name-output"))
            outputLibrary.set(root.resolve("$name-output/libkitecodec_jni.so"))
        }
    }

    private fun repoRoot(): File = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root"))).canonicalFile

    private fun assertFourFieldRecord(record: String) {
        assertTrue(record.endsWith(')'), "malformed manifest record: $record")
        val body = record.removePrefix("KJ_METHOD(").dropLast(1)
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var escaped = false
        body.forEach { character ->
            when {
                escaped -> {
                    field.append(character)
                    escaped = false
                }
                character == '\\' && quoted -> {
                    field.append(character)
                    escaped = true
                }
                character == '"' -> {
                    field.append(character)
                    quoted = !quoted
                }
                character == ',' && !quoted -> {
                    fields += field.toString().trim()
                    field.clear()
                }
                else -> field.append(character)
            }
        }
        fields += field.toString().trim()

        assertEquals(4, fields.size, "manifest records are class, name, descriptor and C function: $record")
        assertTrue(fields.take(3).all { it.startsWith('"') && it.endsWith('"') }, record)
        assertTrue(fields[3].matches(Regex("kj_[a-z0-9_]+")), record)
    }

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = sourceMarker(source, start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue(endIndex >= 0, "the build source is missing section terminator: $end")
        return source.substring(startIndex, endIndex)
    }

    private fun sourceMarker(source: String, marker: String): Int {
        val index = source.indexOf(marker)
        assertTrue(index >= 0, "the build source is missing wiring marker: $marker")
        return index
    }

    private fun assertSourceContains(section: String, expected: String) {
        assertTrue(expected in section, "the build wiring is missing: $expected")
    }
}
