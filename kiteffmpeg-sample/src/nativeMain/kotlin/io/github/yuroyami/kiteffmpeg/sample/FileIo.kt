package io.github.yuroyami.kiteffmpeg.sample

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

internal actual fun writeBytes(path: String, bytes: ByteArray) {
    val f = fopen(path, "wb") ?: error("Cannot open $path for writing")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), f)
                check(written.toInt() == bytes.size) { "Short write to $path" }
            }
        }
    } finally {
        fclose(f)
    }
}
