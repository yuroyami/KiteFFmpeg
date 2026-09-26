package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/** The window a seek hands `avformat_seek_file`, whose derived direction follows the nearer side. */
@OptIn(KiteFFmpegLowLevelApi::class)
class SeekWindowTest {

    @Test
    fun forwardIsFlooredAtTheTarget() {
        assertEquals(5_000L to Long.MAX_VALUE, seekWindow(5_000L, SeekDirection.Forward, null))
        // A floor later than the target raises the window further.
        assertEquals(7_000L to Long.MAX_VALUE, seekWindow(5_000L, SeekDirection.Forward, 7_000L))
        assertEquals(5_000L to Long.MAX_VALUE, seekWindow(5_000L, SeekDirection.Forward, 1_000L))
    }

    @Test
    fun backwardIsCappedAtTheTarget() {
        assertEquals(Long.MIN_VALUE to 5_000L, seekWindow(5_000L, SeekDirection.Backward, null))
        assertEquals(1_000L to 5_000L, seekWindow(5_000L, SeekDirection.Backward, 1_000L))
    }

    @Test
    fun anyIsOpenOnBothSidesUnlessFloored() {
        assertEquals(Long.MIN_VALUE to Long.MAX_VALUE, seekWindow(5_000L, SeekDirection.Any, null))
        assertEquals(1_000L to Long.MAX_VALUE, seekWindow(5_000L, SeekDirection.Any, 1_000L))
    }
}
