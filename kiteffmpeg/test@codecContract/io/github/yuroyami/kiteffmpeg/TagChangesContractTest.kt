package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tags that change during playback reach a caller (#135). Each fixture changes its tags once its
 * open is long over, through the three ways FFmpeg's demuxers do it: the next song of a chained
 * Ogg replaces its stream's comments, an ID3 tag between ADTS frames merges into the container's
 * tags, and a timed ID3 packet of an MPEG-TS data stream merges into that stream's. The library
 * writes the Ogg and the AAC itself, and the ADTS framing, the ID3 tags and the transport stream
 * are built here byte by byte, so the suite needs no command-line tool and runs on a device too.
 *
 * A byte source hands FFmpeg tags of its own the same way (#168), as a station's title would come
 * through a caller's own HTTP client.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class TagChangesContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun aChainedOggBringsTheNextSongsCommentsOnItsFirstPacket() {
        val first = oggSong(seconds = 10, tags = mapOf("title" to "First song", "artist" to "Someone"))
        val second = oggSong(seconds = 2, tags = mapOf("title" to "Second song"))
        val firstPackets = MediaSource.open(BytesSource(first)).use { source -> readAll(source).size }

        val file = materializeContractMedia(first + second, sha256Hex(first + second)).also(paths::add)
        for (opened in listOf<() -> MediaSource>({ MediaSource.open(file) }, { MediaSource.open(BytesSource(first + second)) })) {
            opened().use { source ->
                val audio = assertNotNull(source.primaryAudio)
                assertEquals("First song", audio.metadata["title"], "the open read the first song's comments")
                val containerAtOpen = source.metadata
                val packets = readAll(source)

                val changed = packets.withIndex().filter { it.value.stream != null }
                assertEquals(1, changed.size, "the stream's tags changed once, at the second song:\n$packets")
                val (index, change) = changed.single()
                assertEquals(firstPackets, index, "the change rode the second song's first packet")
                val tags = assertNotNull(change.stream)
                assertEquals("Second song", tags["title"])
                assertFalse("artist" in tags, "the second song's comments replace the first's: $tags")
                assertTrue(packets.none { it.container != null }, "an Ogg's comments belong to its stream")
                assertEquals(change.stream, change.copiedStream, "a copy carries the change")

                assertEquals("First song", audio.metadata["title"], "a stream keeps what it said at open")
                assertEquals(containerAtOpen, source.metadata)
            }
        }
    }

    @Test
    fun anId3TagBetweenAdtsFramesUpdatesTheContainersTags() {
        val frames = aacFrames(seconds = 10)
        val cut = frames.size * 6 / 10
        val stream = id3("TIT2" to "Station", "TPE1" to "Radio") +
            frames.subList(0, cut).joined() +
            id3("TIT2" to "Now playing") +
            frames.subList(cut, frames.size).joined()
        val file = materializeContractMedia(stream, sha256Hex(stream)).also(paths::add)

        MediaSource.open(file).use { source ->
            assertEquals("aac", source.formatName)
            assertEquals("Station", source.metadata["title"], "the open read the leading tag")
            assertEquals("Radio", source.metadata["artist"])
            val packets = readAll(source)

            val changed = packets.withIndex().filter { it.value.container != null }
            assertEquals(1, changed.size, "the container's tags changed once:\n$packets")
            val (index, change) = changed.single()
            assertEquals(cut, index, "the change rode the first frame after the tag")
            val tags = assertNotNull(change.container)
            assertEquals("Now playing", tags["title"])
            assertEquals("Radio", tags["artist"], "an ID3 tag between frames merges into what came before")
            assertEquals(tags, source.metadata, "the source holds the change from the read that brought it")
            assertTrue(packets.none { it.stream != null })
            assertEquals(change.container, change.copiedContainer, "a copy carries the change")
        }

        MediaSource.open(file).use { source ->
            val audio = assertNotNull(source.primaryAudio)
            runBlocking { source.decodedFrames(audio).collect { it.close() } }
            assertEquals("Now playing", source.metadata["title"], "a decode flow's reads update the tags too")
            assertEquals("Radio", source.metadata["artist"])
        }
    }

    @Test
    fun aByteSourcesTagsRideThePacketThatHoldsTheirFirstByte() {
        // Long enough that the open, whose probe reads about five seconds in reads of up to 64 KiB,
        // stays well before the second song.
        val frames = aacFrames(seconds = 40)
        val cut = frames.size * 3 / 4
        val songByte = frames.subList(0, cut).sumOf { it.size }
        val stream = frames.joined()

        val station = RadioSource(stream, songByte) {
            mapOf(
                "title" to "Second song\u0000and what a C string would never see",
                "" to "a pair with no key",
                "\u0000hidden" to "a key that is empty once cut",
                "comment" to "a lone \uD800 surrogate",
            )
        }
        MediaSource.open(station).use { source ->
            assertEquals("Test radio", source.metadata["icy-name"], "the first read's tags are the open's")
            assertNull(source.metadata["title"])
            val packets = readAll(source)

            val changed = packets.withIndex().filter { it.value.container != null }
            assertEquals(1, changed.size, "the container's tags changed once:\n$packets")
            val (index, change) = changed.single()
            assertEquals(cut, index, "the change rode the packet that holds the song's first byte")
            assertEquals(
                mapOf("icy-name" to "Test radio", "title" to "Second song", "comment" to "a lone \uFFFD surrogate"),
                change.container,
                "the tags merged into what the open read, cut at a NUL, without an empty key, and repaired",
            )
            assertEquals(change.container, source.metadata)
            assertEquals(change.container, change.copiedContainer, "a copy carries the change")
            assertTrue(packets.none { it.stream != null })
        }

        MediaSource.open(BytesSource(stream, seekable = false)).use { source ->
            assertTrue(readAll(source).none { it.container != null }, "a source that reports no tags hands out none")
            assertNull(source.metadata["icy-name"])
        }
    }

    @Test
    fun aByteSourceWhoseTagsThrowFailsTheReadItFollowed() {
        val frames = aacFrames(seconds = 40)
        val cut = frames.size * 3 / 4
        val songByte = frames.subList(0, cut).sumOf { it.size }
        val refusal = IllegalStateException("the station's title block was malformed")

        MediaSource.open(RadioSource(frames.joined(), songByte) { throw refusal }).use { source ->
            var read = 0
            val failure = assertFailsWith<FFmpegException> {
                source.openPacketReader(source.streams).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.close()
                        read++
                    }
                }
            }
            assertEquals(cut, read, "every packet before the song's first byte was read")
            assertEquals(refusal, failure.cause, "the source's own exception explains the failed read")
        }
    }

    @Test
    fun aTimedId3PacketCarriesItsStreamsNewTags() {
        val stream = transportStream(
            aacFrames(seconds = 12),
            listOf(
                0L to id3("TIT2" to "First", "TPE1" to "Station"),
                8_000_000L to id3("TIT2" to "Second"),
                10_000_000L to id3("TIT2" to "Third"),
            ),
        )
        val first = mapOf("title" to "First", "artist" to "Station")
        val second = mapOf("title" to "Second", "artist" to "Station")
        val third = mapOf("title" to "Third", "artist" to "Station")

        // A stream that cannot seek is read once, from the start. The open read the first tag
        // while it probed, so only the two after it are changes.
        MediaSource.open(BytesSource(stream, seekable = false)).use { source ->
            val id3 = timedId3(source)
            assertEquals(first, id3.metadata, "the open read the first tag")
            val timed = readAll(source).filter { it.streamIndex == id3.index }
            assertEquals(listOf(null, second, third), timed.map { it.stream })
            assertEquals(first, id3.metadata, "a stream keeps what it said at open")
        }

        // FFmpeg's open measures a seekable transport stream's duration by reading its end, which
        // applies the last tag, and then reads again from the start. Every tag is then applied
        // again as its packet is read, so each packet brings its own set and a caller that follows
        // them shows the right one from the first packet on.
        val file = materializeContractMedia(stream, sha256Hex(stream)).also(paths::add)
        for (opened in listOf<() -> MediaSource>({ MediaSource.open(file) }, { MediaSource.open(BytesSource(stream)) })) {
            opened().use { source ->
                val id3 = timedId3(source)
                assertEquals(third, id3.metadata, "the open's look at the end applied the last tag")
                val packets = readAll(source)
                val timed = packets.filter { it.streamIndex == id3.index }
                assertEquals(listOf(first, second, third), timed.map { it.stream })
                assertEquals(timed.map { it.stream }, timed.map { it.copiedStream }, "a copy carries the change")
                assertTrue(
                    packets.none { it.streamIndex != id3.index && it.stream != null },
                    "a stream's change rides that stream's packet",
                )
                assertTrue(packets.none { it.container != null })
            }
        }
    }

    private fun timedId3(source: MediaSource): StreamInfo =
        source.streams.single { it.type == MediaType.Data }.also { assertEquals("timed_id3", it.codec.name) }

    /** Only the streams a reader selected bring their changes, and a change waits for its stream. */
    @Test
    fun aReaderThatSkipsAStreamNeverHandsOutItsChanges() {
        val stream = transportStream(
            aacFrames(seconds = 12),
            listOf(0L to id3("TIT2" to "First"), 8_000_000L to id3("TIT2" to "Second")),
        )
        MediaSource.open(BytesSource(stream)).use { source ->
            val audio = assertNotNull(source.primaryAudio)
            val packets = readAll(source, listOf(audio))
            assertTrue(packets.isNotEmpty())
            assertTrue(packets.none { it.stream != null || it.container != null }, "a skipped stream's change reached the audio")
        }
    }

    /** What one packet said, read while it was open. */
    private data class Seen(
        val streamIndex: Int,
        val container: Map<String, String>?,
        val stream: Map<String, String>?,
        val copiedContainer: Map<String, String>?,
        val copiedStream: Map<String, String>?,
    )

    private fun readAll(source: MediaSource, streams: List<StreamInfo> = source.streams): List<Seen> = buildList {
        source.openPacketReader(streams).use { reader ->
            while (true) {
                val packet = reader.read() ?: break
                packet.use {
                    it.copy().use { copy ->
                        add(Seen(it.streamIndex, it.newContainerTags, it.newStreamTags, copy.newContainerTags, copy.newStreamTags))
                    }
                }
            }
        }
    }

    /** [seconds] of a tone at 8 kHz as FLAC in an Ogg of its own, with [tags] as its comments. */
    private fun oggSong(seconds: Int, tags: Map<String, String>): ByteArray {
        val output = path("ogg")
        val total = RATE_OGG * seconds
        MediaSink.open(output, "ogg").use { sink ->
            sink.setMetadata(tags)
            val encoder = sink.addAudioEncoder(
                AudioEncoderSpec(codec = CodecId("flac"), sampleRate = RATE_OGG, channels = 1, sampleFormat = SampleFormat.S16),
            )
            // FLAC takes frames of its own size, all but the last.
            val block = encoder.frameSize.takeIf { it > 0 } ?: BLOCK
            runBlocking {
                encoder.drive(
                    (0 until total step block).asFlow().map { first ->
                        val count = minOf(block, total - first)
                        Frame.ofAudio(
                            bytes = tone(first, count, RATE_OGG),
                            sampleCount = count,
                            sampleRate = RATE_OGG,
                            channels = 1,
                            sampleFormat = SampleFormat.S16,
                            ptsMicros = first * 1_000_000L / RATE_OGG,
                        )
                    },
                )
            }
        }
        return readContractBytes(output)
    }

    /** [seconds] of a tone as AAC-LC at 48 kHz mono, each frame wrapped in its ADTS header. */
    private fun aacFrames(seconds: Int): List<ByteArray> {
        val pcm = path("wav")
        TranscodeFixtures.writePcm(pcm, sampleCount = RATE_AAC * seconds, sampleRate = RATE_AAC, channels = 1) { index, _ ->
            (sin(2.0 * PI * 440.0 * index / RATE_AAC) * 8_000.0).roundToInt().toShort()
        }
        val aac = path("m4a")
        runBlocking {
            Transcoder.transcode(
                input = pcm,
                output = aac,
                audioSpec = AudioEncoderSpec(codec = CodecId("aac"), sampleRate = RATE_AAC, channels = 1, bitrateBps = 64_000L),
            )
        }
        return MediaSource.open(aac).use { source ->
            buildList {
                source.openPacketReader(listOfNotNull(source.primaryAudio)).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use { add(adts(it.copyBytes())) }
                    }
                }
            }
        }
    }

    private fun tone(first: Int, count: Int, rate: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = (sin(2.0 * PI * 440.0 * (first + i) / rate) * 8_000.0).roundToInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    /**
     * A station with no size and no seek. It names itself with its first bytes and reports
     * [atSong]'s tags with the second song's first byte, [songByte], stopping a read there as
     * FFmpeg's `http` stops at a title block.
     */
    private class RadioSource(
        private val bytes: ByteArray,
        private val songByte: Int,
        private val atSong: () -> Map<String, String>?,
    ) : MediaByteSource {
        private var position = 0
        private var readStart = -1
        override val size: Long? get() = null
        override val seekable: Boolean get() = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val end = if (position < songByte) songByte else bytes.size
            val count = minOf(length, end - position)
            bytes.copyInto(into, offset, position, position + count)
            readStart = position
            position += count
            return count
        }

        override fun seek(position: Long): Unit = error("a station cannot seek")

        override fun takeTags(): Map<String, String>? = when (readStart) {
            0 -> mapOf("icy-name" to "Test radio")
            songByte -> atSong()
            else -> null
        }

        override fun close() {}
    }

    private class BytesSource(private val bytes: ByteArray, override val seekable: Boolean = true) : MediaByteSource {
        private var position = 0
        override val size: Long? get() = if (seekable) bytes.size.toLong() else null

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() {}
    }

    private companion object {
        const val RATE_OGG = 8_000
        const val RATE_AAC = 48_000
        const val BLOCK = 1_000

        /** ADTS's index for 48 kHz. */
        const val SAMPLING_INDEX_48K = 3

        const val PMT_PID = 0x1000
        const val AUDIO_PID = 0x100
        const val ID3_PID = 0x101

        fun List<ByteArray>.joined(): ByteArray {
            val out = ByteArray(sumOf { it.size })
            var at = 0
            for (part in this) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }

        /** One raw AAC-LC mono frame behind the seven-byte ADTS header that announces it. */
        fun adts(frame: ByteArray): ByteArray {
            val length = frame.size + 7
            val header = byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                ((1 shl 6) or (SAMPLING_INDEX_48K shl 2)).toByte(),
                ((1 shl 6) or (length shr 11)).toByte(),
                (length shr 3).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
            return header + frame
        }

        /** An ID3v2.4 tag holding one UTF-8 text frame for each of [frames]. */
        fun id3(vararg frames: Pair<String, String>): ByteArray {
            val body = frames.map { (id, text) ->
                val data = byteArrayOf(3) + text.encodeToByteArray()
                id.encodeToByteArray() + syncsafe(data.size) + byteArrayOf(0, 0) + data
            }.joined()
            return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size) + body
        }

        fun syncsafe(value: Int): ByteArray =
            ByteArray(4) { index -> ((value shr (21 - 7 * index)) and 0x7F).toByte() }

        /**
         * An MPEG-TS with one program: [audio] as ADTS AAC on [AUDIO_PID], and a timed ID3 stream on
         * [ID3_PID] declared the way Apple's HLS and FFmpeg's own muxer declare it, stream type 0x15
         * with a metadata descriptor naming `ID3 `. Each tag of [tags] goes out as one PES packet
         * just before the first audio frame at or after its time in microseconds.
         */
        fun transportStream(audio: List<ByteArray>, tags: List<Pair<Long, ByteArray>>): ByteArray {
            val continuity = IntArray(0x2000)
            val packets = mutableListOf<ByteArray>()
            packets += section(0, continuity, pat())
            packets += section(PMT_PID, continuity, pmt())
            var pending = tags
            audio.forEachIndexed { index, frame ->
                val micros = index * 1024L * 1_000_000L / RATE_AAC
                while (pending.isNotEmpty() && pending.first().first <= micros) {
                    packets += pes(ID3_PID, 0xBD, micros, pending.first().second, continuity)
                    pending = pending.drop(1)
                }
                packets += pes(AUDIO_PID, 0xC0, micros, frame, continuity)
            }
            return packets.joined()
        }

        fun pat(): ByteArray = psi(
            tableId = 0x00,
            idExtension = 1,
            body = byteArrayOf(0, 1, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte()),
        )

        fun pmt(): ByteArray {
            val metadataDescriptor = byteArrayOf(0x26, 13, 0xFF.toByte(), 0xFF.toByte()) +
                "ID3 ".encodeToByteArray() + byteArrayOf(0xFF.toByte()) + "ID3 ".encodeToByteArray() + byteArrayOf(0, 0x0F)
            val body = byteArrayOf((0xE0 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0) +
                elementary(0x0F, AUDIO_PID, ByteArray(0)) +
                elementary(0x15, ID3_PID, metadataDescriptor)
            return psi(tableId = 0x02, idExtension = 1, body = body)
        }

        fun elementary(type: Int, pid: Int, descriptors: ByteArray): ByteArray = byteArrayOf(
            type.toByte(),
            (0xE0 or (pid shr 8)).toByte(),
            pid.toByte(),
            (0xF0 or (descriptors.size shr 8)).toByte(),
            descriptors.size.toByte(),
        ) + descriptors

        /** A long-form PSI section with its CRC, version 0, current, one section. */
        fun psi(tableId: Int, idExtension: Int, body: ByteArray): ByteArray {
            val length = 5 + body.size + 4
            val head = byteArrayOf(
                tableId.toByte(),
                (0xB0 or (length shr 8)).toByte(),
                length.toByte(),
                (idExtension shr 8).toByte(),
                idExtension.toByte(),
                0xC1.toByte(),
                0,
                0,
            ) + body
            val crc = crc32Mpeg(head)
            return head + ByteArray(4) { index -> (crc shr (24 - 8 * index)).toByte() }
        }

        fun crc32Mpeg(bytes: ByteArray): Int {
            var crc = -1
            for (byte in bytes) {
                crc = crc xor ((byte.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        /** A PSI section in one TS packet, behind a zero pointer field and padded with 0xFF. */
        fun section(pid: Int, continuity: IntArray, section: ByteArray): ByteArray {
            val payload = byteArrayOf(0) + section
            return tsHeader(pid, start = true, continuity) + payload + ByteArray(184 - payload.size) { 0xFF.toByte() }
        }

        /** One PES packet with a presentation time, cut into TS packets, the last one stuffed. */
        fun pes(pid: Int, streamId: Int, micros: Long, data: ByteArray, continuity: IntArray): List<ByteArray> {
            val pts = 90_000L + micros * 9 / 100
            val length = 3 + 5 + data.size
            val header = byteArrayOf(
                0, 0, 1, streamId.toByte(), (length shr 8).toByte(), length.toByte(),
                0x84.toByte(), 0x80.toByte(), 5,
                (0x21L or ((pts shr 29) and 0x0E)).toByte(),
                (pts shr 22).toByte(),
                (0x01L or ((pts shr 14) and 0xFE)).toByte(),
                (pts shr 7).toByte(),
                (0x01L or ((pts shl 1) and 0xFE)).toByte(),
            )
            val packet = header + data
            return (packet.indices step 184).map { from ->
                val chunk = packet.copyOfRange(from, minOf(from + 184, packet.size))
                val start = from == 0
                if (chunk.size == 184) {
                    tsHeader(pid, start, continuity) + chunk
                } else {
                    val stuffing = 183 - chunk.size
                    val adaptation = if (stuffing == 0) byteArrayOf(0) else byteArrayOf(stuffing.toByte(), 0) + ByteArray(stuffing - 1) { 0xFF.toByte() }
                    tsHeader(pid, start, continuity, adaptation = true) + adaptation + chunk
                }
            }
        }

        fun tsHeader(pid: Int, start: Boolean, continuity: IntArray, adaptation: Boolean = false): ByteArray {
            val counter = continuity[pid]
            continuity[pid] = (counter + 1) and 0x0F
            return byteArrayOf(
                0x47,
                ((if (start) 0x40 else 0) or (pid shr 8)).toByte(),
                pid.toByte(),
                ((if (adaptation) 0x30 else 0x10) or counter).toByte(),
            )
        }
    }
}
