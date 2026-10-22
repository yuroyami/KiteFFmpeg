package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The web half of a byte source handing FFmpeg the tags its bytes bring (#168). The fake module reads
 * the input as FFmpeg's input bridge does, asking the tags callback after each read that brought
 * bytes, and keeps what reaches `ffkmp_io_tag`. The C suite and the contract suite cover what FFmpeg
 * then does with the tags.
 *
 * Both ways of reading a source must hand FFmpeg the same tags at the same reads. On demand the
 * source is asked as FFmpeg reads. Staged, it is asked as it drains, and each answer waits at the
 * first byte of the read that brought it, so FFmpeg's reads stop there and the read that starts
 * there hands the tags over.
 *
 * The radio below is 100 bytes long and its second song starts at byte 40, where it stops its reads
 * as FFmpeg's `http` stops at every title block.
 */
class WebByteSourceTagsTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private class RadioSource(
        override val seekable: Boolean = false,
        private val failAtSong: Throwable? = null,
    ) : MediaByteSource {
        private val bytes = ByteArray(100) { it.toByte() }
        private var position = 0
        private var readStart = -1
        var tagsAsked = 0
        var closeCount = 0

        override val size: Long = bytes.size.toLong()

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val end = if (position < SONG_BYTE) SONG_BYTE else bytes.size
            val n = minOf(length, end - position)
            bytes.copyInto(into, offset, position, position + n)
            readStart = position
            position += n
            return n
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun takeTags(): Map<String, String>? {
            tagsAsked++
            return when (readStart) {
                0 -> mapOf("icy-name" to "Test radio")
                SONG_BYTE -> {
                    failAtSong?.let { throw it }
                    // A NUL ends a value as the C string it crosses as does, and an empty key is
                    // left out rather than refused by FFmpeg, which would fail the read.
                    mapOf("title" to "Café 東京 🎵\u0000hidden", "" to "no key", "comment" to "second")
                }
                else -> null
            }
        }

        override fun close() {
            closeCount++
        }
    }

    private fun openRadio(source: RadioSource, onDemand: Boolean): Pair<JsAny, MediaSource> {
        WebIoBridge.readOnDemand = onDemand
        val module = fakePacketReaderCodecModule()
        fakeIoTags(module)
        useCodecModule(module)
        return module to MediaSource.open(source, emptyMap())
    }

    @Test
    fun aSourceReadOnDemandHandsEachReadsTagsAfterIt() {
        val source = RadioSource()
        val (module, media) = openRadio(source, onDemand = true)
        media.use {
            assertEquals(0, source.tagsAsked, "a source read on demand is asked only after a read FFmpeg made")
            assertEquals(RADIO_READS, fakeBridgeReads(module, 16, 20))
            assertEquals(7, source.tagsAsked, "asked after every read that brought bytes")
        }
    }

    @Test
    fun aStagedSourceHandsEachReadsTagsAtTheByteWhereThatReadStarted() {
        val source = RadioSource()
        val (module, media) = openRadio(source, onDemand = false)
        media.use {
            assertEquals(2, source.tagsAsked, "a staged source is asked as it drains, after each of its two reads")
            assertEquals(1, source.closeCount)
            assertEquals(RADIO_READS, fakeBridgeReads(module, 16, 20))
        }
    }

    /** FFmpeg's `http` reports a title block every time it reads one, and a staged source the same. */
    @Test
    fun aStagedSourceHandsTheTagsAgainWhenFFmpegReadsTheirByteAgain() {
        val (module, media) = openRadio(RadioSource(), onDemand = false)
        media.use {
            fakeBridgeReads(module, 16, 20)
            assertEquals(30.0, fakeCallSeek(module, fakeLastOpenSeek(module), 30.0, 0))
            assertEquals("10 | 16 $SONG_TAGS", fakeBridgeReads(module, 16, 2))
        }
    }

    @Test
    fun aStagedSourceWhoseReadsBroughtNoTagsOpensWithoutATagsCallback() {
        WebIoBridge.readOnDemand = false
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        MediaSource.open(PlainSource(), emptyMap()).use {
            assertEquals(0, fakeLastOpenTags(module))
            assertEquals("16 | 16 | 16 | 16 | 16 | 16 | 4 | -1", fakeBridgeReads(module, 16, 20))
        }
    }

    @Test
    fun theTagsCallbackLivesUntilTheMediaCloses() {
        for (onDemand in listOf(true, false)) {
            val (module, media) = openRadio(RadioSource(), onDemand)
            val tags = fakeLastOpenTags(module)
            assertNotEquals(0, tags, "onDemand=$onDemand")
            assertTrue(fakeTableEntryLive(module, tags), "onDemand=$onDemand")
            media.close()
            assertTrue(!fakeTableEntryLive(module, tags), "the tags callback outlived the media, onDemand=$onDemand")
        }
    }

    @Test
    fun aTagFFmpegRefusesFailsTheReadItFollowed() {
        for (onDemand in listOf(true, false)) {
            val (module, media) = openRadio(RadioSource(), onDemand)
            media.use {
                fakeRefuseTag(module, "comment")
                assertEquals(
                    "16 icy-name=Test radio | 16 | 8 | 16 title=Café 東京 🎵 EIO",
                    fakeBridgeReads(module, 16, 20),
                    "onDemand=$onDemand",
                )
            }
        }
    }

    /** FFmpeg only sees the failed read, so the source's own exception is the cause of what it fails. */
    @Test
    fun aSourceReadOnDemandWhoseTagsThrowFailsThePacketReadWithThatCause() {
        val refusal = IllegalStateException("the title block was damaged")
        val (module, media) = openRadio(RadioSource(failAtSong = refusal), onDemand = true)
        fakeDemuxThroughSource(module)
        media.use {
            media.openPacketReader(media.streams).use { reader ->
                repeat(3) { reader.read()!!.close() }
                val failure = assertFailsWith<FFmpegException> { reader.read() }
                assertIs<FFmpegError.Io>(failure.error)
                assertSame(refusal, failure.cause)
            }
        }
    }

    @Test
    fun aStagedSourceWhoseTagsThrowFailsTheOpenWithThatCauseAndIsClosed() {
        val refusal = IllegalStateException("the title block was damaged")
        val source = RadioSource(failAtSong = refusal)
        WebIoBridge.readOnDemand = false
        useCodecModule(fakePacketReaderCodecModule())
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(source, emptyMap()) }
        assertIs<FFmpegError.Io>(failure.error)
        assertSame(refusal, failure.cause)
        assertEquals(1, source.closeCount)
    }

    /** A source that never overrides [MediaByteSource.takeTags]. */
    private class PlainSource : MediaByteSource {
        private var position = 0
        override val size: Long = 100L
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= 100) return -1
            val n = minOf(length, 100 - position)
            position += n
            return n
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }

    private companion object {
        const val SONG_BYTE = 40
        const val SONG_TAGS = "title=Café 東京 🎵 comment=second"
        const val RADIO_READS = "16 icy-name=Test radio | 16 | 8 | 16 $SONG_TAGS | 16 | 16 | 12 | -1"
    }
}

