package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A stream FFmpeg adds after the open reaches a caller (#151).
 *
 * The fixture is a 9.4 KB transport stream in three parts, each made by FFmpeg 6.1's command line
 * with `-c:v mpeg4 -g 5 -pat_period 0.5 -sdt_period 0.5 -fflags +bitexact -flags +bitexact -f mpegts`
 * and then joined end to end:
 *
 * ```
 * ffmpeg -f lavfi -i testsrc=size=16x16:rate=5:d=1.2 $V t1.ts
 * ffmpeg -itsoffset 0.4 -f lavfi -i testsrc=size=16x16:rate=5:d=0.6 \
 *   -f lavfi -i sine=f=440:sample_rate=16000:d=1 -itsoffset 0.4 -f lavfi -i sine=f=880:sample_rate=16000:d=0.6 \
 *   -map 0:v -map 1:a -map 2:a -c:a mp2 -b:a 8k -ac 1 \
 *   -metadata:s:a:0 language=fra -metadata:s:a:1 language=eng -output_ts_offset 1.2 $V t2.ts
 * ffmpeg -f lavfi -i testsrc=size=16x16:rate=5:d=0.6 -output_ts_offset 2.2 $V t3.ts
 * ```
 *
 * So a channel's picture plays alone, two sounds join it, French first and English 0.4 s later,
 * and the picture plays alone again. Opened as a live source would be, without a seek and with half a
 * second of analysis, the open finds only the picture. FFmpeg adds both sounds from the second
 * programme table, in the read that returns the French sound's first packet, and names the English
 * sound MP3 with no channels until its own first packet goes through the parser. The third
 * programme table lists the picture alone again.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class LateStreamContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private class Read(val stream: Int, val dts: Long, val newStreams: List<StreamInfo>?, val newPrograms: List<Program>?) {
        override fun toString(): String =
            "st$stream@$dts" + (newStreams?.let { " streams=" + it.map { s -> "${s.codec.name}/${s.audio?.channels}" } } ?: "") +
                (newPrograms?.let { " programs=" + it.map(Program::streamIndexes) } ?: "")
    }

    private fun PacketReader.next(): Read? = read()?.use { Read(it.streamIndex, it.dts, it.newStreams, it.newPrograms) }

    private fun PacketReader.rest(): List<Read> = generateSequence { next() }.toList()

    private fun live(): MediaSource =
        MediaSource.open(LiveSource(LateSoundTs.bytes), mapOf("analyzeduration" to "500000"))

    /** Reads up to and including the first packet that announces new streams. */
    private fun PacketReader.untilAnnounced(): List<Read> {
        val reads = mutableListOf<Read>()
        while (reads.lastOrNull()?.newStreams == null) reads += assertNotNull(next(), "the source ended before announcing a stream")
        return reads
    }

    private fun assertSound(entry: StreamInfo, codec: String, rate: Int, channels: Int, language: String) {
        assertEquals(MediaType.Audio, entry.type)
        assertEquals(codec, entry.codec.name)
        assertEquals(rate, entry.audio?.sampleRate)
        assertEquals(channels, entry.audio?.channels)
        assertEquals(language, entry.language)
    }

    @Test
    fun aSoundAddedWhileReadingIsAnnouncedOnTheNextPacket() {
        live().use { source ->
            val picture = source.streams.single()
            assertEquals(MediaType.Video, picture.type)
            assertEquals(listOf(listOf(0)), source.programs.map { it.streamIndexes })
            assertNull(source.primaryAudio, "the open found no sound")

            source.openPacketReader(listOf(picture)).use { reader ->
                val reads = reader.untilAnnounced()
                assertEquals(listOf(126_000L, 144_000L, 162_000L, 180_000L, 198_000L), reads.map { it.dts }, "$reads")
                assertTrue(reads.all { it.stream == 0 }, "the reader hands out only the picture: $reads")
                assertTrue(reads.dropLast(1).all { it.newStreams == null && it.newPrograms == null }, "$reads")

                val announced = assertNotNull(reads.last().newStreams)
                assertEquals(3, announced.size)
                assertEquals(picture, announced[0], "the picture's entry is the one the open read")
                assertSound(announced[1], "mp2", 16_000, 1, "fra")
                // What the programme table says before the parser has seen a frame of it.
                assertSound(announced[2], "mp3", 0, 0, "eng")
                assertEquals(listOf(listOf(0, 1, 2)), reads.last().newPrograms?.map { it.streamIndexes })

                assertEquals(announced, source.streams)
                assertEquals(listOf(listOf(0, 1, 2)), source.programs.map { it.streamIndexes })
                assertEquals(1, source.primaryAudio?.index, "the French sound, the first of the picture's programme")
            }
        }
    }

    @Test
    fun theHeldPacketsOfAnAddedSoundComeFirst() {
        live().use { source ->
            source.openPacketReader(source.streams).use { reader ->
                val before = reader.untilAnnounced()
                val announced = assertNotNull(before.last().newStreams)
                reader.reselect(announced.take(2))
                val after = reader.rest()

                assertEquals(
                    listOf(231_294L, 237_774L, 244_254L, 250_734L, 257_214L),
                    after.take(5).map { it.dts },
                    "the French packets FFmpeg handed out before the announcement come first: $after",
                )
                assertTrue(after.take(5).all { it.stream == 1 }, "$after")
                assertTrue(after.none { it.stream == 2 }, "the English sound was not added: $after")
                assertEquals(14, after.count { it.stream == 1 }, "every French packet, from the first: $after")
                assertEquals(12, (before + after).count { it.stream == 0 }, "every picture packet: $after")
                assertTrue(after.all { it.newStreams == null }, "no stream changed after the announcement: $after")

                val shrunk = after.single { it.newPrograms != null }
                assertEquals(0, shrunk.stream)
                assertEquals(288_000L, shrunk.dts)
                assertEquals(listOf(listOf(0)), shrunk.newPrograms?.map { it.streamIndexes })
                assertEquals(listOf(listOf(0)), source.programs.map { it.streamIndexes })
                assertEquals("mp3", source.streams[2].codec.name, "a stream nobody read keeps the entry it was added with")
            }
        }
    }

    @Test
    fun aSoundTheCallerDoesNotAddIsSkipped() {
        live().use { source ->
            source.openPacketReader(source.streams).use { reader ->
                val reads = reader.rest()
                assertTrue(reads.all { it.stream == 0 }, "$reads")
                assertEquals(
                    listOf(126_000L, 144_000L, 162_000L, 180_000L, 198_000L, 216_000L, 270_000L, 288_000L, 306_000L, 324_000L, 342_000L, 360_000L),
                    reads.map { it.dts },
                )
                assertEquals(listOf(198_000L), reads.filter { it.newStreams != null }.map { it.dts })
                assertEquals(listOf(198_000L, 288_000L), reads.filter { it.newPrograms != null }.map { it.dts })
            }
        }
    }

    @Test
    fun anEntryIsCorrectedAtItsStreamsFirstPacket() {
        live().use { source ->
            source.openPacketReader(source.streams).use { reader ->
                val announced = assertNotNull(reader.untilAnnounced().last().newStreams)
                reader.reselect(announced)
                val after = reader.rest()

                val corrected = after.filter { it.newStreams != null }
                assertEquals(1, corrected.size, "$after")
                assertEquals(2, corrected.single().stream)
                assertEquals(267_294L, corrected.single().dts)
                val streams = assertNotNull(corrected.single().newStreams)
                assertEquals(announced.take(2), streams.take(2), "only the English entry changed")
                assertSound(streams[2], "mp2", 16_000, 1, "eng")
                assertEquals(streams, source.streams)
                assertEquals(9, after.count { it.stream == 2 }, "every English packet, from the first: $after")

                // The entry it replaced still names the stream; a forged one never did.
                val replaced = announced[2]
                reader.reselect(listOf(announced[0], replaced))
                source.openDecoder(replaced).close()
                val forged = replaced.copy(metadata = mapOf("language" to "deu"))
                assertFailsWith<IllegalArgumentException> { reader.reselect(listOf(forged)) }
                assertFailsWith<IllegalArgumentException> { source.openDecoder(forged) }
            }
        }
    }

    @Test
    fun aSeekableOpenCorrectsItsEntriesAtTheirFirstPackets() {
        val file = materializeContractMedia(LateSoundTs.bytes, LateSoundTs.sha256).also(paths::add)
        MediaSource.open(file).use { source ->
            // A seekable open reads ahead to the second programme table, before either sound's
            // first packet went through the parser.
            val open = source.streams
            assertEquals(3, open.size)
            assertSound(open[1], "mp3", 0, 0, "fra")
            assertSound(open[2], "mp3", 0, 0, "eng")

            source.openPacketReader(open).use { reader ->
                val reads = reader.rest()
                val corrections = reads.filter { it.newStreams != null }
                assertEquals(listOf(1, 2), corrections.map { it.stream }, "$reads")
                assertEquals(corrections.map { it.stream }, corrections.map { c -> reads.indexOfFirst { it.stream == c.stream } }.map { reads[it].stream })
                corrections.forEach { c -> assertSame(reads.first { it.stream == c.stream }, c, "on the stream's first packet") }

                val first = assertNotNull(corrections[0].newStreams)
                assertSound(first[1], "mp2", 16_000, 1, "fra")
                assertSound(first[2], "mp3", 0, 0, "eng")
                val second = assertNotNull(corrections[1].newStreams)
                assertSound(second[2], "mp2", 16_000, 1, "eng")
                assertEquals(second, source.streams)
                // Every entry the open listed still names its stream.
                open.forEach { source.openDecoder(it).close() }
            }
        }
    }

    @Test
    fun aDecodeFlowKeepsTheListsCurrent() {
        live().use { source ->
            val picture = source.streams.single()
            val frames = runBlocking {
                var count = 0
                source.decodedFrames(picture).collect { frame -> frame.close(); count++ }
                count
            }
            assertTrue(frames > 0)
            // A decode flow skips no stream, so FFmpeg's parser saw the English sound too.
            assertEquals(listOf("mpeg4", "mp2", "mp2"), source.streams.map { it.codec.name })
            assertEquals(listOf(listOf(0)), source.programs.map { it.streamIndexes })
            assertEquals(picture, source.streams[0])
        }
    }

    @Test
    fun aSeekDropsTheHeldPacketsOfAnAddedSound() {
        // Seekable but with no size, as an HTTP source without a length is: the open still finds the
        // picture alone, because FFmpeg reads a transport stream's end for its duration only when it
        // knows where the end is.
        MediaSource.open(UnsizedSource(LateSoundTs.bytes), mapOf("analyzeduration" to "500000")).use { source ->
            assertEquals(1, source.streams.size)
            source.openPacketReader(source.streams).use { reader ->
                val announced = assertNotNull(reader.untilAnnounced().last().newStreams)
                reader.reselect(announced.take(2))
                reader.seek(0)
                val after = reader.rest()

                assertEquals(
                    listOf(126_000L, 144_000L, 162_000L, 180_000L, 231_294L),
                    after.take(5).map { it.dts },
                    "the French packets held before the seek went with it: $after",
                )
                assertEquals(listOf(0, 0, 0, 0, 1), after.take(5).map { it.stream }, "$after")
                assertEquals(14, after.count { it.stream == 1 }, "each French packet once: $after")
            }
        }
    }

    @Test
    fun aChangeADecodeFlowReadRidesTheNextReadersFirstPacket() {
        MediaSource.open(UnsizedSource(LateSoundTs.bytes), mapOf("analyzeduration" to "500000")).use { source ->
            val picture = source.streams.single()
            runBlocking { source.decodedFrames(picture).collect { it.close() } }
            val listed = source.streams
            assertEquals(3, listed.size)

            source.openPacketReader(listOf(picture)).use { reader ->
                reader.seek(0)
                val first = assertNotNull(reader.next())
                assertEquals(0 to 126_000L, first.stream to first.dts)
                assertEquals(listed, first.newStreams, "no packet carried the change before: $first")
                val rest = reader.rest()
                assertTrue(rest.none { it.newStreams != null }, "$rest")
            }
        }
    }

    /** Bytes that seek but have no size. */
    private class UnsizedSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long? get() = null
        override val seekable: Boolean get() = true

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

    /** Bytes as a live source hands them over: no size and no seek. */
    private class LiveSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long? get() = null
        override val seekable: Boolean get() = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) = error("a live source does not seek")

        override fun close() {}
    }
}

