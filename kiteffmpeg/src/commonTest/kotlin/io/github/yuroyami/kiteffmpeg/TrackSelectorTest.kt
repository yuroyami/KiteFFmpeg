package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stream [TrackSelector] picks from a stream list, one rule per test. */
class TrackSelectorTest {

    private fun stream(
        index: Int,
        type: MediaType,
        language: String? = null,
        disposition: Disposition = Disposition.None,
    ) = StreamInfo(
        index = index,
        type = type,
        codec = CodecId(if (type == MediaType.Video) "h264" else "aac"),
        timeBase = Rational(1, 1000),
        durationMicros = null,
        bitrateBps = null,
        metadata = listOfNotNull(language?.let { "language" to it }).toMap(),
        disposition = disposition,
    )

    @Test
    fun coverArtNeverBeatsARealVideoStream() {
        val cover = stream(0, MediaType.Video, disposition = Disposition(attachedPicture = true))
        val video = stream(1, MediaType.Video)
        assertEquals(video, TrackSelector.Default.selectVideo(listOf(cover, video)))
    }

    @Test
    fun aFileWhoseOnlyVideoIsItsCoverArtGetsThePicture() {
        val cover = stream(0, MediaType.Video, disposition = Disposition(attachedPicture = true))
        assertEquals(cover, TrackSelector.Default.selectVideo(listOf(stream(1, MediaType.Audio), cover)))
    }

    @Test
    fun theFirstAudioStreamWinsWhenNothingElseDecides() {
        val first = stream(1, MediaType.Audio)
        val second = stream(2, MediaType.Audio)
        assertEquals(first, TrackSelector.Default.selectAudio(listOf(stream(0, MediaType.Video), first, second)))
    }

    @Test
    fun audioDescriptionNeverBeatsAnOrdinarySibling() {
        val described = stream(1, MediaType.Audio, disposition = Disposition(descriptions = true))
        val alsoDescribed = stream(2, MediaType.Audio, disposition = Disposition(visualImpaired = true))
        val ordinary = stream(3, MediaType.Audio)
        assertEquals(ordinary, TrackSelector.Default.selectAudio(listOf(described, alsoDescribed, ordinary)))
    }

    @Test
    fun commentaryAndAHearingImpairedMixNeverBeatAnOrdinarySibling() {
        val commentary = stream(1, MediaType.Audio, disposition = Disposition(comment = true))
        val clearDialogue = stream(2, MediaType.Audio, disposition = Disposition(hearingImpaired = true))
        val ordinary = stream(3, MediaType.Audio)
        assertEquals(ordinary, TrackSelector.Default.selectAudio(listOf(commentary, clearDialogue, ordinary)))
    }

    @Test
    fun aSpecialStreamIsStillPickedWhenItIsTheOnlyAudio() {
        val commentary = stream(1, MediaType.Audio, disposition = Disposition(comment = true))
        assertEquals(commentary, TrackSelector.Default.selectAudio(listOf(stream(0, MediaType.Video), commentary)))
    }

    @Test
    fun aPreferredLanguageBeatsContainerOrder() {
        val english = stream(1, MediaType.Audio, language = "eng")
        val japanese = stream(2, MediaType.Audio, language = "jpn")
        assertEquals(japanese, TrackSelector(listOf("jpn")).selectAudio(listOf(english, japanese)))
    }

    @Test
    fun preferredLanguagesAreRankedBestFirst() {
        val english = stream(1, MediaType.Audio, language = "eng")
        val japanese = stream(2, MediaType.Audio, language = "jpn")
        val french = stream(3, MediaType.Audio, language = "fre")
        assertEquals(french, TrackSelector(listOf("fre", "jpn")).selectAudio(listOf(english, japanese, french)))
    }

    @Test
    fun aLanguageMatchesOnItsPrimarySubtagAndWithoutRegardToCase() {
        val british = stream(1, MediaType.Audio, language = "en-GB")
        val japanese = stream(2, MediaType.Audio, language = "JPN")
        assertEquals(british, TrackSelector(listOf("EN")).selectAudio(listOf(japanese, british)))
        assertEquals(japanese, TrackSelector(listOf("jpn")).selectAudio(listOf(british, japanese)))
    }

    @Test
    fun twoLetterAndThreeLetterCodesAreDifferentStrings() {
        val english = stream(1, MediaType.Audio, language = "eng")
        val japanese = stream(2, MediaType.Audio, language = "jpn")
        assertEquals(english, TrackSelector(listOf("ja")).selectAudio(listOf(english, japanese)))
    }

    @Test
    fun theDefaultFlagBeatsContainerOrder() {
        val first = stream(1, MediaType.Audio)
        val marked = stream(2, MediaType.Audio, disposition = Disposition(default = true))
        assertEquals(marked, TrackSelector.Default.selectAudio(listOf(first, marked)))
    }

    @Test
    fun aPreferredLanguageBeatsTheDefaultFlag() {
        val english = stream(1, MediaType.Audio, language = "eng", disposition = Disposition(default = true))
        val japanese = stream(2, MediaType.Audio, language = "jpn")
        assertEquals(japanese, TrackSelector(listOf("jpn")).selectAudio(listOf(english, japanese)))
    }

    @Test
    fun aPreferredLanguageNeverPromotesAudioForASpecialAudience() {
        val describedJapanese = stream(1, MediaType.Audio, language = "jpn", disposition = Disposition(visualImpaired = true))
        val english = stream(2, MediaType.Audio, language = "eng")
        assertEquals(english, TrackSelector(listOf("jpn")).selectAudio(listOf(describedJapanese, english)))
    }

    @Test
    fun noStreamOfTheKindAnswersNull() {
        assertNull(TrackSelector.Default.selectVideo(listOf(stream(0, MediaType.Audio))))
        assertNull(TrackSelector.Default.selectAudio(listOf(stream(0, MediaType.Video))))
    }
}
