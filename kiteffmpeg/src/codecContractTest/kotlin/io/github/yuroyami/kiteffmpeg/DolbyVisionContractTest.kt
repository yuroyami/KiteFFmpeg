package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dolby Vision: the stream's configuration record, each frame's RPU, and the composition of a
 * profile 5 base layer, held to libplacebo's composition of the same clip.
 */
internal class DolbyVisionContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun clip(): String =
        materializeContractMedia(DolbyVisionFixtures.CLIP, DolbyVisionFixtures.CLIP_SHA256).also(paths::add)

    private suspend fun <T> withFrames(block: (MediaSource, List<Frame>) -> T): T =
        MediaSource.open(clip()).use { source ->
            val frames = source.decodedFrames(source.streams.single()).toList()
            try {
                assertEquals(DolbyVisionFixtures.FRAMES, frames.size, "decoded frames")
                block(source, frames)
            } finally {
                frames.forEach(Frame::close)
            }
        }

    @Test
    fun theStreamDeclaresProfileFiveWithABaseLayerThatCannotPlayAlone() = runTest {
        MediaSource.open(clip()).use { source ->
            val config = assertNotNull(source.streams.single().video?.dolbyVision, "the stream's configuration")
            assertEquals(
                DolbyVisionConfig(
                    versionMajor = 1,
                    versionMinor = 0,
                    profile = 5,
                    level = 1,
                    hasRpu = true,
                    hasEnhancementLayer = false,
                    hasBaseLayer = true,
                    baseLayerCompatibility = 0,
                ),
                config,
            )
            assertFalse(config.baseLayerPlaysAlone)
        }
    }

    @Test
    fun eachFrameReadsItsOwnRpu() = runTest {
        withFrames { _, frames ->
            frames.forEachIndexed { index, frame ->
                val metadata = assertNotNull(frame.dolbyVision(), "the metadata of frame $index")
                assertEquals(10, metadata.baseLayerBitDepth)
                assertFalse(metadata.usesEnhancementLayer)
                assertEquals(DolbyVisionFixtures.SOURCE_MIN_PQ, metadata.sourceMinPq)
                assertEquals(DolbyVisionFixtures.SOURCE_MAX_PQ, metadata.sourceMaxPq)
                assertEquals(DolbyVisionFixtures.SCENE_BRIGHTNESS[index], metadata.sceneBrightness, "level 1 of frame $index")
            }
        }
    }

    @Test
    fun theComposedPictureIsHdr10AndMatchesLibplacebo() = runTest {
        withFrames { _, frames ->
            frames.forEachIndexed { index, frame ->
                val composed = assertNotNull(frame.composeDolbyVision(), "the composition of frame $index")
                composed.use {
                    val info = it.info
                    assertEquals(PixelFormat.Yuv420p10le, info.pixelFormat)
                    assertEquals(DolbyVisionFixtures.WIDTH, info.width)
                    assertEquals(DolbyVisionFixtures.HEIGHT, info.height)
                    assertEquals(frame.info.pts, info.pts, "the composition keeps the frame's timestamp")
                    assertEquals(ColorPrimaries.Bt2020, info.color.primaries)
                    assertEquals(ColorTransfer.SmpteSt2084, info.color.transfer)
                    assertEquals(ColorMatrix.Bt2020Ncl, info.color.matrix)
                    assertFalse(info.color.fullRange)
                    assertEquals(ChromaLocation.Left, info.color.chromaLocation)
                    val luminance = assertNotNull(info.hdr?.masteringDisplay?.luminance, "the source's range as a mastering display")
                    assertTrue(abs(luminance.max.asDouble - 1000.0) < 1.5, "PQ 3079 is 1000 nits, read ${luminance.max.asDouble}")
                    assertTrue(luminance.min.asDouble < 0.001, "PQ 7 is about 0.0002 nits, read ${luminance.min.asDouble}")
                    assertEquals(ContentLightLevel(maxCll = 943, maxFall = 400), info.hdr?.contentLight)
                    assertNull(it.dolbyVision(), "the composed picture carries no Dolby Vision")
                    DolbyVisionFixtures.assertMatchesExpected(index, it.copyPlanesToByteArray())
                }
            }
        }
    }

    @Test
    fun bandsInAnyOrderComposeTheSamePictureAsOneCall() = runTest {
        withFrames { _, frames ->
            val whole = assertNotNull(frames[0].composeDolbyVision()).use { it.copyPlanesToByteArray() }
            val composition = assertNotNull(frames[0].beginDolbyVisionComposition())
            val banded = composition.use {
                assertEquals(DolbyVisionFixtures.HEIGHT, it.height)
                it.composeRows(16, 24)
                it.composeRows(0, 6)
                it.composeRows(6, 16)
                it.finish()
            }
            banded.use { assertContentEquals(whole, it.copyPlanesToByteArray()) }
        }
    }

    @Test
    fun aBandMustStartOnAnEvenRowAndStayInsideThePicture() = runTest {
        withFrames { _, frames ->
            assertNotNull(frames[0].beginDolbyVisionComposition()).use { composition ->
                listOf(1 to 4, 0 to 3, 0 to 26, -2 to 2, 8 to 6).forEach { (start, end) ->
                    val refusal = assertFailsWith<FFmpegException>("rows $start until $end") { composition.composeRows(start, end) }
                    assertTrue(refusal.error is FFmpegError.InvalidArgument, "rows $start until $end: ${refusal.error}")
                }
                composition.composeRows(4, 4)
            }
        }
    }

    @Test
    fun aCompositionOutlivesItsSourceFrameAndEndsAtFinish() = runTest {
        val composition = withFrames { _, frames -> assertNotNull(frames[1].beginDolbyVisionComposition()) }
        // Every decoded frame is closed by now; the composition holds its own reference.
        composition.composeRows(0, composition.height)
        val picture = composition.finish()
        picture.use { DolbyVisionFixtures.assertMatchesExpected(1, it.copyPlanesToByteArray()) }
        assertFailsWith<IllegalStateException> { composition.composeRows(0, 2) }
        assertFailsWith<IllegalStateException> { composition.finish() }
        composition.close()
    }

    @Test
    fun aStreamAndFramesWithoutDolbyVisionAnswerNull() = runTest {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256).also(paths::add)
        MediaSource.open(path).use { source ->
            val video = source.streams.first { it.type == MediaType.Video }
            assertNull(video.video?.dolbyVision)
            val frames = source.decodedFrames(video).toList()
            try {
                val frame = frames.first()
                assertNull(frame.dolbyVision())
                assertNull(frame.beginDolbyVisionComposition())
                assertNull(frame.composeDolbyVision())
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }
}
