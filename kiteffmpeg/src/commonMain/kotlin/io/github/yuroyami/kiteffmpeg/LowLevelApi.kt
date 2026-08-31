package io.github.yuroyami.kiteffmpeg

/**
 * Marks API that hands out opaque native-backed owners, manual lifetimes, or a stage of the
 * pipeline that the caller must drive itself.
 *
 * The rest of KiteFFmpeg is safe by construction: one call transcodes a file, and a `Frame` from a
 * `Flow` is owned by whoever collected it. The declarations behind this annotation are not like
 * that. They exist because a real-time player cannot use the batch API: it has to read packets and
 * decode them as separate stages so that audio and video decode independently, it has to seek while
 * decoding, it has to flush a decoder afterwards, and it has to reach a frame's pixels without
 * copying 3 MB per frame.
 *
 * What opting in means:
 *
 * - You drive the stages. Nothing here pumps itself.
 * - You own what you are given, and you close it exactly once.
 * - An object handed to you inside a scoped block is invalid the moment that block returns.
 * - There is no stability promise on these signatures before 1.0.
 *
 * If a plain transcode, remux, thumbnail or decode-to-frames does what you need, use those instead.
 * They are the same libraries with the sharp edges already handled.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Low-level KiteFFmpeg API: native-backed handles, manual lifetimes, and no stability promise. " +
        "Opt in with @OptIn(KiteFFmpegLowLevelApi::class) if you are building a player or another " +
        "pipeline that must drive demuxing and decoding as separate stages.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class KiteFFmpegLowLevelApi
