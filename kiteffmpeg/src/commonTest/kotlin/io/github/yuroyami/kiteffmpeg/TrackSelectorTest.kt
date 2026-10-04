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
    fun aTwoLetterAndAThreeLetterCodeNameOneLanguage() {
        val english = stream(1, MediaType.Audio, language = "eng")
        val japanese = stream(2, MediaType.Audio, language = "jpn")
        assertEquals(japanese, TrackSelector(listOf("ja")).selectAudio(listOf(english, japanese)))
        val tagged = stream(3, MediaType.Audio, language = "en")
        assertEquals(tagged, TrackSelector(listOf("eng")).selectAudio(listOf(japanese, tagged)))
    }

    @Test
    fun bothThreeLetterCodesOfALanguageNameIt() {
        val french = stream(1, MediaType.Audio, language = "fra")
        val german = stream(2, MediaType.Audio, language = "deu")
        assertEquals(german, TrackSelector(listOf("ger")).selectAudio(listOf(french, german)))
        val chinese = stream(3, MediaType.Audio, language = "chi")
        assertEquals(chinese, TrackSelector(listOf("zh")).selectAudio(listOf(french, chinese)))
        assertEquals(chinese, TrackSelector(listOf("zho")).selectAudio(listOf(french, chinese)))
    }

    @Test
    fun theRegionAPreferenceNamesWinsAmongStreamsOfItsLanguage() {
        val european = stream(1, MediaType.Audio, language = "pt-PT", disposition = Disposition(default = true))
        val brazilian = stream(2, MediaType.Audio, language = "pt-BR")
        assertEquals(brazilian, TrackSelector(listOf("pt-BR")).selectAudio(listOf(european, brazilian)))
        assertEquals(european, TrackSelector(listOf("pt-BR")).selectAudio(listOf(european)))
    }

    @Test
    fun aStreamThatSaysNoRegionBeatsOneThatNamesAnother() {
        val european = stream(1, MediaType.Audio, language = "pt-PT", disposition = Disposition(default = true))
        val portuguese = stream(2, MediaType.Audio, language = "por")
        assertEquals(portuguese, TrackSelector(listOf("pt-BR")).selectAudio(listOf(european, portuguese)))
    }

    @Test
    fun aScriptBeatsARegionAndARegionCanImplyAScript() {
        val simplified = stream(1, MediaType.Audio, language = "zh-Hans-TW")
        val traditional = stream(2, MediaType.Audio, language = "zh-HK")
        assertEquals(traditional, TrackSelector(listOf("zh-Hant-TW")).selectAudio(listOf(simplified, traditional)))
        val mainland = stream(3, MediaType.Audio, language = "zh-CN")
        val taiwanese = stream(4, MediaType.Audio, language = "zh-TW")
        assertEquals(taiwanese, TrackSelector(listOf("zh-Hant")).selectAudio(listOf(mainland, taiwanese)))
    }

    @Test
    fun theOrderOfThePreferencesComesBeforeHowCloseAStreamIs() {
        val european = stream(1, MediaType.Audio, language = "pt-PT")
        val english = stream(2, MediaType.Audio, language = "en")
        assertEquals(european, TrackSelector(listOf("pt-BR", "en")).selectAudio(listOf(english, european)))
    }

    @Test
    fun aRelatedLanguageIsNotTheLanguage() {
        val middleEnglish = stream(1, MediaType.Audio, language = "enm")
        val japanese = stream(2, MediaType.Audio, language = "jpn", disposition = Disposition(default = true))
        assertEquals(japanese, TrackSelector(listOf("en")).selectAudio(listOf(middleEnglish, japanese)))
    }

    @Test
    fun aCodeThatNamesNoLanguageMatchesNothing() {
        val undetermined = stream(1, MediaType.Audio, language = "und")
        val japanese = stream(2, MediaType.Audio, language = "jpn", disposition = Disposition(default = true))
        assertEquals(japanese, TrackSelector(listOf("und")).selectAudio(listOf(undetermined, japanese)))
        assertEquals(japanese, TrackSelector(listOf("qaa")).selectAudio(listOf(stream(3, MediaType.Audio, language = "qaa"), japanese)))
    }

    @Test
    fun aTagThatIsNoCodeMatchesItsOwnSpelling() {
        val klingon = stream(1, MediaType.Audio, language = "x-klingon")
        val japanese = stream(2, MediaType.Audio, language = "jpn", disposition = Disposition(default = true))
        assertEquals(klingon, TrackSelector(listOf("X-Klingon")).selectAudio(listOf(japanese, klingon)))
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
