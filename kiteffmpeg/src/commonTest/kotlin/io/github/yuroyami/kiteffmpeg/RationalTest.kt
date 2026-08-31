package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RationalTest {

    @Test
    fun normalizesOnConstruction() {
        assertEquals(Rational(1, 2), Rational(2, 4))
        assertEquals(Rational(1, 2), Rational(-2, -4))
        assertEquals(Rational(-1, 2), Rational(1, -2))
        assertEquals(1, Rational(2, 4).num)
        assertEquals(2, Rational(2, 4).den)
    }

    @Test
    fun zeroDenominatorRejected() {
        assertFailsWith<IllegalArgumentException> { Rational(1, 0) }
    }

    @Test
    fun multiplicationReduces() {
        // 30000/1001 * 1001/30000 = 1. Naive Int math would overflow before reducing.
        assertEquals(Rational(1, 1), Rational.Fps2997 * Rational(1001, 30_000))
        // Two large co-factors that overflow Int when multiplied naively (worst case ~4.6e18 fits Long).
        val a = Rational(1_000_000, 7)
        val b = Rational(7, 1_000_000)
        assertEquals(Rational(1, 1), a * b)
    }

    @Test
    fun scalarTimesAvoidsPrematureOverflow() {
        // A pts value in 1/90000 tick-space converted with a microsecond time-base:
        // scalar * num would overflow if computed before dividing by den.
        val pts = 4_000_000_000L  // > Int.MAX, plausible large tick count
        assertEquals(4_000_000_000L / 1_000_000, Rational.Tb_us * pts)
        // Exactness when den divides scalar after gcd reduction.
        assertEquals(2_000L, Rational(1, 2) * 4_000L)
    }

    @Test
    fun inverseSwapsNumDen() {
        assertEquals(Rational(30, 1), Rational(1, 30).inverse)
        assertEquals(Rational(1001, 30_000), Rational.Fps2997.inverse)
    }

    @Test
    fun doubleConversion() {
        assertTrue(kotlin.math.abs(Rational.Fps2997.asDouble - 29.97) < 0.01)
        assertEquals(0.5, Rational(1, 2).asDouble)
    }

    @Test
    fun stringForm() {
        assertEquals("30", Rational(30, 1).toString())
        assertEquals("1/25", Rational(1, 25).toString())
    }
}

class RationalEdgeCaseTest {

    @Test
    fun inverseStaysNormalized() {
        // Regression: inverse used to bypass the normalizing constructor, producing a
        // negative denominator that broke equals/toString.
        val r = Rational(-1, 2).inverse
        assertEquals(Rational(-2, 1), r)
        assertTrue(r.den > 0, "denominator must stay positive, got ${r.num}/${r.den}")
        assertEquals("-2", r.toString())
    }

    @Test
    fun zeroHasNoInverse() {
        assertFailsWith<IllegalArgumentException> { Rational.Zero.inverse }
    }

    @Test
    fun intMinValueNormalizes() {
        // Int.MIN_VALUE math must happen in Long, because naive sign*num overflows.
        val r = Rational(Int.MIN_VALUE, 2)
        assertEquals(Int.MIN_VALUE / 2, r.num)
        assertEquals(1, r.den)
        val flipped = Rational(1, -2)
        assertEquals(-1, flipped.num)
        assertEquals(2, flipped.den)
    }

    @Test
    fun comparisonIsExact() {
        assertTrue(Rational(1, 30) < Rational(1, 25))
        assertTrue(Rational.Fps2997 < Rational.Fps30)
        assertTrue(Rational(-1, 2) < Rational.Zero)
        assertEquals(0, Rational(2, 4).compareTo(Rational(1, 2)))
    }

    @Test
    fun additionSubtractionDivision() {
        assertEquals(Rational(5, 6), Rational(1, 2) + Rational(1, 3))
        assertEquals(Rational(1, 6), Rational(1, 2) - Rational(1, 3))
        assertEquals(Rational(3, 2), Rational(1, 2) / Rational(1, 3))
        assertEquals(Rational(-1, 2), -Rational(1, 2))
        assertFailsWith<IllegalArgumentException> { Rational(1, 2) / Rational.Zero }
    }

    @Test
    fun scalarTimesOverflowThrowsInsteadOfWrapping() {
        assertFailsWith<ArithmeticException> { Rational(1_000_000, 1) * Long.MAX_VALUE }
    }

    /**
     * P1-29. The floors, which 32-bit and 64-bit negation both map onto themselves. Every case
     * below used to return a wrong answer silently rather than refuse.
     */
    @Test
    fun theMinimumValuesRefuseInsteadOfWrapping() {
        // -Int.MIN_VALUE is Int.MIN_VALUE again, so this used to answer with its own input.
        assertFailsWith<ArithmeticException> { -Rational(Int.MIN_VALUE, 1) }

        // Long.MIN_VALUE * -1 wraps to Long.MIN_VALUE, and so does dividing it back by -1, so the
        // overflow check compared equal and passed the wrapped product off as the answer.
        assertFailsWith<ArithmeticException> { Rational(-1, 1) * Long.MIN_VALUE }

        // The magnitude of the scalar is no longer taken by negating it, so a floor scalar that
        // reduces exactly is answered rather than mangled.
        assertEquals(Long.MIN_VALUE / 2, Rational(1, 2) * Long.MIN_VALUE)
    }

    @Test
    fun negationStillRoundTripsEverywhereElse() {
        assertEquals(Rational(-1, 2), -Rational(1, 2))
        assertEquals(Rational(1, 2), -(-Rational(1, 2)))
        assertEquals(Rational(Int.MAX_VALUE, 1), -Rational(-Int.MAX_VALUE, 1))
    }
}
