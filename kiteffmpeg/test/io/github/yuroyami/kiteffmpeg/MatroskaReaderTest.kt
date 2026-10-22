package io.github.yuroyami.kiteffmpeg

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reading of a Matroska segment's `Info` and `Chapters` payloads (#173), from payloads written
 * here with the element IDs and value encodings of RFC 9559, so that a field read from the wrong ID,
 * a flag read with the wrong default, or a nesting read in the wrong order comes out wrong.
 */
class MatroskaReaderTest {

    /** A minimal EBML writer: each element is its ID, its size in the fewest bytes, and its payload. */
    private class Ebml {
        private val out = mutableListOf<Byte>()

        fun bytes(): ByteArray = out.toByteArray()

        fun raw(vararg values: Int) {
            values.forEach { out += it.toByte() }
        }

        fun element(id: Int, payload: ByteArray) {
            var idLength = 1
            while (idLength < 4 && id ushr (8 * idLength) != 0) idLength++
            for (i in idLength - 1 downTo 0) out += (id ushr (8 * i)).toByte()
            var sizeLength = 1
            while (payload.size.toLong() >= (1L shl (7 * sizeLength)) - 1) sizeLength++
            val size = payload.size.toLong() or (1L shl (7 * sizeLength))
            for (i in sizeLength - 1 downTo 0) out += (size ushr (8 * i)).toByte()
            payload.forEach { out += it }
        }

        fun uint(id: Int, value: Long) {
            var length = 1
            while (length < 8 && value ushr (8 * length) != 0L) length++
            element(id, ByteArray(length) { (value ushr (8 * (length - 1 - it))).toByte() })
        }

        fun string(id: Int, value: String) = element(id, value.encodeToByteArray())

        fun master(id: Int, body: Ebml.() -> Unit) = element(id, Ebml().apply(body).bytes())
    }

    private fun payload(body: Ebml.() -> Unit): ByteArray = Ebml().apply(body).bytes()

    private fun uid(first: Int): ByteArray = ByteArray(16) { (first + it).toByte() }

    private fun hex(first: Int): String = uid(first).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val ownUid = 0xA0
    private val otherUid = 0x10

    private fun info(body: Ebml.() -> Unit = {}): ByteArray = payload {
        uint(0x2AD7B1, 1_000_000)
        element(0x73A4, uid(ownUid))
        body()
    }

    private fun Ebml.atom(
        uid: Long,
        start: Long,
        end: Long? = null,
        body: Ebml.() -> Unit = {},
    ) = master(0xB6) {
        uint(0x73C4, uid)
        uint(0x91, start)
        if (end != null) uint(0x92, end)
        body()
    }

    private fun Ebml.edition(uid: Long, body: Ebml.() -> Unit) = master(0x45B9) {
        uint(0x45BC, uid)
        body()
    }

    @Test
    fun theInfoNamesTheSegmentItsNeighboursAndItsFamilies() {
        val segment = MatroskaReader.segment(
            info {
                raw(0xEC, 0x82, 0, 0) // a Void element, which carries nothing
                string(0x7384, "episode.mkv")
                element(0x3CB923, uid(0x30))
                string(0x3C83AB, "previous.mkv")
                element(0x3EB923, uid(0x40))
                string(0x3E83BB, "next.mkv")
                element(0x4444, uid(0x50))
                element(0x4444, uid(0x60))
                string(0x4D80, "a muxer")
            },
            null,
        )
        assertEquals(
            MatroskaSegment(
                uid = hex(ownUid),
                filename = "episode.mkv",
                previousUid = hex(0x30),
                previousFilename = "previous.mkv",
                nextUid = hex(0x40),
                nextFilename = "next.mkv",
                families = listOf(hex(0x50), hex(0x60)),
                editions = emptyList(),
            ),
            segment,
        )
        assertNull(segment.defaultEdition)
    }

    @Test
    fun aUidThatIsNotSixteenBytesReadsAsAbsent() {
        val segment = MatroskaReader.segment(
            payload {
                element(0x73A4, ByteArray(15))
                element(0x3CB923, ByteArray(17))
                element(0x4444, ByteArray(0))
            },
            null,
        )
        assertNull(segment.uid)
        assertNull(segment.previousUid)
        assertEquals(emptyList(), segment.families)
    }

