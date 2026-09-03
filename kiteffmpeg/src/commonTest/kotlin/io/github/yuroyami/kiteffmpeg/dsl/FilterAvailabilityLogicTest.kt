package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a chain reports about filters this build lacks, against a supplied answer rather than
 * against the FFmpeg the test run links.
 *
 * The distinction matters: a development machine links a build carrying every filter, so through
 * the public entry point the missing case never happens and a suite would pass while proving
 * nothing. Everything here drives the internal form with a predicate the test controls.
 */
class FilterAvailabilityLogicTest {

    private val chain = videoFilters {
        deinterlace(Deinterlacer.Bwdif)
        scale(320, 240)
        deinterlace(Deinterlacer.Bwdif)
        raw("hqdn3d=1.5")
    }

    @Test
    fun `every missing filter is named once and in the order it appears`() {
        val missing = chain.missingFilters { false }
        assertEquals(
            listOf("bwdif", "scale"),
            missing,
            "a filter used twice is reported once, and the order is the chain's",
        )
    }

    @Test
    fun `a raw step is never reported missing`() {
        // A raw step carries no filter name this side can check, which is its whole bargain.
        val onlyRaw = videoFilters { raw("nosuchfilter=1") }
        assertEquals(emptyList(), onlyRaw.missingFilters { false })
        onlyRaw.requireAvailable { false }
    }

    @Test
    fun `nothing is missing when the build has everything`() {
        assertEquals(emptyList(), chain.missingFilters { true })
        chain.requireAvailable { true }
    }

    @Test
    fun `only the absent ones are named`() {
        assertEquals(listOf("bwdif"), chain.missingFilters { it != "bwdif" })
    }

    @Test
    fun `the refusal is typed and names every missing filter at once`() {
        val failure = assertFailsWith<FFmpegException> { chain.requireAvailable { false } }
        assertTrue(
            failure.error is FFmpegError.FilterNotFound,
            "expected FilterNotFound, got ${failure.error::class.simpleName}",
        )
        val message = failure.message.orEmpty()
        assertTrue("bwdif" in message, "the refusal must name bwdif: $message")
        assertTrue("scale" in message, "and scale in the same refusal: $message")
    }

    @Test
    fun `one missing filter reads as one and not as a list`() {
        val failure = assertFailsWith<FFmpegException> { chain.requireAvailable { it != "bwdif" } }
        val message = failure.message.orEmpty()
        assertTrue("bwdif" in message && "scale" !in message, message)
        assertTrue("carries it" in message, "a single missing filter reads in the singular: $message")
    }
}
