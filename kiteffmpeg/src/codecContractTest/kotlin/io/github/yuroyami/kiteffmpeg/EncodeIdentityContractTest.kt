package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An encode keeps what describes the picture and the sound: the colour, the HDR metadata, the
 * shape of a pixel and the exact channel layout.
 *
 * `ffmpeg` writes the fixtures and `ffprobe` reads the outputs, so the test is skipped where there
 * is no command-line oracle, which is an Android device. The HDR source is HEVC from libx265,
 * which declares its mastering display and content light level only in the bitstream, the way a
 * UHD Blu-ray or a broadcast stream does. A host `ffmpeg` without libx265 skips those cases.
 */
internal class EncodeIdentityContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        /** x265's spelling: chromaticities in 1/50000, luminance in 1/10000 candela per square metre. */
        const val MASTER_DISPLAY = "G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)"

        val EXPECTED_HDR = HdrMetadata(
            masteringDisplay = MasteringDisplay(
                primaries = DisplayPrimaries(
                    redX = Rational(34000, 50000), redY = Rational(16000, 50000),
                    greenX = Rational(13250, 50000), greenY = Rational(34500, 50000),
                    blueX = Rational(7500, 50000), blueY = Rational(3000, 50000),
                    whiteX = Rational(15635, 50000), whiteY = Rational(16450, 50000),
                ),
                luminance = LuminanceRange(min = Rational(1, 10000), max = Rational(10000000, 10000)),
            ),
            contentLight = ContentLightLevel(maxCll = 1000, maxFall = 400),
        )

        val MPEG4 = VideoEncoderSpec(
            codec = CodecId("mpeg4"),
            width = 160,
            height = 120,
            pixelFormat = PixelFormat.Yuv420p,
            frameRate = Rational(25, 1),
        )
    }

    /** True when the host `ffmpeg` has [encoder]; false where there is no oracle at all. */
    private fun oracleHas(encoder: String): Boolean =
        runMediaOracle("ffmpeg", listOf("-hide_banner", "-encoders"))
            ?.lineSequence()?.any { line -> line.split(' ').filter { it.isNotEmpty() }.getOrNull(1) == encoder } == true

    /** ffprobe's flat description of the first stream of [kind] in [file], or null without an oracle. */
    private fun probe(file: String, kind: String, entries: String): Map<String, String>? = runMediaOracle(
        "ffprobe",
        listOf("-v", "error", "-select_streams", "$kind:0", "-show_entries", entries, "-of", "flat", file),
    )?.lineSequence()
        ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        ?.associate { (key, value) -> key.substringAfter("streams.stream.0.") to value.trim('"') }

    private fun videoTags(file: String): Map<String, String>? =
        probe(file, "v", "stream=color_range,color_space,color_transfer,color_primaries,sample_aspect_ratio:stream_side_data")

    /** The side data fields of the entry whose type is [type], keyed without their list prefix. */
    private fun sideData(tags: Map<String, String>, type: String): Map<String, String>? {
        val prefix = tags.entries.firstOrNull { it.key.endsWith(".side_data_type") && it.value == type }
            ?.key?.removeSuffix("side_data_type") ?: return null
        return tags.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    private fun assertRatio(expected: Rational, actual: String?, what: String) {
        assertNotNull(actual, "$what is missing")
        val (num, den) = actual.split('/').map { it.toDouble() }
        assertTrue(abs(num / den - expected.asDouble) < 1e-4, "$what is $actual, expected $expected")
    }

    private fun assertHdrWritten(tags: Map<String, String>) {
        assertEquals("bt2020", tags["color_primaries"], "primaries: $tags")
        assertEquals("smpte2084", tags["color_transfer"], "transfer: $tags")
        assertEquals("bt2020nc", tags["color_space"], "matrix: $tags")
        assertEquals("tv", tags["color_range"], "range: $tags")
        val display = assertNotNull(sideData(tags, "Mastering display metadata"), "no mastering display: $tags")
        val primaries = EXPECTED_HDR.masteringDisplay!!.primaries!!
        assertRatio(primaries.redX, display["red_x"], "red x")
        assertRatio(primaries.greenY, display["green_y"], "green y")
        assertRatio(primaries.blueX, display["blue_x"], "blue x")
        assertRatio(primaries.whiteY, display["white_point_y"], "white y")
        assertRatio(EXPECTED_HDR.masteringDisplay.luminance!!.max, display["max_luminance"], "max luminance")
        assertRatio(EXPECTED_HDR.masteringDisplay.luminance.min, display["min_luminance"], "min luminance")
        val light = assertNotNull(sideData(tags, "Content light level metadata"), "no content light level: $tags")
        assertEquals("1000", light["max_content"])
        assertEquals("400", light["max_average"])
    }

    /** Two seconds of HDR10 HEVC whose mastering display and light level live only in the bitstream. */
    private fun hdrSource(): String? {
        if (!oracleHas("libx265")) return null
        val output = path("mkv")
        // The colour goes on the frames: ffmpeg takes an encoder's primaries and transfer from its
        // first frame, so the -color_primaries flag alone leaves both unset in the file.
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=2",
                "-vf", "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv",
                "-c:v", "libx265", "-pix_fmt", "yuv420p10le",
                "-x265-params", "log-level=error:hdr10=1:master-display=$MASTER_DISPLAY:max-cll=1000,400",
            ),
            output,
        )
        if (!written) return null
        val tags = videoTags(output) ?: return null
        assertEquals("bt2020", tags["color_primaries"], "the fixture does not declare its colour, so the test proves nothing: $tags")
        assertEquals("smpte2084", tags["color_transfer"], "the fixture does not declare its colour, so the test proves nothing: $tags")
        return output
    }

    @Test
    fun aDecodedHdrFrameCarriesItsMasteringDisplayAndLightLevel() {
        val input = hdrSource() ?: return println("encode identity contract degraded: no ffmpeg with libx265")
        MediaSource.open(input).use { source ->
            val video = source.primaryVideo!!
            runBlocking {
                source.decodedFrames(video).collectFirst { frame -> assertEquals(EXPECTED_HDR, frame.info.hdr) }
            }
        }
    }

    @Test
    fun anHdrSourceKeepsItsColourAndHdrMetadataThroughAnEncode() {
        val input = hdrSource() ?: return println("encode identity contract degraded: no ffmpeg with libx265")
        val output = path("mkv")
        runBlocking { Transcoder.transcode(input = input, output = output, spec = MPEG4) }
        assertHdrWritten(videoTags(output) ?: return)
        MediaSource.open(output).use { encoded ->
            assertEquals(EXPECTED_HDR, encoded.primaryVideo!!.video!!.hdr, "the container declares the HDR metadata now")
        }
    }

    @Test
    fun aRemuxKeepsTheColourAndHdrMetadataOfTheContainer() {
        val input = hdrSource() ?: return println("encode identity contract degraded: no ffmpeg with libx265")
        // The encode moves the HDR metadata from the bitstream into the container, where a remux
        // has to carry it as stream side data.
        val encoded = path("mkv")
        runBlocking { Transcoder.transcode(input = input, output = encoded, spec = MPEG4) }
        val remuxed = path("mkv")
        runBlocking { Remuxer.remux(input = encoded, output = remuxed) }
        assertHdrWritten(videoTags(remuxed) ?: return)
    }

    @Test
    fun aFilterThatChangesTheColourDecidesWhatTheOutputDeclares() {
        val input = hdrSource() ?: return println("encode identity contract degraded: no ffmpeg with libx265")
        val output = path("mkv")
        val filter = "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709"
        runBlocking { Transcoder.transcode(input = input, output = output, spec = MPEG4, videoFilter = filter) }
        val tags = videoTags(output) ?: return
        assertEquals("bt709", tags["color_primaries"], "the filter's colour, not the source's: $tags")
        assertEquals("bt709", tags["color_transfer"], "the filter's colour, not the source's: $tags")
        assertEquals("bt709", tags["color_space"], "the filter's colour, not the source's: $tags")
    }

    @Test
    fun aCallerCanDeclareNoColourAndNoHdrMetadata() {
        val input = hdrSource() ?: return println("encode identity contract degraded: no ffmpeg with libx265")
        val output = path("mkv")
        val bare = MPEG4.copy(color = ColorInfo.Unspecified, hdr = HdrMetadata())
        runBlocking { Transcoder.transcode(input = input, output = output, spec = bare) }
        val tags = videoTags(output) ?: return
        assertEquals(null, tags["color_transfer"]?.takeUnless { it == "unknown" }, "no transfer was asked for: $tags")
        assertNull(sideData(tags, "Mastering display metadata"), "no mastering display was asked for: $tags")
    }

    @Test
    fun aMediaSinkWritesTheColourAndHdrMetadataItIsGiven() {
        if (!oracleHas("mpeg4")) return println("encode identity contract degraded: no ffmpeg")
        val output = path("mkv")
        val spec = MPEG4.copy(
            color = ColorInfo(
                matrix = ColorMatrix.Bt2020Ncl,
                primaries = ColorPrimaries.Bt2020,
                transfer = ColorTransfer.SmpteSt2084,
                rangeSpecified = true,
            ),
            hdr = EXPECTED_HDR,
        )
        MediaSink.open(output).use { sink ->
            val encoder = sink.addVideoEncoder(spec)
            runBlocking {
                encoder.drive(
                    (0 until 5).asFlow().map { index ->
                        Frame.ofVideo(ByteArray(160 * 120 * 3 / 2), 160, 120, PixelFormat.Yuv420p, index * 40_000L)
                    },
                )
            }
        }
        assertHdrWritten(videoTags(output) ?: return)
    }

    /** Two seconds of mpeg4 video whose pixels are 4:3 wide, in Matroska. */
    private fun anamorphicSource(): String? {
        val output = path("mkv")
        val written = MediaOracle.generate(
            listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=2,setsar=4/3", "-c:v", "mpeg4"),
            output,
        )
        return output.takeIf { written }
    }

    @Test
    fun anEncodeKeepsTheShapeOfAPixel() {
        val input = anamorphicSource() ?: return println("encode identity contract degraded: no ffmpeg")
        val output = path("mkv")
        runBlocking { Transcoder.transcode(input = input, output = output, spec = MPEG4) }
        assertEquals("4:3", videoTags(output)?.get("sample_aspect_ratio") ?: return)
    }

    @Test
    fun aFilterThatReshapesThePixelsDecidesTheOutputsShape() {
        val input = anamorphicSource() ?: return println("encode identity contract degraded: no ffmpeg")
        val output = path("mkv")
        // Half the width at the same display shape: each pixel becomes twice as wide, 8:3.
        runBlocking {
            Transcoder.transcode(input = input, output = output, spec = MPEG4.copy(width = 80), videoFilter = "scale=80:120")
        }
        assertEquals("8:3", videoTags(output)?.get("sample_aspect_ratio") ?: return)
    }

    @Test
    fun aRemuxKeepsTheShapeOfAPixel() {
        val input = anamorphicSource() ?: return println("encode identity contract degraded: no ffmpeg")
        val output = path("mkv")
        runBlocking { Remuxer.remux(input = input, output = output) }
        assertEquals("4:3", videoTags(output)?.get("sample_aspect_ratio") ?: return)
    }

    /**
     * Two seconds of 5.1 with side surrounds. Six channels alone mean back surrounds to FFmpeg, so
     * a side layout survives only if it is carried. WAV, because its header records which speaker
     * each channel belongs to and Matroska's does not.
     */
    private fun sideSurroundSource(): String? {
        val output = path("wav")
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-af", "pan=FL+FR+FC+LFE+SL+SR|FL=c0|FR=c0|FC=c0|LFE=c0|SL=c0|SR=c0",
                "-c:a", "pcm_s16le",
            ),
            output,
        )
        return output.takeIf { written }
    }

    private fun layoutOf(file: String): String? = probe(file, "a", "stream=channel_layout")?.get("channel_layout")

    @Test
    fun anEncodeKeepsTheExactChannelLayout() {
        val input = sideSurroundSource() ?: return println("encode identity contract degraded: no ffmpeg")
        assertEquals("5.1(side)", layoutOf(input) ?: return)
        val output = path("wav")
        val spec = AudioEncoderSpec(codec = CodecId.PcmS16, sampleRate = 44_100, channels = 6)
        runBlocking { Transcoder.transcode(input = input, output = output, audioSpec = spec) }
        assertEquals("5.1(side)", layoutOf(output))
    }

    @Test
    fun aDeclaredChannelLayoutWinsOverTheSources() {
        val input = sideSurroundSource() ?: return println("encode identity contract degraded: no ffmpeg")
        val output = path("wav")
        val back51 = 0x3FL
        val spec = AudioEncoderSpec(codec = CodecId.PcmS16, sampleRate = 44_100, channels = 6, channelLayoutMask = back51)
        runBlocking { Transcoder.transcode(input = input, output = output, audioSpec = spec) }
        // FFmpeg names back surrounds plain 5.1.
        assertEquals("5.1", layoutOf(output) ?: return)
    }
}

/**
 * Runs [check] on the first frame of this flow and closes it. `firstOrNull` stops the flow before
 * it emits another frame, where `takeWhile` would take the next one and drop it unclosed.
 */
private suspend fun Flow<Frame>.collectFirst(check: (Frame) -> Unit) {
    firstOrNull { frame ->
        frame.use(check)
        true
    }
}

/** The default layout table [defaultLayoutMask] mirrors, checked against FFmpeg itself. */
internal class DefaultLayoutContractTest {
    @Test
    fun theDefaultLayoutForEachCountIsFfmpegsOwn() {
        for (channels in 1..8) {
            Frame.ofAudio(ByteArray(64 * channels * 4), 64, 48_000, channels, SampleFormat.FltP, 0L).use { frame ->
                assertEquals(frame.info.channelLayoutMask, defaultLayoutMask(channels), "the default for $channels channels")
            }
        }
    }
}
