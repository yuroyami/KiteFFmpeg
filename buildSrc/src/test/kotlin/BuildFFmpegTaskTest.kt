package io.github.yuroyami.kitecodec.buildtools

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuildFFmpegTaskTest {

    /**
     * The staleness check exists because of a MEASURED silence, and this row replays it.
     *
     * On 2026-08-19 `av1_videotoolbox` was pinned into the Apple hwaccel list. A day later every
     * Apple tree on the proving machine still carried the two-hwaccel line, AV1 hardware decode was
     * therefore impossible, and not one gate anywhere was red. The tree is a dead artifact nothing
     * rebuilds and nothing compared. The stamp the tree already carried was the evidence; nobody
     * was reading it.
     */
    @Test
    fun `a tree baked before the av1 hwaccel pin is reported stale, naming the flag`() {
        val installed =
            "/scratch/kitecodec-ffmpeg-1/configure --enable-static --disable-shared " +
                "--enable-videotoolbox --enable-hwaccel='h264_videotoolbox,hevc_videotoolbox' " +
                "--enable-libdav1d --prefix=/scratch/install"
        val expected = listOf(
            "--enable-static", "--disable-shared", "--enable-videotoolbox",
            "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox,av1_videotoolbox",
            "--enable-libdav1d", "--prefix=/somewhere/else",
        )
        val reason = BuildFFmpegTask.staleReason(installed, expected)
        assertTrue(reason != null && "av1_videotoolbox" in reason, "expected the new flag: $reason")
        assertTrue("no longer asked for" in reason!!, "expected the dropped line too: $reason")
    }

    /**
     * The other half, and the half that decides whether anyone leaves the check turned on: one
     * recipe rendered two ways must compare EQUAL. FFmpeg's own config.log echo quotes list values
     * and the task does not, `--prefix` is a fresh scratch path every run, and `--cc` carries an
     * SDK path that moves with every Xcode update. A checker that cried wolf on any of those would
     * be disabled within a day.
     */
    @Test
    fun `the same recipe rendered by ffmpeg and by the task compares equal`() {
        val installed =
            "/tmp/kitecodec-ffmpeg-abc/configure --enable-static " +
                "--enable-protocol='file,fd,pipe' --enable-hwaccel='h264_videotoolbox' " +
                "--cc='clang -arch arm64 -isysroot /Xcode17/SDK/iphoneos -mios-version-min=14.0' " +
                "--prefix=/tmp/kitecodec-ffmpeg-abc/install"
        val expected = listOf(
            "--enable-static",
            "--enable-protocol=file,fd,pipe",
            "--enable-hwaccel=h264_videotoolbox",
            "--cc=clang -arch arm64 -isysroot /Xcode26/SDK/iphoneos -mios-version-min=14.0",
            "--prefix=/var/folders/somewhere/completely/different/install",
        )
        assertEquals(
            null,
            BuildFFmpegTask.staleReason(installed, expected),
            "quoting, the scratch prefix and an Xcode SDK move must not read as a recipe change",
        )
    }

    /** Drift is caught in BOTH directions: a tree carrying what the recipe dropped is stale too. */
    @Test
    fun `a flag the recipe dropped is caught in the other direction`() {
        val installed = "/s/configure --enable-static --enable-filter=scale,pad --enable-muxer=mp4"
        val reason = BuildFFmpegTask.staleReason(
            installed,
            listOf("--enable-static", "--enable-filter=scale,pad"),
        )
        assertTrue(
            reason != null && "--enable-muxer=mp4" in reason && "no longer asked for" in reason,
            "a tree still carrying a dropped flag must be stale: $reason",
        )
    }

    /**
     * dav1d stopped being a toggle on 2026-08-22 (KC-EMBED): it is RECIPE, so a tree WITHOUT it
     * is genuinely stale and must say so. The old exclusion existed only because a -P toggle is
     * not drift; with no toggle left, the exclusion would hide a real AV1-less tree.
     */
    @Test
    fun `a tree without dav1d is stale, because dav1d is recipe now`() {
        val installed = "/s/configure --enable-static"
        val reason = BuildFFmpegTask.staleReason(
            installed,
            listOf("--enable-static", "--enable-libdav1d", "--enable-decoder=libdav1d"),
        )
        assertTrue(
            reason != null && "--enable-libdav1d" in reason,
            "an AV1-less tree must be reported stale naming the dav1d flag: $reason",
        )
        // And a tree WITH it, checked against the same recipe, is not drift.
        assertEquals(
            null,
            BuildFFmpegTask.staleReason(
                "/s/configure --enable-static --enable-libdav1d --enable-decoder=libdav1d --pkg-config=pkg-config",
                listOf("--enable-static", "--enable-libdav1d", "--enable-decoder=libdav1d"),
            ),
        )
    }

    /** The fingerprint is what the two above rest on, so it is pinned directly too. */
    @Test
    fun `the fingerprint keeps capability flags and drops machine paths`() {
        val fingerprint = BuildFFmpegTask.recipeFingerprint(
            listOf(
                "/scratch/configure",
                "--enable-decoder=h264,hevc",
                "--enable-hwaccel='a,b'",
                "--prefix=/scratch/install",
                "--cc=clang -isysroot /SDK",
                "--enable-cross-compile",
                "-arch",
                "arm64",
                // Recipe since KC-EMBED: dav1d flags survive into the fingerprint.
                "--enable-libdav1d",
            ),
        )
        assertEquals(
            setOf(
                "--enable-decoder=h264,hevc", "--enable-hwaccel=a,b", "--enable-cross-compile",
                "--enable-libdav1d",
            ),
            fingerprint,
        )
    }

    @Test
    fun androidArm64AndX64UseTheExactApi24MediaCodecJniPicArguments() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        val root = Files.createTempDirectory("kitecodec-android-args-test")
        try {
            val toolchainBin = root.resolve("toolchains/llvm/prebuilt/test-host/bin").createDirectories()
            toolchainBin.resolve("aarch64-linux-android24-clang").createFile()
            toolchainBin.resolve("x86_64-linux-android24-clang").createFile()

            val arm64 = task.configureArguments(
                target = TargetTriple.AndroidArm64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install-arm64",
                dav1dRoot = java.io.File("/stub/dav1d"),
                ndkToolchainBin = { toolchainBin.toFile() },
            )
            val x64 = task.configureArguments(
                target = TargetTriple.AndroidX64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install-x64",
                dav1dRoot = java.io.File("/stub/dav1d"),
                ndkToolchainBin = { toolchainBin.toFile() },
            )

            assertEquals(
                expectedSharedCoreArguments() + expectedAndroidArguments(
                    toolchainBin = toolchainBin.toString(),
                    arch = "aarch64",
                    compilerPrefix = "aarch64-linux-android",
                    suffix = listOf("--cpu=armv8-a"),
                    installPrefix = "/scratch/install-arm64",
                ),
                arm64,
            )
            assertEquals(
                expectedSharedCoreArguments() + expectedAndroidArguments(
                    toolchainBin = toolchainBin.toString(),
                    arch = "x86_64",
                    compilerPrefix = "x86_64-linux-android",
                    suffix = listOf("--disable-asm"),
                    installPrefix = "/scratch/install-x64",
                ),
                x64,
            )
            listOf(arm64, x64).forEach { arguments ->
                assertTrue("--enable-pic" in arguments)
                assertTrue("--enable-mediacodec" in arguments)
                assertTrue("--enable-jni" in arguments)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun iosProfilesUseTheExactStandardCoreZlibAndCrossArguments() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)

        val device = task.configureArguments(
            target = TargetTriple.IosArm64,
            license = FFmpegLicense.LGPL,
            installPrefix = "/scratch/install",
            dav1dRoot = java.io.File("/stub/dav1d"),
            sdkPath = { "/SDK/${it}" },
        )
        val simulator = task.configureArguments(
            target = TargetTriple.IosSimulatorArm64,
            license = FFmpegLicense.LGPL,
            installPrefix = "/scratch/install",
            dav1dRoot = java.io.File("/stub/dav1d"),
            sdkPath = { "/SDK/${it}" },
        )

        // VideoToolbox DECODE (KPKMP 17.4.8 S2.a) is on for every Apple target, simulator
        // included. The hwaccel line is a PIN: it keeps the two hwaccels D-2 needs even if the
        // wide class policy ever changes.
        //
        // Two more things this golden pins, both added when the parity audit found them missing:
        // AudioToolbox must be REQUESTED (autodetect is off, so an unasked framework is absent and
        // the `*_at` decoder class never compiles), and NO `--disable-asm` may appear on an arm64
        // iOS target, because that flag is what built every iPhone a C-only libavcodec.
        assertEquals(
            expectedSharedCoreArguments() + listOf(
                "--disable-autodetect",
                "--enable-zlib",
                "--enable-videotoolbox",
                "--enable-audiotoolbox",
                "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox,av1_videotoolbox",
                "--arch=arm64",
                "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot /SDK/iphoneos -mios-version-min=14.0",
                "--enable-cross-compile",
                "--enable-libdav1d",
                "--enable-decoder=libdav1d",
                "--pkg-config=pkg-config",
                "--prefix=/scratch/install",
            ),
            device,
        )
        assertEquals(
            expectedSharedCoreArguments() + listOf(
                "--disable-autodetect",
                "--enable-zlib",
                "--enable-videotoolbox",
                "--enable-audiotoolbox",
                "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox,av1_videotoolbox",
                "--arch=arm64",
                "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot /SDK/iphonesimulator -mios-simulator-version-min=14.0",
                "--enable-cross-compile",
                "--enable-libdav1d",
                "--enable-decoder=libdav1d",
                "--pkg-config=pkg-config",
                "--prefix=/scratch/install",
            ),
            simulator,
        )

        val refusal = assertFailsWith<IllegalArgumentException> {
            task.configureArguments(
                target = TargetTriple.IosArm64,
                license = FFmpegLicense.GPL,
                installPrefix = "/must-not-resolve",
                dav1dRoot = java.io.File("/stub/dav1d"),
                sdkPath = { error("GPL refusal must happen before SDK resolution") },
            )
        }
        assertEquals(IOS_GPL_REFUSAL, refusal.message)
    }

    /**
     * The asm law, stated as a law rather than left to the goldens above.
     *
     * Every arm64 target this project builds must carry aarch64 asm: it is the single biggest lever
     * on software decode speed, and nothing about a cross-compile to iOS requires giving it up. Only
     * the x86_64 targets may opt out, because their inline asm needs nasm in the environment.
     */
    @Test
    fun onlyX8664TargetsMayDisableAsm() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)

        listOf(TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64).forEach { target ->
            val args = task.configureArguments(
                target = target,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
            dav1dRoot = java.io.File("/stub/dav1d"),
                sdkPath = { "/SDK/${it}" },
            )
            assertFalse("--disable-asm" in args, "$target must build with aarch64 asm")
            assertTrue("--enable-audiotoolbox" in args, "$target must request AudioToolbox")
        }

        // IosX64 keeps the opt-out, and keeps it for the stated nasm reason.
        assertTrue(
            "--disable-asm" in task.configureArguments(
                target = TargetTriple.IosX64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
            dav1dRoot = java.io.File("/stub/dav1d"),
                sdkPath = { "/SDK/${it}" },
            ),
        )
    }

    @Test
    fun linuxAndMingwUseTheExactKonanCrossCompileArguments() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)

        fun argumentsFor(target: TargetTriple) = task.configureArguments(
            target = target,
            license = FFmpegLicense.LGPL,
            installPrefix = "/scratch/install-${target.dirName}",
            dav1dRoot = java.io.File("/stub/dav1d"),
            konanBin = ::fakeKonanTools,
        )

        // Linux carries --enable-zlib (its konan sysroot has it) and a gcc runtime dir beside the
        // sysroot, so the --cc line grows the extra -B/-L pair.
        assertEquals(
            expectedSharedCoreArguments() + expectedKonanCrossArguments(
                leading = listOf("--enable-zlib"),
                arch = "x86_64",
                targetOs = "linux",
                cc = "/fake/llvm/bin/clang -target x86_64-unknown-linux-gnu " +
                    "--sysroot=/fake/linux-x64/sysroot -fuse-ld=lld -B/fake/llvm/bin " +
                    "-B/fake/linux-x64/gcc -L/fake/linux-x64/gcc",
                trailing = emptyList(),
                installPrefix = "/scratch/install-linux-x64",
            ),
            argumentsFor(TargetTriple.LinuxX64),
        )
        assertEquals(
            expectedSharedCoreArguments() + expectedKonanCrossArguments(
                leading = listOf("--enable-zlib"),
                arch = "aarch64",
                targetOs = "linux",
                cc = "/fake/llvm/bin/clang -target aarch64-unknown-linux-gnu " +
                    "--sysroot=/fake/linux-arm64/sysroot -fuse-ld=lld -B/fake/llvm/bin " +
                    "-B/fake/linux-arm64/gcc -L/fake/linux-arm64/gcc",
                trailing = emptyList(),
                installPrefix = "/scratch/install-linux-arm64",
            ),
            argumentsFor(TargetTriple.LinuxArm64),
        )
        // Windows differs three ways: target-os is mingw32, its headers need -std=gnu11, and it
        // threads with w32threads. The pthreads request from sharedCoreArgs is withdrawn here,
        // since configure takes the last word. zlib it DOES have, at the msys2 package root rather
        // than under the triple directory, which is what made a first reading call it absent.
        // The fake has no runtime dir, which also pins the branch with no -B/-L pair.
        assertEquals(
            expectedSharedCoreArguments() + expectedKonanCrossArguments(
                leading = listOf("--enable-zlib"),
                arch = "x86_64",
                targetOs = "mingw32",
                cc = "/fake/llvm/bin/clang -target x86_64-w64-mingw32 " +
                    "--sysroot=/fake/mingw-x64/sysroot -fuse-ld=lld -B/fake/llvm/bin",
                trailing = listOf(
                    "--extra-cflags=-std=gnu11",
                    "--disable-pthreads", "--enable-w32threads",
                ),
                installPrefix = "/scratch/install-mingw-x64",
            ),
            argumentsFor(TargetTriple.MingwX64),
        )
    }

    @Test
    fun linuxAndMingwCarryNoneOfTheDesktopThirdPartyStack() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        // Decision W-D4 (KPKMP.md 17.13): these three triples get the REDUCED desktop profile,
        // because none of these libraries has ever been cross-built for them. If one grows back,
        // configure fails and the cross build dies, so pin its absence.
        val forbidden = listOf(
            "--enable-libsvtav1", "--enable-libvpx", "--enable-libaom",
            "--enable-libmp3lame", "--enable-libopus", "--enable-libwebp",
            "--enable-libfreetype", "--enable-libharfbuzz", "--enable-libfribidi",
            "--enable-libass", "--enable-filter=drawtext", "--enable-gpl",
        )
        listOf(TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64).forEach { target ->
            val arguments = task.configureArguments(
                target = target,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
            dav1dRoot = java.io.File("/stub/dav1d"),
                konanBin = ::fakeKonanTools,
            )
            forbidden.forEach { flag ->
                assertFalse(flag in arguments, "$flag came back for $target")
            }
            // Compression is per sysroot, measured: all three carry zlib and none carries
            // bzlib or lzma. configure REFUSES a library it cannot find, so asking for one
            // more here would fail the build. zlib is asked for rather than autodetected so
            // the consumer link line, written from this same list, learns to name -lz.
            assertTrue("--enable-zlib" in arguments, "zlib is in every sysroot here ($target)")
            assertFalse("--enable-bzlib" in arguments, "no sysroot here carries bzlib ($target)")
            assertFalse("--enable-lzma" in arguments, "no sysroot here carries lzma ($target)")
            // GPL is refused everywhere since 2026-08-21: this project bakes LGPL only.
            val refusal = assertFailsWith<IllegalArgumentException> {
                task.configureArguments(
                    target = target,
                    license = FFmpegLicense.GPL,
                    installPrefix = "/scratch/install",
            dav1dRoot = java.io.File("/stub/dav1d"),
                    konanBin = ::fakeKonanTools,
                )
            }
            assertEquals(LGPL_ONLY_REFUSAL, refusal.message)
        }
    }

    @Test
    fun everyRecipeCarriesTheThreeDav1dArgumentsBeforeThePrefix() {
        // KC-EMBED (2026-08-22): the dav1d switch is dead, dav1d is recipe. The three arguments
        // sit immediately before --prefix on every profile.
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        val root = Files.createTempDirectory("kitecodec-av1sw-args-test")
        try {
            val toolchainBin = root.resolve("toolchains/llvm/prebuilt/test-host/bin").createDirectories()
            toolchainBin.resolve("aarch64-linux-android24-clang").createFile()

            val arguments = task.configureArguments(
                target = TargetTriple.AndroidArm64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
                dav1dRoot = java.io.File("/stub/dav1d"),
                ndkToolchainBin = { toolchainBin.toFile() },
            )
            assertEquals(
                listOf(
                    "--enable-libdav1d", "--enable-decoder=libdav1d", "--pkg-config=pkg-config",
                    "--prefix=/scratch/install",
                ),
                arguments.takeLast(4),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun theDav1dArchiveAndLinkFlagRideEveryProfile() {
        // Every triple bundles exactly libdav1d.a and leads its link set with -ldav1d: dav1d is
        // the ONLY software AV1 route FFmpeg has, and since KC-EMBED it is never optional.
        TargetTriple.entries.forEach { target ->
            assertEquals(
                listOf("libdav1d.a"),
                StaticLinkFlags.thirdPartyArchives(target, FFmpegLicense.LGPL),
                "$target must bundle exactly libdav1d.a",
            )
            assertEquals(
                "-ldav1d",
                StaticLinkFlags.forTarget(target, FFmpegLicense.LGPL, isStaticVendored = true).first(),
                "$target must name -ldav1d first, before anything that draws from it",
            )
            assertEquals(
                emptyList(),
                StaticLinkFlags.forTarget(target, FFmpegLicense.LGPL, isStaticVendored = false),
                "a shared system FFmpeg resolves its own dependencies ($target)",
            )
        }
    }

    /**
     * The registered tasks and the writable cross files are ONE list.
     *
     * Guards the shape of bug where a target is registered, runs, and dies telling the caller its
     * cross file "needs writing first". wasm32 is absent on purpose and cannot be in this set: it is
     * not a TargetTriple, and dav1d needs pthreads while the shipped wasm profile is single-threaded.
     */
    @Test
    fun dav1dSupportsExactlyTheTargetsItRegisters() {
        // Every triple since the full-coverage release (2026-08-22): the release workflow ships a
        // dav1d flavour for all 11, so the task must write a cross file for all 11.
        assertEquals(TargetTriple.entries.toSet(), BuildDav1dTask.SUPPORTED_TARGETS)
    }

    @Test
    fun appleStaticLinkSetsAreExactlyZlibPlusTheMediaFrameworks() {
        // macOS joined the same portable Apple profile as iOS (2026-08-22), so ALL five Apple
        // triples share one link set: zlib plus the media frameworks. AudioToolbox is named
        // because every Apple profile requests --enable-audiotoolbox, so the static archives
        // hold undefined references into it exactly as they do into VideoToolbox since S2.a.
        listOf(
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64,
            TargetTriple.MacosArm64, TargetTriple.MacosX64,
        ).forEach { target ->
            assertEquals(listOf("libdav1d.a"), StaticLinkFlags.thirdPartyArchives(target, FFmpegLicense.LGPL))
            assertEquals(emptyList(), StaticLinkFlags.hostFallbackSearchFlags(target, "/host", true))
            assertEquals(
                listOf(
                    "-ldav1d",
                    "-lz",
                    "-framework", "CoreFoundation",
                    "-framework", "CoreMedia",
                    "-framework", "CoreVideo",
                    "-framework", "VideoToolbox",
                    "-framework", "AudioToolbox",
                ),
                StaticLinkFlags.forTarget(target, FFmpegLicense.LGPL, true),
            )
        }
    }

    @Test
    fun hashPathSourceIsCopiedToAHashFreeScratchTreeWithoutBuildState() {
        val root = Files.createTempDirectory("kitecodec-source-test")
        try {
            val source = root.resolve("source#checkout").createDirectories()
            source.resolve("configure").createFile()
            source.resolve("configure").setPosixFilePermissions(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            source.resolve("libavcodec/codec.c").apply {
                parent.createDirectories()
                createFile()
            }
            source.resolve("build/stale.o").apply {
                parent.createDirectories()
                createFile()
            }
            source.resolve("nested/build/stale.o").apply {
                parent.createDirectories()
                createFile()
            }
            source.resolve(".git/index").apply {
                parent.createDirectories()
                createFile()
            }

            val scratch = BuildFFmpegTask.createScratchWorkspace(root.resolve("tmp").createDirectories())
            BuildFFmpegTask.copySourceTree(source, scratch.resolve("source"))

            assertFalse('#' in scratch.toAbsolutePath().toString())
            assertTrue(scratch.resolve("source/configure").isExecutable())
            assertTrue(Files.isRegularFile(scratch.resolve("source/libavcodec/codec.c")))
            assertFalse(Files.exists(scratch.resolve("source/build")))
            assertFalse(Files.exists(scratch.resolve("source/nested/build")))
            assertFalse(Files.exists(scratch.resolve("source/.git")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidScratchInstallNeverReplacesAnExistingGoodOutput() {
        val root = Files.createTempDirectory("kitecodec-replacement-test")
        try {
            val goodOutput = createCompleteInstall(
                root.resolve("native-libs/lgpl/ios-arm64").createDirectories(),
                configureEvidence = "./configure --known-good\n",
            )
            goodOutput.resolve("known-good.marker").createFile()
            val invalidScratch = createCompleteInstall(
                root.resolve("scratch-install").createDirectories(),
                configureEvidence = null,
            )

            val failure = assertFailsWith<IllegalStateException> {
                BuildFFmpegTask.replaceOutputTree(invalidScratch, goodOutput)
            }

            assertTrue("configure provenance" in failure.message.orEmpty())
            assertTrue(Files.isRegularFile(goodOutput.resolve("known-good.marker")))
            assertEquals(
                "./configure --known-good\n",
                Files.readString(goodOutput.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun configureEvidenceNormalizesOnlyTheLogMarkerAndUsesTheStableInstalledPath() {
        val root = Files.createTempDirectory("kitecodec-configure-evidence-test")
        try {
            val configLog = root.resolve("build/ffbuild/config.log").apply {
                parent.createDirectories()
                Files.writeString(
                    this,
                    "# ./configure --disable-autodetect --enable-zlib\nignored second line\n",
                )
            }
            val install = createCompleteInstall(root.resolve("install").createDirectories(), null)

            BuildFFmpegTask.writeConfigureEvidence(configLog, install)

            val evidence = install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)
            assertEquals(
                "./configure --disable-autodetect --enable-zlib\n",
                Files.readString(evidence),
            )
            BuildFFmpegTask.verifyInstall(install)

            Files.writeString(configLog, "#not-a-marker\n")
            BuildFFmpegTask.writeConfigureEvidence(configLog, install)
            assertEquals("#not-a-marker\n", Files.readString(evidence))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingConfigureLogIsRefusedBeforeWritingAnInstallRecord() {
        val root = Files.createTempDirectory("kitecodec-missing-configure-log-test")
        try {
            val install = root.resolve("install").createDirectories()
            val missingLog = root.resolve("build/ffbuild/config.log")

            val failure = assertFailsWith<IllegalStateException> {
                BuildFFmpegTask.writeConfigureEvidence(missingLog, install)
            }

            assertTrue("configure provenance is missing" in failure.message.orEmpty())
            assertFalse(Files.exists(install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createCompleteInstall(
        install: java.nio.file.Path,
        configureEvidence: String?,
    ): java.nio.file.Path {
        install.resolve("include/libavformat/avformat.h").apply {
            parent.createDirectories()
            Files.writeString(this, "fixture")
        }
        BuildFFmpegTask.REQUIRED_LIBS.forEach { library ->
            install.resolve("lib/$library.a").apply {
                parent.createDirectories()
                Files.writeString(this, "fixture")
            }
        }
        configureEvidence?.let { text ->
            install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH).apply {
                parent.createDirectories()
                Files.writeString(this, text)
            }
        }
        return install
    }

    /**
     * The exact `sharedCoreArgs()` line. Changing it must stay a reviewed act, not a silent one.
     *
     * Shaped by the wide read-side class policy (KPKMP.md 17.4.9): only the WRITE side and the
     * protocol list are curated, so there is no `--disable-everything` and no named demuxer,
     * decoder, parser or bsf list any more.
     */
    private fun expectedSharedCoreArguments(): List<String> = listOf(
        "--enable-static",
        "--disable-shared",
        "--disable-programs",
        "--disable-doc",
        "--disable-debug",
        "--disable-htmlpages",
        "--disable-manpages",
        "--disable-podpages",
        "--disable-txtpages",
        // 17.4.9 replaced the single `--disable-everything` with five class disables, so the read
        // side compiles whole and only these four classes stay curated.
        "--disable-encoders",
        "--disable-muxers",
        "--disable-filters",
        "--disable-devices",
        "--disable-protocols",
        "--enable-network",
        // `fd` joined the list so an Android content:// descriptor stays seekable.
        "--enable-protocol=file,fd,pipe,data,http,tcp",
        // The wide demuxer class SELECTS udp and rtp via rtsp/sdp, and a named disable beats a
        // select, so these two are hard-off instead of coming back through the class.
        "--disable-protocol=udp,rtp",
        "--enable-muxer=mp4,mov,ipod,webm,matroska,matroska_audio,mp3,wav,flac,ogg,opus,mpegts,image2",
        "--enable-encoder=mpeg4,aac,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg",
        "--enable-filter=buffer,buffersink,abuffer,abuffersink,trim,setpts,setparams,scale,pad,overlay,hue,unsharp,vignette,colorbalance,colorlevels,curves,lut,format,colorchannelmixer,split,null,atrim,asetpts,asetrate,aresample,volume,atempo,adelay,afade,amix,anull,aformat,loop,tpad",
        "--enable-pthreads",
        "--enable-pic",
        "--enable-runtime-cpudetect",
    )

    /**
     * A fake Kotlin/Native toolchain, injected through `configureArguments(konanBin = ...)`.
     *
     * The paths are made up on purpose, so the golden pins the flag SHAPE and never this
     * machine's `~/.konan` layout. Only mingw gets a null runtime dir, to cover both branches.
     */
    private fun fakeKonanTools(target: TargetTriple): BuildFFmpegTask.KonanTools =
        BuildFFmpegTask.KonanTools(
            clang = "/fake/llvm/bin/clang",
            toolchainBin = "/fake/llvm/bin",
            runtimeDir = if (target == TargetTriple.MingwX64) null else "/fake/${target.dirName}/gcc",
            ar = "/fake/llvm/bin/llvm-ar",
            nm = "/usr/bin/nm",
            ranlib = "/fake/llvm/bin/llvm-ar s",
            triple = when (target) {
                TargetTriple.LinuxX64 -> "x86_64-unknown-linux-gnu"
                TargetTriple.LinuxArm64 -> "aarch64-unknown-linux-gnu"
                else -> "x86_64-w64-mingw32"
            },
            sysroot = "/fake/${target.dirName}/sysroot",
        )

    /** The exact cross block `desktopTargetArgs` writes for the three konan-built triples. */
    private fun expectedKonanCrossArguments(
        leading: List<String>,
        arch: String,
        targetOs: String,
        cc: String,
        trailing: List<String>,
        installPrefix: String,
    ): List<String> = leading + listOf(
        "--arch=$arch",
        "--target-os=$targetOs",
        "--enable-cross-compile",
        "--cc=$cc",
        "--ar=/fake/llvm/bin/llvm-ar",
        "--nm=/usr/bin/nm",
        // ranlib is llvm-ar's own `s` operation, and stripping is off because konan's LLVM
        // package ships no strip that reads ELF or PE.
        "--ranlib=/fake/llvm/bin/llvm-ar s",
        "--disable-stripping",
        "--host-cc=/usr/bin/clang",
    ) + trailing + listOf(
        // dav1d is MANDATORY since KC-EMBED (2026-08-22); every recipe carries these three.
        "--enable-libdav1d", "--enable-decoder=libdav1d", "--pkg-config=pkg-config",
    ) + "--prefix=$installPrefix"

    private fun expectedAndroidArguments(
        toolchainBin: String,
        arch: String,
        compilerPrefix: String,
        suffix: List<String>,
        installPrefix: String,
    ): List<String> {
        val compiler = "$toolchainBin/$compilerPrefix${BuildFFmpegTask.ANDROID_API}-clang"
        return listOf(
            "--target-os=android",
            "--arch=$arch",
            "--enable-cross-compile",
            "--cc=$compiler",
            "--cxx=$compiler++",
            "--ar=$toolchainBin/llvm-ar",
            "--ranlib=$toolchainBin/llvm-ranlib",
            "--nm=$toolchainBin/llvm-nm",
            "--strip=$toolchainBin/llvm-strip",
            "--enable-mediacodec",
            "--enable-jni",
            "--enable-encoder=h264_mediacodec,hevc_mediacodec",
            // av1/vp9/vp8 joined here because FFmpeg has no native software AV1 decoder, so on
            // Android the MediaCodec wrappers are the only AV1 route this profile can offer.
            "--enable-decoder=h264_mediacodec,hevc_mediacodec,av1_mediacodec,vp9_mediacodec,vp8_mediacodec",
            "--enable-zlib",
        ) + suffix + listOf(
            // dav1d is MANDATORY since KC-EMBED (2026-08-22); every recipe carries these three.
            "--enable-libdav1d", "--enable-decoder=libdav1d", "--pkg-config=pkg-config",
        ) + "--prefix=$installPrefix"
    }
    /**
     * SOL-B4. The macOS trees carried NO deployment floor at all, so they took the SDK's.
     *
     * MEASURED 2026-08-25 on the committed archive: `otool -l native-libs/lgpl/macos-arm64/lib/
     * libavutil.a` reported `minos 26.0`, while Kotlin/Native links these objects at 12.0
     * (`minVersion.macos` in konan.properties for Kotlin 2.4.10) and the C helper layer compiled
     * at 11.0. Three floors, one product. The iOS branches always passed `-mios-version-min`; the
     * macOS branches simply never did, which is how the SDK default got in.
     *
     * 26.0 is the dangerous direction: an object built for a NEWER floor than the binary linking
     * it means the product claims macOS 12 support while embedding code that asks for 26.
     */
    @Test
    fun `both macOS targets pin the same deployment floor Kotlin Native links against`() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)

        fun ccFor(target: TargetTriple): String =
            task.configureArguments(
                target = target,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
                dav1dRoot = java.io.File("/stub/dav1d"),
            ).single { it.startsWith("--cc=") }

        val expected = "-mmacosx-version-min=${BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET}"
        assertEquals("12.0", BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET, "the floor is konan's own")

        val arm64 = ccFor(TargetTriple.MacosArm64)
        val x64 = ccFor(TargetTriple.MacosX64)
        assertTrue(expected in arm64, "macosArm64 must pin the floor, was: $arm64")
        assertTrue(expected in x64, "macosX64 must pin the floor, was: $x64")
    }

    /**
     * The floor is ONE constant, not a number repeated per branch.
     *
     * This row exists because three places each picked their own and nothing compared them. A fix
     * that writes "12.0" twice re-creates the defect the moment one of them is edited.
     */
    @Test
    fun `the two macOS targets cannot drift apart because they read one constant`() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)

        fun floorIn(target: TargetTriple): String =
            task.configureArguments(
                target = target,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
                dav1dRoot = java.io.File("/stub/dav1d"),
            ).single { it.startsWith("--cc=") }
                .substringAfter("-mmacosx-version-min=")
                .substringBefore(' ')

        val arm64Floor = floorIn(TargetTriple.MacosArm64)
        // Non-empty first: substringAfter returns the whole string when the flag is ABSENT, so a
        // fix deleted from BOTH branches would otherwise satisfy the equality below vacuously.
        assertEquals(BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET, arm64Floor, "the flag must be present")
        assertEquals(arm64Floor, floorIn(TargetTriple.MacosX64))
    }

    /**
     * KC-FLOOR-DRIFT. The deployment floor is a CAPABILITY, so the staleness check must see it.
     *
     * SOL-B4 pinned the macOS floor on 2026-08-25 and the pin was invisible to this check the day
     * it landed. The floor rides inside `--cc`, and `--cc` is machine-specific by key, so it is
     * stripped. The installed side fares no better: splitting the `config.log` line on spaces
     * shreds `--cc='clang -arch arm64 -mmacosx-version-min=12.0'` into fragments, and
     * `-mmacosx-version-min=12.0'` starts with ONE dash, so the `--` filter drops it too.
     *
     * A tree baked before the pin therefore compared EQUAL to a recipe that carries it, which is
     * the same silence the av1_videotoolbox row above was opened for.
     */
    @Test
    fun `a tree baked before the macOS floor was pinned is reported stale`() {
        val installed =
            "/scratch/kitecodec-ffmpeg-1/configure --enable-static " +
                "--cc='clang -arch arm64' --prefix=/scratch/install"
        val expected = listOf(
            "--enable-static",
            "--cc=clang -arch arm64 -mmacosx-version-min=12.0",
            "--prefix=/somewhere/else",
        )
        val reason = BuildFFmpegTask.staleReason(installed, expected)
        assertTrue(reason != null, "a tree with no deployment floor must not match one that pins it")
        assertTrue("12.0" in reason!!, "the reason must name the floor now asked for: $reason")
    }

    /** The other direction: a tree pinned at a floor the recipe has since MOVED is stale too. */
    @Test
    fun `a tree baked at a different macOS floor is reported stale`() {
        val installed =
            "/scratch/c/configure --enable-static " +
                "--cc='clang -arch arm64 -mmacosx-version-min=11.0' --prefix=/scratch/install"
        val expected = listOf(
            "--enable-static",
            "--cc=clang -arch arm64 -mmacosx-version-min=12.0",
            "--prefix=/elsewhere",
        )
        val reason = BuildFFmpegTask.staleReason(installed, expected)
        assertTrue(reason != null, "11.0 and 12.0 are different products")
        assertTrue("11.0" in reason!! && "12.0" in reason, "name both sides: $reason")
    }

    /**
     * And the floor must not become a NEW false positive.
     *
     * The same recipe rendered by FFmpeg and by the task still has to compare equal: the installed
     * side arrives quoted and shredded, the task side arrives as one unsplit string, and the SDK
     * path between them moves with every Xcode update.
     */
    @Test
    fun `one floor rendered both ways compares equal despite quoting and a moved SDK`() {
        val installed =
            "/scratch/c/configure --enable-static " +
                "--cc='clang -arch arm64 -isysroot /Xcode17/SDK/iphoneos -mios-version-min=14.0' " +
                "--prefix=/scratch/install"
        val expected = listOf(
            "--enable-static",
            "--cc=clang -arch arm64 -isysroot /Xcode26/SDK/iphoneos -mios-version-min=14.0",
            "--prefix=/completely/different",
        )
        assertEquals(
            null,
            BuildFFmpegTask.staleReason(installed, expected),
            "an Xcode move must still not read as a recipe change",
        )
    }

    /** The simulator floor carries a hyphen in its platform name and must survive intact. */
    @Test
    fun `the iOS simulator floor is not confused with the device floor`() {
        val device = BuildFFmpegTask.recipeFingerprint(
            listOf("--cc=clang -arch arm64 -mios-version-min=14.0"),
        )
        val simulator = BuildFFmpegTask.recipeFingerprint(
            listOf("--cc=clang -arch arm64 -mios-simulator-version-min=14.0"),
        )
        assertTrue(device.isNotEmpty(), "the device floor must reach the fingerprint")
        assertTrue(simulator.isNotEmpty(), "the simulator floor must reach the fingerprint")
        assertTrue(device != simulator, "device and simulator floors are different products")
    }

    /**
     * Fingerprinting a fingerprint must change nothing, and this is not a theoretical nicety.
     *
     * `CheckFFmpegRecipesTask` stores `expectedRecipeFingerprint()` in its `@Input` and then hands
     * that ALREADY-fingerprinted set to `staleReason`, which fingerprints it a second time. Every
     * token survived that because every token was a real `--flag`. The first synthetic token added
     * to this set (the deployment floor, KC-FLOOR-DRIFT) did not, so the expected side silently
     * lost it while the installed side kept it, and the check reported every iOS tree stale for a
     * floor that had never moved. CAUGHT BY RUNNING THE REAL TASK, not by any unit test here.
     */
    @Test
    fun `fingerprinting a fingerprint is the same fingerprint`() {
        val args = listOf(
            "--enable-static",
            "--enable-hwaccel=h264_videotoolbox,av1_videotoolbox",
            "--cc=clang -arch arm64 -isysroot /Xcode26/SDK/iphoneos -mios-version-min=14.0",
            "--prefix=/scratch/install",
        )
        val once = BuildFFmpegTask.recipeFingerprint(args)
        val twice = BuildFFmpegTask.recipeFingerprint(once.toList())
        assertEquals(once, twice, "a second pass must not drop or invent a token")
        assertTrue(once.any { "14.0" in it }, "the floor must be in the set to begin with")
    }

}
