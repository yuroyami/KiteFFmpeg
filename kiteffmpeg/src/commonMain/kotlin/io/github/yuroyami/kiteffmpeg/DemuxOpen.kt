package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DemuxOptions

/**
 * [MediaSource.open] with typed [DemuxOptions] instead of raw option pairs. [interrupt] behaves as
 * it does on the raw overload; see [OpenInterrupt].
 */
@Throws(FFmpegException::class)
public fun MediaSource.Companion.open(
    path: String,
    options: DemuxOptions,
    interrupt: OpenInterrupt? = null,
): MediaSource = open(path, options.compile().toMap(), interrupt)

/** [MediaSource.open] over caller-supplied bytes, with typed [DemuxOptions]. */
@Throws(FFmpegException::class)
public fun MediaSource.Companion.open(
    io: MediaByteSource,
    options: DemuxOptions,
    interrupt: OpenInterrupt? = null,
): MediaSource = open(io, options.compile().toMap(), interrupt)
