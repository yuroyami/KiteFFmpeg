package io.github.yuroyami.kiteffmpeg

/**
 * The errno numbers of the C library the JNI library was built against, read from the system
 * this JVM runs on (#245). Android is Linux here.
 */
internal actual val platformErrno: PlatformErrno = run {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    when {
        "mac" in name || "darwin" in name -> PlatformErrno(ENOSYS = 78, ETIMEDOUT = 60, ECONNREFUSED = 61, ECONNRESET = 54)
        "win" in name -> PlatformErrno(ENOSYS = 40, ETIMEDOUT = 138, ECONNREFUSED = 107, ECONNRESET = 108)
        else -> PlatformErrno(ENOSYS = 38, ETIMEDOUT = 110, ECONNREFUSED = 111, ECONNRESET = 104)
    }
}
