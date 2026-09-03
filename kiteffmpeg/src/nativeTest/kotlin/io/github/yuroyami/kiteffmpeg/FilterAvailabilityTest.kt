@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.Deinterlacer
import io.github.yuroyami.kiteffmpeg.dsl.videoFilters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A chain can say which of its filters this build lacks, before FFmpeg is asked to parse it.
 *
 * The recipes compile a chosen set of filters, and a chain built from the DSL can name one the
 * build does not carry. Until this, finding that out meant handing the description to FFmpeg and
 * reading whatever it said, which is a parse error about a string rather than an answer about a
 * filter. The chain knows its own filter names, and [FFmpeg.hasFilter] is one call each.
 */
class FilterAvailabilityTest {

    @Test
    fun `a chain names every filter this build lacks not just the first`() {
        val chain = videoFilters {
            deinterlace(Deinterlacer.Bwdif)
            scale(320, 240)
            deinterlace(Deinterlacer.Yadif)
        }
        val missing = chain.missingFilters()
        // scale is in every recipe this project builds; the deinterlacers are not compiled in yet.
        assertTrue("scale" !in missing, "scale is compiled in, so it cannot be missing: $missing")
        assertEquals(
            missing.distinct(),
            missing,
            "a chain naming one filter twice must report it once: $missing",
        )
        for (name in missing) {
            assertTrue(!FFmpeg.hasFilter(name), "$name was reported missing but the build has it")
        }
    }

    @Test
    fun `a chain of filters this build has reports nothing missing`() {
        val chain = videoFilters { scale(320, 240) }
        assertEquals(emptyList(), chain.missingFilters())
        chain.requireAvailable()
    }

    @Test
    fun `requireAvailable refuses typed and names every missing filter at once`() {
        val chain = videoFilters {
            deinterlace(Deinterlacer.Bwdif)
            scale(320, 240)
        }
        val missing = chain.missingFilters()
        if (missing.isEmpty()) return // this build carries them; nothing to refuse

        val failure = assertFailsWith<FFmpegException> { chain.requireAvailable() }
        assertTrue(
            failure.error is FFmpegError.FilterNotFound,
            "a missing filter is FilterNotFound, not ${failure.error::class.simpleName}",
        )
        for (name in missing) {
            assertTrue(
                name in failure.message.orEmpty(),
                "the refusal must name $name; it said ${failure.message}",
            )
        }
    }

    @Test
    fun `building from a chain refuses before FFmpeg is asked to parse anything`() {
        val chain = videoFilters {
            deinterlace(Deinterlacer.Bwdif)
            scale(320, 240)
        }
        if (chain.missingFilters().isEmpty()) return

        val failure = assertFailsWith<FFmpegException> {
            FilterGraph.buildVideo(
                chain,
                width = 320,
                height = 240,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 25),
                frameRate = Rational(25, 1),
            )
        }
        assertTrue(
            failure.error is FFmpegError.FilterNotFound,
            "the chain overload must refuse on availability, got ${failure.error::class.simpleName}",
        )
    }

    @Test
    fun `a buildable chain still builds through the chain overload`() {
        val graph = FilterGraph.buildVideo(
            videoFilters { scale(320, 240) },
            width = 640,
            height = 480,
            pixelFormat = PixelFormat.Yuv420p,
            timeBase = Rational(1, 25),
            frameRate = Rational(25, 1),
        )
        graph.close()
    }
}
