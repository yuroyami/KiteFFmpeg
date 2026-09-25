package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The request's own bookkeeping, without any media. */
class OpenInterruptTest {

    @Test
    fun aBoundTargetRunsOnceWhenTheRequestIsRaised() {
        val request = OpenInterrupt()
        var runs = 0
        request.bind { runs++ }
        assertFalse(request.isInterrupted)
        request.interrupt()
        request.interrupt()
        assertTrue(request.isInterrupted)
        assertEquals(1, runs, "a second interrupt must not run the target again")
    }

    @Test
    fun aTargetBoundAfterTheRequestWasRaisedRunsAtOnce() {
        val request = OpenInterrupt().apply { interrupt() }
        var runs = 0
        request.bind { runs++ }
        assertEquals(1, runs)
    }

    @Test
    fun anUnboundTargetDoesNotRun() {
        val request = OpenInterrupt()
        var runs = 0
        val target: () -> Unit = { runs++ }
        request.bind(target)
        request.unbind(target)
        request.interrupt()
        assertEquals(0, runs)
    }
}
