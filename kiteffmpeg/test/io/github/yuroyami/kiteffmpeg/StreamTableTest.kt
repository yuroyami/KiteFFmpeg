package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The stream table and the held packets behind a source's growing lists (#151), driven by a scripted
 * context. The contract suite reads a real transport stream through every backend; these cases cover
 * what that stream cannot reach, such as a seek while packets are held and the memory bound.
 */
class StreamTableTest {

    /** A context whose streams, entries and programmes a case changes between reads. */
    private class Context(var entries: MutableList<StreamInfo>, var programs: List<Program> = emptyList()) {
        var stamp = 1L
        var entryReads = 0

        fun table() = StreamTable(
            entries.toList(),
            programs,
            readStamp = { stamp },
            readCount = { entries.size },
            readEntry = { index -> entryReads++; entries[index] },
            readPrograms = { streams -> programs.map { it.copy(streamIndexes = it.streamIndexes.filter { i -> i < streams.size }) } },
        )
    }

    private fun picture(index: Int, width: Int = 16) = StreamInfo(
        index = index,
        type = MediaType.Video,
        codec = CodecId("mpeg4"),
        timeBase = Rational(1, 90_000),
        durationMicros = null,
        bitrateBps = null,
        video = VideoStreamInfo(
            width = width,
            height = 16,
            pixelFormat = PixelFormat("yuv420p"),
            frameRate = Rational(5, 1),
            sampleAspectRatio = Rational(1, 1),
        ),
    )

    private fun sound(index: Int, codec: String, rate: Int, channels: Int) = StreamInfo(
        index = index,
        type = MediaType.Audio,
        codec = CodecId(codec),
        timeBase = Rational(1, 90_000),
        durationMicros = null,
        bitrateBps = null,
        audio = AudioStreamInfo(sampleRate = rate, channels = channels, sampleFormat = SampleFormat("s16p")),
    )

    @Test
    fun aReadThatLeavesTheStampAloneChangesNothing() {
        val context = Context(mutableListOf(picture(0)))
        val table = context.table()
        context.entries += sound(1, "mp2", 16_000, 1)

        assertEquals(emptyList(), table.afterRead(0), "the stamp did not move, so nothing is read")
        assertEquals(1, table.streams.size)
        assertEquals(0, context.entryReads)
        assertNull(table.takeStreamsChange())
    }

    @Test
    fun aStreamAddedInTheReadOfItsOwnPacketIsSettled() {
        val context = Context(mutableListOf(picture(0)), listOf(Program(1, 1, listOf(0))))
        val table = context.table()
        context.entries += sound(1, "mp2", 16_000, 1)
        context.entries += sound(2, "mp3", 0, 0)
        context.programs = listOf(Program(1, 1, listOf(0, 1, 2)))
        context.stamp++

        assertEquals(listOf(1, 2), table.afterRead(1))
        assertEquals(listOf("mpeg4", "mp2", "mp3"), table.streams.map { it.codec.name })
        assertEquals(listOf(0, 1, 2), table.programs.single().streamIndexes)
        assertSame(table.streams, table.takeStreamsChange())
        assertNull(table.takeStreamsChange(), "a change is taken once")
        assertEquals(table.programs, table.takeProgramsChange())

        // The stream's next packet does not read it again; the other stream's first one does.
        val reads = context.entryReads
        table.afterRead(1)
        assertEquals(reads, context.entryReads, "the stream added with its own packet was already settled")
        context.entries[2] = sound(2, "mp2", 16_000, 1)
        table.afterRead(2)
        assertEquals(reads + 1, context.entryReads)
        assertEquals("mp2", table.streams[2].codec.name)
        assertEquals(table.streams, table.takeStreamsChange())
        table.afterRead(2)
        assertEquals(reads + 1, context.entryReads, "an entry is read again once, at its stream's first packet")
    }

    @Test
    fun anOpenEntryThatSaysLessIsReadAgainAtItsFirstPacket() {
        val context = Context(mutableListOf(picture(0), sound(1, "mp3", 0, 0), sound(2, "mp2", 48_000, 2), picture(3, width = 0)))
        val table = context.table()
        val open = table.streams
        context.entries[1] = sound(1, "mp2", 16_000, 1)

        table.afterRead(0)
        table.afterRead(2)
        assertEquals(0, context.entryReads, "a complete entry is never read again")
        table.afterRead(3)
        assertNull(table.takeStreamsChange(), "a picture that still has no size is not a change")
        table.afterRead(1)
        assertEquals("mp2", table.streams[1].codec.name)
        assertEquals(table.streams, table.takeStreamsChange())

        assertTrue(table.owns(open[1]), "the replaced entry still names its stream")
        assertTrue(table.owns(table.streams[1]))
        assertTrue(open.all(table::owns))
        assertFalse(table.owns(open[1].copy(metadata = mapOf("language" to "deu"))))
        assertFalse(table.owns(sound(4, "mp2", 16_000, 1)))
        assertFailsWith<IllegalArgumentException> { table.requireOwn(open[1].copy(index = 2)) }
        assertSame(open[0], table.streams[0], "an entry that did not change is the same entry")
    }

