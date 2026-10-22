package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateWasmBindingTaskTest {

    private val real: String by lazy {
        // The actual baseline, so these are assertions about the project and not about a fixture.
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "native/kitecodec-c/signature-baseline.txt").isFile }
        File(root, "native/kitecodec-c/signature-baseline.txt").readText()
    }

    @Test
    fun everyDeclarationInTheRealBaselineParses() {
        val lines = real.lineSequence().map { it.trim() }.count { it.startsWith("KC_API") }
        val parsed = GenerateWasmBindingTask.parse(real)
        // Equality, not "greater than zero": a regex that silently skips records would emit a
        // short export list, and a short export list fails at run time as a missing function.
        assertEquals(lines, parsed.size, "some KC_API records did not parse")
        assertTrue(lines > 180, "the baseline should hold the whole public surface, found $lines")
    }

    @Test
    fun namesAndReturnTypesComeOutSeparated() {
        val byName = GenerateWasmBindingTask.parse(real).associateBy { it.name }
        assertEquals("int", byName.getValue("ffkmp_fmt_read_frame").returns)
        assertEquals("void", byName.getValue("ffkmp_packet_unref").returns)
        assertEquals("int64_t", byName.getValue("ffkmp_frame_pts").returns)
        assertEquals("const char*", byName.getValue("ffkmp_strerror").returns)
        assertEquals("void", byName.getValue("ffkmp_frame_free").returns)
    }

    /** A pointer return must not be mistaken for part of the name, which is what `*` invites. */
    @Test
    fun pointerReturnsDoNotSwallowTheName() {
        val byName = GenerateWasmBindingTask.parse(real).associateBy { it.name }
        assertTrue("ffkmp_frame_alloc" in byName)
        assertTrue(byName.getValue("ffkmp_frame_alloc").returns.endsWith("*"))
        assertTrue("ffkmp_frame_plane" in byName)
        assertTrue("kc_ffmpeg_configuration" in byName)
    }

    @Test
    fun voidParameterListSurvivesAsEmptyOrVoid() {
        val byName = GenerateWasmBindingTask.parse(real).associateBy { it.name }
        assertEquals("void", byName.getValue("ffkmp_averror_eof").parameters)
    }

    /** The callback and JVM entry points are the binding's hand-written boundary, not exports. */
    @Test
    fun theHandWrittenSetIsRealAndExcluded() {
        val names = GenerateWasmBindingTask.parse(real).map { it.name }.toSet()
        GenerateWasmBindingTask.HAND_WRITTEN.forEach {
            assertTrue(it in names, "$it is in the hand-written set but not in the baseline")
        }
    }

    @Test
    fun aMalformedRecordIsNotSilentlyDropped() {
        // If the baseline ever stops being normalized, the count assertion above must catch it.
        val broken = "KC_API int ffkmp_ok(void);\nKC_API int ffkmp_broken(void)\n"
        val parsed = GenerateWasmBindingTask.parse(broken)
        assertEquals(1, parsed.size)
        assertEquals("ffkmp_ok", parsed.single().name)
    }
}
