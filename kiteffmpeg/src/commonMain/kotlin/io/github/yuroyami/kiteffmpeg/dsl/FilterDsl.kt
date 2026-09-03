package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.SampleFormat
import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational

/**
 * The typed filter DSL (KD-1): a compilation layer onto the description STRINGS the
 * existing `FilterGraph.buildVideo`/`buildAudio` already take. Nothing here crosses into C; the
 * laws that bind it:
 *
 * - Values, not magic (law 4): every construct is a plain data class first, and [FilterChain.compile]
 *   is a pure function whose output is inspectable, printable and golden-tested.
 * - Curated core plus escape hatch, never a mirror (law 2): the typed set below is the register's
 *   few dozen; [Raw] carries any chain the typed set lacks, verbatim.
 * - Capability-honest (law 6): [FilterChain.requireAvailable] asks [FFmpeg.hasFilter] for every
 *   typed step and fails TYPED naming the missing filter; it never silently no-ops. [Raw] steps
 *   are the caller's own claim and are exempt, which their KDoc says.
 */
public sealed interface FilterStep {
    /** The FFmpeg filter name, for capability checks. Null for [Raw], whose content is opaque. */
    public val filterName: String?

    /** The compiled `name=args` fragment, exactly as it joins the description string. */
    public fun compile(): String
}

/** Kotlin/JS renders an integral Double without `.0`; keep generated FFmpeg text target-stable. */
private fun Double.ffmpegText(): String {
    val rendered = toString()
    return if (isFinite() && '.' !in rendered && 'e' !in rendered && 'E' !in rendered) "$rendered.0" else rendered
}

// --- Video steps ------------------------------------------------------------------------------

public data class Scale(val width: Int, val height: Int) : FilterStep {
    init {
        require(width != 0 && height != 0) { "scale takes -1 to keep aspect, never 0" }
    }

    override val filterName: String get() = "scale"
    override fun compile(): String = "scale=$width:$height"
}

public data class Crop(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null,
) : FilterStep {
    override val filterName: String get() = "crop"
    override fun compile(): String = buildString {
        append("crop=").append(width).append(':').append(height)
        if (x != null || y != null) append(':').append(x ?: 0).append(':').append(y ?: 0)
    }
}

public data class Pad(
    val width: Int,
    val height: Int,
    val x: Int = -1,
    val y: Int = -1,
    val color: String = "black",
) : FilterStep {
    override val filterName: String get() = "pad"

    /** -1 centres, matching pad's own `(ow-iw)/2` idiom without expression strings in the API. */
    override fun compile(): String {
        val px = if (x < 0) "(ow-iw)/2" else x.toString()
        val py = if (y < 0) "(oh-ih)/2" else y.toString()
        return "pad=$width:$height:$px:$py:${escapeFilterValue(color)}"
    }
}

public enum class QuarterTurn(internal val transpose: String) {
    Clockwise("clock"),
    CounterClockwise("cclock"),
    ClockwiseWithFlip("clock_flip"),
    CounterClockwiseWithFlip("cclock_flip"),
}

public data class Transpose(val turn: QuarterTurn) : FilterStep {
    override val filterName: String get() = "transpose"
    override fun compile(): String = "transpose=${turn.transpose}"
}

public data class Fps(val rate: Rational) : FilterStep {
    override val filterName: String get() = "fps"
    override fun compile(): String = "fps=${rate.num}/${rate.den}"
}

public data class Format(val format: PixelFormat) : FilterStep {
    override val filterName: String get() = "format"
    override fun compile(): String = "format=${format.name}"
}

/** Neutral values compile away, so `Eq(brightness = 0.1)` sends exactly one knob. */
public data class Eq(
    val brightness: Double? = null,
    val contrast: Double? = null,
    val saturation: Double? = null,
    val gamma: Double? = null,
) : FilterStep {
    override val filterName: String get() = "eq"
    override fun compile(): String {
        val args = buildList {
            brightness?.let { add("brightness=${it.ffmpegText()}") }
            contrast?.let { add("contrast=${it.ffmpegText()}") }
            saturation?.let { add("saturation=${it.ffmpegText()}") }
            gamma?.let { add("gamma=${it.ffmpegText()}") }
        }
        require(args.isNotEmpty()) { "eq with every knob absent does nothing; drop the step instead" }
        return "eq=${args.joinToString(":")}"
    }
}

public enum class Deinterlacer(internal val ff: String) { Yadif("yadif"), Bwdif("bwdif") }

