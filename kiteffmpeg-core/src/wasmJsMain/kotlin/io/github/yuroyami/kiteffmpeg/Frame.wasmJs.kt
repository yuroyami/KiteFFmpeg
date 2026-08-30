package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_channels
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_clone
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_color_range
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_colorspace
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_color_primaries
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_color_trc
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_chroma_location
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_copy_to_buffer
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_duration
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_format
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_height
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_is_hardware
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_is_keyframe
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_nb_samples
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_pts
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_sample_rate
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_width
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_image_get_buffer_size
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_rescale_q
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_samples_copy_to_buffer
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_samples_get_buffer_size

/**
 * A decoded frame, as a handle into the codec module (17.14 X-07).
 *
 * The pointer is an opaque `Int` on this side and is never dereferenced here; every read is a call.
 * The frame is owned: [close] frees it, and closing twice is a no-op because the pointer is cleared.
 */
public actual class Frame internal constructor(
    internal var pointer: Int,
    private val streamIndex: Int,
    private val type: MediaType,
    private val timeBase: Rational,
) : AutoCloseable {

    private fun alive(): Int =
        if (pointer != 0) pointer else throw FFmpegException(FFmpegError.Internal("this frame is closed"))

    public actual val info: FrameInfo
        get() {
            val m = requireModule()
            val p = alive()
            return FrameInfo(
                streamIndex = streamIndex,
                type = type,
                pts = ffkmp_frame_pts(m, p),
                timeBase = timeBase,
                width = ffkmp_frame_width(m, p),
                height = ffkmp_frame_height(m, p),
                pixelFormat = if (type == MediaType.Video) pixelFormatOf(m, ffkmp_frame_format(m, p)) else PixelFormat.None,
                sampleCount = ffkmp_frame_nb_samples(m, p),
                sampleRate = ffkmp_frame_sample_rate(m, p),
                channelCount = ffkmp_frame_channels(m, p),
                sampleFormat = if (type == MediaType.Audio) sampleFormatOf(m, ffkmp_frame_format(m, p)) else SampleFormat.None,
                duration = ffkmp_frame_duration(m, p),
                isKeyframe = ffkmp_frame_is_keyframe(m, p) != 0,
                // Only the two fields this backend can currently answer. The rest keep their
                // documented defaults rather than being invented, and the colour policy above
                // this layer treats Unspecified as "guess", which is the honest input.
                // Read like the other backends read it, then resolved by the same rule. This used
                // to answer range only, so a web frame reported Unspecified for matrix, primaries
                // and transfer no matter what the stream declared, and the three backends
                // disagreed about the same file.
                color = resolveDeclaredColor(
                    ColorInfo(
                        matrix = ColorMatrix.fromAv(ffkmp_frame_colorspace(m, p)),
                        primaries = ColorPrimaries.fromAv(ffkmp_frame_color_primaries(m, p)),
                        transfer = ColorTransfer.fromAv(ffkmp_frame_color_trc(m, p)),
                        fullRange = ffkmp_frame_color_range(m, p) == AVCOL_RANGE_JPEG,
                        chromaLocation = ChromaLocation.fromAv(ffkmp_frame_chroma_location(m, p)),
                        rangeSpecified = ffkmp_frame_color_range(m, p) != 0,
                    ),
                    ffkmp_frame_height(m, p),
                ),
                isHardware = ffkmp_frame_is_hardware(m, p) != 0,
            )
        }

    public actual fun copyPlanesToByteArray(): ByteArray {
        val m = requireModule()
        val p = alive()
        val video = type == MediaType.Video
        val size = if (video) {
            ffkmp_image_get_buffer_size(m, ffkmp_frame_format(m, p), ffkmp_frame_width(m, p), ffkmp_frame_height(m, p), 1)
        } else {
            ffkmp_samples_get_buffer_size(m, p)
        }
        // An empty answer for a frame that genuinely carries nothing, which is what the common
        // contract promises and what the other backends do. Throwing here made an unreferenced
        // frame a failure on this backend alone. A NEGATIVE size is still an error:
        // that is FFmpeg refusing to describe the frame, not a frame with no bytes.
        if (size == 0) return ByteArray(0)
        if (size < 0) throw FFmpegException(FFmpegError.Internal("this frame reports no copyable bytes ($size)"))
        val buffer = wasmAlloc(m, size)
        try {
            val written = if (video) {
                ffkmp_frame_copy_to_buffer(m, p, buffer, size)
            } else {
                ffkmp_samples_copy_to_buffer(m, p, buffer, size)
            }
            if (written != size) throw FFmpegException(FFmpegError.Internal("frame copy wrote $written of $size bytes"))
            return readBytes(m, buffer, size)
        } finally {
            wasmFree(m, buffer)
        }
    }

    public actual fun copy(): Frame {
        val m = requireModule()
        val cloned = ffkmp_frame_clone(m, alive())
        if (cloned == 0) throw FFmpegException(FFmpegError.Internal("could not clone this frame"))
        return Frame(cloned, streamIndex, type, timeBase)
    }

    /**
     * Refused, because no hardware frame exists on this backend: the wasm decoder is software by
     * construction, so every frame here is already the software one this would produce.
     *
     * It used to answer with a copy, which contradicts the shared contract that a non-hardware
     * source is refused rather than copied. A caller reaching this has bookkeeping that is wrong
     * somewhere else, and telling them so is the point.
     */
    public actual fun downloadFromHardware(): Frame = throw FFmpegException(
        FFmpegError.InvalidArgument(
            0,
            "this frame is not a hardware frame: the web backend decodes in software, so there is " +
                "nothing to download. Use copy() to take an owned snapshot.",
        ),
    )

    public actual fun encodeImage(codec: CodecId): ByteArray =
        throw FFmpegException(FFmpegError.Unsupported(
            0, "Encoding an image is not implemented on the web backend. The web build carries the " +
                "playback half of KiteFFmpeg (PLANNING.md); encoders were left out because " +
                "the browser has its own in WebCodecs.",
        ))

    actual override fun close() {
        val p = pointer
        if (p != 0) {
            pointer = 0
            ffkmp_frame_free(requireModule(), p)
        }
    }

    public actual companion object {
        public actual fun ofVideo(
            bytes: ByteArray,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            ptsMicros: Long,
        ): Frame = throw FFmpegException(FFmpegError.Unsupported(0, NO_AUTHORING))

        public actual fun ofAudio(
            bytes: ByteArray,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
            sampleFormat: SampleFormat,
            ptsMicros: Long,
        ): Frame = throw FFmpegException(FFmpegError.Unsupported(0, NO_AUTHORING))

        private const val NO_AUTHORING =
            "Authoring a frame from bytes is not implemented on the web backend, which carries " +
                "the playback half of KiteFFmpeg (PLANNING.md)."
    }
}

/** FFmpeg's AVCOL_RANGE_JPEG, the full-range enumerator. */
private const val AVCOL_RANGE_JPEG = 2

internal actual fun rescaleQ(value: Long, source: Rational, destination: Rational): Long =
    ffkmp_rescale_q(requireModule(), value, source.num, source.den, destination.num, destination.den)
