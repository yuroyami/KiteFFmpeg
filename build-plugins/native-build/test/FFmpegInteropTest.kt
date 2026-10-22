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
}
