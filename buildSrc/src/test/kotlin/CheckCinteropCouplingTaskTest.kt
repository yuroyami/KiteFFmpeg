package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Executable specification for the post-S1.a.8 boundary. The committed-tree case pins the zero
 * ceilings. Small fixtures then prove that each forbidden raw shape fails independently while the
 * owned opaque surface remains free to grow.
 */
class CheckCinteropCouplingTaskTest {

    private val repoRoot: File = File(
        System.getProperty("kiteffmpeg.repo.root") ?: "..",
    ).canonicalFile

    private val sourceDir: File get() = repoRoot.resolve("kiteffmpeg/src")

    private val committedBaseline: File get() = repoRoot.resolve("native/kitecodec-c/coupling-baseline.txt")

    /** C declarations supply the exact FFmpeg struct names that Kotlin is forbidden to name. */
    private val cDeclarationFiles: List<File>
        get() = listOf("include", "src")
            .map { repoRoot.resolve("native/kitecodec-c/$it") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("h", "c") } }
            .sortedBy { it.path }

    @Test
    fun theCommittedBaselineMatchesTheOpaqueBoundary() {
        val recorded = CheckCinteropCouplingTask.parseBaseline(committedBaseline)
        val measured = CheckCinteropCouplingTask.measure(sourceDir, cDeclarationFiles)

        assertEquals(0, recorded.counts.getValue(CheckCinteropCouplingTask.CINTEROP_IMPORT_LINES))
        assertEquals(0, recorded.counts.getValue(CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS))
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.CINTEROP_IMPORT_LINES))
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS))
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.DIRECT_LIBAV_CALL_SITES))
        assertEquals(emptySet(), measured.namedStructTypes)

        newTask(committedBaseline, sourceDir, cDeclarationFiles).check()
    }

    @Test
    fun aRawCinteropImportFails() {
        val fixture = fixture("    import ffmpeg.avcodec_send_packet\n")

        val failure = assertFailsWith<GradleException> {
            newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
        }

        assertContains(failure.message.orEmpty(), CheckCinteropCouplingTask.CINTEROP_IMPORT_LINES)
    }

    @Test
    fun aDirectLibavCallFails() {
        val fixture = fixture("fun probe() = avcodec_send_packet (null, null)\n")

        val failure = assertFailsWith<GradleException> {
            newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
        }

        assertContains(failure.message.orEmpty(), CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS)
    }

    @Test
    fun aBacktickedLibavCallFails() {
        val fixture = fixture("fun probe() = `avcodec_receive_frame` (null, null)\n")

        val failure = assertFailsWith<GradleException> {
            newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
        }

        assertContains(failure.message.orEmpty(), CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS)
    }

    @Test
    fun aFullyQualifiedAvioCallFails() {
        val fixture = fixture("fun probe() = ffmpeg.avio_open(null, null, 0)\n")

        val failure = assertFailsWith<GradleException> {
            newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
        }

        assertContains(failure.message.orEmpty(), CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS)
    }

    @Test
    fun aRawFFmpegStructNameFails() {
        val fixture = fixture("val probe: AVFrame? = null\n")

        val failure = assertFailsWith<GradleException> {
            newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
        }

        assertContains(failure.message.orEmpty(), "raw FFmpeg struct type(s) named in Kotlin: AVFrame")
    }

    @Test
    fun initialismFFmpegStructNamesFail() {
        val fixture = fixture(
            """
            |val io: AVIOContext? = null
            |val hardware: AVHWFramesContext? = null
            """.trimMargin(),
        )

        val failure = assertFailsWith<GradleException> {
            newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
        }

        assertContains(failure.message.orEmpty(), "AVIOContext")
        assertContains(failure.message.orEmpty(), "AVHWFramesContext")
    }

    @Test
    fun opaqueImportsAndUsesPass() {
        val fixture = fixture(
            """
            |import ffmpeg.ffkmp_frame_alloc
            |import ffmpeg.kc_frame
            |import ffmpeg.KC_LIB_AVUTIL
            |
            |private val diagnostic = "ffkmp_frame_alloc()"
            |
            |fun probe(frame: kc_frame?): Int {
            |    ffkmp_frame_alloc()
            |    return if (frame == null) KC_LIB_AVUTIL else KC_LIB_AVUTIL + 1
            |}
            """.trimMargin(),
        )

        val measured = CheckCinteropCouplingTask.measure(fixture.sourceDir, fixture.cDeclarations)
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.CINTEROP_IMPORT_LINES))
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS))
        assertEquals(1, measured.counts.getValue(CheckCinteropCouplingTask.FFKMP_CALL_SITES))
        assertEquals(emptySet(), measured.namedStructTypes)
        newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
    }

    @Test
    fun rawNamesInsideDiagnosticStringsDoNotCount() {
        val fixture = fixture(
            """
            |val ordinary = "avcodec_send_packet (null, null); `avcodec_receive_frame` (null, null); " +
            |    "ffmpeg.avio_open(null, null, 0); AVIOContext; AVHWFramesContext"
            |val raw = ${'"'}${'"'}${'"'}avcodec_send_packet (null, null); `avcodec_receive_frame` (null, null);
            |ffmpeg.avio_open(null, null, 0); AVIOContext; AVHWFramesContext${'"'}${'"'}${'"'}
            """.trimMargin(),
        )

        val measured = CheckCinteropCouplingTask.measure(fixture.sourceDir, fixture.cDeclarations)
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS))
        assertEquals(0, measured.counts.getValue(CheckCinteropCouplingTask.DIRECT_LIBAV_CALL_SITES))
        assertEquals(emptySet(), measured.namedStructTypes)
        newTask(fixture.baseline, fixture.sourceDir, fixture.cDeclarations).check()
    }

    @Test
    fun codeOnlyBlanksQuotedContentAndPreservesLayout() {
        val source =
            """
            |val ordinary = "diagnostic"
            |val raw = ${'"'}${'"'}${'"'}raw diagnostic${'"'}${'"'}${'"'}
            |val character = 'x'
            """.trimMargin()
        val code = CheckCinteropCouplingTask.codeOnly(source)

        assertEquals(source.length, code.length)
        assertEquals(source.count { it == '\n' }, code.count { it == '\n' })
        assertTrue("diagnostic" !in code)
        assertTrue("'x'" !in code)
    }

    @Test
    fun commentStrippingHandlesNestingAndPreservesQuotedContent() {
        val stripped = CheckCinteropCouplingTask.stripComments(
            """
            |val a = av_real_call(1) // av_line_comment(2)
            |/* av_block(3) /* nested av_block(4) */ still comment av_block(5) */
            |val b = "av_in_string(6) // not a comment"
            |val c = ${'"'}${'"'}${'"'}av_in_raw(7) /* not a comment */${'"'}${'"'}${'"'}
            |val d = av_after_all(8)
            """.trimMargin(),
        )

        assertContains(stripped, "av_real_call(1)")
        assertContains(stripped, "av_in_string(6)")
        assertContains(stripped, "av_in_raw(7)")
        assertContains(stripped, "av_after_all(8)")
        assertTrue("av_line_comment" !in stripped)
        assertTrue("av_block" !in stripped)
    }

    @Test
    fun templateCommentsDoNotCountButLiveRawCallsAndTypesDo() {
        val commentOnly = fixture(
            """
            |val value = "hidden ${'$'}{1 /* avcodec_send_packet(null, null); AVFrame */}"
            """.trimMargin(),
        )
        val hidden = CheckCinteropCouplingTask.measure(commentOnly.sourceDir, commentOnly.cDeclarations)
        assertEquals(0, hidden.counts.getValue(CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS))
        assertEquals(emptySet(), hidden.namedStructTypes)

        val live = fixture(
            """
            |val value = "live ${'$'}{run { avcodec_send_packet(null, null); null as AVFrame? }}"
            """.trimMargin(),
        )
        val visible = CheckCinteropCouplingTask.measure(live.sourceDir, live.cDeclarations)
        assertEquals(1, visible.counts.getValue(CheckCinteropCouplingTask.FFMPEG_TYPED_CROSSINGS))
        assertEquals(setOf("AVFrame"), visible.namedStructTypes)
    }

    @Test
    fun nestedRawTemplatesAndBacktickBracesStayInTheRightContext() {
        val stripped = CheckCinteropCouplingTask.stripComments(
            """
            |val raw = ${'"'}${'"'}${'"'}value ${'$'}{
            |    if (true) "nested ${'$'}{1 /* avcodec_send_packet(null, null) */}" else `name}kept`
            |    avcodec_receive_frame(null, null)
            |}${'"'}${'"'}${'"'}
            """.trimMargin(),
        )

        assertTrue("avcodec_send_packet" !in stripped)
        assertContains(stripped, "`name}kept`")
        assertContains(stripped, "avcodec_receive_frame(null, null)")
    }

    @Test
    fun anEscapedStringTemplateStaysLiteralText() {
        val stripped = CheckCinteropCouplingTask.stripComments(
            "val escaped = \"\\\${1 /* avcodec_send_packet(null, null) */}\"",
        )

        assertContains(stripped, "avcodec_send_packet(null, null)")
    }

    private fun fixture(kotlin: String): Fixture {
        val root = createTempDirectory("coupling-fixture").toFile()
        root.deleteOnExit()
        root.resolve("nativeInterop/cinterop").mkdirs()
        root.resolve(CheckCinteropCouplingTask.DEF_PATH).writeText("package = ffmpeg\n")
        root.resolve("Sample.kt").writeText(kotlin)
        val declarations = root.resolve("types.h").apply {
            writeText(
                """
                |struct AVFrame;
                |struct AVIOContext;
                |struct AVHWFramesContext;
                """.trimMargin(),
            )
        }
        val baseline = root.resolve("coupling-baseline.txt").apply {
            writeText(
                """
                |cinterop_import_lines 0
                |ffmpeg_typed_crossings 0
                """.trimMargin(),
            )
        }
        return Fixture(root, listOf(declarations), baseline)
    }

    private fun newTask(
        baseline: File,
        source: File,
        declarations: Collection<File>,
    ): CheckCinteropCouplingTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks
            .register("checkCinteropCoupling", CheckCinteropCouplingTask::class.java)
            .get()
        task.sourceDir.set(source)
        task.baselineFile.set(baseline)
        task.cDeclarationFiles.from(declarations)
        return task
    }

    private data class Fixture(
        val sourceDir: File,
        val cDeclarations: List<File>,
        val baseline: File,
    )
}
