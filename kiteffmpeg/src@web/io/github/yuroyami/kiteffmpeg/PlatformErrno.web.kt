package io.github.yuroyami.kiteffmpeg

/**
 * Emscripten's errno numbers, which are WASI's (#206, #245). The web backend reads its codes
 * through webFromCode, which uses these too.
 */
internal actual val platformErrno: PlatformErrno = PlatformErrno(ENOSYS = 52, ETIMEDOUT = 73, ECONNREFUSED = 14, ECONNRESET = 15)
