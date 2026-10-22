package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The web half of tags that change during playback (#135). The module's helper reads and lowers
 * FFmpeg's flags, as on every other backend, so the fake below keeps flags with the helper's rules
 * and these tests check what the Kotlin layer adds: the open forgets what its probe raised, a
 * change rides the first packet handed out after it, a stream's change waits for that stream's
 * packet, [MediaSource.metadata] follows the container's changes and a stream's [StreamInfo] keeps
 * what it said at open.
 *
 * The scripted demuxer hands out packets of streams 0, 0, 1, 0, 1, 0 and 1. Its second read
 * applies new container tags, its third and fourth new tags to stream 1, and its fifth new
 * container tags again.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebTagChangesTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun eachChangeRidesTheFirstPacketOfItsOwnStreamAfterIt() {
        val module = taggingModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            assertEquals(-1, firstTakeIndex(module), "the open forgets what its probe raised before any read")
            assertEquals(mapOf("title" to "At open"), source.metadata)
            val streams = source.streams
            assertEquals(mapOf("title" to "Stream one"), streams[1].metadata)

            val seen = mutableListOf<Pair<Map<String, String>?, Map<String, String>?>>()
            val metadataAfter = mutableListOf<Map<String, String>>()
            source.openPacketReader(streams).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use {
                        it.copy().use { copy ->
                            assertEquals(it.newContainerTags, copy.newContainerTags, "a copy carries the change")
                            assertEquals(it.newStreamTags, copy.newStreamTags, "a copy carries the change")
                        }
                        seen += it.newContainerTags to it.newStreamTags
                    }
                    metadataAfter += source.metadata
                }
            }

            val second = mapOf("title" to "Second", "artist" to "Radio")
            val third = mapOf("title" to "Third")
            assertEquals(
                listOf<Pair<Map<String, String>?, Map<String, String>?>>(
                    null to null,
                    second to null,
                    null to mapOf("title" to "Next cue"),
                    // Stream 1 changed again on this read, which handed out stream 0's packet.
                    null to null,
                    third to mapOf("title" to "Skipped"),
                    null to null,
                    null to null,
                ),
                seen,
            )
            assertEquals(listOf(mapOf("title" to "At open"), second, second, second, third, third, third), metadataAfter)
            assertEquals(mapOf("title" to "Stream one"), streams[1].metadata, "a stream keeps what it said at open")
        }
    }

    @Test
    fun aChangeASkippedPacketBroughtWaitsForTheNextPacketHandedOut() {
        taggingModule()
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val seen = mutableListOf<Pair<Map<String, String>?, Map<String, String>?>>()
            source.openPacketReader(listOf(source.streams[0])).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use { seen += it.newContainerTags to it.newStreamTags }
                }
            }
            // Stream 0's packets are the first, second, fourth and sixth reads. The fifth read,
            // of a skipped packet, brought the third container tags, and stream 1's changes never
            // reach a reader that did not select it.
            assertEquals(
                listOf<Pair<Map<String, String>?, Map<String, String>?>>(
                    null to null,
                    mapOf("title" to "Second", "artist" to "Radio") to null,
                    null to null,
                    mapOf("title" to "Third") to null,
                ),
                seen,
            )
            assertEquals(mapOf("title" to "Third"), source.metadata)
        }
    }

    @Test
    fun aSourceThatNeverChangesHandsOutNoTags() {
        useCodecModule(fakePacketReaderCodecModule())
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            source.openPacketReader(source.streams).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use {
                        assertNull(it.newContainerTags)
                        assertNull(it.newStreamTags)
                    }
                }
            }
            assertEquals(emptyMap<String, String>(), source.metadata)
        }
    }

    private fun taggingModule(): JsAny = installFakeTagChanges(fakePacketReaderCodecModule()).also(::useCodecModule)

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
 * Adds tags that change as the scripted demuxer reads, with FFmpeg's flags kept the way
 * `ffkmp_fmt_take_tag_changes` reads and lowers them: 1 for the container, 2 for the named stream,
 * -1 naming every stream.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        const STREAM = 0x600;
        const cstr = (s) => {
            const b = new TextEncoder().encode(s);
            const p = m._malloc(b.length + 1);
            m.HEAPU8.set(b, p);
            m.HEAPU8[p + b.length] = 0;
            return p;
        };
        const dicts = {};
        let nextDict = 50;
        const dict = (pairs) => {
            const id = nextDict++;
            dicts[id] = pairs.map((kv) => [cstr(kv[0]), cstr(kv[1])]);
            return id;
        };
        m._ffkmp_dict_get = (d, prev) => {
            const list = dicts[d];
            if (!list) return 0;
            const next = prev === 0 ? 0 : (prev % 1000);
            return next >= list.length ? 0 : d * 1000 + next + 1;
        };
        m._ffkmp_dict_entry_key = (e) => dicts[Math.floor(e / 1000)][(e % 1000) - 1][0];
        m._ffkmp_dict_entry_value = (e) => dicts[Math.floor(e / 1000)][(e % 1000) - 1][1];

        let container = dict([["title", "At open"]]);
        const streams = [dict([["title", "Stream zero"]]), dict([["title", "Stream one"]])];
        let containerFlag = 0;
        const streamFlags = [0, 0];
        m._ffkmp_fmt_metadata = () => container;
        m._ffkmp_stream_metadata = (stream) => streams[stream - STREAM];

        // The probe applied tags of its own, which the open already reads as they stand.
        const probe = m._ffkmp_fmt_find_stream_info;
        m._ffkmp_fmt_find_stream_info = (ctx) => {
            containerFlag = 1;
            streamFlags[0] = 1;
            streamFlags[1] = 1;
            return probe(ctx);
        };

        const script = {
            1: { container: [["title", "Second"], ["artist", "Radio"]] },
            2: { stream: [1, [["title", "Next cue"]]] },
            3: { stream: [1, [["title", "Skipped"]]] },
            4: { container: [["title", "Third"]] },
        };
        const read = m._ffkmp_fmt_read_frame;
        let reads = 0;
        m._ffkmp_fmt_read_frame = (ctx, packet) => {
            const rc = read(ctx, packet);
            const change = script[reads++];
            if (rc >= 0 && change) {
                if (change.container) { container = dict(change.container); containerFlag = 1; }
                if (change.stream) { streams[change.stream[0]] = dict(change.stream[1]); streamFlags[change.stream[0]] = 1; }
            }
            return rc;
        };

        m.__takes = [];
        m._ffkmp_fmt_take_tag_changes = (ctx, index) => {
            m.__takes.push(index);
            let answer = 0;
            if (containerFlag) { containerFlag = 0; answer |= 1; }
            for (let i = 0; i < streamFlags.length; i++) {
                if (index !== -1 && index !== i) continue;
                if (streamFlags[i]) { streamFlags[i] = 0; answer |= 2; }
            }
            return answer;
        };
        m._ffkmp_packet_clone = () => m._malloc(16);
        return m;
    }""",
)
private external fun installFakeTagChanges(module: JsAny): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__takes.length === 0 ? 99 : m.__takes[0]")
private external fun firstTakeIndex(module: JsAny): Int
