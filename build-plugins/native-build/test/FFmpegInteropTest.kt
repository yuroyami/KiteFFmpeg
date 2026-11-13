package io.github.yuroyami.kiteffmpeg.buildtools

import kotlin.test.Test
import kotlin.test.assertEquals

class FFmpegInteropTest {

    @Test
    fun theAndroidDefinitionEmbedsOnlyTheMergedArchive() {
        val template = """
            headers = kitecodec_helpers.h
            staticLibraries = libkitecodec.a libavformat.a libavutil.a
            linkerOpts.android = -lmediandk -landroid -llog -lz
        """.trimIndent()

        val android = FFmpegInterop.replaceStaticLibraries(template, FFmpegInterop.SINGLE_ARCHIVE_NAME)

        assertEquals(listOf("libkiteffmpeg-all.a"), FFmpegInterop.staticLibraries(android))
        assertEquals(template.lines().filterNot { it.startsWith("staticLibraries") }, android.lines().filterNot { it.startsWith("staticLibraries") })
    }

    @Test
    fun aSystemFFmpegKeepsTheLibrariesOfTheTemplateBesideItsOwnFolder() {
        val template = """
            staticLibraries = libkitecodec.a
            linkerOpts.osx = -lavformat -lavutil
        """.trimIndent()

        val osx = FFmpegInterop.withLinkerFlags(template, "linkerOpts.osx", "-L/opt/ffmpeg/lib -rpath /opt/ffmpeg/lib")
        val linux = FFmpegInterop.withLinkerFlags(osx, "linkerOpts.linux", "-L/opt/ffmpeg/lib")

        assertEquals(
            listOf(
                "staticLibraries = libkitecodec.a",
                "linkerOpts.osx = -lavformat -lavutil -L/opt/ffmpeg/lib -rpath /opt/ffmpeg/lib",
                "linkerOpts.linux = -L/opt/ffmpeg/lib",
            ),
            linux.lines().filter { it.isNotBlank() },
        )
    }
}
