package io.github.yuroyami.kitecodec

/**
 * Every KiteCodec failure surfaces as [FFmpegException] wrapping a typed [FFmpegError].
 *
 * The hierarchy maps the raw `AVERROR_*` codes onto semantic categories so callers can react
 * without decoding negative errno math:
 *
 * ```kotlin
 * try { Transcoder.transcode(...) } catch (e: FFmpegException) {
 *     when (e.error) {
 *         is FFmpegError.FileNotFound     -> promptForFile()
 *         is FFmpegError.EncoderNotFound  -> fallBackToSoftwareEncoder()
 *         is FFmpegError.InvalidData      -> reportCorruptInput()
 *         else                            -> log(e.error.code, e.message)
 *     }
 * }
 * ```
 *
 * Every subclass keeps the raw code in [code] (0 for [Internal]), so nothing is lost by the
 * classification; unmapped codes surface as [AvError].
 */
public sealed class FFmpegError(public val code: Int, public val message: String) {

    /** Input path does not exist (`AVERROR(ENOENT)`). */
    public class FileNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Filesystem permission denied (`AVERROR(EACCES)` / `AVERROR(EPERM)`). */
    public class PermissionDenied(code: Int, message: String) : FFmpegError(code, message)

    /** Corrupt or unrecognized bitstream/container data (`AVERROR_INVALIDDATA`). */
    public class InvalidData(code: Int, message: String) : FFmpegError(code, message)

    /** Invalid parameter combination rejected by FFmpeg (`AVERROR(EINVAL)`). */
    public class InvalidArgument(code: Int, message: String) : FFmpegError(code, message)

    /** The bound FFmpeg build has no such encoder (`AVERROR_ENCODER_NOT_FOUND`). */
    public class EncoderNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** The bound FFmpeg build has no such decoder (`AVERROR_DECODER_NOT_FOUND`). */
    public class DecoderNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** No demuxer recognizes the input (`AVERROR_DEMUXER_NOT_FOUND`). */
    public class DemuxerNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** No muxer for the requested output format (`AVERROR_MUXER_NOT_FOUND`). */
    public class MuxerNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** The bound FFmpeg build has no such filter (`AVERROR_FILTER_NOT_FOUND`). */
    public class FilterNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** No protocol handler for the URL scheme (`AVERROR_PROTOCOL_NOT_FOUND`). */
    public class ProtocolNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Requested stream does not exist (`AVERROR_STREAM_NOT_FOUND`). */
    public class StreamNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Unknown codec/format private option (`AVERROR_OPTION_NOT_FOUND`). */
    public class OptionNotFound(code: Int, message: String) : FFmpegError(code, message)

    /** Feature not implemented in FFmpeg (`AVERROR_PATCHWELCOME`). */
    public class Unsupported(code: Int, message: String) : FFmpegError(code, message)

    /** Native allocation failure (`AVERROR(ENOMEM)`). */
    public class OutOfMemory(code: Int, message: String) : FFmpegError(code, message)

    /** End of file/stream surfaced as an error (`AVERROR_EOF`). Rare, loops consume it. */
    public class EndOfFile(code: Int, message: String) : FFmpegError(code, message)

    /** Generic I/O failure (`AVERROR(EIO)`). */
    public class Io(code: Int, message: String) : FFmpegError(code, message)

    /** Any `AVERROR_*` code without a dedicated category above. */
    public class AvError(code: Int, message: String) : FFmpegError(code, message)

    /** Library-internal invariant failure, not an FFmpeg return code. */
    public class Internal(message: String) : FFmpegError(0, message)

    /**
     * The linked FFmpeg runtime does not match the headers KiteCodec's C layer was compiled against,
     * so KiteCodec refused to use it.
     *
     * Deliberately its own class and not an [Internal]: nothing about it is a bug in KiteCodec, it is
     * never a property of the media, and a caller that wants to tell "your FFmpeg is wrong" apart from
     * "this file is broken" must be able to catch exactly this. [identity] carries both version
     * columns for all six libraries, both licence strings, and one actionable sentence.
     *
     * [code] is 0, like [Internal]'s, on purpose. The verdict is a KiteCodec status and not an
     * `AVERROR_*` value, and the two number spaces overlap: `AVERROR(EPERM)` is also -1, so putting a
     * verdict in [code] would make it indistinguishable from a permission error. Read
     * [FFmpegIdentity.status] instead.
     */
    public class IncompatibleFFmpegRuntime(
        public val identity: FFmpegIdentity,
    ) : FFmpegError(0, identity.describe())

    override fun toString(): String = "${this::class.simpleName}(code=$code, message=$message)"

