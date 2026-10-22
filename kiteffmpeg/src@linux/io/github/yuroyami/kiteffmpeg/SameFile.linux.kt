package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat

// The field widths differ between targets of one family, so each is widened to Long at once.
@OptIn(UnsafeNumber::class)
internal actual fun fileIdentity(path: String): FileIdentity? = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) != 0) null else FileIdentity(info.st_dev.toLong(), info.st_ino.toLong())
}
