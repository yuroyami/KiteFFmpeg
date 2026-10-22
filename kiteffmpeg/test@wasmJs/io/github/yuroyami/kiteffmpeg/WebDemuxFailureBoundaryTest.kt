package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.OpenerLayout
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Exercises the public demux operation around real registered byte-source callbacks. */
class WebDemuxFailureBoundaryTest {
    @BeforeTest fun start() = forgetCodecModule()
    @AfterTest fun finish() = forgetCodecModule()

    private class Bytes : MediaByteSource {
        var sizeFailure: Throwable? = null
        var nextReadFailure: Throwable? = null
        var closes = 0
        override val size: Long get() = sizeFailure?.let { throw it } ?: 32L
        override val seekable: Boolean get() = true
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            val failure = nextReadFailure
            nextReadFailure = null
            if (failure != null) throw failure
            check(closes == 0)
            into[offset] = 17
            return 1
        }
        override fun seek(position: Long) = Unit
        override fun close() { closes++ }
    }

    private class Fixture : AutoCloseable {
        val root = Bytes()
        val children = listOf(Bytes(), Bytes())
        val module: JsAny
        var action: (String) -> Int = { 0 }
        private var media: MediaSource? = null

        init {
            forgetCodecModule()
            WebIoBridge.readOnDemand = true
            module = fakePacketReaderCodecModule()
            withNestedIo(module)
            installFailureBoundaryProbe(
                module, OpenerLayout.openFn, OpenerLayout.readFn, OpenerLayout.seekFn,
                OpenerLayout.closeFn,
            ) { phase -> action(phase) }
            useCodecModule(module)
        }

        fun source(index: Int): Bytes = if (index == -1) root else children[index]

        fun open(): MediaSource = MediaSource.open(
            root,
            url = "https://media.example/index.m3u8",
            nestedOpener = { url -> children[url.substringAfterLast('/').toInt()] },
        ).also { media = it }

        override fun close() {
            try { media?.close() } finally { releaseFailureBoundaryProbe(module) }
            assertEquals(1, root.closes)
            children.forEach { assertEquals(1, it.closes) }
        }
    }

    @Test fun aRecoveredSizeFailureDoesNotCauseALaterReadSeekPauseOrResumeError() {
        for (index in listOf(-1, 0)) {
            for (next in listOf("read", "seek", "pause", "resume")) {
                Fixture().use { fixture ->
                    val media = fixture.open()
                    if (next == "resume") assertEquals(true, media.pause())
                    val recovered = IllegalStateException("recovered size query")
                    fixture.source(index).sizeFailure = recovered
                    fixture.action = { phase ->
                        if (phase == "read") {
                            // helpers_format.c uses the initial size after a negative size callback.
                            assertEquals(32L, failureBoundarySizeWithFallback(fixture.module, index).toLong())
                        }
                        0
                    }
                    media.openPacketReader(media.streams).use { reader ->
                        assertNotNull(reader.read()).close()
                    }
                    fixture.action = { phase -> if (phase == next) -5 else 0 }
                    val failure = assertFailsWith<FFmpegException> {
                        when (next) {
                            "read" -> media.openPacketReader(media.streams).use { it.read()?.close() }
                            "seek" -> media.openPacketReader(media.streams).use { it.seek(0, SeekDirection.Backward, null) }
                            "pause" -> media.pause()
                            "resume" -> media.resume()
                        }
                    }
                    assertIs<FFmpegError.Io>(failure.error)
                    assertNull(failure.cause, "$next inherited a recovered callback failure from a previous read")
                }
            }
        }
    }

    @Test fun aRecoveredOpenFailureDoesNotCauseAnUnrelatedStreamProbeFailure() {
        for (index in listOf(-1, 0)) {
            Fixture().use { fixture ->
                fixture.action = { phase ->
                    when (phase) {
                        "open" -> {
                            fixture.source(index).sizeFailure = IllegalStateException("recovered while opening")
                            assertEquals(32L, failureBoundarySizeWithFallback(fixture.module, index).toLong())
                            0
                        }
                        "probe" -> -5
                        else -> 0
                    }
                }
                val failure = assertFailsWith<FFmpegException> { fixture.open() }
                assertNull(failure.cause, "stream probing inherited a recovered open failure")
            }
        }
    }

    @Test fun aRecoveredStreamProbeFailureDoesNotCauseTheFirstPacketFailure() {
        for (index in listOf(-1, 0)) {
            Fixture().use { fixture ->
                fixture.action = { phase ->
                    when (phase) {
                        "probe" -> {
                            fixture.source(index).sizeFailure = IllegalStateException("recovered while probing")
                            assertEquals(32L, failureBoundarySizeWithFallback(fixture.module, index).toLong())
                            0
                        }
                        "read" -> -5
                        else -> 0
                    }
                }
                val media = fixture.open()
                val failure = assertFailsWith<FFmpegException> {
                    media.openPacketReader(media.streams).use { it.read()?.close() }
                }
                assertNull(failure.cause, "the first packet inherited a recovered stream-probe failure")
            }
        }
    }

    @Test fun aSuccessfulCallbackCannotEraseAFailureInTheSameReadOrSeek() {
        for (index in listOf(-1, 0)) {
            for (operation in listOf("read", "seek")) {
                Fixture().use { fixture ->
                    val media = fixture.open()
                    val thrown = IllegalStateException("first input failed")
                    fixture.source(index).nextReadFailure = thrown
                    fixture.action = { phase ->
                        if (phase == operation) {
                            assertEquals(-2, failureBoundaryRead(fixture.module, index))
                            // Root: a later successful read. Nested: a different successful child.
                            assertEquals(1, failureBoundaryRead(fixture.module, if (index == -1) -1 else 1))
                            -5
                        } else 0
                    }
                    val failure = assertFailsWith<FFmpegException> {
                        media.openPacketReader(media.streams).use { reader ->
                            if (operation == "read") reader.read()?.close()
                            else reader.seek(0, SeekDirection.Backward, null)
                        }
                    }
                    assertSame(thrown, failure.cause, "$operation lost the original callback failure")
                }
            }
        }
    }

    @Test fun choosingTheRootCauseCannotLeaveAChildCauseForTheNextOperation() {
        Fixture().use { fixture ->
            val media = fixture.open()
            val rootFailure = IllegalStateException("root input failed")
            fixture.root.nextReadFailure = rootFailure
            fixture.children[0].nextReadFailure = IllegalStateException("child input failed too")
            fixture.action = { phase ->
                if (phase == "read") {
                    assertEquals(-2, failureBoundaryRead(fixture.module, -1))
                    assertEquals(-2, failureBoundaryRead(fixture.module, 0))
                    -5
                } else 0
            }
            media.openPacketReader(media.streams).use { reader ->
                val first = assertFailsWith<FFmpegException> { reader.read() }
                assertSame(rootFailure, first.cause, "preserve the existing root-cause priority")
                fixture.action = { phase -> if (phase == "read") -5 else 0 }
                assertNull(assertFailsWith<FFmpegException> { reader.read() }.cause)
            }
        }
    }

    @Test fun aSuccessfulCallbackCannotEraseAFailureInTheSameOpenOrProbe() {
        for (index in listOf(-1, 0)) {
            for (operation in listOf("open", "probe")) {
                Fixture().use { fixture ->
                    val thrown = IllegalStateException("first input failed during $operation")
                    fixture.source(index).nextReadFailure = thrown
                    fixture.action = { phase ->
                        if (phase == operation) {
                            assertEquals(-2, failureBoundaryRead(fixture.module, index))
                            assertEquals(1, failureBoundaryRead(fixture.module, if (index == -1) -1 else 1))
                            -5
                        } else 0
                    }
                    val failure = assertFailsWith<FFmpegException> { fixture.open() }
                    assertSame(thrown, failure.cause, "$operation lost the original callback failure")
                }
            }
        }
    }
}