public data class Deinterlace(val with: Deinterlacer = Deinterlacer.Bwdif) : FilterStep {
    override val filterName: String get() = with.ff
    override fun compile(): String = with.ff
}

public data class DrawBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val color: String = "red",
    val thickness: Int = 3,
) : FilterStep {
    override val filterName: String get() = "drawbox"
    override fun compile(): String =
        "drawbox=$x:$y:$width:$height:${escapeFilterValue(color)}:$thickness"
}

// --- Audio steps ------------------------------------------------------------------------------

/** Linear gain: 1.0 is unity, 0.5 is half amplitude. */
public data class Volume(val gain: Double) : FilterStep {
    override val filterName: String get() = "volume"
    override fun compile(): String = "volume=${gain.ffmpegText()}"
}

public data class Atempo(val tempo: Double) : FilterStep {
    init {
        require(tempo in 0.5..100.0) { "atempo accepts 0.5 to 100, got $tempo" }
    }

    override val filterName: String get() = "atempo"
    override fun compile(): String = "atempo=${tempo.ffmpegText()}"
}

public data class Aresample(val sampleRate: Int) : FilterStep {
    override val filterName: String get() = "aresample"
    override fun compile(): String = "aresample=$sampleRate"
}

/**
 * A downmix or routing matrix: `Pan("stereo", "c0=FL+0.7*FC", "c1=FR+0.7*FC")`. The channel
 * expressions are FFmpeg's own pan syntax, escaped as one value because pan's separator is the
 * pipe, which the chain separator must not see.
 */
public data class Pan(val layout: String, val outputs: List<String>) : FilterStep {
    public constructor(layout: String, vararg outputs: String) : this(layout, outputs.toList())

    init {
        require(outputs.isNotEmpty()) { "pan with no output expressions routes nothing" }
    }

    override val filterName: String get() = "pan"
    override fun compile(): String =
        "pan=${escapeFilterValue((listOf(layout) + outputs).joinToString("|"))}"
}

public data class AudioFormat(
    /**
     * The sample format to pin, typed. It was a raw String while [SampleFormat] already existed,
     * which is the one place in this DSL where a caller could spell a format FFmpeg would reject
     * and only find out at parse time. Still escaped on the way out: a value class holds whatever
     * it was constructed with.
     */
    val sampleFormat: SampleFormat? = null,
    val sampleRate: Int? = null,
    val channelLayout: String? = null,
) : FilterStep {
    override val filterName: String get() = "aformat"
    override fun compile(): String {
        val args = buildList {
            // Escaped like every other value. This one alone was interpolated raw, one
            // line above a neighbour that did escape, so `AudioFormat(sampleFormat = "fltp,volume=0")`
            // silently appended a whole extra filter to the graph.
            sampleFormat?.let { add("sample_fmts=${escapeFilterValue(it.name)}") }
            sampleRate?.let { add("sample_rates=$it") }
            channelLayout?.let { add("channel_layouts=${escapeFilterValue(it)}") }
        }
        require(args.isNotEmpty()) { "aformat with every field absent pins nothing; drop the step instead" }
        return "aformat=${args.joinToString(":")}"
    }
}

/** EBU R128 loudness normalisation with the filter's own defaults. */
public data class Loudnorm(
    val integrated: Double = -24.0,
    val truePeak: Double = -2.0,
    val range: Double = 7.0,
) : FilterStep {
    override val filterName: String get() = "loudnorm"
    override fun compile(): String =
        "loudnorm=I=${integrated.ffmpegText()}:TP=${truePeak.ffmpegText()}:LRA=${range.ffmpegText()}"
}

/**
 * The escape hatch (law 2): any chain fragment the typed set lacks, joined verbatim. Capability
 * checks cannot see inside it; the caller owns that claim.
 */
public data class Raw(val fragment: String) : FilterStep {
    init {
        require(fragment.isNotBlank()) { "a raw fragment cannot be blank" }
    }

    override val filterName: String? get() = null
    override fun compile(): String = fragment
}

// --- The chain --------------------------------------------------------------------------------

public data class FilterChain(val steps: List<FilterStep>) {
    init {
        require(steps.isNotEmpty()) { "a filter chain needs at least one step" }
    }

    /** The description string, exactly what `FilterGraph.buildVideo`/`buildAudio` receive. */
    public fun compile(): String = steps.joinToString(",") { it.compile() }

