@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg.workertest

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The asynchronous runtime in a Web Worker (#183), once on the module that parks through
 * JavaScript Promise Integration and once on the Asyncify one. The Worker is this module's main
 * binary. It reads a real HLS stream with the real FFmpeg and posts what it saw.
 *
 * An engine without Promise Integration skips the `kite-jspi` half and says so.
 */
class AsyncRuntimeInWorkerTest {

    @Test
    fun aRuntimeInAWorkerReadsAnHlsStreamThroughItsOpener() = eachStrategy { name, facts ->
        assertEquals("true", facts["inWorker"], "$name: the runtime ran outside a Worker")
        assertEquals("1", facts["streams"], "$name: streams")
        assertEquals("40", facts["packets"], "$name: packets")
        assertEquals("5", facts["opens"], "$name: nested opens, for an initialization and four segments")
        assertEquals("20", facts["packetsAfterSeek"], "$name: packets after a seek to 2 s")
        assertTrue(facts.getValue("opensAfterSeek").toInt() > 5, "$name: the seek opened no segment again")
        assertEquals("0", facts["refused"], "$name: refused addresses")
        assertEquals("true", facts["eachClosedOnce"], "$name: closes of each nested source")
    }

    @Test
    fun aLiveStreamThatStopsGrowingLeavesTheWorkerItsEventLoopAndUnwindsOnACancel() = eachStrategy { name, facts ->
        assertEquals("true", facts["stillReading"], "$name: the live read ended by itself")
        assertTrue(facts.getValue("livePackets").toInt() in 1..40, "$name: ${facts["livePackets"]} packets before the wait")
        // A sleep that held the thread allows at most ten beats a second, one between two of
        // FFmpeg's 100 ms sleeps. A slow Worker still runs about forty.
        val beats = facts.getValue("beatsInTheWait").toInt()
        assertTrue(beats >= 20, "$name: another coroutine ran $beats times in 1 s of the wait")
        val unwind = facts.getValue("unwindMillis").toInt()
        assertTrue(unwind < 1000, "$name: the cancelled wait took $unwind ms to unwind")
        assertEquals("Interrupted", facts["readAfterCancel"], "$name: a read after the cancel")
        assertEquals("true", facts["eachLiveClosedOnce"], "$name: closes of each nested source")
    }

    /** Runs the Worker once for each strategy this engine has, and hands [check] what it posted. */
    private fun eachStrategy(check: (String, Map<String, String>) -> Unit) = runTest(timeout = 3.minutes) {
        val strategies = ArrayList<String>()
        if (served("jspi") != null && hasPromiseIntegration()) strategies += "jspi"
        if (served("asyncify") != null) strategies += "asyncify"
        if (served("jspi") == null || served("asyncify") == null) {
            check(!required()) {
                "KITEFFMPEG_WEB_MODULE_REQUIRED is true, and no kite-jspi.mjs and kite-asyncify.mjs are linked. " +
                    "Run :kiteffmpeg:linkKiteFFmpegAsyncWasmModules first."
            }
            println("skipped: no linked asynchronous modules, so the Worker reads nothing")
        } else if ("jspi" !in strategies) {
            println("skipped: this engine has no WebAssembly.Suspending, so kite-jspi cannot run here")
        }
        for (name in strategies) {
            val line = runInWorker(served("worker")!!, name, served(name)!!, served("hlsFixture")!!, 90_000).await<JsString>().toString()
            check(!line.startsWith("error=")) { "$name: the Worker failed: ${line.removePrefix("error=")}" }
            check(name, line.split(';').associate { it.substringBefore('=') to it.substringAfter('=') })
        }
    }
}

/** The address karma.config.d/worker.js serves [key] at, or null when the build has no such file. */
@JsFun(
    """(key) => {
        const at = globalThis.__karma__.config.kiteWorker[key];
        return at ? new URL(at, globalThis.location.href).href : null;
    }""",
)
private external fun served(key: String): String?

@JsFun("() => !!globalThis.__karma__.config.kiteWorker.required")
private external fun required(): Boolean

@JsFun("() => typeof WebAssembly.Suspending === 'function'")
private external fun hasPromiseIntegration(): Boolean

/**
 * Starts the Worker, sends it one request when it says "ready", and resolves with its answer. The
 * page ends a Worker that gives no answer in [limitMillis], so a Worker whose thread is held
 * cannot hold the test.
 */
@JsFun(
    """(workerUrl, strategy, moduleUrl, fixtureUrl, limitMillis) => new Promise((resolve) => {
        const worker = new Worker(workerUrl, { type: "module" });
        const end = (line) => {
            clearTimeout(timer);
            worker.terminate();
            resolve(line);
        };
        const timer = setTimeout(() => end("error=no answer in " + limitMillis + " ms"), limitMillis);
        worker.onerror = (event) => end("error=" + (event.message || "the Worker did not start"));
        worker.onmessage = (event) => {
            if (event.data === "ready") worker.postMessage({ strategy, moduleUrl, fixtureUrl });
            else end(String(event.data));
        };
    })""",
)
private external fun runInWorker(workerUrl: String, strategy: String, moduleUrl: String, fixtureUrl: String, limitMillis: Int): Promise<JsString>