/**
 * Keeps two child callbacks just as C owns two AVIOs. Only native demux return codes are scripted;
 * reads and size queries traverse the production callback table, including Kotlin exception capture.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""(m, openAt, readAt, seekAt, closeAt, action) => {
    const opened = [], scratch = m._malloc(8);
    const originalOpen = m._ffkmp_fmt_open_input_io2;
    const originalProbe = m._ffkmp_fmt_find_stream_info;
    const originalRead = m._ffkmp_fmt_read_frame;
    const originalClose = m._ffkmp_fmt_close_input_io;
    const closeChildren = () => {
        while (opened.length) {
            const child = opened.pop();
            child.close(0, child.handle);
        }
    };
    m.__failureBoundaryRead = (index) => {
        const child = index < 0 ? null : opened[index];
        return child ? child.read(child.handle, scratch, 1) : m.__table[m.__lastOpenRead](0, scratch, 1);
    };
    m.__failureBoundarySize = (index) => {
        const child = index < 0 ? null : opened[index];
        const current = child ? child.seek(child.handle, 0n, 0x10000) : m.__table[m.__lastOpenSeek](0, 0n, 0x10000);
        // Exact fallback from kc_io_seek_through, after the callback's own exception was captured.
        return (current >= 0n ? current : child ? child.size : BigInt(m.__lastOpenSize)).toString();
    };
    m.__failureBoundaryRelease = () => { closeChildren(); m._free(scratch); };
    m._ffkmp_fmt_open_input_io2 = (...args) => {
        const rc = originalOpen(...args);
        if (rc < 0) return rc;
        // C ABI: out, opaque, read, seek, tags, size, url, location, mime, opener.
        const opener = args[9];
        const fn = at => m.__table[m.HEAP32[(opener + at) >> 2]];
        try {
            for (let i = 0; i < 2; i++) {
                const url = 'https://media.example/' + i;
                const address = m._malloc(m.lengthBytesUTF8(url) + 1), out = m._malloc(24);
                try {
                    m.stringToUTF8(url, address, m.lengthBytesUTF8(url) + 1);
                    const status = fn(openAt)(0, address, out, out + 8, out + 16);
                    if (status < 0) throw new Error('child open failed: ' + status);
                    opened.push({handle: m.HEAP32[out >> 2], size: new DataView(m.HEAPU8.buffer).getBigInt64(out + 8, true),
                        read: fn(readAt), seek: fn(seekAt), close: fn(closeAt)});
                } finally { m._free(address); m._free(out); }
            }
            const result = action('open');
            if (result < 0) { closeChildren(); m.HEAP32[args[0] >> 2] = 0; }
            return result;
        } catch (failure) { closeChildren(); throw failure; }
    };
    m._ffkmp_fmt_find_stream_info = (...args) => {
        const rc = action('probe');
        return rc < 0 ? rc : originalProbe(...args);
    };
    m._ffkmp_fmt_read_frame = (...args) => {
        const rc = action('read');
        return rc < 0 ? rc : originalRead(...args);
    };
    m._ffkmp_avseek_flag_backward = () => 1;
    m._ffkmp_avseek_flag_any = () => 4;
    m._ffkmp_fmt_seek_file = () => action('seek');
    m._ffkmp_fmt_read_pause = () => { const rc = action('pause'); return rc < 0 ? rc : 1; };
    m._ffkmp_fmt_read_play = () => { const rc = action('resume'); return rc < 0 ? rc : 1; };
    m._ffkmp_fmt_close_input_io = (...args) => { closeChildren(); originalClose(...args); };
}""")
private external fun installFailureBoundaryProbe(
    module: JsAny,
    openAt: Int,
    readAt: Int,
    seekAt: Int,
    closeAt: Int,
    action: (String) -> Int,
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, index) => m.__failureBoundaryRead(index)")
private external fun failureBoundaryRead(module: JsAny, index: Int): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, index) => m.__failureBoundarySize(index)")
private external fun failureBoundarySizeWithFallback(module: JsAny, index: Int): String

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__failureBoundaryRelease()")
private external fun releaseFailureBoundaryProbe(module: JsAny)
