package io.github.yuroyami.kiteffmpeg

/** Dynamically registered JNI bridge. Every external below has exactly one four-field row in
 * native/kitecodec-jni/methods.def. Public actuals use the checked wrappers at the end of this file;
 * tokens and JNI exceptions never leave the module. */
internal object Internals {
    private external fun nativeAbiVersion(): Int
    private external fun nativeInit(): Int
    private external fun nativeAttachCurrentVm(): Int
    private external fun nativeIdentityReport(): String
    private external fun nativeConfiguration(): String
    private external fun nativeHasDecoder(name: String): Boolean
    private external fun nativeHasEncoder(name: String): Boolean
    private external fun nativeHasFilter(name: String): Boolean
    private external fun nativeErrorEagain(): Int
    private external fun nativeErrorEof(): Int
    private external fun nativeStrerror(code: Int): String
    private external fun nativeMediaTypeVideo(): Int
    private external fun nativeMediaTypeAudio(): Int
    private external fun nativeMediaTypeSubtitle(): Int
    private external fun nativeMediaTypeData(): Int
    private external fun nativeMediaTypeAttachment(): Int
    private external fun nativeLiveHandles(): Long
    private external fun nativeRescaleQ(value: Long, sn: Int, sd: Int, dn: Int, dd: Int): Long
    private external fun nativePixelFormatName(value: Int): String?
    private external fun nativePixelFormatValue(name: String): Int
    private external fun nativeSampleFormatName(value: Int): String?
    private external fun nativeSampleFormatValue(name: String): Int
    private external fun nativeSeekFlagBackward(): Int
    private external fun nativeSeekFlagAny(): Int
    private external fun nativeDispositionDefault(): Int
    private external fun nativeDispositionForced(): Int
    private external fun nativeDispositionHearingImpaired(): Int
    private external fun nativeDispositionVisualImpaired(): Int
    private external fun nativeDispositionAttachedPic(): Int

    private external fun nativePacketAlloc(): Long
    private external fun nativePacketFree(token: Long)
    private external fun nativePacketClone(token: Long): Long
    private external fun nativePacketPts(token: Long): Long
    private external fun nativePacketDts(token: Long): Long
    private external fun nativePacketDuration(token: Long): Long
    private external fun nativePacketStreamIndex(token: Long): Int
    private external fun nativePacketSize(token: Long): Int
    private external fun nativePacketIsKeyframe(token: Long): Boolean
    private external fun nativePacketPosition(token: Long): Long
    private external fun nativePacketUnref(token: Long)
    private external fun nativePacketSetStreamIndex(token: Long, value: Int)
    private external fun nativePacketSetPts(token: Long, value: Long)
    private external fun nativePacketSetDts(token: Long, value: Long)
    private external fun nativePacketRescale(token: Long, sn: Int, sd: Int, dn: Int, dd: Int)
    private external fun nativePacketMoveRef(destination: Long, source: Long)
    private external fun nativePacketBytes(token: Long): ByteArray

