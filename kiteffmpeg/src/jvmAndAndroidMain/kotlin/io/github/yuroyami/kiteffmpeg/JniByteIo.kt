package io.github.yuroyami.kiteffmpeg

/**
 * The JNI face of one [MediaByteSource] (M1, the custom AVIO bridge). The C side holds a
 * global ref to this object and calls [read] and [seek] BY NAME through cached jmethodIDs,
 * from whatever thread drives the demuxer. The names and signatures are pinned in
 * native/kitecodec-jni/kj_format.c and the consumer keep rules; renaming either side alone
 * breaks the bridge at open time, loudly.
 *
 * Error contract, mirroring kitecodec_helpers.h: [read] returns bytes read > 0, -1 at end of
 * stream, -2 on failure; [seek] returns the new absolute position or -2. Exceptions never
 * cross into C: they are caught here, parked, and reported as -2. [explain] then attaches the
 * parked exception to the error FFmpeg reports, so the caller sees what the source threw.
 */
internal class JniByteIo(private val io: MediaByteSource) {

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
        val r = io.read(into, 0, minOf(length, into.size))
        when {
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

    /**
     * Attaches the swallowed exception to [error] as its cause, once. [error] is what FFmpeg's error
     * code became, and without the cause it only says that an I/O operation failed.
     */
    fun explain(error: FFmpegException) {
        val swallowed = failure ?: return
        failure = null
        if (error.cause == null) error.initCause(swallowed)
    }

    /** Runs on MediaSource.close, after the C side dropped its refs. */
    fun closeSource() = io.close()
}
