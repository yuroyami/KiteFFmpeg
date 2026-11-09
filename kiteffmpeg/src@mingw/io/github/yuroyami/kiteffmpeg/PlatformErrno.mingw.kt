package io.github.yuroyami.kiteffmpeg

/** The errno numbers of this platform's C library, from mingw-w64's errno.h (#245). */
internal actual val platformErrno: PlatformErrno = PlatformErrno(ENOSYS = 40, ETIMEDOUT = 138, ECONNREFUSED = 107, ECONNRESET = 108)
