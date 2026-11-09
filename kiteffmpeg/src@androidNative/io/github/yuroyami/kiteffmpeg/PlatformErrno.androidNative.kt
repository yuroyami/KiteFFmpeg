package io.github.yuroyami.kiteffmpeg

/** The errno numbers of this platform's C library, from Linux's asm-generic/errno.h (#245). */
internal actual val platformErrno: PlatformErrno = PlatformErrno(ENOSYS = 38, ETIMEDOUT = 110, ECONNREFUSED = 111, ECONNRESET = 104)
