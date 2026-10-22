package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A source says where its length came from (#134): a length FFmpeg guessed from the bit rate reads
 * as [DurationOrigin.Bitrate], and one the container states does not.
 */
class DurationOriginContractTest {

    @Test
    fun anMp3WithoutItsXingHeaderHasAGuessedLength() {
        val path = materializeContractMedia(Mp3WithoutXing.bytes, Mp3WithoutXing.sha256)
        MediaSource.open(path).use { source ->
            assertNotNull(source.durationMicros, "FFmpeg estimates a length for it")
            assertEquals(DurationOrigin.Bitrate, source.durationOrigin)
        }
    }

    @Test
    fun anMp4StatesItsLength() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        MediaSource.open(path).use { source ->
            assertTrue((source.durationMicros ?: 0L) > 0L)
            assertEquals(DurationOrigin.Header, source.durationOrigin)
        }
    }

    @Test
    fun everyCodeFFmpegSendsMapsToAnOrigin() {
        assertEquals(DurationOrigin.Timestamps, DurationOrigin.ofCode(0))
        assertEquals(DurationOrigin.Header, DurationOrigin.ofCode(1))
        assertEquals(DurationOrigin.Bitrate, DurationOrigin.ofCode(2))
        // A code this Kotlin does not know, and the C layer's -1 for no context, name no origin.
        assertEquals(null, DurationOrigin.ofCode(3))
        assertEquals(null, DurationOrigin.ofCode(-1))
    }
}

/** One second of a 440 Hz tone at 8 kHz, 8 kbps MP3, written by ffmpeg with `-write_xing 0`. */
private object Mp3WithoutXing {
    const val sha256: String = "ecde771fcde9230c1f387f00ada5747546d0192656de153a600d3130d7f9e263"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 1_152) { "MP3 fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "MP3 fixture digest changed" }
        }
    }

    private val DATA: String = """
/+MYxAAK8AbVuUEAAv9HcNtgBg+D4PvUCAIHIPg+fxOD4PoggcRB8H34IOu/wxwG/SGOXfznT7ulJoc4WcQ3/GVEJRyhC3+D/+MY
xA8QmUKQAZRQAG8H6ge5ABlxEnAPiYDmR1gd6mHvgGgCQCorCKC6//IR6KpEPh8ab//5CgNCUJA1/4lOg0r//////////+MYxAcO
aKY8Ad4AAP/////40iEYA4ASSQXAKMCsF4wfwajCHFKMQulwxvhNjDbBaMD0D4FAXiEAMDAKoSW07dP//vgSpVAg/+MYxAgNqKYs
AFa8YCmJFGfQGycnl9GIANucd+h5p+CpmGqC8YJQC5QA8gRBoACAVL6x////3+U///9a/1//fGMDIYEAAdcF/+MYxAwO6KYsAKew
gIIBpyIHmpGIyKqc5FMpq8iHmHkCsYMoFpgYARG6RjOX8Urx///Z/v9P////////Rf//+TsVQOAwSYQN/+MYxAsNaKYwAAb+RJgK
ECJ0AhGBgSyEkYCKDdEgESDADgwAMAVCAAsiAEECa4b/////+j///1L/5/wCvIvqBQBjxhoi50XB/+MYxBAMOKY0AAa8RIbAc5vY
ucmiQFeYWoEQQD6GARoI0dlKWRYf////////1//n/DDK1AHHTkMA0BkwOgVjC1FfNpiqMzrR/+MYxBoMUKY0AAewSBUwnAVDA9An
MBwBYzGAw0wGH4/////61f9P/8JmmoLAYFMIFzCzYxaDMEsdozFOGjGvFyMDIG8CgUCM/+MYxCMMKKY0AD78YAHTPHgAUzma3///
//9S///4JW0gyW6MaENIFOqSMOYGU4ITiTSIBFBQwwsEgYC4AIVAFQ8WywrX/////+MYxC0MaKY0AAa8RPr///9d/+/8YZ2pYt4h
ACAIE5gTA3GEWNKapWTJl4ilmD0DMYF4FpgMALGYgOOlYxOz/////qX/T//A/+MYxDYMYKY0AAewSCYigGMEBMcXMqyNd3MIMbI0
5s/DK1FSMGYGMwFwHkQV7IUprMFtqvgeo4i6jcDgDhoEEICrMOYDg4Ly/+MYxD8KiKY0AC68YF40kALjDFAHMEoAQwHQCwKAOIgA
FlsI1////3ei///+shwYkEIAt7AwCgpAwLgUAwNAfA1xfZAybl/A/+MYxE8MMKY0AAH8QMbYcwMLYQQWA2GjgYCwChYeKO0kAj//
///cR43Xa/////3TYGmLCKN+vIzSacbjKTYXtipb8wEfA0AC/+MYxFkMOKZEAVYAAJX/W/gIsKIFIDkDFEr//8d5JDkJhdJc6bf/
/5ocRN1gQgf/+sCEAdAgcP//yoENB4caQ///96EGss09/+MYxGMXsZ6MKZtoAGmq1bbWVteaOjJ2y5cuucg1A6jJIknrRk8yYvMr
Vq13GlxoUNxBsQVwU7IbiL8FdCVMQU1FMy4xMDBV/+MYxD8NILaEQcwIAVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
"""
}
