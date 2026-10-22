package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An AAC sound FFmpeg adds after the open learns its rate and its channels at its first packet, and
 * every packet of it is dated and timed (#174).
 *
 * FFmpeg's AAC parser leaves the rate and the channels unset on purpose, because an ADTS header
 * misstates them for HE-AAC, and lets the open learn them by decoding. A sound that starts after the
 * open never went through that, so without the vendored patch its entry kept no rate and no channels
 * and only the first frame of each transport packet carried a timestamp, with no duration on any.
 *
 * The fixture is a 10.2 KB transport stream in two parts, each made by FFmpeg 6.1's command line
 * with `-c:v mpeg4 -g 5 -pat_period 0.5 -sdt_period 0.5 -fflags +bitexact -flags +bitexact -f mpegts`
 * and then joined end to end:
 *
 * ```
 * ffmpeg -f lavfi -i testsrc=size=16x16:rate=5:d=1.2 $V t1.ts
 * ffmpeg -f lavfi -i testsrc=size=16x16:rate=5:d=1.2 -f lavfi -i sine=f=440:sample_rate=16000:d=1.2 \
 *   -map 0:v -map 1:a -c:a aac -b:a 16k -ac 1 -metadata:s:a:0 language=fra -output_ts_offset 1.2 $V t2.ts
 * ```
 *
 * So a channel's picture plays alone for 1.2 seconds and then a mono 16 kHz AAC sound joins it, in
 * 20 frames of 1024 samples packed into four transport packets.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class LateAacContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private class Read(val stream: Int, val pts: Long, val dts: Long, val duration: Long, val newStreams: List<StreamInfo>?) {
        override fun toString(): String =
            "st$stream@$pts/$dts+$duration" + (newStreams?.let { " streams=" + it.map { s -> "${s.codec.name}/${s.audio?.sampleRate}/${s.audio?.channels}" } } ?: "")
    }

    private fun PacketReader.next(): Read? =
        read()?.use { Read(it.streamIndex, it.pts, it.dts, it.duration, it.newStreams) }

    private fun PacketReader.rest(): List<Read> = generateSequence { next() }.toList()

    private fun live(): MediaSource =
        MediaSource.open(LiveSource(LateAacTs.bytes), mapOf("analyzeduration" to "500000"))

    /** One AAC frame, 1024 samples at 16 kHz, on the transport stream's 90 kHz clock. */
    private val frameTicks = 5_760L

    /** The first frame's time: the sound starts at 1.2 s, behind FFmpeg's 1.4 s offset and its 1024 priming samples. */
    private val firstTicks = 228_240L

    private fun assertDatedAndTimed(sound: List<Read>) {
        assertEquals(20, sound.size, "every AAC frame: $sound")
        assertEquals(List(20) { firstTicks + it * frameTicks }, sound.map { it.pts }, "$sound")
        assertEquals(sound.map { it.pts }, sound.map { it.dts }, "$sound")
        assertTrue(sound.all { it.duration == frameTicks }, "$sound")
    }

    @Test
    fun aLateAacSoundLearnsItsRateAndChannelsAtItsFirstPacket() {
        live().use { source ->
            val picture = source.streams.single()
            source.openPacketReader(source.streams).use { reader ->
                val before = mutableListOf<Read>()
                while (before.lastOrNull()?.newStreams == null) before += assertNotNull(reader.next(), "no stream was added: $before")
                val announced = assertNotNull(before.last().newStreams)
                assertEquals(2, announced.size)
                assertEquals(picture, announced[0])
                // What the programme table says before the parser has seen a frame of it.
                assertEquals("aac", announced[1].codec.name)
                assertEquals(0, announced[1].audio?.sampleRate)
                assertEquals(0, announced[1].audio?.channels)

                reader.reselect(announced)
                val after = reader.rest()
                val sound = after.filter { it.stream == 1 }
                val corrected = after.single { it.newStreams != null }
                assertSame(sound.first(), corrected, "on the sound's first packet: $after")
                val settled = assertNotNull(corrected.newStreams)[1]
                assertEquals("aac", settled.codec.name)
                assertEquals(16_000, settled.audio?.sampleRate)
                assertEquals(1, settled.audio?.channels)
                assertEquals("fra", settled.language)
                assertEquals(settled, source.streams[1])
                assertDatedAndTimed(sound)
            }
        }
    }

    @Test
    fun aSeekableOpenSettlesItsListedAacEntryAtItsFirstPacket() {
        val file = materializeContractMedia(LateAacTs.bytes, LateAacTs.sha256).also(paths::add)
        MediaSource.open(file).use { source ->
            // A seekable open reads ahead to the second programme table, before the sound's first
            // packet went through the parser.
            val open = source.streams
            assertEquals(listOf("mpeg4", "aac"), open.map { it.codec.name })
            assertEquals(0, open[1].audio?.sampleRate)

            source.openPacketReader(open).use { reader ->
                val reads = reader.rest()
                val sound = reads.filter { it.stream == 1 }
                val corrected = reads.single { it.newStreams != null }
                assertSame(sound.first(), corrected, "on the sound's first packet: $reads")
                val settled = assertNotNull(corrected.newStreams)[1]
                assertEquals(16_000, settled.audio?.sampleRate)
                assertEquals(1, settled.audio?.channels)
                assertDatedAndTimed(sound)
                // The entry the open listed still names its stream.
                open.forEach { source.openDecoder(it).close() }
            }
        }
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

private object LateAacTs {
    const val sha256: String = "4c8a9b15a16d30f6a4eb079ed820e1f85c66cd0a5c7df86a4846a8edf22542fa"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 10152) { "LateAacTs fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "LateAacTs fixture digest changed" }
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
/////////////0dQABAAArAdAAHBAADhAPAAEOEA8AAP4QHwBgoEZnJhABWqrLL/////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////R0EAMAdQAAFN/H4AAAAB4AAAgIAFIQAPJCEA
AAGwAQAAAbWJEwAAAQAAAAEgAMSNiAAtAIQCFGMAAAGzABAHAAABthGBNhdgHwhA8B/Hg8B+4gzY+EsISYSwDAQBDH4KJoSgOiUX
J/fLwDvCWIQQU7Kr+j9UrxkIIIA5Er/+XB8wXNh8XJfpceG0GAOBhGVg8B+qgw8EcIABohiWPQUaUSh6DgDgPiGEEet6wENgGA2n
EoGUq2cEnfiU2lBHAQARhCQkEsHJE6fBGxvB4ICsQPmB2B5KDUGEgHgP4EGHbIPAwJ4Bok/CGChBRgpUioEQel2twD4lgdEPUyoH
goBtLU4KrwB/pqv2B7nsZrTEVyGBSDwEE2EMG8DwEDyCiCACGlBmQQB/E5aOUysSR0EKJO6O/B6r+XpUTYkjhdrwetKEf+GIJF+D
KvgwIbQMAf39BDwGCFAYDimA8D/k4XeygzY5D7RIBhz4GaivWxCXL6IwMp+x7gf6PEcBADJ3AP//////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////QYLIXzVQM0CjBk4KIt8CgTwGTURmxGLaXtYH4PAQPfwYENQITXQhjryVWqBgUYOANmlyr
xfo9A/75bAD1AM9/R0EAM04QAAFxJH4A////////////////////////////////////////////////////////////////////
//////////////////////////8AAAHgAACAgAUhAA+wwQAAAbZTwIhUx8JBf8fiRR/8uo+VgfHgIX9U2Z4d996qB2BjB0NB+Jar
w+EvxcqL4JaqqlNEv2KJd/FDf4xBFM50fg2AzKwiAyEDQzBQgw6she2rawDoOBSKcIVHQQEwAUAAAAHAA7uAgAUhAA33If/xYEAW
v/wBIlCtMHSKpXdfZ9acUTJUlypSEkGjsw5izDbWK4liuktG6S4tzFsXR24YtGMxTzPVM5SzipTTwnEP4eo4W2/f6mgL+4O61uVm
5VrVbndbXXbW2qZ02dNamtTWmWmSmSmSiSiWiSgYGBgYGBgYGBgYGBgYGBkQMDAwMDAwMDIjZs2bBkSKWWWWWWWWWWWWWWWWWWWW
WWWWWWWWWWWWWWWWWUcBARFllllllllllllllll4//FgQBcf/AD0ltrZc2XLIsJtQkwm4nD/+t/3+ta01quf/+X/r+I1bhz//V/9
v1NS7vv//l/5/uak4sUt32ECSG6Xnnngl5IYJF6KsLlmwePnYvI8vee0tx77+du6md6ed6ed7mnenUp5M5zqee5qqVYS92fvUc+/
PZ++hCEBAwiFJdSLGDKRopUkdUgwTdJBN0GCboMEhOQNZdu2iR1cJK6/Gqv0NI+3A53CRwEBEjNKYGBgYkOZBs1Ds+D/8WBAFd/8
AOzwrTQ2GUDE58b+ff9s/0+K0ky1rktF6qTgUt9e/pfZn9Iv13Oouye1eLcJULm86/1T1PUWVkLtp3jVK7cNk1P9aLNSb9tzVTTq
pkuJ2nE2GNMuBmTZs28JkKvDDDBxNqcZmYe/v7jJ8Zhn99AyfGYZ/fQH/jMHffQh/4zBecgf4Aa9yA8cws9/cHyfAT77QPj4YnR7
g/x41n9FCfw5/Reiwl1HAQETK5jg//FgQBGf/ADkMK0sQ2qd1p+z/+/X//hN9V3L3N9a36+F703xsNTC1NM9TzTzh39xrzF5zbXT
P175FM5WJhYmliOACjOoawAqAGfqdjJ2+bu68Ma6a2dnAmdtzP0nXItBPxm7MLObSYIrEVjG8KDnJQX+cl8AsMhkICwzI58Bx0EB
gO4PqkWG4CQCrOD/8WBAEX/8AOIwoHZYGQ1mS/Ff/2/+P//HGqrjua7mprfPnxrrnVVoNEcBARTLTM6bOuY1rbaGnn7e16zV0vp8
vp8vpVAL9LAP0FwBx8V96/eLefefeoo10YGoUYgVYvYZDISL92hYAuBQXkNB0h65FgkV4i8hwDtD5hYUEchz4CvAXkOAYDyAHGBc
hIroOP/xYEAQP/wA6DCYdmYZxUJjcV5/N//3P6//90m75kyb+Jzzd1w1mcBzA7b7enft3/J/a80XvxTSNbBE4g1sIB6pdGf+NOgG
f5sjN2d/Z1WXWQcLB3K7RwEBNYwA////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////8XIrkFjCzHJIrplyDIRWNlOD2icGsd1mKJD18hz4C/YQeeGJ4BRKDSDz5xHQQA0TRAAAZRMfgD/////////////
////////////////////////////////////////////////////////////////////////////////AAAB4AAAgIAFIQARPWEA
AAG2VYCIVNUPrVYkeEv/h4rv8BsoG5OjtigxYybVj/0VBD+JXvwvVTymCXBGtub7l/7YBUgUDsf/ErwM3G9HXmwU/8VUGGoHwYdf
VKlfPqxGUAdjDf1SmRHXP0dAEREAQvAlAAHBAAD/Af8AAfyAFEgSAQZGRm1wZWcJU2VydmljZTAxd3xDyv//////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////R0AAEQAAsA0AAcEAAAAB8AAqsQSy
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////9HUAARAAKwHQABwQAA4QDwABDhAPAAD+EB8AYKBGZyYQAVqqyy////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////0dBADVCEAABt3R+AP//////////
////////////////////////////////////////////////////////////////////AAAB4AAAgIAFIQARygEAAAG2V8CIVMGy
ghlwQlauUSviWX1UrVfbEiAfvh79TPcyKWvcMg2g3C8IapUDDvwlFylUr80JNA8pHnsU92qG/3hoeAdL/iVAZvzcanOUDtz1RWnw
Pg3C/w+ViP9qQdwCPtVRHRe/R0EBNgFAAAABwAMxgIAFIQARBSH/8WBAEL/8AOYwoNZSGrTM03j//N5//9am0wyvNe3j11nSO+gZ
aotZOVk/m2uVseFY2WTlXNKxytjsqlMAZc9YGRtgFyzdEXNEXM6WJhTJWXy+Xynqhn5BUC4FaDQZCxXv2KBAuQgVoLCQ7goLDj4y
PEWMDKY0z3Xf7r5YJmF5uAvBUN44//FgQBB//ADqMKDWRBqc2tSsr/+9/b//e59nNVbfVdd821tHAQEX4tQBEEqbOm19rbWttwa6
1zq22i52h0XefAHF/C6DXgG9b2q2Pny2kqtLL8m+TARvndcoEC4DgLoO0KD75JFyCpiSMTOUTP4KWFcqkYUz9W7/dfKJY3nxieZi
q78FQ9Zw//FgQBG//ADmMIQwWyINaL1PXr/+z/X//97dy63xlOju1dTnjnzBKQE0JOC7yNnzP5/J/nQbsMtBNBQBcdb1dDLwCeIO
0J54jOINYGRAwO/dELKkIF7F7EcBARgwGQ0H8OASEBISEh3hgMQLkWH6faOPQL7BfwDvCgoJ4DnwF+oIDkH0iP+tihfu4DmFQOPM
4P/xYEARH/wA3jCpaDUxtVb1X/+br//zoq29c1d+3rx5qtEzgT07XpGSkZKVksjksj3LNeK4l6MyTy9PL0JFE781/8CIotittE80
Ty6YphRneqQkiVuXSoTdqeASHKSOgJCQ9ImIthRKrMe47xVOL5E7qx4rvGNI9i7/de0TwPSzE74WRwEBGe6qRe1c//FgQBBf/ADe
MKi2OCHUuvXz//a9f/x/SlVrnel+3v3xe5xK59g2ax0bHRsdXbFjde13sWs69vKqEfn0YUYoAS9OywaaAFdWxiedm86tjRSopRIg
lG5nHsbXOIvaJY2QizHqiZQgEVirObJF7icQimQTPcd/uvkSDfEGQJRaZF584P/xYEARX/wA5DCIMFspodyS/W//6fv//+1fNUmv
U8zj14++9Xz13xQGoYBxZ7FXUeFHAQE6XgD/////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////9zu+4buKdQ2wWEAbkmJ5dXAGLzBB2iDuhmkW0idB0
znY0ypFoHdbkgmX5bCRQVASEBIagcmRQJHk0xv0A2i+QG4KCw42wdOlJ0ZhIaIaQdfAiRxoFyAXgcEdBADZREAAB2px+AP//////
////////////////////////////////////////////////////////////////////////////////////////////AAAB4AAA
gIAFIQATVqEAAAG2WYCIVOl0HsCAp+PAbcA+pB4SAPaA21WRFBj0V0eAw9yD0GwGasB4T/zBkmasIgMfHgN1XS5V36rVKkdRF/+e
oEnj0FEP4JX+F6sRsHcuxlXB1EdcR0AAEgAAsA0AAcEAAAAB8AAqsQSy////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////9HUAASAAKwHQABwQAA4QDw
ABDhAPAAD+EB8AYKBGZyYQAVqqyy////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////0dBADcHUAAB/cR+AAAAAeAAAICABSEAE+NBAAABsAEAAAG1iRMAAAEAAAABIADEjYgALQCE
AhRjAAABswAQRwAAAbYRgTYXYB8IQPAfx4PAfuIM2PhLCEmEsAwEAQx+CiaEoDolFyf3y8A7wliEEFOyq/o/VK8ZCCCAORK//lwf
MFzYfFyX6XHhtBgDgYRlYPAfqoMPBHCAAaIYlj0FGlEoeg4A4D4hhBHresBDYBgNpxKBlKtnBJ34lNpQRwEAGIQkJBLByROnwRsb
weCArED5geAwBg/BtCADBBHYHxLBhIANHw9Bh+DfBQgg/AOL0g7LkoQvAdHYHwh/9ogiRG0+DzwBuZqupEk/jWM3zEVzBYKAYDgh
gzQPAQU6ROPWAZOCADJS4dNM/TJfCSOghJhLpaJbRYwzheEH3S0SywqrIe+Uc78BEBsvxGBQg8B/EgzAMP+shBEvwMJAPAwKLSeA
wIo+H3mNBmw/BTCGwCKIQKCK9bELlbJHAQA5bQD/////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////x0DDn7HlAG9HlBgsg
Ns0GaHQhj8fFqofAHwGLqCBQDC0S9zNZB4CB9+DAG9aEIEVWB7yVWwDAowcCDEuF3kujsD/mVIOA7cBnv0dBATsBQAAAAcADOICA
BSEAExMh//FgQBDf/ADkMKjWKhkI2uQxuLz3f/5f3//kud6wcyvbW8zq/F8czgGTQrKRr1d5lkdi33fvTcy33fkr08wTy9JTYAZj
P4Z8A5jmVteqF6g0yVMK6nnmn9epppRUTouRSNlIokVSq8wY0jmJReThhzACwsLAE7Bv2iuGRZyDzMVF+40w9o7/8WBAEN/8AOIw
qNYqGsRKkrw//q+///67zepz1761Xn1xRwEBHLl63uVYatmrZrHRtNjbFYa1lOhY3HKVT6/Pr9CrwBNPwJbA8gG86FSvz7DSlylc
Sq7c4yzJRMYgVECA8RjpCQ0K9+xQAqAkID5BIakXIoOPoFdochfwDrIKGRKKobyLEEoKhrf/8WBAEd/8AOgwhDRbIgza53HFet//
8s//8aqM54qsz6Yd/WXUlCtSCyINpH1i59g0PbNvUT9Yj1CtQUAI/V6CK2gDNExxR1xRxvhb/U9BgYGDmfNHAQEd5XJIvXSqmY1j
nJQUSNAwPTDuvTYqpG7v5N3LFZitga9lZQEDpgY5DfpBiHJDQoz8ti5HFkvPaLCtp//xYEASP/wA5DCpTDWji896//xfH//eLpN6
7SpKivPfmgGjITGSka8+7FsO/br2rgdm3VNUb6BgoFM6iiR5/vAEUXctlwVRqV/JJdddMX10vtIyPcxU43Ssdhd8IH4i+4JDId8C
woEigqwsMB1wJgVIeEDgLyK+gOsICw93AEcBAR7gOexXQI9gdAfZA3AxA5wLkXk4//FgQBA//ADeMK0sQ2qETHGfbf/9Tf//uuEq
ldzU1zl51BVCDQWenTBoLBnbiWjdncW6m27mrZq00rSymSnigBDyLKhJoAWVDXU4dEjSTSTYM7PQ7Oys+15Qi3ydFiKZyUyiUAik
YmaCgX4QXkWSzM5dli1EWRLC2mZigmpFpCzg//FgQBCf/ADiMKB2dYmY8vev/9Hz//987Kzi/S9VdCuGueAIRwEBP1cA////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////8pkvvFzefzPf9DmeyNV7ccbZkOGQ+GP9CAIORM2rgCY4Y41iHuiLrHR063ExNoKCJCBz2HiK6A2FBmQgVIoANh3h3hzDMi
5HPwF7AqkUSPIkXrQn4j0sxvBKpH6s7jcuBHQQEwAUAAAAHAAUSAgAUhABUhIf/xYEAUP/wBHjCo0ioiVWvH/0v/zy63rJJJEiSJ
KsLGa9g+Pb+8Pj2/1y0ZO57BSZO17BaOL0v9QbvsHttnmeaa5Rv7woTv7BQzXiEDliI0iMMRpEdKiko0kdKikR0kaRGkRpEaRGkR
pEaRGkRpEaRGkRpKKRGkRpEaRGkRwI0iNJGkRwI0kcEaRHBGkjgRwI4I4I4I4I4I4MYI4I4I4I4O//FgQBN//EcBATEjAP//////
//////////////////////////////////////8BNjCphVPqvfx43/nnPPdy5JEkiSJIBSRihSRit93nft633ed+3pipen4UkYoE
lNnU8UCdsVqrr6MUKSEeCcMUKSMU041macacazaM2jNozaM2jNozaM2jNozaM2jNozaM2jNozaM2jNozaM2hvRm0ZtGbRm0Zoc2j
NozaG4W4W4W4W4W4W4W4W4W4W4W4W4W4W4W+
""".trimIndent()
}
