package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Only the processors and systems a bundle exists for map to one (#266). */
class JniPlatformDirectoryTest {
    @Test
    fun theShippedPlatformsMapToTheirBundles() {
        assertEquals("linux-x64", JniLibrary.platformDirectoryFor("Linux", "amd64"))
        assertEquals("linux-x64", JniLibrary.platformDirectoryFor("Linux", "x86_64"))
        assertEquals("linux-arm64", JniLibrary.platformDirectoryFor("Linux", "aarch64"))
        assertEquals("macos-arm64", JniLibrary.platformDirectoryFor("Mac OS X", "aarch64"))
        assertEquals("macos-x64", JniLibrary.platformDirectoryFor("Mac OS X", "x86_64"))
        assertEquals("windows-x64", JniLibrary.platformDirectoryFor("Windows 11", "amd64"))
        assertEquals("windows-arm64", JniLibrary.platformDirectoryFor("Windows 11", "aarch64"))
    }

    @Test
    fun anUnknownProcessorMapsToNoBundle() {
        for (arch in listOf("riscv64", "arm", "armv7l", "x86", "i386", "ppc64le", "s390x", "")) {
            assertNull(JniLibrary.platformDirectoryFor("Linux", arch), arch)
        }
    }

    @Test
    fun anUnknownSystemMapsToNoBundle() {
        assertNull(JniLibrary.platformDirectoryFor("FreeBSD", "amd64"))
        assertNull(JniLibrary.platformDirectoryFor("", "amd64"))
    }
}
