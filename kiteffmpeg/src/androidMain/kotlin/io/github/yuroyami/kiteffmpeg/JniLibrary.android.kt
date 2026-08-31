package io.github.yuroyami.kiteffmpeg

internal actual object JniLibrary {
    actual val isAndroid: Boolean = true
    @Volatile private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("kitecodec_jni")
            loaded = true
        }
    }
}
