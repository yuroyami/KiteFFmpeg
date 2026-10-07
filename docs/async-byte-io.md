# Asynchronous custom byte I/O: planned contract

**Status: accepted planned contract, not implemented or available in a release.** This document records the public API and ownership design for [KiteFFmpeg #183](https://github.com/yuroyami/KiteFFmpeg/issues/183). Existing synchronous classes, signatures, defaults and the plain web codec artifact remain unchanged. See [Decoding](decoding.md) for the currently available API. Implementation, generated ABI changes and the real FFmpeg acceptance gates below must follow this separate design commit.

The authored mechanism probes exercised tiny C/Wasm programs and actual Kotlin/Wasm stdlib coroutine bridges with Emscripten 6.0.10-git, Kotlin/Wasm 2.4.20, Node 26.10.0 and headless Chrome 149.0.7827.201 on page and Worker, without cross-origin isolation. They produced 60 positive method executions and 12 intended synchronous-import negative suite failures: repeated executions of five authored methods, not 60 distinct tests. These results establish the tested stack-suspension mechanisms only. No async FFmpeg artifact or public async API has been implemented, and real FFmpeg reachability, kotlinx Job cancellation, other engines, host backends and playback behavior remain unqualified.

## Owned runtime and artifact choice

Add a common `AsyncMediaRuntime` owner. On JVM/native it owns an execution lane for C callbacks. On web it owns one separate codec module instance, a module-local registration table, one active native operation, bounded request scratch, cleanup records and log capture. It does not use or replace `KiteFFmpegWeb.module`. Multiple sources may share it, with serialized codec operations. The scope of serialization is the runtime instance, never the entire page or every library instance.

The ordinary `kite.mjs`/`kite.wasm` remain plain. Publish distinct `kite-jspi.mjs`/`.wasm` and `kite-asyncify.mjs`/`.wasm`, each with a versioned async bridge marker and exact C import/export surface. Browser feature detection selects JSPI only when the APIs exist and its artifact passed qualification. Asyncify is a separate explicit compatibility artifact. No browser version floor is inferred from another engine or an issue tracker.

Planned wasmJs declarations and members added to `KiteFFmpegWeb`:

```kotlin
public class WebAsyncCodecArtifacts(
    public val jspiUrl: String? = null,
    public val asyncifyUrl: String? = null,
)

// New members of KiteFFmpegWeb; existing load/attach and their defaults stay unchanged.
public suspend fun loadAsyncRuntime(artifacts: WebAsyncCodecArtifacts): AsyncMediaRuntime
public fun attachAsyncRuntime(codecModule: JsAny): AsyncMediaRuntime
```

Each loadAsyncRuntime invocation intentionally creates one new independent runtime, even for the same URLs; callers share its returned owner explicitly when desired. It has no legacy load-style global singleton or implicit cross-call cache. A canceled load must settle and dispose any instance that arrives late, using the async artifact's explicit disposal hook, before its creation operation is considered cleaned up. These entry points create or adopt an owned runtime, not a second global default. A module-identity registry, weakly keyed on web, admits exactly one owner. Duplicate attachment of a live, closing or closed instance is refused without closing or altering its existing owner. Callers who intend to share a runtime explicitly share the originally returned object. The identity claim and adoption are atomic with respect to competing attachment and legacy loading. Failure before adoption releases only the new claim; a failed or canceled loader must never dispose an instance already owned elsewhere. A successfully adopted instance remains consumed after close. A module cannot be owned by both legacy attach and an async runtime. Validate the bridge version, strategy, required promising exports and all runtime methods before taking byte-source ownership. A plain artifact fails explicitly with `FFmpegError.Unsupported`. The artifacts constructor requires at least one supplied URL and rejects a supplied blank URL. If JSPI is supported and jspiUrl is supplied, select JSPI. Otherwise select a supplied asyncifyUrl. If neither is eligible, fail with the typed unsupported capability error. A selected artifact load, validation or corruption failure surfaces that actual failure and never triggers a strategy fallback. Supplying only asyncifyUrl deliberately selects Asyncify even on a JSPI-capable engine; tests must exercise that supported route without fake URLs or modified feature detection.

| JSPI capability | Supplied URLs | Selection |
| --- | --- | --- |
| Present | Both, or JSPI only | JSPI |
| Present | Asyncify only | Asyncify |
| Absent | Both, or Asyncify only | Asyncify |
| Absent | JSPI only | Typed unsupported capability failure |
| Either | None, or a supplied blank URL | Constructor argument refusal |

For every selected strategy, a broken module is a load/validation failure, not a reason to try the other URL. A plain non-async module passed to attach is refused before provider acquisition. The async module marker includes its strategy, bridge version and required export/import surface.

For JVM/native, a new common `FFmpeg.createAsyncRuntime()` suspending factory creates the owned lane without loading a web artifact. On web that factory reports that an explicit async artifact must be supplied through the web loader. This parallels the existing explicit web load prerequisite without introducing a hidden global async module. Common callers can receive the runtime as a dependency.

## Byte provider surface

The provider interfaces and cleanup signatures are listed in [Concrete planned public surface](#concrete-planned-public-surface).

`read` returns 1..length or -1 at EOF, never 0. Zero/over-count fails without copying into C. Positions and sizes remain signed 64-bit values, with nonnegative valid positions, null unknown size, checked arithmetic and exact BigInt/string transport. `size()` can suspend and is queried for later AVSEEK_SIZE requests. As in the current custom-input contract, null after a known size retains the last known value rather than erasing it. A false seekable prevents position-changing seeks. Metadata getters must be immediate; asynchronous discovery belongs in the awaited opener. `location` is final before the opener returns, with the same empty/NUL rules as the synchronous API. Root `takeTags` follows each positive read; nested sources are never asked for tags.

Each input is an exclusive cursor lease. Passing the same provider object as two live roots, returning a root as its own nested child, or returning one live child object for multiple opens is invalid; callers must return separate provider objects with independent cursors. Admission compares identity, never structural equality. The runtime detects duplicate identities among the roots and children it currently owns and refuses the duplicate without closing the existing lease. Cross-runtime sharing of a provider object is a caller contract violation; this API does not introduce a process-wide strong provider registry. For a fresh identity, ownership transfers when runtime.open is called, including failed or canceled opens after invocation. An opener's fresh non-null result transfers ownership immediately on return, including cancellation racing that return; such a late source is closed instead of registered. Each transferred source closes once. An already transferred provider must not be reused after close. No request-specific HTTP fields, manifest slicing, network client or player policy is added. Redirect behavior, credential forwarding/stripping, CORS, range validation and response-body limits belong to the provider. The library does not silently follow or reconstruct redirects. A Fetch provider choosing redirect:error must preserve that refusal, while a host provider may implement an explicit follow-with-strip policy. Capability selection cannot weaken either policy.

A provider may not reenter operations or close on a runtime already in its callback ancestry. An inherited coroutine-context set of runtime identities rejects the cycle before queuing, including A callback -> B operation -> B callback -> A operation. Ordinary callbacks and cleanup calls, including suspending provider.close, carry that ancestry. A non-cyclic call to another runtime is allowed; unrelated external tasks can still create application-level wait cycles, which callers must avoid. A single marker overwritten by the innermost owner is insufficient. Normal calls for one provider are serialized. Cancellation can invoke the provider's suspending close while its canceled read is finishing, so close must abort its transport and be idempotent. The library retains that read's ByteArray until the provider invocation has settled, never reuses it for a new request, and never exposes a codec-memory pointer. Returning or throwing from read ends the provider's right to access that ByteArray; a provider wrapping a non-cancellable external Promise must prevent later writes after it returns cancellation. The import still validates operation identity immediately before copying any result. Providers must honor coroutine cancellation and bound their own waits. Arbitrary uncooperative synchronous code cannot be forcibly stopped.

## Media operations and handles

Use distinct runtime-owned async handles: `AsyncMediaSource`, `AsyncPacketReader`, `AsyncPacket`, `AsyncStreamDecoder`, `AsyncSubtitleDecoder`, and `AsyncFrame`. All inherit AsyncCloseable. Constructors remain internal; no raw pointer or public private-transport field is exposed. Reuse existing StreamInfo, FrameInfo, Rational, Program, Subtitle and other media descriptions with their existing contracts. These are owned read-only snapshots, not a new guarantee of deep immutability: StreamInfo.codecExtradata is currently a public ByteArray whose documented contract forbids caller mutation. Do not widen this change into an incompatible replacement of those descriptor types.

This is deliberate additional API surface. Existing web Packet/Frame/Decoder methods are synchronous and resolve a global module. Returning those objects from a separate runtime would require a broad module-affinity change and would still leave synchronous calls unable to wait for a parked Asyncify stack. Implicitly throwing a busy error from ordinary frame copying or pretending immediate close freed a queued resource would weaken the existing contracts. Distinct async handles make the suspension and owner explicit. They share internal algorithms, bindings and metadata conversion instead of copying an entire backend.

## Concrete planned public surface

These are declaration signatures for the planned API, not runnable examples of an available
release. Implementation bodies, internal fields and platform actuals are omitted. All declarations
stay in `io.github.yuroyami.kiteffmpeg`; `DecoderOptions` and `DecoderSkip` retain their existing
`io.github.yuroyami.kiteffmpeg.dsl` types. `Flow`, `Channel`, `CoroutineContext` and
`EmptyCoroutineContext` use the existing coroutines/stdlib dependency.

The factories are new members of the existing `FFmpeg` and `KiteFFmpegWeb` objects. Providers are
caller-implemented interfaces; runtime and media classes have internal constructors. `AsyncMediaInfo`
also has an internal constructor and is published only by its source. A closed handle refuses further
media operations and getters, as existing owned handles do; a separately saved description remains a
read-only snapshot. Repeated close observes the same cleanup result.

```kotlin
// Common member added to FFmpeg.
public suspend fun createAsyncRuntime(): AsyncMediaRuntime

public interface AsyncCloseable {
    public suspend fun close()
}

public interface AsyncMediaByteSource : AsyncCloseable {
    public val seekable: Boolean
    public val location: String? get() = null
    public suspend fun size(): Long?
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int
    public suspend fun seek(position: Long)
    public suspend fun takeTags(): Map<String, String>? = null
}

public fun interface AsyncMediaByteOpener {
    public suspend fun open(url: String): AsyncMediaByteSource?
}

public class AsyncMediaRuntime internal constructor() : AsyncCloseable {
    public val identity: FFmpegIdentity
    public suspend fun open(
        io: AsyncMediaByteSource,
        options: Map<String, String> = emptyMap(),
        interrupt: OpenInterrupt? = null,
        url: String? = null,
        mimeType: String? = null,
        nestedOpener: AsyncMediaByteOpener? = null,
    ): AsyncMediaSource
    override suspend fun close()
}

public class AsyncMediaInfo internal constructor() {
    public val streams: List<StreamInfo>
    public val durationMicros: Long?
    public val durationOrigin: DurationOrigin?
    public val formatName: String
    public val metadata: Map<String, String>
    public val chapters: List<Chapter>
    public val matroska: MatroskaSegment?
    public val programs: List<Program>
    public val unusedOpenOptions: List<String>
    public val bitrateBps: Long?
    public val startTimeMicros: Long
    public val isSeekable: Boolean
    public val primaryVideo: StreamInfo?
    public val primaryAudio: StreamInfo?
}

public class AsyncMediaSource internal constructor() : AsyncCloseable {
    public val info: AsyncMediaInfo
    public val corruptData: CorruptData
    public val corruptDataSkipped: Long
    public val streamDivergences: List<StreamDivergence>

    public fun decodedFrames(stream: StreamInfo): Flow<AsyncFrame>
    public fun decodeStreams(streams: List<StreamInfo>): Flow<AsyncFrame>
    public suspend fun seekMicros(micros: Long)
    public suspend fun extractFrame(atMicros: Long, stream: StreamInfo? = null): AsyncFrame

    @KiteFFmpegLowLevelApi
    public suspend fun openPacketReader(streams: List<StreamInfo>): AsyncPacketReader

    @KiteFFmpegLowLevelApi
    public suspend fun openDecoder(
        stream: StreamInfo,
        threadCount: Int = 0,
        lowDelay: Boolean = false,
        decoder: DecoderId? = null,
        options: DecoderOptions? = null,
        hardware: HardwareAccel? = null,
        corruptData: CorruptData = CorruptData.Skip,
    ): AsyncStreamDecoder

    @KiteFFmpegLowLevelApi
    public suspend fun openSubtitleDecoder(
        stream: StreamInfo,
        realTime: Boolean = false,
    ): AsyncSubtitleDecoder

    public suspend fun pause(): Boolean
    public suspend fun resume(): Boolean
    public suspend fun setCorruptData(value: CorruptData)
    public suspend fun resetCorruptDataSkipped()
    public fun interrupt()
    override suspend fun close()
}

@KiteFFmpegLowLevelApi
public class AsyncPacketReader internal constructor() : AsyncCloseable {
    public suspend fun read(): AsyncPacket?
    public suspend fun seek(
        micros: Long,
        direction: SeekDirection = SeekDirection.Backward,
        notEarlierThan: Long? = null,
    )
    public suspend fun reselect(streams: List<StreamInfo>)
    override suspend fun close()
}

@KiteFFmpegLowLevelApi
public class AsyncPacket internal constructor() : AsyncCloseable {
    public val timeBase: Rational
    public val streamIndex: Int
    public val pts: Long
    public val dts: Long
    public val duration: Long
    public val isKeyframe: Boolean
    public val sizeBytes: Int
    public val bytePosition: Long
    public val hasPts: Boolean
    public val ptsMicros: Long?
    public val dtsMicros: Long?
    public val durationMicros: Long?
    public val newContainerTags: Map<String, String>?
    public val newStreamTags: Map<String, String>?
    public val newStreams: List<StreamInfo>?
    public val newPrograms: List<Program>?
    public suspend fun copy(): AsyncPacket
    public suspend fun copyBytes(): ByteArray
    override suspend fun close()
}

@KiteFFmpegLowLevelApi
public class AsyncStreamDecoder internal constructor() : AsyncCloseable {
    public val stream: StreamInfo
    public val isDrained: Boolean
    public val corruptDataSkipped: Long
    public suspend fun send(packet: AsyncPacket?): Boolean
    public suspend fun receive(): AsyncFrame?
    public suspend fun flush()
    public suspend fun setSkipFrame(skip: DecoderSkip)
    public suspend fun resetCorruptDataSkipped()
    override suspend fun close()
}

@KiteFFmpegLowLevelApi
public class AsyncSubtitleDecoder internal constructor() : AsyncCloseable {
    public suspend fun decode(packet: AsyncPacket): Subtitle?
    public suspend fun drain(): Subtitle?
    public suspend fun flush()
    override suspend fun close()
}

public class AsyncFrame internal constructor() : AsyncCloseable {
    public val info: FrameInfo
    @KiteFFmpegLowLevelApi
    public val ptsMicros: Long?
    @KiteFFmpegLowLevelApi
    public val durationMicros: Long?
    @KiteFFmpegLowLevelApi
    public suspend fun copy(): AsyncFrame
    public suspend fun planesByteCount(): Int
    public suspend fun copyPlanesInto(destination: ByteArray): Int
    public suspend fun copyPlanesToByteArray(): ByteArray
    public suspend fun closedCaptions(): ByteArray?
    public suspend fun dolbyVision(): DolbyVisionMetadata?
    public suspend fun dolbyVisionRpu(): DolbyVisionRpu?
    public suspend fun downloadFromHardware(): AsyncFrame
    override suspend fun close()
}

public suspend fun <T : AsyncCloseable, R> T.useAsync(block: suspend (T) -> R): R

public fun Flow<AsyncFrame>.bufferAsyncFrames(
    capacity: Int = Channel.BUFFERED,
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<AsyncFrame>
```

`bufferAsyncFrames` accepts `Channel.BUFFERED` or a nonnegative finite capacity, with zero meaning
rendezvous. It rejects `Channel.UNLIMITED`, `Channel.CONFLATED` and other negative values. The
producer/queue/cleanup ledger owns at most the admitted capacity plus the producer and delivery
handoff slots; cancellation stops new admission and drains every undelivered frame. The bound does
not include frames already accepted and retained by the caller. `context` moves upstream work as in
the existing `bufferFrames`, without transferring the runtime's native lane to that dispatcher.

Media operation errors use `FFmpegException`; argument/cursor/closed-state preconditions retain the
existing Kotlin failure style. Normal cancellation uses `CancellationException`. The declarations
above omit repeated `@Throws` annotations for readability: runtime-backed suspending operations
export `FFmpegException` and `CancellationException` to native callers. Caller-implemented provider
callbacks, `AsyncCloseable.close` and the generic `useAsync` block may propagate arbitrary
`Exception` values, so their native contract uses `@Throws(Exception::class)`; overrides retain that
contract. The provider bridge catches those failures and records the original cause instead of
letting an exception escape through C. Native/Swift tests must verify these precise exported error
paths; a fatal language/runtime error is not a recoverable transport failure.

Immediate packet/frame timestamps are captured while the lane is held, using the same overflow-safe
rescaling and absent-timestamp semantics as the synchronous API. Their getters never invoke the
current platform-backed `rescaleQ` or any other codec export while another operation is parked.

Only the packet/reader/decoder family and the manual operations marked above require
`KiteFFmpegLowLevelApi`, matching the existing batch-versus-manual distinction. The byte-provider,
runtime factory, batch Flow, frame data extraction and cleanup helper remain usable for ordinary
asynchronous decoding. Encoding, filtering, image encoding, frame construction and Dolby Vision
composition are outside this input API; they are not advertised as implemented async counterparts.

Decoder-opening parameters retain the existing meanings and types: stream, threadCount, lowDelay, DecoderId, DecoderOptions, HardwareAccel and CorruptData. Cross-runtime packets are refused before a native call; a native handle never crosses instance memories. Frame/packet copies retain their original runtime. Initial async input completion includes audio/video/subtitle demux and decode plus owned data extraction; encoder/filter/Dolby Vision composition and frame-construction counterparts require their own API design before being exposed. Basic hardware download is included so decoded hardware frames can be extracted without passing them into another runtime. No synchronous bridge back to the ordinary Frame/Packet is promised.

`AsyncMediaInfo` captures every current container-description field in MediaSource, including streams, metadata, programs, duration and origin, start time, seekability, format, chapters, Matroska data, unused options, bit rate and stream-selection defaults. Publish one coherent completed-operation snapshot with no lazy getter that enters C. Refresh cheap scalar values and invalidated descriptions after open and relevant operations, sharing unchanged owned descriptions across snapshots. Preserve StreamTable's layout-stamp and first-packet correction model; do not recopy codec extradata, fonts, attachments, chapters or all metadata on every packet merely to make an outer snapshot. Read each one-shot native change indication once and derive both the source snapshot and packet newStreams/newPrograms/tag delivery from that captured change. A source snapshot must not consume a change before the packet reports it. Tests must count descriptor materializations as well as checking values and retained old snapshots.

A suspending `useAsync` helper performs close under NonCancellable cleanup. The initial Flow surface includes the `bufferAsyncFrames` ownership helper declared above. The existing bufferFrames implementation cannot be reused unchanged because its non-suspending onUndeliveredElement callback calls synchronous Frame.close. Initially accept only a finite nonnegative capacity or BUFFERED; unlimited or dropping/conflated admission is not part of this helper. The async helper must retain every undelivered handle in an owned cleanup ledger, bound pending cleanup by its admitted channel/producer capacity, stop admission during teardown, and await a drain under NonCancellable before its collection completes. It must not launch an unbounded fire-and-forget close per dropped frame. Plain buffer, flowOn and conflate retain their existing native-resource ownership hazards. A decode flow releases the runtime operation lane before invoking a collector; the source cursor lease may remain held by the flow, but the collector must be able to copy or close its delivered frame without deadlocking on the producer's lane. Close is idempotent and concurrent callers await the same result. A runtime close cancels/admission-closes its sources, drains active work, then closes remaining owned decoders/packets/frames and sources in dependency order. Closing a source invalidates its readers/decoders while independently retained packets/frames remain usable until their own close or runtime close. No resource can outlive its runtime.

## Static bridge and execution boundary

Add wasm-only static C trampolines for read, seek/size, root tags, nested open and any suspending provider cleanup boundary. They call known imports by registration/operation IDs. Do not pass a suspending callback through today's addFunction wrappers. The shared C opener struct can continue carrying ordinary C function pointers to those trampolines; its layout need not grow for this mechanism.

The call chain is explicit:

1. Kotlin suspending operation acquires the runtime lane and starts an operation record and scoped log capture.
2. A hand-written Promise-returning JS binding invokes a promising C export.
3. C calls a statically suspending import. The JS import starts the Kotlin provider coroutine and returns its Promise.
4. The Kotlin coroutine awaits Fetch/provider work, returns copied bytes and an exact scalar result to JS, and the import resolves. The parked C stack resumes at that call site.
5. The export completes, log capture and callback failures are finalized for that operation, then the lane is released and the Kotlin caller resumes.

Only C/Wasm frames lie between the promising export and suspending import. Kotlin's coroutine state machine returns a Promise to JS before C is parked; it is not an ordinary JavaScript frame that JSPI is asked to suspend. The authored Kotlin bridge probe exercises both the export await and provider await. Generated immediate scalar wrappers stay untouched for the plain artifact; async wrappers must be typed as Promise results and awaited. Audit open_input_io2, stream discovery, read_frame, checked seek, pause/play and close, plus any other export capable of reaching provider imports. The audit is an explicit export/import/reachable-callback matrix over the actual pinned FFmpeg, helper and final linked artifact, not a list of public method names. Checked seek performs read-ahead through kc_seek_keyframe; partial-open failure and source close can invoke nested close_fn before freeing AVIO buffers and child state. Those void cleanup callbacks must be able to suspend, record close failures, and resume normally so C completes all remaining frees. They cannot reject through the native stack or inherit an already canceled provider Job. Include HLS key and crypto children, live playlist refresh, probe-created children, and subtitle child contexts, not only one successful segment read. Keep Asyncify indirect-call analysis enabled; do not prune it or introduce IGNORE_INDIRECT until the complete shipped graph has evidence. Verify link-time instrumentation includes every required helper/archive frame and size the unwind stack from actual media tests.

The reachability audit also covers waits inside FFmpeg that do not enter a byte provider. In the pinned live-HLS loop, `av_usleep` reaches the single-thread Emscripten sleep implementation; a busy wait cannot release the browser event loop. The async artifacts must suspend these reachable timer waits through a named static import owned by the same admitted operation. Its timer must wake on operation abort, resume C normally and propagate interruption before more demux work. Link wrapping is acceptable only when the final linked artifact proves every required call reaches that wrapper; otherwise use a narrowly scoped async build change. Keep the plain artifact and host sleep behavior unchanged. Do not claim the provider bridge alone makes live playback asynchronous.

One lane covers all C calls, including decoder, frame, allocation and log-capture helpers, for either strategy initially. JSPI permits more reentrancy in principle; the current library and C globals do not establish its safety. Separate web module instances can progress independently. Host runtimes share the linked process-global FFmpeg configuration and log sink; separate lanes do not create separate native libraries or instance-local global log sinks. Keep a native operation's thread-local log-capture begin, C call, rendezvous wait and capture end on one actual owned host thread. A dispatcher limited to one concurrent task but free to switch OS threads is not by itself that guarantee. Codec worker-thread logs retain their existing capture limits. Asyncify overhead and per-runtime memory cost must be measured on actual artifacts; this design makes no size or latency claim.

## Cancellation, errors and closing

Each operation has an identity, provider job, interruption token, failure collector and request leases. Caller cancellation raises the interruption flag through safe module memory/control state, aborts provider requests, and makes pending imports resume with the proper negative result. Do not reject an import directly through C frames when cleanup must execute. Preserve the original provider failure for the matching demux operation only; clear recovered failures between operations, preserving errors across successful sibling callbacks within one operation.

The operation then awaits the C export's unwind under cleanup context before rethrowing CancellationException. Never use the current loader's pattern of abandoning a canceled await while the Promise continues to touch native storage. A simultaneous close of that source follows the same unwind completion, then frees state. Closing a different source or an independent packet/frame only closes admission for that owner and queues its native disposal; it must not interrupt or terminalize the source of the currently entered operation. Runtime close may stop all its sources. A normal seek waits its turn. Cancellation after a native demux operation starts is terminal for that source: after unwind it closes and refuses later reads/seeks. The runtime and independent sources remain usable. Cancellation while waiting for admission does not touch an unentered source. This retains the existing one-way interrupt semantics instead of assuming a generic demuxer can recover from a partially completed read. A live fetch that waits indefinitely can otherwise keep a queued seek waiting indefinitely. Providers need bounded read/operation deadlines; a caller may interrupt, close and reopen at the target, with the additional network/probe cost explicit. Reusable cancel-then-seek requires a separate real FFmpeg recovery proof and design before it is promised. In particular this initial contract alone does not establish responsive live seeking for a player. The same lane also delays AsyncFrame pixel copying, packet cloning and disposal while any source in the runtime has a parked read. Eager metadata does not remove that limitation. An integration that needs already decoded pixels during a pending read must extract owned display data before admitting that read, or separately prove and design safe concurrency; it cannot simply call a synchronous frame helper while Asyncify is parked. Reopening on the same runtime waits for old provider settlement and cleanup. Creating replacement runtimes cannot be treated as an unbounded escape from uncooperative providers. Actual player qualification must measure these control and buffered-presentation consequences through the required experiments below; this initial safety rule is not a decision to leave those controls unfinished.

No codec pointer reaches a provider. Import results copy to codec memory only after validating active generation, count and ownership. An obsolete result is discarded. One outstanding buffer lease remains charged until its provider settles; canceled leases cannot be silently dropped from the byte budget. Admission/backpressure prevents new requests from accumulating behind an uncooperative provider. Runtime close can invalidate all native access after unwind, but it cannot claim that arbitrary provider code has ceased or that its buffers vanished before completion. A caller that never settles its provider can delay final provider cleanup; the API must state this limit.

On JVM/native, run FFmpeg on the runtime's owned lane and rendezvous each synchronous C callback with a provider coroutine on a separate available execution context. The callback thread may wait, while the caller is suspended; it must not block the same dispatcher needed by the provider. Cancellation wakes that rendezvous with an error and preserves the same buffer/source lifetime. Do not use this mechanism on web, where there is no thread to block. Async web opening must bind OpenInterrupt before entry. Its bound callback remains a quick non-reentrant flag/abort-signal notification because OpenInterrupt invokes it under its own lock; transport close runs later in the cleanup owner, never synchronously under that lock. The control flag access must be a validated ABI-safe memory/control operation, not a second C export invoked into a parked Asyncify runtime. This host path also needs executed ownership/cancellation tests before common support is claimed.

## Kotlin job and native-unwind ownership

The public suspending method belongs to the caller Job, but the native export completion belongs to a runtime-owned supervisor until it has unwound. Canceling an await must never discard that export's continuation. The runtime owns a cleanup supervisor whose lifetime ends only after runtime close completes; the operation owns a provider child Job and a completion record separate from the caller's cancellable continuation. No global application scope is introduced.

The operation states are `Waiting`, `Entered`, `Stopping`, `Unwound`, and `Disposed`. Admission checks cancellation before entering C. An operation canceled in `Waiting` releases its admission claim without starting C; an owned input passed to a canceled open is still closed, matching ownership transfer at invocation. An entered demux operation canceled by its caller, its owning source.interrupt/source.close, or runtime.close moves once to `Stopping`. Requests targeting another source do not stop it. That transition marks the source terminal, invalidates new copy permission, sets the native interrupt flag without entering a second C export, cancels the provider child Job, and starts transport close in the cleanup supervisor. The suspended import receives the specified negative error result and C continues through its error cleanup. Only after the export settles may cleanup invoke native close or release its scratch/native structures.

Provider calls run in the provider child Job, with the inherited runtime ancestry marker. Their adapter catches CancellationException and other failures at the import boundary; it records the original cause and resolves the import with an error code instead of leaking a rejection through C. A private operation-abort signal can resolve that import even if the provider has not yet cooperated; in that case its result/ByteArray lease remains retained and invalid, and a late non-null opener result is closed in the cleanup supervisor. Native unwind and provider settlement are tracked separately. The active operation's cleanup waits for both before releasing admission or claiming provider cleanup complete. This prevents repeated cancellation from accumulating abandoned read buffers. A provider that never settles can delay close and further use of that runtime, which is a documented cooperation requirement rather than a timeout that frees still-owned storage.

There is one active native operation and at most one active data/opener callback per runtime. Cancellation cleanup may concurrently call the same provider's close while that callback settles, as required above. Track this separate, bounded cleanup work explicitly; the callback bound does not prohibit the transport-abort path. Initial read scratch is a single maximum 64 KiB chunk; larger C requests receive a legal short read. Waiting callers hold their own arguments and cancellable admission continuations, with no codec-memory allocation or provider request until admitted. The runtime does not materialize an unbounded request mailbox. Completed native packets/frames remain explicitly owned resources; this scratch bound is not a claim that total decoder or application-held media memory is 64 KiB.

`close()` changes admission state immediately, then awaits one shared cleanup completion under NonCancellable. A canceled close caller therefore still waits for its invoked close to finish; concurrent callers observe the same success or cleanup failure. Cleanup attempts every acquired child/source/resource even when one close throws, preserving the first failure and suppressing later distinct failures. Every provider has one close-completion record shared by transport abort, native child-close callbacks and parent teardown. They invoke its close once and await that same success or failure; an idempotent provider is not a substitute for exact library ownership. The runtime supervisor itself is canceled last. Methods that return a newly owned packet/frame/source must handle cancellation between native success and Kotlin delivery by disposing the undelivered value before propagating cancellation. The runtime completion record owns a successful native result until the caller accepts it or cleanup disposes it; cancellation of an await continuation must not erase that result. Flow ownership transfers at entry to the collector's emit call, even when emit throws, matching first()/take() and the existing retained-frame contract. Before that entry the producer or async buffer owns the handle. Do not infer failed delivery from an exception returned by emit. The existing direct Flow implementation deliberately avoids a flow-builder SafeCollector cancellation check between taking ownership and entering the collector. Preserve that exact distinction in async decode and buffer helpers: cancellation after receive/native success but before collector entry disposes the still-owned frame; first()/take() aborting after entry does not return its ownership to the producer. Test both boundaries separately.

After required cleanup, caller cancellation remains CancellationException, with relevant cleanup failures suppressed; provider errors during a non-canceled operation retain their original cause inside the existing FFmpegException model. A source closed by another caller reports a stable closed/interrupted failure for an in-flight operation and refuses later operations. Cancellation of a queued operation is not a terminal source event. Cancellation of an already-entered demux operation is terminal even if C happened to finish successfully before its cancellation callback was delivered; this avoids a nondeterministic promise of recovery. Cancellation of an entered frame/decoder-only operation waits for its synchronous work to finish and disposes an undelivered result, without terminalizing an unrelated source.

These are implementation requirements, not conclusions from the tiny probe. Production tests must use kotlinx.coroutines Job cancellation, including cancellation before admission, during root read, nested open/read/size, after native success before delivery, repeated cancellation, concurrent close and a late returned child. They must verify no source leak, no stale write, no extra request after cancellation, and complete unwind before native release. A deliberate mutation that abandons a canceled export await must fail those tests.

## Required cancellation, seek and playback experiments

The conservative source-terminal rule protects general input ownership. It does not finish
[KitePlayer #546](https://github.com/yuroyami/KitePlayer/issues/546). Completing that integration
requires the experiments and implementation below, with the source bytes, runtime artifacts,
instrumentation, control budgets and results recorded before its behavior is claimed. HTTP deadlines
and presentation policy belong in the provider/player; reusable FFmpeg state, if established,
belongs in this general-purpose library.

### Establish a real FFmpeg baseline and cancellation matrix

Use the shipped pinned FFmpeg and each explicit async artifact, including Asyncify-only selection on
a JSPI-capable engine. Exercise both browser page and Worker environments and the qualified host
backends. Build finite MP4/Matroska/MPEG-TS and HLS/DASH fixtures with known packet and decoded
audio/video/subtitle timelines. Include B-frames, sparse keyframes, independent audio, subtitle
children, HLS key rotation/encryption, live playlist refresh, changed initialization data and a
sliding live window. Keep request and resource-close logs keyed by operation/source generation.

The provider must expose deterministic barriers at root and child open/read/seek/size/tag/close,
not rely on a scheduler delay to guess when cancellation occurred. At each barrier, cancel before
entry, during a partial read, after bytes return but before copying, and after native success before
delivery. Include native error recovery within one operation, caller Job cancellation, explicit
interrupt, concurrent source/runtime close and a deliberately late completion. Assert exact native
and provider disposal, zero stale writes, no post-cancel request, and the original failure cause.
Repeat interrupted child-open/segment-refresh cycles to expose retained allocations and stale
registrations. Measure native unwind separately from provider settlement.

Hold a real live HLS playlist unchanged long enough to enter its internal reload wait. Observe event-loop heartbeats while that exact timer import is parked, then close or cancel and prove prompt native unwind, complete child cleanup and no later reload. Use an independent outer watchdog so an event-loop stall cannot make the test wait forever. Execute this on both async artifacts in browser page and Worker as well as the direct-C harness. A separate mutation that removes only the suspending sleep binding must fail the heartbeat or cancellation oracle; delaying provider reads is not a substitute for this timer-path test.

### Determine whether a reusable demux cancellation boundary exists

Run a private experimental operation-scoped abort path before considering a reusable public
contract. Do not clear or weaken the existing one-way `OpenInterrupt` or the accepted source-terminal
caller-cancellation rule. After an aborted read unwinds, compare all required recovery steps against
a fresh-open oracle: AVIO error/EOF/buffer state, demux parser and child state, packet queues,
selected streams, decoder flush, checked seek, first complete post-seek video frame, audio sample
timeline and subtitles. Test backward/forward/post-EOF seeks, repeated cancel/seek cycles, cancelled
probe/open, cancellation while opening a key or child playlist, and targets at or outside a live
window. Returning one successful packet is not evidence of coherent recovery.

Do not assume EAGAIN or a cleared error flag makes an interrupted demuxer reusable. If actual
supported paths establish a safe operation boundary with a mechanically enforceable capability,
specify and validate that separate public recovery operation before adopting it. Narrow successful
demuxer examples must not weaken the general terminal guarantee. If that proof is absent, implement
the bounded reopen route below; this experiment is a decision gate with an executable fallback,
not a reason to leave playback controls unfinished.

### Implement and measure bounded reopen when needed

The default integration route retains terminal cancellation, aborts the old provider, awaits native
unwind and its source-close record, reopens through the same source/provider policy, reapplies stream
selection, seeks to the requested content-relative point and decodes to the presentation target.
Reconnect at a valid live position when the old target has expired, using the player's explicit
live-window behavior rather than silently presenting stale media. Preserve allowed redirects,
credential policy, track identity, subtitle selection and corrected source metadata on reopen.

The provider has explicit finite deadlines for response headers, stalled reads and cleanup, plus an
abortable transport whose pending callback actually settles. A deadline failure is an error, never
EOF. Ignored Range, a changed resource and stale cache validators must be tested rather than
silently downloading without a bound. Deadlines can stop awaiting external transport only when the
adapter prevents late writes and has ended its right to the request buffer; they cannot free storage
still owned by arbitrary provider code. The same runtime is reused only after that cleanup finishes.
Keep at most one retiring generation and one replacement generation if measurements justify
overlap; do not allocate another runtime on every superseding seek. A noncooperative provider
produces an explicit failure, not a growing list of abandoned runtimes.

Instrument command acceptance, provider abort, native unwind, cleanup completion, reopen/probe,
seek landing, first correct audio/video/subtitle delivery and old-generation rejection. Preserve
the current player's seek-completion meaning and transport legality. Repeated seeks coalesce to
the latest intended target using the player's existing generation rules. Stop/close must settle the
same cleanup, and stale queued frames/events must never revive playback. Establish the controlled
fixture deadline and end-to-end control budget before measuring; record median, high percentiles,
maximum and failures without loosening the budget after a miss. Uncontrolled network results are
reported separately from deterministic fixture behavior.

### Measure the extraction and buffering strategy

Before admitting a potentially parked read, extract the decoded audio/video data needed for queued
presentation into owned bounded buffers. Compare this policy with the current read/decode schedule
using real frame sizes, pixel formats and subtitle delivery; measure extra copies, throughput,
buffer duration, high-water bytes and underruns. Eager metadata alone is not sufficient because
AsyncFrame copy/disposal still queues behind the runtime lane. Charge both extracted buffers and
retained codec handles, and close handles before they accumulate behind the next blocked read.
Never enter ordinary synchronous exports to bypass the lane.

Use a paused provider after buffered frames exist and prove those frames/audio samples continue to
present in order while controls remain serviceable. Then seek/stop while that provider is paused
and prove generation rejection, bounded cleanup and the selected recovery behavior. Include long
CPU decode sections: suspension of I/O does not itself preempt a native CPU call on a single event
loop. If the measured extraction schedule cannot meet the integration budget, change the general
runtime/backend architecture with a separate safety proof and design before claiming support;
simply raising buffers or tolerances is not acceptance.

### Completion evidence

The actual-player gate covers HLS and DASH A/V, subtitle changes, pause/resume, repeated seeks,
post-EOF seek, live refresh/window movement and stop/close on the qualified engines. Run separate
plain-artifact regression tests. Falsification includes restoring a synchronous import, abandoning
an export on cancellation, allowing a late buffer copy, skipping one cleanup owner, and permitting
an old generation to present after seek. Publish exact supported engines/artifacts and measured
control/memory results. The authored mechanism probe remains a prerequisite, not this evidence.

## Acceptance gates

1. Completed isolated mechanism gate: both tiny C mechanisms, real Kotlin/Wasm stdlib coroutine bridge, same-source synchronous-import negative control, native canaries, exception identity, queue order, and close/unwind ordering. Production kotlinx Job tests described above remain required before async API support is claimed.
2. Static C bridge plus an executed reachability matrix against actual pinned FFmpeg: root open/discovery/read/tags/size/seek, checked-seek read-ahead, HLS variants/segments/keys and crypto, live refresh, subtitle child input, pause/play, normal close and partial initialization. Include high virtual offsets, bounded reads, multiple simultaneous child leases, post-EOF seek and injected child-close failures during native cleanup. Delay each reachable import, including void cleanup and internal live-HLS timers, and prove exact close counts, unchanged canaries and complete unwind. Verify event-loop progress and cancellation while the real reload timer is parked. Restore a synchronous provider import, omit the suspending sleep binding and abandon a canceled export await as separate falsification mutations.
3. Full relevant web contracts on plain and async artifacts; binary compatibility checks for old synchronous consumers; real JVM/native provider rendezvous tests. Include duplicate module/provider adoption, inherited A -> B -> A reentry, closing B while A is suspended, successful result canceled before delivery, first()/take()/buffered async frame ownership, descriptor materialization counts and one-shot snapshot/change delivery.
4. Browser Fetch range/stream oracle, explicit redirect policy, bounded application buffers, abort/late reads and ignored-Range behavior. Then real HLS/DASH A/V, seek/live refresh and control responsiveness across the selected browser engines and both mechanisms.

The tiny probe establishes a suspension mechanism only. It does not qualify FFmpeg's entire indirect call graph, async media API completeness, network security, playback latency, or browser-version support.

Keep the additions in the existing kiteffmpeg module and io.github.yuroyami.kiteffmpeg package; no new dependency or Kotlin module split is required. All new common declarations follow the existing KiteFFmpegLowLevelApi opt-in boundary where applicable and native @Throws conventions, including CancellationException on suspending APIs. Unsupported targets, including the current unsupported JavaScript backend, fail at runtime creation with the existing typed unsupported error. No unsupported actual may return a partially initialized owner. Keep the pinned Kotlin 2.4.20/coroutines 1.11.0 toolchain. Generate additive ABI declarations with this repository's apiDump and run apiCheck with -Pkiteffmpeg.requireAllTargets=true, retaining every existing symbol across all thirteen targets and all eleven native trees. Do not hand-edit dumps or substitute the player repository's ABI task names. Old separately compiled synchronous JVM and native consumers, new Swift cancellation/error exports, exact async C binding signatures and both generated-binding mirrors are acceptance gates. The owned async family is additive source surface, not permission to change existing defaults, exported signatures or artifact selection.

## Primary mechanism references

- [Emscripten asynchronous code support](https://emscripten.org/docs/porting/asyncify.html), including Asyncify indirect-call reachability and its restriction on overlapping async operations.
- [WebAssembly JSPI boundaries](https://github.com/WebAssembly/js-promise-integration/blob/main/proposals/js-promise-integration/Overview.md), for promising exports and suspending imports.
- [Kotlin/Wasm JavaScript interop](https://kotlinlang.org/docs/wasm-js-interop.html), for the explicit Promise boundary.

The mechanism references explain the execution model; the qualified engine list above comes only from the authored probe results and is not a browser support claim for this unimplemented FFmpeg API.