    @Test
    fun aProgrammeTableThatReadsTheSameIsNoChange() {
        val context = Context(mutableListOf(picture(0)), listOf(Program(1, 1, listOf(0))))
        val table = context.table()
        context.stamp++
        table.afterRead(0)
        assertNull(table.takeProgramsChange())
        context.programs = listOf(Program(1, 1, listOf(0), mapOf("service_name" to "News")))
        context.stamp++
        table.afterRead(0)
        assertEquals(context.programs, table.takeProgramsChange())
    }

    /** Packets as numbers, each with a size, and a record of which were freed. */
    private class Packets {
        val freed = mutableListOf<Int>()
        val sizes = HashMap<Int, Int>()
        val held = HeldPackets<Int>(sizeOf = { sizes[it] ?: 100 }, free = { freed += it })
    }

    @Test
    fun aSelectedStreamGetsItsHeldPacketsInOrder() {
        val packets = Packets()
        val held = packets.held
        held.added(listOf(1, 2))
        held.hold(1, 1_000_001)
        held.hold(2, 2_000_001)
        held.hold(1, 1_000_002)
        assertTrue(held.isWaiting(1) && held.isWaiting(2))

        held.announce()
        assertTrue(held.decide(selected = setOf(0, 1), seeking = false), "the second stream was not selected")
        assertEquals(setOf<Int>(), held.waitingStreams)
        assertEquals(listOf(2_000_001), packets.freed)
        assertEquals(1 to 1_000_001, held.next(setOf(0, 1)))
        assertEquals(1 to 1_000_002, held.next(setOf(0, 1)))
        assertNull(held.next(setOf(0, 1)))
        assertFalse(held.hasDecisions)
    }

    @Test
    fun anUnannouncedStreamKeepsWaitingAndASeekDropsItsPackets() {
        val packets = Packets()
        val held = packets.held
        held.added(listOf(3))
        held.hold(3, 3_000_001)

        assertFalse(held.decide(selected = setOf(0), seeking = false))
        assertTrue(held.isWaiting(3), "no packet announced it yet")
        assertNull(held.next(setOf(0)))
        assertEquals(emptyList(), packets.freed)

        assertFalse(held.decide(selected = setOf(0), seeking = true))
        assertTrue(held.isWaiting(3), "a seek does not end the wait, only the packets from before it")
        assertEquals(listOf(3_000_001), packets.freed)

        // The caller selected it on its own, before any announcement: its packets are handed out.
        held.hold(3, 3_000_002)
        assertEquals(3 to 3_000_002, held.next(setOf(0, 3)))
    }

    @Test
    fun aSeekDropsTheHeldPacketsOfASelectedStream() {
        val packets = Packets()
        val held = packets.held
        held.added(listOf(1))
        held.hold(1, 1_000_001)
        held.announce()
        assertFalse(held.decide(selected = setOf(0, 1), seeking = true))
        assertEquals(listOf(1_000_001), packets.freed)
        assertNull(held.next(setOf(0, 1)))
    }

    @Test
    fun theOldestHeldPacketGoesPastTheLimit() {
        val packets = Packets()
        val held = packets.held
        held.added(listOf(1))
        for (n in 1..5) {
            packets.sizes[n] = (HeldPackets.HELD_BYTES_LIMIT / 4).toInt()
            held.hold(1, n)
        }
        assertEquals(listOf(1), packets.freed, "five quarters of the limit drop the first")
        assertEquals(1 to 2, held.next(setOf(1)))

        held.clear()
        assertEquals(listOf(1, 3, 4, 5), packets.freed, "closing frees what is left, and the packet handed out is the caller's")
        assertFalse(held.isWaiting(1))
    }

    @Test
    fun oneHeldPacketBiggerThanTheLimitStays() {
        val packets = Packets()
        val held = packets.held
        packets.sizes[1] = (HeldPackets.HELD_BYTES_LIMIT * 2).toInt()
        held.added(listOf(1))
        held.hold(1, 1)
        assertEquals(emptyList(), packets.freed)
    }
}
