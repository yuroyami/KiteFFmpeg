package io.github.yuroyami.kiteffmpeg

/**
 * The pictures a copy that starts at a cut cannot show (#153).
 *
 * A cut starts on the keyframe at or before it. In video with an open group of pictures, the
 * pictures that follow that keyframe in decode order but show before it are predicted from the
 * picture before the keyframe, which the cut does not copy, so no decoder can show them. Copied,
 * they would only move the output's start ahead of its first picture: the output's zero would sit
 * on a picture nobody sees, and a Matroska output, which takes no time below zero, would move
 * every stream and leave the chapters behind. A player that starts on that keyframe drops them
 * too, so a cut leaves them out.
 */
internal class LeadingPictures {
    /** The presentation time of the first picture copied of each video stream, by its index. */
    private val first = HashMap<Int, Long>()

    /**
     * True when [stream] is video and its packet with presentation time [pts], in the stream's own
     * time base, shows before the first picture copied of it. Asked only of packets that are about
     * to be copied, in the order they were read.
     */
    fun isLeading(stream: StreamInfo, pts: Long): Boolean {
        if (stream.type != MediaType.Video || pts == FrameInfo.NOPTS) return false
        val keyframe = first.getOrPut(stream.index) { pts }
        return pts < keyframe
    }
}
