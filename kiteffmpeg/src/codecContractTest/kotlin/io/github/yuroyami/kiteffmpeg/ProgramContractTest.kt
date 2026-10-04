package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A source reports its programmes (#148): which streams play together, the number the container
 * states, and the channel's name and provider.
 *
 * Both fixtures are one 40 ms transport stream with two channels, each one MPEG-2 picture and one
 * MP2 sound, made by FFmpeg 6.1's command line:
 *
 * ```
 * ffmpeg -f lavfi -i testsrc=size=16x16:rate=25:d=0.04 -f lavfi -i sine=f=440:sample_rate=48000:d=0.05 \
 *   -f lavfi -i testsrc2=size=16x16:rate=25:d=0.04 -f lavfi -i sine=f=880:sample_rate=48000:d=0.05 \
 *   -map 0:v -map 1:a -map 2:v -map 3:a -c:v mpeg2video -c:a mp2 -b:a 32k -ac 1 \
 *   -metadata:s:a:0 language=fra -metadata:s:a:1 language=eng \
 *   -program title=ChannelA:program_num=1:st=0:st=1 -program title=ChannelB:program_num=2:st=2:st=3 \
 *   -fflags +bitexact -flags +bitexact -f mpegts two-channels.ts
 * ```
 *
 * The second fixture is the first with programme 2 taken out of every programme association table
 * and each table's checksum recomputed, so only the service description table still names
 * ChannelB. FFmpeg then makes a programme for it with no number and no streams, and finds that
 * channel's two streams by their packets, outside any programme.
 *
 * A third fixture is a multiplex whose first channel is a radio channel, one MP2 sound, and whose
 * second is a television channel, one MPEG-2 picture and one MP2 sound, made the same way with
 * `-map 0:a -map 1:v -map 2:a -program title=Radio:program_num=1:st=0
 * -program title=Television:program_num=2:st=1:st=2` over a 440 Hz sine, `testsrc` and an 880 Hz
 * sine. The radio's sound comes first, so a selection that ignores programmes pairs the
 * television's picture with it (#165).
 */
class ProgramContractTest {

    private fun channel(id: Int, number: Int?, streamIndexes: List<Int>, name: String): Program = Program(
        id = id,
        number = number,
        streamIndexes = streamIndexes,
        metadata = mapOf("service_name" to name, "service_provider" to "FFmpeg"),
    )

    @Test
    fun eachChannelOfAMultiplexReportsItsStreamsNumberAndName() {
        MediaSource.open(materializeContractMedia(TwoChannelsTs.bytes, TwoChannelsTs.sha256)).use { source ->
            assertEquals(
                listOf(channel(1, 1, listOf(0, 1), "ChannelA"), channel(2, 2, listOf(2, 3), "ChannelB")),
                source.programs,
            )
            assertEquals("ChannelB", source.programs[1].serviceName)
            assertEquals("FFmpeg", source.programs[1].serviceProvider)
            assertEquals(listOf("fra", "eng"), source.programs.map { program -> source.streams.single { it.index == program.streamIndexes[1] }.language })
        }
    }

    @Test
    fun aChannelOnlyTheServiceTableNamesHasNoNumberAndNoStreams() {
        MediaSource.open(materializeContractMedia(ServiceTableOnlyTs.bytes, ServiceTableOnlyTs.sha256)).use { source ->
            assertEquals(
                listOf(channel(1, 1, listOf(0, 1), "ChannelA"), channel(2, null, emptyList(), "ChannelB")),
                source.programs,
            )
            assertEquals(4, source.streams.size)
        }
    }

    @Test
    fun aContainerWithNoProgrammeTablesReportsNone() {
        MediaSource.open(materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)).use { source ->
            assertTrue(source.streams.isNotEmpty())
            assertEquals(emptyList(), source.programs)
        }
    }

    @Test
    fun thePrimarySoundIsThePrimaryPicturesOwn() {
        MediaSource.open(materializeContractMedia(RadioAndTelevisionTs.bytes, RadioAndTelevisionTs.sha256)).use { source ->
            assertEquals(listOf(listOf(0), listOf(1, 2)), source.programs.map { it.streamIndexes })
            assertEquals(1, source.primaryVideo?.index)
            assertEquals(2, source.primaryAudio?.index)
        }
    }

    @Test
    fun aLanguagePreferenceChoosesOnlyAmongThePicturesChannelSound() {
        MediaSource.open(materializeContractMedia(TwoChannelsTs.bytes, TwoChannelsTs.sha256)).use { source ->
            val english = TrackSelector(listOf("eng"))
            val video = english.selectVideo(source.streams)
            assertEquals(0, video?.index)
            assertEquals(1, english.selectAudio(source.streams, source.programs, video)?.index)
            val channelB = source.streams.single { it.index == 2 }
            assertEquals(3, english.selectAudio(source.streams, source.programs, channelB)?.index)
        }
    }

    @Test
    fun aProbeReportsTheSameProgrammesAsAnOpenSource() {
        val path = materializeContractMedia(TwoChannelsTs.bytes, TwoChannelsTs.sha256)
        val opened = MediaSource.open(path).use { it.programs }
        assertEquals(opened, MediaSource.probe(path).programs)
        assertEquals(2, opened.size)
    }
}

