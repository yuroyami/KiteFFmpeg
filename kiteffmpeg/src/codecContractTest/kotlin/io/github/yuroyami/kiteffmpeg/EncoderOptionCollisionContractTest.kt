package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A spec that sets the same thing twice is refused rather than silently resolved.
 *
 * The typed fields are written into the codec context first and `options` is applied over them, so
 * before this an option naming the same setting quietly won: a spec asking for 8 Mb/s with
 * `options["b"] = "500k"` encoded at 500k and still reported 8 Mb/s in the field the caller set.
 *
 * Reversing the precedence would only be wrong the other way, and nothing would fail either way.
 * The spec said two things; this says which two and refuses.
 */
class EncoderOptionCollisionContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun sink(): MediaSink = MediaSink.open(contractOutputPath("mkv").also(paths::add))

    @Test
    fun aVideoOptionThatDuplicatesATypedFieldIsRefused() {
        sink().use { sink ->
            val refusal = assertFailsWith<FFmpegException> {
                sink.addVideoEncoder(
                    VideoEncoderSpec(
                        codec = CodecId("mpeg4"),
                        width = 64,
                        height = 64,
                        frameRate = Rational(25, 1),
                        bitrateBps = 8_000_000L,
                        options = mapOf("b" to "500k"),
                    ),
                )
            }
            val message = refusal.message.orEmpty()
            // Both halves named, because "invalid options" would leave the caller hunting for
            // which of the two settings it is being told about.
            assertTrue("b" in message, "the refusal must name the option, said: $message")
            assertTrue("bitrateBps" in message, "and the field it clashes with, said: $message")
        }
    }

    @Test
    fun anAudioOptionThatDuplicatesATypedFieldIsRefused() {
        sink().use { sink ->
            val refusal = assertFailsWith<FFmpegException> {
                sink.addAudioEncoder(
                    AudioEncoderSpec(
                        codec = CodecId("aac"),
                        sampleRate = 44_100,
                        options = mapOf("ar" to "48000"),
                    ),
                )
            }
            assertTrue("sampleRate" in refusal.message.orEmpty())
        }
    }

    @Test
    fun anOptionWithNoTypedFieldBesideItStillPassesThrough() {
        // The whole point of the map. A guard that refused anything unfamiliar would be worse than
        // the silent precedence it replaced, so this is the case that keeps it honest.
        sink().use { sink ->
            sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64,
                    height = 64,
                    frameRate = Rational(25, 1),
                    options = mapOf("bf" to "0"),
                ),
            )
        }
    }
}
