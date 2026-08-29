package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Device-only boundary check: successful facade initialization includes the required VM attach. */
internal class CodecAndroidDeviceTest {
    @Test
    fun identityGateVmAttachAndHandleLedgerAreHealthy() {
        assertTrue(FFmpeg.identity.isAcceptable, FFmpeg.identity.describe())
        assertTrue(FFmpeg.hasDecoder("mpeg4"))
        assertEquals(0L, contractLiveHandleCount())
    }
}
