package io.github.yuroyami.kiteffmpeg.sample

import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteffmpeg.Remuxer
import io.github.yuroyami.kiteffmpeg.Transcoder
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import kotlinx.coroutines.runBlocking

internal expect fun writeBytes(path: String, bytes: ByteArray)

/**
 * CLI demo:
 *
 *   no args / info  : prints FFmpeg info + capability probe.
 *   probe <file>    : opens the file, lists streams + their metadata.
 *   transcode <in> <out> [<filter>] [-an | -acopy] [-vt] [--ss <sec>] [--to <sec>]
 *                   : full pipeline: decode → filter → encode → mux.
 *                     Inputs without video transcode audio-only (filter ignored).
 *                     Audio: AAC re-encode by default, `-acopy` copies bit-exact, `-an` drops.
 *                     `-vt` uses the h264_videotoolbox hardware encoder.
 *                     `--ss`/`--to` trim (frame-exact for re-encoded streams).
 *   thumbnail <in> <out.jpg|png> [<atSec>]
 *                   : extract one frame as a compressed image.
 *   remux <in> <out> [--ss <sec>] [--to <sec>]
 *                   : lossless container rewrite (`ffmpeg -c copy`), keyframe-snapped trim.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "info" -> printInfo()
        "probe" -> probe(args.getOrNull(1) ?: usage("probe <file>"))
        "transcode" -> {
            val a = parseArgs(args.drop(1))
            val noAudio = "-an" in a.flags
            val copyAudio = "-acopy" in a.flags
            if (noAudio && copyAudio) usage("transcode: -an and -acopy are mutually exclusive")
            transcode(
                input = a.positional.getOrNull(0) ?: usage("transcode <in> <out> [filter] [-an|-acopy] [-scopy] [-vt] [--ss s] [--to s] [--title t]"),
                output = a.positional.getOrNull(1) ?: usage("transcode <in> <out> [filter] [-an|-acopy] [-scopy] [-vt] [--ss s] [--to s] [--title t]"),
                filter = a.positional.getOrNull(2) ?: "scale=320:180,eq=brightness=0.05",
                audio = if (noAudio) AudioChoice.Drop else if (copyAudio) AudioChoice.Copy else AudioChoice.Encode,
                subtitles = "-scopy" in a.flags,
                useVideoToolbox = "-vt" in a.flags,
                startMicros = a.seconds("--ss")?.let { (it * 1_000_000).toLong() } ?: 0L,
                endMicros = a.seconds("--to")?.let { (it * 1_000_000).toLong() } ?: Long.MAX_VALUE,
                title = a.options["--title"],
            )
        }
        "thumbnail" -> {
            val a = parseArgs(args.drop(1))
            thumbnail(
                input = a.positional.getOrNull(0) ?: usage("thumbnail <in> <out.jpg|png> [atSec]"),
                output = a.positional.getOrNull(1) ?: usage("thumbnail <in> <out.jpg|png> [atSec]"),
                atSeconds = a.positional.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            )
        }
        "remux" -> {
            val a = parseArgs(args.drop(1))
            remux(
                input = a.positional.getOrNull(0) ?: usage("remux <in> <out> [--ss s] [--to s]"),
                output = a.positional.getOrNull(1) ?: usage("remux <in> <out> [--ss s] [--to s]"),
                startMicros = a.seconds("--ss")?.let { (it * 1_000_000).toLong() } ?: 0L,
                endMicros = a.seconds("--to")?.let { (it * 1_000_000).toLong() } ?: Long.MAX_VALUE,
            )
        }
        else -> usage("info | probe <file> | transcode … | thumbnail … | remux …")
    }
}

private enum class AudioChoice { Drop, Encode, Copy }

private class ParsedArgs(val positional: List<String>, val flags: Set<String>, val options: Map<String, String>) {
    fun seconds(key: String): Double? = options[key]?.toDoubleOrNull()
}

/** Split argv into positionals, bare `-flags`, and `--key value` pairs. */
private fun parseArgs(args: List<String>): ParsedArgs {
    val positional = mutableListOf<String>()
    val flags = mutableSetOf<String>()
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg.startsWith("--") && i + 1 < args.size -> { options[arg] = args[i + 1]; i += 1 }
            arg.startsWith("-") -> flags += arg
            else -> positional += arg
        }
        i += 1
    }
    return ParsedArgs(positional, flags, options)
}

private fun usage(text: String): Nothing {
    println("Usage: kiteffmpeg-sample $text")
    kotlin.system.exitProcess(2)
}

