package io.github.yuroyami.kiteffmpeg

/**
 * The JNI face of one [MediaByteSource], through the custom AVIO bridge. The C side holds a
 * global ref to this object and calls [read], [seek] and [tags] BY NAME through cached jmethodIDs,
 * from whatever thread drives the demuxer. The names and signatures are pinned in
 * native/kitecodec-jni/kj_format.c and the consumer keep rules; renaming either side alone
 * breaks the bridge at open time, loudly.
 *
 * Error contract, mirroring kitecodec_helpers.h: [read] returns bytes read > 0, -1 at end of
 * stream, -2 on failure; [seek] returns the new absolute position or -2. Exceptions never
 * cross into C: they are caught here, parked, and reported as -2. [explain] then attaches the
 * parked exception to the error FFmpeg reports, so the caller sees what the source threw.
 */
internal class JniByteIo(
    private val io: MediaByteSource,
    /** The nested opener of a top-level open, whose failures this reports too. Null for a nested source. */
    private val nested: JniByteOpener? = null,
) {

    private var position = 0L

    /**
     * The exception the last [read] or [seek] swallowed, kept for the error it causes. FFmpeg can
     * report that error one call later, so it waits here until [explain] takes it. A read that
     * delivers bytes clears it: FFmpeg recovered, so it no longer explains a later error.
     */
    @Volatile
    private var failure: Throwable? = null

    /** Called from C. Fills [into] from the source, returns count, -1 EOF, -2 error. */
    @Suppress("unused")
    fun read(into: ByteArray, length: Int): Int = try {
        val want = minOf(length, into.size)
        val r = io.read(into, 0, want)
        when {
            // The C side refuses this count too; checking here keeps the position true and names
            // the cause.
            r > want -> {
                failure = byteSourceOverCount(r, want)
                -2
            }
            r > 0 -> {
                position += r
                failure = null
                r
            }
            r < 0 -> -1
            else -> -2 // 0 breaks the documented block-or-end contract
        }
    } catch (t: Throwable) {
        failure = t
        -2
    }

    /** Called from C. whence is SEEK_SET(0)/SEEK_CUR(1)/SEEK_END(2). */
    @Suppress("unused")
    fun seek(offset: Long, whence: Int): Long = try {
        val target = when (whence) {
            0 -> offset
            1 -> position + offset
            2 -> (io.size ?: -1L).let { if (it < 0) return -2L else it + offset }
            else -> return -2L
        }
        if (target < 0) {
            -2L
        } else {
            io.seek(target)
            position = target
            target
        }
    } catch (t: Throwable) {
        failure = t
        -2L
    }

    /** Called from C for a nested source: the total size, or -1 when it is unknown or the getter threw. */
    @Suppress("unused")
    fun size(): Long = try {
        io.size ?: -1L
    } catch (t: Throwable) {
        failure = t
        -1L
    }

    /** Called from C for a nested source. False when the getter threw. */
    @Suppress("unused")
    fun seekable(): Boolean = try {
        io.seekable
    } catch (t: Throwable) {
        failure = t
        false
    }

    /**
     * Called from C for a nested source, once, as it opens: its [MediaByteSource.location], null
     * when it has none or names an empty one. Unlike the getters above, this one lets the exception
     * through, parked first, because C then fails the address rather than resolving its playlist
     * against the wrong place.
     */
    @Suppress("unused")
    fun location(): String? = try {
        io.openedLocation()
    } catch (t: Throwable) {
        failure = t
        throw t
    }

    /**
     * Called from C after every read of the input that returned bytes: the tags those bytes brought,
     * as keys and values in turn, or null (#168). The exception from [MediaByteSource.takeTags] goes
     * through, parked first, because C then fails the read it followed, as one from [read] does.
     * Never called for a nested source, because FFmpeg reads no tags from one.
     */
    @Suppress("unused")
    fun tags(): Array<String>? = try {
        io.takeTags()?.ffmpegTagPairs()
    } catch (t: Throwable) {
        failure = t
        throw t
    }

    /**
     * Attaches the swallowed exception to [error] as its cause, once. [error] is what FFmpeg's error
     * code became, and without the cause it only says that an I/O operation failed. When this source
     * swallowed nothing, a failure of the nested opener explains the error instead.
     */
    fun explain(error: FFmpegException) {
        val swallowed = takeFailure() ?: nested?.takeFailure() ?: return
        if (error.cause == null) error.initCause(swallowed)
    }

    /** The swallowed exception, once. */
    fun takeFailure(): Throwable? = failure.also { failure = null }

    /**
     * Runs on MediaSource.close, after the C side dropped its refs. A nested source that failed to
     * close is reported here too, because FFmpeg closed it where no caller could hear it.
     */
    fun closeSource() {
        var primary: Throwable? = null
        try {
            io.close()
        } catch (t: Throwable) {
            primary = t
        }
        nested?.takeCloseFailures()?.forEach { failure ->
            primary?.addSuppressed(failure) ?: run { primary = failure }
        }
        primary?.let { throw it }
    }
}