    public companion object {
        // AVERROR_* tags are FFERRTAG('a','b','c','d') = -MKTAG(a,b,c,d); errno-style codes
        // are -errno. All values below are identical across the supported platforms except
        // ENOSYS, which gets each platform's known value listed explicitly.
        private fun tag(a: Int, b: Char, c: Char, d: Char): Int =
            -(a or (b.code shl 8) or (c.code shl 16) or (d.code shl 24))
        private fun tag(a: Char, b: Char, c: Char, d: Char): Int = tag(a.code, b, c, d)

        internal val AVERROR_EOF                = tag('E', 'O', 'F', ' ')
        internal val AVERROR_INVALIDDATA        = tag('I', 'N', 'D', 'A')
        internal val AVERROR_PATCHWELCOME       = tag('P', 'A', 'W', 'E')
        internal val AVERROR_DECODER_NOT_FOUND  = tag(0xF8, 'D', 'E', 'C')
        internal val AVERROR_ENCODER_NOT_FOUND  = tag(0xF8, 'E', 'N', 'C')
        internal val AVERROR_DEMUXER_NOT_FOUND  = tag(0xF8, 'D', 'E', 'M')
        internal val AVERROR_MUXER_NOT_FOUND    = tag(0xF8, 'M', 'U', 'X')
        internal val AVERROR_FILTER_NOT_FOUND   = tag(0xF8, 'F', 'I', 'L')
        internal val AVERROR_PROTOCOL_NOT_FOUND = tag(0xF8, 'P', 'R', 'O')
        internal val AVERROR_STREAM_NOT_FOUND   = tag(0xF8, 'S', 'T', 'R')
        internal val AVERROR_OPTION_NOT_FOUND   = tag(0xF8, 'O', 'P', 'T')

        private const val ENOENT = -2
        private const val EPERM = -1
        private const val EIO = -5
        private const val ENOMEM = -12
        private const val EACCES = -13
        private const val EINVAL = -22

        /** Classify a raw `AVERROR_*` [code] into the semantic hierarchy. */
        internal fun fromCode(code: Int, message: String): FFmpegError = when (code) {
            AVERROR_EOF                 -> EndOfFile(code, message)
            AVERROR_INVALIDDATA         -> InvalidData(code, message)
            AVERROR_PATCHWELCOME        -> Unsupported(code, message)
            AVERROR_DECODER_NOT_FOUND   -> DecoderNotFound(code, message)
            AVERROR_ENCODER_NOT_FOUND   -> EncoderNotFound(code, message)
            AVERROR_DEMUXER_NOT_FOUND   -> DemuxerNotFound(code, message)
            AVERROR_MUXER_NOT_FOUND     -> MuxerNotFound(code, message)
            AVERROR_FILTER_NOT_FOUND    -> FilterNotFound(code, message)
            AVERROR_PROTOCOL_NOT_FOUND  -> ProtocolNotFound(code, message)
            AVERROR_STREAM_NOT_FOUND    -> StreamNotFound(code, message)
            AVERROR_OPTION_NOT_FOUND    -> OptionNotFound(code, message)
            ENOENT                      -> FileNotFound(code, message)
            EACCES, EPERM               -> PermissionDenied(code, message)
            ENOMEM                      -> OutOfMemory(code, message)
            EINVAL                      -> InvalidArgument(code, message)
            EIO                         -> Io(code, message)
            else                        -> AvError(code, message)
        }
    }
}

/**
 * The single exception type KiteCodec throws for FFmpeg-related failures. Inspect [error]
 * for the semantic category and [code] for the raw `AVERROR_*` value.
 */
public class FFmpegException(public val error: FFmpegError) : RuntimeException(error.message) {
    /** The raw `AVERROR_*` code, or 0 for internal errors. */
    public val code: Int get() = error.code
}

/**
 * Why a decoder could not be opened, in words the caller can act on (KC-CAPS).
 *
 * Opened by the owner from a real incident: a device threw FFmpeg's bare `-78` on an AV1 file and
 * nothing on hand could say whether that build carried dav1d. An hour of binary archaeology later
 * the answer was "the installed app was stale". Three of the refusal sites printed `codec id 226`,
 * a number nobody can act on, while the stream's own codec name sat in scope at every one of them.
 *
 * So the message names the codec, and then names the two calls that answer the two questions the
 * incident actually raised: does this build carry it, and WHICH build am I running.
 *
 * [requested] separates two failures that read alike and mean opposite things. A null means this
 * build has no decoder for the stream's codec at all. A value means the specific implementation
 * asked for is absent, and the default decoder may still play the stream perfectly well.
 */
internal fun decoderNotFoundMessage(streamCodec: CodecId, requested: CodecId?): String =
    if (requested == null) {
        "no decoder for codec '${streamCodec.name}' in this build. " +
            "FFmpeg.hasDecoder(\"${streamCodec.name}\") answers that without opening a file, and " +
            "FFmpeg.identity says which build is actually loaded."
    } else {
        "no decoder named '${requested.name}' in this build, requested for a '${streamCodec.name}' " +
            "stream. FFmpeg.hasDecoder(\"${requested.name}\") answers that without opening a file, " +
            "and FFmpeg.identity says which build is actually loaded. Omit the decoder to let " +
            "FFmpeg choose its default for '${streamCodec.name}'."
    }
