package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web half of streams added after the open (#151). The contract suite reads a real transport
 * stream through the JVM and native backends; the web build has no FFmpeg here, so a scripted
 * demuxer changes its tables the way that stream does and these tests check the same promises.
 *
 * The demuxer's eight reads hand out packets of streams 0, 1, 2, 2, 0, 2, 1 and 0, and a packet's
 * pts is the number of the read that made it. Its second read adds stream 2 and puts it in the one
 * programme, before FFmpeg knows the stream's codec, so the entry reads `none`. Its third read, the
 * stream's first packet, is where FFmpeg's parser learns the codec, and the stream's time base moves
 * from milliseconds to the transport stream's 90 kHz with it. A seek moves it to its sixth
 * read, and it skips stream 2's packets while the stream is discarded, as FFmpeg does.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebLateStreamTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun anAddedStreamIsAnnouncedOnTheNextPacketAndCorrectedAtItsFirstPacket() {
        val module = lateModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val open = source.streams
            assertEquals(2, open.size)
            assertEquals(listOf(listOf(0, 1)), source.programs.map { it.streamIndexes })

            source.openPacketReader(open).use { reader ->
                reader.read()!!.use { assertNull(it.newStreams, "nothing changed yet") }
                val announced = reader.read()!!.use {
                    assertEquals(1, it.streamIndex)
                    assertEquals(listOf(listOf(0, 1, 2)), it.newPrograms?.map { p -> p.streamIndexes })
                    assertNotNull(it.newStreams)
                }
                assertEquals("none", announced[2].codec.name, "FFmpeg had no packet of the stream yet")
                assertEquals(Rational(1, 1000), announced[2].timeBase)
                assertEquals(announced, source.streams)
                assertEquals(listOf(0, 1, 2), source.programs.single().streamIndexes)

                // Added with the entry the announcement carried, which its first packet corrects.
                reader.reselect(open + announced[2])
                assertTrue(lateStreamSelected(module))
                val corrected = reader.read()!!.use {
                    assertEquals(2L to 2, it.pts to it.streamIndex)
                    assertEquals(Rational(1, 90_000), it.timeBase, "the packet takes the corrected entry's time base")
                    assertNull(it.newPrograms)
                    assertNotNull(it.newStreams)
                }
                assertEquals("webvtt", corrected[2].codec.name)
                assertEquals(corrected, source.streams)

                // The replaced entry still names the stream.
                reader.reselect(open)
                assertFalse(lateStreamSelected(module))
                reader.reselect(open + announced[2])
                assertTrue(lateStreamSelected(module))
                assertEquals(listOf(3L to 2, 4L to 0, 5L to 2, 6L to 1, 7L to 0), reader.drain())
            }
            assertEquals(0, livePackets(module), "every packet the reader read was freed")
        }
    }

    @Test
    fun theHeldPacketsOfAnAddedStreamComeFirst() {
        val module = lateModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            source.openPacketReader(listOf(source.streams[0])).use { reader ->
                reader.read()!!.use { assertNull(it.newStreams) }
                val announced = reader.read()!!.use {
                    assertEquals(4L, it.pts, "the stream's own packets went by unannounced")
                    assertNotNull(it.newStreams)
                }
                assertEquals("webvtt", announced[2].codec.name, "corrected before any packet carried it")
                reader.reselect(listOf(announced[0], announced[2]))
                assertEquals(listOf(2L to 2, 3L to 2, 5L to 2, 7L to 0), reader.drain())
            }
            assertEquals(0, livePackets(module))
        }
    }

    @Test
    fun aStreamTheCallerDoesNotAddLosesItsHeldPackets() {
        val module = lateModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            source.openPacketReader(listOf(source.streams[0])).use { reader ->
                reader.read()!!.close()
                reader.read()!!.use { assertNotNull(it.newStreams) }
                assertTrue(lateStreamSelected(module), "FFmpeg keeps handing out a waiting stream")
                assertEquals(listOf(7L to 0), reader.drain())
                assertFalse(lateStreamSelected(module))
                assertEquals(0, livePackets(module), "the held packets went at the read after the announcement")
            }
        }
    }

    @Test
    fun aSeekDropsTheHeldPacketsAndACloseFreesThem() {
        val module = lateModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            source.openPacketReader(listOf(source.streams[0])).use { reader ->
                reader.read()!!.close()
                val announced = reader.read()!!.use { it.newStreams!! }
                reader.reselect(listOf(announced[0], announced[2]))
                reader.seek(0, SeekDirection.Backward)
                assertEquals(0, livePackets(module), "the packets from before the seek went")
                assertEquals(listOf(5L to 2, 7L to 0), reader.drain())
            }
        }

        val again = lateModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val reader = source.openPacketReader(listOf(source.streams[0]))
            reader.read()!!.close()
            reader.read()!!.close()
            assertEquals(2, livePackets(again), "the stream's two packets are held")
            reader.close()
            assertEquals(0, livePackets(again), "a closed reader frees what it held")
        }
    }

    @Test
    fun aStreamNoPacketAnnouncedYetKeepsComingThroughAReselect() {
        // The reads end before any selected packet could announce stream 2, and the caller, who
        // reads the source's lists, selects the other stream and seeks back into the new one.
        lateModule(script = "0,1,2,2,1", seekTo = 2)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            source.openPacketReader(listOf(source.streams[0])).use { reader ->
                reader.read()!!.close()
                assertNull(reader.read(), "the end came before any packet announced stream 2")
                val streams = source.streams
                assertEquals(3, streams.size)

                reader.reselect(streams.take(2))
                reader.seek(0, SeekDirection.Backward)
                val announced = reader.read()!!.use {
                    assertEquals(4L to 1, it.pts to it.streamIndex)
                    assertNotNull(it.newStreams)
                }
                reader.reselect(announced)
                assertEquals(listOf(2L to 2, 3L to 2), reader.drain(), "its packets from after the seek were held")
            }
        }
    }

    @Test
    fun aChangeNoPacketCarriedRidesTheNextReadersFirstPacket() {
        lateModule(script = "0,1,2,2,1", seekTo = 0)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            source.openPacketReader(listOf(source.streams[0])).use { reader ->
                reader.read()!!.close()
                assertNull(reader.read(), "the end came before any packet announced stream 2")
            }
            val listed = source.streams
            source.openPacketReader(listOf(listed[0])).use { reader ->
                reader.seek(0)
                reader.read()!!.use {
                    assertEquals(0L to 0, it.pts to it.streamIndex)
                    assertEquals(listed, it.newStreams, "the reader before handed out no packet after the change")
                    assertEquals(listOf(listOf(0, 1, 2)), it.newPrograms?.map { p -> p.streamIndexes })
                }
                assertEquals(emptyList(), reader.drain(), "a stream this reader did not select is skipped")
            }
        }
    }

    @Test
    fun aDecoderOpensOnlyFromThisSourcesEntries() {
        val module = fakeDecodeCodecModule()
        useCodecModule(module)
        setFakeDecodeScript(module, "gg")
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val own = source.streams[0]
            source.openDecoder(own).close()
            // A forged entry with a valid index would decode this source's stream as another file's.
            val forged = own.copy(metadata = mapOf("language" to "deu"))
            assertFailsWith<IllegalArgumentException> { source.openDecoder(forged) }
        }
    }

    /** Every packet left, as its pts and its stream, each closed. */
    private fun PacketReader.drain(): List<Pair<Long, Int>> = buildList {
        while (true) {
            val packet = read() ?: break
            packet.use { add(it.pts to it.streamIndex) }
        }
    }

    private fun lateModule(script: String = "0,1,2,2,0,2,1,0", seekTo: Int = 5): JsAny =
        installFakeLateStream(fakePacketReaderCodecModule(), script, seekTo).also(::useCodecModule)

    /** The smallest byte source `MediaSource.open` accepts; the fake demuxer ignores its content. */
    private class OneByteSource : MediaByteSource {
        override val size: Long = 1L
        override val seekable: Boolean = true
        private var consumed = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (consumed) return -1
            into[offset] = 0
            consumed = true
            return 1
        }

        override fun seek(position: Long) {
            consumed = position != 0L
        }

        override fun close(): Unit = Unit
    }
}

