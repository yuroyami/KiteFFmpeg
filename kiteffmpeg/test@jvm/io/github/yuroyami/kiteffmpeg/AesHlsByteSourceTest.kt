package io.github.yuroyami.kiteffmpeg

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AES-128 HLS through a byte source and the nested opener, read back through the JNI backend
 * (#229), with the segments encrypted by the JDK. The second case is #237's shape: two segments of
 * one file, cut by EXT-X-BYTERANGE, each encrypted with its own IV and padded on its own.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class AesHlsByteSourceTest {

    private class Memory(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
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
        override fun close() = Unit
    }

    private class Opener(private val resources: Map<String, ByteArray>) : MediaByteOpener {
        override fun open(url: String): MediaByteSource? = resources[url]?.let(::Memory)
    }

    private val key = ByteArray(16) { (it * 17 + 3).toByte() }
    private val firstIv = ByteArray(16) { it.toByte() }
    private val secondIv = ByteArray(16) { (0xf0 - it).toByte() }

    private fun encrypt(plain: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }

    private fun hex(bytes: ByteArray) = "0x" + bytes.joinToString("") { "%02X".format(it) }

    /** Two seconds of MPEG-TS video at 10 frames a second, as two separate one-second pieces. */
    private fun pieces(): Pair<ByteArray, ByteArray>? {
        val made = (0..1).map { index ->
            val file = File.createTempFile("kiteffmpeg-aes-$index-", ".ts").apply { deleteOnExit() }
            runMediaOracle(
                "ffmpeg",
                listOf(
                    "-v", "error", "-y", "-f", "lavfi",
                    "-i", "testsrc=size=160x120:rate=10:duration=1", "-c:v", "mpeg4",
                    "-output_ts_offset", "$index", "-f", "mpegts", file.path,
                ),
            ) ?: return null
            file.readBytes()
        }
        return made[0] to made[1]
    }

    /** Each video packet's time, size and bytes, in order, or null without the patch. */
    private fun videoPackets(playlist: String, resources: Map<String, ByteArray>): List<String>? {
        val media = try {
            MediaSource.open(
                Memory(playlist.encodeToByteArray()),
                url = "https://media.example/live/index.m3u8",
                mimeType = "application/vnd.apple.mpegurl",
                nestedOpener = Opener(resources),
            )
        } catch (error: FFmpegException) {
            if (error.error is FFmpegError.Unsupported && "trust_io_open" in error.message.orEmpty()) return null
            throw error
        }
        return media.use {
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            val packets = mutableListOf<String>()
            media.openPacketReader(listOf(video)).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use { val bytes = it.copyBytes(); packets += "${it.pts}/${bytes.size}/${bytes.contentHashCode()}" }
                }
            }
            packets
        }
    }

    @Test
    fun anAes128SegmentDecryptsThroughTheNestedOpener() {
        val (first, _) = pieces() ?: return println("AES HLS degraded: no ffmpeg")
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=${hex(firstIv)}
            #EXTINF:1.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        val packets = videoPackets(
            playlist,
            mapOf("https://media.example/live/seg0.ts" to encrypt(first, firstIv), "https://media.example/live/key.bin" to key),
        ) ?: return println("AES HLS degraded: the linked FFmpeg lacks the trust_io_open patch")
        println("AES HLS: ${packets.size} video packets from one AES-128 segment")
        assertEquals(10, packets.size, "one second at 10 frames a second")
    }

    @Test
    fun twoAes128ByteRangesOfOneFileEachDecryptWithTheirOwnIv() {
        val (first, second) = pieces() ?: return println("AES HLS degraded: no ffmpeg")
        val a = encrypt(first, firstIv)
        val b = encrypt(second, secondIv)
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:1
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=${hex(firstIv)}
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:${a.size}@0
            all.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=${hex(secondIv)}
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:${b.size}@${a.size}
            all.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        val packets = videoPackets(
            playlist,
            mapOf("https://media.example/live/all.ts" to a + b, "https://media.example/live/key.bin" to key),
        ) ?: return println("AES HLS degraded: the linked FFmpeg lacks the trust_io_open patch")
        // The same two pieces as two plain files, a path the range handling never touches.
        val plain = videoPackets(
            """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:1
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:1.0,
                seg0.ts
                #EXTINF:1.0,
                seg1.ts
                #EXT-X-ENDLIST
            """.trimIndent() + "\n",
            mapOf("https://media.example/live/seg0.ts" to first, "https://media.example/live/seg1.ts" to second),
        )!!
        println("AES HLS: ${packets.size} video packets from two AES-128 byte ranges, ${plain.size} from the plain files")
        assertEquals(20, plain.size, "the plain files hold two seconds at 10 frames a second")
        assertEquals(plain, packets, "the byte ranges must decrypt to exactly the plain files' packets")
    }

    /**
     * The same byte ranges over a WAV, whose bytes the demuxer hands out as they are, so a block
     * decrypted under the wrong IV or a padding kept as data shows. MPEG-TS hides both: it
     * resynchronises past a damaged first packet. FFmpeg's segment check refuses a WAV segment,
     * which is its policy and not this test's subject, so extension_picky is off.
     */
    @Test
    fun aes128ByteRangesOfAWavDecryptToItsExactPcm() {
        val pcmBytes = 96_000
        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), 16, 0, 0, 0, 1, 0, 2, 0,
            0x80.toByte(), 0xBB.toByte(), 0, 0, 0x00, 0xEE.toByte(), 0x02, 0, 4, 0, 16, 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 0, 0, 0, 0,
        )
        fun le32(at: Int, value: Int) = (0..3).forEach { header[at + it] = (value ushr (8 * it)).toByte() }
        le32(4, 36 + pcmBytes)
        le32(40, pcmBytes)
        val pcm = ByteArray(pcmBytes) { (it * 7 + 3).toByte() }
        val wav = header + pcm
        val split = header.size + pcmBytes / 2 + 7
        val a = encrypt(wav.copyOfRange(0, split), firstIv)
        val b = encrypt(wav.copyOfRange(split, wav.size), secondIv)
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:1
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=${hex(firstIv)}
            #EXTINF:0.5,
            #EXT-X-BYTERANGE:${a.size}@0
            all.wav
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=${hex(secondIv)}
            #EXTINF:0.5,
            #EXT-X-BYTERANGE:${b.size}@${a.size}
            all.wav
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        val media = try {
            MediaSource.open(
                Memory(playlist.encodeToByteArray()),
                options = mapOf("extension_picky" to "0"),
                url = "https://media.example/live/index.m3u8",
                mimeType = "application/vnd.apple.mpegurl",
                nestedOpener = Opener(mapOf("https://media.example/live/all.wav" to a + b, "https://media.example/live/key.bin" to key)),
            )
        } catch (error: FFmpegException) {
            if (error.error is FFmpegError.Unsupported && "trust_io_open" in error.message.orEmpty()) {
                return println("AES HLS degraded: the linked FFmpeg lacks the trust_io_open patch")
            }
            throw error
        }
        val read = media.use {
            val audio = media.primaryAudio ?: error("the playlist has no audio stream")
            val out = java.io.ByteArrayOutputStream()
            media.openPacketReader(listOf(audio)).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use { out.write(it.copyBytes()) }
                }
            }
            out.toByteArray()
        }
        println("AES HLS: ${read.size} PCM bytes from two AES-128 byte ranges of a WAV")
        assertEquals(pcmBytes, read.size, "the PCM must come back without any segment's padding")
        assertTrue(read.contentEquals(pcm), "the PCM must decrypt to exactly its bytes")
    }
}
