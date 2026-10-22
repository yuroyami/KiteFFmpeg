package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Stages the raw duration every packet of [module] reports, in its own time base units. */
@JsFun(
    """(m, ticks) => {
        m._ffkmp_packet_duration = () => BigInt(ticks);
        m._ffkmp_rescale_q = (v, sn, sd, dn, dd) => (v * BigInt(sn) * BigInt(dd)) / (BigInt(sd) * BigInt(dn));
        m._ffkmp_packet_unref = () => {};
        m._ffkmp_packet_free = () => {};
    }""",
)
private external fun stagePacketDuration(module: JsAny, ticks: Int)

/** A packet's duration on the web follows the rule of the JVM and native backends: not positive is none. */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebPacketDurationTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private fun durationFor(ticks: Int): Long? {
        val module = fakeCodecModule()
        stagePacketDuration(module, ticks)
        useCodecModule(module)
        return Packet(0x100, Rational.of(1L, 1_000L)).use { it.durationMicros }
    }

    @Test
    fun aNegativeDurationIsNone() {
        assertNull(durationFor(-40))
    }

    @Test
    fun aZeroDurationIsNone() {
        assertNull(durationFor(0))
    }

    @Test
    fun aPositiveDurationIsRescaled() {
        assertEquals(40_000L, durationFor(40))
    }
}