private object TwoChannelsTs {
    const val sha256: String = "bef9a92a5391c7c7e26cc67f08daf8c339499f85d0cd6cfed302f70d4d407fa6"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 3196) { "TwoChannelsTs fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "TwoChannelsTs fixture digest changed" }
        }
    }

    private val DATA: String = """
R0AREABC8DwAAcEAAP8B/wAB/IATSBEBBkZGbXBlZwhDaGFubmVsQQAC/IATSBEBBkZGbXBlZwhDaGFubmVsQqNEBoP/////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////9HQAAQAACwEQABwQAAAAHwAAAC8AEggnpN////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/0dQABAAArAdAAHBAADhAPAAAuEA8AAD4QHwBgoEZnJhAIDuTBz/////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////R1ABEAACsB0AAsEAAOEC8AAC4QLwAAPhA/AGCgRlbmcAkYo9
Hf//////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//9HQQAwB1AAAHzPfgAAAAHgAACAgAUhAAffbQAAAbMBABAT///gGAAAAbUUigABAAAAAAG4AAgAQAAAAQAAD//4AAABtY//80GA
AAABARPzqABuAXAgfjggfhgDEB0AnANCYAxAMAC4BOQwB8gB0AagOiEGoSTQDRBDAdAMQ0sMTyGUXkgOwEw0hfrwYgrjEJ6b1oIA
GIIAFRNBA+pADABCAYAD0A1AYgOwB+TQHRDADQA1ATgIQKp5QCcoAEcBABH8NIQBWGl4B2lIFEhoFCEUQwFBZeIW+DGS3uaAOAGI
AvBAApBA/KAE4aCCA6APwEKQDUAfgF4A/JgYA2AqQunAIQKgMQK8MKBCAIDeWA2QAxzl7L2yRmG1YIAJwA7AFgIAHgAwAMgB2QwB
iAPAHeJo0lhgaAxAdAJgGz8hoCS0k0mnJIYxaMtDGyAMOWAFhYASlABW6eAP0ACoEEBx8ACXExG4BeNJXAdAB+gAuxfSBQAn5CAb
9GJPRwEAMn4A////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////8pUgE6EgUAFAA5AFoA4GoA
LibgBtwGBYDAbyaViWAJOAL2AYFOAnISA0soAYgB24YjBvJpD3cAtCZHQAARAACwEQABwQAAAAHwAAAC8AEggnpN////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////0dQABEAArAdAAHBAADhAPAAAuEA8AAD4QHwBgoEZnJhAIDuTBz/////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////R1ABEQACsB0AAsEAAOEC8AAC4QLwAAPhA/AGCgRlbmcA
kYo9Hf//////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////9HQQIwB1AAAHzPfgAAAAHgAACAgAUhAAffbQAAAbMBABAT///gGAAAAbUUigABAAAAAAG4AAgAQAAAAQAAD//4AAABtY//
80GAAAABARP5wTEAC4A0AHQFAE4BfhnJobiEhgCssNJpNLJqSgwmEwhEIaGhob2UgMK6W37Nv60AIwQQKwBKANEE0BOCF/yBTAO0
gIAMAhf5kwCuAT4mhm4DDgMCiUAnwFRqNwG3DCWMw1hi7qgBiTCaUQgAoEcBAhEDXFgB8QgKAg/2EwNAQ9GLKATBoCdAGAKBoajq
JQGVDUsGbti059bwC8BgAapAClIAjADsDIBdxgBgCD/IAWFYChYCEoAdIAQJAT4hOMAKgA5UBnAhAFDQjBrQAOgQATQBMAaAUAQc
h8CgaBUChCAqkArADwmBnIZDAYJxMwDpABQQ/gHYDvYDO+JpYaxxMLPvcAFQAlIeAdAD4B2TAHQYBQAtJoA4IeOGhnRyYBUoA15Y
GSF+ghENRwECMq4A////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////9ZPxSMWhwC1HhHQQEwAUAAAAHAASiAgAUhAAfYYf/9FMRTZCIs/MVl
cv4dxh1l0e962LY18173t6tjXzXs6nlpKkVqCa2rs183hDkouC+BPOAny2FusxkMTLYS6pnky2thEzR7rJYrMlABLr1NkziEpvIG
nc4hIAsOhuqlDP/9FMRTaCIqqMl4y000JYejycsK8sR3R6IsM1YDrNGMuAh40yD2RuhL2NSQhG6jBXb5mnWsEErypue3uqYHQ1sJ
MvNuB4aBg0cBATE/AP//////////////////////////////////////////////////////////////////////////////////
WKTxyErWAPE3sgMcVyZ+mnBw6EqqS0AA//0UxENkIgAAtf5Rz5yP52n6Gz6W35dZbFsa+aV2ti2NfNFtRtbGvmqjs2A0GhQZkpPy
6NLu62LY1813dbFsa+a7uti2NfNd3WxbGvmu7rYtjXzXd1sWxr5ru62LY180R0EDMAFAAAABwAEogIAFIQAH2GH//RTEU2QiKD0T
GLOGs9ns4s3veti2NfNe6C2LY180/J1PaS49aH0mS7NfNJdohLgKQVlyNMtjX6sohS62KuqobS9LY2wwtHiUmr5I05IrypDhuwtt
HpiuIqxdOGcrK7D//RTEU2giK6kjOttdMyzmJKiqboy8EitVJeocNVR64moitB3BbK0rjEPsJCuH7wx9IZhlo+LawPH7YDbquCzK
jaubrVsyTKRHAQMxPwD/////////////////////////////////////////////////////////////////////////////////
/08hjFwxBpEMfNlQkfoUgkq6O9Oje+7MAP/9FMRDZCIAAPX+MY+Ye+al+ep+iq+daOpbGvmpSh0tjXzXlHuWxr5ug7WAM9qUIZIE
FujS7uti2NfNd3WxbGvmu7rYtjXzXd1sWxr5ru62LY1813dbFsa+a7uti2NfNA==
"""
}

