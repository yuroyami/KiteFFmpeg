package io.github.yuroyami.kiteffmpeg

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A refused open option key makes no JNI call while its exception is pending (#104). HotSpot's
 * CheckJNI only warns where Android's aborts the app, so the open runs in a child JVM started with
 * `-Xcheck:jni`, and the test reads the warning from its output.
 */
class JniPendingExceptionTest {

    @Test
    fun aRefusedOptionKeyMakesNoJniCallWithAnExceptionPending() {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(
            java, "-Xcheck:jni", "-cp", System.getProperty("java.class.path"), RefusedOptionKeyMain::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the child JVM did not finish:\n$output")
        assertTrue("REFUSED path" in output && "REFUSED io" in output, "both opens must refuse typed:\n$output")
        assertFalse("exception pending" in output, "a JNI call ran with an exception pending:\n$output")
    }
}

/** Opens by path and through a byte source, each with a key that holds an unpaired surrogate. */
internal object RefusedOptionKeyMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = mapOf("bad" + '\uD800' + "key" to "32")
        try {
            MediaSource.open("/nonexistent/file.wav", options).close()
        } catch (error: FFmpegException) {
            println("REFUSED path ${error.message}")
        }
        val empty = object : MediaByteSource {
            override val size: Long = 0
            override val seekable: Boolean = true
            override fun read(into: ByteArray, offset: Int, length: Int): Int = -1
            override fun seek(position: Long) {}
            override fun close() {}
        }
        try {
            MediaSource.open(empty, options).close()
        } catch (error: FFmpegException) {
            println("REFUSED io ${error.message}")
        }
    }
}
