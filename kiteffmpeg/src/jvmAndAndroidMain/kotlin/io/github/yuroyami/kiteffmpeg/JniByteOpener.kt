package io.github.yuroyami.kiteffmpeg

/**
 * The JNI face of one [MediaByteOpener], through the nested opens of the custom AVIO bridge. The C
 * side holds a global ref to this object and calls [open] and [close] BY NAME through cached
 * jmethodIDs, on the thread that drives the demuxer. The names and signatures are pinned in
 * native/kitecodec-jni/kj_format.c and the consumer keep rules; renaming either side alone breaks
 * the bridge at open time, loudly.
 *
 * Unlike [JniByteIo], [open] lets its exception reach C. C clears it and reports KC_IO_ERR, and that
 * is how C tells a failure from a refusal, which is a null. The exception is parked first, so that
 * the error of a failed open can name it.
 */
internal class JniByteOpener(private val opener: MediaByteOpener) {

    /** The exception the last failed [open] or nested read swallowed, kept for the error it causes. */
    @Volatile
    private var failure: Throwable? = null

    private val closeFailures = mutableListOf<Throwable>()

    /** Called from C. The JNI face of the bytes at [url], or null when the opener declines the address. */
    @Suppress("unused")
    fun open(url: String): JniByteIo? {
        val source = try {
            opener.open(url)
        } catch (t: Throwable) {
            failure = t
            throw t
        }
        return source?.let(::JniByteIo)
    }

    /** Called from C, once for each source [open] returned, when FFmpeg is done with it. Never throws. */
    @Suppress("unused")
    fun close(io: JniByteIo) {
        io.takeFailure()?.let { failure = it }
        try {
            io.closeSource()
        } catch (t: Throwable) {
            synchronized(closeFailures) { closeFailures += t }
        }
    }

    /** The parked exception, once. */
    fun takeFailure(): Throwable? = failure.also { failure = null }

    /** Every exception a nested close threw, once. */
    fun takeCloseFailures(): List<Throwable> = synchronized(closeFailures) {
        closeFailures.toList().also { closeFailures.clear() }
    }
}
