package io.github.yuroyami.kiteffmpeg

@OptIn(KiteFFmpegLowLevelApi::class)
public actual class Resampler actual constructor(
    public actual val input: AudioSpec,
    public actual val output: AudioSpec,
) : AutoCloseable {

    private var token: Long

    /** Where the output timeline starts, set by the first converted frame. */
    private var startMicros: Long? = null
    private var samplesOut = 0L

    init {
        Internals.requireCompatible()
        token = Internals.swrCreate(
            input.sampleRate, input.channels, sampleFormatToAv(input.sampleFormat),
            output.sampleRate, output.channels, sampleFormatToAv(output.sampleFormat),
        )
    }

    @Throws(FFmpegException::class)
    public actual fun convert(frame: Frame): Frame? {
        check(token != 0L) { "Resampler is closed" }
        requireAudioSpec(frame, input)
        if (startMicros == null) startMicros = frame.ptsMicros ?: 0L
        return frame.locked { source -> run(source) }
    }

    @Throws(FFmpegException::class)
    public actual fun flush(): Frame? {
        check(token != 0L) { "Resampler is closed" }
        return run(0L)
    }

    private fun run(source: Long): Frame? {
        val out = Internals.frameAlloc()
        val rc = Internals.swrConvertFrame(token, out, source)
        val samples = if (rc < 0) 0 else Internals.frameSampleCount(out)
        if (rc < 0 || samples <= 0) {
            Internals.frameFree(out)
            if (rc < 0) throw FFmpegException(avError(rc))
            return null
        }
        Internals.frameSetPts(out, resampledPtsMicros(startMicros ?: 0L, samplesOut, output.sampleRate))
        samplesOut += samples
        return Frame(out, true, -1, MediaType.Audio, Rational.Tb_us)
    }

    actual override fun close() {
        val closing = token
        if (closing == 0L) return
        token = 0L
        Internals.swrFree(closing)
    }
}
