package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The closed captions video frames carry, turned into timed text with no container stream behind
 * them (#179). Each frame carries one field 1 caption pair, as an A/53 SEI at 30 frames a second
 * does: a pop-on HELLO on CC1, shown at its end of caption and taken off a second later.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class ClosedCaptionDecoderContractTest {

    /** A CEA-608 byte with its odd parity bit set. */
    private fun odd(byte: Int): Byte = (if (onesIn7(byte) % 2 == 0) byte or 0x80 else byte).toByte()

    /** One frame's captions: a single field 1 pair. */
    private fun pair(a: Int, b: Int): ByteArray = byteArrayOf(0xFC.toByte(), odd(a), odd(b))

    /** The frames of the clip: resume loading, the letters, end of caption, nulls, then the erase. */
    private val frames: List<ByteArray> = buildList {
        add(pair(0x14, 0x20)); add(pair(0x14, 0x20))
        add(pair('H'.code, 'E'.code)); add(pair('L'.code, 'L'.code)); add(pair('O'.code, 0))
        add(pair(0x14, 0x2F)); add(pair(0x14, 0x2F))
        while (size < 29) add(pair(0, 0))
        add(pair(0x14, 0x2C)); add(pair(0x14, 0x2C))
    }

    private fun timeOf(frame: Int): Long = frame * 1_000_000L / 30

    private fun ClosedCaptionDecoder.captions(from: Long = 0): List<Subtitle> = buildList {
        frames.forEachIndexed { index, bytes -> decode(bytes, from + timeOf(index))?.let(::add) }
        drain()?.let(::add)
    }

    @Test
    fun theCaptionsFramesCarryBecomeTextAtTheirTimes() {
        val shown = ClosedCaptionDecoder.open().use { it.captions() }.filter { it.texts.isNotEmpty() }
        assertEquals(1, shown.size, "$shown")
        val caption = shown.single()
        assertEquals(timeOf(5), caption.startMicros, "the caption starts at its end of caption")
        assertEquals(timeOf(29), caption.endMicros, "the caption ends at its erase")
        assertTrue(caption.texts.single().endsWith("HELLO"), "the caption text: ${caption.texts}")
    }

    @Test
    fun theTimesAreTheCallersOwn() {
        val shown = ClosedCaptionDecoder.open().use { it.captions(from = 3_600_000_000L) }.first { it.texts.isNotEmpty() }
        assertEquals(3_600_000_000L + timeOf(5), shown.startMicros)
    }

    @Test
    fun framesWithoutCaptionTextGiveNothing() {
        ClosedCaptionDecoder.open().use { decoder ->
            repeat(60) { assertNull(decoder.decode(pair(0, 0), timeOf(it))) }
            assertNull(decoder.decode(ByteArray(0), timeOf(60)))
            assertNull(decoder.drain())
        }
    }

    @Test
    fun damagedBytesCostTheirOwnCaptionAndNothingMore() {
        ClosedCaptionDecoder.open().use { decoder ->
            // Bytes that are no caption pair at all, and a pair whose valid flag is clear.
            decoder.decode(byteArrayOf(1, 2), 0)
            decoder.decode(byteArrayOf(0xF8.toByte(), 0x7F, 0x7F), 0)
            decoder.flush()
            val shown = decoder.captions(from = 1_000_000L).firstOrNull { it.texts.isNotEmpty() }
            assertNotNull(shown, "a caption after the damage was lost")
            assertTrue(shown.texts.single().endsWith("HELLO"), "${shown.texts}")
        }
    }
}

/** The ones among the low seven bits, the bits a CEA-608 byte carries before its parity bit. */
private fun onesIn7(value: Int): Int = (0 until 7).count { (value shr it) and 1 == 1 }
