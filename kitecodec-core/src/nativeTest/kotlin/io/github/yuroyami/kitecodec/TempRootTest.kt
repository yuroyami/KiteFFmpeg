package io.github.yuroyami.kitecodec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two cases that were actually failing in CI, pinned so they cannot come back.
 *
 * Both were invisible locally: a Mac sets TMPDIR, so neither the "nothing is set" path nor the
 * "Windows has no /tmp" path could ever be reached on the machine the helpers were written on.
 */
class TempRootTest {

    /** GitHub's Ubuntu runners set none of the three. Five suites used to throw here. */
    @Test
    fun nothingSetFallsBackToTmpRatherThanThrowing() {
        assertEquals("/tmp", pickTempRoot(tmpdir = null, temp = null, tmp = null))
        assertEquals("/tmp", pickTempRoot(tmpdir = "", temp = "   ", tmp = null))
    }

    /** Windows sets TEMP and TMP but never TMPDIR, and has no /tmp to fall back to. */
    @Test
    fun windowsStyleEnvironmentPicksTempAndNeverTheposixFallback() {
        assertEquals(
            """C:\Users\runneradmin\AppData\Local\Temp""",
            pickTempRoot(tmpdir = null, temp = """C:\Users\runneradmin\AppData\Local\Temp\""", tmp = """C:\other"""),
        )
    }

    /** TMPDIR wins when present, and a trailing separator never doubles up in the joined path. */
    @Test
    fun tmpdirWinsAndTrailingSeparatorsAreTrimmed() {
        assertEquals("/var/folders/ab", pickTempRoot(tmpdir = "/var/folders/ab/", temp = "/ignored", tmp = null))
    }
}
