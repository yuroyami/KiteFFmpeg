package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sink that is closed having produced nothing must end cleanly, and there are two ways to
 * produce nothing.
 *
 * This is worth pinning because "produced nothing" is the shape a FAILED encode also has. A caller
 * that sets up a sink, hits an error, and closes it in a `finally` walks this path, so it must not
 * throw over the top of the real failure, and it must not leave a half-written file that looks
 * playable. Both cases below were undocumented and untested until 2026-08-30.
 */
class EmptySinkContractTest {

    /** No stream was ever declared: there is nothing a container could even describe. */
    @Test
    fun aSinkClosedWithNoStreamsWritesNoFileAndDoesNotThrow() {
        val path = contractOutputPath("mkv")
        try {
            // Closing must not throw, because this is exactly what a `finally` runs after a setup
            // that failed, and a throw here would replace the real error with this one.
            MediaSink.open(path).close()

            // No header was written, so no file was created. That is the honest outcome: a caller
            // that declared nothing gets nothing, rather than a file that opens and is empty.
            val read = runCatching { readContractBytes(path) }
            assertTrue(
                read.isFailure,
                "a sink with no streams wrote a file anyway, ${read.getOrNull()?.size} bytes, " +
                    "which a reader would have to open before discovering it describes nothing",
            )
        } finally {
            deleteContractPath(path)
        }
    }

    /**
     * A stream WAS declared and no frame ever reached it. Unlike the case above there is something
     * to describe, so the header is written on demand at close and the trailer follows it (audit
     * P1-5): the sink owes a real container or an explicit failure, never a missing file.
     *
     * ### What this deliberately does NOT assert, and why
     *
     * Not that the file reopens. Measured against ffmpeg 8.0's own CLI on 2026-08-30, a zero-frame
     * encode produces the same thing from FFmpeg itself as it does from here: an mp4 that opens and
     * reports ZERO streams, and a matroska that FFmpeg's own demuxer rejects with "invalid as first
     * byte of an EBML number". Same errors, same byte scale, from `ffmpeg -frames:v 0`.
     *
     * So the emptiness of an empty container is FFmpeg's business. This library wraps FFmpeg, and
     * asserting a better empty file than FFmpeg writes would be asserting a promise it does not
     * make. What IS this layer's promise is everything below: close reports the truth, the header
     * and trailer are attempted, and a file exists afterwards.
     */
    @Test
    fun aSinkClosedWithADeclaredStreamAndNoFramesStillWritesItsHeaderAndTrailer() {
        val path = contractOutputPath("mp4")
        try {
            val sink = MediaSink.open(path)
            sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64,
                    height = 64,
                    pixelFormat = PixelFormat.Yuv420p,
                    frameRate = Rational(25, 1),
                ),
            )
            // Must not throw: this is the path a set-up-then-failed encode takes through a finally.
            sink.close()

            val bytes = readContractBytes(path)
            assertTrue(
                bytes.isNotEmpty(),
                "a declared stream must produce a header and a trailer, not a missing file",
            )
            // Opening it must not blow up, which is the part FFmpeg does honour for mp4.
            MediaSource.open(path).use { reopened ->
                assertEquals(
                    0,
                    reopened.streams.size,
                    "FFmpeg writes a stream-less mp4 for a zero-frame encode; if this ever reads 1 " +
                        "then FFmpeg changed and the comment above needs re-measuring, not patching",
                )
            }
        } finally {
            deleteContractPath(path)
        }
    }
}
