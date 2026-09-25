package io.github.yuroyami.kiteffmpeg

import cnames.structs.kc_swr
import ffmpeg.ffkmp_frame_alloc
import ffmpeg.ffkmp_frame_free
import ffmpeg.ffkmp_frame_nb_samples
import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_swr_convert_frame
import ffmpeg.ffkmp_swr_create
import ffmpeg.ffkmp_swr_free
import ffmpeg.kc_frame
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class, KiteFFmpegLowLevelApi::class)
public actual class Resampler actual constructor(
    public actual val input: AudioSpec,
    public actual val output: AudioSpec,
) : AutoCloseable {

    private val handle: CPointer<kc_swr>
    private var closed = false

    /** Where the output timeline starts, set by the first converted frame. */
    private var startMicros: Long? = null
    private var samplesOut = 0L

    init {
        // The FFmpeg identity gate. Before the first allocation.
        requireCompatibleFFmpeg()
        refuseUnwiredFields("AudioSpec.channelLayoutMask" to (input.channelLayoutMask ?: output.channelLayoutMask))
        val inFormat = sampleFormatToAv(input.sampleFormat)
        val outFormat = sampleFormatToAv(output.sampleFormat)
        handle = memScoped {
            val slot = alloc<CPointerVar<kc_swr>>()
            val rc = ffkmp_swr_create(
                slot.ptr,
                input.sampleRate, input.channels, inFormat,
                output.sampleRate, output.channels, outFormat,
            )
            if (rc < 0) throw FFmpegException(avError(rc))
            slot.value ?: throw FFmpegException(FFmpegError.Internal("swr_create returned NULL"))
        }
    }

    @Throws(FFmpegException::class)
    public actual fun convert(frame: Frame): Frame? {
        check(!closed) { "Resampler is closed" }
        requireAudioSpec(frame, input)
        if (startMicros == null) startMicros = frame.ptsMicros ?: 0L
        return frame.withNative { native -> run(native) }
    }

    @Throws(FFmpegException::class)
    public actual fun flush(): Frame? {
        check(!closed) { "Resampler is closed" }
        return run(null)
    }

    private fun run(source: CPointer<kc_frame>?): Frame? {
        val out = ffkmp_frame_alloc() ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
        val rc = ffkmp_swr_convert_frame(handle, out, source)
        val samples = if (rc < 0) 0 else ffkmp_frame_nb_samples(out)
        if (rc < 0 || samples <= 0) {
            ffkmp_frame_free(out)
            if (rc < 0) throw FFmpegException(avError(rc))
            return null
        }
        ffkmp_frame_set_pts(out, resampledPtsMicros(startMicros ?: 0L, samplesOut, output.sampleRate))
        samplesOut += samples
        return Frame(out, ownsPointer = true, streamIndex = -1, streamType = MediaType.Audio, streamTimeBase = Rational.Tb_us)
    }

    actual override fun close() {
        if (closed) return
        closed = true
        memScoped {
            val slot = alloc<CPointerVar<kc_swr>>()
            slot.value = handle
            ffkmp_swr_free(slot.ptr)
        }
    }
}