/**
 * Adds stream 2 at the demuxer's second read, as a transport stream's new programme table does, and
 * its codec at the third, with the layout stamp moving at the second. [script] names each read's
 * stream, and a seek moves to the read at [seekTo]. The packets are counted from their allocation
 * to their free, so a test sees a held packet leak.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, scriptText, seekTo) => {
        const CONTEXT = 0x500;
        const STREAM = 0x600;
        const CODECPAR = 0x700;
        const EOF = -541478725;
        const cstr = (s) => {
            const n = m.lengthBytesUTF8(s) + 1;
            const p = m._malloc(n);
            m.stringToUTF8(s, p, n);
            return p;
        };
        const NONE = cstr("none");
        const WEBVTT = cstr("webvtt");

        const script = scriptText.split(",").map(Number);
        let cursor = 0;
        let count = 2;
        let stamp = 1n;
        let lateCodec = 0;
        let programme = [0, 1];
        let lateSelected = true;
        const streamOf = new Map();
        const readOf = new Map();
        const live = new Set();

        m._ffkmp_fmt_nb_streams = (ctx) => ctx === CONTEXT ? count : 0;
        m._ffkmp_fmt_stream = (ctx, index) =>
            ctx === CONTEXT && index >= 0 && index < count ? STREAM + index : 0;
        m._ffkmp_codecpar_codec_id = (par) => par === CODECPAR + 2 ? lateCodec : 1;
        m._ffkmp_codec_id_name = (id) => id === 0 ? NONE : WEBVTT;
        m._ffkmp_fmt_layout_stamp = () => stamp;
        m._ffkmp_stream_time_base = (stream, num, den) => {
            m.HEAP32[num >> 2] = 1;
            m.HEAP32[den >> 2] = stream === STREAM + 2 && lateCodec ? 90000 : 1000;
        };

        m._ffkmp_fmt_program_count = () => 1;
        m._ffkmp_fmt_program_get = (ctx, index, idOut, numberOut, countOut) => {
            if (index !== 0) return -22;
            m.HEAP32[idOut >> 2] = 1;
            m.HEAP32[numberOut >> 2] = 1;
            m.HEAP32[countOut >> 2] = programme.length;
            return 0;
        };
        m._ffkmp_fmt_program_stream = (ctx, index, position) =>
            index === 0 && position >= 0 && position < programme.length ? programme[position] : -22;
        m._ffkmp_fmt_program_metadata = () => 0;

        const none = m._ffkmp_stream_discard_none;
        const all = m._ffkmp_stream_discard_all;
        m._ffkmp_stream_discard_none = (stream) => { if (stream === STREAM + 2) lateSelected = true; else none(stream); };
        m._ffkmp_stream_discard_all = (stream) => { if (stream === STREAM + 2) lateSelected = false; else all(stream); };

        m._ffkmp_packet_alloc = () => { const p = m._malloc(16); live.add(p); return p; };
        m._ffkmp_fmt_read_frame = (ctx, packet) => {
            while (cursor < script.length) {
                const read = cursor++;
                if (read === 1 && count === 2) {
                    count = 3;
                    programme = [0, 1, 2];
                    stamp = 2n;
                }
                if (read === 2) lateCodec = 1;
                if (script[read] === 2 && !lateSelected) continue;
                streamOf.set(packet, script[read]);
                readOf.set(packet, read);
                return 0;
            }
            return EOF;
        };
        m._ffkmp_packet_stream_index = (packet) => streamOf.has(packet) ? streamOf.get(packet) : -1;
        m._ffkmp_packet_pts = (packet) => BigInt(readOf.has(packet) ? readOf.get(packet) : -1);
        m._ffkmp_packet_size = () => 100;
        m._ffkmp_packet_unref = (packet) => { streamOf.delete(packet); readOf.delete(packet); };
        m._ffkmp_packet_free = (packet) => { streamOf.delete(packet); readOf.delete(packet); live.delete(packet); };
        m._ffkmp_avseek_flag_backward = () => 1;
        m._ffkmp_avseek_flag_any = () => 4;
        m._ffkmp_fmt_seek_file = () => { cursor = seekTo; return 0; };

        m.__lateSelected = () => lateSelected;
        m.__livePackets = () => live.size;
        return m;
    }""",
)
private external fun installFakeLateStream(module: JsAny, scriptText: String, seekTo: Int): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__lateSelected()")
private external fun lateStreamSelected(module: JsAny): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__livePackets()")
private external fun livePackets(module: JsAny): Int
