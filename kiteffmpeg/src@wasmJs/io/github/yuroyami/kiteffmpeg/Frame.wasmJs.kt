package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_content_light
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_mastering_display
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_a53_cc
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_alloc
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_dovi_compose_prepare
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_dovi_compose_rows
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_dovi_metadata
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_dovi_rpu
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_ch_layout_mask
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
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_sample_aspect_ratio
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_sample_rate
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_width
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_image_get_buffer_size
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_rescale_q
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_samples_copy_to_buffer
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_samples_get_buffer_size

/**
 * A decoded frame, as a handle into the codec module.
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

    /** A closed frame throws IllegalStateException here, as it does on the JVM, Android and native. */
    private fun alive(): Int {
        check(pointer != 0) { "Frame is closed, its native buffers are gone" }
        return pointer
    }

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
                // Each frame's own layout and pixel shape, as the other backends read them. Left
                // at their defaults, every web frame said square pixels and no layout, whatever
                // its stream declared (#130). 0 from the helper means no mask to report.
                channelLayoutMask = if (type == MediaType.Audio) {
                    ffkmp_frame_ch_layout_mask(m, p).takeIf { it != 0L }
                } else null,
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
                sampleAspectRatio = if (type == MediaType.Video) {
                    readRational(m, fallbackNum = 1, fallbackDen = 1) { n, d -> ffkmp_frame_sample_aspect_ratio(m, p, n, d) }
                } else Rational(1, 1),
                isHardware = ffkmp_frame_is_hardware(m, p) != 0,
                hdr = if (type == MediaType.Video) {
                    readHdr(
                        m,
                        display = { q, flags -> ffkmp_frame_mastering_display(m, p, q, flags) },
                        light = { maxCll, maxFall -> ffkmp_frame_content_light(m, p, maxCll, maxFall) },
                    )
                } else null,
            )
        }

    public actual fun copyPlanesToByteArray(): ByteArray {
        val size = planesByteCount()
        // An empty answer for a frame that genuinely carries nothing, which is what the common
        // contract promises and what the other backends do. Throwing here made an unreferenced
        // frame a failure on this backend alone.
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { copyPlanes(it, size) }
    }

    public actual fun planesByteCount(): Int {
        val m = requireModule()
        val p = alive()
        // A frame with no picture or no samples holds 0 bytes, as on the native backend. FFmpeg
        // refuses to size one, so it is never asked.
        val size = when (type) {
            MediaType.Video -> {
                val width = ffkmp_frame_width(m, p)
                val height = ffkmp_frame_height(m, p)
                val format = ffkmp_frame_format(m, p)
                if (width <= 0 || height <= 0 || format < 0) return 0
                ffkmp_image_get_buffer_size(m, format, width, height, 1)
            }
            MediaType.Audio -> {
                if (ffkmp_frame_nb_samples(m, p) <= 0) return 0
                ffkmp_samples_get_buffer_size(m, p)
            }
            else -> return 0
        }
        // A NEGATIVE size is an error: that is FFmpeg refusing to describe the frame, not a
        // frame with no bytes.
        if (size < 0) throw FFmpegException(FFmpegError.Internal("this frame reports no copyable bytes ($size)"))
        return size
    }

    public actual fun copyPlanesInto(destination: ByteArray): Int {
        val size = planesByteCount()
        if (destination.size < size) throw destinationTooShort(destination.size, size)
        if (size > 0) copyPlanes(destination, size)
        return size
    }

    public actual fun closedCaptions(): ByteArray? {
        val m = requireModule()
        val p = alive()
        val size = ffkmp_frame_a53_cc(m, p, 0, 0)
        if (size < 0) throw FFmpegException(FFmpegError.fromCode(size, "reading closed captions failed with $size"))
        if (size == 0) return null
        val buffer = wasmAlloc(m, size)
        try {
            val written = ffkmp_frame_a53_cc(m, p, buffer, size)
            if (written != size) throw FFmpegException(FFmpegError.Internal("closed captions wrote $written of $size bytes"))
            return readBytes(m, buffer, size)
        } finally {
            wasmFree(m, buffer)
        }
    }

    public actual fun dolbyVision(): DolbyVisionMetadata? {
        val m = requireModule()
        val p = alive()
        val ints = wasmAlloc(m, DOLBY_VISION_INTS * 4)
        try {
            val rc = ffkmp_frame_dovi_metadata(m, p, ints)
            if (rc < 0) throw FFmpegException(FFmpegError.fromCode(rc, "reading Dolby Vision metadata failed with $rc"))
            if (rc == 0) return null
            return dolbyVisionMetadataOf(IntArray(DOLBY_VISION_INTS) { readInt32(m, ints + it * 4) })
        } finally {
            wasmFree(m, ints)
        }
    }

    public actual fun dolbyVisionRpu(): DolbyVisionRpu? {
        val m = requireModule()
        val p = alive()
        val ints = wasmAlloc(m, DOLBY_VISION_RPU_INTS * 4)
        try {
            val rc = ffkmp_frame_dovi_rpu(m, p, ints, DOLBY_VISION_RPU_INTS)
            if (rc < 0) throw FFmpegException(FFmpegError.fromCode(rc, "reading the Dolby Vision RPU failed with $rc"))
            if (rc == 0) return null
            return dolbyVisionRpuOf(IntArray(DOLBY_VISION_RPU_INTS) { readInt32(m, ints + it * 4) })
        } finally {
            wasmFree(m, ints)
        }
    }

    public actual fun beginDolbyVisionComposition(): DolbyVisionComposition? {
        val m = requireModule()
        val p = alive()
        if (ffkmp_frame_is_hardware(m, p) != 0) throw dolbyVisionHardwareRefusal()
        val composed = ffkmp_frame_alloc(m)
        if (composed == 0) throw FFmpegException(FFmpegError.Internal("could not allocate a frame"))
        val rc = ffkmp_frame_dovi_compose_prepare(m, p, composed)
        if (rc <= 0) {
            ffkmp_frame_free(m, composed)
            if (rc == 0) return null
            throw dolbyVisionPrepareFailure(
                FFmpegError.fromCode(rc, "preparing a Dolby Vision composition failed with $rc"),
                pixelFormatOf(m, ffkmp_frame_format(m, p)),
            )
        }
        val output = Frame(composed, streamIndex, type, timeBase)
        val cloned = ffkmp_frame_clone(m, p)
        if (cloned == 0) {
            output.close()
            throw FFmpegException(FFmpegError.Internal("could not clone this frame"))
        }
        return DolbyVisionComposition(Frame(cloned, streamIndex, type, timeBase), output, ffkmp_frame_height(m, p))
    }

    public actual fun composeDolbyVision(): Frame? = beginDolbyVisionComposition()?.let(::composeWhole)

    /** Copies the frame's [size] bytes through a module buffer into the start of [destination]. */
    private fun copyPlanes(destination: ByteArray, size: Int) {
        val m = requireModule()
        val p = alive()
        val buffer = wasmAlloc(m, size)
        try {
            val written = if (type == MediaType.Video) {
                ffkmp_frame_copy_to_buffer(m, p, buffer, size)
            } else {
                ffkmp_samples_copy_to_buffer(m, p, buffer, size)
            }
            if (written != size) throw FFmpegException(FFmpegError.Internal("frame copy wrote $written of $size bytes"))
            readBytesInto(m, buffer, destination, size)
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
                "playback half of KiteFFmpeg; encoders were left out because " +
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
                "the playback half of KiteFFmpeg."
    }
}

/** FFmpeg's AVCOL_RANGE_JPEG, the full-range enumerator. */
private const val AVCOL_RANGE_JPEG = 2

internal actual fun rescaleQ(value: Long, source: Rational, destination: Rational): Long =
    ffkmp_rescale_q(requireModule(), value, source.num, source.den, destination.num, destination.den)

internal actual fun composeDolbyVisionRows(source: Frame, output: Frame, startRow: Int, endRowExclusive: Int) {
    val rc = ffkmp_frame_dovi_compose_rows(requireModule(), source.pointer, output.pointer, startRow, endRowExclusive)
    if (rc < 0) {
        throw FFmpegException(
            FFmpegError.fromCode(rc, "Dolby Vision composition of rows $startRow until $endRowExclusive failed with $rc"),
        )
    }
}
