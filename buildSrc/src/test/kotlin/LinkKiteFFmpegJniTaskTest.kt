package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkKiteFFmpegJniTaskTest {

    @Test
    fun bothAndroidAbiRecipesPinTheirDedicatedHelperProvidersAndExactLinkFlags() {
        val arm64 = LinkKiteFFmpegJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteFFmpegJniAndroidArm64",
            helperTaskName = "compileKiteFFmpegCForJniAndroidArm64",
            ffmpegDirName = "android-arm64",
            konanTargetName = "android_arm64",
            ndkTarget = "aarch64-linux-android24",
            abiDirectory = "arm64-v8a",
            outputRelativePath = "kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so",
        )
        val x64 = LinkKiteFFmpegJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteFFmpegJniAndroidX64",
            helperTaskName = "compileKiteFFmpegCForJniAndroidX64",
            ffmpegDirName = "android-x64",
            konanTargetName = "android_x64",
            ndkTarget = "x86_64-linux-android24",
            abiDirectory = "x86_64",
            outputRelativePath = "kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so",
        )

        assertEquals(listOf(arm64, x64), LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES)
        assertEquals(expectedAndroidLinkFlags("aarch64-linux-android24"), LinkKiteFFmpegJniTask.androidLinkFlags(arm64))
        assertEquals(expectedAndroidLinkFlags("x86_64-linux-android24"), LinkKiteFFmpegJniTask.androidLinkFlags(x64))
    }

    @Test
    fun elfAndMachOExportControlsAreContentTrackedAndConsumedByTheLinkRecipe() {
        val root = Files.createTempDirectory("kitecodec-jni-link-input-test").toFile()
        try {
            val elf = root.resolve("exports.map").apply { writeText("{ global: JNI_OnLoad; local: *; };\n") }
            val macho = root.resolve("exports.macos").apply { writeText("_JNI_OnLoad\n") }
            val project = ProjectBuilder.builder().build()
            val elfTask = newLinkTask(project, "linkElf", root, elf, LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
            val machoTask = newLinkTask(
                project,
                "linkMacho",
                root,
                macho,
                LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS,
            )

            assertTrue(elf in elfTask.inputs.files.files, "exports.map is not a tracked link-task input")
            assertTrue(macho in machoTask.inputs.files.files, "exports.macos is not a tracked link-task input")
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
    fun generatedSourceOutputTracksTheRootAndForbidsALeafOrEscape() {
        val root = Files.createTempDirectory("kitecodec-jni-output-model-test").toFile()
        try {
            val exportControl = root.resolve("exports.map").apply {
                writeText("{ global: JNI_OnLoad; local: *; };\n")
            }
            val task = newLinkTask(
                ProjectBuilder.builder().build(),
                "linkAndroidArm64",
                root,
                exportControl,
                LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT,
            )
            val outputRoot = task.outputDirectory.get().asFile
            val outputLibrary = task.outputLibrary.get().asFile

            assertTrue(outputRoot in task.outputs.files.files, "generated-source root is not a task output")
            assertTrue(outputLibrary !in task.outputs.files.files, "contained leaf must not overlap the directory output")
            LinkKiteFFmpegJniTask.assertOutputLibraryInsideDirectory(outputRoot, outputLibrary)

            val leafFailure = assertFailsWith<org.gradle.api.GradleException> {
                LinkKiteFFmpegJniTask.assertOutputLibraryInsideDirectory(outputRoot, outputRoot)
            }
            assertTrue(leafFailure.message.orEmpty().contains("must be inside output directory"))
            assertFailsWith<org.gradle.api.GradleException> {
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
        assertFailsWith<org.gradle.api.GradleException> {
            PrepareKiteFFmpegJniHarnessTask.replaceExactlyOnce("alpha", "missing", "beta", "fixture")
        }
        assertFailsWith<org.gradle.api.GradleException> {
            PrepareKiteFFmpegJniHarnessTask.replaceExactlyOnce("alpha alpha", "alpha", "beta", "fixture")
        }
    }

    @Test
    fun jvmArgumentProviderResolvesAllHarnessPathsAndTheRealProbeClasspath() {
        val root = Files.createTempDirectory("kitecodec-jni-jvm-args-test").toFile()
        try {
            val normal = root.resolve("normal.dylib").apply { writeText("normal") }
            val mismatch = root.resolve("mismatch.dylib").apply { writeText("mismatch") }
            val corrupt = root.resolve("corrupt.dylib").apply { writeText("corrupt") }
            val transcript = root.resolve("transcript.txt")
            val runtime = root.resolve("test-runtime.jar").apply { writeText("runtime") }
            val project = ProjectBuilder.builder().build()
            val provider = project.objects.newInstance(KiteFFmpegJvmTestArgumentProvider::class.java).apply {
                normalJniLibrary.set(normal)
                mismatchJniLibrary.set(mismatch)
                corruptJniLibrary.set(corrupt)
                contractTranscript.set(transcript)
                probeClasspath.from(runtime)
            }

            assertEquals(
                listOf(
                    "-Dkiteffmpeg.jni.path=${normal.absolutePath}",
                    "-Dkiteffmpeg.jni.mismatch.path=${mismatch.absolutePath}",
                    "-Dkiteffmpeg.jni.corrupt.path=${corrupt.absolutePath}",
                    "-Dkiteffmpeg.contract.transcript=${transcript.absolutePath}",
                    "-Dkiteffmpeg.jni.probe.classpath=${runtime.absolutePath}",
                ),
                provider.asArguments().toList(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coreBuildWiresBothDedicatedAndroidHelpersAndThePlatformExportControls() {
        val repoRoot = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root")))
        val source = repoRoot.resolve("kiteffmpeg-core/build.gradle.kts").readText()
        val helperRegistrations = sourceSection(
            source,
            "val androidHelperTasks = LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->",
            "tasks.register<LinkKiteFFmpegJniTask>(\n        \"linkKiteFFmpegJniMacosArm64\",",
        )
        val macLinkRegistration = sourceSection(
            source,
            "tasks.register<LinkKiteFFmpegJniTask>(\n        \"linkKiteFFmpegJniMacosArm64\",",
            "// The two Android arms, exactly the S1.c.1 step 6 recipe.",
        )
        val androidLinkRegistrations = source.substring(
            sourceMarker(
                source,
                "val androidJniLinks = LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->",
            ),
        )

        assertSourceContains(
            helperRegistrations,
            "tasks.register<CompileKiteFFmpegCTask>(arm.helperTaskName)",
        )
        assertSourceContains(helperRegistrations, "konanTargetName.set(arm.konanTargetName)")
        assertSourceContains(
            helperRegistrations,
            "outputDir.set(layout.buildDirectory.dir(\"kitecodec-c-jni/${'$'}{arm.konanTargetName}\"))",
        )

        assertSourceContains(androidLinkRegistrations, "val helperCompile = androidHelperTasks.getValue(arm)")
        assertSourceContains(
            androidLinkRegistrations,
            "tasks.register<LinkKiteFFmpegJniTask>(arm.linkTaskName)",
        )
        assertSourceContains(androidLinkRegistrations, "dependsOn(helperCompile)")
        assertSourceContains(
            androidLinkRegistrations,
            "helperArchive.from(helperCompile.flatMap { it.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) })",
        )

        assertSourceContains(
            macLinkRegistration,
            "exportControlFile.set(jniDir.resolve(\"exports.macos\"))",
        )
        assertSourceContains(
            macLinkRegistration,
            "exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "exportControlFile.set(jniDir.resolve(\"exports.map\"))",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "outputDirectory.set(layout.buildDirectory.dir(\"kitecodec-jni/${'$'}{arm.ffmpegDirName}\"))",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "outputLibrary.set(outputDirectory.file(\"${'$'}{arm.abiDirectory}/libkitecodec_jni.so\"))",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "jniLibs.addGeneratedSourceDirectory(",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "LinkKiteFFmpegJniTask::outputDirectory",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "native/kitecodec-c/tests/fake_headers/major_mismatch/kitecodec_ffmpeg_versions.h",
        )
        assertSourceContains(androidLinkRegistrations, "mutationSourceFile.set(")
        assertSourceContains(
            androidLinkRegistrations,
            "ffmpegIncludeDirs.set(listOf(opaqueInclude.absolutePath, macosFfmpegInclude.absolutePath))",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "compileKiteFFmpegCForJniMacosArm64MajorMismatch",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "linkKiteFFmpegJniMacosArm64CorruptDescriptor",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "\"nativeAbiVersion\",       \"()J\",                    kj_abi_version",
        )
        assertSourceContains(androidLinkRegistrations, "mismatchJniLibrary.set(mismatchJniLink.flatMap")
        assertSourceContains(androidLinkRegistrations, "corruptJniLibrary.set(corruptJniLink.flatMap")
        assertSourceContains(androidLinkRegistrations, "probeClasspath.from(testRuntimeClasspath)")
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

    private fun expectedAndroidLinkFlags(ndkTarget: String): List<String> = listOf(
        "--target=$ndkTarget",
        "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
        "-lmediandk", "-landroid", "-llog", "-lz", "-ldl", "-lm",
        "-Wl,-z,defs", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
        "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
    )

    private fun newLinkTask(
        project: org.gradle.api.Project,
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
        return project.tasks.create(name, LinkKiteFFmpegJniTask::class.java).apply {
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
            outputLibrary.set(outputDirectory.file("libkitecodec_jni.so"))
        }
    }

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
        assertTrue(endIndex >= 0, "build script is missing section terminator: $end")
        return source.substring(startIndex, endIndex)
    }

    private fun sourceMarker(source: String, marker: String): Int {
        val index = source.indexOf(marker)
        assertTrue(index >= 0, "build script is missing wiring marker: $marker")
        return index
    }

    private fun assertSourceContains(section: String, expected: String) {
        assertTrue(expected in section, "build-script wiring is missing: $expected")
    }
}
