package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.FilterChain
import io.github.yuroyami.kiteffmpeg.dsl.Scale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The web builds no filter graph. It says so the same way on every route, and it does not claim to
 * have a filter a caller cannot use.
 */
class WebFilterRefusalTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun noFilterIsUsableOnTheWeb() {
        useCodecModule(fakeCodecModule())
        assertFalse(FFmpeg.hasFilter("scale"))
        assertFalse(FFmpeg.hasFilter("aresample"))
    }

    @Test
    fun aStringBuildSaysFilteringIsNotOfferedOnTheWeb() {
        val failure = assertFailsWith<FFmpegException> {
            FilterGraph.buildVideo("scale=16:16", 32, 32, PixelFormat.Yuv420p, Rational(1, 25), Rational(25, 1))
        }
        assertIs<FFmpegError.Unsupported>(failure.error)
        val message = failure.message.orEmpty()
        assertTrue("no filter graph on the web" in message, message)
        assertFalse("not implemented" in message, "decoding does work on the web: $message")
    }

    @Test
    fun aTypedChainSaysTheSameRatherThanNamingMissingFilters() {
        useCodecModule(fakeCodecModule())
        val failure = assertFailsWith<FFmpegException> { FilterChain(listOf(Scale(16, 16))).requireAvailable() }
        assertIs<FFmpegError.Unsupported>(failure.error)
        assertTrue("no filter graph on the web" in failure.message.orEmpty(), failure.message.orEmpty())
    }
}
