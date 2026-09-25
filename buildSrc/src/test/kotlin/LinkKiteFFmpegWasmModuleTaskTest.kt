package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web codec module's link: it exports what the web backend calls and holds the runtime pieces
 * KiteFFmpegWeb.attach checks for, so a module linked from this command is one the backend accepts.
 */
class LinkKiteFFmpegWasmModuleTaskTest {

    private val repoRoot: File = File(System.getProperty("kiteffmpeg.repo.root") ?: "..").canonicalFile

    @Test
    fun theExportsAreTheBindingsHelpersAndTheHandWrittenOpen() {
        val baseline = repoRoot.resolve("native/kitecodec-c/signature-baseline.txt").readText()
        val exports = LinkKiteFFmpegWasmModuleTask.exports(baseline)
        val generated = GenerateWasmBindingTask.parse(baseline).filterNot { it.name in GenerateWasmBindingTask.HAND_WRITTEN }
        assertEquals(generated.size + 3, exports.size)
        assertTrue("_ffkmp_fmt_open_input_io" in exports, "the byte source open is missing")
        assertTrue("_ffkmp_copy_bytes" in exports, "a generated helper is missing")
        assertTrue("_kc_jvm_attach" !in exports, "the JVM-only attach must not be exported")
    }

    @Test
    fun theModuleHasEveryRuntimePieceAttachChecks() {
        // The list KiteFFmpegWeb.kt's missingRuntimeMethods checks, besides _malloc and _free.
        val web = repoRoot.resolve("kiteffmpeg/src/wasmJsMain/kotlin/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt").readText()
        val checked = Regex("""\[("[A-Za-z0-9_]+"(?:,\s*"[A-Za-z0-9_]+")*)]\.filter""").find(web)
            ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }
            ?: error("KiteFFmpegWeb.kt no longer lists the runtime pieces it checks")
        for (name in checked) {
            val present = name in LinkKiteFFmpegWasmModuleTask.RUNTIME_METHODS || name in LinkKiteFFmpegWasmModuleTask.EXTRA_EXPORTS
            assertTrue(present, "attach checks $name, which the link does not export")
        }
    }

    @Test
    fun theCommandLinksTheHelpersBeforeFFmpegAndWritesAnEsModule() {
        val command = LinkKiteFFmpegWasmModuleTask.command(
            "emcc", File("/k/libkitecodec.a"), File("/ff/lib"), File("/t/exports.json"), File("/out/kite.mjs"),
        )
        assertEquals("/k/libkitecodec.a", command[2])
        assertEquals("/ff/lib/libavfilter.a", command[3])
        assertEquals("/ff/lib/libavutil.a", command[8])
        for (flag in listOf("-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sALLOW_TABLE_GROWTH=1", "-sWASM_BIGINT=1")) {
            assertTrue(flag in command, "missing $flag")
        }
        assertEquals(listOf("-o", "/out/kite.mjs"), command.takeLast(2))
    }
}
