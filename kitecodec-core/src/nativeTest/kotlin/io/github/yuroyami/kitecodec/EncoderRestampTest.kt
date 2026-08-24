package io.github.yuroyami.kitecodec

import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Timestamps the encoder has to invent. A decoder hands over frames with no pts for streams whose
 * packets carry none, and the encoder then builds the timeline itself. For audio the step from one
 * frame to the next is the duration of the frame ALREADY written, not of the frame being stamped:
 * 960 samples followed by 1024 must start at 0 and 960, because 960 is where the first frame ended.
 * Stepping by the current frame's count puts the second at 1024 and shifts everything after it.
 */
class EncoderRestampTest {

    private val tmpFiles = mutableListOf<String>()

    private fun tmp(name: String): String {
        val root = systemTempRoot()
        return "$root/kitecodec-test-$name".also { tmpFiles += it }
    }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    /**
     * Micro-seconds back to samples, rounded to nearest. One sample is 20.83 us at 48 kHz, so the
     * micro-second round trip is lossy and a plain truncation would land a sample short.
     */
    private fun microsToSamples(micros: Long, sampleRate: Int): Long =
        (micros * sampleRate + 500_000) / 1_000_000

    @Test
    fun missingAudioTimestampsStepByThePreviousFrameSampleCount() {
        val path = tmp("restamp.mka")
        val sampleRate = 48_000
        val sampleCounts = listOf(960, 1024)
        val timelineEnds = mutableListOf<Long>()

        MediaSink.open(path).use { sink ->
            val enc = sink.addAudioEncoder(
                AudioEncoderSpec(
                    // One packet per frame, no reordering and no fixed frame size, so what the file
                    // holds is exactly what was stamped.
                    codec = CodecId.PcmS16,
                    sampleRate = sampleRate,
                    channels = 1,
                    sampleFormat = SampleFormat.S16,
                )
            )
            withPacket { packet ->
                sampleCounts.forEach { samples ->
                    // No ptsMicros, so the frame reaches the encoder with AV_NOPTS_VALUE.
                    enc.core.encode(
                        packet,
                        Frame.ofAudio(ByteArray(samples * 2), samples, sampleRate, 1, SampleFormat.S16),
                    )
                    // outputMicros is where the written timeline ENDS, the frame just stamped
                    // included ("The last frame's own extent is included", EncoderCore), so after
                    // frame N it is exactly where frame N + 1 has to start.
                    timelineEnds += microsToSamples(enc.core.outputMicros, sampleRate)
                }
            }
        }
        // The running total of what was fed: 960 after the first frame, 960 + 1024 after the
        // second. That second value only comes out at 1984 if the second frame started at 960;
        // stepping by its own 1024 would end the timeline at 2048 instead.
        assertEquals(
            sampleCounts.runningReduce { total, samples -> total + samples }.map { it.toLong() },
            timelineEnds,
            "the second frame must start where the first one ended, not one of its own lengths later",
        )

        // And the file agrees. Every sample fed is in it, so the invented timeline neither skipped
        // nor overlapped: 960 + 1024 samples is 41 333 us of audio.
        MediaSource.open(path).use { src ->
            val audio = src.primaryAudio ?: error("no audio stream written")
            assertEquals(sampleRate, audio.audio!!.sampleRate)
            val decoded = runBlocking { src.decodedFrames(audio).toList() }
            try {
                assertEquals(
                    sampleCounts.sum(),
                    decoded.sumOf { it.info.sampleCount },
                    "the file does not hold every sample that was encoded",
                )
                val ptsSamples = decoded.filter { it.info.hasPts }.map {
                    it.info.pts * it.info.timeBase.num * sampleRate / it.info.timeBase.den
                }
                assertEquals(listOf(0L, 960L), ptsSamples, "the muxed timestamps disagree with the encoder")
            } finally {
                decoded.forEach { it.close() }
            }
        }
    }
}