private object ServiceTableOnlyTs {
    const val sha256: String = "148ad9c05cab656cee2cb7b4464b8810f58166c76db7ceec4972ae48a330ba9c"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 3196) { "ServiceTableOnlyTs fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "ServiceTableOnlyTs fixture digest changed" }
        }
    }

    private val DATA: String = """
R0AREABC8DwAAcEAAP8B/wAB/IATSBEBBkZGbXBlZwhDaGFubmVsQQAC/IATSBEBBkZGbXBlZwhDaGFubmVsQqNEBoP/////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////9HQAAQAACwDQABwQAAAAHwACqxBLL/////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/0dQABAAArAdAAHBAADhAPAAAuEA8AAD4QHwBgoEZnJhAIDuTBz/////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////R1ABEAACsB0AAsEAAOEC8AAC4QLwAAPhA/AGCgRlbmcAkYo9
Hf//////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//9HQQAwB1AAAHzPfgAAAAHgAACAgAUhAAffbQAAAbMBABAT///gGAAAAbUUigABAAAAAAG4AAgAQAAAAQAAD//4AAABtY//80GA
AAABARPzqABuAXAgfjggfhgDEB0AnANCYAxAMAC4BOQwB8gB0AagOiEGoSTQDRBDAdAMQ0sMTyGUXkgOwEw0hfrwYgrjEJ6b1oIA
GIIAFRNBA+pADABCAYAD0A1AYgOwB+TQHRDADQA1ATgIQKp5QCcoAEcBABH8NIQBWGl4B2lIFEhoFCEUQwFBZeIW+DGS3uaAOAGI
AvBAApBA/KAE4aCCA6APwEKQDUAfgF4A/JgYA2AqQunAIQKgMQK8MKBCAIDeWA2QAxzl7L2yRmG1YIAJwA7AFgIAHgAwAMgB2QwB
iAPAHeJo0lhgaAxAdAJgGz8hoCS0k0mnJIYxaMtDGyAMOWAFhYASlABW6eAP0ACoEEBx8ACXExG4BeNJXAdAB+gAuxfSBQAn5CAb
9GJPRwEAMn4A////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////8pUgE6EgUAFAA5AFoA4GoA
LibgBtwGBYDAbyaViWAJOAL2AYFOAnISA0soAYgB24YjBvJpD3cAtCZHQAARAACwDQABwQAAAAHwACqxBLL/////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/////0dQABEAArAdAAHBAADhAPAAAuEA8AAD4QHwBgoEZnJhAIDuTBz/////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////R1ABEQACsB0AAsEAAOEC8AAC4QLwAAPhA/AGCgRlbmcA
kYo9Hf//////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////9HQQIwB1AAAHzPfgAAAAHgAACAgAUhAAffbQAAAbMBABAT///gGAAAAbUUigABAAAAAAG4AAgAQAAAAQAAD//4AAABtY//
80GAAAABARP5wTEAC4A0AHQFAE4BfhnJobiEhgCssNJpNLJqSgwmEwhEIaGhob2UgMK6W37Nv60AIwQQKwBKANEE0BOCF/yBTAO0
gIAMAhf5kwCuAT4mhm4DDgMCiUAnwFRqNwG3DCWMw1hi7qgBiTCaUQgAoEcBAhEDXFgB8QgKAg/2EwNAQ9GLKATBoCdAGAKBoajq
JQGVDUsGbti059bwC8BgAapAClIAjADsDIBdxgBgCD/IAWFYChYCEoAdIAQJAT4hOMAKgA5UBnAhAFDQjBrQAOgQATQBMAaAUAQc
h8CgaBUChCAqkArADwmBnIZDAYJxMwDpABQQ/gHYDvYDO+JpYaxxMLPvcAFQAlIeAdAD4B2TAHQYBQAtJoA4IeOGhnRyYBUoA15Y
GSF+ghENRwECMq4A////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////9ZPxSMWhwC1HhHQQEwAUAAAAHAASiAgAUhAAfYYf/9FMRTZCIs/MVl
cv4dxh1l0e962LY18173t6tjXzXs6nlpKkVqCa2rs183hDkouC+BPOAny2FusxkMTLYS6pnky2thEzR7rJYrMlABLr1NkziEpvIG
nc4hIAsOhuqlDP/9FMRTaCIqqMl4y000JYejycsK8sR3R6IsM1YDrNGMuAh40yD2RuhL2NSQhG6jBXb5mnWsEErypue3uqYHQ1sJ
MvNuB4aBg0cBATE/AP//////////////////////////////////////////////////////////////////////////////////
WKTxyErWAPE3sgMcVyZ+mnBw6EqqS0AA//0UxENkIgAAtf5Rz5yP52n6Gz6W35dZbFsa+aV2ti2NfNFtRtbGvmqjs2A0GhQZkpPy
6NLu62LY1813dbFsa+a7uti2NfNd3WxbGvmu7rYtjXzXd1sWxr5ru62LY180R0EDMAFAAAABwAEogIAFIQAH2GH//RTEU2QiKD0T
GLOGs9ns4s3veti2NfNe6C2LY180/J1PaS49aH0mS7NfNJdohLgKQVlyNMtjX6sohS62KuqobS9LY2wwtHiUmr5I05IrypDhuwtt
HpiuIqxdOGcrK7D//RTEU2giK6kjOttdMyzmJKiqboy8EitVJeocNVR64moitB3BbK0rjEPsJCuH7wx9IZhlo+LawPH7YDbquCzK
jaubrVsyTKRHAQMxPwD/////////////////////////////////////////////////////////////////////////////////
/08hjFwxBpEMfNlQkfoUgkq6O9Oje+7MAP/9FMRDZCIAAPX+MY+Ye+al+ep+iq+daOpbGvmpSh0tjXzXlHuWxr5ug7WAM9qUIZIE
FujS7uti2NfNd3WxbGvmu7rYtjXzXd1sWxr5ru62LY1813dbFsa+a7uti2NfNA==
"""
}

