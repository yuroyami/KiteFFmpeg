package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A decoder marks each frame with its picture type, and an encoder reads an I there as a request
 * for a keyframe, so an all-keyframe source used to come out all keyframes whatever GOP was asked
 * for (#196). The encode clears the type first, as FFmpeg's command line does.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class PictureTypeResetContractTest {

    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private val rate = Rational(24, 1)

    private fun spec(gop: Int) = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = TranscodeFixtures.WIDTH,
        height = TranscodeFixtures.HEIGHT,
        frameRate = rate,
        bitrateBps = 2_000_000L,
        keyframeIntervalFrames = gop,
    )

    /** 48 frames at 24 fps, every one a keyframe, as an intra-only camera format decodes. */
    private fun allKeyframes(): String {
        val output = path("mkv")
        MediaSink.open(output).use { sink ->
            val encoder = sink.addVideoEncoder(spec(gop = 1))
            runBlocking {
                encoder.drive(
                    (0 until FRAMES).asFlow().map { index ->
                        TranscodeFixtures.texturedPicture(index, index * 1_000_000L / 24)
                    },
                )
            }
        }
        return output
    }

    private fun keyframes(path: String): Pair<Int, Int> = MediaSource.open(path).use { source ->
        source.openPacketReader(source.streams).use { reader ->
            var packets = 0
            var keys = 0
            while (true) {
                val packet = reader.read() ?: break
                packet.use { if (it.isKeyframe) keys++; packets++ }
            }
            packets to keys
        }
    }

    @Test
    fun aTranscodeOfAnAllKeyframeSourceKeepsTheGopItAskedFor() {
        val input = allKeyframes()
        assertEquals(FRAMES to FRAMES, keyframes(input), "the source is all keyframes")
        val output = path("mkv")
        runBlocking { Transcoder.transcode(input = input, output = output, spec = spec(gop = 24)) }
        assertEquals(FRAMES to 2, keyframes(output), "48 frames with a GOP of 24 hold two keyframes")
    }

    private companion object {
        const val FRAMES = 48
    }
}