    private external fun nativeFmtOpenInput(path: String): Long
    private external fun nativeFmtOpenInput2(
        path: String,
        keys: Array<String>?,
        values: Array<String>?,
        unusedKeysOut: Array<String?>?,
    ): Long
    private external fun nativeFmtOpenInputIo(
        io: JniByteIo,
        seekable: Boolean,
        size: Long,
        keys: Array<String>?,
        values: Array<String>?,
        unusedKeysOut: Array<String?>?,
    ): Long
    private external fun nativeFmtCloseInputIo(token: Long)
    private external fun nativeFmtChapterCount(token: Long): Int
    private external fun nativeFmtChapterGet(token: Long, index: Int, outFields: LongArray): Int
    private external fun nativeFmtChapterMetadata(token: Long, index: Int): Long
    private external fun nativeFmtCloseInput(token: Long)
    private external fun nativeFmtInterrupt(token: Long)
    private external fun nativeFmtFindStreamInfo(token: Long): Int
    private external fun nativeFmtNbStreams(token: Long): Int
    private external fun nativeFmtStream(token: Long, index: Int): Long
    private external fun nativeFmtDurationMicros(token: Long): Long
    private external fun nativeFmtReadFrame(token: Long, packet: Long): Int
    private external fun nativeFmtSeekMicros(token: Long, stream: Int, micros: Long): Int
    private external fun nativeFmtSetOpt(token: Long, key: String, value: String?): Int
    private external fun nativeFmtStartTime(token: Long): Long
    private external fun nativeFmtInputName(token: Long): String?
    private external fun nativeFmtIsSeekable(token: Long): Boolean
    private external fun nativeFmtSeekFile(token: Long, stream: Int, min: Long, target: Long, max: Long, flags: Int): Int
    private external fun nativeFmtMetadata(token: Long): Long
    private external fun nativeFmtAllocOutput(path: String?, format: String?): Long
    private external fun nativeFmtFreeOutput(token: Long): Int
    private external fun nativeFmtNewStream(format: Long, codec: Long): Long
    private external fun nativeFmtIoOpen(token: Long, path: String): Int
    private external fun nativeFmtAvoidNegativeTs(token: Long)
    private external fun nativeFmtWriteHeader(token: Long): Int
    private external fun nativeFmtWriteFrame(token: Long, packet: Long): Int
    private external fun nativeFmtWriteTrailer(token: Long): Int
    private external fun nativeFmtGlobalHeader(token: Long): Boolean
    private external fun nativeFmtSetMetadata(token: Long, key: String, value: String?): Int
    private external fun nativeBorrowedRelease(token: Long, kind: Int)
    private external fun nativeStreamIndex(token: Long): Int
    private external fun nativeStreamCodecPar(token: Long): Long
    private external fun nativeStreamDuration(token: Long): Long
    private external fun nativeStreamStartTime(token: Long): Long
    private external fun nativeStreamMetadata(token: Long): Long
    private external fun nativeStreamTimeBase(token: Long): Long
    private external fun nativeStreamFrameRate(token: Long): Long
    private external fun nativeStreamSetTimeBase(token: Long, n: Int, d: Int)
    private external fun nativeStreamDiscard(token: Long, discard: Boolean)
    private external fun nativeStreamDisposition(token: Long): Int
    private external fun nativeStreamRotation(token: Long): Int
    private external fun nativeDictNext(token: Long, previous: Long): Long
    private external fun nativeDictKey(token: Long): String?
    private external fun nativeDictValue(token: Long): String?
    private external fun nativeCodecParType(token: Long): Int
    private external fun nativeCodecParId(token: Long): Int
    private external fun nativeCodecParBitrate(token: Long): Long
    private external fun nativeCodecParWidth(token: Long): Int
    private external fun nativeCodecParHeight(token: Long): Int
    private external fun nativeCodecParFormat(token: Long): Int
    private external fun nativeCodecParProfile(token: Long): Int
    private external fun nativeCodecParLevel(token: Long): Int
    private external fun nativeCodecParColorSpace(token: Long): Int
    private external fun nativeCodecParColorPrimaries(token: Long): Int
    private external fun nativeCodecParColorTransfer(token: Long): Int
    private external fun nativeCodecParColorRange(token: Long): Int
    private external fun nativeCodecParChromaLocation(token: Long): Int
    private external fun nativeCodecParBitDepth(token: Long): Int
    private external fun nativeCodecParChromaSubsampling(token: Long): Int
    private external fun nativeCodecParSampleRate(token: Long): Int
    private external fun nativeCodecParChannels(token: Long): Int
    private external fun nativeCodecParExtradata(token: Long): ByteArray?
    private external fun nativeCodecParSar(token: Long): Long
    private external fun nativeCodecParChannelLayout(token: Long): Long
    private external fun nativeCodecParFromContext(parameters: Long, context: Long): Int
    private external fun nativeCodecParCopy(destination: Long, source: Long): Int

    private external fun nativeFindDecoderById(id: Int): Long
    private external fun nativeFindDecoderByName(name: String): Long
    private external fun nativeFindEncoderByName(name: String): Long
    private external fun nativeCodecId(token: Long): Int
    private external fun nativeCodecIdName(id: Int): String
    private external fun nativeCodecRelease(token: Long)
    private external fun nativeCodecCtxAlloc(codec: Long): Long
    private external fun nativeCodecCtxFree(token: Long)
    private external fun nativeCodecCtxFromPar(context: Long, parameters: Long): Int
    private external fun nativeCodecCtxOpen(context: Long, codec: Long): Int
    private external fun nativeCodecCtxSetOpt(context: Long, key: String, value: String?): Int
    private external fun nativeCodecCtxUseVideoToolbox(context: Long): Int
    private external fun nativeCodecCtxSendPacket(context: Long, packet: Long): Int
    private external fun nativeCodecCtxReceiveFrame(context: Long, frame: Long): Int
    private external fun nativeCodecCtxFlush(context: Long)
    private external fun nativeCodecCtxSetThreads(context: Long, count: Int, frameLevel: Boolean)
    private external fun nativeCodecCtxSetLowDelay(context: Long, enabled: Boolean)
    private external fun nativeCodecCtxSendFrame(context: Long, frame: Long): Int
    private external fun nativeCodecCtxReceivePacket(context: Long, packet: Long): Int
    private external fun nativeCodecCtxSetVideo(context: Long, width: Int, height: Int, format: Int, frn: Int, frd: Int, tbn: Int, tbd: Int, bitrate: Long, gop: Int)
    private external fun nativeCodecCtxSetAudio(context: Long, rate: Int, format: Int, channels: Int, bitrate: Long)
    private external fun nativeCodecFirstSampleFormat(codec: Long): Int
    private external fun nativeCodecFirstPixelFormat(codec: Long): Int
    private external fun nativeCodecSupportsPixelFormat(codec: Long, format: Int): Boolean
    private external fun nativeCodecCtxFrameSize(context: Long): Int
    private external fun nativeCodecCtxSampleRate(context: Long): Int
    private external fun nativeCodecCtxChannels(context: Long): Int
    private external fun nativeCodecCtxTimeBase(context: Long): Long
    private external fun nativeCodecCtxGlobalHeader(context: Long)
    private external fun nativeCodecCtxFullRange(context: Long)
    private external fun nativeCodecCtxPixelFormat(context: Long): Int
    private external fun nativeCodecCtxWidth(context: Long): Int
    private external fun nativeCodecCtxHeight(context: Long): Int

