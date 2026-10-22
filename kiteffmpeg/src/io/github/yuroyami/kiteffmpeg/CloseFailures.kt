package io.github.yuroyami.kiteffmpeg

/**
 * Collects the failures of a close, so every release runs whichever of them throws.
 *
 * The first failure is the one the caller receives, and every later one is suppressed into it. A
 * close that stopped at its first throwing step left its later releases undone for good, because
 * the object had already refused a second close (#112).
 */
internal class CloseFailures {
    var first: Throwable? = null
        private set

    /** Runs [step] and keeps what it throws. */
    inline fun run(step: () -> Unit) {
        try {
            step()
        } catch (failure: Throwable) {
            record(failure)
        }
    }

    fun record(failure: Throwable) {
        val kept = first
        if (kept == null) first = failure else if (kept !== failure) kept.addSuppressed(failure)
    }

    /** Throws the first failure, if any. */
    fun rethrow() {
        first?.let { throw it }
    }
}
