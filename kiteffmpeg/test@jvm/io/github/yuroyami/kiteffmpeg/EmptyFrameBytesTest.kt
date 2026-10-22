package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A frame with no picture and no samples holds 0 bytes, as the common contract says and the native
 * backend answers. The JVM backend used to ask FFmpeg to size one, which refuses, so every copy
 * of such a frame threw. The public API makes these frames only on paths no test reaches, so the
 * frame is built here from the bridge.
 */
class EmptyFrameBytesTest {

    @Test
    fun aFrameWithNoPictureOrSamplesHoldsNoBytes() {
        for (type in listOf(MediaType.Video, MediaType.Audio)) {
            Frame(Internals.frameAlloc(), ownsToken = true, streamIndex = 0, streamType = type, streamTimeBase = Rational(1, 1000)).use { frame ->
                assertEquals(0, frame.planesByteCount(), "$type planesByteCount")
                assertEquals(0, frame.copyPlanesToByteArray().size, "$type copyPlanesToByteArray")
                assertEquals(0, frame.copyPlanesInto(ByteArray(0)), "$type copyPlanesInto")
            }
        }
    }
}