private object LateSoundTs {
    const val sha256: String = "4d3e125d03d2523950bf9def4ca6efe2de22a61e637991f8abb833998c3c6948"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 9400) { "LateSoundTs fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "LateSoundTs fixture digest changed" }
        }
    }

    private val DATA: String = """
R0AREABC8CUAAcEAAP8B/wAB/IAUSBIBBkZGbXBlZwlTZXJ2aWNlMDF3fEPK////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////9HQAAQAACwDQABwQAAAAHwACqxBLL/////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/0dQABAAArASAAHBAADhAPAAEOEA8ACPQ+LH////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////R0EAMAdQAAB7DH4AAAAB4AAAgIAFIQAH2GEAAAGwAQAAAbWJ
EwAAAQAAAAEgAMSNiAAtAIQCFGMAAAGzABAHAAABthGBNhdgHwhA8B/Hg8B+4gzY+EsISYSwDAQBDH4KJoSgOiUXJ/fLwDvCWIQQ
U7Kr+j9UrxkIIIA5Er/+XB8wXNh8XJfpceG0GAOBhGVg8B+qgw8EcIABohiWPQUaUSh6DgDgPiGEEet6wENgGA2nEoGUq2cEnfiU
2lBHAQARhCQkEsHJE6fBGxvB4ICsQPmB2B5KDUGEgHgP4EGHbIPAwJ4Bok/CGChBRgpUioEQel2twD4lgdEPUyoHgoBtLU4KrwB/
pqv2B7nsZrTEVyGBSDwEE2EMG8DwEDyCiCACGlBmQQB/E5aOUysSR0EKJO6O/B6r+XpUTYkjhdrwetKEf+GIJF+DKvgwIbQMAf39
BDwGCFAYDimA8D/k4XeygzY5D7RIBhz4GaivWxCXL6IwMp+x7gf6PEcBADJ3AP//////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////QYLIXzVQM0CjBk4KIt8CgTwGTURmxGLaXtYH4PAQPfwYENQITXQhjryVWqBgUYOANmlyrxfo9A/75bAD1
AM9/R0EAM04QAACeNH4A////////////////////////////////////////////////////////////////////////////////
//////////////8AAAHgAACAgAUhAAllAQAAAbZTwIhUx8JBf8fiRR/8uo+VgfHgIX9U2Z4d996qB2BjB0NB+Jarw+EvxcqL4Jaq
qlNEv2KJd/FDf4xBFM50fg2AzKwiAyEDQzBQgw6she2rawDoOBSKcIVHQQA0TRAAAMFcfgD/////////////////////////////
////////////////////////////////////////////////////////////////AAAB4AAAgIAFIQAJ8aEAAAG2VYCIVNUPrVYk
eEv/h4rv8BsoG5OjtigxYybVj/0VBD+JXvwvVTymCXBGtub7l/7YBUgUDsf/ErwM3G9HXmwU/8VUGGoHwYdfVKlfPqxGUAdjDf1S
mRHXP0dAEREAQvAlAAHBAAD/Af8AAfyAFEgSAQZGRm1wZWcJU2VydmljZTAxd3xDyv//////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////R0AAEQAAsA0AAcEAAAAB8AAqsQSy////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////9HUAARAAKwEgABwQAA4QDwABDhAPAAj0Pix///////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////0dBADVCEAAA5IR+AP//////////////////////////
////////////////////////////////////////////////////AAAB4AAAgIAFIQALfkEAAAG2V8CIVMGyghlwQlauUSviWX1U
rVfbEiAfvh79TPcyKWvcMg2g3C8IapUDDvwlFylUr80JNA8pHnsU92qG/3hoeAdL/iVAZvzcanOUDtz1RWnwPg3C/w+ViP9qQdwC
PtVRHRe/R0EANlEQAAEHrH4A////////////////////////////////////////////////////////////////////////////
//////////////////////8AAAHgAACAgAUhAA0K4QAAAbZZgIhU6XQewICn48BtwD6kHhIA9oDbVZEUGPRXR4DD3IPQbAZqwHhP
/MGSZqwiAx8eA3VdLlXfqtUqR1EX/56gSePQUQ/glf4XqxGwdy7GVcHUR1xHQAASAACwDQABwQAAAAHwACqxBLL/////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////0dQABIAArASAAHBAADhAPAAEOEA8ACPQ+LH////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////R0EANwdQAAEq1H4AAAAB4AAAgIAFIQANl4EAAAGw
AQAAAbWJEwAAAQAAAAEgAMSNiAAtAIQCFGMAAAGzABBHAAABthGBNhdgHwhA8B/Hg8B+4gzY+EsISYSwDAQBDH4KJoSgOiUXJ/fL
wDvCWIQQU7Kr+j9UrxkIIIA5Er/+XB8wXNh8XJfpceG0GAOBhGVg8B+qgw8EcIABohiWPQUaUSh6DgDgPiGEEet6wENgGA2nEoGU
q2cEnfiU2lBHAQAYhCQkEsHJE6fBGxvB4ICsQPmB4DAGD8G0IAMEEdgfEsGEgA0fD0GH4N8FCCD8A4vSDsuShC8B0dgfCH/2iCJE
bT4PPAG5mq6kST+NYzfMRXMFgoBgOCGDNA8BBTpE49YBk4IAMlLh00z9Ml8JI6CEmEuloltFjDOF4QfdLRLLCqsh75RzvwEQGy/E
YFCDwH8SDMAw/6yEES/AwkA8DAotJ4DAij4feY0GbD8FMIbAIohAoIr1sQuVskcBADltAP//////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////HQMOfseUAb0eUGCyA2zQZodCGPx8Wqh8AfAYuoIFAMLRL3M1kHgIH34MAb1oQgRVYHvJVbAMCjBwIMS4XeS6Ow
P+ZUg4DtwGe/R0AREABC8CUAAcEAAP8B/wAB/IAUSBIBBkZGbXBlZwlTZXJ2aWNlMDF3fEPK////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////9HQAAQAACwDQABwQAAAAHwACqxBLL/////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////0dQABAAArAoAAHBAADhAPAAEOEA8AAE4QHwBgoEZnJhAAThAvAGCgRlbmcA3mRMM///////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////R0EBMAFAAAABwAFwgIAFIQAPDv3/9RjEQhEE
AAAAAAAABYJ25mJKr37uZarY+a1revmtbGDnK4LZ8c1qggkr0iNXTbojjC1qIgatyAHSL2gCsCpoAmasyUXqI4j/9RjEUhEEAAAA
AAAAFVZGK65gYDgN0YgyyrRGXsqYiJCE2sIlQ1NwmWMhGiYgUqsJDv0rSAcoLMgYgKk0BH1iHRCKwDWgAAD/9RjEUhEEAAAAAAAA
FVZGK65kImcliQlHAQER+3OaQmIANoiTfJNCgfCquYGCS3QgVXQ9GQ4wscJCBrDIkPSL2mQrAqaYiZqzIAAA//UYxFIRBAAAAAAA
ABVWRiuuZCnqI4gYDgN0YgyyrRGXsqYiJCE2sIlQ1NwmWMhGiYgUqsJDv0rSAcoLMgYgKk0BH1iHQAAA//UYxFIRBAAAAAAAABU2
Riu+uZCKwDWkImcliQn7c5pCYgA2iJN8k0KB8Kq5gYJMNCBVdD0ZDjCxwkIGsMiQ9EcBATKvAP//////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////4vaZCsCpoAAR0AAEQAAsA0AAcEAAAAB8AAqsQSy////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////9HUAARAAKwKAABwQAA4QDwABDhAPAABOEB
8AYKBGZyYQAE4QLwBgoEZW5nAN5kTDP/////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////0dBADAHUAABlEx+AAAAAeAAAICABSEAET1hAAABsAEAAAG1iRMAAAEAAAABIADEjYgALQCEAhRjAAABswAQ
BwAAAbYVgTYXYB8IQPAfx4PAfuIM2PhLCEmEsAwEAQx+CiaEoDolFyf3y8A7wliEEFOyq/o/VK8ZCCCAORK//lwfMFzYfFyX6XHh
tBgDgYRlYPAfqoMPBHCAAaIYlj0FGlEoeg4A4D4hhBHresBDYBgNpxKBlKtnBJ34lNpQRwEAEYQkJBLByROnwRsbweCArED5gdge
Sg1BhIB4D+BBh2yDwMCeAaJPwhgoQUYKVIqBEHpdrcA+JYHRD1MqB4KAbS1OCq8Af6ar9ge57Ga0xFchgUg8BBNhDBvA8BA8gogg
AhpQZkEAfxOWjlMrEkdBCiTujvweq/l6VE2JI4Xa8HrShH/hiCRfgyr4MCG0DAH9/QQ8BghQGA4pgPA/5OF3soM2OQ+0SAYc+Bmo
r1sQly+iMDKfse4H+jxHAQAydwD/////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////0GCyF81UDNAowZOC
iLfAoE8Bk1EZsRi2l7WB+DwED38GBDUCE10IY68lVqgYFGDgDZpcq8X6PQP++WwA9QDPf0dAEREAQvAlAAHBAAD/Af8AAfyAFEgS
AQZGRm1wZWcJU2VydmljZTAxd3xDyv//////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////R0EAM04QAAG3dH4A////////////////////////////////////////////////////////////////
//////////////////////////////8AAAHgAACAgAUhABHKAQAAAbZXwIhUx8JBf8fiRR/8uo+VgfHgIX9U2Z4d996qB2BjB0NB
+Jarw+EvxcqL4JaqqlNEv2KJd/FDf4xBFM50fg2AzKwiAyEDQzBQgw6she2rawDoOBSKcIVHQQEzAUAAAAHAAXCAgAUhABEMHf/1
GMRSEQQAAAAAAAAVFkYrxrxmYiZqzJCnqI4gYDgT0YgyyrRGXsqYiJCE2sIlQ1NwmWMhGiYgUqsJDv0rSAcoI8gYgKl0AP/1GMRS
EQQAAAAAAAAVFkYrrxrmBH1iHRCKwDWkImcliQn7c5pCYgA2iJN8k0KB8Kq5gYJL9CBVdD0ZDjCxwkIGsMiQ9IvaAP/1GMRSEQQA
AAAAAAAVFkYrxrxmZCsClkcBARSYiZqzJCnqI4gYDgT0YgyyrRGXsqYiJCE2sIlQ1NwmWMgGiYgUqsJDv0rSAcoI8gD/9RjEUhEE
AAAAAAAAFVZGK65gYgKk0BH1iHRCKwDWkImcliQn7c5pCYgA2iJN8k0KB8Kq5gYJLdCBVdD0ZDjCxwkIGsMgAAD/9RjEUhEEAAAA
AAAAFVZGK65iQ9IvaZCsCppiJmrMkKeojiBgOA3RiDLKtEZeypiIkITawiVDU3CZYyEaJiBSRwEBNa8A////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////qwkO/StAAABHQQIwAUAAAAHAAXCAgAUhABEoPf/1GMRSIAQAAAAAAAAAC92StfKtmy9+zu75812h8+afWcOwiHfP
m9LN2r1VXz5qMr581UZ8+aBF7pWQR7gtAZLLmjElleIAAP/1GMRSEQQAAAAAAAARVlrzrsZsJD1AKJkMNqLiJwG4yRhhSq5gXZxX
Eg4yQ0IHjrXRGOBwtEJVwoyJlZZiAiMhsrmR8AliAP/1GMRSEQQAAAAAAAAXVlzrsZsBhehCJEcBAhFJ8bXIiHtKtGR7Io0JC+Ji
5CclMrkI8WigJk+JHRgcWtcgRXGqsgjYCXQGT/OdAAD/9RjEUhEEAAAAAAAAFVZa7GbGJP2yiJD1AKJkMNqLiJwG4yRhhSq5gVdx
XEgxCQ0IHErXRGMNwtEJVwoyJlZZiAiMhsrgAAD/9RjEUhEEAAAAAAAAFVZa7GbGR8AliBg/hCJEl1tciIXEq0ZHXijQkL4mLkJy
UyuQjxaKAmT4kdGBxa1yBFcaRwECMq8A////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////qyCNgJdAAABHQQA0TRAAAdqcfgD/////////
////////////////////////////////////////////////////////////////////////////////////AAAB4AAAgIAFIQAT
VqEAAAG2WYCIVNUPrVYkeEv/h4rv8BsoG5OjtigxYybVj/0VBD+JXvwvVTymCXBGtub7l/7YBUgUDsf/ErwM3G9HXmwU/8VUGGoH
wYdfVKlfPqxGUAdjDf1SmRHXP0dBATYBQAAAAcABKICABSEAEwk9//UYxFIRBAAAAAAAABVWRiuuaAcoLMgYgKk0BH1iHRCKwDWk
ImcliQn7c5pCYgA2iJN8k0KB8Kq5gYJLdCBVdD0ZDjCxwAAA//UYxFIRBAAAAAAAABVWRiuuYkIGsMiQ9IvaZCsCppiJmrMkKeoj
iBgOA3RiDLKtEZeypiIkITawiVDU3CZYyEaJiBSqwAAA//UYxFIRBAAAAAAAABVWRiuuYkO/StIBRwEBNz8A////////////////
///////////////////////////////////////////////////////////////////KCzIGICpNAR9Yh0QisA1pCJnJYkJ+3OaQ
mIANoiTfJNCgfCquYECS3QgVXRNAAAD/9RjEQhEEAAAAAAAABQ55RmK77TaVgDjCO4AAalyAHSL7ASsCpoFmavyUfqI4gSOA3QAs
st0tnsrWrYIS1q2UNNaGOMliAABHQQIzAUAAAAHAASiAgAUhABMlXf/1GMRSEQQAAAAAAAAVVlrsZsBk/znRiT9soiQ9QCiZDDai
4icBuMkYYUquYFXcVxIMQkNCBxK10RjDcLRCVcKMiZWWYgAAAP/1GMRSEQQAAAAAAAAVVlrsZsIjIbK5kfAJYgYP4QiRJdbXIiFx
KtGR14o0JC+Ji5CclMrkI8WigJk+JHRgcWtcgRXGqsAAAP/1GMRSEQQAAAAAAAAVVlrsZsgjYCXQGUcBAjQ/AP//////////////
////////////////////////////////////////////////////////////////////P850Yk/bKIkPUAomQw2ouInAbjJGGFKr
mBV3FcSDEJDQgcStdEYw3C0QlXCjIAAA//UYxFIgBAAAAAAAAAAZL2aq17K2bL3CZWVMBEZD+5kfA3EDB/Di7u+fNd2fV22B4L6m
Z3z5oG0WOt3d8+a7u+fNd3fPmgAAR0AREABC8CUAAcEAAP8B/wAB/IAUSBIBBkZGbXBlZwlTZXJ2aWNlMDF3fEPK////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////9HQAAQAACwDQABwQAAAAHw
ACqxBLL/////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////0dQABAAArASAAHBAADhAPAAEOEA8ACPQ+LH////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////R0EAMAdQAAH9xH4AAAAB
4AAAgIAFIQAT40EAAAGwAQAAAbWJEwAAAQAAAAEgAMSNiAAtAIQCFGMAAAGzABAHAAABthGBNhdgHwhA8B/Hg8B+4gzY+EsISYSw
DAQBDH4KJoSgOiUXJ/fLwDvCWIQQU7Kr+j9UrxkIIIA5Er/+XB8wXNh8XJfpceG0GAOBhGVg8B+qgw8EcIABohiWPQUaUSh6DgDg
PiGEEet6wENgGA2nEoGUq2cEnfiU2lBHAQARhCQkEsHJE6fBGxvB4ICsQPmB2B5KDUGEgHgP4EGHbIPAwJ4Bok/CGChBRgpUioEQ
el2twD4lgdEPUyoHgoBtLU4KrwB/pqv2B7nsZrTEVyGBSDwEE2EMG8DwEDyCiCACGlBmQQB/E5aOUysSR0EKJO6O/B6r+XpUTYkj
hdrwetKEf+GIJF+DKvgwIbQMAf39BDwGCFAYDimA8D/k4XeygzY5D7RIBhz4GaivWxCXL6IwMp+x7gf6PEcBADJ3AP//////////
////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////QYLIXzVQM0CjBk4KIt8CgTwGTURmxGLaXtYH4PAQPfwYENQITXQhj
ryVWqBgUYOANmlyrxfo9A/75bAD1AM9/R0EAM04QAAIg7H4A////////////////////////////////////////////////////
//////////////////////////////////////////8AAAHgAACAgAUhABVv4QAAAbZTwIhUx8JBf8fiRR/8uo+VgfHgIX9U2Z4d
996qB2BjB0NB+Jarw+EvxcqL4JaqqlNEv2KJd/FDf4xBFM50fg2AzKwiAyEDQzBQgw6she2rawDoOBSKcIVHQQA0TRAAAkQUfgD/
////////////////////////////////////////////////////////////////////////////////////////////AAAB4AAA
gIAFIQAV/IEAAAG2VYCIVNUPrVYkeEv/h4rv8BsoG5OjtigxYybVj/0VBD+JXvwvVTymCXBGtub7l/7YBUgUDsf/ErwM3G9HXmwU
/8VUGGoHwYdfVKlfPqxGUAdjDf1SmRHXPw==
""".trimIndent()
}
