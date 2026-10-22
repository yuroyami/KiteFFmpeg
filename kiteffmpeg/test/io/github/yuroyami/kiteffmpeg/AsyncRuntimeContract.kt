@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Bytes that answer every call after a real wait, as a network does. It counts what the runtime
 * asked of it, so a test can tell that it was closed once.
 *
 * @param chunk the most bytes one read answers. A small chunk keeps FFmpeg reading after the open.
 */
internal class AsyncBytes(
    private val bytes: ByteArray,
    private val waitMillis: Long = 1,
    private val chunk: Int = Int.MAX_VALUE,
) : AsyncMediaByteSource {
    private var position = 0
    var reads = 0
        private set
    var closes = 0
        private set

    /** Every array a read was handed, in order. */
    val arrays = ArrayList<ByteArray>()

    /** Runs at the start of each read, with the count of reads so far, this one included. */
    var beforeRead: (suspend (Int) -> Unit)? = null

    /** Runs at the end of each read that was cancelled, with the array it was handed. */
    var afterCancel: ((ByteArray) -> Unit)? = null

    override val seekable: Boolean = true

    override suspend fun size(): Long? = bytes.size.toLong()

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        reads++
        arrays += into
        try {
            beforeRead?.invoke(reads)
            delay(waitMillis)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            afterCancel?.invoke(into)
            throw cancelled
        }
        if (position >= bytes.size) return -1
        val count = minOf(length, chunk, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        delay(waitMillis)
        this.position = position.toInt()
    }

    override suspend fun close() {
        closes++
    }
}

/** The same bytes for the synchronous API, which the asynchronous results are compared with. */
internal class SyncBytes(private val bytes: ByteArray) : MediaByteSource {
    private var position = 0
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean = true

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    override fun seek(position: Long) {
        this.position = position.toInt()
    }

    override fun close(): Unit = Unit
}

private fun fact(stream: Int, pts: Long, dts: Long, size: Int, key: Boolean, bytes: ByteArray): String =
    "$stream/$pts/$dts/$size/$key/${bytes.contentHashCode()}"

/** Every packet from the read position on, through the synchronous API. */
internal fun PacketReader.facts(): List<String> = buildList {
    while (true) {
        val packet = read() ?: break
        try {
            add(fact(packet.streamIndex, packet.pts, packet.dts, packet.sizeBytes, packet.isKeyframe, packet.copyBytes()))
        } finally {
            packet.close()
        }
    }
}

/** Every packet from the read position on, through the asynchronous API. */
internal suspend fun AsyncPacketReader.facts(): List<String> {
    val facts = ArrayList<String>()
    while (true) {
        val packet = read() ?: break
        packet.useAsync {
            facts += fact(it.streamIndex, it.pts, it.dts, it.sizeBytes, it.isKeyframe, it.copyBytes())
        }
    }
    return facts
}

internal fun syncPacketFacts(bytes: ByteArray, seekMicros: Long? = null): List<String> {
    val source = MediaSource.open(SyncBytes(bytes))
    try {
        val reader = source.openPacketReader(source.streams)
        try {
            if (seekMicros != null) reader.seek(seekMicros, SeekDirection.Backward, null)
            return reader.facts()
        } finally {
            reader.close()
        }
    } finally {
        source.close()
    }
}

internal suspend fun AsyncMediaSource.packetFacts(seekMicros: Long? = null): List<String> =
    openPacketReader(info.streams).useAsync { reader ->
        if (seekMicros != null) reader.seek(seekMicros, SeekDirection.Backward, null)
        reader.facts()
    }

/**
 * What an asynchronous runtime owes its caller, on every backend that has one. Each test runs
 * once for each runtime [runtimes] names, against the real FFmpeg of that backend.
 */
abstract class AsyncRuntimeContract {

    /** The runtimes of this backend, by name. An empty list skips every test. */
    internal abstract suspend fun runtimes(): List<Pair<String, suspend () -> AsyncMediaRuntime>>

    /** False when the synchronous API of this backend cannot run, which also skips every test. */
    protected open suspend fun backendReady(): Boolean = true

    private val clip: ByteArray get() = DecodeContractMedia.bytes

