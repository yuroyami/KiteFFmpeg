@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The window 3 differential arm (KPKMP 17.4.8, S2.a), run by BOTH the native and the JVM suite,
 * which is the point: on macOS the JVM reaches VideoToolbox through the JNI bridge's new rows
 * and must observe exactly what the cinterop path observes. A target without the VideoToolbox
 * encoder (the simulator, Android) degrades to the typed-refusal shape stated below rather than
 * pretending the full path ran; decode-capability differences are FFmpeg's runtime answer, not
 * this suite's guess.
 */
class HwaccelContractTest {

    private val cleanup = mutableListOf<String>()

    @AfterTest
    fun deleteTemporaries() {
        cleanup.forEach { deleteContractPath(it) }
    }

    private fun writeH264(path: String, frames: Int) {
        MediaSink.open(path).use { sink ->
            val video = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId.H264VideoToolbox,
                    width = 320,
                    height = 240,
                    frameRate = Rational(30, 1),
                    bitrateBps = 800_000,
                ),
            )
            runBlocking {
                video.drive(
                    (0 until frames).asFlow().map { index ->
                        Frame.ofVideo(
                            bytes = ByteArray(320 * 240 * 3 / 2) { (it + index * 7).toByte() },
                            width = 320,
                            height = 240,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = index * 1_000_000L / 30,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun videoToolboxDecodesHardwareFramesIdenticallyOnEveryBoundary() {
        if (!FFmpeg.hasEncoder(CodecId.H264VideoToolbox.name)) {
            println("hwaccel contract arm degraded: no h264_videotoolbox encoder on this target")
            return
        }

        val path = contractOutputPath("mp4")
        cleanup += path
        writeH264(path, frames = 30)

        MediaSource.open(path).use { src ->
            val stream = src.primaryVideo ?: error("the encoder wrote no video stream")
            val extradata = assertNotNull(stream.codecExtradata, "MP4 H.264 must expose avcC")
            assertTrue(extradata.isNotEmpty(), "the avcC record must not be empty")
            assertEquals(1, extradata[0].toInt() and 0xff, "the avcC version must be one")
            src.openDecoder(stream, hardware = HardwareAccel.VideoToolbox).use { decoder ->
                src.openPacketReader(listOf(stream)).use { reader ->
                    var hardwareFrames = 0
                    var downloaded = 0
                    while (hardwareFrames < 5) {
                        val packet = reader.read() ?: break
                        val consumed = packet.use { decoder.send(it) }
                        if (!consumed) continue
                        while (true) {
                            val frame = decoder.receive() ?: break
                            frame.use { hw ->
                                assertTrue(
                                    hw.info.isHardware,
                                    "the VideoToolbox decoder produced a software frame at pts ${hw.info.pts}",
                                )
                                hw.downloadFromHardware().use { sw ->
                                    assertTrue(!sw.info.isHardware, "the download must produce a software frame")
                                    assertEquals(hw.info.pts, sw.info.pts, "the download lost the timestamp")
                                    assertEquals(320, sw.info.width)
                                    assertEquals(240, sw.info.height)
                                    assertTrue(
                                        sw.copyPlanesToByteArray().isNotEmpty(),
                                        "the downloaded frame has no readable pixels",
                                    )
                                    downloaded++
                                }
                                hardwareFrames++
                            }
                        }
                    }
                    assertTrue(hardwareFrames >= 5, "expected at least 5 hardware frames, got $hardwareFrames")
                    assertEquals(hardwareFrames, downloaded)
                    println("hwaccel contract arm: $hardwareFrames hardware frames, $downloaded downloads OK")
                }
            }
        }
    }
}
