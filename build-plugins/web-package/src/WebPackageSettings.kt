package io.github.yuroyami.kiteffmpeg.webpackage

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface WebPackageSettings {
    /** `web` puts kite.mjs and kite.wasm in the jar. `web-async` puts kite-jspi and kite-asyncify in it. */
    val variant: String get() = "web"
}
