package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A probe answers what an open source would, and leaves nothing open.
 *
 * The point of the type is that it cannot be leaked: every caller who wanted a duration or a
 * stream list had to open a source, read two fields and remember to close it. This holds the probe
 * to the same answers the source gives, so the convenience cannot quietly become a second, wronger
 * way to read a container.
 */
class MediaProbeContractTest {

    @Test
    fun aProbeAnswersWhatAnOpenSourceAnswers() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        val probe = MediaSource.probe(path)
        MediaSource.open(path).use { source ->
            assertEquals(source.formatName, probe.formatName)
            assertEquals(source.durationMicros, probe.durationMicros)
            assertEquals(source.startTimeMicros, probe.startTimeMicros)
            assertEquals(source.isSeekable, probe.isSeekable)
            assertEquals(source.metadata, probe.metadata)
            assertEquals(source.chapters.size, probe.chapters.size)
            assertEquals(source.streams.size, probe.streams.size)
            assertEquals(
                source.streams.map { it.index to it.type },
                probe.streams.map { it.index to it.type },
            )
        }
    }

    @Test
    fun aProbeFindsBothStreamsOfATwoStreamFile() {
        val probe = MediaSource.probe(materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256))
        assertEquals(2, probe.streams.size, "the fixture carries a video and an audio stream")
        assertTrue(probe.primaryVideo != null, "no video stream found")
        assertTrue(probe.primaryAudio != null, "no audio stream found")
    }

    @Test
    fun aProbeLeavesNothingOpen() {
        // The whole reason the type exists. The handle count is the owner table's own answer, and
        // it is what would catch a probe that read its fields and forgot to close the source.
        val baseline = contractLiveHandleCount()
        repeat(3) { MediaSource.probe(materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)) }
        assertEquals(baseline, contractLiveHandleCount(), "a probe left a source open")
    }

    @Test
    fun aFailedProbeLeavesNothingOpenEither() {
        val baseline = contractLiveHandleCount()
        assertFailsWith<FFmpegException> { MediaSource.probe("/nonexistent/kiteffmpeg-probe.mkv") }
        assertEquals(baseline, contractLiveHandleCount(), "a failed probe left something behind")
    }

    @Test
    fun aProbeOfSomethingUnopenableFailsTheWayAnOpenWould() {
        assertFailsWith<FFmpegException> { MediaSource.probe("/nonexistent/kiteffmpeg-probe.mkv") }
    }
}