    private external fun nativeFrameAlloc(): Long
    private external fun nativeFrameFree(token: Long)
    private external fun nativeFramePts(token: Long): Long
    private external fun nativeFrameWidth(token: Long): Int
    private external fun nativeFrameHeight(token: Long): Int
    private external fun nativeFrameFormat(token: Long): Int
    private external fun nativeFrameIsKeyframe(token: Long): Boolean
    private external fun nativeFrameCopyPlanes(token: Long): ByteArray
    private external fun nativeFrameUnref(token: Long)
    private external fun nativeFrameClone(token: Long): Long
    private external fun nativeFrameConvert(token: Long, format: Int): Long
    private external fun nativeFrameDuration(token: Long): Long
    private external fun nativeFrameSampleCount(token: Long): Int
    private external fun nativeFrameSampleRate(token: Long): Int
    private external fun nativeFrameChannels(token: Long): Int
    private external fun nativeFrameChannelLayout(token: Long): Long
    private external fun nativeFrameColorRange(token: Long): Int
    private external fun nativeFrameColorSpace(token: Long): Int
    private external fun nativeFrameColorPrimaries(token: Long): Int
    private external fun nativeFrameColorTransfer(token: Long): Int
    private external fun nativeFrameChromaLocation(token: Long): Int
    private external fun nativeFrameSampleAspectRatio(token: Long): Long
    private external fun nativeFrameIsHardware(token: Long): Boolean
    private external fun nativeFrameHwDownload(source: Long, destination: Long): Int
    private external fun nativeFrameUseBestEffort(token: Long)
    private external fun nativeFrameSetPts(token: Long, value: Long)
    private external fun nativeFrameSetFormat(token: Long, value: Int)
    private external fun nativeFrameSetWidth(token: Long, value: Int)
    private external fun nativeFrameSetHeight(token: Long, value: Int)
    private external fun nativeFrameSetSampleRate(token: Long, value: Int)
    private external fun nativeFrameSetSampleCount(token: Long, value: Int)
    private external fun nativeFrameSetChannels(token: Long, value: Int)
    private external fun nativeFrameGetBuffer(token: Long, align: Int): Int
    private external fun nativeFrameFillVideo(token: Long, bytes: ByteArray): Int
    private external fun nativeFrameFillAudio(token: Long, bytes: ByteArray): Int

    private external fun nativeGraphBuildVideo(description: String, width: Int, height: Int, format: Int, tbn: Int, tbd: Int, frn: Int, frd: Int, sarn: Int, sard: Int): LongArray
    private external fun nativeGraphBuildAudio(description: String?, rate: Int, format: Int, channels: Int, tbn: Int, tbd: Int, outFormat: Int, outRate: Int, outChannels: Int): LongArray
    private external fun nativeGraphBuildVideoMulti(description: String, count: Int, widths: IntArray, heights: IntArray, formats: IntArray, tbns: IntArray, tbds: IntArray, frns: IntArray, frds: IntArray, sarns: IntArray, sards: IntArray): LongArray
    private external fun nativeGraphBuildAudioMulti(description: String?, count: Int, rates: IntArray, formats: IntArray, channels: IntArray, tbns: IntArray, tbds: IntArray, outFormat: Int, outRate: Int, outChannels: Int): LongArray
    private external fun nativeGraphFree(token: Long)
    private external fun nativeGraphSend(source: Long, frame: Long): Int
    private external fun nativeGraphReceive(sink: Long, frame: Long): Int
    private external fun nativeGraphSetFrameSize(sink: Long, size: Int)
    private external fun nativeGraphTimeBase(sink: Long): Long

    private data class Initialization(
        val gateStatus: Int,
        val report: FFmpegIdentity,
        val packedAbi: Int,
        val reportedAbi: Int,
        val attachStatus: Int?,
    )

