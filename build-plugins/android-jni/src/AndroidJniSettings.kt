package io.github.yuroyami.kiteffmpeg.androidjni

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface AndroidJniSettings {
    /** The Kotlin/Native version whose sysroots the C helper layer compiles against. */
    val kotlinVersion: String get() = "2.4.21"
}