    /**
     * Runs [body] for each runtime, off the test's virtual clock: the byte sources wait in real
     * time, and a virtual timeout would fire while they do.
     */
    internal fun eachRuntime(body: suspend CoroutineScope.(name: String, runtime: AsyncMediaRuntime) -> Unit): TestResult = runTest {
        if (!backendReady()) return@runTest
        withContext(Dispatchers.Default) {
            for ((name, create) in runtimes()) {
                val runtime = create()
                try {
                    withTimeout(30_000) { body(name, runtime) }
                } finally {
                    runtime.close()
                }
            }
        }
    }

    @Test
    fun delayedReadsGiveThePacketsOfTheSynchronousApi() = eachRuntime { name, runtime ->
        val expected = syncPacketFacts(clip)
        val bytes = AsyncBytes(clip, chunk = 512)
        val facts = runtime.open(bytes).useAsync { source ->
            assertEquals(2, source.info.streams.size, "$name: streams")
            source.packetFacts()
        }
        assertTrue(expected.size > 20, "the clip has ${expected.size} packets")
        assertEquals(expected, facts, "$name: packets")
        assertTrue(bytes.reads > 10, "$name: only ${bytes.reads} reads waited")
        assertEquals(1, bytes.closes, "$name: closes of the byte source")
    }

    @Test
    fun delayedReadsDecodeTheFramesOfTheSynchronousApi() = eachRuntime { name, runtime ->
        val expected = MediaSource.open(SyncBytes(clip)).let { source ->
            try {
                source.decodedFrames(source.primaryVideo!!).toList().map { frame ->
                    try {
                        "${frame.ptsMicros}/${frame.copyPlanesToByteArray().contentHashCode()}"
                    } finally {
                        frame.close()
                    }
                }
            } finally {
                source.close()
            }
        }
        val facts = runtime.open(AsyncBytes(clip, chunk = 512)).useAsync { source ->
            val video = assertNotNull(source.info.primaryVideo, "$name: no video stream")
            source.decodedFrames(video).toList().map { frame ->
                frame.useAsync { "${it.ptsMicros}/${it.copyPlanesToByteArray().contentHashCode()}" }
            }
        }
        assertEquals(15, expected.size, "frames of the clip")
        assertEquals(expected, facts, "$name: decoded frames")
    }

    @Test
    fun aSeekLandsWhereTheSynchronousSeekLands() = eachRuntime { name, runtime ->
        val all = syncPacketFacts(clip)
        val expected = syncPacketFacts(clip, seekMicros = 300_000)
        val facts = runtime.open(AsyncBytes(clip, chunk = 512)).useAsync { it.packetFacts(seekMicros = 300_000) }
        assertTrue(expected.size in 1 until all.size, "the seek skipped nothing: ${expected.size} of ${all.size} packets")
        assertEquals(expected, facts, "$name: packets after the seek")
    }

    @Test
    fun aCancelDuringAReadEndsTheSourceAndClosesTheByteSourceOnce() = eachRuntime { name, runtime ->
        val bytes = AsyncBytes(clip, chunk = 256)
        val source = runtime.open(bytes)
        try {
            val reader = source.openPacketReader(source.info.streams)
            val waiting = CompletableDeferred<Unit>()
            var written: ByteArray? = null
            bytes.beforeRead = {
                waiting.complete(Unit)
                awaitCancellation()
            }
            // A byte source that writes after its call was cancelled, which it must not do.
            bytes.afterCancel = { array ->
                array.fill(0x55)
                written = array
            }
            val reads = launch {
                while (true) reader.read()?.close() ?: break
            }
            withTimeout(10_000) { waiting.await() }
            withTimeout(10_000) { reads.cancelAndJoin() }
            assertTrue(reads.isCancelled, "$name: the read was not cancelled")

            // The source is over. Nothing asks the byte source again.
            val after = bytes.reads
            val refused = assertFailsWith<FFmpegException>("$name: a read after the cancel") { reader.read() }
            assertIs<FFmpegError.Interrupted>(refused.error, "$name")
            assertEquals(after, bytes.reads, "$name: reads after the cancel")
            // Each read had an array of its own, so the late write reached nothing FFmpeg reads.
            assertNotNull(written, "$name: the cancelled read never ran its late write")
            assertEquals(1, bytes.arrays.count { it === written }, "$name: reads that shared the late array")
        } finally {
            source.close()
        }
        assertEquals(1, bytes.closes, "$name: closes of the byte source")

        // The runtime still works.
        val again = runtime.open(AsyncBytes(clip)).useAsync { it.packetFacts() }
        assertEquals(syncPacketFacts(clip), again, "$name: packets of a new source after the cancel")
    }