private fun printInfo() {
    val v = FFmpeg.versions
    println("KiteFFmpeg, a Kotlin Multiplatform codec library (FFmpeg backend)")
    println("=".repeat(60))
    println("  libavutil ${v.avutil}  libavcodec ${v.avcodec}  libavformat ${v.avformat}")
    println("  libavfilter ${v.avfilter}  libswscale ${v.swscale}  libswresample ${v.swresample}")
    println()
    val encoders = listOf("libx264", "libx265", "libsvtav1", "mpeg4", "mjpeg", "aac", "libopus", "flac", "h264_videotoolbox")
    val filters  = listOf("scale", "eq", "overlay", "amix", "drawtext", "vignette", "atempo")
    println("encoders: " + encoders.joinToString { "$it=${if (FFmpeg.hasEncoder(it)) "✓" else "✗"}" })
    println("filters:  " + filters.joinToString  { "$it=${if (FFmpeg.hasFilter(it))  "✓" else "✗"}" })
    println()
    // Machine-readable, for scripts/e2e.sh: which encoder `transcode` will actually pick against
    // THIS FFmpeg, and the codec_name ffprobe will then report for the output stream. The e2e
    // suite must assert against the linked build, not against whatever CI happened to install.
    val chosen = pickVideoEncoder(preferHardware = false)
    println("KITECODEC_VIDEO_ENCODER=${chosen.name}")
    println("KITECODEC_VIDEO_CODEC=${outputCodecNameFor(chosen)}")
    val chosenAudio = pickAudioEncoder()
    println("KITECODEC_AUDIO_ENCODER=${chosenAudio.name}")
    println("KITECODEC_AUDIO_CODEC=${outputCodecNameFor(chosenAudio)}")
}

/**
 * The video encoder to use, chosen by PROBING the linked FFmpeg instead of assuming one.
 *
 * `libx264` exists only in a GPL build. KiteFFmpeg's default vendored profile is LGPL, where the
 * dependency-free baseline is `mpeg4`. Hard-coding libx264 made this sample, and the e2e suite
 * that drives it, silently require a GPL FFmpeg, which is exactly the flavour the project does
 * NOT ship by default.
 */
internal fun pickVideoEncoder(preferHardware: Boolean): CodecId {
    if (preferHardware) {
        val hw = listOf(CodecId.H264VideoToolbox, CodecId.H264MediaCodec)
            .firstOrNull { FFmpeg.hasEncoder(it.name) }
        return hw ?: error(
            "Hardware encode requested (-vt) but the linked FFmpeg has neither " +
                "h264_videotoolbox nor h264_mediacodec. Drop -vt for a software encoder.",
        )
    }
    val candidates = listOf(CodecId.Libx264, CodecId("mpeg4"), CodecId("libsvtav1"), CodecId.Mjpeg)
    return candidates.firstOrNull { FFmpeg.hasEncoder(it.name) }
        ?: error("The linked FFmpeg has no usable video encoder (tried ${candidates.joinToString { it.name }}).")
}

/**
 * The audio encoder to use, probed rather than assumed, for the reason [pickVideoEncoder] gives.
 *
 * The video side has been probing since the libx264 lesson; audio was left hard-coded to `aac`,
 * and that turned out to be the same bug with a different name. `aac` was enabled only in the
 * Apple and Android profiles, so a Linux or Windows consumer calling `transcode` with default
 * audio settings got `No encoder named 'aac'` (fixed for future builds on 2026-08-24 by moving
 * `aac` into the shared encoder list, but a build made before that still lacks it).
 *
 * Preference order is deliberate: `aac` first because it is what mp4 and mov are normally written
 * with, then the dependency-free fallbacks the shared profile has always guaranteed.
 */
internal fun pickAudioEncoder(): CodecId {
    val candidates = listOf(CodecId.Aac, CodecId.Flac, CodecId.PcmS16)
    return candidates.firstOrNull { FFmpeg.hasEncoder(it.name) }
        ?: error("The linked FFmpeg has no usable audio encoder (tried ${candidates.joinToString { it.name }}).")
}

/** The `codec_name` ffprobe reports for a stream written by [encoder]. */
internal fun outputCodecNameFor(encoder: CodecId): String = when (encoder.name) {
    "libx264", "h264_videotoolbox", "h264_mediacodec" -> "h264"
    "libx265", "hevc_videotoolbox", "hevc_mediacodec" -> "hevc"
    "libsvtav1" -> "av1"
    else -> encoder.name
}

