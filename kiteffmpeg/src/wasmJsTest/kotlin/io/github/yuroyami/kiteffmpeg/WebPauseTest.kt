package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The web half of [MediaSource.pause] and [MediaSource.resume] (#136). The module's helpers do the
 * work, as on every other backend, so these tests check what the Kotlin layer adds: the verdicts
 * mapped faithfully, a refusal thrown with its code, and a resume that reaches the module only
 * while a pause is in effect.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebPauseTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun aPauseForwardsEveryCallAndAResumeOnlyLiftsOne() {
        val module = pausingModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            assertTrue(source.pause())
            assertTrue(source.pause(), "a repeated pause stays paused")
            assertEquals(2, fakePauseCalls(module), "a repeated pause reaches the module, which keeps the session alive")
            assertTrue(source.resume())
            assertFalse(source.resume(), "the pause was already lifted")
            assertEquals(1, fakePlayCalls(module), "a resume with nothing to lift reached the module")
        }
    }

    @Test
    fun aSourceWithNoNotionOfPausingAnswersFalseAndResumesNothing() {
        val module = pausingModule()
        setFakePauseAnswer(module, 0)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            assertFalse(source.resume(), "there was no pause to lift")
            assertFalse(source.pause())
            assertFalse(source.resume(), "a pause the source does not have leaves nothing to lift")
            assertEquals(0, fakePlayCalls(module))
        }
    }

    @Test
    fun aRefusedResumeThrowsItsCodeAndLeavesTheSourcePaused() {
        val module = pausingModule()
        // AVERROR_HTTP_OTHER_4XX, what FFmpeg answers for a server's 454 Session Not Found.
        val sessionGone = -(0xF8 or ('4'.code shl 8) or ('X'.code shl 16) or ('X'.code shl 24))
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            assertTrue(source.pause())
            setFakePlayAnswer(module, sessionGone)
            val refusal = assertFailsWith<FFmpegException> { source.resume() }
            assertEquals(sessionGone, refusal.error.code)
            setFakePlayAnswer(module, 1)
            assertTrue(source.resume(), "the refused resume left the pause in effect")
            assertEquals(2, fakePlayCalls(module))
        }
    }

    @Test
    fun aRefusedPauseThrowsItsCode() {
        val module = pausingModule()
        setFakePauseAnswer(module, -5)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val refusal = assertFailsWith<FFmpegException> { source.pause() }
            assertIs<FFmpegError.Io>(refusal.error)
            assertFalse(source.resume(), "a refused pause leaves nothing to lift")
        }
    }

    private fun pausingModule(): JsAny = installFakePause(fakePacketReaderCodecModule()).also(::useCodecModule)

    /** The smallest byte source `MediaSource.open` accepts; the fake demuxer ignores its content. */
    private class OneByteSource : MediaByteSource {
        override val size: Long = 1L
        override val seekable: Boolean = true
        private var consumed = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (consumed) return -1
            into[offset] = 0
            consumed = true
            return 1
        }

        override fun seek(position: Long) {
            consumed = position != 0L
        }

        override fun close(): Unit = Unit
    }
}

/** Adds the pause and play helpers to a fake, each answering a value a test sets and counting its calls. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        m.__pauseAnswer = 1;
        m.__playAnswer = 1;
        m.__pauseCalls = 0;
        m.__playCalls = 0;
        m._ffkmp_fmt_read_pause = (ctx) => { m.__pauseCalls++; return m.__pauseAnswer; };
        m._ffkmp_fmt_read_play = (ctx) => { m.__playCalls++; return m.__playAnswer; };
        return m;
    }""",
)
private external fun installFakePause(module: JsAny): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, answer) => { m.__pauseAnswer = answer; }")
private external fun setFakePauseAnswer(module: JsAny, answer: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, answer) => { m.__playAnswer = answer; }")
private external fun setFakePlayAnswer(module: JsAny, answer: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__pauseCalls")
private external fun fakePauseCalls(module: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__playCalls")
private external fun fakePlayCalls(module: JsAny): Int
