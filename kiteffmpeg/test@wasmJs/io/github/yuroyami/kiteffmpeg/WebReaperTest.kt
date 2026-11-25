@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_alloc
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ten frames and ten packets, half closed and half dropped (#242). The registry frees the dropped
 * ones once the collector finds them, each with a warning, and frees none twice. It counts the
 * frees in its own fake module, because the collector also reaps what other tests left unclosed.
 *
 * It needs a collection it can force: `node --expose-gc`, or Chrome with `--js-flags=--expose-gc`,
 * as `scripts/web-tests/run.py` starts them. Without that it passes without checking anything.
 */
class WebReaperTest {
    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() {
        FFmpeg.setLogSink(FFmpegLogLevel.Warning, null)
        forgetCodecModule()
    }

    @Test
    fun aDroppedFrameOrPacketIsFreedWithAWarning() = runTest {
        if (!canForceCollection()) {
            println("WebReaperTest: no gc() on this engine, nothing checked")
            return@runTest
        }
        val module = fakeDecodeCodecModule()
        countPacketFrees(module)
        useCodecModule(module)
        val lines = mutableListOf<String>()
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { _, component, message ->
            if (component == "kiteffmpeg") lines += message
        }
        val before = WebReaper.leakCount()
        makeAndDrop(module, 10)
        // Counted in this module only: objects other tests left unclosed are reaped in theirs.
        suspend fun collect(rounds: Int) = repeat(rounds) {
            forceCollection()
            nextTask().await<JsAny?>()
        }
        var rounds = 0
        while ((fakeFrameBalance(module) != 0 || packetFrees(module) < 10) && rounds < 100) {
            collect(1)
            rounds++
        }
        assertEquals(0, fakeFrameBalance(module), "frames left after $rounds rounds")
        assertEquals(10, packetFrees(module), "packets freed after $rounds rounds")
        collect(3)
        assertEquals(0, fakeFrameBalance(module), "a frame was freed twice")
        assertEquals(10, packetFrees(module), "a packet was freed twice")
        assertTrue(WebReaper.leakCount() - before >= 10, "the leak count did not grow by the 10 dropped objects")
        assertTrue(lines.count { "A Frame was collected" in it } >= 5, lines.toString())
        assertTrue(lines.count { "A Packet was collected" in it } >= 5, lines.toString())
    }

    /** Not inlined into the test, so no local keeps a dropped owner reachable. */
    private fun makeAndDrop(module: JsAny, count: Int) {
        repeat(count) { i ->
            val frame = Frame(ffkmp_frame_alloc(module), 0, MediaType.Video, Rational(1, 1000))
            val packet = Packet(wasmAlloc(module, 8), Rational(1, 1000))
            if (i % 2 == 0) {
                frame.close()
                frame.close()
                packet.close()
            }
        }
    }
}

/** Wraps the fake module's packet free with a count that [packetFrees] reads. */
@JsFun("(m) => { const free = m._ffkmp_packet_free; m.__packetFrees = 0; m._ffkmp_packet_free = (p) => { m.__packetFrees++; free(p); }; }")
private external fun countPacketFrees(module: JsAny)

@JsFun("(m) => m.__packetFrees")
private external fun packetFrees(module: JsAny): Int

@JsFun("() => typeof globalThis.gc === 'function'")
private external fun canForceCollection(): Boolean

@JsFun("() => { globalThis.gc(); }")
private external fun forceCollection()

/** A real macrotask, because finalization callbacks never run inside the current one. */
@JsFun("() => new Promise((resolve) => setTimeout(resolve, 10))")
private external fun nextTask(): Promise<JsAny?>
