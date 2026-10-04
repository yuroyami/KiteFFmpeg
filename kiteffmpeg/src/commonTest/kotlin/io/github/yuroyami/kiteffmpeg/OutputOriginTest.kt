package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a copy's timeline starts, and when it stops holding packets to find out (#153). */
internal class OutputOriginTest {
    private val freed = mutableListOf<String>()
    private val origin = OutputOrigin<String> { freed += it }

    private fun released(): List<Pair<Int, String>> = buildList { origin.release { lane, packet -> add(lane to packet) } }

    /** Video with B-frames decodes its first keyframe before it shows it, and a B-frame shows earlier still. */
    @Test
    fun bFrameVideoSettlesOnTheEarliestPictureOnceOneDecodesAfterIt() {
        val video = origin.addLane(awaited = true)
        // The keyframe shows at 200 ms and decodes at 0. The B-frames that follow show at 100 and
        // 0 ms, so the stream has not shown its earliest yet.
        assertFalse(origin.hold(video, "I", shownMicros = 200_000, decodedMicros = 0))
        assertFalse(origin.hold(video, "B1", shownMicros = 100_000, decodedMicros = 33_000))
        // A packet that decodes at or after the earliest time found means no later one can show
        // earlier, because nothing decodes after it shows.
        assertTrue(origin.hold(video, "P", shownMicros = 300_000, decodedMicros = 100_000))
        assertEquals(100_000L, origin.settle())
        assertEquals(listOf(video to "I", video to "B1", video to "P"), released())
        assertFalse(origin.isHolding)
    }

    @Test
    fun everyAudioAndVideoStreamIsWaitedFor() {
        val video = origin.addLane(awaited = true)
        val audio = origin.addLane(awaited = true)
        assertFalse(origin.hold(video, "V", shownMicros = 50_000, decodedMicros = 50_000))
        // AAC whose first packet skips its priming shows from after the skip, so the stream has
        // shown its start once a packet decodes there.
        assertFalse(origin.hold(audio, "A0", shownMicros = 21_333, decodedMicros = 0))
        assertTrue(origin.hold(audio, "A1", shownMicros = OutputOrigin.NOTHING, decodedMicros = 21_333))
        assertEquals(21_333L, origin.settle())
    }

    /** A picture FFmpeg marks as discarded, such as one before an MP4 edit list starts, shows nothing. */
    @Test
    fun aPacketThatShowsNothingDoesNotCount() {
        val video = origin.addLane(awaited = true)
        assertFalse(origin.hold(video, "before the edit", shownMicros = OutputOrigin.NOTHING, decodedMicros = -100_000))
        assertTrue(origin.hold(video, "first", shownMicros = 0, decodedMicros = 0))
        assertEquals(0L, origin.settle())
        assertEquals(listOf("before the edit", "first"), released().map { it.second })
    }

    @Test
    fun aSparseStreamIsNotWaitedForAndCountsOnlyWhileNothingElseShows() {
        val video = origin.addLane(awaited = true)
        val subtitles = origin.addLane(awaited = false)
        assertFalse(origin.hold(subtitles, "cue", shownMicros = 10_000, decodedMicros = 10_000))
        assertTrue(origin.hold(video, "I", shownMicros = 40_000, decodedMicros = 40_000))
        assertEquals(40_000L, origin.settle())
    }

    @Test
    fun aSparseStreamAloneSettlesOnItsFirstTime() {
        val subtitles = origin.addLane(awaited = false)
        assertTrue(origin.hold(subtitles, "cue", shownMicros = 7_000_000, decodedMicros = 7_000_000))
        assertEquals(7_000_000L, origin.settle())
    }

    /** A stream with nothing near the start stops the wait a second of decode time past the earliest time found. */
    @Test
    fun aStreamThatShowsNothingIsWaitedForOneSecond() {
        val video = origin.addLane(awaited = true)
        origin.addLane(awaited = true)
        var decoded = 0L
        while (decoded < 1_000_000L) {
            assertFalse(origin.hold(video, "V$decoded", shownMicros = 500_000 + decoded, decodedMicros = 500_000 + decoded))
            decoded += 100_000L
        }
        assertTrue(origin.hold(video, "V$decoded", shownMicros = 500_000 + decoded, decodedMicros = 500_000 + decoded))
        assertEquals(500_000L, origin.settle())
    }

    @Test
    fun packetsWithNoTimeAreHeldUpToTheLimitThenTheFallbackDecides() {
        val video = origin.addLane(awaited = true)
        repeat(OutputOrigin.HOLD_LIMIT - 1) {
            assertFalse(origin.hold(video, "p$it", OutputOrigin.NOTHING, OutputOrigin.NOTHING))
        }
        assertTrue(origin.hold(video, "last", OutputOrigin.NOTHING, OutputOrigin.NOTHING))
        assertEquals(3_000_000L, origin.settle(fallback = 3_000_000))
        assertEquals(OutputOrigin.HOLD_LIMIT, released().size)
    }

    /** An encoded frame takes part through its own time, which wins when it is earlier. */
    @Test
    fun anEncodedFrameDecidesWhenItShowsFirst() {
        val video = origin.addLane(awaited = true)
        origin.addLane(awaited = true)
        origin.hold(video, "V", shownMicros = 80_000, decodedMicros = 80_000)
        assertEquals(60_000L, origin.settle(atMost = 60_000))
        assertTrue(origin.isSettled)
        assertFailsWith<IllegalStateException> { origin.hold(video, "late", 90_000, 90_000) }
    }

    @Test
    fun anEncodedFrameAfterTheCopiesDoesNotMoveTheOrigin() {
        val video = origin.addLane(awaited = true)
        origin.hold(video, "V", shownMicros = 80_000, decodedMicros = 80_000)
        assertEquals(80_000L, origin.settle(atMost = 120_000))
    }

    @Test
    fun aFailedWriteFreesThePacketsAfterIt() {
        val video = origin.addLane(awaited = true)
        listOf("a", "b", "c", "d").forEachIndexed { i, name -> origin.hold(video, name, i * 10L, i * 10L - 30) }
        origin.settle()
        val written = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            origin.release { _, packet ->
                if (packet == "b") error("the muxer refused b")
                written += packet
            }
        }
        // The write that failed owns its packet; the ones after it were never handed over.
        assertEquals(listOf("a"), written)
        assertEquals(listOf("c", "d"), freed)
        assertFalse(origin.isHolding)
    }

    @Test
    fun aDiscardFreesEveryHeldPacket() {
        val audio = origin.addLane(awaited = true)
        origin.hold(audio, "x", 0, 0)
        origin.hold(audio, "y", 21_333, 21_333)
        origin.discard()
        assertEquals(listOf("x", "y"), freed)
        assertFalse(origin.isHolding)
        assertFalse(origin.isSettled)
    }

    @Test
    fun nothingShownSettlesOnTheFallback() {
        origin.addLane(awaited = true)
        assertEquals(0L, origin.settle())
        assertEquals(0L, origin.micros)
    }
}