    /**
     * The typed steps whose filter this FFmpeg build does not carry, in the order they appear and
     * without repeats. Empty means the chain can be built.
     *
     * [Raw] steps are exempt by design: they carry no filter name this side can check, and their
     * KDoc says whose claim they are.
     */
    public fun missingFilters(): List<String> = missingFilters(FFmpeg::hasFilter)

    /**
     * The same answer against a supplied availability predicate.
     *
     * It exists because the public form can only ever report what the FFmpeg this test run happens
     * to link, and a development machine links a build that carries everything: on it the missing
     * case is unreachable and a suite that only called the public form would pass while proving
     * nothing about ordering, de-duplication or the exemption. The predicate is the seam that makes
     * those testable on any build.
     */
    internal fun missingFilters(has: (String) -> Boolean): List<String> =
        steps.mapNotNull { it.filterName }.distinct().filterNot(has)

    /**
     * Fails typed when this build lacks any of the chain's filters, naming ALL of them rather than
     * the first: a chain that needs two filters this build does not carry should say so once,
     * not across two failed builds.
     *
     * The error is [FFmpegError.FilterNotFound], which is what FFmpeg itself would eventually
     * answer, so a caller can catch one type whether the refusal came from here or from the parse.
     */
    public fun requireAvailable(): Unit = requireAvailable(FFmpeg::hasFilter)

    /** [requireAvailable] against a supplied predicate; see [missingFilters]. */
    internal fun requireAvailable(has: (String) -> Boolean) {
        val missing = missingFilters(has)
        if (missing.isEmpty()) return
        throw FFmpegException(
            FFmpegError.FilterNotFound(
                FFmpegError.AVERROR_FILTER_NOT_FOUND,
                "this FFmpeg build has no ${missing.joinToString(", ")}; " +
                    "the chain needs a tier that carries " +
                    if (missing.size == 1) "it" else "them",
            ),
        )
    }
}

// --- Builder sugar (law 4: sugar over constructors, never the other way) -----------------------

public class VideoFilterBuilder internal constructor() {
    private val steps = mutableListOf<FilterStep>()

    public fun scale(width: Int, height: Int) { steps += Scale(width, height) }
    public fun crop(width: Int, height: Int, x: Int? = null, y: Int? = null) { steps += Crop(width, height, x, y) }
    public fun pad(width: Int, height: Int, x: Int = -1, y: Int = -1, color: String = "black") {
        steps += Pad(width, height, x, y, color)
    }
    public fun transpose(turn: QuarterTurn) { steps += Transpose(turn) }
    public fun fps(rate: Rational) { steps += Fps(rate) }
    public fun format(format: PixelFormat) { steps += Format(format) }
    public fun eq(
        brightness: Double? = null,
        contrast: Double? = null,
        saturation: Double? = null,
        gamma: Double? = null,
    ) { steps += Eq(brightness, contrast, saturation, gamma) }
    public fun deinterlace(with: Deinterlacer = Deinterlacer.Bwdif) { steps += Deinterlace(with) }
    public fun drawBox(x: Int, y: Int, width: Int, height: Int, color: String = "red", thickness: Int = 3) {
        steps += DrawBox(x, y, width, height, color, thickness)
    }
    public fun raw(fragment: String) { steps += Raw(fragment) }

    internal fun build(): FilterChain = FilterChain(steps.toList())
}

public class AudioFilterBuilder internal constructor() {
    private val steps = mutableListOf<FilterStep>()

    public fun volume(gain: Double) { steps += Volume(gain) }
    public fun atempo(tempo: Double) { steps += Atempo(tempo) }
    public fun aresample(sampleRate: Int) { steps += Aresample(sampleRate) }
    public fun pan(layout: String, vararg outputs: String) { steps += Pan(layout, *outputs) }
    public fun aformat(
        sampleFormat: SampleFormat? = null,
        sampleRate: Int? = null,
        channelLayout: String? = null,
    ) { steps += AudioFormat(sampleFormat, sampleRate, channelLayout) }
    public fun loudnorm(integrated: Double = -24.0, truePeak: Double = -2.0, range: Double = 7.0) {
        steps += Loudnorm(integrated, truePeak, range)
    }
    public fun raw(fragment: String) { steps += Raw(fragment) }

    internal fun build(): FilterChain = FilterChain(steps.toList())
}

public fun videoFilters(block: VideoFilterBuilder.() -> Unit): FilterChain =
    VideoFilterBuilder().apply(block).build()

public fun audioFilters(block: AudioFilterBuilder.() -> Unit): FilterChain =
    AudioFilterBuilder().apply(block).build()
