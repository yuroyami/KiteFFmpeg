@file:OptIn(KiteFFmpegLowLevelApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [AsyncRuntimeContract] in a browser engine, once on the module that parks through JavaScript
 * Promise Integration and once on the Asyncify one, and what only the web has: its own codec
 * module for each runtime, beside the page's.
 *
 * The build points KITEFFMPEG_WEB_ASYNC_MODULES at the folder linkKiteFFmpegAsyncWasmModules
 * wrote. An engine without Promise Integration skips the `kite-jspi` half and says so.
 */
class WebAsyncRuntimeTest : AsyncRuntimeContract() {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    // The contract compares with the synchronous API, which runs on the page's module.
    override suspend fun backendReady(): Boolean = useLinkedCodecModule()

    override suspend fun runtimes(): List<Pair<String, suspend () -> AsyncMediaRuntime>> {
        val jspi = asyncModuleUrl("kite-jspi.mjs")
        val asyncify = asyncModuleUrl("kite-asyncify.mjs")
        if (jspi == null || asyncify == null) {
            check(!asyncModulesRequired()) {
                "KITEFFMPEG_WEB_MODULE_REQUIRED is true, and KITEFFMPEG_WEB_ASYNC_MODULES holds no kite-jspi.mjs " +
                    "and kite-asyncify.mjs. Run :kiteffmpeg:linkKiteFFmpegAsyncWasmModules first."
            }
            println("skipped: no linked asynchronous modules, so this test reads nothing")
            return emptyList()
        }
        val list = ArrayList<Pair<String, suspend () -> AsyncMediaRuntime>>()
        if (hasPromiseIntegration()) {
            list += "jspi" to { KiteFFmpegWeb.loadAsyncRuntime(WebAsyncCodecArtifacts(jspiUrl = jspi)) }
        } else {
            println("skipped: this engine has no WebAssembly.Suspending, so kite-jspi cannot run here")
        }
        list += "asyncify" to { KiteFFmpegWeb.loadAsyncRuntime(WebAsyncCodecArtifacts(asyncifyUrl = asyncify)) }
        return list
    }

    /** The HLS stream of the Node check, served as a page would serve it. */
    private class Hls(private val live: Boolean) {
        val name = if (live) "live.m3u8" else "media.m3u8"
        val playlist: ByteArray = hlsFixture("playlist")!!.let { text ->
            if (live) text.replace("#EXT-X-ENDLIST\n", "").replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "") else text
        }.encodeToByteArray()
        val opened = ArrayList<AsyncBytes>()
        val refused = ArrayList<String>()

        fun root() = AsyncBytes(playlist)

        // FFmpeg loads a live playlist again through the opener, by the address the input was given.
        val opener = AsyncMediaByteOpener { url ->
            delay(2)
            val asked = url.substringAfterLast('/')
            val bytes = if (asked == name) playlist else hlsFixture(asked)?.let(::decodeBase64)
            if (bytes == null) {
                refused += url
                null
            } else {
                AsyncBytes(bytes).also { opened += it }
            }
        }

        suspend fun open(runtime: AsyncMediaRuntime): AsyncMediaSource = runtime.open(
            root(), url = "https://kite.test/$name", mimeType = "application/vnd.apple.mpegurl", nestedOpener = opener,
        )
    }

    @Test
    fun anHlsStreamReadsItsSegmentsThroughTheOpener() = eachRuntime { name, runtime ->
        val hls = Hls(live = false)
        hls.open(runtime).useAsync { source ->
            assertEquals(1, source.info.streams.size, "$name: streams")
            source.openPacketReader(source.info.streams).useAsync { reader ->
                assertEquals(40, reader.facts().size, "$name: packets")
                assertEquals(5, hls.opened.size, "$name: nested opens, for an initialization and four segments")
                reader.seek(2_000_000)
                assertEquals(20, reader.facts().size, "$name: packets after a seek to 2 s")
            }
        }
        assertTrue(hls.refused.isEmpty(), "$name: refused ${hls.refused}")
        assertTrue(hls.opened.size > 5, "$name: the seek opened no segment again")
        assertEquals(List(hls.opened.size) { 1 }, hls.opened.map { it.closes }, "$name: closes of each nested source")
    }

    @Test
    fun aLiveStreamThatStopsGrowingLetsOtherCoroutinesRunAndUnwindsOnACancel() = eachRuntime { name, runtime ->
        val hls = Hls(live = true)
        val source = hls.open(runtime)
        try {
            val reader = source.openPacketReader(source.info.streams)
            var packets = 0
            var beats = 0
            val reads = launch {
                while (true) {
                    reader.read()?.close() ?: break
                    packets++
                }
            }
            val beat = launch {
                while (isActive) {
                    delay(5)
                    beats++
                }
            }
            // Longer than the stream is, so the reader is now waiting for a playlist that never grows.
            delay(1500)
            beat.cancelAndJoin()
            assertTrue(reads.isActive, "$name: the live read ended by itself after $packets packets")
            assertTrue(packets in 1..40, "$name: $packets packets before the wait")
            assertTrue(beats >= 100, "$name: another coroutine ran $beats times in 1.5 s")

            val started = nowMillis()
            withTimeout(5_000) { reads.cancelAndJoin() }
            val took = nowMillis() - started
            assertTrue(took < 1000.0, "$name: the cancelled wait took $took ms to unwind")

            val refused = assertFailsWith<FFmpegException>("$name: a read after the cancel") { reader.read() }
            assertIs<FFmpegError.Interrupted>(refused.error, "$name")
        } finally {
            source.close()
        }
        assertEquals(List(hls.opened.size) { 1 }, hls.opened.map { it.closes }, "$name: closes of each nested source")
    }

    @Test
    fun thePlainModuleIsRefused() = eachRuntime { name, _ ->
        val plain = asyncModuleUrl("../kite-web/kite.mjs")!!
        val artifacts = if (name == "jspi") WebAsyncCodecArtifacts(jspiUrl = plain) else WebAsyncCodecArtifacts(asyncifyUrl = plain)
        val failure = assertFailsWith<FFmpegException>("$name: a runtime on kite.mjs") { KiteFFmpegWeb.loadAsyncRuntime(artifacts) }
        assertIs<FFmpegError.Unsupported>(failure.error, "$name")
        assertTrue("kite-jspi.mjs" in failure.error.message, failure.error.message)
    }

    @Test
    fun thePageModuleDecodesWhileARuntimeWaits() = eachRuntime { name, runtime ->
        val clip = DecodeContractMedia.bytes
        val page = KiteFFmpegWeb.module
        val expected = syncPacketFacts(clip)
        val bytes = AsyncBytes(clip, chunk = 256)
        runtime.open(bytes).useAsync { source ->
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            bytes.beforeRead = {
                waiting.complete(Unit)
                release.await()
            }
            var facts: List<String>? = null
            val reads = launch { facts = source.packetFacts() }
            withTimeout(10_000) { waiting.await() }

            // The runtime's module is parked in a read now. The page's module is another one.
            assertEquals(expected, syncPacketFacts(clip), "$name: the page's module while the runtime waits")
            assertSame(page, KiteFFmpegWeb.module, "$name: the runtime replaced the page's module")

            bytes.beforeRead = null
            release.complete(Unit)
            withTimeout(10_000) { reads.join() }
            assertEquals(expected, facts, "$name: the runtime's packets")
        }
        assertEquals(FFmpeg.identity.describe(), runtime.identity.describe(), "$name: the two modules are one FFmpeg")
    }

    @Test
    fun aModuleHasOneOwner() = eachRuntime { name, _ ->
        val file = if (name == "jspi") "kite-jspi.mjs" else "kite-asyncify.mjs"
        val module = instantiate(asyncModuleUrl(file)!!).await<JsAny>()
        val runtime = KiteFFmpegWeb.attachAsyncRuntime(module)
        try {
            assertFailsWith<IllegalStateException>("$name: a second runtime on one module") { KiteFFmpegWeb.attachAsyncRuntime(module) }
            assertFailsWith<IllegalStateException>("$name: the page's module from a runtime's") { KiteFFmpegWeb.attach(module) }
            val facts = runtime.open(AsyncBytes(DecodeContractMedia.bytes)).useAsync { it.packetFacts() }
            assertEquals(syncPacketFacts(DecodeContractMedia.bytes), facts, "$name: packets of the attached module")
        } finally {
            runtime.close()
        }
        // A closed runtime still owns its module.
        assertFailsWith<IllegalStateException>("$name: a runtime on a closed runtime's module") { KiteFFmpegWeb.attachAsyncRuntime(module) }
    }
}

