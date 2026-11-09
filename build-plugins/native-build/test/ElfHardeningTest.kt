package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The publish's reading of a Linux JNI library's hardening (#224). */
class ElfHardeningTest {

    /** A minimal ELF64 with a RELRO header when asked, a dynamic section, and a string. */
    private fun elf(relro: Boolean, flags: Long, flags1: Long, strings: String): ByteArray {
        val headers = if (relro) 2 else 1
        val dynamicOffset = 64 + headers * 56
        val stringsOffset = dynamicOffset + 3 * 16
        val bytes = ByteArray(stringsOffset + strings.length)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1
        b.putLong(0x20, 64L)
        b.putShort(0x36, 56)
        b.putShort(0x38, headers.toShort())
        b.putInt(64, 2)
        b.putLong(64 + 0x08, dynamicOffset.toLong())
        b.putLong(64 + 0x20, 3L * 16)
        if (relro) b.putInt(64 + 56, 0x6474e552)
        b.putLong(dynamicOffset, 30L); b.putLong(dynamicOffset + 8, flags)
        b.putLong(dynamicOffset + 16, 0x6ffffffbL); b.putLong(dynamicOffset + 24, flags1)
        strings.toByteArray().copyInto(bytes, stringsOffset)
        return bytes
    }

    @Test
    fun aHardenedLibraryReadsAsHardened() {
        assertEquals(ElfHardening(true, true, true), ElfHardening.read(elf(true, 0x8, 0, "__stack_chk_fail")))
        assertEquals(ElfHardening(true, true, true), ElfHardening.read(elf(true, 0, 0x1, "__stack_chk_fail")))
    }

    @Test
    fun eachMissingPartIsNamed() {
        val bare = ElfHardening.read(elf(false, 0, 0, "memcpy"))
        assertEquals(ElfHardening(false, false, false), bare)
        assertEquals(3, bare.missing.size)
    }

    @Test
    fun somethingElseIsRefused() {
        assertFailsWith<BuildFailure> { ElfHardening.read(ByteArray(80)) }
    }

    /** The library this machine last linked, when there is one. */
    @Test
    fun theLocallyLinkedLinuxLibraryIsHardened() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "build-plugins").isDirectory }
            ?: return println("elf hardening: no repository root above the test")
        val library = File(root, "build/tasks/_kiteffmpeg_jniLinuxX64@kiteffmpeg-native/link/lib/libkitecodec_jni.so")
        if (!library.isFile) return println("elf hardening: no local linux-x64 library to read")
        assertEquals(emptyList(), ElfHardening.read(library.readBytes()).missing)
    }
}
