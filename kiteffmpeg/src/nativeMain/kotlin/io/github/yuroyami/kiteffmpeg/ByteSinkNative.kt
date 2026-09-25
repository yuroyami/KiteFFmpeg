package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_fmt_alloc_output_io
import ffmpeg.kc_fmt_ctx
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value

/** One [MediaByteSink] behind the C output bridge's opaque, and the exception its last call threw. */
internal class ByteSinkState(val sink: MediaByteSink) {
    val scratch = ByteArray(64 * 1024)
    val ref: StableRef<ByteSinkState> = StableRef.create(this)

    /** The exception the last write or seek swallowed, kept for the error it causes. */
    var failure: Throwable? = null

    /** The parked exception, once. */
    fun takeFailure(): Throwable? = failure.also { failure = null }

    /** Runs once, after the C side freed the output: disposes the ref, then flushes and closes the sink. */
    fun finish() {
        ref.dispose()
        try {
            sink.flush()
        } finally {
            sink.close()
        }
    }
}

/* The C-callable trampolines. Non-capturing by staticCFunction's rule: all state arrives through
 * the opaque StableRef. write returns 0 or KC_IO_ERR (-2), seek the new position or KC_IO_ERR.
 * Exceptions must NEVER unwind into C. */
@OptIn(ExperimentalForeignApi::class)
private val byteSinkWrite = staticCFunction { opaque: COpaquePointer?, buf: CPointer<UByteVar>?, len: Int ->
    val state = opaque!!.asStableRef<ByteSinkState>().get()
    try {
        val src = buf!!
        var done = 0
        while (done < len) {
            val piece = minOf(len - done, state.scratch.size)
            // Element copy, for the same reason as the byte source's: memcpy's size_t width differs
            // between the 32-bit and 64-bit native targets.
            var i = 0
            while (i < piece) {
                state.scratch[i] = src[done + i].toByte()
                i++
            }
            state.sink.write(state.scratch, 0, piece)
            done += piece
        }
        0
    } catch (failure: Throwable) {
        state.failure = failure
        -2
    }
}

@OptIn(ExperimentalForeignApi::class)
private val byteSinkSeek = staticCFunction { opaque: COpaquePointer?, offset: Long, _: Int ->
    val state = opaque!!.asStableRef<ByteSinkState>().get()
    try {
        state.sink.seek(offset)
        offset
    } catch (failure: Throwable) {
        state.failure = failure
        -2L
    }
}

/** An output context over [state], or FFmpeg's refusal as an exception. */
@OptIn(ExperimentalForeignApi::class)
internal fun allocOutputIo(state: ByteSinkState, format: String): CPointer<kc_fmt_ctx> = memScoped {
    val slot = allocPointerTo<kc_fmt_ctx>()
    val rc = ffkmp_fmt_alloc_output_io(
        slot.ptr, format, state.ref.asCPointer(), byteSinkWrite, if (state.sink.seekable) byteSinkSeek else null,
    )
    if (rc < 0) throw FFmpegException(avError(rc))
    slot.value ?: throw FFmpegException(FFmpegError.Internal("the output open returned no context"))
}
