@file:OptIn(KiteCodecLowLevelApi::class)

package io.github.yuroyami.kitecodec

import io.github.yuroyami.kitecodec.dsl.DecoderOptions
import io.github.yuroyami.kitecodec.dsl.videoFilters
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlinx.cinterop.toKString
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The KD register's two real-media proofs (KPKMP 17.10, KD-1 and KD-2 gates): a DSL-compiled
 * chain runs through the existing FilterGraph path unchanged, and decoder options demonstrably
 * reach FFmpeg, both the wrong-key EINVAL reproduction and the scrubbing preset's measured
 * effect.
 */
class KdIntegrationTest {

    private val cleanup = mutableListOf<String>()

    private fun tmp(name: String): String {
        val dir = systemTempRoot()
        val path = "$dir/kd-$name"
        cleanup += path
        return path
    }

    @AfterTest
    fun deleteTemporaries() {
        cleanup.forEach { remove(it) }
    }

    private fun writeVideo(path: String, frames: Int) {
        MediaSink.open(path).use { sink ->
            val video = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64,
                    height = 64,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                ),
            )
            runBlocking {
                video.drive(
                    (0 until frames).asFlow().map { index ->
                        Frame.ofVideo(
                            bytes = ByteArray(64 * 64 * 3 / 2) { (it + index).toByte() },
                            width = 64,
                            height = 64,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = index * 1_000_000L / 30,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun aDslCompiledChainRunsThroughTheExistingFilterPath() = runBlocking {
        val chain = videoFilters {
            scale(32, 32)
            format(PixelFormat("yuv420p"))
        }
        chain.requireAvailable()
        FilterGraph.buildVideo(
            description = chain.compile(),
            width = 64,
            height = 64,
            pixelFormat = PixelFormat("yuv420p"),
            timeBase = Rational(1, 30),
            frameRate = Rational(30, 1),
        ).use { graph ->
            val input = Frame.ofVideo(
                bytes = ByteArray(64 * 64 * 3 / 2) { it.toByte() },
                width = 64,
                height = 64,
                pixelFormat = PixelFormat.Yuv420p,
                ptsMicros = 0L,
            )
            val outputs = graph.process(kotlinx.coroutines.flow.flowOf(input)).toList()
            try {
                assertTrue(outputs.isNotEmpty(), "the DSL chain produced no frame")
                assertEquals(32, outputs.first().info.width)
                assertEquals(32, outputs.first().info.height)
            } finally {
                outputs.forEach { it.close() }
            }
        }
    }

    @Test
    fun aWrongDecoderOptionKeyReproducesTheEinvalPath() {
        val path = tmp("einval.mkv")
        writeVideo(path, frames = 5)
        MediaSource.open(path).use { src ->
            val video = src.primaryVideo ?: error("no video stream written")
            val failure = assertFailsWith<FFmpegException> {
                src.openDecoder(
                    video,
                    options = DecoderOptions(options = mapOf("definitely_not_an_avoption" to "1")),
                ).close()
            }
            assertTrue(
                failure.message.orEmpty().contains("av_opt_set"),
                "the wrong key must fail at the funnel, got: ${failure.message}",
            )
        }
    }

    @Test
    fun anUnconsumedOpenOptionIsNamedNeverSwallowed() {
        val path = tmp("unused.mkv")
        writeVideo(path, frames = 5)
        MediaSource.open(path, mapOf("definitely_not_an_option" to "1")).use { src ->
            assertEquals(listOf("definitely_not_an_option"), src.unusedOpenOptions)
        }
    }

    @Test
    fun aConsumedPreOpenOptionLeavesNoRemainder() {
        val path = tmp("probesize.mkv")
        writeVideo(path, frames = 5)
        MediaSource.open(path, mapOf("probesize" to "65536")).use { src ->
            assertEquals(emptyList(), src.unusedOpenOptions)
            assertTrue(src.streams.isNotEmpty(), "the shrunk probe still found the stream")
        }
    }

    @Test
    fun aChapterlessContainerReportsAnEmptyTable() {
        val path = tmp("nochapters.mkv")
        writeVideo(path, frames = 5)
        MediaSource.open(path).use { src ->
            assertEquals(emptyList(), src.chapters)
            assertEquals(src.chapters, src.mediaInfo.chapters)
            assertEquals(src.formatName, src.mediaInfo.formatName)
        }
    }

    @Test
    fun theScrubbingPresetMeasurablySkipsNonKeyframes() {
        val path = tmp("scrub.mkv")
        writeVideo(path, frames = 60)

        fun decodeAll(options: DecoderOptions?): Int {
            var received = 0
            MediaSource.open(path).use { src ->
                val video = src.primaryVideo ?: error("no video stream written")
                src.openDecoder(video, options = options).use { decoder ->
                    src.openPacketReader(listOf(video)).use { reader ->
                        while (true) {
                            val packet = reader.read() ?: break
                            packet.use {
                                while (!decoder.send(it)) {
                                    decoder.receive()?.close() ?: error("refused packet, no output")
                                    received++
                                }
                            }
                            while (true) {
                                val frame = decoder.receive() ?: break
                                frame.close()
                                received++
                            }
                        }
                    }
                    decoder.send(null)
                    while (true) {
                        val frame = decoder.receive() ?: break
                        frame.close()
                        received++
                    }
                }
            }
            return received
        }

        val full = decodeAll(null)
        val scrubbed = decodeAll(DecoderOptions.Scrubbing)
        assertTrue(full >= 60, "the full decode must deliver every frame, got $full")
        assertTrue(scrubbed in 1 until full, "scrubbing decoded $scrubbed of $full; the skip pair did nothing")
    }
}
