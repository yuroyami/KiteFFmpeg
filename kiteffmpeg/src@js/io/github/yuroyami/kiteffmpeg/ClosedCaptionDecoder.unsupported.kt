package io.github.yuroyami.kiteffmpeg

public actual class ClosedCaptionDecoder private constructor() : AutoCloseable {
    public actual fun decode(captions: ByteArray, ptsMicros: Long): Subtitle? =
        placeholderBackendUnavailable("Decoding closed captions")

    public actual fun drain(): Subtitle? = placeholderBackendUnavailable("Draining a closed caption decoder")

    public actual fun flush(): Unit = placeholderBackendUnavailable("Flushing a closed caption decoder")

    actual override fun close(): Unit = Unit

    public actual companion object {
        public actual fun open(realTime: Boolean): ClosedCaptionDecoder = placeholderBackendUnavailable("Opening a closed caption decoder")
    }
}
