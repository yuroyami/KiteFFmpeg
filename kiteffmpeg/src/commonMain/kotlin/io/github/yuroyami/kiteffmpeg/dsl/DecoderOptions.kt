package io.github.yuroyami.kiteffmpeg.dsl

/**
 * Typed decoder options (KD-2), applied through the EXISTING `av_opt_set` funnel
 * between codec-context creation and open. Control plane only (law 1): these configure an open,
 * never a per-frame call. [compile] is the pure, golden-tested mapping to option pairs; a wrong
 * key in [options] reproduces the funnel's measured EINVAL path rather than being filtered here.
 */
public data class DecoderOptions(
    /** Skip the loop filter for frames below this importance. The scrubbing half-pair. */
    val skipLoopFilter: DecoderSkip? = null,
    /** Skip DECODING frames below this importance. The other half of scrubbing. */
    val skipFrame: DecoderSkip? = null,
    /** Error-detection strictness flags, joined the way `err_detect` wants them. */
    val errorDetection: Set<ErrorDetection> = emptySet(),
    /** Frame versus slice threading, beyond the existing thread COUNT parameter. */
    val threadType: DecoderThreadType? = null,
    /** The escape hatch (law 2): raw `av_opt_set` pairs for anything the typed set lacks. */
    val options: Map<String, String> = emptyMap(),
) {
    /** The exact option pairs, in a stable order: typed knobs first, escape hatch after. */
    public fun compile(): List<Pair<String, String>> = buildList {
        skipLoopFilter?.let { add("skip_loop_filter" to it.ff) }
        skipFrame?.let { add("skip_frame" to it.ff) }
        if (errorDetection.isNotEmpty()) {
            add("err_detect" to errorDetection.sortedBy { it.ordinal }.joinToString("+") { it.ff })
        }
        threadType?.let { add("thread_type" to it.ff) }
        options.forEach { (key, value) -> add(key to value) }
    }

    public companion object {
        /** The scrubbing pair: cheapest legal decode of only what a scrubbing thumb needs. */
        public val Scrubbing: DecoderOptions = DecoderOptions(
            skipLoopFilter = DecoderSkip.All,
            skipFrame = DecoderSkip.NonKey,
        )
    }
}

/** avcodec's skip ladder, by its own option value names. */
public enum class DecoderSkip(internal val ff: String) {
    None("none"),
    NonReference("noref"),
    Bidirectional("bidir"),
    NonIntra("nointra"),
    NonKey("nokey"),
    All("all"),
}

public enum class DecoderThreadType(internal val ff: String) {
    Frame("frame"),
    Slice("slice"),
    Both("frame+slice"),
}

public enum class ErrorDetection(internal val ff: String) {
    CrcCheck("crccheck"),
    Bitstream("bitstream"),
    Buffer("buffer"),
    Explode("explode"),
    IgnoreErrors("ignore_err"),
    Careful("careful"),
    Compliant("compliant"),
    Aggressive("aggressive"),
}
