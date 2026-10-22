package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A web frame reports its own sample aspect ratio and channel layout, as a frame on the JVM and on
 * native does. Both used to keep the defaults of [FrameInfo], so every web frame said square pixels
 * and no layout, whatever its stream declared (#130).
 */
class WebFrameShapeTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    /** The info of every frame stream 0 of [source] decodes to. */
    private suspend fun frameInfos(source: MediaSource): List<FrameInfo> = source.use {
        val frames = it.decodedFrames(it.streams[0]).toList()
        try {
            frames.map(Frame::info)
        } finally {
            frames.forEach(Frame::close)
        }
    }

    private suspend fun fakeFrameInfos(module: JsAny): List<FrameInfo> {
        useCodecModule(module)
        setFakeDecodeScript(module, "gg")
        return frameInfos(MediaSource.open(OneByteSource(), emptyMap()))
    }

    @Test
    fun aVideoFrameReportsItsOwnSampleAspectRatio() = runTest {
        val module = fakeVideoDecodeCodecModule()
        setFakeDecodedSampleAspectRatio(module, 16, 15)
        val infos = fakeFrameInfos(module)
        assertEquals(2, infos.size, "decoded frames")
        infos.forEach { assertEquals(Rational(16, 15), it.sampleAspectRatio) }
    }

    @Test
    fun anAudioFrameReportsItsOwnChannelLayout() = runTest {
        val infos = fakeFrameInfos(fakeAudioDecodeCodecModule())
        assertEquals(2, infos.size, "decoded frames")
        infos.forEach { info ->
            assertEquals(MediaType.Audio, info.type)
            assertEquals(0x60FL, info.channelLayoutMask, "5.1 with side surrounds")
            assertEquals(Rational(1, 1), info.sampleAspectRatio, "an audio frame has no pixels to shape")
        }
    }

    @Test
    fun anAnamorphicClipDecodesToFramesWithItsPixelShape() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        val source = MediaSource.open(BytesSource(ANAMORPHIC_CLIP), emptyMap())
        assertEquals(Rational(16, 15), source.streams[0].video?.sampleAspectRatio, "what the stream declares")
        val infos = frameInfos(source)
        assertTrue(infos.isNotEmpty(), "the clip decoded to no frame")
        infos.forEach { assertEquals(Rational(16, 15), it.sampleAspectRatio, "what each frame says") }
    }

    @Test
    fun aSideSurroundClipDecodesToFramesWithItsLayout() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        val source = MediaSource.open(BytesSource(SIDE_SURROUND_CLIP), emptyMap())
        assertEquals(0x60FL, source.streams[0].audio?.channelLayoutMask, "what the stream declares")
        val infos = frameInfos(source)
        assertTrue(infos.isNotEmpty(), "the clip decoded to no frame")
        infos.forEach { assertEquals(0x60FL, it.channelLayoutMask, "what each frame says") }
    }

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

    /** The smallest byte source `MediaSource.open` accepts; the fake demuxer ignores its content. */
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

    private companion object {
        /**
         * Two 16x16 grey H.264 frames with 16:15 pixels, made with
         * `ffmpeg -f lavfi -i color=c=gray:size=16x16:duration=0.08:rate=25 -vf setsar=16/15
         * -c:v libx264 -preset ultrafast -pix_fmt yuv420p -bf 0 -bsf:v filter_units=remove_types=6`.
         * The JVM backend reads 16/15 on the stream and on both frames.
         */
        val ANAMORPHIC_CLIP: ByteArray = (
            "000000206674797069736f6d0000020069736f6d69736f32617663316d70343100000008667265650000001c6d646174" +
            "000000076588843a26280e00000005419a202694000003086d6f6f760000006c6d766864000000000000000000000000" +
            "000003e80000005000010000010000000000000000000000000100000000000000000000000000000001000000000000" +
            "00000000000000004000000000000000000000000000000000000000000000000000000000000002000002577472616b" +
            "0000005c746b686400000003000000000000000000000001000000000000005000000000000000000000000000000000" +
            "000100000000000000000000000000000001000000000000000000000000000040000000001111110010000000000024" +
            "656474730000001c656c73740000000000000001000000500000000000010000000001cf6d646961000000206d646864" +
            "000000000000000000000000000032000000040055c400000000002d68646c7200000000000000007669646500000000" +
            "0000000000000000566964656f48616e646c6572000000017a6d696e6600000014766d68640000000100000000000000" +
            "000000002464696e660000001c6472656600000000000000010000000c75726c20000000010000013a7374626c000000" +
            "ba737473640000000000000001000000aa61766331000000000000000100000000000000000000000000000000001000" +
            "1000480000004800000000000000010c4c617663206c6962783236340000000000000000000000000000000000000000" +
            "18ffff00000030617663430142c00affe100196742c00ada7bff0010000f1000000300100000030320f1226a01000468" +
            "ce0fc80000001070617370000000100000000f000000146274727400000000000007d0000007d0000000187374747300" +
            "00000000000001000000020000020000000014737473730000000000000001000000010000001c737473630000000000" +
            "0000010000000100000002000000010000001c7374737a0000000000000000000000020000000b000000090000001473" +
            "74636f0000000000000001000000300000003d75647461000000356d657461000000000000002168646c720000000000" +
            "0000006d6469726170706c00000000000000000000000008696c7374"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /**
         * 240 samples of FLAC at 8 kHz in 5.1 with side surrounds, mask 0x60f, made with
         * `ffmpeg -f lavfi -i sine=duration=0.03:sample_rate=8000 -af "pan=5.1(side)|c0=c0|..."
         * -c:a flac`, with the 8 KiB padding block removed. The JVM backend reads 0x60f on the stream
         * and on the frame.
         */
        val SIDE_SURROUND_CLIP: ByteArray = (
            "664c6143000000220240024000034800034801f40af0000000f02a624bc3491668272f20dda84e06434b8400002e0d00" +
            "00004c61766636302e31362e3130300100000015000000656e636f6465723d4c61766636302e31362e313030fff86458" +
            "00ef444e0000056b0a320dc50fb60fcd0e050a94e599fdbb2c64978b609a8f200125ff268039ae241409828430877e98" +
            "4c1142164e8d3e88888285177c31062c9050448ab7369a2424424b21adcc4c1180a20a25c68621a608c1222284621e9b" +
            "2c49050892ff34d6488902604cb8d1f691090850ae76e8b051090988610359ae241409828430877e984c11421649c000" +
            "00ad614641b8a1f6c1f9a1c0a1529cb33fb7658c92f16c1351e40024bfe4d00735c4828130508610efd309822842c9d1" +
            "a7d1111050a2ef8620c5920a089156e6d3448488496435b9898230144144b8d0c434c1182444508c43d3658920a1125f" +
            "e69ac911204c09971a3ed221210a15cedd160a2121310c206b35c4828130508610efd309822842c938000015ac28c837" +
            "143ed83f3438142a539667f6ecb1925e2d826a3c800497fc9a00e6b89050260a10c21dfa61304508593a34fa22220a14" +
            "5df0c418b24141122adcda689091092c86b7313046028828971a1886982304888a11887a6cb12414224bfcd359222409" +
            "8132e347da44242142b9dba2c144242621840d66b89050260a10c21dfa613045085927000002b5851906e287db07e687" +
            "02854a72ccfedd96324bc5b04d47900092ff93401cd7120a04c1421843bf4c2608a10b27469f444441428bbe18831648" +
            "2822455b9b4d1212212590d6e62608c0510512e34310d30460911142310f4d96248284497f9a6b24448130265c68fb48" +
            "848428573b7458288484c43081acd7120a04c1421843bf4c2608a10b24e0000056b0a320dc50fb60fcd0e050a94e599f" +
            "dbb2c64978b609a8f200125ff268039ae241409828430877e984c1142164e8d3e88888285177c31062c9050448ab7369" +
            "a2424424b21adcc4c1180a20a25c68621a608c1222284621e9b2c49050892ff34d6488902604cb8d1f691090850ae76e" +
            "8b051090988610359ae241409828430877e984c11421649c00000ad614641b8a1f6c1f9a1c0a1529cb33fb7658c92f16" +
            "c1351e40024bfe4d00735c4828130508610efd309822842c9d1a7d1111050a2ef8620c5920a089156e6d344848849643" +
            "5b9898230144144b8d0c434c1182444508c43d3658920a1125fe69ac911204c09971a3ed221210a15cedd160a2121310" +
            "c206b35c4828130508610efd309822842c800644"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
