package io.github.yuroyami.kiteffmpeg

/**
 * The JNI face of one [MediaByteSink], the output twin of [JniByteIo]. The C side holds a global
 * ref to this object and calls [write] and [seek] BY NAME through cached jmethodIDs, on the thread
 * that writes the output. The names and signatures are pinned in native/kitecodec-jni/kj_format.c
 * and the consumer keep rules.
 *
 * [write] returns 0, or -2 on failure; [seek] returns the new position or -2. Exceptions never
 * cross into C: they are parked here and [explain] attaches them to the error FFmpeg reports.
 */
internal class JniByteSink(private val sink: MediaByteSink) {

    val seekable: Boolean = sink.seekable

    /** The exception the last [write] or [seek] swallowed, kept for the error it causes. */
    @Volatile
    private var failure: Throwable? = null

    /** Called from C with the first [length] bytes of [from] to hand over. */
    @Suppress("unused")
    fun write(from: ByteArray, length: Int): Int = try {
        sink.write(from, 0, length)
        0
    } catch (t: Throwable) {
        failure = t
        -2
    }

    /** Called from C with an absolute position. */
    @Suppress("unused")
    fun seek(position: Long): Long = try {
        sink.seek(position)
        position
    } catch (t: Throwable) {
        failure = t
        -2L
    }

    /** Attaches the swallowed exception to [error] as its cause, once. */
    fun explain(error: FFmpegException): FFmpegException {
        val swallowed = failure ?: return error
        failure = null
        if (error.cause == null) error.initCause(swallowed)
        return error
    }

    /** Runs once, after the C side freed the output: the sink's flush, then its close. */
    fun finish() {
        try {
            sink.flush()
        } finally {
            sink.close()
        }
    }
}
