package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_buffersink_set_frame_size
import ffmpeg.ffkmp_buffersink_time_base
import ffmpeg.ffkmp_frame_unref
import ffmpeg.ffkmp_graph_build_audio
import ffmpeg.ffkmp_graph_build_audio_multi
import ffmpeg.ffkmp_graph_build_video
import ffmpeg.ffkmp_graph_build_video_multi
import ffmpeg.ffkmp_graph_failed_requests
import ffmpeg.ffkmp_graph_free
import ffmpeg.ffkmp_graph_receive
import ffmpeg.ffkmp_graph_send
import ffmpeg.kc_filter_ctx
import ffmpeg.kc_filter_graph
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value

/** The native half of a [FilterGraph]: the graph's own calls over its C pointers. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal class NativeFilterBackend(
    private val graph: CPointer<kc_filter_graph>,
    private val srcs: List<CPointer<kc_filter_ctx>>,
    private val sink: CPointer<kc_filter_ctx>,
    private val inputType: MediaType,
) : FilterBackend {

    override val inputCount: Int get() = srcs.size

    override val outputTimeBase: Rational = memScoped {
        val n = alloc<IntVar>(); val d = alloc<IntVar>()
        ffkmp_buffersink_time_base(sink, n.ptr, d.ptr)
        Rational(n.value, d.value.takeIf { it != 0 } ?: 1)
    }

    /** Reusable landing frame for buffersink output; allocated on first use, freed in [free]. */
    private var landing: Frame? = null

    override fun setOutputFrameSize(samples: Int) {
        ffkmp_buffersink_set_frame_size(sink, samples.toUInt())
    }

    override fun send(index: Int, frame: Frame?): Int =
        if (frame == null) ffkmp_graph_send(srcs[index], null)
        else frame.withNative { native -> ffkmp_graph_send(srcs[index], native) }

    /**
     * The landing frame is released after every receive by closing the wrapper around it, which
     * performs the unref `av_buffersink_get_frame` needs before its next call, and what leaves is a
     * clone: an O(1) reference the caller owns.
     */
    override fun receive(): Frame? {
        val into = landing ?: FrameOps.acquire(streamIndex = -1, streamType = inputType, timeBase = outputTimeBase)
            .also { landing = it }
        val rc = ffkmp_graph_receive(sink, into.nativeFrame)
        if (rc == FFErrors.EAGAIN || rc == FFErrors.EOF) return null
        if (rc < 0) throw FFmpegException(avError(rc))
        val view = FrameOps.wrap(into.nativeFrame, -1, inputType, outputTimeBase)
        try {
            return view.copy()
        } finally {
            view.close()
        }
    }

    override fun failedRequests(index: Int): Int = ffkmp_graph_failed_requests(srcs[index])

    override fun isAgain(rc: Int): Boolean = rc == FFErrors.EAGAIN
    override fun isEof(rc: Int): Boolean = rc == FFErrors.EOF
    override fun error(rc: Int): FFmpegError = avError(rc)

    override fun free() {
        landing?.close()
        landing = null
        val a = Arena()
        try {
            val gp = a.alloc<CPointerVar<kc_filter_graph>>().also { it.value = graph }
            ffkmp_graph_free(gp.ptr)
        } finally {
            a.clear()
        }
    }
}

internal actual val filterGraphRefusal: String? = null

@Throws(FFmpegException::class)
internal actual fun buildVideoBackend(
    description: String,
    width: Int,
    height: Int,
    pixelFormat: PixelFormat,
    timeBase: Rational,
    frameRate: Rational,
    sampleAspectRatio: Rational,
): FilterBackend {
    // The FFmpeg identity gate. Before the first allocation.
    requireCompatibleFFmpeg()
    val arena = Arena()
    val graphVar = arena.allocPointerTo<kc_filter_graph>()
    val srcVar = arena.allocPointerTo<kc_filter_ctx>()
    val sinkVar = arena.allocPointerTo<kc_filter_ctx>()

    val rc = ffkmp_graph_build_video(
        graphVar.ptr, srcVar.ptr, sinkVar.ptr,
        description,
        width, height, pixelFormatToAv(pixelFormat),
        timeBase.num, timeBase.den,
        frameRate.num, frameRate.den,
        sampleAspectRatio.num, sampleAspectRatio.den,
    )
    if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }

    val graph = graphVar.value!!
    val src = srcVar.value!!
    val sink = sinkVar.value!!
    arena.clear()
    return NativeFilterBackend(graph, listOf(src), sink, MediaType.Video)
}

