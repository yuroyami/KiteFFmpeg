package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic

/**
 * The one seam that lets a test make a muxer step fail.
 *
 * ## Why this exists
 *
 * `MediaSink.addCopyStream` POISONS the sink when a step after `avformat_new_stream` throws, because
 * FFmpeg cannot take a stream back: the format context already holds a half-configured stream, and
 * without the poison the sink kept looking usable while the next call wrote against it (audit
 * P1-10). That fix was correct and UNPROVABLE. The only steps left inside the guarded block are a
 * codec-parameter copy and a time-base write, and no caller can make either fail. The one lever that
 * existed, forging a `StreamInfo`, was deliberately removed when P1-11 closed.
 *
 * A fix whose evidence cannot exist is not done, by this project's own law. So rather than argue the
 * poison is right, this makes it falsifiable.
 *
 * ## What it costs in production
 *
 * One relaxed atomic read per `addCopyStream` call, which happens once per stream at setup and never
 * per packet or per frame. Unarmed is the only state a shipped consumer can reach: nothing public
 * arms it, [armOnce] is `internal`, and the counter starts at zero.
 *
 * ## Rules
 *
 * ONE-SHOT and self-disarming, so a test that forgets to clean up cannot poison the next one, and a
 * production path can never be left permanently broken by a stray arm. Not thread-safe as a
 * PROTOCOL: two tests arming concurrently would race, which is why arming belongs inside a single
 * test body and never in shared setup.
 */
internal object MuxFaults {

    private val armed = atomic(false)

    /** Arms exactly one [failIfArmed] call. The next one throws; every call after it is normal. */
    internal fun armOnce() {
        armed.value = true
    }

    /** Returns to the shipped state. Safe to call when nothing is armed. */
    internal fun disarm() {
        armed.value = false
    }

    /**
     * Throws once when armed, does nothing otherwise.
     *
     * [where] names the call site so an unexpected injected failure in an unrelated test reads as
     * an injection rather than a real muxer defect.
     */
    internal fun failIfArmed(where: String) {
        if (!armed.compareAndSet(expect = true, update = false)) return
        throw FFmpegException(
            FFmpegError.Internal("injected fault at $where (MuxFaults, test-only seam)"),
        )
    }
}
