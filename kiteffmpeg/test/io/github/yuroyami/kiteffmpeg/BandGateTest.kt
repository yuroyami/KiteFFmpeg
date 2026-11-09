package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A Dolby Vision composition cannot be closed under a band that still composes (#257). */
class BandGateTest {

    @Test
    fun aCloseWhileABandRunsIsRefusedAndOneAfterItRuns() {
        val gate = BandGate()
        assertEquals("taken", gate.enter { "taken" })
        assertFailsWith<IllegalStateException> { gate.whenIdle { error("the close ran under a band") } }
        gate.exit()
        assertEquals(1, gate.whenIdle { 1 })
    }

    @Test
    fun aBandThatFindsTheCompositionClosedIsNotCounted() {
        val gate = BandGate()
        assertFailsWith<IllegalStateException> { gate.enter<Unit> { error("closed") } }
        assertEquals(2, gate.whenIdle { 2 })
    }
}