private fun probe(path: String) {
    MediaSource.open(path).use { src ->
        println("File:        $path")
        println("Format:      ${src.formatName}")
        println("Duration:    ${src.durationMicros?.let { formatSeconds(it / 1_000_000.0) } ?: "?"}")
        println("Start time:  ${formatSeconds(src.startTimeMicros / 1_000_000.0)} (raw stream pts include this offset)")
        println("Streams:")
        src.streams.forEach { s ->
            val tag = when (s.type) {
                MediaType.Video -> "${s.video?.width}x${s.video?.height} ${s.video?.frameRate} ${s.video?.pixelFormat?.name}"
                MediaType.Audio -> "${s.audio?.sampleRate}Hz ${s.audio?.channels}ch ${s.audio?.sampleFormat?.name}"
                else -> s.type.name
            }
            val dur = s.durationMicros?.let { " dur=${formatSeconds(it / 1_000_000.0)}" } ?: ""
            println("  [${s.index}] ${s.type} ${s.codec.name}  $tag  tb=${s.timeBase}$dur  bitrate=${s.bitrateBps ?: "?"}")
        }
        if (src.metadata.isNotEmpty()) {
            println("Metadata:")
            src.metadata.forEach { (k, v) -> println("  $k = $v") }
        }
    }
}

private fun transcode(
    input: String,
    output: String,
    filter: String,
    audio: AudioChoice,
    subtitles: Boolean,
    useVideoToolbox: Boolean,
    startMicros: Long,
    endMicros: Long,
    title: String?,
) {
    println("Transcoding $input → $output")
    runBlocking {
        val (videoInfo, frameRate) = MediaSource.open(input).use { src ->
            src.primaryVideo?.video to (src.primaryVideo?.video?.frameRate)
        }

        val spec = if (videoInfo != null) {
            // If the filter contains `scale=W:H`, the encoder must match the filter's output size.
            val scaleMatch = Regex("""scale=(\d+):(\d+)""").find(filter)
            val codec = pickVideoEncoder(preferHardware = useVideoToolbox)
            VideoEncoderSpec(
                codec = codec,
                width = scaleMatch?.groupValues?.get(1)?.toIntOrNull() ?: videoInfo.width,
                height = scaleMatch?.groupValues?.get(2)?.toIntOrNull() ?: videoInfo.height,
                frameRate = frameRate ?: io.github.yuroyami.kiteffmpeg.Rational(30, 1),
                bitrateBps = 1_500_000,
                // allow_sw lets videotoolbox fall back to its software path on machines/VMs
                // without hardware encode sessions (CI runners).
                options = if (codec.name.endsWith("_videotoolbox")) mapOf("allow_sw" to "1") else emptyMap(),
            )
        } else {
            println("  (input has no video, audio-only transcode, filter ignored)")
            null
        }
        val audioNote = when (audio) {
            AudioChoice.Drop -> "  (audio dropped)"
            AudioChoice.Copy -> "  (audio stream-copied)"
            AudioChoice.Encode -> ""
        }
        if (spec != null) println("Filter: $filter$audioNote  codec=${spec.codec.name}")

        Transcoder.transcode(
            input = input,
            output = output,
            spec = spec,
            // An empty filter argument means "no graph at all": decoder frames go straight to the
            // encoder. Passing "" through would hand libavfilter an unparseable description.
            videoFilter = if (spec != null) filter.takeIf { it.isNotBlank() } else null,
            audioSpec = if (audio == AudioChoice.Encode) AudioEncoderSpec(codec = pickAudioEncoder()) else null,
            audioCopy = audio == AudioChoice.Copy,
            subtitleCopy = subtitles,
            startMicros = startMicros,
            endMicros = endMicros,
            metadata = title?.let { mapOf("title" to it) } ?: emptyMap(),
            onProgress = { p ->
                val pct = p.percent?.let { "  ${(it * 100).toInt()}%" } ?: ""
                println("  …${p.framesEncoded} frames, ${formatSeconds(p.outputMicros / 1_000_000.0)}$pct")
            },
        )
    }
    println("✓ Done.")
}

private fun thumbnail(input: String, output: String, atSeconds: Double) {
    println("Extracting frame at ${formatSeconds(atSeconds)} from $input → $output")
    runBlocking {
        MediaSource.open(input).use { src ->
            src.extractFrame((atSeconds * 1_000_000).toLong()).use { frame ->
                val codec = if (output.endsWith(".png", ignoreCase = true)) CodecId.Png else CodecId.Mjpeg
                val bytes = frame.encodeImage(codec)
                writeBytes(output, bytes)
                println("✓ ${bytes.size} bytes (${codec.name}, ${frame.info.width}x${frame.info.height})")
            }
        }
    }
}

private fun remux(input: String, output: String, startMicros: Long, endMicros: Long) {
    println("Remuxing $input → $output (stream copy, no re-encode)")
    runBlocking {
        Remuxer.remux(input, output, startMicros = startMicros, endMicros = endMicros) { packets ->
            println("  …$packets packets")
        }
    }
    println("✓ Done.")
}

private fun formatSeconds(d: Double): String {
    val rounded = (d * 1000 + if (d >= 0) 0.5 else -0.5).toLong()
    val whole = rounded / 1000
    val frac = (if (rounded < 0) -rounded else rounded) % 1000
    return "$whole.${frac.toString().padStart(3, '0')}s"
}
