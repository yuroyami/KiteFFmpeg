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
 * cross into C: they are caught here, parked, and reported as -2.
 */
internal class JniByteIo(private val io: MediaByteSource) {

    private var position = 0L

    /** The last failure [read] or [seek] swallowed, for a truer diagnosis than FFmpeg's EIO. */
    @Volatile
    var failure: Throwable? = null
        private set

    /** Called from C. Fills [into] from the source, returns count, -1 EOF, -2 error. */
    @Suppress("unused")
    fun read(into: ByteArray, length: Int): Int = try {
        val r = io.read(into, 0, minOf(length, into.size))
        when {
            r > 0 -> {
                position += r
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

    /** Runs on MediaSource.close, after the C side dropped its refs. */
    fun closeSource() = io.close()
}
