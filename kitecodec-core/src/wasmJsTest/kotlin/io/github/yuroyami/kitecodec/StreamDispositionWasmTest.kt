package io.github.yuroyami.kitecodec

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KP SALANKE N04: the web backend built every StreamInfo without a disposition, so every stream
 * answered Disposition.None and the player's default, forced, accessibility and cover-art
 * policies were all dead on the web. The binding entry points existed the whole time.
 */
@OptIn(KiteCodecLowLevelApi::class)
class StreamDispositionWasmTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun streamDispositionFlagsReachStreamInfo() {
        useCodecModule(fakePacketReaderCodecModule())
        val source = MediaSource.open(SingleByteSource(), emptyMap<String, String>())
        try {
            assertEquals(
                Disposition(default = true, forced = true),
                source.streams[0].disposition,
                "stream 0 carries AV_DISPOSITION_DEFAULT or AV_DISPOSITION_FORCED and lost it",
            )
            assertEquals(
                Disposition(hearingImpaired = true),
                source.streams[1].disposition,
                "stream 1 carries AV_DISPOSITION_HEARING_IMPAIRED and lost it",
            )
        } finally {
            source.close()
        }
    }
}

private class SingleByteSource : MediaByteSource {
    override val size: Long = 1L
    override val seekable: Boolean = true
    private var consumed = false

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (consumed) return -1
        into[offset] = 0
        consumed = true
        return 1
    }

    override fun seek(position: Long) {}
    override fun close() {}
}
