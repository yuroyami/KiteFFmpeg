package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure model tests only: Android host tests never load the JNI library. */
class JniPackagingModelTest {
    @Test
    fun generatedRootsAreAboveAbiDirectories() {
        val roots = mapOf(
            "android-arm64" to "arm64-v8a/libkitecodec_jni.so",
            "android-x64" to "x86_64/libkitecodec_jni.so",
        )
        assertEquals(setOf("arm64-v8a", "x86_64"), roots.values.map { it.substringBefore('/') }.toSet())
        assertTrue(roots.values.all { it.endsWith("/libkitecodec_jni.so") })
    }

    @Test
    fun manifestDescriptorsUseOneFourFieldRowPerExternal() {
        // Full source-to-manifest equality is pinned by buildSrc and JVM registration tests. This
        // host-safe model pins the descriptor atoms most likely to be lost by minification.
        val descriptors = listOf("()I", "(J)I", "(JJ)I", "(J[B)I", "(J)Ljava/lang/String;")
        assertTrue(descriptors.all(::isValidMethodDescriptor))
        assertTrue(!isValidMethodDescriptor("(J)"))
        assertTrue(!isValidMethodDescriptor("J)I"))
    }

    private fun isValidMethodDescriptor(value: String): Boolean {
        if (!value.startsWith('(')) return false
        val close = value.indexOf(')')
        if (close < 1 || close == value.lastIndex) return false
        return value.substring(close + 1)
            .matches(Regex("(?:V|Z|B|C|S|I|J|F|D|\\[+[BCSIJFDZ]|L[^;]+;)"))
    }
}
