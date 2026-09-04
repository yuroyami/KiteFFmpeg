package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The web backend answers what the container actually says.
 *
 * It used to answer plausible emptiness: container metadata and chapters were hardcoded empty, the
 * stream read skipped tags, start time, extradata, colour and channel layout, and every non-AV
 * type collapsed to `Data`. That is the worst shape a gap can take, because an empty answer is
 * indistinguishable from a container that genuinely carries nothing, so nothing ever looked wrong.
 *
 * One case per field, against a fake that scripts a real value for each, so a field that goes back
 * to being dropped fails on its own rather than inside a general "metadata broke".
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebContainerModelTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private fun open(): MediaSource {
        useCodecModule(fakeModelCodecModule())
        return MediaSource.open(OneByteSource(), emptyMap())
    }

    @Test
    fun containerMetadataIsReadRatherThanAnsweredEmpty() {
        open().use { source ->
            assertEquals(
                mapOf("title" to "Fake Container", "encoder" to "kite"),
                source.metadata,
                "the container's own dictionary must be walked, in the order it was written",
            )
        }
    }

    @Test
    fun chaptersAreReadWithTheirBoundsAndTitles() {
        open().use { source ->
            val chapters = source.chapters
            assertEquals(2, chapters.size)
            assertEquals(7L, chapters[0].id)
            assertEquals(0L, chapters[0].startMicros)
            assertEquals(1_500_000L, chapters[0].endMicros)
            assertEquals("Opening", chapters[0].title)
            assertEquals(1_500_000L, chapters[1].startMicros)
            assertEquals("Ending", chapters[1].title)
        }
    }

    @Test
    fun perStreamMetadataIsRead() {
        open().use { source ->
            assertEquals("eng", source.streams[0].metadata["language"])
            assertEquals("Main Video", source.streams[0].metadata["title"])
            // The second stream carries a different language, so a fake that returned one shared
            // dictionary for every stream would not pass this.
            assertEquals("ger", source.streams[1].metadata["language"])
        }
    }

    @Test
    fun aStreamStartTimeIsRescaledFromItsOwnTimeBase() {
        open().use { source ->
            // 250 ticks at 1/1000 is a quarter of a second. Reading 250 here would mean the value
            // was passed through as if it were already microseconds.
            assertEquals(250_000L, source.streams[0].startTimeMicros)
        }
    }

    @Test
    fun codecExtradataIsCopiedOut() {
        open().use { source ->
            // Without these a caller decoding elsewhere, WebCodecs above all, cannot configure its
            // decoder at all.
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), source.streams[0].codecExtradata)
        }
    }

    @Test
    fun declaredColourIsReadRatherThanLeftUnspecified() {
        open().use { source ->
            val color = assertNotNull(source.streams[0].video?.color)
            assertEquals(ColorMatrix.Bt709, color.matrix)
            assertEquals(ColorPrimaries.Bt709, color.primaries)
            assertEquals(ColorTransfer.Bt709, color.transfer)
            assertTrue(color.fullRange, "AVCOL_RANGE_JPEG must read as full range")
            assertTrue(color.rangeSpecified, "a declared range must be reported as declared")
        }
    }

    @Test
    fun aNonAvStreamKeepsItsOwnTypeInsteadOfCollapsingToData() {
        open().use { source ->
            // Attachment and Unknown both used to arrive as Data, which erases the difference
            // between a font the container carries and a type this build cannot name.
            assertEquals(MediaType.Attachment, source.streams[1].type)
            assertEquals(MediaType.Video, source.streams[0].type)
        }
    }

    @Test
    fun aDeclaredColourReportsItselfAsDeclared() {
        useCodecModule(fakeModelCodecModule())
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val color = assertNotNull(source.streams[0].video?.color)
            assertTrue(color.matrixSpecified, "a declared matrix must say it was declared")
            assertTrue(color.primariesSpecified)
            assertTrue(color.transferSpecified)
            assertTrue(color.rangeSpecified)
        }
    }

    @Test
    fun aGuessedColourReportsItselfAsGuessed() {
        val module = fakeModelCodecModule()
        useCodecModule(module)
        // The stream now declares nothing. A VALUE still comes out, because every player applies a
        // default rather than refusing to draw; what changes is that the caller can tell it is this
        // library's opinion and not the file's. The value is BT.601 rather than BT.709 because the
        // fake's stream is 180 lines tall, and the guess is by height. That is also why the
        // declared case above reads Bt709: it is the file talking, not the height.
        setFakeColorDeclared(module, false)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val color = assertNotNull(source.streams[0].video?.color)
            assertEquals(ColorMatrix.Smpte170m, color.matrix, "the guess must still be applied")
            assertFalse(color.matrixSpecified, "a guessed matrix must not claim to be declared")
            assertFalse(color.primariesSpecified)
            assertFalse(color.transferSpecified)
            assertFalse(color.rangeSpecified)
        }
    }

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

    @Test
    fun theVideoStreamsFieldOrderIsRead() {
        open().use { source ->
            val video = assertNotNull(source.primaryVideo?.video, "no video stream")
            assertEquals(FieldOrder.TopFirst, video.fieldOrder)
            assertTrue(video.fieldOrder.isInterlaced)
        }
    }

    @Test
    fun theContainerBitRateIsRead() {
        open().use { source ->
            // Not a round number on purpose: a backend that answered a plausible constant instead
            // of reading would have to guess this one.
            assertEquals(3_141_592L, source.bitrateBps)
        }
    }

}