/**
 * A URL for [name] among the linked asynchronous modules, or null when it is not there: a file URL
 * in Node, and in a browser the address karma.config.d/modules.js serves it at.
 */
@JsFun(
    """(name) => {
        const served = globalThis.__karma__ && globalThis.__karma__.config ? globalThis.__karma__.config.kiteModules : undefined;
        if (served) {
            const at = name === "kite-jspi.mjs" ? served.jspi : name === "kite-asyncify.mjs" ? served.asyncify : served.module;
            return at ? new URL(at, globalThis.location.href).href : null;
        }
        const p = globalThis.process;
        const folder = p && p.env ? p.env.KITEFFMPEG_WEB_ASYNC_MODULES : undefined;
        if (!folder || !p.getBuiltinModule) return null;
        const file = p.getBuiltinModule("node:path").join(folder, name);
        if (!p.getBuiltinModule("node:fs").existsSync(file)) return null;
        return p.getBuiltinModule("node:url").pathToFileURL(file).href;
    }""",
)
private external fun asyncModuleUrl(name: String): String?

@JsFun(
    """() => {
        const served = globalThis.__karma__ && globalThis.__karma__.config ? globalThis.__karma__.config.kiteModules : undefined;
        if (served) return !!served.required;
        const p = globalThis.process;
        return !!(p && p.env && p.env.KITEFFMPEG_WEB_MODULE_REQUIRED === "true");
    }""",
)
private external fun asyncModulesRequired(): Boolean

