package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dav1d switch's consistency law (D-7, KC-AV1SW). dav1d is OPTIONAL, so this test asserts
 * no presence: it asserts that the linked FFmpeg's own configure record and its decoder table
 * AGREE about libdav1d. A tree built with the switch on that cannot find the decoder, or a
 * default tree that somehow grew one, are both integration defects this catches on any machine.
 */
class Dav1dConsistencyTest {

    @Test
    fun theConfigureRecordAndTheDecoderTableAgreeAboutLibdav1d() {
        val configuredIn = FFmpeg.buildConfiguration.contains("--enable-libdav1d")
        assertEquals(
            configuredIn,
            FFmpeg.hasDecoder("libdav1d"),
            "configure says enable-libdav1d=$configuredIn but the decoder table disagrees",
        )
    }
}
