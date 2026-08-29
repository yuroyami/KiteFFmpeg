package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_averror_eagain
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_averror_eof
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_avseek_flag_any
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_avseek_flag_backward
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_flush
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_receive_frame
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_send_packet
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_read_frame
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_seek_file
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_alloc
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_alloc
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_clone
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_data
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_dts
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_duration
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_is_keyframe
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_pos
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_pts
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_size
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_stream_index
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_unref

/** FFmpeg's AV_NOPTS_VALUE, the sentinel a stream without timestamps carries. */
private const val NOPTS: Long = Long.MIN_VALUE

private fun Long.microsOrNull(timeBase: Rational): Long? =
    if (this == NOPTS) null else rescaleQ(this, timeBase, MICRO)

private val MICRO = Rational.of(1L, 1_000_000L)

public actual class Packet internal constructor(
    internal var pointer: Int,
    private val base: Rational,
) : AutoCloseable {

    private fun alive(): Int =
        if (pointer != 0) pointer else throw FFmpegException(FFmpegError.Internal("this packet is closed"))

    public actual val timeBase: Rational get() = base
    public actual val streamIndex: Int get() = ffkmp_packet_stream_index(requireModule(), alive())
    public actual val pts: Long get() = ffkmp_packet_pts(requireModule(), alive())
    public actual val dts: Long get() = ffkmp_packet_dts(requireModule(), alive())
    public actual val duration: Long get() = ffkmp_packet_duration(requireModule(), alive())
    public actual val isKeyframe: Boolean get() = ffkmp_packet_is_keyframe(requireModule(), alive()) != 0
    public actual val sizeBytes: Int get() = ffkmp_packet_size(requireModule(), alive())
    public actual val bytePosition: Long get() = ffkmp_packet_pos(requireModule(), alive())
    public actual val hasPts: Boolean get() = pts != NOPTS
    public actual val ptsMicros: Long? get() = pts.microsOrNull(base)
    public actual val dtsMicros: Long? get() = dts.microsOrNull(base)
    public actual val durationMicros: Long? get() = if (duration == 0L) null else rescaleQ(duration, base, MICRO)

    public actual fun copy(): Packet {
        val cloned = ffkmp_packet_clone(requireModule(), alive())
        if (cloned == 0) throw FFmpegException(FFmpegError.Internal("packet clone failed"))
        return Packet(cloned, base)
    }

    public actual fun copyBytes(): ByteArray {
        val m = requireModule()
        val p = alive()
        val size = ffkmp_packet_size(m, p)
        if (size <= 0) return ByteArray(0)
        return readBytes(m, ffkmp_packet_data(m, p), size)
    }

    actual override fun close() {
        val p = pointer
        if (p != 0) {
            pointer = 0
            val m = requireModule()
            ffkmp_packet_unref(m, p)
            ffkmp_packet_free(m, p)
        }
    }
}

public actual enum class SeekDirection {
    Backward,
    Forward,
    Any,
}

public actual class PacketReader internal constructor(
    private val source: MediaSource,
    private val context: Int,
    private val timeBases: Map<Int, Rational>,
    private var wanted: Set<Int>,
    /** The container's own origin, so [seek] can convert the public timeline onto it. */
    private val startTimeMicros: Long,
    private val lifetime: SourceLifetime,
    /** Returns the source's demux-cursor lease. Called exactly once, from [close]. */
    private val onClosed: () -> Unit = {},
) : AutoCloseable {

    private var closed = false

    public actual fun read(): Packet? {
        if (closed) throw FFmpegException(FFmpegError.Internal("this packet reader is closed"))
        lifetime.check("packet reader")
        val m = requireModule()
        val eof = ffkmp_averror_eof(m)
        while (true) {
            val packet = ffkmp_packet_alloc(m)
            if (packet == 0) throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
            val rc = ffkmp_fmt_read_frame(m, context, packet)
            if (rc < 0) {
                ffkmp_packet_free(m, packet)
                if (rc == eof) return null
                throw FFmpegException(FFmpegError.Internal("reading a packet failed with $rc"))
            }
            val index = ffkmp_packet_stream_index(m, packet)
            if (index in wanted) {
                return Packet(packet, timeBases[index] ?: MICRO)
            }
            // Not a stream this reader was opened for: drop it and keep going rather than hand the
            // caller a packet it would have to filter itself.
            ffkmp_packet_unref(m, packet)
            ffkmp_packet_free(m, packet)
        }
    }

    public actual fun seek(micros: Long, direction: SeekDirection, notEarlierThan: Long?) {
        lifetime.check("packet reader")
        val m = requireModule()
        val flags = when (direction) {
            SeekDirection.Backward -> ffkmp_avseek_flag_backward(m)
            SeekDirection.Any -> ffkmp_avseek_flag_any(m)
            SeekDirection.Forward -> 0
        }
        // Content-relative in, container-absolute out. The public timeline starts at zero and the
        // container's may not, so a file whose first timestamp is not zero was seeking to the wrong
        // place by exactly its start time: MPEG-TS captures above all (audit P0-04).
        val target = micros + startTimeMicros
        val min = notEarlierThan?.let { it + startTimeMicros } ?: Long.MIN_VALUE
        // Backward promises never to land after the target, so the target IS the ceiling. Forward
        // and Any may overshoot by contract. Same bound rule as the JVM and Native actuals.
        val max = when (direction) {
            SeekDirection.Backward -> target
            SeekDirection.Forward, SeekDirection.Any -> Long.MAX_VALUE
        }
        val rc = ffkmp_fmt_seek_file(m, context, -1, min, target, max, flags)
        if (rc < 0) throw FFmpegException(FFmpegError.Internal("seek to ${micros}us failed with $rc"))
    }

    public actual fun reselect(streams: List<StreamInfo>) {
        if (closed) throw FFmpegException(FFmpegError.Internal("this packet reader is closed"))
        lifetime.check("packet reader")
        val next = canonicalPacketSelection(source.streams, streams)
        val previous = wanted
        source.applyPacketReaderSelection(next.keys, previous)
        // The set remains the exact delivery gate even for demuxers that ignore discard hints.
        wanted = next.keys
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Exactly once, and even if the source is already gone: the lease lives on the source
        // object, not in the container, so returning it is always safe and always owed.
        onClosed()
    }
}

