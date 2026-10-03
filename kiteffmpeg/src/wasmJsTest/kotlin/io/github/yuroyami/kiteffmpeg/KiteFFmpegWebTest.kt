package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The module adoption contract, which had no test on any target.
 *
 * `KiteFFmpegWeb` owns FFmpeg's global codec registry and this backend's handle table, so who is
 * allowed to attach what, and how often, is a correctness rule rather than a convenience. A fix to
 * this file once landed with no test that could fail if it regressed.
 */
class KiteFFmpegWebTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun theBackendStartsUnloaded() {
        assertFalse(KiteFFmpegWeb.isLoaded)
    }

    @Test
    fun usingTheCodecBeforeLoadingItNamesTheFix() {
        val failure = assertFailsWith<KiteFFmpegWeb.NotLoaded> { requireModule() }
        assertTrue(
            failure.message.orEmpty().contains("KiteFFmpegWeb.load()"),
            "NotLoaded must name the call that fixes it, was: ${failure.message}",
        )
    }

    @Test
    fun attachRefusesAModuleBuiltWithoutTheRuntimePiecesTheBackendReads() {
        val failure = assertFailsWith<KiteFFmpegWeb.IncompleteModule> {
            KiteFFmpegWeb.attach(incompleteCodecModule())
        }
        val message = failure.message.orEmpty()
        for (missing in listOf("stringToUTF8", "lengthBytesUTF8", "HEAP32", "HEAPU8", "_malloc", "_free")) {
            assertTrue(missing in message, "IncompleteModule must name $missing, was: $message")
        }
        assertFalse(KiteFFmpegWeb.isLoaded, "a refused module must not become the loaded one")
    }

    /**
     * The list names what is ABSENT, not the whole expected set.
     *
     * Worth pinning separately: the remediation half of the same message quotes every runtime
     * method including the present ones, so a diagnostic that degraded into "here is the full
     * list" would still look right to a reader and to a laxer assertion.
     */
    @Test
    fun theMissingListNamesOnlyWhatTheModuleDoesNotCarry() {
        val message = assertFailsWith<KiteFFmpegWeb.IncompleteModule> {
            KiteFFmpegWeb.attach(incompleteCodecModule())
        }.message.orEmpty()
        val missingClause = message.substringBefore(". Link it with")
        assertFalse("ccall" in missingClause, "ccall is present, so it is not missing: $missingClause")
        assertFalse("UTF8ToString" in missingClause, "UTF8ToString is present: $missingClause")
        assertTrue("HEAP32" in missingClause, "HEAP32 is absent and must be listed: $missingClause")
    }

    @Test
    fun attachingTheSameModuleTwiceIsANoOp() {
        val module = fakeCodecModule()
        KiteFFmpegWeb.attach(module)
        KiteFFmpegWeb.attach(module)
        assertTrue(KiteFFmpegWeb.isLoaded)
        assertSame(module, KiteFFmpegWeb.module)
    }

    @Test
    fun attachingASecondDifferentModuleIsRefusedAndTheFirstKeepsWinning() {
        val first = fakeCodecModule()
        val second = fakeCodecModule()
        KiteFFmpegWeb.attach(first)
        val failure = assertFailsWith<IllegalStateException> { KiteFFmpegWeb.attach(second) }
        assertTrue(
            failure.message.orEmpty().contains("already attached"),
            "the refusal must say a module is already attached, was: ${failure.message}",
        )
        assertSame(first, KiteFFmpegWeb.module, "the established module must survive a refused attach")
    }

    /**
     * The "calling twice is a no-op rather than a second fetch" half of the contract.
     *
     * Asserted by RUNNING the suspend function and requiring it to finish without ever suspending.
     * A `load` that reached the network would suspend at the import, so [runWithoutSuspending] is
     * the assertion, not the plumbing: no fetch is reachable from here under node.
     */
    @Test
    fun loadDoesNotFetchWhenAModuleIsAlreadyAttached() {
        val module = fakeCodecModule()
        KiteFFmpegWeb.attach(module)
        runWithoutSuspending { KiteFFmpegWeb.load() }
        assertSame(module, KiteFFmpegWeb.module)
    }

    @Test
    fun aRefusedModuleLeavesTheBackendUsableForTheNextAttach() {
        assertFailsWith<KiteFFmpegWeb.IncompleteModule> { KiteFFmpegWeb.attach(incompleteCodecModule()) }
        val good = fakeCodecModule()
        KiteFFmpegWeb.attach(good)
        assertSame(good, KiteFFmpegWeb.module)
    }

    /**
     * Overlapping loads share one fetch. Each used to call the module's factory, which builds a new
     * instance with its own wasm memory, and only the first to land was adopted (#132). All four
     * calls start before any import resolves, because the import is a promise.
     */
    @Test
    fun overlappingLoadsBuildOneModule() = runTest {
        val module = fakeCodecModule()
        val url = countingModuleUrl("overlap", module)
        List(4) { async { KiteFFmpegWeb.load(url) } }.awaitAll()
        assertEquals(1, factoryCalls(), "four overlapping loads built more than one module")
        assertSame(module, KiteFFmpegWeb.module)
    }

    @Test
    fun aLoadThatFailedIsTriedAgainByTheNextCall() = runTest {
        val module = fakeCodecModule()
        val url = countingModuleUrl("retry", module)
        setFactoryFails(true)
        assertFailsWith<IllegalStateException> { KiteFFmpegWeb.load(url) }
        assertFalse(KiteFFmpegWeb.isLoaded)
        setFactoryFails(false)
        KiteFFmpegWeb.load(url)
        assertEquals(2, factoryCalls(), "the failed load was not forgotten, so the retry never fetched")
        assertSame(module, KiteFFmpegWeb.module)
    }

    @Test
    fun aCancelledLoadLeavesTheOthersWaitingForTheSameModule() = runTest {
        val module = fakeCodecModule()
        val url = countingModuleUrl("cancel", module)
        holdFactory()
        val first = async { KiteFFmpegWeb.load(url) }
        val second = async { KiteFFmpegWeb.load(url) }
        testScheduler.runCurrent()
        first.cancel()
        testScheduler.runCurrent()
        assertTrue(first.isCancelled && first.isCompleted, "the cancelled load is still waiting for the module")
        assertFalse(second.isCompleted, "the other load finished before the module landed")
        releaseFactory()
        second.await()
        assertSame(module, KiteFFmpegWeb.module)
        assertEquals(1, factoryCalls())
    }

    @Test
    fun aLoadOfAnotherAddressWhileOneIsInFlightIsRefusedByName() = runTest {
        val module = fakeCodecModule()
        val url = countingModuleUrl("first", module)
        val other = countingModuleUrl("second", module)
        holdFactory()
        val first = async { KiteFFmpegWeb.load(url) }
        testScheduler.runCurrent()
        val refused = async { runCatching { KiteFFmpegWeb.load(other) } }
        testScheduler.runCurrent()
        assertTrue(refused.isCompleted, "the load of another address waited instead of being refused")
        val refusal = assertIs<IllegalStateException>(refused.await().exceptionOrNull())
        assertTrue(url in refusal.message.orEmpty(), "the refusal must name the load in flight, said: ${refusal.message}")
        releaseFactory()
        first.await()
        assertEquals(1, factoryCalls(), "the refused address was fetched anyway")
        assertSame(module, KiteFFmpegWeb.module)
    }
}

