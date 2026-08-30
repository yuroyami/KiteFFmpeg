package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildFFmpegWasmTaskTest {

    private fun task() = ProjectBuilder.builder().build().tasks
        .create("ffmpegWasm", BuildFFmpegWasmTask::class.java)

    private fun args(variant: String) = task().configureArgs(variant, Path.of("/tmp/prefix"))

    /**
     * The regression this file exists for.
     *
     * The web spike's recipe disabled avfilter, because its bare harness never opened a filter
     * graph. `libkitecodec.a` does: `helpers_filter.c` looks up these five filters BY NAME and
     * calls `avfilter_graph_parse_ptr`. A build without them links and then fails at the first
     * filter call, which is the worst place to find out.
     */
    @Test
    fun everyFilterTheCLibraryNamesIsEnabled() {
        val enabled = args("base").single { it.startsWith("--enable-filter=") }
            .removePrefix("--enable-filter=").split(",").toSet()
        listOf("abuffer", "abuffersink", "anull", "buffer", "buffersink").forEach { filter ->
            assertTrue(filter in enabled, "helpers_filter.c names '$filter' but the build disables it")
        }
        assertTrue(args("base").contains("--enable-avfilter"))
        assertFalse(args("base").contains("--disable-avfilter"))
    }

    /** n8.0 has no `--disable-postproc`, and passing it makes configure exit non-zero. */
    @Test
    fun noArgumentThatFFmpegN8DoesNotHave() {
        BuildFFmpegWasmTask.VARIANTS.forEach { variant ->
            assertFalse(
                args(variant).any { it == "--disable-postproc" },
                "--disable-postproc does not exist on n8.0 and configure rejects it",
            )
        }
    }

    /**
     * `--disable-asm` turns SIMD off with it, so the SIMD variant must not pass it. Without this
     * the simd build silently produces the base build and its measurement is a lie.
     */
    @Test
    fun theSimdVariantDoesNotDisableAsmAndTheOthersDo() {
        assertFalse("--disable-asm" in args("simd"))
        assertTrue("-O3 -msimd128" in args("simd").single { it.startsWith("--extra-cflags=") })
        assertTrue("--disable-asm" in args("base"))
        assertTrue("--disable-asm" in args("mt"))
    }

    /** Threads are opt-in: the default artifact must not need cross-origin isolation to run. */
    @Test
    fun onlyTheThreadedVariantEnablesPthreads() {
        assertTrue("--disable-pthreads" in args("base"))
        assertTrue("--disable-pthreads" in args("simd"))
        assertTrue("--enable-pthreads" in args("mt"))
        assertFalse("--disable-pthreads" in args("mt"))
    }

    /**
     * What each MustPlay row actually CONTAINS, as `ffprobe` reports it, not as memory recalls it.
     *
     * A row is listed here with every codec it needs, so adding a codec to the web tier can never
     * again mean "the one somebody happened to think of". The missing opus decoder is why this shape exists: the old
     * version asserted a flat hand-written list, opus was never on it, and the tier decoded the
     * picture of `vp9.webm` and `av1.mkv` while dropping their sound with no test going red.
     */
    private val matrixRowCodecs: Map<String, List<String>> = mapOf(
        "sync1080p30.mp4" to listOf("h264", "aac"),
        "baseline.mkv" to listOf("h264", "aac"),
        "vp9.webm" to listOf("vp9", "opus"),
        "av1.mkv" to listOf("av1", "opus"),
        "hevc4k10.mp4" to listOf("hevc"),
        "audio-aac.m4a" to listOf("aac"),
        "audio-mp3.mp3" to listOf("mp3"),
        "audio-flac.flac" to listOf("flac"),
        "surround51.mp4" to listOf("aac"),
    )

    /**
     * Codecs a MustPlay row needs that this tier KNOWINGLY does not carry, each with its reason.
     *
     * Written down rather than omitted: a gap nobody can see in the build is a gap that gets
     * rediscovered by a user. Silencing a NEW gap means editing this set and its comment, which is
     * a decision with a name on it rather than a list that quietly never mentioned the codec.
     */
    private val knownAbsent: Map<String, String> = mapOf(
        "av1" to "no dav1d for wasm (it wants pthreads, and the default artifact must run without " +
            "cross-origin isolation); av1.mkv is a MustPlay row that this tier cannot serve",
    )

    /**
     * The web tier must serve the 17.5 matrix rows it claims, and this pins the REASON rather than
     * a list somebody maintains by hand.
     *
     * An earlier version asserted the demuxer string equalled `mov,matroska` exactly. It was right
     * to fail when that changed, but it pinned the wrong thing: what matters is that every codec a
     * MustPlay row needs is reachable, not that the string never moves.
     */
    @Test
    fun everyCodecTheMatrixRequiresIsReachable() {
        val decoders = args("base").single { it.startsWith("--enable-decoder=") }
            .substringAfter("=").split(",").toSet()

        matrixRowCodecs.forEach { (row, codecs) ->
            codecs.forEach { codec ->
                val absentOnPurpose = knownAbsent[codec]
                if (absentOnPurpose == null) {
                    assertTrue(codec in decoders, "$codec missing: the MustPlay row $row needs it")
                } else {
                    assertFalse(
                        codec in decoders,
                        "$codec is enabled but still listed as knownAbsent; delete its entry, " +
                            "because a stale exemption hides the next real gap",
                    )
                }
            }
        }
    }

    /**
     * A decoder with no demuxer is a codec nobody can reach, which is exactly how audio-mp3.mp3
     * and audio-flac.flac failed at open with -29 before this was fixed.
     */
    @Test
    fun everyMatrixRowHasSomethingThatCanOpenIt() {
        val demuxers = args("base").single { it.startsWith("--enable-demuxer=") }
            .substringAfter("=").split(",").toSet()
        listOf("mov", "matroska", "mp3", "flac").forEach {
            assertTrue(it in demuxers, "$it demuxer missing: its MustPlay row cannot be opened")
        }
    }

    /**
     * A decoder the tier carries with no parser to feed it is the silent half of the same bug, and
     * webm's audio is where it bites: matroska hands opus packets to a decoder that needs framing.
     */
    @Test
    fun everyAudioDecoderTheWebTierCarriesHasItsParser() {
        val parsers = args("base").single { it.startsWith("--enable-parser=") }
            .substringAfter("=").split(",").toSet()
        listOf("opus", "vorbis", "aac", "flac").forEach {
            assertTrue(it in parsers, "$it parser missing: its decoder cannot be framed")
        }
    }

    @Test
    fun anUnknownVariantIsRefusedRatherThanTreatedAsBase() {
        val task = task()
        task.sourceRef.set("n8.0")
        task.variant.set("turbo")
        assertFailsWith<IllegalArgumentException> { task.run() }
    }
}
