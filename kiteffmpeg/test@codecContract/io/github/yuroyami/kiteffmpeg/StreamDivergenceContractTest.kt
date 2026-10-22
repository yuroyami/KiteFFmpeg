package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The divergence report, wired to a real decoder rather than to a hand-built pair of structs.
 *
 * The comparison itself has its own suite. What this covers is the half a unit test cannot: that
 * the recorder is actually reached from the decode loop, and that an ordinary file reports NOTHING.
 *
 * The second half matters more than it looks. A comparison this cheap to get subtly wrong (a video
 * frame's zero sample rate against a stream's, an undeclared pixel format against a decoded one)
 * would fire on every file in existence, and a report that fires on everything is worse than no
 * report: it trains its reader to ignore it. So an honest file staying silent through a real decode
 * is the case that keeps the feature worth having.
 */
class StreamDivergenceContractTest {

    @Test
    fun anOrdinaryFileReportsNoDisagreementAtAll() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            assertTrue(
                source.streamDivergences.isEmpty(),
                "nothing has decoded yet, so nothing can have disagreed: ${source.streamDivergences}",
            )

            val frames = runBlocking { source.decodeStreams(source.streams).take(8).toList() }
            frames.forEach { it.close() }
            assertTrue(frames.isNotEmpty(), "the fixture must decode something for this to mean anything")

            assertEquals(
                emptyList(),
                source.streamDivergences,
                "an honest file must stay silent, or the report is noise on every file",
            )
        }
    }

    @Test
    fun theComparisonRunsOncePerStreamAndNotOncePerFrame() {
        // Not a performance assertion, a correctness one: a recorder that compared every frame
        // would append the same disagreement once per frame and a caller reading the list would
        // see one mislabelled stream as thousands of entries.
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            val frames = runBlocking { source.decodeStreams(source.streams).take(20).toList() }
            frames.forEach { it.close() }
            assertTrue(
                source.streamDivergences.size <= source.streams.size * DivergentField.entries.size,
                "there cannot be more rows than fields across the streams: ${source.streamDivergences}",
            )
        }
    }

    @Test
    fun aSourceThatNeverDecodedReportsNothing() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            source.streams
            assertEquals(emptyList(), source.streamDivergences)
        }
    }
}
