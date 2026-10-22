package io.github.yuroyami.kiteffmpeg

/**
 * A field where the container's declaration and the decoder's output disagree.
 *
 * The container announces width, height, pixel format, sample rate, channels and sample format
 * before a single packet is read, and every caller that sizes a surface, allocates a buffer or
 * opens an audio device does it from those numbers. The decoder is free to produce something else,
 * and files in the wild do exactly that: a mislabelled resolution in the track header, an audio
 * stream tagged at the wrong rate. The result is a surface of the wrong size or a device opened for
 * a rate nothing will feed it, with nothing anywhere saying why.
 *
 * This library has no logger and no warning callback, on purpose. What it has is values read off
 * the object afterwards, which is how `corruptDataSkipped` and `unusedOpenOptions` already report,
 * and this joins them as [MediaSource.streamDivergences].
 *
 * Not an error. The file plays; the numbers to trust are the decoded ones.
 */
public data class StreamDivergence(
    /** The stream, by its index in the container. */
    val streamIndex: Int,
    val field: DivergentField,
    /** What the container said, formatted for reading. */
    val declared: String,
    /** What the decoder actually produced. */
    val decoded: String,
) {
    /**
     * One line, for a log or an assertion message.
     *
     * `this.field` is spelled out because inside a property accessor the bare name `field` is
     * Kotlin's backing-field keyword, and the compiler reads it as this property having a backing
     * field it never initialises.
     */
    public val message: String
        get() = "stream $streamIndex: the container declared ${this.field} $declared, " +
            "the decoder produced $decoded"
}

/** The fields worth comparing: the ones a caller allocates against before decoding starts. */
public enum class DivergentField {
    Width,
    Height,
    PixelFormat,
    SampleRate,
    Channels,
    SampleFormat,
}

/**
 * What [decoded] contradicts in [declared], or an empty list when they agree.
 *
 * **Only fields the container actually declared are compared.** A container that says nothing is
 * not lying, and most of them say nothing about audio sample format: Matroska has no field for it,
 * so codecpar carries [SampleFormat.None] and the decoder fills in the real one. Treating that as a
 * disagreement would report almost every file and the report would be worthless. Zero width, zero
 * sample rate, zero channels and the `None` formats all mean "not declared" and are skipped, which
 * is the same rule [ColorInfo] follows with its `*Specified` flags.
 *
 * A stream's kind decides which fields apply: nothing compares a sample rate against a video frame.
 */
internal fun divergencesOf(declared: StreamInfo, decoded: FrameInfo): List<StreamDivergence> {
    val found = mutableListOf<StreamDivergence>()
    fun note(field: DivergentField, was: Any, now: Any) {
        found += StreamDivergence(declared.index, field, was.toString(), now.toString())
    }

    declared.video?.let { video ->
        if (video.width > 0 && decoded.width > 0 && video.width != decoded.width) {
            note(DivergentField.Width, video.width, decoded.width)
        }
        if (video.height > 0 && decoded.height > 0 && video.height != decoded.height) {
            note(DivergentField.Height, video.height, decoded.height)
        }
        if (
            video.pixelFormat != PixelFormat.None &&
            decoded.pixelFormat != PixelFormat.None &&
            video.pixelFormat != decoded.pixelFormat
        ) {
            note(DivergentField.PixelFormat, video.pixelFormat, decoded.pixelFormat)
        }
    }

    declared.audio?.let { audio ->
        if (audio.sampleRate > 0 && decoded.sampleRate > 0 && audio.sampleRate != decoded.sampleRate) {
            note(DivergentField.SampleRate, audio.sampleRate, decoded.sampleRate)
        }
        if (audio.channels > 0 && decoded.channelCount > 0 && audio.channels != decoded.channelCount) {
            note(DivergentField.Channels, audio.channels, decoded.channelCount)
        }
        if (
            audio.sampleFormat != SampleFormat.None &&
            decoded.sampleFormat != SampleFormat.None &&
            audio.sampleFormat != decoded.sampleFormat
        ) {
            note(DivergentField.SampleFormat, audio.sampleFormat, decoded.sampleFormat)
        }
    }

    return found
}

/**
 * Remembers the first frame of each stream and what it contradicted.
 *
 * FIRST frame only, and that is the whole cost argument. Reading a frame's [FrameInfo] means a
 * dozen reads back across the binding, so a comparison per frame would put that in the decode loop
 * for a value most callers never ask for. The question being answered is "did the container lie
 * about this stream", which the first frame settles; a decoder that changes format part way through
 * is a different question and not this one.
 *
 * [observe] takes the frame's info as a lambda so a stream already seen costs one set lookup and
 * nothing else.
 *
 * Confined to the demux pass, like the corrupt-data counter beside it: one coroutine drives the
 * loop that calls this, and nothing else writes it.
 */
internal class DivergenceRecorder {

    private val compared = mutableSetOf<Int>()

    var found: List<StreamDivergence> = emptyList()
        private set

    fun observe(declared: StreamInfo, decoded: () -> FrameInfo) {
        // A stream with neither half declared nothing that a frame could contradict. Subtitle and
        // data streams are exactly that, and this is what keeps their frames from being read at
        // all: the whole point of the lambda is that info costs a dozen calls back across the
        // binding, and spending them to compare nothing against nothing is worse than pointless.
        if (declared.video == null && declared.audio == null) return
        if (!compared.add(declared.index)) return
        val disagreements = divergencesOf(declared, decoded())
        if (disagreements.isNotEmpty()) found = found + disagreements
    }
}