public actual class StreamDecoder internal constructor(
    private val context: Int,
    public actual val stream: StreamInfo,
    private val lifetime: SourceLifetime,
    private val corruptData: CorruptData = CorruptData.Skip,
) : AutoCloseable {

    public actual var isDrained: Boolean = false
        private set

    public actual var corruptDataSkipped: Long = 0L
        private set

    /**
     * The one place damaged data is decided about (audit P1-05).
     *
     * This backend used to map damage to `Internal` in some paths and swallow it in others, which
     * was a third behaviour on top of the two the other backends had. All three now agree.
     */
    private fun noteCorruptData(rc: Int) {
        if (corruptData == CorruptData.Fail) {
            throw FFmpegException(FFmpegError.InvalidData(rc, "damaged data, and the policy is to fail"))
        }
        corruptDataSkipped++
    }

    private var closed = false
    private val frame: Int = ffkmp_frame_alloc(requireModule())

    private fun alive() {
        if (closed) throw FFmpegException(FFmpegError.Internal("this decoder is closed"))
        lifetime.check("decoder")
    }

    public actual fun send(packet: Packet?): Boolean {
        alive()
        val m = requireModule()
        // A null packet is the drain signal. A CLOSED packet is a caller mistake, and reading its
        // raw pointer turned the second into the first: closing a packet and sending it began the
        // drain instead of failing, so a stream could end early and silently (audit P0-02).
        val pointer = if (packet == null) {
            0
        } else {
            packet.pointer.takeIf { it != 0 }
                ?: throw FFmpegException(FFmpegError.Internal("this packet is closed"))
        }
        // After the liveness check, because reading the index of a closed packet is the worse
        // failure of the two and deserves its own message.
        requireOwnStream(packet, stream)
        val rc = ffkmp_codecctx_send_packet(m, context, pointer)
        return when {
            rc == 0 -> true
            // Not consumed. The caller must drain and offer this SAME input again, which is why
            // nothing here may record progress: marking the decoder drained on an EAGAIN drain
            // signal ended the stream while the decoder still held frames (audit P0-02).
            rc == ffkmp_averror_eagain(m) -> false
            // Already flushed. Nothing more to send, so the input is not owed another attempt.
            rc == ffkmp_averror_eof(m) -> true
            // Same tolerance the JVM and Native backends apply: a packet the decoder cannot use is
            // not a reason to abandon the file, and refusing it here would strand the caller.
            rc == FFmpegError.AVERROR_INVALIDDATA -> {
                noteCorruptData(rc)
                true
            }
            else -> throw FFmpegException(FFmpegError.Internal("sending a packet failed with $rc"))
        }
    }

    public actual fun receive(): Frame? {
        alive()
        val m = requireModule()
        val rc = ffkmp_codecctx_receive_frame(m, context, frame)
        // Only the codec's own EOF ends the stream, and only here. EAGAIN means "not yet", which
        // is the opposite answer and used to be indistinguishable from it.
        if (rc == ffkmp_averror_eof(m)) {
            isDrained = true
            return null
        }
        if (rc == ffkmp_averror_eagain(m)) return null
        if (rc == FFmpegError.AVERROR_INVALIDDATA) {
            noteCorruptData(rc)
            return null
        }
        if (rc < 0) throw FFmpegException(FFmpegError.Internal("receiving a frame failed with $rc"))
        // The decoder reuses its frame, so the caller gets a clone it owns outright. Handing out
        // the shared one would make the next receive() mutate a frame the caller still holds.
        val owned = io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_clone(m, frame)
        if (owned == 0) throw FFmpegException(FFmpegError.Internal("cloning the decoded frame failed"))
        return Frame(owned, stream.index, stream.type, stream.timeBase)
    }

    public actual fun flush() {
        alive()
        ffkmp_codecctx_flush(requireModule(), context)
        corruptDataSkipped = 0L
        isDrained = false
    }

    actual override fun close() {
        if (closed) return
        closed = true
        val m = requireModule()
        if (frame != 0) ffkmp_frame_free(m, frame)
        // Unconditional: this context was allocated here with avcodec_alloc_context3, and closing
        // the container frees the format context and its streams, never a caller's codec context.
        // Skipping it when the source went first leaked one context per decoder (audit P0-05).
        ffkmp_codecctx_free(m, context)
    }
}

/**
 * The lifetime of the container every reader and decoder borrows from.
 *
 * These hold the `AVFormatContext` as a raw address, and `MediaSource.close()` frees it. Without
 * this, a reader used after its source was closed would read freed memory and answer plausible
 * nonsense, which is exactly the failure the generation-tagged handle table exists to prevent on
 * the JNI side (17.14 X-04). The web backend does not route through that table, so it owes the
 * same guarantee in Kotlin: one flag the parent clears and every child checks first.
 */
internal class SourceLifetime {
    var isOpen: Boolean = true
        private set

    fun closed() {
        isOpen = false
    }

    fun check(what: String) {
        if (!isOpen) {
            throw FFmpegException(
                FFmpegError.Internal(
                    "this $what outlived the MediaSource it came from. The container was closed, " +
                        "so its context is freed and using it would read released memory.",
                ),
            )
        }
    }
}
