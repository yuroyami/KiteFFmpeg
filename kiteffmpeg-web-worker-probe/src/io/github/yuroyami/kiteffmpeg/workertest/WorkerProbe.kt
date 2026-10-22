@file:OptIn(KiteFFmpegLowLevelApi::class, kotlin.js.ExperimentalWasmJsInterop::class, ExperimentalEncodingApi::class)

package io.github.yuroyami.kiteffmpeg.workertest

import io.github.yuroyami.kiteffmpeg.AsyncMediaByteOpener
import io.github.yuroyami.kiteffmpeg.AsyncMediaByteSource
import io.github.yuroyami.kiteffmpeg.AsyncMediaRuntime
import io.github.yuroyami.kiteffmpeg.AsyncMediaSource
import io.github.yuroyami.kiteffmpeg.AsyncPacketReader
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi
import io.github.yuroyami.kiteffmpeg.KiteFFmpegWeb
import io.github.yuroyami.kiteffmpeg.WebAsyncCodecArtifacts
import io.github.yuroyami.kiteffmpeg.useAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise

/**
 * The Worker's side of the test. It says "ready", waits for one request that names a strategy, the
 * address of that strategy's codec module and the address of the HLS stream, and posts one line of
 * `name=value` pairs, or `error=` and a reason.
 */
fun main() {
    val scope = CoroutineScope(Dispatchers.Default)
    serve { strategy, moduleUrl, fixtureUrl ->
        scope.launch {
            val line = try {
                probe(strategy, moduleUrl, fixtureUrl)
            } catch (failure: Throwable) {
                "error=" + failure.stackTraceToString()
            }
            answer(line)
        }
    }
}

private suspend fun probe(strategy: String, moduleUrl: String, fixtureUrl: String): String {
    loadFixture(fixtureUrl).await<JsAny?>()
    val artifacts = if (strategy == "jspi") WebAsyncCodecArtifacts(jspiUrl = moduleUrl) else WebAsyncCodecArtifacts(asyncifyUrl = moduleUrl)
    val facts = LinkedHashMap<String, Any>()
    facts["inWorker"] = inWorker()
    KiteFFmpegWeb.loadAsyncRuntime(artifacts).useAsync { runtime ->
        finite(runtime, facts)
        live(runtime, facts)
    }
    return facts.entries.joinToString(";") { "${it.key}=${it.value}" }
}

/** The whole stream, then a seek to 2 s: every segment comes through the opener, after a wait. */
private suspend fun finite(runtime: AsyncMediaRuntime, facts: MutableMap<String, Any>) {
    val hls = Hls(live = false)
    hls.open(runtime).useAsync { source ->
        facts["streams"] = source.info.streams.size
        source.openPacketReader(source.info.streams).useAsync { reader ->
            facts["packets"] = reader.countToEnd()
            facts["opens"] = hls.opened.size
            reader.seek(2_000_000)
            facts["packetsAfterSeek"] = reader.countToEnd()
        }
    }
    facts["opensAfterSeek"] = hls.opened.size
    facts["refused"] = hls.refused.size
    facts["eachClosedOnce"] = hls.opened.all { it.closes == 1 }
}

/** A live playlist that stops growing: FFmpeg waits for it, and the Worker's event loop must run. */
private suspend fun live(runtime: AsyncMediaRuntime, facts: MutableMap<String, Any>) = coroutineScope {
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
        // The reader has the whole stream once no packet came for half a second. From then on it
        // waits inside FFmpeg for a playlist that never grows.
        withTimeout(20_000) {
            var seen = -1
            while (packets == 0 || packets != seen) {
                seen = packets
                delay(500)
            }
        }
        val before = beats
        delay(1000)
        facts["beatsInTheWait"] = beats - before
        beat.cancelAndJoin()
        facts["stillReading"] = reads.isActive
        facts["livePackets"] = packets

        val started = nowMillis()
        withTimeout(5_000) { reads.cancelAndJoin() }
        facts["unwindMillis"] = (nowMillis() - started).toInt()
        facts["readAfterCancel"] = try {
            reader.read()?.close()
            "answered"
        } catch (refused: FFmpegException) {
            refused.error::class.simpleName ?: "unnamed"
        }
    } finally {
        source.close()
    }
    facts["eachLiveClosedOnce"] = hls.opened.all { it.closes == 1 }
}

private suspend fun AsyncPacketReader.countToEnd(): Int {
    var count = 0
    while (true) {
        read()?.close() ?: return count
        count++
    }
}

/** The HLS stream of the Node check. Each answer comes after a wait, so each call parks FFmpeg. */
private class Hls(live: Boolean) {
    private val name = if (live) "live.m3u8" else "media.m3u8"
    private val playlist: ByteArray = fixture("playlist")!!.let { text ->
        if (live) text.replace("#EXT-X-ENDLIST\n", "").replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "") else text
    }.encodeToByteArray()
    val opened = ArrayList<Bytes>()
    val refused = ArrayList<String>()

    // FFmpeg loads a live playlist again through the opener, by the address the input was given.
    private val opener = AsyncMediaByteOpener { url ->
        delay(2)
        val asked = url.substringAfterLast('/')
        val bytes = if (asked == name) playlist else fixture(asked)?.let { Base64.decode(it) }
        if (bytes == null) {
            refused += url
            null
        } else {
            Bytes(bytes).also { opened += it }
        }
    }

    suspend fun open(runtime: AsyncMediaRuntime): AsyncMediaSource = runtime.open(
        Bytes(playlist), url = "https://kite.test/$name", mimeType = "application/vnd.apple.mpegurl", nestedOpener = opener,
    )
}

private class Bytes(private val bytes: ByteArray) : AsyncMediaByteSource {
    private var position = 0
    var closes = 0
        private set

    override val seekable: Boolean = true

    override suspend fun size(): Long? = bytes.size.toLong()

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        delay(1)
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        delay(1)
        this.position = position.toInt()
    }

    override suspend fun close() {
        closes++
    }
}

@JsFun(
    """(handler) => {
        self.onmessage = (event) => handler(event.data.strategy, event.data.moduleUrl, event.data.fixtureUrl);
        self.postMessage("ready");
    }""",
)
private external fun serve(handler: (String, String, String) -> Unit)

@JsFun("(line) => self.postMessage(line)")
private external fun answer(line: String)

/** Fetches the stream's JSON, as a page's byte provider fetches, and keeps it for [fixture]. */
@JsFun(
    """(url) => fetch(url).then((response) => response.json()).then((json) => {
        globalThis.kiteHlsFixture = json;
        return null;
    })""",
)
private external fun loadFixture(url: String): Promise<JsAny?>

/** The playlist for "playlist", or one of the stream's files in base64, or null. */
@JsFun(
    """(key) => {
        const fixture = globalThis.kiteHlsFixture;
        if (key === "playlist") return fixture.playlist;
        return fixture.files[key] === undefined ? null : fixture.files[key];
    }""",
)
private external fun fixture(key: String): String?

@JsFun("() => typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope")
private external fun inWorker(): Boolean

@JsFun("() => performance.now()")
private external fun nowMillis(): Double
