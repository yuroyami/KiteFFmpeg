@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_copy_bytes
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * The [size] bytes at this pointer, as a new array that C fills in one call. cinterop's readBytes
 * moves one element at a time, which cost half a second for one 1080p frame in a debug build.
 */
internal fun CPointer<*>.toByteArray(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { ffkmp_copy_bytes(it.addressOf(0), this, size) }
    return bytes
}

/** Copies [length] bytes of [source], from [offset], to [destination] in one C call. */
internal fun copyInto(destination: CPointer<*>, source: ByteArray, offset: Int, length: Int) {
    if (length > 0) source.usePinned { ffkmp_copy_bytes(destination, it.addressOf(offset), length) }
}

/** Copies [length] bytes from [source] into [destination], from index 0, in one C call. */
internal fun copyFrom(source: CPointer<*>, destination: ByteArray, length: Int) {
    if (length > 0) destination.usePinned { ffkmp_copy_bytes(it.addressOf(0), source, length) }
}
