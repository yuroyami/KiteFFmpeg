package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny

/*
 * The test seam for the whole web backend.
 *
 * Every generated external in `wasm/KiteFFmpegWasm.kt` takes the emscripten module as its FIRST
 * argument, because the codec lives in a separate wasm module with its own linear memory. That
 * argument is the seam: a JS object carrying a real heap and the handful of `_kc_` entry points a
 * test drives is indistinguishable, from Kotlin's side, from the real codec. No FFmpeg wasm build
 * is needed to exercise this layer, which is why it was untestable for as long as the only plan
 * was to build one.
 *
 * The fake is deliberately small. It implements what the code under test actually reads and
 * nothing else; a call into an entry point it does not carry fails loudly rather than answering
 * plausibly, which is the failure mode the plan describes in production code.
 */

/** A module carrying every runtime piece `KiteFFmpegWeb.attach` requires, plus a working heap. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """() => {
        const buffer = new ArrayBuffer(1 << 21);
        const HEAPU8 = new Uint8Array(buffer);
        const HEAP32 = new Int32Array(buffer);
        let brk = 8;
        let mallocCount = 0;
        let lastMalloc = 0;
        const malloc = (n) => {
            const p = brk;
            brk = (brk + n + 7) & ~7;
            mallocCount++;
            lastMalloc = p;
            return p;
        };
        const cstr = (s) => {
            const b = new TextEncoder().encode(s);
            const p = malloc(b.length + 1);
            HEAPU8.set(b, p);
            HEAPU8[p + b.length] = 0;
            return p;
        };
        const libs = ["libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample"].map(cstr);
        const verdicts = ["ok", "header_newer", "runtime_newer", "incompatible"].map(cstr);
        const stage = malloc(2176);
        return {
            HEAPU8: HEAPU8,
            HEAP32: HEAP32,
            _malloc: malloc,
            _free: () => {},
            ccall: () => 0,
            addFunction: () => 0,
            removeFunction: () => {},
            lengthBytesUTF8: (s) => new TextEncoder().encode(s).length,
            stringToUTF8: (s, p, n) => {
                const b = new TextEncoder().encode(s);
                const k = Math.min(b.length, n - 1);
                HEAPU8.set(b.subarray(0, k), p);
                HEAPU8[p + k] = 0;
            },
            UTF8ToString: (p) => {
                if (p === 0) return null;
                let z = p;
                while (HEAPU8[z] !== 0) z++;
                return new TextDecoder().decode(HEAPU8.subarray(p, z));
            },
            __stage: stage,
            __mallocCount: () => mallocCount,
            __lastMalloc: () => lastMalloc,
            _kc_ffmpeg_report_get: (p) => { HEAPU8.copyWithin(p, stage, stage + 2176); },
            _kc_ffmpeg_library_name: (i) => (i >= 0 && i < libs.length) ? libs[i] : 0,
            _kc_verdict_name: (v) => (v >= 0 && v < verdicts.length) ? verdicts[v] : 0,
        };
    }""",
)
internal external fun fakeCodecModule(): JsAny

/**
 * Adds the smallest real-demux surface needed to exercise [PacketReader] over two subtitle
 * streams. The scripted demuxer deliberately ignores its discard flags: this models containers
 * where those flags are advisory and proves the Kotlin `wanted` set remains the exact gate.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal fun fakePacketReaderCodecModule(): JsAny = installFakePacketReaderSurface(fakeCodecModule())

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        const CONTEXT = 0x500;
        const STREAM = 0x600;
        const CODECPAR = 0x700;
        const EOF = -541478725;
        const packets = [0, 0, 1, 0, 1, 0, 1];
        const packetStreams = new Map();
        const selected = [true, true];
        let cursor = 0;
        let openCount = 0;

        const codecName = m._malloc(9);
        m.stringToUTF8("webvtt", codecName, 9);

        m._ffkmp_fmt_open_input_io = (out, opaque, readFn, seekFn, size, keys, values, n, unused) => {
            m.HEAP32[out >> 2] = CONTEXT;
            m.HEAP32[unused >> 2] = 0;
            openCount++;
            return 0;
        };
        m._ffkmp_fmt_find_stream_info = () => 0;
        m._ffkmp_fmt_start_time = () => 0n;
        m._ffkmp_fmt_nb_streams = (ctx) => ctx === CONTEXT ? 2 : 0;
        m._ffkmp_fmt_stream = (ctx, index) =>
            ctx === CONTEXT && index >= 0 && index < 2 ? STREAM + index : 0;
        m._ffkmp_stream_codecpar = (stream) => CODECPAR + (stream - STREAM);
        m._ffkmp_stream_index = (stream) => stream - STREAM;
        m._ffkmp_stream_time_base = (stream, num, den) => {
            m.HEAP32[num >> 2] = 1;
            m.HEAP32[den >> 2] = 1000;
        };
        m._ffkmp_stream_duration_micros = () => 0n;
        m._ffkmp_stream_rotation_degrees = () => 0;
        m._ffkmp_codecpar_codec_type = () => 3;
        m._ffkmp_codecpar_codec_id = () => 1;
        m._ffkmp_codecpar_bit_rate = () => 0n;
        m._ffkmp_codec_id_name = () => codecName;
        m._ffkmp_media_type_video = () => 0;
        m._ffkmp_media_type_audio = () => 1;
        m._ffkmp_media_type_subtitle = () => 3;

        // Real AV_DISPOSITION_* bit values, so the fake cannot drift from the header:
        // stream 0 is default+forced (1|64), stream 1 is hearing-impaired (128).
        m._ffkmp_stream_disposition = (stream) => stream === STREAM ? 65 : 128;
        m._ffkmp_disposition_default = () => 1;
        m._ffkmp_disposition_forced = () => 64;
        m._ffkmp_disposition_hearing_impaired = () => 128;
        m._ffkmp_disposition_visual_impaired = () => 256;
        m._ffkmp_disposition_attached_pic = () => 1024;

        // The container MODEL: this base fake declares a container that carries none of it, which
        // is a legitimate answer and keeps these entries honest. A fake that simply omitted them
        // would make every reader of the model throw instead, which is the fake working as designed
        // but not what these older tests are about.
        m._ffkmp_fmt_metadata = () => 0;
        m._ffkmp_stream_metadata = () => 0;
        m._ffkmp_fmt_chapter_count = () => 0;
        m._ffkmp_stream_start_time = () => -9223372036854775808n;
        m._ffkmp_codecpar_extradata = () => 0;
        m._ffkmp_media_type_data = () => 2;
        m._ffkmp_media_type_attachment = () => 4;

        m._ffkmp_stream_discard_none = (stream) => { selected[stream - STREAM] = true; };
        m._ffkmp_stream_discard_all = (stream) => { selected[stream - STREAM] = false; };
        m._ffkmp_packet_alloc = () => m._malloc(16);
        m._ffkmp_fmt_read_frame = (ctx, packet) => {
            if (cursor >= packets.length) return EOF;
            packetStreams.set(packet, packets[cursor++]);
            return 0;
        };
        m._ffkmp_packet_stream_index = (packet) => packetStreams.get(packet) ?? -1;
        m._ffkmp_packet_unref = (packet) => { packetStreams.delete(packet); };
        m._ffkmp_packet_free = (packet) => { packetStreams.delete(packet); };
        m._ffkmp_averror_eof = () => EOF;
        m._ffkmp_fmt_close_input_io = (slot) => { m.HEAP32[slot >> 2] = 0; };

        m.__packetReaderCursor = () => cursor;
        m.__packetReaderOpenCount = () => openCount;
        m.__packetReaderSelectionMask = () => (selected[0] ? 1 : 0) | (selected[1] ? 2 : 0);
        return m;
    }""",
)
private external fun installFakePacketReaderSurface(module: JsAny): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__packetReaderCursor()")
internal external fun fakePacketReaderCursor(module: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__packetReaderOpenCount()")
internal external fun fakePacketReaderOpenCount(module: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__packetReaderSelectionMask()")
internal external fun fakePacketReaderSelectionMask(module: JsAny): Int

/**
 * Adds a scripted DECODER on top of the scripted demuxer, so a whole `decodeStreams` or
 * `extractFrame` pass runs with no FFmpeg wasm build present.
 *
 * The decoder models the two codec behaviours the code under test is written against, because a
 * fake that always accepts input and always answers a frame would let broken code pass:
 *
 *  - It refuses new input while its output is unread (`EAGAIN`), which is what drives the
 *    send/drain/retry loop rather than a straight-through path.
 *  - It reports damaged data per packet (`AVERROR_INVALIDDATA`), which is what makes
 *    `corruptDataSkipped` move.
 *
 * The packet run is a string set by [setFakeDecodeScript]: `g` is a good packet the decoder turns
 * into one frame, `x` is one it calls damaged. Both go to stream 0 unless the letter is upper
 * case, which sends it to stream 1.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal fun fakeDecodeCodecModule(): JsAny = installFakeDecodeSurface(fakePacketReaderCodecModule())

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        const EOF = -541478725;
        // Any distinct negative value: production asks the module for it rather than assuming one.
        const EAGAIN = -11;
        // FFERRTAG('I','N','D','A'), spelled the way Errors.kt spells it. This one is NOT asked for
        // through the module, it is computed in Kotlin, so the two must agree by construction.
        const INVALIDDATA = -(73 | (78 << 8) | (68 << 16) | (65 << 24));
        const CODEC = 0xC00;

        let script = "";
        let cursor = 0;
        const packetKind = new Map();
        const packetStream = new Map();

        let decoderOpenFails = false;
        let opens = 0;
        let frees = 0;
        let nextContext = 0xD00;
        const live = new Set();
        const pending = new Set();
        const draining = new Set();
        let frameAllocs = 0;
        let frameClones = 0;
        let frameFrees = 0;
        let seeks = 0;

        m._ffkmp_fmt_read_frame = (ctx, packet) => {
            if (cursor >= script.length) return EOF;
            const letter = script[cursor++];
            packetKind.set(packet, letter.toLowerCase());
            packetStream.set(packet, letter === letter.toUpperCase() ? 1 : 0);
            return 0;
        };
        m._ffkmp_packet_stream_index = (packet) => packetStream.has(packet) ? packetStream.get(packet) : -1;
        m._ffkmp_packet_unref = (packet) => { packetKind.delete(packet); packetStream.delete(packet); };
        m._ffkmp_packet_free = (packet) => { packetKind.delete(packet); packetStream.delete(packet); };
        m._ffkmp_packet_pts = () => 0n;

        m._ffkmp_averror_eagain = () => EAGAIN;
        m._ffkmp_avseek_flag_backward = () => 1;
        m._ffkmp_avseek_flag_any = () => 4;
        // A seek rewinds the scripted run, which is what lets extractFrame walk from a landing point.
        m._ffkmp_fmt_seek_file = () => { seeks++; cursor = 0; return 0; };

        m._ffkmp_find_decoder_by_id = () => decoderOpenFails ? 0 : CODEC;
        m._ffkmp_find_decoder_by_name = () => decoderOpenFails ? 0 : CODEC;
        m._ffkmp_codec_id = () => 1;
        m._ffkmp_codecctx_alloc = () => {
            const c = nextContext;
            nextContext += 0x10;
            opens++;
            live.add(c);
            return c;
        };
        m._ffkmp_codecctx_from_par = () => 0;
        m._ffkmp_codecctx_open = () => 0;
        m._ffkmp_codecctx_set_low_delay = () => {};
        m._ffkmp_codecctx_set_threads = () => {};
        m._ffkmp_codecctx_set_opt = () => 0;
        m._ffkmp_codecctx_flush = (c) => { pending.delete(c); draining.delete(c); };
        m._ffkmp_codecctx_free = (c) => {
            if (live.delete(c)) frees++;
            pending.delete(c);
            draining.delete(c);
        };

        m._ffkmp_codecctx_send_packet = (c, p) => {
            if (p === 0) { draining.add(c); return 0; }
            if (packetKind.get(p) === "x") return INVALIDDATA;
            if (pending.has(c)) return EAGAIN;
            pending.add(c);
            return 0;
        };
        m._ffkmp_codecctx_receive_frame = (c, f) => {
            if (pending.delete(c)) return 0;
            if (draining.has(c)) return EOF;
            return EAGAIN;
        };

        m._ffkmp_frame_alloc = () => { frameAllocs++; return m._malloc(8); };
        m._ffkmp_frame_clone = () => { frameClones++; return m._malloc(8); };
        m._ffkmp_frame_free = () => { frameFrees++; };

        m.__setDecodeScript = (s) => {
            script = s;
            cursor = 0;
            packetKind.clear();
            packetStream.clear();
        };
        m.__setDecoderOpenFails = (v) => { decoderOpenFails = v; };
        m.__decoderOpens = () => opens;
        m.__decoderFrees = () => frees;
        m.__liveDecoders = () => live.size;
        m.__frameBalance = () => frameAllocs + frameClones - frameFrees;
        m.__seeks = () => seeks;
        return m;
    }""",
)
private external fun installFakeDecodeSurface(module: JsAny): JsAny

/** Sets the scripted packet run; see [fakeDecodeCodecModule] for the letters. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, s) => m.__setDecodeScript(s)")
internal external fun setFakeDecodeScript(module: JsAny, script: String)

/** Makes the next `openDecoder` fail the way a missing decoder does. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, v) => m.__setDecoderOpenFails(v)")
internal external fun setFakeDecoderOpenFails(module: JsAny, fails: Boolean)

/** How many codec contexts have been allocated. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__decoderOpens()")
internal external fun fakeDecoderOpens(module: JsAny): Int

/** How many have been freed. A leak is [fakeDecoderOpens] moving while this one does not. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__decoderFrees()")
internal external fun fakeDecoderFrees(module: JsAny): Int

/** Codec contexts allocated and never freed, which is the leak stated directly. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__liveDecoders()")
internal external fun fakeLiveDecoders(module: JsAny): Int

/** Frames allocated and cloned minus frames freed: zero once every frame has been closed. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__frameBalance()")
internal external fun fakeFrameBalance(module: JsAny): Int

/** A module missing most of what the backend reads, for the diagnostic `attach` refuses on. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""() => ({ ccall: () => 0, UTF8ToString: () => null, addFunction: () => 0, removeFunction: () => {} })""")
internal external fun incompleteCodecModule(): JsAny

/** Where [fakeCodecModule] parks the bytes `kc_ffmpeg_report_get` will hand back. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__stage")
internal external fun stagedReportPointer(module: JsAny): Int

/**
 * How many times the fake's `_malloc` has been called.
 *
 * The only way to assert that a refusal allocated NOTHING. A pointer comparison cannot say it: a
 * bump allocator that was never called and one that was called and rewound look identical.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__mallocCount()")
internal external fun mallocCount(module: JsAny): Int

/** The pointer the fake's most recent `_malloc` handed out, so a test can read what was staged. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__lastMalloc()")
internal external fun lastMallocPointer(module: JsAny): Int

/** Test-only writer. Production reads C strings out of this heap and never writes one. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, p, s) => {
        const b = new TextEncoder().encode(s);
        m.HEAPU8.set(b, p);
        m.HEAPU8[p + b.length] = 0;
    }""",
)
internal external fun writeCString(module: JsAny, pointer: Int, text: String)

/** Installs [module] as the loaded codec, bypassing the network step [KiteFFmpegWeb.load] performs. */
internal fun useCodecModule(module: JsAny) {
    KiteFFmpegWeb.module = null
    KiteFFmpegWeb.attach(module)
}