    @Test
    fun editionsKeepTheirStoredOrderFlagsAndNames() {
        val chapters = payload {
            edition(1) {
                uint(0x45BD, 1)
                master(0x4520) {
                    string(0x4521, "Theatrical")
                    string(0x45E4, "en")
                    string(0x45E4, "en-GB")
                }
                atom(11, 0)
            }
            edition(2) {
                uint(0x45DB, 1)
                uint(0x45DD, 1)
                master(0x4520) { string(0x4521, "Extended") }
                atom(21, 0, 1_000)
            }
        }
        val segment = MatroskaReader.segment(info(), chapters)
        val (first, second) = segment.editions
        assertEquals(1L, first.uid)
        assertTrue(first.isHidden)
        assertFalse(first.isDefault)
        assertFalse(first.isOrdered)
        assertEquals(listOf(MatroskaName("Theatrical", listOf("en", "en-GB"), emptyList())), first.names)
        assertEquals(2L, second.uid)
        assertFalse(second.isHidden)
        assertTrue(second.isDefault)
        assertTrue(second.isOrdered)
        assertEquals(listOf(MatroskaName("Extended", emptyList(), emptyList())), second.names)
        assertEquals(second, segment.defaultEdition)
    }

    @Test
    fun withNoEditionFlaggedDefaultTheFirstIsTheDefault() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(1) { atom(11, 0) }
                edition(2) { atom(21, 0) }
            },
        )
        assertEquals(1L, segment.defaultEdition?.uid)
    }

    @Test
    fun aChapterReadsEveryFieldWithItsDefaults() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    atom(11, 0) {
                        string(0x5654, "cue-1")
                    }
                    atom(12, 2_000, 3_000) {
                        uint(0x98, 1)
                        uint(0x4598, 0)
                        element(0x6E67, uid(otherUid))
                        uint(0x6EBC, 77)
                        uint(0x4588, 2)
                    }
                }
            },
        )
        val (plain, flagged) = segment.editions.single().chapters
        assertEquals(
            MatroskaChapter(
                uid = 11,
                stringUid = "cue-1",
                startNanos = 0,
                endNanos = null,
                isHidden = false,
                isEnabled = true,
                segmentUid = null,
                segmentEditionUid = null,
                skipType = null,
                names = emptyList(),
                chapters = emptyList(),
            ),
            plain,
        )
        assertEquals(
            MatroskaChapter(
                uid = 12,
                stringUid = null,
                startNanos = 2_000,
                endNanos = 3_000,
                isHidden = true,
                isEnabled = false,
                segmentUid = hex(otherUid),
                segmentEditionUid = 77,
                skipType = MatroskaSkipType.EndCredits,
                names = emptyList(),
                chapters = emptyList(),
            ),
            flagged,
        )
    }

    @Test
    fun everySkipTypeReadsAsItsOwnAndAnUnknownOneAsNone() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    for (type in 0L..8L) atom(100 + type, type) { uint(0x4588, type) }
                }
            },
        )
        assertEquals(
            MatroskaSkipType.entries + listOf(null),
            segment.editions.single().chapters.map { it.skipType },
        )
    }

    @Test
    fun aLinkToThisSegmentsOwnUidReadsAsNoLink() {
        val segment = MatroskaReader.segment(
            info(),
            payload { edition(1) { atom(11, 0, 500) { element(0x6E67, uid(ownUid)) } } },
        )
        assertNull(segment.editions.single().chapters.single().segmentUid)
    }

    @Test
    fun aSegmentWithNoUidKeepsEveryLink() {
        val segment = MatroskaReader.segment(
            payload { uint(0x2AD7B1, 1_000_000) },
            payload { edition(1) { atom(11, 0, 500) { element(0x6E67, uid(ownUid)) } } },
        )
        assertEquals(hex(ownUid), segment.editions.single().chapters.single().segmentUid)
    }

    @Test
    fun aChapterNameTakesItsBcp47TagsOverItsCodesAndCountries() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    atom(11, 0) {
                        master(0x80) {
                            string(0x85, "Ouverture")
                            string(0x437C, "fre")
                            string(0x437E, "fr")
                            string(0x437D, "fr-CA")
                        }
                        master(0x80) {
                            string(0x85, "Opening")
                            string(0x437C, "eng")
                            string(0x437C, "jpn")
                            string(0x437E, "us")
                        }
                        master(0x80) { string(0x85, "Untagged") }
                    }
                }
            },
        )
        assertEquals(
            listOf(
                MatroskaName("Ouverture", listOf("fr-CA"), emptyList()),
                MatroskaName("Opening", listOf("eng", "jpn"), listOf("us")),
                MatroskaName("Untagged", listOf("eng"), emptyList()),
            ),
            segment.editions.single().chapters.single().names,
        )
    }

    @Test
    fun nestedChaptersKeepTheirOrderAndDepth() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    atom(1, 0, 3_000) {
                        atom(11, 0, 1_000)
                        atom(12, 1_000, 3_000) { atom(121, 1_000, 2_000) }
                    }
                    atom(2, 3_000, 4_000)
                }
            },
        )
        val chapters = segment.editions.single().chapters
        assertEquals(listOf(1L, 2L), chapters.map { it.uid })
        assertEquals(listOf(11L, 12L), chapters[0].chapters.map { it.uid })
        assertEquals(listOf(121L), chapters[0].chapters[1].chapters.map { it.uid })
    }

    @Test
    fun anOrderedEditionPlaysItsEnabledLowestLevelChaptersDepthFirst() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    uint(0x45DD, 1)
                    atom(1, 0, 3_000) {
                        atom(11, 0, 1_000) { uint(0x98, 1) }
                        atom(12, 1_000, 2_000) { uint(0x4598, 0) }
                        atom(13, 2_000, 3_000) { element(0x6E67, uid(otherUid)) }
                    }
                    atom(2, 3_000, 4_000) {
                        uint(0x4598, 0)
                        atom(21, 3_000, 4_000)
                    }
                    atom(3, 4_000, 5_000)
                }
            },
        )
        assertEquals(listOf(11L, 13L, 3L), segment.editions.single().playlist?.map { it.uid })
    }

    @Test
    fun aPlaylistIsNullWhenTheEditionIsNotOrderedOrAPlayedChapterHasNoEnd() {
        val plain = MatroskaReader.segment(info(), payload { edition(1) { atom(1, 0, 1_000) } })
        assertNull(plain.editions.single().playlist)
        val endless = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    uint(0x45DD, 1)
                    atom(1, 0, 1_000)
                    atom(2, 1_000)
                }
            },
        )
        assertNull(endless.editions.single().playlist)
        val endlessButDisabled = MatroskaReader.segment(
            info(),
            payload {
                edition(1) {
                    uint(0x45DD, 1)
                    atom(1, 0, 1_000)
                    atom(2, 1_000) { uint(0x4598, 0) }
                }
            },
        )
        assertEquals(listOf(1L), endlessButDisabled.editions.single().playlist?.map { it.uid })
    }

    @Test
    fun theFirstOfARepeatedSingleElementWins() {
        val segment = MatroskaReader.segment(
            info {
                string(0x7384, "first.mkv")
                string(0x7384, "second.mkv")
            },
            payload {
                edition(1) {
                    atom(11, 5) {
                        uint(0x91, 9)
                        uint(0x73C4, 99)
                    }
                }
            },
        )
        assertEquals("first.mkv", segment.filename)
        val chapter = segment.editions.single().chapters.single()
        assertEquals(11L, chapter.uid)
        assertEquals(5L, chapter.startNanos)
    }

    @Test
    fun integersUseAllEightBytesAndAnEmptyOneIsZero() {
        val segment = MatroskaReader.segment(
            info(),
            payload {
                edition(-1L) {
                    atom(-2L, Long.MAX_VALUE)
                    master(0xB6) {
                        uint(0x73C4, 3)
                        element(0x91, ByteArray(0))
                        element(0x92, ByteArray(9) { 1 })
                    }
                }
            },
        )
        val edition = segment.editions.single()
        assertEquals(-1L, edition.uid)
        assertEquals(-2L, edition.chapters[0].uid)
        assertEquals(Long.MAX_VALUE, edition.chapters[0].startNanos)
        assertEquals(0L, edition.chapters[1].startNanos)
        assertNull(edition.chapters[1].endNanos)
    }

    @Test
    fun aStringEndsAtItsPaddingAndBadUtf8IsReplaced() {
        val segment = MatroskaReader.segment(
            info {
                element(0x7384, "padded.mkv".encodeToByteArray() + ByteArray(6))
                element(0x3C83AB, byteArrayOf('a'.code.toByte(), 0xC3.toByte()))
            },
            null,
        )
        assertEquals("padded.mkv", segment.filename)
        assertEquals("a�", segment.previousFilename)
    }

    @Test
    fun damageEndsItsParentAndKeepsWhatCameBefore() {
        val good = payload {
            edition(1) { atom(11, 0) }
        }
        // An edition whose size runs past the payload, after one that is whole.
        val truncated = good + payload { edition(2) { atom(21, 0) } }.let { it.copyOf(it.size - 3) }
        assertEquals(listOf(1L), MatroskaReader.segment(info(), truncated).editions.map { it.uid })
        // An element of unknown size, which these payloads never hold, written in one byte as 127
        // ones, with 127 bytes after it that a reader taking the size at its word would read.
        val unknown = good + byteArrayOf(0x45, 0xB9.toByte(), 0xFF.toByte()) + ByteArray(127)
        assertEquals(listOf(1L), MatroskaReader.segment(info(), unknown).editions.map { it.uid })
        // An ID longer than four bytes, and a byte that starts no ID at all.
        val longId = good + byteArrayOf(0x08, 1, 2, 3, 4, 0x81.toByte(), 0)
        assertEquals(listOf(1L), MatroskaReader.segment(info(), longId).editions.map { it.uid })
        val zero = good + byteArrayOf(0, 0x81.toByte(), 0)
        assertEquals(listOf(1L), MatroskaReader.segment(info(), zero).editions.map { it.uid })
        // A chapter cut short inside its edition keeps its edition.
        val inner = payload {
            edition(4) {
                atom(41, 0)
                raw(0xB6, 0x88, 0x73, 0xC4)
            }
        }
        assertEquals(listOf(41L), MatroskaReader.segment(info(), inner).editions.single().chapters.map { it.uid })
    }

    @Test
    fun chaptersNestedPastTheLimitAreLeftOutWithoutRunningOutOfStack() {
        fun Ebml.nest(depth: Int) {
            atom(depth.toLong(), 0) { if (depth < 200) nest(depth + 1) }
        }
        val segment = MatroskaReader.segment(info(), payload { edition(1) { nest(1) } })
        var depth = 0
        var level = segment.editions.single().chapters
        while (level.isNotEmpty()) {
            depth++
            level = level.single().chapters
        }
        assertEquals(MatroskaReader.MAX_DEPTH, depth)
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(173)
        val valid = payload {
            edition(1) {
                uint(0x45DD, 1)
                atom(1, 0, 1_000) {
                    master(0x80) { string(0x85, "One") }
                    atom(11, 0, 500)
                }
            }
        }
        repeat(2_000) {
            val bytes = if (it % 2 == 0) {
                ByteArray(random.nextInt(0, 200)).also(random::nextBytes)
            } else {
                valid.copyOf().also { copy -> repeat(3) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() } }
            }
            MatroskaReader.segment(bytes, bytes)
        }
    }

    @Test
    fun onlyAMatroskaSourceThatExportsItsInfoHasASegment() {
        val asked = mutableListOf<String>()
        assertNull(MatroskaReader.read("mov,mp4,m4a,3gp,3g2,mj2") { asked += it; info() })
        assertEquals(emptyList(), asked)
        assertNull(MatroskaReader.read("matroska,webm") { null })
        val segment = MatroskaReader.read("matroska,webm") { if (it == "info_payload") info() else null }
        assertEquals(hex(ownUid), segment?.uid)
        assertEquals(emptyList(), segment?.editions)
    }
}
