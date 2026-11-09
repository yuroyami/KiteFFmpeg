package io.github.yuroyami.kiteffmpeg

/** The errno numbers of this platform's C library, from Darwin's sys/errno.h (#245). */
internal actual val platformErrno: PlatformErrno = PlatformErrno(ENOSYS = 78, ETIMEDOUT = 60, ECONNREFUSED = 61, ECONNRESET = 54)
