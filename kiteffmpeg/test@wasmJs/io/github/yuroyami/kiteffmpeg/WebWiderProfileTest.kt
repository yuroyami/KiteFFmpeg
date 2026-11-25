package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The readers and decoders that the web codec module gained for common web media (#211), each
 * asked to decode a clip in the linked module. Before, the open failed with no demuxer or the
 * audio stream had no decoder. [useLinkedCodecModule] says when there is no module to load.
 */
class WebWiderProfileTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun theOggReaderAndTheOpusDecoderDecodeAClip() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        assertDecodes(OGG_OPUS, codec = "opus", channels = 1)
    }

    @Test
    fun theWavReaderDecodesAClip() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        assertDecodes(wav(sampleRate = 48_000, channels = 2, frames = 4_800), codec = "pcm_s16le", channels = 2)
    }

    @Test
    fun theEac3DecoderDecodesAClipInMatroska() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        assertDecodes(MKV_EAC3, codec = "eac3", channels = 2)
    }

    private suspend fun assertDecodes(bytes: ByteArray, codec: String, channels: Int) {
        MediaSource.open(BytesSource(bytes), emptyMap()).use { source ->
            val audio = source.streams.single()
            assertEquals(codec, audio.codec.name)
            val frames = source.decodedFrames(audio).toList()
            try {
                assertTrue(frames.isNotEmpty(), "no  frame decoded")
                assertEquals(channels, frames.first().info.channelCount)
                assertTrue(frames.sumOf { it.info.sampleCount } > 0, "the  frames hold no samples")
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    /** [bytes] as a seekable input. */
    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

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

        override fun close(): Unit = Unit
    }

    private companion object {
        /** 0.1 s of a 440 Hz tone, mono Opus at 48 kHz in Ogg, written by ffmpeg with bitexact. */
        val OGG_OPUS: ByteArray = (
            "4f6767530002000000000000000000000000000000000228b57201134f707573486561640101380180bb00000000004f" +
            "6767530000000000000000000000000000010000004995be54012e4f707573546167730600000066666d706567010000" +
            "0014000000656e636f6465723d4c617663206c69626f7075734f6767530004f8130000000000000000000002000000e9" +
            "1d7ac306453b35333623788201b76c4724ea02466ffab6038c8134d6e85e01eb1bb15eefc97818f20ab32141ccd1a69f" +
            "e18a9b869d6b81ea049eeb408a2b47502809d0465300744db69f7ac3f400f978a33ff7ac9885035c260a579f8af28d3d" +
            "6c854cc013ea41b661e777fb7714cc5875af3512c7f70b2105785be14678b2f5e27d8fb578fa6929d3cb789ba3125145" +
            "00ace351982a38b483a856e930749e1ac67b15b61c0a192ae8edc49ad779dd764076eb46a55eddf5e82ba82874b00578" +
            "9ba311b41cbf52287519dab0240c2c5126436c0fff048f50802c9c1d8a31ab821cfac6e750ccc9f703f41ce8e16d6882" +
            "6fe2789ba35f759cfc4933bf73fae479acc2466d0be1b98a3c86cd732cd6da912994fd44995cb3661b3f232f3708bd82" +
            "6922b6f7a5a2d50b7805e8d73a2effe08b8da422e786cc919ecd910790212ae1bf180bf280b1770cf3ff89"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** 0.1 s of a 440 Hz tone, stereo E-AC-3 at 48 kHz in Matroska, written by ffmpeg with bitexact. */
        val MKV_EAC3: ByteArray = (
            "1a45dfa3a34286810142f7810142f2810442f381084282886d6174726f736b6142878104428581021853806701000000" +
            "000005c3114d9b74c0bf8487bd686f4dbb8b53ab841549a96653ac81a14dbb8b53ab841654ae6b53ac81cc4dbb8c53ab" +
            "841254c36753ac8201234dbb8c53ab841c53bb6b53ac8205a7ec01000000000000530000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000001549a966a6bf8449b62ff22ad7b1830f42404d80844c6176665741" +
            "844c61766644898840590000000000001654ae6bd2bf84cb19f538ae0100000000000043d7810173c588000000000000" +
            "00019c810022b59c83756e648881008686415f4541433356aa83516155838102e1919f8102b58840e770000000000062" +
            "64812055ee81001254c367d4bf84282b8c847373cb63c08b63c588000000000000000167c89645a387454e434f444552" +
            "4487894c617663206561633367c8a145a3884455524154494f4e44879330303a30303a30302e31303030303030303000" +
            "1f43b6754425bf843828c66ce78100a34104810000800b77007f3487c00020fc210824000006060601818186cfdf4371" +
            "dee18e3be7c00255ddf443cacab2133fafd62e25f37ce448f9f9535715d61db28415cae63ed24b7153ab572a77c10fc4" +
            "8edd50cbdd8aae5c89ee2ba04f28a55ddf443cacab2133fafd62e25f37ce0000000000000070000f1b053a4b6e8ca7f9" +
            "f21d87afdec35360a329fe7cf8f9f3e7cf9f3e015184f42055bf40e28015184f42055bf40e280a6b9a194f2543cbd083" +
            "3f918194f2543cbd0833f91801371d9114da50b705748e0d43614da50b705748e0d4302a6e6867e91f821bf75efff130" +
            "7e91f821bf75efff1304d874a8b352d1d76b8287d1238b352d1d76b8287d1220000000004b59a34104810020800b7700" +
            "7f3487c00020f800002e8000060606018181875069bea32b31fc8721525ae992c328cacc7f3e3e7cf9f3e7cf800b9ea1" +
            "6a8eb5d88728a1d894d486aa235ae7a85aa3ad7621ca287625349053a72fce4a27c8b2035c3ec09fb919e0644e96908e" +
            "82de4a27c8b2035c3ec09fb919e01371cd730d8fe27b964e016d05d9afafc0e33aca7d70d8fe27b964e016d05d9afaf0" +
            "54da50c61fdebe27df8cdc8400f80f6de3c537a83631c8c3fbd7c4fbf19b90801f01ed826c2b22670f5ce8ce60ca623b" +
            "3bf98e461ca4347752eb9c3d73a339832988ecefe639029ce77c6bef9d96309bd651e53ce6eeb8183756a166bef9d963" +
            "09bd651e53ce6eea00000095d4a34104810040800b77007f3487c00020f800002e800006060601818187371d2aa329fe" +
            "7c8761f25ae96b4828ca7f9f3e3e7cf9f3e7cf800c00dd7620cc994df36c3d74b6c1d97b20ad300375d883326537cdb0" +
            "f5d2da414f9d3f2ec645dec5dfdb17a387a7acb0c30acbb19177b177f6c5e8e1e9eb2c0535ad10ef5cbe085ecdde220b" +
            "1fb1dfbd9086c9d6841a3ef5cbe085ecdde220b1fb1dfb04dc74a8588f6ac40e68ba571d01c331948e682f0b19623dab" +
            "1039a2e95c74070cc65209b74a195823eb5d21869a6df03ca6250914fb3b36252b6ac11f5ae90c34d36f81e5312804dd" +
            "4ab807dc4103de7611ca10402de42d8e9ce0307dc4103de7611ca10402de42000000bf1fa34104810060800b77007f34" +
            "87c00020c6318c25800006060601818186f61f3e627f388c4fe7c0000c01613c5927b54027ec01a0403feff9f7fc1014" +
            "303dfa672128052d90146dc952357684056f223756ea129cb00600b09e2c93daa013f600d0201ff7fc00000000000000" +
            "3b800f9819f577f03bad82077cf81533d94a1a1cea4c5a74360398d2c79fe267af1710c5af73ea4d84410956ef45300c" +
            "9833d866d0cdb0ffb547308a8aff765c28aa01956406c180d5f6faaa67b2943439d498b4e86c0731a58f3fc400000000" +
            "0000000e0001e36c2101010420202098f9f3e7cf9f3e7cf9f3e7dff3abe7cf9f3e7cf9f3e7c7fceaf9f3e7cf9f3e7cf9" +
            "f0300300300300000032401c53bb6b97bf84d7b47919bb8fb38100b78af78101f182017cf08109"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** A 16-bit PCM WAV file of silence. */
        fun wav(sampleRate: Int, channels: Int, frames: Int): ByteArray {
            val data = frames * channels * 2
            val out = ByteArray(44 + data)
            fun put32(at: Int, value: Int) { for (i in 0 until 4) out[at + i] = (value shr (8 * i)).toByte() }
            fun put16(at: Int, value: Int) { for (i in 0 until 2) out[at + i] = (value shr (8 * i)).toByte() }
            "RIFF".encodeToByteArray().copyInto(out, 0)
            put32(4, 36 + data)
            "WAVEfmt ".encodeToByteArray().copyInto(out, 8)
            put32(16, 16)
            put16(20, 1)
            put16(22, channels)
            put32(24, sampleRate)
            put32(28, sampleRate * channels * 2)
            put16(32, channels * 2)
            put16(34, 16)
            "data".encodeToByteArray().copyInto(out, 36)
            put32(40, data)
            return out
        }
    }
}
