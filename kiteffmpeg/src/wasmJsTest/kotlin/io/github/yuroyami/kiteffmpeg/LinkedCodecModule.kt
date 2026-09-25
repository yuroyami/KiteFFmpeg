package io.github.yuroyami.kiteffmpeg

/** The linked module, instantiated once per test run and installed again for each test that asks. */
private var linkedModule: JsAny? = null

/**
 * Installs the codec module that linkKiteFFmpegWasmModule built, the way a page loads it. Returns
 * false, after saying so, when the build names no linked module. The build points
 * KITEFFMPEG_WEB_MODULE at `build/kite-web/kite.mjs`; with KITEFFMPEG_WEB_MODULE_REQUIRED set to
 * true, a missing module fails the test instead of skipping it.
 */
internal suspend fun useLinkedCodecModule(): Boolean {
    linkedModule?.let { module ->
        useCodecModule(module)
        return true
    }
    val url = linkedModuleUrl()
    if (url == null) {
        check(!linkedModuleRequired()) {
            "KITEFFMPEG_WEB_MODULE_REQUIRED is true, and KITEFFMPEG_WEB_MODULE names no linked kite.mjs. " +
                "Run :kiteffmpeg:linkKiteFFmpegWasmModule first."
        }
        println("skipped: no linked kite.mjs, so this test decodes nothing")
        return false
    }
    forgetCodecModule()
    KiteFFmpegWeb.load(url)
    linkedModule = KiteFFmpegWeb.module
    return true
}

/** A file URL for the linked module the build named, or null when there is none. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """() => {
        const p = globalThis.process;
        const file = p && p.env ? p.env.KITEFFMPEG_WEB_MODULE : undefined;
        if (!file) return null;
        if (!p.getBuiltinModule) return null;
        if (!p.getBuiltinModule("node:fs").existsSync(file)) return null;
        // pathToFileURL encodes a '#' in the path, which a plain "file://" + path reads as a fragment.
        return p.getBuiltinModule("node:url").pathToFileURL(file).href;
    }""",
)
private external fun linkedModuleUrl(): String?

/** Whether the build asked for a missing module to fail rather than skip. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """() => {
        const p = globalThis.process;
        return !!(p && p.env && p.env.KITEFFMPEG_WEB_MODULE_REQUIRED === "true");
    }""",
)
private external fun linkedModuleRequired(): Boolean
