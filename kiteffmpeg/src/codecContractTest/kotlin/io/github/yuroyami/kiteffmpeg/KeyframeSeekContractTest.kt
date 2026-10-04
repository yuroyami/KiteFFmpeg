package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backward keyframe seek lands on a keyframe that shows at or before its target (#155).
 *
 * FFmpeg finds the keyframe by when it decodes. With B-frames a keyframe decodes before it shows,
 * and in an open group of pictures the B-frames after it in decode order show before it, so MP4's
 * reader, which turns the target into a decode time by one constant, took a keyframe that shows
 * after the target. MPEG-TS seeks by byte position, landed on whatever packet sat there, and its
 * first keyframe showed after the target on every seek. A cut then started late and lost the media
 * between its target and that keyframe.
 *
 * The source is six seconds of 10 fps video with two B-frames between references and a keyframe
 * every eight frames, written by this library beside AAC.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class KeyframeSeekContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun source(extension: String): String = path(extension).also { file ->
        MediaSink.open(file).use { sink ->
            val spec = TranscodeFixtures.videoSpec(Rational(10, 1))
            val video = sink.addVideoEncoder(spec.copy(keyframeIntervalFrames = GOP, options = mapOf("bf" to "2")))
            val sound = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId.Aac, sampleRate = RATE, channels = 1))
            runBlocking {
                video.drive((0 until FRAMES).asFlow().map { TranscodeFixtures.texturedPicture(it, ptsMicros = it * 100_000L) })
                sound.drive((0 until FRAMES * RATE / 10 / AAC_FRAME).asFlow().map(::silence))
            }
        }
    }

    private fun silence(block: Int): Frame =
        Frame.ofAudio(ByteArray(AAC_FRAME * 4), AAC_FRAME, RATE, 1, SampleFormat.FltP, ptsMicros = block * AAC_FRAME * 1_000_000L / RATE)

    /**
     * One packet as read, its times in microseconds from the start of the content. Its [dts] is left
     * out of its identity, because a reader that states none, as Matroska's does, has FFmpeg guess
     * one that a seek changes.
     */
    private data class Read(val stream: Int, val pts: Long, val key: Boolean) {
        var dts = Long.MIN_VALUE
    }

    /** Every packet [reader] still holds, in the order it hands them out. */
    private fun rest(reader: PacketReader, start: Long): List<Read> = buildList {
        while (true) {
            val packet = reader.read() ?: break
            packet.use {
                add(Read(it.streamIndex, (it.ptsMicros ?: Long.MIN_VALUE) - start, it.isKeyframe).apply { dts = (it.dtsMicros ?: Long.MIN_VALUE) - start })
            }
        }
    }

    /**
     * Seeks [extension]'s source backward to every 50 ms and checks where each seek lands: on the
     * last keyframe that shows at or before the target, with every packet from there handed out
     * once and in the file's own order. A target before the first picture lands on the first
     * keyframe, there being none earlier.
     */
    private fun assertBackwardSeeksLandOnTheLastKeyframeInTime(extension: String) {
        MediaSource.open(source(extension)).use { source ->
            val video = checkNotNull(source.primaryVideo).index
            val audio = checkNotNull(source.primaryAudio).index
            val start = source.startTimeMicros
            source.openPacketReader(listOf(source.streams[video], source.streams[audio])).use { reader ->
                val whole = rest(reader, start)
                val pictures = whole.filter { it.stream == video }
                val keys = pictures.filter { it.key }
                assertTrue(keys.size > 4, "$extension: the source has keyframes to seek between, $keys")
                assertTrue(keys.drop(1).all { it.dts < it.pts }, "$extension: its keyframes decode before they show, ${keys.map { it.dts to it.pts }}")
                for (target in 0L until (FRAMES - 1) * 100_000L step 50_000L) {
                    reader.seek(target, SeekDirection.Backward)
                    val after = rest(reader, start)
                    val first = after.first { it.stream == video }
                    assertTrue(first.key, "$extension: a seek to $target us landed on a picture that is not a keyframe, $first")
                    if (target < keys.first().pts) {
                        assertEquals(keys.first(), first, "$extension: a seek to $target us, before the first picture, lands on the first keyframe")
                    } else {
                        assertTrue(first.pts <= target, "$extension: a seek to $target us landed on a keyframe that shows at ${first.pts} us")
                        assertEquals(keys.last { it.pts <= target }, first, "$extension: a seek to $target us lands on the last keyframe at or before it")
                    }
                    val from = pictures.indexOf(first)
                    assertEquals(pictures.drop(from), after.filter { it.stream == video }, "$extension: every picture after a seek to $target us, once and in order")
                    val sounds = whole.filter { it.stream == audio }
                    val heard = after.filter { it.stream == audio }
                    val heardFrom = sounds.indexOf(heard.first())
                    assertEquals(sounds.drop(heardFrom), heard, "$extension: every sound after a seek to $target us, once and in order")
                }
            }
        }
    }

    @Test
    fun aBackwardSeekInMp4LandsOnTheLastKeyframeAtOrBeforeItsTarget() = assertBackwardSeeksLandOnTheLastKeyframeInTime("mp4")

    /** Matroska seeks by when its keyframes show, so it landed right before the fix and must still. */
    @Test
    fun aBackwardSeekInMatroskaLandsOnTheLastKeyframeAtOrBeforeItsTarget() = assertBackwardSeeksLandOnTheLastKeyframeInTime("mkv")

    /** MPEG-TS has no index, so its seek searches byte positions, which hold no keyframe of their own. */
    @Test
    fun aBackwardSeekInMpegTsLandsOnTheLastKeyframeAtOrBeforeItsTarget() = assertBackwardSeeksLandOnTheLastKeyframeInTime("ts")

    /**
     * Seeks [extension]'s source to every 50 ms twice, once with its sound selected and once
     * selecting it only after the seek, and checks that both hand out the same packets. The seek
     * reads ahead to check its keyframe, and what it reads belongs to the streams selected then.
     */
    private fun assertAStreamSelectedAfterASeekStartsWhereItWouldHave(extension: String) {
        MediaSource.open(source(extension)).use { source ->
            val video = source.streams[checkNotNull(source.primaryVideo).index]
            val audio = source.streams[checkNotNull(source.primaryAudio).index]
            val start = source.startTimeMicros
            source.openPacketReader(listOf(video, audio)).use { reader ->
                for (target in 0L until (FRAMES - 1) * 100_000L step 50_000L) {
                    reader.reselect(listOf(video, audio))
                    reader.seek(target, SeekDirection.Backward)
                    val selected = rest(reader, start)
                    reader.reselect(listOf(video))
                    reader.seek(target, SeekDirection.Backward)
                    reader.reselect(listOf(video, audio))
                    val selectedAfter = rest(reader, start)
                    assertEquals(
                        selected.filter { it.stream == audio.index },
                        selectedAfter.filter { it.stream == audio.index },
                        "$extension: the sound selected after a seek to $target us",
                    )
                    assertEquals(
                        selected.filter { it.stream == video.index },
                        selectedAfter.filter { it.stream == video.index },
                        "$extension: the pictures with the sound selected after a seek to $target us",
                    )
                }
            }
        }
    }

    @Test
    fun aStreamSelectedAfterASeekInMp4StartsWhereItWouldHave() = assertAStreamSelectedAfterASeekStartsWhereItWouldHave("mp4")

    @Test
    fun aStreamSelectedAfterASeekInMpegTsStartsWhereItWouldHave() = assertAStreamSelectedAfterASeekStartsWhereItWouldHave("ts")

    /** The pictures of [path]'s video, as their times in microseconds from the start of the content, in decode order. */
    private fun pictures(path: String): List<Read> = MediaSource.open(path).use { source ->
        val video = checkNotNull(source.primaryVideo)
        source.openPacketReader(listOf(video)).use { rest(it, source.startTimeMicros) }
    }

    private fun decodedPictureCount(path: String): Int = MediaSource.open(path).use { source ->
        val frames = runBlocking { source.decodedFrames(checkNotNull(source.primaryVideo)).toList() }
        frames.forEach(Frame::close)
        frames.size
    }

    /**
     * A cut just before a keyframe shows starts on the keyframe before it and keeps every picture
     * from its target on. It used to start on that later keyframe and lose the pictures between.
     */
    private fun assertCutsKeepTheirStart(extension: String) {
        val input = source(extension)
        val pictures = pictures(input)
        val keys = pictures.filter { it.key }
        for (key in keys.drop(1)) {
            val target = key.pts - 50_000L
            val output = path("mkv")
            runBlocking { Remuxer.remux(input, output, startMicros = target) }
            val shown = decodedPictureCount(output)
            val wanted = pictures.count { it.pts >= target }
            assertTrue(shown >= wanted, "$extension: a cut at $target us shows $shown pictures, and $wanted show from there on")
            val landing = keys.last { it.pts <= target }
            assertEquals(pictures.count { it.pts >= landing.pts }, shown, "$extension: a cut at $target us starts on the keyframe at ${landing.pts} us")
        }
    }

    @Test
    fun aCutOfMp4JustBeforeAKeyframeKeepsItsStart() = assertCutsKeepTheirStart("mp4")

    @Test
    fun aCutOfMpegTsJustBeforeAKeyframeKeepsItsStart() = assertCutsKeepTheirStart("ts")

    private companion object {
        const val FRAMES = 60
        const val GOP = 8
        const val RATE = 48_000
        const val AAC_FRAME = 1024
    }
}
