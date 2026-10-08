package io.github.yuroyami.kiteffmpeg

/**
 * [AsyncRuntimeContract] on a backend with threads. Its runtime runs FFmpeg on a thread of its
 * own, which waits there for each byte source call.
 */
class HostAsyncRuntimeContractTest : AsyncRuntimeContract() {
    override suspend fun runtimes(): List<Pair<String, suspend () -> AsyncMediaRuntime>> =
        listOf("host" to { FFmpeg.createAsyncRuntime() })
}