    /** Object construction itself does not load native code. The first identity/operation loads
     * the binary and copies the full report. A rejected runtime remains readable through
     * FFmpeg.identity; only accepted identity/ABI pairs proceed to the JavaVM handoff. */
    private val initialization: Initialization by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JniLibrary.ensureLoaded()
        val status = nativeInit()
        val report = parseIdentity(nativeIdentityReport())
        val packedAbi = nativeAbiVersion()
        val reportedAbi = (report.cAbiVersion.substringBefore('.').toInt() shl 16) or
            (report.cAbiVersion.substringAfter('.').toInt() shl 8)
        val attach = if (status == 0 && report.isAcceptable && packedAbi == reportedAbi) {
            nativeAttachCurrentVm()
        } else {
            null
        }
        Initialization(status, report, packedAbi, reportedAbi, attach)
    }
    internal val identity: FFmpegIdentity get() = initialization.report
    internal val configuration: String get() = checked { nativeConfiguration() }
    internal val errorEagain: Int get() = checked { nativeErrorEagain() }
    internal val errorEof: Int get() = checked { nativeErrorEof() }
    internal val mediaTypeVideo: Int get() = checked { nativeMediaTypeVideo() }
    internal val mediaTypeAudio: Int get() = checked { nativeMediaTypeAudio() }
    internal val mediaTypeSubtitle: Int get() = checked { nativeMediaTypeSubtitle() }
    internal val mediaTypeData: Int get() = checked { nativeMediaTypeData() }
    internal val mediaTypeAttachment: Int get() = checked { nativeMediaTypeAttachment() }
    internal val seekFlagBackward: Int get() = checked { nativeSeekFlagBackward() }
    internal val seekFlagAny: Int get() = checked { nativeSeekFlagAny() }
    internal val dispositionDefault: Int get() = checked { nativeDispositionDefault() }
    internal val dispositionForced: Int get() = checked { nativeDispositionForced() }
    internal val dispositionHearingImpaired: Int get() = checked { nativeDispositionHearingImpaired() }
    internal val dispositionVisualImpaired: Int get() = checked { nativeDispositionVisualImpaired() }
    internal val dispositionAttachedPic: Int get() = checked { nativeDispositionAttachedPic() }

    internal fun requireCompatible() {
        val state = initialization
        if (state.packedAbi != state.reportedAbi) {
            throw FFmpegException(
                FFmpegError.Internal(
                    "KiteFFmpeg C ABI report disagrees with kc_abi_version: " +
                        "report=${state.report.cAbiVersion}, packed=${state.packedAbi}",
                ),
            )
        }
        if (state.gateStatus != 0 || !state.report.isAcceptable) {
            throw FFmpegException(FFmpegError.IncompatibleFFmpegRuntime(state.report))
        }
        val attach = state.attachStatus
        val accepted = if (JniLibrary.isAndroid) attach == JVM_OK
            else attach == JVM_OK || attach == JVM_UNSUPPORTED
        if (!accepted) {
            throw FFmpegException(FFmpegError.Internal("KiteFFmpeg JVM attach failed: status=$attach"))
        }
    }

    internal fun hasDecoder(name: String) = checked { nativeHasDecoder(name) }
    internal fun hasEncoder(name: String) = checked { nativeHasEncoder(name) }
    internal fun hasFilter(name: String) = checked { nativeHasFilter(name) }
    internal fun liveHandles() = checked { nativeLiveHandles() }
    internal fun rescaleQ(value: Long, source: Rational, destination: Rational) = checked { nativeRescaleQ(value, source.num, source.den, destination.num, destination.den) }
    internal fun pixelFormatName(value: Int) = checked { nativePixelFormatName(value) }
    internal fun pixelFormatValue(name: String) = checked { nativePixelFormatValue(name) }
    internal fun sampleFormatName(value: Int) = checked { nativeSampleFormatName(value) }
    internal fun sampleFormatValue(name: String) = checked { nativeSampleFormatValue(name) }
    internal fun codecIdName(id: Int) = checked { nativeCodecIdName(id) }
    internal fun strerror(code: Int) = checked { nativeStrerror(code) }

    internal fun packetAlloc() = token("packet allocation") { nativePacketAlloc() }
    internal fun packetFree(token: Long) = checked { nativePacketFree(token) }
    internal fun packetClone(token: Long) = token("packet clone") { nativePacketClone(token) }
    internal fun packetPts(token: Long) = checked { nativePacketPts(token) }
    internal fun packetDts(token: Long) = checked { nativePacketDts(token) }
    internal fun packetDuration(token: Long) = checked { nativePacketDuration(token) }
    internal fun packetStreamIndex(token: Long) = checked { nativePacketStreamIndex(token) }
    internal fun packetSize(token: Long) = checked { nativePacketSize(token) }
    internal fun packetIsKeyframe(token: Long) = checked { nativePacketIsKeyframe(token) }
    internal fun packetPosition(token: Long) = checked { nativePacketPosition(token) }
    internal fun packetUnref(token: Long) = checked { nativePacketUnref(token) }
    internal fun packetSetStreamIndex(token: Long, value: Int) = checked { nativePacketSetStreamIndex(token, value) }
    internal fun packetSetPts(token: Long, value: Long) = checked { nativePacketSetPts(token, value) }
    internal fun packetSetDts(token: Long, value: Long) = checked { nativePacketSetDts(token, value) }
    internal fun packetRescale(token: Long, source: Rational, destination: Rational) = checked { nativePacketRescale(token, source.num, source.den, destination.num, destination.den) }
    internal fun packetMoveRef(destination: Long, source: Long) = checked { nativePacketMoveRef(destination, source) }
    internal fun packetBytes(token: Long) = checked { nativePacketBytes(token) }

    internal fun fmtOpenInput(path: String) = token("input open") { nativeFmtOpenInput(path) }
    internal fun fmtOpenInput2(path: String, keys: Array<String>?, values: Array<String>?, unusedKeysOut: Array<String?>?) =
        token("input open with options") { nativeFmtOpenInput2(path, keys, values, unusedKeysOut) }
    internal fun fmtOpenInputIo(
        io: JniByteIo,
        seekable: Boolean,
        size: Long,
        keys: Array<String>?,
        values: Array<String>?,
        unusedKeysOut: Array<String?>?,
    ) = token("custom io open") { nativeFmtOpenInputIo(io, seekable, size, keys, values, unusedKeysOut) }
    internal fun fmtCloseInputIo(token: Long) = checked { nativeFmtCloseInputIo(token) }
    internal fun fmtInterrupt(token: Long) = checked { nativeFmtInterrupt(token) }
    internal fun fmtChapterCount(token: Long) = checked { nativeFmtChapterCount(token) }
    internal fun fmtChapterGet(token: Long, index: Int, outFields: LongArray) = checked { nativeFmtChapterGet(token, index, outFields) }
    internal fun fmtChapterMetadata(token: Long, index: Int) = nativeFmtChapterMetadata(token, index)
    internal fun fmtCloseInput(token: Long) = checked { nativeFmtCloseInput(token) }
    internal fun fmtFindStreamInfo(token: Long) = checked { nativeFmtFindStreamInfo(token) }
    internal fun fmtNbStreams(token: Long) = checked { nativeFmtNbStreams(token) }
    internal fun fmtStream(token: Long, index: Int) = token("stream lookup") { nativeFmtStream(token, index) }
    internal fun fmtDuration(token: Long) = checked { nativeFmtDurationMicros(token) }
    internal fun fmtReadFrame(token: Long, packet: Long) = checked { nativeFmtReadFrame(token, packet) }
    internal fun fmtSeekMicros(token: Long, stream: Int, micros: Long) = checked { nativeFmtSeekMicros(token, stream, micros) }
    internal fun fmtSetOpt(token: Long, key: String, value: String?) = checked { nativeFmtSetOpt(token, key, value) }
    internal fun fmtStartTime(token: Long) = checked { nativeFmtStartTime(token) }
    internal fun fmtInputName(token: Long) = checked { nativeFmtInputName(token) ?: "" }
    internal fun fmtIsSeekable(token: Long) = checked { nativeFmtIsSeekable(token) }
    internal fun fmtSeekFile(token: Long, stream: Int, min: Long, target: Long, max: Long, flags: Int) = checked { nativeFmtSeekFile(token, stream, min, target, max, flags) }
    internal fun fmtMetadata(token: Long) = checked { nativeFmtMetadata(token) }
    internal fun fmtAllocOutput(path: String?, format: String?) = token("output allocation") { nativeFmtAllocOutput(path, format) }
    /** Returns the close result: negative when the final flush or file close failed (P1-13). */
    internal fun fmtFreeOutput(token: Long): Int = checked { nativeFmtFreeOutput(token) }
    internal fun fmtNewStream(format: Long, codec: Long = 0) = token("output stream allocation") { nativeFmtNewStream(format, codec) }
    internal fun fmtIoOpen(token: Long, path: String) = checked { nativeFmtIoOpen(token, path) }
    internal fun fmtAvoidNegativeTs(token: Long) = checked { nativeFmtAvoidNegativeTs(token) }
    internal fun fmtWriteHeader(token: Long) = checked { nativeFmtWriteHeader(token) }
    internal fun fmtWriteFrame(token: Long, packet: Long) = checked { nativeFmtWriteFrame(token, packet) }
    internal fun fmtWriteTrailer(token: Long) = checked { nativeFmtWriteTrailer(token) }
    internal fun fmtGlobalHeader(token: Long) = checked { nativeFmtGlobalHeader(token) }
    internal fun fmtSetMetadata(token: Long, key: String, value: String?) = checked { nativeFmtSetMetadata(token, key, value) }
    internal fun borrowedRelease(token: Long, kind: Int) = checked { nativeBorrowedRelease(token, kind) }
    internal fun streamIndex(token: Long) = checked { nativeStreamIndex(token) }
    internal fun streamCodecPar(token: Long) = token("stream codec parameters") { nativeStreamCodecPar(token) }
    internal fun streamDuration(token: Long) = checked { nativeStreamDuration(token) }
    internal fun streamStartTime(token: Long) = checked { nativeStreamStartTime(token) }
    internal fun streamMetadata(token: Long) = checked { nativeStreamMetadata(token) }
    internal fun streamTimeBase(token: Long) = unpackRational(checked { nativeStreamTimeBase(token) })
    internal fun streamFrameRate(token: Long) = unpackRational(checked { nativeStreamFrameRate(token) })
    internal fun streamSetTimeBase(token: Long, value: Rational) = checked { nativeStreamSetTimeBase(token, value.num, value.den) }
    internal fun streamDiscard(token: Long, discard: Boolean) = checked { nativeStreamDiscard(token, discard) }
    internal fun streamDisposition(token: Long) = checked { nativeStreamDisposition(token) }
    internal fun streamRotation(token: Long) = checked { nativeStreamRotation(token) }
    internal fun dictNext(token: Long, previous: Long) = checked { nativeDictNext(token, previous) }
    internal fun dictKey(token: Long) = checked { nativeDictKey(token) ?: "" }
    internal fun dictValue(token: Long) = checked { nativeDictValue(token) ?: "" }
    internal fun readDictionary(token: Long): Map<String, String> {
        if (token == 0L) return emptyMap()
        val result = linkedMapOf<String, String>()
        var entry = 0L
        try {
            while (true) {
                entry = dictNext(token, entry)
                if (entry == 0L) break
                result[dictKey(entry)] = dictValue(entry)
            }
        } finally {
            if (entry != 0L) borrowedRelease(entry, KIND_DICT_ENTRY)
            borrowedRelease(token, KIND_DICT)
        }
        return result
    }
    internal fun codecParType(token: Long) = checked { nativeCodecParType(token) }
    internal fun codecParId(token: Long) = checked { nativeCodecParId(token) }
    internal fun codecParBitrate(token: Long) = checked { nativeCodecParBitrate(token) }
    internal fun codecParWidth(token: Long) = checked { nativeCodecParWidth(token) }
    internal fun codecParHeight(token: Long) = checked { nativeCodecParHeight(token) }
    internal fun codecParFormat(token: Long) = checked { nativeCodecParFormat(token) }
    internal fun codecParProfile(token: Long) = checked { nativeCodecParProfile(token) }
    internal fun codecParLevel(token: Long) = checked { nativeCodecParLevel(token) }
    internal fun codecParColorSpace(token: Long) = checked { nativeCodecParColorSpace(token) }
    internal fun codecParColorPrimaries(token: Long) = checked { nativeCodecParColorPrimaries(token) }
    internal fun codecParColorTransfer(token: Long) = checked { nativeCodecParColorTransfer(token) }
    internal fun codecParColorRange(token: Long) = checked { nativeCodecParColorRange(token) }
    internal fun codecParChromaLocation(token: Long) = checked { nativeCodecParChromaLocation(token) }
    internal fun codecParBitDepth(token: Long) = checked { nativeCodecParBitDepth(token) }
    internal fun codecParChromaSubsampling(token: Long) = checked { nativeCodecParChromaSubsampling(token) }
    internal fun codecParSampleRate(token: Long) = checked { nativeCodecParSampleRate(token) }
    internal fun codecParChannels(token: Long) = checked { nativeCodecParChannels(token) }
    internal fun codecParExtradata(token: Long) = checked { nativeCodecParExtradata(token) }
    internal fun codecParSar(token: Long) = unpackRational(checked { nativeCodecParSar(token) })
    internal fun codecParChannelLayout(token: Long) = checked { nativeCodecParChannelLayout(token) }
    internal fun codecParFromContext(parameters: Long, context: Long) = checked { nativeCodecParFromContext(parameters, context) }
    internal fun codecParCopy(destination: Long, source: Long) = checked { nativeCodecParCopy(destination, source) }

    internal fun findDecoderById(id: Int) = checked { nativeFindDecoderById(id) }
    internal fun findDecoderByName(name: String) = checked { nativeFindDecoderByName(name) }
    internal fun findEncoderByName(name: String) = checked { nativeFindEncoderByName(name) }
    internal fun codecId(token: Long) = checked { nativeCodecId(token) }
    internal fun codecRelease(token: Long) = checked { nativeCodecRelease(token) }
    internal fun codecCtxAlloc(codec: Long) = token("codec context allocation") { nativeCodecCtxAlloc(codec) }
    internal fun codecCtxFree(token: Long) = checked { nativeCodecCtxFree(token) }
    internal fun codecCtxFromPar(context: Long, parameters: Long) = checked { nativeCodecCtxFromPar(context, parameters) }
    internal fun codecCtxOpen(context: Long, codec: Long) = checked { nativeCodecCtxOpen(context, codec) }
    internal fun codecCtxSetOpt(context: Long, key: String, value: String?) = checked { nativeCodecCtxSetOpt(context, key, value) }
    internal fun codecCtxUseVideoToolbox(context: Long) = checked { nativeCodecCtxUseVideoToolbox(context) }
    internal fun codecCtxSendPacket(context: Long, packet: Long) = checked { nativeCodecCtxSendPacket(context, packet) }
    internal fun codecCtxReceiveFrame(context: Long, frame: Long) = checked { nativeCodecCtxReceiveFrame(context, frame) }
    internal fun codecCtxFlush(context: Long) = checked { nativeCodecCtxFlush(context) }
    internal fun codecCtxSetThreads(context: Long, count: Int, frameLevel: Boolean) = checked { nativeCodecCtxSetThreads(context, count, frameLevel) }
    internal fun codecCtxSetLowDelay(context: Long, enabled: Boolean) = checked { nativeCodecCtxSetLowDelay(context, enabled) }
    internal fun codecCtxSendFrame(context: Long, frame: Long) = checked { nativeCodecCtxSendFrame(context, frame) }
    internal fun codecCtxReceivePacket(context: Long, packet: Long) = checked { nativeCodecCtxReceivePacket(context, packet) }
    internal fun codecCtxSetVideo(context: Long, width: Int, height: Int, format: Int, frameRate: Rational, timeBase: Rational, bitrate: Long, gop: Int) = checked { nativeCodecCtxSetVideo(context, width, height, format, frameRate.num, frameRate.den, timeBase.num, timeBase.den, bitrate, gop) }
    internal fun codecCtxSetAudio(context: Long, rate: Int, format: Int, channels: Int, bitrate: Long) = checked { nativeCodecCtxSetAudio(context, rate, format, channels, bitrate) }
    internal fun codecFirstSampleFormat(codec: Long) = checked { nativeCodecFirstSampleFormat(codec) }
    internal fun codecFirstPixelFormat(codec: Long) = checked { nativeCodecFirstPixelFormat(codec) }
    internal fun codecSupportsPixelFormat(codec: Long, format: Int) = checked { nativeCodecSupportsPixelFormat(codec, format) }
    internal fun codecCtxFrameSize(context: Long) = checked { nativeCodecCtxFrameSize(context) }
    internal fun codecCtxSampleRate(context: Long) = checked { nativeCodecCtxSampleRate(context) }
    internal fun codecCtxChannels(context: Long) = checked { nativeCodecCtxChannels(context) }
    internal fun codecCtxTimeBase(context: Long) = unpackRational(checked { nativeCodecCtxTimeBase(context) })
    internal fun codecCtxGlobalHeader(context: Long) = checked { nativeCodecCtxGlobalHeader(context) }
    internal fun codecCtxFullRange(context: Long) = checked { nativeCodecCtxFullRange(context) }
    internal fun codecCtxPixelFormat(context: Long) = checked { nativeCodecCtxPixelFormat(context) }
    internal fun codecCtxWidth(context: Long) = checked { nativeCodecCtxWidth(context) }
    internal fun codecCtxHeight(context: Long) = checked { nativeCodecCtxHeight(context) }

    internal fun frameAlloc() = token("frame allocation") { nativeFrameAlloc() }
    internal fun frameFree(token: Long) = checked { nativeFrameFree(token) }
    internal fun framePts(token: Long) = checked { nativeFramePts(token) }
    internal fun frameWidth(token: Long) = checked { nativeFrameWidth(token) }
    internal fun frameHeight(token: Long) = checked { nativeFrameHeight(token) }
    internal fun frameFormat(token: Long) = checked { nativeFrameFormat(token) }
    internal fun frameIsKeyframe(token: Long) = checked { nativeFrameIsKeyframe(token) }
    internal fun frameCopyPlanes(token: Long) = checked { nativeFrameCopyPlanes(token) }
    internal fun frameUnref(token: Long) = checked { nativeFrameUnref(token) }
    internal fun frameClone(token: Long) = token("frame clone") { nativeFrameClone(token) }
    internal fun frameConvert(token: Long, format: Int) = token("frame conversion") { nativeFrameConvert(token, format) }
    internal fun frameDuration(token: Long) = checked { nativeFrameDuration(token) }
    internal fun frameSampleCount(token: Long) = checked { nativeFrameSampleCount(token) }
    internal fun frameSampleRate(token: Long) = checked { nativeFrameSampleRate(token) }
    internal fun frameChannels(token: Long) = checked { nativeFrameChannels(token) }
    internal fun frameChannelLayout(token: Long) = checked { nativeFrameChannelLayout(token) }
    internal fun frameColorRange(token: Long) = checked { nativeFrameColorRange(token) }
    internal fun frameColorSpace(token: Long) = checked { nativeFrameColorSpace(token) }
    internal fun frameColorPrimaries(token: Long) = checked { nativeFrameColorPrimaries(token) }
    internal fun frameColorTransfer(token: Long) = checked { nativeFrameColorTransfer(token) }
    internal fun frameChromaLocation(token: Long) = checked { nativeFrameChromaLocation(token) }
    internal fun frameSampleAspectRatio(token: Long) = unpackRational(checked { nativeFrameSampleAspectRatio(token) })
    internal fun frameIsHardware(token: Long) = checked { nativeFrameIsHardware(token) }
    internal fun frameHwDownload(source: Long, destination: Long) = checked { nativeFrameHwDownload(source, destination) }
    internal fun frameUseBestEffort(token: Long) = checked { nativeFrameUseBestEffort(token) }
    internal fun frameSetPts(token: Long, value: Long) = checked { nativeFrameSetPts(token, value) }
    internal fun frameSetFormat(token: Long, value: Int) = checked { nativeFrameSetFormat(token, value) }
    internal fun frameSetWidth(token: Long, value: Int) = checked { nativeFrameSetWidth(token, value) }
    internal fun frameSetHeight(token: Long, value: Int) = checked { nativeFrameSetHeight(token, value) }
    internal fun frameSetSampleRate(token: Long, value: Int) = checked { nativeFrameSetSampleRate(token, value) }
    internal fun frameSetSampleCount(token: Long, value: Int) = checked { nativeFrameSetSampleCount(token, value) }
    internal fun frameSetChannels(token: Long, value: Int) = checked { nativeFrameSetChannels(token, value) }
    internal fun frameGetBuffer(token: Long, align: Int) = checked { nativeFrameGetBuffer(token, align) }
    internal fun frameFillVideo(token: Long, bytes: ByteArray) = checked { nativeFrameFillVideo(token, bytes) }
    internal fun frameFillAudio(token: Long, bytes: ByteArray) = checked { nativeFrameFillAudio(token, bytes) }

    internal fun graphBuildVideo(description: String, width: Int, height: Int, format: Int, timeBase: Rational, frameRate: Rational, sar: Rational) = checked { nativeGraphBuildVideo(description, width, height, format, timeBase.num, timeBase.den, frameRate.num, frameRate.den, sar.num, sar.den) }
    internal fun graphBuildAudio(description: String?, rate: Int, format: Int, channels: Int, timeBase: Rational, outFormat: Int, outRate: Int, outChannels: Int) = checked { nativeGraphBuildAudio(description, rate, format, channels, timeBase.num, timeBase.den, outFormat, outRate, outChannels) }
    internal fun graphBuildVideoMulti(description: String, count: Int, widths: IntArray, heights: IntArray, formats: IntArray, tbns: IntArray, tbds: IntArray, frns: IntArray, frds: IntArray, sarns: IntArray, sards: IntArray) = checked { nativeGraphBuildVideoMulti(description, count, widths, heights, formats, tbns, tbds, frns, frds, sarns, sards) }
    internal fun graphBuildAudioMulti(description: String?, count: Int, rates: IntArray, formats: IntArray, channels: IntArray, tbns: IntArray, tbds: IntArray, outFormat: Int, outRate: Int, outChannels: Int) = checked { nativeGraphBuildAudioMulti(description, count, rates, formats, channels, tbns, tbds, outFormat, outRate, outChannels) }
    internal fun graphFree(token: Long) = checked { nativeGraphFree(token) }
    internal fun graphSend(source: Long, frame: Long) = checked { nativeGraphSend(source, frame) }
    internal fun graphReceive(sink: Long, frame: Long) = checked { nativeGraphReceive(sink, frame) }
    internal fun graphSetFrameSize(sink: Long, size: Int) = checked { nativeGraphSetFrameSize(sink, size) }
    internal fun graphTimeBase(sink: Long) = unpackRational(checked { nativeGraphTimeBase(sink) })

    private inline fun <T> checked(block: () -> T): T = try {
        requireCompatible()
        block()
    } catch (error: JniNativeException) {
        throw error.asFFmpegException()
    } catch (error: JniHandleException) {
        throw FFmpegException(FFmpegError.Internal(error.message ?: "invalid native handle"))
    }

    private inline fun token(label: String, block: () -> Long): Long = checked {
        block().also { if (it == 0L) throw FFmpegException(FFmpegError.Internal("$label returned no native handle")) }
    }

    internal const val KIND_CODEC = 1
    internal const val KIND_CODEC_PAR = 3
    internal const val KIND_DICT = 4
    internal const val KIND_DICT_ENTRY = 5
    internal const val KIND_FILTER_CTX = 6
    internal const val KIND_STREAM = 11
    private const val JVM_OK = 0
    private const val JVM_UNSUPPORTED = -2
}

