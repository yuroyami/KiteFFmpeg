package io.github.yuroyami.kiteffmpeg

/**
 * One programme of a source: a set of its streams that play together (#148).
 *
 * A DVB recording or an IPTV multiplex carries several channels in one MPEG transport stream, each
 * with its own picture, sound and subtitles, and a flat stream list cannot say which sound belongs
 * to which picture. The transport stream says it in its programme tables, and names each channel
 * in its service description table. A transport stream with one channel has one programme.
 *
 * FFmpeg also makes one programme for each variant of an HLS master playlist, with the variant's
 * bit rate under `variant_bitrate` in [metadata], and one programme holding every stream of a DASH
 * presentation. A container with no such tables, such as MP4 or Matroska, has none.
 *
 * A stream can belong to more than one programme, as an audio track two channels share does. It
 * can also belong to none, as a transport stream's stream does when FFmpeg found it by its packets
 * rather than in a programme table.
 */
public data class Program(
    /** FFmpeg's identifier for this programme, which no other programme of its source has. */
    val id: Int,
    /**
     * The programme number the container states, which in a transport stream is the service id
     * that the DVB and ATSC guide tables name a channel by. Null when the container states none,
     * as HLS and DASH do not. A transport stream states it in its programme association table, so
     * a channel that only its service description table names has none, and no streams either.
     */
    val number: Int?,
    /**
     * The [StreamInfo.index] of each stream in this programme, in the order the container lists
     * them. Every one names a stream in [MediaSource.streams].
     */
    val streamIndexes: List<Int>,
    /**
     * The programme's own tags. A transport stream's channel name and provider are here, under
     * `service_name` and `service_provider`.
     */
    val metadata: Map<String, String> = emptyMap(),
) {
    /** The channel's name from the service description table, or null when there is none. */
    public val serviceName: String? get() = metadata["service_name"]

    /** Who provides the channel, from the same table, or null when there is none. */
    public val serviceProvider: String? get() = metadata["service_provider"]
}

/**
 * A programme from what the C layer reads (#148): the number FFmpeg holds is 0 when the container
 * states none, and a stream index is kept only when it names one of [known], the indexes of the
 * source's streams, so a programme never names a stream its source does not list.
 */
internal fun programOf(
    id: Int,
    number: Int,
    streamIndexes: List<Int>,
    metadata: Map<String, String>,
    known: Set<Int>,
): Program = Program(
    id = id,
    number = number.takeIf { it > 0 },
    streamIndexes = streamIndexes.filter { it in known },
    metadata = metadata,
)
