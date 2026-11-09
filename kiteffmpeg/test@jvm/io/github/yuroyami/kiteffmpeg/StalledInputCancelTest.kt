package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/**
 * A remux or a transcode of a server that accepts the connection and never sends can be
 * cancelled (#250). The cancel used to wait for FFmpeg's own timeout, of which there is none here.
 */
class StalledInputCancelTest {

    /** Runs [body] against a silent server, which lets go of every connection when it returns. */
    private fun withSilentServer(body: (address: String) -> Unit) {
        val held = CopyOnWriteArrayList<Socket>()
        ServerSocket(0).use { server ->
            val accepting = Thread {
                runCatching { while (true) held += server.accept() }
            }.apply { isDaemon = true; start() }
            try {
                body("tcp://127.0.0.1:${server.localPort}")
            } finally {
                // A failing run must not leave a worker blocked for ever.
                held.forEach { runCatching { it.close() } }
                accepting.interrupt()
            }
        }
    }

    private fun cancelsPromptly(name: String, work: suspend () -> Unit) = runBlocking {
        // Not a child of runBlocking, so a cancel that never returns fails the test instead of
        // hanging it; the silent server then lets go and frees the worker.
        val job = CoroutineScope(Dispatchers.Default).launch { runCatching { work() } }
        delay(1_000)
        job.cancel()
        withTimeout(10_000) { job.join() }
        println("$name: the cancel returned")
    }

    @Test
    fun aRemuxOfASilentServerCanBeCancelled() = withSilentServer { address ->
        val output = File.createTempFile("kiteffmpeg-250-", ".mkv").apply { deleteOnExit() }
        cancelsPromptly("remux") { Remuxer.remux(address, output.path) }
    }

    @Test
    fun aTranscodeOfASilentServerCanBeCancelled() = withSilentServer { address ->
        val output = File.createTempFile("kiteffmpeg-250-", ".mkv").apply { deleteOnExit() }
        cancelsPromptly("transcode") {
            Transcoder.transcode(input = address, output = output.path, spec = TranscodeFixtures.videoSpec(Rational(10, 1)))
        }
    }
}
