package io.github.yuroyami.kiteffmpeg

/**
 * What decoding does when FFmpeg reports damaged data.
 *
 * Every backend used to do [Skip] and say nothing at all: a packet FFmpeg answered
 * `AVERROR_INVALIDDATA` for was treated exactly like a packet that had been consumed, so a damaged
 * file decoded to fewer frames than it should have with no error, no warning and no count. A
 * caller checking a recording for damage, or transcoding an archive and needing to know whether
 * the result is whole, had nothing to read.
 *
 * The behaviour itself is still the default, because skipping damage IS what a player should do:
 * one broken frame in a film should not end playback. What changed is that it is now a decision
 * with a name, and that skipping is counted rather than invisible.
 */
public enum class CorruptData {
    /**
     * Skip the damaged packet or frame, keep decoding, and count it.
     *
     * The default, and what a player wants. Read the count afterwards to find out whether anything
     * was lost: `MediaSource.corruptDataSkipped` and `StreamDecoder.corruptDataSkipped`.
     */
    Skip,

    /**
     * Refuse at the first damaged packet or frame, as [FFmpegException] carrying
     * [FFmpegError.InvalidData].
     *
     * For a caller that would rather fail than produce a quietly incomplete result: verifying a
     * recording, or a transcode whose output must either be whole or not exist.
     */
    Fail,
}
