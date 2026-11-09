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
 * and an `fflags` value that sets `fastseek`. MP3 seeking is correct only when both are left
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
    /**
     * How long one network read or write may wait, in microseconds; null waits for ever. Each
     * protocol in FFmpeg takes it under its own name, so an open by address routes it by the
     * address's scheme (#241): `rw_timeout` for http, https, tcp and rtmp, the demuxer's `timeout`
     * for rtsp, udp's own `timeout`, and for rtp a `timeout` in the address's query, because the
     * rtp reader waits for its first packet through a protocol no option reaches. Any other
     * address, and [compile] itself, use `rw_timeout`. An open over a
     * [io.github.yuroyami.kiteffmpeg.MediaByteSource] reads no
     * network, so this bounds only the network reads of its nested opens; to stop a read of the
     * caller's own bytes, use [io.github.yuroyami.kiteffmpeg.MediaSource.interrupt].
     */
    val ioTimeoutMicros: Long? = null,
    /** Raw option pairs for anything the typed set lacks. They come after the typed ones and win a tie. */
    val options: Map<String, String> = emptyMap(),
    /**
     * The demuxer to use, by FFmpeg name, as the command line's `-f`: the open does not probe. This
     * is what opens headerless input, such as `s16le` or `rawvideo`, whose parameters then go in
     * [options] (`sample_rate`, `video_size` and the like). A name this build does not carry fails
     * with [FFmpegError.DemuxerNotFound]. Unlike [formatWhitelist], which only refuses a probed
     * format, this chooses one.
     */
    val format: String? = null,
) {
    /**
     * The exact option pairs, in a stable order: typed knobs first, the raw pairs after. Here
     * [ioTimeoutMicros] is always `rw_timeout`; an open by address routes it by scheme instead.
     */
    public fun compile(): List<Pair<String, String>> = compileWithTimeout { timeout -> add("rw_timeout" to timeout) }

    /**
     * The address and option pairs an open of [address] uses, with [ioTimeoutMicros] under the
     * name the address's protocol reads. A raw option or a `timeout` already in an rtp address
     * keeps its own value.
     */
    internal fun compileFor(address: String): Pair<String, List<Pair<String, String>>> {
        val timeout = ioTimeoutMicros?.toString() ?: return address to compile()
        val scheme = schemeOf(address)
        if (scheme == "rtp") {
            val query = address.substringAfter('?', "")
            val named = query.split('&').any { it.startsWith("timeout=") }
            val routed = if (named) address else address + (if ('?' in address) "&" else "?") + "timeout=$timeout"
            return routed to compileWithTimeout { }
        }
        val key = if (scheme == "rtsp" || scheme == "udp") "timeout" else "rw_timeout"
        return address to compileWithTimeout { add(key to timeout) }
    }

    private fun compileWithTimeout(
        timeout: MutableList<Pair<String, String>>.(String) -> Unit,
    ): List<Pair<String, String>> = buildList {
        probeSizeBytes?.let { add("probesize" to it.toString()) }
        analyzeDurationMicros?.let { add("analyzeduration" to it.toString()) }
        fpsProbeFrames?.let { add("fpsprobesize" to it.toString()) }
        if (flags.isNotEmpty()) add("fflags" to flags.sortedBy { it.ordinal }.joinToString("") { "+" + it.ff })
        if (formatWhitelist.isNotEmpty()) add("format_whitelist" to formatWhitelist.joinToString(","))
        if (protocolWhitelist.isNotEmpty()) add("protocol_whitelist" to protocolWhitelist.joinToString(","))
        ioTimeoutMicros?.let { timeout(it.toString()) }
        options.forEach { (key, value) -> add(key to value) }
        // Not an FFmpeg option: the C open takes this key out and passes the demuxer it names.
        format?.let { add(FORCED_FORMAT_KEY to it) }
    }

    /** Ready-made option sets. */
    public companion object {
        /** Starts a live stream sooner: no optional buffering and a short probe. */
        public val LowLatency: DemuxOptions = DemuxOptions(
            probeSizeBytes = 32_768,
            analyzeDurationMicros = 500_000,
            flags = setOf(DemuxFlag.NoBuffer),
        )
    }
}

/** The lower-case scheme of [address], or `file` when it has none, as FFmpeg reads a protocol name. */
internal fun schemeOf(address: String): String {
    val end = address.indexOfFirst { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }
    if (end < 2 || address[end] != ':') return "file"
    return address.substring(0, end).lowercase()
}

/** The key the C open takes the forced demuxer from. It must match `KC_FORCED_FORMAT_KEY`. */
internal const val FORCED_FORMAT_KEY: String = "kiteffmpeg_input_format"

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
 * an `fflags` value that sets `fastseek`. A value that clears it, such as `+genpts-fastseek`,
 * asks for the state this guard wants and passes.
 */
internal fun refuseSeekBreakingOptions(options: Map<String, String>) {
    val refused = buildList {
        if ("usetoc" in options) add("usetoc")
        val flags = options["fflags"]
        if (flags != null && setsFastSeek(flags)) add("fflags=$flags")
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

/**
 * Whether an FFmpeg flags value leaves `fastseek` set. FFmpeg reads `+name` as set, `-name` as
 * clear and a bare name as set, in order, so the last mention of `fastseek` decides.
 */
internal fun setsFastSeek(flags: String): Boolean {
    var set = false
    for (match in FLAG_TOKEN.findAll(flags)) {
        if (match.groupValues[2].trim() == "fastseek") set = match.groupValues[1] != "-"
    }
    return set
}

private val FLAG_TOKEN = Regex("([+-]?)([^+-]+)")
