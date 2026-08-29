@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The window 3 real-media proof (KPKMP 17.4.8, S2.a): a decoder opened with
 * [HardwareAccel.VideoToolbox] produces frames that live in hardware memory, and
 * [Frame.downloadFromHardware] brings their pixels back with the presentation properties intact.
 *
 * The clip is encoded here with `h264_videotoolbox`, which the desktop Apple profile carries, so
 * the arm also proves encode and decode agree about the hardware. On a target whose FFmpeg has
 * no VideoToolbox encoder (the simulator: decode exists there, encode does not) the arm degrades
 * to the attach proof alone, and says so, rather than pretending the full path ran.
 */
class HwaccelIntegrationTest {

    private val cleanup = mutableListOf<String>()

    private fun tmp(name: String): String {
        val dir = systemTempRoot()
        val path = "$dir/hwaccel-$name"
        cleanup += path
        return path
    }

    @AfterTest
    fun deleteTemporaries() {
        cleanup.forEach { remove(it) }
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
    fun aVideoToolboxDecoderProducesHardwareFramesAndTheDownloadBringsThemBack() {
        if (!FFmpeg.hasEncoder(CodecId.H264VideoToolbox.name)) {
            // Simulator-shaped build: decode-only VideoToolbox. The attach itself is proven by
            // the C control arm; without an encoder there is no clip to decode, and saying the
            // full path ran here would be a lie.
            println("hwaccel arm degraded: no h264_videotoolbox encoder on this target")
            return
        }

        val path = tmp("vt.mp4")
        writeH264(path, frames = 30)

        MediaSource.open(path).use { src ->
            val stream = src.primaryVideo ?: error("the encoder wrote no video stream")
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
                                assertNotNull(hw.hardwareSurface, "a hardware frame must carry its surface")
                                hw.downloadFromHardware().use { sw ->
                                    assertTrue(!sw.info.isHardware, "the download must produce a software frame")
                                    assertEquals(hw.info.pts, sw.info.pts, "the download lost the timestamp")
                                    assertEquals(320, sw.info.width)
                                    assertEquals(240, sw.info.height)
                                    val bytes = sw.copyPlanesToByteArray()
                                    assertTrue(bytes.isNotEmpty(), "the downloaded frame has no readable pixels")
                                    downloaded++
                                }
                                hardwareFrames++
                            }
                        }
                    }
                    assertTrue(hardwareFrames >= 5, "expected at least 5 hardware frames, got $hardwareFrames")
                    assertEquals(hardwareFrames, downloaded)
                    println("hwaccel arm: $hardwareFrames hardware frames, $downloaded downloads OK")
                }
            }
        }
    }
}
