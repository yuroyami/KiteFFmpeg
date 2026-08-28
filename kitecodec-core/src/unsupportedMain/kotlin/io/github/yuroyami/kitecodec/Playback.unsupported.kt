package io.github.yuroyami.kitecodec

public actual class Packet private constructor() : AutoCloseable {
    public actual val timeBase: Rational
        get() = placeholderBackendUnavailable("Reading a packet time base")
    public actual val streamIndex: Int
        get() = placeholderBackendUnavailable("Reading a packet stream index")
    public actual val pts: Long
        get() = placeholderBackendUnavailable("Reading a packet timestamp")
    public actual val dts: Long
        get() = placeholderBackendUnavailable("Reading a packet decode timestamp")
    public actual val duration: Long
        get() = placeholderBackendUnavailable("Reading a packet duration")
    public actual val isKeyframe: Boolean
        get() = placeholderBackendUnavailable("Reading a packet keyframe flag")
    public actual val sizeBytes: Int
        get() = placeholderBackendUnavailable("Reading a packet size")
    public actual val bytePosition: Long
        get() = placeholderBackendUnavailable("Reading a packet byte position")
    public actual val hasPts: Boolean
        get() = placeholderBackendUnavailable("Reading packet timestamp presence")
    public actual val ptsMicros: Long?
        get() = placeholderBackendUnavailable("Reading a packet timestamp")
    public actual val dtsMicros: Long?
        get() = placeholderBackendUnavailable("Reading a packet decode timestamp")
    public actual val durationMicros: Long?
        get() = placeholderBackendUnavailable("Reading a packet duration")

    @Throws(FFmpegException::class)
    public actual fun copy(): Packet = placeholderBackendUnavailable("Copying a packet")

    public actual fun copyBytes(): ByteArray = placeholderBackendUnavailable("Copying packet bytes")

    actual override fun close(): Unit = Unit
}

public actual enum class SeekDirection {
    Backward,
    Forward,
    Any,
}

public actual class PacketReader private constructor() : AutoCloseable {
    @Throws(FFmpegException::class)
    public actual fun read(): Packet? = placeholderBackendUnavailable("Reading a packet")

    @Throws(FFmpegException::class)
    public actual fun seek(micros: Long, direction: SeekDirection, notEarlierThan: Long?): Unit =
        placeholderBackendUnavailable("Seeking a packet reader")

    @Throws(FFmpegException::class)
    public actual fun reselect(streams: List<StreamInfo>): Unit =
        placeholderBackendUnavailable("Reselecting packet reader streams")

    actual override fun close(): Unit = Unit
}

public actual class StreamDecoder private constructor() : AutoCloseable {
    public actual val stream: StreamInfo
        get() = placeholderBackendUnavailable("Reading a decoder stream")

    public actual var isDrained: Boolean = false
        private set

    public actual var corruptDataSkipped: Long = 0L
        private set

    @Throws(FFmpegException::class)
    public actual fun send(packet: Packet?): Boolean =
        placeholderBackendUnavailable("Sending a decoder packet")

    @Throws(FFmpegException::class)
    public actual fun receive(): Frame? = placeholderBackendUnavailable("Receiving a decoded frame")

    public actual fun flush(): Unit = placeholderBackendUnavailable("Flushing a decoder")

    actual override fun close(): Unit = Unit
}