@Throws(FFmpegException::class)
internal actual fun buildAudioBackend(
    description: String,
    sampleRate: Int,
    sampleFormat: SampleFormat,
    channels: Int,
    timeBase: Rational,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    channelLayoutMask: Long?,
    outputChannelLayoutMask: Long?,
): FilterBackend {
    // The FFmpeg identity gate. Before the first allocation.
    requireCompatibleFFmpeg()
    val arena = Arena()
    val graphVar = arena.allocPointerTo<kc_filter_graph>()
    val srcVar = arena.allocPointerTo<kc_filter_ctx>()
    val sinkVar = arena.allocPointerTo<kc_filter_ctx>()

    val outFmtAv = if (outputSampleFormat == SampleFormat.None) -1 else sampleFormatToAv(outputSampleFormat)
    val rc = ffkmp_graph_build_audio(
        graphVar.ptr, srcVar.ptr, sinkVar.ptr,
        description,
        sampleRate, sampleFormatToAv(sampleFormat), channels,
        timeBase.num, timeBase.den,
        outFmtAv, outputSampleRate, outputChannels,
        channelLayoutMask ?: 0L, outputChannelLayoutMask ?: 0L,
    )
    if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }

    val graph = graphVar.value!!
    val src = srcVar.value!!
    val sink = sinkVar.value!!
    arena.clear()
    return NativeFilterBackend(graph, listOf(src), sink, MediaType.Audio)
}

@Throws(FFmpegException::class)
internal actual fun buildVideoMultiBackend(description: String, inputs: List<VideoInput>): FilterBackend {
    // The FFmpeg identity gate. Before the first allocation.
    requireCompatibleFFmpeg()
            memScoped {
        val n = inputs.size
        val graphVar = allocPointerTo<kc_filter_graph>()
        val sinkVar = allocPointerTo<kc_filter_ctx>()
        val srcsArr = allocArray<CPointerVar<kc_filter_ctx>>(n)
        val widths = allocArray<IntVar>(n); val heights = allocArray<IntVar>(n)
        val pixFmts = allocArray<IntVar>(n)
        val tbN = allocArray<IntVar>(n); val tbD = allocArray<IntVar>(n)
        val frN = allocArray<IntVar>(n); val frD = allocArray<IntVar>(n)
        val sarN = allocArray<IntVar>(n); val sarD = allocArray<IntVar>(n)
        inputs.forEachIndexed { i, inp ->
            widths[i] = inp.width; heights[i] = inp.height
            pixFmts[i] = pixelFormatToAv(inp.pixelFormat)
            tbN[i] = inp.timeBase.num; tbD[i] = inp.timeBase.den
            frN[i] = inp.frameRate.num; frD[i] = inp.frameRate.den
            sarN[i] = inp.sampleAspectRatio.num; sarD[i] = inp.sampleAspectRatio.den
        }

        val rc = ffkmp_graph_build_video_multi(
            graphVar.ptr, srcsArr, sinkVar.ptr,
            description, n,
            widths, heights, pixFmts, tbN, tbD, frN, frD, sarN, sarD,
        )
        if (rc < 0) throw FFmpegException(avError(rc))

        val srcs = (0 until n).map { srcsArr[it]!! }
        return NativeFilterBackend(graphVar.value!!, srcs, sinkVar.value!!, MediaType.Video)
    }
}

@Throws(FFmpegException::class)
internal actual fun buildAudioMultiBackend(
    description: String,
    inputs: List<AudioInput>,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    outputChannelLayoutMask: Long?,
): FilterBackend {
    // The FFmpeg identity gate. Before the first allocation.
    requireCompatibleFFmpeg()
            memScoped {
        val n = inputs.size
        val graphVar = allocPointerTo<kc_filter_graph>()
        val sinkVar = allocPointerTo<kc_filter_ctx>()
        val srcsArr = allocArray<CPointerVar<kc_filter_ctx>>(n)
        val rates = allocArray<IntVar>(n); val fmts = allocArray<IntVar>(n)
        val chans = allocArray<IntVar>(n)
        val tbN = allocArray<IntVar>(n); val tbD = allocArray<IntVar>(n)
        val masks = allocArray<LongVar>(n)
        inputs.forEachIndexed { i, inp ->
            rates[i] = inp.sampleRate
            fmts[i] = sampleFormatToAv(inp.sampleFormat)
            chans[i] = inp.channels
            tbN[i] = inp.timeBase.num; tbD[i] = inp.timeBase.den
            masks[i] = inp.channelLayoutMask ?: 0L
        }
        val outFmtAv = if (outputSampleFormat == SampleFormat.None) -1 else sampleFormatToAv(outputSampleFormat)

        val rc = ffkmp_graph_build_audio_multi(
            graphVar.ptr, srcsArr, sinkVar.ptr,
            description, n,
            rates, fmts, chans, tbN, tbD,
            outFmtAv, outputSampleRate, outputChannels,
            masks, outputChannelLayoutMask ?: 0L,
        )
        if (rc < 0) throw FFmpegException(avError(rc))

        val srcs = (0 until n).map { srcsArr[it]!! }
        return NativeFilterBackend(graphVar.value!!, srcs, sinkVar.value!!, MediaType.Audio)
    }
}
