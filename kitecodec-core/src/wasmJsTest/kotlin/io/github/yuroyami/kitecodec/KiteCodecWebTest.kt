package io.github.yuroyami.kitecodec

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The module adoption contract, which had no test on any target.
 *
 * `KiteCodecWeb` owns FFmpeg's global codec registry and this backend's handle table, so who is
 * allowed to attach what, and how often, is a correctness rule rather than a convenience. Audit row
 * P1-35 was closed against this file with no test that could fail if it regressed.
 */
class KiteCodecWebTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun theBackendStartsUnloaded() {
        assertFalse(KiteCodecWeb.isLoaded)
    }

    @Test
    fun usingTheCodecBeforeLoadingItNamesTheFix() {
        val failure = assertFailsWith<KiteCodecWeb.NotLoaded> { requireModule() }
        assertTrue(
            failure.message.orEmpty().contains("KiteCodecWeb.load()"),
            "NotLoaded must name the call that fixes it, was: ${failure.message}",
        )
    }

    @Test
    fun attachRefusesAModuleBuiltWithoutTheRuntimePiecesTheBackendReads() {
        val failure = assertFailsWith<KiteCodecWeb.IncompleteModule> {
            KiteCodecWeb.attach(incompleteCodecModule())
        }
        val message = failure.message.orEmpty()
        for (missing in listOf("stringToUTF8", "lengthBytesUTF8", "HEAP32", "HEAPU8", "_malloc", "_free")) {
            assertTrue(missing in message, "IncompleteModule must name $missing, was: $message")
        }
        assertFalse(KiteCodecWeb.isLoaded, "a refused module must not become the loaded one")
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
        val message = assertFailsWith<KiteCodecWeb.IncompleteModule> {
            KiteCodecWeb.attach(incompleteCodecModule())
        }.message.orEmpty()
        val missingClause = message.substringBefore(". Link it with")
        assertFalse("ccall" in missingClause, "ccall is present, so it is not missing: $missingClause")
        assertFalse("UTF8ToString" in missingClause, "UTF8ToString is present: $missingClause")
        assertTrue("HEAP32" in missingClause, "HEAP32 is absent and must be listed: $missingClause")
    }

    @Test
    fun attachingTheSameModuleTwiceIsANoOp() {
        val module = fakeCodecModule()
        KiteCodecWeb.attach(module)
        KiteCodecWeb.attach(module)
        assertTrue(KiteCodecWeb.isLoaded)
        assertSame(module, KiteCodecWeb.module)
    }

    @Test
    fun attachingASecondDifferentModuleIsRefusedAndTheFirstKeepsWinning() {
        val first = fakeCodecModule()
        val second = fakeCodecModule()
        KiteCodecWeb.attach(first)
        val failure = assertFailsWith<IllegalStateException> { KiteCodecWeb.attach(second) }
        assertTrue(
            failure.message.orEmpty().contains("already attached"),
            "the refusal must say a module is already attached, was: ${failure.message}",
        )
        assertSame(first, KiteCodecWeb.module, "the established module must survive a refused attach")
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
        KiteCodecWeb.attach(module)
        runWithoutSuspending { KiteCodecWeb.load() }
        assertSame(module, KiteCodecWeb.module)
    }

    @Test
    fun aRefusedModuleLeavesTheBackendUsableForTheNextAttach() {
        assertFailsWith<KiteCodecWeb.IncompleteModule> { KiteCodecWeb.attach(incompleteCodecModule()) }
        val good = fakeCodecModule()
        KiteCodecWeb.attach(good)
        assertSame(good, KiteCodecWeb.module)
    }
}

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