internal class JniHandleException(message: String?) : RuntimeException(message)
internal class JniNativeException(message: String?) : RuntimeException(message)

private fun JniNativeException.asFFmpegException(): FFmpegException {
    val fields = (message ?: "").split('|', limit = 3)
    val code = fields.firstOrNull()?.toIntOrNull() ?: 0
    val context = fields.getOrNull(1).orEmpty()
    val detail = fields.getOrNull(2).orEmpty().ifEmpty { "AVERROR($code)" }
    val text = if (context.isEmpty()) "$detail (code=$code)" else "$context: $detail (code=$code)"
    return FFmpegException(FFmpegError.fromCode(code, text))
}

private fun parseIdentity(serialized: String): FFmpegIdentity {
    val fields = serialized.split('\u001f')
    if (fields.size != 31) throw FFmpegException(FFmpegError.Internal("Malformed JNI identity report: expected 31 fields, got ${fields.size}"))
    fun triple(value: String): IntArray = value.split('.').map { it.toInt() }.let {
        if (it.size != 3) throw NumberFormatException(value)
        intArrayOf(it[0], it[1], it[2])
    }
    val names = listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
    val verdicts = listOf("ok", "major mismatch", "runtime older than headers", "micro older than headers", "configuration disagrees")
    return try {
        val libraries = (0 until 6).map { index ->
            val base = 4 + index * 3
            val header = triple(fields[base])
            val runtime = triple(fields[base + 1])
            FFmpegLibraryIdentity(names[index], header[0], header[1], header[2], runtime[0], runtime[1], runtime[2], verdicts.getOrElse(fields[base + 2].toInt()) { "unknown" })
        }
        FFmpegIdentity(
            status = fields[0].toInt(), bypassed = fields[1].toInt() != 0,
            bypassedStatus = fields[1].toInt(), cAbiVersion = "${fields[2]}.${fields[3]}",
            libraries = libraries, configurationsAgree = fields[22].toInt() != 0,
            configurationsDisagreed = fields[24].takeIf { it.isNotEmpty() }?.split(", ") ?: emptyList(),
            buildFFmpegRef = fields[25], buildLicenseFlavour = fields[26],
            buildProvisioningDir = fields[27], runtimeVersionInfo = fields[28],
            runtimeLicense = fields[29], provisioning = fields[30],
        )
    } catch (error: RuntimeException) {
        throw FFmpegException(FFmpegError.Internal("Malformed JNI identity report: ${error.message}"))
    }
}

