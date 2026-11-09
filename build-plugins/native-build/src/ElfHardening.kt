package io.github.yuroyami.kiteffmpeg.buildtools

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a 64-bit little-endian ELF shared object says about its hardening (#224): a RELRO segment,
 * immediate binding, and a reference to the stack protector. The publish reads it from each
 * Linux JNI library it ships, because a flag that a build stops passing changes nothing else a
 * test can see.
 */
data class ElfHardening(val relro: Boolean, val bindNow: Boolean, val stackProtector: Boolean) {

    /** What is missing, in words, or empty when the library has all three. */
    val missing: List<String>
        get() = buildList {
            if (!relro) add("a RELRO segment (-z relro)")
            if (!bindNow) add("immediate binding (-z now)")
            if (!stackProtector) add("the stack protector (-fstack-protector-strong)")
        }

    companion object {
        private const val PT_DYNAMIC = 2
        private const val PT_GNU_RELRO = 0x6474e552
        private const val DT_NULL = 0L
        private const val DT_FLAGS = 30L
        private const val DT_FLAGS_1 = 0x6ffffffbL
        private const val DF_BIND_NOW = 0x8L
        private const val DF_1_NOW = 0x1L

        /** Reads [bytes], or throws [BuildFailure] when they are not a 64-bit little-endian ELF. */
        fun read(bytes: ByteArray): ElfHardening {
            val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
            if (!magic || bytes[4].toInt() != 2 || bytes[5].toInt() != 1) {
                throw BuildFailure("not a 64-bit little-endian ELF file")
            }
            val programHeaders = elf.getLong(0x20)
            val entrySize = elf.getShort(0x36).toInt() and 0xffff
            val count = elf.getShort(0x38).toInt() and 0xffff
            var relro = false
            var bindNow = false
            for (index in 0 until count) {
                val header = (programHeaders + index.toLong() * entrySize).toInt()
                when (elf.getInt(header)) {
                    PT_GNU_RELRO -> relro = true
                    PT_DYNAMIC -> {
                        val offset = elf.getLong(header + 0x08).toInt()
                        val size = elf.getLong(header + 0x20).toInt()
                        var entry = offset
                        while (entry + 16 <= offset + size) {
                            val tag = elf.getLong(entry)
                            val value = elf.getLong(entry + 8)
                            if (tag == DT_NULL) break
                            if (tag == DT_FLAGS && value and DF_BIND_NOW != 0L) bindNow = true
                            if (tag == DT_FLAGS_1 && value and DF_1_NOW != 0L) bindNow = true
                            entry += 16
                        }
                    }
                }
            }
            return ElfHardening(relro, bindNow, stackProtector = contains(bytes, "__stack_chk_fail".toByteArray()))
        }

        private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
            outer@ for (start in 0..haystack.size - needle.size) {
                for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) continue@outer
                return true
            }
            return false
        }
    }
}
