package io.github.yuroyami.kiteffmpeg

/**
 * No loopback listener on Windows yet: it needs Winsock. The RTSP contract tests read null as
 * "cannot listen" and skip, as they say in their output (#279).
 */
internal actual fun listenOnLoopback(): LoopbackListener? = null
