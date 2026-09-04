package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Loads the codec, which on the web is a separate wasm module fetched over the network.
 *
 * Every other target links FFmpeg into the same binary and can answer `FFmpeg.identity` the instant
 * the process starts. A browser cannot, and cannot block waiting either, so the web needs one
 * explicit step that the common API has nowhere to put. Call this once,
 * await it, and the rest of `kiteffmpeg` behaves normally:
 *
 * ```kotlin
 * KiteFFmpegWeb.load("/kite.mjs")
 * check(FFmpeg.identity.isAcceptable)
 * ```
 *
 * Web only. It exists in no common source set, so no other platform's API changes to accommodate it.
 */
public object KiteFFmpegWeb {

    internal var module: JsAny? = null

    /** True once [load] has completed. Every codec call before that throws [NotLoaded]. */
    public val isLoaded: Boolean get() = module != null

    /**
     * Adopts a codec module the page has already instantiated.
     *
     * This is the reliable way to supply it and the one a bundled application should use. A
     * bundler rewrites `import(url)` at BUILD time, so [load] can fail with "Cannot find module"
     * inside webpack, Vite or Rollup even though the file is served correctly. Loading the module
     * from a plain `<script type="module">` on the page and handing it here has no such problem:
     *
     * ```html
     * <script type="module">
     *   const factory = (await import("./kite.mjs")).default;
     *   globalThis.kiteCodecModule = await factory();
     * </script>
     * ```
     * ```kotlin
     * KiteFFmpegWeb.attach(kiteCodecModule())   // your own external accessor
     * ```
     *
     * Calling twice is a no-op rather than a swap: the module holds FFmpeg's global codec registry
     * and its handle table, so a second one would be a second registry and a second set of handles.
     */
    public fun attach(codecModule: JsAny) {
        // The established module answers FIRST, before validation. Re-attaching the same module is
        // the common case under a bundler that runs a setup block twice, and validating it again
        // was pointless work; validating a DIFFERENT one and then dropping it silently was worse,
        // because the caller's module never became the one in use and nothing said so.
        val established = module
        if (established != null) {
            if (established === codecModule) return
            throw IllegalStateException(
                "a different codec module is already attached. The module owns FFmpeg's global " +
                    "codec registry and this backend's handle table, so a second one would be a " +
                    "second registry and a second set of handles. Attach once per page.",
            )
        }
        val missing = missingRuntimeMethods(codecModule)
        if (missing.isNotEmpty()) throw IncompleteModule(missing)
        module = codecModule
    }

    /**
     * Fetches and instantiates the codec module at [url] itself.
     *
     * Convenient when nothing bundles the page. Under a bundler prefer [attach], for the reason
     * given there. Calling twice is a no-op rather than a second fetch.
     */
    public suspend fun load(url: String = DEFAULT_URL) {
        if (module != null) return
        val loaded = awaitModule(loadModule(url))
        // Checked again after the await: two concurrent loads both saw no module before suspending,
        // and the second one arriving would otherwise throw at [attach] for being a different
        // module than the first one that landed. The instance that got there first wins and the
        // other is simply not adopted, which is what "calling twice is a no-op" has to mean when
        // the two calls overlap.
        if (module != null) return
        attach(loaded)
    }

    /** The conventional name emscripten writes beside the wasm, resolved against the page. */
    public const val DEFAULT_URL: String = "./kite.mjs"

    /**
     * Thrown when the module was built without the runtime pieces this backend reads.
     *
     * Without this the first failure is `Cannot read properties of undefined`, from deep inside a
     * heap read, naming neither the cause nor the build flag that fixes it.
     */
    public class IncompleteModule internal constructor(missing: String) : IllegalArgumentException(
        "This codec module is missing $missing. Link it with " +
            "-sEXPORTED_RUNTIME_METHODS='[\"ccall\",\"UTF8ToString\",\"stringToUTF8\"," +
            "\"lengthBytesUTF8\",\"addFunction\",\"removeFunction\",\"HEAP32\",\"HEAPU8\"]' " +
            "and -sALLOW_TABLE_GROWTH=1, which is what scripts/wasm-browser-demo.sh passes.",
    )

    /** Thrown when the codec is used before [load] has completed. Names the fix, not the symptom. */
    public class NotLoaded : IllegalStateException(
        "The KiteFFmpeg web backend is not loaded. Call KiteFFmpegWeb.load() and await it before " +
            "using FFmpeg, MediaSource or Frame. On the web the codec is a separate wasm module " +
            "that must be fetched first; every other platform links it in and needs no such step.",
    )
}

/** The loaded module, or the one typed error that says what to do about it. */
internal fun requireModule(): JsAny = KiteFFmpegWeb.module ?: throw KiteFFmpegWeb.NotLoaded()

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
// `webpackIgnore` matters: a bundler that sees a bare `import(url)` tries to resolve it at BUILD
// time and turns it into "Cannot find module" at run time. The codec is fetched by the page at
// run time and is never part of the Kotlin bundle.
@JsFun("(url) => import(/* webpackIgnore: true */ url).then(m => m.default())")
private external fun loadModule(url: String): Promise<JsAny>

/** Bridges a JS promise into a suspend function without pulling in a coroutines JS dependency. */
private suspend fun awaitModule(promise: Promise<JsAny>): JsAny =
    kotlin.coroutines.suspendCoroutine { continuation ->
        promise.then(
            onFulfilled = { value -> continuation.resumeWith(Result.success(value)); value },
            onRejected = { error ->
                continuation.resumeWith(Result.failure(IllegalStateException("codec module failed to load: $error")))
                error
            },
        )
    }

/** Names every runtime piece the backend reads and the module does not expose. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""(m) => ["ccall","UTF8ToString","stringToUTF8","lengthBytesUTF8","addFunction","removeFunction","HEAP32","HEAPU8","_malloc","_free"].filter(k => m[k] === undefined).join(", ")""")
private external fun missingRuntimeMethods(module: JsAny): String

/** Decodes a NUL-terminated C string from the codec module's memory. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p) => p === 0 ? null : m.UTF8ToString(p)")
internal external fun utf8OrNull(module: JsAny, pointer: Int): String?
