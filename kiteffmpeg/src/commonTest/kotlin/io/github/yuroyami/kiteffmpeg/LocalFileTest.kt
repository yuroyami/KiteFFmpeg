package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which addresses name a local file, as FFmpeg's file protocol would open them (#146). */
class LocalFileTest {

    @Test
    fun aBarePathIsTheFileItNames() {
        assertEquals("/tmp/clip.mkv", localFileOf("/tmp/clip.mkv"))
        assertEquals("clip.mkv", localFileOf("clip.mkv"))
        assertEquals("C:\\media\\clip.mkv", localFileOf("C:\\media\\clip.mkv"))
    }

    @Test
    fun theFileProtocolPrefixIsCutOff() {
        assertEquals("/tmp/clip.mkv", localFileOf("file:/tmp/clip.mkv"))
        assertEquals("///tmp/clip.mkv", localFileOf("file:///tmp/clip.mkv"))
    }

    @Test
    fun anyOtherSchemeNamesNoLocalFile() {
        assertNull(localFileOf("https://example.com/clip.mkv"))
        assertNull(localFileOf("fd:3"))
        assertNull(localFileOf("rtsp://camera/live"))
        assertNull(localFileOf(""))
        assertNull(localFileOf("file:"))
    }
}
