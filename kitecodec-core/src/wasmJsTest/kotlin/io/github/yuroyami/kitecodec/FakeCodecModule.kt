package io.github.yuroyami.kitecodec

import kotlin.js.JsAny

/*
 * The test seam for the whole web backend.
 *
 * Every generated external in `wasm/KiteCodecWasm.kt` takes the emscripten module as its FIRST
 * argument, because the codec lives in a separate wasm module with its own linear memory. That
 * argument is the seam: a JS object carrying a real heap and the handful of `_kc_` entry points a
 * test drives is indistinguishable, from Kotlin's side, from the real codec. No FFmpeg wasm build
 * is needed to exercise this layer, which is why it was untestable for as long as the only plan
 * was to build one.
 *
 * The fake is deliberately small. It implements what the code under test actually reads and
 * nothing else; a call into an entry point it does not carry fails loudly rather than answering
 * plausibly, which is the failure mode `KC-WASM-MODEL` describes in production code.
 */

/** A module carrying every runtime piece `KiteCodecWeb.attach` requires, plus a working heap. */
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

/** Installs [module] as the loaded codec, bypassing the network step [KiteCodecWeb.load] performs. */
internal fun useCodecModule(module: JsAny) {
    KiteCodecWeb.module = null
    KiteCodecWeb.attach(module)
}

/** Returns the backend to its unloaded state so the next test starts where a fresh page would. */
internal fun forgetCodecModule() {
    KiteCodecWeb.module = null
}