internal fun unpackRational(value: Long): Rational {
    val denominator = value.toInt()
    return Rational((value ushr 32).toInt(), if (denominator == 0) 1 else denominator)
}
internal fun avError(code: Int): FFmpegError = FFmpegError.fromCode(code, "${Internals.strerror(code)} (code=$code)")
internal fun check0(code: Int, label: String) {
    if (code < 0) {
        val error = avError(code)
        throw FFmpegException(FFmpegError.fromCode(code, "$label: ${error.message}"))
    }
}
internal fun pixelFormatFromAv(value: Int): PixelFormat = if (value < 0) PixelFormat.None else Internals.pixelFormatName(value)?.let(::PixelFormat) ?: PixelFormat.None
internal fun pixelFormatToAv(format: PixelFormat): Int = Internals.pixelFormatValue(format.name)
internal fun sampleFormatFromAv(value: Int): SampleFormat = if (value < 0) SampleFormat.None else Internals.sampleFormatName(value)?.let(::SampleFormat) ?: SampleFormat.None
internal fun sampleFormatToAv(format: SampleFormat): Int = Internals.sampleFormatValue(format.name)
internal fun mediaTypeFromAv(value: Int): MediaType = when (value) {
    Internals.mediaTypeVideo -> MediaType.Video
    Internals.mediaTypeAudio -> MediaType.Audio
    Internals.mediaTypeSubtitle -> MediaType.Subtitle
    Internals.mediaTypeData -> MediaType.Data
    Internals.mediaTypeAttachment -> MediaType.Attachment
    else -> MediaType.Unknown
}
internal actual fun rescaleQ(value: Long, source: Rational, destination: Rational): Long = Internals.rescaleQ(value, source, destination)

/** Leaf actual performs only the platform-specific load. [Internals.identity] then runs the gate,
 * maps its report and attaches in that order exactly once. */
internal expect object JniLibrary {
    val isAndroid: Boolean
    fun ensureLoaded()
}
