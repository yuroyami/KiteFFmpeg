package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A container's own bit rate and a video stream's field order are readable, and mean what they say.
 *
 * Both were reachable in C and stopped at the boundary: a caller who wanted "how big is this per
 * second" had to add the streams up, which is wrong for anything with overhead, and a caller who
 * wanted to know whether to deinterlace had nothing at all and had to guess progressive.
 *
 * The bit rate is checked against the fixture's real size and duration rather than against a
 * constant. A constant would pass just as happily if the binding read the wrong field or returned
 * bytes, and those are the two ways this actually breaks.
 */
class ContainerBitRateContractTest {

    @Test
    fun theContainerBitRateMatchesTheFilesRealSizeAndLength() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            val reported = source.bitrateBps
            if (reported == null) return // this demuxer records no rate; nothing is being claimed

            assertTrue(reported > 0, "a present bit rate must be positive, got $reported")
            val micros = source.durationMicros
            assertTrue(micros != null && micros > 0, "the fixture must have a duration to check against")

            val actual = ContractMedia.bytes.size.toLong() * 8 * 1_000_000 / micros
            // Generous on purpose: the demuxer's figure excludes some container overhead and the
            // fixture is short enough that rounding matters. Tight enough to catch bytes-for-bits
            // (8x out) or a stream rate standing in for the container's (well under).
            assertTrue(
                reported > actual / 4 && reported < actual * 4,
                "container bit rate $reported is nowhere near the file's own $actual bps",
            )
        }
    }

    @Test
    fun theProbeReportsTheSameBitRateTheSourceDoes() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        val probe = MediaSource.probe(path)
        MediaSource.open(path).use { source ->
            assertEquals(source.bitrateBps, probe.bitrateBps)
        }
    }

    @Test
    fun everyVideoStreamCarriesAFieldOrderThatAgreesWithItself() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            val video = source.streams.mapNotNull { it.video }
            assertTrue(video.isNotEmpty(), "the fixture carries a video stream")
            for (v in video) {
                val expected = v.fieldOrder == FieldOrder.TopFirst || v.fieldOrder == FieldOrder.BottomFirst
                assertEquals(
                    expected,
                    v.fieldOrder.isInterlaced,
                    "isInterlaced disagrees with ${v.fieldOrder}",
                )
            }
        }
    }

    @Test
    fun everyCodeTheCLayerCanSendMapsToAFieldOrder() {
        // The other direction, and the only place it can be reached. A real interlaced fixture is
        // not available here: this build's mpeg4 encoder rejects the `top` option, and with
        // `+ilme+ildct` alone the order does not survive into the container's codec parameters. So
        // the C suite proves the AVFieldOrder side of the mapping and this proves the Kotlin side.
        assertEquals(FieldOrder.Unknown, FieldOrder.ofCode(0))
        assertEquals(FieldOrder.Progressive, FieldOrder.ofCode(1))
        assertEquals(FieldOrder.TopFirst, FieldOrder.ofCode(2))
        assertEquals(FieldOrder.BottomFirst, FieldOrder.ofCode(3))

        // A code this Kotlin does not know about must not become an interlaced answer by accident:
        // a caller acting on it would deinterlace progressive video.
        for (stray in listOf(-1, 4, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(FieldOrder.Unknown, FieldOrder.ofCode(stray), "code $stray")
        }

        assertTrue(FieldOrder.TopFirst.isInterlaced && FieldOrder.BottomFirst.isInterlaced)
        assertTrue(!FieldOrder.Unknown.isInterlaced && !FieldOrder.Progressive.isInterlaced)
    }

    @Test
    fun theFixtureIsProgressiveOrSaysItDoesNotKnow() {
        // The fixture is encoded frame by frame from progressive input, so an interlaced answer
        // here means the code read a neighbouring field of the struct, not that the file changed.
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            val video = source.primaryVideo?.video ?: error("no video stream")
            assertTrue(
                !video.fieldOrder.isInterlaced,
                "progressive fixture reported ${video.fieldOrder}",
            )
        }
    }
}
