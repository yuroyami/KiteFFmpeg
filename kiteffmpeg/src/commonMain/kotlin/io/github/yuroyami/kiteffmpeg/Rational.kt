package io.github.yuroyami.kiteffmpeg

/**
 * FFmpeg's `AVRational`: an exact fraction. Use it for time-bases, frame rates and aspect ratios.
 * Float conversion is lossy; do all arithmetic on the rational form when possible.
 *
 * Always stored normalized: reduced by gcd, denominator positive. `Rational(2, 4) == Rational(1, 2)`.
 */
public class Rational private constructor(public val num: Int, public val den: Int) : Comparable<Rational> {

    public val asDouble: Double get() = num.toDouble() / den.toDouble()
    public val asFloat: Float   get() = asDouble.toFloat()

    /**
     * Reciprocal: `Rational(1, 30).inverse == Rational(30, 1)`.
     *
     * @throws IllegalArgumentException on [Zero] (the reciprocal would need a zero denominator)
     */
    public val inverse: Rational
        get() {
            require(num != 0) { "Rational.Zero has no reciprocal" }
            return invoke(den, num)
        }

    public operator fun times(other: Rational): Rational =
        of(num.toLong() * other.num, den.toLong() * other.den)

    public operator fun div(other: Rational): Rational {
        require(other.num != 0) { "Division by Rational.Zero" }
        return of(num.toLong() * other.den, den.toLong() * other.num)
    }

    public operator fun plus(other: Rational): Rational =
        of(num.toLong() * other.den + other.num.toLong() * den, den.toLong() * other.den)

    public operator fun minus(other: Rational): Rational =
        of(num.toLong() * other.den - other.num.toLong() * den, den.toLong() * other.den)

    /**
     * Negation, widened before it negates.
     *
     * `-Int.MIN_VALUE` is `Int.MIN_VALUE` again in 32-bit arithmetic, so negating a rational whose
     * numerator sat at the floor used to return the SAME rational and call it the opposite sign
     * (audit P1-29). Widening first makes that case a value that does not fit, which [of] refuses
     * out loud rather than answering wrongly.
     *
     * @throws ArithmeticException when the negated numerator does not fit 32 bits
     */
    public operator fun unaryMinus(): Rational = of(-num.toLong(), den.toLong())

    /**
     * `scalar * num / den` with a Long intermediate. Multiplies after reducing
     * [scalar] against [den] first, so e.g. `pts * Rational(1, 1_000_000)` stays exact
     * far beyond what naive `scalar * num` would overflow at. For timestamp conversion
     * between two arbitrary time-bases prefer the native `av_rescale_q` path (128-bit).
     *
     * @throws ArithmeticException when the product overflows Long even after reduction
     */
    public operator fun times(scalar: Long): Long {
        if (num == 0) return 0L
        // gcd against the DENOMINATOR's residue rather than the scalar's magnitude. Taking
        // `-scalar` first wrapped Long.MIN_VALUE straight back to itself, so the reduction ran on a
        // negative magnitude and answered nonsense. A remainder by a positive
        // denominator is safe for every input, including the floor.
        val d = den.toLong()
        var a = scalar % d
        if (a < 0) a = -a
        var x = d
        var y = a
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        val g = if (x == 0L) 1L else x
        val reduced = scalar / g
        // The one product whose overflow the division check below cannot see: Long.MIN_VALUE * -1
        // wraps to Long.MIN_VALUE, and dividing it back by -1 wraps to the same value again, so the
        // check compares equal and the wrap passes for the answer.
        if (reduced == Long.MIN_VALUE && num == -1) {
            throw ArithmeticException(
                "$scalar * $this overflows Long. Use av_rescale_q-backed APIs for timestamp math",
            )
        }
        val product = reduced * num
        if (product / num != reduced) {
            throw ArithmeticException("$scalar * $this overflows Long. Use av_rescale_q-backed APIs for timestamp math")
        }
        return product / (d / g)
    }

    /** Exact comparison: `Rational(1, 30) < Rational(1, 25)`. */
    override fun compareTo(other: Rational): Int =
        (num.toLong() * other.den).compareTo(other.num.toLong() * den)

    override fun equals(other: Any?): Boolean =
        other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = num * 31 + den

    override fun toString(): String = if (den == 1) "$num" else "$num/$den"

    public companion object {
        /**
         * Build a normalized rational. All math happens in Long, so `Int.MIN_VALUE`
         * numerators/denominators normalize correctly.
         *
         * @throws IllegalArgumentException when [den] is zero
         */
        public operator fun invoke(num: Int, den: Int): Rational = of(num.toLong(), den.toLong())

        /**
         * Build from a Long-valued fraction, exactly or not at all.
         *
         * The old behaviour halved both components until they fit and coerced a denominator that
         * reached zero back to one, which silently turned 50000/1 * 50000/1 into 1250000000/1
         * (audit KiteFFmpeg P1-9). A rational that cannot hold the exact value now throws, the
         * same decision `times(scalar)` already made, and the message points at the 128-bit
         * av_rescale_q path that exists for exactly this.
         *
         * @throws ArithmeticException when the reduced fraction does not fit Int components
         */
        internal fun of(num: Long, den: Long): Rational {
            require(den != 0L) { "Rational denominator can't be zero" }
            if (num == Long.MIN_VALUE || den == Long.MIN_VALUE) {
                // Negating Long.MIN_VALUE wraps to itself, so the sign normalization below would
                // be wrong before any range check could see it.
                throw ArithmeticException("$num/$den cannot be represented as a Rational")
            }
            val sign = if (den < 0) -1 else 1
            var n = sign * num
            var d = sign * den
            val g = gcd(n, d)
            if (g > 1) { n /= g; d /= g }
            if (n !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || d > Int.MAX_VALUE) {
                throw ArithmeticException(
                    "$num/$den reduces to $n/$d, which does not fit 32-bit components. " +
                        "Use av_rescale_q-backed APIs for timestamp math",
                )
            }
            return Rational(n.toInt(), d.toInt())
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = if (a < 0) -a else a
            var y = if (b < 0) -b else b
            while (y != 0L) { val t = x % y; x = y; y = t }
            return if (x == 0L) 1L else x
        }

        public val Zero    : Rational = Rational(0, 1)
        public val Tb_us   : Rational = Rational(1, 1_000_000)
        public val Fps24   : Rational = Rational(24, 1)
        public val Fps25   : Rational = Rational(25, 1)
        public val Fps30   : Rational = Rational(30, 1)
        public val Fps60   : Rational = Rational(60, 1)
        public val Fps2997 : Rational = Rational(30_000, 1001)
        public val Fps2398 : Rational = Rational(24_000, 1001)
    }
}
