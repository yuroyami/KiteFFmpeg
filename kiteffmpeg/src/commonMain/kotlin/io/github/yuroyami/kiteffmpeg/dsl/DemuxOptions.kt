package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException

/**
 * Typed demuxer options for `MediaSource.open`, applied between allocation and open, the only
 * moment probe sizes, flags and whitelists can act. [compile] is the pure mapping to FFmpeg's own
 * option pairs. A key FFmpeg does not consume comes back through `MediaSource.unusedOpenOptions`
 * instead of being dropped.
 *
 * ```kotlin
 * val source = MediaSource.open(url, DemuxOptions(probeSizeBytes = 1_000_000, ioTimeoutMicros = 10_000_000))
 * ```
 *
 * Two keys are refused on every route, typed or raw, with [FFmpegError.InvalidArgument]: `usetoc`,
 * and an `fflags` value that names `fastseek`. MP3 seeking is correct only when both are left
 * alone, and either one breaks seeking in a way that looks like a player bug.
 */
public data class DemuxOptions(
    /** Bytes the demuxer may read to find the streams (`probesize`; FFmpeg reads 5 000 000). */
    val probeSizeBytes: Long? = null,
    /** How much media the stream analysis may read, in microseconds (`analyzeduration`). */
    val analyzeDurationMicros: Long? = null,
    /** Frames read to measure a stream's frame rate (`fpsprobesize`). */
    val fpsProbeFrames: Int? = null,
    /** Demuxer behaviour flags, added to FFmpeg's defaults rather than replacing them (`fflags`). */
    val flags: Set<DemuxFlag> = emptySet(),
    /** The only demuxers the open may use, by FFmpeg name (`format_whitelist`); empty allows all. */
    val formatWhitelist: Set<String> = emptySet(),
    /**
     * The only protocols the open and every nested open may use (`protocol_whitelist`), such as
     * `file` or `http,tcp`; empty allows all. A playlist that points at another protocol is refused.
     */
    val protocolWhitelist: Set<String> = emptySet(),
    /** How long one network read or write may wait, in microseconds (`rw_timeout`); null waits for ever. */
    val ioTimeoutMicros: Long? = null,
    /** Raw option pairs for anything the typed set lacks. They come after the typed ones and win a tie. */
    val options: Map<String, String> = emptyMap(),
) {
    /** The exact option pairs, in a stable order: typed knobs first, the raw pairs after. */
    public fun compile(): List<Pair<String, String>> = buildList {
        probeSizeBytes?.let { add("probesize" to it.toString()) }
        analyzeDurationMicros?.let { add("analyzeduration" to it.toString()) }
        fpsProbeFrames?.let { add("fpsprobesize" to it.toString()) }
        if (flags.isNotEmpty()) add("fflags" to flags.sortedBy { it.ordinal }.joinToString("") { "+" + it.ff })
        if (formatWhitelist.isNotEmpty()) add("format_whitelist" to formatWhitelist.joinToString(","))
        if (protocolWhitelist.isNotEmpty()) add("protocol_whitelist" to protocolWhitelist.joinToString(","))
        ioTimeoutMicros?.let { add("rw_timeout" to it.toString()) }
        options.forEach { (key, value) -> add(key to value) }
    }

    public companion object {
        /** Starts a live stream sooner: no optional buffering and a short probe. */
        public val LowLatency: DemuxOptions = DemuxOptions(
            probeSizeBytes = 32_768,
            analyzeDurationMicros = 500_000,
            flags = setOf(DemuxFlag.NoBuffer),
        )
    }
}

/** A demuxer behaviour flag, by FFmpeg's own `fflags` value name. */
public enum class DemuxFlag(internal val ff: String) {
    /** Drop the packets the demuxer marks as corrupt (`discardcorrupt`). */
    DiscardCorrupt("discardcorrupt"),

    /** Make up the presentation timestamps a stream lacks (`genpts`). */
    GeneratePts("genpts"),

    /** Ignore the container's index and read linearly (`ignidx`). */
    IgnoreIndex("ignidx"),

    /** Skip optional buffering, for low latency (`nobuffer`). */
    NoBuffer("nobuffer"),
}

/**
 * Refuses the two option keys that break MP3 seeking, whichever route they came by: `usetoc`, and
 * an `fflags` value naming `fastseek`.
 */
internal fun refuseSeekBreakingOptions(options: Map<String, String>) {
    val refused = buildList {
        if ("usetoc" in options) add("usetoc")
        val flags = options["fflags"]
        if (flags != null && "fastseek" in flags) add("fflags=$flags")
    }
    if (refused.isEmpty()) return
    throw FFmpegException(
        FFmpegError.InvalidArgument(
            0,
            "Refused open option ${refused.joinToString(" and ")}: MP3 seeking is only correct with " +
                "the table of contents unused and fast seek unset, so these options would break " +
                "seeking in a way that looks like a player bug.",
        ),
    )
}
