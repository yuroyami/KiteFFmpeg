package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest

/** The decode contract on the web backend, against the codec module linkKiteFFmpegWasmModule links. */
class WebDecodeContractTest : DecodeContract() {

    override suspend fun backendReady(): Boolean = useLinkedCodecModule()

    @AfterTest fun finish() = forgetCodecModule()
}