/** Returns the backend to its unloaded state so the next test starts where a fresh page would. */
internal fun forgetCodecModule() {
    KiteFFmpegWeb.module = null
}

/**
 * Adds everything the container MODEL reads: metadata dictionaries, chapters, per-stream tags,
 * start time, extradata, colour, channel layout and the non-AV media types.
 *
 * The web backend used to hardcode most of this empty and collapse every non-AV type to `Data`,
 * which is the failure mode this fake exists to catch: plausible emptiness reads exactly like a
 * container that genuinely carries nothing.
 *
 * Stream 0 is VIDEO so the colour and extradata paths apply; stream 1 is an ATTACHMENT, which is
 * one of the two types that used to be erased.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal fun fakeModelCodecModule(): JsAny = installFakeModelSurface(fakePacketReaderCodecModule())

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        const STREAM = 0x600;
        const CODECPAR = 0x700;
        const cstr = (s) => {
            const b = new TextEncoder().encode(s);
            const p = m._malloc(b.length + 1);
            m.HEAPU8.set(b, p);
            m.HEAPU8[p + b.length] = 0;
            return p;
        };

        // Dictionaries, as arrays of [keyPtr, valuePtr]. Entry handles encode dict and index so a
        // walk cannot wander from one dictionary into another.
        const dicts = {
            1: [["title", "Fake Container"], ["encoder", "kite"]],
            2: [["language", "eng"], ["title", "Main Video"]],
            3: [["language", "ger"]],
            4: [["title", "Opening"]],
            5: [["title", "Ending"]],
        };
        const built = {};
        for (const id of Object.keys(dicts)) {
            built[id] = dicts[id].map((kv) => [cstr(kv[0]), cstr(kv[1])]);
        }
        m._ffkmp_dict_get = (dict, prev) => {
            const list = built[dict];
            if (!list) return 0;
            const next = prev === 0 ? 0 : (prev % 1000);
            if (next >= list.length) return 0;
            return dict * 1000 + next + 1;
        };
        m._ffkmp_dict_entry_key = (entry) => built[Math.floor(entry / 1000)][(entry % 1000) - 1][0];
        m._ffkmp_dict_entry_value = (entry) => built[Math.floor(entry / 1000)][(entry % 1000) - 1][1];

        m._ffkmp_fmt_metadata = () => 1;
        m._ffkmp_stream_metadata = (stream) => stream === STREAM ? 2 : 3;

        // Chapters: id, start and end are int64 OUT slots the caller allocates.
        const chapters = [[7, 0n, 1500000n, 4], [8, 1500000n, 3000000n, 5]];
        m._ffkmp_fmt_chapter_count = () => chapters.length;
        m._ffkmp_fmt_chapter_get = (ctx, index, idOut, startOut, endOut) => {
            if (index < 0 || index >= chapters.length) return -1;
            const c = chapters[index];
            const view = new DataView(m.HEAPU8.buffer);
            view.setBigInt64(idOut, BigInt(c[0]), true);
            view.setBigInt64(startOut, c[1], true);
            view.setBigInt64(endOut, c[2], true);
            return 0;
        };
        m._ffkmp_fmt_chapter_metadata = (ctx, index) => chapters[index][3];

        // Stream 0 is video, stream 1 an attachment: the two answers that used to collapse.
        m._ffkmp_media_type_data = () => 2;
        m._ffkmp_media_type_attachment = () => 4;
        m._ffkmp_codecpar_codec_type = (par) => par === CODECPAR ? 0 : 4;
        m._ffkmp_codecpar_width = () => 320;
        m._ffkmp_codecpar_height = () => 180;
        m._ffkmp_codecpar_sample_aspect_ratio = (par, num, den) => {
            m.HEAP32[num >> 2] = 1;
            m.HEAP32[den >> 2] = 1;
        };
        m._ffkmp_stream_avg_frame_rate = (s, num, den) => {
            m.HEAP32[num >> 2] = 25;
            m.HEAP32[den >> 2] = 1;
        };
        // BT.709 declared, full range, top-left chroma: every field a real value rather than a
        // guess, so a backend that dropped the read answers Unspecified and fails visibly.
        // Flipped by __setColorDeclared, so the same fake can be a stream that says BT.709 and a
        // stream that says nothing at all. Both answers matter: the second is what makes a guess a
        // guess, and there is no way to tell them apart without running both.
        let colorDeclared = true;
        m.__setColorDeclared = (v) => { colorDeclared = v; };
        m._ffkmp_codecpar_color_space = () => colorDeclared ? 1 : 2;
        m._ffkmp_codecpar_color_primaries = () => colorDeclared ? 1 : 2;
        m._ffkmp_codecpar_color_transfer = () => colorDeclared ? 1 : 2;
        m._ffkmp_codecpar_color_range = () => colorDeclared ? 2 : 0;
        m._ffkmp_codecpar_chroma_location = () => 2;
        m._ffkmp_codecpar_ch_layout_mask = () => 3n;

        // The subtitle-only base fake never needed a pixel format; the video branch reads one and
        // then asks for its name.
        const pixName = cstr("yuv420p");
        m._ffkmp_codecpar_format = () => 12;
        m._ffkmp_pix_fmt_name = (id) => id === 12 ? pixName : 0;
        m._ffkmp_codecpar_sample_rate = () => 0;
        m._ffkmp_codecpar_channels = () => 0;

        const EXTRA = [1, 2, 3, 4, 5];
        m._ffkmp_codecpar_extradata = (par, buf, size) => {
            if (buf === 0) return EXTRA.length;
            if (size < EXTRA.length) return -1;
            m.HEAPU8.set(new Uint8Array(EXTRA), buf);
            return EXTRA.length;
        };

        // 250 ticks at the fake's 1/1000 time base, so a backend that forgets to rescale reports
        // 250 instead of 250000 and the difference is the bug.
        m._ffkmp_stream_start_time = () => 250n;
        m._ffkmp_rescale_q = (v, sn, sd, dn, dd) =>
            (v * BigInt(sn) * BigInt(dd)) / (BigInt(sd) * BigInt(dn));
        return m;
    }""",
)
private external fun installFakeModelSurface(module: JsAny): JsAny

/** Makes the model fake's video stream declare its colour, or declare nothing at all. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, v) => m.__setColorDeclared(v)")
internal external fun setFakeColorDeclared(module: JsAny, declared: Boolean)
