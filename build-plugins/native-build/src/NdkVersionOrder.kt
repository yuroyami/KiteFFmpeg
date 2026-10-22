package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File

/**
 * Orders Android NDK folder names such as `29.0.14206865` by their numbers, so `29.10` is newer
 * than `29.2`. A plain string sort gets that pair wrong and silently picks the older NDK.
 */
object NdkVersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(index) ?: return -1
            val y = right.getOrNull(index) ?: return 1
            val order = comparePart(x, y)
            if (order != 0) return order
        }
        return 0
    }

    private fun comparePart(x: String, y: String): Int {
        val xDigits = x.takeWhile(Char::isDigit)
        val yDigits = y.takeWhile(Char::isDigit)
        val byNumber = compareValues(xDigits.toBigIntegerOrNull(), yDigits.toBigIntegerOrNull())
        if (byNumber != 0) return byNumber
        val xRest = x.drop(xDigits.length)
        val yRest = y.drop(yDigits.length)
        return when {
            xRest == yRest -> 0
            // A release sorts above a suffixed build of the same number, such as a release candidate.
            xRest.isEmpty() -> 1
            yRest.isEmpty() -> -1
            else -> xRest.compareTo(yRest)
        }
    }
}

/**
 * The newest NDK installed side by side under [ndkRoot], or null when there is none. Only folders
 * count: a macOS Finder alias beside them is an ordinary file.
 */
fun newestNdk(ndkRoot: File): File? =
    ndkRoot.listFiles { file: File -> file.isDirectory }?.maxWithOrNull(compareBy(NdkVersionOrder) { it.name })