    @Test
    fun aCancelWhileWaitingForTheLaneTouchesNothing() = eachRuntime { name, runtime ->
        val held = AsyncBytes(clip, chunk = 256)
        val other = AsyncBytes(clip, chunk = 256)
        runtime.open(held).useAsync { first ->
            runtime.open(other).useAsync { second ->
                val firstReader = first.openPacketReader(first.info.streams)
                val secondReader = second.openPacketReader(second.info.streams)
                val waiting = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                held.beforeRead = {
                    waiting.complete(Unit)
                    release.await()
                }
                val holder = launch { firstReader.facts() }
                withTimeout(10_000) { waiting.await() }

                // The lane is held by the first source. This read waits for it, and is cancelled there.
                val before = other.reads
                val queued = launch { secondReader.read()?.close() }
                delay(50)
                withTimeout(10_000) { queued.cancelAndJoin() }
                assertEquals(before, other.reads, "$name: the cancelled read reached its byte source")

                held.beforeRead = null
                release.complete(Unit)
                withTimeout(10_000) { holder.join() }
                assertTrue(!holder.isCancelled, "$name: the read that held the lane failed")

                // The second source was never entered, so it is not over.
                assertTrue(secondReader.facts().isNotEmpty(), "$name: the second source reads no more")
                assertEquals(0, other.closes, "$name: the second byte source was closed")
            }
        }
        assertEquals(1, held.closes, "$name: closes of the first byte source")
        assertEquals(1, other.closes, "$name: closes of the second byte source")
    }

    @Test
    fun aByteSourceThatThrowsIsTheCauseOfTheFailure() = eachRuntime { name, runtime ->
        // In the open. Every read fails, so a read FFmpeg tries again fails again.
        val thrown = IllegalStateException("the network went away")
        val refused = AsyncBytes(clip, chunk = 256)
        refused.beforeRead = { throw thrown }
        val failure = assertFailsWith<FFmpegException>("$name: the open") { runtime.open(refused) }
        assertSame(thrown, failure.cause, "$name: the cause of ${failure.error}")
        assertEquals(1, refused.closes, "$name: closes of the byte source the open refused")

        // In a read, after an open that worked.
        val later = IllegalStateException("the network went away later")
        val bytes = AsyncBytes(clip, chunk = 256)
        runtime.open(bytes).useAsync { source ->
            source.openPacketReader(source.info.streams).useAsync { reader ->
                bytes.beforeRead = { throw later }
                val read = assertFailsWith<FFmpegException>("$name: the reads") { reader.facts() }
                assertSame(later, read.cause, "$name: the cause of ${read.error}")
            }
        }
        assertEquals(1, bytes.closes, "$name: closes of the byte source")
    }

    @Test
    fun twoSourcesReadInTurnsOnOneRuntime() = eachRuntime { name, runtime ->
        val expected = syncPacketFacts(clip)
        runtime.open(AsyncBytes(clip, chunk = 256)).useAsync { first ->
            runtime.open(AsyncBytes(clip, chunk = 512)).useAsync { second ->
                val one = first.openPacketReader(first.info.streams)
                val two = second.openPacketReader(second.info.streams)
                var fromOne = 0
                var fromTwo = 0
                while (true) {
                    val a = one.read()
                    val b = two.read()
                    a?.close()
                    b?.close()
                    if (a != null) fromOne++
                    if (b != null) fromTwo++
                    if (a == null && b == null) break
                }
                assertEquals(expected.size, fromOne, "$name: packets of the first source")
                assertEquals(expected.size, fromTwo, "$name: packets of the second source")
            }
        }
    }

    @Test
    fun oneByteSourceObjectServesOneOpen() = eachRuntime { name, runtime ->
        val bytes = AsyncBytes(clip)
        runtime.open(bytes).useAsync {
            assertFailsWith<IllegalArgumentException>("$name: the second open of one object") { runtime.open(bytes) }
            assertEquals(0, bytes.closes, "$name: the refused object was closed")
        }
        assertEquals(1, bytes.closes, "$name: closes of the byte source")
    }
}
