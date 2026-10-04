package io.github.yuroyami.kiteffmpeg

import kotlin.math.abs

/**
 * When a copied audio packet starts to show, in microseconds, as FFmpeg reads a stream's start: at
 * the packet's [time] in [timeBase] plus the [skipSamples] its decoder drops from the start, which
 * hide an encoder's priming and cover the samples before an MP4 edit list starts (#153).
 */
internal fun audioShownMicros(time: Long, timeBase: Rational, skipSamples: Long, sampleRate: Int): Long {
    val skipped = if (skipSamples > 0L) rescaleQ(skipSamples, Rational(1, sampleRate), Rational.Tb_us) else 0L
    return rescaleQ(time, timeBase, Rational.Tb_us) + skipped
}

/**
 * Where each packet of a copied audio stream sits on the output's timeline, counted in samples
 * (#154).
 *
 * A container whose clock is coarser than the samples, as Matroska's and FLV's millisecond is,
 * rounds every packet's time. An AAC packet of 1024 samples at 48 kHz lasts 21.333 ms, so its
 * packets are stamped 0, 21, 43 and 64 ms, and rescaled one at a time into an output that counts
 * samples, as MP4 does, they sat 1008 or 1056 samples apart, and the last lasted 1008. An MP4 edit
 * list then hid the wrong number of priming samples and the sound ended short.
 *
 * So a copy of such a stream counts samples. The first packet sits where the stream starts to show,
 * less the samples it skips, so the priming stays hidden to the sample. That start is known only to
 * within half a tick of the source's clock, so when the output's origin lies within that much of
 * it, the stream starts on the origin: Matroska puts AAC that started with its picture a third of a
 * millisecond after it, and an MP4, which cannot start a sound that little after its picture
 * without playing that much of the priming, would otherwise have played 16 samples of it. Each
 * packet after it starts where the one before it ended and lasts as many samples as FFmpeg reads
 * from the codec that it holds. Its own rounded time decides instead when the two disagree by more
 * than one and a half ticks of the source's clock, which only a real gap or overlap does, and the
 * count carries on from there. That is the rule FFmpeg's command line copies audio by
 * (`av_rescale_delta` in `mux_fixup_ts`), with one difference: the command line starts counting
 * from the first packet's rounded time and pulls each later packet back inside its own rounding,
 * which moves a packet by up to half a tick whenever the first rounding was off. Starting from
 * where the stream shows has no such error to correct, so the count is never pulled.
 *
 * A source whose clock states every sample exactly keeps its own times, because its rounding lost
 * nothing. A counted copy asks the muxer for the samples as its time base ([timeBaseFor]), so an
 * MP4 that takes its movie's clock from its streams', as FFmpeg's does, states the length of the
 * sound in samples too, rather than in the milliseconds a Matroska source counted in.
 */
internal class AudioCopyTimeline(private val timeBase: Rational, private val sampleRate: Int) {
    private val samples = Rational(1, sampleRate)

    /** One tick of the source's clock in samples, rounded up. */
    private val tick = ceilDiv(timeBase.num.toLong() * sampleRate, timeBase.den.toLong())

    /** Half a tick of the source's clock in microseconds, rounded down: how far a stated time can be from the truth. */
    private val halfTickMicros = timeBase.num * 1_000_000L / (2L * timeBase.den)

    /** How far a packet's own time may sit from where the count puts it before it is a gap. */
    private val tolerance = ceilDiv(3L * timeBase.num * sampleRate, 2L * timeBase.den) + 1

    /** The first timed packet's time, in [timeBase], and where it sits, in samples. */
    private var firstTime = FrameInfo.NOPTS
    private var anchor = 0L

    /** Where the packet placed last ended, in samples, or [NOTHING] when nothing said how long it lasts. */
    private var next = NOTHING

    /** How many samples the packet placed last lasts, or 0 when nothing says. */
    var lastSamples: Long = 0L
        private set

    /**
     * Places the next packet, whose time in [timeBase] is [time], or [FrameInfo.NOPTS] when it has
     * none, which skips [skipSamples] at its start and holds [frameSamples] samples as FFmpeg reads
     * the codec, or 0 when FFmpeg cannot tell, and whose stated [duration] in [timeBase] is 0 when
     * unknown, on a timeline whose zero is [originMicros]. Returns where it starts in samples, or
     * [NOTHING] when no time says, and leaves its length in [lastSamples].
     */
    fun place(time: Long, skipSamples: Long, frameSamples: Int, duration: Long, originMicros: Long): Long {
        val position = when {
            // A packet with no time follows the one before it, when that one said how long it lasts.
            time == FrameInfo.NOPTS -> next
            firstTime == FrameInfo.NOPTS -> {
                firstTime = time
                val shown = audioShownMicros(time, timeBase, skipSamples, sampleRate)
                val start = originMicros.coerceIn(shown - halfTickMicros, shown + halfTickMicros)
                anchor = rescaleQ(start - originMicros, Rational.Tb_us, samples) - skipSamples
                anchor
            }
            else -> {
                val own = anchor + rescaleQ(time - firstTime, timeBase, samples)
                if (next != NOTHING && abs(next - own) <= tolerance) next else own
            }
        }
        // The codec's count, unless the packet states a length it cannot be, as a codec whose
        // packets vary in length can when FFmpeg falls back on its usual frame size.
        val stated = if (duration > 0L) rescaleQ(duration, timeBase, samples) else 0L
        lastSamples = if (frameSamples > 0 && (stated == 0L || abs(stated - frameSamples) <= tick + 1)) {
            frameSamples.toLong()
        } else {
            stated
        }
        next = if (position != NOTHING && lastSamples > 0L) position + lastSamples else NOTHING
        return position
    }

    companion object {
        const val NOTHING: Long = Long.MIN_VALUE

        /**
         * True when copied audio at [sampleRate] from a source counting in [timeBase] is placed by
         * counting samples, because that clock cannot state every sample exactly, as Matroska's
         * millisecond cannot. An MP4 audio track's clock, the sample rate, can.
         */
        fun counts(timeBase: Rational, sampleRate: Int): Boolean =
            sampleRate > 0 && timeBase.den.toLong() % (timeBase.num.toLong() * sampleRate) != 0L

        /** The time base a copy from [timeBase] asks the muxer for: the samples when it [counts] them, else the source's own. */
        fun timeBaseFor(timeBase: Rational, sampleRate: Int): Rational =
            if (counts(timeBase, sampleRate)) Rational(1, sampleRate) else timeBase

        private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
    }
}