/** The playlist of the Node check's HLS stream for "playlist", or one of its files in base64, or null. */
@JsFun(
    """(key) => {
        const p = globalThis.process;
        const served = globalThis.__karma__ && globalThis.__karma__.config ? globalThis.__karma__.config.kiteModules : undefined;
        if (!globalThis.kiteHlsFixture && served) {
            // A page has no file to read, and this lookup cannot wait, so the request is a synchronous one.
            const request = new XMLHttpRequest();
            request.open("GET", served.hlsFixture, false);
            request.send();
            globalThis.kiteHlsFixture = JSON.parse(request.responseText);
        }
        if (!globalThis.kiteHlsFixture) {
            const file = p && p.env ? p.env.KITEFFMPEG_WEB_HLS_FIXTURE : undefined;
            globalThis.kiteHlsFixture = JSON.parse(p.getBuiltinModule("node:fs").readFileSync(file, "utf8"));
        }
        const fixture = globalThis.kiteHlsFixture;
        if (key === "playlist") return fixture.playlist;
        return fixture.files[key] === undefined ? null : fixture.files[key];
    }""",
)
private external fun hlsFixture(key: String): String?

// webpack bundles the browser tests and would resolve a plain import() itself, so it is told not to.
@JsFun("(url) => import(/* webpackIgnore: true */ url).then((loaded) => loaded.default({ printErr: () => {} }))")
private external fun instantiate(url: String): Promise<JsAny>

@JsFun("() => performance.now()")
private external fun nowMillis(): Double
