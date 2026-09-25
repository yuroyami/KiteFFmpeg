package io.github.yuroyami.kiteffmpeg

/**
 * One decoded subtitle: what to show, where, and when.
 *
 * Image formats, such as Blu-ray (PGS), DVB and DVD subtitles, decode to [images]. Text formats
 * decode to [texts]. A subtitle with neither clears what the one before it showed, which is how a
 * Blu-ray stream takes a line off the screen.
 */
public data class Subtitle(
    /** When it starts, in microseconds on the stream's own timeline; null when the packet had no timestamp. */
    val startMicros: Long?,
    /**
     * When it ends, on the same timeline, or null when the stream does not say. A Blu-ray subtitle
     * stays on screen until the next one replaces or clears it.
     */
    val endMicros: Long?,
    /**
     * The size of the canvas [images] are placed on, which is the picture the subtitle was
     * authored for. 0 when the stream does not say, and then the video's own size is meant.
     */
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** The images, in canvas pixels. */
    val images: List<SubtitleImage>,
    /**
     * The text of a text format, one entry per rectangle, as FFmpeg's text decoders write it: an ASS
     * event whose fields are ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect and
     * Text.
     */
    val texts: List<String>,
)

/** One positioned image of a [Subtitle], in canvas pixels. */
public class SubtitleImage(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    /** Premultiplied RGBA, 4 bytes per pixel, rows top to bottom with no padding between them. */
    public val rgba: ByteArray,
    /**
     * True when the stream marks this image as forced: shown even when subtitles are off, such as
     * the translation of a sign.
     */
    public val forced: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SubtitleImage) return false
        return x == other.x && y == other.y && width == other.width && height == other.height &&
            forced == other.forced && rgba.contentEquals(other.rgba)
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + forced.hashCode()
        result = 31 * result + rgba.contentHashCode()
        return result
    }

    override fun toString(): String = "SubtitleImage(x=$x, y=$y, width=$width, height=$height, forced=$forced)"
}

/**
 * Decodes the packets of one subtitle stream, from [MediaSource.openSubtitleDecoder]. Read the
 * packets with a [PacketReader] and hand each one to [decode].
 *
 * Not thread safe: use one decoder from one thread at a time.
 */
@KiteFFmpegLowLevelApi
public expect class SubtitleDecoder : AutoCloseable {
    public val stream: StreamInfo

    /**
     * Decodes one packet of [stream]. Null when the packet completes no subtitle, which is normal:
     * a Blu-ray stream sends its palette and its image as separate packets before the one that
     * shows them. The caller still owns [packet] and closes it.
     */
    @Throws(FFmpegException::class)
    public fun decode(packet: Packet): Subtitle?

    /** Forgets the partial subtitle the decoder holds, after a seek. */
    public fun flush()

    override fun close()
}
