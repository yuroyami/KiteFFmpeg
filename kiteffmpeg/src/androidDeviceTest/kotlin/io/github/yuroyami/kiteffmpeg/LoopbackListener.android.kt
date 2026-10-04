package io.github.yuroyami.kiteffmpeg

/** The device test package asks for no network permission, so it cannot listen. */
internal actual fun listenOnLoopback(): LoopbackListener? = null
