package io.github.yuroyami.kiteffmpeg.check

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.yuroyami.kiteffmpeg.FFmpeg

/**
 * Loads the JNI library through the facade and writes one result line to the log, tagged `KITEFFMPEG_CHECK`.
 * The checks are those of the former Android device test: the identity gate accepts the linked FFmpeg, and
 * the `mpeg4` decoder is there.
 */
class CheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = runCatching {
            val identity = FFmpeg.identity
            check(identity.isAcceptable) { identity.describe() }
            check(FFmpeg.hasDecoder("mpeg4")) { "the mpeg4 decoder is missing" }
            "PASS avutil=${FFmpeg.versions.avutil} avcodec=${FFmpeg.versions.avcodec}"
        }.getOrElse { "FAIL ${it::class.simpleName}: ${it.message}" }
        Log.i(TAG, result)
        finish()
    }

    private companion object {
        const val TAG = "KITEFFMPEG_CHECK"
    }
}
