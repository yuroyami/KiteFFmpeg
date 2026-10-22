package io.github.yuroyami.kiteffmpeg

/**
 * Turns the closed captions that video frames carry into timed text (#179): the bytes
 * [Frame.closedCaptions] gives, three per caption pair, as broadcast H.264, HEVC and MPEG-2 carry
 * them in the video stream itself. A caption track stored on its own, such as a MOV `c608` track,
 * is a subtitle stream instead and decodes through [MediaSource.openSubtitleDecoder].
 *
 * This is FFmpeg's own caption decoder, `eia_608`, the one mpv feeds each frame's captions to. It
 * reads the CEA-608 captions of field 1, CC1 and CC2, which is where broadcast puts its captions,
 * those inside CEA-708 data included, and skips the CEA-708 service blocks, which FFmpeg does not
 * decode. The field is fixed rather than guessed: left to guess, FFmpeg's decoder takes the field
 * of the first byte it is given, before it checks that byte, so one damaged or CEA-708 byte at the
 * start leaves it reading a field nothing is captioned in.
 *
 * Hand over each frame's bytes in the order the frames are shown, which is the order the captions
 * were authored in and not the order a decoder with reordered pictures receives them. Each answer
 * is a [Subtitle] in the text form [SubtitleDecoder] gives, one ASS event per rectangle, timed on the
 * caller's timeline: the times given to [decode].
 *
 * The decoder answers in one of two ways, chosen at [open]. Buffered, the default, gives each
 * caption once it leaves the screen, with its start and its end, because only then is its end
 * known; the one on screen when the bytes run out comes from [drain]. That suits a converter
 * writing a subtitle file. Real time gives the screen as it now stands each time it changes, from
 * the time of the frame that changed it, with no end: each answer holds until the next one, and an
 * answer whose text is empty clears the screen. Roll-up and paint-on captions answer as they are
 * painted, at most once every 200 ms. That suits a player, which decodes pictures only a little
 * ahead of the one on screen and would otherwise get each caption as it disappears; mpv opens
 * FFmpeg's decoder this way for every caption stream it plays (#180).
 *
 * Not thread safe: use one decoder from one thread at a time.
 */
@KiteFFmpegLowLevelApi
public expect class ClosedCaptionDecoder : AutoCloseable {
    /**
     * Decodes [captions], the bytes [Frame.closedCaptions] gave for the frame shown at
     * [ptsMicros]. Null when they complete no caption, which is the usual answer: a caption is
     * built over many frames. Damaged bytes cost their own caption and nothing more.
     */
    @Throws(FFmpegException::class)
    public fun decode(captions: ByteArray, ptsMicros: Long): Subtitle?

    /**
     * The caption still on screen once the frames have run out, or null when there is none. Always
     * null in real time, where the last answer already holds.
     */
    @Throws(FFmpegException::class)
    public fun drain(): Subtitle?

    /** Forgets the caption being built, after a seek. */
    public fun flush()

    override fun close()

    public companion object {
        /**
         * A new decoder, which the caller closes, answering in real time when [realTime] is set and
         * buffered otherwise.
         *
         * @throws FFmpegException with [FFmpegError.DecoderNotFound] for a build without FFmpeg's
         *         caption decoder
         */
        @Throws(FFmpegException::class)
        public fun open(realTime: Boolean = false): ClosedCaptionDecoder
    }
}