/**
 * A module address whose default export, the factory, counts its calls in [factoryCalls] and
 * answers [module], or fails while [setFactoryFails] says so, or waits while [holdFactory] says so.
 * [name] keeps one test's address apart from another's.
 */
private fun countingModuleUrl(name: String, module: JsAny): String = countingModuleUrlJs(name, module)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(name, module) => {
        globalThis.__kiteFactory = { calls: 0, module: module, fails: false, held: false, waiting: [] };
        const source = "export default () => { const f = globalThis.__kiteFactory; f.calls++; " +
            "if (f.fails) return Promise.reject(new Error('the factory failed')); " +
            "if (!f.held) return f.module; " +
            "return new Promise((resolve) => f.waiting.push(resolve)); }";
        return "data:text/javascript," + encodeURIComponent(source + " // " + name);
    }""",
)
private external fun countingModuleUrlJs(name: String, module: JsAny): String

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.__kiteFactory.calls")
private external fun factoryCalls(): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(fails) => { globalThis.__kiteFactory.fails = fails; }")
private external fun setFactoryFails(fails: Boolean)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => { globalThis.__kiteFactory.held = true; }")
private external fun holdFactory()

/** Lets every held factory call answer, including one whose import has not run yet. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => { const f = globalThis.__kiteFactory; f.held = false; f.waiting.splice(0).forEach((resolve) => resolve(f.module)); }")
private external fun releaseFactory()

/**
 * Runs [block] and fails unless it completed synchronously.
 *
 * There is no `runBlocking` on wasmJs and no coroutines-test dependency in this repository, so a
 * suspend function that must NOT suspend is driven by hand. Anything that really suspends is
 * reported as such instead of hanging or passing vacuously.
 */
private fun runWithoutSuspending(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        },
    )
    val settled = outcome ?: fail("the call suspended; nothing here is allowed to reach the network")
    settled.getOrThrow()
}
