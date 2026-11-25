@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_packet_unref
import kotlin.js.JsAny
import kotlin.js.toJsReference

/**
 * Frees the codec-module memory of a web [Frame] or [Packet] that was collected without `close()` (#242).
 *
 * `close()` stays the contract: the collector does not see the module's memory, so it can run long
 * after a leaked frame. This only bounds the damage. One `FinalizationRegistry` watches every owner.
 * Its callback runs between tasks and only after the owner is unreachable, so it never frees under
 * a call that still uses the pointer. The held value names the module that made the pointer,
 * because a page or a test can switch modules before the callback runs.
 */
internal object WebReaper {
    private var leaks = 0L

    /** How many frames and packets this page has freed for an owner that never closed them. */
    internal fun leakCount(): Long = leaks

    /** Null where the engine has no `FinalizationRegistry`; nothing is watched there. */
    private val registry: JsAny? by lazy { if (hasFinalizationRegistry()) newRegistry(::reap) else null }

    /** Frees [pointer] from [module] when [owner] is collected before its `close()`. */
    fun watch(owner: Any, module: JsAny?, pointer: Int, kind: Int) {
        if (pointer == 0 || module == null) return
        val watcher = registry ?: return
        registryRegister(watcher, owner.toJsReference(), module, pointer, kind)
    }

    /** Called by `close()`, so the registry never frees a pointer twice. */
    fun forget(owner: Any) {
        val watcher = registry ?: return
        registryUnregister(watcher, owner.toJsReference())
    }

    private fun reap(module: JsAny, pointer: Int, kind: Int) {
        val name = if (kind == PACKET) "Packet" else "Frame"
        try {
            if (kind == PACKET) {
                ffkmp_packet_unref(module, pointer)
                ffkmp_packet_free(module, pointer)
            } else {
                ffkmp_frame_free(module, pointer)
            }
        } catch (gone: Throwable) {
            // The module itself is gone, and its memory with it.
            return
        }
        leaks++
        FFmpegLog.warn(
            "kiteffmpeg",
            "A $name was collected without close(); its native memory was freed late. Close every $name you receive.",
        )
    }

    const val FRAME: Int = 0
    const val PACKET: Int = 1
}

@JsFun("() => typeof FinalizationRegistry === 'function'")
private external fun hasFinalizationRegistry(): Boolean

@JsFun("(onCollect) => new FinalizationRegistry((held) => onCollect(held.m, held.p, held.k))")
private external fun newRegistry(onCollect: (JsAny, Int, Int) -> Unit): JsAny

@JsFun("(registry, owner, m, p, k) => { registry.register(owner, { m: m, p: p, k: k }, owner); }")
private external fun registryRegister(registry: JsAny, owner: JsAny, m: JsAny, p: Int, k: Int)

@JsFun("(registry, owner) => { registry.unregister(owner); }")
private external fun registryUnregister(registry: JsAny, owner: JsAny)