private object RadioAndTelevisionTs {
    const val sha256: String = "0e851e53ea99ebf3d0c1e950d61ca674d026516c47ae20f1b1dba553c91b8e71"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 2068) { "RadioAndTelevisionTs fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "RadioAndTelevisionTs fixture digest changed" }
        }
    }

    private val DATA: String = """
R0AREABC8DsAAcEAAP8B/wAB/IAQSA4BBkZGbXBlZwVSYWRpbwAC/IAVSBMBBkZGbXBlZwpUZWxldmlzaW9uAganOv//////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////9HQAAQAACwEQABwQAAAAHwAAAC8AEggnpN////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
/0dQABAAArASAAHBAADhAPAAA+EA8ADXhkRc////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////R1ABEAACsBcAAsEAAOEB8AAC4QHwAAPhAvAAwE8bO///////
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
//9HQQEwB1AAAHzPfgAAAAHgAACAgAUhAAffbQAAAbMBABAT///gGAAAAbUUigABAAAAAAG4AAgAQAAAAQAAD//4AAABtY//80GA
AAABARPzqABuAXAgfjggfhgDEB0AnANCYAxAMAC4BOQwB8gB0AagOiEGoSTQDRBDAdAMQ0sMTyGUXkgOwEw0hfrwYgrjEJ6b1oIA
GIIAFRNBA+pADABCAYAD0A1AYgOwB+TQHRDADQA1ATgIQKp5QCcoAEcBARH8NIQBWGl4B2lIFEhoFCEUQwFBZeIW+DGS3uaAOAGI
AvBAApBA/KAE4aCCA6APwEKQDUAfgF4A/JgYA2AqQunAIQKgMQK8MKBCAIDeWA2QAxzl7L2yRmG1YIAJwA7AFgIAHgAwAMgB2QwB
iAPAHeJo0lhgaAxAdAJgGz8hoCS0k0mnJIYxaMtDGyAMOWAFhYASlABW6eAP0ACoEEBx8ACXExG4BeNJXAdAB+gAuxfSBQAn5CAb
9GJPRwEBMn4A////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////8pUgE6EgUAFAA5AFoA4GoA
LibgBtwGBYDAbyaViWAJOAL2AYFOAnISA0soAYgB24YjBvJpD3cAtCZHQQAwB1AAAHsMfgAAAAHAASiAgAUhAAfYYf/9FMRTZCIs
/MVlcv4dxh1l0e962LY18173t6tjXzXs6nlpKkVqCa2rs183hDkouC+BPOAny2FusxkMTLYS6pnky2thEzR7rJYrMlABLr1NkziE
pvIGnc4hIAsOhuqlDP/9FMRTaCIqqMl4y000JYejycsK8sR3R6IsM1YDrNGMuAh40yD2RuhL2NSQhG6jBXb5mnWsEErypue3uqYH
Q1sJMkcBADE5AP//////////////////////////////////////////////////////////////////////////824HhoGDWKTx
yErWAPE3sgMcVyZ+mnBw6EqqS0AA//0UxENkIgAAtf5Rz5yP52n6Gz6W35dZbFsa+aV2ti2NfNFtRtbGvmqjs2A0GhQZkpPy6NLu
62LY1813dbFsa+a7uti2NfNd3WxbGvmu7rYtjXzXd1sWxr5ru62LY180R0ECMAFAAAABwAEogIAFIQAH2GH//RTEU2QiKD0TGLOG
s9ns4s3veti2NfNe6C2LY180/J1PaS49aH0mS7NfNJdohLgKQVlyNMtjX6sohS62KuqobS9LY2wwtHiUmr5I05IrypDhuwttHpiu
IqxdOGcrK7D//RTEU2giK6kjOttdMyzmJKiqboy8EitVJeocNVR64moitB3BbK0rjEPsJCuH7wx9IZhlo+LawPH7YDbquCzKjaub
rVsyTKRHAQIxPwD//////////////////////////////////////////////////////////////////////////////////08h
jFwxBpEMfNlQkfoUgkq6O9Oje+7MAP/9FMRDZCIAAPX+MY+Ye+al+ep+iq+daOpbGvmpSh0tjXzXlHuWxr5ug7WAM9qUIZIEFujS
7uti2NfNd3WxbGvmu7rYtjXzXd1sWxr5ru62LY1813dbFsa+a7uti2NfNA==
"""
}
